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
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Verifies {@link se.hirt.mnemic.knowledge.Deriver} change-gating and scoped graph loading: writes that cannot affect
 * any derivation rule or implication skip full derivation passes, while relevant writes (including dynamically added
 * rules) still derive, update, and invalidate accurately.
 */
class DeriverGatingTest {

	@Test
	void irrelevantFactsSkipDerivationAcrossRememberCorrectRereadAndForget() {
		try (Engine e = engine("deriver-gate-irrelevant")) {
			long passesAfterStart = e.deriver().passes();

			// Remembering unrelated facts (works_at, lives_in, uses) must not trigger derivation.
			RememberOutcome r1 = remember(e, "I work at Hooli in Gothenburg.",
					proposal().entity("h", "Hooli", "organization").entity("g", "Gothenburg", "place")
							.fact("works_at", "h").fact("lives_in", "g"));
			assertTrue(r1.derived().nothing());
			assertEquals(passesAfterStart, e.deriver().passes(), "irrelevant remember must skip derive()");

			// Correcting an unrelated fact must not trigger derivation.
			long worksFactId = Long.parseLong(r1.applied().facts().getFirst().id().substring(2));
			e.correct(worksFactId, Map.of("object", "Initrode"), "changed employer");
			assertEquals(passesAfterStart, e.deriver().passes(), "irrelevant correct must skip derive()");

			// Re-reading an observation with unrelated facts must not trigger derivation.
			long obsId = r1.observation().observationId();
			e.reread(obsId, proposal().entity("h", "Initrode", "organization").fact("works_at", "h").build());
			assertEquals(passesAfterStart, e.deriver().passes(), "irrelevant reread must skip derive()");

			// Forgetting an observation with only unrelated facts must not trigger derivation.
			assertTrue(e.forget(obsId));
			assertEquals(passesAfterStart, e.deriver().passes(), "irrelevant forget must skip derive()");
		}
	}

	@Test
	void relevantFactsEventsAndMutationsStillDeriveAndInvalidate() {
		try (Engine e = engine("deriver-gate-relevant")) {
			// Populate many unrelated facts so scoped Graph.loadForDerivation is also exercised under load.
			for (int i = 0; i < 60; i++) {
				remember(e, "I use Tool" + i + ".", proposal().entity("t", "Tool" + i, "tool").fact("uses", "t"));
			}
			long passesBeforeKinship = e.deriver().passes();

			// Relevant facts (parent_of + qualifier mother implying gender) trigger derivation.
			RememberOutcome r = remember(e, "Britt is the mother of Mattias and Sara.",
					proposal().entity("b", "Britt", "person").entity("s", "Sara", "person")
							.fact(fact("b", "parent_of", "self", "mother", null, null, null, null, null, null))
							.fact(fact("b", "parent_of", "s", "mother", null, null, null, null, null, null)));
			assertEquals(passesBeforeKinship + 1, e.deriver().passes());
			assertTrue(r.derived().derived() >= 2, "must derive sibling_of and implied gender: " + r.derived());
			long ownerId = e.entities().owner().id();
			long brittId = e.entities().byRef("Britt").orElseThrow().id();
			assertTrue(e.facts().factsOf(ownerId).stream().anyMatch(
					f -> f.current() && "sibling_of".equals(f.predicate()) && "derived".equals(f.derivationKind())));
			assertTrue(e.facts().factsOf(brittId).stream().anyMatch(f -> f.current() && "gender".equals(f.predicate())
					&& "female".equals(f.objectText()) && "derived".equals(f.derivationKind())));

			// Retracting one parent_of fact must run derivation and invalidate the derived sibling_of row.
			long parentFactId = Long.parseLong(r.applied().facts().get(1).id().substring(2));
			long passesBeforeRetract = e.deriver().passes();
			e.correct(parentFactId, Map.of("wrong", true), "mistaken");
			assertEquals(passesBeforeRetract + 1, e.deriver().passes());
			assertFalse(e.facts().factsOf(ownerId).stream().anyMatch(
					f -> f.current() && "sibling_of".equals(f.predicate()) && "derived".equals(f.derivationKind())));

			// A death event (ends_entity = true) must trigger derivation.
			long passesBeforeDeath = e.deriver().passes();
			remember(e, "Britt died in 2020.",
					proposal().entity("b", "Britt", "person").event("ev1", "died", "2020", "b"));
			assertEquals(passesBeforeDeath + 1, e.deriver().passes());
		}
	}

	@Test
	void customDerivedRuleDynamicallyMakesItsPredicateRelevant() {
		try (Engine e = engine("deriver-gate-custom-rule")) {
			// Initially works_at is not relevant to any rule.
			assertFalse(e.deriver().isRelevant("works_at"));
			long passes0 = e.deriver().passes();
			remember(e, "Alice works at Hooli.", proposal().entity("a", "Alice", "person")
					.entity("h", "Hooli", "organization").fact("a", "works_at", "h"));
			assertEquals(passes0, e.deriver().passes());

			// Define a new derived predicate coworker_of over works_at -> ^works_at.
			PredicateDef coworker = new PredicateDef("coworker_of", "people working at the same organisation", "person",
					"person", false, null, true, null, "low", List.of("coworker", "colleague"),
					"{subject} and {object} are coworkers", List.of(), List.of(), null,
					List.of(Map.of("path", List.of("works_at", "^works_at"))));
			remember(e, "Coworkers work at the same organisation.", proposal().predicate(coworker));
			assertTrue(e.deriver().isRelevant("works_at"), "works_at must now be derivation-relevant");

			long passesBeforeBob = e.deriver().passes();
			RememberOutcome bob = remember(e, "Bob works at Hooli.", proposal().entity("b", "Bob", "person")
					.entity("h", "Hooli", "organization").fact("b", "works_at", "h"));
			assertEquals(passesBeforeBob + 1, e.deriver().passes());
			assertEquals(1, bob.derived().derived());
			long aliceId = e.entities().byRef("Alice").orElseThrow().id();
			List<Fact> coworkers = e.facts().factsOf(aliceId).stream()
					.filter(f -> f.current() && "coworker_of".equals(f.predicate())).toList();
			assertEquals(1, coworkers.size(), "Alice and Bob must be derived as coworkers");
		}
	}
}
