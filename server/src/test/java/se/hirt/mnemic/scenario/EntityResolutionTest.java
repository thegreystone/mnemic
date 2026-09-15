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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section B: entity resolution, the M1 ladder (owner, exact alias, type compatibility). */
class EntityResolutionTest {

	@Test
	@Scenario("B1")
	void aliasAndCaseVariantsResolveToOneEntity() {
		try (Engine e = engine("b1")) {
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			remember(e, "HooLi moved me to the platform org.",
					proposal().entity("e1", "HooLi", "organization").fact("self", "works_at", "e1"));
			remember(e, "HLI is headquartered in New York.", proposal().entity("e1", "HLI", "organization", "Hooli")
					.entity("e2", "New York", "place").fact("e1", "located_in", "e2"));
			List<Entity> spotted = e.entities().spot("HLI");
			assertEquals(1, spotted.size());
			Entity hooliEntity = spotted.getFirst();
			assertEquals("Hooli", hooliEntity.name());
			assertEquals(hooliEntity.id(), e.entities().byRef("HooLi").orElseThrow().id(), "case variant");
			List<String> aliases = e.entities().aliases(hooliEntity.id());
			// Aliases are keyed on their normalised form, so "HooLi" folds into "Hooli" and still resolves.
			assertTrue(aliases.contains("HLI"), aliases.toString());
			assertEquals(1, e.entities().spot("hooli").size());
			assertEquals(1,
					e.facts().factsOf(hooliEntity.id()).stream().filter(f -> f.predicate().equals("works_at")).count(),
					"the repeated works_at corroborated, not duplicated");
			assertTrue(e.facts().factsOf(hooliEntity.id()).stream().anyMatch(f -> f.predicate().equals("located_in")));
		}
	}

	@Test
	@Scenario("B3")
	void sameNameDifferentTypeStaysSeparate() {
		try (Engine e = engine("b3")) {
			remember(e, "I'm reading Java Concurrency in Practice.",
					proposal().entity("e1", "Java Concurrency in Practice", "book").fact("self", "prefers", "e1"));
			remember(e, "Java is the language Mnemic is written in.", proposal().entity("e1", "Mnemic", "project")
					.entity("e2", "Java", "technology").fact("e1", "uses", "e2"));
			remember(e, "Java the island is beautiful.",
					proposal().entity("e1", "Java", "place").fact("self", "prefers", "e1"));
			Entity tech = e.entities().byRef("Java").orElseThrow();
			long javas = e.entities().spot("Java").size();
			assertEquals(2, javas, "technology and place both named Java, kept apart");
			assertNotEquals(tech.id(), e.entities().byRef("Java Concurrency in Practice").orElseThrow().id());
		}
	}

	@Test
	@Scenario("B4")
	void typeMismatchOverridesStringSimilarity() {
		try (Engine e = engine("b4")) {
			remember(e, "Viggo started school today.",
					proposal().entity("e1", "Viggo", "person").fact("self", "knows", "e1"));
			var o = remember(e, "We evaluated Viggo, a load-testing tool.",
					proposal().entity("e1", "Viggo", "technology").fact("self", "uses", "e1"));
			assertTrue(o.applied().questions().isEmpty(), "no question: types differ, so no merge and no doubt");
			List<Entity> viggos = e.entities().spot("Viggo");
			assertEquals(2, viggos.size(), viggos.toString());
			assertEquals("created", o.applied().entities().getFirst().resolution());
		}
	}

	@Test
	@Scenario("B5")
	void firstPersonResolvesToTheOwner() {
		try (Engine e = engine("b5")) {
			remember(e, "I live in Schübelbach.",
					proposal().entity("e1", "Schübelbach", "place").fact("self", "lives_in", "e1"));
			remember(e, "Mattias moved there in 2014.", proposal().entity("e1", "Mattias", "person")
					.entity("e2", "Schübelbach", "place").fact("e1", "lives_in", "e2"));
			Entity owner = e.entities().owner();
			assertEquals("Mattias Sandell", owner.name());
			List<String> aliases = e.entities().aliases(owner.id());
			assertTrue(aliases.containsAll(List.of("I", "me", "Mattias", "Mattias Sandell")), aliases.toString());
			assertEquals(1,
					e.facts().factsOf(owner.id()).stream().filter(f -> f.predicate().equals("lives_in")).count(),
					"one lives_in fact, corroborated");
			RecallResult r = recall(e, "where does Mattias Sandell live");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("lives_in", r.structured().predicate());
			assertEquals(owner.ref(), r.structured().entity());
		}
	}

	@Test
	void unknownTypeAdoptsTheTypeItLaterLearns() {
		try (Engine e = engine("type-promote")) {
			remember(e, "Anna is great.", proposal().fact("self", "knows", "Anna"));
			assertEquals("unknown", e.entities().byRef("Anna").orElseThrow().type());
			remember(e, "Anna Lindqvist is a person I know.",
					proposal().entity("e1", "Anna", "person").fact("self", "knows", "e1"));
			assertEquals("person", e.entities().byRef("Anna").orElseThrow().type());
			assertEquals(1, e.entities().spot("Anna").size());
		}
	}
}
