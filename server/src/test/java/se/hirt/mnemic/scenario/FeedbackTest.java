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

import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.knowledge.Lang;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md family T: what a day of real use found, each fixed with the case that found it. */
class FeedbackTest {

	private static long factId(RememberOutcome o, int i) {
		return Long.parseLong(o.applied().facts().get(i).id().substring(2));
	}

	private static Fact factOf(Engine e, long id) {
		return e.facts().get(id).orElseThrow();
	}

	private static String questionId(RememberOutcome o) {
		return (String) o.applied().questions().getFirst().get("id");
	}

	/** A fact with {@code only: true}: an exclusive restriction. */
	private static FactRef only(String subject, String predicate, String object) {
		return new FactRef(subject, predicate, object, null, null, null, null, List.of(), null, null, null, true);
	}

	private static List<String> current(Engine e) {
		return e.facts().factsOf(e.entities().owner().id()).stream()
				.filter(f -> "current".equals(f.state(e.clock().instant()))).map(Fact::rendering).sorted().toList();
	}

	@Test
	@Scenario("T1")
	void aStatedFactTakesTheEventInTheSameProposalThatOpensIt() {
		try (Engine e = TestHomes.engine("t1-event-behind")) {
			RememberOutcome acme = remember(e, "I work at Acme.",
					proposal().entity("e1", "Acme", "organization").fact("self", "works_at", "e1"));
			// The fact stated beside the event, without derived_from: the event still explains the change.
			RememberOutcome globex = remember(e, "I joined Globex in March 2024.",
					proposal().entity("e1", "Globex", "organization").event("ev1", "joined", "2024-03", "self", "e1")
							.fact("self", "works_at", "e1"));
			assertEquals(List.of(), globex.applied().questions(), "no conflict: the joining explains it");
			assertEquals("superseded", factOf(e, factId(acme, 0)).status());
			assertEquals("2024-03-01", factOf(e, factId(acme, 0)).validEnd(),
					"the earlier job ended when the new one began");
			Fact newJob = factOf(e, factId(globex, 0));
			assertEquals("current", newJob.status());
			assertEquals("2024-03-01", newJob.validStart());
			assertEquals("event", newJob.startSource());
			assertEquals(1, globex.applied().superseded().size(), globex.applied().superseded().toString());
			// The same, stated with the event named: identical outcome.
			RememberOutcome initrode = remember(e, "I joined Initrode in 2025.",
					proposal().entity("e1", "Initrode", "organization").event("ev1", "joined", "2025", "self", "e1")
							.fact(fact("self", "works_at", "e1", null, null, null, null, null, List.of("ev1"), null)));
			assertEquals(List.of(), initrode.applied().questions());
			assertEquals("superseded", factOf(e, newJob.id()).status());
		}
	}

