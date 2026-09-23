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
package se.hirt.mnemic.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import se.hirt.mnemic.model.AnthropicHttpProvider;
import se.hirt.mnemic.model.ChatModel;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.protocol.Protocol;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The usage bench: not "does the store hold the answer" (that is {@code run}) but "does an assistant, given the
 * server's own tool descriptions and the memory protocol, get it out". A model plays the assistant over scripted
 * conversations: it hears what the user says, decides when to call {@code remember}, and, asked a question, decides
 * whether to {@code recall} and what to answer. The tools are the real ones, served by a real server over stdio,
 * exactly as a client would start it. A judge grades the answers, and the trace says how the tools were used: whether
 * recall came before the answer, whether statements were remembered with a reading, and whether an answer was given on
 * a MISS.
 *
 * <pre>
 * bench usage --script bench/usage/scenarios.json --server server/target/mnemic-server-x-runner.jar
 *             --assistant anthropic:claude-fable-5-1 --judge anthropic:claude-fable-5-1 --out results/usage-fable
 *             [--api-key-env NAME] [--limit N] [--only id] [--embed on|off] [--max-calls 8]
 * </pre>
 */
public final class Usage {

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final ObjectMapper LINE = new ObjectMapper();
	private static final int TOOL_RESULT_CHARS = 4000;
	private static final int TRANSCRIPT_CHARS = 24000;

	private Usage() {
	}

	/**
	 * One thing the user does: says something, asks something with the answer a judge should accept, or comes back
	 * another day ({@code brk}: the conversation is over, the server is started afresh on the same store, and what the
	 * assistant knows from here on is what it recalls).
	 */
	record Step(String say, String ask, String expect, String type, boolean brk) {
		boolean question() {
			return ask != null;
		}
	}

	record Scenario(String id, String owner, String about, List<Step> steps) {
	}

	/**
	 * What the model decided: a tool call, or a reply to the user; {@code raw} is the text as it came, and {@code slip}
	 * says the text did not follow the protocol and was read leniently.
	 */
	record Action(String tool, Map<String, Object> arguments, String reply, String raw, boolean slip) {
		boolean isCall() {
			return tool != null;
		}
	}

	private static final Pattern REPLY_SALVAGE = Pattern.compile("\"reply\"\\s*:\\s*\"(.*)", Pattern.DOTALL);
	private static final Pattern INVOKE = Pattern.compile("<invoke\\s+name=\"([a-z_]+)\"\\s*>(.*?)</invoke>",
			Pattern.DOTALL);
	private static final Pattern PARAMETER = Pattern.compile("<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)</parameter>",
			Pattern.DOTALL);
	private static final Pattern LEADING_TOOL = Pattern
			.compile("^[*_`\\s]*(?:[Tt]ool\\s*:?\\s*)?([a-z_]+)[*_`\\s:]*(?:```(?:json)?\\s*)?(?=\\{)");

	static List<Scenario> load(Path script) throws IOException {
		JsonNode root = JSON.readTree(Files.readString(script, StandardCharsets.UTF_8));
		var out = new ArrayList<Scenario>();
		for (JsonNode s : root.get("scenarios")) {
			var steps = new ArrayList<Step>();
			for (JsonNode st : s.get("steps")) {
				steps.add(new Step(text(st, "say"), text(st, "ask"), text(st, "expect"),
						st.has("type") ? st.get("type").asText() : "fact", st.path("break").asBoolean(false)));
			}
			out.add(new Scenario(s.get("id").asText(), s.path("owner").asText("Mattias Sandell"),
					s.path("about").asText(""), steps));
		}
		return out;
	}

	private static String text(JsonNode n, String key) {
		return n.has(key) && !n.get(key).isNull() ? n.get(key).asText() : null;
	}

	/**
	 * The model's turn as an action. The first JSON object in the text decides: {@code tool} with {@code arguments} is
	 * a call, {@code reply} is what the user hears. Text that is no JSON object at all is taken as the reply, so a
	 * model that forgets the protocol still answers rather than stalls; the trace records that it did.
	 */
	static Action parse(String response) {
		return parse(response, Set.of());
	}

