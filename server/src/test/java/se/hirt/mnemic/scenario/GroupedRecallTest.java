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
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.recall.RecallResult.Structured;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * EVALUATION.md F21–F24 and J12: a verdict carries its facts, a question with several relations is answered relation by
 * relation, names in an open question are candidates, and a group word asks every relation in the group.
 */
class GroupedRecallTest {

	/** Mattias's parents, siblings, wife, and her family, plus a colleague: enough kin for every group member. */
	private static void family(Engine e) {
		remember(e, "My parents are Konrad and Gunilla.",
				proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
						.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
						.fact(fact("e2", "parent_of", "self", "mother", null, null, null, null, null, null)));
		remember(e, "Oskar is my brother and Klara is my sister.",
				proposal().entity("e1", "Oskar Nyberg", "person").entity("e2", "Klara Nyberg", "person")
						.fact(fact("e1", "sibling_of", "self", "brother", null, null, null, null, null, null))
						.fact(fact("e2", "sibling_of", "self", "sister", null, null, null, null, null, null)));
		remember(e, "My wife is Anna Lindqvist. Her brother is Erik Lindqvist.",
				proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Erik Lindqvist", "person")
						.fact(fact("e1", "spouse_of", "self", "wife", null, null, null, null, null, null))
						.fact(fact("e2", "sibling_of", "e1", "brother", null, null, null, null, null, null)));
		remember(e, "Bo Berg and I work at Initrode.", proposal().entity("e1", "Bo Berg", "person")
				.entity("e2", "Initrode", "organization").fact("e1", "works_at", "e2").fact("self", "works_at", "e2"));
	}

	private static List<String> renderings(Structured s) {
		return s.facts().stream().map(Fact::rendering).sorted().toList();
	}

	private static Structured also(RecallResult r, String predicate) {
		return r.structured().also().stream().filter(s -> predicate.equals(s.predicate())).findFirst().orElse(null);
	}

	@Test
	@Scenario("F21")
	void aMatchedVerdictCarriesItsFacts() {
		try (Engine e = TestHomes.engine("f21-verdict-facts")) {
			family(e);
			RecallResult r = e.recall().recall("who are Mattias's siblings", null, 120, 10);
			assertEquals("matched", r.structured().state(), r.text());
			assertEquals(2, r.structured().facts().size(), r.text());
			String verdict = r.text().lines().filter(l -> l.startsWith("structured: ")).findFirst().orElseThrow();
			assertTrue(verdict.contains("sibling_of → 2 facts: "), verdict);
			assertTrue(verdict.contains("Oskar Nyberg is Mattias Sandell's brother [f-"), verdict);
			assertTrue(verdict.contains("Klara Nyberg is Mattias Sandell's sister [f-"), verdict);
			// The budget is too small for the observations, and the facts are there all the same.
			assertTrue(r.hits().stream().allMatch(h -> h.shown().isEmpty()), "no observation text fits: " + r.text());
			assertTrue(r.tokensUsed() > 0, "the verdict's facts are paid for");
			// A long list is counted past the first twelve, not listed whole.
			for (String name : List.of("Alva Ek", "Bengt Alm", "Cilla Dahl", "Dag Holm", "Ebba Falk", "Filip Lund",
					"Greta Modig", "Hugo Palm", "Ida Rask", "Jonas Strand", "Kajsa Toll", "Love Udd", "Maja Vik",
					"Nils Ygg")) {
				remember(e, "I know " + name + ".",
						proposal().entity("e1", name, "person").fact("self", "knows", "e1"));
			}
			RecallResult many = recall(e, "who does Mattias know");
			assertEquals(14, many.structured().facts().size(), many.text());
			assertTrue(many.text().contains("; and 2 more"), many.text());
		}
	}

