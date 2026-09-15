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
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactQueries.History;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section D: correction and forgetting, with history. */
class CorrectionTest {

	private static long id(String ref) {
		return Long.parseLong(ref.substring(ref.indexOf('-') + 1));
	}

	@Test
	@Scenario("D1")
	void correctPreservesHistory() {
		try (Engine e = engine("d1")) {
			RememberOutcome o = remember(e, "I live in Zürich.",
					proposal().entity("e1", "Zürich", "place").fact("self", "lives_in", "e1"));
			long original = id(o.applied().facts().getFirst().id());
			Corrected c = e.correct(original, Map.of("object", "Schübelbach"), "wrong town");
			assertEquals("corrected", c.original().status());
			assertEquals("current", c.replacement().status());
			assertEquals(c.replacement().id(), c.original().supersededBy());
			assertEquals("Mattias Sandell lives in Schübelbach", c.replacement().rendering());
			assertEquals("explicit", c.replacement().derivationKind());
			var record = e.facts().supersessionsOf(original).getFirst();
			assertEquals("correction", record.kind());
			assertEquals("wrong town", record.reason());
			assertNotNull(record.observationId(), "the correction is itself an observation");
			assertEquals("correction", e.observations().get(record.observationId()).orElseThrow().source().kind());
			assertEquals(2, e.observations().count(), "the original observation is still stored");

			RecallResult r = recall(e, "where does Mattias live");
			assertEquals(1, r.structured().facts().size(), r.text());
			assertEquals(c.replacement().id(), r.structured().facts().getFirst().id());

			History h = e.history(e.entities().owner().id(), "lives_in");
			assertEquals(2, h.entries().size());
			assertEquals(original, h.entries().get(0).fact().id());
			assertEquals("corrected", h.entries().get(0).fact().status());
			assertEquals("correction", h.entries().get(0).supersessions().getFirst().kind());
			assertEquals(o.observation().observationId(), h.entries().get(0).fact().observationId(),
					"the original observation is reachable from history");
			assertEquals(c.replacement().id(), h.entries().get(1).fact().id());
		}
	}

	@Test
	void correctingANonCurrentFactIsRefused() {
		try (Engine e = engine("d1b")) {
			RememberOutcome o = remember(e, "I live in Zürich.", proposal().fact("lives_in", "Zürich"));
			long original = id(o.applied().facts().getFirst().id());
			e.correct(original, Map.of("object", "Bern"), "wrong");
			MnemicException ex = assertThrows(MnemicException.class,
					() -> e.correct(original, Map.of("object", "Basel"), "again"));
			assertEquals(MnemicException.Code.CONFLICT, ex.code());
			assertTrue(ex.getMessage().contains("f-"), "names the current fact: " + ex.getMessage());
		}
	}

	@Test
	void correctingValidTimeAndEnded() {
		try (Engine e = engine("d1c")) {
			RememberOutcome o = remember(e, "I joined Hooli in 2017.",
					proposal().entity("e1", "Hooli", "organization")
							.fact(se.hirt.mnemic.TestHomes.fact("self", "works_at", "e1", null, null, "2017", null,
									null, null, null)));
			long original = id(o.applied().facts().getFirst().id());
			Corrected c = e.correct(original, Map.of("valid_time", Map.of("start", "2018")), "it was 2018");
			assertEquals("2018-01-01", c.replacement().validStart());
			assertTrue(c.replacement().rendering().endsWith("(since 2018)"), c.replacement().rendering());
			Corrected d = e.correct(c.replacement().id(), Map.of("ended", true), "I left");
			assertTrue(d.replacement().ended());
			assertTrue(d.replacement().rendering().contains("ended"), d.replacement().rendering());
		}
	}

