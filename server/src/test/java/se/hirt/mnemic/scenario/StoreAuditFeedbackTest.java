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
import se.hirt.mnemic.Engine.Consolidation;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
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
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * An audit of a real store, walking every observation, fact, entity, and event with its owner (2026-09-28), found
 * these: a restatement as ended only corroborated the open fact; a second insurance policy was folded into the first
 * and its wording lost; a correction to a value was refused as no change; a correction whose new object resembled the
 * old one asked a question it then withdrew; a merge left duplicate facts to the next consolidate; consolidate's retire
 * said nothing about what it skipped; retyping a sentence-typed event lost the sentence; and phrases became entities of
 * unknown type.
 */
class StoreAuditFeedbackTest {

	private static Fact stored(Engine e, RememberOutcome o) {
		return e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
	}

	private static long insert(Engine e, String sql, Object ... args) {
		return e.database().write(tx -> tx.insert(sql, args));
	}

	private static void update(Engine e, String sql, Object ... args) {
		e.database().write(tx -> tx.update(sql, args));
	}

	@Test
	void aRestatementAsEndedEndsTheOpenFact() {
		try (Engine e = engine("audit-ended")) {
			Fact led = stored(e, remember(e, "I lead Kestrel.",
					proposal().entity("e1", "Kestrel", "project").fact("self", "leads", "e1")));
			assertFalse(led.ended());
			RememberOutcome o = remember(e, "Kestrel is no longer an active project.",
					proposal().entity("e1", "Kestrel", "project")
							.fact(fact("self", "leads", "e1", null, null, null, null, true, List.of(), null)));
			assertEquals(led.ref(), o.applied().facts().getFirst().id(), "the same row, not a new one");
			Fact after = e.facts().get(led.id()).orElseThrow();
			assertTrue(after.ended(), "ended by the restatement: " + after);
			assertTrue(after.rendering().contains("ended"), after.rendering());
			// Said again as ended: a corroboration of the ended row, nothing more.
			remember(e, "I don't lead Kestrel any more.", proposal().entity("e1", "Kestrel", "project")
					.fact(fact("self", "leads", "e1", null, null, null, null, true, List.of(), null)));
			assertEquals(1, e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "leads".equals(f.predicate()) && f.current()).count());
		}
	}

	@Test
	void aRestatementWithAnEndDateDatesTheEnd() {
		try (Engine e = engine("audit-ended-dated")) {
			Fact led = stored(e, remember(e, "I lead Kestrel.",
					proposal().entity("e1", "Kestrel", "project").fact("self", "leads", "e1")));
			remember(e, "I stopped leading Kestrel in March 2025.", proposal().entity("e1", "Kestrel", "project")
					.fact(fact("self", "leads", "e1", null, null, null, "2025-03", true, List.of(), null)));
			Fact after = e.facts().get(led.id()).orElseThrow();
			assertTrue(after.ended());
			assertTrue(after.validEnd() != null && after.validEnd().startsWith("2025-03"), after.toString());
		}
	}

	@Test
	void differentlyWordedQualifiersThatSayDifferentThingsAreTwoFacts() {
		try (Engine e = engine("audit-qualifiers")) {
			Fact household = stored(e,
					remember(e, "My household insurance is with Zurich.",
							proposal().entity("e1", "Zurich Insurance", "organization")
									.fact(fact("self", "uses", "e1", "household insurance (no household casco cover)",
											null, null, null, null, List.of(), null))));
			RememberOutcome motor = remember(e, "I signed Zurich for my car.",
					proposal().entity("e1", "Zurich Insurance", "organization").fact(fact("self", "uses", "e1",
							"motor insurance for the car (signed)", null, null, null, null, List.of(), null)));
			assertNotEquals(household.ref(), motor.applied().facts().getFirst().id(),
					"a second policy, not a restatement");
			assertFalse(motor.applied().facts().getFirst().corroborated());
			assertTrue(motor.applied().facts().getFirst().rendering().contains("motor insurance"),
					motor.applied().facts().toString());
			assertTrue(e.facts().get(household.id()).orElseThrow().rendering().contains("household insurance"));
			// The same policy worded again folds into it, as before.
			RememberOutcome again = remember(e, "The household cover is Zurich's.", proposal()
					.entity("e1", "Zurich Insurance", "organization")
					.fact(fact("self", "uses", "e1", "household insurance", null, null, null, null, List.of(), null)));
			assertEquals(household.ref(), again.applied().facts().getFirst().id());
			assertTrue(again.applied().facts().getFirst().corroborated());
			// Consolidate folds by the same rule: the two policies stay two.
			Consolidation c = e.consolidate(false, List.of());
			assertTrue(c.duplicates().isEmpty(), c.duplicates().toString());
		}
	}

	@Test
	void aValueStoredAsAThingIsCorrectedToTheValueAndRepairedByConsolidate() {
		try (Engine e = engine("audit-literal")) {
			Fact bo = stored(e, remember(e, "Bo is male.",
					proposal().entity("e1", "Bo Berg", "person").fact("e1", "gender", "male")));
			Fact eve = stored(e, remember(e, "Eve is female.",
					proposal().entity("e1", "Eve Berg", "person").fact("e1", "gender", "female")));
			String now = Instant.now().toString();
			long male = insert(e, "INSERT INTO entity(name, type, created_at) VALUES ('male', 'unknown', ?)", now);
			long female = insert(e, "INSERT INTO entity(name, type, created_at) VALUES ('female', 'unknown', ?)", now);
			update(e, "UPDATE fact SET object_id = ?, object_text = NULL WHERE id = ?", male, bo.id());
			update(e, "UPDATE fact SET object_id = ?, object_text = NULL WHERE id = ?", female, eve.id());
			// Named the same, and yet a change: from the entity to the value.
			var c = e.correct(bo.id(), Map.of("object", "male"), "gender takes a value");
			assertNull(c.replacement().objectId(), c.replacement().toString());
			assertEquals("male", c.replacement().objectText());
			// The other is repaired by consolidate, and the entity nothing names any more goes.
			Consolidation out = e.consolidate(false, List.of());
			assertTrue(out.repairs().stream().anyMatch(m -> ("f-" + eve.id()).equals(m.get("fact"))),
					out.repairs().toString());
			Fact repaired = e.facts().get(eve.id()).orElseThrow();
			assertNull(repaired.objectId());
			assertEquals("female", repaired.objectText());
			assertEquals("Eve Berg is female", repaired.rendering());
			assertTrue(e.entities().get(female).isEmpty(), "unnamed now, removed");
		}
	}

	@Test
	void aCorrectedObjectIsNeverAskedAboutAsTheObjectItReplaces() {
		try (Engine e = engine("audit-correct-object")) {
			Fact uses = stored(e, remember(e, "I train on a Tacx Neo trainer.",
					proposal().entity("e1", "Tacx Neo trainer", "thing").fact("self", "uses", "e1")));
			var c = e.correct(uses.id(), Map.of("object", "Tacx Neo 2T trainer"), "the model is the Neo 2T");
			assertNotEquals(uses.objectId(), c.replacement().objectId(), "a new thing, not the one replaced");
			assertEquals("Tacx Neo 2T trainer", e.entities().nameOf(c.replacement().objectId()));
			// An entity named by its id is that entity, as is.
			Entity other = e.entities().byRef("Tacx Neo trainer").orElseThrow();
			var back = e.correct(c.replacement().id(), Map.of("object", other.ref()), "it was the first one after all");
			assertEquals(other.id(), back.replacement().objectId());
		}
	}

	@Test
	void aDescriptionIsKeptAsTextAndOldPhraseEntitiesAreRepaired() {
		try (Engine e = engine("audit-descriptions")) {
			Fact pref = stored(e, remember(e, "Challenge me with contrary evidence rather than agreeing.", proposal()
					.fact("self", "prefers", "being challenged with contrary evidence rather than agreement")));
			assertNull(pref.objectId(), "a description, not a thing");
			assertEquals("being challenged with contrary evidence rather than agreement", pref.objectText());
			assertTrue(e.entities().byRef("being challenged with contrary evidence rather than agreement").isEmpty());
			// A name stays a thing.
			Fact car = stored(e,
					remember(e, "I have a Toyota Sienna.", proposal().fact("self", "uses", "Toyota Sienna")));
			assertTrue(car.objectId() != null, car.toString());
			// A phrase an earlier reading made an entity is repaired: the facts keep it as text, the entity goes.
			String now = Instant.now().toString();
			long phrase = insert(e, "INSERT INTO entity(name, type, created_at) VALUES (?, 'unknown', ?)",
					"routing tax questions to the lawyer rather than to his colleague", now);
			Fact routed = stored(e, remember(e, "Tax questions go to the lawyer.",
					proposal().fact("self", "prefers", "the lawyer for tax questions")));
			update(e, "UPDATE fact SET object_id = ?, object_text = NULL WHERE id = ?", phrase, routed.id());
			Consolidation dry = e.consolidate(true, List.of());
			assertTrue(dry.repairs().stream().anyMatch(m -> m.containsKey("would_repair")), dry.repairs().toString());
			assertTrue(e.entities().get(phrase).isPresent(), "a dry run changes nothing");
			e.consolidate(false, List.of());
			Fact after = e.facts().get(routed.id()).orElseThrow();
			assertNull(after.objectId());
			assertEquals("routing tax questions to the lawyer rather than to his colleague", after.objectText());
			assertTrue(e.entities().get(phrase).isEmpty());
			assertTrue(e.entities().get(car.objectId()).isPresent(), "a named thing is never repaired away");
		}
	}

	@Test
	void aMergeFoldsTheFactsItMadeTheSame() {
		try (Engine e = engine("audit-merge-fold")) {
			Fact old = stored(e, remember(e, "I lead Engram.",
					proposal().entity("e1", "Engram", "project").fact("self", "leads", "e1")));
			Fact now = stored(e, remember(e, "I lead mnemic.",
					proposal().entity("e1", "mnemic", "project").fact("self", "leads", "e1")));
			Map<String, Object> out = e.correctEntity(old.objectId(), Map.of("merge_into", "ent-" + now.objectId()),
					"Engram was renamed mnemic");
			assertTrue(out.containsKey("folded"), out.toString());
			assertEquals(1, e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "leads".equals(f.predicate()) && f.current()).count());
		}
	}

	@Test
	void consolidateSaysWhatItDidNotRetire() {
		try (Engine e = engine("audit-retire")) {
			var note = remember(e, "A note with nothing to extract.");
			Consolidation first = e.consolidate(false, List.of(note.observationId()));
			assertEquals(List.of("obs-" + note.observationId()), first.retired());
			Consolidation second = e.consolidate(false, List.of(note.observationId()));
			assertTrue(second.retired().isEmpty());
			assertEquals(1, second.notRetired().size(), second.notRetired().toString());
			assertTrue(String.valueOf(second.notRetired().getFirst().get("why")).contains("\"retired\": true"),
					second.notRetired().toString());
		}
	}

	@Test
	void retypingASentenceKeepsItAsTheEventsDetail() {
		try (Engine e = engine("audit-event-detail")) {
			RememberOutcome o = remember(e, "In 1997 I took 2nd place in the Q-arne-val melody festival.",
					proposal().event("ev1", "took_2nd_place_in_the_melody_festival", "1997", "self"));
			long evt = Long.parseLong(o.applied().events().getFirst().id().substring(4));
			Map<String, Object> out = e.correctEvent(evt, Map.of("type", "competed"), "a type of one word");
			Event ev = e.events().get(evt).orElseThrow();
			assertEquals("competed", ev.type());
			assertEquals("took 2nd place in the melody festival", ev.detail(), out.toString());
			assertTrue(ev.rendering().contains("(took 2nd place in the melody festival)"), ev.rendering());
			// The detail is set on its own, and survives a re-dating.
			e.correctEvent(evt, Map.of("detail", "2nd place, KTH Q-arne-val"), "the festival's name");
			e.correctEvent(evt, Map.of("valid_time", Map.of("start", "1997-05")), "in May");
			Event after = e.events().get(evt).orElseThrow();
			assertEquals("2nd place, KTH Q-arne-val", after.detail());
			assertTrue(after.rendering().contains("(2nd place, KTH Q-arne-val)"), after.rendering());
			assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("detail", "2nd place, KTH Q-arne-val"), "again"));
			// A rebuild replays the retyping and the detail.
			e.consolidate(false, List.of(), true);
			long competed = e.database().read(tx -> tx.queryLong("SELECT id FROM event WHERE type = 'competed'"));
			Event rebuilt = e.events().get(competed).orElseThrow();
			assertEquals("2nd place, KTH Q-arne-val", rebuilt.detail(), rebuilt.toString());
		}
	}

	@Test
	void eventsRetypedBeforeTheyKeptADetailGetItBack() {
		try (Engine e = engine("audit-event-backfill")) {
			RememberOutcome o = remember(e, "I stopped practicing martial arts in 2005.",
					proposal().event("ev1", "stopped_practicing_martial_arts", "2005", "self"));
			long evt = Long.parseLong(o.applied().events().getFirst().id().substring(4));
			e.correctEvent(evt, Map.of("type", "quit"), "a type of one word");
			update(e, "UPDATE event SET detail = NULL WHERE id = ?", evt); // as a store from before V036 has it
			assertEquals(1, e.events().backfillRetypedDetails());
			assertEquals("stopped practicing martial arts", e.events().get(evt).orElseThrow().detail());
			assertEquals(0, e.events().backfillRetypedDetails(), "idempotent");
		}
	}

	@Test
	void aCarIsKnownToBeACarAndAMotorbikeAMotorcycle() {
		try (Engine e = engine("audit-vehicles")) {
			remember(e, "I own a Zenit 4 and a Yamaha FZ6-S.", proposal().entity("c", "Zenit 4", "car")
					.entity("m", "Yamaha FZ6-S", "motorbike").fact("self", "owns", "c").fact("self", "owns", "m"));
			assertEquals("car", e.entities().byRef("Zenit 4").orElseThrow().type());
			assertEquals("motorcycle", e.entities().byRef("Yamaha FZ6-S").orElseThrow().type(), "a synonym");
			assertTrue(e.entityTypes().isA("car", "vehicle") && e.entityTypes().isA("motorcycle", "vehicle"));
			assertTrue(e.entityTypes().isA("car", "thing"));
			// The kind a question asks for narrows the answer: cars, not every vehicle.
			var cars = se.hirt.mnemic.TestHomes.recall(e, "what cars do I own");
			assertEquals("matched", cars.structured().state(), cars.text());
			assertEquals(1, cars.structured().facts().size(), cars.text());
			var vehicles = se.hirt.mnemic.TestHomes.recall(e, "what vehicles do I own");
			assertEquals(2, vehicles.structured().facts().size(), vehicles.text());
		}
	}
}
