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
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a validated proposal into rows (EXTRACTION.md, Layer 2): predicates and entities are resolved, domain and range
 * checked, valid time normalised with its provenance, the rendering computed, the span anchored, a restatement
 * corroborated, a conflict detected, and an event's effects applied. What Mnemic cannot decide it asks: an ambiguous
 * entity or predicate keeps the whole fact in the question and nothing is stored until the caller answers
 * (EVALUATION.md B2, J3); an unexplained conflict stores the new fact as {@code pending}, linked to its question (C2,
 * D3). Corrections and retractions go through the same path so history reads the same either way.
 */
public final class FactService {

	/**
	 * What a proposal produced. Every id is reported so the caller can refer to it later; {@code definitions} names the
	 * vocabulary the proposal defined or that was registered from its first use here.
	 */
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
	/** {@code kind}: corrected (a replacement stands), retracted (never true), or retired (a derivation covers it). */
	public record Corrected(Fact original, Fact replacement, String kind) {
	}

	/** What taking an observation's reading back removed: its own facts and events, and the closures it had caused. */
	public record Removed(List<Map<String, Object>> facts, List<Map<String, Object>> events, int reopened) {
		public static final Removed NONE = new Removed(List.of(), List.of(), 0);
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
		/** The events of this proposal by key: their type and participant ids, for a stated fact to find its event. */
		final Map<String, String> eventTypeOf = new HashMap<>();
		final Map<String, List<Long>> eventParticipants = new HashMap<>();
		final List<String> warnings = new ArrayList<>();
		final List<Map<String, Object>> questions = new ArrayList<>();
		final List<Map<String, Object>> superseded = new ArrayList<>();
		final List<EntityOut> entityOut = new ArrayList<>();
		final List<EventOut> eventOut = new ArrayList<>();
		final List<FactOut> factOut = new ArrayList<>();
		/** Attributes given on an entity entry, stated as facts of this observation under the predicates named. */
		final List<FactRef> attributeFacts = new ArrayList<>();
		final List<PredicateOut> predicateOut = new ArrayList<>();
		/** Vocabulary the proposal defined or used for the first time: {@code {kind, name, resolution}}. */
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

		/** Records a registration once per name. */
		void defined(String kind, String name, String resolution) {
			defined(kind, name, resolution, null);
		}