	/** D2 with facts: forget removes the facts and history keeps a dated tombstone with no content. */
	@Test
	@Scenario("D5")
	void aSupersededFactCanBeCorrected() {
		try (Engine e = engine("d5-superseded")) {
			RememberOutcome initrode = remember(e, "I worked at Initrode from 2010.",
					proposal().entity("e1", "Initrode", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "2010", null, null, null, null)));
			remember(e, "I joined Hooli in 2018.", proposal().entity("e2", "Hooli", "organization")
					.event("ev1", "joined", "2018", "self", "e2"));
			Fact closed = e.facts().get(Long.parseLong(initrode.applied().facts().getFirst().id().substring(2)))
					.orElseThrow();
			assertEquals("superseded", closed.status());
			assertEquals("2018-01-01", closed.validEnd());
			// The event's date was wrong for this job: it really ended in 2016. History is corrected, not refused.
			Corrected c = e.correct(closed.id(), Map.of("valid_time", Map.of("start", "2010", "end", "2016")), "left earlier");
			Fact fixed = e.facts().get(c.replacement().id()).orElseThrow();
			assertEquals("2016-01-01", fixed.validEnd());
			assertEquals("current", fixed.status(), "history, accepted");
			assertEquals("ended", fixed.state(java.time.Instant.now()));
			assertEquals("corrected", e.facts().get(closed.id()).orElseThrow().status());
			// A corrected fact stays refused.
			assertThrows(MnemicException.class,
					() -> e.correct(closed.id(), Map.of("valid_time", Map.of("start", "2010")), "again"));
		}
	}

	@Test
	@Scenario("D2")
	void forgetLeavesATombstoneInHistory() {
		try (Engine e = engine("d2-facts")) {
			RememberOutcome o = remember(e, "My passport number is 123.",
					proposal().fact("self", "x:passport_number", "123"));
			long obs = o.observation().observationId();
			assertEquals(1, e.facts().count());
			assertTrue(e.forget(obs));
			assertEquals(0, e.facts().count());
			History h = e.history(e.entities().owner().id(), null);
			assertTrue(h.entries().isEmpty(), "no fact content survives");
			assertEquals(1, h.tombstones().size());
			assertEquals(obs, h.tombstones().getFirst().observationId());
			assertNotNull(h.tombstones().getFirst().forgottenAt());
			assertTrue(recall(e, "passport").hits().isEmpty());
		}
	}

	@Test
	void historyShowsEventSupersession() {
		try (Engine e = engine("hist")) {
			remember(e, "I worked at Initrode from 2010.", proposal().entity("e1", "Initrode", "organization")
					.fact(se.hirt.mnemic.TestHomes.fact("self", "works_at", "e1", null, null, "2010", null, null, null,
							null)));
			remember(e, "I joined Hooli in 2018.",
					proposal().entity("e2", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e2")
							.fact(se.hirt.mnemic.TestHomes.fact("self", "works_at", "e2", null, null, null, null, null,
									java.util.List.of("ev1"), null)));
			History h = e.history(e.entities().owner().id(), "works_at");
			assertEquals(2, h.entries().size());
			Fact initrode = h.entries().get(0).fact();
			assertEquals("superseded", initrode.status());
			assertEquals("event", h.entries().get(0).supersessions().getFirst().kind());
			assertEquals("2018-01-01", h.entries().get(0).supersessions().getFirst().closedAt());
			assertTrue(h.entries().get(1).supersessions().isEmpty());
		}
	}
	/** D7: a fact that was never true is retracted: no replacement, out of recall, in history with its reason. */
	@Test
	@Scenario("D7")
	void aFactThatWasNeverTrueCanBeRetracted() {
		try (Engine e = engine("d7-retract")) {
			remember(e, "I decided to replace the BCN3D Sigma, leaning toward the Bambu Lab H2D.",
					proposal().fact("self", "decided", "replace the BCN3D Sigma"));
			long owner = e.entities().owner().id();
			Fact f = e.facts().factsOf(owner).stream().filter(x -> "decided".equals(x.predicate())).findFirst().orElseThrow();
			var c = e.correct(f.id(), Map.of("wrong", true), "that was a leaning, never a decision");
			assertNull(c.replacement(), "a retraction stores no replacement");
			assertEquals("corrected", c.original().status());
			// Out of recall, structured and otherwise.
			RecallResult r = recall(e, "what did Mattias decide");
			assertFalse(r.structured().matched(), r.text());
			assertTrue(r.hits().stream().allMatch(h -> h.facts().stream().noneMatch(x -> x.id() == f.id())), r.text());
			// In history, with the reason.
			var s = e.facts().supersessionsOf(f.id());
			assertEquals(1, s.size());
			assertEquals("retraction", s.getFirst().kind());
			assertTrue(s.getFirst().reason().contains("never a decision"), s.getFirst().reason());
			assertNull(s.getFirst().supersededById());
			// The correction record does not join the backlog.
			assertEquals(0, e.observations().pendingProposals());
		}
	}

	/** D8: an observation recorded wrongly is retired, not forgotten: out of recall, flagged in history, its text kept. */
	@Test
	@Scenario("D8")
	void aWrongObservationIsRetiredNotForgotten() {
		try (Engine e = engine("d8-retire")) {
			long wrong = e.remember("Dad is best reached on Slack these days.", se.hirt.mnemic.observation.Source.user(), null, null, null, null)
					.observation().observationId();
			long right = e.remember("Correction: Dad is on WhatsApp, not Slack; he never used Slack.", se.hirt.mnemic.observation.Source.user(),
					null, null, null, null).observation().observationId();
			assertEquals(2, e.observations().pendingProposals());
			var retired = e.retireObservation(wrong, "said Slack; the later note says WhatsApp", right);
			assertTrue(retired.retired());
			assertEquals(Long.valueOf(right), retired.supersededBy());
			assertEquals(1, e.observations().pendingProposals(), "it left the backlog");
			assertEquals("Dad is best reached on Slack these days.", e.observations().get(wrong).orElseThrow().text(), "the text stays");
			// Recall does not show it; recall with history does, flagged.
			RecallResult r = recall(e, "how do I reach Dad");
			assertTrue(r.hits().stream().noneMatch(h -> h.observation().id() == wrong), r.text());
			assertTrue(r.hits().stream().anyMatch(h -> h.observation().id() == right), r.text());
			RecallResult h = e.recall().recall("how do I reach Dad", null, 800, 10, true);
			var shown = h.hits().stream().filter(x -> x.observation().id() == wrong).findFirst();
			assertTrue(shown.isPresent(), h.text());
			assertTrue(shown.get().shown().startsWith("[retired: said Slack; the later note says WhatsApp; superseded by obs-" + right + "]"), shown.get().shown());
			// Once retired, retiring again is refused; forgetting still works, as ever.
			assertThrows(se.hirt.mnemic.protocol.MnemicException.class, () -> e.retireObservation(wrong, "again", null));
			assertEquals(1, e.observations().retiredCount());
			// A retirement can be undone: the note is live again and, never having had a reading, back in the backlog.
			var back = e.reinstateObservation(wrong);
			assertFalse(back.retired());
			assertEquals(0, e.observations().retiredCount());
			assertEquals(2, e.observations().pendingProposals());
			assertTrue(recall(e, "how do I reach Dad").hits().stream().anyMatch(x -> x.observation().id() == wrong));
			assertThrows(se.hirt.mnemic.protocol.MnemicException.class, () -> e.reinstateObservation(wrong));
		}
	}
}
