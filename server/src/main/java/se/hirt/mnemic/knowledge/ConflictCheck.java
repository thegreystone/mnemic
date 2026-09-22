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

import se.hirt.mnemic.knowledge.Containment.Relation;
import se.hirt.mnemic.knowledge.Containment.Verdict;
import se.hirt.mnemic.knowledge.FactService.Operands;
import se.hirt.mnemic.persistence.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a new fact can stand beside what is on record, decided before its row is written. A functional predicate
 * holds one current value: a second one is a conflict unless an event supersedes the old value, the intervals are
 * disjoint, or one of them has ended; a value that precedes a later one is history and ends where the later one starts.
 * Across modes (EVALUATION.md, family Q), an assertion against a negation, a negation against an assertion, and a fact
 * outside a restriction or beyond a closure are conflicts the user settles; a containment the chain cannot decide is a
 * question, never a contradiction.
 */
final class ConflictCheck {

	/**
	 * The outcome: the fact the new one conflicts with and why (null when it stands), containment gaps to ask about
	 * ({@code top, bound, servedFactId}, the last -1 for the fact being stored), facts a superseding event closes, and
	 * the bounds and ended flag the row takes.
	 */
	record Outcome(Fact conflictWith, String why, List<long[]> asks, List<Fact> toClose, Bounds row, boolean rowEnded) {
		boolean pending() {
			return conflictWith != null;
		}
	}

	private final EventTypeRegistry eventTypes;
	private final EntityTypeRegistry types;
	private final PredicateRegistry predicates;

	ConflictCheck(EventTypeRegistry eventTypes, EntityTypeRegistry types, PredicateRegistry predicates) {
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.types = types;
	}

	/**
	 * Whether the new value and the value on record are one thing at two granularities: either lies within the other.
	 */
	boolean nested(Tx tx, Operands op, Fact other) {
		if (op.object() == null || other.objectId() == null) {
			return false;
		}
		return nested(tx, op.object().id(), other.objectId());
	}

	boolean nested(Tx tx, long a, long b) {
		if (a == b) {
			return false;
		}
		Relation r = Containment.of(tx, a, b, predicates.containmentPredicates(), types).relation();
		return r == Relation.WITHIN || r == Relation.CONTAINS;
	}

	Outcome check(Tx tx, Operands op, Bounds bounds, boolean ended, Event event, String rendering) {
		var toClose = new ArrayList<Fact>();
		var asks = new ArrayList<long[]>();
		Fact conflictWith = null;
		String why = null;
		Bounds row = bounds;
		boolean rowEnded = ended;
		if (op.asserted() && op.predicate().functional()) {
			Fact nearestLater = null;
			for (Fact other : otherCurrentValues(tx, op)) {
				boolean otherStartsAfter = other.validStart() != null && bounds.start() != null
						&& other.validStart().compareTo(bounds.start()) > 0;
				boolean overlapsLater = otherStartsAfter && bounds.end() != null
						&& bounds.end().compareTo(other.validStart()) > 0;
				if (otherStartsAfter && !overlapsLater) {
					if (nearestLater == null || other.validStart().compareTo(nearestLater.validStart()) < 0) {
						nearestLater = other;
					}
					continue;
				}
				if (event != null && eventTypes.supersedes(event.type(), op.predicate().name())) {
					toClose.add(other);
				} else if (disjoint(other, bounds) || other.ended() || ended) {
					continue;
				} else if (nested(tx, op, other)) {
					// "lives in Gschweighusweg 20b" beside "lives in Küssnacht", with 20b located in Küssnacht:
					// one place at two granularities, not two places. Both stand (2026-09-22).
					continue;
				} else {
					conflictWith = other;
					break;
				}
			}
			// A fact that precedes a current one is history, ended where the later one starts: the same inference
			// drawn when the later fact arrives second, so arrival order does not change the outcome.
			if (nearestLater != null && bounds.end() == null && !ended) {
				row = bounds.withEnd(nearestLater.validStart(), nearestLater.validStartPrecision(), "sequence");
				rowEnded = true;
			}
		}
		if (conflictWith == null && !rowEnded) {
			switch (op.mode()) {
			case "asserted" -> {
				Optional<Fact> neg = sameKey(tx, op, "negated");
				if (neg.isPresent()) {
					conflictWith = neg.get();
					why = "\"" + neg.get().rendering() + "\" is on record and \"" + rendering
							+ "\" says the opposite. Did it become so (ended: the negation held until now), was the "
							+ "negation wrong from the start (wrong), or is the new fact wrong (reject)?";
				} else if (op.object() != null) {
					for (Fact bound : boundsIn(tx, op.subject().id(), op.predicate().name())) {
						if ("only".equals(bound.mode()) && bound.objectId() != null
								&& predicates.canContain(types.lineage(op.object().type()))) {
							Verdict c = Containment.of(tx, op.object().id(), bound.objectId(),
									predicates.containmentPredicates(), types);
							if (c.relation() == Relation.DISJOINT) {
								conflictWith = bound;
								why = "\"" + bound.rendering() + "\" is on record and " + op.object().name()
										+ " lies outside " + FactRenderer.nameIn(tx, bound.objectId())
										+ ". Did the restriction "
										+ "end (ended), was it wrong from the start (wrong), or is the new fact wrong (reject)?";
								break;
							}
							if (c.relation() == Relation.UNKNOWN) {
								asks.add(new long[] {c.top(), bound.objectId(), bound.id()});
							}
						} else if ("closure".equals(bound.mode())
								&& types.isA(op.object().type(), bound.objectText())) {
							conflictWith = bound;
							why = "\"" + bound.rendering() + "\" is on record and \"" + rendering
									+ "\" adds to that class. Was the list complete until now (ended), was it never "
									+ "complete (wrong), or is the new fact wrong (reject)?";
							break;
						}
					}
				}
			}
			case "negated" -> {
				Optional<Fact> pos = sameKey(tx, op, "asserted");
				if (pos.isPresent()) {
					conflictWith = pos.get();
					why = "\"" + pos.get().rendering() + "\" is on record and \"" + rendering
							+ "\" says the opposite. Did it stop being so (ended), was the earlier fact wrong from the "
							+ "start (wrong), or is the negation wrong (reject)?";
				}
			}
			case "only" -> {
				if (op.object() != null) {
					for (Fact other : FactQueries.assertedOpen(tx, op.subject().id(), op.predicate().name())) {
						if (other.objectId() == null
								|| !predicates.canContain(types.lineage(Containment.typeOf(tx, other.objectId())))) {
							continue; // a domain or a printer cannot be located: outside the class
						}
						Verdict c = Containment.of(tx, other.objectId(), op.object().id(),
								predicates.containmentPredicates(), types);
						if (c.relation() == Relation.DISJOINT) {
							conflictWith = other;
							why = "\"" + other.rendering() + "\" is on record and lies outside " + op.object().name()
									+ ", which \"" + rendering
									+ "\" excludes. Did the earlier fact end (ended), was it wrong "
									+ "(wrong), or is the restriction wrong (reject)?";
							break;
						}
						if (c.relation() == Relation.UNKNOWN) {
							asks.add(new long[] {c.top(), op.object().id(), -1});
						}
					}
				}
			}
			default -> {
			}
			}
		}
		return new Outcome(conflictWith, why, asks, toClose, row, rowEnded);
	}

