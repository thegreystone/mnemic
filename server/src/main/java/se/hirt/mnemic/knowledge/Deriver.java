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

import se.hirt.mnemic.knowledge.Rule.Hop;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Materialises the derived predicates (EVALUATION.md family K): the projection one layer further. After anything that
 * changes facts, every rule is walked over the current facts and the derived rows are brought in line: a pair a rule
 * now reaches gets a row (or the asserted fact already there is marked corroborated), a row no rule reaches any more is
 * invalidated with the reason on its history, and a row whose qualifier or interval changed is rewritten. A derived row
 * carries {@code derivation_kind: derived}, the base facts it rests on in {@code fact_derivation}, the intersection of
 * their intervals as its valid time, and the observation of its first base fact as its home, so a forget or a rebuild
 * that takes the base away takes it away too, and the next derivation brings it back if another base still carries it.
 * Rules over derived predicates are walked after the predicates they rest on.
 */
public final class Deriver {

	/** What a derivation pass did. */
	public record Outcome(int derived, int invalidated, int updated, int corroborated) {
		public boolean nothing() {
			return derived == 0 && invalidated == 0 && updated == 0 && corroborated == 0;
		}

		public Map<String, Object> toMap() {
			var m = new LinkedHashMap<String, Object>();
			m.put("facts", derived);
			m.put("invalidated", invalidated);
			m.put("updated", updated);
			m.put("corroborated", corroborated);
			return m;
		}
	}

	private final Database db;
	private final PredicateRegistry predicates;
	private final FactRenderer renderer;

	Deriver(Database db, PredicateRegistry predicates, FactRenderer renderer) {
		this.db = db;
		this.predicates = predicates;
		this.renderer = renderer;
	}

	/** Brings the derived rows in line with the current facts and rules. */
	public synchronized Outcome derive() {
		List<Predicate> derived = ordered();
		if (derived.isEmpty() && attributePredicates().isEmpty()) {
			return new Outcome(0, 0, 0, 0);
		}
		return db.write(tx -> {
			Graph g = Graph.load(tx, predicates);
			int made = 0;
			int gone = 0;
			int changed = 0;
			int corroborated = 0;
			// What the record implies about attributes stands as facts too: "Britt is female" from her being a
			// mother, resting on the facts that say so, so it can be asked about and seen, and steps aside for a
			// statement.
			for (String attribute : attributePredicates()) {
				int[] n = materialiseImplied(tx, g, attribute);
				made += n[0];
				changed += n[1];
				gone += n[2];
			}
			for (Predicate p : derived) {
				Map<Key, Candidate> wanted = evaluate(g, p, predicates.rulesOf(p.name()));
				Map<Key, Fact> asserted = current(tx, p, false);
				Map<Key, Fact> have = current(tx, p, true);
				var kept = new HashSet<Long>();
				for (Map.Entry<Key, Candidate> e : wanted.entrySet()) {
					Candidate c = e.getValue();
					Fact stated = asserted.get(e.getKey());
					if (stated != null) {
						corroborated += corroborate(tx, stated, c);
						continue;
					}
					Fact row = have.get(e.getKey());
					if (row == null) {
						insert(tx, p, c);
						made++;
					} else {
						kept.add(row.id());
						if (!same(tx, row, c)) {
							rewrite(tx, row, c);
							changed++;
						}
					}
				}
				for (Fact row : have.values()) {
					if (!kept.contains(row.id())) {
						invalidate(tx, row);
						gone++;
					}
				}
				// Its rows, as they now stand, are base facts for the rules that rest on it.
				for (Row r : tx.query(
						"SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND " + "object_id IS NOT NULL",
						p.name())) {
					g.add(Fact.from(r), p.symmetric());
				}
			}
			return new Outcome(made, gone, changed, corroborated);
		});
	}

	/**
	 * How an asserted fact on a derived predicate stands to the rules (K3, K5): {@code corroborated} when a chain
	 * reaches the same pair, {@code unresolved} when none does; null for a fact of an asserted predicate or a derived
	 * row.
	 */
	public String unificationOf(Fact f) {
		if ("derived".equals(f.derivationKind()) || predicates.rulesOf(f.predicate()).isEmpty()) {
			return null;
		}
		return db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM fact_derivation WHERE fact_id = ?", f.id())) > 0
				? "corroborated" : "unresolved";
	}

	/**
	 * What the rules say about the pair an asserted fact names (K6, K7): walked back from the object, a chain is
	 * complete when every node it reaches continues along the next hop, and the subjects the complete chains end at are
	 * the record's own answer. Only the rules whose qualifier the fact names are walked when it names one.
	 */
	public record Chains(boolean complete, List<Long> subjects) {
		public boolean contradicts(Fact f) {
			return complete && !subjects.contains(f.subjectId());
		}
	}

