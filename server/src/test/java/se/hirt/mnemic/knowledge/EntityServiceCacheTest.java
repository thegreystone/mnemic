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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.knowledge;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.knowledge.EntityService.Mention;
import se.hirt.mnemic.knowledge.EntityService.Resolved;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.fresh;
import static se.hirt.mnemic.TestHomes.proposal;
import static se.hirt.mnemic.TestHomes.remember;

/** Verifies {@link EntityService}'s cached mention index and single-JOIN fuzzy lookup. */
class EntityServiceCacheTest {

	@Test
	void mentionsUsesCachedIndexAcrossRepeatedQueriesAndRefreshesOnLocalCommit() {
		try (Engine e = engine("entity-mention-cache")) {
			remember(e, "Zurich Insurance Company employs Anna Lindqvist, Erik Berg, and Erik Holm.",
					proposal().entity("org", "Zurich Insurance Company", "organization")
							.entity("anna", "Anna Lindqvist", "person").entity("e1", "Erik Berg", "person")
							.entity("e2", "Erik Holm", "person").entity("pi4", "Raspberry Pi 4", "device")
							.entity("pi5", "Raspberry Pi 5", "device").fact("anna", "works_at", "org")
							.fact("e1", "works_at", "org").fact("e2", "works_at", "org").fact("owns", "pi4")
							.fact("owns", "pi5"));

			EntityService es = e.entities();
			assertEquals(0, es.mentionIndexLoads());

			// Empty/blank query skips DB work completely.
			assertTrue(es.mentions("   ").isEmpty());
			assertEquals(0, es.mentionIndexLoads());

			// First real query loads the index once; covers multi-word, unique first name, self, and model family.
			List<Mention> first = es.mentions("Does Anna use my Raspberry Pi at Zurich Insurance Company?");
			Set<String> names = first.stream().map(m -> m.entity().name()).collect(Collectors.toSet());
			assertTrue(names.contains("Anna Lindqvist"), names.toString());
			assertTrue(names.contains("Zurich Insurance Company"), names.toString());
			assertTrue(names.contains("Raspberry Pi 4"), names.toString());
			assertTrue(names.contains("Raspberry Pi 5"), names.toString());
			assertTrue(names.contains(es.owner().name()), names.toString());
			assertEquals(1, es.mentionIndexLoads());

			// Ambiguous first name ("Erik") returns no match, and reuses the cached index.
			assertTrue(es.mentions("Where does Erik live?").isEmpty());
			assertEquals(1, es.mentionIndexLoads(), "repeated mention lookups must hit the cached index");

			// Exact full name disambiguates Erik Berg while still reusing the cached index.
			List<Mention> erik = es.mentions("Where does Erik Berg live?");
			assertEquals(List.of("Erik Berg"), erik.stream().map(m -> m.entity().name()).toList());
			assertEquals(1, es.mentionIndexLoads());

			// A local write increments Database.localVersion(), refreshing the index on the next lookup.
			remember(e, "Freja Lind works at Hooli.", proposal().entity("f", "Freja Lind", "person")
					.entity("h", "Hooli", "organization").fact("f", "works_at", "h"));
			List<Mention> afterWrite = es.mentions("Where does Freja work?");
			assertEquals(List.of("Freja Lind"), afterWrite.stream().map(m -> m.entity().name()).toList());
			assertEquals(2, es.mentionIndexLoads());

			// Subsequent lookups after refresh stay on the cached index.
			assertEquals(List.of("Hooli"), es.mentions("What is Hooli?").stream().map(m -> m.entity().name()).toList());
			assertEquals(2, es.mentionIndexLoads());
		}
	}

