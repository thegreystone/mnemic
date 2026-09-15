/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.scenario;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.recall.RecallResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * EVALUATION.md F2 with the real embedder: runs when MNEMIC_ORT_LIBRARY and MNEMIC_EMBED_MODEL are set.
 */
class SemanticRecallTest {

	private static Embedder embedder() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(lib != null && Files.exists(Path.of(lib)) && model != null
				&& Files.exists(Path.of(model, "model.onnx")), "MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		return new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
	}

	/** A long observation whose relevant sentence sits far past the first 512 tokens. */
	private static String longObservation() {
		var sb = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			sb.append("We talked about the garden for a while, the tomatoes, the beans, and the weather this week. ");
		}
		sb.append("\n\nThen I mentioned that my accounts are at Nordbank, the savings and the salary account.");
		return sb.toString();
	}

	@Test
	@Scenario("F2")
	void aLongObservationIsFoundThroughAChunkBeyondItsOpening() throws Exception {
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(TestHomes.fresh("f2-chunks")).withEmbedder(emb))) {
			e.remember(longObservation(), Source.user(), null, null, null, null);
			e.remember("The 3D printer needs a new nozzle, the old one is clogged.", Source.user(), null, null, null, null);
			assertTrue(e.vectors().count(emb.id()) > 3, "the long observation has several chunk vectors: " + e.vectors().count(emb.id()));
			RecallResult r = recall(e, "where do I bank");
			assertTrue(r.hits().getFirst().observation().text().contains("Nordbank"), r.text());
			assertTrue(r.hits().getFirst().channels().contains("semantic"), r.hits().getFirst().channels().toString());
			// The same store with one vector per observation would miss it: the opening is about gardening.
			float[] q = emb.embed("where do I bank");
			float opening = Embedder.dot(q, emb.embed(longObservation().substring(0, 1200)));
			float bank = Embedder.dot(q, emb.embed("Then I mentioned that my accounts are at Nordbank, the savings and the salary account."));
			assertTrue(bank > opening + 0.1, "the bank sentence is the near chunk: " + bank + " vs the opening " + opening);
		}
	}

	@Test
	@Scenario("F2")
	void theChannelFillsTheTailAndNeverDisplacesAnExactHit() throws Exception {
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(TestHomes.fresh("f2-tail")).withEmbedder(emb))) {
			// Several observations close in meaning to a banking question, and one exact match on a rare word.
			e.remember("My accounts are at Nordbank, the savings and the salary account.", Source.user(), null, null, null, null);
			e.remember("I moved my savings to a new bank last spring.", Source.user(), null, null, null, null);
			e.remember("The bank charges a fee for the credit card.", Source.user(), null, null, null, null);
			e.remember("I pay my bills through the banking app.", Source.user(), null, null, null, null);
			e.remember("The Zugerberg cable car ticket costs twelve francs.", Source.user(), null, null, null, null);
			// An exact word the semantic neighbours do not share: the exact hit stays first.
			RecallResult exact = recall(e, "Zugerberg ticket price");
			assertTrue(exact.hits().getFirst().observation().text().contains("Zugerberg"), exact.text());
			assertTrue(exact.hits().getFirst().channels().contains("lexical"), exact.hits().getFirst().channels().toString());
			// A question with no shared word: the channel supplies the answer, alone.
			RecallResult tail = recall(e, "where do I keep my money");
			assertTrue(tail.hits().stream().anyMatch(h -> h.observation().text().contains("Nordbank")), tail.text());
			assertTrue(tail.hits().stream().allMatch(h -> h.channels().contains("semantic")), tail.text());
		}
	}

	@Test
	@Scenario("F2")
	void consolidateEmbedsWhatWasStoredBeforeTheModelAndForgetRemovesEveryChunk() throws Exception {
		Path home = TestHomes.fresh("f2-backfill");
		// Stored without an embedder: nothing embedded.
		try (Engine plain = new Engine(TestHomes.options(home))) {
			plain.remember(longObservation(), Source.user(), null, null, null, null);
			plain.remember("I work at Hooli.", Source.user(), null, proposal().entity("e1", "Hooli", "organization")
					.fact("works_at", "e1").build(), null, null);
		}
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(home).withEmbedder(emb))) {
			assertEquals(0, e.vectors().count(emb.id()));
			int embedded = e.consolidate(false).embedded();
			assertEquals(3, embedded, "two observations and one fact backfilled");
			long rows = e.vectors().count(emb.id());
			assertTrue(rows > 3, "the long observation contributes several chunk rows: " + rows);
			RecallResult r = recall(e, "where do I bank");
			assertTrue(r.hits().getFirst().observation().text().contains("Nordbank"), r.text());
			long obs = r.hits().getFirst().observation().id();
			e.forget(obs);
			assertEquals(3, e.vectors().count(emb.id()), "every chunk of the forgotten observation is gone; the other observation and the fact's two vectors stay");
			assertEquals(0, e.consolidate(false).embedded());
		}
	}

	/**
	 * First use, end to end: nothing on disk, a local mirror of the model, the engine answering while the fetch
	 * runs, then the channel alive and the backlog embedded. The runtime library comes from the build; needs only
	 * MNEMIC_EMBED_MIRROR (a copy of the model's repository files).
	 */
	@Test
	@Scenario("F2")
	void theModelIsFetchedOnFirstUseAndTheChannelComesAliveWithoutARestart() throws Exception {
		// A mirror laid out like the model's repository (tokenizer.json, onnx/...): MNEMIC_EMBED_MIRROR.
		String mirror = System.getenv("MNEMIC_EMBED_MIRROR");
		Assumptions.assumeTrue(mirror != null && Files.exists(Path.of(mirror, "tokenizer.json")), "MNEMIC_EMBED_MIRROR not set");
		Assumptions.assumeTrue(se.hirt.mnemic.embed.OrtLibrary.bundled(), "no bundled runtime for this platform");
		Path models = Files.createTempDirectory("mnemic-first-use");
		List<se.hirt.mnemic.embed.ModelFetcher.Item> plan = se.hirt.mnemic.embed.ModelFetcher.plan(models,
				Path.of(mirror).toUri().toString());
		assertTrue(!se.hirt.mnemic.embed.ModelFetcher.complete(plan), "nothing on disk yet");
		// The runtime is written out of the build, the model out of the mirror; both land under the models directory.
		var holder = new se.hirt.mnemic.embed.EmbedderHolder(models, plan, () -> se.hirt.mnemic.embed.OrtLibrary.install(models),
				plan.get(0).target().getParent(), se.hirt.mnemic.embed.ModelFetcher.MODEL_ID, e -> {
				});
		try (Engine e = new Engine(TestHomes.options(TestHomes.fresh("f2-first-use")).withEmbedder(holder))) {
			// The engine works at once, without the channel.
			e.remember("My accounts are at Nordbank, the savings and the salary account.", Source.user(), null, null, null, null);
			assertTrue(recall(e, "Nordbank").hits().size() == 1);
			assertTrue(List.of("downloading", "loading", "ready").contains(holder.state().state()), holder.state().toString());
			assertTrue(holder.await(600_000), "the fetch and the load finish: " + holder.state());
			assertEquals("ready", holder.state().state());
			assertTrue(se.hirt.mnemic.embed.ModelFetcher.complete(plan));
			assertEquals(se.hirt.mnemic.embed.OrtLibrary.target(models), holder.library(), "the runtime came out of the build");
			assertTrue(holder.files().values().stream().allMatch("done"::equals), holder.files().toString());
			// What was stored before the model arrived is embedded by the backfill the producer runs; here, by hand.
			assertEquals(1, e.embedMissing(100));
			RecallResult r = recall(e, "where do I bank");
			assertTrue(r.hits().getFirst().observation().text().contains("Nordbank"), r.text());
			assertTrue(r.hits().getFirst().channels().contains("semantic"), r.hits().getFirst().channels().toString());
		} finally {
			holder.close();
		}
	}

	@Test
	@Scenario("F2")
	void aParaphraseIsFoundThroughTheSemanticChannel() throws Exception {
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(TestHomes.fresh("f2-semantic")).withEmbedder(emb))) {
			// Through the engine, as a client would: the observation is embedded as it is stored.
			e.remember("My accounts are at Nordbank, the savings and the salary account.", Source.user(), null, null,
					null, null);
			e.remember("The 3D printer needs a new nozzle, the old one is clogged.", Source.user(), null, null, null, null);
			remember(e, "I work at Hooli.", proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			assertEquals(3, e.vectors().count(emb.id()) - 2, "three observations and one fact (its rendering and its first-person form) embedded at write time");
			// No shared word with the stored text: only meaning finds it.
			RecallResult r = recall(e, "where do I bank");
			assertTrue(r.hits().size() >= 1, r.text());
			assertTrue(r.hits().getFirst().observation().text().contains("Nordbank"), r.text());
			assertTrue(r.hits().getFirst().channels().contains("semantic"), r.hits().getFirst().channels().toString());
			// A German question, English facts.
			RecallResult de = recall(e, "Wo ist mein Geld?");
			assertTrue(de.hits().getFirst().observation().text().contains("Nordbank"), de.text());
			// Forgetting takes the vector with it.
			long obs = r.hits().getFirst().observation().id();
			e.forget(obs);
			RecallResult after = recall(e, "where do I bank");
			assertTrue(after.hits().stream().noneMatch(h -> h.observation().id() == obs), after.text());
			// Consolidate backfills nothing when everything is embedded, and reports it.
			assertEquals(0, e.consolidate(false).embedded());
		}
	}
	/**
	 * F16: the owner alias for vectors. A fact about the owner is rendered in the third person; a first-person
	 * question sits closer to it once the owner's name stands in for "me", and recall finds the observation
	 * behind the fact through the semantic channel.
	 */
	@Test
	@Scenario("F16")
	void aFirstPersonQuestionReachesAThirdPersonFactThroughTheOwnerAlias() throws Exception {
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(TestHomes.fresh("f16-owner-alias")).withEmbedder(emb))) {
			remember(e, "Hooli is where the paycheck comes from, since 2018.",
					proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			e.remember("The 3D printer needs a new nozzle, the old one is clogged.", Source.user(), null, null, null, null);
			e.remember("Anna's birthday is on the 3rd of May.", Source.user(), null, null, null, null);
			String question = "Which company employs me?";
			var fact = e.facts().factsOf(e.entities().owner().id()).stream().filter(f -> f.rendering().contains("Hooli")).findFirst().orElseThrow();
			String alias = se.hirt.mnemic.recall.OwnerAlias.firstPerson(fact.rendering(), TestHomes.OWNER, se.hirt.mnemic.knowledge.Lang.EN);
			assertTrue(alias != null && alias.startsWith("I work at Hooli"), alias);
			// The first-person rendering is closer to the question than the third-person one.
			float plain = Embedder.dot(emb.embedQuery(question), emb.embed(fact.rendering()));
			float aliased = Embedder.dot(emb.embedQuery(question), emb.embed(alias));
			assertTrue(aliased > plain, "alias " + aliased + " vs plain " + plain + " for '" + fact.rendering() + "'");
			RecallResult r = recall(e, question);
			assertTrue(r.hits().size() >= 1, r.text());
			assertTrue(r.hits().getFirst().observation().text().contains("Hooli"), r.text());
			assertTrue(r.hits().getFirst().channels().contains("semantic"), r.hits().getFirst().channels().toString());
		}
	}

	/** A correction's record and replacement fact are embedded when made, not at the next consolidate. */
	@Test
	@Scenario("D1")
	void aCorrectionIsEmbeddedAtOnce() throws Exception {
		try (Embedder emb = embedder();
				Engine e = new Engine(TestHomes.options(TestHomes.fresh("d1-embed-correction")).withEmbedder(emb))) {
			remember(e, "I live in Kilchberg.", proposal().entity("e1", "Kilchberg", "place").fact("lives_in", "e1"));
			long before = e.vectors().count(emb.id());
			long id = e.facts().factsOf(e.entities().owner().id()).getFirst().id();
			e.correct(id, java.util.Map.of("object", "Rüschlikon"), "moved");
			assertTrue(e.vectors().count(emb.id()) > before, "the correction record and the new fact have vectors");
			assertEquals(0, e.vectors().missingObservationCount(emb.id()) + e.vectors().missingFactCount(emb.id()));
		}
	}
}
