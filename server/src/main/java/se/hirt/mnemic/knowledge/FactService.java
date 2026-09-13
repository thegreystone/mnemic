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

import se.hirt.mnemic.knowledge.EntityService.Candidate;
import se.hirt.mnemic.knowledge.EntityService.Resolved;
import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.*;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2 (EXTRACTION.md): predicate resolution, entity resolution, domain/range checks, derivation-kind validation,
 * temporal normalisation with bound provenance, rendering, span anchoring, corroboration, the consistency check on
 * functional predicates, event effects, corrections, history, the question queue and its resolution (DECISIONS.md
 * §2.7), and consolidation (EXTRACTION.md, Layer 3).
 * <p>
 * What Mnemic cannot decide it holds: an ambiguous entity or predicate keeps the whole fact in the question's payload
 * and nothing is stored until the caller answers (EVALUATION.md B2, J3); an unexplained conflict stores the new fact as
 * {@code pending}, linked to its question (C2, D3).
 */
public final class FactService {

	/** What a proposal produced. Every id is reported so the caller can refer to it later. */
	public record Applied(List<EntityOut> entities, List<EventOut> events, List<FactOut> facts,
	                      List<PredicateOut> predicates, List<Map<String, Object>> questions, List<String> warnings,
	                      List<Map<String, Object>> superseded) {
	}

	public record EntityOut(String ref, String id, String name, String resolution, double score) {
	}

	public record EventOut(String ref, String id, String type) {
	}

	public record FactOut(String id, String predicate, String rendering, String status, boolean corroborated) {
	}

	public record PredicateOut(String proposed, String resolution, String id) {
	}

	/** One entry of {@code history}: a fact with every recorded change to it. */
	public record HistoryEntry(Fact fact, List<Supersession> supersessions) {
	}

	public record History(List<HistoryEntry> entries, List<Tombstone> tombstones) {
	}

	/** A forgotten observation that had facts about the entity: when, nothing else (EVALUATION.md D2). */
	public record Tombstone(long observationId, String forgottenAt) {
	}

	public record Corrected(Fact original, Fact replacement) {
	}

	/**
	 * An answer to an open question: the choice is a candidate id ({@code ent-4}, {@code works_at}), {@code new}, or
	 * for conflicts {@code ended}, {@code supersede}, {@code reject}, {@code reinterpret}.
	 */
	public record Resolve(String questionId, String choice) {
	}

	/** What consolidation did or would do (EVALUATION.md G2, G3, J6). */
	public record Consolidated(List<Map<String, Object>> merges, int reclosed,
	                           List<Map<String, Object>> suggestedRegistrations,
	                           List<Map<String, Object>> resolvedQuestions, List<Map<String, Object>> review,
	                           List<Map<String, Object>> duplicates) {
	}

