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

	/** The tools in the OpenAI shape: a function per tool, its input schema as the parameters. */
	static List<Map<String, Object>> wireTools(List<ToolSpec> tools) {
		var out = new java.util.ArrayList<Map<String, Object>>();
		for (ToolSpec t : tools) {
			var fn = new LinkedHashMap<String, Object>();
			fn.put("name", t.name());
			fn.put("description", t.description() == null ? "" : t.description());
			fn.put("parameters", t.inputSchema() == null ? Map.of("type", "object") : t.inputSchema());
			out.add(Map.of("type", "function", "function", fn));
		}
		return out;
	}

	/**
	 * The conversation in the OpenAI shape: an assistant turn carries its calls as {@code tool_calls} with the
	 * arguments as a JSON string, and each result is a {@code tool} message addressed by the call's id.
	 */
	static List<Map<String, Object>> wireMessages(List<Message> messages) throws IOException {
		var out = new java.util.ArrayList<Map<String, Object>>();
		for (Message m : messages) {
			switch (m) {
			case UserMessage u -> out.add(Map.of("role", "user", "content", u.text()));
			case AssistantMessage a -> {
				var msg = new LinkedHashMap<String, Object>();
				msg.put("role", "assistant");
				msg.put("content", a.turn().text() == null ? "" : a.turn().text());
				if (a.turn().hasCalls()) {
					var calls = new java.util.ArrayList<Map<String, Object>>();
					for (ToolCall c : a.turn().calls()) {
						calls.add(Map.of("id", c.id(), "type", "function", "function",
								Map.of("name", c.name(), "arguments", MAPPER.writeValueAsString(c.arguments()))));
					}
					msg.put("tool_calls", calls);
				}
				out.add(msg);
			}
			case ToolResultMessage r ->
				out.add(Map.of("role", "tool", "tool_call_id", r.callId(), "name", r.name(), "content", r.content()));
			}
		}
		return out;
	}

	/**
	 * The model's turn from a chat completion message: its text with any thinking stripped, and its tool calls with the
	 * arguments parsed. Arguments that are not JSON (a local model can still garble them) become an empty map under a
	 * {@code _unparsed} key, so the call still reaches the server and its error tells the model.
	 */
	static Turn turnOf(JsonNode message) {
		String content = stripThinking(message.path("content").asText(""));
		var calls = new java.util.ArrayList<ToolCall>();
		int n = 0;
		for (JsonNode c : message.path("tool_calls")) {
			n++;
			String id = c.path("id").asText("");
			if (id.isEmpty()) {
				id = "call_" + n;
			}
			JsonNode fn = c.path("function");
			String name = fn.path("name").asText("");
			Map<String, Object> args = new LinkedHashMap<>();
			JsonNode raw = fn.path("arguments");
			try {
				if (raw.isObject()) {
					args = MAPPER.convertValue(raw, Map.class);
				} else if (!raw.asText("").isBlank()) {
					args = MAPPER.readValue(raw.asText(), Map.class);
				}
			} catch (IOException | IllegalArgumentException e) {
				args = new LinkedHashMap<>(Map.of("_unparsed", raw.asText("")));
			}
			calls.add(new ToolCall(id, name, args));
		}
		return new Turn(content, calls);
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
			JsonNode root = complete(
					List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)),
					null);
			JsonNode message = root.path("choices").path(0).path("message");
			String content = stripThinking(message.path("content").asText());
			String finish = root.path("choices").path(0).path("finish_reason").asText("");
			if (content.isEmpty() && "length".equals(finish)) {
				throw new IOException("Model ran out of tokens before answering (finish_reason=length); the reply was "
						+ "all reasoning. Lower mnemic.reasoning_effort or raise the context length.");
			}
			return content;
		}

		@Override
		public boolean supportsTools() {
			return true;
		}

		@Override
		public Turn chat(String system, List<Message> messages, List<ToolSpec> tools)
				throws IOException, InterruptedException {
			var wire = new java.util.ArrayList<Map<String, Object>>();
			wire.add(Map.of("role", "system", "content", system));
			wire.addAll(wireMessages(messages));
			JsonNode root = complete(wire, wireTools(tools));
			return turnOf(root.path("choices").path(0).path("message"));
		}

		/** One request, with the reasoning field dropped for good once a server has rejected it. */
		private JsonNode complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools)
				throws IOException, InterruptedException {
			boolean withReasoning = local && !reasoningFieldRejected;
			HttpResponse<String> resp = send(messages, tools, withReasoning);
			if (withReasoning && resp.statusCode() == 400 && resp.body().contains("reasoning")) {
				reasoningFieldRejected = true; // this server does not know the field; never send it again
				resp = send(messages, tools, false);
			}
			if (resp.statusCode() / 100 != 2) {
				throw new IOException("Model endpoint returned " + resp.statusCode() + ": " + resp.body());
			}
			return MAPPER.readTree(resp.body());
		}

		private HttpResponse<String> send(
			List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean withReasoning)
				throws IOException, InterruptedException {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("model", model);
			payload.put("temperature", 0);
			payload.put("max_tokens", local ? 6144 : 4096); // local models write pretty-printed JSON; give them room
			if (withReasoning) {
				payload.put("reasoning_effort", reasoningEffort());
			}
			payload.put("messages", messages);
			if (tools != null && !tools.isEmpty()) {
				payload.put("tools", tools);
			}
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
