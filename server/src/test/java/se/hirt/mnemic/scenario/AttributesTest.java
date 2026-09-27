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
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Predicate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * A thing's own values (colour, size, plate) go in its entity's {@code attributes} and become literal facts: a model
 * that wrote a ring's colour as a fact to "Silver" had made an entity of type unknown out of a colour (2026-09-27).
 */
class AttributesTest {

	@Test
	void anAttributeIsAValueNotAThing() {
		try (Engine e = engine("attributes-literal")) {
			RememberOutcome o = remember(e, "I own an Oura Ring 4, silver, size 13.",
					proposal().entity("r", "Oura Ring 4", "product", Map.of("color", "Silver", "size", "13"))
							.fact("self", "owns", "r"));
			assertEquals(List.of(), o.applied().questions(), o.applied().toString());
			assertEquals(3, o.applied().facts().size(), o.applied().toString());
			// No entity for the colour or the size.
			assertTrue(e.entities().byRef("Silver").isEmpty(), "a colour is not a thing");
			assertTrue(e.entities().byRef("13").isEmpty(), "a size is not a thing");
			// The predicates registered from this use take literals and last, and stay listed as inferred until
			// someone describes them.
			Predicate color = e.predicates().get("color").orElseThrow();
			assertTrue(color.literalRange(), color.toString());
			assertTrue(color.lasting(), color.toString());
			assertTrue(color.isInferred(), "still open to a definition");
			assertTrue(e.predicates().get("size").orElseThrow().literalRange());
			// The facts carry the values as text, and the entity's card shows them as attributes.
			long ring = e.entities().byRef("Oura Ring 4").orElseThrow().id();
			List<Fact> facts = e.facts().factsOf(ring);
			assertTrue(facts.stream().anyMatch(f -> "color".equals(f.predicate()) && "Silver".equals(f.objectText())),
					facts.toString());
			assertTrue(facts.stream().anyMatch(f -> "size".equals(f.predicate()) && "13".equals(f.objectText())),
					facts.toString());
			assertTrue(
					e.predicates().changes("color").stream()
							.anyMatch(c -> "range".equals(c.get("field"))
									&& String.valueOf(c.get("reason")).contains("attribute")),
					"the change is logged: " + e.predicates().changes("color"));
			// A later plain fact on the same predicate takes a literal too.
			RememberOutcome again = remember(e, "The Polestar is grey.",
					proposal().entity("p", "Polestar 4", "vehicle").fact("p", "color", "Grey"));
			assertEquals(1, again.applied().facts().size(), again.applied().toString());
			assertTrue(e.entities().byRef("Grey").isEmpty());
		}
	}

	@Test
	void anAttributeOnAPredicateThatTakesAThingIsUsedAsDefinedAndTheCallerTold() {
		try (Engine e = engine("attributes-defined")) {
			// 'knows' takes a person: the shorthand does not turn it into a literal predicate.
			RememberOutcome o = remember(e, "Anna knows Erik.",
					proposal().entity("a", "Anna Lindqvist", "person", Map.of("knows", "Erik Nyberg")));
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("takes") && w.contains("knows")),
					o.applied().warnings().toString());
			assertFalse(e.predicates().get("knows").orElseThrow().literalRange());
		}
	}

	@Test
	void aPredicateAlreadyHoldingThingsIsNotTurnedIntoAValue() {
		try (Engine e = engine("attributes-holding")) {
			// 'sponsor' registered from a plain fact to an organization; an attribute later cannot re-range it under
			// the facts already stored.
			remember(e, "Hooli sponsors the club.", proposal().entity("h", "Hooli", "organization")
					.entity("c", "BC Arth-Goldau", "organization").fact("h", "sponsor", "c"));
			RememberOutcome o = remember(e, "The club's sponsor is Initrode.",
					proposal().entity("c", "BC Arth-Goldau", "organization", Map.of("sponsor", "Initrode")));
			assertFalse(e.predicates().get("sponsor").orElseThrow().literalRange());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("sponsor")),
					o.applied().warnings().toString());
		}
	}
}
