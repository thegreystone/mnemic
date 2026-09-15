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
package se.hirt.mnemic;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.protocol.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one container test: the tool envelope, the error shape, and a remember/recall round trip through the tool methods
 * themselves. Everything behavioural lives in the scenario tests.
 */
@QuarkusTest
class MnemicToolsTest {

	@Inject
	MnemicTools tools;

	@Test
	void rememberThenRecallThroughTheTools() {
		ToolResponse remembered = tools.remember("We decided to use SQLite for Mnemic.", Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, Optional.empty(),
				Optional.of("tools-test-1"), null);
		assertFalse(remembered.isError(), text(remembered));
		Map<String, Object> result = result(remembered);
		assertTrue(result.get("observation_id").toString().startsWith("obs-"), result.toString());

		ToolResponse recalled = tools.recall(Optional.of("SQLite"), Optional.empty(), Optional.of(400),
				Optional.empty(), Optional.empty());
		assertFalse(recalled.isError(), text(recalled));
		String block = text(recalled);
		assertTrue(block.startsWith("recall: \"SQLite\""), block);
		assertTrue(block.contains("We decided to use SQLite"), block);
		assertTrue(block.contains("structured: "), block);
	}

	@Test
	void structuredErrors() {
		ToolResponse blank = tools.remember("   ", Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(), null);
		assertTrue(blank.isError());
		assertEquals("INVALID_ARGUMENT", error(blank).get("code"));
		assertTrue(error(blank).get("message").toString().contains("Example"), "fix-it text");

		ToolResponse badDate = tools.recall(Optional.of("x"), Optional.of("last spring"), Optional.empty(),
				Optional.empty(), Optional.empty());
		assertTrue(badDate.isError());
		assertEquals("INVALID_ARGUMENT", error(badDate).get("code"));

		ToolResponse badKind = tools.remember("hello", Optional.of("telepathy"), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(), null);
		assertTrue(badKind.isError());
		assertEquals("INVALID_ARGUMENT", error(badKind).get("code"));
	}

	@Test
	void statusReportsTheStore() {
		ToolResponse status = tools.status();
		assertFalse(status.isError(), text(status));
		Map<String, Object> result = result(status);
		assertEquals(20, ((Number) result.get("schema_version")).intValue());
		assertTrue(result.containsKey("pending_proposals"));
		assertTrue(result.get("model_providers").toString().contains("openai-compatible"), result.toString());
		// Every recall channel reports whether it answers; the test profile keeps the semantic one off and says so.
		@SuppressWarnings("unchecked")
		Map<String, Object> channels = (Map<String, Object>) result.get("channels");
		assertEquals("on", channels.get("structured"));
		assertEquals("on", channels.get("keys"));
		assertEquals("on", channels.get("lexical"));
		assertTrue(channels.get("semantic").toString().startsWith("off ("), channels.toString());
		assertEquals("off", ((Map<?, ?>) result.get("embedder")).get("state"));
		assertTrue(result.get("ort_bundled").toString().contains("ONNX Runtime"), result.toString());
		// The two runtime fields explain themselves when nothing is loaded, instead of reading as null.
		assertTrue(result.get("vec").toString().startsWith("scan"), result.get("vec").toString());
		String ort = result.get("ort").toString();
		assertTrue(ort.contains("not loaded") || ort.toLowerCase().contains("onnxruntime"), ort);
		assertTrue(result.get("pending_proposal_ids") instanceof java.util.List, result.toString());
	}

	@Test
	@Scenario("I5")
	void aConnectorObservationGetsItsFactsThroughPropose() {
		// A connector never carries a proposal; the assistant reads the observation and proposes for it.
		ToolResponse stored = tools.remember(
				"From: Dario. Payment received on 11 September; handover moved to 24 September.",
				Optional.of("connector"), Optional.of("home/INBOX/943905"), Optional.empty(), Optional.empty(),
				Optional.empty(), null, Optional.empty(), Optional.of("tools-propose-1"), null);
		assertFalse(stored.isError(), text(stored));
		String obs = (String) result(stored).get("observation_id");
		long pendingBefore = ((Number) result(stored).get("pending_proposals")).longValue();
		assertTrue(pendingBefore >= 1, "the connector observation waits for a reading");
		Map<String, Object> proposal = Map.of("entities",
				java.util.List.of(Map.of("ref", "e1", "name", "Dario", "type", "person")), "events",
				java.util.List.of(Map.of("ref", "ev1", "type", "paid", "participants", java.util.List.of("self", "e1"),
						"valid_time", Map.of("start", "2026-09-11"))));
		ToolResponse proposed = tools.propose(obs, proposal);
		assertFalse(proposed.isError(), text(proposed));
		@SuppressWarnings("unchecked")
		Map<String, Object> got = (Map<String, Object>) result(proposed).get("stored");
		assertEquals(1, ((java.util.List<?>) got.get("events")).size(), got.toString());
		assertEquals(pendingBefore - 1, ((Number) result(proposed).get("pending_proposals")).longValue(),
				"it left the backlog");
		// A second reading is refused: the facts exist and are corrected, not proposed again.
		assertTrue(tools.propose(obs, proposal).isError());
		// The connector rule itself still holds at remember time.
		ToolResponse refused = tools.remember("Another email.", Optional.of("connector"), Optional.of("home/INBOX/1"),
				Optional.empty(), Optional.empty(), Optional.empty(), proposal, Optional.empty(),
				Optional.of("tools-propose-2"), null);
		assertTrue(refused.isError());
		assertTrue(text(refused).contains("propose(observation_id, proposal)"), text(refused));
	}

