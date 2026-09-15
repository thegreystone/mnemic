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
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal.FactRef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md sections I (granularity), E (provenance), J (predicate extensibility), C3/C6 (accumulation). */
class FactsTest {

	@Test
	@Scenario("I1")
	void oneObservationYieldsManyFacts() {
		try (Engine e = engine("i1")) {
			RememberOutcome o = remember(e,
					"My wife is Marit. My father is Konrad. We moved from Sweden to Schübelbach in 2014.",
					proposal().entity("e1", "Marit", "person").entity("e2", "Konrad", "person")
							.entity("e3", "Sweden", "place").entity("e4", "Schübelbach", "place")
							.event("ev1", "moved", "2014", "self", "e4")
							.fact(fact("e1", "spouse_of", "self", "wife", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "self", "father", null, null, null, null, null, null))
							.fact(fact("self", "lives_in", "e4", null, null, "2014", null, null, List.of("ev1"), null))
							.fact(fact("self", "lives_in", "e3", null, null, null, "2014", null, null, null)));
			assertEquals(4, o.applied().facts().size(), o.applied().warnings().toString());
			assertEquals(1, o.applied().events().size());
			long obs = o.observation().observationId();
			List<Fact> facts = e.facts().factsOfObservation(obs);
			assertEquals(4, facts.size());
			assertTrue(facts.stream().allMatch(f -> f.observationId() == obs), "shared derived_from");
			Fact schubelbach = facts.stream().filter(f -> f.rendering().contains("Schübelbach")).findFirst().orElseThrow();
			assertEquals("2014-01-01", schubelbach.validStart());
			assertEquals("year", schubelbach.validStartPrecision());
			assertNotNull(schubelbach.eventId(), "linked to the moved event");
			assertTrue(schubelbach.rendering().endsWith("(since 2014)"), schubelbach.rendering());
			assertNotNull(schubelbach.spanStart(), "anchored in the observation");
			String span = "My wife is Marit. My father is Konrad. We moved from Sweden to Schübelbach in 2014.".substring(
					schubelbach.spanStart(), schubelbach.spanEnd());
			assertTrue(span.startsWith("We moved"), span);
			Fact father = facts.stream().filter(f -> f.predicate().equals("parent_of")).findFirst().orElseThrow();
			assertEquals("Konrad is Mattias Sandell's father", father.rendering());
		}
	}

	@Test
	@Scenario("I2")
	void forgettingTheObservationRemovesAllDerivedFacts() {
		try (Engine e = engine("i2")) {
			RememberOutcome o = remember(e, "My wife is Marit. My father is Konrad.",
					proposal().entity("e1", "Marit", "person").entity("e2", "Konrad", "person")
							.fact(fact("e1", "spouse_of", "self", "wife", null, null, null, null, null, null))
							.fact(fact("e2", "parent_of", "self", "father", null, null, null, null, null, null)));
			long obs = o.observation().observationId();
			assertEquals(2, e.facts().count());
			assertTrue(e.forget(obs));
			assertEquals(0, e.facts().count());
			assertTrue(recall(e, "who is Mattias married to").hits().isEmpty());
			assertTrue(e.entities().byRef("Marit").isEmpty(), "orphaned entity removed");
			assertTrue(e.entities().byRef("Mattias Sandell").isPresent(), "the owner stays");
		}
	}

	@Test
	@Scenario("I3")
	void preChoppedSentencesLoseCoreferenceAndMnemicDoesNotGuess() {
		try (Engine e = engine("i3")) {
			remember(e, "The war lasted six years.");
			remember(e, "It ended in 1945.");
			assertEquals(2, e.observations().count());
			assertEquals(2, e.observations().pendingProposals());
			assertTrue(e.entities().byRef("It").isEmpty());
			assertEquals(0, e.facts().count());
		}
	}