	/**
	 * As {@link #parse(String)}, with the tool names known: a model that writes {@code {"recall": {...}}} instead of
	 * {@code {"tool": "recall", ...}} is read as calling that tool, and a reply whose JSON is broken is salvaged as the
	 * text after {@code "reply":}. Both count as slips, as does loose text.
	 */
	static Action parse(String response, Set<String> tools) {
		if (response == null) {
			return new Action(null, null, "", "", true);
		}
		String body = response.strip();
		Matcher invoke = INVOKE.matcher(body);
		if (invoke.find() && tools.contains(invoke.group(1))) {
			// The model's own tool-call syntax, which a real client would execute; here it is read as the call.
			var args = new LinkedHashMap<String, Object>();
			Matcher param = PARAMETER.matcher(invoke.group(2));
			while (param.find()) {
				String value = param.group(2).strip();
				Object parsed = value;
				if (value.startsWith("{") || value.startsWith("[")) {
					try {
						parsed = JSON.readValue(value, Object.class);
					} catch (IOException e) {
						// keep the text
					}
				}
				args.put(param.group(1), parsed);
			}
			return new Action(invoke.group(1), args, null, response, true);
		}
		int start = body.indexOf('{');
		Matcher lead = LEADING_TOOL.matcher(body);
		if (lead.find() && tools.contains(lead.group(1))) {
			// "**recall** {"query": ...}": the tool named in words, its arguments as the object
			String args = firstObject(body, lead.end());
			if (args == null) {
				args = closed(body, lead.end());
			}
			if (args != null) {
				try {
					return new Action(lead.group(1), arguments(JSON.readTree(args)), null, response, true);
				} catch (IOException e) {
					// fall through: not arguments after all
				}
			}
		}
		if (start >= 0) {
			String object = firstObject(body, start);
			boolean repaired = false;
			if (object == null) {
				// the object never closed: a model that drops a final brace or two is read as if it had not
				object = closed(body, start);
				repaired = true;
			}
			if (object != null) {
				try {
					JsonNode n = JSON.readTree(object);
					if (n.has("tool")) {
						return new Action(n.get("tool").asText(), arguments(n.get("arguments")), null, response,
								repaired);
					}
					if (n.has("reply")) {
						return new Action(null, null, n.get("reply").asText(), response, repaired);
					}
					if (n.size() == 1) {
						String key = n.fieldNames().next();
						if (tools.contains(key) && n.get(key).isObject()) {
							return new Action(key, arguments(n.get(key)), null, response, true);
						}
					}
				} catch (IOException e) {
					// not the object we were looking for: fall through to the text itself
				}
			}
			Matcher m = REPLY_SALVAGE.matcher(body);
			if (m.find()) {
				String text = m.group(1).replaceAll("\\\\?\"\\s*}\\s*$", "").replace("\\n", "\n").replace("\\\"", "\"");
				return new Action(null, null, text, response, true);
			}
		}
		return new Action(null, null, body, response, true);
	}

