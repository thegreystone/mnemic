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
import se.hirt.mnemic.Engine.Reading;
import se.hirt.mnemic.Engine.Rebuilt;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * The observation is the source of truth and a reading is how it was understood: EVALUATION.md S27 (a reading
 * replaced), S28 (closures undone with it), S29 (a rebuild from the log), S30 (corrections replayable), S31 (events
 * typed by a sentence reported for re-reading).
 */
class RereadTest {

	private static long obsId(RememberOutcome o) {
		return o.observation().observationId();
	}

	private static long factId(RememberOutcome o, int i) {
		return Long.parseLong(o.applied().facts().get(i).id().substring(2));
	}

	private static Fact factOf(Engine e, long id) {
		return e.facts().get(id).orElseThrow();
	}

	private static String existedEnd(Engine e, long entityId) {
		return e.database().read(tx -> tx.queryOne("SELECT existed_end FROM entity WHERE id = ?", entityId)
				.map(r -> r.str("existed_end")).orElse(null));
	}

	private static List<String> current(Engine e, long entityId) {
		return e.facts().factsOf(entityId).stream().filter(Fact::current).map(Fact::rendering).sorted().toList();
	}

	@Test
	void aReReadingKeepsTheAliasesOtherObservationsReachedTheEntityBy() {
		try (Engine e = TestHomes.engine("s27-reread-alias")) {
			// The first reading names the car in full and gives it a short alias; a later observation uses the alias.
			RememberOutcome first = remember(e, "I ordered a Polestar 4 Long Range Dual Motor.",
					proposal().entity("e1", "Polestar 4 Long Range Dual Motor", "vehicle", "Polestar 4").fact("self",
							"owns", "e1"));
			long obs = obsId(first);
			Entity car = e.entities().byRef("Polestar 4").orElseThrow();
			RememberOutcome later = remember(e, "The Polestar 4 gets its plates on Tuesday.",
					proposal().entity("e1", "Polestar 4", "vehicle").fact("self", "uses", "e1"));
			assertTrue(later.applied().questions().isEmpty(), later.applied().questions().toString());
			assertEquals(car.id(), e.entities().byRef(later.applied().entities().getFirst().id()).orElseThrow().id(),
					"the later mention resolved through the alias");
			// The first reading is replaced by one that leaves the alias out (an assistant fixing a date, say).
			Reading r = e.reread(obs, proposal().entity("e1", "Polestar 4 Long Range Dual Motor", "vehicle")
					.fact("self", "owns", "e1").build());
			assertTrue(r.replaced());
			assertEquals(car.id(), e.entities().byRef("Polestar 4").orElseThrow().id(),
					"the alias the later observation used survives the re-reading (it was lost on 2026-09-22)");
			assertTrue(e.entities().aliases(car.id()).contains("Polestar 4"),
					e.entities().aliases(car.id()).toString());
			// Forgetting for privacy is another matter: with nothing else using the entity, its aliases go too.
			RememberOutcome lone = remember(e, "I also looked at a Volvo EX30 Twin Motor.", proposal()
					.entity("e1", "Volvo EX30 Twin Motor", "vehicle", "EX30").fact("self", "considering", "e1"));
			assertTrue(e.entities().byRef("EX30").isPresent());
			e.forget(obsId(lone), false);
			assertTrue(e.entities().byRef("EX30").isEmpty(), "the alias went with the only observation that used it");
		}
	}

