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
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md family S: event types and entity types defined by the caller. */
class VocabularyTest {

	private static final EventTypeDef INHERITED = new EventTypeDef("inherited", "Subject inherited object.",
			List.of("owns"), List.of(), List.of(), null, List.of("inherited", "inherit"));
	private static final EntityTypeDef CANTON = new EntityTypeDef("canton", "A Swiss canton.", "place",
			List.of("kanton"), List.of("kanton", "canton"));

	private static List<String> renderings(Engine e, RememberOutcome o) {
		return o.applied().facts().stream().map(f -> f.rendering()).toList();
	}

	@Test
	@Scenario("S1")
	void anEventTypeDefinedInAProposalTakesEffect() {
		try (Engine e = TestHomes.engine("s1-event-type")) {
			RememberOutcome o = remember(e, "I inherited the cabin in Sälen from my grandmother.",
					proposal().eventType(INHERITED).entity("e1", "the Sälen cabin", "place").event("ev1", "inherited",
							null, "self", "e1"));
			assertEquals(List.of(), o.applied().warnings());
			assertEquals(List.of(Map.of("kind", "event_type", "name", "inherited", "resolution", "registered")),
					o.applied().definitions());
			assertEquals(List.of("Mattias Sandell owns the Sälen cabin"), renderings(e, o),
					"the new type opens owns, so the fact the proposal did not state is stored");
			var type = e.eventTypes().get("inherited").orElseThrow();
			assertFalse(type.seed());
			assertEquals(o.observation().observationId(), type.definedBy());
			RememberOutcome again = remember(e, "Same again.", proposal().eventType(INHERITED));
			assertEquals("exists", again.applied().definitions().getFirst().get("resolution"));
		}
	}

	@Test
	@Scenario("S2")
	void aDefinedVocabularySurvivesARestart() {
		Path home = TestHomes.fresh("s2-restart");
		try (Engine e = TestHomes.engine(home)) {
			remember(e, "I inherited the cabin.", proposal().eventType(INHERITED).entityType(CANTON));
		}
		try (Engine e = TestHomes.engine(home)) {
			assertTrue(e.eventTypes().get("inherited").isPresent());
			assertTrue(e.entityTypes().isA("canton", "place"));
			RememberOutcome o = remember(e, "I also inherited the boat.",
					proposal().entity("e1", "the boat", "thing").event("ev1", "inherited", "2020", "self", "e1"));
			assertEquals(List.of("Mattias Sandell owns the boat (since 2020)"), renderings(e, o));
		}
	}

	@Test
	@Scenario("S3")
	void anEntityTypeWithAParentNestsWithinIt() {
		try (Engine e = TestHomes.engine("s3-entity-type")) {
			RememberOutcome o = remember(e, "I live in Kanton Schwyz.",
					proposal().entityType(CANTON).entity("e1", "Kanton Schwyz", "kanton").fact("lives_in", "e1"));
			assertEquals(List.of(), o.applied().warnings());
			assertEquals(List.of("Mattias Sandell lives in Kanton Schwyz"), renderings(e, o),
					"lives_in takes a place, and a canton is one");
			assertEquals("canton", e.entities().byRef("Kanton Schwyz").orElseThrow().type(), "kanton is a synonym");
			RememberOutcome next = remember(e, "Kanton Luzern is next door.",
					proposal().entity("e1", "Kanton Luzern", "canton"));
			assertEquals(List.of(), next.applied().questions(),
					"the shared word is a type word, not an identity: no question against Kanton Schwyz");
			assertEquals("created", next.applied().entities().getFirst().resolution());
		}
	}

	@Test
	@Scenario("S4")
	void aDefinitionThatNamesTheUnknownIsSkipped() {
		try (Engine e = TestHomes.engine("s4-skipped")) {
			RememberOutcome o = remember(e, "I sailed to Gotland.", proposal()
					.eventType(new EventTypeDef("sailed", null, List.of("captains"), List.of(), List.of(), null,
							List.of()))
					.entityType(new EntityTypeDef("moon", null, "planet", List.of(), List.of()))
					.entity("e1", "Gotland", "place").fact("lives_in", "e1"));
			assertEquals(2, o.applied().warnings().size(), o.applied().warnings().toString());
			assertTrue(o.applied().warnings().getFirst().contains("planet"));
			assertTrue(o.applied().warnings().getLast().contains("captains"));
			assertTrue(o.applied().definitions().isEmpty());
			assertTrue(e.eventTypes().get("sailed").isEmpty());
			assertTrue(e.entityTypes().get("moon").isEmpty());
			assertEquals(1, o.applied().facts().size(), "the rest of the proposal is applied");
		}
	}

	@Test
	@Scenario("S5")
	void aVocabularyCorrectionIsLogged() {
		Path home = TestHomes.fresh("s5-correct");
		try (Engine e = TestHomes.engine(home)) {
			remember(e, "Definitions.", proposal().eventType(INHERITED).entityType(CANTON));
			Map<String, Object> ev = e.correctEventType("inherited", Map.of("closes", List.of("owns")),
					"an inheritance ends the giver's ownership");
			assertEquals(List.of(), ((Map<?, ?>) ev.get("before")).get("closes"));
			assertEquals(List.of("owns"), ((Map<?, ?>) ev.get("after")).get("closes"));
			assertEquals(1, ((List<?>) ev.get("changes")).size());
			Map<String, Object> et = e.correctEntityType("canton",
					Map.of("type_words", List.of("kanton", "canton", "ct")), null);
			assertEquals(List.of("kanton", "canton", "ct"), ((Map<?, ?>) et.get("after")).get("type_words"));
			assertTrue(e.entityTypes().isTypeWord("ct"));
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(List.of("owns"), e.eventTypes().get("inherited").orElseThrow().closes());
			assertTrue(e.entityTypes().isTypeWord("ct"));
			Fact any = e.facts().factsOf(e.entities().owner().id()).stream().findFirst().orElse(null);
			assertEquals(null, any, "definitions alone store no facts");
		}
	}
}