		/** {@code inferred}: what the store assumed about a term registered from use, for the caller to correct. */
		void defined(String kind, String name, String resolution, Map<String, Object> inferred) {
			if (definitions.stream().noneMatch(d -> kind.equals(d.get("kind")) && name.equals(d.get("name")))) {
				var m = definition(kind, name, resolution);
				if (inferred != null) {
					m.put("inferred", inferred);
				}
				definitions.add(m);
			}
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
	private final Deriver deriver;
	private final Lang lang;

	/**
	 * Ids of the entities the proposal being applied declares. Two entities declared together are distinct by
	 * assertion, so neither is offered as an ambiguity for the other's name. Set for the duration of one
	 * {@link #apply}; the engine is single-threaded per store, and nested applies save and restore it.
	 */
	private Set<Long> declaredInProposal = Set.of();

	FactService(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EventService events, QuestionService questions, FactQueries queries, FactQuestions asks,
			FactRenderer renderer, FactLedger ledger, EntityTypeRegistry types, Deriver deriver) {
		this.deriver = deriver;
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
		this.conflicts = new ConflictCheck(eventTypes, types, predicates);
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
	 * names an unknown predicate or parent is skipped with a warning; an existing name is left as it is, unless it was
	 * registered from use and the definition describes it. A predicate definition no fact uses is registered here; one
	 * a fact uses resolves with the fact, so a similar predicate can be asked about with the fact held.
	 */
	private void registerVocabulary(Application a) {
		for (PredicateDef d : a.p.predicates()) {
			if (d.name() == null || d.name().isBlank()) {
				continue;
			}
			Optional<Predicate> before = predicates.get(d.name());
			boolean used = a.p.facts().stream().anyMatch(f -> d.name().equals(f.predicate()));
			try {
				if (before.isPresent() && before.get().isInferred() && !PredicateRegistry.bare(d)) {
					String name = predicates.register(d, a.obs.id()).name();
					var m = definition("predicate", name, "defined");
					m.put("rerendered_facts", renderer.rerender(name));
					if (!before.get().functional() && predicates.get(name).orElseThrow().functional()) {
						m.put("rechecked_conflicts", recheckFunctional(name));
					}
					a.definitions.add(m);
				} else if (before.isPresent() && (d.implies() != null || d.definedAs() != null)) {
					// An existing predicate given rules or implications: taken, and logged as a change of it.
					String name = predicates.register(d, a.obs.id()).name();
					var m = definition("predicate", name, "updated");
					m.put("applied", d.implies() != null && d.definedAs() != null ? List.of("implies", "defined_as")
							: d.implies() != null ? List.of("implies") : List.of("defined_as"));
					if (PredicateRegistry.statesMoreThanAdditions(d)) {
						a.warnings.add("Predicate '" + name + "' is already defined: only implies and defined_as were "
								+ "taken from the definition; change the rest with correct(pred:" + name + ", {...}).");
					}
					a.definitions.add(m);
				} else if (before.isPresent() && !before.get().isInferred()
						&& PredicateRegistry.statesMoreThanAdditions(d)) {
					a.warnings.add("Predicate '" + before.get().name() + "' is already defined; the definition was "
							+ "left as it is. Change it with correct(pred:" + before.get().name() + ", {...}).");
				} else if (before.isEmpty() && !used) {
					PredicateRegistry.Resolution r = predicates.resolve(d.name(), d, a.obs.id(), a.warnings);
					if (r.asks()) {
						a.warnings.add("Predicate '" + d.name() + "' reads like '" + r.candidate().name()
								+ "' and no fact uses it here, so it was not registered; use it in a fact to be asked.");
					} else {
						a.definitions.add(definition("predicate", r.predicate().name(), "registered"));
					}
				}
			} catch (MnemicException e) {
				skipped(a, e, "Predicate '" + d.name() + "'");
			}
		}
		// A type may name as its parent another type defined in the same proposal, in any order.
		for (EntityTypeDef d : parentsFirst(a.p.entityTypes())) {
			try {
				Optional<EntityTypeRegistry.EntityType> before = types.get(d.name());
				String resolution = before.isEmpty() ? "registered"
						: before.get().inferred() && !EntityTypeRegistry.bare(d) ? "defined" : "exists";
				String name = types.register(d, a.obs.id()).name();
				if ("defined".equals(resolution)) {
					settle("type_kind", name);
				}
				a.definitions.add(definition("entity_type", name, resolution));
			} catch (MnemicException e) {
				skipped(a, e, "Entity type '" + d.name() + "'");
			}
		}
		for (EventTypeDef d : a.p.eventTypes()) {
			try {
				Optional<EventType> before = d.name() == null ? Optional.empty()
						: eventTypes.get(d.name().replace(' ', '_'));
				String resolution = before.isEmpty() ? "registered"
						: before.get().inferred() && !EventTypeRegistry.bare(d) ? "defined" : "exists";
				var t = eventTypes.register(d, a.obs.id(), name -> predicates.get(name).isPresent());
				var m = definition("event_type", t.name(), resolution);
				if ("defined".equals(resolution)) {
					settle("event_effect", t.name());
				}
				if ("defined".equals(resolution) && (!t.opens().isEmpty() || !t.closes().isEmpty()
						|| !t.supersedes().isEmpty() || t.endsEntity())) {
					// The events stored under the type get the effects it now has, as an answered question would give.
					m.put("applied", applyEffectsOf(t.name()));
				}
				a.definitions.add(m);
			} catch (MnemicException e) {
				skipped(a, e, "Event type '" + d.name() + "'");
			}
		}
	}

	/**
	 * An entity of a type the registry lacks: the type is registered from this use, and the caller is asked once what
	 * kind of thing it is. Nothing is held; the entity stands whatever the answer.
	 */
	/**
	 * The kinds the proposal's facts expect of an entity: the domains of the predicates it is the subject of and the
	 * ranges of those it is the object of. Empty when any of them takes anything, or nothing uses it.
	 */
	private Set<String> expectedKinds(Application a, EntityRef er) {
		var out = new LinkedHashSet<String>();
		for (FactRef f : a.p.facts()) {
			Predicate p = predicates.get(f.predicate()).orElse(null);
			if (p == null) {
				continue;
			}
			// A fact may lack its subject or object (a model's slip, skipped later with a warning): no pair is built
			// from a null, which Map.entry refuses (a 9B proposer took down a LongMemEval question this way).
			String[] ends = {f.subject(), f.object()};
			List<List<String>> kinds = List.of(p.domain(), p.range());
			for (int i = 0; i < 2; i++) {
				if (ends[i] == null || !FactQuestions.isRef(ends[i], er)) {
					continue;
				}
				if (kinds.get(i).contains("*") || kinds.get(i).contains("literal")) {
					return Set.of();
				}
				out.addAll(kinds.get(i));
			}
		}
		return out;
	}

	private void registerTypeFromUse(Application a, Entity e) {
		registerTypeFromUse(a, e.type(), e);
	}

	/** Registers {@code type} from its use for {@code e}, and asks what kind of thing it is. */
	private void registerTypeFromUse(Application a, String type, Entity e) {
		if (EntityTypeRegistry.UNKNOWN.equals(type) || types.get(type).isPresent()) {
			return;
		}
		EntityTypeRegistry.EntityType t = types.registerInferred(type, a.obs.id());
		var inferred = new LinkedHashMap<String, Object>();
		inferred.put("parent", null);
		inferred.put("type_words", List.of());
		inferred.put("correct", "correct(\"type:" + t.name() + "\", {parent, description, synonyms, type_words})");
		a.defined("entity_type", t.name(), "inferred", inferred);
		asks.typeKind(a.obs, t, e, types.roots()).ifPresent(a::ask);
	}

	/** A definition of the term answers the question that asked what it means. */
	private void settle(String kind, String term) {
		for (Question q : questions.open(200)) {
			if (kind.equals(q.kind()) && term.equals(q.subject())) {
				questions.answer(q.id(), "defined");
			}
		}
	}

	private static Map<String, Object> definition(String kind, String name, String resolution) {
		var m = new LinkedHashMap<String, Object>();
		m.put("kind", kind);
		m.put("name", name);
		m.put("resolution", resolution);
		return m;
	}

	/**
	 * The definitions in an order that registers a parent before the types under it, when the parent is defined in the
	 * same proposal; otherwise the given order. A cycle falls back to the given order for what is left.
	 */
	static List<EntityTypeDef> parentsFirst(List<EntityTypeDef> defs) {
		var out = new ArrayList<EntityTypeDef>();
		var left = new ArrayList<>(defs);
		while (!left.isEmpty()) {
			Set<String> pending = left.stream()
					.map(d -> d.name() == null ? "" : d.name().trim().toLowerCase(Locale.ROOT))
					.collect(java.util.stream.Collectors.toSet());
			List<EntityTypeDef> ready = left.stream()
					.filter(d -> d.parent() == null || d.parent().isBlank()
							|| !pending.contains(d.parent().trim().toLowerCase(Locale.ROOT))
							|| d.parent().trim().equalsIgnoreCase(d.name() == null ? "" : d.name().trim()))
					.toList();
			if (ready.isEmpty()) {
				out.addAll(left);
				break;
			}
			out.addAll(ready);
			left.removeAll(ready);
		}
		return out;
	}

	private static void skipped(Application a, MnemicException e, String what) {
		if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
			throw e;
		}
		a.warnings.add(what + " skipped: " + e.getMessage());
	}

	private void resolveEntities(Application a) {
		for (EntityRef er : a.p.entities()) {
			var attributes = new LinkedHashMap<String, Object>(er.attributes());
			if (er.gender() != null && !er.gender().isBlank()) {
				attributes.putIfAbsent("gender", er.gender().trim());
			}
			for (Map.Entry<String, Object> at : attributes.entrySet()) {
				// The shorthand on the entry is a fact like any other: with provenance, correctable, derivable from.
				if (er.name() == null || at.getValue() == null || String.valueOf(at.getValue()).isBlank()) {
					continue;
				}
				a.attributeFacts.add(new FactRef(er.ref() != null ? er.ref() : er.name(),
						at.getKey().trim().toLowerCase(Locale.ROOT), String.valueOf(at.getValue()).trim(), null, null,
						null, null, List.of(), new Proposal.Derivation("explicit"), null, null, null));
			}
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
						distinctFrom(a.refs, entities.exactIds(er.name(), er.aliases(), er.type())),
						expectedKinds(a, er));
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
			if ("created".equals(r.how())) {
				// Another kind of thing with the same name stays another thing (EVALUATION.md B3), but silently
				// once cost a caller a duplicate car: said, with the way to fold them if they are one.
				List<Entity> twins = entities.homonymsOf(r.entity().id());
				if (!twins.isEmpty()) {
					a.warnings.add("Entity '" + er.name() + "' (" + r.entity().type() + ") was created beside "
							+ String.join(", ",
									twins.stream().map(t -> t.ref() + " '" + t.name() + "' (" + t.type() + ")")
											.toList())
							+ ", another kind of thing with the same name. If they are one, fold it with correct("
							+ r.entity().ref() + ", {\"merge_into\": \"" + twins.getFirst().ref() + "\"}).");
				}
			}
			registerTypeFromUse(a, r.entity());
			String proposedType = types.canonical(er.type());
			if (!"created".equals(r.how()) && !EntityTypeRegistry.UNKNOWN.equals(proposedType)
					&& !EntityTypeRegistry.UNKNOWN.equals(r.entity().type())
					&& !types.sameKind(r.entity().type(), proposedType)) {
				// Matched through a kind nobody has placed: the word registers and is asked about, and the match is
				// said, so a caller who meant another thing can define the kind and try again.
				registerTypeFromUse(a, proposedType, r.entity());
				a.warnings.add("Entity '" + er.name() + "' resolved to " + r.entity().ref() + " (" + r.entity().type()
						+ "): '" + proposedType + "' is a kind nobody has placed, so it cannot tell them apart. If "
						+ "they are two things, define the type with its parent (entity_types) or answer its kind, "
						+ "and remember again.");
			}
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
			String type = EventTypeRegistry.key(ev.type());
			EventType et = eventTypes.get(type).orElse(null);
			if (et == null && !EventTypeRegistry.typeLike(ev.type())) {
				// A sentence where the type goes: kept as the occurrence it describes, never as vocabulary.
				a.warnings.add("Event type '" + ev.type() + "' reads as a description, not a type: stored as a plain "
						+ "occurrence with no effect on facts and not registered. Use a short type (a verb, one or two "
						+ "words) and keep the detail in the observation text.");
			} else if (et == null) {
				et = eventTypes.registerInferred(type, a.obs.id());
				var inferred = new LinkedHashMap<String, Object>();
				inferred.put("render", et.render());
				inferred.put("lexicon", et.lexicon());
				inferred.put("effects", "none");
				inferred.put("correct", "correct(\"event:" + et.name()
						+ "\", {opens, closes, supersedes, ends_entity, render, lexicon, description})");
				a.defined("event_type", et.name(), "inferred", inferred);
			}
			String rendering = events.render(type, participants.stream().map(Entity::name).toList(), b);
			EventService.Stored stored = events.store(type, participants, b, rendering, a.obs);
			String key = ev.ref() != null ? ev.ref() : "evt-" + stored.id();
			a.eventIds.put(key, stored.id());
			a.eventTypeOf.put(key, type);
			a.eventParticipants.put(key, participants.stream().map(Entity::id).toList());
			a.eventOut.add(new EventOut(key, "evt-" + stored.id(), type));
			if (stored.effects()) {
				events.applyEffects(stored.id(), type, participants, b, a.obs, a.superseded);
			}
			opened.addAll(openedBy(a, ev, type, participants, key));
			if (et != null && et.inferred()) {
				asks.eventEffect(a.obs, et, participants, stored.id(), fitting(participants)).ifPresent(a::ask);
			}
		}
		return opened;
	}

