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
import se.hirt.mnemic.knowledge.EntityTypeRegistry.EntityType;
import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.proposal.Proposal.EventRef;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The questions the write path puts to the caller when it cannot decide alone: an ambiguous entity or predicate (the
 * fact is held in the question until answered), a conflict on a functional predicate (the new fact is stored pending),
 * a type mismatch (the fact is held), a containment gap, and what a type registered from use means (nothing is held:
 * the event or entity is stored, and the answer applies to everything of that type). Each carries numbered candidates
 * so the model never has to reproduce an internal id.
 */
final class FactQuestions {

	private final QuestionService questions;
	private final EntityService entities;
	private final FactQueries facts;

	FactQuestions(QuestionService questions, EntityService entities, FactQueries facts) {
		this.questions = questions;
		this.entities = entities;
		this.facts = facts;
	}

	Question entity(Observation obs, String name, String type, List<Candidate> candidates, String payload) {
		List<Map<String, Object>> c = numbered(candidates, "a new entity named " + name);
		String message = "Is \"" + name + "\" "
				+ String.join(" or ",
						candidates.stream().map(x -> x.entity().name() + " (" + x.entity().type() + ")").toList())
				+ ", or someone else? The facts mentioning " + name + " are held until you answer with the "
				+ "candidate id or \"new\".";
		return questions.create("entity_resolution", obs.id(), null, name, null, c, payload, message);
	}

	Question predicate(Observation obs, FactRef f, Predicate candidate, String how, PredicateDef def, String payload) {
		var c = new ArrayList<Map<String, Object>>();
		c.add(choice(1, candidate.name(), candidate.name() + ": " + candidate.description(), how));
		c.add(choice(2, "new", "register '" + f.predicate() + "' as a new predicate", null));
		String message = "Does '" + f.predicate() + "' (" + (def == null ? "" : def.description()) + ") mean the "
				+ "same as '" + candidate.name() + "' (" + candidate.description() + ")? " + switch (how) {
				case "similar" -> "They share several words, but a narrower or opposite meaning (a restriction, "
						+ "a negation) would be lost. ";
				case "semantic" -> "They share no words but lie close in meaning. ";
				default -> "";
				} + "The fact is held until you answer with '" + candidate.name() + "' or \"new\"; '" + candidate.name()
				+ "' also records '" + f.predicate() + "' as its alias when the match was similar or semantic.";
		return questions.create("predicate_resolution", obs.id(), null, f.subject(), f.predicate(), c, payload,
				message);
	}

	/** {@code why} explains a conflict a bound raised; null for the plain one-current-value conflict. */
	Question conflict(Observation obs, Predicate pred, Fact existing, long pendingId, String proposed, String why) {
		var c = new ArrayList<Map<String, Object>>();
		String[][] choices = {{"ended", "the earlier one ended (unknown date)"},
				{"supersede", "the new one replaces the earlier one from now"},
				{"reject", "the new one is wrong; keep the earlier"},
				{"reinterpret", "the new one meant something else; a corrected proposal follows"},
				{"wrong", "the earlier one was wrong from the start; the new one corrects it"}};
		int n = 1;
		for (String[] ch : choices) {
			c.add(choice(n++, ch[0], ch[1], null));
		}
		String message = why != null ? why + " The new fact is held pending." : pred.name()
				+ " allows one current value. \"" + existing.rendering() + "\" is current and \"" + proposed
				+ "\" overlaps it with no event, end date, or 'ended' flag to explain the change. The new fact is "
				+ "held pending. Ask the user which is right.";
		String payload = Json.write(Map.of("existing", existing.ref(), "pending", "f-" + pendingId));
		return questions.create("conflict", obs.id(), pendingId, entities.nameOf(existing.subjectId()), pred.name(), c,
				payload, message);
	}

