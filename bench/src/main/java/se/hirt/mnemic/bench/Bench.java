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

import se.hirt.mnemic.embed.Embedder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.bench.Ingestor.Granularity;
import se.hirt.mnemic.bench.Ingestor.Ingested;
import se.hirt.mnemic.bench.LongMemEval.Question;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.recall.RecallResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * The benchmark CLI. A run directory holds {@code config.json}, {@code retrieval.jsonl} (one line per question: ranked
 * sessions, recall@k, latency, context tokens, facts), {@code hypotheses.jsonl} in LongMemEval's official format when a
 * reader is set, {@code judged.jsonl} after {@code judge}, {@code errors.jsonl} for questions that failed, and
 * {@code metrics.json} after {@code metrics}. Models are named as {@code provider:model[@endpoint]} and resolved
 * through the {@link ModelProvider} SPI. A run is resumable: questions already in {@code retrieval.jsonl} are skipped,
 * so a killed run with a paid proposer does not repeat its work.
 *
 * <pre>
 * bench run     --data longmemeval_s.json --out results/m1-facts [--granularity session|turn] [--k 10]
 *               [--budget 4000] [--proposer anthropic:claude-haiku-4-5] [--reader lmstudio:qwen3-4b]
 *               [--api-key-env NAME] [--limit N] [--types t1,t2] [--workers 8] [--cache cache/proposals]
 *               [--refresh-failed] [--reasoning-effort none|low|medium|high]
 * bench judge   --run results/m1-facts --judge anthropic:claude-sonnet-5 [--api-key-env NAME]
 * bench metrics --run results/m1-facts
 * bench compare --a results/m0-session --b results/m1-facts
 * bench show    --data longmemeval_s.json --question 58bf7951 [--proposer ...] [--cache cache/proposals]
 * bench rekey   --data longmemeval_s.json --from lmstudio:proposer --to "lmstudio:proposer[qwen/qwen35/Q4_K_M]" --limit 20
 * </pre>
 */
public final class Bench {

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final ObjectMapper LINE = new ObjectMapper();

