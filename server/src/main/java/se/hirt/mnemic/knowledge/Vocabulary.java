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
package se.hirt.mnemic.knowledge;

import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.protocol.MnemicException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the registries share: JSON lists in text columns, list-valued corrections, and the change log. */
final class Vocabulary {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<String>> LIST = new TypeReference<>() {
	};

	private Vocabulary() {
	}

	static String json(List<String> l) {
		try {
			return JSON.writeValueAsString(l);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}

	static List<String> list(String json) {
		try {
			return json == null ? List.of() : JSON.readValue(json, LIST);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}

	/** A correction value as a list: a JSON array, or a comma-separated string. */
	static List<String> strings(Object value) {
		if (value instanceof List<?> l) {
			return l.stream().map(String::valueOf).toList();
		}
		return List.of(String.valueOf(value).split("\\s*,\\s*"));
	}

	/** A correction value as logged: a list as JSON, anything else as text, null as null. */
	static String text(Object value) {
		return value == null ? null : value instanceof List<?> ? json(strings(value)) : String.valueOf(value);
	}

	/** Records each {@code {field, old, new}} of a correction to a vocabulary entry. */
	static void logChanges(Tx tx, String vocabulary, String name, List<String[]> changes, String reason) {
		for (String[] c : changes) {
			tx.insert(
					"INSERT INTO vocabulary_change(vocabulary, name, field, old_value, new_value, reason, changed_at) "
							+ "VALUES (?,?,?,?,?,?,?)",
					vocabulary, name, c[0], c[1], c[2], reason, Instant.now().toString());
		}
	}

	static List<Map<String, Object>> changes(Tx tx, String vocabulary, String name) {
		var out = new ArrayList<Map<String, Object>>();
		tx.query("SELECT * FROM vocabulary_change WHERE vocabulary = ? AND name = ? ORDER BY id", vocabulary, name)
				.forEach(r -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("field", r.str("field"));
					m.put("old", r.str("old_value"));
					m.put("new", r.str("new_value"));
					m.put("reason", r.str("reason"));
					m.put("changed_at", r.str("changed_at"));
					out.add(m);
				});
		return out;
	}

	/** A vocabulary entry as a tool reply lists it. */
	static Map<String, Object> entry(String name, String description, boolean seed, Long definedBy) {
		var m = new LinkedHashMap<String, Object>();
		m.put("name", name);
		m.put("description", description);
		m.put("origin", seed ? "seed" : "defined");
		if (definedBy != null) {
			m.put("defined_by", "obs-" + definedBy);
		}
		return m;
	}
}
