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
package se.hirt.mnemic.scenario;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/**
 * Correctness, concurrency, atomicity, multi-process cache invalidation, and deterministic-clock hardening tests.
 */
class HardeningTest {

	@Test
	void twoEnginesInvalidateRegistryCachesAndOwnerAcrossProcesses() {
		Path home = fresh("multiprocess-cache");
		try (Engine a = engine(home); Engine b = engine(home)) {
			// Prime b's in-memory caches before a mutates the database.
			assertTrue(b.predicates().get("mentors").isEmpty());
			assertTrue(b.eventTypes().get("promoted_to").isEmpty());
			assertTrue(b.entityTypes().get("spacecraft").isEmpty());
			assertEquals(OWNER, b.entities().owner().name());

			// Engine A registers a new entity type, predicate, event type, and renames the owner.
			remember(a, "I mentor Sara on the Voyagers spacecraft project after being promoted.",
					proposal().entity("e1", "Sara Lind", "person").entity("e2", "Voyager One", "spacecraft")
							.fact("self", "mentors", "e1").event("ev1", "promoted_to", "2025-02-01", "self", "e2"));
			a.correctGroup("family", Map.of("lexicon", List.of("family", "relatives", "kinfolk")), "add cue word");
			a.correctEntity(a.entities().owner().id(), Map.of("name", "Mattias H. Sandell"), "middle initial");

			// Engine B detects PRAGMA data_version advance and refreshes all caches without restart or manual reload.
			assertTrue(b.predicates().get("mentors").isPresent(), "PredicateRegistry refreshed on external write");
			assertTrue(b.predicates().group("family").orElseThrow().lexicon().contains("kinfolk"),
					"PredicateRegistry group cache refreshed on external write");
			assertTrue(b.eventTypes().get("promoted_to").isPresent(), "EventTypeRegistry refreshed on external write");
			assertTrue(b.entityTypes().get("spacecraft").isPresent(), "EntityTypeRegistry refreshed on external write");
			assertEquals("Mattias H. Sandell", b.entities().owner().name(),
					"EntityService.owner() refreshed on external write");
		}
	}

	@Test
	void rebuildRollsBackCompletelyWhenReplayFailsMidway() {
		try (Engine e = engine("rebuild-atomic")) {
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			long obs2 = remember(e, "I live in Schübelbach.",
					proposal().entity("e2", "Schübelbach", "place").fact("self", "lives_in", "e2")).observation()
					.observationId();

			List<String> renderingsBefore = e.facts().factsOf(e.entities().owner().id()).stream().map(Fact::rendering)
					.sorted().toList();
			assertEquals(2, renderingsBefore.size());

			// Corrupt the second observation's stored proposal JSON so replay throws halfway through rebuild().
			e.database()
					.write(tx -> tx.update("UPDATE observation SET proposal_json = ? WHERE id = ?", "{not json", obs2));

			assertThrows(RuntimeException.class, e::rebuild);

			// Transaction rolled back: every fact from before the failed rebuild is still intact.
			List<String> renderingsAfter = e.facts().factsOf(e.entities().owner().id()).stream().map(Fact::rendering)
					.sorted().toList();
			assertEquals(renderingsBefore, renderingsAfter,
					"a failed rebuild must roll back instead of leaving a half-wiped projection");
		}
	}

