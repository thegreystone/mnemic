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
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationsTest {

	@Test
	void freshDatabaseIsAtTheLatestVersion() throws Exception {
		Path dir = Files.createTempDirectory("mnemic-mig");
		try (Database db = new Database(dir.resolve("mnemic.db"))) {
			assertEquals(Migrations.MIGRATIONS.size(), db.schemaVersion());
			List<Row> tables = db.read(
					tx -> tx.query("SELECT name FROM sqlite_master WHERE type IN ('table','trigger') ORDER BY name"));
			var names = tables.stream().map(r -> r.str("name")).toList();
			assertTrue(names.contains("observation"), names.toString());
			assertTrue(names.contains("observation_fts"), names.toString());
			assertTrue(names.contains("observation_ai"), "insert trigger present: " + names);
		}
	}

	@Test
	void checksumIgnoresFormattingButNotDdl() {
		String a = "CREATE TABLE t (\n    id INTEGER PRIMARY KEY,\n    name TEXT -- the name\n);";
		String b = "CREATE TABLE t(id INTEGER PRIMARY KEY,name TEXT);";
		String c = "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL);";
		assertEquals(Migrations.checksum(a), Migrations.checksum(b));
		assertTrue(!Migrations.checksum(a).equals(Migrations.checksum(c)));
	}

	@Test
	void triggerBodiesAreNotSplitOnInnerSemicolons() {
		String sql = """
		             CREATE TABLE t (id INTEGER);
		             CREATE TRIGGER t_ai AFTER INSERT ON t BEGIN
		                 INSERT INTO x VALUES (1);
		                 INSERT INTO y VALUES (2);
		             END;
		             CREATE INDEX i ON t(id);
		             """;
		List<String> statements = Migrations.splitStatements(sql);
		assertEquals(3, statements.size(), statements.toString());
		assertTrue(statements.get(1).startsWith("CREATE TRIGGER") && statements.get(1).endsWith("END"),
				statements.get(1));
	}

	@Test
	void newerDatabaseIsRefused() throws Exception {
		Path dir = Files.createTempDirectory("mnemic-newer");
		Path file = dir.resolve("mnemic.db");
		try (Database db = new Database(file)) {
			db.write(tx -> {
				tx.update(
						"INSERT INTO schema_version(version, description, checksum, applied_at) " + "VALUES (999, 'future', 'x', 'now')");
				return null;
			});
		}
		MnemicException ex = assertThrows(MnemicException.class, () -> new Database(file));
		assertTrue(ex.getMessage().contains("newer"), ex.getMessage());
	}
}
