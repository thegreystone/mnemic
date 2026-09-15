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

import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The one SQLite file per data home (DESIGN.md, Persistence). All work in this process is serialised through one
 * connection and a lock; SQLite's own WAL locking handles a second MCP client process on the same data home, with
 * {@code busy_timeout} and {@code BEGIN IMMEDIATE} for writes so two writers fail fast instead of upgrade-deadlocking.
 */
public final class Database implements AutoCloseable {

	private final Path file;
	private final Connection conn;
	private final ReentrantLock lock = new ReentrantLock();
	/** How many migrations this open applied; the engine re-renders every fact after any. */
	private final int migrated;
	/** The loaded sqlite-vec version, or null when no library was configured. */
	private String vecVersion;

	public Database(Path file) {
		this(file, null);
	}

	/** {@code vecLibrary}: the sqlite-vec loadable library, loaded into this connection when given. */
	public Database(Path file, String vecLibrary) {
		this.file = file;
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			var props = new Properties();
			props.setProperty("busy_timeout", "5000");
			if (vecLibrary != null && !vecLibrary.isBlank()) {
				props.setProperty("enable_load_extension", "true");
			}
			// Instantiate the driver directly: DriverManager rejects drivers loaded by another classloader
			// (e.g. after a Quarkus test in the same JVM).
			conn = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), props);
			try (Statement st = conn.createStatement()) {
				st.execute("PRAGMA journal_mode = WAL");
				st.execute("PRAGMA foreign_keys = ON");
				st.execute("PRAGMA synchronous = NORMAL");
				// forget must leave no residue in the file: overwrite freed pages with zeros.
				st.execute("PRAGMA secure_delete = ON");
			}
			// Auto-commit stays on; every unit of work opens its own explicit transaction, so no lock is ever held
			// between calls and a second process can always open the file.
			if (vecLibrary != null && !vecLibrary.isBlank()) {
				try (var ps = conn.prepareStatement("SELECT load_extension(?, 'sqlite3_vec_init')")) {
					ps.setString(1, vecLibrary.replace('\\', '/'));
					ps.execute();
				}
				try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT vec_version()")) {
					this.vecVersion = rs.next() ? rs.getString(1) : null;
				}
			}
			int before = versionOrZero(conn);
			Migrations.apply(conn);
			this.migrated = versionOrZero(conn) - before;
		} catch (MnemicException e) {
			throw e;
		} catch (Exception e) {
			throw MnemicException.internal("Cannot open database " + file + ": " + e.getMessage(), e);
		}
	}

	public Path file() {
		return file;
	}

	/** A read-only unit of work; always rolled back so no read transaction outlives the call (WAL growth). */
	public <T> T read(Function<Tx, T> work) {
		lock.lock();
		try {
			execute("BEGIN");
			try {
				return work.apply(new Tx(conn, false));
			} finally {
				execute("ROLLBACK");
			}
		} catch (SQLException e) {
			throw MnemicException.internal("Read transaction failed", e);
		} finally {
			lock.unlock();
		}
	}

	/** A mutating unit of work, committed atomically; any exception rolls back and propagates. */
	public <T> T write(Function<Tx, T> work) {
		lock.lock();
		try {
			execute("BEGIN IMMEDIATE");
			try {
				T result = work.apply(new Tx(conn, true));
				execute("COMMIT");
				return result;
			} catch (RuntimeException e) {
				execute("ROLLBACK");
				throw e;
			}
		} catch (SQLException e) {
			throw MnemicException.internal("Transaction failed", e);
		} finally {
			lock.unlock();
		}
	}

	private void execute(String sql) throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute(sql);
		}
	}

	public String vecVersion() {
		return vecVersion;
	}

	public int migrated() {
		return migrated;
	}

	private static int versionOrZero(Connection conn) {
		try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0; // no schema_version table yet: a new file
		}
	}

	/** A store-level setting kept beside the data ({@code store_meta}), or null. */
	public String meta(String key) {
		return read(tx -> tx.queryOne("SELECT value FROM store_meta WHERE key = ?", key).map(r -> r.str("value")).orElse(null));
	}

	public void setMeta(String key, String value) {
		write(tx -> tx.update("INSERT OR REPLACE INTO store_meta(key, value) VALUES (?,?)", key, value));
	}

	public long schemaVersion() {
		return read(tx -> tx.queryLong("SELECT COALESCE(MAX(version), 0) FROM schema_version"));
	}

	/** Rebuilds every FTS index from its canonical table (EVALUATION.md H2: indexes are disposable). */
	public void rebuildIndexes() {
		write(tx -> {
			tx.execute("INSERT INTO observation_fts(observation_fts) VALUES ('rebuild')");
			tx.execute("INSERT INTO fact_fts(fact_fts) VALUES ('rebuild')");
			tx.execute("INSERT INTO event_fts(event_fts) VALUES ('rebuild')");
			return null;
		});
	}

	@Override
	public void close() {
		lock.lock();
		try {
			try (Statement st = conn.createStatement()) {
				st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			} catch (SQLException ignored) {
				// best effort
			}
			conn.close();
		} catch (SQLException e) {
			throw MnemicException.internal("Failed to close database", e);
		} finally {
			lock.unlock();
		}
	}
}
