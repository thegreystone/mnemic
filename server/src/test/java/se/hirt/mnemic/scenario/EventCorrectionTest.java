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
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * An event's date is corrected on the event ({@code correct(evt-N, {valid_time})}), and the facts its effects opened or
 * closed move with it: "the wedding is a week later" is one correction. An assistant tried to move a rehearsal dinner
 * through its event type and was refused (2026-09-23).
 */
class EventCorrectionTest {

	private static long eventId(RememberOutcome o) {
		return Long.parseLong(o.applied().events().getFirst().id().substring(4));
	}

	private static Fact fact(Engine e, RememberOutcome o, int i) {
		return e.facts().get(Long.parseLong(o.applied().facts().get(i).id().substring(2))).orElseThrow();
	}

	@Test
	void movingAnEventMovesTheFactItOpened() {
		try (Engine e = engine("evt-redate-opened")) {
			// "moved" opens lives_in from the event's date: the fact's start comes from the event.
			RememberOutcome o = remember(e, "I moved to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "moved", "2024", "self", "e1"));
			long evt = eventId(o);
			Fact home = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "lives_in".equals(f.predicate())).findFirst().orElseThrow();
			assertEquals("2024", home.validStart().substring(0, 4));
			assertEquals(evt, home.eventId(), "opened by the event");

			Map<String, Object> out = e.correctEvent(evt, Map.of("valid_time", Map.of("start", "2023-06")),
					"it was June the year before");
			assertEquals("evt-" + evt, out.get("event"));
			assertTrue(out.get("facts_moved").toString().contains("f-" + home.id()), out.toString());
			Event moved = e.events().get(evt).orElseThrow();
			assertTrue(moved.validStart().startsWith("2023-06"), moved.validStart());
			assertTrue(moved.rendering().contains("2023-06"), moved.rendering());
			Fact after = e.facts().get(home.id()).orElseThrow();
			assertTrue(after.validStart().startsWith("2023-06"), after.validStart());
			assertTrue(after.rendering().contains("2023-06"), "re-rendered: " + after.rendering());
			// A fact stated beside the event with a date of its own keeps that date.
			RememberOutcome own = remember(e, "I moved to Zug in 2020 and have worked at Hooli since 2018.",
					proposal().entity("e2", "Zug", "place").entity("e3", "Hooli", "organization")
							.event("ev1", "moved", "2020", "self", "e2").fact(se.hirt.mnemic.TestHomes.fact("self",
									"works_at", "e3", null, null, "2018", null, null, null, null)));
			long zug = eventId(own);
			e.correctEvent(zug, Map.of("valid_time", Map.of("start", "2021")), "a year later");
			Fact job = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "works_at".equals(f.predicate())).findFirst().orElseThrow();
			assertTrue(job.validStart().startsWith("2018"), "its own date stays: " + job.validStart());
			// The correction is on record as an observation that dated the event.
			assertTrue(out.get("observation").toString().startsWith("obs-"));
			assertTrue(e.events().observationsOf(evt).size() >= 2, "the correction became a source");
			// And recall as of the old date sees the move.
			RecallResult r = recall(e, "where did I live in 2023-09", Instant.parse("2023-09-01T00:00:00Z"));
			assertTrue(r.text().contains("Willisau"), r.text());
		}
	}

	@Test
	void movingAnEventMovesTheFactItClosed() {
		try (Engine e = engine("evt-redate-closed")) {
			remember(e, "I have lived in Lund since 2010.",
					proposal().entity("e1", "Lund", "place").fact(se.hirt.mnemic.TestHomes.fact("self", "lives_in",
							"e1", null, null, "2010", null, null, null, null)));
			RememberOutcome o = remember(e, "I moved to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "moved", "2024", "self", "e1"));
			long evt = eventId(o);
			Fact lund = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> f.rendering().contains("Lund")).findFirst().orElseThrow();
			assertTrue(lund.validEnd() != null && lund.validEnd().startsWith("2024"), "closed by the move: " + lund);

			e.correctEvent(evt, Map.of("valid_time", Map.of("start", "2023")), "a year earlier");
			Fact after = e.facts().get(lund.id()).orElseThrow();
			assertTrue(after.validEnd().startsWith("2023"), "the end moved too: " + after.validEnd());
			assertTrue(after.rendering().contains("2023"), after.rendering());
			assertTrue(e.facts().supersessionsOf(lund.id()).stream()
					.anyMatch(s -> s.closedAt() != null && s.closedAt().startsWith("2023")), "and the ledger");
		}
	}

	@Test
	void theDateAndTheTypeAreCorrectableOneAtATime() {
		try (Engine e = engine("evt-redate-refused")) {
			RememberOutcome o = remember(e, "I moved to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "moved", "2024", "self", "e1"));
			long evt = eventId(o);
			MnemicException participants = assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("participants", List.of("self")), "wrong people"));
			assertTrue(participants.getMessage().contains("valid_time"), participants.getMessage());
			assertTrue(participants.getMessage().contains("type"), participants.getMessage());
			assertTrue(participants.getMessage().contains("remember(observation_id, proposal)"),
					participants.getMessage());
			MnemicException both = assertThrows(MnemicException.class, () -> e.correctEvent(evt,
					Map.of("type", "relocated", "valid_time", Map.of("start", "2023")), "two at once"));
			assertTrue(both.getMessage().contains("one at a time"), both.getMessage());
			MnemicException same = assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("valid_time", Map.of("start", "2024")), "same"));
			assertTrue(same.getMessage().contains("changes nothing"), same.getMessage());
			assertThrows(MnemicException.class,
					() -> e.correctEvent(999, Map.of("valid_time", Map.of("start", "2024")), "no such event"));
			assertEquals(1, e.observations().all().size(), "a refused correction leaves no record behind");
		}
	}

	@Test
	void aSentenceWhereTheTypeGoesBecomesATypeInOneCall() {
		try (Engine e = engine("evt-retype")) {
			// An early reading put a sentence where the type goes: a plain occurrence, listed by consolidate.
			RememberOutcome o = remember(e, "I earned my M.Sc. in computer science at KTH in 1999.",
					proposal().entity("k", "KTH", "organization").event("ev1", "earned_m_sc_in_computer_science",
							"1999", "self", "k"));
			long evt = eventId(o);
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("reads as a description")),
					o.applied().warnings().toString());
			assertEquals(1, e.consolidate(true).descriptiveEvents().size());
			assertTrue(
					e.findings(e.consolidate(true)).stream()
							.anyMatch(f -> f.contains("correct('evt-" + evt + "', {type: '...'})")),
					e.findings(e.consolidate(true)).toString());
			// A sentence again is refused; a type of a word or two is taken, registered from this use, and the
			// event re-rendered.
			MnemicException sentence = assertThrows(MnemicException.class, () -> e.correctEvent(evt,
					Map.of("type", "got the degree after five years of study"), "still a sentence"));
			assertTrue(sentence.getMessage().contains("reads as a description"), sentence.getMessage());
			Map<String, Object> out = e.correctEvent(evt, Map.of("type", "graduated"), "a type, not a sentence");
			assertEquals("evt-" + evt, out.get("event"));
			@SuppressWarnings("unchecked")
			Map<String, Object> before = (Map<String, Object>) out.get("before");
			@SuppressWarnings("unchecked")
			Map<String, Object> after = (Map<String, Object>) out.get("after");
			assertEquals("earned_m_sc_in_computer_science", before.get("type"));
			assertEquals("graduated", after.get("type"));
			assertTrue(String.valueOf(after.get("rendering")).contains("graduated")
					&& String.valueOf(after.get("rendering")).contains("1999"), after.toString());
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> defs = (List<Map<String, Object>>) out.get("definitions");
			assertEquals("graduated", defs.getFirst().get("name"), out.toString());
			assertEquals("inferred", defs.getFirst().get("resolution"));
			Event again = e.events().get(evt).orElseThrow();
			assertEquals("graduated", again.type());
			assertEquals(List.of(), e.consolidate(true).descriptiveEvents(), "nothing left to list");
			assertTrue(e.eventTypes().get("graduated").orElseThrow().inferred(), "registered from use");
			// The same type again changes nothing and leaves no record; a known type needs no definition.
			MnemicException same = assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("type", "graduated"), "same"));
			assertTrue(same.getMessage().contains("changes nothing"), same.getMessage());
			Map<String, Object> known = e.correctEvent(evt, Map.of("type", "joined"), "a seed type");
			assertFalse(known.containsKey("definitions"), known.toString());
			assertTrue(String.valueOf(known.get("note_opens")).contains("works_at") || known.get("note_opens") == null,
					known.toString());
			assertEquals(3, e.observations().all().size(), "two correction records beside the observation");
		}
	}

	@Test
	void aRebuildRetypesTheEventAgain() {
		try (Engine e = engine("evt-retype-rebuild")) {
			RememberOutcome o = remember(e, "I earned my M.Sc. at KTH in 1999.", proposal()
					.entity("k", "KTH", "organization").event("ev1", "earned_m_sc_at_kth", "1999", "self", "k"));
			long evt = eventId(o);
			e.correctEvent(evt, Map.of("type", "graduated"), "a type, not a sentence");
			Engine.Rebuilt rebuilt = e.rebuild();
			assertEquals(1, rebuilt.corrections(), rebuilt.toString());
			assertEquals(List.of(), rebuilt.unmatched(), rebuilt.toString());
			assertEquals(1, e.events().ofType("graduated").size(), "the correction replayed");
			assertEquals(0, e.events().ofType("earned_m_sc_at_kth").size());
		}
	}

	@Test
	void aBooleanOnAnEventTypeListFieldIsRefusedWithTheShape() {
		try (Engine e = engine("evt-type-list-shape")) {
			remember(e, "The rehearsal dinner is on 11 June 2027.", proposal().entity("e1", "Anna Lindqvist", "person")
					.event("ev1", "rehearsal dinner", "2027-06-11", "self", "e1"));
			// What an assistant sent to move a dinner: the refusal says what the field takes and where to move a date.
			MnemicException refused = assertThrows(MnemicException.class,
					() -> e.correctEventType("rehearsal_dinner", Map.of("supersedes", true), "later date replaces"));
			assertTrue(refused.getMessage().contains("takes a list of predicate names"), refused.getMessage());
			assertTrue(refused.getMessage().contains("correct(evt-N, {valid_time"), refused.getMessage());
		}
	}

	@Test
	void aRebuildMovesTheEventAgain() {
		try (Engine e = engine("evt-redate-rebuild")) {
			RememberOutcome o = remember(e, "I moved to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "moved", "2024", "self", "e1"));
			long evt = eventId(o);
			e.correctEvent(evt, Map.of("valid_time", Map.of("start", "2023-06")), "June the year before");
			Engine.Rebuilt rebuilt = e.rebuild();
			assertEquals(1, rebuilt.corrections(), rebuilt.toString());
			assertEquals(List.of(), rebuilt.unmatched(), rebuilt.toString());
			Event again = e.events().ofType("moved").getFirst();
			assertTrue(again.validStart().startsWith("2023-06"), "the correction replayed: " + again.validStart());
			Fact home = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "lives_in".equals(f.predicate()) && f.current()).findFirst().orElseThrow();
			assertTrue(home.validStart().startsWith("2023-06"), home.toString());
			assertFalse(home.rendering().contains("2024"), home.rendering());
		}
	}
}
