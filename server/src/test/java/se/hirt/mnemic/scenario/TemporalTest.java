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
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section C: temporal semantics. */
class TemporalTest {

	private static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");

	private static Fact stored(Engine e, RememberOutcome o, int index) {
		return e.facts().get(Long.parseLong(o.applied().facts().get(index).id().substring(2))).orElseThrow();
	}

	@Test
	@Scenario("C1")
	void eventClosesAFunctionalFact() {
		try (Engine e = engine("c1")) {
			RememberOutcome a = remember(e, "I worked at Initrode from 2010.",
					proposal().entity("e1", "Initrode", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "2010", null, null, null, null)));
			RememberOutcome b = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e2", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e2")
							.fact(fact("self", "works_at", "e2", null, null, null, null, null, List.of("ev1"), null)));
			assertTrue(b.applied().questions().isEmpty(), "the event explains the change: " + b.applied().questions());
			Fact initrode = stored(e, a, 0);
			assertEquals("superseded", initrode.status());
			assertEquals("2010-01-01", initrode.validStart());
			assertEquals("2018-01-01", initrode.validEnd());
			assertEquals("year", initrode.validEndPrecision());
			assertEquals("event", initrode.endSource());
			Fact hooli = stored(e, b, 0);
			assertEquals(hooli.id(), initrode.supersededBy());
			assertEquals("current", hooli.status());
			assertEquals("2018-01-01", hooli.validStart(), "start taken from the event");
			assertEquals("event", hooli.startSource());
			assertEquals(1, b.applied().superseded().size());
			assertEquals("f-" + initrode.id(), b.applied().superseded().getFirst().get("fact_id"));
			var record = e.facts().supersessionsOf(initrode.id()).getFirst();
			assertEquals("event", record.kind());
			assertNotNull(record.eventId());

			RecallResult now = recall(e, "where does Mattias work");
			assertTrue(now.structured().matched(), now.text());
			assertEquals(List.of(hooli.id()), now.structured().facts().stream().map(Fact::id).toList());

			RecallResult then = recall(e, "where did Mattias work", Instant.parse("2015-06-01T00:00:00Z"));
			assertEquals(List.of(initrode.id()), then.structured().facts().stream().map(Fact::id).toList(), then.text());
		}
	}

	@Test
	@Scenario("C2")
	void contradictionWithoutAnExplainingEventRaisesAConflict() {
		try (Engine e = engine("c2")) {
			RememberOutcome a = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			assertEquals(1, b.applied().questions().size());
			var q = b.applied().questions().getFirst();
			assertEquals("conflict", q.get("kind"));
			assertEquals("works_at", q.get("predicate"));
			assertEquals("pending", b.applied().facts().getFirst().status());
			assertEquals("current", stored(e, a, 0).status(), "Hooli stays current");
			assertEquals(2, e.observations().count(), "the Acme observation is stored");
			RecallResult r = recall(e, "where does Mattias work");
			assertEquals(1, r.structured().facts().size());
			assertEquals("Mattias Sandell works at Hooli", r.structured().facts().getFirst().rendering());
			assertTrue(e.facts().supersessionsOf(stored(e, a, 0).id()).isEmpty(), "silent overwrite count: 0");
		}
	}

	@Test
	@Scenario("C4")
	void relativeTimeResolvesAgainstObservationTime() {
		try (Engine e = engine("c4")) {
			RememberOutcome o = remember(e, "I moved to Switzerland twelve years ago.", T0,
					proposal().entity("e1", "Switzerland", "place")
							.event("ev1", "moved", "twelve years ago", "self", "e1")
							.fact(fact("self", "lives_in", "e1", null, null, null, null, null, List.of("ev1"), null)));
			var ev = e.facts().event(Long.parseLong(o.applied().events().getFirst().id().substring(4))).orElseThrow();
			assertEquals("2014-01-01", ev.validStart());
			assertEquals("year", ev.validStartPrecision());
			Fact f = stored(e, o, 0);
			assertEquals("2014-01-01", f.validStart());
			assertEquals("year", f.validStartPrecision());
			assertTrue(f.rendering().endsWith("(since 2014)"), f.rendering());
		}
	}

	@Test
	@Scenario("C5")
	void precisionIsNeverFabricated() {
		try (Engine e = engine("c5")) {
			RememberOutcome vague = remember(e, "I started at Nordvik sometime in the late nineties.",
					proposal().entity("e1", "Nordvik", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "late nineties", null, null, null, null)));
			Fact f = stored(e, vague, 0);
			assertNull(f.validStart(), "no bound was invented");
			assertTrue(vague.applied().warnings().stream().anyMatch(w -> w.contains("late nineties")),
					vague.applied().warnings().toString());
		}
		try (Engine e = engine("c5b")) {
			RememberOutcome interval = remember(e, "I started at Nordvik sometime in the late nineties.",
					proposal().entity("e1", "Nordvik", "organization")
							.fact(fact("self", "works_at", "e1", null, null, "1997", "1999", null, null, null)));
			Fact f = stored(e, interval, 0);
			assertEquals("1997-01-01", f.validStart());
			assertEquals("year", f.validStartPrecision());
			assertEquals("1999-01-01", f.validEnd());
		}
	}

	@Test
	@Scenario("C7")
	void openEndedFactsAreAnnotatedByVolatilityNotDecayed() {
		Instant later = T0.plusSeconds(3L * 365 * 86_400);
		try (Engine e = engine("c7", later)) {
			remember(e, "I work at Hooli.", T0, proposal().fact("works_at", "Hooli"));
			remember(e, "I was born in Uppsala.", T0,
					proposal().entity("e1", "Uppsala", "place").fact("self", "born_in", "e1"));
			RecallResult work = recall(e, "where does Mattias work");
			assertTrue(work.structured().matched(), work.text());
			Fact f = work.structured().facts().getFirst();
			assertEquals("current", f.state(later), "still returned as current");
			assertTrue(work.text().contains("(3y ago), likely changed"), "staleness annotation present: " + work.text());
			double confidenceLater = e.facts().confidence(f);

			RecallResult born = recall(e, "where was Mattias born");
			assertTrue(born.structured().matched(), born.text());
			assertFalse(born.text().contains("likely changed") || born.text().contains("confirmed "),
					"low-volatility predicate is not flagged: " + born.text());
			assertEquals(confidenceLater, e.facts().confidence(born.structured().facts().getFirst()), 1e-9,
					"confidence never depends on the clock");
		}
	}

	@Test
	@Scenario("C8")
	void lateArrivingEventClosesAnExistingFactRetroactively() {
		try (Engine e = engine("c8")) {
			RememberOutcome hooliFact = remember(e, "I work at Hooli.", T0,
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "Back in 2016 I left Initrode to consult.", T0.plusSeconds(86_400),
					proposal().entity("e1", "Initrode", "organization").event("ev1", "left", "2016", "self", "e1"));
			RememberOutcome initrode = remember(e, "I worked at Initrode until then.", T0.plusSeconds(86_400),
					proposal().entity("e1", "Initrode", "organization")
							.fact(fact("self", "works_at", "e1", null, null, null, null, true, null, null)));
			assertTrue(initrode.applied().questions().isEmpty(), initrode.applied().questions().toString());
			Fact o = stored(e, initrode, 0);
			assertEquals("2016-01-01", o.validEnd(), "closed by the earlier 'left' event");
			assertEquals("event", o.endSource());
			assertTrue(o.ended());
			assertEquals("current", stored(e, hooliFact, 0).status(), "Hooli untouched");
			assertNull(stored(e, hooliFact, 0).validEnd());
		}
	}

	@Test
	@Scenario("C9")
	void sequentialHistoryWithUnknownBoundsDoesNotConflict() {
		try (Engine e = engine("c9")) {
			RememberOutcome o = remember(e,
					"I co-founded Nordvik Virtual Machines in 1998. I later worked in the Runtime " + "Platform Group at Initrode. I work at Hooli now.",
					proposal().entity("e1", "Nordvik Virtual Machines", "organization")
							.entity("e2", "Initrode", "organization").entity("e3", "Runtime Platform Group", "team")
							.entity("e4", "Hooli", "organization").event("ev1", "founded", "1998", "self", "e1")
							.fact(fact("self", "works_at", "e1", null, null, "1998", null, true, List.of("ev1"), null))
							.fact(fact("self", "works_at", "e2", null, null, null, null, true, null, null))
							.fact("e3", "part_of", "e2")
							.fact(fact("self", "works_at", "e4", null, null, null, null, null, null, null)));
			assertTrue(o.applied().questions().isEmpty(), "no conflict: " + o.applied().questions());
			assertEquals(4, o.applied().facts().size(), o.applied().warnings().toString());
			Fact nordvik = stored(e, o, 0);
			Fact initrode = stored(e, o, 1);
			Fact hooli = stored(e, o, 3);
			assertEquals("1998-01-01", nordvik.validStart());
			assertTrue(nordvik.ended() && nordvik.validEnd() == null);
			assertTrue(initrode.ended() && initrode.validStart() == null && initrode.validEnd() == null);
			assertEquals("current", hooli.status());
			assertFalse(hooli.ended());

			RecallResult r = recall(e, "where did Mattias work in 2005", Instant.parse("2005-01-01T00:00:00Z"));
			List<Long> ids = r.structured().facts().stream().map(Fact::id).toList();
			assertTrue(ids.contains(nordvik.id()) && ids.contains(initrode.id()), r.text());
			assertFalse(ids.contains(hooli.id()), "the latest of the sequence is not placed in 2005: " + r.text());
			assertTrue(r.text().contains("bounds: partial"), r.text());
			assertEquals(1, r.events().size(), "the founded event comes along");
			assertEquals("founded", r.events().getFirst().type());
		}
	}

	@Test
	@Scenario("C14")
	void eventsSupersedeInChronologicalOrderNotCallOrder() {
		try (Engine e = engine("c14-order")) {
			// The present job first, then the founding of a company 23 years earlier, then the company in between.
			RememberOutcome hooli = remember(e, "I joined Hooli in March 2019.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "joined", "2019-03", "self", "e1"));
			RememberOutcome hb = remember(e, "I founded Nordvik Data Consulting & Multimedia HB in 1996.",
					proposal().entity("e2", "Nordvik Data Consulting & Multimedia HB", "organization")
							.event("ev1", "founded", "1996", "self", "e2"));
			assertTrue(hb.applied().questions().isEmpty(), hb.applied().questions().toString());
			Fact hooliFact = stored(e, hooli, 0);
			assertEquals("current", hooliFact.status(), "the later job is untouched: " + hooliFact);
			assertNull(hooliFact.validEnd(), "no end 23 years before its start: " + hooliFact);
			Fact nordvik = stored(e, hb, 0);
			assertEquals("1996-01-01", nordvik.validStart());
			assertEquals("2019-03-01", nordvik.validEnd(), "ended at the nearest later job, as C1 would have: " + nordvik);
			assertEquals("sequence", nordvik.endSource());
			assertTrue(nordvik.ended());
			RememberOutcome ab = remember(e, "I founded Nordvik Software Solutions AB in September 1998.",
					proposal().entity("e3", "Nordvik Software Solutions AB", "organization")
							.event("ev1", "founded", "1998-09", "self", "e3"));
			assertTrue(ab.applied().questions().isEmpty(), ab.applied().questions().toString());
			assertEquals("2019-03-01", stored(e, ab, 0).validEnd(), "the AB ends at Hooli");
			assertEquals("current", stored(e, hooli, 0).status(), "still untouched");
			// The HB keeps its 2019 end from before the AB was known; the same sentences in chronological order
			// end it at 1998 (C1), and a correction sets it right either way (D5).
		}
		// The same three in chronological order: every interval the sequence rule would draw.
		try (Engine e = engine("c14-chrono")) {
			RememberOutcome hb = remember(e, "I founded Nordvik Data Consulting & Multimedia HB in 1996.",
					proposal().entity("e2", "Nordvik Data Consulting & Multimedia HB", "organization")
							.event("ev1", "founded", "1996", "self", "e2"));
			RememberOutcome ab = remember(e, "I founded Nordvik Software Solutions AB in September 1998.",
					proposal().entity("e3", "Nordvik Software Solutions AB", "organization")
							.event("ev1", "founded", "1998-09", "self", "e3"));
			RememberOutcome hooli = remember(e, "I joined Hooli in March 2019.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "joined", "2019-03", "self", "e1"));
			assertEquals("1998-09-01", stored(e, hb, 0).validEnd());
			assertEquals("2019-03-01", stored(e, ab, 0).validEnd());
			assertNull(stored(e, hooli, 0).validEnd());
		}
	}

	@Test
	@Scenario("C13")
	void anEventOpensTheFactsItsTypeDeclares() {
		try (Engine e = engine("c13-opens")) {
			// The event alone: the registry says purchased opens owns, so the fact is supplied, dated by the event.
			RememberOutcome bought = remember(e, "I bought Bergstrasse 7 in November 2025.",
					proposal().entity("e1", "Bergstrasse 7", "place").event("ev1", "purchased", "2025-11", "self", "e1"));
			assertEquals(1, bought.applied().facts().size(), bought.applied().toString());
			Fact owns = stored(e, bought, 0);
			assertEquals("owns", owns.predicate());
			assertEquals("2025-11-01", owns.validStart());
			assertNotNull(owns.eventId(), "linked to the event that opened it");
			assertTrue(recall(e, "what does Mattias own").structured().matched());
			// Stated alongside: one fact, not two.
			RememberOutcome joined = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e2", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e2")
							.fact(fact("self", "works_at", "e2", null, null, null, null, null, List.of("ev1"), null)));
			assertEquals(1, joined.applied().facts().size(), joined.applied().toString());
			assertEquals(1, e.facts().factsOf(e.entities().byRef("Hooli").orElseThrow().id()).size());
			// The types decide which of several opened predicates fits: joined(self, a project) cannot be
			// works_at (range organization), so it is member_of, and only that.
			RememberOutcome project = remember(e, "I joined the Profiler project in 2020.",
					proposal().entity("e3", "Profiler", "project").event("ev1", "joined", "2020", "self", "e3"));
			assertEquals(1, project.applied().facts().size(), project.applied().toString());
			assertEquals("member_of", project.applied().facts().getFirst().predicate());
			assertTrue(project.applied().questions().isEmpty(), project.applied().toString());
		}
	}

	@Test
	@Scenario("C13")
	void aFactStatedBesideItsEventByNameIsOneRowAndNoCorroboration() {
		try (Engine e = engine("c13-byname")) {
			// The stated fact names Hooli by name, not by ref: the opened twin must still find it.
			RememberOutcome o = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e2", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e2")
							.fact("self", "works_at", "Hooli"));
			assertEquals(1, o.applied().facts().size(), o.applied().toString());
			assertFalse(o.applied().facts().getFirst().corroborated(), "said once, in one breath");
			Fact f = stored(e, o, 0);
			assertEquals(1, f.corroborations());
		}
	}

	@Test
	@Scenario("C14")
	void anEarlierFactWhoseEndOverlapsTheLaterOneIsAConflict() {
		try (Engine e = engine("c14-overlap")) {
			remember(e, "I joined Hooli in March 2019.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "joined", "2019-03", "self", "e1"));
			// Stated with its own end, after the later job began: two jobs at once under a functional predicate.
			RememberOutcome ab = remember(e, "I worked at Nordvik from 1998 until 2020.",
					proposal().entity("e2", "Nordvik", "organization")
							.fact(fact("self", "works_at", "e2", null, null, "1998", "2020", null, null, null)));
			assertEquals(1, ab.applied().questions().size(), ab.applied().toString());
			assertEquals("conflict", ab.applied().questions().getFirst().get("kind"));
			assertEquals("pending", ab.applied().facts().getFirst().status());
		}
	}

	@Test
	@Scenario("C12")
	void aFactValidFromALaterDateIsNotYetSo() {
		Instant today = Instant.parse("2026-09-10T12:00:00Z");
		try (Engine e = engine("c12-future", today)) {
			remember(e, "I own the Lindenhof apartment.", today,
					proposal().entity("e0", "Lindenhof apartment", "place").fact("self", "owns", "e0"));
			remember(e, "I collect the Zenit 4 from the dealer on 17 September 2026.", today,
					proposal().entity("e1", "Zenit 4", "thing")
							.fact(fact("self", "owns", "e1", null, null, "2026-09-17", null, null, null, null)));
			Fact f = e.facts().factsOf(e.entities().byRef("Zenit 4").orElseThrow().id()).getFirst();
			assertEquals("current", f.status(), "accepted");
			assertEquals("future", f.state(today), "but not yet so");
			// Today: neither a match nor a miss, a NOT YET with the date.
			RecallResult now = recall(e, "does Mattias own the Zenit 4 yet");
			assertEquals("future", now.structured().state(), now.text());
			assertTrue(now.text().contains("NOT YET"), now.text());
			assertTrue(now.text().contains("in 7 days"), now.text());
			assertFalse(now.text().contains("matched"), now.text());
			// The list of what he owns shows it as upcoming, not as owned.
			RecallResult owns = recall(e, "what does Mattias own");
			assertTrue(owns.structured().matched(), owns.text());
			assertEquals(1, owns.structured().facts().size(), "the apartment, not the car: " + owns.text());
			assertTrue(owns.text().contains("upcoming: Mattias Sandell owns Zenit 4"), owns.text());
			// On the day, and after it, a clean yes.
			RecallResult onDay = recall(e, "does Mattias own the Zenit 4", Instant.parse("2026-09-18T12:00:00Z"));
			assertTrue(onDay.structured().matched(), onDay.text());
			// The briefing does not list a plan as a possession, and the review queue does not ask to confirm it.
			assertFalse(e.briefing(400).contains("Zenit"));
			assertTrue(e.consolidate(true).review().stream().noneMatch(m -> m.get("fact").equals(f.ref())));
		}
		// The clock moves past the date: current, with no change to the row, but on the strength of a plan.
		try (Engine e = engine("c12-after", Instant.parse("2026-09-20T12:00:00Z"))) {
			remember(e, "I collect the Zenit 4 from the dealer on 17 September 2026.", today,
					proposal().entity("e1", "Zenit 4", "thing")
							.fact(fact("self", "owns", "e1", null, null, "2026-09-17", null, null, null, null)));
			RecallResult r = recall(e, "does Mattias own the Zenit 4");
			assertTrue(r.structured().matched());
			assertTrue(r.text().contains("planned, not confirmed since it was due"), r.text());
			var review = e.consolidate(true).review();
			assertEquals(Boolean.TRUE, review.getFirst().get("due"), review.toString());
			assertEquals("2026-09-17", review.getFirst().get("planned_for"));
			// Saying it again after the date confirms it: no longer due, no annotation.
			remember(e, "I collected the Zenit 4 on the 17th.", Instant.parse("2026-09-20T12:00:00Z"),
					proposal().entity("e1", "Zenit 4", "thing")
							.fact(fact("self", "owns", "e1", null, null, "2026-09-17", null, null, null, null)));
			assertFalse(recall(e, "does Mattias own the Zenit 4").text().contains("not confirmed since"));
			assertTrue(e.consolidate(true).review().stream().noneMatch(m -> Boolean.TRUE.equals(m.get("due"))));
		}
	}

	@Test
	@Scenario("C10")
	void multipleRolesAreNotAConflict() {
		try (Engine e = engine("c10")) {
			RememberOutcome hooliFact = remember(e, "I'm Director of Engineering at Hooli.",
					proposal().entity("e1", "Hooli", "organization")
							.fact(fact("self", "holds_role", "Director of Engineering", null, "e1", null, null, null,
									null, null)));
			RememberOutcome jmc = remember(e, "I'm the project lead for OpenJDK Kestrel.",
					proposal().entity("e1", "OpenJDK Kestrel", "project")
							.fact(fact("self", "holds_role", "project lead", null, "e1", null, null, null, null,
									null)));
			assertTrue(jmc.applied().questions().isEmpty(), "different scope: " + jmc.applied().questions());
			assertEquals("current", stored(e, hooliFact, 0).status());
			assertEquals("current", stored(e, jmc, 0).status());

			RememberOutcome vp = remember(e, "I've been promoted to VP of Engineering at Hooli.",
					proposal().entity("e1", "Hooli", "organization").event("ev1", "promoted", "2024", "self", "e1")
							.fact(fact("self", "holds_role", "VP of Engineering", null, "e1", null, null, null,
									List.of("ev1"), null)));
			assertTrue(vp.applied().questions().isEmpty(), vp.applied().questions().toString());
			assertEquals("superseded", stored(e, hooliFact, 0).status(), "the Hooli role is superseded");
			assertEquals("current", stored(e, jmc, 0).status(), "the Kestrel role is untouched");
			assertEquals("current", stored(e, vp, 0).status());
		}
	}

	@Test
	@Scenario("C11")
	void deathClosesTheEntitysOpenFacts() {
		try (Engine e = engine("c11")) {
			RememberOutcome lives = remember(e, "My stepfather Bosse lives in Uppsala.",
					proposal().entity("e1", "Bosse", "person").entity("e2", "Uppsala", "place")
							.fact(fact("e1", "parent_of", "self", "stepfather", null, null, null, null, null, null))
							.fact("e1", "lives_in", "e2"));
			RememberOutcome died = remember(e, "Bosse died in 2014.",
					proposal().entity("e1", "Bosse", "person").event("ev1", "died", "2014", "e1"));
			assertEquals(2, died.applied().superseded().size(),
					"both open facts closed: " + died.applied().superseded());
			Fact livesIn = stored(e, lives, 1);
			assertTrue(livesIn.ended());
			assertEquals("2014-01-01", livesIn.validEnd());
			assertEquals("current", livesIn.status(), "closed, not replaced");
			assertEquals("ended", livesIn.state(Instant.now()));

			RecallResult r = recall(e, "where does Bosse live");
			assertEquals("miss", r.structured().state(), "nothing current: " + r.text());
			RecallResult history = e.recall().recall("where does Bosse live", null, 800, 10, true);
			assertTrue(history.structured().matched(), history.text());
			assertTrue(history.text().contains("ended"), history.text());
		}
	}

	/** Scenario family M at fact level: the time filter runs inside the probe, not on a fused top-k. */
	@Test
	@Scenario("M2")
	void asOfOnFactsIsAPreFilter() {
		try (Engine e = engine("m2")) {
			remember(e, "I worked at Initrode from 2010 to 2018.", proposal().entity("e1", "Initrode", "organization")
					.fact(fact("self", "works_at", "e1", null, null, "2010", "2018", null, null, null)));
			for (int i = 0; i < 60; i++) {
				remember(e, "Mattias prefers profiler topic " + i + ".", proposal().fact("prefers", "topic " + i));
			}
			RecallResult r = recall(e, "where did Mattias work", Instant.parse("2015-06-01T00:00:00Z"));
			assertTrue(r.structured().matched(), r.text());
			assertEquals("Initrode", e.facts().entityName(r.structured().facts().getFirst().objectId()));
			RecallResult after = recall(e, "where did Mattias work", Instant.parse("2019-06-01T00:00:00Z"));
			assertEquals("miss", after.structured().state(), "the interval ended in 2018: " + after.text());
		}
	}
}
