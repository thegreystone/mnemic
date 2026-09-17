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
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads over the fact table: by id, by entity, by observation, the structured probe over valid time, the lexical lookup
 * over renderings, the briefing order, provenance, history, and the review of what should be confirmed. Valid time
 * decides what held when; observation time only says since when a fact could have been known.
 */
public final class FactQueries {

	/** One entry of {@link #history}: a fact with every recorded change to it. */
	public record HistoryEntry(Fact fact, List<Supersession> supersessions) {
	}

	public record History(List<HistoryEntry> entries, List<Tombstone> tombstones) {
	}

	/** A forgotten observation that had facts about the entity: when, nothing else (EVALUATION.md D2). */
	public record Tombstone(long observationId, String forgottenAt) {
	}

	private final Database db;
	private final PredicateRegistry predicates;
	private final Clock clock;

	public FactQueries(Database db, PredicateRegistry predicates, Clock clock) {
		this.db = db;
		this.predicates = predicates;
		this.clock = clock;
	}

	public Optional<Fact> get(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM fact WHERE id = ?", id).map(Fact::from));
	}

	/** Every fact touching the entity as subject, object, or scope; current ones first. */
	public List<Fact> factsOf(long entityId) {
		return db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ?
				ORDER BY CASE status WHEN 'current' THEN 0 ELSE 1 END, id""", entityId, entityId, entityId).stream()
				.map(Fact::from).toList());
	}

	/** Facts held pending under a predicate for an entity: conflicts awaiting an answer. */
	public List<Fact> pending(long entityId, String predicate) {
		return db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE status = 'pending' AND predicate = ? AND (subject_id = ? OR object_id = ?)
				ORDER BY id""", predicate, entityId, entityId).stream().map(Fact::from).toList());
	}

	public List<Fact> factsOfObservation(long observationId) {
		return db.read(tx -> tx
				.query("SELECT * FROM fact WHERE observation_id = ? AND derivation_kind <> 'derived' ORDER BY id",
						observationId)
				.stream().map(Fact::from).toList());
	}

	/**
	 * The asserted facts of a predicate that touch the entity: without {@code asOf}, those current or future (and
	 * history when asked for); with it, those that may have held then and were known by then. Under a functional
	 * predicate an open fact with no start is dropped for a past date when ended predecessors exist, since it is the
	 * latest value, not the one that held then.
	 */
	public List<Fact> probe(long entityId, String predicate, Instant asOf, Instant now, boolean includeHistory) {
		List<Row> rows = db.read(tx -> tx.query("""
				SELECT f.*, o.observed_at AS obs_observed_at FROM fact f JOIN observation o ON o.id = f.observation_id
				WHERE f.predicate = ? AND f.status IN ('current', 'superseded', 'corrected') AND f.mode = 'asserted'
				AND (f.subject_id = ? OR f.object_id = ?) ORDER BY f.id""", predicate, entityId, entityId));
		var out = new ArrayList<Fact>();
		if (asOf == null) {
			for (Row r : rows) {
				Fact f = Fact.from(r);
				String state = f.state(now);
				if ("current".equals(state) || "future".equals(state)
						|| (includeHistory && !"pending".equals(f.status()))) {
					out.add(f);
				}
			}
			return out;
		}
		Predicate p = predicates.get(predicate).orElse(null);
		boolean functional = p != null && p.functional();
		boolean timeless = p != null && "low".equals(p.volatility());
		boolean endedPredecessors = rows.stream().map(Fact::from).anyMatch(f -> f.ended() || f.validEnd() != null);
		for (Row r : rows) {
			Fact f = Fact.from(r);
			if ("corrected".equals(f.status()) || !f.mayHoldAt(asOf)
					|| !knownBy(f, r.str("obs_observed_at"), asOf, timeless)) {
				continue;
			}
			boolean openNow = f.current() && !f.ended() && f.validEnd() == null;
			if (functional && openNow && f.validStart() == null && endedPredecessors) {
				continue;
			}
			out.add(f);
		}
		return out;
	}

	/**
	 * Whether a fact could be known at {@code asOf}. A stated valid start decides; so does a timeless predicate
	 * (born_in, parent_of) or a fact that has ended, whose unknown bounds may well reach back (EVALUATION.md C9).
	 * Otherwise the earliest the fact is known to hold is the day it was observed.
	 */
	static boolean knownBy(Fact f, String observedAt, Instant asOf, boolean timeless) {
		if (f.validStart() != null || timeless || observedAt == null || f.ended() || f.validEnd() != null) {
			return true;
		}
		return observedAt.compareTo(asOf.toString()) <= 0;
	}

	/** Facts whose rendering matches the FTS query, best first, within the same time rules as {@link #probe}. */
	public List<Fact> lexical(String match, Instant asOf, Instant now, boolean includeHistory, int limit) {
		if (match == null || match.isBlank()) {
			return List.of();
		}
		List<Row> rows = db.read(tx -> tx.query("""
				SELECT f.*, o.observed_at AS obs_observed_at FROM fact_fts x JOIN fact f ON f.id = x.rowid
				JOIN observation o ON o.id = f.observation_id
				WHERE fact_fts MATCH ? AND f.status IN ('current', 'superseded', 'corrected')
				ORDER BY bm25(fact_fts), f.id DESC LIMIT ?""", match, limit * 3));
		var out = new ArrayList<Fact>();
		for (Row r : rows) {
			Fact f = Fact.from(r);
			boolean ok;
			if (asOf != null) {
				boolean timeless = predicates.get(f.predicate()).map(p -> "low".equals(p.volatility())).orElse(false);
				ok = !"corrected".equals(f.status()) && f.mayHoldAt(asOf)
						&& knownBy(f, r.str("obs_observed_at"), asOf, timeless);
			} else {
				ok = "current".equals(f.state(now)) || (includeHistory && !"pending".equals(f.status()));
			}
			if (ok) {
				out.add(f);
				if (out.size() >= limit) {
					break;
				}
			}
		}
		return out;
	}

	/**
	 * The entity's current facts in briefing order (EVALUATION.md F10): what places a person first (the functional
	 * predicates: job, home, role, spouse), then the lasting relations, then the rest; the best corroborated first
	 * within a group.
	 */
	public List<Fact> briefingFacts(long entityId, Instant now, int limit) {
		return factsOf(entityId).stream().filter(f -> "current".equals(f.state(now)) && f.asserted())
				.sorted(Comparator.comparingInt((Fact f) -> briefingRank(f.predicate()))
						.thenComparing(Fact::corroborations, Comparator.reverseOrder()).thenComparing(Fact::id))
				.limit(limit).toList();
	}

	private int briefingRank(String predicate) {
		Predicate p = predicates.get(predicate).orElse(null);
		if (p == null || p.isInferred()) {
			return 3;
		}
		if (p.functional()) {
			return 0;
		}
		if ("low".equals(p.volatility()) && p.sameType()) {
			return 1;
		}
		return 2;
	}

	/** The current bounds on a subject's predicate: negations, restrictions, closures (family Q). */
	public List<Fact> bounds(long subjectId, String predicate, Instant now) {
		return db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND mode <> 'asserted'
				ORDER BY id""", subjectId, predicate)).stream().map(Fact::from)
				.filter(f -> "current".equals(f.state(now))).toList();
	}

	/** Current open asserted facts of the subject under the predicate whose object is x or lies within x. */
	public List<Fact> assertedTouching(long subjectId, String predicate, long x) {
		return db.read(tx -> assertedOpen(tx, subjectId, predicate).stream()
				.filter(f -> f.objectId() != null && (f.objectId() == x
						|| Containment.ancestors(tx, f.objectId(), predicates.containmentPredicates()).contains(x)))
				.toList());
	}

	static List<Fact> assertedOpen(Tx tx, long subjectId, String predicate) {
		return tx.query("""
				SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND ended = 0
				AND valid_end IS NULL AND mode = 'asserted' ORDER BY id""", subjectId, predicate).stream()
				.map(Fact::from).toList();
	}

	/** Every observation that stated or corroborated the fact, the fact's home first. */
	public List<Long> observationsOf(long factId) {
		return db.read(tx -> FactLedger.observationsOf(tx, factId));
	}

	public List<Supersession> supersessionsOf(long factId) {
		return db.read(tx -> tx.query("SELECT * FROM supersession WHERE fact_id = ? ORDER BY id", factId).stream()
				.map(Supersession::from).toList());
	}

	/** Every fact that ever touched the entity, with its changes, and the tombstones of forgotten observations. */
	public History history(long entityId, String predicate) {
		List<Fact> facts = db.read(tx -> (predicate == null
				? tx.query("""
						SELECT * FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ? ORDER BY id""",
						entityId, entityId, entityId)
				: tx.query("""
						SELECT * FROM fact WHERE (subject_id = ? OR object_id = ? OR scope_id = ?) AND predicate = ?
						ORDER BY id""", entityId, entityId, entityId, predicate)).stream().map(Fact::from).toList());
		var entries = new ArrayList<HistoryEntry>();
		for (Fact f : facts) {
			entries.add(new HistoryEntry(f, supersessionsOf(f.id())));
		}
		List<Tombstone> tombstones = db.read(tx -> tx.query("""
				SELECT o.id, o.forgotten_at FROM forgotten_link l JOIN observation o ON o.id = l.observation_id
				WHERE l.entity_id = ? AND o.forgotten_at IS NOT NULL ORDER BY o.id""", entityId).stream()
				.map(r -> new Tombstone(r.lng("id"), r.str("forgotten_at"))).toList());
		return new History(entries, tombstones);
	}

	/** The current facts stated or corrected into the record; derived ones are counted apart. */
	public long count() {
		return db.read(tx -> tx
				.queryLong("SELECT COUNT(*) FROM fact WHERE status = 'current' AND derivation_kind <> 'derived'"));
	}

	/** The current facts the rules derived (family K). */
	public long derivedCount() {
		return db.read(tx -> tx
				.queryLong("SELECT COUNT(*) FROM fact WHERE status = 'current' AND derivation_kind = 'derived'"));
	}

	/** The kind of source the fact's home observation came from. */
	public String sourceKind(Fact f) {
		return db.read(tx -> tx.queryOne("SELECT source_kind FROM observation WHERE id = ?", f.observationId())
				.map(r -> r.str("source_kind")).orElse("user"));
	}

	/**
	 * Confidence from provenance alone: the source kind sets the base, inference lowers it, corroboration raises it,
	 * and the caller can lower it but never raise it. Never a function of the clock.
	 */
	public double confidence(Fact f) {
		double base = switch (sourceKind(f)) {
		case "user", "correction" -> 0.80;
		case "conversation" -> 0.75;
		case "document" -> 0.70;
		default -> 0.60;
		};
		if ("inferred".equals(f.derivationKind())) {
			base -= 0.15;
		}
		base += 0.05 * Math.min(4, f.corroborations() - 1);
		if (f.callerConfidence() != null) {
			base = Math.min(base, f.callerConfidence());
		}
		return Math.min(0.98, Math.round(base * 100) / 100.0);
	}

	/**
	 * What a session should confirm before trusting the rest, at most {@code limit} items: plans whose date has passed
	 * with no word since ({@code due}), then the open facts longest without confirmation on predicates that age, oldest
	 * first. A fact confirmed within a fortnight, or well inside its predicate's staleness threshold, is not listed;
	 * past the threshold it is marked {@code likely_changed}.
	 */
	public List<Map<String, Object>> review(int limit) {
		Instant now = clock.instant();
		var out = new ArrayList<Map<String, Object>>();
		for (Row r : db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE status = 'current' AND ended = 0 AND valid_start IS NOT NULL
				AND last_confirmed < valid_start ORDER BY valid_start ASC, id ASC"""))) {
			Fact f = Fact.from(r);
			if (!f.due(now)) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", f.ref());
			m.put("rendering", f.rendering());
			m.put("due", true);
			m.put("planned_for", f.validStart());
			m.put("confirmed", f.lastConfirmed().substring(0, 10));
			m.put("age_days", Duration.between(Instant.parse(f.lastConfirmed()), now).toDays());
			m.put("likely_changed", false);
			out.add(m);
			if (out.size() >= limit) {
				return out;
			}
		}
		for (Row r : db.read(tx -> tx.query("""
				SELECT * FROM fact WHERE status = 'current' AND ended = 0 AND valid_end IS NULL
				ORDER BY last_confirmed ASC, id ASC"""))) {
			Fact f = Fact.from(r);
			Predicate p = predicates.get(f.predicate()).orElse(null);
			if (p == null || !p.ages() || "future".equals(f.state(now))) {
				continue;
			}
			Instant confirmed = Instant.parse(f.lastConfirmed());
			long days = Duration.between(confirmed, now).toDays();
			if (days < 14 || days * 3 < p.stalenessDays()) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", f.ref());
			m.put("rendering", f.rendering());
			m.put("confirmed", confirmed.toString().substring(0, 10));
			m.put("age_days", days);
			m.put("likely_changed", days > p.stalenessDays());
			out.add(m);
			if (out.size() >= limit) {
				break;
			}
		}
		return out;
	}
}
