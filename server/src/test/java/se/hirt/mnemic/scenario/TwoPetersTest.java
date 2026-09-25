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
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.recall;
import static se.hirt.mnemic.TestHomes.remember;

/**
 * A "Peter" on record and a "Peter Andersson" who turns up later (2026-09-24). The store asks whether they are the
 * same; answered the same, the entity takes the fuller name; answered someone else, a later bare "Peter" is asked once
 * more, since it now fits either, and that answer settles the mentions after it.
 */
class TwoPetersTest {

	private static Map<String, Object> firstQuestion(RememberOutcome o) {
		assertFalse(o.applied().questions().isEmpty(), "a question was expected: " + o.applied());
		return o.applied().questions().getFirst();
	}

	@SuppressWarnings("unchecked")
	private static List<String> candidateIds(Map<String, Object> q) {
		return ((List<Map<String, Object>>) q.get("candidates")).stream().map(c -> String.valueOf(c.get("id")))
				.toList();
	}

	@Test
	void theSamePersonUnderAFullerNameTakesThatName() {
		try (Engine e = engine("two-peters-same")) {
			remember(e, "Peter is my colleague; he lives in Zug.", proposal().entity("p", "Peter", "person")
					.entity("z", "Zug", "place").fact("self", "knows", "p").fact("p", "lives_in", "z"));
			RememberOutcome full = remember(e, "Peter Andersson joined Hooli in 2020.",
					proposal().entity("pa", "Peter Andersson", "person").entity("h", "Hooli", "organization").fact("pa",
							"works_at", "h"));
			Map<String, Object> q = firstQuestion(full);
			assertEquals("entity_resolution", q.get("kind"));
			assertEquals("Peter Andersson", q.get("subject"));
			String peter = candidateIds(q).getFirst();
			assertTrue(peter.startsWith("ent-"), q.toString());

			// The same person: the entity is now Peter Andersson, "Peter" its alias, and every rendering follows.
			RememberOutcome same = remember(e, "He starts on Monday.", proposal(),
					new Resolve(String.valueOf(q.get("id")), peter));
			Map<String, Object> answer = same.resolved().getFirst();
			assertEquals("answered", answer.get("status"));
			Map<?, ?> renamed = (Map<?, ?>) answer.get("renamed");
			assertEquals("Peter", renamed.get("from"), answer.toString());
			assertEquals("Peter Andersson", renamed.get("to"), answer.toString());
			assertTrue(((Number) renamed.get("rerendered_facts")).intValue() >= 2, answer.toString());
			Entity ent = e.entities().byRef(peter).orElseThrow();
			assertEquals("Peter Andersson", ent.name());
			assertTrue(e.entities().aliases(ent.id()).contains("Peter"), e.entities().aliases(ent.id()).toString());
			String home = recall(e, "where does Peter Andersson live").text();
			assertTrue(home.contains("Peter Andersson lives in Zug"), home);
			// The held fact landed on him, and a bare "Peter" reaches him without a question.
			assertTrue(recall(e, "Peter's employer").text().contains("Peter Andersson works at Hooli"));
			RememberOutcome bare = remember(e, "Peter says hello.",
					proposal().entity("p3", "Peter", "person").fact("p3", "knows", "self"));
			assertTrue(bare.applied().questions().isEmpty(), bare.applied().questions().toString());
			assertEquals(peter, bare.applied().entities().getFirst().id());
		}
	}

	@Test
	void aSecondPeterMakesABareMentionAmbiguousUntilAnsweredOnce() {
		try (Engine e = engine("two-peters-new")) {
			remember(e, "Peter is my colleague; he lives in Zug.", proposal().entity("p", "Peter", "person")
					.entity("z", "Zug", "place").fact("self", "knows", "p").fact("p", "lives_in", "z"));
			RememberOutcome full = remember(e, "Peter Andersson joined Hooli in 2020.",
					proposal().entity("pa", "Peter Andersson", "person").entity("h", "Hooli", "organization").fact("pa",
							"works_at", "h"));
			Map<String, Object> q = firstQuestion(full);
			String firstPeter = candidateIds(q).getFirst();
			RememberOutcome other = remember(e, "He is new here.", proposal(),
					new Resolve(String.valueOf(q.get("id")), "new"));
			String secondPeter = String.valueOf(other.resolved().getFirst().get("entity"));
			assertFalse(firstPeter.equals(secondPeter));
			assertEquals("Peter", e.entities().byRef(firstPeter).orElseThrow().name(), "nothing renamed");

			// A bare "Peter" now fits either: asked once, the one on record first.
			RememberOutcome bare = remember(e, "Peter called about the flat.",
					proposal().entity("p2", "Peter", "person").fact("p2", "knows", "self"));
			Map<String, Object> again = firstQuestion(bare);
			assertEquals("Peter", again.get("subject"));
			assertEquals(List.of(firstPeter, secondPeter, "new"), candidateIds(again), again.toString());
			assertTrue(bare.applied().facts().isEmpty(), "held until answered: " + bare.applied().facts());

			// Answered, and every later bare mention goes the same way without a question.
			RememberOutcome settled = remember(e, "It was the Zug one.", proposal(),
					new Resolve(String.valueOf(again.get("id")), firstPeter));
			assertEquals("answered", settled.resolved().getFirst().get("status"));
			RememberOutcome later = remember(e, "Peter says hello.",
					proposal().entity("p3", "Peter", "person").fact("p3", "knows", "self"));
			assertTrue(later.applied().questions().isEmpty(), later.applied().questions().toString());
			assertEquals(firstPeter, later.applied().entities().getFirst().id());
			// The full name still reaches the other one, unasked.
			RememberOutcome fullAgain = remember(e, "Peter Andersson moved to Bern.",
					proposal().entity("pa2", "Peter Andersson", "person").entity("b", "Bern", "place").fact("pa2",
							"lives_in", "b"));
			assertTrue(fullAgain.applied().questions().isEmpty(), fullAgain.applied().questions().toString());
			assertEquals(secondPeter, fullAgain.applied().entities().getFirst().id());
		}
	}
}
