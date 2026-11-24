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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.recall.RecallResult.Hit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * "What are my current cars" against the author's store (2026-11-24): no relation named, so no verdict, and the cars
 * came 4th, 9th, and 10th behind every observation that called something "current"; asked again with "own", the
 * adjective in "her own account" ranked; two corrections of one car took two of the ten places and pushed another car
 * out; web addresses with no kind were offered as possible cars; and a short name the assistant resolved itself was
 * never recorded as an alias.
 */
class CurrentCarsTest {

	/** The id of the current fact that the owner owns the Yaris. */
	private static long yarisOwned(Engine e) {
		long yaris = e.entities().byRef("Toyota Yaris").orElseThrow().id();
		return e.facts().factsOf(yaris).stream().filter(f -> "owns".equals(f.predicate()) && f.current()).findFirst()
				.orElseThrow().id();
	}

	/** Three cars under two relations, and observations that say "current" and "own" about other things. */
	private static void garage(Engine e) {
		remember(e, "I bought a Polestar 4 in September.",
				proposal().entity("c", "Polestar 4", "car").fact("self", "owns", "c"));
		remember(e, "I also have a Toyota Sienna, which is the car the kids drive.",
				proposal().entity("c", "Toyota Sienna", "car").fact("self", "owns", "c"));
		RememberOutcome yaris = remember(e, "I also have a Toyota Yaris, but my father Konrad drives it.",
				proposal().entity("c", "Toyota Yaris", "car").entity("k", "Konrad Nyberg", "person")
						.fact("self", "owns", "c").fact("k", "drives", "c"));
		// "Toyota Yaris" shares a word with the Sienna and is asked about, as in the store it came from: another car.
		Map<String, Object> asked = yaris.applied().questions().getFirst();
		remember(e, "A different car.", proposal(), new Resolve(String.valueOf(asked.get("id")), "new"));
		remember(e, "My current go-to tool for 3D modelling is Fusion 360.",
				proposal().entity("t", "Fusion 360", "technology").fact("self", "uses", "t"));
		remember(e, "Martial arts and motorcycle riding are no longer current hobbies.");
		remember(e, "My current programming languages are Java and C.");
		remember(e, "My own mobile number is +41 79 000 00 00.", proposal().fact(fact("self", "has_phone",
				"+41 79 000 00 00", "own mobile number", null, null, null, null, null, null)));
		remember(e, "Clara has a cycling subscription on her own Garmin account.");
	}

	private static Set<String> objects(Engine e, List<Fact> facts) {
		return facts.stream().map(f -> e.entities().nameOf(f.objectId())).collect(Collectors.toSet());
	}

	private static boolean mentionsACar(Hit h) {
		String t = h.observation().text();
		return t.contains("Polestar") || t.contains("Sienna") || t.contains("Yaris");
	}

