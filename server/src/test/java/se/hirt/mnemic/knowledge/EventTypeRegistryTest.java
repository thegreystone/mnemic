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
import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event type registry: seeded at start, looked up in memory, written through, read back at the next start. */
class EventTypeRegistryTest {

	private static final Predicate<String> REGISTERED = Set.of("owns", "works_at", "lives_in")::contains;

	private static Database open(Path home) {
		return new Database(home.resolve("mnemic.db"));
	}

	@Test
	void seedIsThereAtStart() {
		try (Database db = open(TestHomes.fresh("events-seed"))) {
			var types = new EventTypeRegistry(db);
			assertEquals(16, types.all().size());
			assertTrue(types.touches("joined", "works_at"));
			assertTrue(types.supersedes("joined", "works_at"));
			assertFalse(types.supersedes("purchased", "owns"));
			assertTrue(types.get("died").orElseThrow().endsEntity());
			assertEquals("purchased", types.cue(List.of("when", "did", "he", "buy", "it"), List.of()).orElseThrow());
		}
	}

	@Test
	void aRegistrationIsVisibleAtOnceAndAtTheNextStart() {
		Path home = TestHomes.fresh("events-register");
		try (Database db = open(home)) {
			var types = new EventTypeRegistry(db);
			EventType inherited = types.register(new EventTypeDef("Inherited", "Subject inherited object.",
					List.of("owns"), List.of(), List.of(), null, List.of("inherited", "Inherit")), null, REGISTERED);
			assertEquals("inherited", inherited.name());
			assertFalse(inherited.seed());
			assertEquals(List.of("inherited", "inherit"), inherited.lexicon());
			assertTrue(types.touches("inherited", "owns"), "answers from the map without a reload");
			assertEquals("inherited", types.cue(List.of("what", "did", "he", "inherit"), List.of()).orElseThrow());
			assertEquals(inherited,
					types.register(
							new EventTypeDef("inherited", null, List.of(), List.of(), List.of(), null, List.of()), null,
							REGISTERED),
					"an existing name is returned as it is");
		}
		try (Database db = open(home)) {
			var reopened = new EventTypeRegistry(db);
			EventType inherited = reopened.get("inherited").orElseThrow();
			assertEquals(List.of("owns"), inherited.opens());
			assertEquals(17, reopened.all().size());
		}
	}

	@Test
	void anUnknownPredicateIsRefused() {
		try (Database db = open(TestHomes.fresh("events-predicate"))) {
			var types = new EventTypeRegistry(db);
			MnemicException ex = assertThrows(MnemicException.class, () -> types.register(
					new EventTypeDef("sailed", null, List.of("captains"), List.of(), List.of(), null, List.of()), null,
					REGISTERED));
			assertEquals(MnemicException.Code.INVALID_ARGUMENT, ex.code());
			assertTrue(types.get("sailed").isEmpty());
		}
	}

	@Test
	void aCorrectionIsLoggedAndSurvivesARestart() {
		Path home = TestHomes.fresh("events-correct");
		try (Database db = open(home)) {
			var types = new EventTypeRegistry(db);
			types.register(new EventTypeDef("inherited", null, List.of("owns"), List.of(), List.of(), null,
					List.of("inherited")), null, REGISTERED);
			EventType after = types.update("inherited", Map.of("closes", List.of("owns"), "ends_entity", "false",
					"lexicon", "inherited, inherit, bequeathed"), "the giver's ownership ends", REGISTERED);
			assertEquals(List.of("owns"), after.closes());
			assertEquals(List.of("inherited", "inherit", "bequeathed"), after.lexicon());
			assertEquals("inherited", types.cue(List.of("bequeathed"), List.of()).orElseThrow());
			assertEquals(3, types.changes("inherited").size());
			assertEquals("the giver's ownership ends", types.changes("inherited").getFirst().get("reason"));
			assertThrows(MnemicException.class,
					() -> types.update("inherited", Map.of("opens", List.of("captains")), null, REGISTERED));
			assertThrows(MnemicException.class,
					() -> types.update("inherited", Map.of("colour", "red"), null, REGISTERED));
		}
		try (Database db = open(home)) {
			var reopened = new EventTypeRegistry(db);
			assertEquals(List.of("owns"), reopened.get("inherited").orElseThrow().closes());
			assertEquals(3, reopened.changes("inherited").size());
		}
	}
}
