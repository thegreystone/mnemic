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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
				Optional.empty());
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
				Optional.empty(), Optional.empty());
		assertTrue(badDate.isError());
		assertEquals("INVALID_ARGUMENT", error(badDate).get("code"));

		ToolResponse badKind = tools.remember(Optional.of("hello"), NONE, Optional.of("telepathy"), NONE,
				Optional.empty(), NONE, NONE, null, Optional.empty(), NONE, null);
		assertTrue(badKind.isError());
		assertEquals("INVALID_ARGUMENT", error(badKind).get("code"));

		ToolResponse nothing = tools.correct("banana", Map.of("object", "x"), NONE);
		assertTrue(nothing.isError());
		assertTrue(error(nothing).get("message").toString().contains("pred:"), text(nothing));

		ToolResponse noRef = tools.inspect("", Optional.empty(), NONE);
		assertTrue(noRef.isError());
		assertEquals("INVALID_ARGUMENT", error(noRef).get("code"));
	}

	@Test
	void statusReportsTheStore() {
		ToolResponse status = tools.status();
		assertFalse(status.isError(), text(status));
		Map<String, Object> result = result(status);
		assertEquals(29, ((Number) result.get("schema_version")).intValue());
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
		assertFalse(text(tools.inspect(obs, Optional.empty(), NONE)).contains("evt-"), "the old event is gone");
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
		ToolResponse h = tools.inspect("Mattias Sandell", Optional.of(true), Optional.of("uses"));
		assertTrue(text(h).contains("observations_retired"), text(h));
		ToolResponse o = tools.inspect(obs, Optional.empty(), NONE);
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
		ToolResponse f = tools.inspect(factId, Optional.empty(), NONE);
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

		ToolResponse history = tools.inspect("Hooli", Optional.of(true), Optional.of("works_at"));
		assertFalse(history.isError(), text(history));
		String h = text(history);
		assertTrue(h.contains("\"observations\":[\"obs-"), h);
		int at = h.indexOf("\"observations\":[\"obs-");
		String list = h.substring(at, h.indexOf("]", at));
		assertTrue(list.split("obs-").length - 1 >= 2, "two conversations behind the one fact: " + list);
		// The same fact by its own id, with its columns.
		ToolResponse f = tools.inspect(firstFactId(again), Optional.empty(), NONE);
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
		assertTrue(text(tools.inspect(ent, Optional.empty(), NONE)).contains("LU"));
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
		ToolResponse f = tools.inspect(id, Optional.empty(), NONE);
		assertEquals("ended", result(f).get("standing"));
		assertFalse(result(f).containsKey("status") || result(f).containsKey("state"), "one field, not two");
		// A present-tense question misses, but names the ended fact rather than claiming ignorance.
		String recalled = text(tools.recall(Optional.of("where does Mattias work"), NONE, Optional.of(400),
				Optional.empty(), Optional.empty()));
		assertTrue(recalled.contains(
				"no current value; 1 ended fact: Mattias Sandell works at Initrode (2019 \u2013 2022) [" + id + "]"),
				recalled);
		assertTrue(recalled.contains("include_history"), recalled);
	}

	@Test
	void inspectNamesTheRegistryAndItsEntries() {
		ToolResponse r = tools.inspect("registry", Optional.empty(), NONE);
		assertFalse(r.isError(), text(r));
		Map<String, Object> result = result(r);
		String preds = result.get("predicates").toString();
		assertTrue(preds.contains("id=pred:works_at"), preds);
		assertTrue(preds.contains("origin=seed"), preds);
		assertTrue(result.get("event_types").toString().contains("id=event:joined"), result.toString());
		String types = result.get("entity_types").toString();
		assertTrue(types.contains("name=country") && types.contains("parent=place"), types);

		ToolResponse one = tools.inspect("pred:works_at", Optional.empty(), NONE);
		assertFalse(one.isError(), text(one));
		assertEquals("works_at", result(one).get("name"));
		assertTrue(result(one).containsKey("changes"));
		assertFalse(tools.inspect("event:joined", Optional.empty(), NONE).isError());
		assertFalse(tools.inspect("type:place", Optional.empty(), NONE).isError());
		assertTrue(tools.inspect("type:spaceship", Optional.empty(), NONE).isError());
		// A vocabulary correction goes through the same id scheme.
		ToolResponse c = tools.correct("event:joined",
				Map.of("lexicon", List.of("joined", "join", "started", "onboarded")), Optional.of("onboarding counts"));
		assertFalse(c.isError(), text(c));
		assertTrue(text(tools.inspect("event:joined", Optional.empty(), NONE)).contains("onboarded"));
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
		return (String) ((List<Map<String, Object>>) ((Map<?, ?>) result(stored).get("stored")).get("facts")).getFirst()
				.get("id");
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
		String aunt = text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE));
		assertTrue(aunt.contains("Nibtest Aunt is Nibtest Kid's aunt\""), "the sister role implies female: " + aunt);
		String kid = text(tools.inspect("Nibtest Kid", Optional.empty(), NONE));
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
				Optional.empty(), Optional.empty()));
		assertTrue(asked.contains("Nibtest Kid is male"), asked);
		assertTrue(text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE)).contains("Nibtest Aunt is female"),
				text(tools.inspect("Nibtest Aunt", Optional.empty(), NONE)));
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
		assertTrue(tools.inspect(lone, Optional.empty(), NONE).isError(), "gone for good");
	}
}
