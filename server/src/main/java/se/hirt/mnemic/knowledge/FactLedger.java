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
import se.hirt.mnemic.persistence.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The record kept beside a fact row: which observations stated or corroborated it ({@code fact_source}) and every
 * change of its status ({@code supersession}). Nothing here deletes; a fact that stops holding is closed and the reason
 * written down, so history can always say what replaced what and why.
 */
final class FactLedger {

	private final FactRenderer renderer;
	private final PredicateRegistry predicates;

	FactLedger(FactRenderer renderer, PredicateRegistry predicates) {
		this.renderer = renderer;
		this.predicates = predicates;
	}

	/** Records that an observation stated or corroborated a fact. The fact's own observation is its home. */
	static void link(Tx tx, long factId, long observationId, String kind) {
		tx.update("INSERT OR IGNORE INTO fact_source(fact_id, observation_id, kind, recorded_at) VALUES (?,?,?,?)",
				factId, observationId, kind, Instant.now().toString());
	}

	/** Every observation behind a fact: its home first, then the others in order of arrival. */
	static List<Long> observationsOf(Tx tx, long factId) {
		var out = new ArrayList<Long>();
		Optional<Long> home = tx.queryOne("SELECT observation_id FROM fact WHERE id = ?", factId)
				.map(r -> r.lng("observation_id"));
		home.ifPresent(out::add);
		for (Row r : tx.query(
				"SELECT observation_id FROM fact_source WHERE fact_id = ? ORDER BY recorded_at, observation_id",
				factId)) {
			long id = r.lng("observation_id");
			if (!out.contains(id)) {
				out.add(id);
			}
		}
		return out;
	}

	/** A restatement: one more corroboration, the confirmation date moved forward when {@code confirmedAt} is later. */
	static void corroborate(Tx tx, long factId, long observationId, String confirmedAt) {
		tx.update("""
				UPDATE fact SET corroborations = corroborations + 1,
				                last_confirmed = MAX(last_confirmed, COALESCE(?, last_confirmed)) WHERE id = ?""",
				confirmedAt, factId);
		link(tx, factId, observationId, "corroborated");
	}

	/**
	 * Closes a fact: the end bound when one is known, the new status, the supersession record, and a fresh rendering.
	 * {@code kind} names what closed it: {@code event}, {@code supersession}, {@code entity_ended}.
	 */
	void close(
		Tx tx, Fact fact, Long byId, String kind, String reason, Long eventId, Long obsId, String end,
		String endPrecision, String newStatus) {
		Bounds b = Bounds.of(fact);
		if (end != null) {
			b = b.withEnd(end, endPrecision, kind);
		}
		tx.update("""
				UPDATE fact SET status = ?, superseded_by = ?, valid_end = ?, valid_end_precision = ?, end_source = ?,
				                ended = 1 WHERE id = ?""", newStatus, byId, b.end(), b.endPrecision(), b.endSource(),
				fact.id());
		renderer.rerender(tx, fact.id());
		supersession(tx, fact.id(), byId, kind, reason, eventId, obsId, b.end());
		closeScoped(tx, fact, eventId, obsId, b.end(), b.endPrecision());
	}

	/** Whether the predicate is stored once and read from both sides. */
	boolean symmetric(String predicate) {
		return predicates.get(predicate).map(Predicate::symmetric).orElse(false);
	}

	/** Whether a participant's death leaves the relation standing. */
	boolean lasting(String predicate) {
		return predicates.get(predicate).map(Predicate::lasting).orElse(false);
	}

	/** Whether anybody said so, or the store assumed it. */
	boolean lastingStated(String predicate) {
		return predicates.get(predicate).map(Predicate::lastingStated).orElse(true);
	}

	/**
	 * A fact functional per scope (a role at an organization) cannot outlive the subject's relation to that scope: when
	 * {@code works_at(s, o)} ends, every open fact of {@code s} scoped to {@code o} ends with it, at the same date,
	 * whatever ended the relation.
	 */
	private void closeScoped(Tx tx, Fact ended, Long eventId, Long obsId, String end, String endPrecision) {
		if (ended.objectId() == null || ended.scopeId() != null) {
			return;
		}
		for (Row r : tx.query("""
				SELECT * FROM fact WHERE subject_id = ? AND scope_id = ? AND status = 'current' AND valid_end IS NULL
				AND id <> ?""", ended.subjectId(), ended.objectId(), ended.id())) {
			Fact scoped = Fact.from(r);
			boolean perScope = predicates.get(scoped.predicate()).map(p -> "scope".equals(p.functionalScope()))
					.orElse(false);
			if (perScope) {
				close(tx, scoped, null, "dependency", "the relation it was scoped to ended", eventId, obsId, end,
						endPrecision, "current");
			}
		}
	}

	/**
	 * Writes a status change; {@code kind} is {@code event}, {@code supersession}, {@code correction},
	 * {@code retraction}, {@code invalidation}, {@code duplicate}, or {@code entity_ended}.
	 */
	static void supersession(
		Tx tx, long factId, Long byId, String kind, String reason, Long eventId, Long obsId, String closedAt) {
		tx.insert("""
				INSERT INTO supersession(fact_id, superseded_by_id, kind, reason, event_id, observation_id, closed_at,
				                         recorded_at) VALUES (?,?,?,?,?,?,?,?)""", factId, byId, kind, reason, eventId,
				obsId, closedAt, Instant.now().toString());
	}
}
