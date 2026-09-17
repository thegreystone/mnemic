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
import se.hirt.mnemic.protocol.MnemicException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule language of derived predicates (EVALUATION.md family K): what a hop means, what is refused. */
class RuleTest {

	@Test
	void aHopNamesAPredicateWithDirectionAndQualifier() {
		List<Rule> rules = Rule.parse(List
				.of(Map.of("path", List.of("^parent_of[mother]", "parent_of"), "qualifier", "maternal grandchild")));
		assertEquals(1, rules.size());
		Rule r = rules.getFirst();
		assertEquals(2, r.path().size());
		assertTrue(r.path().getFirst().inverse());
		assertEquals("parent_of", r.path().getFirst().predicate());
		assertEquals("mother", r.path().getFirst().qualifier());
		assertFalse(r.path().getLast().inverse());
		assertNull(r.path().getLast().qualifier());
		assertEquals("maternal grandchild", r.qualifier());
		assertEquals("^parent_of[mother], parent_of", r.toString());
	}

	@Test
	void aPathMayBeWrittenAsAString() {
		List<Rule> rules = Rule.parse("parent_of, parent_of");
		assertEquals(List.of("parent_of", "parent_of"), rules.getFirst().toMap().get("path"));
		assertNull(rules.getFirst().qualifier());
	}

	@Test
	void boundedRepetitionExpandsToOneRulePerLength() {
		List<Rule> rules = Rule.parse(Map.of("path", List.of("parent_of{1,3}")));
		assertEquals(3, rules.size());
		assertEquals(1, rules.get(0).path().size());
		assertEquals(3, rules.get(2).path().size());
		List<Rule> mixed = Rule.parse(Map.of("path", List.of("spouse_of", "parent_of{1,2}")));
		assertEquals(2, mixed.size());
		assertEquals("spouse_of, parent_of, parent_of", mixed.get(1).toString());
	}

	@Test
	void unboundedRepetitionIsRefused() {
		MnemicException plus = assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("parent_of+"))));
		assertTrue(plus.getMessage().contains("unbounded repetition 'parent_of+'"), plus.getMessage());
		assertTrue(plus.getMessage().contains("parent_of{1,4}"), "names the bounded form: " + plus.getMessage());
		assertThrows(MnemicException.class, () -> Rule.parse(Map.of("path", List.of("parent_of*"))));
		assertThrows(MnemicException.class, () -> Rule.parse(Map.of("path", List.of("parent_of{1,9}"))),
				"beyond the longest path");
		assertThrows(MnemicException.class, () -> Rule.parse(Map.of("path", List.of("parent_of{3,2}"))));
	}

	@Test
	void malformedRulesAreRefusedWithTheShapeNamed() {
		assertTrue(assertThrows(MnemicException.class, () -> Rule.parse(Map.of("qualifier", "x"))).getMessage()
				.contains("needs a 'path'"));
		assertTrue(assertThrows(MnemicException.class, () -> Rule.parse(Map.of("path", List.of("parent of"))))
				.getMessage().contains("is not a hop"));
		assertTrue(
				assertThrows(MnemicException.class, () -> Rule.parse(42)).getMessage().contains("takes a rule object"));
		assertTrue(assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("parent_of"), "min_paths", "two"))).getMessage()
				.contains("whole number"));
	}

	@Test
	void aRuleChoosesItsQualifierByAnyAttribute() {
		List<Rule> rules = Rule.parse(Map.of("path", List.of("trains_with"), "qualifier", "sparring partner", "by",
				Map.of("attribute", "Handedness", "values", Map.of("Left", "southpaw", "right", "orthodox"))));
		Rule r = rules.getFirst();
		assertEquals("handedness", r.attribute());
		assertEquals("southpaw", r.qualifierFor("left"));
		assertEquals("sparring partner", r.qualifierFor(null));
		assertEquals("sparring partner", r.qualifierFor("ambidextrous"), "a value the rule does not name");
		assertTrue(r.chosenByValue("orthodox"));
		assertFalse(r.chosenByValue("sparring partner"), "the plain qualifier is the one given for want of a value");
		Map<?, ?> by = (Map<?, ?>) r.toMap().get("by");
		assertEquals("handedness", by.get("attribute"));
		assertEquals(Map.of("left", "southpaw", "right", "orthodox"), by.get("values"));
		// The first spelling still parses, as the attribute 'gender'.
		Rule old = Rule.parse(Map.of("path", List.of("sibling_of", "parent_of"), "by_gender",
				Map.of("female", "aunt", "male", "uncle"))).getFirst();
		assertEquals("gender", old.attribute());
		assertEquals("aunt", old.qualifierFor("female"));
		assertEquals(r.toMap().keySet(), Rule.fromJson(Rule.toJson(rules)).getFirst().toMap().keySet());
		assertEquals(rules, Rule.fromJson(Rule.toJson(rules)));
	}

	@Test
	void aMalformedByIsRefused() {
		assertTrue(assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("trains_with"), "by", Map.of("values", Map.of("a", "b")))))
				.getMessage().contains("naming an attribute predicate"), "no attribute");
		assertTrue(assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("trains_with"), "by", Map.of("attribute", "handedness"))))
				.getMessage().contains("naming an attribute predicate"), "no values");
		assertTrue(
				assertThrows(MnemicException.class,
						() -> Rule.parse(Map.of("path", List.of("trains_with"), "by",
								Map.of("attribute", "handedness", "values", Map.of()))))
						.getMessage().contains("no values"));
		assertTrue(assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("trains_with"), "by", "handedness"))).getMessage()
				.contains("takes an object"));
		assertTrue(assertThrows(MnemicException.class,
				() -> Rule.parse(Map.of("path", List.of("trains_with"), "by_gender", "female"))).getMessage()
				.contains("takes an object"));
	}

	@Test
	void rulesRoundTripThroughTheirStoredForm() {
		List<Rule> rules = Rule.parse(
				List.of(Map.of("path", List.of("^parent_of", "parent_of"), "min_paths", 2, "qualifier", "sibling"),
						Map.of("path", List.of("spouse_of", "parent_of"), "not", List.of("parent_of"), "qualifier",
								"step-parent")));
		String json = Rule.toJson(rules);
		assertEquals(rules, Rule.fromJson(json));
		assertEquals(List.of(), Rule.fromJson(null));
		Map<String, Object> m = rules.get(1).toMap();
		assertEquals(List.of("spouse_of", "parent_of"), m.get("path"));
		assertEquals(List.of("parent_of"), m.get("not"));
		assertEquals("step-parent", m.get("qualifier"));
		assertFalse(m.containsKey("min_paths"));
	}
}
