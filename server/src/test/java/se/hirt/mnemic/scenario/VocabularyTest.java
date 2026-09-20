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
import se.hirt.mnemic.FixedEmbedding;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;

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
			assertTrue(r.text().contains("inherited (Mattias Sandell, the cabin) (since 2019)"), r.text());
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
			assertEquals(2, o.applied().superseded().size(), o.applied().toString());
			Entity nordvik = e.entities().byRef("Nordvik AB").orElseThrow();
			List<String> now = e.facts().factsOf(nordvik.id()).stream().map(Fact::rendering).toList();
			assertTrue(now.contains("Nordvik AB is located in Stockholm (until 2023)"), now.toString());
			assertTrue(now.contains("Mattias Sandell works at Nordvik AB (until 2023)"),
					"an employment at a wound-up organization ended with it: " + now);
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

	private static Map<String, Object> question(RememberOutcome o, String kind) {
		return o.applied().questions().stream().filter(q -> kind.equals(q.get("kind"))).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static List<String> candidateIds(Map<String, Object> q) {
		return ((List<Map<String, Object>>) q.get("candidates")).stream().map(c -> (String) c.get("id")).toList();
	}

	@Test
	@Scenario("S13")
	void anUnregisteredEventTypeRegistersFromUseAndAsksWhatItDoes() {
		try (Engine e = TestHomes.engine("s13-event-from-use")) {
			RememberOutcome o = remember(e, "I inherited the cabin in 2019.",
					proposal().entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			assertEquals(1, o.applied().events().size(), "the occurrence is stored");
			assertEquals(0, o.applied().facts().size(), "with no effect on facts until the type has one");
			Map<String, Object> defined = o.applied().definitions().getFirst();
			assertEquals("inferred", defined.get("resolution"), defined.toString());
			@SuppressWarnings("unchecked")
			Map<String, Object> assumed = (Map<String, Object>) defined.get("inferred");
			assertEquals("{subject} inherited {object}", assumed.get("render"), "what was assumed is shown");
			assertEquals("none", assumed.get("effects"));
			assertTrue(assumed.get("correct").toString().startsWith("correct(\"event:inherited\""));
			var t = e.eventTypes().get("inherited").orElseThrow();
			assertTrue(t.inferred());
			assertEquals(List.of("inherited", "inherit"), t.lexicon(), "the name's words are its cue");
			assertEquals("{subject} inherited {object}", t.render());
			long eventId = Long.parseLong(o.applied().events().getFirst().id().substring(4));
			assertTrue(e.events().get(eventId).orElseThrow().rendering()
					.startsWith("Mattias Sandell inherited the cabin"));
			Map<String, Object> q = question(o, "event_effect");
			assertEquals("inherited", q.get("subject"));
			List<String> ids = candidateIds(q);
			assertTrue(ids.contains("opens:owns") && ids.contains("closes:owns") && ids.contains("none"),
					ids.toString());
			assertFalse(ids.contains("ends_entity"), "two participants: nothing ceases to exist");
			assertFalse(ids.contains("opens:works_at"), "a place is no employer: " + ids);
			// Asked once per type, however many events arrive before the answer.
			RememberOutcome again = remember(e, "I inherited the boat in 2021.",
					proposal().entity("e1", "the boat", "thing").event("ev1", "inherited", "2021", "self", "e1"));
			assertEquals(0, again.applied().questions().size(), again.applied().questions().toString());
		}
	}

	@Test
	@Scenario("S18")
	void answeringWhatAnEventTypeDoesAppliesToItsStoredEvents() {
		try (Engine e = TestHomes.engine("s18-event-effect")) {
			RememberOutcome o = remember(e, "I inherited the cabin in 2019.",
					proposal().entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			remember(e, "I inherited the boat in 2021.",
					proposal().entity("e1", "the boat", "thing").event("ev1", "inherited", "2021", "self", "e1"));
			String qid = (String) question(o, "event_effect").get("id");
			RememberOutcome a = remember(e, "Inheriting made them mine.", null, new Resolve(qid, "opens:owns"));
			var t = e.eventTypes().get("inherited").orElseThrow();
			assertEquals(List.of("owns"), t.opens());
			assertFalse(t.inferred(), "answered: no longer waiting for a meaning");
			@SuppressWarnings("unchecked")
			Map<String, Object> applied = (Map<String, Object>) a.resolved().getFirst().get("applied");
			assertEquals(2, applied.get("events"), "both stored events got the effect");
			assertEquals(2, ((List<?>) applied.get("facts")).size(), applied.toString());
			List<String> owned = e.facts().factsOf(e.entities().owner().id()).stream().map(Fact::rendering).sorted()
					.toList();
			assertEquals(List.of("Mattias Sandell owns the boat (since 2021)",
					"Mattias Sandell owns the cabin (since 2019)"), owned);
			assertEquals(0, e.questions().openCount());
			// From now on the type works like a seeded one.
			RememberOutcome later = remember(e, "I inherited the flat in 2024.",
					proposal().entity("e1", "the flat", "place").event("ev1", "inherited", "2024", "self", "e1"));
			assertEquals(List.of("Mattias Sandell owns the flat (since 2024)"), renderings(e, later));
			assertEquals(0, later.applied().questions().size());
		}
	}

	@Test
	@Scenario("S14")
	void anUnregisteredEntityTypeRegistersFromUseAndAsksWhatKindItIs() {
		try (Engine e = TestHomes.engine("s14-type-from-use")) {
			RememberOutcome o = remember(e, "Two cantons.", proposal().entity("e1", "Kanton Schwyz", "canton")
					.entity("e2", "Kanton Luzern", "canton").entity("e3", "Anna Lindqvist", "person"));
			assertEquals(3, o.applied().entities().size());
			Map<String, Object> defined = o.applied().definitions().getFirst();
			assertEquals("inferred", defined.get("resolution"), defined.toString());
			assertTrue(((Map<?, ?>) defined.get("inferred")).containsKey("parent"),
					"the assumed parent (none) is shown");
			var t = e.entityTypes().get("canton").orElseThrow();
			assertTrue(t.inferred());
			assertEquals(null, t.parent());
			assertEquals(1, o.applied().questions().size(), "asked once, however many entities use the type");
			Map<String, Object> q = question(o, "type_kind");
			assertEquals("canton", q.get("subject"));
			List<String> ids = candidateIds(q);
			assertTrue(ids.contains("place") && ids.contains("organization") && ids.contains("none"), ids.toString());
			assertFalse(ids.contains("country"), "only root kinds are offered: " + ids);
			RememberOutcome again = remember(e, "Again.", proposal().entity("e1", "Appenzell", "canton"));
			assertEquals(List.of(), again.applied().questions());
			assertEquals(List.of(), again.applied().definitions(), "registered once");
		}
	}

	@Test
	@Scenario("S19")
	void answeringWhatKindAnEntityTypeIsMakesItAcceptedWhereItsParentIs() {
		try (Engine e = TestHomes.engine("s19-type-kind")) {
			RememberOutcome o = remember(e, "I live in Kanton Schwyz.",
					proposal().entity("e1", "Kanton Schwyz", "canton").fact("self", "lives_in", "e1"));
			assertEquals(0, o.applied().facts().size(), "a canton is not yet a place");
			String kindQ = (String) question(o, "type_kind").get("id");
			assertEquals("type_mismatch", question(o, "type_mismatch").get("kind"));
			RememberOutcome a = remember(e, "A canton is a Swiss region.", null, new Resolve(kindQ, "place"));
			assertEquals("place", e.entityTypes().get("canton").orElseThrow().parent());
			assertTrue(e.entityTypes().isA("canton", "place"));
			assertEquals(1, ((List<?>) a.resolved().getFirst().get("settled")).size(),
					"the held fact no longer mismatches: " + a.resolved());
			assertEquals(0, e.questions().openCount());
			assertEquals(List.of("Mattias Sandell lives in Kanton Schwyz"),
					e.facts().factsOf(e.entities().owner().id()).stream().map(Fact::rendering).toList());
			RememberOutcome later = remember(e, "I was born in Kanton Uri.",
					proposal().entity("e1", "Kanton Uri", "canton").fact("self", "born_in", "e1"));
			assertEquals(List.of("Mattias Sandell was born in Kanton Uri"), renderings(e, later));
			assertEquals(List.of(), later.applied().questions());
		}
	}

	@Test
	@Scenario("S20")
	void aTypeMismatchIsAnsweredByRetypingOrByANewKind() {
		try (Engine e = TestHomes.engine("s20-type-mismatch")) {
			// Anna typed as an organization by mistake: the seed type stays what it is, the entity is retyped.
			RememberOutcome o = remember(e, "Anna is my daughter.",
					proposal().entity("e1", "Anna Lindqvist", "organization").fact("self", "parent_of", "e1"));
			Map<String, Object> q = question(o, "type_mismatch");
			assertEquals(List.of("retype:person", "dismiss"), candidateIds(q), "no kind: for a seeded type");
			RememberOutcome a = remember(e, "She is a person.", null,
					new Resolve((String) q.get("id"), "retype:person"));
			assertEquals("person", e.entities().byRef("Anna Lindqvist").orElseThrow().type());
			assertEquals(1, ((List<?>) a.resolved().getFirst().get("facts")).size(), a.resolved().toString());
			assertEquals("Mattias Sandell is Anna Lindqvist's parent",
					e.facts().factsOf(e.entities().owner().id()).getFirst().rendering());
			// A type registered from use can instead become a kind of what the predicate accepts.
			RememberOutcome c = remember(e, "I live in Kanton Schwyz.",
					proposal().entity("e1", "Kanton Schwyz", "canton").fact("self", "lives_in", "e1"));
			Map<String, Object> mismatch = question(c, "type_mismatch");
			assertEquals(List.of("kind:place", "retype:place", "dismiss"), candidateIds(mismatch));
			RememberOutcome k = remember(e, "A canton is a place.", null,
					new Resolve((String) mismatch.get("id"), "kind:place"));
			assertEquals("place", e.entityTypes().get("canton").orElseThrow().parent());
			assertEquals(1, ((List<?>) k.resolved().getFirst().get("facts")).size());
			assertEquals(0, e.questions().openCount(), "the kind question was settled by the same answer");
			// Or the fact was simply wrong.
			RememberOutcome w = remember(e, "Hooli is my godmother.",
					proposal().entity("e1", "Hooli", "organization").fact("e1", "parent_of", "self"));
			String wid = (String) question(w, "type_mismatch").get("id");
			RememberOutcome d = remember(e, "Never mind.", null, new Resolve(wid, "dismiss"));
			assertEquals("dismissed", d.resolved().getFirst().get("status"));
			assertEquals(List.of(), e.facts().factsOf(e.entities().byRef("Hooli").orElseThrow().id()),
					"nothing about Hooli was stored");
		}
	}

	@Test
	@Scenario("S15")
	void aBarePredicateRegistersFromUse() {
		try (Engine e = TestHomes.engine("s15-predicate-from-use")) {
			RememberOutcome o = remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			assertEquals("mentors", o.applied().facts().getFirst().predicate());
			assertEquals("Mattias Sandell mentors Anna Lindqvist", o.applied().facts().getFirst().rendering());
			assertTrue(o.applied().warnings().getFirst().contains("registered from this use"),
					o.applied().warnings().toString());
			Map<String, Object> defined = o.applied().definitions().getFirst();
			assertEquals("inferred", defined.get("resolution"), defined.toString());
			@SuppressWarnings("unchecked")
			Map<String, Object> assumed = (Map<String, Object>) defined.get("inferred");
			assertEquals(List.of("*"), assumed.get("domain"), "the assumed direction is shown, to be corrected");
			assertEquals(List.of("*"), assumed.get("range"));
			assertEquals(false, assumed.get("functional"));
			assertEquals(List.of("mentors", "mentor"), assumed.get("lexicon"));
			assertTrue(assumed.get("correct").toString().contains("pred:mentors"));
			var p = e.predicates().get("mentors").orElseThrow();
			assertTrue(p.isInferred());
			assertEquals(List.of("mentors", "mentor"), p.lexicon(), "the name's words are its cue");
			assertEquals(List.of("*"), p.domain());
			assertTrue(recall(e, "who does Mattias mentor").structured().matched(), "reachable through its own words");
			// A description through correct completes it; the name resolves exactly from then on.
			e.correctPredicate("mentors",
					Map.of("description", "Subject guides object's career.", "domain", "person", "range", "person"),
					"the user explained");
			var completed = e.predicates().get("mentors").orElseThrow();
			assertFalse(completed.isInferred());
			assertEquals(List.of("person"), completed.range());
			RememberOutcome again = remember(e, "I mentor Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "mentors", "e1"));
			assertEquals(List.of(), again.applied().warnings());
			assertEquals(List.of(), again.applied().definitions());
		}
	}

	@Test
	@Scenario("S16")
	void consolidateListsVocabularyRegisteredFromUseUntilItIsDefined() {
		try (Engine e = TestHomes.engine("s16-consolidate")) {
			remember(e, "Cantons, an inheritance, and a mentee.",
					proposal().entity("e1", "Kanton Schwyz", "canton").entity("e2", "Kanton Luzern", "canton")
							.entity("e3", "the cabin", "place").entity("e4", "Anna Lindqvist", "person")
							.event("ev1", "inherited", "2019", "self", "e3").fact("self", "mentors", "e4"));
			List<Map<String, Object>> before = e.consolidate(true).inferredVocabulary();
			assertTrue(before.contains(Map.of("event_type", "inherited", "uses", 1L)), before.toString());
			assertTrue(before.contains(Map.of("entity_type", "canton", "uses", 2L)), before.toString());
			Map<String, Object> mentors = before.stream().filter(m -> "mentors".equals(m.get("predicate"))).findFirst()
					.orElseThrow();
			assertEquals(1L, mentors.get("uses"));
			assertEquals(List.of("obs-1"), mentors.get("observations"));
			// Definitions arriving later complete what was inferred, instead of being ignored as duplicates.
			RememberOutcome defined = remember(e, "Definitions.",
					proposal().eventType(INHERITED).entityType(CANTON)
							.predicate(new PredicateDef("mentors", "Subject guides object's career.", "person",
									"person", false, null, false, null, "medium", List.of("mentor", "mentee"), null,
									List.of(), List.of())));
			assertEquals("defined", defined.applied().definitions().getFirst().get("resolution"),
					defined.applied().definitions().toString());
			assertEquals(List.of("owns"), e.eventTypes().get("inherited").orElseThrow().opens());
			assertEquals("place", e.entityTypes().get("canton").orElseThrow().parent());
			assertEquals(List.of("mentor", "mentee"), e.predicates().get("mentors").orElseThrow().lexicon());
			assertEquals(List.of(), e.consolidate(true).inferredVocabulary(), "once defined, nothing is left to list");
		}
	}

	@Test
	@Scenario("S21")
	void aPartialDefinitionCountsAsADefinition() {
		try (Engine e = TestHomes.engine("s21-partial")) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			assertTrue(e.predicates().get("mentors").orElseThrow().isInferred());
			// No prose, just the two facts the model knows: one mentee at a time, and mentees are people.
			RememberOutcome d = remember(e, "One mentee at a time.", proposal().predicate(new PredicateDef("mentors",
					null, null, "person", true, null, null, null, null, List.of(), null, List.of(), List.of())));
			Map<String, Object> definition = d.applied().definitions().getFirst();
			assertEquals("defined", definition.get("resolution"), definition.toString());
			assertEquals(1, definition.get("rerendered_facts"));
			assertEquals(0, definition.get("rechecked_conflicts"), "functional now, but one mentee so far");
			Predicate p = e.predicates().get("mentors").orElseThrow();
			assertFalse(p.isInferred(), "somebody said something about it");
			assertTrue(p.functional());
			assertEquals(List.of("person"), p.range());
			assertEquals(List.of("*"), p.domain(), "what was not stated stays as inferred");
			assertEquals(List.of("mentors", "mentor"), p.lexicon());
			assertEquals(List.of(), e.consolidate(true).inferredVocabulary());
			// A first use that carries a partial definition is a definition too.
			RememberOutcome f = remember(e, "I coach Erik.",
					proposal()
							.predicate(new PredicateDef("coaches", null, "person", null, null, null, null, null, "low",
									List.of(), null, List.of(), List.of()))
							.entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			assertEquals("registered", f.applied().predicates().getFirst().resolution());
			Predicate c = e.predicates().get("coaches").orElseThrow();
			assertFalse(c.isInferred());
			assertEquals("low", c.volatility());
			assertEquals(List.of("coaches", "coach"), c.lexicon(), "the name still supplies the words");
		}
	}

	@Test
	@Scenario("S22")
	void makingAPredicateFunctionalRechecksItsFacts() {
		try (Engine e = TestHomes.engine("s22-functional")) {
			RememberOutcome a = remember(e, "I share a flat with Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "shares_a_flat_with", "e1"));
			RememberOutcome b = remember(e, "I share a flat with Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "shares_a_flat_with", "e1"));
			assertEquals(List.of(), b.applied().questions(), "nothing inferred is functional");
			long first = Long.parseLong(a.applied().facts().getFirst().id().substring(2));
			long second = Long.parseLong(b.applied().facts().getFirst().id().substring(2));
			assertEquals("current", e.facts().get(second).orElseThrow().status());
			Map<String, Object> c = e.correctPredicate("shares_a_flat_with", Map.of("functional", true),
					"one flatmate at a time");
			assertEquals(1, c.get("rechecked_conflicts"), c.toString());
			assertEquals("current", e.facts().get(first).orElseThrow().status(), "the earlier fact stands");
			assertEquals("pending", e.facts().get(second).orElseThrow().status(), "the later one waits");
			assertEquals(1, e.questions().openCount());
			var q = e.questions().open(10).getFirst();
			assertEquals("conflict", q.kind());
			assertEquals("f-" + second, q.toMap().get("pending_fact"));
			RememberOutcome r = remember(e, "Erik replaced Anna.", null, new Resolve(q.ref(), "supersede"));
			assertEquals("answered", r.resolved().getFirst().get("status"));
			assertEquals("superseded", e.facts().get(first).orElseThrow().status());
			assertEquals("current", e.facts().get(second).orElseThrow().status());
			assertEquals(0, e.correctPredicate("shares_a_flat_with", Map.of("volatility", "low"), null)
					.get("rechecked_conflicts"), "only a change to functional re-checks");
		}
	}

	@Test
	@Scenario("S23")
	void aBareNameCloseInMeaningToARegisteredPredicateIsAskedAbout() {
		try (Engine e = new Engine(
				TestHomes.options(TestHomes.fresh("s23-semantic")).withVocabularyEmbedding(new FixedEmbedding()))) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			assertEquals(0, o.applied().facts().size(), "the fact is held behind the question");
			Map<String, Object> q = question(o, "predicate_resolution");
			assertEquals("coaches", q.get("predicate"));
			@SuppressWarnings("unchecked")
			Map<String, Object> first = ((List<Map<String, Object>>) q.get("candidates")).getFirst();
			assertEquals("mentors", first.get("id"));
			assertEquals("semantic", first.get("match"));
			assertTrue(candidateIds(q).contains("new"));
			assertTrue(q.get("message").toString().contains("mentors"), q.get("message").toString());
			RememberOutcome a = remember(e, "Same thing.", null, new Resolve((String) q.get("id"), "mentors"));
			assertEquals(1, ((List<?>) a.resolved().getFirst().get("facts")).size(), a.resolved().toString());
			Entity erik = e.entities().byRef("Erik Nyberg").orElseThrow();
			assertEquals("Mattias Sandell mentors Erik Nyberg", e.facts().factsOf(erik.id()).getFirst().rendering());
			assertTrue(e.predicates().get("mentors").orElseThrow().aliases().contains("coaches"), "asked once");
			RememberOutcome later = remember(e, "I coach Sara.",
					proposal().entity("e1", "Sara Berg", "person").fact("self", "coaches", "e1"));
			assertEquals("mentors", later.applied().facts().getFirst().predicate());
			assertEquals(List.of(), later.applied().questions());
			// A name close to nothing registers from use as before.
			RememberOutcome v = remember(e, "I visited Zug.",
					proposal().entity("e1", "Zug", "place").fact("self", "visited", "e1"));
			assertEquals(1, v.applied().facts().size(), v.applied().questions() + " " + v.applied().warnings());
			assertEquals("visited", v.applied().facts().getFirst().predicate());
			assertEquals(List.of(), v.applied().questions());
		}
	}

	@Test
	@Scenario("S24")
	void consolidateReportsClosePredicatesAndCorrectMergesThem() {
		try (Engine e = new Engine(
				TestHomes.options(TestHomes.fresh("s24-merge")).withVocabularyEmbedding(new FixedEmbedding()))) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			String qid = (String) question(o, "predicate_resolution").get("id");
			remember(e, "Different things, I thought.", null, new Resolve(qid, "new"));
			assertEquals("coaches", e.predicates().get("coaches").orElseThrow().name());
			List<Map<String, Object>> close = e.consolidate(true).similarVocabulary();
			assertEquals(1, close.size(), close.toString());
			assertEquals("coaches", close.getFirst().get("predicate"));
			assertEquals("mentors", close.getFirst().get("close_to"));
			assertTrue(((Number) close.getFirst().get("score")).doubleValue() > 0.8, close.toString());
			Map<String, Object> m = e.correctPredicate("coaches", Map.of("merge_into", "mentors"), "one relation");
			assertEquals(1, m.get("merged_facts"), m.toString());
			assertEquals("mentors", e.predicates().get("coaches").orElseThrow().name(), "the old name is an alias");
			assertTrue(e.predicates().all().stream().noneMatch(p -> p.name().equals("coaches")));
			List<String> facts = e.facts().factsOf(e.entities().owner().id()).stream().map(Fact::rendering).sorted()
					.toList();
			assertEquals(List.of("Mattias Sandell mentors Anna Lindqvist", "Mattias Sandell mentors Erik Nyberg"),
					facts);
			assertEquals(List.of(), e.consolidate(true).similarVocabulary());
			assertEquals(2, recall(e, "who does Mattias mentor").hits().size());
			assertEquals(1, e.predicates().changes("mentors").size(), "the merge is on record");
		}
	}

	@Test
	@Scenario("S25")
	void anInferredDirectionIsCorrectedAtOnceAndRecallFollowsIt() {
		try (Engine e = TestHomes.engine("s25-direction")) {
			RememberOutcome o = remember(e, "Anna mentors me.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("e1", "mentors", "self"));
			remember(e, "I mentor Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "mentors", "e1"));
			@SuppressWarnings("unchecked")
			Map<String, Object> assumed = (Map<String, Object>) o.applied().definitions().getFirst().get("inferred");
			assertEquals(List.of("*"), assumed.get("domain"), "either side, until somebody says");
			// "does Mattias mentor" says which side Mattias is on; the other questions do not, so both facts come back.
			assertEquals(List.of("Mattias Sandell mentors Erik Nyberg"), structured(e, "who does Mattias mentor"));
			assertEquals(2, structured(e, "who mentors Mattias").size(), "no direction on record yet");
			assertEquals(2, structured(e, "who is Mattias's mentor").size());
			// The model corrects the assumption at once, as the reply's correct line says.
			Map<String, Object> c = e.correctPredicate("mentors",
					Map.of("domain", "person", "range", "person", "description", "Subject guides object's career."),
					"mentors are people guiding people");
			assertEquals(List.of("person"), e.predicates().get("mentors").orElseThrow().domain());
			assertEquals(List.of("person"), e.predicates().get("mentors").orElseThrow().range());
			assertFalse(e.predicates().get("mentors").orElseThrow().isInferred());
			assertEquals(2, c.get("rerendered_facts"));
			// Now a relation between like things: "Mattias's mentor" names the one whose mentee Mattias is.
			assertEquals(List.of("Anna Lindqvist mentors Mattias Sandell"), structured(e, "who is Mattias's mentor"));
			assertEquals(List.of("Anna Lindqvist mentors Mattias Sandell"), structured(e, "who mentors Mattias"));
			assertEquals(List.of("Mattias Sandell mentors Erik Nyberg"), structured(e, "who does Mattias mentor"));
			// And the types are checked from now on.
			RememberOutcome org = remember(e, "I mentor Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "mentors", "e1"));
			assertEquals("type_mismatch", question(org, "type_mismatch").get("kind"));
		}
	}

	private static List<String> structured(Engine e, String query) {
		return recall(e, query).structured().facts().stream().map(Fact::rendering).sorted().toList();
	}

	@Test
	@Scenario("S17")
	void anEventTypeTemplateRendersItsEvents() {
		Path home = TestHomes.fresh("s17-render");
		long eventId;
		try (Engine e = TestHomes.engine(home)) {
			RememberOutcome o = remember(e, "I inherited the cabin in 2019.", proposal()
					.eventType(new EventTypeDef("inherited", "Subject inherited object.", List.of("owns"), List.of(),
							List.of(), null, List.of("inherited", "inherit"), "{subject} inherited {object}"))
					.entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			eventId = Long.parseLong(o.applied().events().getFirst().id().substring(4));
			assertEquals("Mattias Sandell inherited the cabin (since 2019)",
					e.events().get(eventId).orElseThrow().rendering());
			RecallResult r = recall(e, "when did Mattias inherit the cabin");
			assertTrue(r.text().contains("Mattias Sandell inherited the cabin (since 2019)"), r.text());
			assertThrows(se.hirt.mnemic.protocol.MnemicException.class,
					() -> e.correctEventType("inherited", Map.of("render", "came into {object}"), null),
					"a template without the subject is refused");
			Map<String, Object> corrected = e.correctEventType("inherited",
					Map.of("render", "{subject} came into {object}"), "wording");
			assertEquals(1, corrected.get("rerendered_events"));
			assertEquals("Mattias Sandell came into the cabin (since 2019)",
					e.events().get(eventId).orElseThrow().rendering());
			RememberOutcome seed = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e1"));
			long joined = Long.parseLong(seed.applied().events().getFirst().id().substring(4));
			assertEquals("Mattias Sandell joined Hooli (since 2018)", e.events().get(joined).orElseThrow().rendering(),
					"seed types have templates too");
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals("Mattias Sandell came into the cabin (since 2019)",
					e.events().get(eventId).orElseThrow().rendering(), "the corrected wording survives a restart");
		}
	}
}