	/** The JSON object starting at {@code start}, brackets inside strings ignored; null when it never closes. */
	private static String firstObject(String body, int start) {
		var open = new ArrayDeque<Character>();
		boolean inString = false;
		for (int i = start; i < body.length(); i++) {
			char c = body.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
			} else if (c == '{' || c == '[') {
				open.push(c);
			} else if (c == '}' || c == ']') {
				open.pop();
				if (open.isEmpty()) {
					return body.substring(start, i + 1);
				}
			}
		}
		return null;
	}

	/**
	 * The text from {@code start} with every bracket still open closed in order; null when a string is left open, since
	 * then the text was cut, not merely short of a brace.
	 */
	private static String closed(String body, int start) {
		var open = new ArrayDeque<Character>();
		boolean inString = false;
		for (int i = start; i < body.length(); i++) {
			char c = body.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
			} else if (c == '{' || c == '[') {
				open.push(c);
			} else if ((c == '}' || c == ']') && !open.isEmpty()) {
				open.pop();
			}
		}
		if (inString) {
			return null;
		}
		var sb = new StringBuilder(body.substring(start));
		while (!open.isEmpty()) {
			sb.append(open.pop() == '{' ? '}' : ']');
		}
		return sb.toString();
	}

	private static Map<String, Object> arguments(JsonNode n) {
		return n != null && n.isObject() ? JSON.convertValue(n, new TypeReference<>() {
		}) : Map.of();
	}

	/** The records of an earlier run's trace, one per line. */
	static List<Map<String, Object>> readTrace(Path trace) throws IOException {
		var out = new ArrayList<Map<String, Object>>();
		for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				out.add(JSON.readValue(line, new TypeReference<Map<String, Object>>() {
				}));
			}
		}
		return out;
	}

	/** The scenarios an earlier trace finished: every step recorded, in the script's current shape. */
	static Set<String> completedScenarios(List<Map<String, Object>> records, List<Scenario> scenarios) {
		var counts = new HashMap<String, Integer>();
		for (Map<String, Object> r : records) {
			counts.merge(String.valueOf(r.get("scenario")), 1, Integer::sum);
		}
		var done = new LinkedHashSet<String>();
		for (Scenario sc : scenarios) {
			if (counts.getOrDefault(sc.id(), 0) == sc.steps().size()) {
				done.add(sc.id());
			}
		}
		return done;
	}

	static void run(Map<String, String> o) throws Exception {
		Path script = Path.of(o.getOrDefault("script", "usage/scenarios.json"));
		Path server = Path.of(Bench.require(o, "server"));
		Path out = Path.of(Bench.require(o, "out"));
		String keyEnv = o.get("api-key-env");
		ChatModel assistant = ModelProvider.resolve(Bench.require(o, "assistant"), keyEnv);
		Judge judge = o.containsKey("judge") && !"none".equals(o.get("judge"))
				? new Judge(ModelProvider.resolve(o.get("judge"), keyEnv)) : null;
		int limit = Integer.parseInt(o.getOrDefault("limit", "0"));
		String only = o.get("only");
		boolean embed = "on".equals(o.getOrDefault("embed", "off"));
		int maxCalls = Integer.parseInt(o.getOrDefault("max-calls", "8"));
		boolean resume = "true".equals(o.getOrDefault("resume", "false"));
		Bench.reasoningEffort(o);

		Files.createDirectories(out);
		var config = new LinkedHashMap<String, Object>();
		config.put("script", script.toAbsolutePath().toString());
		config.put("server", server.toAbsolutePath().toString());
		config.put("assistant", assistant.id());
		config.put("judge", judge == null ? "none" : judge.model());
		config.put("embed", embed);
		config.put("max_calls", maxCalls);
		config.put("started_at", Instant.now().toString());
		List<Scenario> scenarios = load(script);
		var records = new ArrayList<Map<String, Object>>();
		Set<String> completed = Set.of();
		Path tracePath = out.resolve("usage.jsonl");
		if (resume && Files.exists(tracePath)) {
			// A run that stopped (an API limit, say) goes on from the first scenario it did not finish: the finished
			// ones keep their records and are not paid for twice; a half-done one is played again from the start.
			List<Map<String, Object>> prior = readTrace(tracePath);
			completed = completedScenarios(prior, scenarios);
			for (Map<String, Object> r : prior) {
				if (completed.contains(String.valueOf(r.get("scenario")))) {
					records.add(r);
				}
			}
			config.put("resumed", true);
			config.put("resumed_after", completed);
			System.err.println("resuming after " + completed.size() + " finished scenarios");
		}
		Files.writeString(out.resolve("config.json"), JSON.writeValueAsString(config), StandardCharsets.UTF_8);
		int done = 0;
		try (BufferedWriter trace = Files.newBufferedWriter(tracePath, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			for (Map<String, Object> r : records) {
				trace.write(LINE.writeValueAsString(r));
				trace.newLine();
			}
			trace.flush();
			for (Scenario sc : scenarios) {
				if (only != null && !only.equals(sc.id())) {
					continue;
				}
				if (completed.contains(sc.id())) {
					continue;
				}
				if (limit > 0 && done >= limit) {
					break;
				}
				done++;
				System.err.println("scenario " + sc.id());
				Path home = Files.createTempDirectory("mnemic-usage");
				McpClient client = McpClient.start(server, home, sc.owner(), embed);
				try {
					List<Map<String, Object>> tools = client.listTools();
					String system = systemPrompt(tools);
					Set<String> toolNames = tools.stream().map(t -> (String) t.get("name")).collect(Collectors.toSet());
					var transcript = new StringBuilder();
					boolean afterBreak = false;
					boolean recalledInConversation = false;
					for (int i = 0; i < sc.steps().size(); i++) {
						Step st = sc.steps().get(i);
						Map<String, Object> rec;
						if (st.brk()) {
							// Another day: the same store under a new server, and an empty conversation.
							client.close();
							client = McpClient.start(server, home, sc.owner(), embed);
							transcript.setLength(0);
							afterBreak = true;
							recalledInConversation = false;
							rec = new LinkedHashMap<>();
							rec.put("scenario", sc.id());
							rec.put("step", i);
							rec.put("kind", "break");
						} else {
							rec = step(sc, i, st, assistant, judge, client, system, toolNames, transcript, maxCalls,
									afterBreak, recalledInConversation);
							recalledInConversation |= Boolean.TRUE.equals(rec.get("recalled_in_conversation"));
						}
						records.add(rec);
						trace.write(LINE.writeValueAsString(rec));
						trace.newLine();
						trace.flush();
					}
				} finally {
					client.close();
				}
			}
		}
		Map<String, Object> summary = summarise(records);
		Map<String, Long> apiUsage = AnthropicHttpProvider.usage();
		if (apiUsage.get("requests") > 0) {
			summary.put("api_usage", apiUsage);
			if (!completed.isEmpty()) {
				summary.put("api_usage_note", "this process only; the " + completed.size()
						+ " scenarios taken over from the earlier run were billed then");
			}
		}
		Files.writeString(out.resolve("summary.json"), JSON.writeValueAsString(summary), StandardCharsets.UTF_8);
		System.out.println(JSON.writeValueAsString(summary));
	}

	/** The protocol the assistant is given: the guide, how to act in this harness, and the server's own tools. */
	static String systemPrompt(List<Map<String, Object>> tools) throws IOException {
		// What a real client gets: the server's instructions from the initialize reply, then its tools. The guide
		// is not pasted in; the assistant fetches it with inspect('guide') if it follows the instructions.
		var sb = new StringBuilder();
		sb.append("You are the user's assistant, with a memory server called Mnemic reachable through tools.\n\n");
		sb.append(Protocol.instructions()).append("\n\n");
		sb.append("TOOLS (name, description, input schema)\n");
		for (Map<String, Object> t : tools) {
			sb.append("\n### ").append(t.get("name")).append('\n').append(t.get("description")).append('\n');
			sb.append("input_schema: ").append(LINE.writeValueAsString(t.get("input_schema"))).append('\n');
		}
		// The harness rule comes last, where a model sees it closest to its own turn.
		sb.append("\nHOW TO ACT IN THIS HARNESS\n");
		sb.append("Each turn, reply with exactly one JSON object and nothing else. Either call a tool:\n");
		sb.append("{\"tool\": \"<name>\", \"arguments\": {...}}\n");
		sb.append("for example {\"tool\": \"recall\", \"arguments\": {\"query\": \"Anna's siblings\"}}\n");
		sb.append("and you will receive its result and may act again; or answer the user:\n");
		sb.append("{\"reply\": \"<what you say to the user>\"}\n");
		sb.append("for example {\"reply\": \"Your mother is Gunilla Nyberg.\"}\n");
		sb.append(
				"Even a one-line answer goes inside {\"reply\": ...}; plain text is not delivered. Call one tool at a ");
		sb.append(
				"time. When the user only tells you something, record what is worth keeping and reply in a sentence. ");
		sb.append("Today's date is ").append(Instant.now().toString(), 0, 10).append(".\n");
		return sb.toString();
	}

	private static Map<String, Object> step(
		Scenario sc, int index, Step st, ChatModel assistant, Judge judge, McpClient client, String system,
		Set<String> toolNames, StringBuilder transcript, int maxCalls, boolean afterBreak, boolean recalledEarlier)
			throws Exception {
		String said = st.question() ? st.ask() : st.say();
		transcript.append("User: ").append(said).append('\n');
		var calls = new ArrayList<Map<String, Object>>();
		String reply = null;
		int parseFailures = 0;
		var slipped = new ArrayList<String>();
		String lastVerdict = null;
		boolean rememberedWithReading = false;
		for (int n = 0; n <= maxCalls; n++) {
			String prompt = "Conversation so far, oldest first. Act on the last user turn.\n\n" + tail(transcript);
			String response = assistant.chat(system, prompt);
			Action a = parse(response, toolNames);
			if (a.slip()) {
				parseFailures++;
				slipped.add(head(a.raw(), 400));
			}
			if (!a.isCall()) {
				reply = a.reply();
				transcript.append("Assistant: ").append(reply).append('\n');
				break;
			}
			if (n == maxCalls) {
				reply = "(no reply: the tool-call limit was reached)";
				transcript.append("Assistant: ").append(reply).append('\n');
				break;
			}
			String result = client.call(a.tool(), a.arguments());
			var call = new LinkedHashMap<String, Object>();
			call.put("tool", a.tool());
			call.put("arguments", a.arguments());
			call.put("result_head", head(result, 600));
			calls.add(call);
			if ("recall".equals(a.tool())) {
				lastVerdict = verdictOf(result);
			}
			// A correction or a withdrawal is the statement written down too: "no, that was wrong" is handled by
			// correct, not by a second remember.
			if (("remember".equals(a.tool()) && a.arguments().get("proposal") != null) || "correct".equals(a.tool())
					|| "forget".equals(a.tool())) {
				rememberedWithReading = true;
			}
			transcript.append("Tool ").append(a.tool()).append(' ').append(LINE.writeValueAsString(a.arguments()))
					.append("\nResult: ").append(head(result, TOOL_RESULT_CHARS)).append('\n');
		}
		var rec = new LinkedHashMap<String, Object>();
		rec.put("scenario", sc.id());
		rec.put("step", index);
		rec.put("kind", st.question() ? "ask" : "say");
		if (afterBreak) {
			rec.put("after_break", true);
		}
		rec.put("text", said);
		rec.put("calls", calls);
		rec.put("tool_calls", calls.size());
		rec.put("parse_failures", parseFailures);
		if (!slipped.isEmpty()) {
			rec.put("slipped", slipped);
		}
		rec.put("reply", reply);
		if (st.question()) {
			rec.put("expect", st.expect());
			rec.put("type", st.type());
			boolean recalledNow = calls.stream().anyMatch(c -> "recall".equals(c.get("tool")));
			rec.put("recalled_before_answer", recalledNow);
			// A recall earlier in the same conversation counts too: its block is still in front of the model.
			rec.put("recalled_in_conversation", recalledNow || recalledEarlier);
			rec.put("last_verdict", lastVerdict);
			if (judge != null) {
				// No "_abs" suffix: an abstention here is judged by the memory-assistant rule, not LongMemEval's.
				String qid = sc.id() + "-" + index;
				boolean correct = judge.correct(qid, judgeType(st.type()), st.ask(), st.expect(), reply);
				rec.put("correct", correct);
			}
		} else {
			rec.put("remembered", calls.stream()
					.anyMatch(c -> Set.of("remember", "correct", "forget").contains((String) c.get("tool"))));
			rec.put("remembered_with_reading", rememberedWithReading);
		}
		return rec;
	}

	private static String judgeType(String type) {
		return switch (type == null ? "fact" : type) {
		case "update" -> "knowledge-update";
		case "temporal" -> "temporal-reasoning";
		case "abstention" -> "abstention";
		default -> "single-session-user";
		};
	}

	/** The structured verdict's first word, from a recall block: matched, MISS, KNOWN FALSE, events, entity, ... */
	static String verdictOf(String recallBlock) {
		if (recallBlock == null) {
			return null;
		}
		for (String line : recallBlock.split("\n")) {
			if (line.startsWith("structured: ")) {
				String rest = line.substring("structured: ".length());
				int cut = rest.indexOf(' ');
				return cut < 0 ? rest : rest.substring(0, cut);
			}
		}
		return null;
	}

	private static String tail(StringBuilder transcript) {
		if (transcript.length() <= TRANSCRIPT_CHARS) {
			return transcript.toString();
		}
		return "[...earlier turns omitted...]\n" + transcript.substring(transcript.length() - TRANSCRIPT_CHARS);
	}

	private static String head(String s, int chars) {
		if (s == null) {
			return null;
		}
		return s.length() <= chars ? s : s.substring(0, chars) + "…";
	}

	/** The numbers: accuracy by kind of question, and how the tools were used along the way. */
	static Map<String, Object> summarise(List<Map<String, Object>> records) {
		int says = 0;
		int remembered = 0;
		int withReading = 0;
		int asks = 0;
		int judged = 0;
		int correct = 0;
		int abstentions = 0;
		int abstentionsCorrect = 0;
		int recalledFirst = 0;
		int answeredOnMiss = 0;
		int calls = 0;
		int parseFailures = 0;
		int asksAfterBreak = 0;
		int recalledFirstAfterBreak = 0;
		int recalledInConversationAfterBreak = 0;
		int judgedAfterBreak = 0;
		int correctAfterBreak = 0;
		var byScenario = new LinkedHashMap<String, int[]>(); // asked, correct
		for (Map<String, Object> r : records) {
			if ("break".equals(r.get("kind"))) {
				continue;
			}
			calls += (int) r.getOrDefault("tool_calls", 0);
			parseFailures += (int) r.getOrDefault("parse_failures", 0);
			if ("say".equals(r.get("kind"))) {
				says++;
				remembered += Boolean.TRUE.equals(r.get("remembered")) ? 1 : 0;
				withReading += Boolean.TRUE.equals(r.get("remembered_with_reading")) ? 1 : 0;
				continue;
			}
			asks++;
			boolean recalled = Boolean.TRUE.equals(r.get("recalled_before_answer"));
			recalledFirst += recalled ? 1 : 0;
			boolean afterBreak = Boolean.TRUE.equals(r.get("after_break"));
			asksAfterBreak += afterBreak ? 1 : 0;
			recalledFirstAfterBreak += afterBreak && recalled ? 1 : 0;
			recalledInConversationAfterBreak += afterBreak
					&& (recalled || Boolean.TRUE.equals(r.get("recalled_in_conversation"))) ? 1 : 0;
			boolean abstention = "abstention".equals(r.get("type"));
			abstentions += abstention ? 1 : 0;
			int[] sc = byScenario.computeIfAbsent(String.valueOf(r.get("scenario")), k -> new int[2]);
			sc[0]++;
			if (r.get("correct") != null) {
				judged++;
				boolean ok = Boolean.TRUE.equals(r.get("correct"));
				correct += ok ? 1 : 0;
				sc[1] += ok ? 1 : 0;
				judgedAfterBreak += afterBreak ? 1 : 0;
				correctAfterBreak += afterBreak && ok ? 1 : 0;
				abstentionsCorrect += abstention && ok ? 1 : 0;
				String verdict = String.valueOf(r.get("last_verdict"));
				if (!ok && !abstention && ("MISS".equals(verdict) || "unresolved".equals(verdict))) {
					answeredOnMiss++;
				}
			}
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("statements", says);
		m.put("remembered", remembered);
		m.put("remembered_with_reading", withReading);
		m.put("remember_rate", rate(remembered, says));
		m.put("questions", asks);
		m.put("recalled_before_answer", recalledFirst);
		m.put("recall_first_rate", rate(recalledFirst, asks));
		m.put("judged", judged);
		m.put("correct", correct);
		m.put("accuracy", rate(correct, judged));
		m.put("abstention_questions", abstentions);
		m.put("abstention_correct", abstentionsCorrect);
		m.put("wrong_answers_on_a_miss", answeredOnMiss);
		// After a break the conversation holds nothing: these are the questions only the memory can answer.
		m.put("questions_after_break", asksAfterBreak);
		m.put("recall_first_rate_after_break", rate(recalledFirstAfterBreak, asksAfterBreak));
		m.put("recall_in_conversation_rate_after_break", rate(recalledInConversationAfterBreak, asksAfterBreak));
		m.put("accuracy_after_break", rate(correctAfterBreak, judgedAfterBreak));
		m.put("tool_calls", calls);
		m.put("tool_calls_per_step", records.isEmpty() ? 0 : Math.round(10.0 * calls / records.size()) / 10.0);
		m.put("protocol_slips", parseFailures);
		var per = new LinkedHashMap<String, Object>();
		for (Map.Entry<String, int[]> e : byScenario.entrySet()) {
			per.put(e.getKey(), e.getValue()[1] + "/" + e.getValue()[0]);
		}
		m.put("by_scenario", per);
		return m;
	}

	private static Object rate(int part, int whole) {
		return whole == 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;
	}

	static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}
}
