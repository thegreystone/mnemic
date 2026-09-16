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
import se.hirt.mnemic.Scenario;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.*;

/** EVALUATION.md section H: deployment and isolation, plus scenario family N (two processes on one data home). */
class DeploymentTest {

	@Test
	@Scenario("H1")
	void separateDataHomesAreIsolated() {
		try (Engine a = engine("h1-a"); Engine b = engine("h1-b")) {
			remember(a, "Project X is secret.");
			assertTrue(recall(b, "Project X").hits().isEmpty());
			assertEquals(1, recall(a, "Project X").hits().size());
		}
	}

	@Test
	@Scenario("H4")
	void theOwnersConfiguredIdentityResolvesToTheOwner() {
		try (Engine e = engine("h4-identity")) {
			remember(e, "I work at Hooli.", proposal().entity("e1", "Hooli", "organization").fact("works_at", "e1"));
			// A handle, an address, and the surname alone in a query all spot the owner.
			assertTrue(recall(e, "where does sandell-example work").structured().matched());
			assertTrue(recall(e, "where does mattias@example.com work").structured().matched());
			assertTrue(recall(e, "where does Sandell work").structured().matched());
			assertTrue(recall(e, "where does @sandell_example work").structured().matched());
			// A proposal that names the owner by an address (a commit author) resolves to the owner, not a new person.
			RememberOutcome o = remember(e, "Commit by mattias@example.com: switched the build to Maven.",
					proposal().entity("e1", "Maven", "technology").fact("mattias@example.com", "uses", "e1"));
			assertEquals(1, o.applied().facts().size(), o.applied().warnings().toString());
			Entity owner = e.entities().owner();
			assertTrue(e.facts().factsOf(owner.id()).stream().anyMatch(f -> "uses".equals(f.predicate())),
					"the fact is the owner's");
			assertEquals(owner.id(), e.entities().byRef("mattias@example.com").orElseThrow().id(),
					"no second person was minted for the address");
			// The identity is visible.
			assertTrue(e.entities().aliases(owner.id()).contains("sandell-example"));
		}
	}

	@Test
	@Scenario("H2")
	void indexesAreRebuildable() {
		Path home = fresh("h2");
		try (Engine e = engine(home)) {
			remember(e, "I work at Hooli.");
			// Corrupt the derived index the way an interrupted write or a deleted index directory would.
			e.database().write(tx -> {
				tx.execute("INSERT INTO observation_fts(observation_fts) VALUES ('delete-all')");
				return null;
			});
			assertTrue(recall(e, "Hooli").hits().isEmpty(), "index really is gone");
			e.database().rebuildIndexes();
			assertEquals(1, recall(e, "Hooli").hits().size(), "rebuilt from the observation table");
		}
	}

	/** Scenario family N: a second server process (here a second Engine) on the same data home sees the writes. */
	@Test
	@Scenario("N1")
	void twoEnginesOnOneDataHomeInterleaveWrites() {
		Path home = fresh("n1");
		try (Engine a = engine(home); Engine b = engine(home)) {
			remember(a, "Written by the first process.");
			remember(b, "Written by the second process.");
			assertEquals(2, a.observations().count());
			assertEquals(2, b.observations().count());
			assertEquals(1, recall(a, "second").hits().size());
			assertEquals(1, recall(b, "first").hits().size());
		}
	}

	@Test
	void reopeningADataHomeKeepsEverything() {
		Path home = fresh("reopen");
		long id;
		try (Engine e = engine(home)) {
			id = remember(e, "I moved to Switzerland in 2014.").observationId();
		}
		try (Engine e = engine(home)) {
			assertEquals("I moved to Switzerland in 2014.", e.observations().get(id).orElseThrow().text());
			assertEquals(1, recall(e, "Switzerland").hits().size());
			assertEquals(23, e.database().schemaVersion());
		}
	}
}
