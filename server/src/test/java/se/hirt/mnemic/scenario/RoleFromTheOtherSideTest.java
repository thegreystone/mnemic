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
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Two recall findings from use on a real store (2026-09-25). A symmetric relation's stored qualifier describes the
 * subject, so "Marcus's sister" against "Marcus is Claudia's half-brother" is decided by Claudia's gender on record,
 * not by Marcus's word; and a group word ("family") reaches every relation of its subject even when another part of the
 * question named one of them ("Alexander's children") for someone else.
 */
class RoleFromTheOtherSideTest {

	@Test
	void theRoleAskedForIsTheOtherPersonsAndTheirGenderDecides() {
		try (Engine e = engine("role-other-side")) {
			// Both facts stated from the owner's side: the qualifier is his role, not theirs.
			remember(e, "Christian is my brother.", proposal().entity("c", "Christian Hirt", "person")
					.fact(fact("self", "sibling_of", "c", "brother", null, null, null, null, null, null)));
			remember(e, "Claudia is my half-sister; she is female.",
					proposal().entity("cl", "Claudia Berg", "person")
							.fact(fact("self", "sibling_of", "cl", "half-brother", null, null, null, null, null, null))
							.fact("cl", "gender", "female").fact("self", "gender", "male"));

			RecallResult sister = recall(e, "Mattias's sister");
			assertEquals("matched", sister.structured().state(), sister.text());
			assertTrue(sister.structured().facts().stream().anyMatch(f -> f.rendering().contains("Claudia Berg")),
					sister.text());
			assertTrue(sister.text().contains("Claudia Berg is female on record"), sister.text());
			// Christian's gender is not on record: the same degree from the other side answers, unsettled, and
			// the note says so rather than reading his brother's word as his.
			assertTrue(sister.text().contains("Christian Hirt's gender is not on record"), sister.text());
			assertFalse(sister.text().contains("which covers"),
					"no word of the owner's covers the other's: " + sister.text());

			// The yes/no form: "have" is how the question is asked, not a word the fact must carry.
			RecallResult polar = recall(e, "Does Mattias have a sister?");
			assertEquals("matched", polar.structured().state(), polar.text());
			assertTrue(polar.structured().facts().stream().anyMatch(f -> f.rendering().contains("Claudia Berg")),
					polar.text());

			// A brother asked: Claudia is female on record, so not one; Christian's gender is not on record.
			RecallResult brother = recall(e, "Mattias's brother");
			assertEquals("matched", brother.structured().state(), brother.text());
			assertEquals(1, brother.structured().facts().size(), brother.text());
			assertTrue(brother.structured().facts().getFirst().rendering().contains("Christian Hirt"), brother.text());
			assertTrue(brother.text().contains("records Mattias Sandell's role, not Christian Hirt's"), brother.text());
			RecallResult siblings = recall(e, "Mattias's siblings");
			assertEquals(2, siblings.structured().facts().size(), siblings.text());
		}
	}

	@Test
	void aRoleStatedFromTheOtherSideIsReadWithItsOwnGender() {
		try (Engine e = engine("role-own-side")) {
			// "Clara is my half-sister", stated as Clara's role: the possessor is the object, the word is hers.
			remember(e, "Clara is my half-sister.", proposal().entity("cl", "Clara Berg", "person")
					.fact(fact("cl", "sibling_of", "self", "half-sister", null, null, null, null, null, null)));
			RecallResult sister = recall(e, "Mattias's sister");
			assertEquals("matched", sister.structured().state(), sister.text());
			assertTrue(sister.text().contains("recorded as 'half-sister'"), sister.text());
			RecallResult brother = recall(e, "Mattias's brother");
			assertEquals("miss", brother.structured().state(), "a half-sister is not a brother: " + brother.text());
		}
	}

	@Test
	void aGroupWordReachesARelationAnotherPartOfTheQuestionNamedForSomeoneElse() {
		try (Engine e = engine("group-twice")) {
			remember(e, "Alexander's sons are Max and Leo.",
					proposal().entity("a", "Alexander Hirt", "person").entity("m", "Max Hirt", "person")
							.entity("l", "Leo Hirt", "person")
							.fact(fact("a", "parent_of", "m", "father", null, null, null, null, null, null))
							.fact(fact("a", "parent_of", "l", "father", null, null, null, null, null, null)));
			remember(e, "Christian's parents are Bo and Eva; his partner is Jenny; his daughter is Audrey.",
					proposal().entity("c", "Christian Hirt", "person").entity("b", "Bo Hirt", "person")
							.entity("ev", "Eva Hirt", "person").entity("j", "Jenny Lund", "person")
							.entity("au", "Audrey Hirt", "person")
							.fact(fact("b", "parent_of", "c", "father", null, null, null, null, null, null))
							.fact(fact("ev", "parent_of", "c", "mother", null, null, null, null, null, null))
							.fact("c", "partner_of", "j")
							.fact(fact("c", "parent_of", "au", "father", null, null, null, null, null, null)));
			RecallResult both = recall(e, "Alexander's children and Christian's family");
			String t = both.text();
			assertTrue(t.contains("Alexander Hirt is Max Hirt's father"), t);
			assertTrue(t.contains("Christian Hirt · parent_of (via family)"), "Christian's parents and child: " + t);
			assertTrue(t.contains("Christian Hirt is Audrey Hirt's father"), t);
			assertTrue(t.contains("Christian Hirt is Jenny Lund's partner"), t);
			// The same relation asked twice of the same subject is asked once.
			RecallResult same = recall(e, "Christian's children and family");
			String s = same.text();
			assertEquals(1, s.split("Christian Hirt · parent_of", -1).length - 1, "parent_of once: " + s);
		}
	}

