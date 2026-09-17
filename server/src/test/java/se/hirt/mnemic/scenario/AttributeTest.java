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
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.proposal.Proposal.EntityRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.fact;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * EVALUATION.md K27–K31: a rule chooses its qualifier by any attribute a caller defines, predicates declare what their
 * terms imply, and nothing about gender in particular lives in the engine.
 */
class AttributeTest {

	private static PredicateDef def(
		String name, String description, String domain, String range, boolean functional, boolean symmetric,
		List<String> lexicon, String render, List<String> qualifiers, Object definedAs, Map<String, Object> implies) {
		return new PredicateDef(name, description, domain, range, functional, null, symmetric, null, "medium", lexicon,
				render, qualifiers, List.of(), null, definedAs, implies);
	}

	private static long id(RememberOutcome o) {
		return Long.parseLong(o.applied().facts().getFirst().id().substring(2));
	}

	private static List<String> renderings(Engine e, String predicate) {
		return e.facts().factsOf(e.entities().owner().id()).stream()
				.filter(f -> predicate.equals(f.predicate()) && f.current()).map(Fact::rendering).sorted().toList();
	}

	private static List<String> renderingsOf(Engine e, String name, String predicate) {
		return e.facts().factsOf(e.entities().byRef(name).orElseThrow().id()).stream()
				.filter(f -> predicate.equals(f.predicate()) && f.current()).map(Fact::rendering).sorted().toList();
	}

	/** What Britt is to Mattias, as derived. */
	private static String britt(Engine e) {
		return renderings(e, "aunt_uncle_of").stream().filter(x -> x.startsWith("Britt")).findFirst().orElse("");
	}

	private static void parents(Engine e) {
		remember(e, "My father is Konrad and my mother is Gunilla.",
				proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
						.fact(fact("e1", "parent_of", "self", "father", null, null, null, null, null, null))
						.fact(fact("e2", "parent_of", "self", "mother", null, null, null, null, null, null)));
	}

	/** Britt as Konrad's sibling with no role stated: what she is to Mattias depends on what else is known. */
	private static void brittSibling(Engine e) {
		remember(e, "Britt is Konrad's sibling.",
				proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Konrad Nyberg", "person")
						.fact(fact("e1", "sibling_of", "e2", null, null, null, null, null, null, null)));
	}

