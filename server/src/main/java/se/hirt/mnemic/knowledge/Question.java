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

import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.protocol.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question Mnemic could not answer by itself and put to the caller: an ambiguous entity, an ambiguous predicate, a
 * conflict on a functional predicate, a type mismatch, a containment gap, and what a new event type or entity type
 * registered from use means. It stays open until answered or dismissed; what it holds is applied only then.
 *
 * @param candidates
 *            numbered choices, each {@code {n, id, label, score}}; small integers remapped per question so the model
 *            never has to reproduce an internal id
 * @param payload
 *            the held proposal fragment as JSON, applied on resolution
 */
public record Question(long id, String kind, String status, Long observationId, Long factId, String subject,
		String predicate, List<Map<String, Object>> candidates, String payload, String message, String createdAt,
		String answeredAt, String answer) {

	public String ref() {
		return "q-" + id;
	}

	public boolean open() {
		return "open".equals(status);
	}

	/** The response shape: id first, then what the model needs to ask the user. */
	public Map<String, Object> toMap() {
		var m = new LinkedHashMap<String, Object>();
		m.put("id", ref());
		m.put("kind", kind);
		m.put("status", status);
		if (subject != null) {
			m.put("subject", subject);
		}
		if (predicate != null) {
			m.put("predicate", predicate);
		}
		m.put("candidates", candidates);
		m.put("message", message);
		if (payload != null && !kind.endsWith("_resolution")) {
			Map<String, Object> p = Json.readMap(payload); // conflict: existing/pending; type_mismatch: entity_type/expected
			p.remove("held");
			m.putAll(p);
		}
		if (factId != null) {
			m.put("pending_fact", "f-" + factId);
		}
		if (observationId != null) {
			m.put("observation", "obs-" + observationId);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	static Question from(Row r) {
		List<Map<String, Object>> candidates = (List<Map<String, Object>>) (List<?>) Json
				.readMap("{\"c\":" + r.str("candidates") + "}").get("c");
		return new Question(r.lng("id"), r.str("kind"), r.str("status"), r.lngOrNull("observation_id"),
				r.lngOrNull("fact_id"), r.str("subject"), r.str("predicate"), candidates, r.str("payload"),
				r.str("message"), r.str("created_at"), r.str("answered_at"), r.str("answer"));
	}
}
