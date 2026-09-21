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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The usage bench's own logic: how a model's turn is read, how a recall block's verdict is read, and the sums. */
class UsageTest {

	@Test
	void aTurnIsACallOrAReplyAndLooseTextIsAReply() {
		Usage.Action call = Usage.parse("{\"tool\": \"recall\", \"arguments\": {\"query\": \"who is my mother\"}}");
		assertTrue(call.isCall());
		assertEquals("recall", call.tool());
		assertEquals("who is my mother", call.arguments().get("query"));
		Usage.Action fenced = Usage.parse("Sure.\n```json\n{\"reply\": \"Your mother is Gunilla.\"}\n```");
		assertFalse(fenced.isCall());
		assertEquals("Your mother is Gunilla.", fenced.reply());
		Usage.Action loose = Usage.parse("Your mother is Gunilla.");
		assertFalse(loose.isCall());
		assertEquals("Your mother is Gunilla.", loose.reply());
		assertTrue(loose.slip());
		assertFalse(call.slip());
		assertFalse(fenced.slip());
	}

	@Test
	void aSlipInTheProtocolIsReadLenientlyAndCounted() {
		Usage.Action keyed = Usage.parse("{\"recall\": {\"query\": \"my mother\"}}", Set.of("recall", "remember"));
		assertTrue(keyed.isCall());
		assertTrue(keyed.slip());
		assertEquals("recall", keyed.tool());
		assertEquals("my mother", keyed.arguments().get("query"));
		Usage.Action unknown = Usage.parse("{\"recall\": {\"query\": \"x\"}}", Set.of("remember"));
		assertFalse(unknown.isCall(), "an unknown key is not a call");
		Usage.Action broken = Usage.parse("{\"reply\":\"Line one.\\nIs that right?\\\"}", Set.of("recall"));
		assertFalse(broken.isCall());
		assertTrue(broken.slip());
		assertEquals("Line one.\nIs that right?", broken.reply());
		Usage.Action short1 = Usage.parse(
				"{\"tool\": \"remember\", \"arguments\": {\"text\": \"x\", \"proposal\": {\"facts\": [{\"subject\": \"self\"}]}}",
				Set.of("remember"));
		assertTrue(short1.isCall(), "a call one brace short is still a call");
		assertTrue(short1.slip());
		assertEquals("remember", short1.tool());
		assertTrue(short1.arguments().get("proposal") instanceof Map);
		Usage.Action braces = Usage.parse("{\"reply\": \"Use {braces} and [brackets] freely.\"}");
		assertFalse(braces.slip());
		assertEquals("Use {braces} and [brackets] freely.", braces.reply());
		Usage.Action named = Usage.parse("**recall** {\"query\": \"self's stepmother\"}", Set.of("recall"));
		assertTrue(named.isCall(), "the tool named in words, then its arguments");
		assertTrue(named.slip());
		assertEquals("recall", named.tool());
		assertEquals("self's stepmother", named.arguments().get("query"));
		Usage.Action fencedCall = Usage.parse(
				"**correct**\n```json\n{\"target\": \"f-2\", \"replacement\": {\"ended\": \"2020\"}}\n```",
				Set.of("correct"));
		assertTrue(fencedCall.isCall(), "the tool named, then its arguments in a fence");
		assertEquals("correct", fencedCall.tool());
		assertEquals("f-2", fencedCall.arguments().get("target"));
		Usage.Action notATool = Usage.parse("**note** {\"reply\": \"x\"}", Set.of("recall"));
		assertFalse(notATool.isCall());
		assertEquals("x", notATool.reply());
		Usage.Action cut = Usage.parse("{\"reply\": \"Cut off mid-sent", Set.of());
		assertTrue(cut.slip());
		assertEquals("Cut off mid-sent", cut.reply());
		Usage.Action nested = Usage.parse(
				"{\"tool\": \"remember\", \"arguments\": {\"text\": \"x\", \"proposal\": {\"facts\": [{\"subject\": \"self\"}]}}}");
		assertTrue(nested.isCall());
		assertTrue(nested.arguments().get("proposal") instanceof Map);
	}

	@Test
	void theVerdictIsReadOffTheBlock() {
		assertEquals("matched", Usage.verdictOf("recall: \"x\"\nstructured: matched Mattias · parent_of → 1 fact\n"));
		assertEquals("MISS", Usage.verdictOf("recall: \"x\"\nstructured: MISS — Mattias · parent_of[mother]: ...\n"));
		assertNull(Usage.verdictOf("no block"));
	}

	@Test
	void theSumsCountWhatTheToolsWereUsedFor() {
		List<Map<String, Object>> records = List.of(
				Map.of("scenario", "a", "kind", "say", "tool_calls", 1, "parse_failures", 0, "remembered", true,
						"remembered_with_reading", true),
				Map.of("scenario", "a", "kind", "ask", "tool_calls", 2, "parse_failures", 0, "type", "fact",
						"recalled_before_answer", true, "last_verdict", "matched", "correct", true),
				Map.of("scenario", "a", "kind", "ask", "tool_calls", 1, "parse_failures", 1, "type", "fact",
						"recalled_before_answer", true, "last_verdict", "MISS", "correct", false),
				Map.of("scenario", "b", "kind", "ask", "tool_calls", 0, "parse_failures", 0, "type", "abstention",
						"recalled_before_answer", false, "correct", true));
		Map<String, Object> s = Usage.summarise(records);
		assertEquals(1, s.get("statements"));
		assertEquals(1.0, s.get("remember_rate"));
		assertEquals(3, s.get("questions"));
		assertEquals(2, s.get("recalled_before_answer"));
		assertEquals(2, s.get("correct"));
		assertEquals(1, s.get("abstention_correct"));
		assertEquals(1, s.get("wrong_answers_on_a_miss"));
		assertEquals(1, s.get("protocol_slips"));
		assertEquals("1/2", ((Map<?, ?>) s.get("by_scenario")).get("a"));
	}

	@Test
	void theAssistantGetsTheGuideTheRuleAndTheTools() throws Exception {
		String prompt = Usage.systemPrompt(List.of(Map.of("name", "recall", "description", "Ask the store.",
				"input_schema", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))));
		assertTrue(prompt.contains("# Mnemic memory protocol"), "the server's instructions, as a client gets them");
		assertTrue(prompt.contains("RECALL BEFORE YOU ANSWER"), prompt);
		assertFalse(prompt.contains("## Vocabulary"), "the guide is not pasted in; the assistant fetches it");
		assertTrue(prompt.contains("{\"tool\": \"<name>\", \"arguments\": {...}}"), prompt);
		assertTrue(prompt.contains("{\"reply\": \"<what you say to the user>\"}"), prompt);
		assertTrue(prompt.indexOf("HOW TO ACT") > prompt.indexOf("### recall"), "the harness rule comes last");
		assertTrue(prompt.contains("\"query\""), "the schema is given verbatim");
	}

	@Test
	void theScriptLoads() throws Exception {
		Path script = Path.of("usage", "scenarios.json");
		if (!script.toFile().exists()) {
			script = Path.of("bench", "usage", "scenarios.json");
		}
		List<Usage.Scenario> scenarios = Usage.load(script);
		assertTrue(scenarios.size() >= 10, "scenarios: " + scenarios.size());
		assertTrue(scenarios.stream().allMatch(sc -> sc.steps().stream().anyMatch(Usage.Step::question)),
				"every scenario asks something");
		assertTrue(scenarios.stream().flatMap(sc -> sc.steps().stream()).filter(Usage.Step::question)
				.allMatch(st -> st.expect() != null && !st.expect().isBlank()), "every question has its answer");
	}
}
