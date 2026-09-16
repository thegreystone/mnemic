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
package se.hirt.mnemic.persistence;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.observation.Observation;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.recall;

/**
 * A store as 2.2 left it (schema 18, migrations V001–V018, which are immutable) with what 2.2 wrote for vocabulary it
 * did not know: an {@code x:} predicate with no lexicon, an event under a two-word type nobody registered, an entity of
 * a type nobody registered. Opening it with this release must carry all of it into registering from use.
 */
class UpgradeFrom22Test {

	private static final int SCHEMA_22 = 18;

	/** The reading 2.2 stored beside the text: an x: predicate, a two-word type, a sentence where a type goes. */
	private static final String READING_22 = """
			{"spec_version": 1,
			 "entities": [{"ref": "e1", "name": "Acme", "type": "organization"},
			              {"ref": "e2", "name": "the house", "type": "place"},
			              {"ref": "e3", "name": "Kanton Schwyz", "type": "canton"}],
			 "facts": [{"subject": "self", "predicate": "x:consults_for", "object": "e1"},
			           {"subject": "self", "predicate": "works_at", "object": "e1"}],
			 "events": [{"ref": "ev1", "type": "purchased property", "participants": ["self", "e2"],
			             "valid_time": {"start": "2025-11"}},
			            {"ref": "ev2", "type": "Dealer confirmed receipt of the payment for the house",
			             "participants": ["self", "e1"], "valid_time": {"start": "2025-11-05"}}]}""";

