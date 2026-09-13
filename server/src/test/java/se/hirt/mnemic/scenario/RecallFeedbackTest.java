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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/** Six recall defects from the author's second day of real use (2026-09-10), each pinned here. */
class RecallFeedbackTest {

	private static void family(Engine e) {
		remember(e, "My father is Konrad Nyberg.", proposal().entity("e1", "Konrad Nyberg", "person")
				.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
		var p = proposal();
		String[] kids = {"Marit Nyberg", "Oskar Nyberg", "Nora Nyberg"};
		for (int i = 0; i < kids.length; i++) {
			p.entity("c" + i, kids[i], "person")
					.fact(fact("self", "parent_of", "c" + i, "father", null, null, null, null, null, null));
		}
		remember(e, "My children are Marit, Oskar and Nora.", p);
	}

	@Test
	@Scenario("F6")
	void relationDirectionIsConstrained() {
		try (Engine e = engine("f6-direction")) {
			family(e);
			RecallResult father = recall(e, "who is Mattias's father");
			assertTrue(father.structured().matched(), father.text());
			assertEquals(1, father.structured().facts().size(), "only the fact where Mattias is the child: " + father.text());
			assertEquals(e.entities().owner().id(), father.structured().facts().getFirst().objectId());
			assertTrue(father.text().contains("parent_of[father] → 1 fact"), "the verdict names one fact: " + father.text());
			assertTrue(father.text().contains("Konrad Nyberg is Mattias Sandell's father"), father.text());
			// The rendered body agrees with the verdict: no wrong-direction fact anchors any hit.
			assertFalse(father.text().contains("Mattias Sandell is Marit Nyberg's father"), father.text());
			assertTrue(father.hits().stream().flatMap(h -> h.facts().stream())
					.allMatch(f -> !"parent_of".equals(f.predicate()) || f.objectId() == e.entities().owner().id()),
					"every anchoring parent_of fact has Mattias as the child: " + father.text());
		}
	}

	@Test
	@Scenario("F6")
	void inverseTermsReachTheOtherEndOfARelation() {
		try (Engine e = engine("f6-inverse")) {
			family(e);
			RecallResult kids = recall(e, "who are Mattias's children");
			assertTrue(kids.structured().matched(), kids.text());
			assertEquals("parent_of", kids.structured().predicate());
			assertEquals(3, kids.structured().facts().size(), kids.text());
			long owner = e.entities().owner().id();
			assertTrue(kids.structured().facts().stream().allMatch(f -> f.subjectId() == owner), "Mattias is the parent");
			// The facts the verdict counted are rendered under their observation, not only counted.
			assertTrue(kids.hits().stream().anyMatch(h -> h.facts().size() == 3), "the hit carries the three facts: " + kids.text());
			assertTrue(kids.text().contains("Mattias Sandell is Marit Nyberg's father"), kids.text());
			assertTrue(kids.hits().getFirst().channels().contains("structured"), "ranked by the structured channel: " + kids.text());
			RecallResult parents = recall(e, "who are Mattias's parents");
			assertEquals(1, parents.structured().facts().size(), parents.text());
		}
	}

	@Test
	@Scenario("F6")
	void otherPeoplesRelationsDoNotAnchorAQuestionAboutOne() {
		try (Engine e = engine("f6-siblings")) {
			var p = proposal().entity("m", "Marit Nyberg", "person");
			String[] kids = {"Oskar Nyberg", "Nora Nyberg", "Elias Nyberg", "Freja Nyberg"};
			for (int i = 0; i < kids.length; i++) {
				p.entity("c" + i, kids[i], "person")
						.fact(fact("m", "parent_of", "c" + i, "mother", null, null, null, null, null, null));
			}
			remember(e, "Marit is the mother of Oskar, Nora, Elias and Freja.", p);
			RecallResult r = recall(e, "who is Oskar Nyberg's mother");
			assertTrue(r.text().contains("parent_of[mother] → 1 fact"), r.text());
			assertTrue(r.text().contains("Marit Nyberg is Oskar Nyberg's mother"), r.text());
			assertFalse(r.text().contains("is Nora Nyberg's mother"), "the siblings' facts are not anchors: " + r.text());
			assertEquals(1, r.hits().stream().mapToInt(h -> h.facts().size()).sum(), r.text());
		}
	}

	@Test
	@Scenario("F9")
	void anEventTypeAnswersAWhenQuestion() {
		try (Engine e = engine("f9-purchase")) {
			remember(e, "We bought the house at Bergstrasse 7 in November 2025.", proposal()
					.entity("e1", "Bergstrasse 7, Haus B", "place").event("ev1", "purchased", "2025-11", "self", "e1")
					.fact(fact("self", "owns", "e1", null, null, "2025-11", null, null, java.util.List.of("ev1"), null)));
			var sb = new StringBuilder("I decided not to buy the InfiMaker K1 5-axis CNC. ");
			for (int i = 0; i < 20; i++) {
				sb.append("The machine was discussed at length. ");
			}
			remember(e, sb.toString(), proposal().fact("decided", "not to buy the InfiMaker K1 5-axis CNC"));
			RecallResult r = recall(e, "when did Mattias buy his house");
			assertEquals("events", r.structured().state(), r.text());
			assertFalse(r.events().isEmpty(), r.text());
			assertEquals("purchased", r.events().getFirst().type());
			assertTrue(r.text().contains("2025-11"), r.text());
			assertTrue(r.hits().getFirst().observation().text().contains("Bergstrasse"),
					"the purchase observation outranks the decision not to buy: " + r.text());
			// A type nobody registered still answers through its own name.
			remember(e, "I inherited the cabin in 2019.", proposal().entity("e1", "the cabin", "place")
					.event("ev1", "inherited", "2019", "self", "e1").fact("self", "owns", "e1"));
			RecallResult inh = recall(e, "what did Mattias inherit");
			assertEquals("events", inh.structured().state(), inh.text());
			assertEquals("inherited", inh.events().getFirst().type());
		}
		try (Engine e = engine("f9-two-word-type")) {
			// The author's store typed the event "purchased property": two words, matched word by word, and "buy"
			// reaches it through the registered "purchased" type's lexicon (2026-09-10).
			remember(e, "We bought the house at Bergstrasse 7 in November 2025.", proposal()
					.entity("e1", "Bergstrasse 7, Haus B", "place").event("ev1", "purchased property", "2025-11", "self", "e1")
					.fact(fact("self", "owns", "e1", null, null, "2025-11", null, null, java.util.List.of("ev1"), null)));
			remember(e, "I decided not to buy the InfiMaker K1 5-axis CNC. It was discussed at length.",
					proposal().fact("decided", "not to buy the InfiMaker K1 5-axis CNC"));
			RecallResult r = recall(e, "when did Mattias buy his house");
			assertEquals("events", r.structured().state(), r.text());
			assertEquals("purchased property", r.events().getFirst().type());
			assertTrue(r.hits().getFirst().observation().text().contains("Bergstrasse"), r.text());
		}
	}

	@Test
	@Scenario("F5")
	void aListQuestionRendersTheListItCounted() {
		try (Engine e = engine("f5-owns")) {
			remember(e, "We bought the house at Bergstrasse 7 in Schübelbach in November 2025.", proposal()
					.entity("e1", "Bergstrasse 7, Haus B", "place").entity("e2", "Schübelbach", "place")
					.fact(fact("self", "owns", "e1", null, null, "2025-11", null, null, null, null))
					.fact("e1", "located_in", "e2"));
			remember(e, "Schübelbach is in Kanton Schwyz.", proposal().entity("e1", "Schübelbach", "place")
					.entity("e2", "Kanton Schwyz", "place").fact("e1", "located_in", "e2"));
			remember(e, "I own a Toyota Sienna and a Segway Navimow.", proposal().entity("e1", "Toyota Sienna", "thing")
					.entity("e2", "Segway Navimow", "thing").fact("self", "owns", "e1").fact("self", "owns", "e2"));
			remember(e, "I prefer working early in the morning with coffee.", proposal()
					.fact("prefers", "working early in the morning"));
			RecallResult r = recall(e, "what does Mattias own");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(3, r.structured().facts().size(), r.text());
			long rendered = r.hits().stream().flatMap(h -> h.facts().stream()).filter(f -> "owns".equals(f.predicate())).count();
			assertEquals(3, rendered, "every owns fact the verdict counted is rendered: " + r.text());
			assertTrue(r.text().contains("Mattias Sandell owns Toyota Sienna"), r.text());
			assertTrue(r.hits().getFirst().channels().contains("structured"), r.text());
			assertFalse(r.hits().stream().anyMatch(h -> h.observation().text().contains("coffee")),
					"a preference is not an answer to what he owns: " + r.text());
			// The rule that kept "uses" lists out of the ranking still holds when the question has more in it.
			remember(e, "I use NumPy and Pandas.", proposal().entity("e1", "NumPy", "technology")
					.entity("e2", "Pandas", "technology").fact("self", "uses", "e1").fact("self", "uses", "e2"));
			RecallResult noisy = recall(e, "what two-factor authentication methods did you mention that companies use");
			assertFalse(noisy.hits().stream().flatMap(h -> h.facts().stream()).anyMatch(f -> "uses".equals(f.predicate())),
					"an incidental cue does not rank the owner's list: " + noisy.text());
		}
	}

	@Test
	@Scenario("F11")
	void aYesNoQuestionIsNeverHeadlinedAsAnswered() {
		try (Engine e = engine("f11-polar")) {
			remember(e, "I bought the Lindenhof apartment in Willisau in 2024.",
					proposal().entity("e1", "Lindenhof apartment", "place").fact("owns", "e1")
							.event("ev1", "purchased", "2024", "self", "e1"));
			remember(e, "I relocated from Sweden to Switzerland in 2014. I own nothing in Sweden.",
					proposal().entity("e2", "Sweden", "place").entity("e3", "Switzerland", "place")
							.event("ev2", "relocated", "2014", "self", "e2", "e3"));
			RecallResult r = recall(e, "does Mattias own any property in Sweden");
			assertFalse(r.text().contains("are the answer"), r.text());
			assertFalse(r.text().contains("is the answer"), r.text());
			assertTrue(r.text().contains("yes/no"), r.text());
			assertTrue(r.text().contains("not evidence of no"), r.text());
			// The same shape without the polar opener keeps its verdict.
			RecallResult plain = recall(e, "what does Mattias own");
			assertTrue(plain.structured().matched(), plain.text());
			assertFalse(plain.text().contains("yes/no"), plain.text());
		}
	}

	@Test
	@Scenario("F15")
	void aFactRestatedLaterIsFoundThroughTheLaterConversationToo() {
		try (Engine e = engine("f15-restated")) {
			// The first statement sits deep in a long conversation, so the fact's span is far past the length of the
			// short restatement below (2026-09-11: the excerpt once cut the restatement with the original's offsets).
			String longTalk = "We talked about the weather and the garden for a long while. ".repeat(60) + "I work at Hooli.";
			long first = remember(e, longTalk, proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"))
					.observation().observationId();
			// A later conversation restates it in other words, with the detail the question will ask about.
			long later = remember(e, "Still at Hooli, on the profiler team these days.",
					proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1")).observation().observationId();
			RecallResult r = recall(e, "where does Mattias work");
			assertTrue(r.structured().matched(), r.text());
			List<Long> shown = r.hits().stream().map(h -> h.observation().id()).toList();
			assertTrue(shown.contains(first) && shown.contains(later), "both conversations reachable: " + r.text());
			// The later one is anchored by the fact, not found by chance.
			RecallResult.Hit laterHit = r.hits().stream().filter(h -> h.observation().id() == later).findFirst().orElseThrow();
			assertTrue(laterHit.channels().contains("structured") || laterHit.channels().contains("facts"),
					"the fact reaches its later restatement: " + laterHit.channels());
			assertFalse(laterHit.facts().isEmpty(), "the fact is shown under it too");
			assertTrue(laterHit.shown().contains("profiler team"), "the restatement is excerpted from its own text: " + laterHit.shown());
		}
	}

	@Test
	@Scenario("F14")
	void aQuestionAboutANamedThingNoFactMentionsIsAMiss() {
		try (Engine e = engine("f14-named")) {
			remember(e, "I live in an apartment in Harajuku.", proposal().entity("e1", "Harajuku", "place").fact("lives_in", "e1"));
			remember(e, "I started as a Senior Software Engineer at Acme.", proposal().entity("e1", "Acme", "organization")
					.fact(fact("self", "holds_role", "Senior Software Engineer", null, "e1", null, null, null, null, null)));
			// The fact is about Harajuku; the question is about Shinjuku: not the answer.
			RecallResult r = recall(e, "How long have I been living in my current apartment in Shinjuku?");
			assertEquals("miss", r.structured().state(), r.text());
			assertEquals(1, r.structured().nearMisses().size(), r.text());
			assertTrue(r.text().contains("names shinjuku"), r.text());
			assertTrue(r.text().contains("lives in Harajuku"), "the near-miss is shown: " + r.text());
			// The same question about the place on record is a plain match.
			assertTrue(recall(e, "How long have I been living in my apartment in Harajuku?").structured().matched());
			// No named thing: the lower-case question matches as before.
			assertTrue(recall(e, "where do I live").structured().matched());
			// A role the user does not hold, by name.
			RecallResult role = recall(e, "How many engineers do I lead as Software Engineer Manager?");
			assertFalse(role.structured().matched(), role.text());
			// A brand with the capital inside the word is a named thing too (iPad, macOS).
			remember(e, "I bought headphones last week.", proposal().entity("e1", "headphones", "thing").fact("owns", "e1"));
			RecallResult ipad = recall(e, "How many days did it take for my iPad case to arrive after I bought it?");
			assertFalse(ipad.structured().matched(), ipad.text());
			assertTrue(ipad.text().contains("names ipad"), ipad.text());
		}
	}

	@Test
	@Scenario("F12")
	void anOpenFactSaysWhenItWasLastConfirmed() {
		Instant observed = Instant.parse("2026-08-27T10:00:00Z");
		try (Engine e = engine("f12-age", Instant.parse("2026-09-10T10:00:00Z"))) {
			remember(e, "As of late August the Zenit was not registered yet.", observed,
					proposal().entity("e1", "Zenit", "thing").fact("owns", "e1"));
			remember(e, "I was born in Uppsala.", observed,
					proposal().entity("e2", "Uppsala", "place").fact("born_in", "e2"));
			RecallResult r = recall(e, "what does Mattias own");
			assertTrue(r.text().contains("confirmed 2026-08-27 (14d ago)"), r.text());
			assertFalse(r.text().contains("likely changed"), r.text());
			assertTrue(r.text().contains("observed 2026-08-27 (14d ago)"), r.text());
			// A timeless predicate does not age.
			RecallResult born = recall(e, "where was Mattias born");
			assertTrue(born.structured().matched(), born.text());
			assertFalse(born.text().contains("confirmed 2026"), born.text());
		}
		try (Engine e = engine("f12-stale", Instant.parse("2027-06-01T10:00:00Z"))) {
			remember(e, "I work at Hooli.", observed,
					proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			RecallResult r = recall(e, "where does Mattias work");
			assertTrue(r.text().contains("likely changed"), r.text());
		}
	}

	@Test
	@Scenario("F13")
	void consolidateListsTheFactsLongestWithoutConfirmation() {
		// Half a year after the conversations: fresh facts are not up for review (2026-09-11), old ones are.
		Instant said = Instant.parse("2026-08-27T10:00:00Z");
		try (Engine e = engine("f13-review", Instant.parse("2026-12-05T10:00:00Z"))) {
			remember(e, "I work at Hooli.", said, proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			remember(e, "I was born in Uppsala.", said, proposal().entity("e2", "Uppsala", "place").fact("born_in", "e2"));
			remember(e, "I use Neovim these days.", Instant.parse("2026-12-01T10:00:00Z"),
					proposal().entity("e3", "Neovim", "technology").fact("uses", "e3"));
			var c = e.consolidate(true);
			assertEquals(1, c.review().size(), c.review().toString());
			assertEquals("Mattias Sandell works at Hooli", c.review().getFirst().get("rendering"));
			assertEquals(false, c.review().getFirst().get("likely_changed"));
		}
	}

	@Test
	@Scenario("F6")
	void aSymmetricRelationIsFoundFromBothSides() {
		try (Engine e = engine("f6-symmetric")) {
			// Stored once, from Mattias's side: he is Clara's half-brother. Nothing says Clara's gender.
			remember(e, "Clara is my half-sister.", proposal().entity("e1", "Clara", "person")
					.fact(fact("self", "sibling_of", "e1", "half-brother", null, null, null, null, null, null)));
			RecallResult fromHers = recall(e, "who is Clara's half-brother");
			assertTrue(fromHers.structured().matched(), fromHers.text());
			RecallResult fromHis = recall(e, "who is Mattias's half-sister");
			assertTrue(fromHis.structured().matched(), "the other side of the same relation: " + fromHis.text());
			assertEquals(1, fromHis.structured().facts().size());
			assertTrue(fromHis.text().contains("Mattias Sandell is Clara's half-brother"), fromHis.text());
			// A different degree is still a near-miss, not a match.
			RecallResult brother = recall(e, "who is Mattias's brother");
			assertEquals("miss", brother.structured().state(), brother.text());
			assertEquals(1, brother.structured().nearMisses().size(), brother.text());
		}
	}

	@Test
	@Scenario("F3")
	void containmentIsFollowedFromAMatchedPlace() {
		try (Engine e = engine("f3-chain")) {
			remember(e, "I live in Schübelbach.", proposal().entity("e1", "Schübelbach", "place")
					.fact("self", "lives_in", "e1"));
			remember(e, "Schübelbach is in Kanton Schwyz.", proposal().entity("e1", "Schübelbach", "place")
					.entity("e2", "Kanton Schwyz", "place").fact("e1", "located_in", "e2"));
			remember(e, "Kanton Schwyz is in Switzerland.", proposal().entity("e1", "Kanton Schwyz", "place")
					.entity("e2", "Switzerland", "place").fact("e1", "located_in", "e2"));
			RecallResult r = recall(e, "where does Mattias live");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(2, r.structured().chain().size(), "two hops: canton, country: " + r.text());
			assertTrue(r.text().contains("via: Schübelbach is located in Kanton Schwyz"), r.text());
			assertTrue(r.text().contains("Kanton Schwyz is located in Switzerland"), r.text());
			// The canton's observation ranks with the structured channel, so it is a hit, not a lexical stray.
			assertTrue(r.hits().stream().anyMatch(h -> h.observation().text().contains("Kanton Schwyz")), r.text());
		}
	}

	@Test
	@Scenario("C4")
	void anOpenStartFactIsNotKnownBeforeItWasObserved() {
		try (Engine e = engine("c4-known-by")) {
			Instant t2021 = Instant.parse("2021-06-01T00:00:00Z");
			remember(e, "Konrad lives in the Lindenhof apartment.", t2021, proposal()
					.entity("e1", "Konrad Nyberg", "person").entity("e2", "Lindenhof apartment", "place")
					.fact("e1", "lives_in", "e2"));
			remember(e, "I was born in Uppsala.", t2021, proposal().entity("e1", "Uppsala", "place")
					.fact("self", "born_in", "e1"));
			Instant t2010 = Instant.parse("2010-06-01T00:00:00Z");
			RecallResult where = recall(e, "where does Konrad Nyberg live", t2010);
			assertEquals("miss", where.structured().state(), "not known to hold in 2010: " + where.text());
			assertFalse(where.text().contains("Lindenhof"), where.text());
			RecallResult born = recall(e, "where was Mattias born", t2010);
			assertTrue(born.structured().matched(), "timeless facts hold at any date: " + born.text());
			// Stated valid time still decides regardless of when it was observed (the Memento rule).
			remember(e, "I worked at Initrode from 2008 to 2012.", Instant.parse("2026-01-01T00:00:00Z"), proposal()
					.entity("e1", "Initrode", "organization")
					.fact(fact("self", "works_at", "e1", null, null, "2008", "2012", null, null, null)));
			RecallResult work = recall(e, "where did Mattias work", t2010);
			assertTrue(work.structured().matched(), work.text());
		}
	}

	@Test
	@Scenario("F10")
	void briefingLeadsWithWhatPlacesAPerson() {
		try (Engine e = engine("f10-order")) {
			for (int i = 0; i < 12; i++) {
				remember(e, "I like game " + i + ".", proposal().entity("e1", "Game " + i, "thing").fact("self", "prefers", "e1"));
			}
			remember(e, "I work at Hooli and live in Zürich.", proposal().entity("e1", "Hooli", "organization")
					.entity("e2", "Zürich", "place").fact("self", "works_at", "e1").fact("self", "lives_in", "e2"));
			remember(e, "My father is Konrad Nyberg.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			String b = e.briefing(220);
			assertTrue(b.contains("works at Hooli"), b);
			assertTrue(b.contains("lives in Zürich"), b);
			assertTrue(b.indexOf("works at Hooli") < b.indexOf("Game"), "job before games: " + b);
			assertTrue(b.indexOf("father") < b.indexOf("Game"), "family before games: " + b);
		}
	}

	@Test
	@Scenario("F4")
	void aFactStillFitsWhenItsObservationDoesNot() {
		try (Engine e = engine("f4-degrade")) {
			var sb = new StringBuilder("After a long evaluation I decided not to buy the InfiMaker K1 5-axis CNC. ");
			for (int i = 0; i < 60; i++) {
				sb.append("The machining envelope, the spindle, the controller and the dust extraction were all discussed at length. ");
			}
			remember(e, sb.toString(), proposal().fact("decided", "not to buy the InfiMaker K1 5-axis CNC"));
			RecallResult r = e.recall().recall("what did Mattias decide about the CNC machine", null, 200, 10);
			assertTrue(r.structured().matched(), r.text());
			assertEquals(1, r.hits().size(), "the fact fits even though the prose does not: " + r.text());
			assertTrue(r.hits().getFirst().shown().isEmpty());
			assertTrue(r.text().contains("InfiMaker K1"), r.text());
			assertTrue(r.text().contains("omitted for budget"), r.text());
			assertFalse(r.text().contains("no matching observations"), r.text());
			RecallResult full = e.recall().recall("what did Mattias decide about the CNC machine", null, 4000, 10);
			assertFalse(full.hits().getFirst().shown().isEmpty(), "with budget the prose is there");
		}
	}

	@Test
	@Scenario("F5")
	void theEntityVerdictDoesNotReadAsAnAnswer() {
		try (Engine e = engine("f5-wording")) {
			remember(e, "I like tea.", proposal().fact("prefers", "tea"));
			RecallResult r = recall(e, "tell me about Mattias");
			assertEquals("entity", r.structured().state(), r.text());
			assertTrue(r.text().contains("not used for ranking"), r.text());
			assertFalse(r.text().contains("→ 1 fact"), r.text());
			Entity owner = e.entities().owner();
			assertEquals(1, e.facts().factsOf(owner.id()).stream().filter(Fact::current).count());
		}
	}

	/** F17: recall says which channels had their say, and which ranked the hits when the structured one was silent. */
	@Test
	@Scenario("F17")
	void recallSaysWhatTheAnswerIsBasedOn() {
		try (Engine e = engine("f17-channels")) {
			e.remember("The 3D printer needs a new nozzle, the old one is clogged.", se.hirt.mnemic.observation.Source.user(), null, null, null, null);
			RecallResult r = recall(e, "what is wrong with the printer");
			// No embedder in this engine: the line says so, instead of the block reading as exhaustive.
			assertTrue(r.text().contains("channels: structured, keys, lexical; semantic off ("), r.text());
			// The structured channel had nothing to say; the line names what ranked the hit.
			assertTrue(r.text().contains("the hits below were ranked by lexical"), r.text());
		}
	}

	/** F18: a question about what is coming ranks the observation behind a future-dated fact first. */
	@Test
	@Scenario("F18")
	void aQuestionAboutWhatIsComingRanksTheUpcomingFactFirst() {
		try (Engine e = engine("f18-upcoming")) {
			long robot = e.remember("In 2015 I designed and built Brewbot, an autonomous robotic vehicle for the office.",
					se.hirt.mnemic.observation.Source.user(), null, null, null, null).observation().observationId();
			String start = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(6).toString();
			long car = remember(e, "The Zenit 4 is ready at the dealer; pickup is next week.",
					proposal().entity("e1", "Zenit 4", "thing").fact(fact("self", "owns", "e1", null, null, start, null, null, null, null)))
					.observation().observationId();
			// "vehicle" is the robot's word; "about to" is the cue that what is coming matters more.
			RecallResult r = recall(e, "what vehicle am I about to collect");
			assertEquals(car, r.hits().getFirst().observation().id(), r.text());
			assertTrue(r.hits().getFirst().channels().contains("upcoming"), r.hits().getFirst().channels().toString());
			// The future fact anchors the hit and is shown under it, with its start date, so it still reads as not yet so.
			assertTrue(r.hits().getFirst().facts().stream().anyMatch(f -> f.rendering().contains("Zenit 4")), r.text());
			// Without a forward cue the word decides, as before.
			RecallResult plain = recall(e, "which vehicle did I build");
			assertEquals(robot, plain.hits().getFirst().observation().id(), plain.text());
		}
	}
}
