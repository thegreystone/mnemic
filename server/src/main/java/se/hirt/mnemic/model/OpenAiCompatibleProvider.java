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
import java.util.regex.Pattern;

/**
 * Chat completions over the OpenAI wire format, which LM Studio, Ollama, OpenAI, and most hosted providers speak.
 * Provider names: {@code lmstudio} (localhost:1234, no key), {@code ollama} (localhost:11434, no key), {@code openai}
 * (api.openai.com, key from {@code API_KEY_OPENAI} or {@code OPENAI_API_KEY}), and {@code openai-compatible} (endpoint
 * required after {@code @}). Temperature 0. Only {@code java.net.http} and Jackson, so it is safe inside the native
 * image; the local names are what the server's optional model tier uses.
 */
public final class OpenAiCompatibleProvider implements ModelProvider {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public OpenAiCompatibleProvider() {
	}

	@Override
	public List<String> names() {
		return List.of("lmstudio", "ollama", "openai", "openai-compatible");
	}

	@Override
	public ChatModel create(ModelSpec spec) {
		String endpoint = spec.endpoint();
		String key = null;
		switch (spec.provider()) {
		case "openai" -> {
			endpoint = endpoint == null ? "https://api.openai.com/v1" : endpoint;
			key = ModelSpec.key(spec.apiKeyEnv(), "API_KEY_OPENAI", "OPENAI_API_KEY");
			if (key == null) {
				throw new IllegalStateException(
						"No OpenAI key: set API_KEY_OPENAI (or OPENAI_API_KEY), or pass " + "--api-key-env NAME");
			}
		}
		case "lmstudio" -> endpoint = endpoint == null ? "http://localhost:1234/v1" : endpoint;
		case "ollama" -> endpoint = endpoint == null ? "http://localhost:11434/v1" : endpoint;
		default -> {
			if (endpoint == null) {
				throw new IllegalArgumentException(
						"openai-compatible needs an endpoint: " + "openai-compatible:<model>@http://host/v1");
			}
			key = ModelSpec.key(spec.apiKeyEnv());
		}
		}
		boolean local = !"openai".equals(spec.provider());
		String id = spec.provider() + ":" + spec.model();
		if ("lmstudio".equals(spec.provider())) {
			String loaded = describeLoaded(endpoint, spec.model());
			if (loaded.isEmpty() && serverAnswers(endpoint)) {
				// The server is up but nothing is loaded under that name: LM Studio would load something on demand,
				// and the cache key would be the bare alias rather than the weights behind it.
				throw new IllegalStateException("LM Studio has no model loaded as '" + spec.model() + "'. Load one "
						+ "first: lms load <model> --identifier " + spec.model());
			}
			id += loaded;
		}
		return new Client(id, endpoint, spec.model(), key, local);
	}

	static boolean serverAnswers(String endpoint) {
		try {
			URI uri = URI.create(endpoint.replaceAll("/v1/?$", "") + "/api/v0/models");
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
		} catch (IOException | RuntimeException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * An LM Studio identifier ({@code lms load ... --identifier proposer}) is an alias: two different models loaded
	 * under it in turn are indistinguishable by name. The model id therefore carries what the server reports about the
	 * loaded model ({@code /api/v0/models}: publisher, architecture, quantization), so a cache key follows the weights.
	 * Empty when the endpoint does not answer or the model is not loaded.
	 */
	static String describeLoaded(String endpoint, String model) {
		try {
			URI uri = URI.create(endpoint.replaceAll("/v1/?$", "") + "/api/v0/models");
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			HttpResponse<String> resp = http.send(
					HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() / 100 != 2) {
				return "";
			}
			for (JsonNode m : MAPPER.readTree(resp.body()).path("data")) {
				if (model.equals(m.path("id").asText()) && "loaded".equals(m.path("state").asText())) {
					return "[" + m.path("publisher").asText("?") + "/" + m.path("arch").asText("?") + "/"
							+ m.path("quantization").asText("?") + "]";
				}
			}
		} catch (IOException | RuntimeException e) {
			return "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return "";
	}

	/**
	 * Reasoning models think before they answer; for extraction that is tokens spent on a fixed task. Local endpoints
	 * get {@code reasoning_effort} from {@code mnemic.reasoning_effort} (default {@code none}), and a server that
	 * rejects the field gets the request again without it. Set the property to {@code low}, {@code medium}, or
	 * {@code high} to let the model think.
	 */
	static String reasoningEffort() {
		String v = System.getProperty("mnemic.reasoning_effort",
				System.getenv().getOrDefault("MNEMIC_REASONING_EFFORT", "none"));
		return v == null || v.isBlank() ? "none" : v.trim();
	}

	private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>\\s*|(?s)<thinking>.*?</thinking>\\s*");

	/** Removes a thinking block a model may put in front of its answer when the server does not separate it. */
	static String stripThinking(String content) {
		if (content == null) {
			return "";
		}
		return THINK.matcher(content).replaceAll("").trim();
	}

	static final class Client implements ChatModel {
		private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		private final String id;
		private final URI endpoint;
		private final String model;
		private final String apiKey;
		private final boolean local;
		private volatile boolean reasoningFieldRejected;

		Client(String id, String baseUrl, String model, String apiKey, boolean local) {
			this.id = id;
			this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
			this.model = model;
			this.apiKey = apiKey;
			this.local = local;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public String chat(String system, String user) throws IOException, InterruptedException {
			boolean withReasoning = local && !reasoningFieldRejected;
			HttpResponse<String> resp = send(system, user, withReasoning);
			if (withReasoning && resp.statusCode() == 400 && resp.body().contains("reasoning")) {
				reasoningFieldRejected = true; // this server does not know the field; never send it again
				resp = send(system, user, false);
			}
			if (resp.statusCode() / 100 != 2) {
				throw new IOException("Model endpoint returned " + resp.statusCode() + ": " + resp.body());
			}
			JsonNode root = MAPPER.readTree(resp.body());
			JsonNode message = root.path("choices").path(0).path("message");
			String content = stripThinking(message.path("content").asText());
			String finish = root.path("choices").path(0).path("finish_reason").asText("");
			if (content.isEmpty() && "length".equals(finish)) {
				throw new IOException("Model ran out of tokens before answering (finish_reason=length); the reply was "
						+ "all reasoning. Lower mnemic.reasoning_effort or raise the context length.");
			}
			return content;
		}

		private HttpResponse<String> send(String system, String user, boolean withReasoning)
				throws IOException, InterruptedException {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("model", model);
			payload.put("temperature", 0);
			payload.put("max_tokens", local ? 6144 : 4096); // local models write pretty-printed JSON; give them room
			if (withReasoning) {
				payload.put("reasoning_effort", reasoningEffort());
			}
			payload.put("messages",
					List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
			String body = MAPPER.writeValueAsString(payload);
			HttpRequest.Builder req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(10))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
			if (apiKey != null && !apiKey.isBlank()) {
				req.header("Authorization", "Bearer " + apiKey);
			}
			return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
		}
	}
}
