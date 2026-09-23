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
