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
package se.hirt.mnemic.bench;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.bench.Ingestor.Granularity;
import se.hirt.mnemic.bench.Ingestor.Ingested;
import se.hirt.mnemic.bench.LongMemEval.Question;
import se.hirt.mnemic.model.ChatModel;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.model.ModelSpec;
import se.hirt.mnemic.recall.RecallResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The harness without any network: loader, ingestion, retrieval metrics, diagnosis, bootstrap, model SPI. */
class HarnessTest {

	private static Path fixture() {
		return Path.of("src", "test", "resources", "longmemeval_fixture.json");
	}

	/** A scripted model: answers every prompt with a fixed string, so the proposer path runs offline. */
	private static ChatModel scripted(String reply) {
		return new ChatModel() {
			@Override
			public String id() {
				return "scripted:test";
			}

			@Override
			public String chat(String system, String user) {
				return reply;
			}
		};
	}

	@Test
	void loadsTheOfficialSchema() throws Exception {
		List<Question> qs = LongMemEval.load(fixture());
		assertEquals(2, qs.size());
		Question q = qs.getFirst();
		assertEquals("fx-001", q.id());
		assertEquals("single-session-user", q.type());
		assertEquals(3, q.haystack().size());
		assertEquals("s-beta", q.haystack().get(1).id());
		assertTrue(q.haystack().get(1).turns().getFirst().hasAnswer());
		assertEquals(Instant.parse("2023-06-10T09:00:00Z"), q.date());
		assertEquals(List.of("s-beta"), q.answerSessionIds());
		assertFalse(q.isAbstention());
		assertTrue(qs.get(1).isAbstention());
	}

	@Test
	void sessionGranularityFindsTheAnswerSession() throws Exception {
		Question q = LongMemEval.load(fixture()).getFirst();
		try (Engine e = new Engine(Files.createTempDirectory("bench-session"), "test", Integer.MAX_VALUE, "the user")) {
			assertEquals(3, Ingestor.ingest(e, q, Granularity.SESSION));
			RecallResult r = e.recall().recall(q.question(), q.date(), 4000, 40);
			List<String> ranked = Ingestor.rankedSessions(r);
			assertEquals("s-beta", ranked.getFirst(), r.text());
			assertEquals(1.0, Metrics.recallAtK(ranked, Set.of("s-beta"), 5));
			assertEquals("conversation", r.hits().getFirst().observation().source().kind());
		}
	}

	@Test
	void turnGranularityKeepsSessionProvenance() throws Exception {
		Question q = LongMemEval.load(fixture()).getFirst();
		try (Engine e = new Engine(Files.createTempDirectory("bench-turn"), "test", Integer.MAX_VALUE, "the user")) {
			assertEquals(6, Ingestor.ingest(e, q, Granularity.TURN));
			RecallResult r = e.recall().recall(q.question(), q.date(), 4000, 40);
			assertEquals("s-beta", Ingestor.rankedSessions(r).getFirst(), r.text());
			var top = r.hits().getFirst().observation();
			assertEquals("s-beta", top.source().ref());
			assertNotNull(top.source().chunk());
		}
	}