	@Test
	@Scenario("S27")
	void aReadingIsReplacedAndTheObservationKeepsItsIdentity() {
		try (Engine e = TestHomes.engine("s27-reread")) {
			Instant said = Instant.parse("2025-03-01T10:00:00Z");
			RememberOutcome o = remember(e, "I ordered a red bicycle from Nordvik Cycles in March.", said,
					proposal().entity("e1", "Nordvik Cycles", "organization").event("ev1",
							"ordered a red bicycle from the shop in town", "2025-03", "self", "e1"));
			long obs = obsId(o);
			assertEquals(1, o.applied().events().size());
			assertEquals(1, o.applied().warnings().size(), "a sentence where the type goes");
			long entityId = e.entities().byRef("Nordvik Cycles").orElseThrow().id();
			// The assistant reads the same text again, properly: a type, a participant, and the fact it implies.
			Reading r = e.reread(obs,
					proposal().entity("e1", "Nordvik Cycles", "organization").entity("e2", "the red bicycle", "thing")
							.event("ev1", "ordered", "2025-03", "self", "e2").fact("self", "buys_from", "e1").build());
			assertTrue(r.replaced());
			assertEquals(1, r.removed().events().size(), r.removed().toString());
			assertEquals(0, r.removed().facts().size());
			assertEquals("ordered", r.applied().events().getFirst().type());
			assertEquals(List.of("Mattias Sandell buys from Nordvik Cycles"),
					r.applied().facts().stream().map(f -> f.rendering()).toList());
			Observation same = e.observations().get(obs).orElseThrow();
			assertEquals(said, same.observedAt(), "the text keeps its date");
			assertEquals("I ordered a red bicycle from Nordvik Cycles in March.", same.text());
			assertTrue(same.proposalJson().contains("buys_from"), "the reading on record is the new one");
			assertEquals(entityId, e.entities().byRef("Nordvik Cycles").orElseThrow().id(), "entities keep their ids");
			assertEquals(1, e.events().eventsOfObservation(obs).size(), "the old event is gone");
			assertEquals(obs, factOf(e, factId(new RememberOutcome(o.observation(), r.applied(), List.of(), "x"), 0))
					.observationId(), "provenance is the original observation");
			assertEquals(List.of(), e.consolidate(true).descriptiveEvents(), "nothing left to re-read");
			assertEquals(1, e.observations().count(), "no second observation was created");
			// A reading identical to the current one changes nothing but the ids.
			Reading again = e.reread(obs, Proposal(same.proposalJson()));
			assertEquals(1, again.removed().facts().size());
			assertEquals(List.of("Mattias Sandell buys from Nordvik Cycles"), current(e, e.entities().owner().id()));
		}
	}

	private static se.hirt.mnemic.proposal.Proposal Proposal(String json) {
		return se.hirt.mnemic.proposal.Proposal.parse(json);
	}