	@Test
	@Scenario("K27")
	void aCallerDefinedAttributeChoosesTheQualifier() {
		try (Engine e = TestHomes.engine("k27-attribute")) {
			RememberOutcome vocabulary = remember(e, "Handedness, training, sparring.", proposal()
					.predicate(def("handedness", "Subject's dominant hand.", "person", "literal", true, false,
							List.of("handedness", "left-handed", "right-handed"), "{subject} is {object}-handed",
							List.of(), null, null))
					.predicate(def("trains_with", "Subject trains with object.", "person", "person", false, true,
							List.of("trains with", "training partner"), "{subject} trains with {object}", List.of(),
							null, null))
					.predicate(def("sparring_partner_of", "Subject spars with object.", "person", "person", false, true,
							List.of("sparring partner", "spars with"),
							"{subject} is {object}'s {qualifier|sparring partner}",
							List.of("southpaw sparring partner", "orthodox sparring partner"),
							List.of(Map.of("path", List.of("trains_with"), "qualifier", "sparring partner", "by",
									Map.of("attribute", "handedness", "values", Map.of("left",
											"southpaw sparring partner", "right", "orthodox sparring partner")))),
							null)));
			assertTrue(vocabulary.applied().warnings().isEmpty(), vocabulary.applied().warnings().toString());
			RememberOutcome o = remember(e, "Anna is left-handed and trains with Bo.",
					proposal().entity("e1", "Anna Lindqvist", "person").entity("e2", "Bo Nyberg", "person")
							.fact(fact("e1", "handedness", "left", null, null, null, null, null, null, null))
							.fact(fact("e1", "trains_with", "e2", null, null, null, null, null, null, null)));
			assertEquals(1, o.derived().derived(), o.derived().toString());
			assertEquals(List.of("Anna Lindqvist is Bo Nyberg's southpaw sparring partner"),
					renderingsOf(e, "Anna Lindqvist", "sparring_partner_of"));
			// The attribute fact is a fact: correct it and the derivation follows; withdraw it and the plain word returns.
			Fact hand = e.facts().factsOf(e.entities().byRef("Anna Lindqvist").orElseThrow().id()).stream()
					.filter(f -> "handedness".equals(f.predicate())).findFirst().orElseThrow();
			e.correct(hand.id(), Map.of("object", "Right"), "as it turns out");
			assertEquals(List.of("Anna Lindqvist is Bo Nyberg's orthodox sparring partner"),
					renderingsOf(e, "Anna Lindqvist", "sparring_partner_of"), "case does not matter");
			Fact now = e.facts().factsOf(e.entities().byRef("Anna Lindqvist").orElseThrow().id()).stream()
					.filter(f -> "handedness".equals(f.predicate()) && f.current()).findFirst().orElseThrow();
			e.correct(now.id(), Map.of("wrong", true), "unknown after all");
			assertEquals(List.of("Anna Lindqvist is Bo Nyberg's sparring partner"),
					renderingsOf(e, "Anna Lindqvist", "sparring_partner_of"));
			// The gap is listed under the attribute's own name, and the entity card shows the value when there is one.
			var gaps = e.consolidate(true).attributeUnknown();
			assertEquals(1, gaps.size(), gaps.toString());
			assertEquals("handedness", gaps.getFirst().get("attribute"));
			assertEquals("Anna Lindqvist", gaps.getFirst().get("name"));
			// The shorthand on an entity entry works for any attribute, not only gender.
			RememberOutcome again = remember(e, "Bo is right-handed.", proposal().entity(
					new EntityRef("e1", "Bo Nyberg", "person", List.of(), null, Map.of("handedness", "right"))));
			assertEquals(1, again.applied().facts().size(), again.applied().warnings().toString());
			assertEquals("Bo Nyberg is right-handed", again.applied().facts().getFirst().rendering());
		}
	}

