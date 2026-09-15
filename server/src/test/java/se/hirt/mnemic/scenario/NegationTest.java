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

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.recall;

/**
 * EVALUATION.md family Q: known-false facts and exclusive restrictions (written 2026-09-10 from two probes
 * against a real store, built with the K family in M4). The proposals are written as JSON so that the two new
 * keys, {@code negated} and {@code only}, and the {@code closures} list are spelled the way a caller sends them.
 *
 * <p>The shapes:
 * <ul>
 * <li>{@code "negated": true} on a fact: the subject does not stand in the relation to the object. The object is
 * an entity ("I do not own the Willisau apartment") or a literal class ("anything in Sweden").</li>
 * <li>{@code "only": true} on a fact whose object is a place: an exclusive restriction. Every asserted fact under
 * the predicate for the subject whose object can be located must be located, through the {@code located_in}
 * chain, within the bound. Objects that cannot be located (a domain, a printer) are outside the class and
 * untouched. Nothing else in the class is so.</li>
 * <li>{@code closures: [{subject, predicate, type}]}: a completeness marker. The recorded facts under the
 * predicate for the subject whose objects have that type are all of them, so an absent fact in the class is a
 * no.</li>
 * </ul>
 * Neither is listed among the subject's positive facts; both appear on a {@code bounds:} line. A contradiction
 * on the same key is a conflict question with the existing answers ({@code ended}, {@code wrong},
 * {@code reject}). Containment the chain cannot decide is a {@code containment} question, never an assertion.
 */
class NegationTest {

	private static RememberOutcome remember(Engine e, String text, String proposalJson, Resolve... resolves) {
		return e.remember(text, Source.user(), null, proposalJson == null ? null : Proposal.parse(proposalJson),
				Proposal.CURRENT_SPEC_VERSION, null, List.of(resolves));
	}

	private static String questionId(RememberOutcome o) {
		return (String) o.applied().questions().getFirst().get("id");
	}

	private static Map<String, Object> firstQuestion(RememberOutcome o) {
		return o.applied().questions().getFirst();
	}

	/** Two Swiss properties, one with a complete chain to the country and one whose chain stops at the canton. */
	private static void swissProperties(Engine e) {
		remember(e, "I own the Lindenhof apartment in Willisau, Kanton Luzern, Switzerland.", """
				{"entities": [{"ref": "e1", "name": "Lindenhof apartment", "type": "place"},
				              {"ref": "e2", "name": "Willisau", "type": "place"},
				              {"ref": "e3", "name": "Kanton Luzern", "type": "place"},
				              {"ref": "e4", "name": "Switzerland", "type": "country"}],
				 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"},
				           {"subject": "e1", "predicate": "located_in", "object": "e2"},
				           {"subject": "e2", "predicate": "located_in", "object": "e3"},
				           {"subject": "e3", "predicate": "located_in", "object": "e4"}]}""");
		remember(e, "I own Bergstrasse 7 in Schübelbach, Kanton Schwyz.", """
				{"entities": [{"ref": "e1", "name": "Bergstrasse 7", "type": "place"},
				              {"ref": "e2", "name": "Schübelbach", "type": "place"},
				              {"ref": "e3", "name": "Kanton Schwyz", "type": "place"}],
				 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"},
				           {"subject": "e1", "predicate": "located_in", "object": "e2"},
				           {"subject": "e2", "predicate": "located_in", "object": "e3"}]}""");
	}

	@Test
	@Scenario("Q1")
	void aNegatedFactIsKnownFalseNotUnknown() {
		try (Engine e = engine("q1-negated")) {
			// Before anything is said, the question is open: a MISS, and a MISS is not "no".
			RecallResult before = recall(e, "does Mattias own a boat");
			assertEquals("miss", before.structured().state(), before.text());
			assertTrue(before.text().contains("not evidence of no"), before.text());

			RememberOutcome o = remember(e, "I do not own a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "negated": true}]}""");
			assertEquals(1, o.applied().facts().size());
			assertEquals("Mattias Sandell does not own boat", o.applied().facts().getFirst().rendering(),
					"a negated fact renders as a negation, never as the positive sentence");

			RecallResult after = recall(e, "does Mattias own a boat");
			assertEquals("known_false", after.structured().state(), after.text());
			assertTrue(after.text().contains("KNOWN FALSE"), after.text());
			assertTrue(after.text().contains("does not own boat"), after.text());
			assertFalse(after.text().contains("not evidence of no"), "known false is evidence: " + after.text());
		}
	}

