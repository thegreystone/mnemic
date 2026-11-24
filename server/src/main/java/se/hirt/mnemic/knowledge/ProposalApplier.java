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
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.EntityOut;
import se.hirt.mnemic.knowledge.FactService.EventOut;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.FactService.Operands;
import se.hirt.mnemic.knowledge.FactService.PredicateOut;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.ClosureRef;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventRef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
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

/**
 * Applies a validated {@link Proposal} to the store (EXTRACTION.md, Layer 2): registers vocabulary, resolves entities,
 * records events and applies their effects, writes facts and closures, checks conflicts, and raises questions for what
 * Mnemic cannot decide on its own. Stateless across calls; all per-proposal state lives in {@link Application}.
 */
final class ProposalApplier {

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
		/**
		 * Ids of entities declared together in this proposal (plus the replaced object during a correction), so none is
		 * offered as an ambiguity for another's name.
		 */
		final Set<Long> declared;

		Application(Observation obs, Proposal p, Map<String, Entity> bound, Set<Long> declared) {
			this.obs = obs;
			this.p = p;
			this.declared = declared;
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

	/**
	 * What the write of one fact produced; {@code out} is null when the row was the same proposal's own restatement.
	 */
	private record Stored(FactOut out, long id, Fact conflictWith, String why, List<long[]> asks) {
	}

	private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\n]");
	private static final Pattern REF_SHAPE = Pattern.compile("(?i)(e|ev|ent|evt)[-_]?\\d+");
	/** A stored entity's id as the store writes it, which a fact may name instead of the entity's name. */
	private static final Pattern ENTITY_ID = Pattern.compile("(?i)ent-\\d+");
	/** An object that is a clause about the world, not a plan or an option. */
	private static final Pattern RECOLLECTION = Pattern.compile("^(?:that|whether|if)\\b", Pattern.CASE_INSENSITIVE);
	/** Words too common to tell two qualifiers apart. */
	private static final Set<String> WORDING_STOPWORDS = Set.of("the", "and", "for", "with", "from", "not", "per",
			"his", "her", "their", "our", "its", "this", "that", "was", "are", "has", "have", "into", "onto", "about");

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EventService events;
	private final QuestionService questions;
	private final FactQuestions asks;
	private final FactRenderer renderer;
	private final FactLedger ledger;
	private final ConflictCheck conflicts;
	private final EntityTypeRegistry types;
	private final Lang lang;

