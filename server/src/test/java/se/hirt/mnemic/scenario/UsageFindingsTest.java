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
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * What the usage bench's new scenarios turned up on 2026-09-23 (a pasted mortgage email, a code base, a preference with
 * a qualifier): questions the store need not ask, an events verdict that claimed too much, a miss that hid the
 * relations on record, and a qualifier that was stored but never shown.
 */
class UsageFindingsTest {

	private static List<Map<String, Object>> ofKind(RememberOutcome o, String kind) {
		return o.applied().questions().stream().filter(q -> kind.equals(q.get("kind"))).toList();
	}

	@Test
	void aNewEventTypeBetweenThingsNoOneValuedRelationJoinsIsNotAskedAbout() {
		try (Engine e = engine("usage-effect-quiet")) {
			// A renewal between a loan, its borrower, and a bank: nothing one-valued to open or close. Stored as an
			// occurrence, its type registered, no question; the reply says how to give the type effects later.
			RememberOutcome o = remember(e, "The mortgage on Bergstrasse 7 renews on 1 March 2027.",
					proposal().entity("e1", "Mortgage on Bergstrasse 7", "thing")
							.entity("e2", "Nordbank Hypotheken", "organization")
							.event("ev1", "mortgage renewal", "2027-03-01", "e1", "self", "e2"));
			assertEquals(1, o.applied().events().size());
			assertEquals(List.of(), ofKind(o, "event_effect"), o.applied().questions().toString());
			assertEquals(1, o.applied().definitions().size(), "the type still registers from use");
			// A fix by a person on a thing: the same, nothing one-valued between them.
			RememberOutcome fix = remember(e, "Milo fixed the flaky test on 10 September 2026.",
					proposal().entity("e1", "Milo", "person").entity("e2", "the flaky test", "thing").event("ev1",
							"fixed", "2026-09-10", "e1", "e2"));
			assertEquals(List.of(), ofKind(fix, "event_effect"), fix.applied().questions().toString());
			// A participant whose kind is still an open question fits every relation: no effect question beside the
			// kind question (a mortgage renewal on 2026-09-23).
			RememberOutcome unplaced = remember(e, "The escrow on the deal closes in March.", proposal()
					.entity("e1", "the escrow", "escrow").event("ev1", "escrow closing", "2027-03", "self", "e1"));
			assertEquals(1, ofKind(unplaced, "type_kind").size(), unplaced.applied().questions().toString());
			assertEquals(List.of(), ofKind(unplaced, "event_effect"), unplaced.applied().questions().toString());
			// Two people at a dinner: kinship is one-valued, but its changes are seeded events, not this one.
			RememberOutcome dinner = remember(e, "Anna and I had the rehearsal dinner on 11 June 2027.",
					proposal().entity("e1", "Anna Lindqvist", "person").event("ev1", "rehearsal dinner", "2027-06-11",
							"self", "e1"));
			assertEquals(List.of(), ofKind(dinner, "event_effect"), dinner.applied().questions().toString());
			// A release with the release alone: not a person, so nothing to end either.
			RememberOutcome rel = remember(e, "Kestrel 3.0 is released in December 2026.",
					proposal().entity("e1", "Kestrel 3.0", "thing").event("ev1", "released", "2026-12", "e1"));
			assertEquals(List.of(), ofKind(rel, "event_effect"), rel.applied().questions().toString());
		}
	}

	@Test
	void aNewEventTypeThatCouldReplaceOrEndSomethingIsStillAsked() {
		try (Engine e = engine("usage-effect-asked")) {
			// A person and a place: lives_in is one-valued, and the event may be the move that replaces it.
			RememberOutcome move = remember(e, "I relocated to Willisau in 2024.",
					proposal().entity("e1", "Willisau", "place").event("ev1", "relocated", "2024", "self", "e1"));
			assertEquals(1, ofKind(move, "event_effect").size(), move.applied().questions().toString());
			// The candidates are the relations the event could really open or close: nothing derived, and nothing
			// the participants' kinds rule out.
			String candidates = ofKind(move, "event_effect").getFirst().get("candidates").toString();
			assertTrue(candidates.contains("opens:lives_in"), candidates);
			assertFalse(candidates.contains("grandparent_of") || candidates.contains("aunt_uncle_of")
					|| candidates.contains("cousin_of") || candidates.contains("in_law_of"), candidates);
			assertFalse(candidates.contains("works_at"), "a place is not an employer: " + candidates);
			// One person alone: the event may end them.
			RememberOutcome gone = remember(e, "Bosse passed away in 2014.", proposal()
					.entity("e1", "Torsten Björk", "person", "Bosse").event("ev1", "passed away", "2014", "e1"));
			List<Map<String, Object>> q = ofKind(gone, "event_effect");
			assertEquals(1, q.size(), gone.applied().questions().toString());
			assertTrue(q.getFirst().toString().contains("ends_entity"), q.toString());
		}
	}