	public Chains chainsFor(Fact f) {
		Predicate p = predicates.get(f.predicate()).orElseThrow();
		List<Rule> rules = predicates.rulesOf(p.name());
		List<Rule> named = rules.stream().filter(r -> f.qualifier() != null && f.qualifier().equals(r.qualifier()))
				.toList();
		List<Rule> walked = named.isEmpty() ? rules : named;
		return db.read(tx -> {
			Graph g = Graph.load(tx, predicates, p.name());
			boolean complete = true;
			var subjects = new TreeSet<Long>();
			for (Rule r : walked) {
				var frontier = new TreeSet<Long>(List.of(f.objectId()));
				for (int i = r.path().size() - 1; i >= 0; i--) {
					Hop h = r.path().get(i);
					Hop back = new Hop(h.predicate(), h.qualifier(), !h.inverse());
					var next = new TreeSet<Long>();
					for (long n : frontier) {
						List<Edge> edges = g.edges(n, back);
						if (edges.isEmpty()) {
							complete = false;
						}
						for (Edge e : edges) {
							next.add(e.to());
						}
					}
					frontier = next;
				}
				frontier.remove(f.objectId());
				subjects.addAll(frontier);
			}
			return new Chains(complete, List.copyOf(subjects));
		});
	}

	/**
	 * Entities whose derived relations rendered without the attribute a rule chooses by (an "aunt or uncle" for want of
	 * a gender) because none is stated and nothing on record implies it: per attribute, each with how many relations
	 * wait on it, most first, so the gaps are seen at once rather than one relation at a time. Only entities with such
	 * a relation are listed; being merely known is no gap.
	 */
	public List<Map<String, Object>> attributeUnknown() {
		return db.read(tx -> {
			Graph g = Graph.load(tx, predicates);
			var counts = new LinkedHashMap<String, Map<Long, Integer>>();
			for (Row r : tx.query("SELECT f.subject_id, f.predicate, f.qualifier, MIN(d.rule) AS rule FROM fact f "
					+ "JOIN fact_derivation d ON d.fact_id = f.id WHERE f.status = 'current' AND "
					+ "f.derivation_kind = 'derived' GROUP BY f.id ORDER BY f.id")) {
				List<Rule> rules = predicates.rulesOf(r.str("predicate"));
				int i = (int) r.lng("rule");
				if (i < 0 || i >= rules.size() || rules.get(i).attribute() == null
						|| rules.get(i).chosenByValue(r.str("qualifier"))
						|| g.attribute(rules.get(i).attribute(), r.lng("subject_id")) != null) {
					continue; // no rule by an attribute, or the value chose the word, or a value the rule does not name
				}
				counts.computeIfAbsent(rules.get(i).attribute(), k -> new LinkedHashMap<>()).merge(r.lng("subject_id"),
						1, Integer::sum);
			}
			var out = new ArrayList<Map<String, Object>>();
			for (Map.Entry<String, Map<Long, Integer>> a : counts.entrySet()) {
				a.getValue().entrySet().stream().sorted((x, y) -> Integer.compare(y.getValue(), x.getValue())).limit(25)
						.forEach(e -> {
							var m = new LinkedHashMap<String, Object>();
							m.put("attribute", a.getKey());
							m.put("entity", "ent-" + e.getKey());
							m.put("name", tx.queryOne("SELECT name FROM entity WHERE id = ?", e.getKey())
									.map(x -> x.str("name")).orElse(null));
							m.put("neutral_relations", e.getValue());
							m.put("stated_roles", g.rolesOf(e.getKey(), a.getKey()));
							out.add(m);
						});
			}
			return out;
		});
	}