	/**
	 * The entity's type does not fit the predicate: the fact is held, and the answer either makes the type a kind of
	 * one the predicate accepts, retypes this entity, or dismisses the fact.
	 */
	Question typeMismatch(
		Observation obs, Predicate pred, String position, Entity e, List<String> allowed, boolean newKind,
		String held) {
		var c = new ArrayList<Map<String, Object>>();
		int n = 1;
		for (String kind : allowed) {
			if ("*".equals(kind) || "literal".equals(kind)) {
				continue;
			}
			if (newKind) {
				c.add(choice(n++, "kind:" + kind, "every " + e.type() + " is a kind of " + kind
						+ " (sets the parent of " + e.type() + "; the fact is stored)", null));
			}
			c.add(choice(n++, "retype:" + kind,
					e.name() + " is a " + kind + ", not a " + e.type() + " (retypes the entity; the fact is stored)",
					null));
		}
		c.add(choice(n, "dismiss", "the fact is wrong; drop it", null));
		String message = pred.name() + " expects a " + position + " of type " + allowed + "; " + e.name() + " is "
				+ e.type() + ". The fact is held until you answer: "
				+ (newKind ? "is a " + e.type() + " a kind of " + String.join(" or ", allowed) + " (kind:...), " : "")
				+ "is " + e.name() + " really a " + String.join(" or ", allowed) + " (retype:...), or is the fact "
				+ "wrong (dismiss)?";
		var payload = new LinkedHashMap<String, Object>();
		payload.put("entity", e.ref());
		payload.put("entity_type", e.type());
		payload.put("position", position);
		payload.put("expected", allowed);
		payload.put("held", held);
		return questions.create("type_mismatch", obs.id(), null, e.name(), pred.name(), c, Json.write(payload),
				message);
	}

	/**
	 * What an event type registered from use does to facts, asked once per type: which fitting predicate it opens or
	 * closes, whether it ends the subject, or nothing. Empty when the type is already asked about.
	 */
	Optional<Question> eventEffect(
		Observation obs, EventType t, List<Entity> participants, long eventId, List<Predicate> fitting) {
		if (questions.open(200).stream()
				.anyMatch(q -> "event_effect".equals(q.kind()) && t.name().equals(q.subject()))) {
			return Optional.empty();
		}
		var c = new ArrayList<Map<String, Object>>();
		int n = 1;
		for (Predicate p : fitting) {
			c.add(choice(n++, "opens:" + p.name(),
					"the event starts " + p.name() + (p.functional() ? " and replaces its earlier value" : ""), null));
		}
		for (Predicate p : fitting) {
			c.add(choice(n++, "closes:" + p.name(), "the event ends " + p.name(), null));
		}
		if (participants.size() == 1) {
			c.add(choice(n++, "ends_entity", participants.getFirst().name() + " ceases to exist", null));
		}
		c.add(choice(n, "none", "a plain occurrence with no effect on facts", null));
		String who = participants.isEmpty() ? "its participants"
				: String.join(" and ", participants.stream().map(Entity::name).toList());
		String message = "'" + t.name() + "' is a new event type; the event is stored as an occurrence with no effect "
				+ "on facts. Does it start or end a relation between " + who + "? Answer opens:<predicate>, "
				+ "closes:<predicate>, ends_entity, or none. The answer applies to every event of this type, "
				+ "including those already stored.";
		String payload = Json.write(Map.of("event_type", t.name(), "event", "evt-" + eventId));
		return Optional.of(questions.create("event_effect", obs.id(), null, t.name(), null, c, payload, message));
	}

