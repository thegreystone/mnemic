/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
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
package se.hirt.mnemic.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.embed.Embedder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner alias measured on the bake-off's sample set (bench/bakeoff/personal-notes.json, owner "Alex"): recall at 1
 * per language with the plain question (plain), with the better of the plain and the rewritten question per item
 * (q-max), with the two question vectors averaged (q-avg), and with the question as asked against texts about the owner
 * embedded a second time in the first person (p-1st), the item taking its better vector as a chunked item does. Runs
 * with MNEMIC_ORT_LIBRARY and MNEMIC_EMBED_MODEL; MNEMIC_BAKEOFF_MODELS adds every model directory under it.
 */
class OwnerAliasSampleTest {

	private record Item(String text, List<String> q) {
	}

	@Test
	void theOwnerAliasOnTheRenderingSideRaisesRecallOnTheSampleSet() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Path samples = Path.of("..", "bench", "bakeoff", "personal-notes.json");
		Assumptions.assumeTrue(lib != null && model != null && Files.exists(samples),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		JsonNode root = new ObjectMapper().readTree(Files.readString(samples));
		var languages = new ArrayList<String>();
		root.path("languages").forEach(n -> languages.add(n.asText()));
		var items = new ArrayList<Item>();
		for (JsonNode n : root.path("items")) {
			var q = new ArrayList<String>();
			for (String lang : languages) {
				q.add(n.path("q").path(lang).asText());
			}
			items.add(new Item(n.path("text").asText(), q));
		}
		var dirs = new ArrayList<Path>();
		dirs.add(Path.of(model));
		String more = System.getenv("MNEMIC_BAKEOFF_MODELS");
		if (more != null && Files.isDirectory(Path.of(more))) {
			try (var s = Files.list(Path.of(more))) {
				s.filter(d -> Files.exists(d.resolve("model.onnx")) && !d.getFileName().toString().endsWith("-int8"))
						.sorted().forEach(dirs::add);
			}
		}
		System.out.println("owner alias on " + items.size() + " items, languages " + languages);
		double plainTotal = 0, expandedTotal = 0;
		for (Path dir : dirs) {
			try (Embedder e = new Embedder(Path.of(lib), dir, dir.getFileName().toString())) {
				float[][] vectors = new float[items.size()][];
				for (int i = 0; i < items.size(); i++) {
					vectors[i] = e.embed(items.get(i).text());
				}
				// The first-person variant of every text that names the owner, as the store would embed it.
				float[][] firstPerson = new float[items.size()][];
				int variants = 0;
				for (int i = 0; i < items.size(); i++) {
					String fp = OwnerAlias.firstPerson(items.get(i).text(), "Alex", se.hirt.mnemic.knowledge.Lang.EN);
					if (fp != null) {
						firstPerson[i] = e.embed(fp);
						variants++;
					}
				}
				var line = new StringBuilder(String.format(Locale.ROOT, "%-40s %2d texts with a first-person variant |",
						dir.getFileName(), variants));
				double[] avg = new double[4];
				for (int l = 0; l < languages.size(); l++) {
					int[] top = new int[4];
					for (int i = 0; i < items.size(); i++) {
						String q = items.get(i).q().get(l);
						float[] qv = e.embedQuery(q);
						String alt = OwnerAlias.withOwner(q, "Alex");
						float[] av = alt == null ? qv : e.embedQuery(alt);
						float[] mean = new float[qv.length];
						double norm = 0;
						for (int d = 0; d < qv.length; d++) {
							mean[d] = (qv[d] + av[d]) / 2;
							norm += mean[d] * mean[d];
						}
						for (int d = 0; d < qv.length; d++) {
							mean[d] /= (float) Math.sqrt(norm);
						}
						float[] own = score(i, qv, av, mean, vectors, firstPerson);
						boolean[] first = {true, true, true, true};
						for (int j = 0; j < items.size(); j++) {
							if (j == i) {
								continue;
							}
							float[] other = score(j, qv, av, mean, vectors, firstPerson);
							for (int k = 0; k < 4; k++) {
								if (other[k] > own[k]) {
									first[k] = false;
								}
							}
						}
						for (int k = 0; k < 4; k++) {
							top[k] += first[k] ? 1 : 0;
						}
					}
					line.append(String.format(Locale.ROOT, " %s plain %.3f q-max %.3f q-avg %.3f p-1st %.3f |",
							languages.get(l), top[0] / (double) items.size(), top[1] / (double) items.size(),
							top[2] / (double) items.size(), top[3] / (double) items.size()));
					for (int k = 0; k < 4; k++) {
						avg[k] += top[k] / (double) items.size() / languages.size();
					}
				}
				line.append(String.format(Locale.ROOT, " avg plain %.3f q-max %.3f q-avg %.3f p-1st %.3f", avg[0],
						avg[1], avg[2], avg[3]));
				System.out.println(line);
				if (dir.equals(Path.of(model))) {
					plainTotal = avg[0];
					expandedTotal = avg[3];
				}
			}
		}
		assertTrue(expandedTotal >= plainTotal,
				"the alias must not lower recall: " + plainTotal + " -> " + expandedTotal);
	}

	/** The four strategies' similarity of a question to item {@code j}: plain, q-max, q-avg, p-1st. */
	private static float[] score(
		int j, float[] qv, float[] av, float[] mean, float[][] vectors, float[][] firstPerson) {
		float plain = Embedder.dot(qv, vectors[j]);
		float alt = Embedder.dot(av, vectors[j]);
		float fp = firstPerson[j] == null ? plain : Math.max(plain, Embedder.dot(qv, firstPerson[j]));
		return new float[] {plain, Math.max(plain, alt), Embedder.dot(mean, vectors[j]), fp};
	}
}