	@Test
	@Scenario("T2")
	void answersNeedNoObservation() {
		try (Engine e = TestHomes.engine("t2-answer-only")) {
			remember(e, "Two Annas.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Anna Berg", "person"));
			RememberOutcome q = remember(e, "I know Anna.", proposal().fact("self", "knows", "Anna"));
			long observations = e.observations().count();
			Entity berg = e.entities().byRef("Anna Berg").orElseThrow();
			List<Map<String, Object>> resolved = e.answer(List.of(new Resolve(questionId(q), berg.ref())));
			assertEquals("answered", resolved.getFirst().get("status"), resolved.toString());
			assertEquals(observations, e.observations().count(), "no observation was recorded for the answer");
			assertEquals(List.of("Mattias Sandell knows Anna Berg"), current(e));
			assertEquals(0, e.questions().openCount());
			// A conflict answered the same way: the closing date is today, the record cites the question's observation.
			RememberOutcome hooli = remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			RememberOutcome acme = remember(e, "I work at Acme.",
					proposal().entity("e1", "Acme", "organization").fact("self", "works_at", "e1"));
			e.answer(List.of(new Resolve(questionId(acme), "supersede")));
			assertEquals("superseded", factOf(e, factId(hooli, 0)).status());
			assertEquals("current", factOf(e, factId(acme, 0)).status());
			assertEquals(observations + 2, e.observations().count());
		}
	}

	@Test
	@Scenario("T3")
	void aNameThatSaysMoreIsAskedAboutNotMergedAndForgetTakesItsAliasBack() {
		try (Engine e = TestHomes.engine("t3-fuzzy")) {
			remember(e, "I use a Raspberry Pi.",
					proposal().entity("e1", "Raspberry Pi", "thing").fact("self", "uses", "e1"));
			RememberOutcome five = remember(e, "I bought a Raspberry Pi 5.",
					proposal().entity("e1", "Raspberry Pi 5", "thing").fact("self", "owns", "e1"));
			assertEquals("entity_resolution", five.applied().questions().getFirst().get("kind"),
					"a version is an identity: asked, not merged");
			assertEquals(0, five.applied().facts().size());
			assertTrue(e.entities().byRef("Raspberry Pi").isPresent(),
					"entities now: "
							+ e.entities().active(10).stream()
									.map(x -> x.ref() + " " + x.name() + " " + e.entities().aliases(x.id())).toList()
							+ " question: " + five.applied().questions());
			remember(e, "I live in Kanton Luzern.",
					proposal().entity("e1", "Kanton Luzern", "place").fact("self", "lives_in", "e1"));
			RememberOutcome city = remember(e, "Luzern is a city.", proposal().entity("e1", "Luzern", "place"));
			assertEquals("entity_resolution", city.applied().questions().getFirst().get("kind"),
					"a place word names another level: asked, not merged");
			// An organization's type word is a suffix of the same thing: still merged silently.
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			RememberOutcome inc = remember(e, "Hooli Inc pays well.",
					proposal().entity("e1", "Hooli Inc", "organization").fact("self", "prefers", "e1"));
			assertEquals("fuzzy", inc.applied().entities().getFirst().resolution());
			Entity hooli = e.entities().byRef("Hooli").orElseThrow();
			assertTrue(e.entities().aliases(hooli.id()).contains("Hooli Inc"));
			// Forgetting the observation that added the alias takes the alias back; the entity's own name stays.
			e.forget(inc.observation().observationId());
			assertFalse(e.entities().aliases(hooli.id()).contains("Hooli Inc"));
			assertTrue(e.entities().aliases(hooli.id()).contains("Hooli"));
		}
	}

	@Test
	@Scenario("T4")
	void forgettingAFactForgetsItsCorrections() {
		try (Engine e = TestHomes.engine("t4-forget-corrections")) {
			RememberOutcome o = remember(e, "I live in Zug.",
					proposal().entity("e1", "Zug", "place").fact("self", "lives_in", "e1"));
			var corrected = e.correct(factId(o, 0), Map.of("object", "Baar"), "it is Baar");
			var again = e.correct(corrected.replacement().id(), Map.of("object", "Cham"), "no, Cham");
			assertEquals(3, e.facts().factsOf(e.entities().owner().id()).size());
			long records = e.observations().all().stream().filter(x -> "correction".equals(x.source().kind())).count();
			assertEquals(2, records);
			assertTrue(e.forget(o.observation().observationId()));
			assertEquals(0, e.facts().factsOf(e.entities().owner().id()).size(),
					"the fact and every correction of it are gone");
			assertTrue(recall(e, "where does Mattias live").hits().isEmpty());
			assertTrue(e.facts().get(again.replacement().id()).isEmpty());
		}
	}

	@Test
	@Scenario("T5")
	void aRoleAtAnOrganizationEndsWithTheEmployment() {
		try (Engine e = TestHomes.engine("t5-scoped")) {
			remember(e, "I work at Acme as a senior firmware engineer.",
					proposal().entity("e1", "Acme", "organization").fact("self", "works_at", "e1").fact(fact("self",
							"holds_role", "senior firmware engineer", null, "e1", null, null, null, null, null)));
			assertEquals(2, current(e).size(), current(e).toString());
			// Superseded through an event.
			RememberOutcome globex = remember(e, "I joined Globex in 2024.",
					proposal().entity("e1", "Globex", "organization").event("ev1", "joined", "2024", "self", "e1"));
			List<String> now = current(e);
			assertTrue(now.stream().noneMatch(s -> s.contains("senior firmware engineer")), now.toString());
			Fact role = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "holds_role".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2024-01-01", role.validEnd());
			assertEquals("dependency", role.endSource());
			assertEquals(1, globex.applied().superseded().size(), globex.applied().superseded().toString());
			// Superseded through a conflict answer.
			remember(e, "I hold the role of CTO at Globex.", proposal().entity("e1", "Globex", "organization")
					.fact(fact("self", "holds_role", "CTO", null, "e1", null, null, null, null, null)));
			RememberOutcome initrode = remember(e, "I work at Initrode.",
					proposal().entity("e1", "Initrode", "organization").fact("self", "works_at", "e1"));
			e.answer(List.of(new Resolve(questionId(initrode), "supersede")));
			assertTrue(current(e).stream().noneMatch(s -> s.contains("CTO")), current(e).toString());
			// Not through a mere restatement or an unrelated closure.
			assertTrue(current(e).stream().anyMatch(s -> s.contains("Initrode")));
		}
	}

	@Test
	@Scenario("T8")
	void anEntityIsCorrectedByIdAndAWrongAliasDropped() {
		try (Engine e = TestHomes.engine("t8-entity-correct")) {
			remember(e, "I live in Kanton Luzern.",
					proposal().entity("e1", "Kanton Luzern", "place").fact("self", "lives_in", "e1"));
			Entity kanton = e.entities().byRef("Kanton Luzern").orElseThrow();
			// An alias a wrong match left behind, as an older release did.
			e.database().write(tx -> tx.update("INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (?,?,?)",
					kanton.id(), "Luzern", "luzern"));
			assertTrue(e.entities().aliases(kanton.id()).contains("Luzern"));
			Map<String, Object> m = e.correctEntity(kanton.id(), Map.of("aliases", List.of("Kanton Luzern", "LU")),
					"the city is not the canton");
			List<String> aliases = e.entities().aliases(kanton.id());
			assertFalse(aliases.contains("Luzern"), aliases.toString());
			assertTrue(aliases.contains("LU") && aliases.contains("Kanton Luzern"), aliases.toString());
			assertEquals(kanton.ref(), m.get("entity"));
			// From then on the city is a thing of its own.
			RememberOutcome city = remember(e, "Luzern is a city.", proposal().entity("e1", "Luzern", "place"));
			assertEquals("entity_resolution", city.applied().questions().getFirst().get("kind"),
					"no alias left to resolve by: asked");
			// A rename re-renders what mentions the entity; the type is correctable too; the name never drops.
			Map<String, Object> renamed = e.correctEntity(kanton.id(), Map.of("name", "Canton of Lucerne"), "English");
			assertEquals(1, renamed.get("rerendered_facts"));
			assertTrue(current(e).getFirst().contains("Canton of Lucerne"), current(e).toString());
			e.correctEntity(kanton.id(), Map.of("aliases", List.of()), "none");
			assertTrue(e.entities().aliases(kanton.id()).contains("Canton of Lucerne"));
			e.correctEntity(kanton.id(), Map.of("type", "country"), "a joke");
			assertEquals("country", e.entities().get(kanton.id()).orElseThrow().type());
			// The owner's identity cannot be dropped.
			e.correctEntity(e.entities().owner().id(), Map.of("aliases", List.of()), "none");
			assertTrue(e.entities().aliases(e.entities().owner().id()).contains("Mattias"));
			assertTrue(e.entities().aliases(e.entities().owner().id()).contains("mattias@example.com"));
		}
	}

	@Test
	@Scenario("T9")
	void aPresentTenseMissNamesTheEndedFacts() {
		try (Engine e = TestHomes.engine("t9-ended-miss")) {
			remember(e, "Anna worked at Initrode from 2019 to 2022, and at Hooli before that.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Initrode", "organization")
							.entity("e3", "Hooli", "organization")
							.fact(fact("e1", "works_at", "e2", null, null, "2019", "2022", null, null, null))
							.fact(fact("e1", "works_at", "e3", null, null, "2015", "2019", null, null, null)));
			RecallResult r = recall(e, "where does Anna work");
			assertEquals("miss", r.structured().state(), r.text());
			assertEquals(2, r.structured().ended().size(), "both ended jobs are named");
			assertTrue(r.text().contains("no current value; 2 ended facts:"), r.text());
			assertTrue(r.text().contains("Initrode") && r.text().contains("Hooli"), r.text());
			assertFalse(r.text().contains("no such fact is known"), r.text());
			// With history asked for, they are the answer; as of a date, the one then current is.
			RecallResult h = e.recall().recall("where does Anna work", null, 800, 10, true);
			assertEquals("matched", h.structured().state(), h.text());
			assertEquals(2, h.structured().facts().size());
			RecallResult then = recall(e, "where does Anna work", java.time.Instant.parse("2020-06-01T00:00:00Z"));
			assertEquals("matched", then.structured().state());
			assertEquals("Anna Lindqvist works at Initrode (2019 \u2013 2022)",
					then.structured().facts().getFirst().rendering());
			// A predicate with nothing at all says so as before.
			RecallResult none = recall(e, "where does Anna live");
			assertTrue(none.text().contains("no such fact is known"), none.text());
		}
	}

	@Test
	@Scenario("T10")
	void aClosureOverAnUnknownTypeIsSkippedWithTheKnownTypesNamed() {
		try (Engine e = TestHomes.engine("t10-closure-type")) {
			remember(e, "I live in Schübelbach.",
					proposal().entity("e1", "Schübelbach", "place").fact("self", "lives_in", "e1"));
			RememberOutcome bad = remember(e, "That is the only place I live.",
					proposal().closure("self", "lives_in", "exhaustive"));
			assertTrue(bad.applied().facts().isEmpty(), "nothing stored: " + bad.applied().facts());
			String warning = bad.applied().warnings().getFirst();
			assertTrue(warning.contains("Closure skipped: closure over unknown entity type 'exhaustive'"), warning);
			assertTrue(warning.contains("place") && warning.contains("organization"),
					"the known types are listed: " + warning);
			assertEquals(1, e.facts().factsOf(e.entities().owner().id()).size());
			// A synonym of a registered type completes that type.
			RememberOutcome ok = remember(e, "That is the only nation I live in.",
					proposal().closure("self", "lives_in", "nation"));
			assertTrue(ok.applied().facts().getFirst().rendering().contains("among countries"),
					ok.applied().facts().getFirst().rendering());
		}
	}

	@Test
	@Scenario("T11")
	void aRestrictionReadsGrammatically() {
		try (Engine e = TestHomes.engine("t11-only")) {
			RememberOutcome lives = remember(e, "I only live in Switzerland.",
					proposal().entity("e1", "Switzerland", "country").fact(only("self", "lives_in", "e1")));
			assertFalse(lives.applied().facts().isEmpty(), "applied: " + lives.applied());
			assertEquals("Mattias Sandell lives only within Switzerland",
					lives.applied().facts().getFirst().rendering());
			RememberOutcome owns = remember(e, "I only own property in Switzerland.",
					proposal().entity("e1", "Switzerland", "country").fact(only("self", "owns", "e1")));
			assertEquals("Mattias Sandell owns only within Switzerland", owns.applied().facts().getFirst().rendering());
		}
		try (Engine e = TestHomes.engine(TestHomes.fresh("t11-only-de"), Lang.DE)) {
			RememberOutcome lives = remember(e, "Ich wohne nur in der Schweiz.",
					proposal().entity("e1", "Schweiz", "country").fact(only("self", "lives_in", "e1")));
			assertEquals("Mattias Sandell wohnt nur innerhalb von Schweiz",
					lives.applied().facts().getFirst().rendering());
		}
	}

	@Test
	@Scenario("T12")
	void aMissDoesNotBlameTheOtherEntitysEvents() {
		try (Engine e = TestHomes.engine("t12-miss-events")) {
			remember(e, "Anna joined Initrode in 2024.", proposal().entity("e1", "Anna Lindqvist", "person")
					.entity("e2", "Initrode", "organization").event("ev1", "joined", "2024", "e1", "e2"));
			RecallResult r = recall(e, "does Mattias work at Initrode");
			assertEquals("miss", r.structured().state(), r.text());
			assertEquals(1, r.events().size(), "Initrode's event is still shown, as context");
			assertFalse(r.text().contains("about Mattias Sandell on the next line may explain why"), r.text());
			assertTrue(r.text().contains("involves what else the question names, not Mattias Sandell"), r.text());
			// An event the subject took part in may explain the miss, and is said to.
			remember(e, "I left Initrode in 2020.",
					proposal().entity("e1", "Initrode", "organization").event("ev1", "left", "2020", "self", "e1"));
			RecallResult again = recall(e, "does Mattias work at Initrode");
			assertTrue(again.text().contains("1 event about Mattias Sandell on the next line may explain why"),
					again.text());
		}
	}

	@Test
	@Scenario("T13")
	void lettersAloneDoNotRaiseAQuestionAcrossTypes() {
		try (Engine e = TestHomes.engine("t13-fuzzy-types")) {
			remember(e, "Sandvik is a customer.",
					proposal().entity("e1", "Sandvik", "organization").fact("self", "knows", "e1"));
			// No type given and no name part shared: "Sandvol" is new, not a question about Sandvik.
			RememberOutcome untyped = remember(e, "I know Sandvol.", proposal().fact("self", "knows", "Sandvol"));
			assertTrue(untyped.applied().questions().stream().noneMatch(q -> "entity_resolution".equals(q.get("kind"))),
					untyped.applied().questions().toString());
			assertTrue(e.entities().byRef("Sandvol").isPresent());
			// The same letters with the same type still ask, as before.
			RememberOutcome typed = remember(e, "Sandvig is a supplier.",
					proposal().entity("e1", "Sandvig", "organization").fact("self", "knows", "e1"));
			assertEquals("entity_resolution", typed.applied().questions().getFirst().get("kind"),
					typed.applied().questions().toString());
			// A shared name part asks whatever the type: "Anna" against Anna Lindqvist.
			remember(e, "Anna Lindqvist is a friend.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome anna = remember(e, "I know Anna.", proposal().fact("self", "knows", "Anna"));
			assertEquals("entity_resolution", anna.applied().questions().getFirst().get("kind"),
					anna.applied().questions().toString());
		}
	}

	@Test
	@Scenario("T14")
	void consolidateListsVocabularyNothingUsesWhoseDefinitionWasForgotten() {
		try (Engine e = TestHomes.engine("t14-unused-vocabulary")) {
			RememberOutcome def = remember(e, "I built a robot called Coff-E.",
					proposal()
							.entityType(new EntityTypeDef("robot", "A machine that acts on its own.", "thing",
									List.of(), List.of()))
							.eventType(new EventTypeDef("built", "Someone finished making something.", List.of(),
									List.of(), List.of(), null, List.of("built", "made"), null))
							.entity("e1", "Coff-E", "robot").event("ev1", "built", "2025", "self", "e1"));
			assertTrue(e.consolidate(true).unusedVocabulary().isEmpty(), "in use: not listed");
			e.forget(def.observation().observationId());
			List<Map<String, Object>> dry = e.consolidate(true).unusedVocabulary();
			assertEquals(List.of("built", "robot"), dry.stream()
					.map(m -> (String) (m.containsKey("event_type") ? m.get("event_type") : m.get("entity_type")))
					.sorted().toList(), dry.toString());
			assertEquals("obs-" + def.observation().observationId() + " (forgotten)", dry.getFirst().get("defined_by"));
			assertEquals(0, dry.getFirst().get("uses"));
			List<Map<String, Object>> wet = e.consolidate(false).unusedVocabulary();
			assertEquals(2, wet.size(), "still listed once the definition is unanchored: " + wet);
			assertTrue(wet.stream().noneMatch(m -> m.containsKey("predicate")), "seeded vocabulary never: " + wet);
		}
	}

	@Test
	@Scenario("T15")
	void aCoupleWhoAreNotMarried() {
		try (Engine e = TestHomes.engine("t15-partner")) {
			assertTrue(e.predicates().get("partner_of").orElseThrow().symmetric());
			RememberOutcome o = remember(e, "Anna and Bo live together.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Bo Nyberg", "person")
							.fact(fact("e2", "partner_of", "e1", "boyfriend", null, null, null, null, null, null)));
			assertEquals("Bo Nyberg is Anna Lindqvist's boyfriend", o.applied().facts().getFirst().rendering());
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			RememberOutcome w = remember(e, "Anna married Bo in June 2024.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Bo Nyberg", "person").event("ev1",
							"married", "2024-06", "e1", "e2"));
			Fact partner = factOf(e, factId(o, 0));
			assertEquals("2024-06-01", partner.validEnd(), "closed from the other side: " + partner.rendering());
			assertEquals("ended", partner.state(e.clock().instant()));
			assertTrue(w.applied().facts().stream().anyMatch(f -> "spouse_of".equals(f.predicate())),
					"the wedding opens spouse_of: " + w.applied().facts());
			RecallResult p = recall(e, "who is Anna's partner");
			assertEquals("miss", p.structured().state(), p.text());
			assertTrue(p.text().contains("1 ended fact: Bo Nyberg is Anna Lindqvist's boyfriend"), p.text());
			RecallResult m = recall(e, "who is Anna married to");
			assertEquals("matched", m.structured().state(), m.text());
			// separated ends a partnership too.
			RememberOutcome c = remember(e, "Lisa and Tom are a couple.",
					proposal().entity("e1", "Lisa Berg", "person").entity("e2", "Tom Berg", "person")
							.fact(fact("e1", "partner_of", "e2", null, null, "2020", null, null, null, null)));
			remember(e, "Tom and Lisa split up in 2023.", proposal().entity("e1", "Tom Berg", "person")
					.entity("e2", "Lisa Berg", "person").event("ev1", "separated", "2023", "e1", "e2"));
			assertEquals("2023-01-01", factOf(e, factId(c, 0)).validEnd());
		}
	}

	@Test
	@Scenario("T16")
	void anEngagement() {
		try (Engine e = TestHomes.engine("t16-engaged")) {
			RememberOutcome o = remember(e, "Anna and Bo got engaged in 2023.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Bo Nyberg", "person").event("ev1",
							"engaged", "2023", "e1", "e2"));
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			Fact engaged = e.facts().factsOfObservation(o.observation().observationId()).stream()
					.filter(f -> "engaged_to".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("Anna Lindqvist is engaged to Bo Nyberg (since 2023)", engaged.rendering());
			remember(e, "Anna married Bo in June 2024.", proposal().entity("e1", "Anna Lindqvist", "person")
					.entity("e2", "Bo Nyberg", "person").event("ev1", "married", "2024-06", "e1", "e2"));
			Fact after = factOf(e, engaged.id());
			assertEquals("2024-06-01", after.validEnd(), after.rendering());
			assertEquals("ended", after.state(e.clock().instant()));
		}
	}

	@Test
	@Scenario("T17")
	void aStepParent() {
		try (Engine e = TestHomes.engine("t17-step-parent")) {
			RememberOutcome o = remember(e, "Lars is my stepfather.", proposal().entity("e1", "Lars Berg", "person")
					.fact(fact("e1", "step_parent_of", "self", "stepfather", null, null, null, null, null, null)));
			assertEquals("Lars Berg is Mattias Sandell's stepfather", o.applied().facts().getFirst().rendering());
			assertEquals("step_parent_of", o.applied().facts().getFirst().predicate());
			assertFalse(e.predicates().get("parent_of").orElseThrow().qualifiers().contains("stepfather"));
			assertTrue(e.predicates().get("step_parent_of").orElseThrow().lexicon().contains("stepfather"));
		}
		try (Engine e = TestHomes.engine(TestHomes.fresh("t17-step-parent-de"), Lang.DE)) {
			RememberOutcome o = remember(e, "Lars ist mein Stiefvater.", proposal().entity("e1", "Lars Berg", "person")
					.fact(fact("e1", "step_parent_of", "self", "stepfather", null, null, null, null, null, null)));
			assertEquals("Lars Berg ist Stiefvater von Mattias Sandell", o.applied().facts().getFirst().rendering());
		}
	}

	@Test
	@Scenario("T18")
	void anUndatedDeathEndsFactsWithoutInventingADate() {
		try (Engine e = TestHomes.engine("t18-undated-death")) {
			RememberOutcome o = remember(e, "Bosse lived in Zug.", proposal().entity("e1", "Bosse Nyberg", "person")
					.entity("e2", "Zug", "place").fact("e1", "lives_in", "e2"));
			remember(e, "Bosse has died.",
					proposal().entity("e1", "Bosse Nyberg", "person").event("ev1", "died", null, "e1"));
			Fact f = factOf(e, factId(o, 0));
			assertTrue(f.ended(), f.rendering());
			assertNull(f.validEnd(), "no date was given, none is invented: " + f.rendering());
			assertEquals("ended", f.state(e.clock().instant()));
			long bosse = e.entities().byRef("Bosse Nyberg").orElseThrow().id();
			assertNull(e.database().read(
					tx -> tx.query("SELECT existed_end FROM entity WHERE id = ?", bosse).getFirst().str("existed_end")),
					"the entity's end is not invented either");
		}
	}

	@Test
	@Scenario("T6")
	void aStoredQualifierCuesItsPredicate() {
		try (Engine e = TestHomes.engine("t6-qualifier-cue")) {
			remember(e, "Erik is my godfather.", proposal().entity("e1", "Erik Nyberg", "person")
					.fact(fact("self", "related_to", "e1", "godfather", null, null, null, null, null, null)));
			remember(e, "Katja was my partner.", proposal().entity("e1", "Katja Berg", "person")
					.fact(fact("self", "related_to", "e1", "former partner", null, null, null, null, null, null)));
			RecallResult r = recall(e, "who is Mattias's godfather");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("related_to", r.structured().predicate());
			assertEquals(List.of("Mattias Sandell is related to Erik Nyberg (godfather)"),
					r.structured().facts().stream().map(Fact::rendering).toList(), "the qualifier narrows the answer");
			RecallResult p = recall(e, "who was Mattias's former partner");
			assertTrue(p.structured().matched(), p.text());
			assertEquals(1, p.structured().facts().size());
			assertTrue(p.structured().facts().getFirst().rendering().contains("Katja"));
		}
	}

	@Test
	@Scenario("T7")
	void consolidateRemovesWhatForgettingLeftBehind() {
		try (Engine e = TestHomes.engine("t7-orphans")) {
			RememberOutcome a = remember(e, "I met Tobias Falk at the fair.",
					proposal()
							.eventType(new EventTypeDef("met_at_fair", "Subject met object at a fair.",
									List.of("knows"), List.of(), List.of(), null, List.of("fair")))
							.entity("e1", "Tobias Falk", "person").fact("self", "knows", "e1"));
			RememberOutcome b = remember(e, "Tobias works at Acme.", proposal().entity("e1", "Tobias Falk", "person")
					.entity("e2", "Acme", "organization").fact("e1", "works_at", "e2"));
			Entity tobias = e.entities().byRef("Tobias Falk").orElseThrow();
			e.forget(a.observation().observationId());
			assertTrue(e.entities().get(tobias.id()).isPresent(), "still referenced by the second observation");
			e.forget(b.observation().observationId());
			assertTrue(e.entities().get(tobias.id()).isEmpty(),
					"the forget that removed the last reference looked again: Tobias is gone");
			assertEquals(a.observation().observationId(), e.eventTypes().get("met_at_fair").orElseThrow().definedBy(),
					"the definition points at a tombstone");
			// A re-seed keeps entities on purpose; consolidate sweeps what a later forget left.
			RememberOutcome c1 = remember(e, "Lisa Berg is a colleague.",
					proposal().entity("e1", "Lisa Berg", "person").fact("self", "knows", "e1"));
			RememberOutcome c2 = remember(e, "Lisa works at Acme.", proposal().entity("e1", "Lisa Berg", "person")
					.entity("e2", "Acme", "organization").fact("e1", "works_at", "e2"));
			Entity lisa = e.entities().byRef("Lisa Berg").orElseThrow();
			e.forget(c1.observation().observationId());
			e.forget(c2.observation().observationId(), true);
			assertTrue(e.entities().get(lisa.id()).isPresent(), "kept for a re-seed");
			assertEquals(0, e.consolidate(true).removedEntities(), "a dry run removes nothing");
			var c = e.consolidate(false);
			assertEquals(2, c.removedEntities(), "Lisa and Acme, orphaned and no longer wanted");
			assertTrue(e.entities().get(lisa.id()).isEmpty());
			assertNull(e.eventTypes().get("met_at_fair").orElseThrow().definedBy(), "the definition stays, unanchored");
			assertTrue(e.entities().get(e.entities().owner().id()).isPresent(), "the owner is never an orphan");
		}
	}
}