	@Test
	@Scenario("S26")
	void aStoreFrom22OpensWithItsVocabularyRegisteredFromUse() throws Exception {
		Path home = TestHomes.fresh("upgrade-22");
		Path file = home.resolve("mnemic.db");
		try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(),
				new java.util.Properties()); Statement st = c.createStatement()) {
			st.execute("PRAGMA foreign_keys = ON");
			Migrations.applyUpTo(c, SCHEMA_22);
			// What 2.2 wrote: the owner, an observation, a bare predicate stored as x:, an unregistered event type
			// spelled with a space, an entity of a type nobody registered, and the meta rows the engine expects.
			st.execute("INSERT INTO entity(id, name, type, created_at) VALUES (1, 'Mattias Sandell', 'person', 't')");
			st.execute(
					"INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (1, 'Mattias Sandell', 'mattias sandell')");
			try (var ps = c.prepareStatement("INSERT INTO observation(id, text, source_kind, observed_at, recorded_at, "
					+ "content_hash, proposal_json, spec_version) VALUES (1, ?, 'user', '2026-01-01T00:00:00Z', "
					+ "'2026-01-01T00:00:00Z', 'h1', ?, 1)")) {
				ps.setString(1, "I consult for Acme and bought the house in Kanton Schwyz.");
				ps.setString(2, READING_22);
				ps.executeUpdate();
			}
			st.execute(
					"INSERT INTO entity(id, name, type, created_from, created_at) VALUES (2, 'Acme', 'organization', 1, 't')");
			st.execute("INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (2, 'Acme', 'acme')");
			st.execute(
					"INSERT INTO entity(id, name, type, created_from, created_at) VALUES (3, 'the house', 'place', 1, 't')");
			st.execute("INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (3, 'the house', 'the house')");
			st.execute(
					"INSERT INTO entity(id, name, type, created_from, created_at) VALUES (4, 'Kanton Schwyz', 'canton', 1, 't')");
			st.execute(
					"INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (4, 'Kanton Schwyz', 'kanton schwyz')");
			st.execute("INSERT INTO predicate(name, description, domain, range, functional, symmetric, volatility, "
					+ "lexicon, render, qualifiers, aliases, inverse_lexicon, defined_by, seed, created_at) VALUES "
					+ "('x:consults_for', NULL, '[\"*\"]', '[\"*\"]', 0, 0, 'medium', '[]', "
					+ "'{subject} consults for {object}', '[]', '[]', '[]', 1, 0, 't')");
			st.execute("INSERT INTO fact(id, subject_id, predicate, object_id, ended, status, derivation_kind, "
					+ "observation_id, rendering, mode, corroborations, last_confirmed, created_at) VALUES (1, 1, "
					+ "'x:consults_for', 2, 0, 'current', 'explicit', 1, 'Mattias Sandell consults for Acme', "
					+ "'asserted', 1, '2026-01-01T00:00:00Z', 't')");
			st.execute("INSERT INTO fact_source(fact_id, observation_id, kind, recorded_at) VALUES (1, 1, 'stated', "
					+ "'2026-01-01T00:00:00Z')");
			st.execute("INSERT INTO event(id, type, observation_id, valid_start, valid_start_precision, rendering, "
					+ "created_at) VALUES (1, 'purchased property', 1, '2025-11-01', 'month', "
					+ "'purchased property(Mattias Sandell, the house)', 't')");
			st.execute("INSERT INTO event_participant(event_id, entity_id) VALUES (1, 1)");
			st.execute("INSERT INTO event_participant(event_id, entity_id) VALUES (1, 3)");
			// A description where the type goes, as the assistant often wrote it under 2.2.
			st.execute("INSERT INTO event(id, type, observation_id, valid_start, valid_start_precision, rendering, "
					+ "created_at) VALUES (2, 'Dealer confirmed receipt of the payment for the house', 1, '2025-11-05', "
					+ "'day', 'Dealer confirmed receipt of the payment for the house(Mattias Sandell, Acme)', 't')");
			st.execute("INSERT INTO event_participant(event_id, entity_id) VALUES (2, 1)");
			st.execute("INSERT INTO event_participant(event_id, entity_id) VALUES (2, 2)");
			// The seed row 2.2 wrote for works_at (the registry seeds at start; the fixture must carry its own).
			st.execute("INSERT INTO predicate(name, description, domain, range, functional, symmetric, volatility, "
					+ "lexicon, render, qualifiers, aliases, inverse_lexicon, seed, created_at) VALUES ('works_at', "
					+ "'Subject works for object organization.', '[\"person\"]', '[\"organization\"]', 1, 0, 'high', "
					+ "'[\"work\", \"works\"]', '{subject} works at {object}', '[]', '[]', '[]', 1, 't')");
			// A correction as 2.2 wrote it: the record carries no reading, only its fact and the supersession row.
			st.execute("INSERT INTO entity(id, name, type, created_from, created_at) VALUES (5, 'Initrode', "
					+ "'organization', 1, 't')");
			st.execute("INSERT INTO entity_alias(entity_id, alias, alias_norm) VALUES (5, 'Initrode', 'initrode')");
			st.execute("INSERT INTO fact(id, subject_id, predicate, object_id, ended, status, derivation_kind, "
					+ "observation_id, rendering, mode, corroborations, last_confirmed, created_at) VALUES (2, 1, "
					+ "'works_at', 2, 0, 'corrected', 'explicit', 1, 'Mattias Sandell works at Acme', 'asserted', 1, "
					+ "'2026-01-01T00:00:00Z', 't')");
			st.execute("INSERT INTO fact_source(fact_id, observation_id, kind, recorded_at) VALUES (2, 1, 'stated', "
					+ "'2026-01-01T00:00:00Z')");
			st.execute("INSERT INTO observation(id, text, source_kind, source_ref, observed_at, recorded_at, "
					+ "content_hash, proposal_json, spec_version) VALUES (2, 'Correction of Mattias Sandell works at "
					+ "Acme: wrong company', 'correction', 'f-2', '2026-01-02T00:00:00Z', '2026-01-02T00:00:00Z', "
					+ "'h2', '{}', NULL)");
			st.execute("INSERT INTO fact(id, subject_id, predicate, object_id, ended, status, derivation_kind, "
					+ "observation_id, rendering, mode, corroborations, last_confirmed, created_at) VALUES (3, 1, "
					+ "'works_at', 5, 0, 'current', 'explicit', 2, 'Mattias Sandell works at Initrode', 'asserted', 1, "
					+ "'2026-01-02T00:00:00Z', 't')");
			st.execute("INSERT INTO fact_source(fact_id, observation_id, kind, recorded_at) VALUES (3, 2, 'stated', "
					+ "'2026-01-02T00:00:00Z')");
			st.execute("UPDATE fact SET superseded_by = 3 WHERE id = 2");
			st.execute("INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, observation_id, recorded_at) "
					+ "VALUES (2, 3, 'correction', 'wrong company', 2, '2026-01-02T00:00:00Z')");
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(Migrations.MIGRATIONS.size(), e.database().schemaVersion());
			// The x: predicate is a plain predicate registered from use: renamed, with the words of its name as cue.
			Predicate consults = e.predicates().get("consults_for").orElseThrow();
			assertTrue(consults.isInferred());
			assertEquals(List.of("consults", "consult"), consults.lexicon());
			assertTrue(e.predicates().all().stream().noneMatch(p -> p.name().startsWith("x:")));
			Fact f = e.facts().get(1).orElseThrow();
			assertEquals("consults_for", f.predicate());
			assertEquals("Mattias Sandell consults for Acme", f.rendering());
			assertTrue(recall(e, "who does Mattias consult for").structured().matched(), "found through its words");
			// The 2.2 correction record has been given a reading from what it did.
			Observation record = e.observations().get(2).orElseThrow();
			assertTrue(record.proposalJson().contains("corrects"), record.proposalJson());
			assertTrue(record.proposalJson().contains("Acme") && record.proposalJson().contains("Initrode"));
			// The two-word event type is spelled the registry's way and registered from use.
			var purchased = e.eventTypes().get("purchased_property").orElseThrow();
			assertTrue(purchased.inferred());
			assertEquals("purchased_property", e.events().get(1).orElseThrow().type());
			assertTrue(
					e.events().get(1).orElseThrow().rendering()
							.startsWith("Mattias Sandell purchased property the house"),
					e.events().get(1).orElseThrow().rendering());
			var bought = recall(e, "when did Mattias buy the house");
			assertEquals("events", bought.structured().state(),
					"found through the seed type's cue word: " + bought.text());
			assertEquals("purchased_property", bought.events().getFirst().type());
			// A description where the type went is spelled the registry's way but stays an occurrence, unregistered.
			assertEquals("dealer_confirmed_receipt_of_the_payment_for_the_house",
					e.events().get(2).orElseThrow().type());
			assertTrue(e.eventTypes().all().stream().noneMatch(t -> t.name().startsWith("dealer")), "not vocabulary");
			assertTrue(
					e.events().get(2).orElseThrow().rendering().startsWith(
							"dealer confirmed receipt of the payment for the house (Mattias Sandell, Acme)"),
					e.events().get(2).orElseThrow().rendering());
			// The entity type nobody registered is registered from use, without a parent.
			var canton = e.entityTypes().get("canton").orElseThrow();
			assertTrue(canton.inferred());
			assertEquals(null, canton.parent());
			// Everything carried over is listed for the user to settle, and nothing was asked at start.
			List<Map<String, Object>> inferred = e.consolidate(true).inferredVocabulary();
			assertTrue(inferred.stream().anyMatch(m -> "consults_for".equals(m.get("predicate"))), inferred.toString());
			assertTrue(inferred.stream().anyMatch(m -> "purchased_property".equals(m.get("event_type"))));
			assertTrue(inferred.stream().anyMatch(m -> "canton".equals(m.get("entity_type"))));
			assertEquals(0, e.questions().openCount());
			assertFalse(e.predicates().get("x:consults_for").isPresent());
		}
		// A second open changes nothing more, and a rebuild re-derives the same store from its 2.2 readings.
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(0, e.database().migrated());
			assertEquals("consults_for", e.facts().get(1).orElseThrow().predicate());
			var rebuilt = e.rebuild();
			assertEquals(1, rebuilt.observations(), rebuilt.toString());
			assertEquals(1, rebuilt.corrections(), "the 2.2 correction, replayed from its backfilled reading");
			assertEquals(List.of(), rebuilt.unmatched());
			List<Fact> owner = e.facts().factsOf(e.entities().owner().id());
			assertEquals(
					List.of("Mattias Sandell works at Initrode"), owner.stream().filter(Fact::current)
							.filter(f -> f.predicate().equals("works_at")).map(Fact::rendering).toList(),
					"the correction still holds");
			assertTrue(owner.stream().anyMatch(f -> "corrected".equals(f.status()) && f.rendering().contains("Acme")));
			assertEquals(2, owner.stream().filter(Fact::current).count(), "no duplicate from the original");
			Fact again = e.facts().factsOfObservation(1).stream().filter(Fact::current).findFirst().orElseThrow();
			assertEquals("consults_for", again.predicate());
			assertEquals("Mattias Sandell consults for Acme", again.rendering());
			assertEquals(2, e.events().eventsOfObservation(1).size(), "both events, re-derived");
			assertTrue(
					e.events().eventsOfObservation(1).stream()
							.anyMatch(ev -> ev.type().equals("dealer_confirmed_receipt_of_the_payment_for_the_house")),
					"the sentence-typed event is still an occurrence");
			assertEquals(1, e.consolidate(true).descriptiveEvents().size(), "and still listed for re-reading");
			// The one thing a replay asks: what the two-word type inherited from 2.2 does, since nobody ever said.
			assertEquals(1, e.questions().openCount());
			assertEquals("event_effect", e.questions().open(5).getFirst().kind());
			assertEquals("purchased_property", e.questions().open(5).getFirst().subject());
		}
	}
}