	@Test
	@Scenario("F22")
	void aQuestionWithSeveralRelationsIsAnsweredRelationByRelation() {
		try (Engine e = TestHomes.engine("f22-compound")) {
			family(e);
			RecallResult r = recall(e, "Mattias's parents and Anna's siblings");
			Structured s = r.structured();
			assertEquals("matched", s.state(), r.text());
			assertEquals("parent_of", s.predicate(), r.text());
			assertEquals("Mattias Sandell", s.entityName());
			assertEquals(
					List.of("Gunilla Nyberg is Mattias Sandell's mother", "Konrad Nyberg is Mattias Sandell's father"),
					renderings(s));
			assertEquals(1, s.also().size(), r.text());
			Structured siblings = also(r, "sibling_of");
			assertNotNull(siblings, r.text());
			assertEquals("Anna Lindqvist", siblings.entityName(), "siblings are Anna's, not Mattias's: " + r.text());
			assertEquals(List.of("Erik Lindqvist is Anna Lindqvist's brother"), renderings(siblings));
			assertTrue(r.text().contains("also: matched Anna Lindqvist · sibling_of → 1 fact: Erik Lindqvist"),
					r.text());
			// The other way round, the verdict follows the question.
			RecallResult reversed = recall(e, "Anna's siblings and Mattias's parents");
			assertEquals("sibling_of", reversed.structured().predicate(), reversed.text());
			assertEquals("Anna Lindqvist", reversed.structured().entityName());
			assertEquals("parent_of", also(reversed, "parent_of").predicate());
			assertEquals("Mattias Sandell", also(reversed, "parent_of").entityName());
			// "the siblings of Anna" binds after the word as "Anna's siblings" binds before it.
			RecallResult of = recall(e, "the siblings of Anna");
			assertEquals("Anna Lindqvist", of.structured().entityName(), of.text());
			assertEquals(1, of.structured().facts().size(), of.text());
			// A relation tied to nobody that finds nothing is left out; one tied to someone is said, and "and cousins"
			// after "Mattias's parents" is tied to Mattias.
			RecallResult untied = recall(e, "Mattias's parents and the cousins");
			assertEquals("parent_of", untied.structured().predicate());
			assertTrue(untied.structured().also().isEmpty(),
					"cousins found nothing and were bound to nobody: " + untied.text());
			RecallResult shared = recall(e, "Mattias's parents and cousins");
			assertEquals(1, shared.structured().also().size(), shared.text());
			assertTrue(shared.text().contains("also: MISS — Mattias Sandell · cousin_of"), shared.text());
			RecallResult tied = recall(e, "Mattias's parents and Anna's cousins");
			assertEquals(1, tied.structured().also().size(), tied.text());
			assertEquals("miss", tied.structured().also().getFirst().state(), tied.text());
			assertTrue(tied.text().contains("also: MISS — Anna Lindqvist · cousin_of"), tied.text());
		}
	}

	@Test
	@Scenario("F23")
	void namesInAnOpenQuestionAreCandidatesNotConditions() {
		try (Engine e = TestHomes.engine("f23-candidates")) {
			family(e);
			RecallResult both = recall(e, "Mattias's parents Konrad and Gunilla");
			assertEquals("matched", both.structured().state(), both.text());
			assertEquals(2, both.structured().facts().size(), "either name is enough: " + both.text());
			// A yes/no question still holds every name to account.
			RecallResult polar = recall(e, "are Konrad and Bo Berg Mattias's parents");
			assertFalse(polar.structured().matched(), "no fact touches both: " + polar.text());
			// Facts that exist but touch none of the names are said to exist, as near-misses.
			RecallResult wrong = recall(e, "Mattias's siblings Konrad");
			assertEquals("miss", wrong.structured().state(), wrong.text());
			assertEquals(2, wrong.structured().nearMisses().size(), wrong.text());
			assertTrue(wrong.text().contains("the 2 sibling_of facts for Mattias Sandell touch none of Konrad Nyberg"),
					wrong.text());
			assertFalse(wrong.text().contains("no sibling_of fact for"), wrong.text());
		}
	}