	@Test
	void proposerAttachesFactsAsKeys() throws Exception {
		Question q = LongMemEval.load(fixture()).getFirst();
		String proposal = """
		                  {"spec_version": 1, "entities": [{"ref": "e1", "name": "Lisbon", "type": "place"}],
		                   "facts": [{"subject": "self", "predicate": "lives_in", "object": "e1"}]}""";
		ApiProposer proposer = new ApiProposer(scripted("```json\n" + proposal + "\n```"));
		try (Engine e = new Engine(Files.createTempDirectory("bench-prop"), "test", Integer.MAX_VALUE, "the user")) {
			Ingested ing = Ingestor.ingest(e, q, Granularity.SESSION, proposer);
			assertEquals(3, ing.observations());
			assertEquals(3, ing.facts(), "one lives_in per session (the scripted proposer repeats itself)");
			assertEquals(0, ing.proposalsFailed());
			assertEquals(3, proposer.attempted());
			RecallResult r = e.recall().recall("where does the user live", q.date(), 4000, 40);
			assertTrue(r.structured().matched(), r.text());
		}
		ApiProposer broken = new ApiProposer(scripted("I cannot help with that."));
		try (Engine e = new Engine(Files.createTempDirectory("bench-broken"), "test", Integer.MAX_VALUE, "the user")) {
			Ingested ing = Ingestor.ingest(e, q, Granularity.SESSION, broken);
			assertEquals(3, ing.observations(), "observations are stored even when proposals fail");
			assertEquals(0, ing.facts());
			assertEquals(3, ing.proposalsFailed());
		}
	}

	@Test
	void proposalCacheServesRepeatsAndPrefetchKeepsOrder() throws Exception {
		Path dir = Files.createTempDirectory("mnemic-cache");
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		ChatModel counting = new ChatModel() {
			@Override
			public String id() {
				return "test:counting";
			}

			@Override
			public String chat(String system, String user) {
				calls.incrementAndGet();
				int at = user.indexOf("Observation:") + "Observation:".length() + 5; // skip the newline and quotes
				String n = user.substring(at, at + 1);
				return "{\"facts\": [{\"predicate\": \"x:n\", \"object\": \"" + n + "\"}]}";
			}
		};
		try (ApiProposer p = new ApiProposer(counting, new ProposalCache(dir), 4)) {
			List<String> obs = List.of("1 one", "2 two", "3 three", "1 one");
			List<String> dates = List.of("2024-01-01", "2024-01-01", "2024-01-01", "2024-01-01");
			var futures = p.prefetch(obs, dates);
			for (int i = 0; i < obs.size(); i++) {
				assertEquals(obs.get(i).substring(0, 1), futures.get(i).get().orElseThrow().facts().getFirst().object(),
						"result " + i + " belongs to observation " + i);
			}
		}
		int first = calls.get();
		assertEquals(3, first, "the repeated observation shares one in-flight call");
		try (ApiProposer again = new ApiProposer(counting, new ProposalCache(dir), 2)) {
			for (var f : again.prefetch(List.of("2 two", "3 three"), List.of("2024-01-01", "2024-01-01"))) {
				assertTrue(f.get().isPresent());
			}
			assertEquals(2, again.cached(), "second run is served from disk");
		}
		assertEquals(first, calls.get(), "no new calls");
		assertTrue(Files.walk(dir).anyMatch(f -> f.toString().endsWith(".txt")), "cache files on disk");

		// A reply that does not parse stays cached (and keeps failing) unless refresh-failed asks again.
		var broken = new java.util.concurrent.atomic.AtomicBoolean(true);
		ChatModel flaky = new ChatModel() {
			@Override
			public String id() {
				return "test:flaky";
			}

			@Override
			public String chat(String system, String user) {
				calls.incrementAndGet();
				return broken.get() ? "sorry, no" : "{\"facts\": [{\"predicate\": \"x:n\", \"object\": \"ok\"}]}";
			}
		};
		try (ApiProposer p = new ApiProposer(flaky, new ProposalCache(dir), 1)) {
			assertTrue(p.propose("9 nine", "2024-01-01").isEmpty());
			assertEquals(1, p.failed());
		}
		broken.set(false);
		int before = calls.get();
		try (ApiProposer p = new ApiProposer(flaky, new ProposalCache(dir), 1)) {
			assertTrue(p.propose("9 nine", "2024-01-01").isEmpty(), "still the cached failure");
			assertEquals(before, calls.get(), "no call without refresh-failed");
		}
		try (ApiProposer p = new ApiProposer(flaky, new ProposalCache(dir), 1, true)) {
			assertTrue(p.propose("9 nine", "2024-01-01").isPresent(), "asked again and parsed");
			assertEquals(1, p.refreshed());
			assertEquals(before + 1, calls.get());
		}
		try (ApiProposer p = new ApiProposer(flaky, new ProposalCache(dir), 1, true)) {
			assertTrue(p.propose("9 nine", "2024-01-01").isPresent());
			assertEquals(0, p.refreshed(), "the good reply replaced the file");
			assertEquals(before + 1, calls.get());
		}

		// An endpoint error on one session is an observation without a proposal, not a dead question; nothing cached.
		ChatModel refusing = new ChatModel() {
			@Override
			public String id() {
				return "test:refusing";
			}

			@Override
			public String chat(String system, String user) throws java.io.IOException {
				throw new java.io.IOException("Model endpoint returned 400: context size exceeded");
			}
		};
		try (ApiProposer p = new ApiProposer(refusing, new ProposalCache(dir), 1)) {
			assertTrue(p.propose("8 eight", "2024-01-01").isEmpty());
			assertEquals(1, p.errors());
			assertEquals(1, p.failed());
			assertEquals(0, p.cached());
		}
		try (ApiProposer p = new ApiProposer(refusing, new ProposalCache(dir), 1)) {
			assertTrue(p.propose("8 eight", "2024-01-01").isEmpty(), "not cached, asked again");
			assertEquals(1, p.errors());
		}
	}