	/** The predicates an event between these participants could open or close: those whose types fit them. */
	private List<Predicate> fitting(List<Entity> participants) {
		if (participants.isEmpty()) {
			return List.of();
		}
		List<String> subject = types.lineage(participants.getFirst().type());
		List<String> object = participants.size() < 2 ? null : types.lineage(participants.get(1).type());
		return predicates.all().stream().filter(p -> !p.literalRange() && p.acceptsSubject(subject)
				&& (object == null ? p.range().contains("*") : p.acceptsObject(object))).toList();
	}

	/**
	 * Applies a type's effects to the events already stored under it, after an answer or a correction gave it some: the
	 * facts they open and close, as if the type had been defined when they were remembered.
	 */
	public Map<String, Object> applyEffectsOf(String type) {
		var factsOut = new ArrayList<String>();
		var superseded = new ArrayList<Map<String, Object>>();
		var questions = new ArrayList<Map<String, Object>>();
		int n = 0;
		for (Event ev : events.ofType(type)) {
			Optional<Observation> obs = db
					.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", ev.observationId()))
					.map(Observation::from);
			List<Entity> participants = ev.participants().stream().map(id -> entities.get(id).orElse(null))
					.filter(Objects::nonNull).toList();
			if (obs.isEmpty() || participants.size() != ev.participants().size()) {
				continue;
			}
			var a = new Application(obs.get(),
					new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(), List.of()), Map.of());
			List<String> names = new ArrayList<>();
			for (Entity e : participants) {
				a.bind(e.name(), e);
				names.add(e.name());
			}
			a.eventIds.put(ev.ref(), ev.id());
			Bounds b = new Bounds(ev.validStart(), ev.validStartPrecision(), null, ev.validEnd(),
					ev.validEndPrecision(), null);
			events.applyEffects(ev.id(), type, participants, b, obs.get(), a.superseded);
			applyFacts(a, openedBy(a, new EventRef(ev.ref(), type, names, null), type, participants, ev.ref()));
			factsOut.addAll(a.factOut.stream().map(FactOut::id).toList());
			superseded.addAll(a.superseded);
			questions.addAll(a.questions);
			n++;
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("events", n);
		m.put("facts", factsOut);
		m.put("superseded", superseded);
		if (!questions.isEmpty()) {
			m.put("questions", questions);
		}
		return m;
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
			boolean stated = a.p.facts().stream()
					.anyMatch(f -> f.predicate() != null && et.get().opens().contains(f.predicate())
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
		all.addAll(a.attributeFacts);
		all.addAll(opened);
		var order = new ArrayList<Integer>();
		for (int i = 0; i < all.size(); i++) {
			String pr = all.get(i).predicate();
			if (predicates.isContainment(pr)) {
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
		if ("inferred".equals(res.how())) {
			Predicate p = res.predicate();
			var inferred = new LinkedHashMap<String, Object>();
			inferred.put("domain", p.domain());
			inferred.put("range", p.range());
			inferred.put("direction",
					"either: any subject, any object; state domain and range to fix which side is which");
			inferred.put("functional", p.functional());
			inferred.put("symmetric", p.symmetric());
			inferred.put("volatility", p.volatility());
			inferred.put("lasting", p.lasting());
			inferred.put("lexicon", p.lexicon());
			inferred.put("render", p.render());
			inferred.put("correct", "correct(\"pred:" + p.name()
					+ "\", {domain, range, functional, symmetric, volatility, lasting, lexicon, render, description})");
			a.defined("predicate", p.name(), "inferred", inferred);
			a.warnings.add("Predicate '" + f.predicate() + "' was registered from this use with everything inferred "
					+ "from its name (see definitions); state what you know in 'predicates' or through correct.");
		} else if ("registered".equals(res.how()) && def != null && def.lasting() == null) {
			// A definition that said nothing about lasting: the store assumed, and says what, once, where the
			// definition is answered.
			Predicate p = res.predicate();
			var assumed = new LinkedHashMap<String, Object>();
			assumed.put("assumed", Map.of("lasting", p.lasting()));
			assumed.put("correct", "correct(\"pred:" + p.name() + "\", {\"lasting\": " + !p.lasting() + "})");
			a.defined("predicate", p.name(), "registered", assumed);
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
		Long named = f.derivedFrom().stream().map(a.eventIds::get).filter(Objects::nonNull).findFirst().orElse(null);
		// Stated beside the event that opens it, without naming it: the event explains it all the same.
		final Long eventId = named != null ? named : eventBehind(a, op);
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
			// A participant whose record had already ended when this was said (a death on record): the fact ends
			// with them, as it would have had the death come later, unless the relation is lasting or the fact
			// began after the end (K41).
			if (!c.pending() && c.row().end() == null && !c.rowEnded() && !ledger.lasting(op.predicate().name())) {
				for (Entity who : op.object() == null ? List.of(op.subject()) : List.of(op.subject(), op.object())) {
					Optional<Event> ending = EventService.endingOf(tx, who.id());
					if (ending.isEmpty()
							|| (c.row().start() != null && c.row().start().compareTo(ending.get().validStart()) > 0)) {
						continue;
					}
					Fact fresh = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", id).orElseThrow());
					Bounds at = new Bounds(ending.get().validStart(), ending.get().validStartPrecision(), null, null,
							null, null);
					a.superseded.add(events.closeAgainstEnding(tx, fresh, ending.get().id(), ending.get().type(), at,
							a.obs.id()));
					break;
				}
			}
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
			// The predicate the answer would store: the containment predicate that nests the one kind in the other.
			String within = predicates
					.containmentFor(types.lineage(entities.get(ask[0]).map(Entity::type).orElse("unknown")),
							types.lineage(entities.get(ask[1]).map(Entity::type).orElse("unknown")))
					.map(Predicate::name).orElse(null);
			if (within != null) {
				asks.containment(a.obs, ask[0], ask[1], ask[2], within).ifPresent(a::ask);
			}
		}
		return Optional.ofNullable(stored.out());
	}

	/**
	 * The event in the same proposal that opens this fact's predicate between its subject and object, when the caller
	 * stated the fact beside the event without naming it in {@code derived_from}. Null when there is none.
	 */
	private Long eventBehind(Application a, Operands op) {
		if (op.object() == null) {
			return null;
		}
		for (Map.Entry<String, Long> ev : a.eventIds.entrySet()) {
			List<Long> ids = a.eventParticipants.getOrDefault(ev.getKey(), List.of());
			Optional<EventType> et = eventTypes.get(a.eventTypeOf.get(ev.getKey()));
			if (et.isPresent() && et.get().opens().contains(op.predicate().name()) && !ids.isEmpty()
					&& ids.getFirst() == op.subject().id() && ids.contains(op.object().id())) {
				return ev.getValue();
			}
		}
		return null;
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
		Entity subject = f.subject() == null ? entities.owner() : resolveRef(a, f.subject(), kinds(pred.domain()));
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
			object = resolveRef(a, f.object(), kinds(pred.range()));
			if (object == null) {
				return null;
			}
			if ("only".equals(mode) && !predicates.canContain(types.lineage(object.type()))) {
				throw MnemicException.invalidArgument("'only' needs as its object something things can lie within "
						+ "(the object of a containment predicate: a place, or what a definition marks so); "
						+ object.name() + " is " + object.type() + ".");
			}
			if ("asserted".equals(mode)) {
				warnIfContainsAnotherObject(a, subject, pred, object);
			}
		}
		if (!pred.acceptsSubject(types.lineage(subject.type()))) {
			a.ask(asks.typeMismatch(a.obs, pred, "subject", subject, pred.domain(), newKind(subject),
					FactQuestions.heldProposal(a.p, f, a.defs.get(f.predicate()))));
			return null;
		}
		if (object != null && !pred.acceptsObject(types.lineage(object.type()))) {
			a.ask(asks.typeMismatch(a.obs, pred, "object", object, pred.range(), newKind(object),
					FactQuestions.heldProposal(a.p, f, a.defs.get(f.predicate()))));
			return null;
		}
		Entity scope = f.scope() == null ? null : resolveRef(a, f.scope());
		if (f.scope() != null && scope == null) {
			return null;
		}
		return new Operands(subject, pred, object, objectText, qualifier(a, f, pred), scope, mode);
	}

	/** Whether the entity's type was registered from use and could still be made a kind of something. */
	private boolean newKind(Entity e) {
		return types.get(e.type()).map(EntityTypeRegistry.EntityType::inferred).orElse(false);
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
		if (pred.containment()) {
			return;
		}
		List<Long> siblings = db.read(tx -> tx.query("""
				SELECT object_id FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
				AND object_id IS NOT NULL AND object_id <> ?""", subject.id(), pred.name(), object.id())).stream()
				.map(r -> r.lng("object_id")).toList();
		for (long sibling : siblings) {
			if (db.read(tx -> Containment.ancestors(tx, sibling, predicates.containmentPredicates()))
					.contains(object.id())) {
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
	/** The current stated row a restatement folds into; a derived row (family K) is never it. */
	private static Optional<Row> currentRow(Tx tx, Operands op) {
		return tx.queryOne("""
				SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
				AND derivation_kind <> 'derived'
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
		if (types.get(type).isEmpty()) {
			throw MnemicException.invalidArgument("closure over unknown entity type '" + c.type()
					+ "': a closure completes a class of things, one of "
					+ types.all().stream().map(t -> t.name()).sorted().toList()
					+ "; define a new type under 'entity_types' first.");
		}
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
	/** The kinds a predicate's side expects, or nothing when it takes anything. */
	private static Set<String> kinds(List<String> side) {
		return side.contains("*") || side.contains("literal") ? Set.of() : new LinkedHashSet<>(side);
	}

	private Entity resolveRef(Application a, String ref) {
		return resolveRef(a, ref, Set.of());
	}

	private Entity resolveRef(Application a, String ref, Set<String> expected) {
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
				distinctFrom(a.refs, entities.exactIds(ref, List.of(), null)), expected);
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
	 * After a predicate became functional: where a subject (and scope, when the predicate is functional per scope) has
	 * several open current values, the earliest stands and each later one becomes {@code pending} behind a conflict
	 * question, as it would have had the predicate been functional when it arrived. The count of conflicts.
	 */
	public int recheckFunctional(String name) {
		Predicate p = predicates.get(name).orElseThrow(() -> MnemicException.notFound("No predicate " + name));
		if (!p.functional()) {
			return 0;
		}
		boolean scoped = "scope".equals(p.functionalScope());
		List<Fact> open = db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND valid_end IS NULL AND ended = 0
				AND mode = 'asserted' ORDER BY id""", p.name())).stream().map(Fact::from).toList();
		var first = new HashMap<String, Fact>();
		int n = 0;
		for (Fact f : open) {
			String key = f.subjectId() + (scoped ? "/" + f.scopeId() : "");
			Fact existing = first.putIfAbsent(key, f);
			if (existing == null) {
				continue;
			}
			Observation obs = db.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", f.observationId()))
					.map(Observation::from).orElse(null);
			if (obs == null) {
				continue;
			}
			db.write(tx -> tx.update("UPDATE fact SET status = 'pending' WHERE id = ?", f.id()));
			Question q = asks.conflict(obs, p, existing, f.id(), f.rendering(), null);
			questions.linkFact(q.id(), f.id());
			n++;
		}
		return n;
	}

	/**
	 * Replaces a fact without destroying history (EVALUATION.md D1): the original is marked {@code corrected}, the
	 * replacement is derived from the correction observation through the same path as any fact, and the two are linked.
	 * The correction observation's reading records which fact it corrects (by key, since fact ids do not survive a
	 * rebuild) and the replacement, so a rebuild replays it. A superseded fact can be corrected too, since an event may
	 * have closed it at the wrong date.
	 */
	public Corrected correct(long factId, Map<String, Object> replacement, String reason, Observation correction) {
		Fact original = correctable(factId);
		FactRef ref = readingOf(original, replacement);
		if (sameStatement(ref, readingOf(original, Map.of()))) {
			throw MnemicException.invalidArgument("The correction changes nothing about " + original.ref()
					+ ": every key names the value on record. Name a key with a different value, {\"wrong\": true}, or "
					+ "{\"redundant\": true}.");
		}
		return correctWith(original, ref, reason, correction);
	}

	/**
	 * The fact as a correction reading states it: the original's values, the replacement's over them. With an empty
	 * replacement, the fact as it stands.
	 */
	public FactRef readingOf(Fact original, Map<String, Object> replacement) {
		String subject = str(replacement, "subject",
				original.subjectId() == entities.owner().id() ? "self" : entities.nameOf(original.subjectId()));
		String object = str(replacement, "object",
				original.objectId() != null ? entities.nameOf(original.objectId()) : original.objectText());
		String qualifier = str(replacement, "qualifier", original.qualifier());
		String scope = str(replacement, "scope",
				original.scopeId() == null ? null : entities.nameOf(original.scopeId()));
		// A fact moved to another predicate: the vocabulary refactor a dedicated predicate asks for.
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

	/** Whether two readings state the same fact: the same terms and bounds, whatever the precision noted. */
	public static boolean sameStatement(FactRef a, FactRef b) {
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
		Applied a = apply(correction,
				new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref), List.of()));
		if (a.facts().isEmpty()) {
			db.write(tx -> tx.update("UPDATE fact SET status = 'current' WHERE id = ?", original.id()));
			throw MnemicException.invalidArgument("The correction produced no fact: " + String.join("; ", a.warnings())
					+ (a.questions().isEmpty() ? "" : " " + a.questions()));
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
	public Corrected retract(long factId, String reason, Observation correction) {
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
	 * none covers it at the moment), with the reason on its history. Replayed on rebuild like any correction.
	 */
	public Corrected retire(long factId, String reason, Observation correction) {
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
	public Map<String, Object> reading(String kind, Fact about, String reason, List<FactRef> facts) {
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
	 * Does a correction record's work again from its reading, against whatever fact now matches its key: a rebuild
	 * re-derives the log in order and reaches the record after the observation it corrected. When no current fact
	 * matches, what the user stated still stands: the replacement is stored as a fact of the record, with nothing
	 * marked corrected, and the rebuild reports the record for a person to check. False then.
	 */
	public String replayCorrection(Observation record) {
		Map<String, Object> reading = Json.readMap(record.proposalJson());
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
				apply(record, new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), p.facts(), List.of()));
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
		// A record from before no-op corrections were refused: it stated what stood, and states it again. Nothing to do.
		if (sameStatement(p.facts().getFirst(), readingOf(target.get(), Map.of()))) {
			return "no_op";
		}
		correctWith(target.get(), p.facts().getFirst(), reason, record);
		return "replayed";
	}

	/**
	 * Correction records from before readings were stored on them (2.2) get one from what they did: the supersession
	 * row the record wrote names the original, the reason, and the replacement, and the replacement row, wherever it
	 * lives now (a later fold may have re-homed it), says what the record stated. The count given a reading.
	 * Idempotent: a record with a reading is left alone.
	 */
	public int backfillCorrectionReadings() {
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

	/**
	 * A stored fact as the proposal fragment that stated it: the bounds it was given, not those a later event or
	 * sequence gave it, since those follow from other entries of the log.
	 */
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

	/** The current fact a correction key names, by the names of its subject, object, and scope. */
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
						|| freeQualifier(predicates.get(f.predicate()).orElseThrow()))
				.filter(f -> scope == null ? f.scopeId() == null
						: f.scopeId() != null && Names.norm(entities.nameOf(f.scopeId())).equals(Names.norm(scope)))
				.findFirst();
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
	/** Whether one entity lies within the other on record (a house within its town), in either direction. */
	boolean nested(Tx tx, long a, long b) {
		return conflicts.nested(tx, a, b);
	}

	public Removed forgetDerived(long observationId, boolean keepEntities) {
		var undated = new ArrayList<Long>();
		Removed removed = db.write(tx -> {
			tx.update("""
					INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
					SELECT observation_id, subject_id FROM fact WHERE observation_id = ?""", observationId);
			tx.update("""
					INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
					SELECT observation_id, object_id FROM fact WHERE observation_id = ? AND object_id IS NOT NULL""",
					observationId);
			tx.update("UPDATE question SET status = 'dismissed', answer = 'forgotten' WHERE observation_id = ? "
					+ "AND status = 'open'", observationId);
			var facts = new ArrayList<Map<String, Object>>();
			var goneFacts = new ArrayList<Long>();
			for (Row r : tx.query("SELECT * FROM fact WHERE observation_id = ?", observationId)) {
				Fact f = Fact.from(r);
				List<Long> others = FactLedger.observationsOf(tx, f.id()).stream().filter(o -> o != observationId)
						.toList();
				if (!others.isEmpty()) {
					// Another observation also stated it: it survives there, with one corroboration fewer.
					tx.update(
							"UPDATE fact SET observation_id = ?, corroborations = MAX(1, corroborations - 1) WHERE id = ?",
							others.getFirst(), f.id());
				} else {
					goneFacts.add(f.id());
					facts.add(Map.of("id", f.ref(), "rendering", f.rendering(), "status", f.status()));
				}
			}
			// An event this observation stated beside others survives there: re-homed when this was its home, and
			// undated again when this was the observation that dated it (its closures are redone without the date
			// once the transaction is through).
			for (Row src : tx.query("SELECT event_id, kind FROM event_source WHERE observation_id = ?",
					observationId)) {
				long evId = src.lng("event_id");
				List<Long> others = tx
						.query("SELECT observation_id FROM event_source WHERE event_id = ? "
								+ "AND observation_id <> ? ORDER BY observation_id", evId, observationId)
						.stream().map(r -> r.lng("observation_id")).toList();
				if (others.isEmpty()) {
					continue;
				}
				tx.update("DELETE FROM event_source WHERE event_id = ? AND observation_id = ?", evId, observationId);
				tx.update("UPDATE event SET observation_id = ? WHERE id = ? AND observation_id = ?", others.getFirst(),
						evId, observationId);
				if ("dated".equals(src.str("kind"))) {
					reopen(tx, List.of(), List.of(evId));
					this.events.undate(tx, evId);
					undated.add(evId);
				}
			}
			var events = new ArrayList<Map<String, Object>>();
			var goneEvents = new ArrayList<Long>();
			for (Event ev : EventService.events(tx,
					tx.query("SELECT * FROM event WHERE observation_id = ?", observationId))) {
				goneEvents.add(ev.id());
				events.add(Map.of("id", ev.ref(), "type", ev.type(), "rendering", ev.rendering()));
				// An event that ended its subject took the entity with it; the entity is back.
				if (eventTypes.get(ev.type()).map(EventType::endsEntity).orElse(false)
						&& !ev.participants().isEmpty()) {
					tx.update("UPDATE entity SET existed_end = NULL WHERE id = ?", ev.participants().getFirst());
				}
			}
			int reopened = reopen(tx, goneFacts, goneEvents);
			// An alias this observation added goes with it, unless another observation's reading names it: then it
			// is the name that observation reached the entity by, and it moves there (a re-reading that left
			// "Polestar 4" out of the aliases lost it for every observation that had used it, 2026-09-22). A fuzzy
			// match's name that no other reading uses ("Hooli Inc") still goes. An entity's own name stays whatever
			// added it.
			for (Row al : tx.query("SELECT a.id, a.alias, a.alias_norm, e.name FROM entity_alias a JOIN entity e "
					+ "ON e.id = a.entity_id WHERE a.source_observation = ?", observationId)) {
				if (Names.norm(al.str("name")).equals(al.str("alias_norm"))) {
					continue;
				}
				String needle = "\""
						+ al.str("alias").toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
				Long elsewhere = tx.query(
						"SELECT id FROM observation WHERE id <> ? AND forgotten_at IS NULL AND proposal_json "
								+ "IS NOT NULL AND instr(lower(proposal_json), ?) > 0 ORDER BY id LIMIT 1",
						observationId, needle).stream().map(r -> r.lng("id")).findFirst().orElse(null);
				if (elsewhere != null) {
					tx.update("UPDATE entity_alias SET source_observation = ? WHERE id = ?", elsewhere, al.lng("id"));
				} else {
					tx.update("DELETE FROM entity_alias WHERE id = ?", al.lng("id"));
				}
			}
			tx.update("DELETE FROM fact_source WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM fact WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM event_source WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM event WHERE observation_id = ?", observationId);
			return new Removed(facts, events, reopened);
		});
		// An event that lost its date still ends what an undated one ends (T18), just without a day.
		for (long evId : undated) {
			events.get(evId).ifPresent(ev -> {
				Optional<Observation> home = db
						.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", ev.observationId()))
						.map(Observation::from);
				List<Entity> participants = ev.participants().stream().map(id -> entities.get(id).orElse(null))
						.filter(Objects::nonNull).toList();
				if (home.isPresent() && participants.size() == ev.participants().size()) {
					events.applyEffects(ev.id(), ev.type(), participants, Bounds.NONE, home.get(), new ArrayList<>());
				}
			});
		}
		if (!keepEntities) {
			entities.removeOrphansCreatedBy(observationId);
		}
		return removed;
	}

	/** The correction records that corrected or retracted this observation's facts: they restate what it said. */
	public List<Long> correctionRecordsOf(long observationId) {
		return db.read(tx -> tx.query("""
				SELECT DISTINCT s.observation_id AS id FROM supersession s
				JOIN fact f ON f.id = s.fact_id JOIN observation o ON o.id = s.observation_id
				WHERE f.observation_id = ? AND s.kind IN ('correction', 'retraction') AND o.source_kind = 'correction'
				AND o.forgotten_at IS NULL""", observationId)).stream().map(r -> r.lng("id")).toList();
	}

	/**
	 * Undoes what the facts and events being removed did to other facts: a fact they closed, superseded, or corrected
	 * is current again, its end cleared when that closure gave it, and the record of the closure goes. The count.
	 */
	private int reopen(Tx tx, List<Long> goneFacts, List<Long> goneEvents) {
		if (goneFacts.isEmpty() && goneEvents.isEmpty()) {
			return 0;
		}
		String facts = goneFacts.isEmpty() ? "-1"
				: goneFacts.stream().map(String::valueOf).collect(Collectors.joining(","));
		String events = goneEvents.isEmpty() ? "-1"
				: goneEvents.stream().map(String::valueOf).collect(Collectors.joining(","));
		int n = 0;
		for (Row s : tx.query("SELECT * FROM supersession WHERE superseded_by_id IN (" + facts + ") OR event_id IN ("
				+ events + ") ORDER BY id DESC")) {
			long factId = s.lng("fact_id");
			if (goneFacts.contains(factId)) {
				continue;
			}
			Optional<Row> row = tx.queryOne("SELECT * FROM fact WHERE id = ?", factId);
			if (row.isEmpty()) {
				continue;
			}
			Fact f = Fact.from(row.get());
			String closedAt = s.str("closed_at");
			boolean endFromThis = f.validEnd() != null && f.validEnd().equals(closedAt) && f.endSource() != null
					&& !"stated".equals(f.endSource()) && !"resolved".equals(f.endSource());
			String status = "superseded".equals(f.status()) || "corrected".equals(f.status()) ? "current" : f.status();
			tx.update("UPDATE fact SET status = ?, superseded_by = NULL WHERE id = ?", status, factId);
			if (endFromThis) {
				tx.update("UPDATE fact SET valid_end = NULL, valid_end_precision = NULL, end_source = NULL, ended = 0 "
						+ "WHERE id = ?", factId);
			}
			tx.update("DELETE FROM supersession WHERE id = ?", s.lng("id"));
			renderer.rerender(tx, factId);
			n++;
		}
		return n;
	}
}