	@Test
	@Scenario("T26")
	void aKindAskedForWithoutARelationIsAnsweredFromEveryRelation() {
		try (Engine e = engine("t26-cars")) {
			garage(e);
			RecallResult mine = recall(e, "What are my current cars?");
			assertEquals("matched", mine.structured().state(), mine.text());
			assertEquals("car", mine.structured().kind(), mine.text());
			assertEquals(Set.of("Polestar 4", "Toyota Sienna", "Toyota Yaris"), objects(e, mine.structured().facts()),
					mine.text());
			assertTrue(mine.text().contains("matched Mattias Sandell · car (under owns) → 3 facts"), mine.text());
			// The three statements come first, before anything that merely says "current".
			assertTrue(mine.hits().size() >= 3, mine.text());
			assertTrue(mine.hits().subList(0, 3).stream().allMatch(CurrentCarsTest::mentionsACar), mine.text());
			assertTrue(mine.hits().stream().noneMatch(h -> h.observation().text().contains("hobbies")), mine.text());

			// Someone else's car, under the relation it stands in.
			RecallResult konrad = recall(e, "What are Konrad's cars?");
			assertEquals("matched", konrad.structured().state(), konrad.text());
			assertEquals("drives", konrad.structured().predicate(), konrad.text());
			assertEquals(1, konrad.structured().facts().size(), konrad.text());

			// The kind word before the verb, with no relation named, works the same way.
			RecallResult which = recall(e, "which cars do I have");
			assertEquals("car", which.structured().kind(), which.text());
			assertEquals(3, which.structured().facts().size(), which.text());

			// A kind the store never heard of leaves the question to the entity's facts, as before.
			RecallResult gliders = recall(e, "What are my current gliders?");
			assertEquals("entity", gliders.structured().state(), gliders.text());
			// With the relation named, the kind narrows it as before (F19), and the narrowed answer ranks first even
			// though the owner is its subject.
			RecallResult owned = recall(e, "what cars does Mattias own");
			assertEquals("owns", owned.structured().predicate(), owned.text());
			assertEquals("car", owned.structured().kind(), owned.text());
			assertEquals(3, owned.structured().facts().size(), owned.text());
			assertTrue(owned.text().contains("matched Mattias Sandell · car (under owns) → 3 facts"), owned.text());
			assertTrue(owned.hits().subList(0, 3).stream().allMatch(CurrentCarsTest::mentionsACar), owned.text());
		}
	}

	@Test
	@Scenario("T27")
	void aCorrectionIsShownWithTheStatementItCorrected() {
		try (Engine e = engine("t27-corrections")) {
			garage(e);
			long owns = yarisOwned(e);
			Corrected first = e.correct(owns, Map.of("qualifier", "formally; in practice Konrad's car"),
					"I never gave it to him formally.");
			e.correct(first.replacement().id(), Map.of("valid_time", Map.of("start", "2021-11-11")),
					"I have had it since 11 November 2021.");
			RecallResult r = recall(e, "What are my current cars?");
			assertTrue(r.hits().stream().noneMatch(h -> "correction".equals(h.observation().source().kind())),
					"no correction record is a hit of its own: " + r.text());
			Hit statement = r.hits().stream().filter(h -> h.observation().text().contains("Toyota Yaris")).findFirst()
					.orElseThrow(() -> new AssertionError(r.text()));
			assertTrue(
					statement.facts().stream()
							.anyMatch(f -> f.rendering().contains("in practice Konrad's car")
									&& f.rendering().contains("since 2021-11-11")),
					"the statement carries what now holds: " + r.text());
			// All three cars are still in the ten, the corrections no longer crowding one out.
			assertTrue(r.hits().stream().filter(CurrentCarsTest::mentionsACar).count() >= 3, r.text());
			// A correction that only matches by its reason folds into the statement too.
			RecallResult byReason = recall(e, "formally given");
			assertTrue(byReason.hits().stream().noneMatch(h -> "correction".equals(h.observation().source().kind())),
					byReason.text());
			// A record whose fact is gone (ids renumbered by a rebuild) folds through the facts it made current.
			e.database().write(
					tx -> tx.update("UPDATE observation SET source_ref = 'f-99999' WHERE source_kind = 'correction'"));
			RecallResult stale = recall(e, "What are my current cars?");
			assertTrue(stale.hits().stream().noneMatch(h -> "correction".equals(h.observation().source().kind())),
					"folded without the fact it named: " + stale.text());
		}
	}

