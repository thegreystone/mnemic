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
import java.util.List;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md sections I (granularity), D2 (forget) and E5 (connectors), the observation-level parts. */
class GranularityAndForgettingTest {

	@Test
	@Scenario("I4")
	void oversizedObservationIsAcceptedWithAWarning() {
		try (Engine e = engine("i4")) {
			String big = "Mattias wrote a long design document about SQLite. ".repeat(250); // ~12,500 chars
			Remembered r = remember(e, big);
			assertEquals(1, r.warnings().size(), r.warnings().toString());
			assertTrue(r.warnings().getFirst().contains("chunk"), r.warnings().getFirst());
			assertEquals(big, e.observations().get(r.observationId()).orElseThrow().text(), "stored whole");
			assertEquals(1, recall(e, "SQLite design").hits().size());
			assertTrue(recall(e, "SQLite design").hits().getFirst().shown().length() < big.length(),
					"an excerpt, not the whole document, goes into the block");
		}
	}

	/** D2 in its M0 form: the observation and its index entry are gone; a dated tombstone remains. */
	@Test
	@Scenario("D6")
	void aFactSaidInTwoConversationsSurvivesForgettingOne() {
		try (Engine e = engine("d6-rehome")) {
			long a = remember(e, "I work at Hooli.", proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"))
					.observation().observationId();
			var second = remember(e, "As I said, I work at Hooli.", proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			long b = second.observation().observationId();
			assertTrue(second.applied().facts().getFirst().corroborated());
			long factId = Long.parseLong(second.applied().facts().getFirst().id().substring(2));
			assertEquals(List.of(a, b), e.facts().observationsOf(factId), "both conversations are on record");
			// Forget the first: the fact survives, homed at the second, one corroboration fewer.
			assertTrue(e.forget(a));
			Fact f = e.facts().get(factId).orElseThrow();
			assertEquals(b, f.observationId());
			assertEquals(1, f.corroborations());
			assertEquals(List.of(b), e.facts().observationsOf(factId));
			assertTrue(recall(e, "where does Mattias work").structured().matched(), "still known");
			// Forget the second: nothing else said it, so it goes.
			assertTrue(e.forget(b));
			assertTrue(e.facts().get(factId).isEmpty());
		}
	}

	@Test
	@Scenario("D4")
	void forgetCanKeepTheEntitiesItCreatedForAReseed() {
		try (Engine e = engine("d4-reseed")) {
			long obs = remember(e, "I own Bergstrasse 7 in Schübelbach.",
					proposal().entity("e1", "Bergstrasse 7", "place").entity("e2", "Schübelbach", "place")
							.fact("self", "owns", "e1").fact("e1", "located_in", "e2")).observation().observationId();
			long town = e.entities().byRef("Schübelbach").orElseThrow().id();
			assertTrue(e.forget(obs, true));
			assertTrue(e.entities().get(town).isPresent(), "kept, with its id");
			assertTrue(e.facts().factsOf(town).isEmpty(), "but nothing derived from the observation remains");
			// Remembering again binds to the same entity: no renumbering, no question.
			var again = remember(e, "I own Bergstrasse 7 in Schübelbach.",
					proposal().entity("e1", "Bergstrasse 7", "place").entity("e2", "Schübelbach", "place")
							.fact("self", "owns", "e1").fact("e1", "located_in", "e2"));
			assertTrue(again.applied().questions().isEmpty(), again.applied().questions().toString());
			assertEquals(town, e.entities().byRef("Schübelbach").orElseThrow().id());
			// The default still removes what nothing else references.
			long obs2 = remember(e, "I once visited Zug.", proposal().entity("e1", "Zug", "place")
					.fact("self", "x:visited", "e1")).observation().observationId();
			long zug = e.entities().byRef("Zug").orElseThrow().id();
			assertTrue(e.forget(obs2));
			assertTrue(e.entities().get(zug).isEmpty(), "privacy default: gone");
		}
	}

	@Test
	@Scenario("D2")
	void forgetRemovesAndSaysSo() throws Exception {
		Path home = fresh("d2");
		try (Engine e = engine(home)) {
			long id = remember(e, "My passport number is 123.").observationId();
			assertTrue(e.forget(id));
			assertTrue(recall(e, "passport").hits().isEmpty());
			var tombstone = e.observations().get(id).orElseThrow();
			assertTrue(tombstone.forgotten());
			assertEquals("", tombstone.text());
			assertFalse(e.forget(id), "second forget is a no-op");
		}
		// Scenario family O: zero residue in the file itself (close() checkpoints the WAL; secure_delete zeroes pages).
		byte[] bytes = Files.readAllBytes(home.resolve(Engine.DB_FILE));
		assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("passport number"),
				"forgotten text must not survive in the database file");
	}

	@Test
	@Scenario("E5")
	void connectorObservationsDoNotBecomeFactsOnTheirOwn() {
		try (Engine e = engine("e5")) {
			Source connector = new Source("connector", "msg-1", null, null, null);
			Remembered r = e.observations()
					.remember("Subject: lunch\n\nSee you at noon.", connector, null, null, null, null);
			assertEquals(1, r.pendingProposals());
			MnemicException ex = assertThrows(MnemicException.class,
					() -> e.observations().remember("Another mail", connector, null, "{\"facts\":[]}", 1, null));
			assertEquals(MnemicException.Code.INVALID_ARGUMENT, ex.code());
		}
	}
}