	@Test
	@Scenario("Q2")
	void aNegatedFactIsNotListedAmongThePositiveOnes() {
		try (Engine e = engine("q2-listing")) {
			swissProperties(e);
			remember(e, "I do not own a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "negated": true}]}""");
			RecallResult r = recall(e, "what does Mattias own");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(2, r.structured().facts().size(), "the negation is not something he owns: " + r.text());
			assertTrue(r.text().contains("bounds: Mattias Sandell does not own boat"), r.text());
			// The briefing lists what is so, not what is not.
			String briefing = e.briefing(800);
			assertFalse(briefing.contains("does not own"), briefing);
		}
	}

	@Test
	@Scenario("Q3")
	void aClassNegationAnswersThePolarQuestionOverTheClass() {
		try (Engine e = engine("q3-class")) {
			swissProperties(e);
			remember(e, "I relocated from Sweden to Switzerland in 2014. I own nothing in Sweden.", """
					{"entities": [{"ref": "e1", "name": "Sweden", "type": "country"},
					              {"ref": "e2", "name": "Switzerland", "type": "country"}],
					 "events": [{"ref": "ev1", "type": "relocated", "participants": ["self", "e1", "e2"],
					             "valid_time": {"start": "2014"}}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "anything in Sweden", "negated": true}]}""");
			RecallResult r = recall(e, "does Mattias own any property in Sweden");
			assertEquals("known_false", r.structured().state(), r.text());
			assertTrue(r.text().contains("does not own anything in Sweden"), r.text());
			assertFalse(r.text().contains("are the answer"), "the Swiss purchase and the move are context: " + r.text());
		}
	}

