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

import se.hirt.mnemic.knowledge.EntityService.Resolved;
import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.ClosureRef;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventRef;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.proposal.Proposal.ValidTime;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a validated proposal into rows (EXTRACTION.md, Layer 2): predicates and entities are resolved, domain and range
 * checked, valid time normalised with its provenance, the rendering computed, the span anchored, a restatement
 * corroborated, a conflict detected, and an event's effects applied. What Mnemic cannot decide it asks: an ambiguous
 * entity or predicate keeps the whole fact in the question and nothing is stored until the caller answers
 * (EVALUATION.md B2, J3); an unexplained conflict stores the new fact as {@code pending}, linked to its question (C2,
 * D3). Corrections and retractions go through the same path so history reads the same either way.
 */
public final class FactService {

	/** What a proposal produced. Every id is reported so the caller can refer to it later. */
	public record Applied(List<EntityOut> entities, List<EventOut> events, List<FactOut> facts,
			List<PredicateOut> predicates, List<Map<String, Object>> questions, List<String> warnings,
			List<Map<String, Object>> superseded, List<Map<String, Object>> definitions) {
		public static final Applied NOTHING = new Applied(List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of());

		public Applied withWarning(String warning) {
			var all = new ArrayList<>(warnings);
			all.add(warning);
			return new Applied(entities, events, facts, predicates, questions, all, superseded, definitions);
		}
	}

	public record EntityOut(String ref, String id, String name, String resolution, double score) {
	}

	public record EventOut(String ref, String id, String type) {
	}

	public record FactOut(String id, String predicate, String rendering, String status, boolean corroborated) {
	}

	public record PredicateOut(String proposed, String resolution, String id) {
	}

	/** A correction's outcome; {@code replacement} is null for a retraction. */
	public record Corrected(Fact original, Fact replacement) {
	}

	/** The resolved parts of one fact to store. */
	record Operands(Entity subject, Predicate predicate, Entity object, String objectText, String qualifier,
			Entity scope, String mode) {
		long objectId() {
			return object == null ? -1 : object.id();
		}

		String objectTextKey() {
			return objectText == null ? "" : objectText.toLowerCase(Locale.ROOT);
		}

		long scopeId() {
			return scope == null ? -1 : scope.id();
		}

		String objectName() {
			return object != null ? object.name() : objectText;
		}

		boolean asserted() {
			return "asserted".equals(mode);
		}
	}

	/** The state of one proposal being applied: the refs bound so far and everything reported back. */
	private static final class Application {
		final Observation obs;
		final Proposal p;
		final Map<String, Entity> refs = new LinkedHashMap<>();
		final Map<String, PredicateDef> defs = new HashMap<>();
		final Map<String, Long> eventIds = new HashMap<>();
		final List<String> warnings = new ArrayList<>();
		final List<Map<String, Object>> questions = new ArrayList<>();
		final List<Map<String, Object>> superseded = new ArrayList<>();
		final List<EntityOut> entityOut = new ArrayList<>();
		final List<EventOut> eventOut = new ArrayList<>();
		final List<FactOut> factOut = new ArrayList<>();
		final List<PredicateOut> predicateOut = new ArrayList<>();
		/** Event types and entity types the proposal defined: {@code {kind, name, resolution}}. */
		final List<Map<String, Object>> definitions = new ArrayList<>();

		Application(Observation obs, Proposal p, Map<String, Entity> bound) {
			this.obs = obs;
			this.p = p;
			for (Map.Entry<String, Entity> e : bound.entrySet()) {
				bind(e.getKey(), e.getValue());
			}
			for (PredicateDef d : p.predicates()) {
				defs.put(d.name(), d);
			}
		}

		/** Binds a name (and its normalised form) to an entity, or to null while it is held behind a question. */
		void bind(String name, Entity e) {
			refs.put(name, e);
			refs.put(Names.norm(name), e);
		}

		void ask(Question q) {
			questions.add(q.toMap());
		}

		Applied result() {
			return new Applied(entityOut, eventOut, factOut, predicateOut, questions, warnings, superseded,
					definitions);
		}
	}

