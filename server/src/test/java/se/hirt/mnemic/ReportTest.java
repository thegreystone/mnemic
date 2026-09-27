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
package se.hirt.mnemic;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine.Consolidation;
import se.hirt.mnemic.Engine.RememberOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * The page consolidate writes: the store as a person reads it, with what to look at first, stated before derived, ids
 * that reach the rest, and caps that keep it a report and not a copy of the database (2026-09-27).
 */
class ReportTest {

	@Test
	void thePageSaysWhatToLookAtAndWhatIsKnown() throws Exception {
		try (Engine e = engine("report-page")) {
			// Parents and a sibling: stated facts, and derived ones (uncle, cousin) that must be counted, not listed.
			remember(e, "My parents are Konrad and Gunilla Nyberg; my brother Oskar has a son, Leo.",
					proposal().entity("k", "Konrad Nyberg", "person").entity("g", "Gunilla Nyberg", "person")
							.entity("o", "Oskar Nyberg", "person").entity("l", "Leo Nyberg", "person")
							.fact(fact("k", "parent_of", "self", "father", null, null, null, null, null, null))
							.fact(fact("g", "parent_of", "self", "mother", null, null, null, null, null, null))
							.fact(fact("o", "sibling_of", "self", "brother", null, null, null, null, null, null))
							.fact(fact("o", "parent_of", "l", "father", null, null, null, null, null, null)));
			// A relation registered from use: a finding.
			remember(e, "I mentor Anna.",
					proposal().entity("a", "Anna Lindqvist", "person").fact("self", "mentors", "a"));
			// An open question: a bare "Anna" that fits Anna Lindqvist.
			RememberOutcome asked = remember(e, "Anna moved to Bern.",
					proposal().entity("a2", "Anna", "person").entity("b", "Bern", "place").fact("a2", "lives_in", "b"));
			assertFalse(asked.applied().questions().isEmpty(), asked.applied().toString());
			String questionId = String.valueOf(asked.applied().questions().getFirst().get("id"));
			// A correction: a recent change.
			long mentors = Long.parseLong(e.facts().factsOf(e.entities().byRef("Anna Lindqvist").orElseThrow().id())
					.getFirst().ref().substring(2));
			e.correct(mentors, Map.of("wrong", true), "I coach her, I do not mentor her");

			Consolidation c = e.consolidate(true);
			List<String> findings = e.findings(c);
			assertTrue(findings.stream().anyMatch(f -> f.contains("'mentors'") && f.contains("registered from use")),
					findings.toString());
			assertTrue(findings.stream().anyMatch(f -> f.contains(questionId) && f.contains("remember(resolve")),
					findings.toString());

			Path path = e.writeReport(c, true);
			assertEquals(e.home().resolve("report.md"), path);
			String page = Files.readString(path);
			assertTrue(page.startsWith("# Mnemic report for Mattias Sandell — "), page.lines().findFirst().orElse(""));
			assertTrue(page.contains("(dry run)"), page);
			assertTrue(page.contains("## Things to look at (" + findings.size() + ")"), page);
			// Stated before derived, and the derived ones as a count.
			int stated = page.indexOf("Konrad Nyberg is Mattias Sandell's father [f-");
			int derived = page.indexOf("- derived, not stated: ");
			assertTrue(stated > 0 && derived > stated, page);
			assertTrue(page.contains(" aunt_uncle_of") || page.contains(" cousin_of"), "counted, not listed: " + page);
			assertFalse(page.contains("is Leo Nyberg's uncle [f-"),
					"a derived fact is not listed under About: " + page);
			// People with their ids, the question with its answer call, the correction as a change.
			assertTrue(page.contains("### Oskar Nyberg (person, ent-"), page);
			assertTrue(page.contains("## Open questions (1)") && page.contains(questionId + " [entity_resolution]"),
					page);
			assertTrue(page.contains("## Recent changes") && page.contains("I coach her, I do not mentor her"), page);
			// The same page through the engine, and the briefing's pointer to it.
			assertEquals(page, e.report().orElseThrow());
			String briefing = e.briefing(4000);
			assertTrue(briefing.contains("report of ") && briefing.contains(" things to look at; inspect('report')"),
					briefing);
		}
	}

