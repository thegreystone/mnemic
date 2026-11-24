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
package se.hirt.mnemic.recall;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Lang;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search-term rules under T28: words that only say "now", and "own" where it is the adjective. */
class HomographTest {

	@Test
	@Scenario("T28")
	void ownIsTheVerbOnlyWhereNoPossessiveOrParenthesisMakesItTheAdjective() {
		assertEquals(0,
				Query.matchedTerms("Mattias Sandell has phone +41 79 000 00 00 (own mobile number)", List.of("own")));
		assertEquals(0, Query.matchedTerms("Mattias Sandell's own number is on the card.", List.of("own")));
		assertEquals(0, Query.matchedTerms("Clara rides on her own Garmin account.", List.of("own")));
		assertEquals(1, Query.matchedTerms("Konrad Nyberg does not own Toyota Yaris", List.of("own")));
		assertEquals(1, Query.matchedTerms("my own car, which I own outright", List.of("own")),
				"one use as the verb is enough");
		// Other words keep the prefix rule.
		assertEquals(1, Query.matchedTerms("Mattias Sandell leads the team", List.of("lead")));
		// A full-text hit is dropped only when nothing but the adjective matched, words split as the index splits them.
		assertTrue(Query.matchedOnlyAsOtherWords("Correction of Mattias Sandell has phone 123 (own mobile number)",
				List.of("cars", "own")));
		assertFalse(Query.matchedOnlyAsOtherWords("The 3d-worms game is his own", List.of("worms", "own")));
		assertFalse(Query.matchedOnlyAsOtherWords("I own a car", List.of("cars", "own")));
	}

	@Test
	@Scenario("T28")
	void theFullTextQueryLeavesOutTheAdjectiveAndTheWordsOfThePresent() {
		String fts = Query.ftsQuery("what cars does Mattias own", Set.of("mattias"));
		assertTrue(fts.contains("(\"own\" NOT ("), fts);
		assertTrue(fts.contains("\"her own\""), fts);
		assertTrue(fts.contains("\"s own\""), "the possessive as the index splits it: " + fts);
		String present = Query.ftsQuery("what are my current cars", Set.of(),
				Set.copyOf(se.hirt.mnemic.knowledge.LanguagePack.of(Lang.EN).query().tenseFiller()));
		assertFalse(present.contains("current"), present);
		assertTrue(present.contains("\"cars\""), present);
		// Nothing but a present-tense word: kept, or there would be nothing to search.
		assertEquals("\"current\"*", Query.ftsQuery("current", Set.of(), Set.of("current")));
		// The question's own "my own" is the adjective throughout.
		assertEquals(Set.of("own"), Query.adjectivalOnly("what is my own phone number"));
		assertEquals(Set.of(), Query.adjectivalOnly("what cars does Mattias own"));
	}

	@Test
	@Scenario("T26")
	void theKindAfterTheCopulaIsReadWhenNoRelationIsNamed() {
		assertEquals(List.of("cars"), Query.kindsAfterCopula("What are my current cars?", Set.of(), Lang.EN));
		assertEquals(List.of("bikes"), Query.kindsAfterCopula("which are Anna's bikes", Set.of("anna"), Lang.EN));
		assertEquals(List.of(), Query.kindsAfterCopula("who is Anna", Set.of("anna"), Lang.EN));
	}
}