	ProposalApplier(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EventService events, QuestionService questions, FactQuestions asks, FactRenderer renderer,
			FactLedger ledger, ConflictCheck conflicts, EntityTypeRegistry types) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.events = events;
		this.questions = questions;
		this.asks = asks;
		this.renderer = renderer;
		this.ledger = ledger;
		this.conflicts = conflicts;
		this.types = types;
		this.lang = renderer.lang();
	}

	Applied apply(Observation obs, Proposal p, Map<String, Entity> bound, Set<Long> extraDistinct) {
		var declared = new HashSet<Long>(extraDistinct);
		for (EntityRef er : p.entities()) {
			declared.addAll(entities.exactIds(er.name(), er.aliases(), er.type()));
		}
		var a = new Application(obs, p, bound, declared);
		registerVocabulary(a);
		resolveEntities(a);
		List<FactRef> opened = applyEvents(a);
		applyFacts(a, opened);
		applyClosures(a);
		return a.result();
	}

	/**
	 * Everything a name must not be confused with: the proposal's other declared entities and those already resolved.
	 */
	private Set<Long> distinctFrom(Application a, Set<Long> own) {
		var out = new HashSet<>(a.declared);
		for (Entity e : a.refs.values()) {
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
				List<String> placedKinds = placeNamedKinds(name);
				if ("defined".equals(resolution)) {
					settle("type_kind", name);
				}
				var m = definition("entity_type", name, resolution);
				if (!placedKinds.isEmpty()) {
					m.put("placed", placedKinds);
				}
				a.definitions.add(m);
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
					m.put("applied", applyEffectsOf(t.name()));
				}
				a.definitions.add(m);
			} catch (MnemicException e) {
				skipped(a, e, "Event type '" + d.name() + "'");
			}
		}
	}

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

	/**
	 * Registers {@code type} from its use for {@code e}. A spelling that says where the type goes places it at once;
	 * any other new type is asked about, once.
	 */
	private void registerTypeFromUse(Application a, String type, Entity e) {
		if (EntityTypeRegistry.UNKNOWN.equals(type) || types.get(type).isPresent()) {
			return;
		}
		Optional<EntityTypeRegistry.Placement> placed = types.placeFromUse(type, a.obs.id());
		if (placed.isPresent() && !placed.get().created()) {
			var m = definition("entity_type", placed.get().type().name(), "exists");
			m.put("placed_by", placed.get().how());
			a.definitions.add(m);
			return;
		}
		EntityTypeRegistry.EntityType t = placed.map(EntityTypeRegistry.Placement::type)
				.orElseGet(() -> types.registerInferred(type, a.obs.id()));
		var inferred = new LinkedHashMap<String, Object>();
		inferred.put("parent", t.parent());
		placed.ifPresent(p -> inferred.put("placed_by", p.how()));
		inferred.put("type_words", List.of());
		inferred.put("correct",
				"correct(\"type:" + t.name() + "\", {parent, description, synonyms, type_words, kinds})");
		a.defined("entity_type", t.name(), "inferred", inferred);
		if (placed.isEmpty()) {
			asks.typeKind(a.obs, t, e, types.roots()).ifPresent(a::ask);
		}
	}

	/** A definition of the term answers the question that asked what it means. */
	private void settle(String kind, String term) {
		for (Question q : questions.open(200)) {
			if (kind.equals(q.kind()) && term.equals(q.subject())) {
				questions.answer(q.id(), "defined");
			}
		}
	}

	/**
	 * Places the unplaced types a type's kinds name, after a definition or a correction gave it kinds, and settles the
	 * questions that asked what they were. Returns what was placed.
	 */
	List<String> placeNamedKinds(String typeName) {
		List<String> placed = types.adoptKinds(typeName);
		for (String name : placed) {
			settle("type_kind", name);
		}
		return placed;
	}

	static Map<String, Object> definition(String kind, String name, String resolution) {
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
				if (e != null) {
					a.entityOut.add(new EntityOut(key, e.ref(), e.name(), "bound", 1.0));
				}
				continue;
			}
			Resolved r;
			try {
				r = entities.resolve(er.name(), er.type(), er.aliases(), a.obs.id(),
						distinctFrom(a, entities.exactIds(er.name(), er.aliases(), er.type())), expectedKinds(a, er));
			} catch (MnemicException e) {
				if (e.code() != MnemicException.Code.INVALID_ARGUMENT) {
					throw e;
				}
				a.warnings.add("Entity '" + er.name() + "' skipped: " + e.getMessage());
				continue;
			}
			if (r.ambiguous()) {
				a.ask(asks.entity(a.obs, er.name(), er.type(), r.candidates(), FactQuestions.heldProposal(a.p, er)));
				a.refs.put(key, null);
				a.refs.put(Names.norm(er.name()), null);
				continue;
			}
			a.refs.put(key, r.entity());
			a.refs.put(Names.norm(er.name()), r.entity());
			a.entityOut.add(new EntityOut(key, r.entity().ref(), r.entity().name(), r.how(), r.score()));
			// An alias given here that another thing already carries: shared, never merged; whether they are one
			// thing is asked (2026-11-24, where a shared alias used to merge them).
			for (Map.Entry<Entity, String> other : entities.sharingAliases(r.entity().id(), er.aliases()).entrySet()) {
				asks.sameEntity(a.obs.id(), r.entity(), other.getKey(), other.getValue()).ifPresent(a::ask);
			}
			if ("created".equals(r.how())) {
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
				continue;
			}
			Bounds b = Bounds.of(ev.validTime(), a.obs.observedAt(), a.warnings);
			String type = EventTypeRegistry.key(ev.type());
			EventType et = eventTypes.get(type).orElse(null);
			if (et == null && !EventTypeRegistry.typeLike(ev.type())) {
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
			List<String> roles = ev.roleList();
			warnUnknownRoles(a, ev);
			String rendering = events.render(type, participants.stream().map(Entity::name).toList(), roles, null, b);
			EventService.Stored stored = events.store(type, participants, roles, b, rendering, a.obs);
			var acting = new ArrayList<Entity>();
			for (int i = 0; i < participants.size(); i++) {
				if (roles.get(i) == null) {
					acting.add(participants.get(i));
				}
			}
			String key = ev.ref() != null ? ev.ref() : "evt-" + stored.id();
			a.eventIds.put(key, stored.id());
			a.eventTypeOf.put(key, type);
			a.eventParticipants.put(key, acting.stream().map(Entity::id).toList());
			a.eventOut.add(new EventOut(key, "evt-" + stored.id(), type));
			if (stored.effects()) {
				events.applyEffects(stored.id(), type, acting, b, a.obs, a.superseded);
			}
			opened.addAll(openedBy(a, ev.acting(), type, acting, key));
			if (et != null && et.inferred()) {
				List<Predicate> fit = fitting(acting);
				if (effectWorthAsking(acting, fit)) {
					asks.eventEffect(a.obs, et, acting, stored.id(), fit).ifPresent(a::ask);
				}
			}
		}
		return opened;
	}

	private boolean effectWorthAsking(List<Entity> participants, List<Predicate> fitting) {
		if (participants.stream().anyMatch(p -> types.unplaced(p.type()))) {
			return false;
		}
		if (fitting.stream().anyMatch(p -> p.functional() && !p.groups().contains("family"))) {
			return true;
		}
		return participants.size() == 1 && types.isA(participants.getFirst().type(), "person");
	}

	private List<Predicate> fitting(List<Entity> participants) {
		if (participants.isEmpty()) {
			return List.of();
		}
		List<String> subject = types.lineage(participants.getFirst().type());
		List<String> object = participants.size() < 2 ? null : types.lineage(participants.get(1).type());
		return predicates.all().stream()
				.filter(p -> !p.literalRange() && p.acceptsSubject(subject)
						&& (object == null ? p.range().contains("*") : p.acceptsObject(object))
						&& predicates.rulesOf(p.name()).isEmpty())
				.toList();
	}

	/**
	 * Applies a type's effects to the events already stored under it, after an answer or a correction gave it some: the
	 * facts they open and close, as if the type had been defined when they were remembered.
	 */
	Map<String, Object> applyEffectsOf(String type) {
		var factsOut = new ArrayList<String>();
		var superseded = new ArrayList<Map<String, Object>>();
		var questionsOut = new ArrayList<Map<String, Object>>();
		int n = 0;
		for (Event ev : events.ofType(type)) {
			Optional<Observation> obs = db
					.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", ev.observationId()))
					.map(Observation::from);
			List<Entity> participants = ev.acting().stream().map(id -> entities.get(id).orElse(null))
					.filter(Objects::nonNull).toList();
			if (obs.isEmpty() || participants.size() != ev.acting().size()) {
				continue;
			}
			var a = new Application(obs.get(),
					new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(), List.of()), Map.of(),
					Set.of());
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
			questionsOut.addAll(a.questions);
			n++;
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("events", n);
		m.put("facts", factsOut);
		m.put("superseded", superseded);
		if (!questionsOut.isEmpty()) {
			m.put("questions", questionsOut);
		}
		return m;
	}

	private static void warnUnknownRoles(Application a, EventRef ev) {
		for (String who : ev.roles().keySet()) {
			if (!ev.participants().contains(who)) {
				a.warnings.add("Role '" + ev.roles().get(who) + "' of '" + who + "' ignored: '" + who
						+ "' is not one of the participants " + ev.participants() + " as written.");
			} else if (who.equals(ev.participants().getFirst())) {
				a.warnings.add("Role '" + ev.roles().get(who) + "' of '" + who + "' ignored: the first participant "
						+ "carries the sentence and takes no role.");
			}
		}
	}

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
				if (pr == null || (pr.literalRange() && !pr.mixedRange())
						|| !pr.acceptsSubject(types.lineage(subj.type()))
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
		if (a.attributeFacts.contains(f) && res.predicate() != null && !res.predicate().literalRange()) {
			Predicate p = predicates.literalFromUse(res.predicate().name(),
					"attribute of " + f.subject() + " in obs-" + a.obs.id());
			if (p.literalRange()) {
				res = new PredicateRegistry.Resolution(p, res.how(), res.candidate(), res.alternatives());
			} else {
				a.warnings.add("Attribute '" + f.predicate() + "' of " + f.subject() + " is stored under '" + p.name()
						+ "', which takes " + p.range() + ", not a value: \"" + f.object()
						+ "\" resolves to an entity. State it as a fact under a predicate with range literal instead.");
			}
		}
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
		} else if ("registered".equals(res.how()) && def != null
				&& (def.lasting() == null || res.candidate() != null)) {
			Predicate p = res.predicate();
			var note = new LinkedHashMap<String, Object>();
			if (def.lasting() == null) {
				note.put("assumed", Map.of("lasting", p.lasting()));
				note.put("correct", "correct(\"pred:" + p.name() + "\", {\"lasting\": " + !p.lasting() + "})");
			}
			if (res.candidate() != null) {
				note.put("close_to", res.candidate().name());
				note.put("fold", "correct(\"pred:" + p.name() + "\", {\"merge_into\": \"" + res.candidate().name()
						+ "\"}) if they are one relation");
			}
			a.defined("predicate", p.name(), "registered", note);
		}
		if (res.asks()) {
			a.ask(asks.predicate(a.obs, f, res.candidate(), res.how(), def, FactQuestions.heldProposal(a.p, f, def),
					res.alternatives()));
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
		final List<String> objectNames = op.object() == null ? List.of(Names.norm(op.objectText()))
				: entities.aliases(op.object().id()).stream().map(Names::norm).toList();
		Stored stored = db.write(tx -> {
			Optional<Row> existing = currentRow(tx, op);
			if (existing.isPresent()) {
				Fact e = Fact.from(existing.get());
				if (isEnded && !e.ended() && e.validEnd() == null) {
					return endRestated(tx, a.obs, op, e, bounds);
				}
				return corroborate(tx, a.obs, op, e);
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
			if (!c.pending() && "asserted".equals(op.mode())) {
				endEarlier(tx, a, op, id, objectNames, c.row().start(), c.row().startPrecision(), eventId);
			}
			if (c.besides() != null) {
				Fact fresh = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", id).orElseThrow());
				String reason = ConflictCheck.besideReason(tx, fresh, c.besides(), "store: ");
				FactLedger.supersession(tx, id, null, "nested", reason, null, a.obs.id(), null);
				a.warnings.add(
						"\"" + rowRendering + "\" " + reason.substring("store: ".length()) + "; both are current.");
			}
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
					ask[2] = id;
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
		if (pred.literalRange() && !(pred.mixedRange() && namesAnEntity(f.object(), a.refs))) {
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
		} else if (pred.range().contains("*") && !namesAnEntity(f.object(), a.refs) && readsAsDescription(f.object())) {
			objectText = f.object().trim();
		} else {
			object = resolveRef(a, f.object(), kinds(pred.thingRange()));
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

	/**
	 * Whether an object reads as a description rather than a name: it starts in lower case over three words or more, it
	 * is a list, or it runs to seven words.
	 */
	static boolean readsAsDescription(String object) {
		String s = object == null ? "" : object.strip();
		List<String> tokens = Names.tokens(s);
		if (tokens.isEmpty()) {
			return false;
		}
		boolean lower = Character.isLowerCase(s.codePointAt(0));
		return (lower && tokens.size() >= 3) || ((s.contains(",") || s.contains(";")) && tokens.size() >= 4)
				|| tokens.size() >= 7;
	}

	private boolean newKind(Entity e) {
		return types.get(e.type()).map(EntityTypeRegistry.EntityType::inferred).orElse(false);
	}

	private static String qualifier(Application a, FactRef f, Predicate pred) {
		if (f.qualifier() == null) {
			return null;
		}
		String q = freeQualifier(pred) ? f.qualifier().trim() : f.qualifier().trim().toLowerCase(Locale.ROOT);
		if (!freeQualifier(pred) && !pred.qualifiers().contains(q)) {
			a.warnings.add("Qualifier '" + q + "' is not one of " + pred.qualifiers() + " for " + pred.name()
					+ "; kept as given.");
		}
		return q;
	}

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
						+ ". A claim that everything the subject " + pred.name()
						+ " lies within a place is a restriction, not " + pred.name()
						+ " of the place; if that was meant, keep it as text until restrictions are "
						+ "representable.");
				return;
			}
		}
	}

	private static Optional<Row> currentRow(Tx tx, Operands op) {
		boolean free = freeQualifier(op.predicate());
		return tx
				.query("""
						SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current'
						AND derivation_kind <> 'derived'
						AND COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?
						AND (? = 1 OR COALESCE(qualifier, '') = ?) AND COALESCE(scope_id, -1) = ? AND mode = ?
						ORDER BY id""", op.subject().id(), op.predicate().name(), op.objectId(), op.objectTextKey(),
						free ? 1 : 0, op.qualifier() == null ? "" : op.qualifier(), op.scopeId(), op.mode())
				.stream().filter(r -> !free || sameWording(op.qualifier(), r.str("qualifier"))).findFirst();
	}

	/**
	 * Whether two free-text qualifiers word the same thing: either is empty, or at least half of the content words of
	 * the shorter one appear in the other.
	 */
	static boolean sameWording(String a, String b) {
		if (a == null || a.isBlank() || b == null || b.isBlank()) {
			return true;
		}
		Set<String> x = wordingTokens(a);
		Set<String> y = wordingTokens(b);
		if (x.isEmpty() || y.isEmpty()) {
			return a.strip().equalsIgnoreCase(b.strip());
		}
		long shared = x.stream().filter(y::contains).count();
		return shared * 2 >= Math.min(x.size(), y.size());
	}

	private static Set<String> wordingTokens(String s) {
		var out = new HashSet<String>();
		for (String t : Names.tokens(s)) {
			if (t.length() >= 3 && !WORDING_STOPWORDS.contains(t) && !t.chars().allMatch(Character::isDigit)) {
				out.add(t);
			}
		}
		return out;
	}

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

	private void endEarlier(
		Tx tx, Application a, Operands op, long id, List<String> objectNames, String start, String precision,
		Long eventId) {
		for (String q : predicates.endsOf(op.predicate().name())) {
			for (Row r : tx.query("SELECT * FROM fact WHERE predicate = ? AND subject_id = ? AND status = 'current' "
					+ "AND ended = 0 AND valid_end IS NULL AND mode = 'asserted' AND derivation_kind <> 'derived' "
					+ "AND id <> ?", q, op.subject().id(), id)) {
				Fact f = Fact.from(r);
				boolean same = f.objectId() != null ? op.object() != null && f.objectId() == op.object().id()
						: f.objectText() != null && objectNames.contains(Names.norm(f.objectText()));
				if (!same || (f.validStart() != null && start != null && f.validStart().compareTo(start) > 0)) {
					continue;
				}
				ledger.close(tx, f, id, "ended_by", op.predicate().name() + " ends " + q, eventId, a.obs.id(), start,
						precision, "current");
				var m = new LinkedHashMap<String, Object>();
				m.put("fact_id", f.ref());
				m.put("predicate", f.predicate());
				m.put("rendering",
						Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", f.id()).orElseThrow()).rendering());
				m.put("ended_by", "f-" + id);
				m.put("closed_at", start == null ? null : Bounds.show(start, precision));
				m.put("reason", op.predicate().name() + " ends " + q);
				a.superseded.add(m);
			}
		}
	}

	private Stored endRestated(Tx tx, Observation obs, Operands op, Fact e, Bounds stated) {
		FactLedger.corroborate(tx, e.id(), obs.id(), obs.observedAt().toString());
		ledger.close(tx, e, null, "stated", "restated as ended", null, obs.id(), stated.end(), stated.endPrecision(),
				e.status());
		Fact after = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", e.id()).orElseThrow());
		return new Stored(new FactOut(e.ref(), op.predicate().name(), after.rendering(), after.status(), true), e.id(),
				null, null, List.of());
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

	/**
	 * After a predicate became functional: where a subject (and scope, when the predicate is functional per scope) has
	 * several open current values, the earliest stands and each later one becomes {@code pending} behind a conflict
	 * question, as it would have had the predicate been functional when it arrived. The count of conflicts.
	 */
	int recheckFunctional(String name) {
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

	// ── references & helpers ─────────────────────────────────────────────

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
		if (ENTITY_ID.matcher(ref).matches()) {
			Entity byId = entities.byRef(ref.toLowerCase(Locale.ROOT))
					.orElseThrow(() -> MnemicException.invalidArgument("'" + ref + "' names no entity on record."));
			a.bind(ref, byId);
			return byId;
		}
		if (REF_SHAPE.matcher(ref).matches()) {
			throw MnemicException.invalidArgument("'" + ref + "' refers to no entity in this proposal.");
		}
		Resolved r = entities.resolve(ref, null, List.of(), a.obs.id(),
				distinctFrom(a, entities.exactIds(ref, List.of(), null)), expected);
		if (r.ambiguous()) {
			var er = new EntityRef(null, ref, null, List.of());
			a.ask(asks.entity(a.obs, ref, null, r.candidates(), FactQuestions.heldProposal(a.p, er)));
			a.bind(ref, null);
			return null;
		}
		a.bind(ref, r.entity());
		return r.entity();
	}

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

	private boolean namesAnEntity(String ref, Map<String, Entity> refs) {
		if (ref == null || ref.isBlank()) {
			return false;
		}
		return refs.containsKey(ref) || refs.containsKey(Names.norm(ref))
				|| EntityService.SELF.contains(Names.norm(ref)) || REF_SHAPE.matcher(ref).matches()
				|| !entities.exactIds(ref, List.of(), null).isEmpty();
	}

	static boolean freeQualifier(Predicate p) {
		return p.qualifiers() == null || p.qualifiers().isEmpty();
	}

	static boolean fuller(String candidate, String current) {
		if (candidate == null || candidate.isBlank()) {
			return false;
		}
		if (current == null || current.isBlank()) {
			return true;
		}
		return candidate.strip().length() > current.strip().length() && !candidate.equalsIgnoreCase(current);
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
}
