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
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Names of two letters (Bo, Al, Jo, Li, Ng). The identity gate that keeps "it" and "an" from matching anything used to
 * drop them too, so "Bo Berg" and "Eva Berg" shared their only identity token and raised a namesake question, a bare
 * "Bo" was created as a second entity without a question, and "Bo" in a question was not spotted. A short word that
 * stands capitalised where it is written, and is no stopword, is a name (2026-09-26).
 */
class ShortNamesTest {

	@SuppressWarnings("unchecked")
	private static List<String> candidateIds(Map<String, Object> q) {
		return ((List<Map<String, Object>>) q.get("candidates")).stream().map(c -> String.valueOf(c.get("id")))
				.toList();
	}

	@Test
	void aShortGivenNameIsIdentity() {
		try (Engine e = engine("short-names-identity")) {
			remember(e, "Bo Berg works at Hooli.", proposal().entity("b", "Bo Berg", "person")
					.entity("h", "Hooli", "organization").fact("b", "works_at", "h"));
			// Another Berg with another first name is another person, no question asked.
			RememberOutcome eva = remember(e, "Eva Berg lives in Zug.", proposal().entity("ev", "Eva Berg", "person")
					.entity("z", "Zug", "place").fact("ev", "lives_in", "z"));
			assertTrue(eva.applied().questions().isEmpty(), eva.applied().questions().toString());
			assertEquals("created", eva.applied().entities().getFirst().resolution(), eva.applied().toString());
			// A bare "Bo" fits Bo Berg and is asked, as "Anna" would be against "Anna Lindqvist".
			RememberOutcome bare = remember(e, "Bo moved to Bern.", proposal().entity("b2", "Bo", "person")
					.entity("bern", "Bern", "place").fact("b2", "lives_in", "bern"));
			assertFalse(bare.applied().questions().isEmpty(), "asked, not duplicated: " + bare.applied());
			Map<String, Object> q = bare.applied().questions().getFirst();
			assertEquals("Bo", q.get("subject"));
			assertTrue(candidateIds(q).getFirst().startsWith("ent-"), q.toString());
			assertTrue(String.valueOf(q.get("message")).contains("Bo Berg"), q.toString());
		}
	}

	@Test
	void aShortNameInAQuestionIsSpotted() {
		try (Engine e = engine("short-names-spotted")) {
			remember(e, "Anna's husband is Carl; Bo's wife is Dana.",
					proposal().entity("a", "Anna Lindqvist", "person").entity("c", "Carl Lindqvist", "person")
							.entity("b", "Bo Berg", "person").entity("d", "Dana Berg", "person")
							.fact(fact("c", "spouse_of", "a", "husband", null, null, null, null, null, null))
							.fact(fact("d", "spouse_of", "b", "wife", null, null, null, null, null, null)));
			RecallResult wife = recall(e, "Who is Bo's wife?");
			assertEquals("matched", wife.structured().state(), wife.text());
			assertTrue(wife.text().contains("Dana Berg is Bo Berg's wife"), wife.text());
			RecallResult both = recall(e, "Anna's husband and Bo's family");
			assertTrue(both.text().contains("Dana Berg is Bo Berg's wife"), both.text());
			// A short common word is still no name: nothing is spotted for "it" or "so".
			RecallResult none = recall(e, "So, is it true?");
			assertTrue(none.structured().state().equals("unresolved"), none.text());
		}
	}

	@Test
	void whatCountsAsAShortName() {
		assertTrue(Names.looksLikeName("bo", "Bo moved to Bern."));
		assertTrue(Names.looksLikeName("bo", "Bo Berg"));
		assertTrue(Names.looksLikeName("bo", "Is Bo home?"));
		assertFalse(Names.looksLikeName("bo", "the bo staff"), "not capitalised");
		assertFalse(Names.looksLikeName("it", "It is late."), "a stopword");
		assertFalse(Names.looksLikeName("b", "B. Berg"), "one letter");
		assertFalse(Names.looksLikeName("bob", "Bob Berg"), "three letters are identity anyway");
	}