	@Test
	@Scenario("E4")
	void derivationKindIsRecordedAndValidated() {
		try (Engine e = engine("e4")) {
			RememberOutcome first = remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			Fact f1 = e.facts().get(id(first.applied().facts().getFirst().id())).orElseThrow();
			assertEquals("explicit", f1.derivationKind());

			RememberOutcome second = remember(e, "Org chart: Mattias Sandell, Director of Engineering.",
					new Source("document", "orgchart.pdf", null, null, null), proposal().fact(
							fact("self", "holds_role", "Director of Engineering", null, "Hooli", null, null, null,
									null, "explicit")));
			Fact f2 = e.facts().get(id(second.applied().facts().getFirst().id())).orElseThrow();
			assertEquals("extracted", f2.derivationKind(), "explicit rejected for a document source");
			assertTrue(second.applied().warnings().stream().anyMatch(w -> w.contains("explicit")),
					second.applied().warnings().toString());
			assertEquals("Mattias Sandell holds the role Director of Engineering at Hooli", f2.rendering());
		}
	}

	@Test
	@Scenario("E3")
	void callerConfidenceIsEvidenceNotTruth() {
		try (Engine e = engine("e3")) {
			var fr = new se.hirt.mnemic.proposal.Proposal.FactRef("self", "born_in", "Uppsala", null, null, null,
					null, null, null, 0.99);
			RememberOutcome o = remember(e, "Mattias was born in Uppsala.", proposal().fact(fr));
			assertEquals(1, o.applied().facts().size());
			// The caller's number is stored as evidence about the caller, on the fact row, never as the confidence.
			Fact f = e.facts().get(id(o.applied().facts().getFirst().id())).orElseThrow();
			assertEquals(1, f.corroborations());
		}
	}

	@Test
	@Scenario("E5")
	void connectorObservationsCannotPropose() {
		try (Engine e = engine("e5b")) {
			Source connector = new Source("connector", "msg-1", null, null, null);
			var ex = org.junit.jupiter.api.Assertions.assertThrows(se.hirt.mnemic.protocol.MnemicException.class,
					() -> remember(e, "See you at noon.", connector, proposal().fact("knows", "Anna")));
			assertEquals(se.hirt.mnemic.protocol.MnemicException.Code.INVALID_ARGUMENT, ex.code());
		}
	}

	@Test
	@Scenario("C3")
	void nonFunctionalPredicatesAccumulate() {
		try (Engine e = engine("c3")) {
			remember(e, "I'm a member of the Platform Fellows.",
					proposal().entity("e1", "Platform Fellows", "organization").fact("self", "member_of", "e1"));
			remember(e, "I'm a member of the Kestrel project.",
					proposal().entity("e1", "Kestrel", "project").fact("self", "member_of", "e1"));
			List<Fact> facts = e.facts().factsOf(e.entities().owner().id());
			assertEquals(2, facts.stream().filter(f -> f.predicate().equals("member_of") && f.current()).count());
		}
	}

	@Test
	@Scenario("C6")
	void reConfirmationCorroboratesWithoutANewFact() {
		try (Engine e = engine("c6")) {
			Instant t0 = Instant.parse("2026-09-06T10:00:00Z");
			RememberOutcome a = remember(e, "I work at Hooli.", t0, proposal().fact("works_at", "Hooli"));
			RememberOutcome b = remember(e, "Still at Hooli, busy week.", t0.plusSeconds(400L * 86_400),
					proposal().fact("works_at", "Hooli"));
			assertEquals(a.applied().facts().getFirst().id(), b.applied().facts().getFirst().id());
			assertTrue(b.applied().facts().getFirst().corroborated());
			Fact f = e.facts().get(id(a.applied().facts().getFirst().id())).orElseThrow();
			assertEquals(2, f.corroborations());
			assertEquals(t0.plusSeconds(400L * 86_400).toString(), f.lastConfirmed());
			assertEquals(0, b.applied().events().size());
		}
	}

