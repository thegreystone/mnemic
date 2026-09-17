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
import se.hirt.mnemic.persistence.Tx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Where things lie, from the current asserted facts of the containment predicates (EVALUATION.md, family Q): those a
 * definition marks {@code containment}, {@code located_in} and {@code part_of} among the seeds, any a caller adds. A
 * missing link in a chain is a gap the write path asks about, never a contradiction: only a chain that reaches a
 * different branch, or a different thing of a kind that never overlaps (an entity type marked {@code disjoint}:
 * countries by the seed), makes two things disjoint. Nothing here knows a predicate or a kind by name.
 */
public final class Containment {

	public enum Relation {
		WITHIN, CONTAINS, DISJOINT, UNKNOWN
	}

	/** {@code top}: the highest thing reached from x whose containment is not on record, the one to ask about. */
	public record Verdict(Relation relation, long top) {
	}

	private static final int MAX_HOPS = 6;

	private final Database db;
	private final PredicateRegistry predicates;
	private final EntityTypeRegistry types;

	public Containment(Database db, PredicateRegistry predicates, EntityTypeRegistry types) {
		this.db = db;
		this.predicates = predicates;
		this.types = types;
	}

	/** Ancestors of a thing, nearest first. */
	public List<Long> ancestors(long entityId) {
		return db.read(tx -> ancestors(tx, entityId, predicates.containmentPredicates()));
	}

	/** Whether x lies within {@code bound}. */
	public Verdict of(long x, long bound) {
		return db.read(tx -> of(tx, x, bound, predicates.containmentPredicates(), types));
	}

	/** Ancestors along the given containment predicates, nearest first. */
	static List<Long> ancestors(Tx tx, long entityId, Collection<String> within) {
		var out = new ArrayList<Long>();
		if (within.isEmpty()) {
			return out;
		}
		String in = String.join(",", within.stream().map(p -> "'" + p.replace("'", "''") + "'").toList());
		long at = entityId;
		for (int hop = 0; hop < MAX_HOPS; hop++) {
			Optional<Long> up = tx.queryOne("SELECT object_id FROM fact WHERE subject_id = ? AND predicate IN (" + in
					+ ") AND status = 'current' AND ended = 0 AND mode = 'asserted' AND object_id IS NOT NULL "
					+ "ORDER BY id LIMIT 1", at).map(r -> r.lng("object_id"));
			if (up.isEmpty() || up.get() == entityId || out.contains(up.get())) {
				break;
			}
			out.add(up.get());
			at = up.get();
		}
		return out;
	}

	static Verdict of(Tx tx, long x, long bound, Collection<String> within, EntityTypeRegistry types) {
		if (x == bound) {
			return new Verdict(Relation.WITHIN, x);
		}
		List<Long> px = ancestors(tx, x, within);
		if (px.contains(bound)) {
			return new Verdict(Relation.WITHIN, x);
		}
		var pb = new ArrayList<Long>();
		pb.add(bound);
		pb.addAll(ancestors(tx, bound, within));
		if (pb.contains(x)) {
			return new Verdict(Relation.CONTAINS, x);
		}
		long top = px.isEmpty() ? x : px.getLast();
		for (Long a : px) {
			if (pb.contains(a)) {
				return new Verdict(Relation.DISJOINT, top); // the chains meet, with x on another branch
			}
		}
		Long dx = disjointIn(tx, x, px, types);
		Long dbound = disjointIn(tx, bound, pb, types);
		if (dx != null && dbound != null && !dx.equals(dbound)) {
			return new Verdict(Relation.DISJOINT, top);
		}
		return new Verdict(Relation.UNKNOWN, top);
	}

	/** The first thing on the way up of a kind that never overlaps another of its kind, or null. */
	private static Long disjointIn(Tx tx, long id, List<Long> path, EntityTypeRegistry types) {
		if (types.isDisjoint(typeOf(tx, id))) {
			return id;
		}
		for (Long a : path) {
			if (types.isDisjoint(typeOf(tx, a))) {
				return a;
			}
		}
		return null;
	}

	static String typeOf(Tx tx, long entityId) {
		return tx.queryOne("SELECT type FROM entity WHERE id = ?", entityId).map(r -> r.str("type")).orElse("unknown");
	}
}
