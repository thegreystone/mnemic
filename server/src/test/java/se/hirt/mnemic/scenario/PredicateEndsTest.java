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
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * A fact of one relation ends an open fact of another about the same subject and object, as the predicate says
 * ({@code ends}): owning a thing, or deciding on it, ends considering it. "I'm leaning toward a Zenit 4" stayed current
 * after the car was ordered and was read as still undecided (usage bench, 2026-09-29). Nothing is said about purchases:
 * a purchase opens owns, and owns ends considering.
 */
class PredicateEndsTest {

	private static Fact stored(Engine e, RememberOutcome o) {
		return e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
	}

	@Test
	void aPurchaseEndsTheConsiderationThroughOwns() {
		try (Engine e = engine("ends-purchase")) {
			Fact leaning = stored(e, remember(e, "I'm leaning toward a Zenit 4 to replace the Volvo.",
					proposal().entity("z", "Zenit 4", "car").fact("self", "considering", "z")));
			assertEquals(e.entities().byRef("Zenit 4").orElseThrow().id(), leaning.objectId(),
					"a named thing is the thing, not words");
			RememberOutcome bought = remember(e, "I bought the Zenit 4 on 15 September 2026.",
					proposal().entity("z", "Zenit 4", "car").event("ev1", "purchased", "2026-09-15", "self", "z"));
			Fact after = e.facts().get(leaning.id()).orElseThrow();
			assertTrue(after.ended(), after.toString());
			assertTrue(after.validEnd() != null && after.validEnd().startsWith("2026-09-15"), after.toString());
			assertTrue(bought.applied().superseded().stream().anyMatch(m -> leaning.ref().equals(m.get("fact_id"))),
					bought.applied().superseded().toString());
		}
	}

	@Test
	void aDecisionEndsAConsiderationSaidInWordsByName() {
		try (Engine e = engine("ends-decided-words")) {
			// Said in words, before the car was a thing on record.
			Fact leaning = stored(e,
					remember(e, "I'm considering the Zenit 4.", proposal().fact("self", "considering", "Zenit 4")));
			assertNull(leaning.objectId());
			remember(e, "I decided on the Zenit 4.",
					proposal().entity("z", "Zenit 4", "car").fact("self", "decided", "z"));
			assertTrue(e.facts().get(leaning.id()).orElseThrow().ended(), "matched by the thing's name");
		}
	}

	@Test
	void somethingElseIsLeftAloneAndAPlanStaysWords() {
		try (Engine e = engine("ends-unrelated")) {
			Fact printer = stored(e, remember(e, "I'm leaning toward replacing my 3D printer eventually.",
					proposal().fact("self", "considering", "replacing my 3D printer eventually")));
			assertNull(printer.objectId(), "a plan in words stays words");
			Fact car = stored(e, remember(e, "I'm leaning toward a Zenit 4.",
					proposal().entity("z", "Zenit 4", "car").fact("self", "considering", "z")));
			remember(e, "I own a Volvo V60.", proposal().entity("v", "Volvo V60", "car").fact("self", "owns", "v"));
			assertFalse(e.facts().get(car.id()).orElseThrow().ended(), "another car ends nothing");
			assertFalse(e.facts().get(printer.id()).orElseThrow().ended());
			// A consideration that began after the ownership is not ended by it.
			Fact volvoAgain = stored(e,
					remember(e, "I'm considering selling... no, keeping the Volvo V60 from 2027.",
							proposal().entity("v", "Volvo V60", "car").fact(TestHomes.fact("self", "considering", "v",
									null, null, "2027", null, null, List.of(), null))));
			assertFalse(e.facts().get(volvoAgain.id()).orElseThrow().ended());
		}
	}

	@Test
	void aStoreSaysWhatItsOwnPredicatesEnd() {
		try (Engine e = engine("ends-correct")) {
			assertEquals(List.of("considering"), e.predicates().endsOf("decided"));
			assertEquals(List.of("considering"), e.predicates().endsOf("owns"));
			assertTrue(e.predicates().get("considering").orElseThrow().mixedRange());
			// uses ends nothing until the store says so.
			Fact trying = stored(e, remember(e, "I'm considering Obsidian for notes.",
					proposal().entity("o", "Obsidian", "technology").fact("self", "considering", "o")));
			e.correctPredicate("uses", Map.of("ends", List.of("considering")), "using it settles it");
			assertEquals(List.of("considering"), e.predicates().endsOf("uses"));
			remember(e, "I use Obsidian now.",
					proposal().entity("o", "Obsidian", "technology").fact("self", "uses", "o"));
			assertTrue(e.facts().get(trying.id()).orElseThrow().ended());
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("uses", Map.of("ends", List.of("uses")), "itself"));
			assertThrows(MnemicException.class,
					() -> e.correctPredicate("uses", Map.of("ends", List.of("no_such_relation")), "unknown"));
		}
	}

	@Test
	void consolidateEndsWhatWasSaidBeforeAPredicateEndedAnything() {
		try (Engine e = engine("ends-consolidate")) {
			e.correctPredicate("owns", Map.of("ends", List.of()), "as a store before V037 had it");
			Fact leaning = stored(e, remember(e, "I'm leaning toward a Zenit 4.",
					proposal().entity("z", "Zenit 4", "car").fact("self", "considering", "z")));
			remember(e, "I own the Zenit 4.", proposal().entity("z", "Zenit 4", "car").fact("self", "owns", "z"));
			assertFalse(e.facts().get(leaning.id()).orElseThrow().ended());
			e.correctPredicate("owns", Map.of("ends", List.of("considering")), "back to the seed");
			assertTrue(e.consolidate(true, List.of()).reclosed() >= 1, "a dry run counts it");
			assertFalse(e.facts().get(leaning.id()).orElseThrow().ended(), "and changes nothing");
			e.consolidate(false, List.of());
			assertTrue(e.facts().get(leaning.id()).orElseThrow().ended());
		}
	}

	@Test
	void aStoreFromBeforeTheMigrationGetsTheSeedEndsAndRanges() throws Exception {
		Path home = TestHomes.fresh("ends-upgrade");
		try (Engine e = engine(home)) {
			remember(e, "I'm considering the Zenit 4.", proposal().fact("self", "considering", "Zenit 4"));
		}
		try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + home.resolve("mnemic.db").toAbsolutePath(),
				new java.util.Properties()); Statement st = c.createStatement()) {
			// The store as schema 36 left it: no ends column, considering and decided taking words only.
			st.execute("ALTER TABLE predicate DROP COLUMN ends");
			st.execute("UPDATE predicate SET range = '[\"literal\"]' WHERE name IN ('considering', 'decided')");
			st.execute("DELETE FROM schema_version WHERE version = 37");
		}
		try (Engine e = engine(home)) {
			assertEquals(37, e.database().schemaVersion());
			assertEquals(List.of("considering"), e.predicates().endsOf("owns"));
			assertEquals(List.of("literal", "*"), e.predicates().get("decided").orElseThrow().range());
			assertTrue(e.predicates().changes("owns").stream().anyMatch(ch -> "ends".equals(ch.get("field"))),
					e.predicates().changes("owns").toString());
		}
	}
}