	@Test
	void theNameOfTheThingAskedAboutIsNotAnEventCue() {
		try (Engine e = engine("usage-event-cue")) {
			remember(e, "The mortgage on Bergstrasse 7 renews on 1 March 2027; confirm by 15 January 2027.",
					proposal().entity("e1", "Mortgage on Bergstrasse 7", "thing")
							.entity("e2", "Nordbank Hypotheken", "organization")
							.event("ev1", "mortgage renewal", "2027-03-01", "e1", "self", "e2")
							.event("ev2", "confirmation deadline", "2027-01-15", "self", "e2", "e1"));
			// "mortgage" is the loan's name, not the renewal: the balance is not on record, and the verdict must
			// not say two events "match the question and answer it".
			RecallResult balance = recall(e, "what is the outstanding balance on the Mortgage on Bergstrasse 7");
			assertFalse("events".equals(balance.structured().state()), balance.text());
			assertFalse(balance.text().contains("match the question and answer it"), balance.text());
			// The event's own words still cue it.
			RecallResult when = recall(e, "when is the renewal of the Mortgage on Bergstrasse 7");
			assertEquals("events", when.structured().state(), when.text());
			assertTrue(when.text().contains("mortgage renewal"), when.text());
			RecallResult confirm = recall(e, "by when must I confirm the Mortgage on Bergstrasse 7");
			assertEquals("events", confirm.structured().state(), confirm.text());
			assertTrue(confirm.text().contains("confirmation deadline"), confirm.text());
		}
	}

	@Test
	void aMissNamesTheRelationsTheThingIsOnRecordUnder() {
		try (Engine e = engine("usage-miss-under")) {
			remember(e, "Tobias is responsible for the Kestrel parser module, which is part of Kestrel.",
					proposal().entity("e1", "Tobias", "person").entity("e2", "Kestrel parser module", "thing")
							.entity("e3", "Kestrel", "project").fact("e2", "part_of", "e3")
							.fact("e1", "responsible_for", "e2"));
			// Asked with a near synonym the store does not know as one: a miss, and the note says what is there.
			RecallResult r = recall(e, "who owns the Kestrel parser module");
			assertEquals("miss", r.structured().state(), r.text());
			assertTrue(r.text().contains("Kestrel parser module is on record under part_of, responsible_for, not owns"),
					r.text());
			// The owner's long record is not listed at every miss about them.
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			RecallResult brother = recall(e, "do I have a brother");
			assertFalse(brother.text().contains("is on record under"), brother.text());
		}
	}

