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
			assertEquals(12, types.all().size());
		}
	}

	@Test
	void aSpellingThatSaysWhereATypeGoesPlacesIt() {
		Path home = TestHomes.fresh("types-place-from-use");
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			// A word in a type's kinds: a new type under it, inferred, so a definition can still move it.
			EntityTypeRegistry.Placement hotel = types.placeFromUse("hotel", null).orElseThrow();
			assertEquals("place", hotel.type().parent());
			assertTrue(hotel.type().inferred());
			assertTrue(types.isA("hotel", "place"));
			assertEquals(List.of("hotel", "gracery"), types.identityTokens("Hotel Gracery", "hotel"),
					"a kind is not an affix: the hotel keeps every word of its name");
			// A plural of such a word: the singular is the type, the plural its synonym.
			EntityTypeRegistry.Placement cats = types.placeFromUse("cats", null).orElseThrow();
			assertEquals("cat", cats.type().name());
			assertEquals(List.of("cats"), cats.type().synonyms());
			assertEquals("animal", cats.type().parent());
			assertEquals("cat", types.canonical("cats"));
			assertEquals(List.of("cat", "animal"), types.lineage("cats"), "a synonym reads as its type");
			// A plural of a registered type: that type, the plural added as a synonym, the type still movable.
			EntityTypeRegistry.Placement hotels = types.placeFromUse("hotels", null).orElseThrow();
			assertEquals("hotel", hotels.type().name());
			assertFalse(hotels.created());
			assertTrue(hotels.type().synonyms().contains("hotels"));
			assertTrue(hotels.type().inferred(), "a synonym the store noted does not make the type deliberate");
			// A plural of a synonym: the type the synonym names, and no type of the synonym's own.
			EntityTypeRegistry.Placement companies = types.placeFromUse("companies", null).orElseThrow();
			assertEquals("organization", companies.type().name());
			assertFalse(companies.created());
			assertTrue(types.get("company").isEmpty(), "the synonym is not hijacked into a subtype");
			assertEquals("organization", types.canonical("companies"));
			// A compound is placed under what its head names: a registered type first, a kind second.
			EntityTypeRegistry.Placement boutique = types.placeFromUse("boutique hotel", null).orElseThrow();
			assertEquals("boutique_hotel", boutique.type().name());
			assertEquals("hotel", boutique.type().parent(), "hotel exists by now, so the head names it");
			assertEquals("team", types.placeFromUse("football team", null).orElseThrow().type().parent());
			assertEquals("place", types.placeFromUse("garden cottage", null).orElseThrow().type().parent());
			// An unplaced type's plural leaves it unplaced: the question that asked about it still stands.
			types.registerInferred("van", null);
			types.placeFromUse("vans", null).orElseThrow();
			assertTrue(types.unplaced("van"), "still nobody's kind");
			assertTrue(types.unplaced("vans"), "and so is its plural");
			// A word nothing in the store answers for: nothing placed, the caller asks.
			assertTrue(types.placeFromUse("substance", null).isEmpty());
			assertTrue(types.placeFromUse("hotel", null).isEmpty(), "a registered type is not placed again");
		}
		try (Database db = open(home)) {
			var reopened = new EntityTypeRegistry(db);
			assertEquals("cat", reopened.canonical("cats"));
			assertEquals("hotel", reopened.get("boutique_hotel").orElseThrow().parent());
			assertTrue(reopened.isA("boutique_hotel", "place"));
		}
	}

	@Test
	void kindsAreTheStoresOwnVocabulary() {
		Path home = TestHomes.fresh("types-kinds-grow");
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			// A defined type with kinds of its own places what it names, ahead of the seeds.
			types.register(new EntityTypeDef("vehicle", "Something one drives.", "thing", List.of(), List.of(), null,
					List.of("car", "van", "truck")), null);
			assertEquals("vehicle", types.placeFromUse("van", null).orElseThrow().type().parent());
			// A type waiting for its kind is placed when a definition names it, and stays movable.
			types.registerInferred("truck", null);
			assertEquals(List.of("truck"), types.adoptKinds("vehicle"));
			assertEquals("vehicle", types.get("truck").orElseThrow().parent());
			assertTrue(types.get("truck").orElseThrow().inferred());
			// A word two types claim places nothing, from use or from a definition.
			types.register(new EntityTypeDef("lorry", "A big truck.", "thing", List.of(), List.of(), null,
					List.of("truck", "tanker")), null);
			types.registerInferred("tanker", null);
			assertEquals(List.of("tanker"), types.adoptKinds("lorry"), "truck is claimed twice, tanker once");
			assertEquals("vehicle", types.get("truck").orElseThrow().parent(), "not moved");
			// "car" is now claimed by thing (seed) and vehicle: nothing is placed, the caller asks.
			assertTrue(types.placeFromUse("car", null).isEmpty(), "two claims: ask");
			// A correction settles it: the seed's list is the store's to change, and the change is logged.
			List<String> without = types.get("thing").orElseThrow().kinds().stream().filter(k -> !k.equals("car"))
					.toList();
			types.update("thing", Map.of("kinds", without), "cars are vehicles here");
			assertEquals("vehicle", types.placeFromUse("car", null).orElseThrow().type().parent());
			assertTrue(types.changes("thing").stream().anyMatch(c -> "kinds".equals(c.get("field"))),
					types.changes("thing").toString());
		}
		try (Database db = open(home)) {
			var reopened = new EntityTypeRegistry(db);
			assertFalse(reopened.get("thing").orElseThrow().kinds().contains("car"),
					"the corrected seed list stays corrected across a start");
			assertEquals(List.of("car", "van", "truck"), reopened.get("vehicle").orElseThrow().kinds());
		}
	}

	@Test
	void aStoreFromBeforeKindsGetsTheSeedWordsOnce() {
		Path home = TestHomes.fresh("types-kinds-once");
		try (Database db = open(home)) {
			new EntityTypeRegistry(db);
			// A row from before the column: NULL, not an empty list.
			db.write(tx -> tx.update("UPDATE entity_type SET kinds = NULL WHERE name = 'place'"));
		}
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			assertTrue(types.get("place").orElseThrow().kinds().contains("hotel"), "filled from the seed");
			types.update("place", Map.of("kinds", List.of()), "none here");
		}
		try (Database db = open(home)) {
			var reopened = new EntityTypeRegistry(db);
			assertEquals(List.of(), reopened.get("place").orElseThrow().kinds(), "an emptied list is not refilled");
		}
	}

	@Test
	void aSeedThatArrivesAfterTheStoreNamedItFromUseIsMergedIn() {
		Path home = TestHomes.fresh("types-seed-after-use");
		try (Database db = open(home)) {
			new EntityTypeRegistry(db);
			// As if the store had met "animal" from use before the seed existed: an inferred row with a parent.
			db.write(tx -> tx.update("DELETE FROM entity_type WHERE name = 'animal'"));
			db.write(tx -> tx.update("""
					INSERT INTO entity_type(name, description, parent, synonyms, type_words, kinds, defined_by, seed,
					                        inferred, created_at, disjoint) VALUES ('animal', NULL, 'thing', '[]', '[]',
					                        '[]', NULL, 0, 1, '2026-09-01T00:00:00Z', 0)"""));
		}
		try (Database db = open(home)) {
			var types = new EntityTypeRegistry(db);
			EntityTypeRegistry.EntityType animal = types.get("animal").orElseThrow();
			assertTrue(animal.seed(), "a seed from now on");
			assertEquals("thing", animal.parent(), "the parent the store chose stays");
			assertTrue(animal.kinds().contains("dog"), "with the seed's kinds");
			assertEquals("animal", types.canonical("pet"), "and its synonyms");
			assertEquals("animal", types.placeFromUse("dog", null).orElseThrow().type().parent());
		}
	}

	@Test
	void everySeedKindNamesOneKind() {
		var seen = new java.util.HashMap<String, String>();
		for (EntityTypeRegistry.EntityType t : EntityTypeRegistry.seed()) {
			for (String w : t.kinds()) {
				String other = seen.put(w, t.name());
				assertEquals(null, other, "'" + w + "' is a kind of both " + other + " and " + t.name());
				assertEquals(w, w.toLowerCase(), w);
			}
		}
		// A kind is never a synonym of any seed: a synonym names the type itself, a kind names a subtype.
		var synonyms = new java.util.HashSet<String>();
		EntityTypeRegistry.seed().forEach(t -> synonyms.addAll(t.synonyms()));
		for (EntityTypeRegistry.EntityType t : EntityTypeRegistry.seed()) {
			for (String w : t.kinds()) {
				assertFalse(synonyms.contains(w), "'" + w + "' is both a synonym and a kind");
			}
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
			assertEquals(13, reopened.all().size());
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
