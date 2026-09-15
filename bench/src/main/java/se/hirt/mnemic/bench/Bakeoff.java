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
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import se.hirt.mnemic.embed.Embedder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The embedder bake-off: every candidate model over the same personal-note samples, three languages of questions
 * against English texts, through the same tokenizer and runtime the server uses. Reports recall at 1 and 3 and the mean
 * reciprocal rank per language, the share of group questions that rank their own item above the others sharing its
 * name, the mean similarity of a question to its own item (how far a language sits from the texts), and the cost:
 * dimensions, file size, load time, milliseconds per sentence.
 *
 * <pre>
 * bakeoff --models dir1,dir2,... --ort-library lib --samples bench/bakeoff/personal-notes.json --out results.json
 * </pre>
 *
 * A directory's name is the model's id; a suffix such as {@code -int8} names a variant of the same model and keeps its
 * pooling and prefixes.
 */
final class Bakeoff {

	private record Item(String id, String text, String group, Map<String, String> q) {
	}

	/** The sample set: items with a question per language, and how many items share each group name. */
	private record Samples(List<String> languages, List<Item> items, Map<String, Integer> groupSize) {
	}

	/** One language's figures, exact for the overall average, and its rounded result row. */
	private record LangScore(double r1, double r3, double mrr, int groupOk, int groupN, Map<String, Object> row) {
	}

	private Bakeoff() {
	}

	static void run(Map<String, String> o) throws Exception {
		Path samplesFile = Path.of(o.getOrDefault("samples", "bench/bakeoff/personal-notes.json"));
		Path library = Path.of(o.get("ort-library"));
		List<Path> models = Arrays.stream(o.get("models").split(",")).map(String::trim).map(Path::of).toList();
		int repeat = Integer.parseInt(o.getOrDefault("repeat", "3"));
		ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		Samples samples = load(json.readTree(Files.readString(samplesFile)));
		var results = new ArrayList<Map<String, Object>>();
		System.out.printf(
				Locale.ROOT, "%d items, %d in groups, languages %s%n%n", samples.items().size(), samples.items()
						.stream().filter(i -> i.group() != null && samples.groupSize().get(i.group()) > 1).count(),
				samples.languages());
		for (Path dir : models) {
			Map<String, Object> row = evaluate(dir, library, samples, repeat);
			results.add(row);
			System.out.println(line(row, samples.languages()));
		}
		if (o.containsKey("out")) {
			Path out = Path.of(o.get("out"));
			Files.createDirectories(out.toAbsolutePath().getParent());
			var doc = new LinkedHashMap<String, Object>();
			doc.put("samples", samplesFile.toString());
			doc.put("items", samples.items().size());
			doc.put("languages", samples.languages());
			doc.put("results", results);
			Files.writeString(out, json.writeValueAsString(doc));
			System.out.println("written " + out);
		}
		System.out.println();
		System.out.println(table(results, samples.languages()));
	}

	private static Samples load(JsonNode root) {
		var languages = new ArrayList<String>();
		root.path("languages").forEach(n -> languages.add(n.asText()));
		var items = new ArrayList<Item>();
		for (JsonNode n : root.path("items")) {
			var q = new LinkedHashMap<String, String>();
			n.path("q").fields().forEachRemaining(e -> q.put(e.getKey(), e.getValue().asText()));
			items.add(new Item(n.path("id").asText(), n.path("text").asText(),
					n.hasNonNull("group") ? n.path("group").asText() : null, q));
		}
		var groupSize = new HashMap<String, Integer>();
		for (Item it : items) {
			if (it.group() != null) {
				groupSize.merge(it.group(), 1, Integer::sum);
			}
		}
		return new Samples(languages, items, groupSize);
	}

	/** One model directory as a result row: cost, then recall per language and overall; an error row on failure. */
	private static Map<String, Object> evaluate(Path dir, Path library, Samples samples, int repeat) {
		String name = dir.getFileName().toString();
		String base = name.replaceAll("-(int8|uint8|fp16|q4|quant\\w*|O\\d)$", "");
		var row = new LinkedHashMap<String, Object>();
		row.put("model", name);
		try {
			long t0 = System.nanoTime();
			Embedder.Spec spec = Embedder.Spec.forModel(base);
			try (Embedder e = new Embedder(library, dir, name, spec)) {
				row.put("load_ms", (System.nanoTime() - t0) / 1_000_000);
				row.put("dims", e.dims());
				row.put("pooling", spec.pooling());
				long bytes = Files.size(dir.resolve("model.onnx"));
				if (Files.exists(dir.resolve("model.onnx_data"))) {
					bytes += Files.size(dir.resolve("model.onnx_data")); // weights kept beside the graph
				}
				row.put("file_mb", bytes >> 20);
				List<Item> items = samples.items();
				float[][] vectors = new float[items.size()][];
				for (int i = 0; i < items.size(); i++) {
					vectors[i] = e.embed(items.get(i).text());
				}
				var times = new ArrayList<Long>();
				for (int r = 0; r < repeat; r++) {
					for (Item it : items) {
						long s = System.nanoTime();
						e.embed(it.text());
						times.add(System.nanoTime() - s);
					}
				}
				times.sort(Long::compare);
				row.put("ms_per_sentence_p50", times.get(times.size() / 2) / 1e6);
				row.put("ms_per_sentence_mean", times.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6);
				var perLang = new LinkedHashMap<String, Object>();
				double allR1 = 0, allR3 = 0, allMrr = 0, allGroup = 0;
				int allGroupN = 0;
				for (String lang : samples.languages()) {
					LangScore l = score(e, samples, lang, vectors);
					perLang.put(lang, l.row());
					allR1 += l.r1();
					allR3 += l.r3();
					allMrr += l.mrr();
					allGroup += l.groupOk();
					allGroupN += l.groupN();
				}
				int langs = samples.languages().size();
				row.put("languages", perLang);
				row.put("recall_at_1", round(allR1 / langs));
				row.put("recall_at_3", round(allR3 / langs));
				row.put("mrr", round(allMrr / langs));
				row.put("group_accuracy", allGroupN == 0 ? null : round(allGroup / allGroupN));
			}
		} catch (Exception ex) {
			row.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
		}
		return row;
	}