	@Test
	void aTypeNamedByAKindWordIsPlacedWithoutAQuestion() {
		try (Engine e = engine("usage-kind-words")) {
			// "hotel", "school", "dog": words in the kinds of place, organization, animal. Placed at once,
			// reported as inferred with what placed them, no question.
			RememberOutcome o = remember(e, "I'm staying at Hotel Gracery; Viggo starts at the Primarschule.",
					proposal().entity("e1", "Hotel Gracery", "hotel").entity("e2", "Primarschule Schübelbach", "school")
							.entity("e3", "Rufus", "dog").fact("self", "lives_in", "e1"));
			assertTrue(o.applied().questions().stream().noneMatch(q -> "type_kind".equals(q.get("kind"))),
					o.applied().questions().toString());
			assertEquals("place", e.entityTypes().get("hotel").orElseThrow().parent());
			assertEquals("organization", e.entityTypes().get("school").orElseThrow().parent());
			assertEquals("animal", e.entityTypes().get("dog").orElseThrow().parent());
			assertTrue(e.entityTypes().get("hotel").orElseThrow().inferred(),
					"still inferred: a definition may move it");
			Map<String, Object> hotel = o.applied().definitions().stream().filter(d -> "hotel".equals(d.get("name")))
					.findFirst().orElseThrow();
			assertEquals("inferred", hotel.get("resolution"));
			assertEquals("place", ((Map<?, ?>) hotel.get("inferred")).get("parent"), hotel.toString());
			assertTrue(((Map<?, ?>) hotel.get("inferred")).get("placed_by").toString().contains("kind of place"));
			// Placed under place, the hotel is a place: the fact stands.
			assertEquals(1, o.applied().facts().size(), o.applied().toString());
			// A word no type knows is still asked about, once.
			RememberOutcome unknown = remember(e, "I'm allergic to penicillin.",
					proposal().entity("e1", "penicillin", "substance").fact("self", "dislikes", "e1"));
			assertEquals(1,
					unknown.applied().questions().stream().filter(q -> "type_kind".equals(q.get("kind"))).count(),
					unknown.applied().questions().toString());
			// A plural spelling is the singular type: both cats are cats, and the plural is a synonym.
			RememberOutcome plural = remember(e, "Two cats.",
					proposal().entity("e1", "Misse", "cats").entity("e2", "Findus", "cats"));
			assertTrue(plural.applied().questions().isEmpty(), plural.applied().questions().toString());
			assertEquals("cat", e.entityTypes().get("cat").orElseThrow().name());
			assertEquals("cat", e.entities().byRef("Misse").orElseThrow().type());
			assertEquals("cat", e.entities().byRef("Findus").orElseThrow().type());
			// A plural of a seed type is that type, reported as existing, nothing inferred.
			RememberOutcome people = remember(e, "Two people.",
					proposal().entity("e1", "Sven Nyberg", "persons").entity("e2", "Hedvig Nyberg", "persons"));
			assertEquals("person", e.entities().byRef("Sven Nyberg").orElseThrow().type());
			Map<String, Object> person = people.applied().definitions().stream()
					.filter(d -> "person".equals(d.get("name"))).findFirst().orElseThrow();
			assertEquals("exists", person.get("resolution"), person.toString());
			assertTrue(person.get("placed_by").toString().contains("plural"), person.toString());
		}
	}