	private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\n]");
	private static final Pattern REF_SHAPE = Pattern.compile("(?i)(e|ev|ent|evt)[-_]?\\d+");
	private static final Pattern AGO = Pattern.compile("(\\d+)\\s+(year|month|week|day)s?\\s+ago");
	private static final Pattern LAST = Pattern.compile("last\\s+(year|month|week)");

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final QuestionService questions;

	public FactService(
			Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			QuestionService questions) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.questions = questions;
		this.lang = predicates.lang();
	}

	/** The language of the fact layer (MNEMIC_LANGUAGE): templates, suffixes, and the words around a fact. */
	private final Lang lang;

	// ── apply a proposal ─────────────────────────────────────────────────

	public Applied apply(Observation obs, Proposal p) {
		return apply(obs, p, Map.of());
	}

	/**
	 * Ids of entities the proposal being applied declares (exact matches of their names and aliases). An
	 * entity the caller declared alongside another is distinct from it by assertion, so it is never offered
	 * as an ambiguity for the other's name. Set for the duration of one {@link #apply}; the engine is
	 * single-threaded per store, and nested applies save and restore it.
	 */
	private Set<Long> declaredInProposal = Set.of();

	/** {@code bound} pre-binds names to entities: used when a question is answered with a chosen candidate. */
	public Applied apply(Observation obs, Proposal p, Map<String, Entity> bound) {
		Set<Long> outer = declaredInProposal;
		try {
			var declared = new HashSet<Long>();
			for (EntityRef er : p.entities()) {
				declared.addAll(entities.exactIds(er.name(), er.aliases(), er.type()));
			}
			declaredInProposal = declared;
			return applyInner(obs, p, bound);
		} finally {
			declaredInProposal = outer;
		}
	}

	/** Everything a name must not be confused with: the proposal's other declared entities and those already resolved. */
	private Set<Long> distinctFrom(Map<String, Entity> refs, Set<Long> own) {
		var out = new HashSet<>(declaredInProposal);
		for (Entity e : refs.values()) {
			if (e != null) {
				out.add(e.id());
			}
		}
		out.removeAll(own);
		out.remove(entities.owner().id());
		return out;
	}

	private Applied applyInner(Observation obs, Proposal p, Map<String, Entity> bound) {
		var warnings = new ArrayList<String>();
		var qs = new ArrayList<Map<String, Object>>();
		var superseded = new ArrayList<Map<String, Object>>();
		var entityOut = new ArrayList<EntityOut>();
		var eventOut = new ArrayList<EventOut>();
		var factOut = new ArrayList<FactOut>();
		var predicateOut = new ArrayList<PredicateOut>();
		var refs = new LinkedHashMap<String, Entity>();
		for (Map.Entry<String, Entity> e : bound.entrySet()) {
			refs.put(e.getKey(), e.getValue());
			refs.put(Names.norm(e.getKey()), e.getValue());
		}

		var defs = new HashMap<String, PredicateDef>();
		for (PredicateDef d : p.predicates()) {
			defs.put(d.name(), d);
		}

		for (EntityRef er : p.entities()) {
			if (er.name() == null || er.name().isBlank()) {
				warnings.add("An entity without a 'name' was skipped" + (er.ref() != null ? " (ref " + er.ref() + ")"
						: "") + "; facts that refer to it are skipped too.");
				continue;
			}
			if (refs.containsKey(Names.norm(er.name()))) {
				Entity e = refs.get(Names.norm(er.name()));
				if (er.ref() != null) {
					refs.put(er.ref(), e);
				}
				if (e != null) { // null: the same name is already held behind an entity question
					entityOut.add(
							new EntityOut(er.ref() != null ? er.ref() : er.name(), e.ref(), e.name(), "bound", 1.0));
				}
				continue;
			}
			Resolved r;
			try {
				r = entities.resolve(er.name(), er.type(), er.aliases(), obs.id(),
						distinctFrom(refs, entities.exactIds(er.name(), er.aliases(), er.type())));
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				warnings.add("Entity '" + er.name() + "' skipped: " + e.getMessage());
				continue;
			}
			String key = er.ref() != null ? er.ref() : er.name();
			if (r.ambiguous()) {
				// Hold everything that mentions this entity behind one question (B2).
				Question q = entityQuestion(obs, er.name(), er.type(), r.candidates(), heldProposal(p, er));
				qs.add(q.toMap());
				refs.put(key, null);
				refs.put(Names.norm(er.name()), null);
				continue;
			}
			refs.put(key, r.entity());
			refs.put(Names.norm(er.name()), r.entity());
			entityOut.add(new EntityOut(key, r.entity().ref(), r.entity().name(), r.how(), r.score()));
		}

		var eventIds = new HashMap<String, Long>();
		var opened = new ArrayList<FactRef>();
		for (EventRef ev : p.events()) {
			if (ev.type() == null || ev.type().isBlank()) {
				warnings.add("An event without a 'type' was skipped.");
				continue;
			}
			var participants = new ArrayList<Entity>();
			boolean held = false;
			try {
				for (String ref : ev.participants()) {
					Entity e = resolveRef(ref, refs, obs, p, qs);
					if (e == null) {
						held = true;
						break;
					}
					participants.add(e);
				}
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				warnings.add("Event '" + ev.type() + "' skipped: " + e.getMessage());
				continue;
			}
			if (held) {
				continue; // waits with the entity question
			}
			Bounds b = bounds(ev.validTime(), obs.observedAt(), warnings);
			String type = ev.type().trim().toLowerCase(Locale.ROOT);
			String rendering = type + "(" + String.join(", ",
					participants.stream().map(Entity::name).toList()) + ")" + b.suffix(false);
			// The same event said again, with or without its date, is the event on record, not a second one
			// (2026-09-11, "co-founded" stored dated in one conversation and undated in another). A date the record
			// lacked is filled in; a different date is a different event.
			Optional<Event> same = sameEvent(type, participants, b.start);
			final boolean dating = same.isPresent() && same.get().validStart() == null && b.start != null;
			long id;
			if (same.isPresent()) {
				id = same.get().id();
				if (dating) {
					db.write(tx -> {
						tx.update("""
						          UPDATE event SET valid_start = ?, valid_start_precision = ?, valid_end = ?, valid_end_precision = ?,
						                           rendering = ? WHERE id = ?""", b.start, b.startPrecision, b.end, b.endPrecision, rendering, same.get().id());
						return null;
					});
				}
			} else {
				id = db.write(tx -> {
					long eid = tx.insert("""
					                     INSERT INTO event(type, observation_id, valid_start, valid_start_precision, valid_end,
					                                       valid_end_precision, rendering, created_at) VALUES (?,?,?,?,?,?,?,?)""",
							type, obs.id(), b.start, b.startPrecision, b.end, b.endPrecision, rendering,
							Instant.now().toString());
					for (Entity e : participants) {
						tx.update("INSERT OR IGNORE INTO event_participant(event_id, entity_id) VALUES (?,?)", eid, e.id());
					}
					return eid;
				});
			}
			String key = ev.ref() != null ? ev.ref() : "evt-" + id;
			eventIds.put(key, id);
			eventOut.add(new EventOut(key, "evt-" + id, type));
			if (same.isEmpty() || dating) {
				applyEventEffects(id, type, participants, b, obs, superseded);
			}
			// An event type that opens a predicate (purchased → owns, joined → works_at) supplies the fact when the
			// proposal did not state it: the registry said so since M2, and until 2026-09-10 only closes and
			// ends_entity were honoured. A stated fact wins; a participant of the wrong type is left alone.
			Optional<EventType> et = eventTypes.get(type);
			if (et.isPresent() && participants.size() > 1 && !et.get().opens().isEmpty()) {
				Entity subj = participants.getFirst();
				for (int i = 1; i < participants.size(); i++) {
					Entity obj = participants.get(i);
					// One fact per event and object: a type that opens several predicates (joined: works_at,
					// member_of) opens the first whose types fit, and nothing when the proposal stated any of them.
					boolean stated = p.facts().stream().anyMatch(f -> et.get().opens().contains(f.predicate())
							&& bound(refs, f.subject()) == subj.id() && bound(refs, f.object()) == obj.id());
					if (stated) {
						continue;
					}
					for (String pred : et.get().opens()) {
						Predicate pr = predicates.get(pred).orElse(null);
						if (pr == null || pr.literalRange() || !pr.acceptsSubject(subj.type())
								|| !pr.acceptsObject(obj.type())) {
							continue;
						}
						opened.add(new FactRef(ev.participants().getFirst(), pred, ev.participants().get(i), null, null,
								ev.validTime(), null, List.of(key), null, null));
						break;
					}
				}
			}
		}

		// Containment first: a bound or a closure is checked against where a thing lies, and a proposal that says
		// "I bought a cabin in Sälen, Sweden" states the cabin's place after the cabin (family Q, Q7).
		// The output keeps the proposal's order; facts opened by events follow the stated ones.
		var allFacts = new ArrayList<>(p.facts());
		allFacts.addAll(opened);
		var order = new ArrayList<Integer>();
		for (int i = 0; i < allFacts.size(); i++) {
			String pr = allFacts.get(i).predicate();
			if ("located_in".equals(pr) || "part_of".equals(pr)) {
				order.add(i);
			}
		}
		for (int i = 0; i < allFacts.size(); i++) {
			if (!order.contains(i)) {
				order.add(i);
			}
		}
		var outs = new FactOut[allFacts.size()];
		for (int i : order) {
			FactRef f = allFacts.get(i);
			try {
				outs[i] = applyFact(obs, p, f, refs, eventIds, defs, qs, warnings, superseded, predicateOut).orElse(null);
			} catch (MnemicException e) {
				if (e.code() == MnemicException.Code.INVALID_ARGUMENT) {
					warnings.add("Fact skipped: " + e.getMessage());
				} else {
					throw e;
				}
			}
		}
		for (FactOut out : outs) {
			if (out != null) {
				factOut.add(out);
			}
		}
		for (Proposal.ClosureRef c : p.closures()) {
			try {
				applyClosure(obs, p, c, refs, qs, warnings).ifPresent(factOut::add);
			} catch (MnemicException e) {
				if (e.code() == MnemicException.Code.INVALID_ARGUMENT) {
					warnings.add("Closure skipped: " + e.getMessage());
				} else {
					throw e;
				}
			}
		}
		return new Applied(entityOut, eventOut, factOut, predicateOut, qs, warnings, superseded);
	}

	private Optional<FactOut> applyFact(
			Observation obs, Proposal p, FactRef f, Map<String, Entity> refs, Map<String, Long> eventIds,
			Map<String, PredicateDef> defs, List<Map<String, Object>> qs, List<String> warnings,
			List<Map<String, Object>> superseded, List<PredicateOut> predicateOut) {
		PredicateRegistry.Resolution res = predicates.resolve(f.predicate(), defs.get(f.predicate()), obs.id(),
				warnings);
		if (defs.containsKey(f.predicate()) && predicateOut.stream()
				.noneMatch(o -> o.proposed().equals(f.predicate()))) {
			Predicate named = res.predicate() != null ? res.predicate() : res.candidate();
			predicateOut.add(new PredicateOut(f.predicate(), res.how(), named == null ? null : named.name()));
		}
		if (res.asks()) {
			Question q = predicateQuestion(obs, f, res.candidate(), res.how(), defs.get(f.predicate()),
					heldProposal(p, f, defs.get(f.predicate())));
			qs.add(q.toMap());
			return Optional.empty();
		}
		Predicate pred = res.predicate();
		Entity subject = f.subject() == null ? entities.owner() : resolveRef(f.subject(), refs, obs, p, qs);
		if (subject == null) {
			return Optional.empty(); // held behind an entity question
		}
		if (f.object() == null || f.object().isBlank()) {
			throw MnemicException.invalidArgument("fact " + pred.name() + " has no 'object'.");
		}
		Entity object = null;
		String objectText = null;
		final String mode = f.mode();
		if (pred.literalRange()) {
			objectText = f.object().trim();
			// "considering that X happened in 1998" is a recollection filed as a plan (2026-09-11, f-178): the spec says
			// not to, and now the store refuses it, so the caller stores the recollection as the fact it recalls.
			if (("considering".equals(pred.name()) || "decided".equals(pred.name()))
					&& RECOLLECTION.matcher(objectText).find()) {
				warnings.add("'" + pred.name() + "' takes a plan or an option as its object, never a statement: \"" + objectText
						+ "\" reads as a recollection. Not stored; store what is recalled as the fact itself, with "
						+ "caller_confidence when unsure.");
				return Optional.empty();
			}
		} else if ("negated".equals(mode) && !namesAnEntity(f.object(), refs)) {
			// "I own nothing in Sweden": the object of a negation may be a class, not a thing (family Q). It is kept
			// as a literal rather than minted as an entity called "anything in Sweden".
			objectText = f.object().trim();
		} else {
			object = resolveRef(f.object(), refs, obs, p, qs);
			if (object == null) {
				return Optional.empty();
			}
			if ("only".equals(mode) && !Names.isPlace(object.type())) {
				throw MnemicException.invalidArgument("'only' needs a place as its object (the bound everything lies "
						+ "within); " + object.name() + " is " + object.type() + ".");
			}
			final Entity outer = object;
			if ("asserted".equals(mode)) containsAnotherObject(subject, pred, outer).ifPresent(inner -> warnings.add(
					"'" + subject.name() + " " + pred.name() + " " + outer.name() + "': " + outer.name() + " contains "
							+ inner.name() + ", which " + subject.name() + " already " + pred.name() + ". A claim that "
							+ "everything the subject " + pred.name() + " lies within a place is a restriction, not "
							+ pred.name() + " of the place; if that was meant, keep it as text until restrictions are "
							+ "representable."));
		}
		if (!pred.acceptsSubject(subject.type())) {
			qs.add(typeMismatch(obs, pred, "subject", subject, pred.domain()).toMap());
			return Optional.empty();
		}
		if (object != null && !pred.acceptsObject(object.type())) {
			qs.add(typeMismatch(obs, pred, "object", object, pred.range()).toMap());
			return Optional.empty();
		}
		Entity scope = f.scope() == null ? null : resolveRef(f.scope(), refs, obs, p, qs);
		if (f.scope() != null && scope == null) {
			return Optional.empty();
		}
		// A vocabulary qualifier (mother, half-sister) is normalised; free text on a predicate without a vocabulary
		// keeps the caller's casing ("converted from HB to AB", 2026-09-10).
		String qualifier = f.qualifier() == null ? null
				: pred.qualifiers().isEmpty() ? f.qualifier().trim() : f.qualifier().trim().toLowerCase(Locale.ROOT);
		if (qualifier != null && !pred.qualifiers().isEmpty() && !pred.qualifiers().contains(qualifier)) {
			warnings.add(
					"Qualifier '" + qualifier + "' is not one of " + pred.qualifiers() + " for " + pred.name() + "; kept as given.");
		}
		if (qualifier != null && !pred.render().contains("{qualifier")) {
			warnings.add("Qualifier '" + qualifier + "' is stored but " + pred.name() + "'s template has no slot for it, "
					+ "so the rendering will not show it; correct the predicate's render (add [[ ({qualifier})]]) or put "
					+ "the meaning in the observation text.");
		}
		String kind = derivationKind(f, obs, warnings);
		boolean ended = Boolean.TRUE.equals(f.ended());
		Bounds b = bounds(f.validTime(), obs.observedAt(), warnings);
		Long eventId = f.derivedFrom().stream().map(eventIds::get).filter(java.util.Objects::nonNull).findFirst()
				.orElse(null);
		Event event = eventId == null ? null : event(eventId).orElse(null);
		if (event != null && b.start == null && event.validStart() != null) {
			b = new Bounds(event.validStart(), event.validStartPrecision(), "event", b.end, b.endPrecision,
					b.endSource);
		}
		if (b.end == null && object != null) {
			Optional<Event> closing = closingEvent(pred.name(), subject.id(), object.id());
			if (closing.isPresent() && closing.get().validStart() != null) {
				b = new Bounds(b.start, b.startPrecision, b.startSource, closing.get().validStart(),
						closing.get().validStartPrecision(), "event");
				ended = true;
			}
		}
		String objectName = object != null ? object.name() : objectText;
		// A caller that said "I believe" passes caller_confidence below 0.6; the rendering says so, since a
		// recollection about 1998 must not read like a statement (2026-09-10, family E).
		Double callerConfidence = f.callerConfidence() == null ? null : Math.max(0.0, Math.min(1.0, f.callerConfidence()));
		final String base = renderFact(pred, mode, subject.name(), objectName, scope == null ? null : scope.name(),
				qualifier) + (callerConfidence != null && callerConfidence < Fact.BELIEVED_BELOW ? lang.believed() : "");
		String rendering = base + b.suffix(ended, lang);
		int[] span = span(obs.text(), objectName);

		final Entity subj = subject;
		final Entity obj = object;
		final String objText = objectText;
		final Entity sc = scope;
		final Bounds bounds = b;
		final boolean isEnded = ended;
		record Stored(FactOut out, Fact conflictWith, long id, String why, List<long[]> asks) {
		}
		Stored stored = db.write(tx -> {
			Optional<Row> existing = tx.queryOne("""
			                                     SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
			                                     AND COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?
			                                     AND (? = 1 OR COALESCE(qualifier, '') = ?) AND COALESCE(scope_id, -1) = ? AND mode = ?
			                                     ORDER BY id LIMIT 1""",
					subj.id(), pred.name(), obj == null ? -1 : obj.id(),
					objText == null ? "" : objText.toLowerCase(Locale.ROOT), freeQualifier(pred) ? 1 : 0,
					qualifier == null ? "" : qualifier, sc == null ? -1 : sc.id(), mode);
			if (existing.isPresent()) {
				Fact e = Fact.from(existing.get());
				if (e.observationId() == obs.id()) {
					// The same fact twice in one proposal (stated, and opened by its event): one row, and saying it
					// twice in one breath is not a corroboration (review, 2026-09-10).
					return new Stored(null, null, e.id(), null, List.of());
				}
				String confirmed =
						obs.observedAt().toString().compareTo(e.lastConfirmed()) > 0 ? obs.observedAt().toString()
								: e.lastConfirmed();
				tx.update("UPDATE fact SET corroborations = corroborations + 1, last_confirmed = ? WHERE id = ?",
						confirmed, e.id());
				link(tx, e.id(), obs.id(), "corroborated");
				String shown = e.rendering();
				if (freeQualifier(pred) && fuller(qualifier, e.qualifier())) {
					// The restatement says more (2026-09-11: "financed the work at" lost "until venture capital
					// arrived" to the older wording): the fact takes the fuller wording, the older one stays in the
					// observation that said it.
					tx.update("UPDATE fact SET qualifier = ? WHERE id = ?", qualifier, e.id());
					shown = rerenderOne(tx, e.id());
				}
				return new Stored(new FactOut(e.ref(), pred.name(), shown, e.status(), true), null, e.id(), null,
						List.of());
			}
			String status = "current";
			var toClose = new ArrayList<Fact>();
			Fact conflictWith = null;
			String conflictWhy = null;
			var asks = new ArrayList<long[]>();
			// A fact that precedes a current one (a 1996 job sent after the 2019 one) is history: it ends at the
			// nearest later start, the same inference C1 draws when the later fact arrives second, so arrival
			// order does not change the outcome. Until 2026-09-10 the founding event closed the 2019 job at 1996.
			Bounds row = bounds;
			boolean rowEnded = isEnded;
			if ("asserted".equals(mode) && pred.functional()) {
				Fact nearestLater = null;
				for (Fact other : otherCurrentValues(tx, subj.id(), pred, obj, objText, sc)) {
					boolean otherStartsAfter = other.validStart() != null && bounds.start != null
							&& other.validStart().compareTo(bounds.start) > 0;
					// ...unless the new fact's own end reaches past the later one's start: then they overlap, and a
					// functional predicate cannot hold both (review, 2026-09-10).
					boolean overlapsLater = otherStartsAfter && bounds.end != null
							&& bounds.end.compareTo(other.validStart()) > 0;
					if (otherStartsAfter && !overlapsLater) {
						if (nearestLater == null || other.validStart().compareTo(nearestLater.validStart()) < 0) {
							nearestLater = other;
						}
						continue;
					}
					if (event != null && supersedes(event.type(), pred.name())) {
						toClose.add(other);
					} else if (disjoint(other, bounds)) {
						continue;
					} else if (other.ended() || isEnded) {
						continue;
					} else {
						conflictWith = other;
						status = "pending";
						break;
					}
				}
				if (nearestLater != null && bounds.end == null && !isEnded) {
					row = new Bounds(bounds.start, bounds.startPrecision, bounds.startSource, nearestLater.validStart(),
							nearestLater.validStartPrecision(), "sequence");
					rowEnded = true;
				}
			}
			final Bounds stored0 = row;
			final boolean ended0 = rowEnded;
			final String rowRendering = ended0 != isEnded ? base + stored0.suffix(true, lang) : rendering;
			// Family Q: a negation against the asserted fact on the same key, an asserted fact against a negation, a
			// bound, or a closure, is a conflict the user settles; a bound the containment chain cannot decide is a
			// containment question, never an assertion (K7: an incomplete chain is not a contradiction).
			if (conflictWith == null && !ended0) {
				if ("asserted".equals(mode)) {
					Optional<Fact> neg = sameKey(tx, subj.id(), pred.name(), obj, objText, qualifier, sc, "negated", freeQualifier(pred));
					if (neg.isPresent()) {
						conflictWith = neg.get();
						conflictWhy = "\"" + neg.get().rendering() + "\" is on record and \"" + rendering
								+ "\" says the opposite. Did it become so (ended: the negation held until now), was the "
								+ "negation wrong from the start (wrong), or is the new fact wrong (reject)?";
					}
					if (conflictWith == null && obj != null) {
						for (Fact bound : boundsIn(tx, subj.id(), pred.name())) {
							if ("only".equals(bound.mode()) && bound.objectId() != null && Names.isPlace(obj.type())) {
								Containment c = containment(tx, obj.id(), bound.objectId());
								if (c.relation() == Relation.DISJOINT) {
									conflictWith = bound;
									conflictWhy = "\"" + bound.rendering() + "\" is on record and " + obj.name()
											+ " lies outside " + nameIn(tx, bound.objectId()) + ". Did the restriction "
											+ "end (ended), was it wrong from the start (wrong), or is the new fact wrong (reject)?";
									break;
								}
								if (c.relation() == Relation.UNKNOWN) {
									asks.add(new long[] {c.top(), bound.objectId(), bound.id()});
								}
							} else if ("closure".equals(bound.mode()) && kindMatches(bound.objectText(), obj.type())) {
								conflictWith = bound;
								conflictWhy = "\"" + bound.rendering() + "\" is on record and \"" + rendering
										+ "\" adds to that class. Was the list complete until now (ended), was it never "
										+ "complete (wrong), or is the new fact wrong (reject)?";
								break;
							}
						}
					}
				} else if ("negated".equals(mode)) {
					Optional<Fact> pos = sameKey(tx, subj.id(), pred.name(), obj, objText, qualifier, sc, "asserted", freeQualifier(pred));
					if (pos.isPresent()) {
						conflictWith = pos.get();
						conflictWhy = "\"" + pos.get().rendering() + "\" is on record and \"" + rendering
								+ "\" says the opposite. Did it stop being so (ended), was the earlier fact wrong from the "
								+ "start (wrong), or is the negation wrong (reject)?";
					}
				} else if ("only".equals(mode) && obj != null) {
					for (Fact other : assertedOpen(tx, subj.id(), pred.name())) {
						if (other.objectId() == null || !Names.isPlace(typeIn(tx, other.objectId()))) {
							continue; // a domain or a printer cannot be located: outside the class
						}
						Containment c = containment(tx, other.objectId(), obj.id());
						if (c.relation() == Relation.DISJOINT) {
							conflictWith = other;
							conflictWhy = "\"" + other.rendering() + "\" is on record and lies outside " + obj.name()
									+ ", which \"" + rendering + "\" excludes. Did the earlier fact end (ended), was it wrong "
									+ "(wrong), or is the restriction wrong (reject)?";
							break;
						}
						if (c.relation() == Relation.UNKNOWN) {
							asks.add(new long[] {c.top(), obj.id(), -1});
						}
					}
				}
				if (conflictWith != null) {
					status = "pending";
				}
			}
			long id = tx.insert("""
			                    INSERT INTO fact(subject_id, predicate, object_id, object_text, qualifier, scope_id, valid_start,
			                                     valid_start_precision, valid_end, valid_end_precision, ended, status,
			                                     derivation_kind, observation_id, event_id, span_start, span_end, rendering,
			                                     spec_version, caller_confidence, corroborations, last_confirmed, created_at,
			                                     start_source, end_source, mode)
			                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?)""", subj.id(), pred.name(),
					obj == null ? null : obj.id(), objText, qualifier, sc == null ? null : sc.id(), stored0.start,
					stored0.startPrecision, stored0.end, stored0.endPrecision, ended0 ? 1 : 0, status, kind, obs.id(),
					eventId, span == null ? null : span[0], span == null ? null : span[1], rowRendering, obs.specVersion(),
					callerConfidence, obs.observedAt().toString(), Instant.now().toString(), stored0.startSource,
					stored0.endSource, mode);
			link(tx, id, obs.id(), "stated");
			for (long[] ask : asks) {
				if (ask[2] < 0) {
					ask[2] = id; // the restriction being stored is the one the containment serves
				}
			}
			for (Fact other : toClose) {
				close(tx, other, id, "event", null, eventId, obs.id(), event.validStart(), event.validStartPrecision(),
						"superseded");
				superseded.add(supersededOut(other, id, event));
			}
			return new Stored(new FactOut("f-" + id, pred.name(), rowRendering, status, false), conflictWith, id,
					conflictWhy, asks);
		});
		if (stored.conflictWith() != null) {
			Question q = conflictQuestion(obs, pred, stored.conflictWith(), stored.id(), rendering, stored.why());
			questions.linkFact(q.id(), stored.id());
			qs.add(questions.get(q.id()).orElseThrow().toMap());
		}
		for (long[] ask : stored.asks()) {
			containmentQuestion(obs, ask[0], ask[1], ask[2]).ifPresent(q -> qs.add(q.toMap()));
		}
		return Optional.ofNullable(stored.out());
	}

	/** The id of the entity a proposal ref is bound to, or -1 when it is not (yet) bound; {@code self} is the owner. */
	private long bound(Map<String, Entity> refs, String ref) {
		if (ref == null || ref.isBlank() || EntityService.SELF.contains(Names.norm(ref))) {
			return entities.owner().id();
		}
		Entity e = refs.get(ref);
		if (e == null) {
			e = refs.get(Names.norm(ref));
		}
		return e == null ? -1 : e.id();
	}

	/** True when {@code ref} is a declared proposal ref, {@code self}, or the exact name of a known entity. */
	private boolean namesAnEntity(String ref, Map<String, Entity> refs) {
		if (ref == null || ref.isBlank()) {
			return false;
		}
		return refs.containsKey(ref) || refs.containsKey(Names.norm(ref)) || EntityService.SELF.contains(Names.norm(ref))
				|| REF_SHAPE.matcher(ref).matches() || !entities.exactIds(ref, List.of(), null).isEmpty();
	}

	/** The rendering for a mode: the template, its negation, its bound, or the closure sentence (family Q). */
	String renderFact(Predicate pred, String mode, String subject, String object, String scope, String qualifier) {
		String q = lang.qualifier(qualifier);
		return switch (mode == null ? "asserted" : mode) {
			case "negated" -> pred.renderNegated(lang, predicates.negatedTemplate(pred.name()), subject, object, scope, q);
			case "only" -> pred.renderOnly(lang, subject, object, scope, q);
			case "closure" -> pred.renderClosure(lang, subject, object);
			default -> pred.render(subject, object, scope, q);
		};
	}

	/**
	 * A completeness marker (family Q, Q9): stored as a fact row of mode {@code closure} whose object text is the
	 * class, so history, corroboration, supersession, and the conflict machinery apply unchanged. Nothing already
	 * stored is questioned by it; a later fact in the class is (Q9).
	 */
	private Optional<FactOut> applyClosure(
			Observation obs, Proposal p, Proposal.ClosureRef c, Map<String, Entity> refs,
			List<Map<String, Object>> qs, List<String> warnings) {
		if (c.predicate() == null || c.predicate().isBlank()) {
			throw MnemicException.invalidArgument("a closure needs a 'predicate'.");
		}
		Predicate pred = predicates.get(c.predicate()).orElseThrow(() -> MnemicException.invalidArgument(
				"closure over unknown predicate '" + c.predicate() + "'."));
		if (c.type() == null || c.type().isBlank()) {
			throw MnemicException.invalidArgument("a closure needs a 'type' (the class it completes, e.g. place).");
		}
		Entity subject = c.subject() == null ? entities.owner() : resolveRef(c.subject(), refs, obs, p, qs);
		if (subject == null) {
			return Optional.empty();
		}
		String type = Names.type(c.type());
		String rendering = renderFact(pred, "closure", subject.name(), type, null, null);
		String kind = derivationKind(new FactRef(c.subject(), c.predicate(), type, null, null, null, null, List.of(),
				null, null), obs, warnings);
		return Optional.of(db.write(tx -> {
			Optional<Row> existing = tx.queryOne("""
			                                     SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND mode = 'closure'
			                                     AND lower(object_text) = ? ORDER BY id LIMIT 1""", subject.id(), pred.name(), type);
			if (existing.isPresent()) {
				Fact e = Fact.from(existing.get());
				tx.update("UPDATE fact SET corroborations = corroborations + 1, last_confirmed = ? WHERE id = ?",
						obs.observedAt().toString(), e.id());
				link(tx, e.id(), obs.id(), "corroborated");
				return new FactOut(e.ref(), pred.name(), e.rendering(), e.status(), true);
			}
			long id = tx.insert("""
			                    INSERT INTO fact(subject_id, predicate, object_id, object_text, qualifier, scope_id, ended, status,
			                                     derivation_kind, observation_id, rendering, spec_version, corroborations,
			                                     last_confirmed, created_at, mode)
			                    VALUES (?,?,NULL,?,NULL,NULL,0,'current',?,?,?,?,1,?,?,'closure')""", subject.id(), pred.name(),
					type, kind, obs.id(), rendering, obs.specVersion(), obs.observedAt().toString(),
					Instant.now().toString());
			link(tx, id, obs.id(), "stated");
			return new FactOut("f-" + id, pred.name(), rendering, "current", false);
		}));
	}

	// ── the observations behind a fact ─────────────────────────────────

	/** Records that an observation stated or corroborated a fact (schema 16); the first is the fact's home. */
	private static void link(Tx tx, long factId, long observationId, String kind) {
		tx.update("INSERT OR IGNORE INTO fact_source(fact_id, observation_id, kind, recorded_at) VALUES (?,?,?,?)",
				factId, observationId, kind, Instant.now().toString());
	}

	/** Every observation that stated or corroborated the fact, the fact's home first, then in order of arrival. */
	public List<Long> observationsOf(long factId) {
		return db.read(tx -> observationsOf(tx, factId));
	}

	private static List<Long> observationsOf(Tx tx, long factId) {
		var out = new ArrayList<Long>();
		Optional<Long> home = tx.queryOne("SELECT observation_id FROM fact WHERE id = ?", factId).map(r -> r.lng("observation_id"));
		home.ifPresent(out::add);
		for (Row r : tx.query("SELECT observation_id FROM fact_source WHERE fact_id = ? ORDER BY recorded_at, observation_id",
				factId)) {
			long id = r.lng("observation_id");
			if (!out.contains(id)) {
				out.add(id);
			}
		}
		return out;
	}

	// ── containment (family Q) ───────────────────────────────────────────

	public enum Relation {
		WITHIN, CONTAINS, DISJOINT, UNKNOWN
	}

	/** {@code top} is the highest place reached from x whose containment is not on record, for the question. */
	public record Containment(Relation relation, long top) {
	}

	/** Ancestors of a place through current asserted {@code located_in} facts, nearest first, at most six hops. */
	public List<Long> ancestors(long entityId) {
		return db.read(tx -> ancestors(tx, entityId));
	}

	private static List<Long> ancestors(Tx tx, long entityId) {
		var out = new ArrayList<Long>();
		long at = entityId;
		for (int hop = 0; hop < 6; hop++) {
			final long from = at;
			Optional<Long> up = tx.queryOne("""
			                                SELECT object_id FROM fact WHERE subject_id = ? AND predicate = 'located_in' AND status = 'current'
			                                AND ended = 0 AND mode = 'asserted' AND object_id IS NOT NULL ORDER BY id LIMIT 1""", from)
					.map(r -> r.lng("object_id"));
			if (up.isEmpty() || up.get() == entityId || out.contains(up.get())) {
				break;
			}
			out.add(up.get());
			at = up.get();
		}
		return out;
	}

	/**
	 * Whether x lies within the bound, from what is on record: the chain from x reaches the bound (within), the
	 * bound's chain reaches x (contains), the two chains meet under a common place with x on another branch, or
	 * they reach different countries (disjoint), else unknown. A missing link is a gap the containment question
	 * surfaces at write time, never a contradiction.
	 */
	public Containment containment(long x, long bound) {
		return db.read(tx -> containment(tx, x, bound));
	}

	private static Containment containment(Tx tx, long x, long bound) {
		if (x == bound) {
			return new Containment(Relation.WITHIN, x);
		}
		List<Long> px = ancestors(tx, x);
		if (px.contains(bound)) {
			return new Containment(Relation.WITHIN, x);
		}
		var pb = new ArrayList<Long>();
		pb.add(bound);
		pb.addAll(ancestors(tx, bound));
		if (pb.contains(x)) {
			return new Containment(Relation.CONTAINS, x);
		}
		long top = px.isEmpty() ? x : px.getLast();
		for (Long a : px) {
			if (pb.contains(a)) {
				return new Containment(Relation.DISJOINT, top);
			}
		}
		Long cx = countryIn(tx, x, px);
		Long cb = countryIn(tx, bound, pb);
		if (cx != null && cb != null && !cx.equals(cb)) {
			return new Containment(Relation.DISJOINT, top);
		}
		return new Containment(Relation.UNKNOWN, top);
	}

	private static Long countryIn(Tx tx, long id, List<Long> path) {
		if ("country".equals(typeIn(tx, id))) {
			return id;
		}
		for (Long a : path) {
			if ("country".equals(typeIn(tx, a))) {
				return a;
			}
		}
		return null;
	}

	private static String typeIn(Tx tx, long entityId) {
		return tx.queryOne("SELECT type FROM entity WHERE id = ?", entityId).map(r -> r.str("type")).orElse("unknown");
	}

	/** A closure over {@code place} covers towns and countries; any other class is exact. */
	public static boolean kindMatches(String closureType, String entityType) {
		return closureType.equals(entityType) || ("place".equals(closureType) && Names.isPlace(entityType));
	}

	/**
	 * Whether a predicate's qualifier is wording rather than identity (2026-09-11, two "believed to be the same
	 * company" facts that differed only in how the belief was phrased): a predicate with a qualifier vocabulary
	 * (parent_of: mother, father) keys its facts by qualifier; one without (related_to, knows) does not.
	 */
	static boolean freeQualifier(Predicate p) {
		return p.qualifiers() == null || p.qualifiers().isEmpty();
	}

	/** An object that is a clause about the world, not a plan or an option. */
	private static final Pattern RECOLLECTION = Pattern.compile("^(?:that|whether|if)\\b", Pattern.CASE_INSENSITIVE);

	private static Optional<Fact> sameKey(
			Tx tx, long subjectId, String predicate, Entity obj, String objText, String qualifier, Entity scope,
			String mode, boolean freeQualifier) {
		return tx.queryOne("""
		                   SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND ended = 0
		                   AND valid_end IS NULL AND COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?
		                   AND (? = 1 OR COALESCE(qualifier, '') = ?) AND COALESCE(scope_id, -1) = ? AND mode = ? ORDER BY id LIMIT 1""",
				subjectId, predicate, obj == null ? -1 : obj.id(), objText == null ? "" : objText.toLowerCase(Locale.ROOT),
				freeQualifier ? 1 : 0, qualifier == null ? "" : qualifier, scope == null ? -1 : scope.id(), mode).map(Fact::from);
	}

	private static List<Fact> boundsIn(Tx tx, long subjectId, String predicate) {
		return tx.query("""
		                SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND ended = 0
		                AND valid_end IS NULL AND mode IN ('only', 'closure') ORDER BY id""", subjectId, predicate).stream()
				.map(Fact::from).toList();
	}

	private static List<Fact> assertedOpen(Tx tx, long subjectId, String predicate) {
		return tx.query("""
		                SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND ended = 0
		                AND valid_end IS NULL AND mode = 'asserted' ORDER BY id""", subjectId, predicate).stream()
				.map(Fact::from).toList();
	}

	/** The current bounds on a subject's predicate: negations, restrictions, closures (family Q). */
	public List<Fact> bounds(long subjectId, String predicate, Instant now) {
		return db.read(tx -> tx.query("""
		                              SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND mode <> 'asserted'
		                              ORDER BY id""", subjectId, predicate)).stream().map(Fact::from)
				.filter(f -> "current".equals(f.state(now))).toList();
	}

	/** Current open asserted facts of the subject under the predicate whose object is x or lies within x. */
	public List<Fact> assertedTouching(long subjectId, String predicate, long x) {
		return db.read(tx -> assertedOpen(tx, subjectId, predicate).stream()
				.filter(f -> f.objectId() != null && (f.objectId() == x || ancestors(tx, f.objectId()).contains(x)))
				.toList());
	}

	private Optional<Question> containmentQuestion(Observation obs, long top, long bound, long servesFactId) {
		String payload = Json.write(Map.of("entity", "ent-" + top, "within", "ent-" + bound));
		boolean open = questions.open(200).stream()
				.anyMatch(q -> "containment".equals(q.kind()) && payload.equals(q.payload()));
		if (open) {
			return Optional.empty();
		}
		String topName = entityName(top);
		String boundName = entityName(bound);
		String serves = get(servesFactId).map(Fact::rendering).orElse("the restriction");
		var c = new ArrayList<Map<String, Object>>();
		var yes = new LinkedHashMap<String, Object>();
		yes.put("n", 1);
		yes.put("id", "yes");
		yes.put("label", topName + " is within " + boundName + " (stores " + topName + " located_in " + boundName + ")");
		c.add(yes);
		var no = new LinkedHashMap<String, Object>();
		no.put("n", 2);
		no.put("id", "no");
		no.put("label", topName + " is not within " + boundName);
		c.add(no);
		String message = "Is " + topName + " within " + boundName + "? The containment chain on record stops at "
				+ topName + ", and \"" + serves + "\" holds only if it is. Answer yes or no.";
		return Optional.of(questions.create("containment", obs.id(), null, topName, "located_in", c, payload, message));
	}

	private Map<String, Object> resolveContainment(Question q, String choice, Observation obs) {
		Map<String, Object> payload = Json.readMap(q.payload());
		long top = Long.parseLong(payload.get("entity").toString().substring(4));
		long bound = Long.parseLong(payload.get("within").toString().substring(4));
		var m = new LinkedHashMap<String, Object>();
		switch (choice.toLowerCase(Locale.ROOT)) {
		case "yes" -> {
			var ref = new FactRef(entityName(top), "located_in", entityName(bound), null, null, null, null, List.of(),
					new Proposal.Derivation("explicit"), null);
			Applied a = apply(obs, new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref),
					List.of()));
			m.put("facts", a.facts().stream().map(FactOut::id).toList());
		}
		case "no" -> m.put("note", entityName(top) + " is recorded as not within " + entityName(bound)
				+ " only in this answer; the restriction and the fact it questioned disagree, correct one of them.");
		default -> throw MnemicException.invalidArgument(
				"'" + choice + "' is not an answer to " + q.ref() + "; use yes or no.");
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		return m;
	}

	// ── questions ────────────────────────────────────────────────────────

	private static List<Map<String, Object>> numbered(List<Candidate> candidates, boolean withNew, String newLabel) {
		var out = new ArrayList<Map<String, Object>>();
		int n = 1;
		for (Candidate c : candidates) {
			var m = new LinkedHashMap<String, Object>();
			m.put("n", n++);
			m.put("id", c.entity().ref());
			m.put("label", c.entity().name() + " (" + c.entity().type() + ")");
			m.put("score", c.score());
			out.add(m);
		}
		if (withNew) {
			var m = new LinkedHashMap<String, Object>();
			m.put("n", n);
			m.put("id", "new");
			m.put("label", newLabel);
			out.add(m);
		}
		return out;
	}

	private Question entityQuestion(
			Observation obs, String name, String type, List<Candidate> candidates,
			String payload) {
		List<Map<String, Object>> c = numbered(candidates, true, "a new entity named " + name);
		String message = "Is \"" + name + "\" " + String.join(" or ",
				candidates.stream().map(x -> x.entity().name() + " (" + x.entity().type() + ")")
						.toList()) + ", or someone else? The facts mentioning " + name + " are held until you answer with the " + "candidate id or \"new\".";
		return questions.create("entity_resolution", obs.id(), null, name, null, c, payload, message);
	}

	private Question predicateQuestion(
			Observation obs, FactRef f, Predicate candidate, String how, PredicateDef def,
			String payload) {
		var c = new ArrayList<Map<String, Object>>();
		var m1 = new LinkedHashMap<String, Object>();
		m1.put("n", 1);
		m1.put("id", candidate.name());
		m1.put("label", candidate.name() + ": " + candidate.description());
		m1.put("match", how);
		c.add(m1);
		var m2 = new LinkedHashMap<String, Object>();
		m2.put("n", 2);
		m2.put("id", "new");
		m2.put("label", "register '" + f.predicate() + "' as a new predicate");
		c.add(m2);
		String message = "Does '" + f.predicate() + "' (" + (def == null ? "" : def.description()) + ") mean the "
				+ "same as '" + candidate.name() + "' (" + candidate.description() + ")? "
				+ ("similar".equals(how) ? "They share several words, but a narrower or opposite meaning (a restriction, "
						+ "a negation) would be lost. " : "")
				+ "The fact is held until you answer with '" + candidate.name() + "' or \"new\"; " + "'"
				+ candidate.name() + "' also records '" + f.predicate() + "' as its alias when the match was similar.";
		return questions.create("predicate_resolution", obs.id(), null, f.subject(), f.predicate(), c, payload,
				message);
	}

	private Question conflictQuestion(Observation obs, Predicate pred, Fact existing, long pendingId, String proposed) {
		return conflictQuestion(obs, pred, existing, pendingId, proposed, null);
	}

	private Question conflictQuestion(
			Observation obs, Predicate pred, Fact existing, long pendingId, String proposed, String why) {
		var c = new ArrayList<Map<String, Object>>();
		String[][] choices = {{"ended", "the earlier one ended (unknown date)"},
				{"supersede", "the new one replaces the earlier one from now"},
				{"reject", "the new one is wrong; keep the earlier"},
				{"reinterpret", "the new one meant something else; a corrected proposal follows"},
				{"wrong", "the earlier one was wrong from the start; the new one corrects it"}};
		int n = 1;
		for (String[] ch : choices) {
			var m = new LinkedHashMap<String, Object>();
			m.put("n", n++);
			m.put("id", ch[0]);
			m.put("label", ch[1]);
			c.add(m);
		}
		String message = why != null ? why + " The new fact is held pending."
				: pred.name() + " allows one current value. \"" + existing.rendering() + "\" is current " + "and \"" + proposed + "\" overlaps it with no event, end date, or 'ended' flag to explain the " + "change. The new fact is held pending. Ask the user which is right.";
		String payload = Json.write(Map.of("existing", existing.ref(), "pending", "f-" + pendingId));
		return questions.create("conflict", obs.id(), pendingId, entityName(existing.subjectId()), pred.name(), c,
				payload, message);
	}

	private Question typeMismatch(Observation obs, Predicate pred, String position, Entity e, List<String> allowed) {
		String message = pred.name() + " expects a " + position + " of type " + allowed + "; " + e.name() + " is " + e.type() + ". Not stored. Correct the entity type or the predicate, then remember again.";
		var c = new ArrayList<Map<String, Object>>();
		var m = new LinkedHashMap<String, Object>();
		m.put("n", 1);
		m.put("id", "dismiss");
		m.put("label", "dismiss");
		c.add(m);
		String payload = Json.write(
				Map.of("entity", e.ref(), "entity_type", e.type(), "position", position, "expected", allowed));
		return questions.create("type_mismatch", obs.id(), null, e.name(), pred.name(), c, payload, message);
	}

	/** The proposal fragment a question holds: the facts and events that mention the entity, plus their refs. */
	private static String heldProposal(Proposal p, EntityRef er) {
		var facts = p.facts().stream().filter(f -> mentions(f, er)).toList();
		var events = p.events().stream().filter(ev -> ev.participants().stream().anyMatch(x -> isRef(x, er))).toList();
		// Only the entities the held facts and events refer to travel with them. Carrying the whole proposal
		// made every answer re-resolve every other held name against the entities the earlier answers had just
		// created: six questions answered in one call produced fifteen more (2026-09-09).
		var used = new HashSet<String>();
		for (FactRef f : facts) {
			for (String s : new String[] {f.subject(), f.object(), f.scope()}) {
				if (s != null) {
					used.add(Names.norm(s));
				}
			}
		}
		for (EventRef ev : events) {
			for (String s : ev.participants()) {
				if (s != null) {
					used.add(Names.norm(s));
				}
			}
		}
		var entities = p.entities().stream().filter(e -> e == er
				|| (e.ref() != null && used.contains(Names.norm(e.ref()))) || used.contains(Names.norm(e.name()))).toList();
		return Json.write(new Proposal(p.specVersion(), entities, events, facts, p.predicates()));
	}

	private static String heldProposal(Proposal p, FactRef f, PredicateDef def) {
		return Json.write(new Proposal(p.specVersion(), p.entities(), List.of(), List.of(f),
				def == null ? List.of() : List.of(def)));
	}

	private static boolean mentions(FactRef f, EntityRef er) {
		return isRef(f.subject(), er) || isRef(f.object(), er) || isRef(f.scope(), er);
	}

	private static boolean isRef(String s, EntityRef er) {
		return s != null && (s.equals(er.ref()) || Names.norm(s).equals(Names.norm(er.name())));
	}

	/** Resolves answers to open questions; each result says what happened. */
	/**
	 * Resolves answers to open questions; each result says what happened. Entity answers are taken in two
	 * phases: every entity is chosen or created first, then every held fragment is applied with all of them
	 * bound, so a fragment that names another answered subject finds it whatever the order of the answers
	 * (2026-09-09: "Willisau located_in Kanton Luzern" answered before Kanton Luzern's own question re-asked it).
	 * Afterwards any open entity question whose subject now names an entity exactly is settled.
	 */
	public List<Map<String, Object>> resolve(Observation obs, List<Resolve> resolves) {
		var out = new ArrayList<Map<String, Object>>();
		var pending = new ArrayList<Object[]>(); // {Question, choice, Entity, result map}
		var bindings = new LinkedHashMap<String, Entity>();
		for (Resolve r : resolves) {
			Question q = questions.require(r.questionId());
			if (!q.open()) {
				throw MnemicException.conflict(q.ref() + " is already " + q.status(), Map.of("question", q.ref()));
			}
			String choice = r.choice() == null ? "" : r.choice().trim();
			var result = new LinkedHashMap<String, Object>();
			result.put("question", q.ref());
			result.put("choice", choice);
			switch (q.kind()) {
			case "entity_resolution" -> {
				Entity chosen = chooseEntity(q, choice);
				bindings.put(q.subject(), chosen);
				pending.add(new Object[] {q, choice, chosen, result});
			}
			case "predicate_resolution" -> result.putAll(resolvePredicate(q, choice, obs));
			case "conflict" -> result.putAll(resolveConflict(q, choice, obs));
			case "containment" -> result.putAll(resolveContainment(q, choice, obs));
			default -> {
				questions.dismiss(q.id(), choice);
				result.put("status", "dismissed");
			}
			}
			out.add(result);
		}
		for (Object[] p : pending) {
			Question q = (Question) p[0];
			@SuppressWarnings("unchecked")
			var result = (LinkedHashMap<String, Object>) p[3];
			result.putAll(applyHeld(q, (String) p[1], (Entity) p[2], bindings, obs));
		}
		List<Map<String, Object>> settled = settleExactSubjects(false);
		if (!settled.isEmpty()) {
			var m = new LinkedHashMap<String, Object>();
			m.put("settled", settled);
			out.add(m);
		}
		return out;
	}

	/** The entity an entity question's answer names: a listed candidate, any existing {@code ent-N}, or a new one. */
	private Entity chooseEntity(Question q, String choice) {
		Proposal held = Proposal.parse(q.payload());
		if ("new".equalsIgnoreCase(choice)) {
			String type = held.entities().stream().filter(e -> Names.norm(e.name()).equals(Names.norm(q.subject())))
					.map(EntityRef::type).findFirst().orElse(null);
			// Created once: a second answer naming the same new subject in the same call reuses it.
			return entities.byRef(q.subject()).filter(e -> e.id() != entities.owner().id())
					.orElseGet(() -> entities.create(q.subject(), type, List.of(), q.observationId()));
		}
		boolean listed = q.candidates().stream().anyMatch(c -> choice.equals(c.get("id")));
		if (!listed && !choice.startsWith("ent-")) {
			throw MnemicException.invalidArgument(
					"'" + choice + "' is not a candidate of " + q.ref() + "; answer with one of " + q.candidates()
							+ ", an existing entity id (ent-N), or \"new\".");
		}
		Entity chosen = entities.byRef(choice).orElseThrow(() -> MnemicException.notFound("No entity " + choice));
		// The name the question was about is now known to be an alias of the chosen entity.
		entities.resolve(chosen.name(), chosen.type(), List.of(q.subject()), q.observationId());
		return chosen;
	}

	/** Applies what an entity question held, with every subject answered in the same call bound. */
	private Map<String, Object> applyHeld(Question q, String choice, Entity chosen, Map<String, Entity> bindings,
			Observation obs) {
		Proposal held = Proposal.parse(q.payload());
		Observation source = q.observationId() == null ? obs : observation(q.observationId()).orElse(obs);
		Applied a = apply(source, held, bindings);
		questions.answer(q.id(), choice);
		var m = new LinkedHashMap<String, Object>();
		m.put("status", "answered");
		m.put("entity", chosen.ref());
		m.put("facts", a.facts().stream().map(FactOut::id).toList());
		m.put("events", a.events().stream().map(EventOut::id).toList());
		if (!a.questions().isEmpty()) {
			m.put("questions", a.questions());
		}
		return m;
	}

	/**
	 * Open entity questions whose subject now names an entity exactly are settled: what they held is applied
	 * to that entity (identical facts corroborate rather than duplicate) and the question closes. Run after
	 * every batch of answers and by consolidate.
	 */
	List<Map<String, Object>> settleExactSubjects(boolean dryRun) {
		var settled = new ArrayList<Map<String, Object>>();
		for (Question q : questions.open(500)) {
			if (!"entity_resolution".equals(q.kind()) || q.subject() == null) {
				continue;
			}
			Optional<Entity> exact = entities.byRef(q.subject());
			if (exact.isEmpty() || exact.get().id() == entities.owner().id()) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("question", q.ref());
			m.put("subject", q.subject());
			m.put("entity", exact.get().ref());
			if (!dryRun) {
				Optional<Observation> src = q.observationId() == null ? Optional.empty() : observation(q.observationId());
				if (src.isPresent() && q.payload() != null) {
					Applied a = apply(src.get(), Proposal.parse(q.payload()), Map.of(q.subject(), exact.get()));
					m.put("facts", a.facts().stream().map(FactOut::id).toList());
				}
				questions.answer(q.id(), exact.get().ref());
			}
			settled.add(m);
		}
		return settled;
	}

	private Map<String, Object> resolvePredicate(Question q, String choice, Observation obs) {
		Proposal held = Proposal.parse(q.payload());
		FactRef f = held.facts().getFirst();
		Proposal toApply;
		if ("new".equalsIgnoreCase(choice)) {
			PredicateDef def = held.predicates().isEmpty() ? null : held.predicates().getFirst();
			if (def == null) {
				throw MnemicException.invalidArgument(q.ref() + " holds no definition to register.");
			}
			predicates.register(def, q.observationId());
			toApply = new Proposal(held.specVersion(), held.entities(), List.of(), List.of(f), List.of());
		} else {
			Predicate target = predicates.get(choice).orElseThrow(() -> MnemicException.invalidArgument(
					"'" + choice + "' is not a registered predicate; answer with the candidate or \"new\"."));
			// A confirmed synonym is asked once: the proposed name becomes an alias (J2). An ambiguous match
			// confirmed for this fact stays a one-off (J3).
			boolean similar = q.candidates().stream()
					.anyMatch(cd -> target.name().equals(cd.get("id")) && "similar".equals(cd.get("match")));
			if (similar) {
				predicates.addAlias(target, f.predicate());
			}
			FactRef re = new FactRef(f.subject(), target.name(), f.object(), f.qualifier(), f.scope(), f.validTime(),
					f.ended(), f.derivedFrom(), f.derivation(), f.callerConfidence());
			toApply = new Proposal(held.specVersion(), held.entities(), List.of(), List.of(re), List.of());
		}
		Observation source = q.observationId() == null ? obs : observation(q.observationId()).orElse(obs);
		Applied a = apply(source, toApply);
		questions.answer(q.id(), choice);
		var m = new LinkedHashMap<String, Object>();
		m.put("status", "answered");
		m.put("facts", a.facts().stream().map(FactOut::id).toList());
		return m;
	}

	private Map<String, Object> resolveConflict(Question q, String choice, Observation obs) {
		Map<String, Object> payload = Json.readMap(q.payload());
		long existingId = Long.parseLong(payload.get("existing").toString().substring(2));
		long pendingId = Long.parseLong(payload.get("pending").toString().substring(2));
		var m = new LinkedHashMap<String, Object>();
		switch (choice.toLowerCase(Locale.ROOT)) {
		case "ended" -> db.write(tx -> {
			Fact existing = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", existingId).orElseThrow());
			close(tx, existing, null, "supersession", "user: earlier value ended", null, obs.id(), null, null,
					"current");
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			return null;
		});
		case "supersede" -> db.write(tx -> {
			Fact existing = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", existingId).orElseThrow());
			String today = obs.observedAt().toString().substring(0, 10);
			close(tx, existing, pendingId, "supersession", "user: replaced", null, obs.id(), today, "day",
					"superseded");
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			return null;
		});
		case "wrong" -> db.write(tx -> {
			// Not a change over time, an error caught at the conflict: the earlier fact is corrected by the new one,
			// the same shape correct() leaves behind, so history reads the same either way (2026-09-10).
			tx.update("UPDATE fact SET status = 'corrected', superseded_by = ? WHERE id = ?", pendingId, existingId);
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			tx.insert("INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, "
					+ "closed_at, recorded_at) VALUES (?,?,'correction',?,NULL,?,NULL,?)", existingId, pendingId,
					"user: the earlier record was wrong", obs.id(), Instant.now().toString());
			return null;
		});
		case "reject", "reinterpret" -> db.write(tx -> {
			tx.update("UPDATE fact SET status = 'rejected' WHERE id = ?", pendingId);
			tx.insert("""
			          INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id,
			                                   closed_at, recorded_at) VALUES (?,NULL,'invalidation',?,NULL,?,NULL,?)""",
					pendingId, "user: " + choice, obs.id(), Instant.now().toString());
			return null;
		});
		default -> throw MnemicException.invalidArgument(
				"'" + choice + "' is not an answer to " + q.ref() + "; use ended, supersede, reject, reinterpret, or wrong.");
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		m.put("existing", "f-" + existingId);
		m.put("pending", "f-" + pendingId);
		return m;
	}

	// ── consolidation (EXTRACTION.md, Layer 3) ───────────────────────────

	public Consolidated consolidate(boolean dryRun) {
		var merges = new ArrayList<Map<String, Object>>();
		for (long[] pair : entities.duplicateAliasPairs()) {
			Entity a = entities.get(pair[0]).orElseThrow();
			Entity b = entities.get(pair[1]).orElseThrow();
			if (a.id() == b.id()) {
				continue;
			}
			if (dryRun) {
				merges.add(Map.of("would_merge", b.ref(), "into", a.ref(), "reason", "shared alias"));
			} else {
				merges.add(entities.merge(b.id(), a.id(), null, "consolidate: shared alias"));
			}
		}
		int reclosed = 0;
		for (Row r : db.read(tx -> tx.query("""
		                                    SELECT * FROM fact WHERE status = 'current' AND ended = 1 AND valid_end IS NULL AND object_id IS NOT NULL"""))) {
			Fact f = Fact.from(r);
			Optional<Event> closing = closingEvent(f.predicate(), f.subjectId(), f.objectId());
			if (closing.isPresent() && closing.get().validStart() != null) {
				reclosed++;
				if (!dryRun) {
					Event ev = closing.get();
					db.write(tx -> {
						close(tx, f, null, "event", "consolidate: closing event found", ev.id(), ev.observationId(),
								ev.validStart(), ev.validStartPrecision(), "current");
						return null;
					});
				}
			}
		}
		List<Map<String, Object>> resolvedQuestions = settleExactSubjects(dryRun);
		// Duplicates from before the identity rules learned free-text qualifiers and undated events (2026-09-11):
		// the same fact worded twice becomes one fact with a corroboration, the same event with and without its
		// date becomes the dated one.
		var duplicates = new ArrayList<Map<String, Object>>();
		for (Row r : db.read(tx -> tx.query("""
		                                    SELECT a.id AS keep, b.id AS drop_id FROM fact a JOIN fact b ON b.subject_id = a.subject_id
		                                    AND b.predicate = a.predicate AND COALESCE(b.object_id, -1) = COALESCE(a.object_id, -1)
		                                    AND COALESCE(lower(b.object_text), '') = COALESCE(lower(a.object_text), '')
		                                    AND COALESCE(b.scope_id, -1) = COALESCE(a.scope_id, -1) AND b.mode = a.mode AND b.id > a.id
		                                    WHERE a.status = 'current' AND b.status = 'current' AND a.ended = 0 AND b.ended = 0
		                                    AND a.valid_end IS NULL AND b.valid_end IS NULL ORDER BY a.id, b.id"""))) {
			long keep = r.lng("keep");
			long drop = r.lng("drop_id");
			Fact kept = get(keep).orElse(null);
			Fact dropped = get(drop).orElse(null);
			if (kept == null || dropped == null || !kept.current() || !dropped.current()) {
				continue; // folded already in this pass
			}
			Predicate pred = predicates.get(kept.predicate()).orElse(null);
			if (pred == null || !freeQualifier(pred)) {
				continue;
			}
			boolean takeWording = fuller(dropped.qualifier(), kept.qualifier());
			duplicates.add(Map.of(dryRun ? "would_fold" : "folded", dropped.ref(), "into", kept.ref(), "reason",
					"the same fact, its qualifier worded differently" + (takeWording ? "; the fuller wording of " + dropped.ref() + " is kept" : "")));
			if (!dryRun) {
				db.write(tx -> {
					tx.update("UPDATE fact SET status = 'corrected', superseded_by = ? WHERE id = ?", keep, drop);
					tx.update("UPDATE fact SET corroborations = corroborations + 1 WHERE id = ?", keep);
					if (takeWording) {
						tx.update("UPDATE fact SET qualifier = ? WHERE id = ?", dropped.qualifier(), keep);
						rerenderOne(tx, keep);
					}
					link(tx, keep, dropped.observationId(), "corroborated");
					tx.insert("""
					          INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, closed_at,
					                                   recorded_at) VALUES (?,?,'duplicate',?,NULL,?,NULL,?)""", drop, keep,
							"consolidate: the same fact worded twice", dropped.observationId(), Instant.now().toString());
					return null;
				});
			}
		}
		for (Row r : db.read(tx -> tx.query("""
		                                    SELECT a.id AS first, b.id AS second FROM event a JOIN event b ON b.type = a.type AND b.id > a.id
		                                    WHERE a.valid_start IS NULL OR b.valid_start IS NULL OR a.valid_start = b.valid_start ORDER BY a.id, b.id"""))) {
			Optional<Event> a = event(r.lng("first"));
			Optional<Event> b = event(r.lng("second"));
			if (a.isEmpty() || b.isEmpty() || a.get().participants().size() != b.get().participants().size()
					|| !a.get().participants().containsAll(b.get().participants())) {
				continue;
			}
			// The dated one stays; between two alike, the earlier.
			Event keep = a.get().validStart() == null && b.get().validStart() != null ? b.get() : a.get();
			Event drop = keep == a.get() ? b.get() : a.get();
			duplicates.add(Map.of(dryRun ? "would_fold" : "folded", drop.ref(), "into", keep.ref(), "reason",
					"the same event, " + (drop.validStart() == null ? "undated" : "dated the same")));
			if (!dryRun) {
				db.write(tx -> {
					tx.update("UPDATE fact SET event_id = ? WHERE event_id = ?", keep.id(), drop.id());
					tx.update("UPDATE supersession SET event_id = ? WHERE event_id = ?", keep.id(), drop.id());
					tx.update("DELETE FROM event_participant WHERE event_id = ?", drop.id());
					tx.update("DELETE FROM event WHERE id = ?", drop.id());
					return null;
				});
			}
		}
		return new Consolidated(merges, reclosed, predicates.frequentExtended(3), resolvedQuestions, review(5), duplicates);
	}

	/** Re-renders every fact under a predicate after its template changed (EVALUATION.md J5). */
	/** Every fact, under every predicate: after a migration, so no rendering predates its template. */
	public int rerenderAll() {
		int n = 0;
		for (Predicate p : predicates.all()) {
			n += rerender(p.name());
		}
		return n;
	}

	/** Whether a free-text qualifier says more than the one on record: longer, and not merely a re-casing. */
	static boolean fuller(String candidate, String current) {
		if (candidate == null || candidate.isBlank()) {
			return false;
		}
		if (current == null || current.isBlank()) {
			return true;
		}
		return candidate.strip().length() > current.strip().length() && !candidate.equalsIgnoreCase(current);
	}

	/** One fact's rendering recomputed from its columns; the new rendering. */
	private String rerenderOne(Tx tx, long factId) {
		Fact f = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", factId).orElseThrow());
		Predicate p = predicates.get(f.predicate()).orElseThrow();
		String objectName = f.objectId() != null ? nameIn(tx, f.objectId()) : f.objectText();
		Bounds b = new Bounds(f.validStart(), f.validStartPrecision(), f.startSource(), f.validEnd(), f.validEndPrecision(), f.endSource());
		String rendering = renderFact(p, f.mode(), nameIn(tx, f.subjectId()), objectName,
				f.scopeId() == null ? null : nameIn(tx, f.scopeId()), f.qualifier()) + (f.believed() ? lang.believed() : "")
				+ b.suffix(f.ended(), lang);
		tx.update("UPDATE fact SET rendering = ? WHERE id = ?", rendering, f.id());
		return rendering;
	}

	public int rerender(String predicate) {
		Predicate p = predicates.get(predicate)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + predicate));
		return db.write(tx -> {
			int n = 0;
			for (Row r : tx.query("SELECT * FROM fact WHERE predicate = ?", p.name())) {
				Fact f = Fact.from(r);
				String objectName = f.objectId() != null ? nameIn(tx, f.objectId()) : f.objectText();
				Bounds b = new Bounds(f.validStart(), f.validStartPrecision(), f.startSource(), f.validEnd(),
						f.validEndPrecision(), f.endSource());
				String rendering = renderFact(p, f.mode(), nameIn(tx, f.subjectId()), objectName,
						f.scopeId() == null ? null : nameIn(tx, f.scopeId()), f.qualifier())
						+ (f.believed() ? lang.believed() : "") + b.suffix(f.ended(), lang);
				tx.update("UPDATE fact SET rendering = ? WHERE id = ?", rendering, f.id());
				n++;
			}
			return n;
		});
	}

	private static String nameIn(Tx tx, long entityId) {
		return tx.queryOne("SELECT name FROM entity WHERE id = ?", entityId).map(r -> r.str("name"))
				.orElse("ent-" + entityId);
	}

	// ── consistency helpers ──────────────────────────────────────────────

	private List<Fact> otherCurrentValues(
			Tx tx, long subjectId, Predicate pred, Entity obj, String objText,
			Entity scope) {
		String scopeClause = "scope".equals(pred.functionalScope()) ? " AND COALESCE(scope_id, -1) = ?" : "";
		var args = new ArrayList<Object>(List.of(subjectId, pred.name(), obj == null ? -1 : obj.id(),
				objText == null ? "" : objText.toLowerCase(Locale.ROOT)));
		if (!scopeClause.isEmpty()) {
			args.add(scope == null ? -1 : scope.id());
		}
		return tx.query("""
		                SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND valid_end IS NULL
		                AND mode = 'asserted' AND NOT (COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?)""" + scopeClause + " ORDER BY id",
				args.toArray()).stream().map(Fact::from).toList();
	}

	/** The event type a question names, registered or merely stored (EventTypeRegistry.cue). */
	public Optional<String> eventCue(List<String> tokens) {
		List<String> stored = db.read(tx -> tx.query("SELECT DISTINCT type FROM event").stream()
				.map(r -> r.str("type")).toList());
		return eventTypes.cue(tokens, stored);
	}

	public List<Event> eventsOfType(long entityId, String type) {
		return eventsOf(entityId).stream().filter(ev -> ev.type().equalsIgnoreCase(type)).toList();
	}

	/** Whether an event type opens, closes, or supersedes facts of the predicate (its effect on that key). */
	public boolean eventTouches(String eventType, String predicate) {
		return eventTypes.get(eventType)
				.map(t -> t.opens().contains(predicate) || t.closes().contains(predicate) || t.supersedes()
						.contains(predicate)).orElse(false);
	}

	private boolean supersedes(String eventType, String predicate) {
		return eventTypes.get(eventType).map(t -> t.supersedes().contains(predicate)).orElse(false);
	}

	/** The fact's interval begins after the event's date: the event cannot have ended it. */
	private static boolean startsAfter(Fact f, Bounds b) {
		return f.validStart() != null && b.start != null && f.validStart().compareTo(b.start) > 0;
	}

	private static boolean disjoint(Fact other, Bounds b) {
		if (other.validEnd() != null && b.start != null && b.start.compareTo(other.validEnd()) >= 0) {
			return true;
		}
		return b.end != null && other.validStart() != null && b.end.compareTo(other.validStart()) <= 0;
	}

	private static Map<String, Object> supersededOut(Fact other, long byId, Event event) {
		var m = new LinkedHashMap<String, Object>();
		m.put("fact_id", other.ref());
		m.put("predicate", other.predicate());
		m.put("rendering", other.rendering());
		m.put("superseded_by", "f-" + byId);
		m.put("closed_at", event == null ? null : Bounds.show(event.validStart(), event.validStartPrecision()));
		m.put("event", event == null ? null : event.ref());
		return m;
	}

	/** Closes a fact: writes the end bound, marks the status, records the supersession, re-renders. */
	private void close(
			Tx tx, Fact fact, Long byId, String kind, String reason, Long eventId, Long obsId,
			String end, String endPrecision, String newStatus) {
		String rendering = fact.rendering().replaceAll(" \\((" + Lang.suffixWords() + ")[^)]*\\)$", "")
				.replaceAll(" \\(\\d{4}[^)]*\\)$", "");
		Bounds b = new Bounds(fact.validStart(), fact.validStartPrecision(), fact.startSource(),
				end != null ? end : fact.validEnd(), end != null ? endPrecision : fact.validEndPrecision(),
				end != null ? kind : fact.endSource());
		tx.update("""
		          UPDATE fact SET status = ?, superseded_by = ?, valid_end = ?, valid_end_precision = ?, end_source = ?,
		          ended = 1, rendering = ? WHERE id = ?""", newStatus, byId, b.end, b.endPrecision, b.endSource,
				rendering + b.suffix(true, lang), fact.id());
		tx.insert("""
		          INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, closed_at,
		                                   recorded_at) VALUES (?,?,?,?,?,?,?,?)""", fact.id(), byId, kind, reason,
				eventId, obsId, b.end, Instant.now().toString());
	}

	// ── event effects (DECISIONS.md §2.10) ──────────────────────────────

	private void applyEventEffects(
			long eventId, String type, List<Entity> participants, Bounds b, Observation obs,
			List<Map<String, Object>> superseded) {
		Optional<EventType> et = eventTypes.get(type);
		if (et.isEmpty() || participants.isEmpty()) {
			return;
		}
		Entity subject = participants.getFirst();
		db.write(tx -> {
			for (String pred : et.get().closes()) {
				for (int i = 1; i < participants.size(); i++) {
					for (Row r : tx.query("""
					                      SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
					                      AND valid_end IS NULL AND (object_id = ? OR scope_id = ?)""", subject.id(),
							pred, participants.get(i).id(), participants.get(i).id())) {
						Fact f = Fact.from(r);
						if (startsAfter(f, b)) {
							continue; // an event closes only a fact whose interval it falls inside (2026-09-10)
						}
						close(tx, f, null, "event", null, eventId, obs.id(), b.start, b.startPrecision, "current");
						superseded.add(closedOut(f, eventId, b));
					}
				}
			}
			if (et.get().endsEntity()) {
				tx.update("UPDATE entity SET existed_end = ? WHERE id = ?", b.start, subject.id());
				for (Row r : tx.query("""
				                      SELECT * FROM fact WHERE subject_id = ? AND status = 'current' AND valid_end IS NULL
				                      AND ended = 0""", subject.id())) {
					Fact f = Fact.from(r);
					if (startsAfter(f, b)) {
						continue;
					}
					close(tx, f, null, "entity_ended", type + " " + Bounds.show(b.start, b.startPrecision), eventId,
							obs.id(), b.start, b.startPrecision, "current");
					superseded.add(closedOut(f, eventId, b));
				}
			}
			return null;
		});
	}

	private static Map<String, Object> closedOut(Fact f, long eventId, Bounds b) {
		var m = new LinkedHashMap<String, Object>();
		m.put("fact_id", f.ref());
		m.put("predicate", f.predicate());
		m.put("rendering", f.rendering());
		m.put("ended_at", Bounds.show(b.start, b.startPrecision));
		m.put("event", "evt-" + eventId);
		return m;
	}

	/** An event on record of this type with exactly these participants, undated or dated the same. */
	private Optional<Event> sameEvent(String type, List<Entity> participants, String start) {
		List<Long> ids = participants.stream().map(Entity::id).toList();
		List<Long> candidates = db.read(tx -> tx.query(
				"SELECT id FROM event WHERE type = ? AND (valid_start IS NULL OR ? IS NULL OR valid_start = ?) ORDER BY id",
				type, start, start)).stream().map(r -> r.lng("id")).toList();
		for (long id : candidates) {
			Optional<Event> e = event(id);
			if (e.isPresent() && e.get().participants().size() == ids.size() && e.get().participants().containsAll(ids)) {
				return e;
			}
		}
		return Optional.empty();
	}

	private Optional<Event> closingEvent(String predicate, long subjectId, long objectId) {
		for (EventType t : eventTypes.all()) {
			if (!t.closes().contains(predicate)) {
				continue;
			}
			List<Event> found = db.read(tx -> events(tx, tx.query("""
			                                                      SELECT e.* FROM event e
			                                                      JOIN event_participant s ON s.event_id = e.id AND s.entity_id = ?
			                                                      JOIN event_participant o ON o.event_id = e.id AND o.entity_id = ?
			                                                      WHERE e.type = ? ORDER BY e.id""", subjectId,
					objectId, t.name())));
			if (!found.isEmpty()) {
				return Optional.of(found.getFirst());
			}
		}
		return Optional.empty();
	}

	// ── corrections and history ──────────────────────────────────────────

	public Corrected correct(long factId, Map<String, Object> replacement, String reason, Observation correction) {
		Fact original = get(factId).orElseThrow(() -> MnemicException.notFound("No fact f-" + factId));
		// A superseded fact is history, and history can be wrong: an event that closed it at the wrong date is
		// undone by correcting its valid_time (2026-09-10). Only a fact already corrected or rejected is refused.
		if (!original.current() && !"superseded".equals(original.status())) {
			throw MnemicException.conflict("f-" + factId + " is " + original.status() + ", not current; correct " + (
					original.supersededBy() != null ? "f-" + original.supersededBy()
							: "the current fact") + " instead.", Map.of("status", original.status()));
		}
		db.write(tx -> tx.update("UPDATE fact SET status = 'corrected' WHERE id = ?", factId));
		String subject = str(replacement, "subject",
				original.subjectId() == entities.owner().id() ? "self" : entityName(original.subjectId()));
		String object = str(replacement, "object",
				original.objectId() != null ? entityName(original.objectId()) : original.objectText());
		String qualifier = str(replacement, "qualifier", original.qualifier());
		String scope = str(replacement, "scope", original.scopeId() == null ? null : entityName(original.scopeId()));
		ValidTime vt = replacement.get("valid_time") instanceof Map<?, ?> m ? new ValidTime(str(m, "start", null),
				str(m, "end", null), str(m, "precision", null))
				: (original.validStart() == null && original.validEnd() == null ? null
						: new ValidTime(original.validStart(), original.validEnd(), original.validStartPrecision()));
		Boolean ended =
				replacement.containsKey("ended") ? Boolean.TRUE.equals(replacement.get("ended")) : original.ended();
		Double callerConfidence = replacement.containsKey("caller_confidence")
				? (replacement.get("caller_confidence") == null ? null : ((Number) replacement.get("caller_confidence")).doubleValue())
				: original.callerConfidence();
		var ref = new FactRef(subject, original.predicate(), object, qualifier, scope, vt, ended, List.of(),
				new Proposal.Derivation("explicit"), callerConfidence, "negated".equals(original.mode()) ? Boolean.TRUE : null,
				"only".equals(original.mode()) ? Boolean.TRUE : null);
		Applied a = apply(correction,
				new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref), List.of()));
		if (a.facts().isEmpty()) {
			db.write(tx -> tx.update("UPDATE fact SET status = 'current' WHERE id = ?", factId));
			throw MnemicException.invalidArgument(
					"The correction produced no fact: " + String.join("; ", a.warnings()) + (a.questions().isEmpty()
							? "" : " " + a.questions()));
		}
		long newId = Long.parseLong(a.facts().getFirst().id().substring(2));
		db.write(tx -> {
			tx.update("UPDATE fact SET superseded_by = ? WHERE id = ?", newId, factId);
			tx.insert("""
			          INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, closed_at,
			                                   recorded_at) VALUES (?,?,'correction',?,NULL,?,NULL,?)""", factId, newId,
					reason, correction.id(), Instant.now().toString());
			return null;
		});
		return new Corrected(get(factId).orElseThrow(), get(newId).orElseThrow());
	}

	/**
	 * A fact that was never true (2026-09-11, D7): marked corrected with no replacement, the reason recorded as
	 * a retraction, so it leaves recall and stays in history. A superseded fact can be retracted too.
	 */
	public Corrected retract(long factId, String reason, Observation correction) {
		Fact original = get(factId).orElseThrow(() -> MnemicException.notFound("No fact f-" + factId));
		if (!original.current() && !"superseded".equals(original.status())) {
			throw MnemicException.conflict("f-" + factId + " is " + original.status() + ", not current.", Map.of("status", original.status()));
		}
		db.write(tx -> {
			tx.update("UPDATE fact SET status = 'corrected', superseded_by = NULL WHERE id = ?", factId);
			tx.insert("""
			          INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, closed_at,
			                                   recorded_at) VALUES (?,NULL,'retraction',?,NULL,?,NULL,?)""", factId,
					reason == null || reason.isBlank() ? "user: never true" : reason, correction.id(), Instant.now().toString());
			return null;
		});
		return new Corrected(get(factId).orElseThrow(), null);
	}

	private static String str(Map<?, ?> m, String key, String dflt) {
		Object v = m.get(key);
		return v == null ? dflt : v.toString();
	}

	public List<Supersession> supersessionsOf(long factId) {
		return db.read(tx -> tx.query("SELECT * FROM supersession WHERE fact_id = ? ORDER BY id", factId).stream()
				.map(Supersession::from).toList());
	}

	public History history(long entityId, String predicate) {
		List<Fact> facts = db.read(tx -> (predicate == null ? tx.query("""
		                                                               SELECT * FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ? ORDER BY id""",
				entityId, entityId, entityId) : tx.query("""
		                                                 SELECT * FROM fact WHERE (subject_id = ? OR object_id = ? OR scope_id = ?) AND predicate = ?
		                                                 ORDER BY id""", entityId, entityId, entityId,
				predicate)).stream().map(Fact::from).toList());
		var entries = new ArrayList<HistoryEntry>();
		for (Fact f : facts) {
			entries.add(new HistoryEntry(f, supersessionsOf(f.id())));
		}
		List<Tombstone> tombstones = db.read(tx -> tx.query("""
		                                                    SELECT o.id, o.forgotten_at FROM forgotten_link l JOIN observation o ON o.id = l.observation_id
		                                                    WHERE l.entity_id = ? AND o.forgotten_at IS NOT NULL ORDER BY o.id""",
				entityId).stream().map(r -> new Tombstone(r.lng("id"), r.str("forgotten_at"))).toList());
		return new History(entries, tombstones);
	}

	// ── helpers ──────────────────────────────────────────────────────────

	/** Resolves a reference; null when the name is held behind an entity question (created here if needed). */
	private Entity resolveRef(
			String ref, Map<String, Entity> refs, Observation obs, Proposal p,
			List<Map<String, Object>> qs) {
		if (ref == null || ref.isBlank()) {
			throw MnemicException.invalidArgument("an entity reference is empty.");
		}
		if (refs.containsKey(ref)) {
			return refs.get(ref);
		}
		if (refs.containsKey(Names.norm(ref))) {
			return refs.get(Names.norm(ref));
		}
		if (EntityService.SELF.contains(Names.norm(ref))) {
			return entities.owner();
		}
		if (REF_SHAPE.matcher(ref).matches()) {
			// "e7" with no such entity in the proposal: a dangling reference, not a person called e7.
			throw MnemicException.invalidArgument("'" + ref + "' refers to no entity in this proposal.");
		}
		Resolved r = entities.resolve(ref, null, List.of(), obs.id(),
				distinctFrom(refs, entities.exactIds(ref, List.of(), null)));
		if (r.ambiguous()) {
			var er = new EntityRef(null, ref, null, List.of());
			Question q = entityQuestion(obs, ref, null, r.candidates(), heldProposal(p, er));
			qs.add(q.toMap());
			refs.put(ref, null);
			refs.put(Names.norm(ref), null);
			return null;
		}
		refs.put(ref, r.entity());
		refs.put(Names.norm(ref), r.entity());
		return r.entity();
	}

	private Optional<Observation> observation(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", id)
				.map(r -> new Observation(r.lng("id"), r.str("text"),
						new se.hirt.mnemic.observation.Source(r.str("source_kind"), r.str("source_ref"),
								r.intOrNull("source_chunk"), r.str("assistant"), r.str("session")),
						Instant.parse(r.str("observed_at")), Instant.parse(r.str("recorded_at")),
						r.str("proposal_json"), r.intOrNull("spec_version"), r.str("forgotten_at") != null)));
	}

	private static String derivationKind(FactRef f, Observation obs, List<String> warnings) {
		String given = f.derivation() == null ? null : f.derivation().kind();
		String sourceKind = obs.source().kind();
		boolean userSaid = "user".equals(sourceKind) || "correction".equals(sourceKind);
		if (given == null) {
			return userSaid ? "explicit" : "extracted";
		}
		String k = given.toLowerCase(Locale.ROOT);
		if ("derived".equals(k)) {
			warnings.add("derivation.kind 'derived' is set only by Mnemic; recorded as 'inferred'.");
			return "inferred";
		}
		if ("explicit".equals(k) && !userSaid) {
			warnings.add(
					"derivation.kind 'explicit' is only valid for a user source; this is a " + sourceKind + " source, recorded as 'extracted'.");
			return "extracted";
		}
		if (!List.of("explicit", "extracted", "inferred").contains(k)) {
			warnings.add("Unknown derivation.kind '" + given + "'; recorded as 'inferred'.");
			return "inferred";
		}
		return k;
	}

	// ── temporal normalisation ───────────────────────────────────────────

	record Bounds(String start, String startPrecision, String startSource, String end, String endPrecision,
	              String endSource) {
		String suffix(boolean ended) {
			return suffix(ended, Lang.EN);
		}

		String suffix(boolean ended, Lang lang) {
			if (start != null && end != null) {
				return " (" + show(start, startPrecision) + " – " + show(end, endPrecision) + ")";
			}
			if (start != null) {
				return ended ? lang.fromEnded(show(start, startPrecision)) : lang.since(show(start, startPrecision));
			}
			if (end != null) {
				return lang.until(show(end, endPrecision));
			}
			return ended ? lang.ended() : "";
		}

		static String show(String iso, String precision) {
			if (iso == null) {
				return "?";
			}
			return switch (precision == null ? "" : precision) {
				case "year" -> iso.substring(0, 4);
				case "month" -> iso.substring(0, 7);
				default -> iso.length() >= 10 ? iso.substring(0, 10) : iso;
			};
		}
	}

	static Bounds bounds(ValidTime vt, Instant observedAt, List<String> warnings) {
		if (vt == null) {
			return new Bounds(null, null, null, null, null, null);
		}
		String[] s = bound(vt.start(), vt.precision(), observedAt, warnings);
		String[] e = bound(vt.end(), vt.precision(), observedAt, warnings);
		return new Bounds(s[0], s[1], s[2], e[0], e[1], e[2]);
	}

	static String[] bound(String text, String precision, Instant observedAt, List<String> warnings) {
		if (text == null || text.isBlank() || "unknown".equalsIgnoreCase(text) || "null".equals(text)) {
			return new String[] {null, null, null};
		}
		String t = text.trim();
		if (t.matches("\\d{4}")) {
			return new String[] {t + "-01-01", precisionOr(precision, "year"), "stated"};
		}
		if (t.matches("\\d{4}-\\d{2}")) {
			return new String[] {t + "-01", precisionOr(precision, "month"), "stated"};
		}
		if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
			return new String[] {t, precisionOr(precision, "day"), "stated"};
		}
		if (t.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
			return new String[] {t, precisionOr(precision, "instant"), "stated"};
		}
		String[] relative = relative(words(t.toLowerCase(Locale.ROOT)), observedAt);
		if (relative != null) {
			return relative;
		}
		warnings.add(
				"valid_time '" + t + "' is not an ISO date (YYYY, YYYY-MM, YYYY-MM-DD) or a simple relative " + "expression (N years/months/days ago, last year/month, yesterday). Bound ignored; resolve it " + "against the observation date and give an honest precision, or use \"unknown\".");
		return new String[] {null, null, null};
	}

	private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("one", 1), Map.entry("a", 1),
			Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
			Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
			Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("fifteen", 15), Map.entry("twenty", 20));

	private static String words(String t) {
		var sb = new StringBuilder();
		for (String w : t.split("\\s+")) {
			Integer n = WORDS.get(w);
			sb.append(n != null ? n.toString() : w).append(' ');
		}
		return sb.toString().trim();
	}

	private static String[] relative(String t, Instant observedAt) {
		LocalDate base =
				observedAt == null ? LocalDate.now(ZoneOffset.UTC) : observedAt.atZone(ZoneOffset.UTC).toLocalDate();
		Matcher m = AGO.matcher(t);
		if (m.find()) {
			int n = Integer.parseInt(m.group(1));
			return switch (m.group(2)) {
				case "year" -> new String[] {base.minusYears(n).getYear() + "-01-01", "year", "resolved"};
				case "month" -> new String[] {base.minusMonths(n).withDayOfMonth(1).toString(), "month", "resolved"};
				case "week" -> new String[] {base.minusWeeks(n).toString(), "day", "resolved"};
				default -> new String[] {base.minusDays(n).toString(), "day", "resolved"};
			};
		}
		m = LAST.matcher(t);
		if (m.find()) {
			return switch (m.group(1)) {
				case "year" -> new String[] {base.minusYears(1).getYear() + "-01-01", "year", "resolved"};
				case "month" -> new String[] {base.minusMonths(1).withDayOfMonth(1).toString(), "month", "resolved"};
				default -> new String[] {base.minusWeeks(1).toString(), "day", "resolved"};
			};
		}
		if (t.equals("yesterday")) {
			return new String[] {base.minusDays(1).toString(), "day", "resolved"};
		}
		if (t.equals("today") || t.equals("now")) {
			return new String[] {base.toString(), "day", "resolved"};
		}
		if (t.equals("this year")) {
			return new String[] {base.getYear() + "-01-01", "year", "resolved"};
		}
		return null;
	}

	private static String precisionOr(String given, String inferred) {
		return given == null || given.isBlank() ? inferred : given.toLowerCase(Locale.ROOT);
	}

	// ── span anchoring ───────────────────────────────────────────────────

	static int[] span(String text, String needle) {
		if (text == null || needle == null || needle.isBlank()) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		int at = lower.indexOf(needle.toLowerCase(Locale.ROOT));
		if (at < 0) {
			String[] words = needle.split("\\s+");
			at = lower.indexOf(words[words.length - 1].toLowerCase(Locale.ROOT));
			if (at < 0) {
				return null;
			}
		}
		int start = 0;
		for (int i = at - 1; i >= 0; i--) {
			if (SENTENCE_END.matcher(String.valueOf(text.charAt(i))).matches()) {
				start = i + 1;
				break;
			}
		}
		int end = text.length();
		var m = SENTENCE_END.matcher(text);
		if (m.find(at)) {
			end = Math.min(text.length(), m.end());
		}
		while (start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		return new int[] {start, end};
	}

	// ── queries ──────────────────────────────────────────────────────────

	public Optional<Fact> get(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM fact WHERE id = ?", id).map(Fact::from));
	}

	public List<Fact> factsOf(long entityId) {
		return db.read(tx -> tx.query("""
		                              SELECT * FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ?
		                              ORDER BY CASE status WHEN 'current' THEN 0 ELSE 1 END, id""", entityId, entityId,
				entityId).stream().map(Fact::from).toList());
	}

	public List<Fact> probe(long entityId, String predicate, Instant asOf, Instant now, boolean includeHistory) {
		List<Row> rows = db.read(tx -> tx.query("""
				SELECT f.*, o.observed_at AS obs_observed_at FROM fact f JOIN observation o ON o.id = f.observation_id
				WHERE f.predicate = ? AND f.status IN ('current', 'superseded', 'corrected') AND f.mode = 'asserted'
				AND (f.subject_id = ? OR f.object_id = ?) ORDER BY f.id""", predicate, entityId, entityId));
		var out = new ArrayList<Fact>();
		if (asOf == null) {
			for (Row r : rows) {
				Fact f = Fact.from(r);
				String state = f.state(now);
				// Future facts come back too: the caller says "not yet" rather than "no such fact".
				if ("current".equals(state) || "future".equals(state) || (includeHistory && !"pending".equals(f.status()))) {
					out.add(f);
				}
			}
			return out;
		}
		Predicate p = predicates.get(predicate).orElse(null);
		boolean functional = p != null && p.functional();
		boolean timeless = p != null && "low".equals(p.volatility());
		List<Fact> all = rows.stream().map(Fact::from).toList();
		boolean endedPredecessors = all.stream().anyMatch(f -> f.ended() || f.validEnd() != null);
		for (Row r : rows) {
			Fact f = Fact.from(r);
			if ("corrected".equals(f.status()) || !f.mayHoldAt(asOf)) {
				continue;
			}
			if (!knownBy(f, r.str("obs_observed_at"), asOf, timeless)) {
				continue;
			}
			boolean openNow = f.current() && !f.ended() && f.validEnd() == null;
			if (functional && openNow && f.validStart() == null && endedPredecessors) {
				continue;
			}
			out.add(f);
		}
		return out;
	}

	/**
	 * A fact with no valid start says nothing about when it began. For a timeless predicate (low volatility:
	 * born_in, parent_of) that is fine at any date; for a volatile one (lives_in observed in 2021) the earliest
	 * date it is known to hold is the observation date, so it does not enter a 2010 view. Valid time still decides
	 * whenever it is stated (DECISIONS.md §6): a fact dated 2010–2018 and observed in 2026 is found for 2015.
	 */
	static boolean knownBy(Fact f, String observedAt, Instant asOf, boolean timeless) {
		if (f.validStart() != null || timeless || observedAt == null) {
			return true;
		}
		if (f.ended() || f.validEnd() != null) {
			return true; // history with unknown bounds may well have held earlier (EVALUATION.md C9)
		}
		return observedAt.compareTo(asOf.toString()) <= 0;
	}

	public List<Fact> factsOfObservation(long observationId) {
		return db.read(tx -> tx.query("SELECT * FROM fact WHERE observation_id = ? ORDER BY id", observationId).stream()
				.map(Fact::from).toList());
	}

	public List<Fact> lexical(String match, Instant asOf, Instant now, boolean includeHistory, int limit) {
		if (match == null || match.isBlank()) {
			return List.of();
		}
		List<Row> rows = db.read(tx -> tx.query("""
				SELECT f.*, o.observed_at AS obs_observed_at FROM fact_fts x JOIN fact f ON f.id = x.rowid
				JOIN observation o ON o.id = f.observation_id
				WHERE fact_fts MATCH ? AND f.status IN ('current', 'superseded', 'corrected')
				ORDER BY bm25(fact_fts), f.id DESC LIMIT ?""", match, limit * 3));
		var out = new ArrayList<Fact>();
		for (Row r : rows) {
			Fact f = Fact.from(r);
			boolean ok;
			if (asOf != null) {
				boolean timeless = predicates.get(f.predicate()).map(p -> "low".equals(p.volatility())).orElse(false);
				ok = !"corrected".equals(f.status()) && f.mayHoldAt(asOf) && knownBy(f, r.str("obs_observed_at"), asOf,
						timeless);
			} else {
				ok = "current".equals(f.state(now)) || (includeHistory && !"pending".equals(f.status()));
			}
			if (ok) {
				out.add(f);
				if (out.size() >= limit) {
					break;
				}
			}
		}
		return out;
	}

	/** Events whose rendering matches the query, within the valid-time window: keys like facts (V005). */
	public List<Event> lexicalEvents(String match, Instant asOf, int limit) {
		if (match == null || match.isBlank()) {
			return List.of();
		}
		String day = asOf == null ? null : asOf.toString().substring(0, 10);
		return db.read(tx -> events(tx, tx.query("""
		                                         SELECT e.* FROM event_fts x JOIN event e ON e.id = x.rowid
		                                         WHERE event_fts MATCH ? ORDER BY bm25(event_fts), e.id DESC LIMIT ?""",
						match, limit * 2))).stream()
				.filter(ev -> day == null || ev.validStart() == null || ev.validStart().compareTo(day) <= 0)
				.limit(limit).toList();
	}

	/** The owner's current facts, most corroborated first, for the briefing (EVALUATION.md F10). */
	public List<Fact> briefingFacts(long entityId, Instant now, int limit) {
		// Orientation first: the facts that place a person (one current value: works_at, lives_in, holds_role,
		// spouse_of), then the lasting relations (born_in, parent_of, sibling_of), then everything else; within a
		// group the best corroborated. Insertion order put half-siblings and a 3D printer before the job (2026-09-10).
		return factsOf(entityId).stream().filter(f -> "current".equals(f.state(now)) && f.asserted())
				.sorted(java.util.Comparator.comparingInt((Fact f) -> briefingRank(f.predicate()))
						.thenComparing(Fact::corroborations, java.util.Comparator.reverseOrder())
						.thenComparing(Fact::id)).limit(limit).toList();
	}

	private int briefingRank(String predicate) {
		Predicate p = predicates.get(predicate).orElse(null);
		if (p == null || p.isExtended()) {
			return 3;
		}
		if (p.functional()) {
			return 0;
		}
		if ("low".equals(p.volatility()) && p.sameType()) {
			return 1;
		}
		return 2;
	}

	public List<Event> eventsOf(long entityId) {
		return db.read(tx -> events(tx, tx.query("""
		                                         SELECT e.* FROM event e JOIN event_participant p ON p.event_id = e.id WHERE p.entity_id = ?
		                                         ORDER BY e.id""", entityId)));
	}

	public List<Event> eventsOfObservation(long observationId) {
		return db.read(
				tx -> events(tx, tx.query("SELECT * FROM event WHERE observation_id = ? ORDER BY id", observationId)));
	}

	public Optional<Event> event(long id) {
		return db.read(tx -> {
			List<Event> l = events(tx, tx.query("SELECT * FROM event WHERE id = ?", id));
			return l.isEmpty() ? Optional.empty() : Optional.of(l.getFirst());
		});
	}

	private static List<Event> events(Tx tx, List<Row> rows) {
		var out = new ArrayList<Event>();
		for (Row r : rows) {
			List<Long> parts = tx.query("SELECT entity_id FROM event_participant WHERE event_id = ? ORDER BY entity_id",
					r.lng("id")).stream().map(x -> x.lng("entity_id")).toList();
			out.add(Event.from(r, parts));
		}
		return out;
	}

	public String entityName(long id) {
		return entities.get(id).map(Entity::name).orElse("ent-" + id);
	}

	public String sourceKind(Fact f) {
		return db.read(tx -> tx.queryOne("SELECT source_kind FROM observation WHERE id = ?", f.observationId())
				.map(r -> r.str("source_kind")).orElse("user"));
	}

	/**
	 * The open facts longest without confirmation on predicates that age, oldest first: what a session should
	 * confirm or end before trusting the rest (2026-09-10, "confirm five things rather than trust eighty").
	 * {@code likely_changed} is set past the predicate's staleness threshold.
	 */
	private java.time.Clock clock = java.time.Clock.systemUTC();

	/** The engine's clock, so that what is due or stale follows the same time as recall (tests fix it). */
	public void useClock(java.time.Clock clock) {
		this.clock = clock;
	}

	public List<Map<String, Object>> review(int limit) {
		Instant now = clock.instant();
		var out = new ArrayList<Map<String, Object>>();
		// Plans past their date first: "was the car collected?" is the question a session should open with.
		for (Row r : db.read(tx -> tx.query("""
		                                    SELECT * FROM fact WHERE status = 'current' AND ended = 0 AND valid_start IS NOT NULL
		                                    AND last_confirmed < valid_start ORDER BY valid_start ASC, id ASC"""))) {
			Fact f = Fact.from(r);
			if (!f.due(now)) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", f.ref());
			m.put("rendering", f.rendering());
			m.put("due", true);
			m.put("planned_for", f.validStart());
			m.put("confirmed", f.lastConfirmed().substring(0, 10));
			m.put("age_days", Duration.between(Instant.parse(f.lastConfirmed()), now).toDays());
			m.put("likely_changed", false);
			out.add(m);
			if (out.size() >= limit) {
				return out;
			}
		}
		for (Row r : db.read(tx -> tx.query("""
		                                    SELECT * FROM fact WHERE status = 'current' AND ended = 0 AND valid_end IS NULL
		                                    ORDER BY last_confirmed ASC, id ASC """))) {
			Fact f = Fact.from(r);
			Predicate p = predicates.get(f.predicate()).orElse(null);
			if (p == null || !p.ages() || "future".equals(f.state(now))) {
				continue;
			}
			Instant confirmed = Instant.parse(f.lastConfirmed());
			long days = Duration.between(confirmed, now).toDays();
			if (days < 14 || days * 3 < p.stalenessDays()) {
				continue; // said this fortnight, or well inside what its predicate tolerates: nothing to confirm yet
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", f.ref());
			m.put("rendering", f.rendering());
			m.put("confirmed", confirmed.toString().substring(0, 10));
			m.put("age_days", days);
			m.put("likely_changed", days > p.stalenessDays());
			out.add(m);
			if (out.size() >= limit) {
				break;
			}
		}
		return out;
	}

	/**
	 * The place-hierarchy sanity check (2026-09-10, "Mattias owns Switzerland"): when a new fact's object contains,
	 * through current {@code located_in} facts, the object of a current fact with the same subject and predicate,
	 * the new fact is almost certainly a restriction written as ownership. Returns the contained object.
	 */
	private Optional<Entity> containsAnotherObject(Entity subject, Predicate pred, Entity object) {
		if ("located_in".equals(pred.name()) || "part_of".equals(pred.name())) {
			return Optional.empty();
		}
		List<Long> siblings = db.read(tx -> tx.query("""
		                                             SELECT object_id FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
		                                             AND object_id IS NOT NULL AND object_id <> ?""", subject.id(), pred.name(), object.id()))
				.stream().map(r -> r.lng("object_id")).toList();
		for (long sibling : siblings) {
			long at = sibling;
			for (int hop = 0; hop < 4; hop++) {
				final long from = at;
				Optional<Long> up = db.read(tx -> tx.queryOne("""
				                                              SELECT object_id FROM fact WHERE subject_id = ? AND predicate = 'located_in' AND status = 'current'
				                                              AND object_id IS NOT NULL LIMIT 1""", from)).map(r -> r.lng("object_id"));
				if (up.isEmpty()) {
					break;
				}
				if (up.get() == object.id()) {
					return entities.get(sibling);
				}
				at = up.get();
			}
		}
		return Optional.empty();
	}

	public double confidence(Fact f) {
		double base = switch (sourceKind(f)) {
			case "user", "correction" -> 0.80;
			case "conversation" -> 0.75;
			case "document" -> 0.70;
			default -> 0.60;
		};
		if ("inferred".equals(f.derivationKind())) {
			base -= 0.15;
		}
		base += 0.05 * Math.min(4, f.corroborations() - 1);
		if (f.callerConfidence() != null) {
			base = Math.min(base, f.callerConfidence()); // the caller can lower it, never raise it
		}
		return Math.min(0.98, Math.round(base * 100) / 100.0);
	}

	public long count() {
		return db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM fact WHERE status = 'current'"));
	}

	public void forgetDerived(long observationId) {
		forgetDerived(observationId, false);
	}

	/**
	 * {@code keepEntities}: leave the entities this observation created in place, with their ids and aliases, so
	 * that forgetting and re-remembering the same text (a re-seed) does not renumber a town every other fact
	 * points at (2026-09-10). The default removes what nothing else references, which is what forgetting for
	 * privacy needs (EVALUATION.md family O).
	 */
	public void forgetDerived(long observationId, boolean keepEntities) {
		db.write(tx -> {
			tx.update("""
			          INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
			          SELECT observation_id, subject_id FROM fact WHERE observation_id = ?""", observationId);
			tx.update("""
			          INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
			          SELECT observation_id, object_id FROM fact WHERE observation_id = ? AND object_id IS NOT NULL""",
					observationId);
			tx.update(
					"UPDATE question SET status = 'dismissed', answer = 'forgotten' WHERE observation_id = ? " + "AND status = 'open'",
					observationId);
			// A fact this observation stated but another also said survives, re-homed to the earliest of the others,
			// with one corroboration fewer (schema 16): forgetting a conversation forgets what only it said.
			for (Row r : tx.query("SELECT id FROM fact WHERE observation_id = ?", observationId)) {
				long factId = r.lng("id");
				List<Long> others = observationsOf(tx, factId).stream().filter(o -> o != observationId).toList();
				if (!others.isEmpty()) {
					tx.update("UPDATE fact SET observation_id = ?, corroborations = MAX(1, corroborations - 1) WHERE id = ?",
							others.getFirst(), factId);
				}
			}
			tx.update("DELETE FROM fact_source WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM fact WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM event WHERE observation_id = ?", observationId);
			return null;
		});
		if (!keepEntities) {
			entities.removeOrphansCreatedBy(observationId);
		}
	}
}
