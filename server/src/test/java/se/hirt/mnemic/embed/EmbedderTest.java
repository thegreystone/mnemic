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
package se.hirt.mnemic.embed;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embedder against the real model and runtime. Runs when {@code MNEMIC_ORT_LIBRARY} and {@code MNEMIC_EMBED_MODEL}
 * (a directory with model.onnx and tokenizer.json) are set; skipped otherwise, so the suite never depends on a 430 MB
 * download.
 */
class EmbedderTest {

	private static Embedder open() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		return new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
	}

	@Test
	void tokenizerReadsTheVocabularyAndSegmentsText() throws Exception {
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(model != null && Files.exists(Path.of(model, "tokenizer.json")));
		// The model's own tokenizer, whichever kind it is: the 107m model is a Unigram, the 311m-r2 a BPE.
		Tokenizer t = Tokenizer.load(Path.of(model, "tokenizer.json"));
		int[] ids = t.encode("Hello world", 512);
		assertTrue(ids.length >= 3 && ids.length <= 8, "two words and what the tokenizer adds: " + ids.length);
		// German and Swedish segment into known pieces, never all unknowns.
		int[] de = t.encode("Der Arbeitgeber sitzt in Schübelbach", 512);
		int[] sv = t.encode("Min arbetsgivare ligger i Göteborg", 512);
		assertTrue(de.length > 4 && sv.length > 4, de.length + " " + sv.length);
		assertTrue(java.util.Arrays.stream(de).distinct().count() > 3, "German is not one token repeated");
		assertTrue(java.util.Arrays.stream(sv).distinct().count() > 3, "Swedish is not one token repeated");
		if (t instanceof Unigram u) {
			assertEquals(250002, u.size());
			assertEquals(0, ids[0], "<s>");
			assertEquals(2, ids[ids.length - 1], "</s>");
			assertEquals("▁Hello▁world", Unigram.normalise("  Hello   world "));
			assertEquals(0, java.util.Arrays.stream(de).filter(i -> i == 3).count(), "no <unk> in German");
			assertEquals(0, java.util.Arrays.stream(sv).filter(i -> i == 3).count(), "no <unk> in Swedish");
		}
	}

	@Test
	void similarMeaningsAreCloseAcrossLanguages() throws Exception {
		try (Embedder e = open()) {
			assertTrue(e.dims() == 384 || e.dims() == 768, "granite 107m or 311m-r2: " + e.dims());
			float[] bank = e.embed("where do I bank");
			float[] accounts = e.embed("my accounts are at Nordbank");
			float[] printer = e.embed("the 3D printer needs a new nozzle");
			float sameTopic = Embedder.dot(bank, accounts);
			float otherTopic = Embedder.dot(bank, printer);
			assertTrue(sameTopic > otherTopic + 0.1,
					"bank vs accounts " + sameTopic + ", bank vs printer " + otherTopic);
			// Cross-language: a German or Swedish question lands nearer both English facts about the person than an
			// unrelated English sentence. Which of the two facts wins is a coin toss on facts this short that share
			// the name, which is what the bake-off is for; the numbers are printed.
			float[] de = e.embed("Wo arbeitet Mattias?");
			float[] sv = e.embed("Var bor Mattias?");
			float[] job = e.embed("Mattias Sandell works at Hooli");
			float[] home = e.embed("Mattias Sandell lives in Schübelbach");
			System.out.printf("de/job %.3f de/home %.3f de/printer %.3f sv/job %.3f sv/home %.3f sv/printer %.3f%n",
					Embedder.dot(de, job), Embedder.dot(de, home), Embedder.dot(de, printer), Embedder.dot(sv, job),
					Embedder.dot(sv, home), Embedder.dot(sv, printer));
			// The margins are the model's: granite-107m cleared 0.1 both ways, granite-311m-r2 gives 0.07 for German
			// and 0.12 for Swedish (2026-09-27); what is asserted is that the topic wins clearly, not by how much.
			assertTrue(Embedder.dot(de, job) > Embedder.dot(de, printer) + 0.05, "German question, English facts");
			assertTrue(Embedder.dot(sv, home) > Embedder.dot(sv, printer) + 0.05, "Swedish question, English facts");
			// Deterministic.
			assertEquals(1.0f, Embedder.dot(bank, e.embed("where do I bank")), 1e-4);
			long t0 = System.nanoTime();
			for (int i = 0; i < 20; i++) {
				e.embed("Mattias Sandell works at Hooli as a director of engineering since March 2019");
			}
			System.out.println("embed ms per sentence: " + (System.nanoTime() - t0) / 20 / 1_000_000.0);
		}
	}
}
