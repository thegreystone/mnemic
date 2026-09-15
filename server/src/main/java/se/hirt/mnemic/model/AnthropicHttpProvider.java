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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic's Messages API over {@code java.net.http}, no SDK: one POST, {@code x-api-key} and
 * {@code anthropic-version} headers, {@code system} as a top-level field, the text blocks of the reply joined. Retries
 * on 429, 529, and 5xx with backoff, since the bench sends eight requests at once. The id is {@code anthropic:<model>};
 * the bench's proposal cache is keyed by it.
 */
public final class AnthropicHttpProvider implements ModelProvider {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String VERSION = "2023-06-01";
	private static final int MAX_ATTEMPTS = 8;

	public AnthropicHttpProvider() {
	}

	@Override
	public List<String> names() {
		return List.of("anthropic", "claude");
	}

	@Override
	public ChatModel create(ModelSpec spec) {
		String key = ModelSpec.key(spec.apiKeyEnv(), "API_KEY_ANTHROPIC", "ANTHROPIC_API_KEY");
		if (key == null) {
			throw new IllegalStateException(
					"No Anthropic key: set API_KEY_ANTHROPIC (or ANTHROPIC_API_KEY), or pass " + "--api-key-env NAME");
		}
		String endpoint = spec.endpoint() == null ? "https://api.anthropic.com" : spec.endpoint();
		return new Client("anthropic:" + spec.model(), endpoint, spec.model(), key);
	}

	static final class Client implements ChatModel {
		private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		private final String id;
		private final URI messages;
		private final String model;
		private final String apiKey;

		Client(String id, String baseUrl, String model, String apiKey) {
			this.id = id;
			this.messages = URI.create(baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "") + "/v1/messages");
			this.model = model;
			this.apiKey = apiKey;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public String chat(String system, String user) throws IOException, InterruptedException {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("model", model);
			payload.put("max_tokens", 4096);
			payload.put("system", system);
			payload.put("messages", List.of(Map.of("role", "user", "content", user)));
			String body = MAPPER.writeValueAsString(payload);
			IOException last = null;
			for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
				HttpRequest req = HttpRequest.newBuilder(messages).timeout(Duration.ofMinutes(3))
						.header("Content-Type", "application/json").header("x-api-key", apiKey)
						.header("anthropic-version", VERSION).POST(HttpRequest.BodyPublishers.ofString(body)).build();
				HttpResponse<String> resp;
				try {
					resp = http.send(req, HttpResponse.BodyHandlers.ofString());
				} catch (IOException e) {
					last = e;
					backoff(attempt);
					continue;
				}
				int status = resp.statusCode();
				if (status == 429 || status == 529 || status / 100 == 5) {
					last = new IOException("Anthropic returned " + status + ": " + errorMessage(resp.body()));
					backoff(attempt);
					continue;
				}
				if (status / 100 != 2) {
					throw new IOException("Anthropic returned " + status + ": " + errorMessage(resp.body()));
				}
				JsonNode root = MAPPER.readTree(resp.body());
				String stop = root.path("stop_reason").asText("");
				if (stop.toLowerCase().contains("refusal")) {
					throw new IOException("Model refused the request (stop_reason=refusal)");
				}
				var sb = new StringBuilder();
				for (JsonNode block : root.path("content")) {
					if ("text".equals(block.path("type").asText())) {
						if (sb.length() > 0) {
							sb.append('\n');
						}
						sb.append(block.path("text").asText());
					}
				}
				return sb.toString().trim();
			}
			throw last == null ? new IOException("Anthropic: no response") : last;
		}

		private static void backoff(int attempt) throws InterruptedException {
			Thread.sleep(Math.min(30_000L, 500L * (1L << Math.min(attempt, 6))));
		}

		private static String errorMessage(String body) {
			try {
				JsonNode n = MAPPER.readTree(body);
				String m = n.path("error").path("message").asText(null);
				if (m != null) {
					return m;
				}
			} catch (IOException ignored) {
				// not JSON
			}
			return body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
		}
	}
}
