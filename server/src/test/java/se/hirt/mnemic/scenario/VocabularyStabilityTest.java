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
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.FixedEmbedding;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.embed.Embedding;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * The edges of registering from use and matching by meaning: no model, a model that arrives late or changes, type
 * filters, merge refusals, what a merge carries along, re-checks, partial definitions of every kind, and restarts.
 */
class VocabularyStabilityTest {

	private static Engine engineWith(String name, Embedding embedding) {
		return new Engine(TestHomes.options(TestHomes.fresh(name)).withVocabularyEmbedding(embedding));
	}

	private static Map<String, Object> question(RememberOutcome o, String kind) {
		return o.applied().questions().stream().filter(q -> kind.equals(q.get("kind"))).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static List<String> candidateIds(Map<String, Object> q) {
		return ((List<Map<String, Object>>) q.get("candidates")).stream().map(c -> (String) c.get("id")).toList();
	}

	private static List<String> renderings(Engine e, long entityId) {
		return e.facts().factsOf(entityId).stream().map(Fact::rendering).sorted().toList();
	}

	@Test
	@Scenario("S23")
	void withoutAModelOnlyWordsAreCompared() {
		try (Engine e = TestHomes.engine("stab-no-model")) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			assertEquals("coaches", o.applied().facts().getFirst().predicate(), "no words shared: registered");
			assertEquals(List.of(), o.applied().questions());
			assertEquals(List.of(), e.consolidate(true).similarVocabulary(), "nothing to compare meaning with");
			assertEquals(2, e.consolidate(true).inferredVocabulary().size());
		}
	}

