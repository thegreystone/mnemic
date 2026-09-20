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
package se.hirt.mnemic.recall;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService.Mention;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.Query.Bound;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Query#bind}: how a question's relation words are placed, bound to their subjects, and given the entities of
 * their stretch of the question. Pure: hand-built entities, predicates, and mentions, no store.
 */
class QueryBindTest {

	private static final Entity MATTIAS = new Entity(1, "Mattias Sandell", "person", null, null);
	private static final Entity ANNA = new Entity(2, "Anna Lindqvist", "person", null, null);
	private static final Entity KONRAD = new Entity(3, "Konrad Nyberg", "person", null, null);
	private static final Entity GUNILLA = new Entity(4, "Gunilla Nyberg", "person", null, null);

	private static Predicate predicate(String name) {
		return new Predicate(name, null, List.of("person"), List.of("person"), false, null, false, null, "low",
				List.of(), "{subject} " + name + " {object}", List.of(), List.of(), List.of(), null, true, false,
				false);
	}

	private static final Predicate PARENT_OF = predicate("parent_of");
	private static final Predicate SIBLING_OF = predicate("sibling_of");
	private static final Predicate SPOUSE_OF = predicate("spouse_of");

	/** Mentions at the token positions the names take in {@code text}, for the names it contains. */
	private static List<Mention> mentions(String text, Entity ... entities) {
		List<String> tokens = se.hirt.mnemic.knowledge.Names.tokens(text);
		var out = new java.util.ArrayList<Mention>();
		for (Entity e : entities) {
			List<String> name = se.hirt.mnemic.knowledge.Names.tokens(e.name());
			List<String> first = name.subList(0, 1);
			for (int i = 0; i < tokens.size(); i++) {
				if (i + name.size() <= tokens.size() && tokens.subList(i, i + name.size()).equals(name)) {
					out.add(new Mention(e, i, i + name.size()));
					i += name.size() - 1;
				} else if (tokens.subList(i, i + 1).equals(first)) {
					out.add(new Mention(e, i, i + 1));
				}
			}
		}
		return out;
	}

	@Test
	void aRelationWordBindsToTheNameBeforeItAndItsStretchHoldsTheRest() {
		String text = "Mattias's parents Konrad and Gunilla, and Anna's siblings";
		List<Cue> cues = List.of(new Cue(SIBLING_OF, null, "siblings", "any"),
				new Cue(PARENT_OF, null, "parents", "subject"));
		List<Bound> bound = Query.bind(text, cues, mentions(text, MATTIAS, ANNA, KONRAD, GUNILLA),
				List.of(MATTIAS, ANNA, KONRAD, GUNILLA));
		assertEquals(2, bound.size());
		Bound parents = bound.get(0);
		assertEquals("parent_of", parents.cue().predicate().name(), "in the question's order");
		Bound siblings = bound.get(1);
		assertEquals("sibling_of", siblings.cue().predicate().name());
		assertEquals(List.of(ANNA), siblings.subjects());
		assertEquals(List.of(), siblings.others(), "Konrad and Gunilla belong to the parents");
		assertEquals(List.of(MATTIAS), parents.subjects());
		assertEquals(List.of(KONRAD, GUNILLA), parents.others());
		assertEquals(1, parents.start());
		assertEquals(2, parents.end());
	}

	@Test
	void ofBindsTheNameAfterTheWord() {
		String text = "the parents of Anna";
		List<Bound> bound = Query.bind(text, List.of(new Cue(PARENT_OF, null, "parents", "subject")),
				mentions(text, ANNA), List.of(ANNA));
		assertEquals(List.of(ANNA), bound.getFirst().subjects());
		assertEquals(List.of(), bound.getFirst().others());
	}

	@Test
	void anUnboundWordKeepsTheWholeQuestion() {
		String text = "who are the siblings of the parents of Anna and Konrad";
		List<Cue> cues = List.of(new Cue(SIBLING_OF, null, "siblings", "any"),
				new Cue(PARENT_OF, null, "parents", "subject"));
		List<Bound> bound = Query.bind(text, cues, mentions(text, ANNA, KONRAD), List.of(ANNA, KONRAD));
		Bound siblings = bound.get(0);
		assertFalse(siblings.hasSubject(), "\"siblings of the parents\" names no entity");
		assertEquals(List.of(), siblings.others(), "its stretch ends where the parents' begins");
		Bound parents = bound.get(1);
		assertEquals(List.of(ANNA), parents.subjects());
		assertEquals(List.of(KONRAD), parents.others());
	}

