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
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md T25: an event cites every observation that stated it, and survives the loss of one. */
class EventSourcesTest {

	private static long said(RememberOutcome o) {
		return o.observation().observationId();
	}

	private static Fact home(Engine e, Entity lars) {
		return e.facts().factsOf(lars.id()).stream().filter(f -> "lives_in".equals(f.predicate())).findFirst()
				.orElseThrow();
	}

	@Test
	@Scenario("T25")
	void anEventCitesEveryObservationBehindItAndSurvivesTheLossOfOne() {
		try (Engine e = TestHomes.engine("t25-sources")) {
			remember(e, "Lars lives in Uppsala.", proposal().entity("e1", "Lars Melin", "person")
					.entity("e2", "Uppsala", "place").fact("e1", "lives_in", "e2"));
			Entity lars = e.entities().byRef("Lars Melin").orElseThrow();
			RememberOutcome first = remember(e, "Lars has passed away.",
					proposal().entity("e1", "Lars Melin", "person").event("ev1", "died", null, "e1"));
			RememberOutcome second = remember(e, "Lars died on 7 October 2014.",
					proposal().entity("e1", "Lars Melin", "person").event("ev1", "died", "2014-10-07", "e1"));
			List<Event> deaths = e.events().eventsOfType(lars.id(), "died");
			assertEquals(1, deaths.size(), "one death, stated twice: " + deaths);
			Event death = deaths.getFirst();
			assertEquals("2014-10-07", death.validStart());
			assertEquals(List.of(said(first), said(second)), e.events().observationsOf(death.id()),
					"both observations, the first as its home");
			assertEquals("2014-10-07", home(e, lars).validEnd(), "the dated death ended his home");
			// The first statement goes: the event stays, re-homed, with its date.
			assertTrue(e.forget(said(first), true));
			Event kept = e.events().get(death.id()).orElseThrow();
			assertEquals(said(second), kept.observationId());
			assertEquals("2014-10-07", kept.validStart());
			assertEquals(List.of(said(second)), e.events().observationsOf(death.id()));
			assertEquals("2014-10-07", home(e, lars).validEnd());
		}
		// The dating statement goes instead: the event stays with the first, undated, and still ends what it ended.
		try (Engine e = TestHomes.engine("t25-undated")) {
			remember(e, "Lars lives in Uppsala.", proposal().entity("e1", "Lars Melin", "person")
					.entity("e2", "Uppsala", "place").fact("e1", "lives_in", "e2"));
			Entity lars = e.entities().byRef("Lars Melin").orElseThrow();
			RememberOutcome first = remember(e, "Lars has passed away.",
					proposal().entity("e1", "Lars Melin", "person").event("ev1", "died", null, "e1"));
			RememberOutcome second = remember(e, "Lars died on 7 October 2014.",
					proposal().entity("e1", "Lars Melin", "person").event("ev1", "died", "2014-10-07", "e1"));
			Event death = e.events().eventsOfType(lars.id(), "died").getFirst();
			assertTrue(e.forget(said(second), true));
			Event kept = e.events().get(death.id()).orElseThrow();
			assertEquals(said(first), kept.observationId());
			assertNull(kept.validStart(), "the date went with the observation that supplied it");
			Fact home = home(e, lars);
			assertTrue(home.ended(), "still ended by the death: " + home);
			assertNull(home.validEnd(), "without a day");
			// A rebuild finds the sources from the observations again.
			e.rebuild();
			Event rebuilt = e.events().eventsOfType(lars.id(), "died").getFirst();
			assertEquals(1, e.events().observationsOf(rebuilt.id()).size());
		}
	}
}
