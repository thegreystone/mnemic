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
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
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

	@Test
	@Scenario("S6")
	void aRestrictionOverADefinedPlaceKindDecidesAQuestion() {
		try (Engine e = TestHomes.engine("s6-restriction")) {
			RememberOutcome only = remember(e, "I only own property in Kanton Schwyz, which is in Switzerland.",
					proposal().entityType(CANTON).entity("e1", "Kanton Schwyz", "canton")
							.entity("e2", "Switzerland", "country").fact("e1", "located_in", "e2")
							.fact(new FactRef("self", "owns", "e1", null, null, null, null, List.of(), null, null, null,
									Boolean.TRUE)));
			assertEquals(List.of(), only.applied().warnings());
			assertEquals(2, only.applied().facts().size(), only.applied().toString());
			remember(e, "Sweden is a country.", proposal().entity("e1", "Sweden", "country"));
			RecallResult sweden = recall(e, "does Mattias own anything in Sweden");
			assertEquals("known_false", sweden.structured().state(), sweden.text());
			assertEquals("restriction", sweden.structured().basis());
			RememberOutcome flat = remember(e, "I own a flat in Kanton Luzern.",
					proposal().entity("e1", "Kanton Luzern", "canton").entity("e2", "Switzerland", "country")
							.entity("e3", "the Luzern flat", "place").fact("e1", "located_in", "e2")
							.fact("e3", "located_in", "e1").fact("owns", "e3"));
			assertEquals(1, flat.applied().questions().size(), flat.applied().toString());
			assertEquals("conflict", flat.applied().questions().getFirst().get("kind"),
					"two cantons under one country lie on different branches");
		}
	}

	@Test
	@Scenario("S7")
	void aDefinedEventTypeClosesAFactThatArrivesLater() {
		try (Engine e = TestHomes.engine("s7-closes-later")) {
			remember(e, "I gave the boat away in 2021.",
					proposal()
							.eventType(new EventTypeDef("gave_away", "Subject gave object away.", List.of(),
									List.of("owns"), List.of(), null, List.of("gave away")))
							.entity("e1", "the boat", "thing").event("ev1", "gave_away", "2021", "self", "e1"));
			RememberOutcome o = remember(e, "I have owned the boat since 2015.",
					proposal().entity("e1", "the boat", "thing")
							.fact(fact("self", "owns", "e1", null, null, "2015", null, null, null, null)));
			assertEquals(List.of("Mattias Sandell owns the boat (2015 – 2021)"), renderings(e, o));
			Fact f = e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
			assertEquals("event", f.endSource());
			assertEquals("ended", f.state(e.clock().instant()));
		}
	}

	@Test
	@Scenario("S8")
	void aDefinedLexiconAnswersAQuestionWithTheEvent() {
		try (Engine e = TestHomes.engine("s8-lexicon")) {
			remember(e, "I inherited the cabin from my grandmother in 2019.", proposal().eventType(INHERITED)
					.entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			RecallResult r = recall(e, "when did Mattias inherit the cabin");
			assertEquals("events", r.structured().state(), r.text());
			assertEquals(1, r.events().size(), r.text());
			assertEquals("inherited", r.events().getFirst().type());
			assertTrue(r.text().contains("inherited(Mattias Sandell, the cabin) (since 2019)"), r.text());
		}
	}

	@Test
	@Scenario("S9")
	void anEntityTypedBeforeItsTypeExistedTakesTheRegisteredName() {
		try (Engine e = TestHomes.engine("s9-retype")) {
			RememberOutcome before = remember(e, "Kanton Schwyz.", proposal().entity("e1", "Kanton Schwyz", "kanton"));
			Entity schwyz = e.entities().byRef(before.applied().entities().getFirst().id()).orElseThrow();
			assertEquals("kanton", schwyz.type(), "an unregistered type passes through as written");
			RememberOutcome refused = remember(e, "I live there.", proposal().fact("lives_in", "Kanton Schwyz"));
			assertEquals(0, refused.applied().facts().size());
			assertEquals("type_mismatch", refused.applied().questions().getFirst().get("kind"));
			remember(e, "A canton is a kind of place.", proposal().entityType(CANTON));
			assertEquals("canton", e.entities().get(schwyz.id()).orElseThrow().type(), "retyped on registration");
			RememberOutcome accepted = remember(e, "I live in Kanton Schwyz.",
					proposal().fact("lives_in", "Kanton Schwyz"));
			assertEquals(List.of("Mattias Sandell lives in Kanton Schwyz"), renderings(e, accepted));
		}
	}

	@Test
	@Scenario("S10")
	void aDefinitionTravelsWithAHeldProposal() {
		try (Engine e = TestHomes.engine("s10-held")) {
			remember(e, "Anna Lindqvist is a colleague.", proposal().entity("e1", "Anna Lindqvist", "person"));
			RememberOutcome held = remember(e, "Anna inherited the cabin.",
					proposal().eventType(INHERITED).entity("e1", "Anna", "person").entity("e2", "the cabin", "place")
							.event("ev1", "inherited", "2019", "e1", "e2"));
			assertEquals(1, held.applied().questions().size(), held.applied().toString());
			assertEquals("entity_resolution", held.applied().questions().getFirst().get("kind"));
			assertEquals(0, held.applied().events().size(), "the event waits with the question");
			assertTrue(e.eventTypes().get("inherited").isPresent(), "the definition is registered at once");
			String q = held.applied().questions().getFirst().get("id").toString();
			Entity anna = e.entities().byRef("Anna Lindqvist").orElseThrow();
			RememberOutcome answered = remember(e, "Yes, that Anna.", null, new Resolve(q, anna.ref()));
			Map<?, ?> result = answered.resolved().getFirst();
			assertEquals("answered", result.get("status"));
			assertEquals(1, ((List<?>) result.get("events")).size(), result.toString());
			assertEquals(List.of("Anna Lindqvist owns the cabin (since 2019)"),
					e.facts().factsOf(anna.id()).stream().map(Fact::rendering).toList());
		}
	}

	@Test
	@Scenario("S11")
	void aDefinedTypeThatEndsAnEntityClosesItsOpenFacts() {
		try (Engine e = TestHomes.engine("s11-ends-entity")) {
			remember(e, "Nordvik AB is in Stockholm and I work there.",
					proposal().entity("e1", "Nordvik AB", "organization").entity("e2", "Stockholm", "place")
							.fact("e1", "located_in", "e2").fact("works_at", "e1"));
			RememberOutcome o = remember(e, "Nordvik AB was wound up in 2023.",
					proposal()
							.eventType(new EventTypeDef("wound_up", "Subject organization was wound up.", List.of(),
									List.of(), List.of(), true, List.of("wound up", "liquidated")))
							.entity("e1", "Nordvik AB", "organization").event("ev1", "wound_up", "2023", "e1"));
			assertEquals(1, o.applied().superseded().size(), o.applied().toString());
			assertEquals("Nordvik AB is located in Stockholm", o.applied().superseded().getFirst().get("rendering"));
			Entity nordvik = e.entities().byRef("Nordvik AB").orElseThrow();
			List<String> now = e.facts().factsOf(nordvik.id()).stream().map(Fact::rendering).toList();
			assertTrue(now.contains("Nordvik AB is located in Stockholm (until 2023)"), now.toString());
			assertTrue(now.contains("Mattias Sandell works at Nordvik AB"),
					"the owner's fact is not the organization's own");
		}
	}

	@Test
	@Scenario("S12")
	void consolidateHonoursACorrectedEventType() {
		try (Engine e = TestHomes.engine("s12-reclose")) {
			remember(e, "I handed the boat over in 2020.",
					proposal()
							.eventType(new EventTypeDef("handed_over", null, List.of(), List.of(), List.of(), null,
									List.of("handed over")))
							.entity("e1", "the boat", "thing").event("ev1", "handed_over", "2020", "self", "e1"));
			RememberOutcome o = remember(e, "I used to own the boat.", proposal().entity("e1", "the boat", "thing")
					.fact(fact("self", "owns", "e1", null, null, "2015", null, Boolean.TRUE, null, null)));
			assertEquals(List.of("Mattias Sandell owns the boat (from 2015, ended)"), renderings(e, o),
					"the type closed nothing when the event happened");
			e.correctEventType("handed_over", Map.of("closes", List.of("owns")), "handing over ends ownership");
			assertEquals(1, e.consolidate(true).reclosed(), "a dry run reports what the correction would date");
			assertEquals(1, e.consolidate(false).reclosed());
			Fact f = e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
			assertEquals("Mattias Sandell owns the boat (2015 – 2020)", f.rendering());
			assertEquals(0, e.consolidate(false).reclosed(), "done once");
		}
	}

	private static List<Map<String, Object>> ofKind(RememberOutcome o, String kind) {
		return o.applied().suggestions().stream().filter(x -> kind.equals(x.get("kind"))).toList();
	}

	@Test
	@Scenario("S13")
	void anUnregisteredEventTypeIsASuggestion() {
		try (Engine e = TestHomes.engine("s13-suggest-event")) {
			RememberOutcome o = remember(e, "I inherited the cabin in 2019.",
					proposal().entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			assertEquals(1, o.applied().events().size(), "the occurrence is stored");
			assertEquals(0, o.applied().facts().size(), "with no effect on facts");
			assertEquals(0, o.applied().questions().size(), "and nothing is held");
			List<Map<String, Object>> s = ofKind(o, "event_type");
			assertEquals(1, s.size(), o.applied().suggestions().toString());
			assertEquals("inherited", s.getFirst().get("name"));
			assertTrue(s.getFirst().get("message").toString().contains("Check with the user"), s.toString());
			Map<?, ?> define = (Map<?, ?>) s.getFirst().get("define");
			Map<?, ?> skeleton = (Map<?, ?>) ((List<?>) define.get("event_types")).getFirst();
			assertEquals("inherited", skeleton.get("name"));
			assertEquals(List.of("inherited"), skeleton.get("lexicon"));
			RememberOutcome defined = remember(e, "Inheriting means I own it.", proposal().eventType(INHERITED)
					.entity("e1", "the boat", "thing").event("ev1", "inherited", "2021", "self", "e1"));
			assertEquals(List.of(), defined.applied().suggestions(),
					"defined in the same proposal: nothing to suggest");
			assertEquals(List.of("Mattias Sandell owns the boat (since 2021)"), renderings(e, defined));
		}
	}

	@Test
	@Scenario("S14")
	void anUnregisteredEntityTypeIsASuggestion() {
		try (Engine e = TestHomes.engine("s14-suggest-type")) {
			RememberOutcome o = remember(e, "Two cantons.", proposal().entity("e1", "Kanton Schwyz", "canton")
					.entity("e2", "Kanton Luzern", "canton").entity("e3", "Anna Lindqvist", "person"));
			assertEquals(3, o.applied().entities().size());
			List<Map<String, Object>> s = ofKind(o, "entity_type");
			assertEquals(1, s.size(), "one suggestion per unregistered type, however many entities use it");
			assertEquals("canton", s.getFirst().get("name"));
			assertTrue(s.getFirst().get("message").toString().contains("Kanton Schwyz"));
			assertEquals(List.of(), ofKind(o, "predicate"));
			RememberOutcome again = remember(e, "Again.",
					proposal().entityType(CANTON).entity("e1", "Kanton Uri", "canton"));
			assertEquals(List.of(), again.applied().suggestions());
		}
	}

	@Test
	@Scenario("S15")
	void aBarePredicateIsASuggestionAsWellAsAnExtension() {
		try (Engine e = TestHomes.engine("s15-suggest-predicate")) {
			RememberOutcome o = remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			assertEquals("x:mentors", o.applied().facts().getFirst().predicate());
			assertTrue(o.applied().warnings().getFirst().contains("not registered"), o.applied().warnings().toString());
			List<Map<String, Object>> s = ofKind(o, "predicate");
			assertEquals(1, s.size(), o.applied().suggestions().toString());
			assertEquals("mentors", s.getFirst().get("name"));
			Map<?, ?> skeleton = (Map<?, ?>) ((List<?>) ((Map<?, ?>) s.getFirst().get("define")).get("predicates"))
					.getFirst();
			assertEquals("mentors", skeleton.get("name"));
			RememberOutcome deliberate = remember(e, "I coach Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "x:coaches", "e1"));
			assertEquals(List.of(), deliberate.applied().suggestions(), "an x: predicate is an extension on purpose");
		}
	}

	@Test
	@Scenario("S16")
	void consolidateListsVocabularyInUseWithoutADefinition() {
		try (Engine e = TestHomes.engine("s16-consolidate")) {
			remember(e, "Cantons and an inheritance.",
					proposal().entity("e1", "Kanton Schwyz", "canton").entity("e2", "Kanton Luzern", "canton")
							.entity("e3", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e3"));
			List<Map<String, Object>> before = e.consolidate(true).suggestedRegistrations();
			assertTrue(before.contains(Map.of("event_type", "inherited", "uses", 1L)), before.toString());
			assertTrue(before.contains(Map.of("entity_type", "canton", "uses", 2L)), before.toString());
			remember(e, "Definitions.", proposal().eventType(INHERITED).entityType(CANTON));
			List<Map<String, Object>> after = e.consolidate(true).suggestedRegistrations();
			assertEquals(List.of(), after, "once defined, nothing is left to suggest");
		}
	}
}