	@Test
	void roleWordsSayAGender() {
		assertEquals("female", Predicate.impliedGender("half-sister"));
		assertEquals("male", Predicate.impliedGender("paternal grandfather"));
		assertEquals("female", Predicate.impliedGender("Female"));
		assertNull(Predicate.impliedGender("sibling"));
		assertNull(Predicate.impliedGender("twin"));
		assertNull(Predicate.impliedGender(null));
	}

	@Test
	void anyRoleGivingRelationReadsTheSameWay() {
		// Not sibling_of in particular: a marriage stated from the owner's side, with the other's gender on record.
		try (Engine e = engine("role-spouse")) {
			remember(e, "I am Anna's husband; she is a woman.",
					proposal().entity("a", "Anna Lindqvist", "person")
							.fact(fact("self", "spouse_of", "a", "husband", null, null, null, null, null, null))
							.fact("a", "gender", "woman"));
			RecallResult wife = recall(e, "Mattias's wife");
			assertEquals("matched", wife.structured().state(), wife.text());
			assertTrue(wife.text().contains("Anna Lindqvist is female on record"), wife.text());
			RecallResult husband = recall(e, "Mattias's husband");
			assertEquals("miss", husband.structured().state(), "she is not his husband: " + husband.text());
			// "has ... got" is the asking, not a word the fact must carry.
			RecallResult polar = recall(e, "Has Mattias got a wife?");
			assertEquals("matched", polar.structured().state(), polar.text());
		}
	}

	@Test
	void aUserDefinedRelationReadsItsOwnRoleWordsThroughImplies() {
		// No English kin word anywhere: a symmetric relation the user defined, whose roles say a gender through
		// 'implies', and a gender recorded as "kvinna" that the gender predicate's own implications read.
		try (Engine e = engine("role-defined")) {
			remember(e, "Crew roles.",
					proposal().predicate(new PredicateDef("crewmate_of", "Subject sails with object.", "person",
							"person", false, null, true, null, "medium", List.of("crewmate", "crewmates"),
							"{subject} is {object}'s {qualifier|crewmate}", List.of("navigatrix", "navigator"),
							List.of(), null, null,
							Map.of("navigatrix", Map.of("gender", "female"), "navigator", Map.of("gender", "male")))));
			remember(e, "Gender words.",
					proposal().predicate(new PredicateDef("gender", null, null, null, null, null, null, null, null,
							List.of(), null, List.of(), List.of(), null, null,
							Map.of("kvinna", Map.of("gender", "female"), "man", Map.of("gender", "male")))));
			remember(e, "I am Kim's navigator; Kim is a kvinna.",
					proposal().entity("k", "Kim Berg", "person")
							.fact(fact("self", "crewmate_of", "k", "navigator", null, null, null, null, null, null))
							.fact("k", "gender", "kvinna"));
			RecallResult navigatrix = recall(e, "Mattias's navigatrix");
			assertEquals("matched", navigatrix.structured().state(), navigatrix.text());
			assertTrue(navigatrix.text().contains("Kim Berg is female on record"), navigatrix.text());
			RecallResult navigator = recall(e, "Mattias's navigator");
			assertEquals("miss", navigator.structured().state(), "Kim is the navigatrix: " + navigator.text());
			// The same relation stated from Kim's side reads her word with its own gender.
			remember(e, "Lova is my navigatrix.", proposal().entity("lo", "Lova Berg", "person")
					.fact(fact("lo", "crewmate_of", "self", "navigatrix", null, null, null, null, null, null)));
			RecallResult two = recall(e, "Mattias's navigatrix");
			assertEquals(2, two.structured().facts().size(), two.text());
		}
	}

	@Test
	void aGroupWordReachesAnyRelationNamedElsewhere() {
		// Not parent_of in particular: a marriage named by its own word for one person, and by "family" for another.
		try (Engine e = engine("group-any-relation")) {
			remember(e, "Anna's husband is Carl; Bosse's wife is Dana.",
					proposal().entity("a", "Anna Lindqvist", "person").entity("c", "Carl Lindqvist", "person")
							.entity("b", "Bosse Berg", "person").entity("d", "Dana Berg", "person")
							.fact(fact("c", "spouse_of", "a", "husband", null, null, null, null, null, null))
							.fact(fact("d", "spouse_of", "b", "wife", null, null, null, null, null, null)));
			RecallResult both = recall(e, "Anna's husband and Bosse's family");
			String t = both.text();
			assertTrue(t.contains("Carl Lindqvist is Anna Lindqvist's husband"), t);
			assertTrue(t.contains("Bosse Berg · spouse_of (via family)"), "Bo's marriage through the group: " + t);
			assertTrue(t.contains("Dana Berg is Bosse Berg's wife"), t);
		}
	}

}
