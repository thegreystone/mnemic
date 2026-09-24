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
import org.eclipse.microprofile.config.ConfigProvider;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.protocol.Protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tool surface inside the container: seven tools, one id scheme, structured errors. */
@QuarkusTest
class MnemicToolsTest {

	private static final Optional<String> NONE = Optional.empty();

	@Inject
	MnemicTools tools;

	private ToolResponse remember(String text, Map<String, Object> proposal, String key) {
		return tools.remember(Optional.of(text), NONE, NONE, NONE, Optional.empty(), NONE, NONE, proposal,
				Optional.empty(), Optional.of(key), null);
	}

	@Test
	void rememberThenRecallThroughTheTools() {
		ToolResponse remembered = remember("We decided to use SQLite for Mnemic.", null, "tools-test-1");
		assertFalse(remembered.isError(), text(remembered));
		Map<String, Object> result = result(remembered);
		assertTrue(result.get("observation_id").toString().startsWith("obs-"), result.toString());

		ToolResponse recalled = tools.recall(Optional.of("SQLite"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null);
		assertFalse(recalled.isError(), text(recalled));
		String block = text(recalled);
		assertTrue(block.startsWith("recall: \"SQLite\""), block);
		assertTrue(block.contains("We decided to use SQLite"), block);
		assertTrue(block.contains("structured: "), block);
	}

	@Test
	void structuredErrors() {
		ToolResponse blank = remember("   ", null, "tools-blank");
		assertTrue(blank.isError());
		assertEquals("INVALID_ARGUMENT", error(blank).get("code"));
		assertTrue(error(blank).get("message").toString().contains("Example"), "fix-it text");

		ToolResponse badDate = tools.recall(Optional.of("x"), Optional.of("last spring"), Optional.empty(),
				Optional.empty(), Optional.empty(), null);
		assertTrue(badDate.isError());
		assertEquals("INVALID_ARGUMENT", error(badDate).get("code"));

		ToolResponse badKind = tools.remember(Optional.of("hello"), NONE, Optional.of("telepathy"), NONE,
				Optional.empty(), NONE, NONE, null, Optional.empty(), NONE, null);
		assertTrue(badKind.isError());
		assertEquals("INVALID_ARGUMENT", error(badKind).get("code"));

		// A wrong type in a proposal is named in the caller's terms, with the fix for the confusion seen in use.
		ToolResponse badType = tools.remember(Optional.of("Lena died."), NONE, NONE, NONE, Optional.empty(), NONE, NONE,
				Map.of("facts", List.of(Map.of("subject", "Konrad Nyberg", "predicate", "spouse_of", "object",
						"Lena Berg", "ended", "yes"))),
				Optional.empty(), NONE, null);
		assertTrue(badType.isError());
		String badTypeMessage = error(badType).get("message").toString();
		assertTrue(badTypeMessage.contains("'facts[0].ended' takes a boolean (got \"yes\")"), badTypeMessage);
		assertTrue(badTypeMessage.contains("valid_time: {\"end\": \"2020\"}"), badTypeMessage);
		assertFalse(badTypeMessage.contains("java.lang"), badTypeMessage);
		// A date in 'ended' is what it looks like: the fact ended then (Sonnet 5 wrote it twice in one run).
		ToolResponse endedOn = tools.remember(Optional.of("Konrad's marriage to Lena ended in 2020."), NONE, NONE, NONE,
				Optional.empty(), NONE, NONE,
				Map.of("facts",
						List.of(Map.of("subject", "Konrad Nyberg", "predicate", "spouse_of", "object", "Lena Berg",
								"valid_time", Map.of("start", "2015"), "ended", "2020"))),
				Optional.empty(), NONE, null);
		assertFalse(endedOn.isError(), text(endedOn));
		@SuppressWarnings("unchecked")
		Map<String, Object> endedFact = ((List<Map<String, Object>>) ((Map<?, ?>) result(endedOn).get("stored"))
				.get("facts")).getFirst();
		assertEquals("ended", endedFact.get("standing"), endedFact.toString());
		assertTrue(endedFact.get("rendering").toString().contains("2015 – 2020"), endedFact.toString());

		ToolResponse nothing = tools.correct("banana", Map.of("object", "x"), NONE);
		assertTrue(nothing.isError());
		assertTrue(error(nothing).get("message").toString().contains("pred:"), text(nothing));

		ToolResponse noRef = tools.inspect("", Optional.empty(), NONE, null);
		assertTrue(noRef.isError());
		assertEquals("INVALID_ARGUMENT", error(noRef).get("code"));
	}

	@Test
	void statusReportsTheStore() {
		ToolResponse status = tools.status();
		assertFalse(status.isError(), text(status));
		Map<String, Object> result = result(status);
		assertEquals(35, ((Number) result.get("schema_version")).intValue());
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
		assertTrue(result.get("pending_proposal_ids") instanceof List, result.toString());
	}

	@Test
	@Scenario("I5")
	void aConnectorObservationGetsItsFactsThroughRememberWithAnObservationId() {
		// A connector never carries a proposal; the assistant reads the observation and attaches a reading.
		ToolResponse stored = tools.remember(
				Optional.of("From: Dario. Payment received on 11 September; handover moved to 24 September."), NONE,
				Optional.of("connector"), Optional.of("home/INBOX/943905"), Optional.empty(), NONE, NONE, null,
				Optional.empty(), Optional.of("tools-propose-1"), null);
		assertFalse(stored.isError(), text(stored));
		String obs = (String) result(stored).get("observation_id");
		long pendingBefore = ((Number) result(stored).get("pending_proposals")).longValue();
		assertTrue(pendingBefore >= 1, "the connector observation waits for a reading");

		Map<String, Object> proposal = Map.of("entities",
				List.of(Map.of("ref", "e1", "name", "Dario", "type", "person")), "events",
				List.of(Map.of("ref", "ev1", "type", "paid", "participants", List.of("self", "e1"), "valid_time",
						Map.of("start", "2026-09-11"))));
		ToolResponse attached = tools.remember(NONE, Optional.of(obs), NONE, NONE, Optional.empty(), NONE, NONE,
				proposal, Optional.empty(), NONE, null);
		assertFalse(attached.isError(), text(attached));
		@SuppressWarnings("unchecked")
		Map<String, Object> got = (Map<String, Object>) result(attached).get("stored");
		assertEquals(1, ((List<?>) got.get("events")).size(), got.toString());
		assertEquals(pendingBefore - 1, ((Number) result(attached).get("pending_proposals")).longValue(),
				"it left the backlog");
		// A second reading replaces the first: what it produced is taken back and listed, and the text keeps its id.
		Map<String, Object> again = Map.of("entities", List.of(Map.of("ref", "e1", "name", "Dario", "type", "person")),
				"facts", List.of(Map.of("subject", "self", "predicate", "knows", "object", "e1")));
		ToolResponse reread = tools.remember(NONE, Optional.of(obs), NONE, NONE, Optional.empty(), NONE, NONE, again,
				Optional.empty(), NONE, null);
		assertFalse(reread.isError(), text(reread));
		@SuppressWarnings("unchecked")
		Map<String, Object> replaced = (Map<String, Object>) result(reread).get("replaced");
		assertEquals(1, ((List<?>) replaced.get("events")).size(), replaced.toString());
		assertEquals(0, ((List<?>) replaced.get("facts")).size());
		assertEquals(0, replaced.get("reopened_facts"));
		assertEquals(1, ((List<?>) ((Map<?, ?>) result(reread).get("stored")).get("facts")).size());
		assertEquals(obs, result(reread).get("observation_id"));
		assertFalse(text(tools.inspect(obs, Optional.empty(), NONE, null)).contains("evt-"), "the old event is gone");
		// Text and observation_id together are refused, and so is an observation_id without a proposal.
		assertTrue(tools.remember(Optional.of("x"), Optional.of(obs), NONE, NONE, Optional.empty(), NONE, NONE,
				proposal, Optional.empty(), NONE, null).isError());
		assertTrue(tools.remember(NONE, Optional.of(obs), NONE, NONE, Optional.empty(), NONE, NONE, null,
				Optional.empty(), NONE, null).isError());
		// The connector rule itself still holds at remember time.
		ToolResponse refused = tools.remember(Optional.of("Another email."), NONE, Optional.of("connector"),
				Optional.of("home/INBOX/1"), Optional.empty(), NONE, NONE, proposal, Optional.empty(),
				Optional.of("tools-propose-2"), null);
		assertTrue(refused.isError());
		assertTrue(text(refused).contains("remember(observation_id, proposal)"), text(refused));
	}

	@Test
	void retiringAnObservationThroughCorrectKeepsTheFactsAndCanBeUndone() {
		Map<String, Object> proposal = Map.of("entities",
				List.of(Map.of("ref", "e1", "name", "Slack", "type", "technology")), "facts",
				List.of(Map.of("subject", "self", "predicate", "uses", "object", "e1")));
		ToolResponse stored = remember("Dad is best reached on Slack.", proposal, "tools-retire-1");
		assertFalse(stored.isError(), text(stored));
		String obs = (String) result(stored).get("observation_id");

		ToolResponse r = tools.correct(obs, Map.of("retired", true), Optional.of("it was WhatsApp"));
		assertFalse(r.isError(), text(r));
		assertEquals(obs, result(r).get("retired"));
		assertEquals(1, ((List<?>) result(r).get("facts_citing")).size(), "the fact it produced is named");
		assertTrue(result(r).containsKey("note"));
		assertEquals(1L, ((Number) result(tools.status()).get("observations_retired")).longValue());
		// The fact's history marks the observation as retired, and the observation says so itself.
		ToolResponse h = tools.inspect("Mattias Sandell", Optional.of(true), Optional.of("uses"), null);
		assertTrue(text(h).contains("observations_retired"), text(h));
		ToolResponse o = tools.inspect(obs, Optional.empty(), NONE, null);
		assertEquals(true, result(o).get("retired"), text(o));
		assertEquals("it was WhatsApp", result(o).get("retired_reason"));
		// Undo.
		ToolResponse u = tools.correct(obs, Map.of("retired", false), NONE);
		assertFalse(u.isError(), text(u));
		assertEquals(obs, result(u).get("reinstated"));
		assertEquals(0L, ((Number) result(tools.status()).get("observations_retired")).longValue());
		// A replacement without 'retired' is refused with the shape spelled out.
		assertTrue(tools.correct(obs, Map.of("text", "x"), NONE).isError());
	}

	@Test
	void withdrawingAFactThroughCorrect() {
		Map<String, Object> proposal = Map.of("facts",
				List.of(Map.of("subject", "self", "predicate", "decided", "object", "replace the printer")));
		ToolResponse stored = remember("I decided to replace the printer.", proposal, "tools-retract-1");
		assertFalse(stored.isError(), text(stored));
		String factId = firstFactId(stored);

		ToolResponse r = tools.correct(factId, Map.of("wrong", true),
				Optional.of("it was a leaning, never a decision"));
		assertFalse(r.isError(), text(r));
		assertEquals(true, result(r).get("retracted"));
		assertEquals("corrected", ((Map<?, ?>) result(r).get("original")).get("standing"));
		// Once withdrawn, it cannot be withdrawn or corrected again; inspect shows the reason.
		assertTrue(tools.correct(factId, Map.of("wrong", true), NONE).isError());
		ToolResponse f = tools.inspect(factId, Optional.empty(), NONE, null);
		assertFalse(f.isError(), text(f));
		assertEquals("corrected", result(f).get("standing"));
		assertTrue(text(f).contains("it was a leaning, never a decision"), text(f));
	}

	@Test
	void inspectShowsEveryConversationBehindAFact() {
		Map<String, Object> proposal = Map.of("entities",
				List.of(Map.of("ref", "e1", "name", "Hooli", "type", "organization")), "facts",
				List.of(Map.of("subject", "self", "predicate", "works_at", "object", "e1")));
		assertFalse(remember("I work at Hooli.", proposal, "tools-history-1").isError());
		ToolResponse again = remember("As I said, I work at Hooli.", proposal, "tools-history-2");
		assertFalse(again.isError(), text(again));

		ToolResponse history = tools.inspect("Hooli", Optional.of(true), Optional.of("works_at"), null);
		assertFalse(history.isError(), text(history));
		String h = text(history);
		assertTrue(h.contains("\"observations\":[\"obs-"), h);
		int at = h.indexOf("\"observations\":[\"obs-");
		String list = h.substring(at, h.indexOf("]", at));
		assertTrue(list.split("obs-").length - 1 >= 2, "two conversations behind the one fact: " + list);
		// The same fact by its own id, with its columns.
		ToolResponse f = tools.inspect(firstFactId(again), Optional.empty(), NONE, null);
		assertFalse(f.isError(), text(f));
		assertEquals("works_at", result(f).get("predicate"));
		assertEquals("Hooli", ((Map<?, ?>) result(f).get("object")).get("name"));
	}

	@Test
	void correctTakesAnEntityThroughTheTool() {
		ToolResponse stored = remember("I live in Kanton Luzern.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Kanton Luzern", "type", "place")), "facts",
						List.of(Map.of("subject", "self", "predicate", "lives_in", "object", "e1"))),
				"tools-entity-1");
		assertFalse(stored.isError(), text(stored));
		@SuppressWarnings("unchecked")
		String ent = (String) ((List<Map<String, Object>>) ((Map<?, ?>) result(stored).get("stored")).get("entities"))
				.getFirst().get("id");
		assertTrue(ent.startsWith("ent-"), ent);
		ToolResponse c = tools.correct(ent, Map.of("aliases", List.of("Kanton Luzern", "LU")),
				Optional.of("the canton"));
		assertFalse(c.isError(), text(c));
		assertEquals(ent, result(c).get("entity"));
		assertTrue(((Map<?, ?>) result(c).get("after")).get("aliases").toString().contains("LU"), text(c));
		assertTrue(text(tools.inspect(ent, Optional.empty(), NONE, null)).contains("LU"));
		ToolResponse bad = tools.correct(ent, Map.of("remove_aliases", List.of("x")), NONE);
		assertTrue(bad.isError());
		assertTrue(error(bad).get("message").toString().contains("aliases"), text(bad));
	}

	@Test
	void correctMovesAFactRetiresARedundantOneAndRefusesUnknownKeysThroughTheTool() {
		ToolResponse stored = remember("Katja was my partner until 2019.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Katja Berg", "type", "person")), "facts",
						List.of(Map.of("subject", "self", "predicate", "related_to", "object", "e1", "qualifier",
								"partner", "valid_time", Map.of("end", "2019")))),
				"tools-move-1");
		assertFalse(stored.isError(), text(stored));
		String id = firstFactId(stored);
		ToolResponse unknown = tools.correct(id, Map.of("relation", "partner_of"), Optional.of("typo"));
		assertTrue(unknown.isError(), text(unknown));
		assertTrue(error(unknown).get("message").toString().contains("Unknown fact property 'relation'"),
				text(unknown));
		ToolResponse moved = tools.correct(id, Map.of("predicate", "partner_of"), Optional.of("its own predicate"));
		assertFalse(moved.isError(), text(moved));
		Map<?, ?> replacement = (Map<?, ?>) result(moved).get("replacement");
		assertEquals("partner_of", replacement.get("predicate"), text(moved));
		assertTrue(replacement.get("rendering").toString().contains("partner (until 2019)"), text(moved));
		assertEquals("corrected", ((Map<?, ?>) result(moved).get("original")).get("standing"));
		// A stated relation a derivation covers is retired as redundant: superseded by the derived fact.
		ToolResponse family = remember("My father is Konrad. Britt is Konrad's sister. Britt is my aunt.", Map.of(
				"entities",
				List.of(Map.of("ref", "e1", "name", "Konrad Nyberg", "type", "person"),
						Map.of("ref", "e2", "name", "Britt Nyberg", "type", "person")),
				"facts",
				List.of(Map.of("subject", "e1", "predicate", "parent_of", "object", "self", "qualifier", "father"),
						Map.of("subject", "e2", "predicate", "sibling_of", "object", "e1", "qualifier", "sister"),
						Map.of("subject", "self", "predicate", "related_to", "object", "e2", "qualifier", "aunt"))),
				"tools-move-2");
		assertFalse(family.isError(), text(family));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> facts = (List<Map<String, Object>>) ((Map<?, ?>) result(family).get("stored"))
				.get("facts");
		String aunt = facts.stream().filter(f -> "related_to".equals(f.get("predicate"))).findFirst().orElseThrow()
				.get("id").toString();
		ToolResponse listed = tools.consolidate(Optional.of(true), Optional.empty(), Optional.empty());
		assertTrue(text(listed).contains("\"misfiled_relations\"") && text(listed).contains(aunt), text(listed));
		ToolResponse retired = tools.correct(aunt, Map.of("redundant", true), Optional.of("the chain covers it"));
		assertFalse(retired.isError(), text(retired));
		assertEquals("superseded", ((Map<?, ?>) result(retired).get("original")).get("standing"), text(retired));
		assertEquals(true, result(retired).get("retired"), "one word for the operation: " + text(retired));
		assertEquals("aunt_uncle_of", ((Map<?, ?>) result(retired).get("covered_by")).get("predicate"), text(retired));
		assertEquals(((Map<?, ?>) result(retired).get("covered_by")).get("id"), result(retired).get("superseded_by"));
		// A correction that changes nothing is refused, and leaves no record behind to replay.
		long observations = ((Number) result(tools.status()).get("observations")).longValue();
		String movedId = (String) replacement.get("id");
		ToolResponse noop = tools.correct(movedId, Map.of("qualifier", "partner"), Optional.of("as it is"));
		assertTrue(noop.isError(), text(noop));
		assertTrue(error(noop).get("message").toString().contains("changes nothing"), text(noop));
		assertEquals(observations, ((Number) result(tools.status()).get("observations")).longValue(),
				"no correction record for a refused correction");
	}

	@Test
	void aFactHasOneStandingByItsDatesOrItsRecord() {
		ToolResponse stored = remember("I worked at Initrode from 2019 to 2022.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Initrode", "type", "organization")), "facts",
						List.of(Map.of("subject", "self", "predicate", "works_at", "object", "e1", "valid_time",
								Map.of("start", "2019", "end", "2022")))),
				"tools-standing-1");
		assertFalse(stored.isError(), text(stored));
		String id = firstFactId(stored);
		assertEquals("ended",
				((Map<?, ?>) ((List<?>) ((Map<?, ?>) result(stored).get("stored")).get("facts")).getFirst())
						.get("standing"),
				"a past interval: ended, not current");
		ToolResponse f = tools.inspect(id, Optional.empty(), NONE, null);
		assertEquals("ended", result(f).get("standing"));
		assertFalse(result(f).containsKey("status") || result(f).containsKey("state"), "one field, not two");
		// A present-tense question misses, but names the ended fact rather than claiming ignorance.
		String recalled = text(tools.recall(Optional.of("where does Mattias work"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty(), null));
		assertTrue(recalled.contains(
				"no current value; 1 ended fact: Mattias Sandell works at Initrode (2019 \u2013 2022) [" + id + "]"),
				recalled);
		assertTrue(recalled.contains("include_history"), recalled);
	}

	@Test
	void inspectNamesTheRegistryAndItsEntries() {
		ToolResponse r = tools.inspect("registry", Optional.empty(), NONE, null);
		assertFalse(r.isError(), text(r));
		Map<String, Object> result = result(r);
		String preds = result.get("predicates").toString();
		assertTrue(preds.contains("id=pred:works_at"), preds);
		assertTrue(preds.contains("origin=seed"), preds);
		assertTrue(result.get("event_types").toString().contains("id=event:joined"), result.toString());
		String types = result.get("entity_types").toString();
		assertTrue(types.contains("name=country") && types.contains("parent=place"), types);

		ToolResponse one = tools.inspect("pred:works_at", Optional.empty(), NONE, null);
		assertFalse(one.isError(), text(one));
		assertEquals("works_at", result(one).get("name"));
		assertTrue(result(one).containsKey("changes"));
		assertFalse(tools.inspect("event:joined", Optional.empty(), NONE, null).isError());
		assertFalse(tools.inspect("type:place", Optional.empty(), NONE, null).isError());
		assertTrue(tools.inspect("type:spaceship", Optional.empty(), NONE, null).isError());
		// A vocabulary correction goes through the same id scheme.
		ToolResponse c = tools.correct("event:joined",
				Map.of("lexicon", List.of("joined", "join", "started", "onboarded")), Optional.of("onboarding counts"));
		assertFalse(c.isError(), text(c));
		assertTrue(text(tools.inspect("event:joined", Optional.empty(), NONE, null)).contains("onboarded"));
	}

	@Test
	void consolidateRebuildsAndReportsEventsToReRead() {
		ToolResponse stored = remember("The dealer confirmed the payment for the boat on 11 September.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Nordvik Marin", "type", "organization")),
						"events",
						List.of(Map.of("ref", "ev1", "type", "dealer confirmed receipt of the payment for the boat",
								"participants", List.of("e1", "self"), "valid_time", Map.of("start", "2026-09-11")))),
				"tools-rebuild-1");
		assertFalse(stored.isError(), text(stored));
		String obs = (String) result(stored).get("observation_id");
		ToolResponse report = tools.consolidate(Optional.of(true), Optional.empty(), Optional.empty());
		assertFalse(report.isError(), text(report));
		List<?> descriptive = (List<?>) result(report).get("descriptive_events");
		assertTrue(descriptive.stream().anyMatch(d -> obs.equals(((Map<?, ?>) d).get("observation"))),
				descriptive.toString());
		assertFalse(result(report).containsKey("rebuilt"), "a dry run never rebuilds");
		ToolResponse rebuilt = tools.consolidate(Optional.of(false), Optional.empty(), Optional.of(true));
		assertFalse(rebuilt.isError(), text(rebuilt));
		@SuppressWarnings("unchecked")
		Map<String, Object> r = (Map<String, Object>) result(rebuilt).get("rebuilt");
		assertTrue(((Number) r.get("observations")).intValue() >= 1, r.toString());
		assertEquals(List.of(), r.get("unmatched"));
		assertTrue(r.containsKey("facts_before") && r.containsKey("facts_after"));
	}

	@SuppressWarnings("unchecked")
	private static String firstFactId(ToolResponse stored) {
		List<Map<String, Object>> facts = (List<Map<String, Object>>) ((Map<?, ?>) result(stored).get("stored"))
				.get("facts");
		assertFalse(facts.isEmpty(), "no fact stored: " + text(stored));
		return (String) facts.getFirst().get("id");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aRelationWithADirectionSaysHowToTurnItAround() {
		// "My father is Konrad" read with the parent as the object: the reply names the fact as it stands and the call
		// that switches it, and the no-change error names the same move (usage bench, 2026-09-24).
		ToolResponse r = remember("My father is Direction Father.", Map.of("facts", List.of(Map.of("subject", "self",
				"predicate", "parent_of", "object", "Direction Father", "qualifier", "father"))), "tools-direction-1");
		assertFalse(r.isError(), text(r));
		Map<String, Object> stored = (Map<String, Object>) result(r).get("stored");
		Map<String, Object> fact = ((List<Map<String, Object>>) stored.get("facts")).getFirst();
		String id = fact.get("id").toString();
		String direction = fact.get("direction").toString();
		assertTrue(direction.startsWith("to switch to the other way around use correct(\"" + id + "\", "), direction);
		assertTrue(direction.contains("\"subject\": \"Direction Father\""), direction);
		assertTrue(direction.contains("\"object\": \"" + fact.get("rendering").toString().split(" is ")[0] + "\""),
				direction);

		ToolResponse same = tools.correct(id, Map.of("qualifier", "father"), NONE);
		assertTrue(same.isError(), text(same));
		String message = error(same).get("message").toString();
		// The owner spelled out by name is still the owner: restating both sides as they stand is no change either.
		ToolResponse spelled = tools.correct(id,
				Map.of("subject", fact.get("rendering").toString().split(" is ")[0], "object", "Direction Father"),
				NONE);
		assertTrue(spelled.isError(), text(spelled));
		assertTrue(error(spelled).get("message").toString().contains("changes nothing"), text(spelled));
		assertTrue(
				message.contains("To switch subject and object around, give both: {\"subject\": \"Direction Father\""),
				message);

		Map<String, Object> swap = Map.of("subject", "Direction Father", "object",
				fact.get("rendering").toString().split(" is ")[0]);
		ToolResponse turned = tools.correct(id, swap, Optional.of("the text says my father"));
		assertFalse(turned.isError(), text(turned));
		Map<String, Object> replacement = (Map<String, Object>) result(turned).get("replacement");
		assertTrue(replacement.get("rendering").toString().startsWith("Direction Father is "), replacement.toString());
		assertTrue(
				replacement.get("direction").toString().contains("\"subject\": \""
						+ fact.get("rendering").toString().split(" is ")[0] + "\", \"object\": \"Direction Father\""),
				replacement.toString());

		// No direction where the sides cannot swap (a person in a place) or the relation is symmetric.
		ToolResponse job = remember("Ulla Qvist lives in Testholm.",
				Map.of("entities",
						List.of(Map.of("name", "Ulla Qvist", "type", "person"),
								Map.of("name", "Testholm", "type", "place")),
						"facts",
						List.of(Map.of("subject", "Ulla Qvist", "predicate", "lives_in", "object", "Testholm"))),
				"tools-direction-2");
		assertFalse(job.isError(), text(job));
		List<Map<String, Object>> jobFacts = (List<Map<String, Object>>) ((Map<String, Object>) result(job)
				.get("stored")).get("facts");
		assertFalse(jobFacts.isEmpty(), text(job));
		Map<String, Object> jobFact = jobFacts.getFirst();
		assertFalse(jobFact.containsKey("direction"), jobFact.toString());
		ToolResponse wife = remember(
				"Ulla Qvist is married to Per Brorsson.", Map.of("facts", List.of(Map.of("subject", "Per Brorsson",
						"predicate", "spouse_of", "object", "Ulla Qvist", "qualifier", "husband"))),
				"tools-direction-3");
		assertFalse(wife.isError(), text(wife));
		Map<String, Object> wifeFact = ((List<Map<String, Object>>) ((Map<String, Object>) result(wife).get("stored"))
				.get("facts")).getFirst();
		assertFalse(wifeFact.containsKey("direction"), wifeFact.toString());
	}

	@Test
	void aReadRepeatedVerbatimGetsANoteUntilSomethingIsWritten() {
		// A weaker model re-issues the same recall until it runs out of calls; the note stops it (2026-09-24).
		assertFalse(remember("Repeat Tester likes pears.", null, "tools-repeat-1").isError());
		ToolResponse first = tools.recall(Optional.of("Repeat Tester pears"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null);
		assertTrue(text(first).startsWith("recall: \"Repeat Tester pears\""), text(first));
		ToolResponse again = tools.recall(Optional.of("Repeat Tester pears"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null);
		assertFalse(again.isError(), text(again));
		assertEquals(MnemicTools.REPEATED_NOTE, text(again));
		// A different question, or anything written since, is answered in full.
		ToolResponse other = tools.recall(Optional.of("Repeat Tester apples"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null);
		assertTrue(text(other).startsWith("recall: \"Repeat Tester apples\""), text(other));
		ToolResponse back = tools.recall(Optional.of("Repeat Tester pears"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null);
		assertTrue(text(back).startsWith("recall: \"Repeat Tester pears\""), "not consecutive: " + text(back));
		assertFalse(remember("Repeat Tester likes plums too.", null, "tools-repeat-2").isError());
		ToolResponse afterWrite = tools.recall(Optional.of("Repeat Tester pears"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty(), null);
		assertTrue(text(afterWrite).startsWith("recall: \"Repeat Tester pears\""), text(afterWrite));
		// inspect the same way, as a result object.
		ToolResponse look = tools.inspect("registry", Optional.empty(), NONE, null);
		assertFalse(look.isError(), text(look));
		ToolResponse lookAgain = tools.inspect("registry", Optional.empty(), NONE, null);
		assertEquals(true, result(lookAgain).get("repeated"), text(lookAgain));
		// An error repeated is an error again: its message is what says what to change.
		assertTrue(tools.inspect("", Optional.empty(), NONE, null).isError());
		assertTrue(tools.inspect("", Optional.empty(), NONE, null).isError(), "an error is not memoised");
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

	@Test
	void impliedAttributesFlowThroughTheTool() {
		// A derived predicate from the client side, with an attribute-driven qualifier and implications.
		ToolResponse defined = remember("Nieces and nephews.", Map.of("predicates", List.of(Map.of("name", "nibling_of",
				"description", "Subject is a child of a sibling of object.", "domain", "person", "range", "person",
				"lexicon", List.of("nephew", "nephews", "niece", "nieces", "nibling"), "render",
				"{subject} is {object}'s {qualifier|nibling}", "qualifiers", List.of("nephew", "niece"), "defined_as",
				List.of(Map.of("path", List.of("^parent_of", "sibling_of"), "qualifier", "nibling", "by",
						Map.of("attribute", "gender", "values", Map.of("male", "nephew", "female", "niece")))),
				"implies", Map.of("nephew", Map.of("gender", "male"), "niece", Map.of("gender", "female"))))),
				"tools-nibling-1");
		assertFalse(defined.isError(), text(defined));
		assertTrue(text(defined).contains("\"definitions\""), text(defined));
		ToolResponse optIn = tools.correct("pred:sibling_of",
				Map.of("implies", Map.of("sister", Map.of("gender", "female"), "brother", Map.of("gender", "male"))),
				Optional.of("we trust them"));
		assertFalse(optIn.isError(), text(optIn));
		ToolResponse family = remember(
				"Nibtest Parent is Nibtest Kid's father. Nibtest Aunt is Nibtest Parent's sister.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Nibtest Parent", "type", "person"),
								Map.of("ref", "e2", "name", "Nibtest Kid", "type",
										"person"),
								Map.of("ref", "e3", "name", "Nibtest Aunt", "type", "person")),
						"facts",
						List.of(Map.of("subject", "e1", "predicate", "parent_of", "object", "e2", "qualifier",
								"father"),
								Map.of("subject", "e3", "predicate", "sibling_of", "object", "e1", "qualifier",
										"sister"))),
				"tools-nibling-2");
		assertFalse(family.isError(), text(family));
		String aunt = text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE, null));
		assertTrue(aunt.contains("Nibtest Aunt is Nibtest Kid's aunt\""), "the sister role implies female: " + aunt);
		String kid = text(tools.inspect("Nibtest Kid", Optional.empty(), NONE, null));
		assertTrue(kid.contains("Nibtest Kid is Nibtest Aunt's nibling"), "no gender for the kid yet: " + kid);
		ToolResponse nephew = remember("Nibtest Kid is Nibtest Aunt's nephew.", Map.of("entities",
				List.of(Map.of("ref", "e1", "name", "Nibtest Kid", "type", "person"),
						Map.of("ref", "e2", "name", "Nibtest Aunt", "type", "person")),
				"facts",
				List.of(Map.of("subject", "e1", "predicate", "nibling_of", "object", "e2", "qualifier", "nephew"))),
				"tools-nibling-3");
		assertFalse(nephew.isError(), text(nephew));
		String gaps = text(tools.consolidate(Optional.of(true), Optional.empty(), Optional.empty()));
		assertTrue(gaps.contains("\"attribute_unknown\":[]"), "both have a gender by implication: " + gaps);
		// The implied values stand as facts: asked about, and shown with what implies them.
		String asked = text(tools.recall(Optional.of("what is Nibtest Kid's gender"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty(), null));
		assertTrue(asked.contains("Nibtest Kid is male"), asked);
		assertTrue(text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE, null)).contains("Nibtest Aunt is female"),
				text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE, null)));
	}

	@Test
	void aDuplicateEntityIsListedFoldedOrForgottenThroughTheTools() {
		ToolResponse first = remember("I work at Grankulla.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Grankulla", "type", "organization")), "facts",
						List.of(Map.of("subject", "self", "predicate", "works_at", "object", "e1"))),
				"tools-dup-1");
		assertFalse(first.isError(), text(first));
		ToolResponse second = remember("I live in Grankulla.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Grankulla", "type", "place")), "facts",
						List.of(Map.of("subject", "self", "predicate", "lives_in", "object", "e1"))),
				"tools-dup-2");
		assertFalse(second.isError(), text(second));
		@SuppressWarnings("unchecked")
		String company = ((List<Map<String, Object>>) ((Map<?, ?>) result(first).get("stored")).get("entities"))
				.getFirst().get("id").toString();
		// The same name under another kind is asked, the exact match first; "new" makes it another thing.
		@SuppressWarnings("unchecked")
		Map<String, Object> q = ((List<Map<String, Object>>) result(second).get("questions")).getFirst();
		assertEquals("entity_resolution", q.get("kind"), text(second));
		assertTrue(q.get("message").toString().contains("Grankulla (organization)"), text(second));
		ToolResponse answered = tools.remember(NONE, NONE, NONE, NONE, Optional.empty(), NONE, NONE, null,
				Optional.empty(), Optional.of("tools-dup-2-answer"),
				List.of(Map.of("question_id", q.get("id").toString(), "choice", "new")));
		assertFalse(answered.isError(), text(answered));
		String listed = text(tools.consolidate(Optional.of(true), Optional.empty(), Optional.empty()));
		assertTrue(listed.contains("\"name_collisions\"") && listed.contains("\"shared\":\"Grankulla\""), listed);
		@SuppressWarnings("unchecked")
		Map<String, Object> collision = ((List<Map<String, Object>>) result(
				tools.consolidate(Optional.of(true), Optional.empty(), Optional.empty())).get("name_collisions"))
				.stream().filter(c -> "Grankulla".equals(c.get("shared"))).findFirst().orElseThrow();
		String town = company.equals(collision.get("entity")) ? collision.get("and").toString()
				: collision.get("entity").toString();
		// An entity facts name is not forgotten; the refusal names them and the fold.
		ToolResponse refused = tools.forget(town, Optional.empty());
		assertTrue(refused.isError(), text(refused));
		assertTrue(error(refused).get("message").toString().contains("merge_into"), text(refused));
		// The fold, through correct.
		ToolResponse folded = tools.correct(town, Map.of("merge_into", company), Optional.of("one place"));
		assertFalse(folded.isError(), text(folded));
		assertEquals(company, result(folded).get("entity"), text(folded));
		assertTrue(result(folded).containsKey("merged"), text(folded));
		// An entity left alone by a re-seed (its observation forgotten, entities kept) is forgotten by id.
		ToolResponse third = remember("I own a Segway Navimow.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Segway Navimow", "type", "thing")), "facts",
						List.of(Map.of("subject", "self", "predicate", "owns", "object", "e1"))),
				"tools-dup-3");
		assertFalse(third.isError(), text(third));
		@SuppressWarnings("unchecked")
		String lone = ((List<Map<String, Object>>) ((Map<?, ?>) result(third).get("stored")).get("entities")).getFirst()
				.get("id").toString();
		assertFalse(tools.forget(result(third).get("observation_id").toString(), Optional.of(true)).isError());
		ToolResponse gone = tools.forget(lone, Optional.empty());
		assertFalse(gone.isError(), text(gone));
		assertEquals(true, result(gone).get("removed"), text(gone));
		assertTrue(tools.inspect(lone, Optional.empty(), NONE, null).isError(), "gone for good");
	}

	@Test
	void anEventNamesTheObservationsBehindIt() {
		ToolResponse first = remember("Sven Alm retired.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Sven Alm", "type", "person")), "events",
						List.of(Map.of("ref", "ev1", "type", "retired", "participants", List.of("e1")))),
				"tools-event-source-1");
		assertFalse(first.isError(), text(first));
		ToolResponse second = remember("Sven Alm retired in 2020.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Sven Alm", "type", "person")), "events",
						List.of(Map.of("ref", "ev1", "type", "retired", "participants", List.of("e1"), "valid_time",
								Map.of("start", "2020")))),
				"tools-event-source-2");
		assertFalse(second.isError(), text(second));
		@SuppressWarnings("unchecked")
		String evt = (String) ((List<Map<String, Object>>) ((Map<?, ?>) result(second).get("stored")).get("events"))
				.getFirst().get("id");
		Map<String, Object> shown = result(tools.inspect(evt, Optional.empty(), NONE, null));
		assertEquals(2, ((List<?>) shown.get("observations")).size(), shown.toString());
		assertEquals(shown.get("observation"), ((List<?>) shown.get("observations")).getFirst(), "its home first");
	}

	@Test
	void groupsAndLastingGoThroughTheTools() {
		Map<String, Object> registry = result(tools.inspect("registry", Optional.empty(), NONE, null));
		String groups = registry.get("groups").toString();
		assertTrue(groups.contains("id=group:family") && groups.contains("parent_of"), groups);
		ToolResponse family = tools.inspect("group:family", Optional.empty(), NONE, null);
		assertFalse(family.isError(), text(family));
		assertTrue(result(family).get("members").toString().contains("sibling_of"), text(family));
		ToolResponse c = tools.correct("group:family",
				Map.of("lexicon", List.of("family", "families", "relatives", "kin", "clan")), Optional.of("clan too"));
		assertFalse(c.isError(), text(c));
		assertTrue(text(tools.inspect("group:family", Optional.empty(), NONE, null)).contains("clan"));
		assertTrue(tools.inspect("group:nope", Optional.empty(), NONE, null).isError());
		assertTrue(tools.correct("group:nope", Map.of("lexicon", List.of("x")), NONE).isError());
		// A predicate's groups and its lasting flag are correctable by the same id scheme, and shown.
		ToolResponse p = tools.correct("pred:knows", Map.of("lasting", true, "groups", List.of("acquaintance")),
				Optional.of("a friend stays a friend"));
		assertFalse(p.isError(), text(p));
		Map<String, Object> knows = result(tools.inspect("pred:knows", Optional.empty(), NONE, null));
		assertEquals(true, knows.get("lasting"));
		assertEquals(false, result(tools.inspect("pred:spouse_of", Optional.empty(), NONE, null)).get("lasting"),
				"said either way, so a client can tell not lasting from not known");
		assertFalse(
				result(tools.inspect("pred:spouse_of", Optional.empty(), NONE, null)).containsKey("lasting_assumed"),
				"the seed decided");
		assertFalse(knows.containsKey("lasting_assumed"), "a correction decided");
		// A definition that says nothing about lasting is answered with what was assumed, and inspect shows it.
		ToolResponse coached = remember("Sten Alm coaches me.",
				Map.of("predicates",
						List.of(Map.of("name", "coached_by_tool", "domain", "person", "range", "person", "lexicon",
								List.of("coached by"))),
						"entities", List.of(Map.of("ref", "e1", "name", "Sten Alm", "type", "person")), "facts",
						List.of(Map.of("subject", "self", "predicate", "coached_by_tool", "object", "e1"))),
				"tools-lasting-assumed");
		assertFalse(coached.isError(), text(coached));
		String definitions = String.valueOf(result(coached).get("definitions"));
		assertTrue(definitions.contains("coached_by_tool") && definitions.contains("lasting=false")
				&& definitions.contains("correct(\"pred:coached_by_tool\", {\"lasting\": true})"), definitions);
		Map<String, Object> coachedBy = result(tools.inspect("pred:coached_by_tool", Optional.empty(), NONE, null));
		assertEquals(false, coachedBy.get("lasting"));
		assertEquals(true, coachedBy.get("lasting_assumed"));
		assertEquals(List.of("acquaintance"), knows.get("groups"));
		assertFalse(tools.inspect("group:acquaintance", Optional.empty(), NONE, null).isError(),
				"registered from its first mention");
		// A comma-separated string is a list too, as everywhere else in correct.
		ToolResponse two = tools.correct("pred:knows", Map.of("groups", "acquaintance, contacts"), NONE);
		assertFalse(two.isError(), text(two));
		assertEquals(List.of("acquaintance", "contacts"),
				result(tools.inspect("pred:knows", Optional.empty(), NONE, null)).get("groups"));
	}

	@Test
	void theProtocolIsServedWholeFromItsTwoFiles() {
		// The instructions ride in the MCP initialize reply, through the config the MCP library reads.
		String served = ConfigProvider.getConfig().getValue("quarkus.mcp.server.server-info.instructions",
				String.class);
		assertEquals(Protocol.instructions(), served);
		assertTrue(served.startsWith("# Mnemic memory protocol"), served);
		assertTrue(served.contains("RECALL BEFORE YOU ANSWER") && served.contains("inspect('guide')"), served);
		assertFalse(served.contains("## Vocabulary"), "the detail is not repeated in the instructions");
		// Claude Code puts every server's instructions in one block of about 4 KB and cuts the rest silently
		// (anthropics/claude-code#43474), so with other servers configured only a short text arrives whole.
		assertTrue(served.length() <= 2000, "instructions are " + served.length() + " chars; keep them under 2000");
		// The four rules a proposal cannot do without ride in the instructions, so the guide is optional reading.
		assertTrue(served.contains("considering") && served.contains("negated") && served.contains("ISO")
				&& served.contains("kinship"), served);
		assertTrue(Protocol.guide().length() <= 4500, "the guide is " + Protocol.guide().length() + " chars; a "
				+ "session fetches it whole, keep it under 4500");
		// The guide is fetched through inspect, and is the other file whole.
		ToolResponse guide = tools.inspect("guide", Optional.empty(), NONE, null);
		assertFalse(guide.isError(), text(guide));
		assertEquals(Protocol.guide(), result(guide).get("text"));
		assertTrue(Protocol.guide().contains("## Vocabulary") && Protocol.guide().contains("lasting: true"));
		assertFalse(Protocol.guide().contains("RECALL BEFORE YOU ANSWER"), "the loop is not repeated in the guide");
		// An assistant that reads only the tool descriptions is still told where the guide is.
		assertTrue(ToolDescriptions.INSPECT.contains("'guide'"), ToolDescriptions.INSPECT);
		assertTrue(tools.inspect("", Optional.empty(), NONE, null).isError());
		assertTrue(text(tools.inspect("", Optional.empty(), NONE, null)).contains("'guide'"), "the id list names it");
	}

	@Test
	void aTypeAPredicateNamesExistsFromThenOnAndAListIsADomain() {
		// A definition may write domain and range as lists, and may name a type the registry has not seen: the
		// predicate is the statement that the type exists, so the type is registered with it (2026-09-21, Fable run:
		// the store offered kind:animal as an answer and then refused it for want of a type named animal).
		Map<String, Object> proposal = Map.of("predicates",
				List.of(Map.of("name", "keeps_pet", "description", "The subject keeps the object as a pet", "domain",
						List.of("person"), "range", List.of("critter"))),
				"entities", List.of(Map.of("ref", "b", "name", "Bello", "type", "hound")), "facts",
				List.of(Map.of("subject", "self", "predicate", "keeps_pet", "object", "b")));
		ToolResponse stored = tools.remember(Optional.of("Our hound is called Bello."), NONE, NONE, NONE,
				Optional.empty(), NONE, NONE, proposal, Optional.empty(), Optional.of("type-from-predicate"), null);
		assertFalse(stored.isError(), text(stored));
		Map<String, Object> pred = result(tools.inspect("pred:keeps_pet", Optional.empty(), NONE, null));
		assertEquals(List.of("person"), pred.get("domain"), pred.toString());
		assertEquals(List.of("critter"), pred.get("range"), pred.toString());
		assertFalse(tools.inspect("type:critter", Optional.empty(), NONE, null).isError(),
				"named by the range, so it exists");
		// The mismatch (a hound is not known to be a critter) is asked, and the offered kind can be chosen.
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> questions = (List<Map<String, Object>>) result(stored).get("questions");
		Map<String, Object> mismatch = questions.stream().filter(q -> "type_mismatch".equals(q.get("kind"))).findFirst()
				.orElseThrow(() -> new AssertionError("no type_mismatch question: " + questions));
		ToolResponse answered = tools.remember(NONE, NONE, NONE, NONE, Optional.empty(), NONE, NONE, null,
				Optional.empty(), NONE, List.of(Map.of("question_id", mismatch.get("id"), "choice", "kind:critter")));
		assertFalse(answered.isError(), text(answered));
		assertEquals("critter", result(tools.inspect("type:hound", Optional.empty(), NONE, null)).get("parent"));
	}

	@Test
	void anAnswerGivenWithAReReadingIsGivenFirst() {
		// An ambiguity, then the assistant's natural move: answer "new" and resend the reading in one call. The
		// answer must not be dropped (it was, silently, and one assistant asked the same question three times).
		remember("I met Ingrid Lund at the conference.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Ingrid Lund", "type", "person")), "facts",
						List.of(Map.of("subject", "self", "predicate", "knows", "object", "e1"))),
				"reread-resolve-1");
		Map<String, Object> reading = Map.of("entities",
				List.of(Map.of("ref", "e1", "name", "Ingrid", "type", "person")), "facts",
				List.of(Map.of("subject", "e1", "predicate", "works_at", "object", "Hooli")));
		ToolResponse asked = remember("Ingrid is joining Hooli.", reading, "reread-resolve-2");
		assertFalse(asked.isError(), text(asked));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> questions = (List<Map<String, Object>>) result(asked).get("questions");
		assertEquals(1, questions.size(), questions.toString());
		String obs = (String) result(asked).get("observation_id");
		ToolResponse answeredAndReread = tools.remember(NONE, Optional.of(obs), NONE, NONE, Optional.empty(), NONE,
				NONE, reading, Optional.empty(), NONE,
				List.of(Map.of("question_id", questions.getFirst().get("id"), "choice", "new")));
		assertFalse(answeredAndReread.isError(), text(answeredAndReread));
		Map<String, Object> out = result(answeredAndReread);
		assertTrue(out.get("resolved").toString().contains("answered"), "the answer was applied: " + out);
		assertTrue(((List<?>) out.getOrDefault("questions", List.of())).isEmpty(),
				"the new reading resolves to the entity the answer created: " + out);
		Map<String, Object> ingrid = result(tools.inspect("Ingrid", Optional.empty(), NONE, null));
		Map<String, Object> lund = result(tools.inspect("Ingrid Lund", Optional.empty(), NONE, null));
		assertNotEquals(ingrid.get("id"), lund.get("id"), "a new Ingrid, as answered: " + ingrid + " / " + lund);
		assertTrue(ingrid.get("facts").toString().contains("works_at"), "the re-reading's fact is on her: " + ingrid);
		assertFalse(lund.get("facts").toString().contains("works_at"), "and not on Ingrid Lund: " + lund);
	}

	@Test
	void aNameThatLandsOnTheWrongKindOfThingIsReadAsItsNamesakeOfTheRightKind() {
		// The maker and the car share a name: "the Polestar" spots the organization exactly, and plates are a
		// vehicle's. The structured verdict said MISS while the lexical hit was the plate fact (2026-09-22).
		remember("Polestar issued me a digital key for the car.",
				Map.of("entities", List.of(Map.of("ref", "e1", "name", "Polestar", "type", "organization")), "facts",
						List.of(Map.of("subject", "self", "predicate", "uses", "object", "e1"))),
				"namesake-1");
		Map<String, Object> plate = Map.of("entities", List.of(Map.of("ref", "e1", "name",
				"Polestar 4 Long Range Dual Motor Prime", "type", "vehicle", "aliases", List.of("Polestar 4"))),
				"predicates",
				List.of(Map.of("name", "has_plate", "description", "A vehicle carries this registration plate.",
						"domain", "vehicle", "range", "*", "lexicon", List.of("plate", "registration plate"))),
				"facts", List.of(Map.of("subject", "e1", "predicate", "has_plate", "object", "SZ 121742")));
		ToolResponse stored = remember("The plate for the Polestar is SZ 121742.", plate, "namesake-2");
		assertFalse(stored.isError(), text(stored));
		String block = text(tools.recall(Optional.of("What is the registration plate for the Polestar again?"), NONE,
				Optional.of(600), Optional.empty(), Optional.empty(), null));
		assertTrue(block.contains("structured: matched"), block);
		assertTrue(block.contains("SZ 121742"), block);
		assertTrue(block.contains("'Polestar' read as Polestar 4 Long Range Dual Motor Prime"), block);
		// Asked about the organization with a predicate that does apply to it, the organization answers as before.
		String org = text(tools.recall(Optional.of("what does Mattias use"), NONE, Optional.of(400), Optional.empty(),
				Optional.empty(), null));
		assertTrue(org.contains("uses Polestar") || org.contains("uses → 1 fact"), org);
		// And a name with no namesake of the right kind is still a MISS, not a guess.
		String miss = text(tools.recall(Optional.of("what is the registration plate for Hooli"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty(), null));
		assertTrue(miss.contains("MISS") || miss.contains("unresolved"), miss);
	}

	@Test
	void anOpenConflictBetweenAPlaceAndAPlaceWithinItTakesBoth() {
		// "Lives in Küssnacht" on record, then "lives at Gschweighusweg 20b" with nothing yet saying where 20b is:
		// asked (q-52, 2026-09-22). The assistant states the containment and answers "both"; both facts stand.
		remember("Ossian Nyberg lives in Küssnacht am Rigi.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Ossian Nyberg", "type", "person"),
								Map.of("ref", "e2", "name", "Küssnacht am Rigi", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "lives_in", "object", "e2"))),
				"both-1");
		ToolResponse house = remember("Ossian lives at Gschweighusweg 20b.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Ossian Nyberg", "type", "person"),
								Map.of("ref", "e2", "name", "Gschweighusweg 20b", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "lives_in", "object", "e2"))),
				"both-2");
		assertFalse(house.isError(), text(house));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> questions = (List<Map<String, Object>>) result(house).get("questions");
		assertEquals(1, questions.size(), text(house));
		assertEquals("conflict", questions.getFirst().get("kind"), text(house));
		String q = questions.getFirst().get("id").toString();
		// Before the containment is on record, "both" is refused and says what would make it apply.
		ToolResponse early = tools.remember(NONE, NONE, NONE, NONE, Optional.empty(), NONE, NONE, null,
				Optional.empty(), Optional.of("both-3"), List.of(Map.of("question_id", q, "choice", "both")));
		assertTrue(early.isError(), text(early));
		assertTrue(text(early).contains("Gschweighusweg 20b located in Küssnacht am Rigi"), text(early));
		// Answers are applied before a call's proposal, so the containment goes in first, then the answer.
		ToolResponse contained = remember("Gschweighusweg 20b is in Küssnacht am Rigi.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Gschweighusweg 20b", "type", "place"),
								Map.of("ref", "e2", "name", "Küssnacht am Rigi", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "located_in", "object", "e2"))),
				"both-4");
		assertFalse(contained.isError(), text(contained));
		ToolResponse both = tools.remember(NONE, NONE, NONE, NONE, Optional.empty(), NONE, NONE, null, Optional.empty(),
				Optional.of("both-5"), List.of(Map.of("question_id", q, "choice", "both")));
		assertFalse(both.isError(), text(both));
		assertTrue(text(both).contains("\"status\":\"answered\""), text(both));
		// Through inspect: the question shows what answered it, the fact's changes say a person did.
		String question = text(tools.inspect(q, Optional.empty(), NONE, null));
		assertTrue(question.contains("\"answer\":\"both\""), question);
		String pendingRef = questions.getFirst().get("pending").toString();
		String fact = text(tools.inspect(pendingRef, Optional.empty(), NONE, null));
		assertTrue(fact.contains("\"kind\":\"nested\"") && fact.contains("user: both; stands beside"), fact);
		String block = text(tools.recall(Optional.of("where does Ossian Nyberg live"), NONE, Optional.of(600),
				Optional.empty(), Optional.empty(), null));
		assertTrue(block.contains("lives in Küssnacht am Rigi") && block.contains("lives in Gschweighusweg 20b"),
				block);
		assertFalse(block.contains("pending"), block);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aQuestionOpenedWhileAnsweringIsReportedWithTheAnswer() {
		// A held fact applied by an answer can open a question of its own (here a conflict): it is reported at the top
		// of the reply that answered, not left for status to reveal (2026-09-23).
		remember("Ylva Strand lives in Lund.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Ylva Strand", "type", "person"),
								Map.of("ref", "e2", "name", "Lund", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "lives_in", "object", "e2"))),
				"opened-1");
		ToolResponse held = remember("Ylva lives in Malmö now.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Ylva", "type", "person"),
								Map.of("ref", "e2", "name", "Malmö", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "lives_in", "object", "e2"))),
				"opened-2");
		List<Map<String, Object>> asked = (List<Map<String, Object>>) result(held).get("questions");
		Map<String, Object> who = asked.stream().filter(q -> "entity_resolution".equals(q.get("kind"))).findFirst()
				.orElseThrow(() -> new AssertionError(text(held)));
		String candidate = ((List<Map<String, Object>>) who.get("candidates")).stream().map(c -> c.get("id").toString())
				.filter(id -> id.startsWith("ent-")).findFirst().orElseThrow();
		ToolResponse answered = tools.remember(NONE, NONE, NONE, NONE, Optional.empty(), NONE, NONE, null,
				Optional.empty(), Optional.of("opened-3"),
				List.of(Map.of("question_id", who.get("id").toString(), "choice", candidate)));
		assertFalse(answered.isError(), text(answered));
		List<Map<String, Object>> opened = (List<Map<String, Object>>) result(answered).get("questions");
		assertTrue(opened != null && opened.stream().anyMatch(q -> "conflict".equals(q.get("kind"))),
				"the conflict the applied fact opened is in the answer's reply: " + text(answered));
	}

	@Test
	void anEventIsMovedThroughCorrect() {
		ToolResponse stored = remember("Pelle Wiklund moved to Tjörn in 2021.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Pelle Wiklund", "type", "person"),
								Map.of("ref", "e2", "name", "Tjörn", "type", "place")),
						"events", List.of(Map.of("ref", "ev1", "type", "moved", "participants", List.of("e1", "e2"),
								"valid_time", Map.of("start", "2021")))),
				"evt-move-1");
		assertFalse(stored.isError(), text(stored));
		@SuppressWarnings("unchecked")
		String evt = ((List<Map<String, Object>>) ((Map<String, Object>) result(stored).get("stored")).get("events"))
				.getFirst().get("id").toString();
		ToolResponse moved = tools.correct(evt, Map.of("valid_time", Map.of("start", "2020-09")),
				Optional.of("it was the autumn before"));
		assertFalse(moved.isError(), text(moved));
		assertTrue(text(moved).contains("\"after\"") && text(moved).contains("2020-09"), text(moved));
		String inspected = text(tools.inspect(evt, Optional.empty(), NONE, null));
		assertTrue(inspected.contains("2020-09"), inspected);
		String home = text(tools.recall(Optional.of("where does Pelle Wiklund live"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty(), null));
		assertTrue(home.contains("Tjörn") && home.contains("2020-09"), home);
		ToolResponse refused = tools.correct(evt, Map.of("type", "relocated"), NONE);
		assertTrue(refused.isError() && text(refused).contains("valid_time"), text(refused));
	}

	@Test
	void aNameIsAnEntityInEveryToolAndAPredicateIsAlwaysPrefixed() {
		// inspect reads a bare name as an entity; correct did too, except when it was a predicate. One rule now: a
		// predicate is pred:<name> in both, and a bare predicate name is refused with the prefix (2026-09-23).
		ToolResponse bare = tools.correct("works_at", Map.of("lexicon", List.of("job")), NONE);
		assertTrue(bare.isError() && text(bare).contains("pred:works_at"), text(bare));
		ToolResponse prefixed = tools.correct("pred:works_at", Map.of("lexicon", List.of("work", "works", "job")),
				Optional.of("one more cue word"));
		assertFalse(prefixed.isError(), text(prefixed));
		// The replaced facts of a re-reading are described with the same word as stored facts: 'standing'.
		ToolResponse first = remember("Nils Vik lives in Gävle.",
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Nils Vik", "type", "person"),
								Map.of("ref", "e2", "name", "Gävle", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "lives_in", "object", "e2"))),
				"rename-1");
		String obs = result(first).get("observation_id").toString();
		ToolResponse reread = tools.remember(NONE, Optional.of(obs), NONE, NONE, Optional.empty(), NONE, NONE,
				Map.of("entities",
						List.of(Map.of("ref", "e1", "name", "Nils Vik", "type", "person"),
								Map.of("ref", "e2", "name", "Gävle", "type", "place")),
						"facts", List.of(Map.of("subject", "e1", "predicate", "born_in", "object", "e2"))),
				Optional.empty(), Optional.of("rename-2"), null);
		assertFalse(reread.isError(), text(reread));
		assertTrue(text(reread).contains("\"replaced\"") && text(reread).contains("\"standing\""), text(reread));
		assertFalse(text(reread).contains("\"status\":\"current\""),
				"no second word for the same thing: " + text(reread));
	}

	@Test
	void asOfTakesAYearOrAMonthAndMeansItsEnd() {
		assertEquals("2015-12-31T23:59:59Z", MnemicTools.instant("2015").toString());
		assertEquals("2016-02-29T23:59:59Z", MnemicTools.instant("2016-02").toString());
		assertEquals("2015-06-01T23:59:59Z", MnemicTools.instant(" 2015-06-01 ").toString());
		assertEquals("2026-09-06T10:12:00Z", MnemicTools.instant("2026-09-06T10:12:00Z").toString());
		assertTrue(assertThrows(MnemicException.class, () -> MnemicTools.instant("March 2018")).getMessage()
				.contains("2015-06"));
		// Through the tool: a year is enough to ask about a point in the past.
		remember(
				"Pelle Wiklund worked at Tjörn Varv from 2010 to March 2018.", Map
						.of("facts",
								List.of(Map.of("subject", "Pelle Wiklund", "predicate", "works_at", "object",
										"Tjörn Varv", "valid_time", Map.of("start", "2010", "end", "2018-03")))),
				"as-of-year");
		ToolResponse r = tools.recall(Optional.of("where does Pelle Wiklund work"), Optional.of("2015"),
				Optional.empty(), Optional.empty(), Optional.empty(), null);
		assertFalse(r.isError(), text(r));
		assertTrue(text(r).contains("as of 2015-12-31"), text(r));
	}

}