	@Test
	void thePageIsCappedNotACopyOfTheStore() throws Exception {
		try (Engine e = engine("report-caps")) {
			for (int i = 1; i <= 30; i++) {
				var p = proposal().entity("p" + i, "Person " + i + " Berg", "person").entity("t" + i, "Town " + i,
						"place");
				p = p.fact("p" + i, "lives_in", "t" + i);
				for (int k = 1; k <= 12; k++) {
					p = p.fact("p" + i, "decided", "option " + k + " of person " + i);
				}
				remember(e, "Person " + i + " Berg lives in Town " + i + " and made twelve decisions.", p);
			}
			for (int i = 1; i <= 60; i++) {
				remember(e, "Decision " + i + ".", proposal().fact("decided", "to take option number " + i));
			}
			Consolidation c = e.consolidate(true);
			String page = Report.render(e, c, true, e.clock().instant());
			assertTrue(page.length() <= Report.MAX_CHARS + 200, "cut at the cap: " + page.length());
			long headings = page.lines().filter(l -> l.startsWith("### ")).count();
			assertTrue(headings <= Report.ENTITIES, "at most " + Report.ENTITIES + " entities: " + headings);
			assertTrue(page.contains("more: inspect('ent-"), "a crowded entity points at inspect: " + page);
			assertTrue(page.contains("more stated facts: recall by topic"), page);
		}
	}

	@Test
	void findingsComeNewestFirstAndSayWhatIsNew() throws Exception {
		try (Engine e = engine("report-recency")) {
			remember(e, "I mentor Anna.",
					proposal().entity("a", "Anna Lindqvist", "person").fact("self", "mentors", "a"));
			Consolidation first = e.consolidate(true);
			assertTrue(e.findings(first).stream().anyMatch(f -> f.startsWith("new: ") && f.contains("'mentors'")),
					"before any consolidation everything is new: " + e.findings(first));
			assertEquals(0, e.lastConsolidationMark(), "a dry run changes nothing, the mark included");
			e.consolidate(false);
			assertTrue(e.lastConsolidationMark() > 0);
			// A second relation registered from use after the first consolidation: it comes first, marked new,
			// and the one the reader has already been shown is not marked.
			remember(e, "I juggle for Erik.",
					proposal().entity("er", "Erik Nyberg", "person").fact("self", "juggles_for", "er"));
			Consolidation second = e.consolidate(true);
			List<String> findings = e.findings(second);
			List<String> vocabulary = findings.stream().filter(f -> f.contains("registered from use")).toList();
			assertEquals(2, vocabulary.size(), findings.toString());
			assertTrue(vocabulary.get(0).startsWith("new: ") && vocabulary.get(0).contains("'juggles_for'"),
					vocabulary.toString());
			assertFalse(vocabulary.get(1).startsWith("new: "), vocabulary.toString());
			assertTrue(vocabulary.get(1).contains("'mentors'"), vocabulary.toString());
			assertEquals(Boolean.TRUE, second.inferredVocabulary().getFirst().get("new"));
			assertFalse(second.inferredVocabulary().get(1).containsKey("new"));
			// The page carries the mark too.
			String page = Report.render(e, second, true, e.clock().instant());
			assertTrue(page.contains("- new: the predicate 'juggles_for'"), page);
		}
	}

	@Test
	void theOrderIsByTheObservationCitedThenByAnyId() {
		List<Map<String, Object>> entries = List.of(Map.of("fact", "f-9", "observation", "obs-2"),
				Map.of("predicate", "x", "observations", List.of("obs-1", "obs-7")), Map.of("entity", "ent-40"),
				Map.of("event", "evt-3", "observation", "obs-5"));
		List<Map<String, Object>> ordered = Engine.recentFirst(entries, 4);
		assertEquals("x", ordered.get(0).get("predicate"), "cites obs-7, newest and new: " + ordered);
		assertEquals(Boolean.TRUE, ordered.get(0).get("new"));
		assertEquals("evt-3", ordered.get(1).get("event"), "obs-5 is new too: " + ordered);
		assertEquals("f-9", ordered.get(2).get("fact"), "obs-2 is older than the mark: " + ordered);
		assertFalse(ordered.get(2).containsKey("new"));
		assertEquals("ent-40", ordered.get(3).get("entity"), "no observation cited: last, by its own id");
	}

}
