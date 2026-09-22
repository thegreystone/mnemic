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
import se.hirt.mnemic.recall.RecallResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * A name that lands on a thing the relation does not apply to is read as its namesake of the right kind: "the plate for
 * the Polestar" is the car's, not the maker's (2026-09-22). The rule is about names and types, not about cars: a
 * company named after a person, a city that shares its name with an insurer, a model number.
 */
class NamesakeTest {

	@Test
	void aCompanyNamedAfterAPersonDoesNotHideThePerson() {
		try (Engine e = engine("namesake-company")) {
			remember(e, "I work at Nordvik.",
					proposal().entity("e1", "Nordvik", "organization").fact("self", "works_at", "e1"));
			remember(e, "Hans Nordvik lives in Lund.", proposal().entity("e1", "Hans Nordvik", "person")
					.entity("e2", "Lund", "place").fact("e1", "lives_in", "e2"));
			// "Nordvik" is the company, exactly; living is a person's. The person named Nordvik is meant.
			RecallResult r = recall(e, "where does Nordvik live");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Hans Nordvik lives in Lund"), r.text());
			assertTrue(r.text().contains(
					"'Nordvik' read as Hans Nordvik: lives_in is of a person, and Nordvik is an " + "organization"),
					r.text());
			// The company is still the company where the relation is its own.
			RecallResult work = recall(e, "where do I work");
			assertEquals("matched", work.structured().state(), work.text());
			assertTrue(work.text().contains("works at Nordvik"), work.text());
			assertFalse(work.text().contains("read as"), work.text());
		}
	}

	@Test
	void theObjectSideIsTypedToo() {
		try (Engine e = engine("namesake-object")) {
			remember(e, "I live in Zürich.", proposal().entity("e1", "Zürich", "place").fact("self", "lives_in", "e1"));
			remember(e, "Anna Lindqvist works at Zurich Insurance.", proposal().entity("e1", "Anna Lindqvist", "person")
					.entity("e2", "Zurich Insurance", "organization").fact("e1", "works_at", "e2"));
			// "at Zurich" lands on the city; one works at an organization. The insurer is meant.
			RecallResult r = recall(e, "who works at Zurich");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Anna Lindqvist works at Zurich Insurance"), r.text());
			assertTrue(r.text().contains("read as Zurich Insurance"), r.text());
			// And the city is still where the owner lives.
			RecallResult home = recall(e, "where do I live");
			assertEquals("matched", home.structured().state(), home.text());
			assertTrue(home.text().contains("lives in Zürich"), home.text());
		}
	}

	@Test
	void aThingThatFitsIsNeverSecondGuessed() {
		try (Engine e = engine("namesake-fits")) {
			remember(e, "Anna lives in Uppsala.", proposal().entity("e1", "Anna", "person")
					.entity("e2", "Uppsala", "place").fact("e1", "lives_in", "e2"));
			remember(e, "Anna Lindqvist lives in Lund.", proposal().entity("e1", "Anna Lindqvist", "person")
					.entity("e2", "Lund", "place").fact("e1", "lives_in", "e2"));
			// A person named Anna exists and a person can live somewhere: her own fact, and only hers.
			RecallResult r = recall(e, "where does Anna live");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Anna lives in Uppsala"), r.text());
			assertFalse(r.text().contains("read as"), r.text());
			assertFalse(r.text().contains("Anna Lindqvist lives in Lund [f"), "not her namesake's fact: " + r.text());
		}
	}

	@Test
	void withoutANamesakeOfTheRightKindAMissStaysAMiss() {
		try (Engine e = engine("namesake-none")) {
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "Hooli Ventures is a subsidiary.",
					proposal().entity("e1", "Hooli Ventures", "organization").fact("e1", "part_of", "Hooli"));
			// Two organizations share the name; neither lives anywhere. Nothing is guessed.
			RecallResult r = recall(e, "where does Hooli live");
			assertEquals("miss", r.structured().state(), r.text());
			assertFalse(r.text().contains("read as"), r.text());
		}
	}

	@Test
	void aYesNoQuestionIsDecidedThroughTheNamesake() {
		try (Engine e = engine("namesake-polar")) {
			remember(e, "I work at Nordvik.",
					proposal().entity("e1", "Nordvik", "organization").fact("self", "works_at", "e1"));
			remember(e, "Hans Nordvik lives in Lund.", proposal().entity("e1", "Hans Nordvik", "person")
					.entity("e2", "Lund", "place").fact("e1", "lives_in", "e2"));
			RecallResult yes = recall(e, "does Nordvik live in Lund");
			assertEquals("matched", yes.structured().state(), yes.text());
			assertTrue(yes.text().contains("Hans Nordvik lives in Lund"), yes.text());
		}
	}

	@Test
	void everyNamesakeOfTheRightKindAnswersAnOpenQuestion() {
		try (Engine e = engine("namesake-several")) {
			remember(e, "I work at Nordvik.",
					proposal().entity("e1", "Nordvik", "organization").fact("self", "works_at", "e1"));
			remember(e, "Hans Nordvik lives in Lund and Eva Nordvik lives in Malmö.",
					proposal().entity("e1", "Hans Nordvik", "person").entity("e2", "Lund", "place")
							.entity("e3", "Eva Nordvik", "person").entity("e4", "Malmö", "place")
							.fact("e1", "lives_in", "e2").fact("e3", "lives_in", "e4"));
			RecallResult r = recall(e, "where does Nordvik live");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(
					r.text().contains("Hans Nordvik lives in Lund") && r.text().contains("Eva Nordvik lives in Malmö"),
					"both people named Nordvik, since the question does not say which: " + r.text());
		}
	}

	@Test
	void aModelNumberIsANamesakeToo() {
		try (Engine e = engine("namesake-model")) {
			remember(e, "Raspberry is a fruit I like.",
					proposal().entity("e1", "Raspberry", "food").fact("self", "prefers", "e1"));
			remember(e, "The Raspberry Pi 5 is located in the workshop.",
					proposal().entity("e1", "Raspberry Pi 5", "thing").entity("e2", "the workshop", "place").fact("e1",
							"located_in", "e2"));
			// located_in is a place's or a thing's; the fruit is neither, and the Pi shares the word.
			RecallResult r = recall(e, "where is Raspberry located");
			assertTrue(
					r.text().contains("Raspberry Pi 5 is located in the workshop")
							|| "miss".equals(r.structured().state()),
					"either the Pi answers or nothing is guessed, never the fruit: " + r.text());
			assertFalse(r.text().contains("Raspberry is located"), r.text());
		}
	}
}