	@Test
	void boAndEvaInEitherOrder() {
		// The pairings that once raised a namesake question or made a silent duplicate, in both orders.
		try (Engine e = engine("short-names-bo-eva")) {
			remember(e, "Bo Berg is a colleague.",
					proposal().entity("b", "Bo Berg", "person").fact("self", "knows", "b"));
			RememberOutcome eva = remember(e, "Eva Berg is a neighbour.",
					proposal().entity("ev", "Eva Berg", "person").fact("self", "knows", "ev"));
			assertTrue(eva.applied().questions().isEmpty(), eva.applied().questions().toString());
			assertEquals("created", eva.applied().entities().getFirst().resolution());
			RememberOutcome kim = remember(e, "Kim Berg and Lo Berg are twins.",
					proposal().entity("k", "Kim Berg", "person").entity("lo", "Lo Berg", "person")
							.fact(fact("k", "sibling_of", "lo", "twin", null, null, null, null, null, null)));
			assertTrue(kim.applied().questions().isEmpty(), kim.applied().questions().toString());
			assertEquals(4, e.entities().active(10).stream().filter(x -> x.name().endsWith("Berg")).count());
			// A bare short name and a bare longer one are treated alike: asked against their full names.
			RememberOutcome bo = remember(e, "Bo called.",
					proposal().entity("b2", "Bo", "person").fact("b2", "knows", "self"));
			assertEquals("Bo", bo.applied().questions().getFirst().get("subject"), bo.applied().toString());
			assertTrue(String.valueOf(bo.applied().questions().getFirst().get("message")).contains("Bo Berg"));
			RememberOutcome ev = remember(e, "Eva called too.",
					proposal().entity("ev2", "Eva", "person").fact("ev2", "knows", "self"));
			assertEquals("Eva", ev.applied().questions().getFirst().get("subject"), ev.applied().toString());
			assertTrue(String.valueOf(ev.applied().questions().getFirst().get("message")).contains("Eva Berg"));
		}
		try (Engine e = engine("short-names-eva-bo")) {
			// Eva first, then Bo: the same, the other way round.
			remember(e, "Eva Berg is a neighbour.",
					proposal().entity("ev", "Eva Berg", "person").fact("self", "knows", "ev"));
			RememberOutcome bo = remember(e, "Bo Berg is a colleague.",
					proposal().entity("b", "Bo Berg", "person").fact("self", "knows", "b"));
			assertTrue(bo.applied().questions().isEmpty(), bo.applied().questions().toString());
			assertEquals("created", bo.applied().entities().getFirst().resolution());
			RecallResult who = recall(e, "Who is Bo?");
			assertTrue(who.text().contains("Bo Berg"), who.text());
		}
	}

	@Test
	void theRuleIsAboutWhereTheWordStandsNotItsLanguage() {
		try (Engine e = engine("short-names-language")) {
			remember(e, "Bo Berg wohnt in Zug.",
					proposal().entity("b", "Bo Berg", "person").entity("z", "Zug", "place").fact("b", "lives_in", "z"));
			// A German question: "Wo" stands capitalised at the start and is no English stopword, but it opens only
			// a lookup, and nobody is called Wo; "Bo" is found.
			RecallResult wo = recall(e, "Wo wohnt Bo?");
			assertEquals("Bo Berg", wo.structured().entityName(), wo.text());
			// Three-letter names keep their typo neighbourhood: "Ewa Berg" against "Eva Berg" is still asked.
			remember(e, "Eva Berg is a neighbour.",
					proposal().entity("ev", "Eva Berg", "person").fact("self", "knows", "ev"));
			RememberOutcome ewa = remember(e, "Ewa Berg called.",
					proposal().entity("ew", "Ewa Berg", "person").fact("ew", "knows", "self"));
			assertFalse(ewa.applied().questions().isEmpty(), "a possible typo is asked: " + ewa.applied());
			assertEquals("Ewa Berg", ewa.applied().questions().getFirst().get("subject"));
		}
	}

}
