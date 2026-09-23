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
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * EVALUATION.md T19–T21: the same name under a kind nobody has placed is the same thing; a homonym of another kind is
 * said, listed, and foldable; an entity nothing names can be removed; aliases are listed once and replaced as
 * documented. The round a caller lost to a second car, typed 'vehicle' where the first was a 'thing'.
 */
class DuplicateEntityTest {

	private static final String CAR = "Polestar 4 Long Range Dual Motor Prime";

	@Test
	@Scenario("T19")
	void aKindNobodyHasPlacedDoesNotMakeASecondEntity() {
		try (Engine e = engine("t19-unplaced-kind")) {
			remember(e, "I ordered a Polestar 4 Long Range Dual Motor Prime.",
					proposal().entity("e1", CAR, "thing", "Polestar 4").fact("self", "owns", "e1"));
			long car = e.entities().byRef("Polestar 4").orElseThrow().id();
			// The same name, typed as a kind the store has never heard of: 'conveyance' cannot tell it apart.
			RememberOutcome o = remember(e, "The Polestar 4 is my only vehicle.",
					proposal().entity("e1", CAR, "conveyance").fact("self", "prefers", "e1"));
			assertEquals("alias", o.applied().entities().getFirst().resolution(), o.applied().toString());
			assertEquals(car, e.entities().byRef(CAR).orElseThrow().id(), "one car, not two");
			assertEquals(1, e.entities().spot("Polestar 4").size());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("nobody has placed")),
					"said, not silent: " + o.applied().warnings());
			// The word is registered from use and asked about, so the user can place it.
			assertTrue(e.entityTypes().unplaced("conveyance"));
			Map<String, Object> q = o.applied().questions().stream().filter(x -> "type_kind".equals(x.get("kind")))
					.findFirst().orElseThrow(() -> new AssertionError(o.applied().questions().toString()));
			assertEquals("conveyance", q.get("subject"), q.toString());
			e.answer(List.of(new Resolve(q.get("id").toString(), "thing")));
			assertFalse(e.entityTypes().unplaced("conveyance"));
			// Placed under thing, a vehicle is a thing: the next mention resolves without a word, and the more
			// specific kind wins.
			RememberOutcome again = remember(e, "The Polestar 4 is a vehicle I like.",
					proposal().entity("e1", CAR, "conveyance").fact("self", "prefers", "e1"));
			assertEquals("alias", again.applied().entities().getFirst().resolution());
			assertTrue(again.applied().warnings().isEmpty(), again.applied().warnings().toString());
			assertEquals("conveyance", e.entities().byRef(CAR).orElseThrow().type());
			assertEquals(1, e.entities().spot("Polestar 4").size());
			// The same name resolves without a type too, as it always did.
			RememberOutcome untyped = remember(e, "The Polestar 4 again.",
					proposal().entity("e1", "Polestar 4", null).fact("self", "prefers", "e1"));
			assertEquals(car, e.entities().byRef(untyped.applied().entities().getFirst().id()).orElseThrow().id());
		}
	}

	@Test
	@Scenario("T20")
	void aHomonymOfAnotherKindIsSaidListedAndFoldable() {
		try (Engine e = engine("t20-homonym")) {
			remember(e, "I work at Mercury.",
					proposal().entity("e1", "Mercury", "organization").fact("self", "works_at", "e1"));
			long company = e.entities().byRef("Mercury").orElseThrow().id();
			// A place named Mercury is another kind of thing (EVALUATION.md B3): asked, the company first.
			RememberOutcome o = remember(e, "I live in Mercury.",
					proposal().entity("e1", "Mercury", "place").fact("self", "lives_in", "e1"));
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"), q.toString());
			assertTrue(q.toString().contains("ent-" + company), q.toString());
			e.answer(List.of(new Resolve(q.get("id").toString(), "new")));
			assertEquals(2, e.entities().spot("Mercury").size());
			long town = e.entities().spot("Mercury").stream().filter(x -> x.id() != company).findFirst().orElseThrow()
					.id();
			// Consolidate lists the collision and merges nothing on its own.
			var c = e.consolidate(true);
			assertTrue(c.merges().isEmpty(), c.merges().toString());
			assertEquals(1, c.nameCollisions().size(), c.nameCollisions().toString());
			Map<String, Object> collision = c.nameCollisions().getFirst();
			assertEquals("Mercury", collision.get("shared"));
			assertEquals("ent-" + company, collision.get("entity"));
			assertEquals("ent-" + town, collision.get("and"));
			assertEquals("correct(ent-" + town + ", {\"merge_into\": \"ent-" + company + "\"})",
					collision.get("if_one"));
			// The user says they are one: the correction folds the town into the company.
			Map<String, Object> folded = e.correctEntity(town, Map.of("merge_into", "ent-" + company),
					"the town is the company's site");
			assertEquals("ent-" + company, folded.get("entity"), folded.toString());
			assertTrue(folded.containsKey("merged"), folded.toString());
			assertEquals(company, e.entities().get(town).orElseThrow().id(), "the old id forwards to the survivor");
			assertEquals(1, e.entities().spot("Mercury").size());
			assertTrue(e.facts().factsOf(company).stream().anyMatch(f -> "lives_in".equals(f.predicate())),
					"the facts moved");
			assertTrue(e.consolidate(true).nameCollisions().isEmpty());
			// Refusals: into itself, and the owner away.
			assertThrows(MnemicException.class,
					() -> e.correctEntity(company, Map.of("merge_into", "ent-" + company), "no"));
			assertThrows(MnemicException.class,
					() -> e.correctEntity(e.entities().owner().id(), Map.of("merge_into", "ent-" + company), "no"));
		}
	}

	@Test
	@Scenario("T20")
	void anEntityNothingNamesCanBeForgotten() {
		try (Engine e = engine("t20-forget-entity")) {
			var o = remember(e, "I own a Segway Navimow.",
					proposal().entity("e1", "Segway Navimow", "thing").fact("self", "owns", "e1"));
			long mower = e.entities().byRef("Segway Navimow").orElseThrow().id();
			// Named by a fact: refused, with the fact and the way out.
			MnemicException refused = assertThrows(MnemicException.class, () -> e.forgetEntity(mower));
			assertTrue(refused.getMessage().contains("1 fact (f-"), refused.getMessage());
			assertTrue(refused.getMessage().contains("merge_into"), refused.getMessage());
			assertTrue(e.entities().get(mower).isPresent());
			// The observation forgotten with its entities kept: the entity stands alone, and can go.
			e.forget(o.observation().observationId(), true);
			assertTrue(e.entities().get(mower).isPresent(), "kept, as asked");
			Map<String, Object> gone = e.forgetEntity(mower);
			assertEquals(true, gone.get("removed"), gone.toString());
			assertEquals("Segway Navimow", gone.get("name"));
			assertTrue(e.entities().get(mower).isEmpty());
			assertTrue(e.entities().spot("Segway Navimow").isEmpty());
			// The owner is never removed; an id that names nothing is not found.
			assertThrows(MnemicException.class, () -> e.forgetEntity(e.entities().owner().id()));
			assertThrows(MnemicException.class, () -> e.forgetEntity(mower));
		}
	}

	@Test
	@Scenario("T21")
	void aliasesAreListedOnceAndReplacedAsDocumented() {
		try (Engine e = engine("t21-aliases")) {
			remember(e, "Anna is a friend.",
					proposal().entity("e1", "Anna Lindqvist", "person").fact("self", "knows", "e1"));
			Entity anna = e.entities().byRef("Anna Lindqvist").orElseThrow();
			// A name with a hyphen is stored in its word form too, under the same alias: listed once.
			Map<String, Object> renamed = e.correctEntity(anna.id(), Map.of("name", "Anna Lindqvist-Berg"), "married");
			List<String> aliases = e.entities().aliases(anna.id());
			assertEquals(aliases.size(), new HashSet<>(aliases).size(), "each alias once: " + aliases);
			assertTrue(aliases.containsAll(List.of("Anna Lindqvist", "Anna Lindqvist-Berg")), aliases.toString());
			@SuppressWarnings("unchecked")
			List<String> shown = (List<String>) ((Map<?, ?>) renamed.get("after")).get("aliases");
			assertEquals(shown.size(), new HashSet<>(shown).size(), "and once in the reply: " + shown);
			// aliases is the list to keep: the old name goes, the entity's own name stays.
			e.correctEntity(anna.id(), Map.of("aliases", List.of("Anna L-B")), "as she signs");
			assertEquals(List.of("Anna Lindqvist-Berg", "Anna L-B"), e.entities().aliases(anna.id()));
			assertTrue(e.entities().byRef("Anna Lindqvist").isEmpty(), "the maiden name is no longer an alias");
			assertEquals(anna.id(), e.entities().byRef("Anna L-B").orElseThrow().id());
		}
	}

	@Test
	@Scenario("T22")
	void exactMatchesAreTheFirstCandidatesWhateverTheirKindAndAskedOnce() {
		try (Engine e = engine("t22-candidates")) {
			remember(e, "Kinds.",
					proposal().entityType(new EntityTypeDef("equipment", "Gear.", "thing", List.of(), List.of(), null))
							.entityType(new EntityTypeDef("service", "A service.", null, List.of(), List.of(), null)));
			remember(e, "I own a Tacx trainer and use Tacx Premium.",
					proposal().entity("e1", "Tacx trainer", "equipment").entity("e2", "Tacx Premium", "service")
							.fact("self", "owns", "e1").fact("self", "uses", "e2"));
			long trainer = e.entities().byRef("Tacx trainer").orElseThrow().id();
			// Typed as a product, the exact name of another placed kind is the first candidate, at 1.0.
			RememberOutcome o = remember(e, "The Tacx trainer needs a firmware update.",
					proposal().entity("e1", "Tacx trainer", "product").fact("self", "prefers", "e1"));
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"), q.toString());
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> candidates = (List<Map<String, Object>>) q.get("candidates");
			assertEquals("ent-" + trainer, candidates.getFirst().get("id"), q.toString());
			assertEquals(1.0, candidates.getFirst().get("score"), q.toString());
			assertTrue(candidates.stream().anyMatch(c -> "new".equals(c.get("id"))), q.toString());
			// Answered with the entity, the same mention never asks again.
			e.answer(List.of(new Resolve(q.get("id").toString(), "ent-" + trainer)));
			RememberOutcome again = remember(e, "The Tacx trainer is great.",
					proposal().entity("e1", "Tacx trainer", "product").fact("self", "prefers", "e1"));
			assertTrue(again.applied().questions().isEmpty(), "asked once: " + again.applied().questions());
			assertEquals("ent-" + trainer, again.applied().entities().getFirst().id());
			assertEquals("answered", again.applied().entities().getFirst().resolution());
			assertEquals(1, e.entities().spot("Tacx trainer").size());
		}
	}

	@Test
	@Scenario("T22")
	void thePredicateSaysWhatKindOfThingAnUntypedNameIs() {
		try (Engine e = engine("t22-expected-kind")) {
			remember(e, "Zurich Insurance is a customer.",
					proposal().entity("e1", "Zurich Insurance", "organization").fact("self", "knows", "e1"));
			// The object of lives_in is a place: an insurer of the same letters is no candidate, and the town is
			// created as a place, nobody having typed it.
			RememberOutcome o = remember(e, "I live in Zürich.", proposal().fact("self", "lives_in", "Zürich"));
			assertTrue(o.applied().questions().isEmpty(), o.applied().questions().toString());
			Entity zurich = e.entities().byRef("Zürich").orElseThrow();
			assertEquals("place", zurich.type());
			assertEquals(1, o.applied().facts().size(), o.applied().toString());
			// A kind nobody has placed ("city") gets the same shaping: the town is a candidate, the insurer is not.
			RememberOutcome city = remember(e, "I also like Zurich Airport.",
					proposal().entity("e1", "Zurich Airport", "city").fact("self", "lives_in", "e1"));
			Map<String, Object> asked = city.applied().questions().getFirst();
			assertEquals("entity_resolution", asked.get("kind"), asked.toString());
			assertTrue(asked.toString().contains(zurich.ref()) && !asked.toString().contains("Zurich Insurance"),
					asked.toString());
			// Typed by the caller as a placed kind, the insurer is offered as before.
			RememberOutcome typed = remember(e, "Zurich Re is a customer too.",
					proposal().entity("e1", "Zurich Re", "organization").fact("self", "knows", "e1"));
			assertEquals("entity_resolution", typed.applied().questions().getFirst().get("kind"),
					typed.applied().questions().toString());
		}
	}

	@Test
	@Scenario("T23")
	void aShorterNameRefersToTheWholeFamily() {
		try (Engine e = engine("t23-family")) {
			remember(e, "Where my gadgets are kept.",
					proposal().predicate(new PredicateDef("kept_at", "The place where the subject is kept.", "thing",
							"place", false, null, null, null, "medium", List.of("kept", "stored"),
							"{subject} is kept at {object}", List.of(), List.of())));
			RememberOutcome stored = remember(e,
					"The Raspberry Pi 4 is in the office and the Raspberry Pi 5 in the lab.",
					proposal().entity("e1", "Raspberry Pi 4", "thing").entity("e2", "Raspberry Pi 5", "thing")
							.entity("p1", "the office", "place").entity("p2", "the lab", "place")
							.fact("e1", "kept_at", "p1").fact("e2", "kept_at", "p2").fact("self", "owns", "e1")
							.fact("self", "owns", "e2"));
			assertEquals(4, stored.applied().facts().size(), stored.applied().toString());
			// At recall, the family name spots every member and the reader sorts them out.
			long owner = e.entities().owner().id();
			assertEquals(2,
					e.entities().spot("where is my raspberry pi").stream().filter(x -> x.id() != owner).count());
			RecallResult where = recall(e, "where is my raspberry pi kept");
			assertEquals(2, where.structured().facts().size(), where.text());
			assertTrue(where.text().contains("Raspberry Pi 4 is kept at the office")
					&& where.text().contains("Raspberry Pi 5 is kept at the lab"), where.text());
			// The number spots one.
			RecallResult five = recall(e, "how many raspberry pi 5 do I own");
			assertEquals(1, five.structured().facts().size(), five.text());
			assertTrue(five.text().contains("Raspberry Pi 5"), five.text());
			// At write time, the family name fits both: asked, never merged.
			RememberOutcome o = remember(e, "My Raspberry Pi needs a new SD card.",
					proposal().entity("e1", "Raspberry Pi", "thing").fact("self", "prefers", "e1"));
			Map<String, Object> q = o.applied().questions().getFirst();
			assertEquals("entity_resolution", q.get("kind"), q.toString());
			assertTrue(q.toString().contains("Raspberry Pi 4") && q.toString().contains("Raspberry Pi 5"),
					q.toString());
			// A plain word is not a family: nothing spotted for a number alone or a stopword.
			assertTrue(e.entities().spot("where is 5").isEmpty());
		}
	}

	@Test
	@Scenario("T24")
	void anUndatedEventIsInNoYear() {
		try (Engine e = engine("t24-undated-event")) {
			remember(e, "I moved to Schübelbach.", proposal().entity("e1", "Schübelbach", "place")
					.event("ev1", "moved", null, "self", "e1").fact("self", "lives_in", "e1"));
			remember(e, "I joined Hooli in 2015.", proposal().entity("e1", "Hooli", "organization")
					.event("ev1", "joined", "2015", "self", "e1").fact("self", "works_at", "e1"));
			RecallResult now = recall(e, "when did Mattias move");
			assertTrue(now.events().stream().anyMatch(ev -> "moved".equals(ev.type())), now.text());
			RecallResult then = recall(e, "when did Mattias move", Instant.parse("2010-06-01T00:00:00Z"));
			assertTrue(then.events().stream().noneMatch(ev -> "moved".equals(ev.type())),
					"an undated event is not placed in 2010: " + then.text());
			RecallResult joined = recall(e, "when did Mattias join Hooli", Instant.parse("2016-01-01T00:00:00Z"));
			assertTrue(joined.events().stream().anyMatch(ev -> "joined".equals(ev.type())), joined.text());
		}
	}
}
