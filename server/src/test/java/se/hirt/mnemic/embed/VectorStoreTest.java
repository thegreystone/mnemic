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

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.remember;

/** The vector table and its scan, with synthetic vectors: no model needed. */
class VectorStoreTest {

	@Test
	void nearestNeighboursByDotProduct() {
		try (Engine e = engine("vec-store")) {
			VectorStore v = e.vectors();
			v.put(VectorStore.OBSERVATION, 1, "m", new float[] {1, 0, 0});
			v.put(VectorStore.OBSERVATION, 2, "m", new float[] {0, 1, 0});
			v.put(VectorStore.FACT, 3, "m", new float[] {0.9f, 0.1f, 0});
			v.put(VectorStore.FACT, 4, "other", new float[] {1, 0, 0});
			List<VectorStore.Hit> hits = v.search("m", new float[] {1, 0, 0}, 2);
			assertEquals(2, hits.size());
			assertEquals(1, hits.get(0).id());
			assertEquals(VectorStore.OBSERVATION, hits.get(0).kind());
			assertEquals(3, hits.get(1).id());
			assertEquals(3, v.count("m"), "vectors of another model never mix");
			assertTrue(v.has(VectorStore.FACT, 3, "m"));
			assertFalse(v.has(VectorStore.FACT, 3, "other"));
			// Replacing keeps one row per item and model.
			v.put(VectorStore.OBSERVATION, 1, "m", new float[] {0, 0, 1});
			assertEquals(3, v.count("m"));
			assertEquals(2, v.search("m", new float[] {0, 1, 0}, 1).getFirst().id());
		}
	}

	@Test
	void chunksScoreByTheirBestAndCountAsOneItem() {
		try (Engine e = engine("vec-chunks")) {
			VectorStore v = e.vectors();
			v.putChunks(VectorStore.OBSERVATION, 1, "m", List.of(new float[] {0, 1, 0}, new float[] {0.9f, 0.1f, 0}));
			v.put(VectorStore.OBSERVATION, 2, "m", new float[] {0.8f, 0.2f, 0});
			List<VectorStore.Hit> hits = v.search("m", new float[] {1, 0, 0}, 5);
			assertEquals(2, hits.size(), "one hit per item, not per chunk");
			assertEquals(1, hits.get(0).id(), "the item's best chunk decides");
			assertEquals(3, v.count("m"), "rows are chunks");
			v.putChunks(VectorStore.OBSERVATION, 1, "m", List.of(new float[] {0, 0, 1}));
			assertEquals(2, v.count("m"), "replacing an item drops its old chunks");
		}
		assertEquals(List.of(""), Embedder.chunks(""));
		assertEquals(1, Embedder.chunks("short").size());
		String para = "Sentence one is here. ".repeat(40); // ~880 chars
		List<String> parts = Embedder.chunks(para + "\n\n" + para + "\n\n" + para);
		assertTrue(parts.size() >= 3, "three paragraphs of ~880 chars need at least three chunks: " + parts.size());
		assertTrue(parts.stream().allMatch(p -> p.length() <= Embedder.CHUNK_CHARS));
		assertTrue(parts.stream().allMatch(p -> p.endsWith(".")), "cut at sentence or paragraph ends");
	}

	@Test
	void aReHomedFactKeepsItsVector() {
		try (Engine e = engine("vec-rehome")) {
			long a = remember(e, "I work at Hooli.", se.hirt.mnemic.TestHomes.proposal().fact("works_at", "Hooli"))
					.observation().observationId();
			var second = remember(e, "As I said, I work at Hooli.", se.hirt.mnemic.TestHomes.proposal().fact("works_at", "Hooli"));
			long factId = Long.parseLong(second.applied().facts().getFirst().id().substring(2));
			VectorStore v = e.vectors();
			v.put(VectorStore.OBSERVATION, a, "m", new float[] {1});
			v.put(VectorStore.OBSERVATION, second.observation().observationId(), "m", new float[] {1});
			v.put(VectorStore.FACT, factId, "m", new float[] {1});
			assertTrue(e.forget(a));
			assertTrue(v.has(VectorStore.FACT, factId, "m"), "the fact survived, so did its vector");
			assertFalse(v.has(VectorStore.OBSERVATION, a, "m"), "the forgotten observation's vector is gone");
			assertTrue(e.forget(second.observation().observationId()));
			assertFalse(v.has(VectorStore.FACT, factId, "m"), "nothing else said it: the fact and its vector are gone");
		}
	}

	@Test
	void missingRowsAndForgetting() {
		try (Engine e = engine("vec-missing")) {
			long obs = remember(e, "I work at Hooli.", se.hirt.mnemic.TestHomes.proposal().fact("works_at", "Hooli"))
					.observation().observationId();
			VectorStore v = e.vectors();
			assertEquals(List.of(obs), v.missingObservations("m", 10));
			assertEquals(1, v.missingFacts("m", 10).size());
			v.put(VectorStore.OBSERVATION, obs, "m", new float[] {1});
			v.put(VectorStore.FACT, v.missingFacts("m", 10).getFirst(), "m", new float[] {1});
			assertTrue(v.missingObservations("m", 10).isEmpty());
			assertTrue(v.missingFacts("m", 10).isEmpty());
			assertTrue(e.forget(obs), "forgetting removes the vectors with the rows");
			assertEquals(0, v.count("m"));
		}
	}
}