	/** The base facts and rule of a derived fact, or the derivations that corroborate an asserted one. */
	public Map<String, Object> derivationOf(long factId) {
		return db.read(tx -> {
			List<Row> rows = tx.query("SELECT * FROM fact_derivation WHERE fact_id = ? ORDER BY rule, base_fact_id",
					factId);
			if (rows.isEmpty()) {
				return Map.of();
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("kind", rows.getFirst().str("kind"));
			m.put("rule", rows.getFirst().lng("rule"));
			m.put("base", rows.stream().map(r -> "f-" + r.lng("base_fact_id")).distinct().toList());
			// A lasting row with no end whose base a death ended says so, so a reader sees why it is still current.
			Fact row = tx.query("SELECT * FROM fact WHERE id = ?", factId).stream().map(Fact::from).findFirst()
					.orElse(null);
			if (row != null && "derived".equals(row.derivationKind()) && row.validEnd() == null
					&& predicates.get(row.predicate()).map(Predicate::lasting).orElse(false)) {
				Map<Long, List<String[]>> deaths = Graph.deaths(tx);
				var outlived = new ArrayList<String>();
				for (Row r : rows) {
					long baseId = r.lng("base_fact_id");
					Fact base = tx.query("SELECT * FROM fact WHERE id = ?", baseId).stream().map(Fact::from).findFirst()
							.orElse(null);
					boolean closed = tx.queryLong(
							"SELECT COUNT(*) FROM supersession WHERE fact_id = ? AND kind = 'entity_ended'",
							baseId) > 0;
					if (base != null && Graph.byDeath(base, closed, deaths)) {
						outlived.add(base.rendering() + " [" + base.ref() + "]");
					}
				}
				if (!outlived.isEmpty()) {
					m.put("outlived", outlived);
				}
			}
			return m;
		});
	}

	// ── evaluation ───────────────────────────────────────────────────────

	/** A subject and object pair, one way round for a symmetric predicate. */
	private record Key(long subject, long object) {
	}

	/** What a rule found for a pair: the rule, its qualifier, the facts walked, and the interval they share. */
	private record Candidate(int rule, String qualifier, List<Long> base, Bounds bounds, boolean ended, long subject,
			long object) {
	}

	private record Path(List<Long> nodes, List<Edge> edges) {
	}

	private Map<Key, Candidate> evaluate(Graph g, Predicate p, List<Rule> rules) {
		var out = new LinkedHashMap<Key, Candidate>();
		for (int i = 0; i < rules.size(); i++) {
			Rule rule = rules.get(i);
			Hop first = rule.path().getFirst();
			for (long start : g.starts(first)) {
				var byEnd = new LinkedHashMap<Long, List<Path>>();
				walk(g, rule.path(), 0, start, new ArrayList<>(List.of(start)), new ArrayList<>(), byEnd);
				for (Map.Entry<Long, List<Path>> e : byEnd.entrySet()) {
					long end = e.getKey();
					if (rule.not().stream().anyMatch(n -> n.startsWith("^") ? g.connected(end, start, n.substring(1))
							: g.connected(start, end, n))) {
						continue;
					}
					int paths = e.getValue().size();
					if ((rule.minPaths() != null && paths < rule.minPaths())
							|| (rule.maxPaths() != null && paths > rule.maxPaths())) {
						continue;
					}
					if (rule.minDegree() != null) {
						Hop last = rule.path().getLast();
						Hop back = new Hop(last.predicate(), last.qualifier(), !last.inverse());
						if (g.degree(start, first) < rule.minDegree() || g.degree(end, back) < rule.minDegree()) {
							continue; // too little is known of one of them to say
						}
					}
					Key key = p.symmetric() ? new Key(Math.min(start, end), Math.max(start, end)) : new Key(start, end);
					if (out.containsKey(key)) {
						continue; // an earlier, more specific rule holds
					}
					String value = rule.attribute() == null ? null : g.attribute(rule.attribute(), key.subject());
					out.put(key, candidate(i, rule, e.getValue(), key, value, p.lasting()));
				}
			}
		}
		return out;
	}

	/** Every simple path from {@code node} along the hops from {@code at}, grouped by where it ends. */
	private static void walk(
		Graph g, List<Hop> hops, int at, long node, List<Long> nodes, List<Edge> edges, Map<Long, List<Path>> byEnd) {
		if (at == hops.size()) {
			byEnd.computeIfAbsent(node, k -> new ArrayList<>()).add(new Path(List.copyOf(nodes), List.copyOf(edges)));
			return;
		}
		Hop hop = hops.get(at);
		var seen = new HashSet<Long>();
		for (Edge e : g.edges(node, hop)) {
			long next = e.to();
			if (nodes.contains(next) || !seen.add(next)) {
				continue; // simple paths only, one edge per neighbour
			}
			nodes.add(next);
			edges.add(e);
			walk(g, hops, at + 1, next, nodes, edges, byEnd);
			edges.removeLast();
			nodes.removeLast();
		}
	}

	/**
	 * The interval a set of paths supports: a path holds while all its facts hold; the pair holds while any path does.
	 */
	/**
	 * {@code lasting}: the derived predicate is one a death does not end, so a base fact a death ended still carries
	 * it: a stepfather who died is a late stepfather, not a former one. A relation that is not (coworkers) ends when
	 * its base does.
	 */
	private static Candidate candidate(int rule, Rule r, List<Path> paths, Key key, String value, boolean lasting) {
		var base = new TreeSet<Long>();
		Bounds bounds = Bounds.NONE;
		boolean ended = true;
		boolean first = true;
		for (Path path : paths) {
			Bounds pb = Bounds.NONE;
			boolean pathEnded = false;
			for (Edge e : path.edges()) {
				base.add(e.factId());
				Bounds b = e.bounds();
				boolean outlived = lasting && e.byDeath();
				if (b.start() != null && (pb.start() == null || b.start().compareTo(pb.start()) > 0)) {
					pb = pb.withStart(b.start(), b.startPrecision(), "derived");
				}
				if (!outlived && b.end() != null && (pb.end() == null || b.end().compareTo(pb.end()) < 0)) {
					pb = pb.withEnd(b.end(), b.endPrecision(), "derived");
				}
				pathEnded |= e.ended() && !outlived;
			}
			pathEnded |= pb.end() != null;
			if (first) {
				bounds = pb;
				ended = pathEnded;
				first = false;
				continue;
			}
			// Union across paths: the earliest start; open while any path is open, else the latest end.
			if (pb.start() == null || (bounds.start() != null && pb.start().compareTo(bounds.start()) < 0)) {
				bounds = new Bounds(pb.start(), pb.startPrecision(), pb.start() == null ? null : "derived",
						bounds.end(), bounds.endPrecision(), bounds.endSource());
			}
			if (!pathEnded) {
				bounds = new Bounds(bounds.start(), bounds.startPrecision(), bounds.startSource(), null, null, null);
				ended = false;
			} else if (ended && pb.end() != null && (bounds.end() == null || pb.end().compareTo(bounds.end()) > 0)) {
				bounds = bounds.withEnd(pb.end(), pb.endPrecision(), "derived");
			}
		}
		return new Candidate(rule, r.qualifierFor(value), List.copyOf(base), bounds, ended, key.subject(),
				key.object());
	}

	// ── rows ─────────────────────────────────────────────────────────────

	private static Map<Key, Fact> current(Tx tx, Predicate p, boolean derived) {
		var out = new LinkedHashMap<Key, Fact>();
		for (Row r : tx.query(
				"SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND object_id IS NOT NULL "
						+ "AND mode = 'asserted' AND derivation_kind " + (derived ? "=" : "<>") + " 'derived'",
				p.name())) {
			Fact f = Fact.from(r);
			Key key = p.symmetric()
					? new Key(Math.min(f.subjectId(), f.objectId()), Math.max(f.subjectId(), f.objectId()))
					: new Key(f.subjectId(), f.objectId());
			out.putIfAbsent(key, f);
		}
		return out;
	}

	private static boolean same(Tx tx, Fact row, Candidate c) {
		List<Long> base = tx
				.query("SELECT base_fact_id FROM fact_derivation WHERE fact_id = ? ORDER BY base_fact_id", row.id())
				.stream().map(r -> r.lng("base_fact_id")).toList();
		return Objects.equals(row.qualifier(), c.qualifier()) && Objects.equals(row.validStart(), c.bounds().start())
				&& Objects.equals(row.validEnd(), c.bounds().end()) && row.ended() == c.ended()
				&& base.equals(c.base());
	}

	/** The predicates some rule chooses by, or some predicate's terms imply: the attributes the record reads. */
	private Set<String> attributePredicates() {
		var out = new TreeSet<String>();
		for (Predicate p : predicates.all()) {
			for (Rule r : predicates.rulesOf(p.name())) {
				if (r.attribute() != null) {
					out.add(r.attribute());
				}
			}
			for (Map<String, String> assigns : predicates.impliesOf(p.name()).values()) {
				out.addAll(assigns.keySet());
			}
		}
		out.removeIf(a -> predicates.get(a).isEmpty());
		return out;
	}

	/**
	 * Derived rows under an attribute predicate for the entities whose other facts imply a value and none is stated:
	 * made, rewritten, or invalidated to match. Returns {made, rewritten, invalidated}.
	 */
	private int[] materialiseImplied(Tx tx, Graph g, String attribute) {
		Map<Long, Graph.Implied> wanted = g.implied(attribute);
		var have = new LinkedHashMap<Long, Fact>();
		for (Row r : tx.query("SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND "
				+ "derivation_kind = 'derived' AND object_text IS NOT NULL", attribute)) {
			Fact f = Fact.from(r);
			have.putIfAbsent(f.subjectId(), f);
		}
		int made = 0;
		int changed = 0;
		int gone = 0;
		var kept = new HashSet<Long>();
		for (Map.Entry<Long, Graph.Implied> e : wanted.entrySet()) {
			long subject = e.getKey();
			Graph.Implied w = e.getValue();
			Fact row = have.get(subject);
			if (row == null) {
				long home = tx.queryLong("SELECT MIN(observation_id) FROM fact WHERE id IN (" + ids(w.base()) + ")");
				long id = tx.insert("""
						INSERT INTO fact(subject_id, predicate, object_id, object_text, ended, status, derivation_kind,
						                 observation_id, rendering, corroborations, last_confirmed, created_at, mode)
						VALUES (?,?,NULL,?,0,'current','derived',?,'',1,?,?,'asserted')""", subject, attribute,
						w.value(), home, Instant.now().toString(), Instant.now().toString());
				linkImplied(tx, id, w);
				renderer.rerender(tx, id);
				made++;
				continue;
			}
			kept.add(row.id());
			List<Long> base = tx
					.query("SELECT base_fact_id FROM fact_derivation WHERE fact_id = ? ORDER BY " + "base_fact_id",
							row.id())
					.stream().map(r -> r.lng("base_fact_id")).toList();
			if (!w.value().equals(row.objectText()) || !base.equals(w.base().stream().sorted().toList())) {
				tx.update("UPDATE fact SET object_text = ? WHERE id = ?", w.value(), row.id());
				tx.update("DELETE FROM fact_derivation WHERE fact_id = ?", row.id());
				linkImplied(tx, row.id(), w);
				renderer.rerender(tx, row.id());
				changed++;
			}
		}
		for (Fact row : have.values()) {
			if (!kept.contains(row.id())) {
				invalidate(tx, row);
				gone++;
			}
		}
		return new int[] {made, changed, gone};
	}

	private static void linkImplied(Tx tx, long id, Graph.Implied w) {
		for (long base : w.base()) {
			tx.update("INSERT OR IGNORE INTO fact_derivation(fact_id, rule, base_fact_id, kind) VALUES (?,?,?,?)", id,
					-1, base, "implied");
		}
		for (Row r : tx.query("SELECT DISTINCT observation_id FROM fact WHERE id IN (" + ids(w.base()) + ")")) {
			FactLedger.link(tx, id, r.lng("observation_id"), "derived");
		}
	}

	private void insert(Tx tx, Predicate p, Candidate c) {
		long home = tx.queryLong("SELECT MIN(observation_id) FROM fact WHERE id IN (" + ids(c.base()) + ")");
		Bounds b = c.bounds();
		long id = tx.insert("""
				INSERT INTO fact(subject_id, predicate, object_id, object_text, qualifier, scope_id, valid_start,
				                 valid_start_precision, valid_end, valid_end_precision, ended, status, derivation_kind,
				                 observation_id, rendering, corroborations, last_confirmed, created_at, start_source,
				                 end_source, mode)
				VALUES (?,?,?,NULL,?,NULL,?,?,?,?,?,'current','derived',?,'',1,?,?,?,?,'asserted')""", c.subject(),
				p.name(), c.object(), c.qualifier(), b.start(), b.startPrecision(), b.end(), b.endPrecision(),
				c.ended() ? 1 : 0, home, Instant.now().toString(), Instant.now().toString(),
				b.start() == null ? null : "derived", b.end() == null ? null : "derived");
		link(tx, id, c, "base");
		FactLedger.link(tx, id, home, "derived");
		linkObservations(tx, id, c);
		renderer.rerender(tx, id);
	}

	private void rewrite(Tx tx, Fact row, Candidate c) {
		Bounds b = c.bounds();
		tx.update("""
				UPDATE fact SET qualifier = ?, valid_start = ?, valid_start_precision = ?, valid_end = ?,
				                valid_end_precision = ?, ended = ?, start_source = ?, end_source = ? WHERE id = ?""",
				c.qualifier(), b.start(), b.startPrecision(), b.end(), b.endPrecision(), c.ended() ? 1 : 0,
				b.start() == null ? null : "derived", b.end() == null ? null : "derived", row.id());
		tx.update("DELETE FROM fact_derivation WHERE fact_id = ?", row.id());
		link(tx, row.id(), c, "base");
		linkObservations(tx, row.id(), c);
		renderer.rerender(tx, row.id());
	}

	private static void invalidate(Tx tx, Fact row) {
		List<String> gone = tx
				.query("SELECT d.base_fact_id AS id, f.status FROM fact_derivation d "
						+ "LEFT JOIN fact f ON f.id = d.base_fact_id WHERE d.fact_id = ?", row.id())
				.stream().filter(r -> r.str("status") == null || !"current".equals(r.str("status"))).map(r -> "f-"
						+ r.lng("id") + (r.str("status") == null ? " (forgotten)" : " (" + r.str("status") + ")"))
				.toList();
		String reason = gone.isEmpty() ? "no rule derives it any more"
				: "a base fact changed: " + String.join(", ", gone);
		tx.update("UPDATE fact SET status = 'invalidated' WHERE id = ?", row.id());
		FactLedger.supersession(tx, row.id(), null, "invalidation", reason, null, null, null);
	}

	private static int corroborate(Tx tx, Fact stated, Candidate c) {
		int n = 0;
		for (long base : c.base()) {
			n += tx.update("INSERT OR IGNORE INTO fact_derivation(fact_id, rule, base_fact_id, kind) VALUES (?,?,?,?)",
					stated.id(), c.rule(), base, "corroborates");
		}
		return n > 0 ? 1 : 0;
	}

	/** A derived fact cites every observation behind its base facts, so each is its source and a forget cascades. */
	private static void linkObservations(Tx tx, long id, Candidate c) {
		for (Row r : tx.query("SELECT DISTINCT observation_id FROM fact WHERE id IN (" + ids(c.base()) + ")")) {
			FactLedger.link(tx, id, r.lng("observation_id"), "derived");
		}
	}

	/**
	 * The derived fact that covers a stated one (a stated related_to[aunt] beside a derived aunt_uncle_of): a current
	 * derived row between the same two entities whose predicate names the stated role, or any when no role was stated.
	 */
	public Optional<Fact> covering(Fact stated) {
		if (stated.objectId() == null) {
			return Optional.empty();
		}
		return db.read(tx -> {
			for (Row r : tx.query(
					"SELECT * FROM fact WHERE status = 'current' AND derivation_kind = 'derived' AND "
							+ "((subject_id = ? AND object_id = ?) OR (subject_id = ? AND object_id = ?)) ORDER BY id",
					stated.subjectId(), stated.objectId(), stated.objectId(), stated.subjectId())) {
				Fact d = Fact.from(r);
				if (d.id() != stated.id() && (stated.qualifier() == null || predicates.get(d.predicate())
						.map(p -> PredicateRegistry.namesRole(p, stated.qualifier())).orElse(false))) {
					return Optional.of(d);
				}
			}
			return Optional.empty();
		});
	}

	private static void link(Tx tx, long id, Candidate c, String kind) {
		for (long base : c.base()) {
			tx.update("INSERT OR IGNORE INTO fact_derivation(fact_id, rule, base_fact_id, kind) VALUES (?,?,?,?)", id,
					c.rule(), base, kind);
		}
	}

	private static String ids(List<Long> ids) {
		return String.join(",", ids.stream().map(String::valueOf).toList());
	}

	/** The derived predicates, each after the ones its rules walk. */
	private List<Predicate> ordered() {
		var out = new ArrayList<Predicate>();
		var done = new HashSet<String>();
		for (Predicate p : predicates.derived()) {
			order(p, out, done, new HashSet<>());
		}
		return out;
	}

	private void order(Predicate p, List<Predicate> out, Set<String> done, Set<String> visiting) {
		if (done.contains(p.name()) || !visiting.add(p.name())) {
			return;
		}
		for (Rule r : predicates.rulesOf(p.name())) {
			for (Hop h : r.path()) {
				predicates.get(h.predicate()).filter(q -> !predicates.rulesOf(q.name()).isEmpty())
						.ifPresent(q -> order(q, out, done, visiting));
			}
		}
		done.add(p.name());
		out.add(p);
	}

	// ── the graph ────────────────────────────────────────────────────────

	/** A base fact as an edge, in the direction it is walked. */
	private record Edge(long from, long to, String predicate, String qualifier, long factId, Bounds bounds,
			boolean ended, boolean byDeath) {
	}

	/** The current asserted facts between entities, indexed both ways. */
	private static final class Graph {
		private final Map<Long, List<Edge>> forward = new HashMap<>();
		private final Map<Long, List<Edge>> backward = new HashMap<>();

		/** The fact was closed by a participant's death: a supersession of kind entity_ended on its ledger. */
		private static final String BY_DEATH = "EXISTS (SELECT 1 FROM supersession s WHERE s.fact_id = fact.id "
				+ "AND s.kind = 'entity_ended') AS by_death";

		/** When each entity's record says it ended (died, dissolved): the date and its precision, per event. */
		private static Map<Long, List<String[]>> deaths(Tx tx) {
			var out = new HashMap<Long, List<String[]>>();
			for (Row r : tx
					.query("""
							SELECT ep.entity_id AS entity_id, ev.valid_start AS at, ev.valid_start_precision AS precision
							FROM event ev JOIN event_type et ON et.name = ev.type JOIN event_participant ep ON ep.event_id = ev.id
							WHERE et.ends_entity = 1 AND ev.valid_start IS NOT NULL""")) {
				out.computeIfAbsent(r.lng("entity_id"), k -> new ArrayList<>())
						.add(new String[] {r.str("at"), r.str("precision")});
			}
			return out;
		}

		/**
		 * Whether a death ended the fact: the death closed it (the ledger says so), or the caller wrote the end
		 * themselves and it falls on the day a participant's record says they died ("married Lars until 2014-10-07"
		 * beside "Lars died 2014-10-07" is a marriage death ended, however it was written). Dates are stored normalised
		 * ("2021" as 2021-01-01 at year precision), so they match at the coarser precision of the two. An end an event
		 * or a later fact explains keeps its explanation, and a derived row's end is its base's, not its own to
		 * attribute.
		 */
		private static boolean byDeath(Fact f, boolean closedByDeath, Map<Long, List<String[]>> deaths) {
			if (closedByDeath) {
				return true;
			}
			if (f.validEnd() == null || !"stated".equals(f.endSource()) || "derived".equals(f.derivationKind())) {
				return false;
			}
			for (Long who : new Long[] {f.subjectId(), f.objectId()}) {
				for (String[] death : deaths.getOrDefault(who, List.of())) {
					String precision = coarser(f.validEndPrecision(), death[1]);
					if (Bounds.show(f.validEnd(), precision).equals(Bounds.show(death[0], precision))) {
						return true;
					}
				}
			}
			return false;
		}

		private static final List<String> COARSE_TO_FINE = List.of("year", "month", "day");

		/** The coarser of two precisions; anything unknown counts as day, which is what {@link Bounds#show} does. */
		private static String coarser(String a, String b) {
			int ia = COARSE_TO_FINE.indexOf(a);
			int ib = COARSE_TO_FINE.indexOf(b);
			return COARSE_TO_FINE.get(Math.min(ia < 0 ? 2 : ia, ib < 0 ? 2 : ib));
		}

		static Graph load(Tx tx, PredicateRegistry predicates) {
			var g = new Graph();
			Map<Long, List<String[]>> deaths = deaths(tx);
			for (Row r : tx.query("SELECT fact.*, " + BY_DEATH + " FROM fact WHERE status = 'current' AND "
					+ "object_id IS NOT NULL AND mode = 'asserted' AND derivation_kind <> 'derived'")) {
				Fact f = Fact.from(r);
				g.add(f, predicates.get(f.predicate()).map(Predicate::symmetric).orElse(false),
						byDeath(f, r.lng("by_death") == 1, deaths));
			}
			g.predicates = predicates;
			g.loadStated(tx);
			return g;
		}

		/** A stated current fact as an attribute source: who, under what, with which role, saying what. */
		private record Stated(long id, long subject, String predicate, String qualifier, String literal) {
		}

		/** An attribute value the record implies for an entity, and the stated facts that imply it. */
		record Implied(String value, List<Long> base) {
		}

		private PredicateRegistry predicates;
		private final List<Stated> stated = new ArrayList<>();
		/** Attribute values by predicate name, read on demand: name → entity → value. */
		private final Map<String, Map<Long, String>> attributes = new HashMap<>();

		/**
		 * What the record has to go on for an entity's attribute: each stated role or value of its own, and whether the
		 * predicate declares an implication for it. For the gap list, so a missing value can be read.
		 */
		List<String> rolesOf(long id, String attribute) {
			var out = new ArrayList<String>();
			for (Stated s : stated) {
				if (s.subject() != id) {
					continue;
				}
				String term = s.qualifier() != null ? s.qualifier() : s.literal();
				if (term == null || s.predicate().equals(attribute)) {
					continue;
				}
				Map<String, String> assigns = predicates.impliesOf(s.predicate())
						.get(term.trim().toLowerCase(java.util.Locale.ROOT));
				out.add(s.predicate() + "[" + term + "]"
						+ (assigns != null && assigns.containsKey(attribute)
								? " implies " + attribute + " " + assigns.get(attribute)
								: " (no implication declared for '" + term + "' on " + s.predicate() + ")"));
			}
			return out;
		}

		private void loadStated(Tx tx) {
			// The object as a word: the literal, or the name of the entity a value was resolved to before the
			// attribute predicate was known to take literals.
			for (Row r : tx.query("SELECT id, subject_id, predicate, qualifier, COALESCE(object_text, (SELECT name "
					+ "FROM entity WHERE id = fact.object_id)) AS literal FROM fact WHERE status = 'current' AND "
					+ "mode = 'asserted' AND derivation_kind <> 'derived' ORDER BY id")) {
				stated.add(new Stated(r.lng("id"), r.lng("subject_id"), r.str("predicate"), r.str("qualifier"),
						r.str("literal")));
			}
		}

		/**
		 * The value of an attribute for an entity, or null. First a current fact under the attribute predicate itself,
		 * its value canonicalised by that predicate's own {@code implies} ("woman" → female); else what the qualifiers
		 * and values of the entity's other stated facts imply, where their predicates declare it ({@code implies} on
		 * parent_of: mother → gender female), when they agree; else nothing. Derived rows are never read, so a guess
		 * cannot feed itself.
		 */
		String attribute(String name, long id) {
			return attributes.computeIfAbsent(name, this::attributeValues).get(id);
		}

		/** The entities with no stated value under the attribute whose other facts imply one, with those facts. */
		Map<Long, Implied> implied(String name) {
			var direct = new HashSet<Long>();
			var implied = new LinkedHashMap<Long, Implied>();
			var disagreed = new HashSet<Long>();
			for (Stated s : stated) {
				if (s.predicate().equals(name)) {
					if (s.literal() != null) {
						direct.add(s.subject());
					}
					continue;
				}
				Map<String, Map<String, String>> implies = predicates.impliesOf(s.predicate());
				String term = s.qualifier() != null ? s.qualifier() : s.literal();
				if (implies.isEmpty() || term == null) {
					continue;
				}
				Map<String, String> assigns = implies.get(term.trim().toLowerCase(java.util.Locale.ROOT));
				if (assigns == null || !assigns.containsKey(name)) {
					continue;
				}
				String v = assigns.get(name);
				Implied had = implied.get(s.subject());
				if (had == null) {
					implied.put(s.subject(), new Implied(v, List.of(s.id())));
				} else if (!had.value().equals(v)) {
					disagreed.add(s.subject());
				} else {
					var base = new ArrayList<>(had.base());
					base.add(s.id());
					implied.put(s.subject(), new Implied(v, List.copyOf(base)));
				}
			}
			for (long d : disagreed) {
				implied.remove(d);
			}
			for (long d : direct) {
				implied.remove(d);
			}
			return implied;
		}

		private Map<Long, String> attributeValues(String name) {
			var out = new HashMap<Long, String>();
			for (Map.Entry<Long, Implied> e : implied(name).entrySet()) {
				out.put(e.getKey(), e.getValue().value());
			}
			Map<String, Map<String, String>> own = predicates.impliesOf(name);
			for (Stated s : stated) {
				if (s.predicate().equals(name) && s.literal() != null) {
					String v = s.literal().trim().toLowerCase(java.util.Locale.ROOT);
					Map<String, String> canonical = own.get(v);
					if (canonical != null && canonical.containsKey(name)) {
						v = canonical.get(name);
					}
					out.putIfAbsent(s.subject(), v);
				}
			}
			return out;
		}

		/** How many distinct neighbours a node has along a hop: a person's known parents, say. */
		int degree(long node, Hop hop) {
			var seen = new HashSet<Long>();
			for (Edge e : edges(node, hop)) {
				seen.add(e.to());
			}
			return seen.size();
		}

		/** Every current fact, derived ones too, except those under {@code except}: the graph a chain is checked on. */
		static Graph load(Tx tx, PredicateRegistry predicates, String except) {
			var g = new Graph();
			Map<Long, List<String[]>> deaths = deaths(tx);
			for (Row r : tx.query("SELECT fact.*, " + BY_DEATH + " FROM fact WHERE status = 'current' AND "
					+ "object_id IS NOT NULL AND mode = 'asserted' AND predicate <> ?", except)) {
				Fact f = Fact.from(r);
				g.add(f, predicates.get(f.predicate()).map(Predicate::symmetric).orElse(false),
						byDeath(f, r.lng("by_death") == 1, deaths));
			}
			g.predicates = predicates;
			g.loadStated(tx);
			return g;
		}

		void add(Fact f, boolean symmetric) {
			add(f, symmetric, false);
		}

		/** {@code byDeath}: the fact was ended by a participant's death, which a lasting relation outlives. */
		void add(Fact f, boolean symmetric, boolean byDeath) {
			Bounds b = Bounds.of(f);
			put(new Edge(f.subjectId(), f.objectId(), f.predicate(), f.qualifier(), f.id(), b, f.ended(), byDeath));
			if (symmetric) {
				put(new Edge(f.objectId(), f.subjectId(), f.predicate(), f.qualifier(), f.id(), b, f.ended(), byDeath));
			}
		}

		private void put(Edge e) {
			forward.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
			backward.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
		}

		/** The edges a hop walks from a node, each turned to point the way walked. */
		List<Edge> edges(long node, Hop hop) {
			var out = new ArrayList<Edge>();
			for (Edge e : (hop.inverse() ? backward : forward).getOrDefault(node, List.of())) {
				if (!e.predicate().equals(hop.predicate())) {
					continue;
				}
				if (hop.qualifier() != null && !hop.qualifier().equalsIgnoreCase(e.qualifier())) {
					continue;
				}
				out.add(hop.inverse() ? new Edge(e.to(), e.from(), e.predicate(), e.qualifier(), e.factId(), e.bounds(),
						e.ended(), e.byDeath()) : e);
			}
			return out;
		}

		/** Every node a rule's first hop can leave from. */
		Set<Long> starts(Hop first) {
			var out = new TreeSet<Long>();
			for (Map.Entry<Long, List<Edge>> e : (first.inverse() ? backward : forward).entrySet()) {
				if (e.getValue().stream().anyMatch(x -> x.predicate().equals(first.predicate()))) {
					out.add(e.getKey());
				}
			}
			return out;
		}

		/** Whether a fact of the predicate runs from one node to the other (either way if it is symmetric). */
		boolean connected(long from, long to, String predicate) {
			return forward.getOrDefault(from, List.of()).stream()
					.anyMatch(e -> e.predicate().equals(predicate) && e.to() == to);
		}
	}
}
