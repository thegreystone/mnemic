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

import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Storage for the question queue; {@link QuestionResolver} applies what an answered question held. */
public final class QuestionService {

	private final Database db;

	public QuestionService(Database db) {
		this.db = db;
	}

	public Question create(
			String kind, Long observationId, Long factId, String subject, String predicate,
			List<Map<String, Object>> candidates, String payload, String message) {
		long id = db.write(tx -> tx.insert("""
		                                   INSERT INTO question(kind, status, observation_id, fact_id, subject, predicate, candidates, payload,
		                                                        message, created_at) VALUES (?,'open',?,?,?,?,?,?,?,?)""",
				kind, observationId, factId, subject, predicate, Json.write(candidates), payload, message,
				Instant.now().toString()));
		return get(id).orElseThrow();
	}

	public Optional<Question> get(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM question WHERE id = ?", id).map(Question::from));
	}

	public Question require(String ref) {
		long id;
		try {
			id = Long.parseLong(ref.startsWith("q-") ? ref.substring(2) : ref);
		} catch (NumberFormatException e) {
			throw MnemicException.invalidArgument("'" + ref + "' is not a question id like q-12.");
		}
		return get(id).orElseThrow(() -> MnemicException.notFound("No question " + ref));
	}

	public List<Question> open(int limit) {
		return db.read(
				tx -> tx.query("SELECT * FROM question WHERE status = 'open' ORDER BY id LIMIT ?", limit).stream()
						.map(Question::from).toList());
	}

	public long openCount() {
		return db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM question WHERE status = 'open'"));
	}

	public void answer(long id, String answer) {
		db.write(tx -> tx.update("UPDATE question SET status = 'answered', answer = ?, answered_at = ? WHERE id = ?",
				answer, Instant.now().toString(), id));
	}

	public void dismiss(long id, String reason) {
		db.write(tx -> tx.update("UPDATE question SET status = 'dismissed', answer = ?, answered_at = ? WHERE id = ?",
				reason, Instant.now().toString(), id));
	}

	public void linkFact(long questionId, long factId) {
		db.write(tx -> {
			tx.update("UPDATE question SET fact_id = ? WHERE id = ?", factId, questionId);
			tx.update("UPDATE fact SET question_id = ? WHERE id = ?", questionId, factId);
			return null;
		});
	}
}
