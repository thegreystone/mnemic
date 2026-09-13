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

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** A unit of work over the one connection. Handed out by {@link Database#read} and {@link Database#write} only. */
public final class Tx {

	private final Connection conn;
	private final boolean writable;

	Tx(Connection conn, boolean writable) {
		this.conn = conn;
		this.writable = writable;
	}

	public List<Row> query(String sql, Object... args) {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			bind(ps, args);
			try (ResultSet rs = ps.executeQuery()) {
				return rows(rs);
			}
		} catch (SQLException e) {
			throw MnemicException.internal("Query failed: " + e.getMessage(), e);
		}
	}

	public Optional<Row> queryOne(String sql, Object... args) {
		List<Row> rows = query(sql, args);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public long queryLong(String sql, Object... args) {
		return queryOne(sql, args).map(r -> ((Number) r.asMap().values().iterator().next()).longValue())
				.orElseThrow(() -> MnemicException.internal("Scalar query returned no row", null));
	}

	public int update(String sql, Object... args) {
		requireWritable();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			bind(ps, args);
			return ps.executeUpdate();
		} catch (SQLException e) {
			throw MnemicException.internal("Update failed: " + e.getMessage(), e);
		}
	}

	/** Executes an INSERT and returns the new rowid. */
	public long insert(String sql, Object... args) {
		requireWritable();
		try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			bind(ps, args);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					return keys.getLong(1);
				}
			}
			throw MnemicException.internal("INSERT returned no key", null);
		} catch (SQLException e) {
			throw MnemicException.internal("Insert failed: " + e.getMessage(), e);
		}
	}

	public void execute(String sql) {
		requireWritable();
		try (Statement st = conn.createStatement()) {
			st.execute(sql);
		} catch (SQLException e) {
			throw MnemicException.internal("Statement failed: " + e.getMessage(), e);
		}
	}

	private void requireWritable() {
		if (!writable) {
			throw MnemicException.internal("Write attempted inside a read transaction", null);
		}
	}

	private static void bind(PreparedStatement ps, Object... args) throws SQLException {
		for (int i = 0; i < args.length; i++) {
			ps.setObject(i + 1, args[i]);
		}
	}

	private static List<Row> rows(ResultSet rs) throws SQLException {
		ResultSetMetaData md = rs.getMetaData();
		int n = md.getColumnCount();
		var out = new ArrayList<Row>();
		while (rs.next()) {
			var cols = new LinkedHashMap<String, Object>();
			for (int i = 1; i <= n; i++) {
				cols.put(md.getColumnLabel(i), rs.getObject(i));
			}
			out.add(new Row(cols));
		}
		return out;
	}
}
