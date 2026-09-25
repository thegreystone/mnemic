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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.model.ChatModel.AssistantMessage;
import se.hirt.mnemic.model.ChatModel.Message;
import se.hirt.mnemic.model.ChatModel.ToolCall;
import se.hirt.mnemic.model.ChatModel.ToolResultMessage;
import se.hirt.mnemic.model.ChatModel.ToolSpec;
import se.hirt.mnemic.model.ChatModel.Turn;
import se.hirt.mnemic.model.ChatModel.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tool calling on both wires: the tools go out as the vendor defines them, a conversation with calls and results
 * is laid out as the vendor requires it, and a reply's calls come back structured (2026-09-24).
 */
class ToolWireTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("recall", "Retrieve what is known.",
			Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))));

	private static List<Message> conversation() {
		Turn call = new Turn("", List.of(new ToolCall("c1", "recall", Map.of("query", "Anna's siblings"))));
		Turn answer = new Turn("Anna's brother is Erik.", List.of());
		return List.of(new UserMessage("Who is Anna's brother?"), new AssistantMessage(call),
				new ToolResultMessage("c1", "recall", "recall: ... Erik Lindqvist is Anna's brother"),
				new AssistantMessage(answer), new UserMessage("Thanks."));
	}

	@Test
	@SuppressWarnings("unchecked")
	void openAiWireCarriesCallsAsToolCallsAndResultsAsToolMessages() throws Exception {
		List<Map<String, Object>> tools = OpenAiCompatibleProvider.wireTools(TOOLS);
		assertEquals("function", tools.getFirst().get("type"));
		Map<String, Object> fn = (Map<String, Object>) tools.getFirst().get("function");
		assertEquals("recall", fn.get("name"));
		assertEquals(TOOLS.getFirst().inputSchema(), fn.get("parameters"));

		List<Map<String, Object>> wire = OpenAiCompatibleProvider.wireMessages(conversation());
		assertEquals(List.of("user", "assistant", "tool", "assistant", "user"),
				wire.stream().map(m -> m.get("role")).toList());
		List<Map<String, Object>> calls = (List<Map<String, Object>>) wire.get(1).get("tool_calls");
		assertEquals("c1", calls.getFirst().get("id"));
		Map<String, Object> called = (Map<String, Object>) calls.getFirst().get("function");
		assertEquals("recall", called.get("name"));
		assertEquals("{\"query\":\"Anna's siblings\"}", called.get("arguments"));
		assertEquals("c1", wire.get(2).get("tool_call_id"));
		assertFalse(wire.get(3).containsKey("tool_calls"), "a text turn carries no tool_calls");

		// The reply: a call with its arguments as a JSON string, and one a local model garbled.
		Turn turn = OpenAiCompatibleProvider.turnOf(JSON.readTree(
				"""
						{"role": "assistant", "content": "<think>hm</think>Let me look.", "tool_calls": [
						  {"id": "x1", "type": "function", "function": {"name": "recall", "arguments": "{\\"query\\": \\"Bo\\"}"}},
						  {"type": "function", "function": {"name": "inspect", "arguments": "{\\"ref\\": \\"f-1"}}]}"""));
		assertEquals("Let me look.", turn.text());
		assertEquals(2, turn.calls().size());
		assertEquals("Bo", turn.calls().get(0).arguments().get("query"));
		assertEquals("call_2", turn.calls().get(1).id(), "an id is made up when the server sent none");
		assertTrue(turn.calls().get(1).arguments().containsKey("_unparsed"), turn.calls().get(1).toString());
		Turn plain = OpenAiCompatibleProvider.turnOf(JSON.readTree("{\"content\": \"Erik.\"}"));
		assertFalse(plain.hasCalls());
		assertEquals("Erik.", plain.text());
	}

	@Test
	@SuppressWarnings("unchecked")
	void anthropicWireAlternatesRolesWithResultsInsideUserMessages() throws Exception {
		List<Map<String, Object>> tools = AnthropicHttpProvider.wireTools(TOOLS);
		assertEquals("recall", tools.getFirst().get("name"));
		assertEquals(TOOLS.getFirst().inputSchema(), tools.getFirst().get("input_schema"));

		List<Map<String, Object>> wire = AnthropicHttpProvider.wireMessages(conversation());
		assertEquals(List.of("user", "assistant", "user", "assistant", "user"),
				wire.stream().map(m -> m.get("role")).toList());
		List<Map<String, Object>> use = (List<Map<String, Object>>) wire.get(1).get("content");
		assertEquals("tool_use", use.getFirst().get("type"));
		assertEquals("c1", use.getFirst().get("id"));
		assertEquals(Map.of("query", "Anna's siblings"), use.getFirst().get("input"));
		List<Map<String, Object>> result = (List<Map<String, Object>>) wire.get(2).get("content");
		assertEquals("tool_result", result.getFirst().get("type"));
		assertEquals("c1", result.getFirst().get("tool_use_id"));

		// Two results then the next user turn share one user message, since roles must alternate.
		Turn two = new Turn("", List.of(new ToolCall("a", "recall", Map.of("query", "x")),
				new ToolCall("b", "recall", Map.of("query", "y"))));
		List<Map<String, Object>> merged = AnthropicHttpProvider.wireMessages(
				List.of(new UserMessage("Hi"), new AssistantMessage(two), new ToolResultMessage("a", "recall", "ra"),
						new ToolResultMessage("b", "recall", "rb"), new UserMessage("And?")));
		assertEquals(3, merged.size(), merged.toString());
		assertEquals(3, ((List<?>) merged.get(2).get("content")).size(), "two results and the text, one message");

		Turn turn = AnthropicHttpProvider.turnOf(JSON.readTree("""
				{"content": [{"type": "text", "text": "Checking."},
				             {"type": "tool_use", "id": "toolu_1", "name": "recall", "input": {"query": "Bo"}}],
				 "stop_reason": "tool_use"}"""));
		assertEquals("Checking.", turn.text());
		assertEquals("toolu_1", turn.calls().getFirst().id());
		assertEquals("Bo", turn.calls().getFirst().arguments().get("query"));
	}
}
