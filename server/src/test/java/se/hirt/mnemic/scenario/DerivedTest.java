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

import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.observation.Source;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/** EVALUATION.md family K: derived predicates, rules walked over the facts and materialised as facts of their own. */
class DerivedTest {

	/** grandparent_of, as a caller would define it: the specific rules first, the general one last. */
	private static PredicateDef grandparent() {
		return new PredicateDef("grandparent_of", "Subject is a parent of a parent of object.", "person", "person",
				false, null, false, null, "low", List.of("grandparent", "grandmother", "grandfather"),
				"{subject} is {object}'s {qualifier|grandparent}", List.of("grandmother", "grandfather"), List.of(),
				null,
				List.of(Map.of("path", List.of("parent_of[mother]", "parent_of"), "qualifier", "grandmother"),
						Map.of("path", List.of("parent_of[father]", "parent_of"), "qualifier", "grandfather"),
						Map.of("path", List.of("parent_of", "parent_of"))));
	}

	private static List<Fact> derived(Engine e, String predicate) {
		return e.facts().factsOf(e.entities().owner().id()).stream()
				.filter(f -> predicate.equals(f.predicate()) && f.current()).toList();
	}

	@Test
	@Scenario("K1")
	void aRuleDerivesAFactAndItIsRecallable() {
		try (Engine e = TestHomes.engine("k1-grandparent")) {
			RememberOutcome def = remember(e, "A grandparent is a parent of a parent.",
					proposal().predicate(grandparent()));
			// The seed knows grandparent_of; the test's rules replace the seed's, and the rest of the definition is
			// left alone with a word about it.
			assertTrue(def.applied().warnings().stream().allMatch(w -> w.contains("only implies and defined_as")),
					def.applied().warnings().toString());
			assertEquals(3, e.predicates().rulesOf("grandparent_of").size());
			remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			RememberOutcome o = remember(e, "Konrad's mother was Astrid.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null)));
			assertTrue(o.derived().derived() >= 1, o.derived().toString());
			List<Fact> g = derived(e, "grandparent_of");
			assertEquals(1, g.size());
			assertEquals("Astrid Nyberg is Mattias Sandell's grandmother", g.getFirst().rendering(),
					"the seed rules know the side");
			assertEquals("derived", g.getFirst().derivationKind());
			Map<String, Object> by = e.deriver().derivationOf(g.getFirst().id());
			assertEquals("base", by.get("kind"));
			assertEquals(2, ((List<?>) by.get("base")).size(), "both parent facts: " + by);
			RecallResult r = recall(e, "who is Mattias's grandmother");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Astrid Nyberg is Mattias Sandell's grandmother"), r.text());
			// The derived row is not counted as stated, and not homed on the observation as its own.
			assertEquals(2, e.facts().count());
			assertEquals(1, derived(e, "grandparent_of").size());
			assertEquals(1, e.facts().factsOfObservation(o.observation().observationId()).size());
		}
	}

	@Test
	@Scenario("K2")
	void aDerivedFactIsInvalidatedWhenABaseFactChanges() {
		try (Engine e = TestHomes.engine("k2-invalidated")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			RememberOutcome o = remember(e, "Konrad's mother was Astrid.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null)));
			long astridFact = Long.parseLong(o.applied().facts().getFirst().id().substring(2));
			Fact before = derived(e, "grandparent_of").getFirst();
			e.correct(astridFact, Map.of("subject", "Signe Nyberg"), "wrong name");
			Fact gone = e.facts().get(before.id()).orElseThrow();
			assertEquals("invalidated", gone.status(), "not corrected: the user never said it");
			List<Fact> now = derived(e, "grandparent_of");
			assertEquals(1, now.size());
			assertEquals("Signe Nyberg is Mattias Sandell's grandmother", now.getFirst().rendering());
			String history = e.database()
					.read(tx -> tx.query("SELECT kind, reason FROM supersession WHERE fact_id = ?", before.id()))
					.stream().map(r -> r.str("kind") + ": " + r.str("reason")).findFirst().orElse("");
			assertTrue(history.startsWith("invalidation: a base fact changed: f-" + astridFact + " (corrected)"),
					history);
			RecallResult r = recall(e, "who is Mattias's grandmother");
			assertTrue(
					r.structured().facts().stream().anyMatch(f -> f.rendering().contains("Signe"))
							&& r.structured().facts().stream().noneMatch(f -> f.rendering().contains("Astrid")),
					r.text());
			// Forgetting the base observation takes the derivation with it; nothing is left behind.
			e.forget(o.observation().observationId());
			assertTrue(derived(e, "grandparent_of").isEmpty());
		}
	}

	@Test
	@Scenario("K3")
	void anAssertedDerivedRelationWithUnknownSideIsStoredUnresolved() {
		try (Engine e = TestHomes.engine("k3-unresolved")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			RememberOutcome o = remember(e, "Signe is my grandmother.",
					proposal().entity("e1", "Signe Nyberg", "person").fact(
							fact("e1", "grandparent_of", "self", "grandmother", null, null, null, null, null, null)));
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			assertEquals(1, o.applied().facts().size());
			Fact f = derived(e, "grandparent_of").getFirst();
			assertEquals("explicit", f.derivationKind(), "asserted, not derived");
			assertEquals("unresolved", e.deriver().unificationOf(f));
			assertTrue(e.deriver().derivationOf(f.id()).isEmpty());
			assertTrue(derived(e, "grandparent_of").stream().noneMatch(x -> "derived".equals(x.derivationKind())),
					"no intermediate parent is invented");
			assertEquals(List.of("Signe Nyberg"), e.entities().active(10).stream().map(x -> x.name()).toList(),
					"Signe and the owner, nobody in between");
			RecallResult r = recall(e, "who is Mattias's grandmother");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Signe Nyberg is Mattias Sandell's grandmother"), r.text());
		}
	}

	@Test
	@Scenario("K4")
	void aPartialConstraintIsKept() {
		try (Engine e = TestHomes.engine("k4-partial")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			RememberOutcome o = remember(e, "Signe is my paternal grandmother.",
					proposal().entity("e1", "Signe Nyberg", "person").fact(fact("e1", "grandparent_of", "self",
							"paternal grandmother", null, null, null, null, null, null)));
			Fact f = e.facts().get(Long.parseLong(o.applied().facts().getFirst().id().substring(2))).orElseThrow();
			assertEquals("paternal grandmother", f.qualifier());
			assertEquals("Signe Nyberg is Mattias Sandell's paternal grandmother", f.rendering());
			assertEquals("unresolved", e.deriver().unificationOf(f));
		}
	}

	@Test
	@Scenario("K5")
	void unificationCorroboratesWithoutReplacing() {
		try (Engine e = TestHomes.engine("k5-unify")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			RememberOutcome stated = remember(e, "Signe is my grandmother.",
					proposal().entity("e1", "Signe Nyberg", "person").fact(
							fact("e1", "grandparent_of", "self", "grandmother", null, null, null, null, null, null)));
			long asserted = Long.parseLong(stated.applied().facts().getFirst().id().substring(2));
			remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			RememberOutcome o = remember(e, "Konrad's mother is Signe.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Signe Nyberg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null)));
			assertEquals(1, o.derived().corroborated(), o.derived().toString());
			assertTrue(derived(e, "grandparent_of").stream().noneMatch(x -> "derived".equals(x.derivationKind())),
					"the asserted fact is the row; no second one");
			Fact f = e.facts().get(asserted).orElseThrow();
			assertEquals("current", f.status());
			assertEquals("explicit", f.derivationKind(), "stays asserted");
			assertEquals("corroborated", e.deriver().unificationOf(f));
			Map<String, Object> by = e.deriver().derivationOf(asserted);
			assertEquals("corroborates", by.get("kind"));
			assertEquals(2, ((List<?>) by.get("base")).size(), by.toString());
			RecallResult r = recall(e, "who is Mattias's grandmother");
			assertEquals(1, r.structured().facts().size(), "one result, not two: " + r.text());
			assertTrue(e.consolidate(true).unresolvedDerivations().isEmpty());
		}
	}

	@Test
	@Scenario("K6")
	void aCompleteContradictingChainRaisesAConflict() {
		try (Engine e = TestHomes.engine("k6-contradiction")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			remember(e, "My father is Konrad and my mother is Gunilla.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "self", "mother", null, null, null, null, null, null)));
			remember(e, "Konrad's mother is Astrid. Gunilla's mother is Anna.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.entity("e3", "Gunilla Nyberg", "person").entity("e4", "Anna Berg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null))
							.fact(fact("e4", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			RememberOutcome stated = remember(e, "Signe is my grandmother.",
					proposal().entity("e1", "Signe Nyberg", "person").fact(
							fact("e1", "grandparent_of", "self", "grandmother", null, null, null, null, null, null)));
			long asserted = Long.parseLong(stated.applied().facts().getFirst().id().substring(2));
			assertEquals("current", e.facts().get(asserted).orElseThrow().status(), "stored regardless");
			var c = e.consolidate(false);
			assertEquals(1, c.unresolvedDerivations().size(), c.unresolvedDerivations().toString());
			Map<String, Object> u = c.unresolvedDerivations().getFirst();
			assertEquals("complete", u.get("chain"));
			assertEquals(2, ((List<?>) u.get("derived")).size(), "both grandmothers: " + u);
			String q = (String) u.get("question");
			assertTrue(q != null && q.startsWith("q-"), u.toString());
			var question = e.questions().require(q);
			assertEquals("derivation", question.kind());
			assertTrue(question.message().contains("Astrid") && question.message().contains("Anna"),
					question.message());
			// Asked once: a second run names the same question.
			assertEquals(q, e.consolidate(false).unresolvedDerivations().getFirst().get("question"));
			e.answer(List.of(new se.hirt.mnemic.knowledge.QuestionResolver.Resolve(q, "wrong")));
			assertEquals("rejected", e.facts().get(asserted).orElseThrow().status());
			assertTrue(e.consolidate(true).unresolvedDerivations().isEmpty(), "settled");
		}
	}

	@Test
	@Scenario("K7")
	void anIncompleteChainDoesNotRaiseAConflict() {
		try (Engine e = TestHomes.engine("k7-incomplete")) {
			remember(e, "A grandparent is a parent of a parent.", proposal().predicate(grandparent()));
			remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			remember(e, "Signe is my grandmother.", proposal().entity("e1", "Signe Nyberg", "person")
					.fact(fact("e1", "grandparent_of", "self", "grandmother", null, null, null, null, null, null)));
			var c = e.consolidate(false);
			assertEquals(1, c.unresolvedDerivations().size());
			Map<String, Object> u = c.unresolvedDerivations().getFirst();
			assertEquals("incomplete", u.get("chain"), "Konrad's mother is unknown: Signe may still fit");
			assertNull(u.get("question"));
			assertEquals(0, e.questions().openCount());
			// With Konrad's mother known as Astrid the paternal chain is complete, but the maternal rule finds no
			// mother of Mattias: still incomplete, still nothing asked.
			remember(e, "Konrad's mother is Astrid.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null)));
			u = e.consolidate(false).unresolvedDerivations().getFirst();
			// Under the test's rules the stated "grandmother" names one rule, whose chain now continues to Astrid:
			// complete, and asked.
			assertEquals("complete", u.get("chain"), u.toString());
			assertTrue(u.get("question") != null, u.toString());
			assertEquals(1,
					derived(e, "grandparent_of").stream().filter(x -> "derived".equals(x.derivationKind())).count(),
					"Astrid, derived as the paternal grandmother");
		}
	}

	@Test
	@Scenario("K8")
	void aRuleIntersectsValidTime() {
		try (Engine e = TestHomes.engine("k8-coworker")) {
			RememberOutcome def = remember(e, "Coworkers work at the same place at the same time.",
					proposal().predicate(new PredicateDef("coworker_of",
							"Subject and object work at the same organization at the same time.", "person", "person",
							false, null, true, null, "medium", List.of("coworker", "coworkers"),
							"{subject} is a coworker of {object}", List.of(), List.of(), null,
							List.of(Map.of("path", List.of("works_at", "^works_at"))))));
			assertTrue(def.applied().warnings().isEmpty(), def.applied().warnings().toString());
			remember(e, "I worked at Initrode from 2010 to 2018.", proposal().entity("e1", "Initrode", "organization")
					.fact(fact("self", "works_at", "e1", null, null, "2010", "2018", null, null, null)));
			RememberOutcome o = remember(e, "Anna worked at Initrode from 2015 to 2020.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Initrode", "organization")
							.fact(fact("e1", "works_at", "e2", null, null, "2015", "2020", null, null, null)));
			assertTrue(o.derived().derived() >= 1, o.derived().toString());
			Fact c = derived(e, "coworker_of").getFirst();
			assertEquals("2015-01-01", c.validStart());
			assertEquals("2018-01-01", c.validEnd());
			assertTrue(c.ended());
			assertEquals("derived", c.startSource());
			assertTrue(c.rendering().contains("2015") && c.rendering().contains("2018"), c.rendering());
			// Stored once for a symmetric predicate, whichever side the walk began on.
			assertEquals(1, derived(e, "coworker_of").size());
			RecallResult now = recall(e, "who are Mattias's coworkers");
			assertEquals("miss", now.structured().state(), now.text());
			assertTrue(
					now.text().contains("1 ended fact: Mattias Sandell is a coworker of Anna Lindqvist (2015 – 2018)"),
					now.text());
			RecallResult r = e.recall().recall("who were Mattias's coworkers", null, 800, 10, true);
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Anna Lindqvist"), r.text());
		}
	}

	@Test
	@Scenario("K9")
	void unboundedRecursionIsRefused() {
		try (Engine e = TestHomes.engine("k9-recursion")) {
			MnemicException bad = assertThrows(MnemicException.class,
					() -> remember(e, "An ancestor is a parent, or a parent of an ancestor.",
							proposal().predicate(new PredicateDef("ancestor_of", "Subject is an ancestor of object.",
									"person", "person", false, null, false, null, "low",
									List.of("ancestor", "ancestors"), "{subject} is an ancestor of {object}", List.of(),
									List.of(), null, List.of(Map.of("path", List.of("parent_of+")))))));
			assertTrue(bad.getMessage().contains("unbounded repetition 'parent_of+'"), bad.getMessage());
			assertTrue(e.predicates().get("ancestor_of").isEmpty(), "not registered");
			assertTrue(bad.getMessage().contains("vocabulary only"),
					"and the observation with it: " + bad.getMessage());
			// A rule that names its own predicate is recursion too.
			MnemicException self = assertThrows(MnemicException.class,
					() -> remember(e, "An ancestor is a parent of an ancestor.",
							proposal().predicate(new PredicateDef("ancestor_of", null, null, null, null, null, null,
									null, null, List.of(), null, List.of(), List.of(), null,
									List.of(Map.of("path", List.of("parent_of", "ancestor_of")))))));
			assertTrue(self.getMessage().contains("names itself"), self.getMessage());
			// Bounded, it is accepted and walked to the bound.
			RememberOutcome ok = remember(e, "An ancestor is a parent, up to three generations back.",
					proposal().predicate(new PredicateDef("ancestor_of", "Subject is an ancestor of object.", "person",
							"person", false, null, false, null, "low", List.of("ancestor", "ancestors"),
							"{subject} is an ancestor of {object}", List.of(), List.of(), null,
							List.of(Map.of("path", List.of("parent_of{1,3}"))))));
			assertTrue(ok.applied().warnings().isEmpty(), ok.applied().warnings().toString());
			assertEquals(3, e.predicates().rulesOf("ancestor_of").size());
			remember(e, "My father is Konrad. Konrad's mother was Astrid. Astrid's father was Nils.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.entity("e3", "Nils Berg", "person")
							.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null))
							.fact(fact("e3", "parent_of", "e2", "father", null, null, null, null, null, null)));
			long ancestors = e.database().read(tx -> tx
					.queryLong("SELECT COUNT(*) FROM fact WHERE predicate = 'ancestor_of' AND status = 'current'"));
			assertEquals(6, ancestors, "three ancestors of Mattias, two of Konrad, one of Astrid");
			assertEquals(3, derived(e, "ancestor_of").size());
		}
	}

	@Test
	@Scenario("K10")
	void aRuleIsTypeChecked() {
		try (Engine e = TestHomes.engine("k10-typecheck")) {
			MnemicException bad = assertThrows(MnemicException.class,
					() -> remember(e, "A grand employer employs a parent.",
							proposal().predicate(new PredicateDef("grand_employer_of", "Nonsense.", "organization",
									"person", false, null, false, null, "low", List.of("grand employer"),
									"{subject} is the grand employer of {object}", List.of(), List.of(), null,
									List.of(Map.of("path", List.of("works_at", "parent_of")))))));
			assertTrue(bad.getMessage().contains("the range of 'works_at' (organization) does not fit the domain of "),
					bad.getMessage());
			assertTrue(e.predicates().get("grand_employer_of").isEmpty());
			// Walked backwards, the same predicate fits: the parents of one's colleagues.
			RememberOutcome ok = remember(e, "Colleagues' parents.",
					proposal().predicate(new PredicateDef("colleague_parent_of",
							"Subject is a parent of a " + "colleague of object.", "person", "person", false, null,
							false, null, "low", List.of("colleague parent"),
							"{subject} is a parent of a colleague of {object}", List.of(), List.of(), null,
							List.of(Map.of("path", List.of("parent_of", "works_at", "^works_at"))))));
			assertTrue(ok.applied().warnings().isEmpty(), ok.applied().warnings().toString());
			// A hop into a literal cannot be walked on.
			MnemicException literal = assertThrows(MnemicException.class, () -> remember(e, "Role holders' parents.",
					proposal().predicate(new PredicateDef("role_parent_of", "Nonsense.", "person", "person", false,
							null, false, null, "low", List.of("role parent"), "{subject} rp {object}", List.of(),
							List.of(), null, List.of(Map.of("path", List.of("holds_role", "parent_of")))))));
			assertTrue(literal.getMessage().contains("literal"), literal.getMessage());
			assertNull(e.predicates().get("role_parent_of").orElse(null));
			assertFalse(e.predicates().derived().isEmpty());
		}
	}

	private static void parents(Engine e) {
		remember(e, "My father is Konrad and my mother is Gunilla.",
				proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
						.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
						.fact(fact("e2", "parent_of", "self", "mother", null, null, null, null, null, null)));
	}

	private static long id(RememberOutcome o) {
		return Long.parseLong(o.applied().facts().getFirst().id().substring(2));
	}

	@Test
	@Scenario("K18")
	void aStatedRelationADerivationCoversIsListedAndRetired() {
		try (Engine e = TestHomes.engine("k18-shadow")) {
			parents(e);
			remember(e, "Britt is Konrad's sister.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "sibling_of", "e2", "sister", null, null, null, null, null, null)));
			RememberOutcome stated = remember(e, "Britt is my aunt.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("self", "related_to", "e1", "aunt", null, null, null, null, null, null)));
			long statedId = id(stated);
			var misfiled = e.consolidate(true).misfiledRelations();
			assertEquals(1, misfiled.size(), misfiled.toString());
			Map<String, Object> m = misfiled.getFirst();
			assertEquals("f-" + statedId, m.get("fact"));
			assertEquals("aunt_uncle_of", m.get("predicate"));
			assertNotNull(m.get("derived"), "the derived aunt covers it: " + m);
			assertTrue(String.valueOf(m.get("hint")).contains("\"redundant\": true"), m.toString());
			// Retired as redundant: superseded by the derived fact, not withdrawn as never true.
			Corrected c = e.correct(statedId, Map.of("redundant", true), "the chain covers it");
			assertEquals("superseded", c.original().status());
			assertEquals("aunt_uncle_of", c.replacement().predicate());
			assertEquals(c.replacement().id(), c.original().supersededBy());
			String history = e.database()
					.read(tx -> tx.query("SELECT reason FROM supersession WHERE fact_id = ?", statedId)).stream()
					.map(r -> r.str("reason")).findFirst().orElse("");
			assertTrue(history.startsWith("retired: covered by derived f-"), history);
			assertTrue(e.consolidate(true).misfiledRelations().isEmpty());
			assertTrue(e.consolidate(true).unresolvedDerivations().stream()
					.noneMatch(u -> ("f-" + statedId).equals(u.get("fact"))), "retired: no longer unresolved");
			assertEquals(0, e.questions().openCount(), "no derivation question either");
			// A rebuild replays the retirement.
			e.consolidate(false, List.of(), true);
			assertTrue(e.facts().factsOf(e.entities().owner().id()).stream()
					.noneMatch(f -> "related_to".equals(f.predicate()) && f.current()), "still retired after rebuild");
			assertTrue(e.facts().factsOf(e.entities().owner().id()).stream()
					.anyMatch(f -> "aunt_uncle_of".equals(f.predicate()) && f.current()));
		}
	}

	@Test
	@Scenario("K19")
	void aDerivedFactCitesEveryObservationBehindIt() {
		try (Engine e = TestHomes.engine("k19-provenance")) {
			RememberOutcome a = remember(e, "My father is Konrad.", proposal().entity("e1", "Konrad Nyberg", "person")
					.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null)));
			RememberOutcome b = remember(e, "Konrad's mother was Astrid.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Astrid Nyberg", "person")
							.fact(fact("e2", "parent_of", "e1", "mother", null, null, null, null, null, null)));
			Fact g = derived(e, "grandparent_of").getFirst();
			List<Long> sources = e.facts().observationsOf(g.id());
			assertTrue(sources.contains(a.observation().observationId())
					&& sources.contains(b.observation().observationId()), "both: " + sources);
			e.forget(a.observation().observationId());
			assertTrue(derived(e, "grandparent_of").isEmpty(), "forgetting either base observation takes it away");
		}
	}

	@Test
	@Scenario("K20")
	void aGenderGivenOutrightNamesTheRole() {
		try (Engine e = TestHomes.engine("k20-gender")) {
			parents(e);
			// The shorthand on the entity entry becomes a gender fact of the observation, with provenance.
			RememberOutcome o = remember(e, "Britt is Konrad's sibling.",
					proposal().entity(new EntityRef("e1", "Britt Nyberg", "person", List.of(), "female"))
							.entity("e2", "Konrad Nyberg", "person")
							.fact(fact("e1", "sibling_of", "e2", null, null, null, null, null, null, null)));
			long britt = e.entities().byRef("Britt Nyberg").orElseThrow().id();
			Fact g = e.facts().factsOf(britt).stream().filter(f -> "gender".equals(f.predicate())).findFirst()
					.orElseThrow();
			assertEquals("Britt Nyberg is female", g.rendering());
			assertEquals(o.observation().observationId(), g.observationId());
			assertTrue(o.derived().derived() >= 1, o.derived().toString());
			assertTrue(derived(e, "aunt_uncle_of").getFirst().rendering().endsWith("is Mattias Sandell's aunt"),
					"no role names her, the stated gender does: " + derived(e, "aunt_uncle_of"));
			// Corrected like any fact, the derivations follow; withdrawn, they go neutral again.
			e.correct(g.id(), Map.of("object", "male"), "as it turns out");
			assertTrue(derived(e, "aunt_uncle_of").getFirst().rendering().endsWith("is Mattias Sandell's uncle"),
					derived(e, "aunt_uncle_of").toString());
			Fact now = e.facts().factsOf(britt).stream().filter(f -> "gender".equals(f.predicate()) && f.current())
					.findFirst().orElseThrow();
			e.correct(now.id(), Map.of("wrong", true), "unknown after all");
			assertTrue(
					derived(e, "aunt_uncle_of").getFirst().rendering().endsWith("is Mattias Sandell's aunt or uncle"),
					derived(e, "aunt_uncle_of").toString());
			// Stated as a fact outright, the same; and the gap is listed while it is open.
			assertEquals(1, e.consolidate(true).attributeUnknown().size(),
					e.consolidate(true).attributeUnknown().toString());
			assertEquals("Britt Nyberg", e.consolidate(true).attributeUnknown().getFirst().get("name"));
			assertEquals("gender", e.consolidate(true).attributeUnknown().getFirst().get("attribute"));
			remember(e, "Britt is a woman.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("e1", "gender", "woman", null, null, null, null, null, null, null)));
			assertTrue(derived(e, "aunt_uncle_of").getFirst().rendering().endsWith("is Mattias Sandell's aunt"),
					derived(e, "aunt_uncle_of").toString());
			assertTrue(e.consolidate(true).attributeUnknown().isEmpty());
		}
	}

	@Test
	@Scenario("K22")
	void aFactMovesToAnotherPredicateAndUnknownKeysAreRefused() {
		try (Engine e = TestHomes.engine("k22-migrate")) {
			RememberOutcome o = remember(e, "Katja was my partner until 2019.",
					proposal().entity("e1", "Katja Berg", "person")
							.fact(fact("self", "related_to", "e1", "partner", null, null, "2019", null, null, null)));
			long id = id(o);
			var misfiled = e.consolidate(true).misfiledRelations();
			assertEquals("partner_of", misfiled.getFirst().get("predicate"), misfiled.toString());
			assertNull(misfiled.getFirst().get("derived"));
			MnemicException unknown = assertThrows(MnemicException.class,
					() -> e.correct(id, Map.of("relation", "partner_of"), "typo"));
			assertTrue(unknown.getMessage().contains("Unknown fact property 'relation'"), unknown.getMessage());
			assertTrue(unknown.getMessage().contains("predicate"), "the correctable keys are listed");
			MnemicException missing = assertThrows(MnemicException.class,
					() -> e.correct(id, Map.of("predicate", "paramour_of"), "no such"));
			assertTrue(missing.getMessage().contains("No predicate 'paramour_of'"), missing.getMessage());
			Corrected c = e.correct(id, Map.of("predicate", "partner_of"), "its own predicate now");
			assertEquals("corrected", c.original().status());
			assertEquals("partner_of", c.replacement().predicate());
			assertEquals("2019-01-01", c.replacement().validEnd(), "the interval travels with it");
			assertEquals("Katja Berg is Mattias Sandell's partner (until 2019)".replace(
					"Katja Berg is Mattias Sandell's", "Mattias Sandell is Katja Berg's"), c.replacement().rendering());
			assertTrue(e.consolidate(true).misfiledRelations().isEmpty());
		}
	}

	@Test
	@Scenario("K24")
	void aCorrectionThatChangesNothingIsNeverRecordedAndAnOldOneReplaysAsNothing() {
		try (Engine e = TestHomes.engine("k24-noop")) {
			RememberOutcome o = remember(e, "Katja was my partner until 2019.",
					proposal().entity("e1", "Katja Berg", "person")
							.fact(fact("self", "partner_of", "e1", "partner", null, null, "2019", null, null, null)));
			long id = id(o);
			long observations = e.observations().count();
			MnemicException noop = assertThrows(MnemicException.class,
					() -> e.correct(id, Map.of("qualifier", "partner"), "as it is"));
			assertTrue(noop.getMessage().contains("changes nothing"), noop.getMessage());
			assertEquals(observations, e.observations().count(), "the refused correction left no record");
			MnemicException missing = assertThrows(MnemicException.class,
					() -> e.correct(id, Map.of("predicate", "paramour_of"), "no such"));
			assertTrue(missing.getMessage().contains("No predicate"), missing.getMessage());
			assertEquals(observations, e.observations().count(), "nor did the one naming no predicate");
			// A record an older release kept for a correction that changed nothing: replayed as nothing.
			Fact f = e.facts().get(id).orElseThrow();
			Map<String, Object> reading = e.factService().reading("corrects", f, "it was the same",
					List.of(e.factService().readingOf(f, Map.of())));
			e.observations().remember("Correction of " + f.rendering() + " → {}",
					new Source("correction", f.ref(), null, null, null), e.clock().instant(), Json.write(reading), null,
					null);
			long facts = e.facts().count();
			Engine.Rebuilt r = e.consolidate(false, List.of(), true).rebuilt();
			assertEquals(1, r.noOps().size(), "named, not replayed: " + r);
			assertEquals(0, r.corrections());
			assertTrue(r.unmatched().isEmpty(), r.unmatched().toString());
			assertEquals(facts, e.facts().count(), "no duplicate fact from the replay");
			assertEquals(1, e.facts().factsOf(e.entities().owner().id()).stream()
					.filter(x -> "partner_of".equals(x.predicate()) && x.current()).count());
		}
	}
}