	@Test
	@Scenario("T28")
	void wordsThatOnlyPlaceAQuestionInTimeOrAreAnotherWordAreNotSearched() {
		try (Engine e = engine("t28-own")) {
			garage(e);
			RecallResult r = recall(e, "what cars does Mattias own");
			assertTrue(
					r.hits().stream()
							.noneMatch(h -> h.observation().text().contains("own mobile number")
									|| h.observation().text().contains("Garmin")),
					"'my own', 'her own' are not the verb: " + r.text());
			// The question's own adjective is no relation either (the rules underneath: HomographTest).
			RecallResult number = recall(e, "what is my own phone number");
			assertEquals("has_phone", number.structured().predicate(), number.text());
			// "current" is no search term: the observations that only say it are not hits for the cars.
			RecallResult current = recall(e, "What are my current cars?");
			assertTrue(current.hits().stream().noneMatch(h -> h.observation().text().contains("programming")),
					current.text());
			// A correction record quotes the rendering, "(own mobile number)", where the index cannot see the
			// parenthesis: the text is read again, and neither the record nor the statement it folds into is a hit.
			long phone = e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(f -> "has_phone".equals(f.predicate()) && f.current()).findFirst().orElseThrow().id();
			e.correct(phone, Map.of("qualifier", "own mobile number, private"), "it is private");
			RecallResult again = recall(e, "what cars does Mattias own");
			assertTrue(again.hits().stream().noneMatch(h -> h.observation().text().contains("mobile number")),
					again.text());
		}
	}

	@Test
	@Scenario("T29")
	void aWebAddressWithNoKindIsAWebsiteAndNoCandidateForAnyOtherKind() {
		try (Engine e = engine("t29-websites")) {
			garage(e);
			RememberOutcome profile = remember(e, "My GitHub profile is https://github.com/sandell-example.",
					proposal().entity("g", "https://github.com/sandell-example", null).fact("self", "owns", "g"));
			Entity site = e.entities().byRef(profile.applied().entities().getFirst().id()).orElseThrow();
			assertEquals("website", site.type());
			RecallResult r = recall(e, "what cars does Mattias own");
			assertEquals(3, r.structured().facts().size(), r.text());
			assertFalse(r.text().contains("may be one"), "a website is not offered as a car: " + r.text());
			// A store from before: untyped web addresses become websites when the store opens.
			e.database().write(tx -> tx.update("UPDATE entity SET type = 'unknown' WHERE id = ?", site.id()));
			assertEquals(1, e.entities().typeWebAddresses());
			assertEquals("website", e.entities().get(site.id()).orElseThrow().type());
			assertEquals(0, e.entities().typeWebAddresses(), "once");
			// An older store kept such an object as words, not an entity: no candidate either.
			long owned = e.facts().factsOf(site.id()).getFirst().id();
			e.database().write(tx -> tx.update("UPDATE fact SET object_id = NULL, object_text = ? WHERE id = ?",
					"https://soundcloud.com/sandell-example", owned));
			RecallResult words = recall(e, "what cars does Mattias own");
			assertEquals(3, words.structured().facts().size(), words.text());
			assertFalse(words.text().contains("may be one"), words.text());
		}
	}

	@Test
	@Scenario("T30")
	void aNameConfirmedForAnEntityIsItsAliasAndNeverAMerge() {
		try (Engine e = engine("t30-aliases")) {
			// A name the assistant resolved itself is passed as an alias of the entity on record.
			RememberOutcome mother = remember(e, "My mother is Clara Nyberg.",
					proposal().entity("m", "Clara Nyberg", "person")
							.fact(fact("m", "parent_of", "self", "mother", null, null, null, null, null, null)));
			String clara = mother.applied().entities().getFirst().id();
			remember(e, "My mother (Clara) drives the Yaris.", proposal().entity("m", "Clara Nyberg", "person", "Clara")
					.entity("c", "Toyota Yaris", "car").fact("m", "drives", "c"));
			long claraId = e.entities().byRef(clara).orElseThrow().id();
			assertTrue(e.entities().aliases(claraId).contains("Clara"), e.entities().aliases(claraId).toString());
			RememberOutcome bare = remember(e, "Clara called.",
					proposal().entity("c2", "Clara", "person").fact("c2", "knows", "self"));
			assertTrue(bare.applied().questions().isEmpty(), bare.applied().questions().toString());
			assertEquals(clara, bare.applied().entities().getFirst().id());

			// Added later, without listing every name kept.
			e.correctEntity(claraId, Map.of("add_aliases", List.of("Mamma")), "what the children call her");
			assertTrue(e.entities().aliases(claraId).containsAll(List.of("Clara Nyberg", "Clara", "Mamma")),
					e.entities().aliases(claraId).toString());
		}
	}

