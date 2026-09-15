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

import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Forward-only, numbered SQL migrations embedded as classpath resources ({@code db/migration/Vnnn__name.sql}), recorded
 * in {@code schema_version}. No down-migrations. Checksums are verified so an edited already-applied migration fails
 * loudly, and a database written by a newer binary is refused, since two versions may share a data home.
 */
final class Migrations {

	/** Add new migrations here, in order. Never edit an applied one. */
	static final List<String> MIGRATIONS = List.of("V001__observations.sql", "V002__facts.sql", "V003__time.sql",
			"V004__questions.sql", "V005__event_keys.sql", "V006__proposer.sql", "V007__located_in_nests.sql",
			"V008__sibling_qualifiers.sql", "V009__inverse_lexicon.sql", "V010__event_lexicon.sql",
			"V011__qualifier_convention.sql", "V012__fact_mode.sql", "V013__related_to_qualifier.sql",
			"V014__rerender_after_template_change.sql", "V015__embedding.sql", "V016__fact_observation_and_chunks.sql",
			"V017__language.sql", "V018__retired_observation.sql", "V019__vocabularies.sql", "V020__event_render.sql");

	private static final Pattern NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

	private Migrations() {
	}

	static void apply(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.execute("BEGIN IMMEDIATE");
		}
		try {
			applyAll(c);
			try (Statement st = c.createStatement()) {
				st.execute("COMMIT");
			}
		} catch (RuntimeException | SQLException e) {
			try (Statement st = c.createStatement()) {
				st.execute("ROLLBACK");
			}
			throw e;
		}
	}

	private static void applyAll(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.execute("""
					CREATE TABLE IF NOT EXISTS schema_version (
					    id          INTEGER PRIMARY KEY AUTOINCREMENT,
					    version     INTEGER NOT NULL UNIQUE,
					    description TEXT NOT NULL,
					    checksum    TEXT NOT NULL,
					    applied_at  TEXT NOT NULL
					)""");
		}
		int known = MIGRATIONS.size();
		try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM schema_version");
				ResultSet rs = ps.executeQuery()) {
			if (rs.next() && rs.getInt(1) > known) {
				throw MnemicException.internal("Database schema version " + rs.getInt(1)
						+ " is newer than this binary understands (" + known + "). Upgrade Mnemic.", null);
			}
		}
		for (String file : MIGRATIONS) {
			var m = NAME.matcher(file);
			if (!m.matches()) {
				throw new IllegalStateException("Bad migration file name: " + file);
			}
			int version = Integer.parseInt(m.group(1));
			String description = m.group(2);
			String sql = load(file);
			String checksum = checksum(sql);

			try (PreparedStatement ps = c.prepareStatement("SELECT checksum FROM schema_version WHERE version = ?")) {
				ps.setInt(1, version);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						if (!checksum.equals(rs.getString(1))) {
							throw new IllegalStateException("Migration " + file + " was modified after being applied.");
						}
						continue;
					}
				}
			}
			try (Statement st = c.createStatement()) {
				for (String statement : splitStatements(sql)) {
					st.execute(statement);
				}
			}
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO schema_version(version, description, checksum, applied_at) VALUES (?,?,?,?)")) {
				ps.setInt(1, version);
				ps.setString(2, description);
				ps.setString(3, checksum);
				ps.setString(4, Instant.now().toString());
				ps.executeUpdate();
			}
		}
	}

	private static String load(String file) {
		String path = "db/migration/" + file;
		try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing migration resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw MnemicException.internal("Cannot read migration " + path, e);
		}
	}

	/**
	 * The fingerprint of an applied migration: the statements it executes with comments stripped, whitespace collapsed,
	 * and spaces around {@code ( ) , ;} removed, so reformatting a file never locks a database out of its own schema
	 * while any DDL change still trips the guard.
	 */
	static String checksum(String sql) {
		String canonical = String.join(";", splitStatements(sql)).replaceAll("\\s+", " ").replaceAll("\\s*([(),;])\\s*",
				"$1");
		return Json.hashText(canonical);
	}

	/**
	 * Splits on statement-terminating semicolons after stripping {@code --} comments. Trigger bodies contain semicolons
	 * inside {@code BEGIN ... END}, so a statement is only closed when no BEGIN is open.
	 */
	static List<String> splitStatements(String sql) {
		String noComments = sql.lines().map(line -> {
			int i = line.indexOf("--");
			return i >= 0 ? line.substring(0, i) : line;
		}).reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append).toString();
		var out = new ArrayList<String>();
		var current = new StringBuilder();
		int depth = 0;
		for (String piece : noComments.split(";")) {
			current.append(piece);
			String upper = piece.toUpperCase();
			depth += count(upper, "BEGIN");
			depth -= count(upper, "END");
			if (depth <= 0) {
				String s = current.toString().trim();
				if (!s.isEmpty()) {
					out.add(s);
				}
				current.setLength(0);
				depth = 0;
			} else {
				current.append(';');
			}
		}
		String tail = current.toString().trim();
		if (!tail.isEmpty()) {
			out.add(tail);
		}
		return out;
	}

	private static int count(String text, String word) {
		return (int) Arrays.stream(text.split("\\W+")).filter(word::equals).count();
	}
}
