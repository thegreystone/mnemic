/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.scenario;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md family R: the language of the fact layer (MNEMIC_LANGUAGE). */
class LanguageTest {

	private static Fact stored(Engine e, RememberOutcome o, int i) {
		return e.facts().get(Long.parseLong(o.applied().facts().get(i).id().substring(2))).orElseThrow();
	}

	@Test
	@Scenario("R1")
	void aGermanStoreRendersFactsInGerman() {
		try (Engine e = TestHomes.engine(TestHomes.fresh("r1-de"), Lang.DE)) {
			assertEquals("de", e.lang().code());
			RememberOutcome job = remember(e, "Ich arbeite seit 2018 bei Hooli.",
					proposal().entity("e1", "Hooli", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "2018", null, null, null, null)));
			assertEquals("Mattias Sandell arbeitet bei Hooli (seit 2018)", stored(e, job, 0).rendering());
			RememberOutcome mother = remember(e, "Marit ist Oskars Mutter.",
					proposal().entity("e1", "Marit Nyberg", "person").entity("e2", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "mother", null, null, null, null, null, null)));
			assertEquals("Marit Nyberg ist Mutter von Oskar Nyberg", stored(e, mother, 0).rendering(),
					"the qualifier is stored as the vocabulary's word and rendered in German");
			assertEquals("mother", stored(e, mother, 0).qualifier());
			RememberOutcome boat = remember(e, "Ich besitze kein Boot.",
					TestHomes.proposal().entity("e1", "Boot", "thing")
							.fact(new se.hirt.mnemic.proposal.Proposal.FactRef("self", "owns", "e1", null, null, null,
									null, List.of(), null, null, Boolean.TRUE, null)));
			assertEquals("Mattias Sandell besitzt Boot nicht", stored(e, boat, 0).rendering(),
					"the language's negation template");
			RememberOutcome believed = remember(e, "Ich glaube, ich wohne in Zug.",
					proposal().entity("e1", "Zug", "place").fact(new se.hirt.mnemic.proposal.Proposal.FactRef("self",
							"lives_in", "e1", null, null, null, null, List.of(), null, 0.5)));
			assertEquals("Mattias Sandell wohnt in Zug (vermutet)", stored(e, believed, 0).rendering());
			// A German question finds the predicate through the language's cue words.
			RecallResult r = recall(e, "wo arbeitet Mattias");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("works_at", r.structured().predicate());
			// English cue words still work in a German store.
			assertTrue(recall(e, "where does Mattias work").structured().matched());
		}
	}

	@Test
	@Scenario("R2")
	void switchingTheLanguageReRendersEveryFactFromWhatIsStored() {
		Path home = TestHomes.fresh("r2-switch");
		long factId;
		try (Engine e = TestHomes.engine(home, Lang.EN)) {
			RememberOutcome job = remember(e, "I worked at Initrode from 2010 until 2018.",
					proposal().entity("e1", "Initrode", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "2010", "2018", null, null, null)));
			factId = stored(e, job, 0).id();
			assertEquals("Mattias Sandell works at Initrode (2010 – 2018)", stored(e, job, 0).rendering());
		}
		try (Engine e = TestHomes.engine(home, Lang.DE)) {
			assertEquals("Mattias Sandell arbeitet bei Initrode (2010 – 2018)",
					e.facts().get(factId).orElseThrow().rendering(),
					"re-rendered at open from the stored subject, object, and bounds");
			assertEquals("de", e.database().meta("language"));
		}
		try (Engine e = TestHomes.engine(home, Lang.EN)) {
			assertEquals("Mattias Sandell works at Initrode (2010 – 2018)",
					e.facts().get(factId).orElseThrow().rendering(), "and back, losslessly");
		}
	}

	@Test
	@Scenario("R3")
	void anUnknownLanguageIsRefusedAndTheDefaultIsEnglish() {
		assertThrows(IllegalArgumentException.class, () -> Lang.of("fr"));
		assertEquals(Lang.EN, Lang.of(null));
		assertEquals(Lang.EN, Lang.of(""));
		assertEquals(Lang.DE, Lang.of(" De "));
		try (Engine e = TestHomes.engine("r3-default")) {
			assertEquals("en", e.lang().code());
			assertFalse(e.facts().get(1).isPresent());
		}
	}

	@Test
	@Scenario("R4")
	void aDefinedPredicateCarriesTemplatesForOtherLanguages() {
		try (Engine e = TestHomes.engine(TestHomes.fresh("r4-renders"), Lang.DE)) {
			var mentors = new PredicateDef("mentors", "Subject guides the career of object.", "person", "person", false,
					null, false, null, "medium", List.of("mentor", "mentors"), "{subject} mentors {object}", List.of(),
					List.of(), Map.of("de", Map.of("render", "{subject} betreut {object}", "negated",
							"{subject} betreut {object} nicht", "lexicon", List.of("betreut", "betreuen"))));
			RememberOutcome o = remember(e, "Ich betreue Anna.", proposal().entity("e1", "Anna Lindqvist", "person")
					.predicate(mentors).fact("self", "mentors", "e1"));
			assertEquals(List.of(), o.applied().warnings());
			assertEquals("Mattias Sandell betreut Anna Lindqvist", stored(e, o, 0).rendering());
			RememberOutcome not = remember(e, "Oskar betreue ich nicht.",
					proposal().entity("e1", "Oskar Nyberg", "person").fact(new FactRef("self", "mentors", "e1", null,
							null, null, null, List.of(), null, null, Boolean.TRUE, null)));
			assertEquals("Mattias Sandell betreut Oskar Nyberg nicht", stored(e, not, 0).rendering(),
					"the language's own negation template");
			RecallResult r = recall(e, "wer betreut Anna");
			assertEquals("mentors", r.structured().predicate(),
					"the German cue words reach the predicate: " + r.text());
			assertEquals("matched", r.structured().state(), r.text());
			Map<String, Object> corrected = e.correctPredicate("mentors",
					Map.of("renders", Map.of("de", "{subject} ist Mentor von {object}")), "wording");
			assertEquals(2, corrected.get("rerendered_facts"));
			assertEquals("Mattias Sandell ist Mentor von Anna Lindqvist", stored(e, o, 0).rendering());
			List<?> changes = (List<?>) corrected.get("changes");
			assertEquals("renders.de", ((Map<?, ?>) changes.getFirst()).get("field"));
			var french = new PredicateDef("coaches", "Subject coaches object.", "person", "person", false, null, false,
					null, "medium", List.of(), "{subject} coaches {object}", List.of(), List.of(),
					Map.of("fr", "{subject} entraîne {object}"));
			RememberOutcome refused = remember(e, "Ich trainiere Anna.", proposal()
					.entity("e1", "Anna Lindqvist", "person").predicate(french).fact("self", "coaches", "e1"));
			assertEquals(0, refused.applied().facts().size());
			assertTrue(refused.applied().warnings().getFirst().contains("Unsupported language"),
					refused.applied().warnings().toString());
		}
	}
}