	@Test
	@Scenario("J1")
	void callerDefinesANewPredicateOnTheFly() {
		try (Engine e = engine("j1")) {
			RememberOutcome o = remember(e, "My godmother is Hedvig.", proposal().predicate(
							new PredicateDef("godparent_of", "Subject sponsored object at baptism or equivalent.", "person",
									"person", false, null, null, null, "low", List.of("godparent", "godmother", "godfather"),
									"{subject} is {object}'s {qualifier|godparent}", List.of("godmother", "godfather"),
									List.of())).entity("e1", "Hedvig", "person")
					.fact(fact("e1", "godparent_of", "self", "godmother", null, null, null, null, null, null)));
			assertEquals("registered", o.applied().predicates().getFirst().resolution());
			Predicate p = e.predicates().get("godparent_of").orElseThrow();
			assertEquals(o.observation().observationId(), p.definedBy());
			assertEquals(1, o.applied().facts().size(), o.applied().warnings().toString());
			assertEquals("Hedvig is Mattias Sandell's godmother", o.applied().facts().getFirst().rendering());

			RecallResult r = recall(e, "who is Mattias's godmother");
			assertTrue(r.structured().matched(), r.text());
			assertEquals("godparent_of", r.structured().predicate());
			assertEquals("godmother", r.structured().qualifier());
		}
	}

	@Test
	@Scenario("J2")
	void synonymPredicateResolvesToAnExistingOne() {
		try (Engine e = engine("j2")) {
			remember(e, "I work at Hooli.", proposal().fact("works_at", "Hooli"));
			RememberOutcome o = remember(e, "Mattias is employed by Hooli.", proposal().predicate(
							new PredicateDef("employed_by", "Subject works for object organization.", "person", "organization",
									true, null, null, null, null, List.of(), null, List.of(), List.of()))
					.fact("self", "employed_by", "Hooli"));
			// Similar is asked, never applied (2026-09-10): the candidate is named, the fact is held.
			assertEquals("similar", o.applied().predicates().getFirst().resolution());
			assertEquals("works_at", o.applied().predicates().getFirst().id());
			assertTrue(o.applied().facts().isEmpty(), "held until answered");
			assertEquals(1, o.applied().questions().size());
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("predicate_resolution", q.get("kind"));
			assertTrue(String.valueOf(q.get("message")).contains("Subject works for object organization"),
					"both descriptions are shown so meaning can be compared: " + q);
			assertTrue(e.predicates().get("employed_by").isEmpty(), "no alias before the answer");

			RememberOutcome yes = remember(e, "Yes, same thing.", null,
					new Resolve((String) q.get("id"), "works_at"));
			assertTrue(e.predicates().get("employed_by").isPresent(), "confirmed: recorded as an alias");
			assertEquals("works_at", e.predicates().get("employed_by").orElseThrow().name());
			assertEquals(1, e.facts().count(), "one works_at fact, confirmed again");
			assertEquals(1, ((List<?>) yes.resolved().getFirst().get("facts")).size());
			// Asked once: the alias resolves the next use without a question.
			RememberOutcome again = remember(e, "Still employed by Hooli.", proposal().fact("self", "employed_by", "Hooli"));
			assertTrue(again.applied().questions().isEmpty());
			assertEquals(1, e.facts().count());
		}
	}