	/** The questions of one language against the passage vectors. */
	private static LangScore score(Embedder e, Samples samples, String lang, float[][] vectors) {
		List<Item> items = samples.items();
		int r1 = 0, r3 = 0, groupOk = 0, groupN = 0;
		double mrr = 0, sim = 0;
		var misses = new ArrayList<String>();
		for (int i = 0; i < items.size(); i++) {
			Item it = items.get(i);
			String q = it.q().get(lang);
			if (q == null) {
				continue;
			}
			float[] qv = e.embedQuery(q);
			int rank = 1;
			float own = Embedder.dot(qv, vectors[i]);
			int best = i;
			float bestScore = own;
			boolean groupBeaten = false;
			for (int j = 0; j < items.size(); j++) {
				if (j == i) {
					continue;
				}
				float s = Embedder.dot(qv, vectors[j]);
				if (s > own) {
					rank++;
					if (it.group() != null && it.group().equals(items.get(j).group())) {
						groupBeaten = true;
					}
				}
				if (s > bestScore) {
					bestScore = s;
					best = j;
				}
			}
			if (rank == 1) {
				r1++;
			} else {
				misses.add(it.id() + "->" + items.get(best).id() + "@" + rank);
			}
			if (rank <= 3) {
				r3++;
			}
			mrr += 1.0 / rank;
			sim += own;
			if (it.group() != null && samples.groupSize().get(it.group()) > 1) {
				groupN++;
				if (!groupBeaten) {
					groupOk++;
				}
			}
		}
		int n = items.size();
		var l = new LinkedHashMap<String, Object>();
		l.put("recall_at_1", round(r1 / (double) n));
		l.put("recall_at_3", round(r3 / (double) n));
		l.put("mrr", round(mrr / n));
		l.put("group_accuracy", groupN == 0 ? null : round(groupOk / (double) groupN));
		l.put("mean_similarity_to_own", round(sim / n));
		l.put("misses", misses);
		return new LangScore(r1 / (double) n, r3 / (double) n, mrr / n, groupOk, groupN, l);
	}

	private static double round(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	@SuppressWarnings("unchecked")
	private static String line(Map<String, Object> row, List<String> languages) {
		if (row.containsKey("error")) {
			return row.get("model") + ": ERROR " + row.get("error");
		}
		var sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%-42s dims %4s %5s MB  %6.2f ms  R@1 %.3f R@3 %.3f MRR %.3f group %s |",
				row.get("model"), row.get("dims"), row.get("file_mb"), (Double) row.get("ms_per_sentence_p50"),
				(Double) row.get("recall_at_1"), (Double) row.get("recall_at_3"), (Double) row.get("mrr"),
				row.get("group_accuracy")));
		var per = (Map<String, Map<String, Object>>) row.get("languages");
		for (String lang : languages) {
			var l = per.get(lang);
			sb.append(String.format(Locale.ROOT, " %s R@1 %.3f MRR %.3f sim %.2f", lang, (Double) l.get("recall_at_1"),
					(Double) l.get("mrr"), (Double) l.get("mean_similarity_to_own")));
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	static String table(List<Map<String, Object>> results, List<String> languages) {
		var sb = new StringBuilder();
		sb.append("| Model | Dims | MB | ms/sentence | R@1 | R@3 | MRR | Group |");
		for (String lang : languages) {
			sb.append(" R@1 ").append(lang).append(" | MRR ").append(lang).append(" |");
		}
		sb.append("\n|---|---|---|---|---|---|---|---|");
		for (String ignored : languages) {
			sb.append("---|---|");
		}
		sb.append("\n");
		for (var row : results) {
			if (row.containsKey("error")) {
				sb.append("| ").append(row.get("model")).append(" | error: ").append(row.get("error")).append(" |\n");
				continue;
			}
			sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %.1f | %.3f | %.3f | %.3f | %s |", row.get("model"),
					row.get("dims"), row.get("file_mb"), (Double) row.get("ms_per_sentence_p50"),
					(Double) row.get("recall_at_1"), (Double) row.get("recall_at_3"), (Double) row.get("mrr"),
					row.get("group_accuracy")));
			var per = (Map<String, Map<String, Object>>) row.get("languages");
			for (String lang : languages) {
				var l = per.get(lang);
				sb.append(String.format(Locale.ROOT, " %.3f | %.3f |", (Double) l.get("recall_at_1"),
						(Double) l.get("mrr")));
			}
			sb.append("\n");
		}
		return sb.toString();
	}
}