	@Test
	void resultFilesReadAsLinesOrAsAPrettyPrintedStream() throws Exception {
		Path dir = Files.createTempDirectory("mnemic-jsonl");
		Path lines = dir.resolve("lines.jsonl");
		Files.writeString(lines, """
				{"question_id":"a","recall_at_5":1.0}
				{"question_id":"b","recall_at_5":0.0}
				""");
		Path pretty = dir.resolve("pretty.jsonl");
		Files.writeString(pretty, """
				{
				  "question_id": "a",
				  "recall_at_5": 1.0
				}
				{
				  "question_id": "b",
				  "recall_at_5": 0.0
				}
				""");
		assertEquals(2, Bench.readLines(lines).size());
		assertEquals(2, Bench.readLines(pretty).size());
		assertEquals("b", Bench.readLines(pretty).get(1).get("question_id").asText());
		assertEquals(0, Bench.readLines(dir.resolve("missing.jsonl")).size());
	}

	@Test
	void modelSpecsAndProviderLookup() {
		ModelSpec a = ModelSpec.parse("anthropic:claude-haiku-4-5", null);
		assertEquals("anthropic", a.provider());
		assertEquals("claude-haiku-4-5", a.model());
		ModelSpec c = ModelSpec.parse("openai-compatible:my-model@http://host:8080/v1", "MY_KEY");
		assertEquals("http://host:8080/v1", c.endpoint());
		assertEquals("MY_KEY", c.apiKeyEnv());
		assertThrows(IllegalArgumentException.class, () -> ModelSpec.parse("no-provider", null));
		// An explicit endpoint nobody answers: no live LM Studio is consulted, and the id stays the bare spec.
		ChatModel lm = ModelProvider.resolve("lmstudio:qwen3-4b@http://127.0.0.1:9/v1", null);
		assertEquals("lmstudio:qwen3-4b", lm.id());
		assertThrows(IllegalArgumentException.class, () -> ModelProvider.resolve("nosuch:model", null));
		assertEquals("{\"a\": 1}", ApiProposer.extractJson("Sure!\n```json\n{\"a\": 1}\n```\nDone."));
		assertFalse(ApiProposer.loadSpec().isBlank(), "spec ships in the server jar");
	}

