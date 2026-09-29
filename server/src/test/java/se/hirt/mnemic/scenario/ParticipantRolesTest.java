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
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.protocol.MnemicException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * An event's participants may have roles: the one who took part alongside ("with"), the one bought from ("from"), a
 * co-author. "Marcus Hirt created Calle Wilund, JMAPI" read as if Calle had been created (2026-09-29): with a role he
 * is "created JMAPI with Calle Wilund", and the type's effects are between the others only.
 */
class ParticipantRolesTest {

	private static Event event(Engine e, RememberOutcome o) {
		return e.events().get(Long.parseLong(o.applied().events().getFirst().id().substring(4))).orElseThrow();
	}

	private static List<Fact> ownerFacts(Engine e, String predicate) {
		return e.facts().factsOf(e.entities().owner().id()).stream()
				.filter(f -> predicate.equals(f.predicate()) && f.current()).toList();
	}

	@Test
	void aRoleReadsAfterTheSentence() {
		try (Engine e = engine("roles-render")) {
			Event ev = event(e,
					remember(e, "Bo and I created Kestrel in 2019.",
							proposal().entity("b", "Bo Berg", "person").entity("k", "Kestrel", "project").event("ev1",
									"created", "2019", Map.of("b", "with"), "self", "b", "k")));
			String owner = e.entities().owner().name();
			assertTrue(ev.rendering().startsWith(owner + " created Kestrel with Bo Berg"), ev.rendering());
			long bo = e.entities().byRef("Bo Berg").orElseThrow().id();
			assertEquals("with", ev.roleOf(bo));
			assertNull(ev.roleOf(e.entities().owner().id()));
			// A role that is a noun reads as one.
			Event book = event(e,
					remember(e, "I wrote the Kestrel book with Bo as co-author.",
							proposal().entity("b", "Bo Berg", "person").entity("t", "The Kestrel Book", "thing")
									.event("ev1", "wrote", null, Map.of("b", "co-author"), "self", "t", "b")));
			assertTrue(book.rendering().contains("The Kestrel Book (co-author: Bo Berg)"), book.rendering());
		}
	}

	@Test
	void aParticipantWithARoleIsLeftOutOfTheTypesEffects() {
		try (Engine e = engine("roles-effects")) {
			// Bought from a dealer: the car is owned, the dealer is not.
			remember(e, "I bought the Volvo from Hammer Auto in 2024.",
					proposal().entity("c", "Volvo XC40", "thing").entity("d", "Hammer Auto", "organization")
							.event("ev1", "purchased", "2024", Map.of("d", "from"), "self", "c", "d"));
			List<Fact> owns = ownerFacts(e, "owns");
			assertEquals(1, owns.size(), owns.toString());
			assertEquals("Volvo XC40", e.entities().nameOf(owns.getFirst().objectId()));
			// Joined with a friend: the job is at the company, not at the friend.
			remember(e, "I joined Hooli with Bo in 2018.",
					proposal().entity("h", "Hooli", "organization").entity("b", "Bo Berg", "person").event("ev1",
							"joined", "2018", Map.of("b", "with"), "self", "h", "b"));
			List<Fact> jobs = ownerFacts(e, "works_at");
			assertEquals(1, jobs.size(), jobs.toString());
			assertEquals("Hooli", e.entities().nameOf(jobs.getFirst().objectId()));
		}
	}

	@Test
	void rolesAreCorrectedOnAnEventOnRecordAndReplayedByARebuild() {
		try (Engine e = engine("roles-correct")) {
			Event ev = event(e, remember(e, "Calle and I wrote JMAPI.", proposal().entity("c", "Calle Wilund", "person")
					.entity("j", "JMAPI", "project").event("ev1", "created", null, "self", "c", "j")));
			assertTrue(ev.rendering().contains("created Calle Wilund, JMAPI"), ev.rendering());
			Map<String, Object> out = e.correctEvent(ev.id(), Map.of("roles", Map.of("Calle Wilund", "with")),
					"I did not create Calle Wilund");
			Event fixed = e.events().get(ev.id()).orElseThrow();
			assertTrue(fixed.rendering().contains("created JMAPI with Calle Wilund"), out.toString());
			// Said again as it stands, it is no change; the first participant takes no role; a stranger is refused.
			assertThrows(MnemicException.class,
					() -> e.correctEvent(ev.id(), Map.of("roles", Map.of("Calle Wilund", "with")), "again"));
			assertThrows(MnemicException.class,
					() -> e.correctEvent(ev.id(), Map.of("roles", Map.of("self", "with")), "me"));
			assertThrows(MnemicException.class,
					() -> e.correctEvent(ev.id(), Map.of("roles", Map.of("Nobody Else", "with")), "who"));
			// A rebuild replays the correction.
			e.consolidate(false, List.of(), true);
			long rebuilt = e.database().read(tx -> tx.queryLong("SELECT id FROM event WHERE type = 'created'"));
			Event again = e.events().get(rebuilt).orElseThrow();
			Entity calle = e.entities().byRef("Calle Wilund").orElseThrow();
			assertEquals("with", again.roleOf(calle.id()), again.toString());
			// An empty role clears it.
			e.correctEvent(rebuilt, Map.of("roles", Map.of("Calle Wilund", "")), "undo");
			Event cleared = e.events().get(rebuilt).orElseThrow();
			assertNull(cleared.roleOf(calle.id()));
			assertTrue(cleared.rendering().contains("created Calle Wilund, JMAPI"), cleared.rendering());
		}
	}