	@Test
	@Scenario("Q4")
	void aNegationAgainstAPositiveFactIsAConflictNotAnOverwrite() {
		try (Engine e = engine("q4-conflict")) {
			RememberOutcome a = remember(e, "I own a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"}]}""");
			RememberOutcome b = remember(e, "I do not own a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "negated": true}]}""");
			assertEquals("conflict", firstQuestion(b).get("kind"), "\"no longer\" and \"never did\" differ");
			assertEquals("pending", b.applied().facts().getFirst().status(), "the negation waits for the answer");
			assertEquals("current", a.applied().facts().getFirst().status());
			String choices = String.valueOf(firstQuestion(b).get("candidates"));
			assertTrue(choices.contains("ended") && choices.contains("wrong") && choices.contains("reject"), choices);

			// ended: he owned it and no longer does. The positive fact closes, the negation is current.
			remember(e, "I sold it last year.", null, new Resolve(questionId(b), "ended"));
			RecallResult r = recall(e, "does Mattias own a boat");
			assertEquals("known_false", r.structured().state(), r.text());
			assertTrue(r.text().contains("ended"), "the earlier ownership is visible as history: " + r.text());
		}
	}

	@Test
	@Scenario("Q5")
	void aPositiveFactAgainstANegationIsAConflictWhoseEndedAnswerClosesTheNegation() {
		try (Engine e = engine("q5-reverse")) {
			remember(e, "I do not own a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "negated": true}]}""");
			RememberOutcome b = remember(e, "I bought a boat.", """
					{"entities": [{"ref": "e1", "name": "boat", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"}],
					 "events": [{"ref": "ev1", "type": "purchased", "participants": ["self", "e1"]}]}""");
			assertEquals("conflict", firstQuestion(b).get("kind"));
			remember(e, "Things changed.", null, new Resolve(questionId(b), "ended"));
			RecallResult r = recall(e, "does Mattias own a boat");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(1, r.structured().facts().size());
			assertFalse(r.text().contains("KNOWN FALSE"), r.text());
			assertFalse(r.text().contains("bounds:"), "the ended negation is history, not a bound: " + r.text());
		}
	}

	@Test
	@Scenario("Q6")
	void anExclusiveRestrictionAnswersNoOutsideItsBoundAndAsksWhereTheChainIsIncomplete() {
		try (Engine e = engine("q6-only")) {
			swissProperties(e);
			RememberOutcome o = remember(e, "I only own properties in Switzerland.", """
					{"entities": [{"ref": "e1", "name": "Switzerland", "type": "country"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "only": true}]}""");
			assertEquals("Mattias Sandell owns only within Switzerland", o.applied().facts().getFirst().rendering());
			// The Lindenhof chain reaches Switzerland: consistent, no question. The Bergstrasse chain stops at
			// Kanton Schwyz: not a conflict (K7), a containment question.
			assertEquals(1, o.applied().questions().size(), o.applied().questions().toString());
			assertEquals("containment", firstQuestion(o).get("kind"));
			assertTrue(String.valueOf(firstQuestion(o).get("message")).contains("Kanton Schwyz"), firstQuestion(o).toString());
			assertEquals("current", o.applied().facts().getFirst().status(),
					"an undecided chain holds nothing back; the restriction is what the user said");

			// Sweden is a country other than the bound: disjoint, so the restriction answers no.
			remember(e, "Sweden is where I come from.", """
					{"entities": [{"ref": "e1", "name": "Sweden", "type": "country"}]}""");
			RecallResult sweden = recall(e, "does Mattias own any property in Sweden");
			assertEquals("known_false", sweden.structured().state(), sweden.text());
			assertTrue(sweden.text().contains("by restriction"), sweden.text());
			assertTrue(sweden.text().contains("owns only within Switzerland"), sweden.text());

			// The canton's containment is not known, so the question about it is neither yes nor no.
			RecallResult schwyz = recall(e, "does Mattias own property in Kanton Schwyz");
			assertTrue(schwyz.structured().matched(), schwyz.text());
			assertTrue(schwyz.text().contains("not known"), "whether Kanton Schwyz is within Switzerland: " + schwyz.text());

			// Answering the containment question stores the missing link and settles it.
			remember(e, "Yes, Schwyz is a Swiss canton.", null, new Resolve(questionId(o), "yes"));
			RecallResult after = recall(e, "does Mattias own property in Kanton Schwyz");
			assertFalse(after.text().contains("not known"), after.text());
			var canton = e.entities().byRef("Kanton Schwyz").orElseThrow();
			assertTrue(e.facts().factsOf(canton.id()).stream().anyMatch(f -> "located_in".equals(f.predicate())
					&& f.rendering().equals("Kanton Schwyz is located in Switzerland")), "the answer is stored as a fact");
		}
	}

	@Test
	@Scenario("Q7")
	void aFactOutsideAnExclusiveRestrictionIsAConflict() {
		try (Engine e = engine("q7-outside")) {
			swissProperties(e);
			remember(e, "I only own properties in Switzerland.", """
					{"entities": [{"ref": "e1", "name": "Switzerland", "type": "country"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "only": true}]}""");
			RememberOutcome b = remember(e, "I bought a cabin in Sälen, Sweden.", """
					{"entities": [{"ref": "e1", "name": "Sälen cabin", "type": "place"},
					              {"ref": "e2", "name": "Sälen", "type": "place"},
					              {"ref": "e3", "name": "Sweden", "type": "country"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"},
					           {"subject": "e1", "predicate": "located_in", "object": "e2"},
					           {"subject": "e2", "predicate": "located_in", "object": "e3"}]}""");
			assertEquals("conflict", firstQuestion(b).get("kind"), "a Swedish property against \"only Switzerland\"");
			assertEquals("pending", b.applied().facts().getFirst().status());
			// ended: the restriction held until now.
			remember(e, "The Switzerland-only days are over.", null, new Resolve(questionId(b), "ended"));
			RecallResult r = recall(e, "what does Mattias own");
			assertEquals(3, r.structured().facts().size(), r.text());
			assertFalse(r.text().contains("bounds:"), "an ended restriction no longer bounds: " + r.text());
			assertEquals("matched", recall(e, "does Mattias own any property in Sweden").structured().state());
		}
	}

	@Test
	@Scenario("Q9")
	void aCompletenessMarkerTurnsAnAbsentFactIntoANo() {
		try (Engine e = engine("q9-closure")) {
			swissProperties(e);
			RememberOutcome o = remember(e, "Those are all the properties I own.", """
					{"closures": [{"subject": "self", "predicate": "owns", "type": "place"}]}""");
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			remember(e, "Sweden is where I come from.", """
					{"entities": [{"ref": "e1", "name": "Sweden", "type": "country"}]}""");
			RecallResult sweden = recall(e, "does Mattias own any property in Sweden");
			assertEquals("known_false", sweden.structured().state(), sweden.text());
			assertTrue(sweden.text().contains("by closure"), sweden.text());
			assertTrue(sweden.text().contains("bounds: what Mattias Sandell owns among places is completely recorded"),
					sweden.text());
			// A boat is not a place: outside the class, so still not known either way.
			RecallResult boat = recall(e, "does Mattias own a boat");
			assertEquals("miss", boat.structured().state(), boat.text());
			// A later property is a conflict with the closure, with the usual answers.
			RememberOutcome later = remember(e, "I bought a cabin in Sälen.", """
					{"entities": [{"ref": "e1", "name": "Sälen cabin", "type": "place"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"}]}""");
			assertEquals("conflict", firstQuestion(later).get("kind"));
			remember(e, "The list was complete until now.", null, new Resolve(questionId(later), "ended"));
			assertEquals(3, recall(e, "what does Mattias own").structured().facts().size());
		}
	}

	@Test
	@Scenario("Q10")
	void aFunctionalPredicateAnswersNoForAnotherValue() {
		try (Engine e = engine("q10-functional")) {
			remember(e, "I work at Hooli.", """
					{"entities": [{"ref": "e1", "name": "Hooli", "type": "organization"}],
					 "facts": [{"subject": "self", "predicate": "works_at", "object": "e1"}]}""");
			remember(e, "Acme is a competitor.", """
					{"entities": [{"ref": "e1", "name": "Acme", "type": "organization"}]}""");
			RecallResult acme = recall(e, "does Mattias work at Acme");
			assertEquals("known_false", acme.structured().state(), acme.text());
			assertTrue(acme.text().contains("one current value, Mattias Sandell works at Hooli"), acme.text());
			// The value itself is a plain yes; an unknown company is the same no.
			assertTrue(recall(e, "does Mattias work at Hooli").structured().matched());
			assertEquals("known_false", recall(e, "does Mattias work at Globex").structured().state());
			// A non-functional predicate never decides this way.
			remember(e, "I lead OpenJDK Kestrel.", """
					{"entities": [{"ref": "e1", "name": "OpenJDK Kestrel", "type": "project"}],
					 "facts": [{"subject": "self", "predicate": "leads", "object": "e1"}]}""");
			assertEquals("miss", recall(e, "does Mattias lead Kubernetes").structured().state());
		}
	}

	@Test
	@Scenario("Q11")
	void aPastTenseQuestionIsAnsweredByHistoryAndNeverDecidedFalse() {
		try (Engine e = engine("q11-past")) {
			remember(e, "I worked at Initrode until 2018, then joined Hooli.", """
					{"entities": [{"ref": "e1", "name": "Initrode", "type": "organization"},
					              {"ref": "e2", "name": "Hooli", "type": "organization"}],
					 "facts": [{"subject": "self", "predicate": "works_at", "object": "e1", "valid_time": {"end": "2018"}},
					           {"subject": "self", "predicate": "works_at", "object": "e2", "valid_time": {"start": "2018"}}]}""");
			RecallResult initrode = recall(e, "did Mattias work at Initrode");
			assertTrue(initrode.structured().matched(), initrode.text());
			assertFalse(initrode.text().contains("KNOWN FALSE"), initrode.text());
			assertTrue(initrode.text().contains("Initrode"), initrode.text());
			// Present tense over the same store: Initrode is over, Hooli is the one value.
			assertEquals("known_false", recall(e, "does Mattias work at Initrode").structured().state());
		}
	}

	@Test
	@Scenario("Q12")
	void aClosureOverAnotherClassAndAnotherSubjectStaysInItsLane() {
		try (Engine e = engine("q12-lanes")) {
			remember(e, "I lead OpenJDK Kestrel and the Profiler team; that is all I lead.", """
					{"entities": [{"ref": "e1", "name": "OpenJDK Kestrel", "type": "project"},
					              {"ref": "e2", "name": "Profiler team", "type": "product"}],
					 "facts": [{"subject": "self", "predicate": "leads", "object": "e1"},
					           {"subject": "self", "predicate": "leads", "object": "e2"}],
					 "closures": [{"subject": "self", "predicate": "leads", "type": "project"}]}""");
			remember(e, "Kubernetes is a project; Anna leads it.", """
					{"entities": [{"ref": "e1", "name": "Kubernetes", "type": "project"},
					              {"ref": "e2", "name": "Anna Lindqvist", "type": "person"}],
					 "facts": [{"subject": "e2", "predicate": "leads", "object": "e1"}]}""");
			// A project he does not lead: no, by closure. His own projects: yes.
			RecallResult k8s = recall(e, "does Mattias lead Kubernetes");
			assertEquals("known_false", k8s.structured().state(), k8s.text());
			assertTrue(k8s.text().contains("by closure"), k8s.text());
			assertTrue(recall(e, "does Mattias lead OpenJDK Kestrel").structured().matched());
			// A product is not in the closed class: the profiler question is a plain yes, an unknown product a miss.
			remember(e, "Sentinel is a product.", """
					{"entities": [{"ref": "e1", "name": "Sentinel", "type": "product"}]}""");
			assertEquals("miss", recall(e, "does Mattias lead Sentinel").structured().state());
			// Anna's leads are not closed by Mattias's closure.
			assertTrue(recall(e, "does Anna lead Kubernetes").structured().matched());
			// Saying it again corroborates the closure rather than duplicating it.
			RememberOutcome again = remember(e, "Those two are the only projects I lead.", """
					{"closures": [{"subject": "self", "predicate": "leads", "type": "project"}]}""");
			assertTrue(again.applied().facts().getFirst().corroborated());
		}
	}

	@Test
	@Scenario("Q13")
	void theBoundsLineIsScopedToWhatTheQuestionNames() {
		try (Engine e = engine("q13-scope")) {
			swissProperties(e);
			remember(e, "I have no shop machines, and I only own property in Switzerland.", """
					{"entities": [{"ref": "e1", "name": "Switzerland", "type": "country"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "shop machines", "negated": true},
					           {"subject": "self", "predicate": "owns", "object": "e1", "only": true}]}""");
			remember(e, "I own a Zenit 4.", """
					{"entities": [{"ref": "e1", "name": "Zenit 4", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"}]}""");
			// A question about the car: neither the machines nor the Swiss bound could cover it.
			RecallResult car = recall(e, "does Mattias own the Zenit 4");
			assertTrue(car.structured().matched(), car.text());
			assertFalse(car.text().contains("bounds:"), car.text());
			// A question about a machine: the negation, not the Swiss bound.
			RecallResult lathe = recall(e, "does Mattias own a shop lathe");
			assertTrue(lathe.text().contains("bounds: Mattias Sandell does not own shop machines"), lathe.text());
			assertFalse(lathe.text().contains("only within"), lathe.text());
			// The whole predicate: everything.
			RecallResult all = recall(e, "what does Mattias own");
			assertTrue(all.text().contains("shop machines") && all.text().contains("only within Switzerland"), all.text());
		}
	}

	@Test
	@Scenario("Q8")
	void anExclusiveRestrictionOverPlacesLeavesOtherKindsOfObjectAlone() {
		try (Engine e = engine("q8-class")) {
			swissProperties(e);
			remember(e, "I own the domain server.example.se and a Bambu Lab printer.", """
					{"entities": [{"ref": "e1", "name": "server.example.se", "type": "domain"},
					              {"ref": "e2", "name": "Bambu Lab printer", "type": "thing"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1"},
					           {"subject": "self", "predicate": "owns", "object": "e2"}]}""");
			RememberOutcome o = remember(e, "I only own properties in Switzerland.", """
					{"entities": [{"ref": "e1", "name": "Switzerland", "type": "country"}],
					 "facts": [{"subject": "self", "predicate": "owns", "object": "e1", "only": true}]}""");
			// A domain and a printer cannot be located: outside the class, so neither a conflict nor a question
			// about them. The only question is the canton chain from swissProperties.
			assertEquals(1, o.applied().questions().size(), o.applied().questions().toString());
			assertEquals("containment", firstQuestion(o).get("kind"));
			RecallResult r = recall(e, "what does Mattias own");
			assertEquals(4, r.structured().facts().size(), r.text());
			assertTrue(r.text().contains("bounds: Mattias Sandell owns only within Switzerland"), r.text());
		}
	}
}