	private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\n]");
	private static final Pattern REF_SHAPE = Pattern.compile("(?i)(e|ev|ent|evt)[-_]?\\d+");
	/** An object that is a clause about the world, not a plan or an option. */
	private static final Pattern RECOLLECTION = Pattern.compile("^(?:that|whether|if)\\b", Pattern.CASE_INSENSITIVE);

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EventService events;
	private final QuestionService questions;
	private final FactQueries queries;
	private final FactQuestions asks;
	private final FactRenderer renderer;
	private final FactLedger ledger;
	private final ConflictCheck conflicts;
	private final EntityTypeRegistry types;
	private final Lang lang;

	/**
	 * Ids of the entities the proposal being applied declares. Two entities declared together are distinct by
	 * assertion, so neither is offered as an ambiguity for the other's name. Set for the duration of one
	 * {@link #apply}; the engine is single-threaded per store, and nested applies save and restore it.
	 */
	private Set<Long> declaredInProposal = Set.of();

	FactService(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EventService events, QuestionService questions, FactQueries queries, FactQuestions asks,
			FactRenderer renderer, FactLedger ledger, EntityTypeRegistry types) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.events = events;
		this.questions = questions;
		this.queries = queries;
		this.asks = asks;
		this.renderer = renderer;
		this.ledger = ledger;
		this.types = types;
		this.conflicts = new ConflictCheck(eventTypes, types);
		this.lang = renderer.lang();
	}

	// ── apply a proposal ─────────────────────────────────────────────────

	public Applied apply(Observation obs, Proposal p) {
		return apply(obs, p, Map.of());
	}

	/** {@code bound} pre-binds names to entities: the answers to entity questions. */
	public Applied apply(Observation obs, Proposal p, Map<String, Entity> bound) {
		Set<Long> outer = declaredInProposal;
		try {
			var declared = new HashSet<Long>();
			for (EntityRef er : p.entities()) {
				declared.addAll(entities.exactIds(er.name(), er.aliases(), er.type()));
			}
			declaredInProposal = declared;
			var a = new Application(obs, p, bound);
			registerVocabulary(a);
			resolveEntities(a);
			List<FactRef> opened = applyEvents(a);
			applyFacts(a, opened);
			applyClosures(a);
			return a.result();
		} finally {
			declaredInProposal = outer;
		}
	}

	/**
	 * Everything a name must not be confused with: the proposal's other declared entities and those already resolved.
	 */
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

	/**
	 * Vocabulary the proposal defines goes first, so the entities and events that follow can use it. A definition that
	 * names an unknown predicate or parent is skipped with a warning; an existing name is left as it is.
	 */
	private void registerVocabulary(Application a) {
		for (EntityTypeDef d : a.p.entityTypes()) {
			try {
				boolean known = d.name() != null && types.get(d.name().replace(' ', '_')).isPresent();
				a.definitions.add(definition("entity_type", types.register(d, a.obs.id()).name(), known));
			} catch (MnemicException e) {
				skipped(a, e, "Entity type '" + d.name() + "'");
			}
		}
		for (EventTypeDef d : a.p.eventTypes()) {
			try {
				boolean known = d.name() != null && eventTypes.get(d.name().replace(' ', '_')).isPresent();
				var t = eventTypes.register(d, a.obs.id(), name -> predicates.get(name).isPresent());
				a.definitions.add(definition("event_type", t.name(), known));
			} catch (MnemicException e) {
				skipped(a, e, "Event type '" + d.name() + "'");
			}
		}
	}

	private static Map<String, Object> definition(String kind, String name, boolean known) {
		var m = new LinkedHashMap<String, Object>();
		m.put("kind", kind);
		m.put("name", name);
		m.put("resolution", known ? "exists" : "registered");
		return m;
	}

	private static void skipped(Application a, MnemicException e, String what) {
		if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
			throw e;
		}
		a.warnings.add(what + " skipped: " + e.getMessage());
	}

	private void resolveEntities(Application a) {
		for (EntityRef er : a.p.entities()) {
			if (er.name() == null || er.name().isBlank()) {
				a.warnings.add(
						"An entity without a 'name' was skipped" + (er.ref() != null ? " (ref " + er.ref() + ")" : "")
								+ "; facts that refer to it are skipped too.");
				continue;
			}
			String key = er.ref() != null ? er.ref() : er.name();
			if (a.refs.containsKey(Names.norm(er.name()))) {
				Entity e = a.refs.get(Names.norm(er.name()));
				if (er.ref() != null) {
					a.refs.put(er.ref(), e);
				}
				if (e != null) { // null: the same name is already held behind an entity question
					a.entityOut.add(new EntityOut(key, e.ref(), e.name(), "bound", 1.0));
				}
				continue;
			}
			Resolved r;
			try {
				r = entities.resolve(er.name(), er.type(), er.aliases(), a.obs.id(),
						distinctFrom(a.refs, entities.exactIds(er.name(), er.aliases(), er.type())));
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				a.warnings.add("Entity '" + er.name() + "' skipped: " + e.getMessage());
				continue;
			}
			if (r.ambiguous()) {
				// Everything that mentions this entity is held behind one question (EVALUATION.md B2).
				a.ask(asks.entity(a.obs, er.name(), er.type(), r.candidates(), FactQuestions.heldProposal(a.p, er)));
				a.refs.put(key, null);
				a.refs.put(Names.norm(er.name()), null);
				continue;
			}
			a.refs.put(key, r.entity());
			a.refs.put(Names.norm(er.name()), r.entity());
			a.entityOut.add(new EntityOut(key, r.entity().ref(), r.entity().name(), r.how(), r.score()));
		}
	}

	/**
	 * Stores the events, applies their effects, and returns the facts their types open that the proposal did not state.
	 */
	private List<FactRef> applyEvents(Application a) {
		var opened = new ArrayList<FactRef>();
		for (EventRef ev : a.p.events()) {
			if (ev.type() == null || ev.type().isBlank()) {
				a.warnings.add("An event without a 'type' was skipped.");
				continue;
			}
			List<Entity> participants;
			try {
				participants = participants(a, ev);
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				a.warnings.add("Event '" + ev.type() + "' skipped: " + e.getMessage());
				continue;
			}
			if (participants == null) {
				continue; // waits with the entity question
			}
			Bounds b = Bounds.of(ev.validTime(), a.obs.observedAt(), a.warnings);
			String type = ev.type().trim().toLowerCase(Locale.ROOT);
			String rendering = type + "(" + String.join(", ", participants.stream().map(Entity::name).toList()) + ")"
					+ b.suffix(false, Lang.EN);
			EventService.Stored stored = events.store(type, participants, b, rendering, a.obs);
			String key = ev.ref() != null ? ev.ref() : "evt-" + stored.id();
			a.eventIds.put(key, stored.id());
			a.eventOut.add(new EventOut(key, "evt-" + stored.id(), type));
			if (stored.effects()) {
				events.applyEffects(stored.id(), type, participants, b, a.obs, a.superseded);
			}
			opened.addAll(openedBy(a, ev, type, participants, key));
		}
		return opened;
	}

	/** The event's participants, or null when one is held behind an entity question. */
	private List<Entity> participants(Application a, EventRef ev) {
		var participants = new ArrayList<Entity>();
		for (String ref : ev.participants()) {
			Entity e = resolveRef(a, ref);
			if (e == null) {
				return null;
			}
			participants.add(e);
		}
		return participants;
	}

	/**
	 * The facts an event type opens (purchased → owns, joined → works_at) between the first participant and each other
	 * one, unless the proposal stated one of them. A type that opens several predicates opens the first whose types
	 * fit; a participant of the wrong type is left alone.
	 */
	private List<FactRef> openedBy(Application a, EventRef ev, String type, List<Entity> participants, String key) {
		Optional<EventType> et = eventTypes.get(type);
		if (et.isEmpty() || participants.size() < 2 || et.get().opens().isEmpty()) {
			return List.of();
		}
		var opened = new ArrayList<FactRef>();
		Entity subj = participants.getFirst();
		for (int i = 1; i < participants.size(); i++) {
			Entity obj = participants.get(i);
			boolean stated = a.p.facts().stream().anyMatch(f -> et.get().opens().contains(f.predicate())
					&& bound(a.refs, f.subject()) == subj.id() && bound(a.refs, f.object()) == obj.id());
			if (stated) {
				continue;
			}
			for (String pred : et.get().opens()) {
				Predicate pr = predicates.get(pred).orElse(null);
				if (pr == null || pr.literalRange() || !pr.acceptsSubject(types.lineage(subj.type()))
						|| !pr.acceptsObject(types.lineage(obj.type()))) {
					continue;
				}
				opened.add(new FactRef(ev.participants().getFirst(), pred, ev.participants().get(i), null, null,
						ev.validTime(), null, List.of(key), null, null));
				break;
			}
		}
		return opened;
	}

	/**
	 * Containment facts first, since a bound or a closure is checked against where a thing lies and a proposal may
	 * state the cabin's place after the cabin. The output keeps the proposal's order; facts opened by events follow.
	 */
	private void applyFacts(Application a, List<FactRef> opened) {
		var all = new ArrayList<>(a.p.facts());
		all.addAll(opened);
		var order = new ArrayList<Integer>();
		for (int i = 0; i < all.size(); i++) {
			String pr = all.get(i).predicate();
			if ("located_in".equals(pr) || "part_of".equals(pr)) {
				order.add(i);
			}
		}
		for (int i = 0; i < all.size(); i++) {
			if (!order.contains(i)) {
				order.add(i);
			}
		}
		var outs = new FactOut[all.size()];
		for (int i : order) {
			try {
				outs[i] = applyFact(a, all.get(i)).orElse(null);
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				a.warnings.add("Fact skipped: " + e.getMessage());
			}
		}
		for (FactOut out : outs) {
			if (out != null) {
				a.factOut.add(out);
			}
		}
	}

	private void applyClosures(Application a) {
		for (ClosureRef c : a.p.closures()) {
			try {
				applyClosure(a, c).ifPresent(a.factOut::add);
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				a.warnings.add("Closure skipped: " + e.getMessage());
			}
		}
	}

	// ── one fact ─────────────────────────────────────────────────────────

	private Optional<FactOut> applyFact(Application a, FactRef f) {
		PredicateDef def = a.defs.get(f.predicate());
		PredicateRegistry.Resolution res = predicates.resolve(f.predicate(), def, a.obs.id(), a.warnings);
		if (def != null && a.predicateOut.stream().noneMatch(o -> o.proposed().equals(f.predicate()))) {
			Predicate named = res.predicate() != null ? res.predicate() : res.candidate();
			a.predicateOut.add(new PredicateOut(f.predicate(), res.how(), named == null ? null : named.name()));
		}
		if (res.asks()) {
			a.ask(asks.predicate(a.obs, f, res.candidate(), res.how(), def, FactQuestions.heldProposal(a.p, f, def)));
			return Optional.empty();
		}
		Operands op = operands(a, f, res.predicate());
		if (op == null) {
			return Optional.empty();
		}
		String kind = derivationKind(f, a.obs, a.warnings);
		boolean ended = Boolean.TRUE.equals(f.ended());
		Bounds b = Bounds.of(f.validTime(), a.obs.observedAt(), a.warnings);
		Long eventId = f.derivedFrom().stream().map(a.eventIds::get).filter(Objects::nonNull).findFirst().orElse(null);
		Event event = eventId == null ? null : events.get(eventId).orElse(null);
		if (event != null && b.start() == null && event.validStart() != null) {
			b = b.withStart(event.validStart(), event.validStartPrecision(), "event");
		}
		if (b.end() == null && op.object() != null) {
			Optional<Event> closing = events.closingEvent(op.predicate().name(), op.subject().id(), op.object().id());
			if (closing.isPresent() && closing.get().validStart() != null) {
				b = b.withEnd(closing.get().validStart(), closing.get().validStartPrecision(), "event");
				ended = true;
			}
		}
		Double callerConfidence = f.callerConfidence() == null ? null
				: Math.max(0.0, Math.min(1.0, f.callerConfidence()));
		String base = renderer.sentence(op.predicate(), op.mode(), op.subject().name(), op.objectName(),
				op.scope() == null ? null : op.scope().name(), op.qualifier()) + renderer.believed(callerConfidence);
		String rendering = base + b.suffix(ended, lang);
		int[] span = span(a.obs.text(), op.objectName());
		final Bounds bounds = b;
		final boolean isEnded = ended;
		Stored stored = db.write(tx -> {
			Optional<Row> existing = currentRow(tx, op);
			if (existing.isPresent()) {
				return corroborate(tx, a.obs, op, Fact.from(existing.get()));
			}
			ConflictCheck.Outcome c = conflicts.check(tx, op, bounds, isEnded, event, rendering);
			String rowRendering = c.rowEnded() != isEnded ? base + c.row().suffix(true, lang) : rendering;
			long id = tx.insert("""
					INSERT INTO fact(subject_id, predicate, object_id, object_text, qualifier, scope_id, valid_start,
					                 valid_start_precision, valid_end, valid_end_precision, ended, status,
					                 derivation_kind, observation_id, event_id, span_start, span_end, rendering,
					                 spec_version, caller_confidence, corroborations, last_confirmed, created_at,
					                 start_source, end_source, mode)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?)""", op.subject().id(),
					op.predicate().name(), op.object() == null ? null : op.object().id(), op.objectText(),
					op.qualifier(), op.scope() == null ? null : op.scope().id(), c.row().start(),
					c.row().startPrecision(), c.row().end(), c.row().endPrecision(), c.rowEnded() ? 1 : 0,
					c.pending() ? "pending" : "current", kind, a.obs.id(), eventId, span == null ? null : span[0],
					span == null ? null : span[1], rowRendering, a.obs.specVersion(), callerConfidence,
					a.obs.observedAt().toString(), Instant.now().toString(), c.row().startSource(), c.row().endSource(),
					op.mode());
			FactLedger.link(tx, id, a.obs.id(), "stated");
			for (long[] ask : c.asks()) {
				if (ask[2] < 0) {
					ask[2] = id; // the restriction being stored is the one the containment serves
				}
			}
			for (Fact other : c.toClose()) {
				ledger.close(tx, other, id, "event", null, eventId, a.obs.id(), event.validStart(),
						event.validStartPrecision(), "superseded");
				a.superseded.add(supersededOut(other, id, event));
			}
			return new Stored(new FactOut("f-" + id, op.predicate().name(), rowRendering,
					c.pending() ? "pending" : "current", false), id, c.conflictWith(), c.why(), c.asks());
		});
		if (stored.conflictWith() != null) {
			Question q = asks.conflict(a.obs, op.predicate(), stored.conflictWith(), stored.id(), rendering,
					stored.why());
			questions.linkFact(q.id(), stored.id());
			a.ask(questions.get(q.id()).orElseThrow());
		}
		for (long[] ask : stored.asks()) {
			asks.containment(a.obs, ask[0], ask[1], ask[2]).ifPresent(a::ask);
		}
		return Optional.ofNullable(stored.out());
	}

	/**
	 * What the write of one fact produced; {@code out} is null when the row was the same proposal's own restatement.
	 */
	private record Stored(FactOut out, long id, Fact conflictWith, String why, List<long[]> asks) {
	}

	/**
	 * Resolves subject, object, and scope, and normalises the qualifier; null when a name is held behind a question or
	 * a type mismatch was raised. A literal predicate keeps the object as text, and so does a negated class ("I own
	 * nothing in Sweden") rather than minting an entity called "anything in Sweden".
	 */
	private Operands operands(Application a, FactRef f, Predicate pred) {
		Entity subject = f.subject() == null ? entities.owner() : resolveRef(a, f.subject());
		if (subject == null) {
			return null;
		}
		if (f.object() == null || f.object().isBlank()) {
			throw MnemicException.invalidArgument("fact " + pred.name() + " has no 'object'.");
		}
		String mode = f.mode();
		Entity object = null;
		String objectText = null;
		if (pred.literalRange()) {
			objectText = f.object().trim();
			if (("considering".equals(pred.name()) || "decided".equals(pred.name()))
					&& RECOLLECTION.matcher(objectText).find()) {
				a.warnings.add("'" + pred.name() + "' takes a plan or an option as its object, never a statement: \""
						+ objectText
						+ "\" reads as a recollection. Not stored; store what is recalled as the fact itself, "
						+ "with caller_confidence when unsure.");
				return null;
			}
		} else if ("negated".equals(mode) && !namesAnEntity(f.object(), a.refs)) {
			objectText = f.object().trim();
		} else {
			object = resolveRef(a, f.object());
			if (object == null) {
				return null;
			}
			if ("only".equals(mode) && !types.isA(object.type(), "place")) {
				throw MnemicException.invalidArgument("'only' needs a place as its object (the bound everything lies "
						+ "within); " + object.name() + " is " + object.type() + ".");
			}
			if ("asserted".equals(mode)) {
				warnIfContainsAnotherObject(a, subject, pred, object);
			}
		}
		if (!pred.acceptsSubject(types.lineage(subject.type()))) {
			a.ask(asks.typeMismatch(a.obs, pred, "subject", subject, pred.domain()));
			return null;
		}
		if (object != null && !pred.acceptsObject(types.lineage(object.type()))) {
			a.ask(asks.typeMismatch(a.obs, pred, "object", object, pred.range()));
			return null;
		}
		Entity scope = f.scope() == null ? null : resolveRef(a, f.scope());
		if (f.scope() != null && scope == null) {
			return null;
		}
		return new Operands(subject, pred, object, objectText, qualifier(a, f, pred), scope, mode);
	}

	/** A vocabulary qualifier (mother, half-sister) is normalised; free text keeps the caller's casing. */
	private static String qualifier(Application a, FactRef f, Predicate pred) {
		if (f.qualifier() == null) {
			return null;
		}
		String q = freeQualifier(pred) ? f.qualifier().trim() : f.qualifier().trim().toLowerCase(Locale.ROOT);
		if (!freeQualifier(pred) && !pred.qualifiers().contains(q)) {
			a.warnings.add("Qualifier '" + q + "' is not one of " + pred.qualifiers() + " for " + pred.name()
					+ "; kept as given.");
		}
		if (!pred.render().contains("{qualifier")) {
			a.warnings.add("Qualifier '" + q + "' is stored but " + pred.name() + "'s template has no slot for it, "
					+ "so the rendering will not show it; correct the predicate's render (add [[ ({qualifier})]]) or put "
					+ "the meaning in the observation text.");
		}
		return q;
	}

	/**
	 * A new fact whose object contains, through {@code located_in}, the object of a current fact with the same subject
	 * and predicate is almost certainly a restriction written as ownership ("owns Switzerland"): warn.
	 */
	private void warnIfContainsAnotherObject(Application a, Entity subject, Predicate pred, Entity object) {
		if ("located_in".equals(pred.name()) || "part_of".equals(pred.name())) {
			return;
		}
		List<Long> siblings = db.read(tx -> tx.query("""
				SELECT object_id FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
				AND object_id IS NOT NULL AND object_id <> ?""", subject.id(), pred.name(), object.id())).stream()
				.map(r -> r.lng("object_id")).toList();
		for (long sibling : siblings) {
			if (db.read(tx -> Containment.ancestors(tx, sibling)).contains(object.id())) {
				String inner = entities.nameOf(sibling);
				a.warnings.add("'" + subject.name() + " " + pred.name() + " " + object.name() + "': " + object.name()
						+ " contains " + inner + ", which " + subject.name() + " already " + pred.name()
						+ ". A claim that " + "everything the subject " + pred.name()
						+ " lies within a place is a restriction, not " + pred.name()
						+ " of the place; if that was meant, keep it as text until restrictions are "
						+ "representable.");
				return;
			}
		}
	}

	/** The current row with the same key and mode, whatever its bounds. */
	private static Optional<Row> currentRow(Tx tx, Operands op) {
		return tx.queryOne("""
				SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
				AND COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?
				AND (? = 1 OR COALESCE(qualifier, '') = ?) AND COALESCE(scope_id, -1) = ? AND mode = ?
				ORDER BY id LIMIT 1""", op.subject().id(), op.predicate().name(), op.objectId(), op.objectTextKey(),
				freeQualifier(op.predicate()) ? 1 : 0, op.qualifier() == null ? "" : op.qualifier(), op.scopeId(),
				op.mode());
	}

	/**
	 * A restatement of a fact on record: one row, one more corroboration. Said twice in the same observation (stated,
	 * and opened by its event) it is neither. A fuller free-text qualifier replaces the wording on record.
	 */
	private Stored corroborate(Tx tx, Observation obs, Operands op, Fact e) {
		if (e.observationId() == obs.id()) {
			return new Stored(null, e.id(), null, null, List.of());
		}
		FactLedger.corroborate(tx, e.id(), obs.id(), obs.observedAt().toString());
		String shown = e.rendering();
		if (freeQualifier(op.predicate()) && fuller(op.qualifier(), e.qualifier())) {
			tx.update("UPDATE fact SET qualifier = ? WHERE id = ?", op.qualifier(), e.id());
			shown = renderer.rerender(tx, e.id());
		}
		return new Stored(new FactOut(e.ref(), op.predicate().name(), shown, e.status(), true), e.id(), null, null,
				List.of());
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

	/**
	 * A completeness marker (EVALUATION.md Q9): a fact row of mode {@code closure} whose object text is the class, so
	 * history, corroboration, supersession, and the conflict machinery apply unchanged. Nothing already stored is
	 * questioned by it; a later fact in the class is.
	 */
	private Optional<FactOut> applyClosure(Application a, ClosureRef c) {
		if (c.predicate() == null || c.predicate().isBlank()) {
			throw MnemicException.invalidArgument("a closure needs a 'predicate'.");
		}
		Predicate pred = predicates.get(c.predicate()).orElseThrow(
				() -> MnemicException.invalidArgument("closure over unknown predicate '" + c.predicate() + "'."));
		if (c.type() == null || c.type().isBlank()) {
			throw MnemicException.invalidArgument("a closure needs a 'type' (the class it completes, e.g. place).");
		}
		Entity subject = c.subject() == null ? entities.owner() : resolveRef(a, c.subject());
		if (subject == null) {
			return Optional.empty();
		}
		String type = types.canonical(c.type());
		String rendering = renderer.sentence(pred, "closure", subject.name(), type, null, null);
		String kind = derivationKind(
				new FactRef(c.subject(), c.predicate(), type, null, null, null, null, List.of(), null, null), a.obs,
				a.warnings);
		return Optional.of(db.write(tx -> {
			Optional<Row> existing = tx.queryOne(
					"""
							SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND mode = 'closure'
							AND lower(object_text) = ? ORDER BY id LIMIT 1""",
					subject.id(), pred.name(), type);
			if (existing.isPresent()) {
				Fact e = Fact.from(existing.get());
				FactLedger.corroborate(tx, e.id(), a.obs.id(), a.obs.observedAt().toString());
				return new FactOut(e.ref(), pred.name(), e.rendering(), e.status(), true);
			}
			long id = tx.insert("""
					INSERT INTO fact(subject_id, predicate, object_id, object_text, qualifier, scope_id, ended, status,
					                 derivation_kind, observation_id, rendering, spec_version, corroborations,
					                 last_confirmed, created_at, mode)
					VALUES (?,?,NULL,?,NULL,NULL,0,'current',?,?,?,?,1,?,?,'closure')""", subject.id(), pred.name(),
					type, kind, a.obs.id(), rendering, a.obs.specVersion(), a.obs.observedAt().toString(),
					Instant.now().toString());
			FactLedger.link(tx, id, a.obs.id(), "stated");
			return new FactOut("f-" + id, pred.name(), rendering, "current", false);
		}));
	}

	// ── references ──────────────────────────────────────────────────────

	/** Resolves a reference; null when the name is held behind an entity question (asked here if needed). */
	private Entity resolveRef(Application a, String ref) {
		if (ref == null || ref.isBlank()) {
			throw MnemicException.invalidArgument("an entity reference is empty.");
		}
		if (a.refs.containsKey(ref)) {
			return a.refs.get(ref);
		}
		if (a.refs.containsKey(Names.norm(ref))) {
			return a.refs.get(Names.norm(ref));
		}
		if (EntityService.SELF.contains(Names.norm(ref))) {
			return entities.owner();
		}
		if (REF_SHAPE.matcher(ref).matches()) {
			throw MnemicException.invalidArgument("'" + ref + "' refers to no entity in this proposal.");
		}
		Resolved r = entities.resolve(ref, null, List.of(), a.obs.id(),
				distinctFrom(a.refs, entities.exactIds(ref, List.of(), null)));
		if (r.ambiguous()) {
			var er = new EntityRef(null, ref, null, List.of());
			a.ask(asks.entity(a.obs, ref, null, r.candidates(), FactQuestions.heldProposal(a.p, er)));
			a.bind(ref, null);
			return null;
		}
		a.bind(ref, r.entity());
		return r.entity();
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
		return refs.containsKey(ref) || refs.containsKey(Names.norm(ref))
				|| EntityService.SELF.contains(Names.norm(ref)) || REF_SHAPE.matcher(ref).matches()
				|| !entities.exactIds(ref, List.of(), null).isEmpty();
	}

	/**
	 * Whether the qualifier is wording rather than identity: a predicate without a vocabulary does not key facts by it.
	 */
	static boolean freeQualifier(Predicate p) {
		return p.qualifiers() == null || p.qualifiers().isEmpty();
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

	/** The derivation kind a fact is recorded with, validated against the source (EVALUATION.md E4). */
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
			warnings.add("derivation.kind 'explicit' is only valid for a user source; this is a " + sourceKind
					+ " source, recorded as 'extracted'.");
			return "extracted";
		}
		if (!List.of("explicit", "extracted", "inferred").contains(k)) {
			warnings.add("Unknown derivation.kind '" + given + "'; recorded as 'inferred'.");
			return "inferred";
		}
		return k;
	}

	/** The sentence of the observation that mentions the object, as {start, end} offsets; null when it is not found. */
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

	// ── corrections ─────────────────────────────────────────────────────

	/**
	 * Replaces a fact without destroying history (EVALUATION.md D1): the original is marked {@code corrected}, the
	 * replacement is derived from the correction observation through the same path as any fact, and the two are linked.
	 * A superseded fact can be corrected too, since an event may have closed it at the wrong date.
	 */
	public Corrected correct(long factId, Map<String, Object> replacement, String reason, Observation correction) {
		Fact original = queries.get(factId).orElseThrow(() -> MnemicException.notFound("No fact f-" + factId));
		if (!original.current() && !"superseded".equals(original.status())) {
			throw MnemicException.conflict("f-" + factId + " is " + original.status() + ", not current; correct "
					+ (original.supersededBy() != null ? "f-" + original.supersededBy() : "the current fact")
					+ " instead.", Map.of("status", original.status()));
		}
		db.write(tx -> tx.update("UPDATE fact SET status = 'corrected' WHERE id = ?", factId));
		String subject = str(replacement, "subject",
				original.subjectId() == entities.owner().id() ? "self" : entities.nameOf(original.subjectId()));
		String object = str(replacement, "object",
				original.objectId() != null ? entities.nameOf(original.objectId()) : original.objectText());
		String qualifier = str(replacement, "qualifier", original.qualifier());
		String scope = str(replacement, "scope",
				original.scopeId() == null ? null : entities.nameOf(original.scopeId()));
		ValidTime vt = replacement.get("valid_time") instanceof Map<?, ?> m
				? new ValidTime(str(m, "start", null), str(m, "end", null), str(m, "precision", null))
				: (original.validStart() == null && original.validEnd() == null ? null
						: new ValidTime(original.validStart(), original.validEnd(), original.validStartPrecision()));
		Boolean ended = replacement.containsKey("ended") ? Boolean.TRUE.equals(replacement.get("ended"))
				: original.ended();
		Double callerConfidence = replacement.containsKey("caller_confidence")
				? (replacement.get("caller_confidence") == null ? null
						: ((Number) replacement.get("caller_confidence")).doubleValue())
				: original.callerConfidence();
		var ref = new FactRef(subject, original.predicate(), object, qualifier, scope, vt, ended, List.of(),
				new Proposal.Derivation("explicit"), callerConfidence,
				"negated".equals(original.mode()) ? Boolean.TRUE : null,
				"only".equals(original.mode()) ? Boolean.TRUE : null);
		Applied a = apply(correction,
				new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref), List.of()));
		if (a.facts().isEmpty()) {
			db.write(tx -> tx.update("UPDATE fact SET status = 'current' WHERE id = ?", factId));
			throw MnemicException.invalidArgument("The correction produced no fact: " + String.join("; ", a.warnings())
					+ (a.questions().isEmpty() ? "" : " " + a.questions()));
		}
		long newId = Long.parseLong(a.facts().getFirst().id().substring(2));
		db.write(tx -> {
			tx.update("UPDATE fact SET superseded_by = ? WHERE id = ?", newId, factId);
			FactLedger.supersession(tx, factId, newId, "correction", reason, null, correction.id(), null);
			return null;
		});
		return new Corrected(queries.get(factId).orElseThrow(), queries.get(newId).orElseThrow());
	}

	/**
	 * Withdraws a fact that was never true (EVALUATION.md D7): marked corrected with no replacement, the reason
	 * recorded as a retraction, so it leaves recall and stays in history.
	 */
	public Corrected retract(long factId, String reason, Observation correction) {
		Fact original = queries.get(factId).orElseThrow(() -> MnemicException.notFound("No fact f-" + factId));
		if (!original.current() && !"superseded".equals(original.status())) {
			throw MnemicException.conflict("f-" + factId + " is " + original.status() + ", not current.",
					Map.of("status", original.status()));
		}
		db.write(tx -> {
			tx.update("UPDATE fact SET status = 'corrected', superseded_by = NULL WHERE id = ?", factId);
			FactLedger.supersession(tx, factId, null, "retraction",
					reason == null || reason.isBlank() ? "user: never true" : reason, null, correction.id(), null);
			return null;
		});
		return new Corrected(queries.get(factId).orElseThrow(), null);
	}

	private static String str(Map<?, ?> m, String key, String dflt) {
		Object v = m.get(key);
		return v == null ? dflt : v.toString();
	}

	// ── forgetting ──────────────────────────────────────────────────────

	/**
	 * Removes what an observation produced (EVALUATION.md D2, family O). A fact another observation also stated
	 * survives, re-homed there with one corroboration fewer. {@code keepEntities} leaves the entities the observation
	 * created in place, for a re-seed of the same text; forgetting for privacy removes those nothing else references.
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
			tx.update("UPDATE question SET status = 'dismissed', answer = 'forgotten' WHERE observation_id = ? "
					+ "AND status = 'open'", observationId);
			for (Row r : tx.query("SELECT id FROM fact WHERE observation_id = ?", observationId)) {
				long factId = r.lng("id");
				List<Long> others = FactLedger.observationsOf(tx, factId).stream().filter(o -> o != observationId)
						.toList();
				if (!others.isEmpty()) {
					tx.update(
							"UPDATE fact SET observation_id = ?, corroborations = MAX(1, corroborations - 1) WHERE id = ?",
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