	@Test
	void theSameEventSaidAgainWithARoleGetsIt() {
		try (Engine e = engine("roles-restated")) {
			Event ev = event(e, remember(e, "Calle and I wrote JMAPI.", proposal().entity("c", "Calle Wilund", "person")
					.entity("j", "JMAPI", "project").event("ev1", "created", null, "self", "c", "j")));
			RememberOutcome again = remember(e, "I wrote JMAPI together with Calle.",
					proposal().entity("c", "Calle Wilund", "person").entity("j", "JMAPI", "project").event("ev1",
							"created", null, Map.of("c", "with"), "self", "c", "j"));
			assertEquals(ev.ref(), again.applied().events().getFirst().id(), "one event");
			assertTrue(e.events().get(ev.id()).orElseThrow().rendering().contains("created JMAPI with Calle Wilund"));
		}
	}

	@Test
	void aRoleForSomeoneNotTakingPartIsSaid() {
		try (Engine e = engine("roles-unknown")) {
			RememberOutcome o = remember(e, "I created Kestrel.", proposal().entity("k", "Kestrel", "project")
					.event("ev1", "created", null, Map.of("Bo Berg", "with", "self", "with"), "self", "k"));
			assertEquals(2, o.applied().warnings().stream().filter(w -> w.startsWith("Role ")).count(),
					o.applied().warnings().toString());
			assertTrue(event(e, o).roles().stream().allMatch(r -> r == null));
		}
	}

	@Test
	void aTypeNoEventUsesAnyMoreIsRemoved() {
		try (Engine e = engine("roles-remove-type")) {
			Event ev = event(e, remember(e, "Calle and I wrote JMAPI.", proposal().entity("c", "Calle Wilund", "person")
					.entity("j", "JMAPI", "project").event("ev1", "co_created", null, "self", "c", "j")));
			// Used: refused. A seed: refused.
			assertThrows(MnemicException.class,
					() -> e.correctEventType("co_created", Map.of("remove", true), "not needed"));
			assertThrows(MnemicException.class, () -> e.correctEventType("joined", Map.of("remove", true), "no"));
			// Once the event is retyped with a role, the workaround type is used by nothing and goes.
			e.correctEvent(ev.id(), Map.of("type", "created", "roles", Map.of("Calle Wilund", "with")), "roles");
			Map<String, Object> out = e.correctEventType("co_created", Map.of("remove", true), "unused");
			assertEquals(true, out.get("removed"));
			assertTrue(e.eventTypes().get("co_created").isEmpty());
			assertTrue(e.events().get(ev.id()).orElseThrow().rendering().contains("created JMAPI with Calle Wilund"));
		}
	}

	@Test
	void anEntityTypeNothingRestsOnIsRemoved() {
		try (Engine e = engine("remove-entity-type")) {
			remember(e, "Coff-E is a robot.", proposal().entity("c", "Coff-E", "robot").fact("self", "owns", "c"));
			assertTrue(e.entityTypes().get("robot").isPresent());
			// Typed by an entity: refused. A seed: refused.
			assertThrows(MnemicException.class,
					() -> e.correctEntityType("robot", Map.of("remove", true), "not needed"));
			assertThrows(MnemicException.class, () -> e.correctEntityType("person", Map.of("remove", true), "no"));
			Entity coffe = e.entities().byRef("Coff-E").orElseThrow();
			e.correctEntity(coffe.id(), Map.of("type", "thing"), "a thing after all");
			Map<String, Object> out = e.correctEntityType("robot", Map.of("remove", true), "unused");
			assertEquals(true, out.get("removed"));
			assertTrue(e.entityTypes().get("robot").isEmpty());
		}
	}
}
