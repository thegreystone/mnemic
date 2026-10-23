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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import se.hirt.mnemic.Engine;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.TestHomes.P;
import se.hirt.mnemic.recall.RecallResult;

/**
 * A decision or a plan is words, and a question about a thing those words name is answered by it: the literal object of
 * a fact touches the entities it names, not only the ones it links to.
 */
class LiteralMentionTest {

	private static Engine store() {
		Engine e = TestHomes.engine("literal-mention");
		remember(e, "I decided to insure the Polestar with Zurich Insurance rather than Helvetia.",
				proposal().entity("z", "Zurich Insurance", "organization").entity("h", "Helvetia", "organization")
						.entity("n", "Nordbank", "organization")
						.fact("self", "decided", "insure the Polestar with Zurich Insurance rather than Helvetia"));
		return e;
	}

	@Test
	void aDecisionAnswersAQuestionAboutWhatItNames() {
		try (Engine e = store()) {
			RecallResult r = recall(e, "What did Mattias decide about Zurich Insurance?");
			assertTrue(r.structured().matched(), r.text());
			assertEquals(1, r.structured().facts().size(), r.text());
			RecallResult other = recall(e, "What did Mattias decide about Helvetia?");
			assertTrue(other.structured().matched(), other.text());
		}
	}

	@Test
	void aDecisionThatNamesSomethingElseStaysANearMiss() {
		try (Engine e = store()) {
			RecallResult r = recall(e, "What did Mattias decide about Nordbank?");
			assertFalse(r.structured().matched(), r.text());
			assertEquals(1, r.structured().nearMisses().size(), r.text());
			assertTrue(r.structured().notes().stream().anyMatch(n -> n.contains("by name in their wording")), r.text());
		}
	}

	@Test
	void anInsurersNameDoesNotAlsoNameTheCity() {
		try (Engine e = decision("insure the Polestar with Zurich Insurance", insurerAndCity())) {
			assertDecisionAbout(e, "Zurich Insurance", true);
			assertDecisionAbout(e, "Zürich", false);
		}
	}

	@Test
	void theCityCanBeNamedSeparatelyAfterTheInsurer() {
		try (Engine e = decision("insure the Polestar with Zurich Insurance before moving to Zürich",
				insurerAndCity())) {
			assertDecisionAbout(e, "Zurich Insurance", true);
			assertDecisionAbout(e, "Zürich", true);
		}
	}

	@Test
	void aNormalizedExplicitAliasNamesOnlyTheInsurer() {
		try (Engine e = decision("insure the Polestar with ZÜRICH ASSURANCE",
				proposal().entity("city", "Zürich", "place").entity("insurer", "Zurich Insurance", "organization",
						"Zürich Assurance"))) {
			assertDecisionAbout(e, "Zurich Insurance", true);
			assertDecisionAbout(e, "Zürich", false);
		}
	}

	@Test
	void aLongOrganizationNameDoesNotAlsoNameTheCity() {
		try (Engine e = decision("insure the Polestar with Zurich Insurance Company of Switzerland",
				proposal().entity("city", "Zürich", "place").entity("insurer",
						"Zurich Insurance Company of Switzerland", "organization", "ZICS"))) {
			assertDecisionAbout(e, "Zurich Insurance Company of Switzerland", true);
			assertDecisionAbout(e, "ZICS", true);
			assertDecisionAbout(e, "Zürich", false);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"New York", "New York City"})
	void aLongerNameOrAliasDoesNotAlsoNameTheShorterPlace(String destination) {
		try (Engine e = decision("visit " + destination,
				proposal().entity("york", "York", "place").entity("new-york", "New York", "place", "New York City"))) {
			assertDecisionAbout(e, "New York", true);
			assertDecisionAbout(e, "York", false);
		}
	}

	@Test
	void aNameMustOccupyWholeWords() {
		try (Engine e = decision("visit Yorkshire",
				proposal().entity("york", "York", "place").entity("yorkshire", "Yorkshire", "place"))) {
			assertDecisionAbout(e, "Yorkshire", true);
			assertDecisionAbout(e, "York", false);
		}
	}

	@Test
	void aUniqueFirstNameNamesThePerson() {
		try (Engine e = decision("invite Anna to the meeting", proposal().entity("anna", "Anna Lindqvist", "person"))) {
			assertDecisionAbout(e, "Anna Lindqvist", true);
		}
	}

	@Test
	void anAmbiguousFirstNameDoesNotChooseAPerson() {
		try (Engine e = decision("invite Anna to the meeting",
				proposal().entity("anna-l", "Anna Lindqvist", "person").entity("anna-b", "Anna Berg", "person"))) {
			assertDecisionAbout(e, "Anna Lindqvist", false);
			assertDecisionAbout(e, "Anna Berg", false);
		}
	}

	@Test
	void aLaterRecallSeesNewAliases() {
		try (Engine e = decision("insure the Polestar with Local Insurer", insurerAndCity())) {
			assertDecisionAbout(e, "Zurich Insurance", false);
			remember(e, "Local Insurer is another name for Zurich Insurance.",
					proposal().entity("insurer", "Zurich Insurance", "organization", "Local Insurer"));
			assertDecisionAbout(e, "Zurich Insurance", true);
			assertDecisionAbout(e, "Zürich", false);
		}
	}

	private static P insurerAndCity() {
		return proposal().entity("city", "Zürich", "place").entity("insurer", "Zurich Insurance", "organization");
	}

	private static Engine decision(String wording, P entities) {
		Engine e = TestHomes.engine("literal-mention");
		remember(e, "I decided to " + wording + ".", entities.fact("self", "decided", wording));
		return e;
	}

	private static void assertDecisionAbout(Engine e, String name, boolean matched) {
		RecallResult r = recall(e, "What did Mattias decide about " + name + "?");
		assertEquals(matched, r.structured().matched(), r.text());
		assertEquals(matched ? 1 : 0, r.structured().facts().size(), r.text());
		assertEquals(matched ? 0 : 1, r.structured().nearMisses().size(), r.text());
	}
}
