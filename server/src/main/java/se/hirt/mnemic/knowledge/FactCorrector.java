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
package se.hirt.mnemic.knowledge;

import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.ValidTime;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fact and event corrections, retractions, retirements, redating, retyping, and correction-log replay and backfill
 * (EVALUATION.md D1, D7). Stateless; delegates fact insertion during replacement/replay to {@link ProposalApplier}.
 */
final class FactCorrector {

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EventService events;
	private final FactQueries queries;
	private final FactRenderer renderer;
	private final Deriver deriver;
	private final ProposalApplier applier;

	FactCorrector(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EventService events, FactQueries queries, FactRenderer renderer, Deriver deriver, ProposalApplier applier) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.events = events;
		this.queries = queries;
		this.renderer = renderer;
		this.deriver = deriver;
		this.applier = applier;
	}

	/**
	 * Replaces a fact without destroying history (EVALUATION.md D1): the original is marked {@code corrected}, the
	 * replacement is derived from the correction observation through the same path as any fact, and the two are linked.
	 */
	Corrected correct(long factId, Map<String, Object> replacement, String reason, Observation correction) {
		Fact original = correctable(factId);
		FactRef ref = readingOf(original, replacement);
		boolean toValue = original.objectId() != null && replacement.containsKey("object")
				&& predicates.get(ref.predicate()).map(p -> p.literalRange() && !p.mixedRange()).orElse(false);
		if (!toValue && sameStatement(ref, readingOf(original, Map.of()))) {
			String swap = original.objectId() == null ? ""
					: " To switch subject and object around, give both: {\"subject\": \""
							+ entities.nameOf(original.objectId()) + "\", \"object\": \""
							+ entities.nameOf(original.subjectId()) + "\"}.";
			throw MnemicException.invalidArgument("The correction changes nothing about " + original.ref()
					+ ": every key names the value on record. Name a key with a different value, {\"wrong\": true}, or "
					+ "{\"redundant\": true}." + swap);
		}
		return correctWith(original, ref, reason, correction);
	}

	/**
	 * The fact as a correction reading states it: the original's values, the replacement's over them. With an empty
	 * replacement, the fact as it stands.
	 */
	FactRef readingOf(Fact original, Map<String, Object> replacement) {
		String subject = selfOrName(str(replacement, "subject",
				original.subjectId() == entities.owner().id() ? "self" : entities.nameOf(original.subjectId())));
		String object = original.objectId() != null
				? selfOrName(str(replacement, "object", entities.nameOf(original.objectId())))
				: str(replacement, "object", original.objectText());
		String qualifier = str(replacement, "qualifier", original.qualifier());
		String scope = str(replacement, "scope",
				original.scopeId() == null ? null : entities.nameOf(original.scopeId()));
		String predicate = str(replacement, "predicate", original.predicate());
		if (predicate != null && !predicate.equals(original.predicate())) {
			predicate = predicates.get(predicate).map(Predicate::name).orElseThrow(() -> MnemicException
					.invalidArgument("No predicate '" + replacement.get("predicate") + "' to move the fact to."));
		}
		ValidTime vt = replacement.get("valid_time") instanceof Map<?, ?> m
				? new ValidTime(str(m, "start", null), str(m, "end", null), str(m, "precision", null))
				: (original.validStart() == null && original.validEnd() == null ? null
						: new ValidTime(original.validStart(), original.validEnd(),
								original.validStartPrecision() != null ? original.validStartPrecision()
										: original.validEndPrecision()));
		Boolean ended = replacement.containsKey("ended") ? Boolean.TRUE.equals(replacement.get("ended"))
				: original.ended();
		Double callerConfidence = replacement.containsKey("caller_confidence")
				? (replacement.get("caller_confidence") == null ? null
						: ((Number) replacement.get("caller_confidence")).doubleValue())
				: original.callerConfidence();
		return new FactRef(subject, predicate, object, qualifier, scope, vt, ended, List.of(),
				new Proposal.Derivation("explicit"), callerConfidence,
				"negated".equals(original.mode()) ? Boolean.TRUE : null,
				"only".equals(original.mode()) ? Boolean.TRUE : null);
	}

	private String selfOrName(String name) {
		if (name == null || "self".equals(name)) {
			return name;
		}
		Entity owner = entities.owner();
		if (name.equalsIgnoreCase(owner.name())
				|| entities.aliases(owner.id()).stream().anyMatch(name::equalsIgnoreCase)) {
			return "self";
		}
		return name;
	}

	/** Whether two readings state the same fact: the same terms and bounds, whatever the precision noted. */
	static boolean sameStatement(FactRef a, FactRef b) {
		return Objects.equals(a.subject(), b.subject()) && Objects.equals(a.predicate(), b.predicate())
				&& Objects.equals(a.object(), b.object()) && Objects.equals(a.qualifier(), b.qualifier())
				&& Objects.equals(a.scope(), b.scope())
				&& Objects.equals(a.validTime() == null ? null : a.validTime().start(),
						b.validTime() == null ? null : b.validTime().start())
				&& Objects.equals(a.validTime() == null ? null : a.validTime().end(),
						b.validTime() == null ? null : b.validTime().end())
				&& Objects.equals(a.ended(), b.ended()) && Objects.equals(a.callerConfidence(), b.callerConfidence())
				&& Objects.equals(a.negated(), b.negated()) && Objects.equals(a.only(), b.only());
	}

	private Fact correctable(long factId) {
		Fact original = queries.get(factId).orElseThrow(() -> MnemicException.notFound("No fact f-" + factId));
		if (!original.current() && !"superseded".equals(original.status())) {
			throw MnemicException.conflict("f-" + factId + " is " + original.status() + ", not current; correct "
					+ (original.supersededBy() != null ? "f-" + original.supersededBy() : "the current fact")
					+ " instead.", Map.of("status", original.status()));
		}
		return original;
	}

	/** The correction itself: the record's reading written, the original marked, the replacement derived and linked. */
	Corrected correctWith(Fact original, FactRef ref, String reason, Observation correction) {
		storeReading(correction, reading("corrects", original, reason, List.of(ref)));
		db.write(tx -> tx.update("UPDATE fact SET status = 'corrected' WHERE id = ?", original.id()));
		Set<Long> distinct = original.objectId() != null
				&& !Objects.equals(ref.object(), readingOf(original, Map.of()).object()) ? Set.of(original.objectId())
						: Set.of();
		Applied a = applier.apply(correction,
				new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref), List.of()), Map.of(),
				distinct);
		if (a.facts().isEmpty()) {
			db.write(tx -> tx.update("UPDATE fact SET status = 'current' WHERE id = ?", original.id()));
			String held = a.questions().isEmpty() ? ""
					: " The correction was not made, and its question was withdrawn with it: "
							+ a.questions().stream().map(q -> String.valueOf(q.get("message"))).toList()
							+ ". Name the entity you mean by its id (\"object\": \"ent-N\"), or rename the entity"
							+ " itself with correct(ent-N, {\"name\": ...}).";
			throw MnemicException
					.invalidArgument("The correction produced no fact: " + String.join("; ", a.warnings()) + held);
		}
		long newId = Long.parseLong(a.facts().getFirst().id().substring(2));
		db.write(tx -> {
			tx.update("UPDATE fact SET superseded_by = ? WHERE id = ?", newId, original.id());
			FactLedger.supersession(tx, original.id(), newId, "correction", reason, null, correction.id(), null);
			return null;
		});
		return new Corrected(queries.get(original.id()).orElseThrow(), queries.get(newId).orElseThrow(), "corrected");
	}

	/**
	 * Withdraws a fact that was never true (EVALUATION.md D7): marked corrected with no replacement, the reason
	 * recorded as a retraction, so it leaves recall and stays in history.
	 */
	Corrected retract(long factId, String reason, Observation correction) {
		Fact original = correctable(factId);
		storeReading(correction, reading("retracts", original, reason, List.of()));
		db.write(tx -> {
			tx.update("UPDATE fact SET status = 'corrected', superseded_by = NULL WHERE id = ?", factId);
			FactLedger.supersession(tx, factId, null, "retraction",
					reason == null || reason.isBlank() ? "user: never true" : reason, null, correction.id(), null);
			return null;
		});
		return new Corrected(queries.get(factId).orElseThrow(), null, "retracted");
	}

	/**
	 * Retires a stated fact a derivation now covers: it was true, and it still is, but the record derives it from the
	 * facts behind it, so the statement steps back as {@code superseded} by the derived fact (or by nothing named, when
	 * none covers it at the moment), with the reason on its history.
	 */
	Corrected retire(long factId, String reason, Observation correction) {
		Fact original = correctable(factId);
		Optional<Fact> cover = deriver.covering(original);
		storeReading(correction, reading("retires", original, reason, List.of()));
		db.write(tx -> {
			tx.update("UPDATE fact SET status = 'superseded', superseded_by = ? WHERE id = ?",
					cover.map(Fact::id).orElse(null), factId);
			FactLedger.supersession(tx, factId, cover.map(Fact::id).orElse(null), "supersession",
					"retired: " + (cover.isPresent() ? "covered by derived " + cover.get().ref() : "stated apart")
							+ (reason == null || reason.isBlank() ? "" : "; " + reason),
					null, correction.id(), null);
			return null;
		});
		return new Corrected(queries.get(factId).orElseThrow(), cover.orElse(null), "retired");
	}

	/**
	 * The reading of a correction record: the fact it is about, named by its key rather than its id, the reason, and
	 * the replacement it states (none for a retraction). What a rebuild needs to do it again.
	 */
	Map<String, Object> reading(String kind, Fact about, String reason, List<FactRef> facts) {
		var key = new LinkedHashMap<String, Object>();
		key.put("subject", about.subjectId() == entities.owner().id() ? "self" : entities.nameOf(about.subjectId()));
		key.put("predicate", about.predicate());
		key.put("object", about.objectId() != null ? entities.nameOf(about.objectId()) : about.objectText());
		key.put("qualifier", about.qualifier());
		key.put("scope", about.scopeId() == null ? null : entities.nameOf(about.scopeId()));
		key.put("mode", about.mode());
		var m = new LinkedHashMap<String, Object>();
		m.put(kind, key);
		m.put("reason", reason);
		m.put("facts", facts);
		return m;
	}

	private void storeReading(Observation record, Map<String, Object> reading) {
		db.write(tx -> tx.update("UPDATE observation SET proposal_json = ? WHERE id = ?", Json.write(reading),
				record.id()));
	}

	/**
	 * Moves an event and the facts that hang on its date, and refreshes their renderings.
	 */
	EventService.Redated redate(long eventId, Bounds b, Observation record) {
		EventService.Redated moved = events.redate(eventId, b, record.id());
		var preds = new LinkedHashSet<String>();
		for (Long id : moved.opened()) {
			queries.get(id).ifPresent(f -> preds.add(f.predicate()));
		}
		for (Long id : moved.closed()) {
			queries.get(id).ifPresent(f -> preds.add(f.predicate()));
		}
		for (String p : preds) {
			renderer.rerender(p);
		}
		return moved;
	}

	/** Gives an event another type, detail, and/or participant roles. */
	Map<String, Object> retype(long eventId, String type, String detail, Map<Long, String> roles, Observation record) {
		var out = new LinkedHashMap<String, Object>();
		if (eventTypes.get(type).isEmpty() && EventTypeRegistry.typeLike(type)) {
			EventType et = eventTypes.registerInferred(type, record.id());
			var inferred = new LinkedHashMap<String, Object>();
			inferred.put("render", et.render());
			inferred.put("lexicon", et.lexicon());
			inferred.put("effects", "none");
			inferred.put("correct", "correct(\"event:" + et.name()
					+ "\", {opens, closes, supersedes, ends_entity, render, lexicon, description})");
			var d = ProposalApplier.definition("event_type", et.name(), "inferred");
			d.put("inferred", inferred);
			out.put("definitions", List.of(d));
		}
		EventService.Retyped moved = events.retype(eventId, type, detail, roles, record.id());
		out.put("before", Map.of("type", moved.type(), "rendering", moved.before()));
		out.put("after", Map.of("type", type, "rendering", moved.after()));
		if (moved.detail() != null) {
			out.put("detail", moved.detail());
		}
		EventType et = eventTypes.get(type).orElse(null);
		if (et == null) {
			return out;
		}
		var ends = new ArrayList<>(et.closes());
		ends.addAll(et.supersedes());
		if (!ends.isEmpty() || et.endsEntity()) {
			out.put("note", "the type ends things (" + String.join(", ", ends) + (et.endsEntity() ? " ends_entity" : "")
					+ "): consolidate closes what it reaches");
		}
		if (!et.opens().isEmpty()) {
			out.put("note_opens", "the type opens " + et.opens() + ": state the fact it opened with remember");
		}
		return out;
	}

	/**
	 * Replays a correction observation's reading during a rebuild against whatever fact or event now matches its key.
	 */
	String replayCorrection(Observation record) {
		Map<String, Object> reading = Json.readMap(record.proposalJson());
		if (reading.get("redates") instanceof Map<?, ?> key) {
			return replayRedating(record, key, reading);
		}
		if (reading.get("retypes") instanceof Map<?, ?> key) {
			Optional<Event> ev = eventByKey(key);
			if (ev.isEmpty()) {
				return "unmatched";
			}
			retype(ev.get().id(), String.valueOf(reading.get("type")),
					reading.get("detail") == null ? null : String.valueOf(reading.get("detail")),
					rolesByName(ev.get(), reading.get("roles")), record);
			return "replayed";
		}
		boolean retraction = reading.get("retracts") instanceof Map<?, ?>;
		boolean retirement = reading.get("retires") instanceof Map<?, ?>;
		Object key = retraction ? reading.get("retracts")
				: retirement ? reading.get("retires") : reading.get("corrects");
		if (!(key instanceof Map<?, ?> k)) {
			return "unmatched";
		}
		String reason = reading.get("reason") == null ? null : String.valueOf(reading.get("reason"));
		Proposal p = Proposal.parse(record.proposalJson());
		Optional<Fact> target = findByKey(k);
		if (target.isEmpty()) {
			if (!p.facts().isEmpty()) {
				applier.apply(record,
						new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), p.facts(), List.of()),
						Map.of(), Set.of());
			}
			return "unmatched";
		}
		if (retraction) {
			retract(target.get().id(), reason, record);
			return "replayed";
		}
		if (retirement) {
			retire(target.get().id(), reason, record);
			return "replayed";
		}
		if (p.facts().isEmpty()) {
			return "unmatched";
		}
		if (sameStatement(p.facts().getFirst(), readingOf(target.get(), Map.of()))) {
			return "no_op";
		}
		correctWith(target.get(), p.facts().getFirst(), reason, record);
		return "replayed";
	}

	/**
	 * Roles given by participant name ({@code {"Calle Wilund": "with"}}) as roles by entity id.
	 */
	Map<Long, String> rolesByName(Event ev, Object given) {
		if (!(given instanceof Map<?, ?> m) || m.isEmpty()) {
			return Map.of();
		}
		var out = new LinkedHashMap<Long, String>();
		for (Map.Entry<?, ?> r : m.entrySet()) {
			String who = String.valueOf(r.getKey());
			Entity e = EntityService.SELF.contains(Names.norm(who)) ? entities.owner()
					: entities.byRef(who).orElseThrow(() -> MnemicException
							.invalidArgument("No entity '" + who + "' among the participants of " + ev.ref() + "."));
			if (!ev.participants().contains(e.id())) {
				throw MnemicException.invalidArgument(e.name() + " is not a participant of " + ev.ref() + "; its "
						+ "participants are " + ev.participants().stream().map(entities::nameOf).toList() + ".");
			}
			if (ev.participants().getFirst() == e.id()) {
				throw MnemicException.invalidArgument(e.name() + " is the first participant of " + ev.ref()
						+ ", who carries the sentence and takes no role.");
			}
			out.put(e.id(), r.getValue() == null ? "" : String.valueOf(r.getValue()));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private Optional<Event> eventByKey(Map<?, ?> key) {
		String type = String.valueOf(key.get("type"));
		var ids = new ArrayList<Long>();
		for (Object name : (List<Object>) key.get("participants")) {
			Optional<Entity> e = entities.byRef(String.valueOf(name));
			if (e.isEmpty()) {
				return Optional.empty();
			}
			ids.add(e.get().id());
		}
		String start = key.get("valid_start") == null || String.valueOf(key.get("valid_start")).isBlank() ? null
				: String.valueOf(key.get("valid_start"));
		return events.find(type, ids, start);
	}

	@SuppressWarnings("unchecked")
	private String replayRedating(Observation record, Map<?, ?> key, Map<String, Object> reading) {
		Optional<Event> ev = eventByKey(key);
		if (ev.isEmpty()) {
			return "unmatched";
		}
		Map<String, Object> vt = (Map<String, Object>) reading.get("valid_time");
		Bounds b = Bounds.of(new ValidTime(text(vt.get("start")), text(vt.get("end")), text(vt.get("precision"))),
				record.observedAt(), new ArrayList<>());
		redate(ev.get().id(), b, record);
		return "replayed";
	}

	private static String text(Object o) {
		return o == null ? null : String.valueOf(o);
	}

	/**
	 * Correction records from before readings were stored on them get one from their supersession row. Idempotent.
	 */
	int backfillCorrectionReadings() {
		int n = 0;
		for (Row r : db.read(tx -> tx.query("SELECT * FROM observation WHERE source_kind = 'correction' "
				+ "AND forgotten_at IS NULL AND (proposal_json IS NULL OR proposal_json = '{}') ORDER BY id"))) {
			Observation record = Observation.from(r);
			Optional<Row> change = db.read(tx -> tx.queryOne(
					"SELECT * FROM supersession WHERE observation_id = ? AND kind IN ('correction', 'retraction') "
							+ "ORDER BY id LIMIT 1",
					record.id()));
			if (change.isEmpty()) {
				continue;
			}
			Optional<Fact> original = queries.get(change.get().lng("fact_id"));
			if (original.isEmpty()) {
				continue;
			}
			boolean retraction = "retraction".equals(change.get().str("kind"));
			Long replacementId = change.get().lngOrNull("superseded_by_id");
			if (replacementId == null) {
				replacementId = original.get().supersededBy();
			}
			List<FactRef> facts = retraction ? List.of()
					: replacementId != null && queries.get(replacementId).isPresent()
							? List.of(refOf(queries.get(replacementId).get()))
							: queries.factsOfObservation(record.id()).stream().map(this::refOf).toList();
			storeReading(record,
					reading(retraction ? "retracts" : "corrects", original.get(), change.get().str("reason"), facts));
			n++;
		}
		return n;
	}

	FactRef refOf(Fact f) {
		String subject = f.subjectId() == entities.owner().id() ? "self" : entities.nameOf(f.subjectId());
		String object = f.objectId() != null ? entities.nameOf(f.objectId()) : f.objectText();
		String scope = f.scopeId() == null ? null : entities.nameOf(f.scopeId());
		boolean statedStart = f.validStart() != null && !"event".equals(f.startSource());
		boolean statedEnd = f.validEnd() != null
				&& (f.endSource() == null || "stated".equals(f.endSource()) || "resolved".equals(f.endSource()));
		ValidTime vt = !statedStart && !statedEnd ? null : new ValidTime(statedStart ? f.validStart() : null,
				statedEnd ? f.validEnd() : null, statedStart ? f.validStartPrecision() : f.validEndPrecision());
		Boolean ended = f.ended() && !statedEnd && f.endSource() == null ? Boolean.TRUE : null;
		return new FactRef(subject, f.predicate(), object, f.qualifier(), scope, vt, ended, List.of(),
				new Proposal.Derivation("explicit"), f.callerConfidence(),
				"negated".equals(f.mode()) ? Boolean.TRUE : null, "only".equals(f.mode()) ? Boolean.TRUE : null);
	}

	Optional<Fact> findByKey(Map<?, ?> key) {
		String subject = str(key, "subject", null);
		Entity s = subject == null || EntityService.SELF.contains(Names.norm(subject)) ? entities.owner()
				: entities.byRef(subject).orElse(null);
		if (s == null) {
			return Optional.empty();
		}
		String predicate = str(key, "predicate", null);
		String object = str(key, "object", null);
		String qualifier = str(key, "qualifier", null);
		String scope = str(key, "scope", null);
		String mode = str(key, "mode", "asserted");
		return queries.factsOf(s.id()).stream().filter(f -> f.subjectId() == s.id() && f.current())
				.filter(f -> f.predicate().equals(predicate) && mode.equals(f.mode()))
				.filter(f -> object == null ? f.objectId() == null && f.objectText() == null
						: f.objectId() != null ? Names.norm(entities.nameOf(f.objectId())).equals(Names.norm(object))
								: f.objectText() != null && f.objectText().equalsIgnoreCase(object))
				.filter(f -> Objects.equals(qualifier, f.qualifier())
						|| ProposalApplier.freeQualifier(predicates.get(f.predicate()).orElseThrow()))
				.filter(f -> scope == null ? f.scopeId() == null
						: f.scopeId() != null && Names.norm(entities.nameOf(f.scopeId())).equals(Names.norm(scope)))
				.findFirst();
	}

	private static String str(Map<?, ?> m, String key, String dflt) {
		Object v = m.get(key);
		return v == null ? dflt : v.toString();
	}
}
