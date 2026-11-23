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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.protocol.Json;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fresh;
import static se.hirt.mnemic.TestHomes.options;

/**
 * Verifies {@link MnemicTools#status} reports embedder metadata without triggering live ONNX inference on every call.
 * Runs when {@code MNEMIC_ORT_LIBRARY} and {@code MNEMIC_EMBED_MODEL} are available.
 */
class StatusOptimizationTest {

	@Test
	@SuppressWarnings("unchecked")
	void statusDoesNotRunLiveEmbedderInference() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");

		try (Embedder emb = new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
				Engine engine = new Engine(options(fresh("status-no-infer")).withEmbedder(emb))) {
			assertTrue(emb.dims() > 0);
			assertTrue(emb.probeMs() >= 0.0);
			assertEquals(1, emb.inferences(), "only the constructor's one-time dimension probe should have run");

			MnemicConfig config = (MnemicConfig) Proxy.newProxyInstance(MnemicConfig.class.getClassLoader(),
					new Class<?>[] {MnemicConfig.class}, (proxy, method, args) -> Optional.empty());
			MnemicTools tools = new MnemicTools();
			tools.engine = engine;
			tools.config = config;

			for (int i = 0; i < 5; i++) {
				ToolResponse resp = tools.status(null);
				assertFalse(resp.isError());
				String json = ((TextContent) resp.content().getFirst()).text();
				Map<String, Object> envelope = Json.readMap(json);
				Map<String, Object> result = (Map<String, Object>) envelope.get("result");
				Map<String, Object> embInfo = (Map<String, Object>) result.get("embedder");
				assertEquals("ready", embInfo.get("state"));
				assertEquals(emb.id(), embInfo.get("model"));
				assertEquals(emb.dims(), ((Number) embInfo.get("dims")).intValue());
				assertEquals(emb.probeMs(), ((Number) embInfo.get("ms")).doubleValue(), 1e-6);
			}

			assertEquals(1, emb.inferences(), "status() must never invoke ONNX inference");
		}
	}
}
