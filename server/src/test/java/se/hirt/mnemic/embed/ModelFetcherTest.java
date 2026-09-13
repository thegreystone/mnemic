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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model fetch: pinned hashes, partial files, and the lock. No network: file: sources. */
class ModelFetcherTest {

	private static Path temp(String name) throws IOException {
		Path dir = Files.createTempDirectory("mnemic-fetch-" + name);
		dir.toFile().deleteOnExit();
		return dir;
	}

	@Test
	void fetchesVerifiesAndPlacesFiles() throws Exception {
		Path source = temp("src");
		Path models = temp("models");
		Path weights = source.resolve("model.onnx");
		Files.writeString(weights, "weights, allegedly", StandardCharsets.UTF_8);
		Path plain = source.resolve("tokenizer.json");
		Files.writeString(plain, "{\"model\": {\"type\": \"Unigram\"}}", StandardCharsets.UTF_8);
		List<ModelFetcher.Item> plan = List.of(
				new ModelFetcher.Item("model.onnx", weights.toUri(), ModelFetcher.sha256(weights), Files.size(weights),
						models.resolve("model").resolve("model.onnx")),
				new ModelFetcher.Item("tokenizer.json", plain.toUri(), ModelFetcher.sha256(plain), Files.size(plain),
						models.resolve("model").resolve("tokenizer.json")));
		assertFalse(ModelFetcher.complete(plan));
		assertEquals(Files.size(weights) + Files.size(plain), ModelFetcher.totalBytes(plan));
		long[] last = {0};
		ModelFetcher.fetch(models, plan, received -> last[0] = received);
		assertTrue(ModelFetcher.complete(plan));
		assertEquals("weights, allegedly", Files.readString(models.resolve("model").resolve("model.onnx")));
		assertEquals(Files.readString(plain), Files.readString(models.resolve("model").resolve("tokenizer.json")));
		assertEquals(Files.size(weights) + Files.size(plain), last[0], "progress reaches the total");
		try (var s = Files.list(models.resolve("model"))) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "no partial files remain");
		}
		// A second fetch has nothing to do and touches nothing.
		assertEquals(0, ModelFetcher.totalBytes(plan));
		ModelFetcher.fetch(models, plan, received -> {
		});
	}

	@Test
	void aFileThatDoesNotMatchItsHashIsDiscarded() throws Exception {
		Path source = temp("src2");
		Path models = temp("models2");
		Path file = source.resolve("model.onnx");
		Files.writeString(file, "weights", StandardCharsets.UTF_8);
		List<ModelFetcher.Item> plan = List.of(new ModelFetcher.Item("model.onnx", file.toUri(),
				"0000000000000000000000000000000000000000000000000000000000000000", Files.size(file),
				models.resolve("m").resolve("model.onnx")));
		IOException e = assertThrows(IOException.class, () -> ModelFetcher.fetch(models, plan, r -> {
		}));
		assertTrue(e.getMessage().contains("did not match"), e.getMessage());
		assertFalse(Files.exists(models.resolve("m").resolve("model.onnx")), "nothing placed");
		assertFalse(Files.exists(models.resolve("m").resolve("model.onnx.part")), "nothing left behind");
	}

	@Test
	void thePlanNamesThePinnedModelFilesAndNoCode() {
		Path models = Path.of("models");
		List<ModelFetcher.Item> plan = ModelFetcher.plan(models, null);
		assertEquals(2, plan.size(), "the model and its tokenizer; the runtime is part of the build");
		assertTrue(plan.stream().allMatch(i -> i.url().toString().startsWith("https://huggingface.co/ibm-granite/" + ModelFetcher.MODEL_ID + "/")), plan.toString());
		assertTrue(plan.get(0).url().toString().contains("/onnx/model"), "the graph lives under onnx/ in the repository: " + plan.get(0).url());
		assertEquals("model.onnx", plan.get(0).target().getFileName().toString(), "whichever build, it is model.onnx on disk");
		assertTrue(plan.stream().allMatch(i -> i.sha256().length() == 64));
		assertTrue(plan.stream().noneMatch(i -> i.name().endsWith(".jar") || i.name().endsWith(".dll")
				|| i.name().endsWith(".so") || i.name().endsWith(".dylib")), "no executable code in the plan");
		// A mirror replaces the host, never the hashes.
		List<ModelFetcher.Item> mirrored = ModelFetcher.plan(models, "file:///m/model/");
		assertTrue(mirrored.get(0).url().toString().startsWith("file:///m/model/onnx/model"), mirrored.get(0).url().toString());
		assertEquals(plan.get(0).sha256(), mirrored.get(0).sha256());
	}
}