	private Bench() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			usage();
			return;
		}
		Map<String, String> opts = parse(args);
		switch (args[0]) {
		case "run" -> run(opts);
		case "judge" -> judge(opts);
		case "metrics" -> metrics(opts);
		case "compare" -> compare(opts);
		case "show" -> show(opts);
		case "rekey" -> rekey(opts);
		case "bakeoff" -> Bakeoff.run(opts);
		case "usage" -> Usage.run(opts);
		default -> usage();
		}
	}

	// ── run ─────────────────────────────────────────────────────────────

	static void run(Map<String, String> o) throws Exception {
		Path data = Path.of(require(o, "data"));
		Path out = Path.of(require(o, "out"));
		Granularity granularity = Granularity.valueOf(o.getOrDefault("granularity", "session").toUpperCase());
		int k = Integer.parseInt(o.getOrDefault("k", "10"));
		int budget = Integer.parseInt(o.getOrDefault("budget", "4000"));
		int limit = Integer.parseInt(o.getOrDefault("limit", "0"));
		Set<String> types = o.containsKey("types") ? Set.of(o.get("types").split(",")) : Set.of();
		boolean onlyAbstention = "true".equals(o.get("abstention")); // the 30 questions whose answer is "not known"
		Embedder embedder = embedder(o);
		String keyEnv = o.get("api-key-env");
		Reader reader = o.containsKey("reader") && !"none".equals(o.get("reader"))
				? new Reader(ModelProvider.resolve(o.get("reader"), keyEnv)) : null;
		int workers = Integer.parseInt(o.getOrDefault("workers", "8"));
		Path cacheDir = Path.of(o.getOrDefault("cache", "cache/proposals"));
		boolean refreshFailed = "true".equals(o.get("refresh-failed"));
		reasoningEffort(o);
		ApiProposer proposer = o.containsKey("proposer") && !"none".equals(o.get("proposer"))
				? new ApiProposer(ModelProvider.resolve(o.get("proposer"), keyEnv), new ProposalCache(cacheDir),
						workers, refreshFailed)
				: null;

		Files.createDirectories(out);
		var config = new LinkedHashMap<String, Object>();
		config.put("data", data.toAbsolutePath().toString());
		config.put("granularity", granularity.name().toLowerCase());
		config.put("k", k);
		config.put("budget", budget);
		config.put("proposer", proposer == null ? "none" : proposer.model());
		config.put("reader", reader == null ? "none" : reader.model());
		config.put("proposal_workers", workers);
		config.put("proposal_cache", proposer == null ? null : cacheDir.toAbsolutePath().toString());
		config.put("refresh_failed", refreshFailed);
		config.put("reasoning_effort", System.getProperty("mnemic.reasoning_effort", "none"));
		config.put("embedder", embedder == null ? "none" : embedder.id());
		config.put("started_at", Instant.now().toString());
		config.put("engine_version", "m3");
		Files.writeString(out.resolve("config.json"), JSON.writeValueAsString(config), StandardCharsets.UTF_8);

		List<Question> questions = LongMemEval.load(data);
		Path homes = Files.createTempDirectory("mnemic-bench");
		Set<String> alreadyDone = done(out);
		int done = 0;
		int errors = 0;
		try (BufferedWriter retrieval = Files.newBufferedWriter(out.resolve("retrieval.jsonl"), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				BufferedWriter hyps = reader == null ? null : Files.newBufferedWriter(out.resolve("hypotheses.jsonl"),
						StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (Question q : questions) {
				if (!types.isEmpty() && !types.contains(q.type())) {
					continue;
				}
				if (onlyAbstention && !q.isAbstention()) {
					continue;
				}
				if (limit > 0 && done >= limit) {
					break;
				}
				if (alreadyDone.contains(q.id())) {
					done++;
					continue;
				}
				Path home = homes.resolve(q.id());
				RecallResult r;
				try (Engine e = new Engine(options(home).withEmbedder(embedder))) {
					try {
						long t0 = System.nanoTime();
						Ingested ing = Ingestor.ingest(e, q, granularity, proposer);
						long ingestNs = System.nanoTime() - t0;
						long t1 = System.nanoTime();
						r = e.recall().recall(q.question(), q.date(), budget, k * 4);
						long recallNs = System.nanoTime() - t1;
						writeLine(retrieval, retrievalLine(q, ing, r, ingestNs, recallNs));
					} catch (RuntimeException | IOException ex) {
						// One bad question must not void the other 499: record it and move on.
						// The first frames of our own code, so the record says where, not only what.
						List<String> where = java.util.Arrays.stream(ex.getStackTrace())
								.filter(fr -> fr.getClassName().startsWith("se.hirt.mnemic")).limit(4)
								.map(fr -> fr.getClassName().substring(fr.getClassName().lastIndexOf('.') + 1) + "."
										+ fr.getMethodName() + ":" + fr.getLineNumber())
								.toList();
						Files.writeString(out.resolve("errors.jsonl"),
								LINE.writeValueAsString(
										Map.of("question_id", q.id(), "error", String.valueOf(ex), "at", where)) + "\n",
								StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
						System.err.println("  error on " + q.id() + ": " + ex);
						deleteTree(home);
						done++; // the slot is spent: a subset must be the same questions on every run
						errors++;
						continue;
					}
				}
				if (reader != null) {
					writeLine(hyps, hypothesisLine(q, reader.answer(q.question(), q.dateText(), r.text()), r.text()));
				}
				done++;
				if (done % (proposer == null ? 25 : 5) == 0) {
					System.err.println(
							"  " + done + " questions" + (proposer == null ? "" : " (proposals: " + proposer.attempted()
									+ ", cached " + proposer.cached() + ", failed " + proposer.failed() + ")"));
				}
				deleteTree(home);
			}
		} finally {
			if (proposer != null) {
				proposer.close();
			}
		}
		System.out.println("run complete: " + done + " questions"
				+ (errors > 0 ? " (" + errors + " errored, see errors.jsonl)" : "") + " -> " + out.toAbsolutePath()
				+ (proposer == null ? ""
						: " (proposals: " + proposer.attempted() + ", cached " + proposer.cached() + ", refreshed "
								+ proposer.refreshed() + ", failed " + proposer.failed() + ", endpoint errors "
								+ proposer.errors() + ")"));
		metrics(Map.of("run", out.toString()));
	}

	/** One question's retrieval result: what was ingested, how the sessions ranked, and the recall figures. */
	private static Map<String, Object> retrievalLine(
		Question q, Ingested ing, RecallResult r, long ingestNs, long recallNs) {
		List<String> ranked = Ingestor.rankedSessions(r);
		Set<String> answers = new HashSet<>(q.answerSessionIds());
		var line = new LinkedHashMap<String, Object>();
		line.put("question_id", q.id());
		line.put("question_type", q.type());
		line.put("abstention", q.isAbstention());
		line.put("observations", ing.observations());
		line.put("facts", ing.facts());
		line.put("proposals_failed", ing.proposalsFailed());
		line.put("ranked_sessions", ranked);
		line.put("answer_sessions", q.answerSessionIds());
		line.put("recall_at_5", Metrics.recallAtK(ranked, answers, 5));
		line.put("recall_at_10", Metrics.recallAtK(ranked, answers, 10));
		line.put("structured", r.structured().state());
		line.put("candidates", r.candidates());
		line.put("context_tokens", r.tokensUsed());
		line.put("ingest_ms", ingestNs / 1_000_000);
		line.put("recall_ms", recallNs / 1_000_000);
		return line;
	}

	/** A reader's answer in LongMemEval's hypothesis format, with the context it saw. */
	private static Map<String, Object> hypothesisLine(Question q, String hypothesis, String context) {
		var h = new LinkedHashMap<String, Object>();
		h.put("question_id", q.id());
		h.put("question_type", q.type());
		h.put("question", q.question());
		h.put("answer", q.answer());
		h.put("hypothesis", hypothesis);
		h.put("context", context);
		return h;
	}

	private static void writeLine(BufferedWriter w, Map<String, Object> line) throws IOException {
		w.write(LINE.writeValueAsString(line));
		w.newLine();
		w.flush();
	}

	// ── show ────────────────────────────────────────────────────────────

	/** One question end to end, printed: the recall block the reader would see, and where the answers ranked. */
	static void show(Map<String, String> o) throws Exception {
		Path data = Path.of(require(o, "data"));
		String id = require(o, "question");
		Granularity granularity = Granularity.valueOf(o.getOrDefault("granularity", "session").toUpperCase());
		int k = Integer.parseInt(o.getOrDefault("k", "10"));
		int budget = Integer.parseInt(o.getOrDefault("budget", "4000"));
		Path cacheDir = Path.of(o.getOrDefault("cache", "cache/proposals"));
		reasoningEffort(o);
		ApiProposer proposer = o.containsKey("proposer") && !"none".equals(o.get("proposer"))
				? new ApiProposer(ModelProvider.resolve(o.get("proposer"), o.get("api-key-env")),
						new ProposalCache(cacheDir), Integer.parseInt(o.getOrDefault("workers", "8")))
				: null;
		Question q = LongMemEval.load(data).stream().filter(x -> x.id().startsWith(id)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No question " + id));
		Path home = Files.createTempDirectory("mnemic-show");
		try (Engine e = new Engine(options(home))) {
			Ingested ing = Ingestor.ingest(e, q, granularity, proposer);
			RecallResult r = e.recall().recall(q.question(), q.date(), budget, k * 4);
			List<String> ranked = Ingestor.rankedSessions(r);
			System.out.println("question " + q.id() + " [" + q.type() + "] " + q.dateText() + ": " + q.question());
			System.out.println("answer: " + q.answer());
			System.out.println("answer sessions: " + q.answerSessionIds() + " ranked at "
					+ q.answerSessionIds().stream().map(a -> String.valueOf(ranked.indexOf(a))).toList() + " of "
					+ ranked.size() + " (observations " + ing.observations() + ", facts " + ing.facts() + ")");
			for (String a : q.answerSessionIds()) {
				q.haystack().stream().filter(sess -> sess.id().equals(a)).findFirst().ifPresent(sess -> {
					String text = Ingestor.render(sess.turns());
					System.out.println("answer session " + a + " begins: \""
							+ text.substring(0, Math.min(300, text.length())).replace('\n', ' ') + "\"");
				});
			}
			System.out.println();
			System.out.println(r.text());
		} finally {
			if (proposer != null) {
				proposer.close();
			}
			deleteTree(home);
		}
	}

	/**
	 * {@code --reasoning-effort none|low|medium|high} for local reasoning models: the OpenAI-compatible provider reads
	 * it from the system property. Models that think (gpt-oss) do not accept {@code none}; the default stays
	 * {@code none} because it is what turns Qwen 3.5's thinking off.
	 */
	static void reasoningEffort(Map<String, String> o) {
		if (o.containsKey("reasoning-effort")) {
			System.setProperty("mnemic.reasoning_effort", o.get("reasoning-effort"));
		}
	}

	// ── rekey ───────────────────────────────────────────────────────────

	/**
	 * Copies cached replies from one model id to another for the questions a run covers, so replies cached under an
	 * earlier form of a model id (an LM Studio alias without its publisher/architecture/quantization suffix) stay
	 * usable. Never overwrites an existing entry.
	 */
	static void rekey(Map<String, String> o) throws Exception {
		Path data = Path.of(require(o, "data"));
		String from = require(o, "from");
		String to = require(o, "to");
		int limit = Integer.parseInt(o.getOrDefault("limit", "0"));
		Set<String> types = o.containsKey("types") ? Set.of(o.get("types").split(",")) : Set.of();
		Granularity granularity = Granularity.valueOf(o.getOrDefault("granularity", "session").toUpperCase());
		ProposalCache cache = new ProposalCache(Path.of(o.getOrDefault("cache", "cache/proposals")));
		String spec = ModelProposer.spec();
		int seen = 0, copied = 0, missing = 0, present = 0, done = 0;
		for (Question q : LongMemEval.load(data)) {
			if (!types.isEmpty() && !types.contains(q.type())) {
				continue;
			}
			if (limit > 0 && done++ >= limit) {
				break;
			}
			for (String[] p : Ingestor.prompts(q, granularity)) {
				seen++;
				String user = ModelProposer.userMessage(p[0], p[1]);
				var reply = cache.get(ProposalCache.key(from, spec, user));
				if (reply.isEmpty()) {
					missing++;
					continue;
				}
				String toKey = ProposalCache.key(to, spec, user);
				if (cache.get(toKey).isPresent()) {
					present++;
					continue;
				}
				cache.put(toKey, reply.get());
				copied++;
			}
		}
		System.out.println("rekey " + from + " -> " + to + ": prompts " + seen + ", copied " + copied
				+ ", already present " + present + ", not cached under the old id " + missing);
	}

	// ── judge ───────────────────────────────────────────────────────────

	static void judge(Map<String, String> o) throws Exception {
		Path run = Path.of(require(o, "run"));
		Judge judge = new Judge(ModelProvider.resolve(require(o, "judge"), o.get("api-key-env")));
		List<JsonNode> hyps = readLines(run.resolve("hypotheses.jsonl"));
		try (BufferedWriter w = Files.newBufferedWriter(run.resolve("judged.jsonl"), StandardCharsets.UTF_8)) {
			for (JsonNode h : hyps) {
				boolean correct = judge.correct(h.get("question_id").asText(), h.get("question_type").asText(),
						h.get("question").asText(), h.path("answer").asText(), h.path("hypothesis").asText());
				var line = new LinkedHashMap<String, Object>();
				line.put("question_id", h.get("question_id").asText());
				line.put("question_type", h.get("question_type").asText());
				line.put("label", correct);
				line.put("judge", judge.model());
				line.put("cause", Diagnose.classify(correct, h.get("question_id").asText().endsWith("_abs"),
						h.path("answer").asText(), h.path("context").asText()).name());
				line.put("refusal", Diagnose.looksLikeRefusal(h.path("hypothesis").asText()));
				w.write(LINE.writeValueAsString(line));
				w.newLine();
			}
		}
		metrics(Map.of("run", run.toString()));
	}

	// ── metrics ─────────────────────────────────────────────────────────

	static void metrics(Map<String, String> o) throws IOException {
		Path run = Path.of(require(o, "run"));
		var report = new LinkedHashMap<String, Object>();
		List<JsonNode> retrieval = readLines(run.resolve("retrieval.jsonl"));
		var types = new ArrayList<String>();
		var r5 = new ArrayList<Double>();
		var r10 = new ArrayList<Double>();
		var recallMs = new ArrayList<Long>();
		var ingestMs = new ArrayList<Long>();
		var ctx = new ArrayList<Double>();
		var factsPerQ = new ArrayList<Double>();
		var structured = new LinkedHashMap<String, Integer>();
		for (JsonNode n : retrieval) {
			structured.merge(n.path("structured").asText("n/a"), 1, Integer::sum);
			if (n.get("abstention").asBoolean()) {
				continue; // LongMemEval excludes the 30 abstention items from retrieval metrics
			}
			types.add(n.get("question_type").asText());
			r5.add(n.get("recall_at_5").asDouble());
			r10.add(n.get("recall_at_10").asDouble());
			recallMs.add(n.get("recall_ms").asLong());
			ingestMs.add(n.get("ingest_ms").asLong());
			ctx.add(n.get("context_tokens").asDouble());
			factsPerQ.add(n.path("facts").asDouble(0));
		}
		var ret = new LinkedHashMap<String, Object>();
		ret.put("questions", r5.size());
		ret.put("session_recall_at_5", Metrics.mean(r5).toString());
		ret.put("session_recall_at_10", Metrics.mean(r10).toString());
		var byType = new LinkedHashMap<String, Object>();
		Metrics.byKey(types, r5).forEach((t, xs) -> byType.put(t, Metrics.mean(xs).toString()));
		ret.put("recall_at_5_by_type", byType);
		ret.put("structured_states", structured);
		ret.put("facts_per_question_mean", Metrics.mean(factsPerQ).value());
		ret.put("recall_ms_p50", Metrics.percentile(recallMs, 0.5));
		ret.put("recall_ms_p95", Metrics.percentile(recallMs, 0.95));
		ret.put("ingest_ms_p50", Metrics.percentile(ingestMs, 0.5));
		ret.put("context_tokens_mean", Metrics.mean(ctx).value());
		report.put("retrieval", ret);

		Path judged = run.resolve("judged.jsonl");
		if (Files.exists(judged)) {
			var qa = new LinkedHashMap<String, Object>();
			var labels = new ArrayList<Boolean>();
			var qTypes = new ArrayList<String>();
			var absLabels = new ArrayList<Boolean>();
			int refusalsOnAnswerable = 0, answerable = 0;
			var causes = new LinkedHashMap<String, Integer>();
			for (JsonNode n : readLines(judged)) {
				boolean abs = n.get("question_id").asText().endsWith("_abs");
				boolean label = n.get("label").asBoolean();
				if (abs) {
					absLabels.add(label);
				} else {
					labels.add(label);
					qTypes.add(n.get("question_type").asText());
					answerable++;
					if (n.path("refusal").asBoolean()) {
						refusalsOnAnswerable++;
					}
				}
				causes.merge(n.path("cause").asText(), 1, Integer::sum);
			}
			qa.put("accuracy", Metrics.accuracy(labels).toString());
			var accByType = new LinkedHashMap<String, Object>();
			Metrics.byKey(qTypes, labels).forEach((t, xs) -> accByType.put(t, Metrics.accuracy(xs).toString()));
			qa.put("accuracy_by_type", accByType);
			qa.put("abstention_recall", Metrics.accuracy(absLabels).toString());
			qa.put("refusals_on_answerable", answerable == 0 ? null : (double) refusalsOnAnswerable / answerable);
			qa.put("causes", causes);
			report.put("qa", qa);
		}
		String text = JSON.writeValueAsString(report);
		Files.writeString(run.resolve("metrics.json"), text, StandardCharsets.UTF_8);
		System.out.println(text);
	}

	// ── compare ─────────────────────────────────────────────────────────

	static void compare(Map<String, String> o) throws IOException {
		Path a = Path.of(require(o, "a"));
		Path b = Path.of(require(o, "b"));
		var out = new LinkedHashMap<String, Object>();
		out.put("a", a.toString());
		out.put("b", b.toString());
		out.put("retrieval_recall_at_5_diff", Metrics.pairedDifference(labels(a, "retrieval.jsonl", "recall_at_5"),
				labels(b, "retrieval.jsonl", "recall_at_5")).toString());
		out.put("retrieval_recall_at_10_diff", Metrics.pairedDifference(labels(a, "retrieval.jsonl", "recall_at_10"),
				labels(b, "retrieval.jsonl", "recall_at_10")).toString());
		if (Files.exists(a.resolve("judged.jsonl")) && Files.exists(b.resolve("judged.jsonl"))) {
			out.put("accuracy_diff",
					Metrics.pairedDifference(labels(a, "judged.jsonl", "label"), labels(b, "judged.jsonl", "label"))
							.toString());
		}
		System.out.println(JSON.writeValueAsString(out));
	}

	/** Per-question boolean from a JSONL file; numeric fields count as true when 1.0. */
	private static Map<String, Boolean> labels(Path run, String file, String field) throws IOException {
		var out = new LinkedHashMap<String, Boolean>();
		for (JsonNode n : readLines(run.resolve(file))) {
			if (n.get("question_id").asText().endsWith("_abs") && "retrieval.jsonl".equals(file)) {
				continue; // no answer sessions: retrieval recall is undefined for abstention items
			}
			JsonNode v = n.get(field);
			out.put(n.get("question_id").asText(), v.isBoolean() ? v.asBoolean() : v.asDouble() >= 1.0);
		}
		return out;
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static Set<String> done(Path out) throws IOException {
		var ids = new HashSet<String>();
		for (JsonNode n : readLines(out.resolve("retrieval.jsonl"))) {
			ids.add(n.get("question_id").asText());
		}
		return ids;
	}

	/**
	 * One JSON object per line is what the harness writes; a reformatted stream of pretty-printed objects reads too.
	 */
	static List<JsonNode> readLines(Path file) throws IOException {
		var out = new ArrayList<JsonNode>();
		if (!Files.exists(file)) {
			return out;
		}
		try (var it = LINE.readerFor(JsonNode.class).readValues(file.toFile())) {
			while (it.hasNext()) {
				out.add((JsonNode) it.next());
			}
		}
		return out;
	}

	static Map<String, String> parse(String[] args) {
		var out = new LinkedHashMap<String, String>();
		for (int i = 1; i < args.length; i++) {
			if (args[i].startsWith("--")) {
				String key = args[i].substring(2);
				String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
				out.put(key, value);
			}
		}
		return out;
	}

	/**
	 * The shared in-process embedder for a run, or null: {@code --embed-model
	 *
	<dir>
	 *  --ort-library <lib>}.
	 */
	static Embedder embedder(Map<String, String> o) throws IOException {
		if (!o.containsKey("embed-model")) {
			return null;
		}
		Path dir = Path.of(o.get("embed-model"));
		return new Embedder(Path.of(require(o, "ort-library")), dir, dir.getFileName().toString());
	}

	/** A bench engine: no soft limit on observation length, the owner is "the user". */
	static Engine.Options options(Path home) {
		return Engine.Options.of(home, "bench").withSoftLimit(Integer.MAX_VALUE).withOwner("the user");
	}

	static String require(Map<String, String> o, String key) {
		String v = o.get(key);
		if (v == null) {
			throw new IllegalArgumentException("--" + key + " is required");
		}
		return v;
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// SQLite on Windows may hold the WAL briefly; a leftover temp dir is harmless.
				}
			});
		}
	}

	private static void usage() {
		System.out.println(
				"""
						bench run     --data <longmemeval.json> --out <dir> [--granularity session|turn] [--k 10] [--budget 4000]
						              [--proposer provider:model] [--reader provider:model] [--api-key-env NAME] [--limit N] [--types a,b]
						              [--workers 8] [--cache cache/proposals] [--refresh-failed] [--reasoning-effort none|low|medium|high]
						bench judge   --run <dir> --judge provider:model [--api-key-env NAME]
						bench metrics --run <dir>
						bench compare --a <dir> --b <dir>
						bench usage   --script usage/scenarios.json --server <runner.jar|binary> --assistant provider:model
						              [--judge provider:model] [--api-key-env NAME] [--out <dir>] [--limit N] [--only id] [--embed on|off]
						providers: anthropic:<model>  openai:<model>  lmstudio:<model>  ollama:<model>  openai-compatible:<model>@<url>""");
	}
}