	@Test
	void aWordTakenByOneCueIsNotTakenByAnotherButGroupMembersShareTheirs() {
		String text = "Anna's family";
		List<Cue> cues = List.of(new Cue(PARENT_OF, null, "family", "any", "family"),
				new Cue(SPOUSE_OF, null, "family", "any", "family"), new Cue(SIBLING_OF, null, "family", "any"));
		List<Bound> bound = Query.bind(text, cues, mentions(text, ANNA), List.of(ANNA));
		assertEquals(2, bound.size(), "the two members share the word; the cue on its own vocabulary finds it taken");
		assertTrue(bound.stream().allMatch(b -> b.subjects().equals(List.of(ANNA))));
		assertTrue(bound.stream().allMatch(b -> b.cue().viaGroup()));
	}

	@Test
	void aRepeatedWordIsBoundAtEachOccurrenceThatHasASubject() {
		String text = "Anna's parents and Konrad's parents";
		List<Bound> bound = Query.bind(text, List.of(new Cue(PARENT_OF, null, "parents", "subject")),
				mentions(text, ANNA, KONRAD), List.of(ANNA, KONRAD));
		assertEquals(2, bound.size());
		assertEquals(List.of(ANNA), bound.get(0).subjects());
		assertEquals(List.of(), bound.get(0).others(), "Konrad belongs to the second parents");
		assertEquals(List.of(KONRAD), bound.get(1).subjects());
		// Without a subject, once; and not at all beside the same relation asked with one.
		String twice = "who are the parents, and who are the parents";
		assertEquals(1, Query.bind(twice, List.of(new Cue(PARENT_OF, null, "parents", "subject")), List.of(), List.of())
				.size());
		String beside = "the parents, and Anna's parents";
		List<Bound> one = Query.bind(beside, List.of(new Cue(PARENT_OF, null, "parents", "subject")),
				mentions(beside, ANNA), List.of(ANNA));
		assertEquals(1, one.size());
		assertEquals(List.of(ANNA), one.getFirst().subjects());
	}

	@Test
	void aWordAfterAndSharesTheSubjectBeforeIt() {
		String text = "Anna's parents and siblings, and the cousins";
		List<Cue> cues = List.of(new Cue(SIBLING_OF, null, "siblings", "any"),
				new Cue(PARENT_OF, null, "parents", "subject"),
				new Cue(predicate("cousin_of"), null, "cousins", "any"));
		List<Bound> bound = Query.bind(text, cues, mentions(text, ANNA), List.of(ANNA));
		assertEquals(3, bound.size());
		Bound parents = bound.stream().filter(b -> "parent_of".equals(b.cue().predicate().name())).findFirst()
				.orElseThrow();
		Bound siblings = bound.stream().filter(b -> "sibling_of".equals(b.cue().predicate().name())).findFirst()
				.orElseThrow();
		Bound cousins = bound.stream().filter(b -> "cousin_of".equals(b.cue().predicate().name())).findFirst()
				.orElseThrow();
		assertEquals(List.of(ANNA), parents.subjects());
		assertEquals(List.of(ANNA), siblings.subjects(), "\"and siblings\" are Anna's too");
		assertFalse(cousins.hasSubject(), "\"and the cousins\" has a word between: nobody's in particular");
		assertEquals(List.of(), parents.others());
	}

	@Test
	void aFamilyNameBindsEveryMemberItSpots() {
		String text = "where is my raspberry pi kept";
		Entity pi4 = new Entity(7, "Raspberry Pi 4", "thing", null, null);
		Entity pi5 = new Entity(8, "Raspberry Pi 5", "thing", null, null);
		List<Mention> mentions = List.of(new Mention(MATTIAS, 2, 3), new Mention(pi4, 3, 5), new Mention(pi5, 3, 5));
		List<Bound> bound = Query.bind(text, List.of(new Cue(predicate("kept_at"), null, "kept", "any")), mentions,
				List.of(MATTIAS, pi4, pi5));
		assertEquals(List.of(pi4, pi5), bound.getFirst().subjects());
		assertEquals(List.of(MATTIAS), bound.getFirst().others());
	}
}