	@Test
	@Scenario("T31")
	void twoPeopleMayShareANameAndNothingIsMergedUnasked() {
		try (Engine e = engine("t31-shared")) {
			RememberOutcome mother = remember(e, "My mother is Clara Nyberg; everyone calls her Clara.",
					proposal().entity("m", "Clara Nyberg", "person", "Clara")
							.fact(fact("m", "parent_of", "self", "mother", null, null, null, null, null, null)));
			long claraNyberg = e.entities().byRef(mother.applied().entities().getFirst().id()).orElseThrow().id();
			// A colleague who is also called Clara: the alias is shared, never taken as proof, and it is asked
			// whether the two are one. Nothing is merged before the answer.
			RememberOutcome colleague = remember(e, "Clara Lind, Clara at work, is my colleague at Hooli.",
					proposal().entity("c", "Clara Lind", "person", "Clara").fact("self", "knows", "c"));
			Map<String, Object> asked = colleague.applied().questions().getFirst();
			assertEquals("entity_resolution", asked.get("kind"), "which one is meant, the new name: " + asked);
			RememberOutcome created = remember(e, "A different Clara.", proposal(),
					new Resolve(String.valueOf(asked.get("id")), "new"));
			long claraLind = e.entities().byRef(String.valueOf(created.resolved().getFirst().get("entity")))
					.orElseThrow().id();
			assertFalse(claraLind == claraNyberg);
			assertTrue(e.entities().aliases(claraLind).contains("Clara"), e.entities().aliases(claraLind).toString());
			assertTrue(e.entities().aliases(claraNyberg).contains("Clara"));
			assertEquals(0, e.entities().merges().size());

			// A bare "Clara" is asked at every mention, both of them candidates.
			for (String text : List.of("Clara called.", "Clara called again.")) {
				RememberOutcome bare = remember(e, text,
						proposal().entity("x", "Clara", "person").fact("x", "knows", "self"));
				Map<String, Object> q = bare.applied().questions().getFirst();
				assertEquals("entity_resolution", q.get("kind"), q.toString());
				assertTrue(q.toString().contains("ent-" + claraNyberg) && q.toString().contains("ent-" + claraLind),
						q.toString());
				e.answer(List.of(new Resolve(String.valueOf(q.get("id")), "ent-" + claraNyberg)));
			}

			// Consolidate asks once whether things sharing a name are one, and merges nothing; "no" is not asked again.
			var c = e.consolidate(false);
			assertEquals(1, c.sameEntity().size(), c.sameEntity().toString());
			assertEquals("Clara", c.sameEntity().getFirst().get("shared"));
			String sameQ = String.valueOf(c.sameEntity().getFirst().get("asked"));
			assertEquals(0, e.entities().merges().size(), "consolidate merges nothing unasked");
			e.answer(List.of(new Resolve(sameQ, "no")));
			assertTrue(e.consolidate(false).sameEntity().isEmpty(), "a no is not asked again");
			assertFalse(e.entities().get(claraLind).orElseThrow().id() == claraNyberg);

			// The answered pair stays answered: the same alias given again asks nothing and merges nothing.
			RememberOutcome again = remember(e, "Clara Nyberg, not Clara Lind.",
					proposal().entity("m", "Clara Nyberg", "person", "Clara Lind"));
			assertTrue(again.applied().questions().isEmpty(), again.applied().questions().toString());
			// A merge only by an answer, or by correct(merge_into): an alias another thing carries asks.
			remember(e, "Freja helps at home.", proposal().entity("f", "Freja", "person").fact("f", "knows", "self"));
			RememberOutcome third = remember(e, "My mother Clara Nyberg is Freja to her grandchildren.",
					proposal().entity("m", "Clara Nyberg", "person", "Freja"));
			Map<String, Object> same = third.applied().questions().getFirst();
			assertEquals("same_entity", same.get("kind"), same.toString());
			assertEquals(0, e.entities().merges().size(), "asked, not merged");
			e.answer(List.of(new Resolve(String.valueOf(same.get("id")), "yes")));
			assertEquals(1, e.entities().merges().size(), "merged on the answer");
		}
	}

