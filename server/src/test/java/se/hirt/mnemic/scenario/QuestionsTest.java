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
import se.hirt.mnemic.Engine.Consolidation;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.recall.TokenEstimator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/**
 * EVALUATION.md M3: the question queue (B2, J3), conflict resolution (C2 → D3 and the other answers), consolidation
 * (G2, G3, J6), predicate correction (J5), and the briefing (F10). What Mnemic cannot decide it asks; nothing is
 * guessed and nothing is silently overwritten (DECISIONS.md §2.7).
 */
class QuestionsTest {

	private static Fact stored(Engine e, RememberOutcome o, int index) {
		return e.facts().get(Long.parseLong(o.applied().facts().get(index).id().substring(2))).orElseThrow();
	}

	private static String questionId(RememberOutcome o) {
		return o.applied().questions().getFirst().get("id").toString();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> candidates(RememberOutcome o) {
		return (List<Map<String, Object>>) o.applied().questions().getFirst().get("candidates");
	}

	@Test
	@Scenario("B2")
	void ambiguousMatchReturnsAQuestionNotAGuess() {
		try (Engine e = engine("b2")) {
			remember(e, "I met Anna Lindqvist at the conference.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			Entity anna = e.entities().byRef("Anna Lindqvist").orElseThrow();
			RememberOutcome second = remember(e, "Anna is joining Hooli next month.", proposal()
					.entity("e1", "Anna", "person").entity("e2", "Hooli", "organization").fact("e1", "works_at", "e2"));
			assertEquals(1, second.applied().questions().size(), second.applied().toString());
			Map<String, Object> q = second.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"));
			assertEquals("Anna", q.get("subject"));
			List<Map<String, Object>> c = candidates(second);
			assertTrue(c.stream().anyMatch(x -> anna.ref().equals(x.get("id"))), "Anna Lindqvist is a candidate: " + c);
			assertTrue(c.stream().anyMatch(x -> "new".equals(x.get("id"))), "'new' is offered: " + c);
			assertTrue(second.applied().facts().isEmpty(), "the fact is held, not stored");
			assertTrue(e.entities().byRef("Anna").isEmpty(), "no new Anna entity yet");
			assertEquals(1, e.questions().openCount());

			RememberOutcome yes = remember(e, "Yes, the same Anna.", null, new Resolve(questionId(second), anna.ref()));
			assertEquals(1, yes.resolved().size(), yes.resolved().toString());
			assertEquals("answered", yes.resolved().getFirst().get("status"));
			List<Fact> facts = e.facts().factsOf(anna.id());
			assertTrue(facts.stream().anyMatch(f -> "works_at".equals(f.predicate()) && f.subjectId() == anna.id()),
					"held fact now stored against Anna Lindqvist: " + facts);
			assertEquals(0, e.questions().openCount(), "question closed");
			assertEquals(anna.id(), e.entities().byRef("Anna").orElseThrow().id(), "Anna is now an alias");
		}
	}

	@Test
	@Scenario("B2")
	void answeringNewCreatesTheSecondEntity() {
		try (Engine e = engine("b2-new")) {
			remember(e, "I met Anna Lindqvist at the conference.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome second = remember(e, "Anna is joining Hooli next month.", proposal()
					.entity("e1", "Anna", "person").entity("e2", "Hooli", "organization").fact("e1", "works_at", "e2"));
			RememberOutcome no = remember(e, "No, a different Anna.", null, new Resolve(questionId(second), "new"));
			Entity created = e.entities().byRef(no.resolved().getFirst().get("entity").toString()).orElseThrow();
			assertEquals("Anna", created.name());
			assertEquals("person", created.type());
			assertTrue(e.facts().factsOf(created.id()).stream().anyMatch(f -> "works_at".equals(f.predicate())));
			Entity lindqvist = e.entities().byRef("Anna Lindqvist").orElseThrow();
			assertTrue(created.id() != lindqvist.id(), "two people now");
			assertEquals(created.id(), e.entities().byRef("Anna").orElseThrow().id());
		}
	}

	@Test
	@Scenario("B2")
	void sloppyProposalsDegradeToWarningsNotErrors() {
		try (Engine e = engine("b2-sloppy")) {
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			// A local 27B sent: an entity without a name, the same ambiguous name twice, an event with a blank
			// participant. None of these may abort the observation (bench errors.jsonl, 2026-09-08).
			var p = proposal().entity("e1", "Anna", "person").entity("e2", "Anna", "person").entity("e3", null, "place")
					.event("ev1", "met", "2024", "self", "").fact("e1", "works_at", "Hooli")
					.fact("e3", "located_in", "Sweden");
			RememberOutcome o = remember(e, "Anna, from somewhere, met someone.", p);
			assertEquals(1, o.applied().questions().size(), "one question for the ambiguous Anna: " + o.applied());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("without a 'name'")),
					o.applied().toString());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.startsWith("Event 'met' skipped")),
					o.applied().toString());
			assertTrue(o.applied().facts().isEmpty(), "nothing stored from held or broken parts");
		}
	}

	@Test
	@Scenario("B2")
	void sharedSurnameBehindDifferentGivenNamesIsNotAmbiguous() {
		try (Engine e = engine("b2-surname")) {
			remember(e, "My father is Konrad Nyberg.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			// Six children declared in one proposal, each with a full name that shares only the family name.
			var p = proposal();
			String[] names = {"Marit Nyberg", "Oskar Nyberg", "Nora Nyberg", "Elias Nyberg", "Freja Nyberg",
					"Vilhelm Nyberg"};
			for (int i = 0; i < names.length; i++) {
				p.entity("c" + i, names[i], "person")
						.fact(fact("self", "parent_of", "c" + i, "father", null, null, null, null, null, null));
			}
			RememberOutcome o = remember(e, "My children are Marit, Oskar, Nora, Elias, Freja and Vilhelm.", p);
			assertTrue(o.applied().questions().isEmpty(),
					"no ambiguity against the father: " + o.applied().questions());
			assertEquals(6, o.applied().facts().size());
			assertEquals(6, o.applied().entities().stream().filter(x -> "created".equals(x.resolution())).count());
			// A bare first name that a known full name starts with is still an ambiguity.
			RememberOutcome anna = remember(e, "Marit called.",
					proposal().entity("e1", "Marit", "person").fact("self", "knows", "e1"));
			assertEquals(1, anna.applied().questions().size(), "'Marit' vs 'Marit Nyberg' stays a question");
		}
	}

	@Test
	void entityTypesInOneProposalMayNameEachOtherAsParents() {
		try (Engine e = engine("types-in-order")) {
			// "dog" names "animal" as its parent, and "animal" is defined after it in the same proposal.
			RememberOutcome o = remember(e, "Our dog is called Rufus.",
					proposal().entity("r", "Rufus", "dog").fact("self", "owns", "r")
							.entityType(new EntityTypeDef("dog", "A domestic dog", "animal", List.of(),
									List.of("dog", "puppy")))
							.entityType(new EntityTypeDef("animal", "A living animal", null, List.of(), List.of())));
			assertEquals("animal", e.entityTypes().get("dog").orElseThrow().parent(), o.applied().toString());
			assertTrue(o.applied().warnings().stream().noneMatch(w -> w.contains("unknown parent")),
					o.applied().warnings().toString());
			assertTrue(o.applied().questions().stream().noneMatch(q -> "type_kind".equals(q.get("kind"))),
					"the kind was stated, so it is not asked: " + o.applied().questions());
		}
	}

	@Test
	void anAnswerThatSettlesALaterAnswerInTheSameBatchDoesNotFailTheBatch() {
		try (Engine e = engine("batch-settles")) {
			// A new type with no stated kind, used where a person is expected: the store asks what kind of thing a
			// dog is, and whether the mismatch is fine. Answering the kind settles the mismatch.
			RememberOutcome o = remember(e, "Rufus works at Initrode.",
					proposal().entity("r", "Rufus", "dog").fact("r", "works_at", "Initrode"));
			List<Map<String, Object>> asked = o.applied().questions();
			String kind = asked.stream().filter(q -> "type_kind".equals(q.get("kind"))).map(q -> q.get("id").toString())
					.findFirst().orElseThrow(() -> new AssertionError("no type_kind question: " + asked));
			String mismatch = asked.stream().filter(q -> "type_mismatch".equals(q.get("kind")))
					.map(q -> q.get("id").toString()).findFirst()
					.orElseThrow(() -> new AssertionError("no type_mismatch question: " + asked));
			RememberOutcome answered = remember(e, "A dog is a person here.", null, new Resolve(kind, "person"),
					new Resolve(mismatch, "kind:person"));
			assertEquals(2, answered.resolved().size(), answered.resolved().toString());
			assertEquals("answered", answered.resolved().get(0).get("status"), answered.resolved().toString());
			assertTrue(answered.resolved().get(1).get("status").toString().startsWith("already"),
					"settled by the first answer, reported rather than refused: " + answered.resolved());
			assertEquals(0, e.questions().openCount());
			// Repeating an answer is reported the same way, never an error.
			RememberOutcome again = remember(e, "Yes, a person.", null, new Resolve(kind, "person"));
			assertEquals("already answered", again.resolved().getFirst().get("status"), again.resolved().toString());
		}
	}

	@Test
	@Scenario("B2")
	void answeringSeveralQuestionsInOneCallDoesNotCascade() {
		try (Engine e = engine("b2-batch")) {
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			// Two names that are both ambiguous against Anna Lindqvist, held in one call.
			RememberOutcome held = remember(e, "Anna and Anna Karlsson came by.",
					proposal().entity("e1", "Anna", "person").entity("e2", "Anna Karlsson", "person")
							.fact("self", "knows", "e1").fact("self", "knows", "e2"));
			assertEquals(2, held.applied().questions().size(), held.applied().toString());
			String q1 = held.applied().questions().get(0).get("id").toString();
			String q2 = held.applied().questions().get(1).get("id").toString();
			RememberOutcome answered = remember(e, "Both new people.", null, new Resolve(q1, "new"),
					new Resolve(q2, "new"));
			assertEquals(2, answered.resolved().size());
			assertEquals(0, e.questions().openCount(), "answering one must not re-ask about the other");
			Entity lindqvist = e.entities().byRef("Anna Lindqvist").orElseThrow();
			Entity anna = e.entities().byRef("Anna").orElseThrow();
			Entity karlsson = e.entities().byRef("Anna Karlsson").orElseThrow();
			assertTrue(anna.id() != lindqvist.id() && karlsson.id() != lindqvist.id() && anna.id() != karlsson.id(),
					"three distinct Annas");

		}
	}

	@Test
	@Scenario("B2")
	void anExistingEntityIdIsAlwaysAnAcceptableAnswer() {
		try (Engine e = engine("b2-anyid")) {
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome held = remember(e, "Anna came by.",
					proposal().entity("e1", "Anna", "person").fact("self", "knows", "e1"));
			Entity other = e.entities().create("Anna Berg", "person", List.of(), null);
			RememberOutcome answered = remember(e, "It was Anna Berg.", null,
					new Resolve(questionId(held), other.ref()));
			assertEquals(other.ref(), answered.resolved().getFirst().get("entity"));
			assertEquals(0, e.questions().openCount());
			assertEquals(other.id(), e.entities().byRef("Anna").orElseThrow().id(), "'Anna' is now her alias");
		}
	}

	@Test
	@Scenario("G2")
	void consolidateClosesAQuestionWhoseSubjectNowExists() {
		try (Engine e = engine("g2-stale")) {
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome held = remember(e, "Anna Karlsson came by.",
					proposal().entity("e1", "Anna Karlsson", "person").fact("self", "knows", "e1"));
			assertEquals(1, held.applied().questions().size());
			// Meanwhile the entity comes into being another way (a later observation, an answer elsewhere).
			Entity created = e.entities().create("Anna Karlsson", "person", List.of(), null);
			Consolidation dry = e.consolidate(true);
			assertEquals(1, dry.resolvedQuestions().size());
			assertEquals(1, e.questions().openCount(), "dry run changes nothing");
			Consolidation c = e.consolidate(false);
			assertEquals(1, c.resolvedQuestions().size());
			assertEquals(created.ref(), c.resolvedQuestions().getFirst().get("entity"));
			assertEquals(0, e.questions().openCount());
			assertTrue(e.facts().factsOf(created.id()).stream().anyMatch(f -> "knows".equals(f.predicate())),
					"the held fact landed on the existing entity");
		}
	}

	@Test
	@Scenario("C3")
	void placesNest() {
		try (Engine e = engine("c3-nest")) {
			RememberOutcome a = remember(e, "Schübelbach is in Switzerland.",
					proposal().entity("e1", "Schübelbach", "place").entity("e2", "Switzerland", "place").fact("e1",
							"located_in", "e2"));
			RememberOutcome b = remember(e, "Schübelbach is in Kanton Schwyz.",
					proposal().entity("e1", "Schübelbach", "place").entity("e2", "Kanton Schwyz", "place").fact("e1",
							"located_in", "e2"));
			assertTrue(b.applied().questions().isEmpty(), "containment nests, no conflict: " + b.applied().questions());
			assertEquals("current", stored(e, a, 0).status());
			assertEquals("current", stored(e, b, 0).status());
			assertEquals(33, e.database().schemaVersion());
		}
	}

	@Test
	@Scenario("F1")
	void aMissWithMatchingEventsIsNotAMiss() {
		try (Engine e = engine("f1-events")) {
			remember(e, "My daughter Marit was born in 2010 and my son Elias in 2012.",
					proposal().entity("e1", "Marit Nyberg", "person").entity("e2", "Elias Nyberg", "person")
							.event("ev1", "born", "2010", "e1").event("ev2", "born", "2012", "e2")
							.fact(fact("self", "parent_of", "e1", "father", null, null, null, null, null, null))
							.fact(fact("self", "parent_of", "e2", "father", null, null, null, null, null, null)));
			RecallResult r = recall(e, "when were Mattias's children born");
			// "children" is a cue for parent_of since the inverse lexicon (2026-09-10), so the verdict may match the
			// children themselves; either way the birth events carry the answer and no MISS heads the block.
			assertTrue("events".equals(r.structured().state()) || "matched".equals(r.structured().state()), r.text());
			assertEquals(2, r.events().size(), r.text());
			assertFalse(r.text().contains("MISS"),
					"the headline must not tell the assistant to say it does not know: " + r.text());
			assertTrue(r.text().contains("events: ") && r.text().contains("Marit Nyberg was born"), r.text());
		}
	}

	@Test
	@Scenario("B2")
	void answersInOneCallBindEachOtherWhateverTheOrder() {
		try (Engine e = engine("b2-order")) {
			// Two names that are each a real ambiguity against something already known, held in one proposal
			// with a fact tying them together: "Luzern" against the known "Luzern Süd", "Willisau" against the
			// apartment's alias.
			remember(e, "Luzern Süd is a district.",
					proposal().entity("e1", "Luzern Süd", "place").fact("self", "prefers", "e1"));
			remember(e, "The Lindenhof apartment is ours.", proposal()
					.entity("e1", "Lindenhof apartment", "place", "the Willisau apartment").fact("self", "owns", "e1"));
			RememberOutcome held = remember(e, "Willisau is near Luzern.", proposal().entity("e1", "Willisau", "place")
					.entity("e2", "Luzern", "place").fact("e1", "located_in", "e2"));
			assertEquals(2, held.applied().questions().size(), held.applied().questions().toString());
			String willisau = held.applied().questions().stream().filter(q -> "Willisau".equals(q.get("subject")))
					.findFirst().orElseThrow().get("id").toString();
			String luzern = held.applied().questions().stream().filter(q -> "Luzern".equals(q.get("subject")))
					.findFirst().orElseThrow().get("id").toString();
			// Willisau first: its held fact names Luzern, which the second answer creates.
			RememberOutcome answered = remember(e, "Both new.", null, new Resolve(willisau, "new"),
					new Resolve(luzern, "new"));
			assertEquals(0, e.questions().openCount(), "no follow-up question: " + answered.resolved());
			Entity r = e.entities().byRef("Willisau").orElseThrow();
			Entity l = e.entities().byRef("Luzern").orElseThrow();
			assertTrue(
					e.facts().factsOf(r.id()).stream()
							.anyMatch(f -> "located_in".equals(f.predicate()) && f.objectId() == l.id()),
					"Willisau located_in Luzern landed on the new entity");
			assertEquals(1, e.facts().factsOf(r.id()).size(), "exactly once");
		}
	}

	@Test
	@Scenario("J1")
	void siblingKindsAndDegreesAreKnownQualifiers() {
		try (Engine e = engine("j1-siblings")) {
			RememberOutcome o = remember(e, "Erik is my twin, Sara my half-sister.",
					proposal().entity("e1", "Erik", "person").entity("e2", "Sara", "person")
							.fact(fact("e1", "sibling_of", "self", "twin", null, null, null, null, null, null))
							.fact(fact("e2", "sibling_of", "self", "half-sister", null, null, null, null, null, null)));
			assertTrue(o.applied().warnings().isEmpty(), o.applied().warnings().toString());
			assertEquals(2, o.applied().facts().size());
			assertTrue(o.applied().facts().getFirst().rendering().contains("twin"), o.applied().facts().toString());
		}
	}

	@Test
	@Scenario("B2")
	void anAliasOfAnEntityDeclaredDistinctIsNotAnAmbiguity() {
		try (Engine e = engine("b2-alias")) {
			// Declared together: the apartment carries the alias, Willisau is a place of its own.
			RememberOutcome o = remember(e, "The Lindenhof apartment is in Willisau.",
					proposal().entity("e1", "Lindenhof apartment", "place", "the Willisau apartment")
							.entity("e2", "Willisau", "place").fact("e1", "located_in", "e2"));
			assertTrue(o.applied().questions().isEmpty(), "declared distinct: " + o.applied().questions());
			assertEquals(1, o.applied().facts().size());
		}
		try (Engine e = engine("b2-alias-earlier")) {
			// The apartment was declared in an earlier call; declaring both again still asserts they are distinct.
			remember(e, "We own the Lindenhof apartment.", proposal()
					.entity("e1", "Lindenhof apartment", "place", "the Willisau apartment").fact("self", "owns", "e1"));
			RememberOutcome o = remember(e, "The Lindenhof apartment is in Willisau.",
					proposal().entity("e1", "Lindenhof apartment", "place").entity("e2", "Willisau", "place").fact("e1",
							"located_in", "e2"));
			assertTrue(o.applied().questions().isEmpty(), "still distinct: " + o.applied().questions());
			// Willisau alone, with nothing said about the apartment, is the boundary: the alias makes it a fair question.
		}
		try (Engine e = engine("b2-alias-alone")) {
			remember(e, "We own the Lindenhof apartment.", proposal()
					.entity("e1", "Lindenhof apartment", "place", "the Willisau apartment").fact("self", "owns", "e1"));
			RememberOutcome o = remember(e, "Willisau is pretty.",
					proposal().entity("e1", "Willisau", "place").fact("self", "prefers", "e1"));
			assertEquals(1, o.applied().questions().size(), "nothing declared it distinct: " + o.applied().questions());
		}
	}

	@Test
	@Scenario("B2")
	void aSharedTypeWordIsNotAnIdentity() {
		try (Engine e = engine("b2-typeword")) {
			remember(e, "Kanton Schwyz is in Switzerland.", proposal().entity("e1", "Kanton Schwyz", "place")
					.entity("e2", "Switzerland", "place").fact("e1", "located_in", "e2"));
			RememberOutcome o = remember(e, "Willisau is in Kanton Luzern.",
					proposal().entity("e1", "Willisau", "place").entity("e2", "Kanton Luzern", "place").fact("e1",
							"located_in", "e2"));
			assertTrue(o.applied().questions().isEmpty(), "'Kanton' identifies nothing: " + o.applied().questions());
			assertEquals(2, e.entities().spot("Kanton Luzern").size() + e.entities().spot("Kanton Schwyz").size());
			// The same for companies: "Acme AB" against "Beta AB".
			remember(e, "I consult for Acme AB.",
					proposal().entity("e1", "Acme AB", "organization").fact("self", "member_of", "e1"));
			RememberOutcome b = remember(e, "I also work with Beta AB.",
					proposal().entity("e1", "Beta AB", "organization").fact("self", "member_of", "e1"));
			assertTrue(b.applied().questions().isEmpty(), b.applied().questions().toString());
			// A shared identifying token still asks: "Anna" against "Anna Lindqvist".
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome c = remember(e, "Anna called.",
					proposal().entity("e1", "Anna", "person").fact("self", "knows", "e1"));
			assertEquals(1, c.applied().questions().size());
		}
	}

	@Test
	@Scenario("D3")
	void forgetIsNotTheDefaultForContradictions() {
		try (Engine e = engine("d3")) {
			RememberOutcome a = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			assertEquals("conflict", b.applied().questions().getFirst().get("kind"));
			String pendingId = b.applied().facts().getFirst().id();
			assertEquals("pending", stored(e, b, 0).status());
			assertEquals(pendingId, b.applied().questions().getFirst().get("pending_fact"),
					"question links the pending fact");

			RememberOutcome c = remember(e, "Sorry, I meant I consult for Acme.",
					proposal().fact("consults_for", "Acme"), new Resolve(questionId(b), "reinterpret"));
			assertEquals("current", stored(e, a, 0).status(), "Hooli untouched");
			assertEquals("rejected", stored(e, b, 0).status(), "Acme works_at rejected, not deleted");
			assertEquals("consults_for", c.applied().facts().getFirst().predicate());
			assertEquals("current", stored(e, c, 0).status());
			assertTrue(e.observations().get(b.observation().observationId()).isPresent(),
					"earlier observation retained");
			var changes = e.facts().supersessionsOf(Long.parseLong(pendingId.substring(2)));
			assertEquals("invalidation", changes.getFirst().kind());
			assertTrue(changes.getFirst().reason().contains("reinterpret"));
			assertEquals(0, e.questions().openCount());
		}
	}

	@Test
	@Scenario("C2")
	void conflictAnswersEndedSupersedeReject() {
		try (Engine e = engine("c2-answers")) {
			// ended: the earlier value ended at an unknown date, the new one is current.
			RememberOutcome a = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			remember(e, "I left Hooli, I just don't remember when.", null, new Resolve(questionId(b), "ended"));
			Fact hooli = stored(e, a, 0);
			assertTrue(hooli.ended());
			assertNull(hooli.validEnd(), "unknown end date stays unknown");
			assertEquals("current", hooli.status(), "closed, not replaced");
			assertEquals("current", stored(e, b, 0).status());

			// supersede: the new one replaces the earlier from today.
			RememberOutcome c = remember(e, "I work at Initech.", proposal().fact("works_at", "Initech"));
			assertEquals("conflict", c.applied().questions().getFirst().get("kind"));
			remember(e, "Initech replaced Acme.", null, new Resolve(questionId(c), "supersede"));
			Fact acme = stored(e, b, 0);
			assertEquals("superseded", acme.status());
			assertEquals(stored(e, c, 0).id(), acme.supersededBy());
			assertNotNull(acme.validEnd());
			assertEquals("current", stored(e, c, 0).status());

			// reject: the new one was wrong.
			RememberOutcome d = remember(e, "I work at Globex.", proposal().fact("works_at", "Globex"));
			remember(e, "No, that was a joke.", null, new Resolve(questionId(d), "reject"));
			assertEquals("rejected", stored(e, d, 0).status());
			assertEquals("current", stored(e, c, 0).status(), "Initech stays");
			assertEquals(0, e.questions().openCount());

			// a closed question answered again: reported as such, nothing changes, nothing fails (an assistant
			// that repeats a batch after one item failed must not be refused for the items that went through)
			RememberOutcome again = remember(e, "Again.", null, new Resolve(questionId(d), "ended"));
			assertEquals("already answered", again.resolved().getFirst().get("status"), again.resolved().toString());
			assertEquals("reject", again.resolved().getFirst().get("answer"), "the standing answer is shown");
			assertEquals("rejected", stored(e, d, 0).status(), "the second answer did not apply");
			assertEquals("current", stored(e, c, 0).status());
		}
	}

	@Test
	@Scenario("D3")
	void aConflictCanSayTheEarlierRecordWasWrong() {
		try (Engine e = engine("d3-wrong")) {
			RememberOutcome a = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			assertEquals("conflict", b.applied().questions().getFirst().get("kind"));
			remember(e, "Hooli was a mistake, it was always Acme.", null, new Resolve(questionId(b), "wrong"));
			assertEquals("corrected", stored(e, a, 0).status(), "the earlier fact is corrected, not ended or rejected");
			assertEquals(stored(e, b, 0).id(), stored(e, a, 0).supersededBy());
			assertEquals("current", stored(e, b, 0).status());
			var changes = e.facts().supersessionsOf(stored(e, a, 0).id());
			assertEquals("correction", changes.getFirst().kind());
			assertEquals(0, e.questions().openCount());
		}
	}

	@Test
	@Scenario("G2")
	void laterAliasMergesEarlierDuplicates() {
		try (Engine e = engine("g2")) {
			remember(e, "I met Anna Lindqvist.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			RememberOutcome al = remember(e, "AL and I talked about JFR.",
					proposal().entity("e1", "AL", "person").fact("self", "knows", "e1"));
			assertEquals("created", al.applied().entities().getFirst().resolution(), "AL is too short to fuzzy-match");
			long alId = Long.parseLong(al.applied().entities().getFirst().id().substring(4));
			RememberOutcome third = remember(e, "Anna — AL to her friends — is visiting.",
					proposal().entity("e1", "Anna Lindqvist", "person", "AL", "Anna").fact("self", "knows", "e1"));
			assertEquals("merged", third.applied().entities().getFirst().resolution(), third.applied().toString());
			Entity anna = e.entities().byRef("Anna Lindqvist").orElseThrow();
			assertEquals(anna.id(), e.entities().byRef("AL").orElseThrow().id());
			assertEquals(anna.id(), e.entities().byRef("Anna").orElseThrow().id());
			assertEquals(anna.id(), e.entities().get(alId).orElseThrow().id(), "the old id follows the merge");
			List<String> aliases = e.entities().aliases(anna.id());
			assertTrue(aliases.contains("AL") && aliases.contains("Anna"), aliases.toString());
			assertTrue(e.facts().factsOf(anna.id()).stream().allMatch(f -> f.objectId() == anna.id()),
					"facts re-pointed");
			assertEquals(1, e.entities().merges().size(), "merge recorded");
			Consolidation c = e.consolidate(false);
			assertTrue(c.merges().isEmpty(), "nothing left to merge");
		}
	}

	@Test
	@Scenario("G3")
	void consolidateWithoutAModelDoesNotFabricate() {
		try (Engine e = engine("g3")) {
			remember(e, "Met Anna for coffee, she now leads the platform team at Acme.");
			Consolidation c = e.consolidate(false);
			assertEquals(1, c.pendingProposals());
			assertEquals(1, c.backlog().size());
			assertEquals(0, e.facts().count(), "no facts invented");
			assertTrue(c.merges().isEmpty());
			assertTrue(c.inferredVocabulary().isEmpty());
		}
	}

	@Test
	@Scenario("G4")
	void answersAndCorrectionsNeverJoinTheBacklog() {
		try (Engine e = engine("g4-backlog")) {
			RememberOutcome o = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			assertEquals(0, e.observations().pendingProposals());
			// An answer with no proposal of its own has nothing to extract.
			remember(e, "I left Hooli, I just don't remember when.", null, new Resolve(questionId(b), "ended"));
			assertEquals(0, e.observations().pendingProposals(), "an answer is not a backlog entry");
			// Neither has a correction record.
			e.correct(stored(e, o, 0).id(), Map.of("valid_time", Map.of("start", "2019")), "start date");
			assertEquals(0, e.observations().pendingProposals(), "a correction is not a backlog entry");
			// A plain note is, until the caller retires it.
			long note = remember(e, "Just a note to self.").observationId();
			assertEquals(1, e.observations().pendingProposals());
			var c = e.consolidate(false, List.of(note));
			assertEquals(List.of("obs-" + note), c.retired());
			assertEquals(0, e.observations().pendingProposals());
			assertEquals("Just a note to self.", e.observations().get(note).orElseThrow().text(), "text kept");
		}
	}

	@Test
	@Scenario("J3")
	void ambiguousPredicateMatchAsks() {
		try (Engine e = engine("j3")) {
			var def = new PredicateDef("advises", "Subject gives professional advice on work matters to object.",
					"person", "organization", false, null, null, null, null, List.of(), null, List.of(), List.of());
			RememberOutcome o = remember(e, "I advise Acme on their profiler.",
					proposal().predicate(def).entity("e1", "Acme", "organization").fact("self", "advises", "e1"));
			assertEquals(1, o.applied().questions().size(), o.applied().toString());
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("predicate_resolution", q.get("kind"));
			List<Map<String, Object>> c = candidates(o);
			assertTrue(c.stream().anyMatch(x -> "works_at".equals(x.get("id"))), c.toString());
			assertTrue(c.stream().anyMatch(x -> "new".equals(x.get("id"))), c.toString());
			assertTrue(o.applied().facts().isEmpty(), "held");
			assertTrue(e.predicates().get("advises").isEmpty(), "not registered yet");

			RememberOutcome yes = remember(e, "No, advising is not working there.", null,
					new Resolve(questionId(o), "new"));
			assertTrue(e.predicates().get("advises").isPresent(), "registered on 'new'");
			assertEquals(1, ((List<?>) yes.resolved().getFirst().get("facts")).size());
			Entity acme = e.entities().byRef("Acme").orElseThrow();
			assertTrue(e.facts().factsOf(acme.id()).stream().anyMatch(f -> "advises".equals(f.predicate())));
		}
	}

	@Test
	@Scenario("J3")
	void answeringWithTheCandidateUsesIt() {
		try (Engine e = engine("j3-existing")) {
			var def = new PredicateDef("advises", "Subject gives professional advice on work matters to object.",
					"person", "organization", false, null, null, null, null, List.of(), null, List.of(), List.of());
			RememberOutcome o = remember(e, "I advise Acme on their profiler.",
					proposal().predicate(def).entity("e1", "Acme", "organization").fact("self", "advises", "e1"));
			remember(e, "Yes, it is a job.", null, new Resolve(questionId(o), "works_at"));
			Entity acme = e.entities().byRef("Acme").orElseThrow();
			assertTrue(e.facts().factsOf(acme.id()).stream().anyMatch(f -> "works_at".equals(f.predicate())));
			assertTrue(e.predicates().get("advises").isEmpty());
		}
	}

	@Test
	@Scenario("J5")
	void predicateCorrectionPropagates() {
		try (Engine e = engine("j5")) {
			var def = new PredicateDef("godparent_of", "Subject sponsored object at baptism or equivalent.", "person",
					"person", false, null, null, null, "low", List.of("godparent", "godmother", "godfather"),
					"{subject} is {object}'s {qualifier|godparent}", List.of("godmother", "godfather"), List.of());
			remember(e, "My godmother is Hedvig.", proposal().predicate(def).entity("e1", "Hedvig", "person")
					.fact(fact("e1", "godparent_of", "self", "godmother", null, null, null, null, null, null)));
			remember(e, "My godfather is Sven.", proposal().entity("e1", "Sven", "person")
					.fact(fact("e1", "godparent_of", "self", "godfather", null, null, null, null, null, null)));
			Map<String, Object> out = e.correctPredicate("godparent_of",
					Map.of("render", "{object}'s {qualifier|godparent} is {subject}"), "better rendering");
			assertEquals(2, out.get("rerendered_facts"));
			Entity hedvig = e.entities().byRef("Hedvig").orElseThrow();
			Fact f = e.facts().factsOf(hedvig.id()).getFirst();
			assertEquals("Mattias Sandell's godmother is Hedvig", f.rendering());
			assertEquals(1, e.predicates().changes("godparent_of").size(), "the change is on record");
			assertTrue(e.recall().recall("who is Mattias's godmother", null, 800, 10).structured().matched());
		}
	}

	@Test
	@Scenario("J6")
	void predicatesRegisteredFromUseAreListedForDefinition() {
		try (Engine e = engine("j6")) {
			for (String who : List.of("Anna", "Erik", "Sara")) {
				remember(e, "I mentor " + who + ".",
						proposal().entity("e1", who, "person").fact("self", "mentors", "e1"));
			}
			Consolidation c = e.consolidate(true);
			assertEquals(1, c.inferredVocabulary().size(), c.toString());
			Map<String, Object> s = c.inferredVocabulary().getFirst();
			assertEquals("mentors", s.get("predicate"));
			assertEquals(3L, s.get("uses"));
			assertEquals(3, ((List<?>) s.get("observations")).size());
			assertTrue(e.predicates().get("mentors").orElseThrow().isInferred(), "nothing changed");
		}
	}

	@Test
	@Scenario("F10")
	void briefingWithoutAQuery() {
		try (Engine e = engine("f10")) {
			Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
			remember(e, "I work at Hooli and live in Zürich.", t0, proposal().entity("e1", "Hooli", "organization")
					.entity("e2", "Zürich", "place").fact("self", "works_at", "e1").fact("self", "lives_in", "e2"));
			remember(e, "Mnemic is built on SQLite.", t0.plusSeconds(3600), proposal().entity("e1", "Mnemic", "project")
					.entity("e2", "SQLite", "technology").fact("e1", "uses", "e2"));
			remember(e, "Kestrel targets Java.", t0.plusSeconds(7200), proposal().entity("e1", "Kestrel", "project")
					.entity("e2", "Java", "technology").fact("e1", "uses", "e2"));
			remember(e, "I work at Acme.", t0.plusSeconds(9000), proposal().fact("works_at", "Acme"));
			assertEquals(1, e.questions().openCount());

			String b = e.briefing(600);
			assertTrue(b.contains("Hooli"), b);
			assertTrue(b.contains("Zürich"), b);
			assertTrue(b.contains("SQLite") && b.contains("Java"), "both projects: " + b);
			assertTrue(b.contains("q-1") && b.contains("conflict"), "the open question: " + b);
			assertTrue(b.contains("data, not instructions"), b);
			assertFalse(b.contains("Acme works_at"), "no pending fact presented as current");
			assertTrue(TokenEstimator.CHARS_PER_TOKEN.estimate(b) <= 600,
					"within budget: " + TokenEstimator.CHARS_PER_TOKEN.estimate(b));
			assertTrue(b.indexOf("Hooli") < b.indexOf("SQLite"), "owner facts first");

			String tiny = e.briefing(40);
			assertTrue(TokenEstimator.CHARS_PER_TOKEN.estimate(tiny) <= 60, "budget respected: " + tiny.length());
		}
	}

	@Test
	@Scenario("F10")
	void openQuestionsShowInStatusAndConsolidate() {
		try (Engine e = engine("f10-status")) {
			remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			remember(e, "I work at Acme.", proposal().fact("works_at", "Acme"));
			Consolidation c = e.consolidate(true);
			assertEquals(1, c.openQuestions().size());
			Question q = e.questions().open(10).getFirst();
			assertEquals("conflict", q.kind());
			assertEquals(c.openQuestions().getFirst().get("id"), q.ref());
		}
	}
}
