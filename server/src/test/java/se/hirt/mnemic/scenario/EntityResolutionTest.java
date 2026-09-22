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
package se.hirt.mnemic.scenario;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section B: entity resolution, the M1 ladder (owner, exact alias, type compatibility). */
class EntityResolutionTest {

	@Test
	@Scenario("B1")
	void aliasAndCaseVariantsResolveToOneEntity() {
		try (Engine e = engine("b1")) {
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "HooLi moved me to the platform org.",
					proposal().entity("e1", "HooLi", "organization").fact("self", "works_at", "e1"));
			remember(e, "HLI is headquartered in New York.", proposal().entity("e1", "HLI", "organization", "Hooli")
					.entity("e2", "New York", "place").fact("e1", "located_in", "e2"));
			List<Entity> spotted = e.entities().spot("HLI");
			assertEquals(1, spotted.size());
			Entity hooliEntity = spotted.getFirst();
			assertEquals("Hooli", hooliEntity.name());
			assertEquals(hooliEntity.id(), e.entities().byRef("HooLi").orElseThrow().id(), "case variant");
			List<String> aliases = e.entities().aliases(hooliEntity.id());
			// Aliases are keyed on their normalised form, so "HooLi" folds into "Hooli" and still resolves.
			assertTrue(aliases.contains("HLI"), aliases.toString());
			assertEquals(1, e.entities().spot("hooli").size());
			assertEquals(1,
					e.facts().factsOf(hooliEntity.id()).stream().filter(f -> f.predicate().equals("works_at")).count(),
					"the repeated works_at corroborated, not duplicated");
			assertTrue(e.facts().factsOf(hooliEntity.id()).stream().anyMatch(f -> f.predicate().equals("located_in")));
		}
	}

	@Test
	void aThingNamedAfterItsOwnerIsNotTheOwner() {
		try (Engine e = engine("b3-possessive")) {
			remember(e, "I know Marcus Lagergren from the JVM days.",
					proposal().entity("e1", "Marcus Lagergren", "person").fact("self", "knows", "e1"));
			// "Marcus's iPad", a device nobody has placed, shares a word with the person. The possessive says whose
			// it is, not what it is: no question (an assistant went round this one three times on 2026-09-22).
			RememberOutcome ipad = remember(e, "My iPad is cosmetically damaged but still works.",
					proposal().entity("e1", "Marcus's iPad", "device").fact("self", "owns", "e1"));
			// The only question is what kind of thing a "device" is, since the type is new: not who the iPad is.
			assertTrue(ipad.applied().questions().stream().noneMatch(q -> "entity_resolution".equals(q.get("kind"))),
					ipad.applied().questions().toString());
			assertEquals(1, ipad.applied().facts().size(), "the fact is stored, not held: " + ipad.applied());
			ipad.applied().questions().stream().filter(q -> "type_kind".equals(q.get("kind"))).findFirst()
					.ifPresent(q -> e.answer(List.of(new Resolve(q.get("id").toString(), "product"))));
			Entity device = e.entities().byRef("Marcus's iPad").orElseThrow();
			assertNotEquals(e.entities().byRef("Marcus Lagergren").orElseThrow().id(), device.id());
			// Another owner's iPad is another thing, not this one and not a question.
			RememberOutcome malin = remember(e, "Malin's iPad is the older one.",
					proposal().entity("e1", "Malin's iPad", "device").fact("self", "knows", "e1"));
			assertTrue(malin.applied().questions().stream().noneMatch(q -> "entity_resolution".equals(q.get("kind"))),
					malin.applied().questions().toString());
			assertNotEquals(device.id(), e.entities().byRef("Malin's iPad").orElseThrow().id());
			// The same name again is the same thing.
			RememberOutcome again = remember(e, "Marcus's iPad was bought in 2023.",
					proposal().entity("e1", "Marcus's iPad", "device").fact("self", "owns", "e1"));
			assertTrue(again.applied().questions().isEmpty(), again.applied().questions().toString());
			assertEquals(device.id(), e.entities().byRef("Marcus's iPad").orElseThrow().id());
			assertEquals(1, e.facts().factsOf(device.id()).size(), "the same fact corroborated, not duplicated");
		}
	}

	@Test
	void thePossessiveRuleIsAboutNamesNotAboutDevicesOrOwning() {
		try (Engine e = engine("b3-possessive-general")) {
			// Any kind of thing, any predicate, either spelling of the possessive: the possessor is not the thing.
			remember(e, "Anna Lindqvist is my wife.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "spouse_of", "e1"));
			remember(e, "Hooli is where I work.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "Andreas Berg is a colleague.",
					proposal().entity("e1", "Andreas Berg", "person").fact("self", "knows", "e1"));
			RememberOutcome car = remember(e, "Anna's car is in the shop.",
					proposal().entity("e1", "Anna's car", "vehicle").fact("self", "uses", "e1"));
			RememberOutcome office = remember(e, "Hooli's office is in Zürich.",
					proposal().entity("e1", "Hooli's office", "place").entity("e2", "Zürich", "place").fact("e1",
							"located_in", "e2"));
			RememberOutcome bike = remember(e, "Andreas' bike was stolen.",
					proposal().entity("e1", "Andreas' bike", "vehicle").fact("self", "knows", "e1"));
			for (RememberOutcome o : List.of(car, office, bike)) {
				assertTrue(o.applied().questions().stream().noneMatch(q -> "entity_resolution".equals(q.get("kind"))),
						o.applied().questions().toString());
			}
			assertNotEquals(e.entities().byRef("Anna Lindqvist").orElseThrow().id(),
					e.entities().byRef("Anna's car").orElseThrow().id());
			assertNotEquals(e.entities().byRef("Hooli").orElseThrow().id(),
					e.entities().byRef("Hooli's office").orElseThrow().id());
			assertNotEquals(e.entities().byRef("Andreas Berg").orElseThrow().id(),
					e.entities().byRef("Andreas' bike").orElseThrow().id());
			// The owner is still found by name afterwards, without the things named after them getting in the way.
			RememberOutcome anna = remember(e, "Anna Lindqvist changed jobs.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("e1", "works_at", "Initrode"));
			assertTrue(anna.applied().questions().isEmpty(), anna.applied().questions().toString());
			RememberOutcome hooli = remember(e, "I still work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			assertTrue(hooli.applied().questions().isEmpty(), hooli.applied().questions().toString());
			// A name that ends in the possessive names the thing itself: "McDonald's" has no possessor, and its
			// spelling without the apostrophe still finds it as before.
			remember(e, "McDonald's is a client.",
					proposal().entity("e1", "McDonald's", "organization").fact("self", "related_to", "e1"));
			Entity mcd = e.entities().byRef("McDonald's").orElseThrow();
			RememberOutcome plain = remember(e, "McDonalds renewed the contract.",
					proposal().entity("e1", "McDonalds", "organization").fact("self", "related_to", "e1"));
			boolean same = e.entities().byRef("McDonalds").map(x -> x.id() == mcd.id()).orElse(false);
			boolean asked = plain.applied().questions().stream()
					.anyMatch(q -> String.valueOf(q.get("candidates")).contains(mcd.ref()));
			assertTrue(same || asked, "merged or asked, never a stranger: " + plain.applied());
		}
	}

	@Test
	void thePersonStillResolvesAsBeforeBesideAThingNamedAfterHim() {
		try (Engine e = engine("b3-possessive-person")) {
			remember(e, "I know Marcus Lagergren from the JVM days.",
					proposal().entity("e1", "Marcus Lagergren", "person").fact("self", "knows", "e1"));
			Entity lagergren = e.entities().byRef("Marcus Lagergren").orElseThrow();
			RememberOutcome ipad = remember(e, "My iPad is damaged.",
					proposal().entity("e1", "Marcus's iPad", "device").fact("self", "owns", "e1"));
			ipad.applied().questions().stream().filter(q -> "type_kind".equals(q.get("kind"))).findFirst()
					.ifPresent(q -> e.answer(List.of(new Resolve(q.get("id").toString(), "product"))));
			Entity device = e.entities().byRef("Marcus's iPad").orElseThrow();
			// The full name is the person, exactly, and the iPad never comes into it.
			RememberOutcome full = remember(e, "Marcus Lagergren moved to Stockholm.",
					proposal().entity("e1", "Marcus Lagergren", "person").fact("e1", "lives_in", "Stockholm"));
			assertTrue(full.applied().questions().isEmpty(), full.applied().questions().toString());
			assertEquals(lagergren.id(),
					e.entities().byRef(full.applied().entities().getFirst().id()).orElseThrow().id());
			// The first name alone is ambiguous with the person, as a first name always was, and the iPad is not
			// offered: "Marcus" is whose the iPad is, not what it is.
			RememberOutcome first = remember(e, "Marcus called about the talk.",
					proposal().entity("e1", "Marcus", "person").fact("self", "knows", "e1"));
			assertEquals(1, first.applied().questions().size(), first.applied().questions().toString());
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> candidates = (List<Map<String, Object>>) first.applied().questions().getFirst()
					.get("candidates");
			assertTrue(candidates.stream().anyMatch(c -> lagergren.ref().equals(c.get("id"))), candidates.toString());
			assertTrue(candidates.stream().noneMatch(c -> device.ref().equals(c.get("id"))),
					"the iPad is no candidate for a person: " + candidates);
			e.answer(
					List.of(new Resolve(first.applied().questions().getFirst().get("id").toString(), lagergren.ref())));
			assertEquals(lagergren.id(), e.entities().byRef("Marcus").orElseThrow().id(), "Marcus is now his alias");
			// The surname alone is the person too, by the same half-name rule as before.
			RememberOutcome surname = remember(e, "Lagergren is speaking at JFokus.",
					proposal().entity("e1", "Lagergren", "person").fact("self", "knows", "e1"));
			List<Map<String, Object>> qs = surname.applied().questions();
			assertTrue(qs.isEmpty() || qs.getFirst().get("candidates").toString().contains(lagergren.ref()),
					"resolved or asked with him as the candidate: " + surname.applied());
			// And the iPad, asked for by its own name, is still itself.
			assertEquals(device.id(), e.entities().byRef("Marcus's iPad").orElseThrow().id());
			assertEquals("device", device.type());
		}
	}

	@Test
	@Scenario("B3")
	void sameNameDifferentTypeIsAskedOnce() {
		try (Engine e = engine("b3")) {
			remember(e, "I'm reading Java Concurrency in Practice.",
					proposal().entity("e1", "Java Concurrency in Practice", "book").fact("self", "prefers", "e1"));
			remember(e, "Java is the language Mnemic is written in.", proposal().entity("e1", "Mnemic", "project")
					.entity("e2", "Java", "technology").fact("e1", "uses", "e2"));
			Entity tech = e.entities().byRef("Java").orElseThrow();
			// The same name under another kind: the caller cannot know what type it was first stored under, so the
			// exact match is the first candidate and the question is asked.
			RememberOutcome island = remember(e, "Java the island is beautiful.",
					proposal().entity("e1", "Java", "place").fact("self", "prefers", "e1"));
			Map<String, Object> q = island.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"), q.toString());
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> candidates = (List<Map<String, Object>>) q.get("candidates");
			assertEquals(tech.ref(), candidates.getFirst().get("id"), "the exact match comes first: " + q);
			assertEquals(1.0, candidates.getFirst().get("score"));
			e.answer(List.of(new Resolve(q.get("id").toString(), "new")));
			assertEquals(2, e.entities().spot("Java").size(), "technology and place both named Java, kept apart");
			assertNotEquals(tech.id(), e.entities().byRef("Java Concurrency in Practice").orElseThrow().id());
			// Typed, each later mention resolves to its own kind without a word.
			RememberOutcome again = remember(e, "Java the island again.",
					proposal().entity("e1", "Java", "place").fact("self", "prefers", "e1"));
			assertTrue(again.applied().questions().isEmpty(), again.applied().questions().toString());
			assertEquals("place", e.entities().byRef(again.applied().entities().getFirst().id()).orElseThrow().type());
			// Untyped, the name fits both: asked, and the answer settles every later untyped mention.
			RememberOutcome bare = remember(e, "Java again.", proposal().fact("self", "prefers", "Java"));
			Map<String, Object> q2 = bare.applied().questions().getFirst();
			assertEquals("entity_resolution", q2.get("kind"), q2.toString());
			e.answer(List.of(new Resolve(q2.get("id").toString(), tech.ref())));
			RememberOutcome settled = remember(e, "Java once more.", proposal().fact("self", "uses", "Java"));
			assertTrue(settled.applied().questions().isEmpty(), "asked once: " + settled.applied().questions());
			assertEquals(tech.id(),
					e.facts().factsOfObservation(settled.observation().observationId()).getFirst().objectId());
		}
	}

	@Test
	@Scenario("B4")
	void typeMismatchOverridesStringSimilarity() {
		try (Engine e = engine("b4")) {
			remember(e, "Viggo started school today.",
					proposal().entity("e1", "Viggo", "person").fact("self", "knows", "e1"));
			var o = remember(e, "We evaluated Viggo, a load-testing tool.",
					proposal().entity("e1", "Viggo", "technology").fact("self", "uses", "e1"));
			// Not merged on the strength of the letters: asked, with the person first.
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"), q.toString());
			assertTrue(q.get("message").toString().contains("Viggo (person)"), q.toString());
			e.answer(List.of(new Resolve(q.get("id").toString(), "new")));
			List<Entity> viggos = e.entities().spot("Viggo");
			assertEquals(2, viggos.size(), viggos.toString());
			assertTrue(viggos.stream().anyMatch(x -> "technology".equals(x.type())), viggos.toString());
			// Letters alone, across types, never merge: a third Viggo typed person is the person.
			RememberOutcome person = remember(e, "Viggo again.",
					proposal().entity("e1", "Viggo", "person").fact("self", "knows", "e1"));
			assertTrue(person.applied().questions().isEmpty(), person.applied().questions().toString());
			assertEquals("person",
					e.entities().byRef(person.applied().entities().getFirst().id()).orElseThrow().type());
		}
	}

	@Test
	@Scenario("B5")
	void firstPersonResolvesToTheOwner() {
		try (Engine e = engine("b5")) {
			remember(e, "I live in Schübelbach.",
					proposal().entity("e1", "Schübelbach", "place").fact("self", "lives_in", "e1"));
			remember(e, "Mattias moved there in 2014.", proposal().entity("e1", "Mattias", "person")
					.entity("e2", "Schübelbach", "place").fact("e1", "lives_in", "e2"));
			Entity owner = e.entities().owner();
			assertEquals("Mattias Sandell", owner.name());
			List<String> aliases = e.entities().aliases(owner.id());
			assertTrue(aliases.containsAll(List.of("I", "me", "Mattias", "Mattias Sandell")), aliases.toString());
			assertEquals(1,
					e.facts().factsOf(owner.id()).stream().filter(f -> f.predicate().equals("lives_in")).count(),
					"one lives_in fact, corroborated");
			RecallResult r = recall(e, "where does Mattias Sandell live");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("lives_in", r.structured().predicate());
			assertEquals(owner.ref(), r.structured().entity());
		}
	}

	@Test
	void unknownTypeAdoptsTheTypeItLaterLearns() {
		try (Engine e = engine("type-promote")) {
			// Nobody typed Anna; the predicate did: the object of knows is a person.
			remember(e, "Anna is great.", proposal().fact("self", "knows", "Anna"));
			assertEquals("person", e.entities().byRef("Anna").orElseThrow().type());
			// A predicate that takes anything types nothing.
			remember(e, "Bosse is great.", proposal().fact("self", "prefers", "Bosse"));
			assertEquals("unknown", e.entities().byRef("Bosse").orElseThrow().type());
			remember(e, "Anna Lindqvist is a person I know.",
					proposal().entity("e1", "Anna", "person").fact("self", "knows", "e1"));
			assertEquals("person", e.entities().byRef("Anna").orElseThrow().type());
			assertEquals(1, e.entities().spot("Anna").size());
		}
	}
}