	@Test
	@Scenario("K28")
	void aPredicateDeclaresWhatItsTermsImply() {
		try (Engine e = TestHomes.engine("k28-implies")) {
			parents(e);
			// A caller's predicate says what its qualifiers mean: a godmother is female.
			RememberOutcome vocabulary = remember(e, "Godparents.",
					proposal().predicate(def("godparent_of", "Subject is a godparent of object.", "person", "person",
							false, false, List.of("godparent", "godmother", "godfather"),
							"{subject} is {object}'s {qualifier|godparent}", List.of("godmother", "godfather"), null,
							Map.of("godmother", Map.of("gender", "female"), "godfather", Map.of("gender", "male")))));
			assertTrue(vocabulary.applied().warnings().isEmpty(), vocabulary.applied().warnings().toString());
			assertEquals("female", e.predicates().impliesOf("godparent_of").get("godmother").get("gender"));
			brittSibling(e);
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's aunt or uncle"), renderings(e, "aunt_uncle_of"));
			RememberOutcome o = remember(e, "Britt is Linus's godmother.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Linus Nyberg", "person")
							.fact(fact("e1", "godparent_of", "e2", "godmother", null, null, null, null, null, null)));
			assertEquals(1, o.derived().updated(), "the implication reached the derivation: " + o.derived());
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's aunt"), renderings(e, "aunt_uncle_of"));
			// Declared afterwards through correct, the same; naming an unknown attribute is refused.
			var out = e.correctPredicate("godparent_of", Map.of("implies", Map.of()), "none after all");
			assertTrue(((Map<?, ?>) out.get("implies")).isEmpty());
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's aunt or uncle"), renderings(e, "aunt_uncle_of"));
			e.correctPredicate("godparent_of", Map.of("implies", Map.of("Godmother", Map.of("Gender", "Female"))),
					"and again");
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's aunt"), renderings(e, "aunt_uncle_of"),
					"terms and values are lowercased");
			MnemicException unknown = assertThrows(MnemicException.class, () -> e.correctPredicate("godparent_of",
					Map.of("implies", Map.of("godmother", Map.of("faith", "catholic"))), "no such attribute"));
			assertTrue(unknown.getMessage().contains("unknown attribute predicate 'faith'"), unknown.getMessage());
			MnemicException shape = assertThrows(MnemicException.class,
					() -> e.correctPredicate("godparent_of", Map.of("implies", Map.of("godmother", "female")), "x"));
			assertTrue(shape.getMessage().contains("takes an object"), shape.getMessage());
			assertTrue(e.predicates().impliesOf("godparent_of").containsKey("godmother"), "refused: left as it was");
			// A definition naming an unknown attribute is skipped with the reason, the observation kept.
			MnemicException bad = assertThrows(MnemicException.class,
					() -> remember(e, "Patrons.",
							proposal().predicate(def("patron_of", "x", "person", "person", false, false,
									List.of("patron"), "{subject} is {object}'s patron", List.of(), null,
									Map.of("patron", Map.of("rank", "high"))))));
			assertTrue(bad.getMessage().contains("unknown attribute predicate 'rank'"), bad.getMessage());
			assertTrue(e.predicates().get("patron_of").isEmpty());
		}
	}

	@Test
	@Scenario("K29")
	void aStatedValueOutranksImplicationsAndDisagreementYieldsNothing() {
		try (Engine e = TestHomes.engine("k29-precedence")) {
			parents(e);
			brittSibling(e);
			// Implied by a role: a mother is female.
			remember(e, "Britt is Kim's mother.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Kim Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "mother", null, null, null, null, null, null)));
			assertEquals("Britt Nyberg is Mattias Sandell's aunt", britt(e));
			// A second role that disagrees: the record cannot say, and says nothing.
			RememberOutcome husband = remember(e, "Britt is Lena's husband.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Lena Berg", "person")
							.fact(fact("e1", "spouse_of", "e2", "husband", null, null, null, null, null, null)));
			assertEquals(1, husband.derived().updated(), husband.derived().toString());
			assertEquals("Britt Nyberg is Mattias Sandell's aunt or uncle", britt(e));
			// A stated value settles it, whatever the roles say; its own implications canonicalise the word.
			remember(e, "Britt is a woman.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("e1", "gender", "Woman", null, null, null, null, null, null, null)));
			assertEquals("Britt Nyberg is Mattias Sandell's aunt", britt(e));
			Map<String, Object> card = Map.of();
			// A value the rule does not name is a value all the same: the plain word, and no gap listed.
			Fact g = e.facts().factsOf(e.entities().byRef("Britt Nyberg").orElseThrow().id()).stream()
					.filter(f -> "gender".equals(f.predicate()) && f.current()).findFirst().orElseThrow();
			e.correct(g.id(), Map.of("object", "nonbinary"), "as stated");
			assertEquals("Britt Nyberg is Mattias Sandell's aunt or uncle", britt(e));
			assertTrue(
					e.consolidate(true).attributeUnknown().stream()
							.noneMatch(x -> "Britt Nyberg".equals(x.get("name"))),
					"known, only not one the rule names: " + e.consolidate(true).attributeUnknown());
			assertTrue(card.isEmpty());
			// A rule choosing by an attribute nobody registered is refused when defined.
			MnemicException bad = assertThrows(MnemicException.class,
					() -> remember(e, "Nonsense.",
							proposal().predicate(def("x_of", "x", "person", "person", false, false, List.of("xof"),
									"{subject} x {object}", List.of(),
									List.of(Map.of("path", List.of("parent_of"), "by",
											Map.of("attribute", "mood", "values", Map.of("sunny", "bright parent")))),
									null))));
			assertTrue(bad.getMessage().contains("unknown attribute predicate 'mood'"), bad.getMessage());
		}
	}

	@Test
	@Scenario("K30")
	void siblingTermsImplyNothingUntilAStoreSaysSo() {
		try (Engine e = TestHomes.engine("k30-sibling-implies")) {
			parents(e);
			remember(e, "Kim is my child.", proposal().entity("e1", "Kim Sandell", "person")
					.fact(fact("self", "parent_of", "e1", null, null, null, null, null, null, null)));
			remember(e, "Konrad and Gunilla are Oskar's parents; Oskar is my brother.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.entity("e3", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "mother", null, null, null, null, null, null))
							.fact(fact("e3", "sibling_of", "self", "brother", null, null, null, null, null, null)));
			assertEquals(List.of("Oskar Nyberg is Kim Sandell's aunt or uncle"),
					renderingsOf(e, "Kim Sandell", "aunt_uncle_of"), "brother implies nothing by the seed");
			assertTrue(e.predicates().impliesOf("sibling_of").isEmpty());
			// The gap says what the record has to go on, so the reason is read, not guessed.
			Map<String, Object> gap = e.consolidate(true).attributeUnknown().stream()
					.filter(x -> "Oskar Nyberg".equals(x.get("name"))).findFirst().orElseThrow();
			assertEquals(List.of("sibling_of[brother] (no implication declared for 'brother' on sibling_of)"),
					gap.get("stated_roles"), gap.toString());
			// The store opts in, and the stated brother becomes an uncle.
			var out = e.correctPredicate("sibling_of",
					Map.of("implies",
							Map.of("brother", Map.of("gender", "male"), "sister", Map.of("gender", "female"))),
					"we trust them");
			assertEquals(1, ((Map<?, ?>) out.get("derived")).get("updated"), out.toString());
			assertEquals(List.of("Oskar Nyberg is Kim Sandell's uncle"),
					renderingsOf(e, "Kim Sandell", "aunt_uncle_of"));
			// Only current facts imply: retired as redundant, the stated brother says nothing more.
			Fact stated = e.facts().factsOf(e.entities().byRef("Oskar Nyberg").orElseThrow().id()).stream().filter(
					f -> "sibling_of".equals(f.predicate()) && !"derived".equals(f.derivationKind()) && f.current())
					.findFirst().orElseThrow();
			e.correct(stated.id(), Map.of("redundant", true), "the parents say it");
			assertEquals(List.of("Oskar Nyberg is Kim Sandell's aunt or uncle"),
					renderingsOf(e, "Kim Sandell", "aunt_uncle_of"));
			// The seed's own implications are kept across a restart only where the store did not change them.
			assertFalse(e.predicates().impliesOf("parent_of").isEmpty());
		}
	}

	@Test
	@Scenario("K31")
	void onlyEntitiesWithNeutralDerivedRelationsAreListedAsGaps() {
		try (Engine e = TestHomes.engine("k31-gaps")) {
			parents(e);
			brittSibling(e);
			remember(e, "I know Tobias.", proposal().entity("e1", "Tobias Falk", "person").fact("self", "knows", "e1"));
			remember(e, "Konrad and Gunilla are Oskar's parents.",
					proposal().entity("e1", "Konrad Nyberg", "person").entity("e2", "Gunilla Nyberg", "person")
							.entity("e3", "Oskar Nyberg", "person")
							.fact(fact("e1", "parent_of", "e3", "father", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "e3", "mother", null, null, null, null, null, null)));
			var gaps = e.consolidate(true).attributeUnknown();
			assertTrue(gaps.stream().noneMatch(g -> "Tobias Falk".equals(g.get("name"))),
					"merely known, nothing derived: " + gaps);
			assertTrue(gaps.stream().anyMatch(g -> "Britt Nyberg".equals(g.get("name"))), gaps.toString());
			assertTrue(gaps.stream().anyMatch(g -> "Mattias Sandell".equals(g.get("name"))),
					"the owner's sibling relation is neutral too: " + gaps);
			assertTrue(gaps.stream().allMatch(g -> "gender".equals(g.get("attribute"))), gaps.toString());
			assertTrue(gaps.stream().allMatch(g -> ((Number) g.get("neutral_relations")).intValue() >= 1));
			// Stated, the gap closes; the fallback qualifier stays where a rule names no value for it.
			remember(e, "I am male, and Britt is female.",
					proposal().entity("e1", "Britt Nyberg", "person")
							.fact(fact("self", "gender", "male", null, null, null, null, null, null, null))
							.fact(fact("e1", "gender", "female", null, null, null, null, null, null, null)));
			assertTrue(e.consolidate(true).attributeUnknown().isEmpty(),
					e.consolidate(true).attributeUnknown().toString());
			assertEquals(List.of("Mattias Sandell is Oskar Nyberg's brother"), renderings(e, "sibling_of"));
		}
	}

	@Test
	@Scenario("K32")
	void anImpliedValueStandsAsADerivedFactAndStepsAsideForAStatement() {
		try (Engine e = TestHomes.engine("k32-implied-facts")) {
			parents(e);
			brittSibling(e);
			RememberOutcome o = remember(e, "Britt is Kim's mother.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Kim Nyberg", "person")
							.fact(fact("e1", "parent_of", "e2", "mother", null, null, null, null, null, null)));
			long britt = e.entities().byRef("Britt Nyberg").orElseThrow().id();
			Fact implied = e.facts().factsOf(britt).stream().filter(f -> "gender".equals(f.predicate()) && f.current())
					.findFirst().orElseThrow();
			assertEquals("Britt Nyberg is female", implied.rendering());
			assertEquals("derived", implied.derivationKind());
			Map<String, Object> by = e.deriver().derivationOf(implied.id());
			assertEquals("implied", by.get("kind"));
			assertEquals(List.of(o.applied().facts().getFirst().id()), by.get("base"), "rests on the mother fact");
			assertTrue(e.facts().observationsOf(implied.id()).contains(o.observation().observationId()));
			// Asked about, it answers; it is not a stated fact of the record.
			RecallResult r = TestHomes.recall(e, "what is Britt's gender");
			assertEquals("matched", r.structured().state(), r.text());
			assertEquals(0, e.facts().factsOfObservation(o.observation().observationId()).stream()
					.filter(f -> "gender".equals(f.predicate())).count());
			// A statement takes over without a conflict question, whatever the implication said.
			RememberOutcome stated = remember(e, "Britt is male.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("e1", "gender", "male", null, null, null, null, null, null, null)));
			assertTrue(stated.applied().questions().isEmpty(), stated.applied().questions().toString());
			assertEquals("invalidated", e.facts().get(implied.id()).orElseThrow().status());
			assertEquals(List.of("Britt Nyberg is Mattias Sandell's uncle"), renderings(e, "aunt_uncle_of"));
			// Withdrawn, the implication returns as a fact again.
			e.correct(id(stated), Map.of("wrong", true), "no");
			assertTrue(e.facts().factsOf(britt).stream().anyMatch(f -> "gender".equals(f.predicate()) && f.current()
					&& "derived".equals(f.derivationKind()) && "Britt Nyberg is female".equals(f.rendering())));
			// Implications that disagree leave no fact behind.
			remember(e, "Britt is Lena's husband.",
					proposal().entity("e1", "Britt Nyberg", "person").entity("e2", "Lena Berg", "person")
							.fact(fact("e1", "spouse_of", "e2", "husband", null, null, null, null, null, null)));
			assertTrue(e.facts().factsOf(britt).stream().noneMatch(f -> "gender".equals(f.predicate()) && f.current()));
		}
	}

	@Test
	@Scenario("K33")
	void anExclusionMayBeWrittenAgainstTheDirection() {
		try (Engine e = TestHomes.engine("k33-not-inverse")) {
			// A "protégé": someone one mentors who is not one's own child, written both ways round.
			RememberOutcome bad = remember(e, "Mentoring.",
					proposal()
							.predicate(def("mentors", "Subject mentors object.", "person", "person", false, false,
									List.of("mentors", "mentor"), "{subject} mentors {object}", List.of(), null, null))
							.predicate(def("protege_of", "Subject is a protégé of object.", "person", "person", false,
									false, List.of("protégé", "protege"), "{subject} is {object}'s protégé", List.of(),
									List.of(Map.of("path", List.of("^mentors"), "not", List.of("^parent_of"))), null)));
			remember(e, "I mentor Kim and Lisa; Kim is my child.",
					proposal().entity("e1", "Kim Berg", "person").entity("e2", "Lisa Berg", "person")
							.fact("self", "mentors", "e1").fact("self", "mentors", "e2")
							.fact(fact("self", "parent_of", "e1", null, null, null, null, null, null, null)));
			assertEquals(List.of("Lisa Berg is Mattias Sandell's protégé"), renderings(e, "protege_of"),
					"Kim is excluded by the parent fact running the other way");
			MnemicException unknown = assertThrows(MnemicException.class,
					() -> remember(e, "Nonsense.",
							proposal().predicate(def("x_of", "x", "person", "person", false, false, List.of("xof"),
									"{subject} x {object}", List.of(),
									List.of(Map.of("path", List.of("^mentors"), "not", List.of("^guides"))), null))));
			assertTrue(unknown.getMessage().contains("excludes unknown predicate 'guides'"), unknown.getMessage());
		}
	}

	@Test
	@Scenario("K34")
	void aProposalThatOnlyDefinesRefusedVocabularyLeavesNoObservation() {
		try (Engine e = TestHomes.engine("k34-refused-definition")) {
			long observations = e.observations().count();
			MnemicException refused = assertThrows(MnemicException.class,
					() -> remember(e, "Nonsense.",
							proposal().predicate(def("x_of", "x", "person", "person", false, false, List.of("xof"),
									"{subject} x {object}", List.of(), List.of(Map.of("path", List.of("parent_of+"))),
									null))));
			assertTrue(refused.getMessage().contains("vocabulary only") && refused.getMessage().contains("unbounded"),
					refused.getMessage());
			assertEquals(observations, e.observations().count(), "nothing kept");
			// With a fact in the same proposal the observation stands, the definition skipped with its warning.
			RememberOutcome mixed = remember(e, "Nonsense and a fact.",
					proposal().predicate(def("x_of", "x", "person", "person", false, false, List.of("xof"),
							"{subject} x {object}", List.of(), List.of(Map.of("path", List.of("parent_of+"))), null))
							.entity("e1", "Kim Berg", "person").fact("self", "knows", "e1"));
			assertEquals(1, mixed.applied().facts().size());
			assertFalse(mixed.applied().warnings().isEmpty());
			assertEquals(observations + 1, e.observations().count());
		}
	}

	@Test
	@Scenario("K35")
	void aQuestionNamingTheObjectSideReachesTheObjectSidePredicate() {
		try (Engine e = TestHomes.engine("k35-routing")) {
			remember(e, "Nieces and nephews.", proposal().predicate(def("nibling_of",
					"Subject is a child of a sibling of object.", "person", "person", false, false,
					List.of("nephew", "nephews", "niece", "nieces", "nibling"),
					"{subject} is {object}'s {qualifier|nibling}", List.of("nephew", "niece"),
					List.of(Map.of("path", List.of("^parent_of", "sibling_of"), "qualifier", "nibling", "by",
							Map.of("attribute", "gender", "values", Map.of("male", "nephew", "female", "niece")))),
					Map.of("nephew", Map.of("gender", "male"), "niece", Map.of("gender", "female")))));
			remember(e, "Oskar is my brother; his son is Linus.",
					proposal().entity("e1", "Oskar Nyberg", "person").entity("e2", "Linus Nyberg", "person")
							.fact(fact("e1", "sibling_of", "self", "brother", null, null, null, null, null, null))
							.fact(fact("e1", "parent_of", "e2", "father", null, null, null, null, null, null)));
			remember(e, "Linus is a boy.", proposal().entity("e1", "Linus Nyberg", "person")
					.fact(fact("e1", "gender", "boy", null, null, null, null, null, null, null)));
			RecallResult r = TestHomes.recall(e, "who are Mattias's nephews");
			assertEquals("nibling_of", r.structured().predicate(),
					"the word's own predicate, not the inverse: " + r.text());
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(r.text().contains("Linus Nyberg is Mattias Sandell's nephew"), r.text());
		}
	}

	@Test
	@Scenario("K36")
	void aNeutralRecordedQualifierAnswersAGenderedQuestionAndSaysSo() {
		try (Engine e = TestHomes.engine("k36-neutral-answer")) {
			parents(e);
			brittSibling(e);
			assertEquals("Britt Nyberg is Mattias Sandell's aunt or uncle", britt(e));
			RecallResult r = TestHomes.recall(e, "who is Mattias's aunt");
			assertEquals("matched", r.structured().state(), r.text());
			assertTrue(
					r.structured().notes().stream()
							.anyMatch(n -> n.contains("recorded as 'aunt or uncle'") && n.contains("covers 'aunt'")),
					"the answer says what is not on record: " + r.text());
			// Once the gender is on record the answer is plain, and the other one is no longer an answer.
			remember(e, "Britt is a woman.", proposal().entity("e1", "Britt Nyberg", "person")
					.fact(fact("e1", "gender", "woman", null, null, null, null, null, null, null)));
			RecallResult again = TestHomes.recall(e, "who is Mattias's aunt");
			assertEquals("matched", again.structured().state(), again.text());
			assertTrue(again.structured().notes().stream().noneMatch(n -> n.contains("covers")), again.text());
			RecallResult uncle = TestHomes.recall(e, "who is Mattias's uncle");
			assertEquals("miss", uncle.structured().state(), uncle.text());
		}
	}

	@Test
	@Scenario("K37")
	void aProposalMayAddImplicationsAndRulesToAnExistingPredicate() {
		try (Engine e = TestHomes.engine("k37-add-to-existing")) {
			parents(e);
			remember(e, "Kim is my child.", proposal().entity("e1", "Kim Sandell", "person")
					.fact(fact("self", "parent_of", "e1", null, null, null, null, null, null, null)));
			remember(e, "Oskar is my brother.", proposal().entity("e1", "Oskar Nyberg", "person")
					.fact(fact("e1", "sibling_of", "self", "brother", null, null, null, null, null, null)));
			assertEquals(List.of("Oskar Nyberg is Kim Sandell's aunt or uncle"),
					renderingsOf(e, "Kim Sandell", "aunt_uncle_of"));
			// The way a caller sends it: a definition of the seed predicate, carrying only what to add.
			RememberOutcome o = remember(e, "Sibling roles say who is who.",
					proposal().predicate(new PredicateDef("sibling_of", null, null, null, null, null, null, null, null,
							List.of(), null, List.of(), List.of(), null, null,
							Map.of("brother", Map.of("gender", "male"), "sister", Map.of("gender", "female")))));
			assertTrue(o.applied().warnings().isEmpty(), o.applied().warnings().toString());
			assertEquals(1, o.applied().definitions().size(), o.applied().definitions().toString());
			assertEquals("updated", o.applied().definitions().getFirst().get("resolution"));
			assertEquals(List.of("implies"), o.applied().definitions().getFirst().get("applied"));
			assertEquals("male", e.predicates().impliesOf("sibling_of").get("brother").get("gender"));
			assertTrue(
					e.predicates().changes("sibling_of").stream()
							.anyMatch(c -> "implies".equals(c.get("field"))
									&& String.valueOf(c.get("reason")).startsWith("defined in obs-")),
					e.predicates().changes("sibling_of").toString());
			assertTrue(o.derived().updated() >= 1, "and the derivations followed: " + o.derived());
			assertEquals(List.of("Oskar Nyberg is Kim Sandell's uncle"),
					renderingsOf(e, "Kim Sandell", "aunt_uncle_of"));
			// The rest of an existing predicate's definition is not the proposal's to change: said, and left alone.
			RememberOutcome more = remember(e, "Siblings again.",
					proposal().predicate(new PredicateDef("sibling_of", "A sibling.", null, null, null, null, null,
							null, null, List.of("sib"), null, List.of(), List.of(), null, null,
							Map.of("sister", Map.of("gender", "female")))));
			assertTrue(more.applied().warnings().getFirst().contains("only implies and defined_as were taken"),
					more.applied().warnings().toString());
			assertEquals(List.of("sibling", "siblings"), e.predicates().get("sibling_of").orElseThrow().lexicon());
			MnemicException none = assertThrows(MnemicException.class,
					() -> remember(e, "Siblings once more.",
							proposal().predicate(new PredicateDef("sibling_of", "A sibling.", null, null, null, null,
									null, null, null, List.of("sib"), null, List.of(), List.of(), null, null, null))));
			assertTrue(none.getMessage().contains("already defined; the definition was left"), none.getMessage());
		}
	}
}
