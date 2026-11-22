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
package se.hirt.mnemic.model;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local reasoning models: what the provider does with a thinking block and how the effort is chosen. */
class OpenAiCompatibleProviderTest {

	@Test
	void thinkingBlocksAreStripped() {
		assertEquals("{\"ok\": true}", OpenAiCompatibleProvider
				.stripThinking("<think>\nLet me consider the JSON.\n</think>\n\n{\"ok\": true}"));
		assertEquals("{\"ok\": true}", OpenAiCompatibleProvider.stripThinking("{\"ok\": true}"));
		assertEquals("", OpenAiCompatibleProvider.stripThinking(null));
		assertEquals("a b", OpenAiCompatibleProvider.stripThinking("<thinking>x</thinking>a <think>y</think>b"));
	}

	@Test
	void unreachableEndpointGivesNoDescription() {
		assertEquals("", OpenAiCompatibleProvider.describeLoaded("http://127.0.0.1:9/v1", "proposer"));
		assertEquals(false, OpenAiCompatibleProvider.serverAnswers("http://127.0.0.1:9/v1"));
	}

	@Test
	void reasoningEffortDefaultsToNone() {
		String before = System.getProperty("mnemic.reasoning_effort");
		try {
			System.clearProperty("mnemic.reasoning_effort");
			assertEquals(System.getenv().getOrDefault("MNEMIC_REASONING_EFFORT", "none"),
					OpenAiCompatibleProvider.reasoningEffort());
			System.setProperty("mnemic.reasoning_effort", "low");
			assertEquals("low", OpenAiCompatibleProvider.reasoningEffort());
		} finally {
			if (before == null) {
				System.clearProperty("mnemic.reasoning_effort");
			} else {
				System.setProperty("mnemic.reasoning_effort", before);
			}
		}
	}

	@Test
	void clientRetriesTransient429And5xxAndFailsFastOn400() throws Exception {
		AtomicInteger retryCalls = new AtomicInteger();
		AtomicInteger badCalls = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/retry/chat/completions", exchange -> {
			exchange.getRequestBody().readAllBytes();
			int n = retryCalls.incrementAndGet();
			int status = n == 1 ? 429 : n == 2 ? 503 : 200;
			String body = status == 200
					? "{\"choices\":[{\"message\":{\"content\":\"recovered\"},\"finish_reason\":\"stop\"}]}"
					: "{\"error\":\"transient\"}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.createContext("/bad/chat/completions", exchange -> {
			exchange.getRequestBody().readAllBytes();
			badCalls.incrementAndGet();
			byte[] bytes = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(400, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.createContext("/v1/models", exchange -> {
			byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.createContext("/api/v0/models", exchange -> {
			byte[] bytes = "{\"data\":[{\"id\":\"proposer\",\"state\":\"loaded\",\"publisher\":\"qwen\",\"arch\":\"qwen3\",\"quantization\":\"Q4_K_M\"}]}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			String host = "http://127.0.0.1:" + server.getAddress().getPort();
			var retryClient = new OpenAiCompatibleProvider.Client("test", host + "/retry", "m", "k", false, 1L);
			assertEquals("recovered", retryClient.chat("sys", "hi"));
			assertEquals(3, retryCalls.get(), "retried 429 and 503 then succeeded on attempt 3");

			var badClient = new OpenAiCompatibleProvider.Client("test", host + "/bad", "m", "k", false, 1L);
			IOException ex = assertThrows(IOException.class, () -> badClient.chat("sys", "hi"));
			assertTrue(ex.getMessage().contains("400"), ex.getMessage());
			assertEquals(1, badCalls.get(), "non-transient 400 must not be retried");

			assertTrue(OpenAiCompatibleProvider.serverAnswers(host + "/v1"));
			assertEquals("[qwen/qwen3/Q4_K_M]", OpenAiCompatibleProvider.describeLoaded(host + "/v1", "proposer"));
		} finally {
			server.stop(0);
		}
	}
}