	@Test
	@Scenario("T32")
	void aRoleWordInThePluralCuesItsRelation() {
		try (Engine e = engine("t32-plural")) {
			remember(e, "Elias and Oskar Nyberg are my brothers; Freja is my sister.",
					proposal().entity("a", "Elias Nyberg", "person").entity("b", "Oskar Nyberg", "person")
							.entity("c", "Freja Nyberg", "person")
							.fact(fact("a", "sibling_of", "self", "brother", null, null, null, null, null, null))
							.fact(fact("b", "sibling_of", "self", "brother", null, null, null, null, null, null))
							.fact(fact("c", "sibling_of", "self", "sister", null, null, null, null, null, null)));
			RecallResult brothers = recall(e, "who are my brothers");
			assertEquals("sibling_of", brothers.structured().predicate(), brothers.text());
			assertEquals("brother", brothers.structured().qualifier(), brothers.text());
			assertEquals(2, brothers.structured().facts().size(), brothers.text());
			assertEquals(1, recall(e, "who are Mattias's sisters").structured().facts().size());
			assertEquals(2, recall(e, "who is my brother").structured().facts().size(), "the singular as before");
		}
	}

	@Test
	@Scenario("T30")
	void answeringWhichOneAShortNameMeansAddsTheAliasWithoutMerging() {
		try (Engine e = engine("t30-answer")) {
			remember(e, "Peter is my colleague; he lives in Zug.", proposal().entity("p", "Peter", "person")
					.entity("z", "Zug", "place").fact("self", "knows", "p").fact("p", "lives_in", "z"));
			RememberOutcome full = remember(e, "Peter Andersson joined Hooli in 2020.",
					proposal().entity("pa", "Peter Andersson", "person").entity("h", "Hooli", "organization").fact("pa",
							"works_at", "h"));
			Map<String, Object> q = full.applied().questions().getFirst();
			String firstPeter = String.valueOf(((List<?>) q.get("candidates")).stream()
					.map(c -> ((Map<?, ?>) c).get("id")).findFirst().orElseThrow());
			RememberOutcome other = remember(e, "He is new here.", proposal(),
					new Resolve(String.valueOf(q.get("id")), "new"));
			String secondPeter = String.valueOf(other.resolved().getFirst().get("entity"));

			// A bare "Peter" fits either; answered with the one who is not called that yet.
			RememberOutcome bare = remember(e, "Peter called about Hooli.",
					proposal().entity("p2", "Peter", "person").fact("p2", "knows", "self"));
			Map<String, Object> again = bare.applied().questions().getFirst();
			RememberOutcome answered = remember(e, "The Hooli one.", proposal(),
					new Resolve(String.valueOf(again.get("id")), secondPeter));
			Map<String, Object> answer = answered.resolved().getFirst();
			assertEquals("Peter", answer.get("alias_added"), answer.toString());
			// Both people are still there: the alias was added, not resolved into a merge of the Peter on record.
			Entity zug = e.entities().byRef(firstPeter).orElseThrow();
			assertEquals(null, zug.mergedInto(), "the first Peter was not merged away");
			assertEquals("Peter", zug.name());
			Entity hooli = e.entities().byRef(secondPeter).orElseThrow();
			assertFalse(zug.id() == hooli.id());
			assertTrue(e.entities().aliases(hooli.id()).contains("Peter"), e.entities().aliases(hooli.id()).toString());
			String home = recall(e, "where does Peter live").text();
			assertTrue(home.contains("Peter lives in Zug"), home);
		}
	}
}
