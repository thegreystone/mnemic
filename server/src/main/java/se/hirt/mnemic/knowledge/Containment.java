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
import java.util.List;
import java.util.Optional;

/**
 * Where places lie, from the current asserted {@code located_in} facts (EVALUATION.md, family Q). A missing link in a
 * chain is a gap the write path asks about, never a contradiction: only a chain that reaches a different branch or a
 * different country makes two places disjoint.
 */
public final class Containment {

	public enum Relation {
		WITHIN, CONTAINS, DISJOINT, UNKNOWN
	}

	/** {@code top}: the highest place reached from x whose containment is not on record, the one to ask about. */
	public record Verdict(Relation relation, long top) {
	}

	private static final int MAX_HOPS = 6;

	private final Database db;

	public Containment(Database db) {
		this.db = db;
	}

	/** Ancestors of a place, nearest first. */
	public List<Long> ancestors(long entityId) {
		return db.read(tx -> ancestors(tx, entityId));
	}

	static List<Long> ancestors(Tx tx, long entityId) {
		var out = new ArrayList<Long>();
		long at = entityId;
		for (int hop = 0; hop < MAX_HOPS; hop++) {
			Optional<Long> up = tx.queryOne("""
					SELECT object_id FROM fact WHERE subject_id = ? AND predicate = 'located_in' AND status = 'current'
					AND ended = 0 AND mode = 'asserted' AND object_id IS NOT NULL ORDER BY id LIMIT 1""", at)
					.map(r -> r.lng("object_id"));
			if (up.isEmpty() || up.get() == entityId || out.contains(up.get())) {
				break;
			}
			out.add(up.get());
			at = up.get();
		}
		return out;
	}

	/** Whether x lies within {@code bound}. */
	public Verdict of(long x, long bound) {
		return db.read(tx -> of(tx, x, bound));
	}

	static Verdict of(Tx tx, long x, long bound) {
		if (x == bound) {
			return new Verdict(Relation.WITHIN, x);
		}
		List<Long> px = ancestors(tx, x);
		if (px.contains(bound)) {
			return new Verdict(Relation.WITHIN, x);
		}
		var pb = new ArrayList<Long>();
		pb.add(bound);
		pb.addAll(ancestors(tx, bound));
		if (pb.contains(x)) {
			return new Verdict(Relation.CONTAINS, x);
		}
		long top = px.isEmpty() ? x : px.getLast();
		for (Long a : px) {
			if (pb.contains(a)) {
				return new Verdict(Relation.DISJOINT, top); // the chains meet, with x on another branch
			}
		}
		Long cx = countryIn(tx, x, px);
		Long cb = countryIn(tx, bound, pb);
		if (cx != null && cb != null && !cx.equals(cb)) {
			return new Verdict(Relation.DISJOINT, top);
		}
		return new Verdict(Relation.UNKNOWN, top);
	}

	private static Long countryIn(Tx tx, long id, List<Long> path) {
		if ("country".equals(typeOf(tx, id))) {
			return id;
		}
		for (Long a : path) {
			if ("country".equals(typeOf(tx, a))) {
				return a;
			}
		}
		return null;
	}

	static String typeOf(Tx tx, long entityId) {
		return tx.queryOne("SELECT type FROM entity WHERE id = ?", entityId).map(r -> r.str("type")).orElse("unknown");
	}

}