	@Test
	@Scenario("S23")
	void aModelThatArrivesLaterIsUsedFromThenOn() {
		var model = new AtomicReference<Embedding>();
		try (Engine e = new Engine(
				TestHomes.options(TestHomes.fresh("stab-late-model")).withVocabularyEmbedding(model::get))) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome before = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			assertEquals(List.of(), before.applied().questions(), "no model yet: registered from use");
			model.set(new FixedEmbedding());
			List<Map<String, Object>> close = e.consolidate(true).similarVocabulary();
			assertEquals(1, close.size(), "the pair is found once a model is there: " + close);
			RememberOutcome after = remember(e, "I'm coaching Sara.",
					proposal().entity("e1", "Sara Berg", "person").fact("self", "coaching", "e1"));
			Map<String, Object> q = question(after, "predicate_resolution");
			assertEquals("coaches", candidateIds(q).getFirst(), "the nearest of the two, not the first: " + q);
		}
	}

	@Test
	@Scenario("S23")
	void vectorsOfOneModelAreNeverMixedWithAnothers() {
		var model = new AtomicReference<Embedding>(new FixedEmbedding());
		try (Engine e = new Engine(
				TestHomes.options(TestHomes.fresh("stab-model-swap")).withVocabularyEmbedding(model::get))) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			remember(e, "Different.", null, new Resolve((String) question(o, "predicate_resolution").get("id"), "new"));
			assertEquals(1, e.consolidate(true).similarVocabulary().size());
			model.set(new FixedEmbedding("other-model", false));
			assertEquals(List.of(), e.consolidate(true).similarVocabulary(),
					"under a model that keeps the words apart, nothing is close: vectors were recomputed");
			model.set(new FixedEmbedding());
			assertEquals(1, e.consolidate(true).similarVocabulary().size(), "and back");
		}
	}

	@Test
	@Scenario("S23")
	void aNameIsOnlyComparedWithPredicatesWhoseTypesFit() {
		try (Engine e = engineWith("stab-type-filter", new FixedEmbedding())) {
			remember(e, "I mentor Anna.", proposal()
					.predicate(new PredicateDef("mentors", "Subject guides object's career.", "person", "person", false,
							null, false, null, "medium", List.of("mentor"), null, List.of(), List.of()))
					.entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			// A definition that says the object is an organization cannot mean mentors, whatever the words.
			RememberOutcome typed = remember(e, "I coach Hooli.",
					proposal()
							.predicate(new PredicateDef("coaches", null, "person", "organization", null, null, null,
									null, null, List.of(), null, List.of(), List.of()))
							.entity("e1", "Hooli", "organization").fact("self", "coaches", "e1"));
			assertEquals(List.of(), typed.applied().questions(), typed.applied().questions().toString());
			assertEquals("registered", typed.applied().predicates().getFirst().resolution());
			// The bare name, with nothing said about its types, is asked about.
			RememberOutcome bare = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaching", "e1"));
			assertEquals("predicate_resolution", question(bare, "predicate_resolution").get("kind"));
		}
	}

	@Test
	@Scenario("S24")
	void mergeRefusesWhatWouldLoseTheSeedOrItself() {
		try (Engine e = TestHomes.engine("stab-merge-refusals")) {
			remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("works_at", Map.of("merge_into", "coaches"), null), "a seed source");
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("coaches", Map.of("merge_into", "coaches"), null), "itself");
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("coaches", Map.of("merge_into", "no_such_thing"), null), "unknown target");
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("coaches", Map.of("merge_into", "knows", "description", "x"), null),
					"merge_into stands alone");
			assertTrue(e.predicates().all().stream().anyMatch(p -> p.name().equals("coaches")), "nothing happened");
			// Into a seed predicate is fine: the seed absorbs.
			Map<String, Object> m = e.correctPredicate("coaches", Map.of("merge_into", "knows"), "close enough");
			assertEquals(1, m.get("merged_facts"));
			assertEquals("knows", e.predicates().get("coaches").orElseThrow().name());
			assertEquals(List.of("Mattias Sandell knows Erik Nyberg"), renderings(e, e.entities().owner().id()));
		}
	}

	@Test
	@Scenario("S24")
	void mergeCarriesEventTypesAndFactsAlong() {
		try (Engine e = engineWith("stab-merge-carry", new FixedEmbedding())) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			remember(e, "Different.", null, new Resolve((String) question(o, "predicate_resolution").get("id"), "new"));
			RememberOutcome ev = remember(e, "I took Sara on in 2024.",
					proposal()
							.eventType(new EventTypeDef("took_on", "Subject took object on.", List.of("coaches"),
									List.of(), List.of(), null, List.of("took on")))
							.entity("e1", "Sara Berg", "person").event("ev1", "took_on", "2024", "self", "e1"));
			assertEquals(List.of("Mattias Sandell coaches Sara Berg (since 2024)"),
					ev.applied().facts().stream().map(f -> f.rendering()).toList());
			Map<String, Object> m = e.correctPredicate("coaches", Map.of("merge_into", "mentors"), "one relation");
			assertEquals(2, m.get("merged_facts"));
			assertEquals(List.of("mentors"), e.eventTypes().get("took_on").orElseThrow().opens(),
					"the event type now opens the surviving predicate");
			assertEquals(1, e.eventTypes().changes("took_on").size());
			assertEquals(
					List.of("Mattias Sandell mentors Anna Lindqvist", "Mattias Sandell mentors Erik Nyberg",
							"Mattias Sandell mentors Sara Berg (since 2024)"),
					renderings(e, e.entities().owner().id()));
			RememberOutcome later = remember(e, "I took Lisa on in 2025.",
					proposal().entity("e1", "Lisa Berg", "person").event("ev1", "took_on", "2025", "self", "e1"));
			assertEquals("mentors", later.applied().facts().getFirst().predicate());
			assertTrue(e.predicates().get("coaches").orElseThrow().aliases().contains("coaches"));
		}
	}

	@Test
	@Scenario("S13")
	void aDescriptionWhereTheTypeGoesIsAnOccurrenceNotVocabulary() {
		try (Engine e = TestHomes.engine("stab-description-type")) {
			RememberOutcome o = remember(e, "The dealer confirmed the payment for the boat on 11 September.",
					proposal().entity("e1", "Nordvik Marin", "organization").event("ev1",
							"dealer confirmed receipt of the payment for the boat", "2026-09-11", "e1", "self"));
			assertEquals(1, o.applied().events().size(), "the occurrence is kept");
			assertEquals(List.of(), o.applied().questions(), "nothing is asked about a sentence");
			assertEquals(List.of(), o.applied().definitions(), "and nothing registered");
			assertTrue(o.applied().warnings().getFirst().contains("reads as a description"),
					o.applied().warnings().toString());
			assertTrue(e.eventTypes().all().stream().noneMatch(t -> t.name().startsWith("dealer")));
			long id = Long.parseLong(o.applied().events().getFirst().id().substring(4));
			assertEquals("dealer_confirmed_receipt_of_the_payment_for_the_boat",
					e.events().get(id).orElseThrow().type());
			assertEquals(
					"dealer confirmed receipt of the payment for the boat (Nordvik Marin, Mattias Sandell) (since 2026-09-11)",
					e.events().get(id).orElseThrow().rendering());
			assertEquals(List.of(), e.consolidate(true).inferredVocabulary());
			// The same words as a proper type register as usual.
			RememberOutcome typed = remember(e, "Payment confirmed.",
					proposal().entity("e1", "Nordvik Marin", "organization").event("ev1", "confirmed payment",
							"2026-09-11", "e1", "self"));
			assertEquals("confirmed_payment", typed.applied().events().getFirst().type());
			assertEquals(1, typed.applied().definitions().size());
			assertEquals(1, typed.applied().questions().size());
			// Types are spelled one way whatever the caller's punctuation.
			assertTrue(EventTypeRegistry.typeLike("co-founded") && EventTypeRegistry.typeLike("Purchased Property"));
			assertFalse(EventTypeRegistry.typeLike("swiss 2025 tax return deadline"));
			assertFalse(EventTypeRegistry.typeLike("moved to the lake house"));
			assertEquals("co_founded", EventTypeRegistry.key("Co-Founded"));
		}
	}

	@Test
	@Scenario("S13")
	void aFactWithoutAPredicateBesideAnOpeningEventIsSkippedNotFatal() {
		// Seen from a local model on LongMemEval: a fact missing its predicate next to a purchase crashed the
		// check for whether the proposal already stated what the event opens.
		try (Engine e = TestHomes.engine("stab-no-predicate")) {
			RememberOutcome o = remember(e, "I bought the boat in 2024.",
					proposal().entity("e1", "the boat", "thing").event("ev1", "purchased", "2024", "self", "e1")
							.fact(new se.hirt.mnemic.proposal.Proposal.FactRef("self", null, "e1", null, null, null,
									null, List.of(), null, null)));
			assertEquals(List.of("Mattias Sandell owns the boat (since 2024)"),
					o.applied().facts().stream().map(f -> f.rendering()).toList(), "the event still opens owns");
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("predicate")),
					o.applied().warnings().toString());
		}
	}

	@Test
	@Scenario("S22")
	void rechecksOnlyOpenValuesOfTheSameSubject() {
		try (Engine e = TestHomes.engine("stab-recheck")) {
			remember(e, "I shared a flat with Anna until 2020.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact(fact("self", "shares_a_flat_with", "e1",
							null, null, "2015", "2020", Boolean.TRUE, null, null)));
			remember(e, "I share a flat with Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "shares_a_flat_with", "e1"));
			remember(e, "Anna shares a flat with Sara.", proposal().entity("e1", "Anna Lindqvist", "person")
					.entity("e2", "Sara Berg", "person").fact("e1", "shares_a_flat_with", "e2"));
			Map<String, Object> c = e.correctPredicate("shares_a_flat_with", Map.of("functional", true), null);
			assertEquals(0, c.get("rechecked_conflicts"),
					"an ended value and another subject's value conflict with nothing");
			assertEquals(0, e.questions().openCount());
			remember(e, "I share a flat with Lisa too.",
					proposal().entity("e1", "Lisa Berg", "person").fact("self", "shares_a_flat_with", "e1"));
			assertEquals(1, e.questions().openCount(), "from now on the predicate conflicts like any functional one");
		}
	}

	@Test
	@Scenario("S21")
	void partialDefinitionsCompleteEventAndEntityTypes() {
		try (Engine e = TestHomes.engine("stab-partial-types")) {
			RememberOutcome first = remember(e, "I inherited the cabin in Kanton Schwyz in 2019.",
					proposal().entity("e1", "the cabin", "place").entity("e2", "Kanton Schwyz", "canton").event("ev1",
							"inherited", "2019", "self", "e1"));
			assertEquals(2, first.applied().questions().size(), "event_effect and type_kind");
			// A bare definition changes nothing.
			RememberOutcome bare = remember(e, "Names only.", proposal()
					.eventType(new EventTypeDef("inherited", null, List.of(), List.of(), List.of(), null, List.of()))
					.entityType(new EntityTypeDef("canton", null, null, List.of(), List.of())));
			assertEquals(List.of("exists", "exists"),
					bare.applied().definitions().stream().map(d -> d.get("resolution")).toList());
			assertTrue(e.eventTypes().get("inherited").orElseThrow().inferred());
			// A partial one completes the term and has the consequences an answer would have.
			RememberOutcome partial = remember(e, "Inheriting is owning; a canton is a place.", proposal()
					.eventType(
							new EventTypeDef("inherited", null, List.of("owns"), List.of(), List.of(), null, List.of()))
					.entityType(new EntityTypeDef("canton", null, "place", List.of(), List.of())));
			assertEquals(List.of("defined", "defined"),
					partial.applied().definitions().stream().map(d -> d.get("resolution")).toList());
			@SuppressWarnings("unchecked")
			Map<String, Object> applied = (Map<String, Object>) partial.applied().definitions().stream()
					.filter(d -> "event_type".equals(d.get("kind"))).findFirst().orElseThrow().get("applied");
			assertEquals(1, ((List<?>) applied.get("facts")).size(), applied.toString());
			assertFalse(e.eventTypes().get("inherited").orElseThrow().inferred());
			assertEquals(List.of("owns"), e.eventTypes().get("inherited").orElseThrow().opens());
			assertEquals(List.of("Mattias Sandell owns the cabin (since 2019)"),
					renderings(e, e.entities().owner().id()));
			assertEquals("place", e.entityTypes().get("canton").orElseThrow().parent());
			assertFalse(e.entityTypes().get("canton").orElseThrow().inferred());
			assertEquals(0, e.questions().openCount(), "the definitions answered the open questions");
			assertEquals(List.of(), e.consolidate(true).inferredVocabulary());
		}
	}

	@Test
	@Scenario("S21")
	void restartKeepsWhatWasInferredAndWhatWasDefined() {
		Path home = TestHomes.fresh("stab-restart");
		try (Engine e = new Engine(TestHomes.options(home).withVocabularyEmbedding(new FixedEmbedding()))) {
			remember(e, "I mentor Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "mentors", "e1"));
			RememberOutcome o = remember(e, "I coach Erik.",
					proposal().entity("e1", "Erik Nyberg", "person").fact("self", "coaches", "e1"));
			remember(e, "Different.", null, new Resolve((String) question(o, "predicate_resolution").get("id"), "new"));
			e.correctPredicate("coaches", Map.of("functional", true), "one at a time");
		}
		try (Engine e = new Engine(TestHomes.options(home).withVocabularyEmbedding(new FixedEmbedding()))) {
			Predicate mentors = e.predicates().get("mentors").orElseThrow();
			Predicate coaches = e.predicates().get("coaches").orElseThrow();
			assertTrue(mentors.isInferred());
			assertFalse(coaches.isInferred());
			assertTrue(coaches.functional());
			assertEquals(List.of("mentors"),
					e.consolidate(true).inferredVocabulary().stream().map(m -> m.get("predicate")).toList());
			List<Map<String, Object>> close = e.consolidate(true).similarVocabulary();
			assertEquals(1, close.size(), "vectors are recomputed after a restart: " + close);
			assertEquals("mentors", close.getFirst().get("predicate"));
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(List.of(), e.consolidate(true).similarVocabulary(), "no model, no pairs");
			assertTrue(e.predicates().get("mentors").orElseThrow().isInferred(), "the flag is stored, not derived");
		}
	}
}
