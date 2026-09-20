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

import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.recall.RecallResult;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md K11–K15: the seed kinship vocabulary, derived from parents, marriages, and siblings. */
class KinshipTest {

	private static void parents(Engine e) {
		remember(e, "My father is Konrad and my mother is Gunilla.",
				proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
						.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
						.fact(fact("e2", "parent_of", "self", "mother", null, null, null, null, null, null)));
	}

	private static List<String> renderings(Engine e, String predicate) {
		return e.facts().factsOf(e.entities().owner().id()).stream()
				.filter(f -> predicate.equals(f.predicate()) && f.current()).map(Fact::rendering).sorted().toList();
	}

	@Test
	@Scenario("K11")
	void grandparentsAreDerivedWithTheirSideAndGender() {
		try (Engine e = TestHomes.engine("k11-grandparents")) {
			assertEquals(3, e.predicates().rulesOf("grandparent_of").size(), "seeded");
			parents(e);
			RememberOutcome o = remember(e, "Konrad's parents are Astrid and Nils. Gunilla's mother is Anna.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.entity("e3", "Nils Nyberg", "person").entity("e4", "Gunilla Nyberg", "person")
							.entity("e5", "Anna Berg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null))
							.fact(fact("e3", "parent_of", "e1", "father", null, null, null, null, null, null))
							.fact(fact("e5", "parent_of", "e4", "mother", null, null, null, null, null, null)));
			assertTrue(o.derived().derived() >= 3, o.derived().toString());
			assertEquals(List.of("Anna Berg is Mattias Sandell's maternal grandmother",
					"Astrid Nyberg is Mattias Sandell's paternal grandmother",
					"Nils Nyberg is Mattias Sandell's paternal grandfather"), renderings(e, "grandparent_of"));
			RecallResult r = recall(e, "who is Mattias's grandmother");
			assertEquals("matched", r.structured().state(), r.text());
			assertEquals(2, r.structured().facts().size(), "both grandmothers: " + r.text());
			RecallResult gf = recall(e, "who is Mattias's paternal grandfather");
			assertTrue(gf.text().contains("Nils Nyberg"), gf.text());
			assertEquals(5, e.facts().count(), "only the parents were stated");
		}
	}

	@Test
	@Scenario("K12")
	void siblingsAreDerivedFromASharedParentAndAStatedSiblingTakesOver() {
		try (Engine e = TestHomes.engine("k12-siblings")) {
			parents(e);
			RememberOutcome o = remember(e, "Konrad and Gunilla are Oskar's parents.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.entity("e3", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			assertTrue(o.derived().derived() >= 1, o.derived().toString());
			assertEquals(List.of("Mattias Sandell is Oskar Nyberg's sibling"), renderings(e, "sibling_of"));
			long derivedId = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "sibling_of".equals(f.predicate())).findFirst().orElseThrow().id();
			// Stated afterwards, the statement is the row; the derived one gives way and corroborates it.
			RememberOutcome stated = remember(e, "Oskar is my brother.",
					proposal().entity("e1", "Oskar Nyberg", "person")
							.fact(fact("e1", "sibling_of", "self", "brother", null, null, null, null, null, null)));
			assertEquals(1, stated.derived().invalidated(), stated.derived().toString());
			assertEquals(1, stated.derived().corroborated(), stated.derived().toString());
			assertEquals("invalidated", e.facts().get(derivedId).orElseThrow().status());
			assertEquals(List.of("Oskar Nyberg is Mattias Sandell's brother"), renderings(e, "sibling_of"));
			long statedId = Long.parseLong(stated.applied().facts().getFirst().id().substring(2));
			assertEquals("corroborated", e.deriver().unificationOf(e.facts().get(statedId).orElseThrow()));
			// One shared parent derives a sibling; a stated half-sibling is corroborated, not contradicted.
			remember(e, "Gunilla is also Tage's mother.",
					proposal().entity("e1", "Gunilla Nyberg", "person").entity("e2", "Tage Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "mother", null, null, null, null, null, null)));
			assertTrue(renderings(e, "sibling_of").contains("Mattias Sandell is Tage Nyberg's sibling"),
					renderings(e, "sibling_of").toString());
			RememberOutcome half = remember(e, "Tage is my half-brother.",
					proposal().entity("e1", "Tage Nyberg", "person").fact(
							fact("e1", "sibling_of", "self", "half-brother", null, null, null, null, null, null)));
			assertEquals(1, half.derived().corroborated(), half.derived().toString());
			assertTrue(e.consolidate(true).unresolvedDerivations().isEmpty());
		}
	}

	@Test
	@Scenario("K13")
	void auntsUnclesAndCousins() {
		try (Engine e = TestHomes.engine("k13-aunt-cousin")) {
			parents(e);
			remember(e, "Konrad and Gunilla are Oskar's parents.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.entity("e3", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			RememberOutcome o = remember(e, "Britt is Konrad's sister. Her son is Linus.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Konrad Nyberg", "person")
							.entity("e3", "Linus Nyberg", "person")
							.fact(fact("e1", "sibling_of", "e2", "sister", null, null, null, null, null, null))
							.fact(fact("e1", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			assertTrue(o.derived().derived() >= 4, "aunt of two, cousin of two: " + o.derived());
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's aunt"), renderings(e, "aunt_uncle_of"));
			assertEquals(List.of("Mattias Sandell is Linus Nyberg's cousin"), renderings(e, "cousin_of"));
			RecallResult aunt = recall(e, "who is Mattias's aunt");
			assertEquals("matched", aunt.structured().state(), aunt.text());
			assertTrue(aunt.text().contains("Britt Nyberg is Mattias Sandell's aunt"), aunt.text());
			RecallResult cousins = recall(e, "who are Mattias's cousins");
			assertEquals("matched", cousins.structured().state(), cousins.text());
			assertTrue(cousins.text().contains("Linus Nyberg"), cousins.text());
			// A sibling is not a cousin, and nobody is their own.
			List<Fact> all = e.facts().factsOf(e.entities().byRef("Oskar Nyberg").orElseThrow().id());
			assertTrue(all.stream().noneMatch(f -> "cousin_of".equals(f.predicate())
					&& (f.subjectId() == e.entities().owner().id() || f.objectId() == e.entities().owner().id())),
					"Oskar and Mattias are siblings, not cousins");
			assertTrue(all.stream().anyMatch(f -> "cousin_of".equals(f.predicate())), "Oskar is Linus's cousin");
		}
	}

	@Test
	@Scenario("K14")
	void inLawsFollowTheMarriageInTime() {
		try (Engine e = TestHomes.engine("k14-in-laws")) {
			remember(e, "I married Marit in 2010.", proposal().entity("e1", "Marit Sandell", "person")
					.fact(fact("e1", "spouse_of", "self", "wife", null, "2010", null, null, null, null)));
			RememberOutcome o = remember(e, "Marit's parents are Sven and Ingrid; her brother is Erik.",
					proposal().entity("e1", "Marit Sandell", "person").entity("e2", "Sven Lund", "person")
							.entity("e3", "Ingrid Lund", "person").entity("e4", "Erik Lund", "person")
							.fact(fact("e2", "parent_of", "e1", "father", null, null, null, null, null, null))
							.fact(fact("e3", "parent_of", "e1", "mother", null, null, null, null, null, null))
							.fact(fact("e4", "sibling_of", "e1", "brother", null, null, null, null, null, null)));
			assertTrue(o.derived().derived() >= 3, o.derived().toString());
			// Erik's role is known only from a sibling qualifier, which is not trusted for gender: sibling-in-law.
			assertEquals(List.of("Erik Lund is Mattias Sandell's sibling-in-law (since 2010)",
					"Ingrid Lund is Mattias Sandell's mother-in-law (since 2010)",
					"Mattias Sandell is Erik Lund's sibling-in-law (since 2010)",
					"Mattias Sandell is Ingrid Lund's child-in-law (since 2010)",
					"Mattias Sandell is Sven Lund's child-in-law (since 2010)",
					"Sven Lund is Mattias Sandell's father-in-law (since 2010)"), renderings(e, "in_law_of"));
			RecallResult r = recall(e, "who is Mattias's father-in-law");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Sven Lund"), r.text());
			// The divorce ends the marriage; the in-laws end with it, rewritten rather than restated.
			RememberOutcome d = remember(e, "Marit and I divorced in 2020.",
					proposal().entity("e1", "Marit Sandell", "person").event("ev1", "divorced", "2020", "self", "e1"));
			assertEquals(6, d.derived().updated(), d.derived().toString());
			List<String> after = renderings(e, "in_law_of");
			assertTrue(after.stream().allMatch(x -> x.contains("2010 – 2020")), after.toString());
			Fact sven = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "in_law_of".equals(f.predicate()) && "father-in-law".equals(f.qualifier())).findFirst()
					.orElseThrow();
			assertEquals("2020-01-01", sven.validEnd());
			assertEquals("ended", sven.state(e.clock().instant()));
		}
	}

	@Test
	@Scenario("K15")
	void aStepParentIsTheSpouseOfAParentWhoIsNotAParent() {
		try (Engine e = TestHomes.engine("k15-step-parent")) {
			parents(e);
			remember(e, "Konrad and Gunilla were married.",
					proposal().entity("e1", "Gunilla Nyberg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, null, "2012", null, null, null)));
			RememberOutcome o = remember(e, "Konrad married Lena in 2015.",
					proposal().entity("e1", "Lena Berg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2015", null, null, null, null)));
			assertTrue(o.derived().derived() >= 1, o.derived() + " applied " + o.applied());
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (since 2015)"),
					renderings(e, "step_parent_of"));
			assertFalse(renderings(e, "step_parent_of").toString().contains("Gunilla"),
					"a parent is never a step-parent");
			RememberOutcome stated = remember(e, "Lena is my stepmother.",
					proposal().entity("e1", "Lena Berg", "person").fact(
							fact("e1", "step_parent_of", "self", "stepmother", null, null, null, null, null, null)));
			assertEquals(1, stated.derived().corroborated(), stated.derived().toString());
			assertEquals(1, stated.derived().invalidated(), "the derived row gives way to the statement");
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother"), renderings(e, "step_parent_of"));
		}
	}

	@Test
	@Scenario("K16")
	void fullAndHalfSiblingsAreToldApartOnlyWhenBothSidesAreKnown() {
		try (Engine e = TestHomes.engine("k16-half-siblings")) {
			remember(e, "I am male.",
					proposal().fact(fact("self", "gender", "male", null, null, null, null, null, null, null)));
			parents(e);
			remember(e, "Konrad and Gunilla are Oskar's parents.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.entity("e3", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			remember(e, "Gunilla and Sven are Tage's parents.",
					proposal().entity("e1", "Gunilla Nyberg", "person").entity("e2", "Sven Berg", "person")
							.entity("e3", "Tage Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "mother", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "father", null, null, null, null, null, null)));
			remember(e, "Gunilla is Ville's mother.",
					proposal().entity("e1", "Gunilla Nyberg", "person").entity("e2", "Ville Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "mother", null, null, null, null, null, null)));
			assertEquals(List.of("Mattias Sandell is Oskar Nyberg's brother",
					"Mattias Sandell is Tage Nyberg's half-brother", "Mattias Sandell is Ville Nyberg's sibling"),
					renderings(e, "sibling_of"),
					"two shared parents: brother; one, with two known each: half-brother; one, Ville's other unknown: sibling");
			// Ville's father turns up, and is not Konrad: half-brother now.
			remember(e, "Ville's father is Sven.",
					proposal().entity("e1", "Sven Berg", "person").entity("e2", "Ville Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "father", null, null, null, null, null, null)));
			assertTrue(renderings(e, "sibling_of").contains("Mattias Sandell is Ville Nyberg's half-brother"),
					renderings(e, "sibling_of").toString());
		}
	}

	@Test
	@Scenario("K17")
	void aRoleReachesItsOwnPredicateBeforeAFreeQualifierInUse() {
		try (Engine e = TestHomes.engine("k17-routing")) {
			parents(e);
			remember(e, "Konrad married Lena in 2015.",
					proposal().entity("e1", "Lena Berg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2015", null, null, null, null)));
			RememberOutcome stated = remember(e, "Lars was my stepfather.",
					proposal().entity("e1", "Lars Melin", "person")
							.fact(fact("self", "related_to", "e1", "stepfather", null, null, null, null, null, null)));
			RecallResult r = recall(e, "who is Mattias's stepfather");
			assertEquals("step_parent_of", r.structured().predicate(),
					"the dedicated predicate, not related_to: " + r.text());
			RecallResult m = recall(e, "who is Mattias's stepmother");
			assertEquals("matched", m.structured().state(), m.text());
			assertTrue(m.text().contains("Lena Berg is Mattias Sandell's stepmother"), m.text());
			// The stated one is listed as misfiled: no derived fact covers it, so the move is the hint.
			var misfiled = e.consolidate(true).misfiledRelations();
			assertEquals(1, misfiled.size(), misfiled.toString());
			assertEquals(stated.applied().facts().getFirst().id(), misfiled.getFirst().get("fact"));
			assertEquals("step_parent_of", misfiled.getFirst().get("predicate"));
			assertNull(misfiled.getFirst().get("derived"));
			assertTrue(String.valueOf(misfiled.getFirst().get("hint")).contains("\"predicate\": \"step_parent_of\""),
					misfiled.toString());
		}
	}

	@Test
	@Scenario("K38")
	void aBaseFactEndedOnTheDayOfADeathWasEndedByIt() {
		try (Engine e = TestHomes.engine("k38-death-date")) {
			parents(e);
			// The death is on record first; the marriage arrives later, written with the death day as its end.
			remember(e, "Lena died on 4 March 2020.",
					proposal().entity("e1", "Lena Berg", "person").event("ev1", "died", "2020-03-04", "e1"));
			remember(e, "Konrad was married to Lena from 2015 until her death.",
					proposal().entity("e1", "Lena Berg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2015", "2020-03-04", null, null, null)));
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (since 2015)"),
					renderings(e, "step_parent_of"), "ended on the day she died: ended by her death");
			// Written at year precision, the same.
			remember(e, "Konrad was married to Ulla from 2016 until her death.",
					proposal().entity("e1", "Ulla Lind", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2016", "2021", null, null, null)));
			remember(e, "Ulla died on 9 May 2021.",
					proposal().entity("e1", "Ulla Lind", "person").event("ev1", "died", "2021-05-09", "e1"));
			assertTrue(
					renderings(e, "step_parent_of").contains("Ulla Lind is Mattias Sandell's stepmother (since 2016)"),
					renderings(e, "step_parent_of").toString());
			// An end on another day is an end: a divorce, a separation, whatever it was.
			remember(e, "Konrad was married to Maj from 2017 to 2018.",
					proposal().entity("e1", "Maj Ek", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2017", "2018", null, null, null)));
			remember(e, "Maj died in 2023.",
					proposal().entity("e1", "Maj Ek", "person").event("ev1", "died", "2023", "e1"));
			assertTrue(
					renderings(e, "step_parent_of")
							.contains("Maj Ek is Mattias Sandell's stepmother (2017 \u2013 2018)"),
					renderings(e, "step_parent_of").toString());
			// An end another event explains keeps its explanation, even on the year of a death: a divorce is a divorce.
			remember(e, "Konrad married Siv in 2016.",
					proposal().entity("e1", "Siv Alm", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2016", null, null, null, null)));
			remember(e, "Konrad and Siv divorced in 2019.", proposal().entity("e1", "Konrad Nyberg", "person")
					.entity("e2", "Siv Alm", "person").event("ev1", "divorced", "2019", "e1", "e2"));
			remember(e, "Siv died in 2019.",
					proposal().entity("e1", "Siv Alm", "person").event("ev1", "died", "2019", "e1"));
			assertTrue(
					renderings(e, "step_parent_of")
							.contains("Siv Alm is Mattias Sandell's stepmother (2016 \u2013 2019)"),
					renderings(e, "step_parent_of").toString());
			// The marriages themselves keep the ends they were given.
			Fact ulla = e.facts().factsOf(e.entities().byRef("Ulla Lind").orElseThrow().id()).stream()
					.filter(f -> "spouse_of".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2021-01-01", ulla.validEnd());
			assertEquals("stated", ulla.endSource());
			// The record says why a row without an end still stands.
			Fact stepmother = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "step_parent_of".equals(f.predicate()) && f.rendering().startsWith("Lena Berg"))
					.findFirst().orElseThrow();
			Object outlived = e.deriver().derivationOf(stepmother.id()).get("outlived");
			assertNotNull(outlived, e.deriver().derivationOf(stepmother.id()).toString());
			assertTrue(outlived.toString().contains("Lena Berg is Konrad Nyberg's wife"), outlived.toString());
		}
	}

	@Test
	@Scenario("K40")
	void aDeathDoesNotEndTheDeceasedsLastingFacts() {
		try (Engine e = TestHomes.engine("k40-lasting")) {
			parents(e);
			assertTrue(e.predicates().get("parent_of").orElseThrow().lasting());
			assertFalse(e.predicates().get("spouse_of").orElseThrow().lasting());
			remember(e, "Konrad and Gunilla married in 1970.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "husband", null, "1970", null, null, null, null)));
			remember(e, "Konrad died in 2021.",
					proposal().entity("e1", "Konrad Nyberg", "person").event("ev1", "died", "2021", "e1"));
			assertEquals(
					List.of("Gunilla Nyberg is Mattias Sandell's mother", "Konrad Nyberg is Mattias Sandell's father"),
					renderings(e, "parent_of"), "a father stays a father");
			Fact marriage = e.facts().factsOf(e.entities().byRef("Konrad Nyberg").orElseThrow().id()).stream()
					.filter(f -> "spouse_of".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2021-01-01", marriage.validEnd(), "the marriage ended with him");
			// A rebuild replays the log through the same rules.
			e.rebuild();
			assertEquals(
					List.of("Gunilla Nyberg is Mattias Sandell's mother", "Konrad Nyberg is Mattias Sandell's father"),
					renderings(e, "parent_of"), "replayed: a father stays a father");
			assertEquals("2021-01-01", e.facts().factsOf(e.entities().byRef("Konrad Nyberg").orElseThrow().id())
					.stream().filter(f -> "spouse_of".equals(f.predicate())).findFirst().orElseThrow().validEnd());
			// A predicate of the store's own says it in its definition; without it, the deceased's relation ends with them.
			RememberOutcome defs = remember(e, "Britt taught me the violin, and Sten coached me.", proposal()
					.predicate(new PredicateDef("taught", "Subject taught object.", "person", "person", false, null,
							false, null, "low", List.of("taught", "teacher"), null, List.of(), List.of(), null, null,
							null, null, null, Boolean.TRUE))
					.predicate(new PredicateDef("coaches", "Subject coaches object.", "person", "person", false, null,
							false, null, "medium", List.of("coached", "coach"), null, List.of(), List.of()))
					.entity("e1", "Britt Ek", "person").entity("e2", "Sten Alm", "person").fact("e1", "taught", "self")
					.fact("e2", "coaches", "self"));
			assertTrue(e.predicates().get("taught").orElseThrow().lasting());
			assertFalse(e.predicates().get("coaches").orElseThrow().lasting());
			// The definition that said nothing about lasting is answered with what was assumed; the other is not.
			List<Map<String, Object>> definitions = defs.applied().definitions();
			assertTrue(
					definitions.stream()
							.anyMatch(d -> "coaches".equals(d.get("name"))
									&& String.valueOf(d.get("inferred")).contains("assumed={lasting=false}")),
					definitions.toString());
			assertTrue(definitions.stream().noneMatch(d -> "taught".equals(d.get("name"))), definitions.toString());
			assertTrue(e.predicates().get("taught").orElseThrow().lastingStated());
			assertFalse(e.predicates().get("coaches").orElseThrow().lastingStated());
			RememberOutcome deaths = remember(e, "Britt died in 2022, and so did Sten.",
					proposal().entity("e1", "Britt Ek", "person").entity("e2", "Sten Alm", "person")
							.event("ev1", "died", "2022", "e1").event("ev2", "died", "2022", "e2"));
			assertEquals(List.of("Britt Ek taught Mattias Sandell"), renderings(e, "taught"),
					"the deceased's own lasting fact stays");
			assertEquals(List.of("Sten Alm coaches Mattias Sandell (until 2022)"),
					e.facts().factsOf(e.entities().owner().id()).stream().filter(f -> "coaches".equals(f.predicate()))
							.map(Fact::rendering).toList());
			// The closure under the assumed predicate says so, with the way to change it; the seed's does not.
			assertTrue(
					deaths.applied().superseded().stream()
							.anyMatch(m -> "coaches".equals(m.get("predicate")) && String.valueOf(m.get("note"))
									.contains("correct(\"pred:coaches\", {\"lasting\": true})")),
					deaths.applied().superseded().toString());
			assertTrue(
					deaths.applied().superseded().stream()
							.noneMatch(m -> !"coaches".equals(m.get("predicate")) && m.containsKey("note")),
					deaths.applied().superseded().toString());
			// Correctable, and logged; from then on it is stated.
			e.correctPredicate("coaches", Map.of("lasting", true), "a coach stays a coach");
			assertTrue(e.predicates().get("coaches").orElseThrow().lasting());
			assertTrue(e.predicates().get("coaches").orElseThrow().lastingStated());
			// An attribute (a literal-valued predicate) is lasting unless the definition says otherwise.
			remember(e, "Britt's blood type was A.",
					proposal()
							.predicate(new PredicateDef("blood_type_of", null, "person", "literal", true, null, false,
									null, "low", List.of("blood type"), null, List.of(), List.of()))
							.entity("e1", "Britt Ek", "person").fact("e1", "blood_type_of", "A"));
			assertTrue(e.predicates().get("blood_type_of").orElseThrow().lasting(), "an attribute by default");
			remember(e, "Britt died in 2023.",
					proposal().entity("e1", "Britt Ek", "person").event("ev1", "died", "2023", "e1"));
			assertTrue(
					e.facts().factsOf(e.entities().byRef("Britt Ek").orElseThrow().id()).stream()
							.filter(f -> "blood_type_of".equals(f.predicate())).allMatch(f -> f.validEnd() == null),
					"her blood type did not change");
		}
	}

	@Test
	@Scenario("K39")
	void aDeathClosesARelationStatedFromEitherSide() {
		try (Engine e = TestHomes.engine("k39-either-side")) {
			parents(e);
			// The marriage stated with Konrad as the subject: Lena is the object, and her death still ends it.
			remember(e, "Konrad married Lena in 2015.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Lena Berg", "person")
							.fact(fact("e1", "spouse_of", "e2", "husband", null, "2015", null, null, null, null)));
			remember(e, "Lena died in 2020.",
					proposal().entity("e1", "Lena Berg", "person").event("ev1", "died", "2020", "e1"));
			Fact marriage = e.facts().factsOf(e.entities().byRef("Lena Berg").orElseThrow().id()).stream()
					.filter(f -> "spouse_of".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2020-01-01", marriage.validEnd(), "ended with her, whichever side stated it");
			// Her gender is not on record (a husband role says nothing about her), so the role reads neutrally.
			assertEquals(List.of("Lena Berg is Mattias Sandell's step-parent (since 2015)"),
					renderings(e, "step_parent_of"), "and the lasting relation outlives it");
			// Not only symmetric ones: any open fact the deceased stands in, unless the predicate is lasting.
			remember(e, "Konrad reports to Sven.", proposal().entity("e1", "Konrad Nyberg", "person")
					.entity("e2", "Sven Alm", "person").fact("e1", "reports_to", "e2"));
			remember(e, "Sven died in 2022.",
					proposal().entity("e1", "Sven Alm", "person").event("ev1", "died", "2022", "e1"));
			Fact reports = e.facts().factsOf(e.entities().byRef("Konrad Nyberg").orElseThrow().id()).stream()
					.filter(f -> "reports_to".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2022-01-01", reports.validEnd(), "one does not report to the dead");
		}
	}

	@Test
	@Scenario("K21")
	void aDeathDoesNotEndALastingRelation() {
		try (Engine e = TestHomes.engine("k21-death")) {
			parents(e);
			remember(e, "Konrad married Lena in 2015.",
					proposal().entity("e1", "Lena Berg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2015", null, null, null, null)));
			remember(e, "Lena died in 2020.",
					proposal().entity("e1", "Lena Berg", "person").event("ev1", "died", "2020", "e1"));
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (since 2015)"),
					renderings(e, "step_parent_of"), "a late stepmother is still a stepmother");
			Fact marriage = e.facts().factsOf(e.entities().byRef("Lena Berg").orElseThrow().id()).stream()
					.filter(f -> "spouse_of".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2020-01-01", marriage.validEnd(), "the marriage itself ended with her");
			// A divorce is a different matter.
			remember(e, "Konrad married Ulla in 2016.",
					proposal().entity("e1", "Ulla Lind", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2016", null, null, null, null)));
			remember(e, "Konrad and Ulla divorced in 2019.", proposal().entity("e1", "Konrad Nyberg", "person")
					.entity("e2", "Ulla Lind", "person").event("ev1", "divorced", "2019", "e1", "e2"));
			assertTrue(
					renderings(e, "step_parent_of")
							.contains("Ulla Lind is Mattias Sandell's stepmother (2016 \u2013 2019)"),
					renderings(e, "step_parent_of").toString());
			// A relation that changes with time ends when its base does, death or not.
			remember(e, "Coworkers.", proposal().predicate(new PredicateDef("coworker_of",
					"Subject and object work at the same organization at the same time.", "person", "person", false,
					null, true, null, "medium", List.of("coworker", "coworkers"), "{subject} is a coworker of {object}",
					List.of(), List.of(), null, List.of(Map.of("path", List.of("works_at", "^works_at"))))));
			remember(e, "I have worked at Initrode since 2010.", proposal().entity("e1", "Initrode", "organization")
					.fact(fact("self", "works_at", "e1", null, null, "2010", null, null, null, null)));
			remember(e, "Anna joined Initrode in 2012.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Initrode", "organization")
							.fact(fact("e1", "works_at", "e2", null, null, "2012", null, null, null, null)));
			remember(e, "Anna died in 2021.",
					proposal().entity("e1", "Anna Lindqvist", "person").event("ev1", "died", "2021", "e1"));
			Fact coworker = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "coworker_of".equals(f.predicate()) && f.current()).findFirst().orElseThrow();
			assertEquals("2021-01-01", coworker.validEnd(), coworker.rendering());
		}
	}

	@Test
	@Scenario("K23")
	void aSeedRuleAStoreCarriesInAnOlderFormIsRefreshedAtStart() {
		java.nio.file.Path home = TestHomes.fresh("k23-seed-refresh");
		try (Engine e = TestHomes.engine(home)) {
			// The first seed's single rule, with by_gender in the other order a run's map may have given it.
			e.database()
					.write(tx -> tx.update("UPDATE predicate SET rule = ? WHERE name = 'sibling_of'",
							"[{\"path\":[\"^parent_of\",\"parent_of\"],\"qualifier\":\"sibling\","
									+ "\"by_gender\":{\"male\":\"brother\",\"female\":\"sister\"}}]"));
			e.predicates().reload();
			assertEquals(1, e.predicates().rulesOf("sibling_of").size());
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(3, e.predicates().rulesOf("sibling_of").size(), "the three tiers, whatever the old order");
			// A rule the user set is left alone across restarts.
			e.correctPredicate(
					"cousin_of", Map.of("defined_as", List.of(Map.of("path",
							List.of("^parent_of", "sibling_of", "parent_of"), "qualifier", "first cousin"))),
					"my wording");
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals("first cousin", e.predicates().rulesOf("cousin_of").getFirst().qualifier());
		}
	}

	@Test
	@Scenario("K25")
	void aRoleOnTheWrongSideOfASiblingFactDoesNotDecideAGender() {
		try (Engine e = TestHomes.engine("k25-gender-source")) {
			remember(e, "I married Marit in 2010.", proposal().entity("e1", "Marit Sandell", "person")
					.fact(fact("e1", "spouse_of", "self", "wife", null, "2010", null, null, null, null)));
			// A sibling fact with the role attached to the wrong side, as a reading may do: it says nothing of David.
			remember(e, "David is Marit's brother.",
					proposal().entity("e1", "David Lund", "person").entity("e2", "Marit Sandell", "person")
							.fact(fact("e1", "sibling_of", "e2", "half-sister", null, null, null, null, null, null)));
			List<String> inLaws = renderings(e, "in_law_of");
			assertTrue(inLaws.contains("David Lund is Mattias Sandell's sibling-in-law (since 2010)"),
					inLaws.toString());
			assertTrue(inLaws.stream().noneMatch(x -> x.contains("David Lund is Mattias Sandell's sister-in-law")),
					inLaws.toString());
			// A stated parent role is trusted; so is the gender given outright.
			remember(e, "David is Lisa's father.",
					proposal().entity("e1", "David Lund", "person").entity("e2", "Lisa Lund", "person")
							.fact(fact("e1", "parent_of", "e2", "father", null, null, null, null, null, null)));
			assertTrue(
					renderings(e, "in_law_of").contains("David Lund is Mattias Sandell's brother-in-law (since 2010)"),
					renderings(e, "in_law_of").toString());
		}
	}

	@Test
	@Scenario("K26")
	void aGenderPredicateRegisteredFromUseIsTakenOverByTheSeed() {
		java.nio.file.Path home = TestHomes.fresh("k26-gender-adopted");
		try (Engine e = TestHomes.engine(home)) {
			parents(e);
			remember(e, "Britt is Konrad's sibling.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "sibling_of", "e2", null, null, null, null, null, null, null)));
			// As a store from before the seed had it: 'gender' registered from a caller's use, bare, with a fact.
			e.database().write(tx -> {
				tx.update("UPDATE predicate SET seed = 0, inferred = 1, render = '{subject} gender {object}', "
						+ "domain = '[\"*\"]', range = '[\"*\"]', lexicon = '[\"gender\"]' WHERE name = 'gender'");
				return null;
			});
			e.predicates().reload();
			remember(e, "David's gender is male.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("e1", "gender", "male", null, null, null, null, null, null, null)));
			assertTrue(e.predicates().get("gender").orElseThrow().isInferred());
		}
		try (Engine e = TestHomes.engine(home)) {
			var gender = e.predicates().get("gender").orElseThrow();
			assertTrue(gender.seed() && !gender.isInferred(), "adopted by the seed at start");
			assertEquals("{subject} is {object}", gender.render());
			long britt = e.entities().byRef("Britt Nyberg").orElseThrow().id();
			assertTrue(
					e.facts().factsOf(britt).stream().anyMatch(
							f -> "gender".equals(f.predicate()) && "Britt Nyberg is male".equals(f.rendering())),
					"re-rendered the seed's way");
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's uncle"), renderings(e, "aunt_uncle_of"),
					"and read by the rules from the first derivation");
			assertTrue(e.consolidate(true).inferredVocabulary().stream()
					.noneMatch(m -> "gender".equals(m.get("predicate"))), "nothing left to define");
		}
	}

	@Test
	@Scenario("K40")
	void aLastingSymmetricRelationSurvivesADeathAndACorrectionStandsAcrossRestarts() {
		Path home = TestHomes.fresh("k40-symmetric");
		try (Engine e = TestHomes.engine(home)) {
			remember(e, "Oskar is my brother.", proposal().entity("e1", "Oskar Nyberg", "person")
					.fact(fact("e1", "sibling_of", "self", "brother", null, null, null, null, null, null)));
			remember(e, "Oskar died in 2020.",
					proposal().entity("e1", "Oskar Nyberg", "person").event("ev1", "died", "2020", "e1"));
			assertEquals(List.of("Oskar Nyberg is Mattias Sandell's brother"), renderings(e, "sibling_of"),
					"a brother stays a brother, whichever side he was stated from");
			// The owner may decide otherwise for a seed predicate, and the seed does not undo it at the next start.
			e.correctPredicate("parent_of", Map.of("lasting", false), "I want ends on record");
			// A store that defined born_in itself before the seed knew it holds the same relation: claimed at start.
			e.database().write(tx -> tx.update("UPDATE predicate SET seed = 0, lasting = 0 WHERE name = ?", "born_in"));
		}
		try (Engine e = TestHomes.engine(home)) {
			assertFalse(e.predicates().get("parent_of").orElseThrow().lasting(), "the correction stands");
			assertTrue(e.predicates().get("sibling_of").orElseThrow().lasting(), "the others keep the seed's flag");
			assertTrue(e.predicates().get("born_in").orElseThrow().lasting(), "claimed by name and kind");
		}
	}

	@Test
	@Scenario("K40")
	void changingLastingByCorrectionReDerives() {
		try (Engine e = TestHomes.engine("k40-rederive")) {
			parents(e);
			remember(e, "Konrad married Lena in 2015.",
					proposal().entity("e1", "Lena Berg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2015", null, null, null, null)));
			remember(e, "Lena died in 2020.",
					proposal().entity("e1", "Lena Berg", "person").event("ev1", "died", "2020", "e1"));
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (since 2015)"),
					renderings(e, "step_parent_of"));
			Map<String, Object> ended = e.correctPredicate("step_parent_of", Map.of("lasting", false),
					"a step-parent is a former one after a death");
			assertTrue(ended.containsKey("derived"), "the correction re-derives: " + ended);
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (2015 \u2013 2020)"),
					renderings(e, "step_parent_of"));
			e.correctPredicate("step_parent_of", Map.of("lasting", true), "no, a late stepmother is a stepmother");
			assertEquals(List.of("Lena Berg is Mattias Sandell's stepmother (since 2015)"),
					renderings(e, "step_parent_of"));
		}
	}

	@Test
	@Scenario("K38")
	void onlyTheFirstParticipantOfADeathDied() {
		try (Engine e = TestHomes.engine("k38-witness")) {
			parents(e);
			remember(e, "Lena died on 4 March 2020, Konrad at her side.", proposal().entity("e1", "Lena Berg", "person")
					.entity("e2", "Konrad Nyberg", "person").event("ev1", "died", "2020-03-04", "e1", "e2"));
			remember(e, "Konrad was married to Vera from 2016 until 4 March 2020.",
					proposal().entity("e1", "Vera Holm", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "spouse_of", "e2", "wife", null, "2016", "2020-03-04", null, null, null)));
			assertTrue(
					renderings(e, "step_parent_of")
							.contains("Vera Holm is Mattias Sandell's stepmother (2016 \u2013 2020-03-04)"),
					"Konrad did not die that day, he was there: " + renderings(e, "step_parent_of"));
			// A store from before participants kept their order: the one whose record ended is still the one.
			e.database().write(tx -> tx.update("UPDATE event_participant SET position = NULL"));
			e.deriver().derive();
			assertTrue(
					renderings(e, "step_parent_of")
							.contains("Vera Holm is Mattias Sandell's stepmother (2016 – 2020-03-04)"),
					renderings(e, "step_parent_of").toString());
		}
	}

}
