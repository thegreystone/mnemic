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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.recall.RecallResult;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section F: the structured channel, the miss signal, near-misses, aliases, events. */
class StructuredRecallTest {

	private static void kinship(Engine e) {
		remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad", "person")
				.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
		remember(e, "My stepfather was Torsten Björk, Bosse to the family.",
				proposal().entity("e1", "Torsten Björk", "person", "Bosse")
						.fact(fact("e1", "parent_of", "self", "stepfather", null, null, null, null, null, null)));
	}

	@Test
	@Scenario("F5")
	void structuredMissIsReportedNotPaperedOver() {
		try (Engine e = engine("f5")) {
			kinship(e);
			RecallResult r = recall(e, "who is Mattias's mother");
			assertEquals("miss", r.structured().state(), r.text());
			assertEquals(e.entities().owner().ref(), r.structured().entity());
			assertEquals("parent_of", r.structured().predicate());
			assertEquals("mother", r.structured().qualifier());
			assertEquals(2, r.structured().nearMisses().size(), "father and stepfather are near-misses");
			assertTrue(r.text().contains("structured: MISS"), r.text());
			assertTrue(r.text().contains("near-miss"), r.text());
			for (RecallResult.Hit h : r.hits()) {
				assertTrue(h.nearMiss(), "every returned item is marked as a near miss: " + r.text());
				assertTrue(h.facts().stream().noneMatch(f -> "mother".equals(f.qualifier())), "nothing fabricated");
			}
		}
	}

	@Test
	@Scenario("F6")
	void kinshipQualifierMatchesStructurally() {
		try (Engine e = engine("f6")) {
			kinship(e);
			RecallResult r = recall(e, "who is Mattias's father");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(1, r.structured().facts().size());
			assertEquals("Konrad is Mattias Sandell's father", r.structured().facts().getFirst().rendering());
			assertEquals(1, r.structured().nearMisses().size(), "the stepfather is the near miss");
			assertTrue(r.hits().getFirst().observation().text().contains("Konrad"),
					"the father observation ranks first: " + r.text());
			assertFalse(r.hits().getFirst().nearMiss());
		}
	}

	@Test
	@Scenario("F7")
	void aliasCapturedAtWriteTimeResolvesAtReadTime() {
		try (Engine e = engine("f7")) {
			kinship(e);
			Entity bosse = e.entities().byRef("Bosse").orElseThrow();
			assertEquals("Torsten Björk", bosse.name());
			assertTrue(e.entities().aliases(bosse.id()).contains("Bosse"));
			RecallResult r = recall(e, "who is Bosse");
			assertEquals(1, r.hits().size(), r.text());
			assertTrue(r.hits().getFirst().facts().stream().anyMatch(f -> f.subjectId() == bosse.id()), r.text());
			assertEquals(1, e.facts().factsOf(bosse.id()).size());
		}
	}

	@Test
	@Scenario("F8")
	void predicatesRegisteredFromUseCueOnTheirOwnWordsOnly() {
		try (Engine e = engine("f8")) {
			remember(e, "I'm leading the Profiler team at Hooli.",
					proposal().entity("e1", "Profiler team", "product").fact("self", "responsible_for", "e1"));
			RecallResult r = recall(e, "what does Mattias lead");
			assertEquals(1, r.hits().size(), r.text());
			assertFalse(r.structured().matched(), "'lead' is not among the name's words: " + r.structured());
			assertTrue(r.hits().getFirst().channels().contains("lexical"), r.text());
			assertTrue(recall(e, "what is Mattias responsible for").structured().matched(), "its own words cue it");
		}
		try (Engine e = engine("f8-core")) {
			remember(e, "I'm leading the Profiler team at Hooli.",
					proposal().entity("e1", "Profiler team", "product").fact("self", "leads", "e1"));
			RecallResult r = recall(e, "what does Mattias lead");
			assertTrue(r.structured().matched(), r.text());
		}
	}

	@Test
	@Scenario("F9")
	void eventsAreReturnedWithFacts() {
		try (Engine e = engine("f9")) {
			var o = remember(e, "I joined Hooli in 2018.",
					proposal().entity("e2", "Hooli", "organization").event("ev1", "joined", "2018", "self", "e2")
							.fact(fact("self", "works_at", "e2", null, null, "2018", null, null,
									java.util.List.of("ev1"), null)));
			assertEquals(1, o.applied().events().size());
			RecallResult r = recall(e, "where does Mattias work");
			assertTrue(r.structured().matched(), r.text());
			var f = r.structured().facts().getFirst();
			assertEquals(Long.valueOf(o.applied().events().getFirst().id().substring(4)), f.eventId());
			var ev = e.events().get(f.eventId()).orElseThrow();
			assertEquals("joined", ev.type());
			assertEquals("2018-01-01", ev.validStart());
			assertTrue(ev.rendering().startsWith("Mattias Sandell joined Hooli"), ev.rendering());
			assertTrue(f.rendering().endsWith("(since 2018)"), f.rendering());
		}
	}

	@Test
	void factsAreKeysAndTheObservationIsTheValue() {
		try (Engine e = engine("keys")) {
			// The observation never says "works"; only the fact rendering does. Facts-as-keys must find it.
			remember(e, "Hooli, since 2018. Best decision ever.", proposal().entity("e1", "Hooli", "organization")
					.fact(fact("self", "works_at", "e1", null, null, "2018", null, null, null, null)));
			RecallResult r = recall(e, "Mattias employer");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(1, r.hits().size(), r.text());
			assertTrue(r.hits().getFirst().shown().contains("Best decision ever"), "observation returned as value");
		}
	}
}