	@Test
	void asOfExcludesSessionsAfterTheQuestionDate() throws Exception {
		Question q = LongMemEval.load(fixture()).getFirst();
		try (Engine e = new Engine(Files.createTempDirectory("bench-asof"), "test", Integer.MAX_VALUE, "the user")) {
			Ingestor.ingest(e, q, Granularity.SESSION);
			RecallResult before = e.recall().recall("Lisbon job", Instant.parse("2023-05-10T00:00:00Z"), 4000, 40);
			assertTrue(before.hits().isEmpty(), "the Lisbon session is dated 2023-05-15: " + before.text());
		}
	}

	@Test
	void diagnosisSeparatesRetrievalMissFromAnswerError() {
		assertEquals(Diagnose.Cause.ANSWER_ERROR,
				Diagnose.classify(false, false, "Lisbon", "user: I'm moving to Lisbon for the new job"));
		assertEquals(Diagnose.Cause.RETRIEVAL_MISS,
				Diagnose.classify(false, false, "Lisbon", "user: recommend a sourdough recipe"));
		assertEquals(Diagnose.Cause.ABSTENTION_MISS, Diagnose.classify(false, true, "n/a", "anything"));
		assertEquals(Diagnose.Cause.CORRECT, Diagnose.classify(true, false, "Lisbon", ""));
		assertTrue(Diagnose.looksLikeRefusal("I don't have that information in the retrieved memory."));
		assertFalse(Diagnose.looksLikeRefusal("You moved to Lisbon."));
	}

	@Test
	void judgeDecisionAndPrompts() {
		assertTrue(Judge.isYes("Yes, the response contains the answer."));
		assertFalse(Judge.isYes("No."));
		assertTrue(Judge.prompt("q_abs", "single-session-user", "q", "a", "h").contains("unanswerable"));
		assertTrue(Judge.prompt("q", "temporal-reasoning", "q", "a", "h").contains("off-by-one"));
		assertTrue(Judge.prompt("q", "knowledge-update", "q", "a", "h").contains("updated"));
	}

	@Test
	void bootstrapIntervalsAndPairedDifference() {
		var labels = new java.util.ArrayList<Boolean>();
		for (int i = 0; i < 100; i++) {
			labels.add(i < 80);
		}
		Metrics.Estimate acc = Metrics.accuracy(labels);
		assertEquals(0.8, acc.value(), 1e-9);
		assertTrue(acc.low() > 0.7 && acc.high() < 0.9, acc.toString());

		var a = new java.util.LinkedHashMap<String, Boolean>();
		var b = new java.util.LinkedHashMap<String, Boolean>();
		for (int i = 0; i < 200; i++) {
			a.put("q" + i, i < 100);
			b.put("q" + i, i < 130);
		}
		Metrics.Estimate diff = Metrics.pairedDifference(a, b);
		assertEquals(0.15, diff.value(), 1e-9);
		assertTrue(diff.low() > 0, "interval excludes zero: " + diff);

		Metrics.Estimate same = Metrics.pairedDifference(a, Map.copyOf(a));
		assertEquals(0.0, same.value(), 1e-9);
		assertEquals(0.0, same.low(), 1e-9);
	}

	@Test
	void recallAtKAndPercentiles() {
		assertEquals(0.5, Metrics.recallAtK(List.of("x", "a", "y"), Set.of("a", "b"), 5));
		assertEquals(0.0, Metrics.recallAtK(List.of("x", "y", "a"), Set.of("a"), 2));
		assertTrue(Double.isNaN(Metrics.recallAtK(List.of(), Set.of(), 5)));
		assertEquals(3.0, Metrics.percentile(List.of(5L, 1L, 3L, 2L, 4L), 0.5));
		assertEquals(5.0, Metrics.percentile(List.of(5L, 1L, 3L, 2L, 4L), 0.95));
	}

	@Test
	void cliOptionParsing() {
		Map<String, String> o = Bench.parse(new String[] {"run", "--data", "x.json", "--out", "r", "--limit", "5"});
		assertEquals("x.json", o.get("data"));
		assertEquals("5", o.get("limit"));
	}
}
