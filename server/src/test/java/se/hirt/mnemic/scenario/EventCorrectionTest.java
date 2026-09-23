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
	void onlyTheDateIsCorrectable() {
		try (Engine e = engine("evt-redate-refused")) {
			RememberOutcome o = remember(e, "I moved to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "moved", "2024", "self", "e1"));
			long evt = eventId(o);
			MnemicException type = assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("type", "relocated"), "wrong word"));
			assertTrue(type.getMessage().contains("valid_time"), type.getMessage());
			assertTrue(type.getMessage().contains("remember(observation_id, proposal)"), type.getMessage());
			MnemicException same = assertThrows(MnemicException.class,
					() -> e.correctEvent(evt, Map.of("valid_time", Map.of("start", "2024")), "same"));
			assertTrue(same.getMessage().contains("changes nothing"), same.getMessage());
			assertThrows(MnemicException.class,
					() -> e.correctEvent(999, Map.of("valid_time", Map.of("start", "2024")), "no such event"));
			assertEquals(1, e.observations().all().size(), "a refused correction leaves no record behind");
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
