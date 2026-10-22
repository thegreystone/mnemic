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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import se.hirt.mnemic.Engine;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.RecallResult;

/**
 * A question names a relation in a language its lexicon does not cover, and the model cues it anyway: "Handynummer" and
 * "telefonnummer" reach has_phone, whose seed lexicon is English, while question words and unrelated nouns cue nothing.
 * Runs only with {@code MNEMIC_ORT_LIBRARY} and {@code MNEMIC_EMBED_MODEL} set.
 */
class SemanticCueTest {

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

	private static List<String> cuedPredicates(Engine e, String question) {
		return e.predicates().cues(question).stream().map(c -> c.predicate().name()).distinct().toList();
	}

	@Test
	void aPhoneWordInAnotherLanguageCuesHasPhone() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-cue")) {
			List<Cue> german = e.predicates().cues("Wie lautet Malins Handynummer?");
			assertTrue(german.stream().anyMatch(c -> c.predicate().name().equals("has_phone")),
					"Handynummer cues has_phone: " + german);
			assertEquals("handynummer",
					german.stream().filter(c -> c.predicate().name().equals("has_phone")).findFirst().get().term());
			List<Cue> swedish = e.predicates().cues("Vad har Malin för telefonnummer?");
			assertTrue(swedish.stream().anyMatch(c -> c.predicate().name().equals("has_phone")),
					"telefonnummer cues has_phone: " + swedish);
		}
	}

	@Test
	void aLexiconWordKeepsItsCueAndIsNotCuedTwice() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-cue-lexical")) {
			List<Cue> cues = e.predicates().cues("What is Malin's phone number?");
			long onPhone = cues.stream().filter(c -> c.predicate().name().equals("has_phone")).count();
			assertEquals(1, onPhone, "one cue on has_phone, from the lexicon: " + cues);
		}
	}

	@Test
	void noiseCuesNothing() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-cue-noise")) {
			assertTrue(cuedPredicates(e, "What is the weather today?").isEmpty());
			assertTrue(cuedPredicates(e, "Wie war das Meeting gestern?").isEmpty());
			assertTrue(cuedPredicates(e, "Status of the report?").isEmpty());
		}
	}

	@Test
	void aQuestionInSwedishFindsThePhoneNumberThroughTheStructuredChannel() throws Exception {
		try (Embedder emb = embedder(); Engine e = engine(emb, "semantic-cue-recall")) {
			remember(e, "Malin's mobile number is +41 76 000 00 00.",
					proposal().entity("m", "Malin Example", "person").fact("m", "has_phone", "+41 76 000 00 00"));
			RecallResult r = recall(e, "Vad har Malin Example för telefonnummer?");
			assertFalse(r.hits().isEmpty(), "the number is found");
			RecallResult.Hit first = r.hits().getFirst();
			assertTrue(first.observation().text().contains("+41 76 000 00 00"), first.observation().text());
			assertTrue(first.channels().contains("structured"),
					"answered by the structured channel: " + first.channels());
		}
	}
}