	@Test
	void failedFactAndEventCorrectionsLeaveNoOrphanRecordsOrTombstones() {
		try (Engine e = engine("correction-atomic")) {
			var out = remember(e, "I work at Hooli and joined in 2020.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1").event("ev1",
							"joined", "2020-03-01", "self", "e1"));
			long factId = Long.parseLong(out.applied().facts().getFirst().id().substring(2));
			long eventId = Long.parseLong(out.applied().events().getFirst().id().substring(4));

			long totalObsBefore = e.database().read(tx -> tx.queryLong("SELECT COUNT(*) FROM observation"));

			// Refused no-op fact correction.
			assertThrows(MnemicException.class, () -> e.correct(factId, Map.of("object", "Hooli"), "no change"));
			// Refused unknown-predicate fact correction.
			assertThrows(MnemicException.class,
					() -> e.correct(factId, Map.of("predicate", "no_such_predicate_xyz"), "bad predicate"));
			// Refused no-op event redate.
			assertThrows(MnemicException.class,
					() -> e.correctEvent(eventId, Map.of("valid_time", Map.of("start", "2020-03-01")), "same date"));
			// Refused descriptive event retype.
			assertThrows(MnemicException.class, () -> e.correctEvent(eventId,
					Map.of("type", "joined the company after a long interview process"), "too long"));

			long totalObsAfter = e.database().read(tx -> tx.queryLong("SELECT COUNT(*) FROM observation"));
			assertEquals(totalObsBefore, totalObsAfter,
					"refused corrections must roll back cleanly with zero orphan observations or tombstones");
			assertEquals("current", e.facts().get(factId).orElseThrow().status());
		}
	}

	@Test
	void failedRereadPreservesOriginalFactsAndProposal() {
		try (Engine e = engine("reread-atomic")) {
			long obsId = remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1")).observation()
					.observationId();
			long factsBefore = e.facts().count();
			String proposalBefore = e.observations().get(obsId).orElseThrow().proposalJson();

			// Trigger a failure during fact insertion AFTER forgetDerived() and attachProposal() have run.
			e.database().write(tx -> {
				tx.update("CREATE TEMP TRIGGER fail_reread_fact_insert BEFORE INSERT ON fact "
						+ "BEGIN SELECT RAISE(ABORT, 'simulated reread failure'); END");
				return null;
			});
			try {
				Proposal replacement = proposal().entity("e1", "Initrode", "organization")
						.fact("self", "works_at", "e1").build();
				assertThrows(MnemicException.class, () -> e.reread(obsId, replacement));
			} finally {
				e.database().write(tx -> {
					tx.update("DROP TRIGGER IF EXISTS temp.fail_reread_fact_insert");
					return null;
				});
			}

			assertEquals(factsBefore, e.facts().count(), "original facts survive a failed reread");
			assertEquals("Mattias Sandell works at Hooli",
					e.facts().factsOf(e.entities().owner().id()).getFirst().rendering());
			assertEquals(proposalBefore, e.observations().get(obsId).orElseThrow().proposalJson(),
					"original proposal_json survives a failed reread");
		}
	}

	@Test
	void failedEntityMergeAndCorrectionRollsBackAtomically() {
		try (Engine e = engine("entity-correct-atomic")) {
			remember(e, "Hooli competes with Pied Piper.", proposal().entity("e1", "Hooli", "organization")
					.entity("e2", "Pied Piper", "organization").fact("self", "works_at", "e1"));
			Entity hooli = e.entities().byRef("Hooli").orElseThrow();
			Entity piedPiper = e.entities().byRef("Pied Piper").orElseThrow();

			var changes = new java.util.LinkedHashMap<String, Object>();
			changes.put("merge_into", hooli.ref());
			changes.put("unknown_property", "boom");
			assertThrows(MnemicException.class, () -> e.correctEntity(piedPiper.id(), changes, "atomic test"));

			// Merge must have been rolled back when unknown_property failed.
			Long mergedInto = e.database()
					.read(tx -> tx.queryOne("SELECT merged_into FROM entity WHERE id = ?", piedPiper.id()).orElseThrow()
							.lngOrNull("merged_into"));
			assertNull(mergedInto, "entity merge rolled back on error in the same correctEntity call");
		}
	}

	@Test
	void injectableClockControlsObservationEntityAndEmbeddingTimestamps() {
		Instant fixed = Instant.parse("2024-03-15T10:20:30Z");
		Path home = fresh("clock-deterministic");
		try (Engine e = new Engine(options(home).withClock(Clock.fixed(fixed, ZoneOffset.UTC)))) {
			long obs1 = remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1")).observation()
					.observationId();
			long obs2 = remember(e, "Retire this note.").observationId();
			long obs3 = remember(e, "Forget this note.").observationId();

			e.retireObservation(obs2, "superseded", obs1);
			e.forget(obs3);
			e.vectors().put(VectorStore.OBSERVATION, obs1, "test-model", new float[] {1.0f, 0.0f});

			assertEquals(fixed, e.observations().get(obs1).orElseThrow().observedAt());
			assertEquals(fixed, e.observations().get(obs1).orElseThrow().recordedAt());
			assertEquals(fixed.toString(), e.observations().get(obs2).orElseThrow().retiredAt());
			String forgottenAt = e.database()
					.read(tx -> tx.queryOne("SELECT forgotten_at FROM observation WHERE id = ?", obs3).orElseThrow()
							.str("forgotten_at"));
			assertEquals(fixed.toString(), forgottenAt);

			String entityCreatedAt = e.database().read(tx -> tx
					.queryOne("SELECT created_at FROM entity WHERE name = ?", "Hooli").orElseThrow().str("created_at"));
			assertEquals(fixed.toString(), entityCreatedAt);

			String embeddingCreatedAt = e.database().read(tx -> tx
					.queryOne("SELECT created_at FROM embedding WHERE item_kind = 'observation' AND item_id = ?", obs1)
					.orElseThrow().str("created_at"));
			assertEquals(fixed.toString(), embeddingCreatedAt);
		}
	}

	@Test
	void concurrentRememberAndPredicateLookupOnSharedEngineAreThreadSafe() throws Exception {
		String[] prefixes = {"Amber", "Basalt", "Cobalt", "Dune", "Ember", "Fjord", "Granite", "Harbor", "Ivory",
				"Jasper", "Kestrel", "Linden", "Marble", "Nimbus", "Onyx", "Pebble", "Quartz", "Redwood", "Slate",
				"Timber", "Umber", "Velvet", "Willow", "Xenon", "Yarrow", "Zephyr", "Alder", "Birch", "Cedar", "Delta",
				"Elm", "Flint"};
		String[] places = {"Aarhus", "Bergen", "Cork", "Dresden", "Essen", "Faro", "Ghent", "Hamar", "Innsbruck",
				"Jena", "Kiel", "Linz", "Malmo", "Nantes", "Oulu", "Pilsen", "Quimper", "Riga", "Siena", "Turku", "Ulm",
				"Vejle", "Wels", "Xanten", "Ystad", "Zug", "Albi", "Breda", "Chur", "Delft", "Evora", "Freiburg"};
		try (Engine e = engine("concurrent-engine"); ExecutorService pool = Executors.newFixedThreadPool(4)) {
			int threads = 4;
			int perThread = 8;
			var tasks = new ArrayList<Callable<Void>>();
			for (int t = 0; t < threads; t++) {
				final int worker = t;
				tasks.add(() -> {
					for (int i = 0; i < perThread; i++) {
						int idx = worker * perThread + i;
						String org = prefixes[idx];
						String city = places[idx];
						var out = remember(e, "Worker " + worker + " noted " + org + " in " + city + ".",
								proposal().entity("o", org, "organization").entity("c", city, "place").fact("o",
										"located_in", "c"));
						assertEquals(1, out.applied().facts().size(),
								"warnings=" + out.applied().warnings() + " questions=" + out.applied().questions());
						assertTrue(e.predicates().get("located_in").isPresent());
						e.predicates().negatedTemplate("located_in");
						assertTrue(e.entityTypes().get("organization").isPresent());
						assertTrue(e.eventTypes().get("joined").isPresent());
					}
					return null;
				});
			}
			List<Future<Void>> futures = pool.invokeAll(tasks);
			for (Future<Void> f : futures) {
				f.get();
			}
			assertEquals(threads * perThread, e.observations().count());
			assertEquals(threads * perThread, e.facts().count());
		}
	}
}