	@Test
	@Scenario("F24")
	void aGroupWordAsksEveryRelationInTheGroup() {
		try (Engine e = TestHomes.engine("f24-group")) {
			family(e);
			PredicateRegistry.Group family = e.predicates().group("family").orElseThrow();
			assertTrue(family.seed());
			assertTrue(e.predicates().membersOf("family").stream().map(Predicate::name).toList()
					.containsAll(List.of("parent_of", "spouse_of", "sibling_of", "in_law_of", "cousin_of")));
			RecallResult r = recall(e, "Mattias's family");
			Structured s = r.structured();
			assertEquals("matched", s.state(), r.text());
			assertEquals("family", s.group(), r.text());
			assertEquals("parent_of", s.predicate(), "the first member that answered: " + r.text());
			List<String> answered = s.all().stream().filter(Structured::answered).map(Structured::predicate).toList();
			assertTrue(answered.containsAll(List.of("parent_of", "spouse_of", "sibling_of", "in_law_of")), r.text());
			assertTrue(r.text().contains("structured: matched Mattias Sandell · parent_of (via family) → 2 facts: "),
					r.text());
			assertTrue(r.text().contains("also: matched Mattias Sandell · spouse_of (via family) → 1 fact: "),
					r.text());
			assertTrue(r.text().contains("also: matched Mattias Sandell · sibling_of (via family) → 2 facts: "),
					r.text());
			assertTrue(r.text().contains("Erik Lindqvist is Mattias Sandell's sibling-in-law"),
					"derived kin too: " + r.text());
			String nothing = r.text().lines()
					.filter(l -> l.startsWith("also: nothing under family for Mattias Sandell: ")).findFirst()
					.orElseThrow(() -> new AssertionError(r.text()));
			assertTrue(nothing.contains("partner_of") && nothing.contains("cousin_of"), nothing);
			assertEquals(1, r.text().lines().filter(l -> l.startsWith("also: nothing under")).count(), r.text());
			assertFalse(r.text().contains("works_at"), "a colleague is not family: " + r.text());
			// The group's other words, and a relation named beside the group word probed once, by its own word.
			assertEquals("family", recall(e, "what do you know about Mattias's relatives").structured().group());
			RecallResult beside = recall(e, "Mattias's siblings and family");
			assertEquals("sibling_of", beside.structured().predicate(), beside.text());
			assertNull(beside.structured().group(), "named by its own word: " + beside.text());
			assertEquals(1, beside.structured().all().stream().filter(v -> "sibling_of".equals(v.predicate())).count());
		}
		// The store's language's words reach the group too.
		Path home = TestHomes.fresh("f24-familie");
		try (Engine e = TestHomes.engine(home, Lang.DE)) {
			remember(e, "Meine Eltern sind Konrad und Gunilla.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.fact(fact("e1", "parent_of", "self", "vater", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "self", "mutter", null, null, null, null, null, null)));
			RecallResult r = recall(e, "Mattias' Familie");
			assertEquals("family", r.structured().group(), r.text());
			assertEquals(2, r.structured().facts().size(), r.text());
		}
	}

	@Test
	@Scenario("J12")
	void predicatesFormGroupsAndGroupsNest() {
		try (Engine e = TestHomes.engine("j12-groups")) {
			remember(e, "Our dog is called Rufus.",
					proposal()
							.predicate(new PredicateDef("has_pet", "Subject keeps object as a pet.", "person", "thing",
									false, null, false, null, "low", List.of(), null, List.of(), List.of(), null, null,
									null, null, List.of("pets")))
							.entity("e1", "Rufus", "thing").fact("self", "has_pet", "e1"));
			Predicate hasPet = e.predicates().get("has_pet").orElseThrow();
			assertEquals(List.of("pets"), hasPet.groups());
			PredicateRegistry.Group pets = e.predicates().group("pets").orElseThrow();
			assertFalse(pets.seed());
			assertTrue(pets.lexicon().contains("pets"), "the name's words cue it: " + pets.lexicon());
			assertEquals(List.of("has_pet"), e.predicates().membersOf("pets").stream().map(Predicate::name).toList());
			RecallResult r = recall(e, "Mattias's pets");
			assertEquals("matched", r.structured().state(), r.text());
			assertEquals("pets", r.structured().group(), r.text());
			assertEquals("has_pet", r.structured().predicate());
			// More words, a description, and words in another language, all logged.
			Map<String, Object> corrected = e.correctGroup("pets",
					Map.of("lexicon", List.of("pets", "pet", "animals"), "description", "The animals of the house.",
							"renders", Map.of("de", Map.of("lexicon", List.of("haustiere", "tiere")))),
					"more words");
			assertEquals(List.of("has_pet"), corrected.get("members"));
			assertEquals("pets", recall(e, "Mattias's animals").structured().group());
			assertEquals(List.of("haustiere", "tiere"), e.predicates().groupLexiconIn("pets", "de"));
			List<?> changes = (List<?>) corrected.get("changes");
			assertEquals(3, changes.size(), changes.toString());
			// Groups nest: a word for the outer group reaches the inner group's predicates, and a seed predicate
			// joins a group by correction.
			e.correctGroup("pets", Map.of("groups", List.of("household")), "pets are part of the household");
			assertTrue(e.predicates().group("household").isPresent(), "registered from its first mention");
			remember(e, "I own a Volvo.", proposal().entity("e1", "the Volvo", "thing").fact("self", "owns", "e1"));
			e.correctPredicate("owns", Map.of("groups", List.of("household")), "the house's things");
			assertEquals(List.of("household"), e.predicates().get("owns").orElseThrow().groups());
			assertEquals(List.of("owns", "has_pet"),
					e.predicates().membersOf("household").stream().map(Predicate::name).toList());
			RecallResult household = recall(e, "Mattias's household");
			List<String> predicates = household.structured().all().stream().filter(Structured::answered)
					.map(Structured::predicate).sorted().toList();
			assertEquals(List.of("has_pet", "owns"), predicates, household.text());
			// A group may belong to several groups; one that would contain itself is refused.
			e.correctGroup("pets", Map.of("groups", List.of("household", "family")), "pets are family too");
			assertEquals(List.of("household", "family"), e.predicates().group("pets").orElseThrow().groups());
			assertTrue(e.predicates().membersOf("family").stream().anyMatch(p -> "has_pet".equals(p.name())));
			MnemicException cycle = assertThrows(MnemicException.class,
					() -> e.correctGroup("household", Map.of("groups", List.of("pets")), "loop"));
			assertTrue(cycle.getMessage().contains("would contain itself"), cycle.getMessage());
			// The registry lists the groups; a predicate's entry names its own.
			assertTrue(e.predicates().groups().stream().map(PredicateRegistry.Group::name).toList()
					.containsAll(List.of("family", "pets", "household")));
			assertEquals(List.of("household"), e.predicates().get("owns").orElseThrow().groups());
			assertFalse(e.consolidate(true).inferredVocabulary().stream()
					.anyMatch(m -> String.valueOf(m).contains("has_pet")), "a definition with groups is a definition");
		}
	}
}