	@Test
	void retireKeepsTheFactsAndSaysSoAndCanBeUndone() {
		Map<String, Object> proposal = Map.of("entities",
				java.util.List.of(Map.of("ref", "e1", "name", "Slack", "type", "technology")), "facts",
				java.util.List.of(Map.of("subject", "self", "predicate", "uses", "object", "e1")));
		ToolResponse stored = tools.remember("Dad is best reached on Slack.", Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), proposal, Optional.empty(),
				Optional.of("tools-retire-1"), null);
		assertFalse(stored.isError(), text(stored));
		String obs = (String) result(stored).get("observation_id");
		ToolResponse r = tools.retire(obs, Optional.of("it was WhatsApp"), Optional.empty(), Optional.empty());
		assertFalse(r.isError(), text(r));
		assertEquals(obs, result(r).get("retired"));
		assertEquals(1, ((java.util.List<?>) result(r).get("facts_citing")).size(), "the fact it produced is named");
		assertTrue(result(r).containsKey("note"));
		assertEquals(1L, ((Number) result(tools.status()).get("observations_retired")).longValue());
		// The fact's history marks the observation as retired.
		String factId = (String) ((java.util.List<Map<String, Object>>) ((Map<?, ?>) result(stored).get("stored"))
				.get("facts")).getFirst().get("id");
		ToolResponse h = tools.history("Mattias Sandell", Optional.of("uses"));
		assertTrue(text(h).contains("observations_retired"), text(h));
		// Undo.
		ToolResponse u = tools.retire(obs, Optional.empty(), Optional.empty(), Optional.of(true));
		assertFalse(u.isError(), text(u));
		assertEquals(obs, result(u).get("reinstated"));
		assertEquals(0L, ((Number) result(tools.status()).get("observations_retired")).longValue());
	}

	@Test
	void retractWithdrawsAFactThatWasNeverTrue() {
		Map<String, Object> proposal = Map.of("facts",
				java.util.List.of(Map.of("subject", "self", "predicate", "decided", "object", "replace the printer")));
		ToolResponse stored = tools.remember("I decided to replace the printer.", Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), proposal, Optional.empty(),
				Optional.of("tools-retract-1"), null);
		assertFalse(stored.isError(), text(stored));
		@SuppressWarnings("unchecked")
		String factId = (String) ((java.util.List<Map<String, Object>>) ((Map<?, ?>) result(stored).get("stored"))
				.get("facts")).getFirst().get("id");
		ToolResponse r = tools.retract(factId, Optional.of("it was a leaning, never a decision"));
		assertFalse(r.isError(), text(r));
		assertEquals("corrected", ((Map<?, ?>) result(r).get("retracted")).get("status"));
		// Once withdrawn, it cannot be withdrawn or corrected again.
		assertTrue(tools.retract(factId, Optional.empty()).isError());
	}

	@Test
	void historyListsEveryConversationBehindAFact() {
		Map<String, Object> proposal = Map.of("entities",
				java.util.List.of(Map.of("ref", "e1", "name", "Hooli", "type", "organization")), "facts",
				java.util.List.of(Map.of("subject", "self", "predicate", "works_at", "object", "e1")));
		ToolResponse first = tools.remember("I work at Hooli.", Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), proposal, Optional.empty(), Optional.of("tools-history-1"), null);
		assertFalse(first.isError(), text(first));
		ToolResponse again = tools.remember("As I said, I work at Hooli.", Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), proposal, Optional.empty(),
				Optional.of("tools-history-2"), null);
		assertFalse(again.isError(), text(again));
		ToolResponse history = tools.history("Hooli", Optional.of("works_at"));
		assertFalse(history.isError(), text(history));
		String h = text(history);
		assertTrue(h.contains("\"observations\":[\"obs-"), h);
		int comma = h.indexOf("\"observations\":[\"obs-");
		String list = h.substring(comma, h.indexOf("]", comma));
		assertTrue(list.split("obs-").length - 1 >= 2, "two conversations behind the one fact: " + list);
	}

	@Test
	void listPredicatesNamesTheRegistry() {
		ToolResponse r = tools.list_predicates();
		assertFalse(r.isError(), text(r));
		Map<String, Object> result = result(r);
		String preds = result.get("predicates").toString();
		assertTrue(preds.contains("name=works_at"), preds);
		assertTrue(preds.contains("name=considering"), "the predicate added on 2026-09-10 is visible: " + preds);
		assertTrue(preds.contains("origin=seed"), preds);
		assertTrue(result.get("event_types").toString().contains("name=joined"), result.toString());
		String types = result.get("entity_types").toString();
		assertTrue(types.contains("name=country") && types.contains("parent=place"), types);
	}

	@Test
	void anUndefinedTermIsSuggestedInTheReply() {
		Map<String, Object> proposal = Map.of("entities", List.of(Map.of("name", "the cabin", "type", "place")),
				"events", List.of(Map.of("type", "inherited", "participants", List.of("self", "the cabin"))));
		ToolResponse r = tools.remember("I inherited the cabin.", Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), proposal, Optional.empty(), Optional.of("tools-suggest-1"), null);
		assertFalse(r.isError(), text(r));
		Map<String, Object> result = result(r);
		assertEquals(List.of(), result.get("questions"), "nothing is held");
		String suggestions = result.get("suggestions").toString();
		assertTrue(suggestions.contains("kind=event_type") && suggestions.contains("name=inherited"), suggestions);
		assertTrue(suggestions.contains("Check with the user"), suggestions);
		assertTrue(suggestions.contains("define={event_types="), "a skeleton to start from: " + suggestions);
	}

	private static String text(ToolResponse r) {
		return ((TextContent) r.content().getFirst()).text();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(ToolResponse r) {
		return (Map<String, Object>) Json.readMap(text(r)).get("result");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> error(ToolResponse r) {
		return (Map<String, Object>) Json.readMap(text(r)).get("error");
	}
}
