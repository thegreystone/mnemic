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
package se.hirt.mnemic.knowledge;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.EntityTypeRegistry.EntityType;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entity type registry: seeded at start, looked up in memory, written through, read back at the next start. */
class EntityTypeRegistryTest {

	private static Database open(Path home) {
		return new Database(home.resolve("mnemic.db"));
	}

	@Test
	void seedIsThereAtStartAndAnswersFromMemory() {
		try (Database db = open(TestHomes.fresh("types-seed"))) {
			var types = new EntityTypeRegistry(db);
			assertEquals("organization", types.canonical("Company"), "a synonym maps onto the registered type");
			assertEquals("place", types.canonical("town"));
			assertEquals("unknown", types.canonical(null));
			assertEquals("spaceship", types.canonical("Spaceship"), "an unregistered type passes through lowercased");
			assertEquals(List.of("country", "place"), types.lineage("country"));
			assertTrue(types.isA("country", "place"));
			assertFalse(types.isA("place", "country"));
			assertTrue(types.sameKind("country", "place"), "a town and a country resolve against each other");
			assertFalse(types.sameKind("person", "place"));
			assertTrue(types.isTypeWord("gmbh"));
			assertEquals(List.of("luzern"), types.identityTokens("Kanton Luzern", "place"));
			assertEquals(List.of("luzern"), types.identityTokens("Kanton Luzern", "country"),
					"a country drops the type words of the place it is");
			assertEquals(List.of("kanton"), types.identityTokens("Kanton", "place"),
					"a name that is only a type word keeps it");
			assertEquals(11, types.all().size());
		}
	}

	@Test
	void aRegistrationIsVisibleAtOnceAndAtTheNextStart() {
		Path home = TestHomes.fresh("types-register");
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			EntityType canton = types.register(new EntityTypeDef("Canton", "A Swiss canton.", "Place",
					List.of("Kanton"), List.of("kanton", "canton")), null);
			assertEquals("canton", canton.name());
			assertEquals("place", canton.parent());
			assertFalse(canton.seed());
			assertEquals("canton", types.canonical("kanton"), "the new synonym answers without a reload");
			assertTrue(types.isA("canton", "place"));
			assertTrue(types.isTypeWord("kanton"));
			assertEquals(List.of("schwyz"), types.identityTokens("Kanton Schwyz", "canton"));
			assertEquals(canton, types.register(new EntityTypeDef("canton", "again", null, List.of(), List.of()), null),
					"an existing name is returned as it is");
		}
		try (Database db = open(home)) {
			var reopened = new EntityTypeRegistry(db);
			EntityType canton = reopened.get("canton").orElseThrow();
			assertEquals("place", canton.parent());
			assertEquals(List.of("kanton"), canton.synonyms());
			assertEquals("canton", reopened.canonical("kanton"));
			assertEquals(12, reopened.all().size());
		}
	}

	@Test
	void anUnknownParentIsRefused() {
		try (Database db = open(TestHomes.fresh("types-parent"))) {
			var types = new EntityTypeRegistry(db);
			MnemicException ex = assertThrows(MnemicException.class,
					() -> types.register(new EntityTypeDef("moon", null, "planet", List.of(), List.of()), null));
			assertEquals(MnemicException.Code.INVALID_ARGUMENT, ex.code());
			assertTrue(types.get("moon").isEmpty(), "nothing was written");
		}
	}

	@Test
	void aCorrectionIsLoggedAndSurvivesARestart() {
		Path home = TestHomes.fresh("types-correct");
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			types.register(new EntityTypeDef("canton", null, "place", List.of(), List.of("kanton")), null);
			EntityType after = types.update("canton",
					Map.of("type_words", List.of("kanton", "canton", "ct"), "synonyms", "kanton, cantone"),
					"more spellings");
			assertEquals(List.of("kanton", "canton", "ct"), after.typeWords());
			assertEquals(List.of("kanton", "cantone"), after.synonyms(), "a comma-separated string is a list too");
			assertTrue(types.isTypeWord("ct"), "the maps follow the write");
			assertEquals("canton", types.canonical("cantone"));
			List<Map<String, Object>> changes = types.changes("canton");
			assertEquals(2, changes.size());
			assertEquals("more spellings", changes.getFirst().get("reason"));
			assertThrows(MnemicException.class, () -> types.update("place", Map.of("parent", "canton"), "a cycle"),
					"a type cannot nest within its own descendant");
			assertThrows(MnemicException.class, () -> types.update("canton", Map.of("colour", "red"), null));
		}
		try (Database db = open(home)) {
			var reopened = new EntityTypeRegistry(db);
			assertTrue(reopened.isTypeWord("ct"));
			assertEquals(2, reopened.changes("canton").size());
		}
	}
}
