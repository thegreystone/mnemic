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
package se.hirt.mnemic.persistence;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.Scenario;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.PredicateRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V031: a group's words in another language, once in a table of their own, join its one list of cue words. */
class GroupLexiconMigrationTest {

	private static final int SCHEMA_30 = 30;

	@Test
	@Scenario("J12")
	void aGroupsWordsInAnotherLanguageJoinItsList() throws Exception {
		Path home = TestHomes.fresh("upgrade-30-groups");
		Path file = home.resolve("mnemic.db");
		try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(),
				new java.util.Properties()); Statement st = c.createStatement()) {
			st.execute("PRAGMA foreign_keys = ON");
			Migrations.applyUpTo(c, SCHEMA_30);
			st.execute("INSERT INTO predicate_group(name, description, lexicon, groups, seed, created_at) VALUES "
					+ "('pets', NULL, '[\"pets\",\"pet\"]', '[]', 0, 't')");
			st.execute("INSERT INTO predicate_group_render(name, language, lexicon) VALUES "
					+ "('pets', 'de', '[\"haustiere\",\"pet\"]')");
			// Two predicates of the store's own from before anything could be said about lasting: one the owner
			// corrected (a change on its log), one nobody decided.
			st.execute("INSERT INTO predicate(name, description, domain, range, functional, symmetric, volatility, "
					+ "lexicon, render, qualifiers, aliases, inverse_lexicon, seed, created_at, lasting) VALUES "
					+ "('coaches', 'Subject coaches object.', '[\"person\"]', '[\"person\"]', 0, 0, 'medium', "
					+ "'[\"coaches\"]', '{subject} coaches {object}', '[]', '[]', '[]', 0, 't', 1), "
					+ "('mentors', 'Subject mentors object.', '[\"person\"]', '[\"person\"]', 0, 0, 'medium', "
					+ "'[\"mentors\"]', '{subject} mentors {object}', '[]', '[]', '[]', 0, 't', 0)");
			st.execute(
					"INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at) VALUES "
							+ "('coaches', 'lasting', 'false', 'true', 'a coach stays a coach', 't')");
		}
		try (Engine e = TestHomes.engine(home)) {
			assertEquals(35, e.database().schemaVersion());
			PredicateRegistry.Group pets = e.predicates().group("pets").orElseThrow();
			assertEquals(List.of("pets", "pet", "haustiere"), pets.lexicon(), "merged, once each, in order");
			assertTrue(e.database()
					.read(tx -> tx.query(
							"SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'predicate_group_render'"))
					.isEmpty(), "the table is gone");
			// V032: who said what about lasting.
			assertTrue(e.predicates().get("parent_of").orElseThrow().lastingStated(), "the seed decided");
			assertTrue(e.predicates().get("coaches").orElseThrow().lastingStated(), "the owner decided");
			assertTrue(e.predicates().get("coaches").orElseThrow().lasting());
			assertFalse(e.predicates().get("mentors").orElseThrow().lastingStated(), "nobody decided");
		}
	}
}