	@Test
	void mentionsIsRollbackSafeInsideFailedWriteTransaction() {
		try (Engine e = engine("entity-mention-rollback")) {
			remember(e, "I work at Hooli.", proposal().entity("h", "Hooli", "organization").fact("works_at", "h"));
			EntityService es = e.entities();
			assertEquals(List.of("Hooli"), es.mentions("What is Hooli?").stream().map(m -> m.entity().name()).toList());
			long loadsBefore = es.mentionIndexLoads();

			assertThrows(IllegalStateException.class, () -> e.database().write(tx -> {
				es.resolve("GhostCorp", "organization", List.of(), null);
				// Inside the open write tx, mentions bypasses the cache and sees the uncommitted entity.
				assertEquals(List.of("GhostCorp"),
						es.mentions("What is GhostCorp?").stream().map(m -> m.entity().name()).toList());
				throw new IllegalStateException("abort tx");
			}));

			// After rollback, GhostCorp is gone and the cached index was neither polluted nor invalidated.
			assertTrue(es.mentions("What is GhostCorp?").isEmpty());
			assertEquals(List.of("Hooli"), es.mentions("What is Hooli?").stream().map(m -> m.entity().name()).toList());
			assertEquals(loadsBefore, es.mentionIndexLoads());
		}
	}

	@Test
	void mentionsDetectsExternalProcessWritesViaDataVersion() {
		Path home = fresh("entity-mention-multiprocess");
		try (Engine e1 = engine(home); Engine e2 = engine(home)) {
			assertTrue(e1.entities().mentions("What is ExternalOrg?").isEmpty());
			long loadsBefore = e1.entities().mentionIndexLoads();

			remember(e2, "ExternalOrg builds compilers.",
					proposal().entity("ext", "ExternalOrg", "organization").fact("works_at", "ext"));

			List<Mention> spotted = e1.entities().mentions("What is ExternalOrg?");
			assertEquals(List.of("ExternalOrg"), spotted.stream().map(m -> m.entity().name()).toList());
			assertEquals(loadsBefore + 1, e1.entities().mentionIndexLoads());
		}
	}

	@Test
	void fuzzyMatchesDeduplicatedAliasesAcrossManyEntities() {
		try (Engine e = engine("entity-fuzzy-join")) {
			EntityService es = e.entities();
			// Seed 40 entities with multiple aliases, including handles/addresses that store extra word-form rows.
			var createdIds = new java.util.HashSet<Long>();
			for (int i = 0; i < 40; i++) {
				String tag = "Item" + (char) ('A' + (i / 26)) + (char) ('A' + (i % 26));
				Resolved created = es.resolve("Subsystem " + tag, "project",
						List.of(tag.toLowerCase() + "@example.com", tag + " Service"), null, createdIds);
				assertEquals("created", created.how(), "each distinct entity should be created: " + tag);
				createdIds.add(created.entity().id());
			}
			es.resolve("Hooli Corporation", "organization", List.of("Hooli Corp", "contact@hooli.example"), null);
			es.resolve("Eva Berg", "person", List.of(), null);
			es.resolve("Raspberry Pi 5", "device", List.of("RPi 5"), null);

			// Exact alias match still works.
			Resolved exact = es.resolve("Hooli Corp", "organization", List.of(), null);
			assertEquals("alias", exact.how());
			assertEquals("Hooli Corporation", exact.entity().name());

			// Fuzzy typo match ("Ewa Berg" vs "Eva Berg") still raises an ambiguity with the right candidate.
			Resolved typo = es.resolve("Ewa Berg", "person", List.of(), null);
			assertTrue(typo.ambiguous(), typo.toString());
			assertFalse(typo.candidates().isEmpty());
			assertEquals("Eva Berg", typo.candidates().getFirst().entity().name());

			// Versioned name ("Raspberry Pi" vs "Raspberry Pi 5") stays ambiguous rather than auto-merging.
			Resolved versioned = es.resolve("Raspberry Pi", "device", List.of(), null);
			assertTrue(versioned.ambiguous(), versioned.toString());
			assertTrue(versioned.candidates().stream().anyMatch(c -> c.entity().name().equals("Raspberry Pi 5")));
		}
	}
}
