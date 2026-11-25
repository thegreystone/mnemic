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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.scenario;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Role words in questions, from the author's store (2026-11-25): "mom" and "mother" given to the owner's mother as
 * aliases took the word from the relation, and "who is my mother" and "who is Alexander's mother" lost their verdicts;
 * "who is my mom" was a miss beside the fact that said "mother"; and "what car does mom drive" found no one, while
 * "what car does my mother drive" looked for a car under parent_of.
 */
class RoleWordsTest {

	/**
	 * The owner's parents, his mother called "mom" and "mother" as aliases, her car and her sister, his own car, and a
	 * son whose mother is someone else.
	 */
	private static void family(Engine e) {
		remember(e, "My mother is Gunilla Nyberg, mom to me, and my father is Konrad Nyberg.",
				proposal().entity("g", "Gunilla Nyberg", "person", "mom", "mother")
						.entity("k", "Konrad Nyberg", "person")
						.fact(fact("g", "parent_of", "self", "mother", null, null, null, null, null, null))
						.fact(fact("k", "parent_of", "self", "father", null, null, null, null, null, null)));
		remember(e, "Gunilla drives a Toyota Yaris and lives in Zug; I drive the Zenit 4 and live in Schübelbach.",
				proposal().entity("g", "Gunilla Nyberg", "person").entity("y", "Toyota Yaris", "car")
						.entity("z", "Zenit 4", "car").entity("zug", "Zug", "place").entity("s", "Schübelbach", "place")
						.fact("g", "drives", "y").fact("self", "drives", "z").fact("g", "lives_in", "zug")
						.fact("self", "lives_in", "s"));
		remember(e, "Ingrid Lind is my mother's sister; she lives in Malmö.",
				proposal().entity("i", "Ingrid Lind", "person").entity("g", "Gunilla Nyberg", "person")
						.entity("m", "Malmö", "place")
						.fact(fact("i", "sibling_of", "g", "sister", null, null, null, null, null, null))
						.fact("i", "lives_in", "m"));
		remember(e, "Marit Nyberg is my wife, and Oskar Nyberg is our son.",
				proposal().entity("w", "Marit Nyberg", "person").entity("o", "Oskar Nyberg", "person")
						.fact(fact("w", "spouse_of", "self", "wife", null, null, null, null, null, null))
						.fact(fact("w", "parent_of", "o", "mother", null, null, null, null, null, null))
						.fact(fact("self", "parent_of", "o", "father", null, null, null, null, null, null)));
	}

	private static Set<String> subjects(Engine e, List<Fact> facts) {
		return facts.stream().map(f -> e.entities().nameOf(f.subjectId())).collect(Collectors.toSet());
	}

	private static Set<String> objects(Engine e, List<Fact> facts) {
		return facts.stream().map(f -> e.entities().nameOf(f.objectId())).collect(Collectors.toSet());
	}

	@Test
	@Scenario("T33")
	void aRoleWordThatIsAlsoAnAliasStaysTheRelation() {
		try (Engine e = engine("t33-alias-role")) {
			family(e);
			RecallResult mine = recall(e, "who is my mother");
			assertEquals("matched", mine.structured().state(), mine.text());
			assertEquals("parent_of", mine.structured().predicate(), mine.text());
			assertEquals(Set.of("Gunilla Nyberg"), subjects(e, mine.structured().facts()), mine.text());

			// Someone else's mother is not the owner's because the owner's is called "mother".
			RecallResult oskars = recall(e, "who is Oskar Nyberg's mother");
			assertEquals("matched", oskars.structured().state(), oskars.text());
			assertEquals("Oskar Nyberg", oskars.structured().entityName(), oskars.text());
			assertEquals(Set.of("Marit Nyberg"), subjects(e, oskars.structured().facts()), oskars.text());
			RecallResult ofOskar = recall(e, "who is the mother of Oskar Nyberg");
			assertEquals(Set.of("Marit Nyberg"), subjects(e, ofOskar.structured().facts()), ofOskar.text());

			// "mom" and "dad" are the roles "mother" and "father" on record.
			RecallResult mom = recall(e, "who is my mom");
			assertEquals("matched", mom.structured().state(), mom.text());
			assertEquals(Set.of("Gunilla Nyberg"), subjects(e, mom.structured().facts()), mom.text());
			RecallResult dad = recall(e, "who is my dad");
			assertEquals(Set.of("Konrad Nyberg"), subjects(e, dad.structured().facts()), dad.text());
		}
	}

	@Test
	@Scenario("T34")
	void aRoleWordStandingAloneIsTheOwners() {
		try (Engine e = engine("t34-alone")) {
			family(e);
			RecallResult who = recall(e, "who is mom");
			assertEquals("matched", who.structured().state(), who.text());
			assertEquals("Mattias Sandell", who.structured().entityName(), who.text());
			assertEquals(Set.of("Gunilla Nyberg"), subjects(e, who.structured().facts()), who.text());

			RecallResult car = recall(e, "what car does mom drive");
			assertEquals("matched", car.structured().state(), car.text());
			assertEquals("drives", car.structured().predicate(), car.text());
			assertEquals("Gunilla Nyberg", car.structured().entityName(), car.text());
			assertEquals(Set.of("Toyota Yaris"), objects(e, car.structured().facts()), car.text());
			assertTrue(car.text().contains("via: Gunilla Nyberg is Mattias Sandell's mother"), car.text());

			RecallResult home = recall(e, "where does mom live");
			assertEquals("lives_in", home.structured().predicate(), home.text());
			assertEquals(Set.of("Zug"), objects(e, home.structured().facts()), home.text());

			// A plural, or a word after an article, is no name: the question's own subject keeps it.
			RecallResult brothers = recall(e, "does Konrad Nyberg have brothers");
			assertEquals("Konrad Nyberg", brothers.structured().entityName(), brothers.text());
			RecallResult a = recall(e, "does Konrad Nyberg have a sister");
			assertEquals("Konrad Nyberg", a.structured().entityName(), a.text());
		}
	}

	@Test
	@Scenario("T35")
	void aRoleBeforeARelationIsThePathToWhoHoldsIt() {
		try (Engine e = engine("t35-path")) {
			family(e);
			RecallResult car = recall(e, "what car does my mother drive");
			assertEquals("matched", car.structured().state(), car.text());
			assertEquals("drives", car.structured().predicate(), car.text());
			assertEquals(Set.of("Toyota Yaris"), objects(e, car.structured().facts()), car.text());

			RecallResult aunt = recall(e, "where does my mother's sister live");
			assertEquals("matched", aunt.structured().state(), aunt.text());
			assertEquals("lives_in", aunt.structured().predicate(), aunt.text());
			assertEquals(Set.of("Malmö"), objects(e, aunt.structured().facts()), aunt.text());
			assertEquals(2, aunt.structured().chain().stream().filter(f -> !"lives_in".equals(f.predicate())).count(),
					aunt.text());

			// A role nobody holds is the answer: a miss, and not the owner's own car.
			RecallResult nobody = recall(e, "what car does my grandmother drive");
			assertEquals("miss", nobody.structured().state(), nobody.text());
			assertEquals("grandparent_of", nobody.structured().predicate(), nobody.text());
			assertTrue(nobody.structured().all().stream().flatMap(s -> s.facts().stream())
					.noneMatch(f -> "drives".equals(f.predicate())), nobody.text());
		}
	}
}
