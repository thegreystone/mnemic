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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.knowledge.PredicateRegistry.Resolution;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * Predicate resolution with the real embedder: a bare name a model wrote is met by the closest existing relations, by
 * their words with lemmas and by meaning, and the question offers the closest few. Runs when the server pom's
 * local-embedder profile finds the runtime and the model under ~/.mnemic/models, or when MNEMIC_ORT_LIBRARY and
 * MNEMIC_EMBED_MODEL are set; skips otherwise (2026-09-27).
 */
class SemanticVocabularyTest {

	private static Embedder embedder() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Assumptions.assumeTrue(
				lib != null && Files.exists(Path.of(lib)) && model != null
						&& Files.exists(Path.of(model, "model.onnx")),
				"MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		return new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString());
	}

	private static Engine engine(Embedder emb, String name) {
		return new Engine(TestHomes.options(TestHomes.fresh(name)).withVocabularyEmbedding(emb));
	}

	private static Resolution resolve(Engine e, String name) {
		return e.predicates().resolve(name, null, null, new ArrayList<>());
	}

	private static List<String> names(List<se.hirt.mnemic.knowledge.Predicate> ps) {
		return ps.stream().map(p -> p.name()).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> candidateIds(Map<String, Object> q) {
		return ((List<Map<String, Object>>) q.get("candidates")).stream().map(c -> String.valueOf(c.get("id")))
				.toList();
	}

	@Test
	void aBareNameCloseInMeaningIsAskedAboutAndAnsweredOnce() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-vocabulary-meaning")) {
			// "dwells_in" shares no word with lives_in; the model puts them together.
			RememberOutcome o = remember(e, "Anna dwells in Zug.", proposal().entity("a", "Anna Lindqvist", "person")
					.entity("z", "Zug", "place").fact("a", "dwells_in", "z"));
			assertEquals(0, o.applied().facts().size(), "held behind the question: " + o.applied());
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("predicate_resolution", q.get("kind"));
			assertEquals("lives_in", candidateIds(q).getFirst(), q.toString());
			@SuppressWarnings("unchecked")
			Map<String, Object> first = ((List<Map<String, Object>>) q.get("candidates")).getFirst();
			assertEquals("semantic", first.get("match"));
			RememberOutcome a = remember(e, "Yes.", null, new Resolve(String.valueOf(q.get("id")), "lives_in"));
			assertEquals(1, ((List<?>) a.resolved().getFirst().get("facts")).size(), a.resolved().toString());
			assertTrue(recall(e, "where does Anna live").text().contains("Anna Lindqvist lives in Zug"));
			assertTrue(e.predicates().get("lives_in").orElseThrow().aliases().contains("dwells_in"));
			assertEquals("alias", resolve(e, "dwells_in").how(), "asked once");
		}
	}

	@Test
	void wordsWithLemmasComeFirstAndMeaningFillsTheAlternatives() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-vocabulary-closest")) {
			// By words: "leans" meets "leaning"; "hates" meets dislikes; "likes" meets prefers; "married_to" meets
			// spouse_of; "parent" is parent_of before the "parent-in-law" of in_law_of.
			assertEquals("considering", resolve(e, "leans_toward").candidate().name());
			assertEquals("dislikes", resolve(e, "hates").candidate().name());
			assertEquals("prefers", resolve(e, "likes").candidate().name());
			assertEquals("spouse_of", resolve(e, "married_to").candidate().name());
			Resolution parent = resolve(e, "parent");
			assertEquals("parent_of", parent.candidate().name(), names(parent.alternatives()).toString());
			assertTrue(names(parent.alternatives()).contains("step_parent_of"),
					names(parent.alternatives()).toString());
			// "brother" is a qualifier of sibling_of, which is offered beside the "brother-in-law" of in_law_of.
			Resolution brother = resolve(e, "brother_of");
			List<String> offered = new ArrayList<>(List.of(brother.candidate().name()));
			offered.addAll(names(brother.alternatives()));
			assertTrue(offered.contains("sibling_of") && offered.contains("in_law_of"), offered.toString());
			// By meaning: a relation registered from use earlier is offered beside the seed it resembles.
			assertEquals("inferred", resolve(e, "is_staff_at").how());
			Resolution employed = resolve(e, "employed_by");
			assertEquals("works_at", employed.candidate().name());
			assertTrue(names(employed.alternatives()).contains("is_staff_at"),
					names(employed.alternatives()).toString());
		}
	}

	@Test
	void aNameCloseToNothingRegistersFromUse() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-vocabulary-new")) {
			for (String name : List.of("visited", "godparent_of", "cannot_stand")) {
				Resolution r = resolve(e, name);
				assertEquals("inferred", r.how(), name + ": " + r.how());
				assertNull(r.candidate(), name);
				assertEquals(name, r.predicate().name());
			}
			RememberOutcome v = remember(e, "I visited Zug.",
					proposal().entity("z", "Zug", "place").fact("self", "visited", "z"));
			assertEquals(1, v.applied().facts().size(), v.applied().questions().toString());
			assertEquals(List.of(), v.applied().questions());
		}
	}
}
