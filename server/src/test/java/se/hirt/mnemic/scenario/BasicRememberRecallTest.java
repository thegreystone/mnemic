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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section A: basic remember / recall. */
class BasicRememberRecallTest {

	@Test
	@Scenario("A1")
	void storeAndRecallASimpleFact() {
		try (Engine e = engine("a1")) {
			RememberOutcome o = remember(e, "I work at Hooli.",
					proposal().entity("e2", "Hooli", "organization").fact("self", "works_at", "e2"));
			Observation stored = e.observations().get(o.observation().observationId()).orElseThrow();
			assertEquals("I work at Hooli.", stored.text(), "stored verbatim");

			assertEquals(1, o.applied().facts().size(), o.applied().warnings().toString());
			Fact f = e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
			assertEquals("works_at", f.predicate());
			assertEquals("current", f.status());
			assertEquals("explicit", f.derivationKind(), "asserted by the user");
			assertNull(f.validStart(), "valid start unknown");
			assertNull(f.eventId(), "asserted, no event");
			assertEquals(e.entities().owner().id(), f.subjectId());
			assertEquals("Mattias Sandell works at Hooli", f.rendering());
			assertEquals(stored.id(), f.observationId());

			RecallResult r = recall(e, "where does Mattias work");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("works_at", r.structured().predicate());
			assertEquals(1, r.hits().size(), r.text());
			assertTrue(r.hits().getFirst().channels().contains("structured"), r.text());
			assertEquals(f.id(), r.hits().getFirst().facts().getFirst().id());
			assertTrue(r.text().contains("I work at Hooli."), "the observation is the value: " + r.text());
			assertTrue(r.text().contains("structured: matched Mattias Sandell · works_at"), r.text());
		}
	}

	@Test
	@Scenario("A2")
	void recallWithNoMatchingKnowledge() {
		try (Engine e = engine("a2")) {
			remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RecallResult r = recall(e, "what is Mattias's favourite colour");
			assertTrue(r.hits().isEmpty(), "no fabricated hit: " + r.text());
			assertTrue(r.text().contains("no matching observations"), r.text());
			assertFalse(r.structured().matched(), r.structured().toString());
			assertTrue(List.of("miss", "unresolved").contains(r.structured().state()), r.structured().state());
		}
	}

	@Test
	@Scenario("A3")
	void proposalLessRememberIsStoredAndQueued() {
		try (Engine e = engine("a3")) {
			Remembered r = remember(e, "Met Anna for coffee, she now leads the platform team at Acme.");
			assertEquals(1, r.pendingProposals());
			List<Observation> backlog = e.observations().backlog(10);
			assertEquals(1, backlog.size());
			assertEquals(r.observationId(), backlog.getFirst().id());
		}
	}

	@Test
	void blankTextIsAFixItError() {
		try (Engine e = engine("blank")) {
			MnemicException ex = assertThrows(MnemicException.class, () -> remember(e, "   "));
			assertEquals(MnemicException.Code.INVALID_ARGUMENT, ex.code());
			assertTrue(ex.getMessage().contains("Example"), "fix-it text names a valid call: " + ex.getMessage());
		}
	}

	@Test
	void idempotencyKeyReplaysInsteadOfDuplicating() {
		try (Engine e = engine("idem")) {
			Remembered first = e.observations().remember("I live in Zürich.", Source.user(), null, null, null, "k1");
			Remembered second = e.observations().remember("I live in Zürich.", Source.user(), null, null, null, "k1");
			assertTrue(second.replayed());
			assertEquals(first.observationId(), second.observationId());
			assertEquals(1, e.observations().count());
		}
	}

	@Test
	void diacriticsFoldInLexicalRecall() {
		try (Engine e = engine("umlaut")) {
			remember(e, "I live in Schübelbach.");
			assertEquals(1, recall(e, "Schubelbach").hits().size());
			assertEquals(1, recall(e, "schübelbach").hits().size());
		}
	}

	@Test
	void observationIsStoredEvenWhenTheProposalIsRejected() {
		try (Engine e = engine("reject")) {
			RememberOutcome o = remember(e, "Hooli is my godmother.",
					proposal().entity("e1", "Hooli", "organization").fact("e1", "parent_of", "self"));
			assertEquals(0, o.applied().facts().size());
			assertFalse(o.applied().questions().isEmpty(), "type mismatch is a question");
			assertEquals("type_mismatch", o.applied().questions().getFirst().get("kind"));
			assertEquals(1, e.observations().count(), "the observation is still stored");
		}
	}
}
