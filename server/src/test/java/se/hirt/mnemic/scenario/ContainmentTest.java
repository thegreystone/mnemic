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
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * EVALUATION.md Q14–Q17: containment is a property of a predicate and disjointness a property of a kind, so a caller
 * can nest things the seed never heard of and have bounds, closures, and questions work over them.
 */
class ContainmentTest {

	private static PredicateDef containment(
		String name, String description, String domain, String range, List<String> lexicon, String render) {
		return new PredicateDef(name, description, domain, range, false, null, false, null, "low", lexicon, render,
				List.of(), List.of(), null, null, null, true);
	}

	private static FactRef only(String subject, String predicate, String object) {
		return new FactRef(subject, predicate, object, null, null, null, null, List.of(), null, null, null, true);
	}

	/** Companies never overlap; departments are units of organizations, through a predicate the caller defines. */
	private static void vocabulary(Engine e) {
		RememberOutcome o = remember(e, "Companies and departments.", proposal()
				.entityType(new EntityTypeDef("company", "A company; two companies never overlap.", "organization",
						List.of(), List.of(), true))
				.entityType(new EntityTypeDef("department", "A department of an organization.", "organization",
						List.of("dept"), List.of(), null))
				.predicate(containment("unit_of", "Subject department is a unit of object organization.", "department",
						"organization", List.of("unit of", "department of"), "{subject} is a unit of {object}")));
		assertTrue(o.applied().warnings().isEmpty(), o.applied().warnings().toString());
	}

	@Test
	@Scenario("Q14")
	void aCallerDefinedContainmentPredicateCarriesBoundsAndQuestions() {
		try (Engine e = TestHomes.engine("q14-containment")) {
			vocabulary(e);
			assertTrue(e.predicates().get("unit_of").orElseThrow().containment());
			assertTrue(e.predicates().containmentPredicates().containsAll(List.of("located_in", "part_of", "unit_of")));
			assertTrue(e.entityTypes().isDisjoint("company"));
			assertFalse(e.entityTypes().isDisjoint("department"));
			remember(e, "Platform is a department of Hooli.", proposal().entity("e1", "Platform", "department")
					.entity("e2", "Hooli", "company").fact("e1", "unit_of", "e2"));
			// A restriction may be bounded by anything the caller's predicate can nest things in.
			RememberOutcome bound = remember(e, "I only lead things within Hooli.",
					proposal().entity("e1", "Hooli", "company").fact(only("self", "leads", "e1")));
			assertEquals("Mattias Sandell leads only within Hooli", bound.applied().facts().getFirst().rendering());
			RememberOutcome inside = remember(e, "I lead Platform.",
					proposal().entity("e1", "Platform", "department").fact("self", "leads", "e1"));
			assertTrue(inside.applied().questions().isEmpty(), "within the bound: " + inside.applied().questions());
			remember(e, "Initrode is a company.", proposal().entity("e1", "Initrode", "company"));
			RecallResult no = recall(e, "does Mattias lead Initrode");
			assertEquals("known_false", no.structured().state(), no.text());
			assertEquals("restriction", no.structured().basis(), "two companies never overlap: " + no.text());
			RecallResult yes = recall(e, "does Mattias lead Platform");
			assertEquals("matched", yes.structured().state(), yes.text());
			// A department nobody has placed: the containment question names the caller's predicate.
			RememberOutcome open = remember(e, "I also lead Data Team.",
					proposal().entity("e1", "Data Team", "department").fact("self", "leads", "e1"));
			assertEquals(1, open.applied().questions().size(), open.applied().questions().toString());
			Map<String, Object> q = open.applied().questions().getFirst();
			assertEquals("containment", q.get("kind"));
			assertTrue(q.get("message").toString().contains("Is Data Team within Hooli?"), q.toString());
			assertTrue(q.toString().contains("Data Team unit_of Hooli"),
					"the answer's predicate is the caller's: " + q);
			RecallResult undecided = recall(e, "does Mattias lead Data Team");
			assertTrue(undecided.structured().notes().stream()
					.anyMatch(n -> n.contains("whether Data Team is within Hooli is not known")), undecided.text());
			var answered = e.answer(List.of(new Resolve(q.get("id").toString(), "yes")));
			assertEquals("answered", answered.getFirst().get("status"));
			assertTrue(e.facts().factsOf(e.entities().byRef("Data Team").orElseThrow().id()).stream()
					.anyMatch(f -> "unit_of".equals(f.predicate()) && f.current()), "the answer stored the nesting");
			RecallResult decided = recall(e, "does Mattias lead Data Team");
			assertTrue(decided.structured().notes().stream().noneMatch(n -> n.contains("is not known")),
					decided.text());
		}
	}