	@Test
	@Scenario("S27")
	void aReReadKeepsWhatOtherObservationsAlsoSaidAndRefusesCorrectionRecords() {
		try (Engine e = TestHomes.engine("s27-shared")) {
			RememberOutcome a = remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "As I said, I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			long hooli = factId(a, 0);
			assertEquals(2, factOf(e, hooli).corroborations());
			// The first observation is re-read as saying something else: the fact survives, stated by the second.
			Reading r = e.reread(obsId(a),
					proposal().entity("e1", "Hooli", "organization").fact("self", "member_of", "e1").build());
			assertEquals(0, r.removed().facts().size(), "a fact another observation also stated is not removed");
			assertTrue(factOf(e, hooli).current());
			assertEquals(1, factOf(e, hooli).corroborations());
			assertNotEquals(obsId(a), factOf(e, hooli).observationId(),
					"re-homed to the observation that still says it");
			// A correction record is not a reading of the world; it is refused.
			e.correct(hooli, Map.of("object", "Initrode"), "moved");
			Observation record = e.observations().all().stream().filter(o -> "correction".equals(o.source().kind()))
					.findFirst().orElseThrow();
			assertThrows(MnemicException.class, () -> e.reread(record.id(), proposal().build()));
			// And a re-read of an observation held behind a question raises the question afresh, once.
			remember(e, "Anna Lindqvist and Anna Berg are both Annas.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Anna Berg", "person"));
			RememberOutcome q = remember(e, "I know Anna.", proposal().fact("self", "knows", "Anna"));
			assertEquals(1, q.applied().questions().size(), q.applied().questions().toString());
			Reading held = e.reread(obsId(q), proposal().fact("self", "knows", "Anna").build());
			assertEquals(1, held.applied().questions().size(), held.applied().questions().toString());
			assertEquals(1, e.questions().openCount(),
					"the old question was dismissed, the new one stands: " + e.questions().open(10).stream()
							.map(x -> x.ref() + " obs-" + x.observationId() + " " + x.kind()).toList());
		}
	}

	@Test
	@Scenario("S28")
	void closuresAndEntityEndsAreUndoneWhenTheirEventGoes() {
		try (Engine e = TestHomes.engine("s28-closures")) {
			RememberOutcome job = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e1", "Hooli", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "2018", null, null, null, null)));
			long works = factId(job, 0);
			RememberOutcome left = remember(e, "I left Hooli in 2020.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "left", "2020", "self", "e1"));
			assertEquals("Mattias Sandell works at Hooli (2018 – 2020)", factOf(e, works).rendering());
			assertEquals("event", factOf(e, works).endSource());
			// The leaving was misread: re-read as something else, and the job is open again.
			Reading r = e.reread(obsId(left), proposal().entity("e1", "Hooli", "organization")
					.event("ev1", "visited", "2020", "self", "e1").build());
			assertEquals(1, r.removed().reopened(), r.removed().toString());
			assertEquals("Mattias Sandell works at Hooli (since 2018)", factOf(e, works).rendering());
			assertNull(factOf(e, works).validEnd());
			assertNull(factOf(e, works).endSource());
			assertFalse(factOf(e, works).ended());
			assertEquals(0, e.facts().supersessionsOf(works).size(), "the record of the closure went with the event");
			// The same for a death that was not one.
			RememberOutcome born = remember(e, "Konrad Nyberg was born in 1940.",
					proposal().entity("e1", "Konrad Nyberg", "person").event("ev1", "born", "1940", "e1"));
			Entity konrad = e.entities().byRef("Konrad Nyberg").orElseThrow();
			RememberOutcome lives = remember(e, "Konrad lives in Uppsala.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Uppsala", "place").fact("e1",
							"lives_in", "e2"));
			long uppsala = factId(lives, 0);
			RememberOutcome died = remember(e, "Konrad died in 2024.",
					proposal().entity("e1", "Konrad Nyberg", "person").event("ev1", "died", "2024", "e1"));
			assertEquals("2024", existedEnd(e, konrad.id()).substring(0, 4));
			assertTrue(factOf(e, uppsala).rendering().contains("2024"), factOf(e, uppsala).rendering());
			Reading undo = e.reread(obsId(died),
					proposal().entity("e1", "Konrad Nyberg", "person").event("ev1", "retired", "2024", "e1").build());
			assertEquals(1, undo.removed().reopened());
			assertNull(existedEnd(e, konrad.id()), "the person exists again");
			assertEquals("Konrad Nyberg lives in Uppsala", factOf(e, uppsala).rendering());
			assertEquals(1, born.applied().events().size());
			// Forget shares the same undoing.
			RememberOutcome quit = remember(e, "I left Hooli in 2021.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "left", "2021", "self", "e1"));
			assertEquals("Mattias Sandell works at Hooli (2018 – 2021)", factOf(e, works).rendering());
			e.forget(obsId(quit));
			assertEquals("Mattias Sandell works at Hooli (since 2018)", factOf(e, works).rendering());
		}
	}

	@Test
	@Scenario("S28")
	void aSupersessionByARemovedFactIsUndone() {
		try (Engine e = TestHomes.engine("s28-supersede")) {
			RememberOutcome a = remember(e, "I live in Zug.",
					proposal().entity("e1", "Zug", "place").fact("self", "lives_in", "e1"));
			RememberOutcome b = remember(e, "I moved to Luzern in 2022.",
					proposal().entity("e1", "Luzern", "place").event("ev1", "moved", "2022", "self", "e1"));
			long zug = factId(a, 0);
			assertEquals("superseded", factOf(e, zug).status());
			Reading r = e.reread(obsId(b),
					proposal().entity("e1", "Luzern", "place").event("ev1", "visited", "2022", "self", "e1").build());
			assertEquals(1, r.removed().facts().size(), "the opened lives_in Luzern went");
			assertEquals(1, r.removed().reopened());
			assertEquals("current", factOf(e, zug).status());
			assertNull(factOf(e, zug).supersededBy());
			assertEquals("Mattias Sandell lives in Zug", factOf(e, zug).rendering());
		}
	}

	@Test
	@Scenario("S30")
	void aCorrectionRecordCarriesItsReading() {
		try (Engine e = TestHomes.engine("s30-record")) {
			RememberOutcome a = remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			e.correct(factId(a, 0), Map.of("object", "Initrode"), "wrong company");
			RememberOutcome b = remember(e, "I decided to sell the boat.",
					proposal().fact("self", "decided", "sell the boat"));
			e.correct(factId(b, 0), Map.of("wrong", true), "a leaning, never a decision");
			List<Observation> records = e.observations().all().stream()
					.filter(o -> "correction".equals(o.source().kind())).toList();
			assertEquals(2, records.size());
			Map<String, Object> correction = Json.readMap(records.get(0).proposalJson());
			@SuppressWarnings("unchecked")
			Map<String, Object> key = (Map<String, Object>) correction.get("corrects");
			assertEquals("self", key.get("subject"));
			assertEquals("works_at", key.get("predicate"));
			assertEquals("Hooli", key.get("object"));
			assertEquals("wrong company", correction.get("reason"));
			assertEquals("Initrode", ((Map<?, ?>) ((List<?>) correction.get("facts")).getFirst()).get("object"));
			Map<String, Object> retraction = Json.readMap(records.get(1).proposalJson());
			assertEquals("decided", ((Map<?, ?>) retraction.get("retracts")).get("predicate"));
			assertEquals(List.of(), retraction.get("facts"));
			assertEquals(0, e.observations().pendingProposals(), "records with a reading are not backlog");
		}
	}

	@Test
	@Scenario("S29")
	void aRebuildReDerivesTheProjectionFromTheLog() {
		try (Engine e = TestHomes.engine("s29-rebuild")) {
			long owner = e.entities().owner().id();
			RememberOutcome job = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e1"));
			remember(e, "I left Hooli in 2020 and joined Initrode.",
					proposal().entity("e1", "Hooli", "organization").entity("e2", "Initrode", "organization")
							.event("ev1", "left", "2020", "self", "e1").event("ev2", "joined", "2020", "self", "e2"));
			RememberOutcome home = remember(e, "I live in Zug.",
					proposal().entity("e1", "Zug", "place").fact("self", "lives_in", "e1"));
			e.correct(factId(home, 0), Map.of("object", "Baar"), "it is Baar");
			RememberOutcome boat = remember(e, "I decided to sell the boat.",
					proposal().fact("self", "decided", "sell the boat"));
			e.correct(factId(boat, 0), Map.of("wrong", true), "never decided");
			// An ambiguity answered by the user: the alias the answer made goes with the reading, so the question
			// comes back on replay and the answer is given again.
			remember(e, "Two Annas.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Anna Berg", "person"));
			RememberOutcome knows = remember(e, "I know Anna.", proposal().fact("self", "knows", "Anna"));
			String qid = (String) knows.applied().questions().getFirst().get("id");
			Entity lindqvist = e.entities().byRef("Anna Lindqvist").orElseThrow();
			remember(e, "The Lindqvist one.", null, new Resolve(qid, lindqvist.ref()));
			// A conflict answered by the user: nothing but the answer says which value stands, so a rebuild must give
			// it again.
			RememberOutcome car = remember(e, "I drive a Volvo.",
					proposal().entity("e1", "Volvo V90", "thing").fact("self", "drives", "e1"));
			e.correctPredicate("drives", Map.of("functional", true), "one car at a time");
			RememberOutcome car2 = remember(e, "I drive a Polestar now.",
					proposal().entity("e1", "Polestar 4", "thing").fact("self", "drives", "e1"));
			String conflict = (String) car2.applied().questions().getFirst().get("id");
			remember(e, "The Polestar replaced the Volvo.", null, new Resolve(conflict, "supersede"));
			assertEquals("superseded", factOf(e, factId(car, 0)).status());
			List<String> before = current(e, owner);
			assertEquals(List.of("Mattias Sandell drives Polestar 4", "Mattias Sandell knows Anna Lindqvist",
					"Mattias Sandell lives in Baar", "Mattias Sandell works at Hooli (2018 – 2020)",
					"Mattias Sandell works at Initrode (since 2020)"), before);
			long observations = e.observations().count();
			long entities = e.entities().byRef("Hooli").orElseThrow().id();
			assertEquals(0, e.questions().openCount());

			Rebuilt r = e.rebuild();
			assertEquals(8, r.observations(), r.toString());
			assertEquals(2, r.corrections());
			assertEquals(2, r.answers(), "the entity answer (its alias went with the reading) and the conflict answer, "
					+ "both given again: " + r);
			assertEquals(List.of(), r.unmatched());
			assertEquals(before, current(e, owner), "the same knowledge, re-derived");
			assertEquals(observations, e.observations().count(), "no observation added or lost");
			assertEquals(entities, e.entities().byRef("Hooli").orElseThrow().id(), "entities keep their ids");
			assertEquals(0, e.questions().openCount(), "the answer was given again");
			assertTrue(
					e.facts().factsOf(owner).stream()
							.anyMatch(f -> "corrected".equals(f.status()) && f.rendering().contains("Zug")),
					"the corrected original is on record again");
			assertTrue(
					e.facts().factsOf(owner).stream()
							.anyMatch(f -> "corrected".equals(f.status()) && f.rendering().contains("sell the boat")),
					"and so is the retraction");
			assertTrue(recall(e, "where does Mattias live").text().contains("Baar"));
			// Idempotent.
			Rebuilt again = e.rebuild();
			assertEquals(before, current(e, owner));
			assertEquals(List.of(), again.unmatched());
			assertEquals(r.factsAfter(), again.factsAfter());
		}
	}

	@Test
	@Scenario("S29")
	void aRebuildReportsACorrectionWhoseFactIsGone() {
		try (Engine e = TestHomes.engine("s29-unmatched")) {
			RememberOutcome home = remember(e, "I live in Zug.",
					proposal().entity("e1", "Zug", "place").fact("self", "lives_in", "e1"));
			e.correct(factId(home, 0), Map.of("object", "Baar"), "it is Baar");
			// The observation is re-read as saying nothing about a home: the correction has nothing to correct.
			e.reread(obsId(home), proposal().entity("e1", "Zug", "place").fact("self", "visited", "e1").build());
			Rebuilt r = e.rebuild();
			assertEquals(1, r.unmatched().size(), r.toString());
			assertTrue(r.unmatched().getFirst().startsWith("obs-"));
			assertEquals(List.of("Mattias Sandell lives in Baar", "Mattias Sandell visited Zug"),
					current(e, e.entities().owner().id()),
					"what the user stated stands, unattached to a correction: " + e.facts()
							.factsOf(e.entities().owner().id()).stream()
							.map(f -> f.ref() + " " + f.status() + " " + f.rendering() + " obs-" + f.observationId())
							.toList());
			assertTrue(e.facts().factsOf(e.entities().owner().id()).stream()
					.noneMatch(f -> "corrected".equals(f.status())));
			// Through consolidate, with the report.
			var c = e.consolidate(false, List.of(), true);
			assertEquals(1, c.rebuilt().unmatched().size());
			assertNull(e.consolidate(true, List.of(), true).rebuilt(), "never on a dry run");
		}
	}

	@Test
	@Scenario("S31")
	void eventsTypedByASentenceAreReportedForReReading() {
		try (Engine e = TestHomes.engine("s31-descriptive")) {
			RememberOutcome o = remember(e, "The dealer confirmed the payment on 11 September.",
					proposal().entity("e1", "Nordvik Marin", "organization").event("ev1",
							"dealer confirmed receipt of the payment", "2026-09-11", "e1", "self"));
			remember(e, "I inherited the cabin.",
					proposal().entity("e1", "the cabin", "place").event("ev1", "inherited", "2019", "self", "e1"));
			List<Map<String, Object>> report = e.consolidate(true).descriptiveEvents();
			assertEquals(1, report.size(), report.toString());
			assertEquals("obs-" + obsId(o), report.getFirst().get("observation"));
			assertEquals("dealer_confirmed_receipt_of_the_payment", report.getFirst().get("type"));
			e.reread(obsId(o), proposal().entity("e1", "Nordvik Marin", "organization")
					.event("ev1", "confirmed_payment", "2026-09-11", "e1", "self").build());
			assertEquals(List.of(), e.consolidate(true).descriptiveEvents());
		}
	}
}
