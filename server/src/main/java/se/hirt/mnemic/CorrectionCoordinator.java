/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic;

import se.hirt.mnemic.knowledge.Bounds;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.EventService;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.Rule;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Coordinates user-initiated corrections across facts, entities, events, predicates, groups, and vocabulary types:
 * validates requested changes, writes replayable correction observations where required, applies mutations atomically
 * with registry rollback recovery, triggers derivations when affected, and backfills missing embeddings.
 */
final class CorrectionCoordinator {

	private static final Set<String> FACT_PROPERTIES = Set.of("subject", "predicate", "object", "qualifier", "scope",
			"valid_time", "ended", "caller_confidence", "wrong", "redundant");

	private final Database db;
	private final ObservationService observations;
	private final Knowledge knowledge;
	private final Clock clock;
	private final SemanticIndexer indexer;
	private final DerivationGate derivationGate;

	CorrectionCoordinator(Database db, ObservationService observations, Knowledge knowledge, Clock clock,
			SemanticIndexer indexer, DerivationGate derivationGate) {
		this.db = db;
		this.observations = observations;
		this.knowledge = knowledge;
		this.clock = clock;
		this.indexer = indexer;
		this.derivationGate = derivationGate;
	}

	/**
	 * Corrects an entity: its name, its type, the aliases it keeps ({@code aliases} is the list to keep; the entity's
	 * own name and the owner's identity always stay), or {@code merge_into}, which folds it into the entity named
	 * (facts, events, and aliases move; the id keeps resolving to the survivor). The user says what a thing is called;
	 * a wrong fuzzy match earlier is undone by dropping the alias it left.
	 */
	Map<String, Object> correctEntity(long entityId, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"aliases\": [\"Hooli\", \"Hooli Inc\"]} or {\"name\": \"Hooli AG\"} or "
				+ "{\"type\": \"organization\"} or {\"merge_into\": \"ent-7\"}");
		return runCorrection(() -> {
			var before = knowledge.entities().get(entityId)
					.orElseThrow(() -> MnemicException.notFound("No entity ent-" + entityId));
			List<String> aliasesBefore = knowledge.entities().aliases(before.id());
			var rest = new LinkedHashMap<String, Object>(replacement);
			Object mergeInto = rest.remove("merge_into");
			Map<String, Object> merge = null;
			long id = before.id();
			if (mergeInto != null) {
				Entity into = knowledge.entities().byRef(String.valueOf(mergeInto)).orElseThrow(() -> MnemicException
						.notFound("No entity '" + mergeInto + "' to merge " + before.ref() + " into."));
				if (into.id() == before.id()) {
					throw MnemicException.invalidArgument(before.ref() + " cannot be merged into itself.");
				}
				if (before.id() == knowledge.entities().owner().id()) {
					throw MnemicException.invalidArgument("The owner is never merged away; merge the other entity into "
							+ before.ref() + " instead.");
				}
				merge = knowledge.entities().merge(before.id(), into.id(), null,
						"correct: " + (reason == null || reason.isBlank() ? "the same thing twice" : reason));
				id = into.id();
			}
			var after = rest.isEmpty() ? knowledge.entities().get(id).orElseThrow()
					: knowledge.entities().correct(id, rest);
			int rerendered = 0;
			if (!after.name().equals(before.name()) && merge == null) {
				rerendered = knowledge.renderer().rerenderMentioning(id);
			}
			List<Map<String, Object>> folded = List.of();
			if (merge != null) {
				// The facts that now say the same thing twice fold at once, not at the next consolidate (2026-09-28).
				folded = knowledge.consolidator().foldDuplicatesOf(id);
				knowledge.deriver().derive();
			}
			var out = new LinkedHashMap<String, Object>();
			out.put("entity", after.ref());
			out.put("before", entityState(before, aliasesBefore));
			out.put("after", entityState(after, knowledge.entities().aliases(id)));
			if (merge != null) {
				out.put("merged", merge);
			}
			if (!folded.isEmpty()) {
				out.put("folded", folded);
			}
			out.put("rerendered_facts", rerendered);
			out.put("reason", reason);
			return out;
		});
	}

	/**
	 * Corrects a fact without destroying history (EVALUATION.md D1): the reason becomes a correction observation, the
	 * replacement is derived from it, the original is marked {@code corrected} and linked. A replacement of
	 * {@code {"wrong": true}} retracts the fact instead (D7).
	 */
	Corrected correct(long factId, Map<String, Object> replacement, String reason) {
		String about = knowledge.facts().get(factId).map(f -> f.rendering()).orElse("f-" + factId);
		String why = reason == null || reason.isBlank() ? "" : ": " + reason;
		if (replacement != null) {
			for (String key : replacement.keySet()) {
				if (!FACT_PROPERTIES.contains(key)) {
					throw MnemicException.invalidArgument("Unknown fact property '" + key + "'; correctable: subject, "
							+ "predicate, object, qualifier, scope, valid_time, ended, caller_confidence; or "
							+ "{\"wrong\": true} to withdraw, {\"redundant\": true} to retire a fact a derivation "
							+ "covers.");
				}
			}
		}
		Corrected result;
		if (replacement != null && Boolean.TRUE.equals(replacement.get("redundant"))) {
			result = runCorrection(() -> {
				Observation record = correctionRecord("Retirement of " + about + why, factId);
				Corrected retired = knowledge.factService().retire(factId, reason, record);
				if (derivationGate.needsDerivation(retired)) {
					knowledge.deriver().derive();
				}
				return retired;
			});
		} else if (replacement != null && Boolean.TRUE.equals(replacement.get("wrong"))) {
			result = runCorrection(() -> {
				Observation record = correctionRecord("Retraction of " + about + why, factId);
				Corrected retracted = knowledge.factService().retract(factId, reason, record);
				if (derivationGate.needsDerivation(retracted)) {
					knowledge.deriver().derive();
				}
				return retracted;
			});
		} else {
			if (replacement == null || replacement.isEmpty()) {
				throw MnemicException.invalidArgument("'changes' must name what changes, e.g. {\"object\": "
						+ "\"Schübelbach\"} or {\"valid_time\": {\"start\": \"2014\"}} or {\"ended\": true}.");
			}
			result = runCorrection(() -> {
				Observation record = correctionRecord("Correction of " + about + why + " → " + Json.write(replacement),
						factId);
				Corrected corrected = knowledge.factService().correct(factId, replacement, reason, record);
				if (derivationGate.needsDerivation(corrected)) {
					knowledge.deriver().derive();
				}
				return corrected;
			});
		}
		indexer.embedMissing(50);
		return result;
	}

	/**
	 * Moves an event to another date ({@code correct(evt-3, {valid_time: {start: "2027-06-19"}})}): the facts its
	 * effects opened or closed move with it. The reason becomes a correction observation whose reading names the event
	 * by key and the new date, so a rebuild moves it again. An assistant tried to move a wedding through its event type
	 * and got a refusal about predicates (2026-09-23).
	 */
	Map<String, Object> correctEvent(long eventId, Map<String, Object> replacement, String reason) {
		Event ev = knowledge.events().get(eventId)
				.orElseThrow(() -> MnemicException.notFound("No event evt-" + eventId));
		if (replacement != null && !replacement.isEmpty()
				&& Set.of("type", "detail", "roles").containsAll(replacement.keySet())) {
			Object type = replacement.get("type");
			Object detail = replacement.get("detail");
			Object roles = replacement.get("roles");
			if (roles != null && !(roles instanceof Map<?, ?>)) {
				throw MnemicException.invalidArgument("'roles' maps a participant to its role: {\"roles\": "
						+ "{\"Calle Wilund\": \"with\"}}; an empty role clears one.");
			}
			return retypeEvent(ev, type == null ? ev.type() : String.valueOf(type),
					detail == null ? null : String.valueOf(detail), knowledge.factService().rolesByName(ev, roles),
					reason);
		}
		if (replacement == null || !(replacement.get("valid_time") instanceof Map<?, ?> vt)
				|| !replacement.keySet().equals(Set.of("valid_time"))) {
			throw MnemicException.invalidArgument("An event is corrected by its date, {\"valid_time\": {\"start\": "
					+ "\"2027-06-19\"}} (a year, a month, or a day; 'end' too for a span), or by its type and detail, "
					+ "{\"type\": \"graduated\", \"detail\": \"M.Sc. in Computer Science\"} (a type is a verb or two "
					+ "words; the detail is what the type and participants leave unsaid), one at a time. To change who "
					+ "took part, re-read its observation with remember(observation_id, proposal).");
		}
		var warnings = new ArrayList<String>();
		Instant now = clock.instant();
		Bounds b = Bounds.of(
				new Proposal.ValidTime(vtText(vt.get("start")), vtText(vt.get("end")), vtText(vt.get("precision"))),
				now, warnings);
		if (b.start() == null && b.end() == null) {
			throw MnemicException
					.invalidArgument("'valid_time' names no date: give 'start' (2027-06-19, 2027-06, or 2027).");
		}
		if (Objects.equals(b.start(), ev.validStart()) && Objects.equals(b.end(), ev.validEnd())) {
			throw MnemicException.invalidArgument("The correction changes nothing about " + ev.ref() + ": it is dated "
					+ ev.validStart() + " already.");
		}
		String why = reason == null || reason.isBlank() ? "" : ": " + reason;
		List<String> names = ev.participants().stream().map(id -> knowledge.entities().nameOf(id)).toList();
		var reading = new LinkedHashMap<String, Object>();
		reading.put("redates", Map.of("type", ev.type(), "participants", names, "valid_start",
				ev.validStart() == null ? "" : ev.validStart()));
		reading.put("valid_time", vt);
		if (reason != null && !reason.isBlank()) {
			reading.put("reason", reason);
		}
		return runCorrection(() -> {
			Remembered r = observations.remember("Re-dating of " + ev.rendering() + why + " → " + Json.write(vt),
					new Source("correction", ev.ref(), null, null, null), now, Json.write(reading), null, null);
			Observation record = observations.get(r.observationId()).orElseThrow();
			EventService.Redated moved = knowledge.factService().redate(eventId, b, record);
			Set<String> relevant = knowledge.deriver().relevantPredicates();
			if (!moved.opened().isEmpty() || !moved.closed().isEmpty()
					|| derivationGate.eventAffectsDerivation(ev.type(), relevant)) {
				knowledge.deriver().derive();
			}
			var out = new LinkedHashMap<String, Object>();
			out.put("event", ev.ref());
			out.put("before",
					Map.of("valid_start", ev.validStart() == null ? "" : ev.validStart(), "rendering", moved.before()));
			out.put("after", Map.of("valid_start", b.start() == null ? "" : b.start(), "rendering", moved.after()));
			out.put("facts_moved",
					Stream.concat(moved.opened().stream(), moved.closed().stream()).map(id -> "f-" + id).toList());
			out.put("observation", record.ref());
			if (!warnings.isEmpty()) {
				out.put("warnings", warnings);
			}
			return out;
		});
	}

	/**
	 * Gives an event another type from a correction record, so that a rebuild does it again: the sentence an early
	 * reading put where the type goes becomes a type of a word or two in one call, where a re-read of the whole
	 * observation was needed before (2026-09-27).
	 */
	private Map<String, Object> retypeEvent(
		Event ev, String given, String detail, Map<Long, String> roles, String reason) {
		String type = EventTypeRegistry.key(given);
		boolean retyping = !type.equals(ev.type());
		if (retyping && (type.isBlank() || !EventTypeRegistry.typeLike(given))) {
			throw MnemicException.invalidArgument("An event type is a verb or two words (graduated, "
					+ "purchased_property); '" + given + "' reads as a description. Put it in 'detail' instead.");
		}
		String newDetail = detail == null || detail.isBlank() ? null : detail.strip();
		// Only the roles that change: a role said again as it stands is no change.
		var changedRoles = new LinkedHashMap<Long, String>();
		for (Map.Entry<Long, String> r : roles.entrySet()) {
			String now = r.getValue() == null || r.getValue().isBlank() ? null : r.getValue().strip();
			if (!Objects.equals(now, ev.roleOf(r.getKey()))) {
				changedRoles.put(r.getKey(), now == null ? "" : now);
			}
		}
		if (!retyping && (newDetail == null || newDetail.equals(ev.detail())) && changedRoles.isEmpty()) {
			throw MnemicException.invalidArgument("The correction changes nothing about " + ev.ref() + ": its type is '"
					+ type + "' already" + (newDetail == null ? "" : ", and so is its detail")
					+ (roles.isEmpty() ? "." : ", and so are the roles given."));
		}
		String why = reason == null || reason.isBlank() ? "" : ": " + reason;
		Instant now = clock.instant();
		List<String> names = ev.participants().stream().map(id -> knowledge.entities().nameOf(id)).toList();
		var reading = new LinkedHashMap<String, Object>();
		reading.put("retypes", Map.of("type", ev.type(), "participants", names, "valid_start",
				ev.validStart() == null ? "" : ev.validStart()));
		reading.put("type", type);
		if (newDetail != null) {
			reading.put("detail", newDetail);
		}
		var roleNames = new LinkedHashMap<String, String>();
		changedRoles.forEach((id, role) -> roleNames.put(knowledge.entities().nameOf(id), role));
		if (!roleNames.isEmpty()) {
			reading.put("roles", roleNames);
		}
		if (reason != null && !reason.isBlank()) {
			reading.put("reason", reason);
		}
		var said = new ArrayList<String>();
		if (retyping) {
			said.add(type);
		}
		if (newDetail != null) {
			said.add("detail \"" + newDetail + "\"");
		}
		roleNames.forEach(
				(who, role) -> said.add(role.isEmpty() ? who + " without a role" : who + " as \"" + role + "\""));
		String change = String.join(", ", said);
		return runCorrection(() -> {
			Remembered r = observations.remember(
					(retyping ? "Retyping of " : "Correction of ") + ev.rendering() + why + " → " + change,
					new Source("correction", ev.ref(), null, null, null), now, Json.write(reading), null, null);
			Observation record = observations.get(r.observationId()).orElseThrow();
			Map<String, Object> changed = knowledge.factService().retype(ev.id(), type, newDetail, changedRoles,
					record);
			Set<String> relevant = knowledge.deriver().relevantPredicates();
			if (derivationGate.eventAffectsDerivation(ev.type(), relevant)
					|| derivationGate.eventAffectsDerivation(type, relevant)) {
				knowledge.deriver().derive();
			}
			var out = new LinkedHashMap<String, Object>();
			out.put("event", ev.ref());
			out.putAll(changed);
			out.put("observation", record.ref());
			return out;
		});
	}

	/**
	 * Corrects a predicate's definition (EVALUATION.md J5): the change is logged with its reason and every fact under
	 * the predicate is re-rendered.
	 */
	Map<String, Object> correctPredicate(String name, Map<String, Object> replacement, String reason) {
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'changes' must name what changes, e.g. {\"render\": "
					+ "\"{object} is {subject}'s {qualifier|parent}\"} or {\"lexicon\": [\"mother\", \"father\"]}.");
		}
		var before = knowledge.predicates().get(name)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + name));
		if (replacement.get("merge_into") != null) {
			return mergePredicate(before, String.valueOf(replacement.get("merge_into")), replacement, reason);
		}
		var after = knowledge.predicates().update(name, replacement, reason);
		int rerendered = knowledge.renderer().rerender(name);
		var out = new LinkedHashMap<String, Object>();
		out.put("predicate", name);
		out.put("before", Map.of("render", before.render(), "lexicon", before.lexicon(), "qualifiers",
				before.qualifiers(), "functional", before.functional()));
		out.put("after", Map.of("render", after.render(), "lexicon", after.lexicon(), "qualifiers", after.qualifiers(),
				"functional", after.functional()));
		out.put("rerendered_facts", rerendered);
		// A predicate that just became functional has facts stored as if it were not: they are checked now.
		out.put("rechecked_conflicts",
				!before.functional() && after.functional() ? knowledge.factService().recheckFunctional(name) : 0);
		out.put("changes", knowledge.predicates().changes(name));
		if (replacement.containsKey("defined_as")) {
			out.put("defined_as", knowledge.predicates().rulesOf(name).stream().map(Rule::toMap).toList());
		}
		if (replacement.containsKey("implies")) {
			out.put("implies", knowledge.predicates().impliesOf(name));
		}
		// The derivations rest on the rules, the implications, and whether the relation outlives a death.
		if (replacement.containsKey("defined_as") || replacement.containsKey("implies")
				|| replacement.containsKey("lasting")) {
			out.put("derived", knowledge.deriver().derive().toMap());
		}
		return out;
	}

	/** Folds one predicate into another: {@code correct(pred:coaches, {merge_into: "mentors"})}. */
	private Map<String, Object> mergePredicate(
		Predicate from, String into, Map<String, Object> replacement, String reason) {
		if (replacement.size() > 1) {
			throw MnemicException.invalidArgument("'merge_into' stands alone; correct the target afterwards.");
		}
		var target = knowledge.predicates().get(into)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + into));
		int moved = knowledge.predicates().merge(from.name(), target.name(), reason);
		knowledge.eventTypes().renamePredicate(from.name(), target.name(), reason);
		knowledge.renderer().rerender(target.name());
		var out = new LinkedHashMap<String, Object>();
		out.put("predicate", from.name());
		out.put("merged_into", target.name());
		out.put("merged_facts", moved);
		out.put("aliases", knowledge.predicates().get(target.name()).orElseThrow().aliases());
		out.put("changes", knowledge.predicates().changes(target.name()));
		return out;
	}

	/** Corrects an event type's definition; the change is logged with its reason. */
	Map<String, Object> correctEventType(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"closes\": [\"works_at\"]} or {\"lexicon\": [\"quit\", \"resigned\"]}");
		var before = knowledge.eventTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No event type " + name));
		if (replacement.containsKey("remove")) {
			if (replacement.size() > 1 || !Boolean.TRUE.equals(replacement.get("remove"))) {
				throw MnemicException.invalidArgument("{\"remove\": true} stands alone.");
			}
			knowledge.eventTypes().remove(before.name(), reason);
			var out = new LinkedHashMap<String, Object>();
			out.put("event_type", before.name());
			out.put("removed", true);
			out.put("before", eventTypeMap(before));
			return out;
		}
		var after = knowledge.eventTypes().update(name, replacement, reason,
				p -> knowledge.predicates().get(p).isPresent());
		int rerendered = knowledge.events().rerender(after.name());
		var out = new LinkedHashMap<String, Object>();
		out.put("event_type", after.name());
		out.put("before", eventTypeMap(before));
		out.put("after", eventTypeMap(after));
		out.put("rerendered_events", rerendered);
		out.put("changes", knowledge.eventTypes().changes(after.name()));
		return out;
	}

	/** Corrects an entity type's definition; the change is logged with its reason. */
	Map<String, Object> correctEntityType(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"parent\": \"place\"} or {\"type_words\": [\"kanton\", \"canton\"]}");
		var before = knowledge.entityTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No entity type " + name));
		if (replacement.containsKey("remove")) {
			if (replacement.size() > 1 || !Boolean.TRUE.equals(replacement.get("remove"))) {
				throw MnemicException.invalidArgument("{\"remove\": true} stands alone.");
			}
			List<String> naming = knowledge.predicates().all().stream()
					.filter(p -> p.domain().contains(before.name()) || p.range().contains(before.name()))
					.map(Predicate::name).toList();
			if (!naming.isEmpty()) {
				throw MnemicException.invalidArgument(
						"'" + before.name() + "' is in the domain or range of " + naming + "; correct them first.");
			}
			knowledge.entityTypes().remove(before.name(), reason);
			var out = new LinkedHashMap<String, Object>();
			out.put("entity_type", before.name());
			out.put("removed", true);
			out.put("before", typeMap(before));
			return out;
		}
		var after = knowledge.entityTypes().update(name, replacement, reason);
		var out = new LinkedHashMap<String, Object>();
		out.put("entity_type", after.name());
		out.put("before", typeMap(before));
		out.put("after", typeMap(after));
		if (replacement.containsKey("kinds")) {
			out.put("placed", knowledge.factService().placeNamedKinds(after.name()));
		}
		out.put("changes", knowledge.entityTypes().changes(after.name()));
		return out;
	}

	/**
	 * Corrects a predicate group ({@code correct(group:family, {lexicon: [...]})}): its description, cue words, words
	 * in another language, or the groups it belongs to. The change is logged with its reason.
	 */
	Map<String, Object> correctGroup(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"lexicon\": [\"family\", \"relatives\"]} or {\"groups\": [\"family\"]}");
		var before = knowledge.predicates().group(name).orElseThrow(() -> MnemicException.notFound("No group " + name));
		var after = knowledge.predicates().updateGroup(before.name(), replacement, reason);
		var out = new LinkedHashMap<String, Object>();
		out.put("group", after.name());
		out.put("before", before.toMap());
		out.put("after", after.toMap());
		out.put("members", knowledge.predicates().membersOf(after.name()).stream().map(Predicate::name).toList());
		out.put("changes", knowledge.predicates().groupChanges(after.name()));
		return out;
	}

	private <T> T runCorrection(Supplier<T> work) {
		return Engine.runAtomic(db, knowledge, work);
	}

	private Observation correctionRecord(String text, long factId) {
		Remembered r = observations.remember(text, new Source("correction", "f-" + factId, null, null, null),
				clock.instant(), "{}", null, null);
		return observations.get(r.observationId()).orElseThrow();
	}

	private static Map<String, Object> entityState(Entity e, List<String> aliases) {
		var m = new LinkedHashMap<String, Object>();
		m.put("name", e.name());
		m.put("type", e.type());
		m.put("aliases", aliases);
		return m;
	}

	private static Map<String, Object> eventTypeMap(EventTypeRegistry.EventType t) {
		var m = new LinkedHashMap<String, Object>();
		m.put("opens", t.opens());
		m.put("closes", t.closes());
		m.put("supersedes", t.supersedes());
		m.put("ends_entity", t.endsEntity());
		m.put("lexicon", t.lexicon());
		m.put("render", t.render());
		return m;
	}

	private static Map<String, Object> typeMap(EntityTypeRegistry.EntityType t) {
		var m = new LinkedHashMap<String, Object>();
		m.put("parent", t.parent());
		m.put("synonyms", t.synonyms());
		m.put("type_words", t.typeWords());
		m.put("kinds", t.kinds());
		return m;
	}

	private static void requireReplacement(Map<String, Object> replacement, String example) {
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'changes' must name what changes, e.g. " + example + ".");
		}
	}

	private static String vtText(Object o) {
		return o == null ? null : String.valueOf(o);
	}
}
