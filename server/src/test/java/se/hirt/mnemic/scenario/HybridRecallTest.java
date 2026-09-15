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
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section F: hybrid recall, the parts the lexical channel alone must satisfy. */
class HybridRecallTest {

	@Test
	@Scenario("F1")
	void lexicalMatchWithoutEmbeddings() {
		try (Engine e = engine("f1")) {
			remember(e, "We decided to use SQLite for Mnemic's storage.");
			remember(e, "Lunch was good.");
			RecallResult r = recall(e, "SQLite");
			assertEquals(1, r.hits().size(), r.text());
			assertTrue(r.hits().getFirst().observation().text().contains("SQLite"));
		}
	}

	@Test
	@Scenario("F4")
	void contextBudgetIsRespected() {
		try (Engine e = engine("f4")) {
			for (int i = 0; i < 60; i++) {
				remember(e, "Fact number " + i + " about Mattias: he likes topic " + i + " and project alpha.");
			}
			RecallResult r = e.recall().recall("tell me about Mattias", null, 300, 100);
			assertTrue(r.tokensUsed() <= 300, "used " + r.tokensUsed());
			assertTrue(r.truncated(), "sixty hits cannot fit in 300 tokens");
			assertTrue(r.hits().size() < 60 && !r.hits().isEmpty(), "hits " + r.hits().size());
			assertTrue(r.text().contains("truncated by budget"), r.text());
		}
	}

	/**
	 * DECISIONS.md §2.3 and scenario family M: the time constraint is applied before ranking, so a query against a
	 * store where almost everything is from the wrong period still returns the right items.
	 */
	@Test
	@Scenario("M1")
	void asOfIsAPreFilterNotAPostFilter() {
		try (Engine e = engine("m1")) {
			Instant early = Instant.parse("2015-03-01T12:00:00Z");
			remember(e, "Mattias works at Initrode on the JDK.", early);
			for (int i = 0; i < 95; i++) {
				remember(e, "Mattias works on profiler item " + i + " at Hooli.",
						Instant.parse("2026-01-01T00:00:00Z"));
			}
			RecallResult r = e.recall().recall("where does Mattias work", Instant.parse("2015-06-01T00:00:00Z"), 800,
					10);
			assertEquals(1, r.hits().size(), r.text());
			assertTrue(r.hits().getFirst().observation().text().contains("Initrode"));
			assertEquals(1, r.candidates(), "the 95 later observations never entered the candidate list");
		}
	}

	@Test
	void queryMadeOnlyOfStopwordsReturnsNothingRatherThanEverything() {
		try (Engine e = engine("stop")) {
			remember(e, "I work at Hooli.");
			RecallResult r = recall(e, "what is it");
			assertTrue(r.hits().isEmpty(), r.text());
		}
	}

	@Test
	void ftsSyntaxInTheQuestionCannotBreakTheQuery() {
		try (Engine e = engine("syntax")) {
			remember(e, "The build uses NOT NULL constraints and a (weird) OR clause.");
			RecallResult r = recall(e, "NOT NULL (weird) OR \"clause AND");
			assertEquals(1, r.hits().size(), r.text());
		}
	}
}