	/**
	 * What kind of thing an entity type registered from use is, asked once per type: a registered root type as its
	 * parent, or a kind of its own. Empty when the type is already asked about.
	 */
	Optional<Question> typeKind(Observation obs, EntityType t, Entity e, List<String> roots) {
		if (questions.open(200).stream().anyMatch(q -> "type_kind".equals(q.kind()) && t.name().equals(q.subject()))) {
			return Optional.empty();
		}
		var c = new ArrayList<Map<String, Object>>();
		int n = 1;
		for (String root : roots) {
			if (!root.equals(t.name())) {
				c.add(choice(n++, root, "a " + t.name() + " is a kind of " + root, null));
			}
		}
		c.add(choice(n, "none", "a kind of its own", null));
		String message = "'" + t.name() + "' is a new kind of thing (the type of " + e.name() + "). Is a " + t.name()
				+ " a kind of " + String.join(", ", roots.stream().filter(r -> !r.equals(t.name())).toList())
				+ "? Answer with the kind, so that everything that accepts it accepts a " + t.name() + ", or none.";
		String payload = Json.write(Map.of("entity_type", t.name(), "entity", e.ref()));
		return Optional.of(questions.create("type_kind", obs.id(), null, t.name(), null, c, payload, message));
	}

	/** Asks whether {@code top} lies within {@code bound}; empty when the same question is already open. */
	Optional<Question> containment(Observation obs, long top, long bound, long servesFactId) {
		String payload = Json.write(Map.of("entity", "ent-" + top, "within", "ent-" + bound));
		boolean open = questions.open(200).stream()
				.anyMatch(q -> "containment".equals(q.kind()) && payload.equals(q.payload()));
		if (open) {
			return Optional.empty();
		}
		String topName = entities.nameOf(top);
		String boundName = entities.nameOf(bound);
		String serves = facts.get(servesFactId).map(Fact::rendering).orElse("the restriction");
		List<Map<String, Object>> c = List.of(choice(1, "yes",
				topName + " is within " + boundName + " (stores " + topName + " located_in " + boundName + ")", null),
				choice(2, "no", topName + " is not within " + boundName, null));
		String message = "Is " + topName + " within " + boundName + "? The containment chain on record stops at "
				+ topName + ", and \"" + serves + "\" holds only if it is. Answer yes or no.";
		return Optional.of(questions.create("containment", obs.id(), null, topName, "located_in", c, payload, message));
	}

	private static List<Map<String, Object>> numbered(List<Candidate> candidates, String newLabel) {
		var out = new ArrayList<Map<String, Object>>();
		int n = 1;
		for (Candidate c : candidates) {
			var m = choice(n++, c.entity().ref(), c.entity().name() + " (" + c.entity().type() + ")", null);
			m.put("score", c.score());
			out.add(m);
		}
		out.add(choice(n, "new", newLabel, null));
		return out;
	}

	private static LinkedHashMap<String, Object> choice(int n, String id, String label, String match) {
		var m = new LinkedHashMap<String, Object>();
		m.put("n", n);
		m.put("id", id);
		m.put("label", label);
		if (match != null) {
			m.put("match", match);
		}
		return m;
	}

	/**
	 * The proposal fragment an entity question holds: the facts and events that mention the entity, with only the
	 * declared entities those refer to. Carrying the whole proposal made every answer re-resolve every other held name
	 * against the entities the earlier answers had just created.
	 */
	static String heldProposal(Proposal p, EntityRef er) {
		var facts = p.facts().stream().filter(f -> mentions(f, er)).toList();
		var events = p.events().stream().filter(ev -> ev.participants().stream().anyMatch(x -> isRef(x, er))).toList();
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
				|| (e.ref() != null && used.contains(Names.norm(e.ref()))) || used.contains(Names.norm(e.name())))
				.toList();
		return Json.write(new Proposal(p.specVersion(), entities, events, facts, p.predicates()));
	}

	/** The fragment a predicate question holds: the one fact, with the proposal's entities and the definition. */
	static String heldProposal(Proposal p, FactRef f, PredicateDef def) {
		return Json.write(new Proposal(p.specVersion(), p.entities(), List.of(), List.of(f),
				def == null ? List.of() : List.of(def)));
	}

	private static boolean mentions(FactRef f, EntityRef er) {
		return isRef(f.subject(), er) || isRef(f.object(), er) || isRef(f.scope(), er);
	}

	private static boolean isRef(String s, EntityRef er) {
		return s != null && (s.equals(er.ref()) || Names.norm(s).equals(Names.norm(er.name())));
	}
}