	@Test
	@Scenario("E6")
	void aBeliefIsMarkedAndRanksBelowAStatement() {
		try (Engine e = engine("e4-believed")) {
			RememberOutcome believed = remember(e, "I think the HB became the AB in 1998.",
					proposal().entity("e1", "Nordvik HB", "organization").entity("e2", "Nordvik AB", "organization")
							.fact(new FactRef("e1", "related_to", "e2", "believed to be the same company, converted from HB to AB",
									null, null, null, List.of(), null, 0.5)));
			assertTrue(believed.applied().warnings().isEmpty(), believed.applied().warnings().toString());
			Fact f = e.facts().get(Long.parseLong(believed.applied().facts().getFirst().id().substring(2))).orElseThrow();
			assertEquals("Nordvik HB is related to Nordvik AB (believed to be the same company, converted from HB to AB) (believed)",
					f.rendering(), "the qualifier and the belief both survive");
			assertTrue(f.believed());
			assertEquals(0.5, e.facts().confidence(f), 1e-9, "the caller's number floors the computed 0.80");
			RecallResult r = recall(e, "how is Nordvik HB related to Nordvik AB");
			assertTrue(r.text().contains("believed 0.50"), r.text());
			// A firm statement later: correct with caller_confidence, and the belief is history.
			var c = e.correct(f.id(), Map.of("caller_confidence", 1.0), "Mattias confirmed it");
			assertFalse(c.replacement().rendering().contains("(believed)"), c.replacement().rendering());
			assertEquals(0.80, e.facts().confidence(c.replacement()), 1e-9);
			// A qualifier on a template without a slot is stored and reported, never dropped silently.
			RememberOutcome noSlot = remember(e, "I lead Kestrel as its steward.",
					proposal().entity("e3", "Kestrel", "project")
							.fact(new FactRef("self", "leads", "e3", "steward", null, null, null, List.of(), null, null)));
			assertTrue(noSlot.applied().warnings().stream().anyMatch(w -> w.contains("no slot")),
					noSlot.applied().warnings().toString());
		}
	}

	@Test
	@Scenario("J7")
	void aRestrictionDefinedAsANewPredicateIsNotCollapsedIntoOwns() {
		try (Engine e = engine("j7-restriction")) {
			remember(e, "I own Bergstrasse 7 in Schübelbach, Kanton Schwyz, Switzerland.",
					proposal().entity("e1", "Bergstrasse 7", "place").entity("e2", "Schübelbach", "place")
							.entity("e3", "Kanton Schwyz", "place").entity("e4", "Switzerland", "country")
							.fact("self", "owns", "e1").fact("e1", "located_in", "e2").fact("e2", "located_in", "e3")
							.fact("e3", "located_in", "e4"));
			// The definition an assistant wrote on 2026-09-10, verbatim in meaning: a closure, not ownership.
			var def = new PredicateDef("real_estate_confined_to",
					"All of the subject's real estate lies within the object; the subject owns no property anywhere else.",
					"person", "place", false, null, null, null, "medium", List.of("real estate", "property", "owns", "own", "confined"),
					"{subject}'s real estate is confined to {object}", List.of(), List.of());
			RememberOutcome o = remember(e, "Mattias only owns properties in Switzerland.",
					proposal().predicate(def).entity("e1", "Switzerland", "country")
							.fact("self", "real_estate_confined_to", "e1"));
			assertTrue(o.applied().facts().isEmpty(), "nothing asserted: " + o.applied().facts());
			assertEquals(1, o.applied().questions().size(), o.applied().toString());
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("predicate_resolution", q.get("kind"));
			assertTrue(String.valueOf(q.get("candidates")).contains("owns"), q.toString());
			assertTrue(String.valueOf(q.get("candidates")).contains("new"), q.toString());
			assertTrue(recall(e, "what does Mattias own").text().contains("owns → 1 fact"), "the store is untouched");

			remember(e, "No, that is a different claim.", null, new Resolve((String) q.get("id"), "new"));
			assertTrue(e.predicates().get("real_estate_confined_to").isPresent());
			assertFalse(e.predicates().get("real_estate_confined_to").orElseThrow().aliases().contains("owns"));
			RecallResult r = recall(e, "what does Mattias own");
			assertTrue(r.text().contains("owns → 1 fact"), "still one thing owned: " + r.text());
			assertFalse(r.text().contains("owns Switzerland"), r.text());
			assertTrue(recall(e, "where is Mattias's real estate confined to").text()
					.contains("Mattias Sandell's real estate is confined to Switzerland"));
		}
	}