	@Test
	void theAssistantTeachesTheStoreAFamilyOfKindsAtOnce() {
		try (Engine e = engine("usage-kinds-taught")) {
			// The store asks what a "van" is; instead of answering one word at a time, the assistant defines the
			// family: from then on every kind it named is placed without a question, in this store.
			RememberOutcome asked = remember(e, "I drive a van.",
					proposal().entity("e1", "the van", "van").fact("self", "uses", "e1"));
			assertEquals(1, asked.applied().questions().stream().filter(q -> "type_kind".equals(q.get("kind"))).count(),
					asked.applied().questions().toString());
			RememberOutcome taught = remember(e, "A van is a vehicle; so are trucks and scooters.",
					proposal().entityType(
							new se.hirt.mnemic.proposal.Proposal.EntityTypeDef("vehicle", "Something one drives.",
									"thing", List.of(), List.of(), null, List.of("van", "truck", "scooter"))));
			assertEquals("vehicle", e.entityTypes().get("van").orElseThrow().parent(), taught.applied().toString());
			assertEquals(0, e.questions().openCount(), "the definition answered the question");
			RememberOutcome later = remember(e, "Anna rides a scooter.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Anna's scooter", "scooter")
							.fact("e1", "uses", "e2"));
			assertTrue(later.applied().questions().isEmpty(), later.applied().questions().toString());
			assertEquals("vehicle", e.entityTypes().get("scooter").orElseThrow().parent());
			assertTrue(e.entityTypes().isA("scooter", "thing"));
			// And inspect shows the family, so the next session can build on it.
			assertEquals(List.of("van", "truck", "scooter"), e.entityTypes().get("vehicle").orElseThrow().kinds());
		}
	}

	@Test
	void aThingNamedAfterItsTownIsNotTheTown() {
		try (Engine e = engine("usage-kind-word-identity")) {
			// The town first, then a hotel named after it: two things. A kind is not dropped from the name the way
			// an affix ("Kanton") is, so nothing merges and the town is not retyped (review, 2026-09-23).
			remember(e, "I live in Zürich.", proposal().entity("e1", "Zürich", "place").fact("self", "lives_in", "e1"));
			RememberOutcome stay = remember(e, "Anna is staying at Hotel Zürich.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Hotel Zürich", "hotel").fact("e1",
							"located_in", "e2"));
			assertEquals("place", e.entities().byRef("Zürich").orElseThrow().type(), "the town keeps its type");
			// The shared word may make the store ask; it never decides on its own that they are one.
			for (Map<String, Object> q : stay.applied().questions()) {
				if ("entity_resolution".equals(q.get("kind"))) {
					remember(e, "Another thing.", null,
							new se.hirt.mnemic.knowledge.QuestionResolver.Resolve(q.get("id").toString(), "new"));
				}
			}
			assertTrue(e.entities().byRef("Hotel Zürich").isPresent(), stay.applied().toString());
			assertNotEquals(e.entities().byRef("Zürich").orElseThrow().id(),
					e.entities().byRef("Hotel Zürich").orElseThrow().id(), "not merged: " + stay.applied());
			assertEquals("place", e.entities().byRef("Zürich").orElseThrow().type(), "and not retyped");
		}
	}

	@Test
	void theBriefingSaysHowMuchOfTheRecordItShows() {
		try (Engine e = engine("usage-briefing-footer")) {
			// Forty-five decisions with literal objects: no entities to confuse, more facts than the briefing shows.
			for (int i = 1; i <= 45; i++) {
				remember(e, "Decision " + i + ".", proposal().fact("decided", "to take option number " + i));
			}
			String briefing = e.recall().briefing(e.questions()).render(4000);
			assertTrue(briefing.contains("of 45 current facts about Mattias Sandell shown; recall by topic"), briefing);
		}
	}

	@Test
	void aMissSaysWhomTheObservationsBelowMention() {
		try (Engine e = engine("usage-miss-mentions")) {
			// A reading that reversed the parent relation: no step-parent is derived, so "stepmother" is a miss. The
			// marriage is in the text below all the same; the verdict says whom that text mentions, so a reader can
			// answer from it instead of taking the miss for the whole truth (Haiku, 2026-09-23).
			// (The text carries the word, as the semantic channel would find it in a configured store.)
			remember(e, "My father Konrad Nyberg married Lena Berg in 2015, so she is my stepmother.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Lena Berg", "person")
							.fact(se.hirt.mnemic.TestHomes.fact("self", "parent_of", "e1", "father", null, null, null,
									null, null, null))
							.fact("e1", "spouse_of", "e2"));
			RecallResult r = recall(e, "Mattias's stepmother");
			assertEquals("miss", r.structured().state(), r.text());
			assertTrue(r.text().contains("no such fact on record"), r.text());
			assertTrue(r.text().contains("mention") && r.text().contains("Konrad Nyberg")
					&& r.text().contains("Lena Berg"), r.text());
			assertFalse(r.text().contains("read them"), "a fact about the text, never a nudge to answer: " + r.text());
			// Nothing to mention: the miss stands alone, as an honest not-known should.
			RecallResult bare = recall(e, "Mattias's dentist");
			assertFalse(bare.text().contains("below mention"), bare.text());
		}
	}

	@Test
	void aQualifierOnAPreferenceIsShown() {
		try (Engine e = engine("usage-prefers-qualifier")) {
			RememberOutcome o = remember(e, "I prefer WhatsApp over email for anything urgent.",
					proposal().fact(fact("self", "prefers", "WhatsApp", "for anything urgent, over email", null, null,
							null, null, List.of(), null)));
			assertEquals(List.of(), o.applied().warnings(), "no warning about a slot the template lacks");
			long id = Long.parseLong(o.applied().facts().getFirst().id().substring(2));
			assertEquals("Mattias Sandell prefers WhatsApp (for anything urgent, over email)",
					e.facts().get(id).orElseThrow().rendering());
			RememberOutcome plain = remember(e, "I use Kestrel.",
					proposal().entity("e1", "Kestrel", "project").fact("self", "uses", "e1"));
			long plainId = Long.parseLong(plain.applied().facts().getFirst().id().substring(2));
			assertEquals("Mattias Sandell uses Kestrel", e.facts().get(plainId).orElseThrow().rendering(),
					"without a qualifier the slot leaves no trace");
		}
	}
}
