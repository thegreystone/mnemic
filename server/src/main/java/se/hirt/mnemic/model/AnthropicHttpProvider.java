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
import se.hirt.mnemic.model.ChatModel.AssistantMessage;
import se.hirt.mnemic.model.ChatModel.Message;
import se.hirt.mnemic.model.ChatModel.ToolCall;
import se.hirt.mnemic.model.ChatModel.ToolResultMessage;
import se.hirt.mnemic.model.ChatModel.ToolSpec;
import se.hirt.mnemic.model.ChatModel.Turn;
import se.hirt.mnemic.model.ChatModel.UserMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

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
	private static final Map<String, LongAdder> USAGE = new ConcurrentHashMap<>();
	private static final List<String> USAGE_KEYS = List.of("input_tokens", "cache_creation_input_tokens",
			"cache_read_input_tokens", "output_tokens");

	public AnthropicHttpProvider() {
	}

	private static void tally(JsonNode usage) {
		for (String key : USAGE_KEYS) {
			USAGE.computeIfAbsent(key, k -> new LongAdder()).add(usage.path(key).asLong(0));
		}
		USAGE.computeIfAbsent("requests", k -> new LongAdder()).increment();
	}

	/** Tokens billed so far in this process, summed over every model: what a run cost, in the API's own terms. */
	public static Map<String, Long> usage() {
		var out = new LinkedHashMap<String, Long>();
		out.put("requests", USAGE.getOrDefault("requests", new LongAdder()).sum());
		for (String key : USAGE_KEYS) {
			out.put(key, USAGE.getOrDefault(key, new LongAdder()).sum());
		}
		return out;
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

	/** The tools in the Messages API shape: name, description, input_schema. */
	static List<Map<String, Object>> wireTools(List<ToolSpec> tools) {
		var out = new java.util.ArrayList<Map<String, Object>>();
		for (ToolSpec t : tools) {
			var m = new LinkedHashMap<String, Object>();
			m.put("name", t.name());
			m.put("description", t.description() == null ? "" : t.description());
			m.put("input_schema", t.inputSchema() == null ? Map.of("type", "object") : t.inputSchema());
			out.add(m);
		}
		return out;
	}

	/**
	 * The conversation in the Messages API shape: an assistant turn is text and {@code tool_use} blocks, a result is a
	 * {@code tool_result} block in a user message, and since roles must alternate, the results of one turn and any user
	 * text that follows them share one user message.
	 */
	static List<Map<String, Object>> wireMessages(List<Message> messages) {
		var out = new java.util.ArrayList<Map<String, Object>>();
		List<Map<String, Object>> userBlocks = null;
		for (Message m : messages) {
			switch (m) {
			case UserMessage u -> {
				if (userBlocks == null) {
					userBlocks = new java.util.ArrayList<>();
				}
				userBlocks.add(Map.of("type", "text", "text", u.text()));
			}
			case ToolResultMessage r -> {
				if (userBlocks == null) {
					userBlocks = new java.util.ArrayList<>();
				}
				userBlocks.add(Map.of("type", "tool_result", "tool_use_id", r.callId(), "content", r.content()));
			}
			case AssistantMessage a -> {
				if (userBlocks != null) {
					out.add(Map.of("role", "user", "content", userBlocks));
					userBlocks = null;
				}
				var blocks = new java.util.ArrayList<Map<String, Object>>();
				if (a.turn().text() != null && !a.turn().text().isBlank()) {
					blocks.add(Map.of("type", "text", "text", a.turn().text()));
				}
				if (a.turn().hasCalls()) {
					for (ToolCall c : a.turn().calls()) {
						blocks.add(Map.of("type", "tool_use", "id", c.id(), "name", c.name(), "input",
								c.arguments() == null ? Map.of() : c.arguments()));
					}
				}
				if (blocks.isEmpty()) {
					blocks.add(Map.of("type", "text", "text", "(nothing)"));
				}
				out.add(Map.of("role", "assistant", "content", blocks));
			}
			}
		}
		if (userBlocks != null) {
			out.add(Map.of("role", "user", "content", userBlocks));
		}
		return out;
	}

	/** The model's turn from a reply: its text blocks joined, its tool_use blocks as calls. */
	@SuppressWarnings("unchecked")
	static Turn turnOf(JsonNode root) {
		var sb = new StringBuilder();
		var calls = new java.util.ArrayList<ToolCall>();
		for (JsonNode block : root.path("content")) {
			String type = block.path("type").asText();
			if ("text".equals(type)) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(block.path("text").asText());
			} else if ("tool_use".equals(type)) {
				Map<String, Object> input = block.path("input").isObject()
						? MAPPER.convertValue(block.path("input"), Map.class) : new LinkedHashMap<>();
				calls.add(new ToolCall(block.path("id").asText(""), block.path("name").asText(""), input));
			}
		}
		return new Turn(sb.toString().trim(), calls);
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
			JsonNode root = complete(system, List.of(Map.of("role", "user", "content", user)), null);
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

		@Override
		public boolean supportsTools() {
			return true;
		}

		@Override
		public Turn chat(String system, List<Message> messages, List<ToolSpec> tools)
				throws IOException, InterruptedException {
			return turnOf(complete(system, wireMessages(messages), wireTools(tools)));
		}

		/**
		 * One request with retries. The system prompt is the same on every call of a run: a cache breakpoint on it
		 * makes each call after the first pay a tenth for it, and the tools, which precede it in the cached prefix,
		 * ride along. Short prompts fall under the model's minimum and are simply not cached.
		 */
		private JsonNode complete(String system, List<Map<String, Object>> wire, List<Map<String, Object>> tools)
				throws IOException, InterruptedException {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("model", model);
			payload.put("max_tokens", 4096);
			payload.put("system",
					List.of(Map.of("type", "text", "text", system, "cache_control", Map.of("type", "ephemeral"))));
			if (tools != null && !tools.isEmpty()) {
				payload.put("tools", tools);
			}
			payload.put("messages", wire);
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
				tally(root.path("usage"));
				String stop = root.path("stop_reason").asText("");
				if (stop.toLowerCase().contains("refusal")) {
					throw new IOException("Model refused the request (stop_reason=refusal)");
				}
				return root;
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