	@Test
	@Scenario("Q15")
	void containmentFactsApplyFirstWhateverPredicateNestsThem() {
		try (Engine e = TestHomes.engine("q15-ordering")) {
			vocabulary(e);
			remember(e, "I only lead things within Hooli.",
					proposal().entity("e1", "Hooli", "company").fact(only("self", "leads", "e1")));
			// The nesting comes after the job in the proposal; it is still applied first, so nothing is asked.
			RememberOutcome o = remember(e, "I lead Lab, a department of Hooli.",
					proposal().entity("e1", "Lab", "department").entity("e2", "Hooli", "company")
							.fact("self", "leads", "e1").fact("e1", "unit_of", "e2"));
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			assertEquals(2, o.applied().facts().size());
		}
	}

	@Test
	@Scenario("Q16")
	void containmentAndDisjointnessAreCorrectableAndChecked() {
		try (Engine e = TestHomes.engine("q16-correctable")) {
			// A predicate registered from use, declared a containment afterwards.
			remember(e, "The Lab is inside Building 7.", proposal().entity("e1", "Lab", "place")
					.entity("e2", "Building 7", "place").fact("e1", "inside_of", "e2"));
			assertFalse(e.predicates().get("inside_of").orElseThrow().containment());
			var out = e.correctPredicate("inside_of", Map.of("containment", true), "it nests things");
			assertEquals(true, e.predicates().get("inside_of").orElseThrow().containment());
			assertTrue(e.predicates().changes("inside_of").stream().anyMatch(c -> "containment".equals(c.get("field"))),
					out.toString());
			assertTrue(e.containment().ancestors(e.entities().byRef("Lab").orElseThrow().id())
					.contains(e.entities().byRef("Building 7").orElseThrow().id()), "walked along the new predicate");
			// A kind declared disjoint afterwards decides containment from then on.
			remember(e, "Campuses.", proposal()
					.entityType(new EntityTypeDef("campus", "A campus.", "place", List.of(), List.of(), null)));
			assertFalse(e.entityTypes().isDisjoint("campus"));
			e.correctEntityType("campus", Map.of("disjoint", true), "two campuses never overlap");
			assertTrue(e.entityTypes().isDisjoint("campus"));
			assertTrue(e.entityTypes().changes("campus").stream().anyMatch(c -> "disjoint".equals(c.get("field"))));
			remember(e, "Two campuses.",
					proposal().entity("e1", "North Campus", "campus").entity("e2", "South Campus", "campus"));
			long north = e.entities().byRef("North Campus").orElseThrow().id();
			long south = e.entities().byRef("South Campus").orElseThrow().id();
			assertEquals(se.hirt.mnemic.knowledge.Containment.Relation.DISJOINT,
					e.containment().of(north, south).relation());
			// A restriction needs a bound things can lie within: a person is not one.
			RememberOutcome bad = remember(e, "I only lead within Anna.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact(only("self", "leads", "e1")));
			assertTrue(bad.applied().facts().isEmpty(), bad.applied().facts().toString());
			assertTrue(bad.applied().warnings().getFirst().contains("containment predicate"),
					bad.applied().warnings().toString());
		}
	}

	@Test
	@Scenario("Q17")
	void theSeedsKeepTheirPlacesAndCountries() {
		try (Engine e = TestHomes.engine("q17-seeds")) {
			assertTrue(e.predicates().get("located_in").orElseThrow().containment());
			assertTrue(e.predicates().get("part_of").orElseThrow().containment());
			assertFalse(e.predicates().get("works_at").orElseThrow().containment());
			assertTrue(e.entityTypes().isDisjoint("country"));
			assertFalse(e.entityTypes().isDisjoint("place"));
			assertTrue(e.predicates().canContain(e.entityTypes().lineage("country")));
			assertFalse(e.predicates().canContain(e.entityTypes().lineage("organization")),
					"part_of nests anything but names no kind a container; a caller's predicate may");
			assertFalse(e.predicates().canContain(e.entityTypes().lineage("person")));
			assertEquals("located_in",
					e.predicates().containmentFor(e.entityTypes().lineage("place"), e.entityTypes().lineage("country"))
							.orElseThrow().name());
		}
	}
}