	@Test
	@Scenario("J8")
	void owningAPlaceThatContainsSomethingAlreadyOwnedIsFlagged() {
		try (Engine e = engine("j8-hierarchy")) {
			remember(e, "I own the Lindenhof apartment in Willisau, Kanton Luzern, Switzerland.",
					proposal().entity("e1", "Lindenhof apartment", "place").entity("e2", "Willisau", "place")
							.entity("e3", "Kanton Luzern", "place").entity("e4", "Switzerland", "country")
							.fact("self", "owns", "e1").fact("e1", "located_in", "e2").fact("e2", "located_in", "e3")
							.fact("e3", "located_in", "e4"));
			RememberOutcome o = remember(e, "Mattias only owns properties in Switzerland.",
					proposal().entity("e1", "Switzerland", "country").fact("self", "owns", "e1"));
			assertEquals(1, o.applied().facts().size(), "stored, the caller said so, but flagged");
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("Switzerland contains Lindenhof apartment")),
					o.applied().warnings().toString());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("restriction")),
					o.applied().warnings().toString());
			// A second property in another Swiss town is not flagged: nothing owned contains it.
			RememberOutcome fine = remember(e, "I also own a flat in Zug.",
					proposal().entity("e1", "Zug flat", "place").fact("self", "owns", "e1"));
			assertTrue(fine.applied().warnings().isEmpty(), fine.applied().warnings().toString());
		}
	}

	@Test
	@Scenario("J4")
	void domainRangeMismatchIsAQuestion() {
		try (Engine e = engine("j4")) {
			remember(e, "I work at Hooli.",
					proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1"));
			RememberOutcome o = remember(e, "Hooli is my godmother.", proposal().predicate(
							new PredicateDef("godparent_of", "Sponsor at baptism.", "person", "person", false, null, null, null,
									null, List.of("godparent"), null, List.of("godmother"), List.of()))
					.fact("Hooli", "godparent_of", "self"));
			assertEquals(0, o.applied().facts().size());
			assertEquals("type_mismatch", o.applied().questions().getFirst().get("kind"));
			assertEquals("organization", o.applied().questions().getFirst().get("entity_type"));
		}
	}

	@Test
	void unregisteredPredicateWithoutDefinitionBecomesExtended() {
		try (Engine e = engine("x")) {
			RememberOutcome o = remember(e, "I consult for Acme.", proposal().fact("consults_for", "Acme"));
			assertEquals(1, o.applied().facts().size());
			assertEquals("x:consults_for", o.applied().facts().getFirst().predicate());
			assertFalse(o.applied().warnings().isEmpty());
			assertTrue(recall(e, "Acme").hits().size() >= 1, "reachable lexically");
		}
	}

	private static long id(String ref) {
		return Long.parseLong(ref.substring(ref.indexOf('-') + 1));
	}
	/** J9: a free-text qualifier is wording, not identity: the same relation said twice is one fact, corroborated. */
	@Test
	@Scenario("J9")
	void aFreeTextQualifierDoesNotMakeASecondFact() {
		try (Engine e = engine("j9-free-qualifier")) {
			remember(e, "Nordvik HB became Nordvik Software Solutions AB, I believe.",
					proposal().entity("e1", "Nordvik HB", "organization").entity("e2", "Nordvik Software Solutions AB", "organization")
							.fact(fact("e1", "related_to", "e2", "believed to be the same company, converted from HB to AB", null, null, null, null, null, null)));
			RememberOutcome again = remember(e, "As I said, Nordvik HB turned into Nordvik Software Solutions AB.",
					proposal().entity("e1", "Nordvik HB", "organization").entity("e2", "Nordvik Software Solutions AB", "organization")
							.fact(fact("e1", "related_to", "e2", "believed to be the same company, HB converted to AB, unconfirmed", null, null, null, null, null, null)));
			assertTrue(again.applied().facts().getFirst().corroborated(), "the second wording corroborates: " + again.applied().facts());
			long hb = e.entities().byRef("Nordvik HB").orElseThrow().id();
			List<Fact> related = e.facts().factsOf(hb).stream().filter(f -> "related_to".equals(f.predicate()) && f.current()).toList();
			assertEquals(1, related.size(), related.toString());
			assertEquals(2, related.getFirst().corroborations());
			// The restatement said more, so the fact took its wording; the older wording stays in obs-1.
			assertTrue(related.getFirst().qualifier().endsWith("unconfirmed"), related.getFirst().qualifier());
			assertTrue(related.getFirst().rendering().contains("unconfirmed"), related.getFirst().rendering());
			assertTrue(again.applied().facts().getFirst().rendering().contains("unconfirmed"), "the reply shows the wording taken");
			// A qualifier from a vocabulary stays identity: a mother and a father are two facts.
			remember(e, "Anna is my mother.", proposal().entity("e3", "Anna", "person").fact(fact("e3", "parent_of", "self", "mother", null, null, null, null, null, null)));
			remember(e, "Erik is my father.", proposal().entity("e4", "Erik", "person").fact(fact("e4", "parent_of", "self", "father", null, null, null, null, null, null)));
			long owner = e.entities().owner().id();
			assertEquals(2, e.facts().factsOf(owner).stream().filter(f -> "parent_of".equals(f.predicate())).count());
		}
	}

	/** J10: the same event with and without its date is one event; the date fills in. */
	@Test
	@Scenario("J10")
	void anUndatedEventIsTheDatedOneOnRecord() {
		try (Engine e = engine("j10-event-identity")) {
			RememberOutcome dated = remember(e, "I co-founded Nordvik Virtual Machines in 1998.",
					proposal().entity("e1", "Nordvik Virtual Machines", "organization").event("ev1", "co-founded", "1998", "self", "e1"));
			RememberOutcome undated = remember(e, "Back when I co-founded Nordvik Virtual Machines, we were four people.",
					proposal().entity("e1", "Nordvik Virtual Machines", "organization").event("ev1", "co-founded", null, "self", "e1"));
			assertEquals(dated.applied().events().getFirst().id(), undated.applied().events().getFirst().id(), "one event on record");
			// The other way round: an undated event first, the date arrives later and fills in.
			remember(e, "I left the company at some point.", proposal().entity("e1", "Nordvik Virtual Machines", "organization").event("ev2", "left", null, "self", "e1"));
			RememberOutcome later = remember(e, "I left Nordvik Virtual Machines in 2002.",
					proposal().entity("e1", "Nordvik Virtual Machines", "organization").event("ev2", "left", "2002", "self", "e1"));
			long id = Long.parseLong(later.applied().events().getFirst().id().substring(4));
			var ev = e.events().get(id).orElseThrow();
			assertEquals("2002-01-01", ev.validStart(), ev.toString());
			assertTrue(ev.rendering().contains("2002"), ev.rendering());
			// A different date is a different event.
			RememberOutcome other = remember(e, "I co-founded Nordvik Virtual Machines again in 2005, as a joke.",
					proposal().entity("e1", "Nordvik Virtual Machines", "organization").event("ev3", "co-founded", "2005", "self", "e1"));
			assertNotEquals(dated.applied().events().getFirst().id(), other.applied().events().getFirst().id());
		}
	}

	/** J11: a recollection filed as a plan is refused, with the way to store it instead. */
	@Test
	@Scenario("J11")
	void aRecollectionIsNotAPlan() {
		try (Engine e = engine("j11-recollection")) {
			RememberOutcome o = remember(e, "I think Nordvik Virtual Machines was created around September 1998.",
					proposal().fact("self", "considering", "that Nordvik Virtual Machines was created around 1998-09"));
			assertTrue(o.applied().facts().isEmpty(), "not stored: " + o.applied().facts());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("recollection") && w.contains("caller_confidence")),
					o.applied().warnings().toString());
			// A plan is still a plan.
			RememberOutcome plan = remember(e, "I'm considering a sabbatical in 2027.", proposal().fact("self", "considering", "a sabbatical in 2027"));
			assertEquals(1, plan.applied().facts().size());
		}
	}
}