	private static boolean disjoint(Fact other, Bounds b) {
		if (other.validEnd() != null && b.start() != null && b.start().compareTo(other.validEnd()) >= 0) {
			return true;
		}
		return b.end() != null && other.validStart() != null && b.end().compareTo(other.validStart()) <= 0;
	}

	/** The current open fact with the same key in another mode, if any. */
	private static Optional<Fact> sameKey(Tx tx, Operands op, String mode) {
		return tx.queryOne(
				"""
						SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND derivation_kind <> 'derived' AND ended = 0
						AND valid_end IS NULL AND COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?
						AND (? = 1 OR COALESCE(qualifier, '') = ?) AND COALESCE(scope_id, -1) = ? AND mode = ? ORDER BY id LIMIT 1""",
				op.subject().id(), op.predicate().name(), op.objectId(), op.objectTextKey(),
				FactService.freeQualifier(op.predicate()) ? 1 : 0, op.qualifier() == null ? "" : op.qualifier(),
				op.scopeId(), mode).map(Fact::from);
	}

	/** The current open restrictions and closures on the subject's predicate. */
	private static List<Fact> boundsIn(Tx tx, long subjectId, String predicate) {
		return tx
				.query("""
						SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND derivation_kind <> 'derived' AND ended = 0
						AND valid_end IS NULL AND mode IN ('only', 'closure') ORDER BY id""",
						subjectId, predicate)
				.stream().map(Fact::from).toList();
	}

	/** The other current asserted values of a functional predicate, per scope when the predicate is scoped. */
	private static List<Fact> otherCurrentValues(Tx tx, Operands op) {
		boolean scoped = "scope".equals(op.predicate().functionalScope());
		var args = new ArrayList<Object>(
				List.of(op.subject().id(), op.predicate().name(), op.objectId(), op.objectTextKey()));
		if (scoped) {
			args.add(op.scopeId());
		}
		return tx
				.query("""
						SELECT * FROM fact WHERE subject_id = ? AND predicate = ? AND status = 'current' AND valid_end IS NULL
						AND derivation_kind <> 'derived' AND mode = 'asserted' AND NOT (COALESCE(object_id, -1) = ? AND COALESCE(lower(object_text), '') = ?)"""
						+ (scoped ? " AND COALESCE(scope_id, -1) = ?" : "") + " ORDER BY id", args.toArray())
				.stream().map(Fact::from).toList();
	}
}
