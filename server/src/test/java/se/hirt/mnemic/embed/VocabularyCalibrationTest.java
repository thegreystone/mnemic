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
package se.hirt.mnemic.embed;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.Predicate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints how the real model scores bare names against the seed vocabulary, so the thresholds in
 * {@code PredicateRegistry} can be read off real numbers. Runs only with {@code MNEMIC_ORT_LIBRARY} and
 * {@code MNEMIC_EMBED_MODEL} set; asserts nothing, since the numbers are the point.
 */
class VocabularyCalibrationTest {

	@Test
	void scoresOfBareNamesAgainstTheSeed() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		try (Embedder emb = new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
				Engine e = TestHomes.engine("calibration")) {
			List<Predicate> seed = e.predicates().all();
			float[][] vectors = new float[seed.size()][];
			for (int i = 0; i < seed.size(); i++) {
				vectors[i] = emb.embed(text(seed.get(i)));
			}
			for (String name : List.of("coaches", "mentors", "employed_by", "is_employed_by", "resides_in", "dwells_in",
					"visited", "consults_for", "shares_a_flat_with", "godparent_of", "hates", "married_to",
					"owns_shares_in", "passport_number", "studied_at", "father_of", "reports_to", "manages")) {
				float[] q = emb.embed(name.replace('_', ' ') + ". " + String.join(", ", Predicate.lexiconOf(name)));
				String best = null;
				float bestScore = 0, second = 0;
				for (int i = 0; i < seed.size(); i++) {
					float s = Embedding.dot(q, vectors[i]);
					if (s > bestScore) {
						second = bestScore;
						bestScore = s;
						best = seed.get(i).name();
					} else if (s > second) {
						second = s;
					}
				}
				float lead = bestScore - second;
				String verdict = bestScore >= 0.88f && lead >= 0.04f ? "semantic"
						: bestScore >= 0.86f && lead >= 0.02f ? "ambiguous" : "-";
				System.out.printf("%-20s -> %-12s %.3f lead %.3f  %s%n", name, best, bestScore, lead, verdict);
			}
		}
	}

	/** German names against a German store, whose seed predicates carry German cue words beside the English ones. */
	@Test
	void scoresOfGermanNamesAgainstAGermanStore() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		try (Embedder emb = new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
				Engine e = TestHomes.engine(TestHomes.fresh("calibration-de"), Lang.DE)) {
			List<Predicate> seed = e.predicates().all();
			float[][] vectors = new float[seed.size()][];
			for (int i = 0; i < seed.size(); i++) {
				vectors[i] = emb.embed(text(seed.get(i)));
			}
			for (String name : List.of("wohnt_in", "arbeitet_bei", "angestellt_bei", "verheiratet_mit", "hasst",
					"betreut", "besucht", "berichtet_an", "vater_von", "besitzt_anteile_an", "studierte_an", "kennt",
					"leitet", "teilt_wohnung_mit", "passnummer", "coacht")) {
				float[] q = emb.embed(name.replace('_', ' ') + ". " + String.join(", ", Predicate.lexiconOf(name)));
				String best = null;
				float bestScore = 0, second = 0;
				for (int i = 0; i < seed.size(); i++) {
					float s = Embedding.dot(q, vectors[i]);
					if (s > bestScore) {
						second = bestScore;
						bestScore = s;
						best = seed.get(i).name();
					} else if (s > second) {
						second = s;
					}
				}
				float lead = bestScore - second;
				String verdict = bestScore >= 0.88f && lead >= 0.04f ? "semantic"
						: bestScore >= 0.86f && lead >= 0.02f ? "ambiguous" : "-";
				System.out.printf("de %-20s -> %-12s %.3f lead %.3f  %s%n", name, best, bestScore, lead, verdict);
			}
		}
	}

	private static String text(Predicate p) {
		var sb = new StringBuilder(p.name().replace('_', ' '));
		if (p.description() != null) {
			sb.append(". ").append(p.description());
		}
		if (!p.lexicon().isEmpty()) {
			sb.append(". ").append(String.join(", ", p.lexicon()));
		}
		return sb.toString();
	}
}
