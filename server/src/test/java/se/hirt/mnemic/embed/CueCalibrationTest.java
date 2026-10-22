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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import se.hirt.mnemic.Engine;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry;

/**
 * Prints how the real model scores short question phrases, in several languages and including noise, against the seed
 * vocabulary, so the thresholds of semantic cues can be read off real numbers. Runs only with
 * {@code MNEMIC_ORT_LIBRARY} and {@code MNEMIC_EMBED_MODEL} set; asserts nothing.
 */
class CueCalibrationTest {

	@Test
	void scoresOfQuestionPhrasesAgainstTheSeed() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		try (Embedder emb = new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
				Engine e = TestHomes.engine("cue-calibration")) {
			List<Predicate> seed = e.predicates().all();
			float[][] vectors = new float[seed.size()][];
			for (int i = 0; i < seed.size(); i++) {
				vectors[i] = emb.embed(text(seed.get(i)));
			}
			List<String> phrases = List.of(
					// English, expected hits
					"phone number", "mobile", "cellphone", "employer", "where does he live", "parents", "siblings",
					"wife", "married", "boss", "owns", "cousin", "grandmother",
					// German
					"Handynummer", "Telefonnummer", "Arbeitgeber", "wohnt", "Eltern", "Geschwister", "Ehefrau",
					"verheiratet", "besitzt", "Wohnort", "Kinder",
					// Swedish
					"telefonnummer", "mobilnummer", "arbetsgivare", "bor", "föräldrar", "syskon", "fru", "gift med",
					"äger", "kusin", "mormor", "chef", "jobbar",
					// noise: question words, verbs, unrelated nouns
					"vad", "har", "för", "wie", "ist", "was", "what", "which", "when", "today", "weather", "car",
					"project", "status", "report", "meeting", "birthday", "passport", "address", "email",
					// lowercase, as the tokeniser hands them over, and the windows of the noise questions
					"handynummer", "wie lautet", "lautet", "weather like", "like today", "like", "wie war", "war das",
					"das meeting", "meeting gestern", "gestern", "war", "das", "status of", "vad har", "har för",
					"för telefonnummer");
			for (String phrase : phrases) {
				float[] q = emb.embed(phrase);
				String best = null, secondName = null;
				float bestScore = 0, second = 0;
				for (int i = 0; i < seed.size(); i++) {
					float s = Embedding.dot(q, vectors[i]);
					if (s > bestScore) {
						second = bestScore;
						secondName = best;
						bestScore = s;
						best = seed.get(i).name();
					} else if (s > second) {
						second = s;
						secondName = seed.get(i).name();
					}
				}
				System.out.printf("cue %-20s -> %-16s %.3f lead %.3f (next %s) %s%n", phrase, best, bestScore,
						bestScore - second, secondName,
						PredicateRegistry.semanticCue(bestScore, bestScore - second) ? "cue" : "-");
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
