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

import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.EventOut;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Applies the caller's answers to open questions. An entity answer binds the name and applies what the question held; a
 * predicate answer confirms or registers the predicate and applies the fact; a conflict answer settles which of two
 * facts stands; a containment answer stores the missing link; a type mismatch answer fixes the type and applies the
 * held fact; an event effect or type kind answer defines the vocabulary and applies it to what is already stored.
 */
public final class QuestionResolver {

	/**
	 * An answer to an open question: the choice is a candidate id ({@code ent-4}, {@code works_at}), {@code new}, or
	 * for conflicts {@code ended}, {@code supersede}, {@code reject}, {@code reinterpret}, {@code wrong}.
	 */
	public record Resolve(String questionId, String choice) {
	}

	private record Pending(Question question, String choice, Entity chosen, Map<String, Object> result,
			Observation obs) {
	}

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EntityTypeRegistry entityTypes;
	private final QuestionService questions;
	private final FactService facts;
	private final FactLedger ledger;

	QuestionResolver(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EntityTypeRegistry entityTypes, QuestionService questions, FactService facts, FactLedger ledger) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.entityTypes = entityTypes;
		this.questions = questions;
		this.facts = facts;
		this.ledger = ledger;
	}

	/**
	 * Resolves answers; each result says what happened. Entity answers are taken in two phases: every entity is chosen
	 * or created first, then every held fragment is applied with all of them bound, so a fragment that names another
	 * answered subject finds it whatever the order of the answers. Afterwards any open entity question whose subject
	 * now names an entity exactly is settled.
	 */
	public List<Map<String, Object>> resolve(Observation obs, List<Resolve> resolves) {
		return resolve(obs, resolves, obs.observedAt());
	}

	/** Answers given on their own, with no observation carrying them: each question's own observation stands in. */
	public List<Map<String, Object>> resolve(List<Resolve> resolves, Instant now) {
		return resolve(null, resolves, now);
	}

	private List<Map<String, Object>> resolve(Observation carrier, List<Resolve> resolves, Instant now) {
		var out = new ArrayList<Map<String, Object>>();
		var pending = new ArrayList<Pending>();
		var bindings = new LinkedHashMap<String, Entity>();
		for (Resolve r : resolves) {
			Question q = questions.require(r.questionId());
			if (!q.open()) {
				// An earlier answer in the same batch may have settled this one (a type's kind settles the
				// mismatches under it), or the caller repeats an answer: the batch goes on, and the item says so.
				var already = new LinkedHashMap<String, Object>();
				already.put("question", q.ref());
				already.put("choice", r.choice() == null ? "" : r.choice().trim());
				already.put("status", "already " + q.status());
				if (q.answer() != null) {
					already.put("answer", q.answer());
				}
				out.add(already);
				continue;
			}
			// A question housekeeping raised (a derivation) has no observation of its own and needs none.
			Observation obs = carrier != null ? carrier
					: q.observationId() == null ? null
							: observation(q.observationId()).orElseThrow(() -> MnemicException.invalidArgument(q.ref()
									+ " has no observation to answer against; pass the answer with a remember that has "
									+ "text."));
			String choice = r.choice() == null ? "" : r.choice().trim();
			var result = new LinkedHashMap<String, Object>();
			result.put("question", q.ref());
			result.put("choice", choice);
			switch (q.kind()) {
			case "entity_resolution" -> {
				Entity chosen = chooseEntity(q, choice);
				bindings.put(q.subject(), chosen);
				pending.add(new Pending(q, choice, chosen, result, obs));
			}
			case "predicate_resolution" -> result.putAll(resolvePredicate(q, choice, obs));
			case "conflict" -> result.putAll(resolveConflict(q, choice, obs, now));
			case "containment" -> result.putAll(resolveContainment(q, choice, obs));
			case "type_mismatch" -> result.putAll(resolveTypeMismatch(q, choice, obs));
			case "event_effect" -> result.putAll(resolveEventEffect(q, choice));
			case "type_kind" -> result.putAll(resolveTypeKind(q, choice, obs));
			case "derivation" -> result.putAll(resolveDerivation(q, choice, obs));
			default -> {
				questions.dismiss(q.id(), choice);
				result.put("status", "dismissed");
			}
			}
			out.add(result);
		}
		for (Pending p : pending) {
			p.result().putAll(applyHeld(p.question(), p.choice(), p.chosen(), bindings, p.obs()));
		}
		List<Map<String, Object>> settled = settleExactSubjects(false);
		if (!settled.isEmpty()) {
			out.add(Map.<String, Object> of("settled", settled));
		}
		return out;
	}

	/** The entity an entity question's answer names: a listed candidate, any existing {@code ent-N}, or a new one. */
	private Entity chooseEntity(Question q, String choice) {
		Proposal held = Proposal.parse(q.payload());
		if ("new".equalsIgnoreCase(choice)) {
			String type = held.entities().stream().filter(e -> Names.norm(e.name()).equals(Names.norm(q.subject())))
					.map(EntityRef::type).findFirst().orElse(null);
			// Created once: a second answer naming the same new subject in the same call reuses it. An entity the
			// question offered and the answer passed over is not it, whatever its name.
			return entities.byRef(q.subject())
					.filter(e -> e.id() != entities.owner().id()
							&& q.candidates().stream().noneMatch(c -> e.ref().equals(c.get("id")))
							&& entityTypes.compatible(e.type(), entityTypes.canonical(type)))
					.orElseGet(() -> entities.create(q.subject(), type, List.of(), q.observationId()));
		}
		boolean listed = q.candidates().stream().anyMatch(c -> choice.equals(c.get("id")));
		if (!listed && !choice.startsWith("ent-")) {
			throw MnemicException.invalidArgument("'" + choice + "' is not a candidate of " + q.ref()
					+ "; answer with one of " + q.candidates() + ", an existing entity id (ent-N), or \"new\".");
		}
		Entity chosen = entities.byRef(choice).orElseThrow(() -> MnemicException.notFound("No entity " + choice));
		// The name the question was about is now known to be an alias of the chosen entity.
		entities.resolve(chosen.name(), chosen.type(), List.of(q.subject()), q.observationId());
		return chosen;
	}

	private Map<String, Object> applyHeld(
		Question q, String choice, Entity chosen, Map<String, Entity> bindings, Observation obs) {
		Proposal held = Proposal.parse(q.payload());
		Applied a = facts.apply(source(q, obs), held, bindings);
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
	 * Open entity questions whose subject now names an entity exactly are settled: what they held is applied to that
	 * entity (identical facts corroborate rather than duplicate) and the question closes.
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
				Optional<Observation> src = q.observationId() == null ? Optional.empty()
						: observation(q.observationId());
				if (src.isPresent() && q.payload() != null) {
					Applied a = facts.apply(src.get(), Proposal.parse(q.payload()), Map.of(q.subject(), exact.get()));
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
			// Without a definition the name is registered from this use, as it would have been had nothing matched.
			PredicateDef def = held.predicates().isEmpty() ? new PredicateDef(f.predicate(), null, null, null, null,
					null, null, null, null, List.of(), null, List.of(), List.of()) : held.predicates().getFirst();
			predicates.register(def, q.observationId());
			toApply = new Proposal(held.specVersion(), held.entities(), List.of(), List.of(f), List.of());
		} else {
			Predicate target = predicates.get(choice).orElseThrow(() -> MnemicException.invalidArgument(
					"'" + choice + "' is not a registered predicate; answer with the candidate or \"new\"."));
			// A confirmed synonym is asked once: the proposed name becomes an alias (EVALUATION.md J2). An ambiguous
			// match confirmed for this fact stays a one-off (J3).
			boolean similar = q.candidates().stream().anyMatch(cd -> target.name().equals(cd.get("id"))
					&& ("similar".equals(cd.get("match")) || "semantic".equals(cd.get("match"))));
			if (similar) {
				predicates.addAlias(target, f.predicate());
			}
			FactRef re = new FactRef(f.subject(), target.name(), f.object(), f.qualifier(), f.scope(), f.validTime(),
					f.ended(), f.derivedFrom(), f.derivation(), f.callerConfidence());
			toApply = new Proposal(held.specVersion(), held.entities(), List.of(), List.of(re), List.of());
		}
		Applied a = facts.apply(source(q, obs), toApply);
		questions.answer(q.id(), choice);
		var m = new LinkedHashMap<String, Object>();
		m.put("status", "answered");
		m.put("facts", a.facts().stream().map(FactOut::id).toList());
		return m;
	}

	private Map<String, Object> resolveConflict(Question q, String choice, Observation obs, Instant now) {
		Map<String, Object> payload = Json.readMap(q.payload());
		long existingId = Long.parseLong(payload.get("existing").toString().substring(2));
		long pendingId = Long.parseLong(payload.get("pending").toString().substring(2));
		switch (choice.toLowerCase(Locale.ROOT)) {
		case "ended" -> db.write(tx -> {
			ledger.close(tx, factIn(tx, existingId), null, "supersession", "user: earlier value ended", null, obs.id(),
					null, null, "current");
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			return null;
		});
		case "supersede" -> db.write(tx -> {
			String today = now.toString().substring(0, 10);
			ledger.close(tx, factIn(tx, existingId), pendingId, "supersession", "user: replaced", null, obs.id(), today,
					"day", "superseded");
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			return null;
		});
		case "wrong" -> db.write(tx -> {
			// Not a change over time but an error caught at the conflict: the same shape a correction leaves behind.
			tx.update("UPDATE fact SET status = 'corrected', superseded_by = ? WHERE id = ?", pendingId, existingId);
			tx.update("UPDATE fact SET status = 'current' WHERE id = ?", pendingId);
			FactLedger.supersession(tx, existingId, pendingId, "correction", "user: the earlier record was wrong", null,
					obs.id(), null);
			return null;
		});
		case "reject", "reinterpret" -> db.write(tx -> {
			tx.update("UPDATE fact SET status = 'rejected' WHERE id = ?", pendingId);
			FactLedger.supersession(tx, pendingId, null, "invalidation", "user: " + choice, null, obs.id(), null);
			return null;
		});
		default -> throw MnemicException.invalidArgument("'" + choice + "' is not an answer to " + q.ref()
				+ "; use ended, supersede, reject, reinterpret, or wrong.");
		}
		questions.answer(q.id(), choice);
		var m = new LinkedHashMap<String, Object>();
		m.put("status", "answered");
		m.put("existing", "f-" + existingId);
		m.put("pending", "f-" + pendingId);
		return m;
	}

	/**
	 * A stated fact on a derived predicate against the record's complete chains (K6): {@code keep} lets it stand,
	 * {@code wrong} rejects it, the way a rejected pending fact is.
	 */
	private Map<String, Object> resolveDerivation(Question q, String choice, Observation obs) {
		long factId = q.factId();
		var m = new LinkedHashMap<String, Object>();
		switch (choice.toLowerCase(Locale.ROOT)) {
		case "keep" -> m.put("fact", "f-" + factId);
		case "wrong" -> db.write(tx -> {
			tx.update("UPDATE fact SET status = 'rejected' WHERE id = ?", factId);
			FactLedger.supersession(tx, factId, null, "invalidation",
					"user: wrong; the record's own chains say otherwise", null, obs == null ? null : obs.id(), null);
			m.put("fact", "f-" + factId);
			m.put("rejected", true);
			return null;
		});
		default -> throw MnemicException
				.invalidArgument("'" + choice + "' is not an answer to " + q.ref() + "; use keep or wrong.");
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		return m;
	}

	private Map<String, Object> resolveContainment(Question q, String choice, Observation obs) {
		Map<String, Object> payload = Json.readMap(q.payload());
		long top = Long.parseLong(payload.get("entity").toString().substring(4));
		long bound = Long.parseLong(payload.get("within").toString().substring(4));
		var m = new LinkedHashMap<String, Object>();
		switch (choice.toLowerCase(Locale.ROOT)) {
		case "yes" -> {
			var ref = new FactRef(entities.nameOf(top), q.predicate() == null ? "located_in" : q.predicate(),
					entities.nameOf(bound), null, null, null, null, List.of(), new Proposal.Derivation("explicit"),
					null);
			Applied a = facts.apply(obs,
					new Proposal(Proposal.CURRENT_SPEC_VERSION, List.of(), List.of(), List.of(ref), List.of()));
			m.put("facts", a.facts().stream().map(FactOut::id).toList());
		}
		case "no" -> m.put("note", entities.nameOf(top) + " is recorded as not within " + entities.nameOf(bound)
				+ " only in this answer; the restriction and the fact it questioned disagree, correct one of them.");
		default -> throw MnemicException
				.invalidArgument("'" + choice + "' is not an answer to " + q.ref() + "; use yes or no.");
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		return m;
	}

	/** The entity's type is made a kind of an accepted one, or the entity is retyped; then the held fact is applied. */
	private Map<String, Object> resolveTypeMismatch(Question q, String choice, Observation obs) {
		Map<String, Object> payload = Json.readMap(q.payload());
		String type = String.valueOf(payload.get("entity_type"));
		long entityId = Long.parseLong(payload.get("entity").toString().substring(4));
		var m = new LinkedHashMap<String, Object>();
		if ("dismiss".equalsIgnoreCase(choice)) {
			questions.dismiss(q.id(), choice);
			m.put("status", "dismissed");
			return m;
		}
		boolean listed = q.candidates().stream().anyMatch(c -> choice.equals(c.get("id")));
		if (!listed) {
			throw MnemicException.invalidArgument("'" + choice + "' is not an answer to " + q.ref()
					+ "; answer with one " + "of " + q.candidates().stream().map(c -> c.get("id")).toList() + ".");
		}
		String kind = choice.substring(choice.indexOf(':') + 1);
		if (choice.startsWith("kind:")) {
			// The kind was offered, so choosing it must work even if nothing else has registered the type yet.
			entityTypes.registerInferred(kind, q.observationId());
			entityTypes.update(type, Map.of("parent", kind, "description", "A kind of " + kind + "."),
					"user: " + q.ref());
			m.put("entity_type", type);
			m.put("parent", kind);
			// The same answer settles the open question about what kind of thing the type is.
			for (Question other : questions.open(200)) {
				if ("type_kind".equals(other.kind()) && type.equals(other.subject())) {
					questions.answer(other.id(), kind);
				}
			}
		} else {
			entities.retype(entityId, kind);
			m.put("entity", "ent-" + entityId);
			m.put("type", kind);
		}
		Object held = payload.get("held");
		if (held != null) {
			// Bound to the entity itself: re-resolving its name under the old type would mint another entity.
			Entity ent = entities.get(entityId).orElseThrow();
			Applied a = facts.apply(source(q, obs), Proposal.parse(String.valueOf(held)), Map.of(ent.name(), ent));
			m.put("facts", a.facts().stream().map(FactOut::id).toList());
			if (!a.questions().isEmpty()) {
				m.put("questions", a.questions());
			}
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		return m;
	}

	/** The type gets its effect and every event stored under it gets the consequences. */
	private Map<String, Object> resolveEventEffect(Question q, String choice) {
		String type = q.subject();
		String c = choice.toLowerCase(Locale.ROOT);
		var m = new LinkedHashMap<String, Object>();
		var replacement = new LinkedHashMap<String, Object>();
		if ("none".equals(c)) {
			replacement.put("description", "A plain occurrence with no effect on facts.");
		} else if ("ends_entity".equals(c)) {
			replacement.put("ends_entity", true);
			replacement.put("description", "The subject ceases to exist.");
		} else if (c.startsWith("opens:") || c.startsWith("closes:")) {
			String name = c.substring(c.indexOf(':') + 1);
			Predicate p = predicates.get(name).orElseThrow(() -> MnemicException.invalidArgument(
					"'" + name + "' is not a registered predicate; answer with one of the candidates."));
			if (c.startsWith("opens:")) {
				replacement.put("opens", List.of(p.name()));
				if (p.functional()) {
					replacement.put("supersedes", List.of(p.name()));
				}
				replacement.put("description", "Starts " + p.name() + ".");
			} else {
				replacement.put("closes", List.of(p.name()));
				replacement.put("description", "Ends " + p.name() + ".");
			}
		} else {
			throw MnemicException.invalidArgument("'" + choice + "' is not an answer to " + q.ref()
					+ "; use opens:<predicate>, closes:<predicate>, ends_entity, or none.");
		}
		eventTypes.update(type, replacement, "user: " + q.ref(), name -> predicates.get(name).isPresent());
		m.put("event_type", type);
		if (!"none".equals(c)) {
			m.put("applied", facts.applyEffectsOf(type));
		}
		questions.answer(q.id(), choice);
		m.put("status", "answered");
		return m;
	}

	/**
	 * The type gets its parent, so everything that accepts the parent accepts it; facts held behind a type mismatch
	 * that the parent resolves are stored.
	 */
	private Map<String, Object> resolveTypeKind(Question q, String choice, Observation obs) {
		String type = q.subject();
		var replacement = new LinkedHashMap<String, Object>();
		if ("none".equalsIgnoreCase(choice)) {
			replacement.put("description", "A kind of its own.");
		} else {
			String parent = entityTypes.canonical(choice);
			if (entityTypes.get(parent).isEmpty()) {
				throw MnemicException.invalidArgument(
						"'" + choice + "' is not a registered entity type; answer with one of the candidates or none.");
			}
			replacement.put("parent", parent);
			replacement.put("description", "A kind of " + parent + ".");
		}
		var t = entityTypes.update(type, replacement, "user: " + q.ref());
		questions.answer(q.id(), choice);
		var m = new LinkedHashMap<String, Object>();
		m.put("entity_type", t.name());
		m.put("parent", t.parent());
		if (t.parent() != null) {
			var settled = new ArrayList<Map<String, Object>>();
			List<String> lineage = entityTypes.lineage(t.name());
			for (Question other : questions.open(200)) {
				if (!"type_mismatch".equals(other.kind())) {
					continue;
				}
				Map<String, Object> payload = Json.readMap(other.payload());
				if (!type.equals(payload.get("entity_type")) || !(payload.get("expected") instanceof List<?> expected)
						|| expected.stream().noneMatch(lineage::contains)) {
					continue;
				}
				var s = new LinkedHashMap<String, Object>();
				s.put("question", other.ref());
				if (payload.get("held") != null) {
					long entityId = Long.parseLong(payload.get("entity").toString().substring(4));
					Map<String, Entity> bound = entities.get(entityId).map(en -> Map.of(en.name(), en))
							.orElse(Map.of());
					Applied a = facts.apply(source(other, obs), Proposal.parse(String.valueOf(payload.get("held"))),
							bound);
					s.put("facts", a.facts().stream().map(FactOut::id).toList());
				}
				questions.answer(other.id(), "kind:" + t.parent());
				settled.add(s);
			}
			if (!settled.isEmpty()) {
				m.put("settled", settled);
			}
		}
		m.put("status", "answered");
		return m;
	}

	/** The observation the question was asked about, else the one carrying the answer. */
	private Observation source(Question q, Observation fallback) {
		return q.observationId() == null ? fallback : observation(q.observationId()).orElse(fallback);
	}

	private Optional<Observation> observation(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", id).map(Observation::from));
	}

	private static Fact factIn(Tx tx, long id) {
		return Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", id).orElseThrow());
	}
}
