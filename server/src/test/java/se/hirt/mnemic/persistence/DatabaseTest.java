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

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.fresh;

/** Reentrant transactions, savepoints, Error rollback, external data_version detection, and WAL checkpointing. */
class DatabaseTest {

	@Test
	void nestedWriteCommitsAndRollsBackSavepointsIndependently() {
		Path file = fresh("db-nested").resolve("mnemic.db");
		try (Database db = new Database(file)) {
			db.write(tx -> {
				tx.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)", "outer", "1");
				assertThrows(IllegalStateException.class, () -> db.write(inner -> {
					inner.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)", "inner_fail", "bad");
					throw new IllegalStateException("rollback savepoint");
				}));
				db.write(inner -> inner.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)",
						"inner_ok", "2"));
				return null;
			});
			assertEquals("1", db.meta("outer"));
			assertNull(db.meta("inner_fail"), "failed inner savepoint was rolled back");
			assertEquals("2", db.meta("inner_ok"), "succeeding inner savepoint committed with outer");

			// Outer failure rolls back everything including already-released savepoints.
			assertThrows(IllegalStateException.class, () -> db.write(tx -> {
				db.write(inner -> inner.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)",
						"rolled_with_outer", "nope"));
				throw new IllegalStateException("outer fails");
			}));
			assertNull(db.meta("rolled_with_outer"));
		}
	}

	@Test
	void writeInsideReadIsRejectedWithoutCorruptingConnection() {
		Path file = fresh("db-read-write").resolve("mnemic.db");
		try (Database db = new Database(file)) {
			MnemicException ex = assertThrows(MnemicException.class, () -> db.read(tx -> {
				db.write(w -> w.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)", "k", "v"));
				return null;
			}));
			assertEquals(MnemicException.Code.INTERNAL, ex.code());
			// Subsequent writes still work cleanly.
			db.setMeta("after", "ok");
			assertEquals("ok", db.meta("after"));
		}
	}

	@Test
	void errorDuringWriteRollsBackAndLeavesConnectionUsable() {
		Path file = fresh("db-error-rollback").resolve("mnemic.db");
		try (Database db = new Database(file)) {
			AssertionError err = assertThrows(AssertionError.class, () -> db.write(tx -> {
				tx.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?, ?)", "should_not_persist", "x");
				throw new AssertionError("simulated JVM error");
			}));
			assertEquals("simulated JVM error", err.getMessage());
			assertNull(db.meta("should_not_persist"), "Error rolled back the active transaction");
			// Connection must not be stuck with an open transaction.
			db.setMeta("recovered", "yes");
			assertEquals("yes", db.meta("recovered"));
		}
	}

	@Test
	void dataVersionAdvancesWhenAnotherConnectionCommits() {
		Path file = fresh("db-dataversion").resolve("mnemic.db");
		try (Database a = new Database(file); Database b = new Database(file)) {
			long v0 = b.dataVersion();
			assertEquals(v0, b.dataVersion(), "stable with no external writes");
			a.setMeta("cross_process", "updated");
			long v1 = b.dataVersion();
			assertNotEquals(v0, v1, "external commit advances PRAGMA data_version");
			assertEquals("updated", b.meta("cross_process"));
		}
	}

	@Test
	void checkpointTruncatesWalWhileOpen() throws Exception {
		Path dir = fresh("db-checkpoint");
		Path file = dir.resolve("mnemic.db");
		Path wal = dir.resolve("mnemic.db-wal");
		try (Database db = new Database(file)) {
			db.setMeta("payload", "value-".repeat(200));
			assertTrue(Files.exists(wal) && Files.size(wal) > 0, "WAL holds uncommitted-checkpoint frames");
			db.checkpoint();
			assertTrue(!Files.exists(wal) || Files.size(wal) == 0,
					"PRAGMA wal_checkpoint(TRUNCATE) empties the WAL while open");
		}
	}
}
