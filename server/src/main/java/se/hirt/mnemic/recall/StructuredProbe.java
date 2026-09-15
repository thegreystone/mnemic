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
package se.hirt.mnemic.recall;

import se.hirt.mnemic.knowledge.Containment;
import se.hirt.mnemic.knowledge.Containment.Relation;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.RecallResult.Structured;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The structured channel: the spotted entities and the first predicate cue looked up directly over valid time. The
 * verdict is {@code matched}, {@code miss} (entity and predicate understood, no such fact: never evidence of no),
 * {@code future}, {@code known_false} (decided by a negation, a restriction, a closure, or the one current value of
 * a functional predicate), {@code entity} (no cue), or {@code unresolved}.
 */
final class StructuredProbe {

	private static final int CHAIN_HOPS = 2;

	private final EntityService entities;
	private final FactQueries facts;
	private final Containment containment;

	StructuredProbe(EntityService entities, FactQueries facts, Containment containment) {
		this.entities = entities;
		this.facts = facts;
		this.containment = containment;
	}

	Structured probe(Query q, Instant asOf, Instant now, boolean includeHistory) {
		if (q.spotted().isEmpty()) {
			return new Structured("unresolved", null, null, null, null, List.of(), List.of(), List.of());
		}
		if (q.cues().isEmpty()) {
			return entityOnly(q, asOf, now, includeHistory);
		}
		Cue cue = q.cues().getFirst();
		Entity entity = q.spotted().getFirst();
		var matched = new ArrayList<Fact>();
		var near = new ArrayList<Fact>();
		var future = new ArrayList<Fact>();
		for (Entity e : q.spotted()) {
			String direction = direction(cue, e);
			// A past-tense yes/no question is answered by ended facts too.
			for (Fact f : facts.probe(e.id(), cue.predicate().name(), asOf, now, includeHistory || q.past())) {
				if (q.past() && "corrected".equals(f.status())) {
					continue;
				}
				// A fact valid from a later date is recorded, not yet so: neither a match nor a miss. With as_of
				// the date decides.
				if (asOf == null && "future".equals(f.state(now))) {
					if (future.stream().noneMatch(m -> m.id() == f.id())) {
						future.add(f);
					}
					continue;
				}
				if ("subject".equals(direction) && f.subjectId() != e.id()) {
					continue; // "Mattias's children": Mattias is the parent, the subject
				}
				if ("object".equals(direction) && (f.objectId() == null || f.objectId() != e.id())) {
					continue; // "Mattias's father": Mattias is the child, the object
				}
				// A symmetric relation is stored once with the qualifier describing the subject; asked from the
				// other side ("Mattias's half-sister" against "Mattias is Clara's half-brother") the family matches
				// and the gender, which is not on record, is not invented.
				boolean qualifierOk = cue.qualifier() == null || cue.qualifier().equalsIgnoreCase(f.qualifier())
						|| (f.qualifier() == null && cue.predicate().qualifiers().isEmpty())
						|| (cue.predicate().symmetric() && Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier()));
				if (qualifierOk) {
					if (matched.stream().noneMatch(m -> m.id() == f.id())) {
						matched.add(f);
					}
				} else {
					near.add(f);
				}
			}
		}
		// The bounds on what the spotted subjects have under this predicate. A question that names a thing shows
		// only the bounds that could cover it; a question about the whole predicate shows them all.
		var bounds = new ArrayList<Fact>();
		for (Entity e : q.spotted()) {
			bounds.addAll(facts.bounds(e.id(), cue.predicate().name(), now));
		}
		List<Entity> xs = q.others(entities.owner().id());
		if (!xs.isEmpty() || !q.residual().isEmpty()) {
			bounds.removeIf(b -> !covers(b, xs, q.residual()));
		}
		Fact decidedBy = null;
		String basis = null;
		var notes = new ArrayList<String>();
		if (q.polar()) {
			// A yes/no question is about the thing it names: only facts touching it (directly, or through the
			// place it lies in) support a yes. "Does Mattias own a boat" is not answered by his apartments.
			if (!xs.isEmpty()) {
				matched.removeIf(f -> q.spotted().stream().anyMatch(x -> !touches(f, x)));
				future.removeIf(f -> q.spotted().stream().anyMatch(x -> !touches(f, x)));
			} else if (!q.residual().isEmpty()) {
				matched.removeIf(f -> Names.tokens(f.rendering()).stream().noneMatch(q.residual()::contains));
				future.removeIf(f -> Names.tokens(f.rendering()).stream().noneMatch(q.residual()::contains));
			}
			if (!q.past() && matched.isEmpty() && future.isEmpty() && (!xs.isEmpty() || !q.residual().isEmpty())) {
				for (Fact b : bounds) {
					basis = decides(b, xs, q.residual(), notes);
					if (basis != null) {
						decidedBy = b;
						break;
					}
				}
				// A functional predicate has one current value: the question named another, so the answer is no.
				if (decidedBy == null && cue.predicate().functional()) {
					for (Entity e : q.spotted()) {
						for (Fact f : facts.probe(e.id(), cue.predicate().name(), asOf, now, false)) {
							if (f.subjectId() == e.id() && "current".equals(f.state(now))
									&& xs.stream().noneMatch(x -> touches(f, x))) {
								decidedBy = f;
								basis = "functional";
								break;
							}
						}
						if (decidedBy != null) {
							break;
						}
					}
				}
			} else if (!q.past()) {
				// Matched, but a restriction may leave the named place undecided: say so.
				for (Fact b : bounds) {
					if ("only".equals(b.mode()) && b.objectId() != null) {
						for (Entity x : xs) {
							if (Names.isPlace(x.type()) && x.id() != b.objectId()
									&& containment.of(x.id(), b.objectId()).relation() == Relation.UNKNOWN) {
								notes.add("whether " + x.name() + " is within " + entities.nameOf(b.objectId()) + " is not known");
							}
						}
					}
				}
			}
		}
		if (decidedBy != null && "negation".equals(basis)) {
			// The earlier positive fact on the same key, if any, is history the verdict should show.
			for (Fact f : facts.probe(decidedBy.subjectId(), decidedBy.predicate(), null, now, true)) {
				if (!"current".equals(f.state(now)) && !"pending".equals(f.status()) && !"corrected".equals(f.status())
						&& f.subjectId() == decidedBy.subjectId() && Objects.equals(f.objectId(), decidedBy.objectId())) {
					notes.add("earlier: " + f.rendering() + " [" + f.ref() + ", " + f.state(now) + "]");
				}
			}
		}
		if (!q.named().isEmpty() && !matched.isEmpty() && decidedBy == null) {
			// Every named word must be mentioned by some matched fact: "Software Engineer Manager" is not covered by
			// "Senior Software Engineer" on the strength of one shared word.
			var mentioned = new HashSet<String>();
			for (Fact f : matched) {
				mentioned.addAll(Names.tokens(f.rendering()));
			}
			if (!mentioned.containsAll(q.named())) {
				near.addAll(0, matched);
				matched.clear();
				notes.add("the question names " + String.join(", ", q.named()) + ", which none of the facts mention");
			}
		}
		String state = decidedBy != null ? "known_false"
				: matched.isEmpty() ? (future.isEmpty() ? "miss" : "future") : "matched";
		List<Fact> chain = matched.isEmpty() ? List.of() : chain(matched, asOf, now);
		return new Structured(state, entity.ref(), entity.name(), cue.predicate().name(), cue.qualifier(),
				List.copyOf(matched), List.copyOf(near), chain, List.copyOf(bounds), decidedBy, basis,
				List.copyOf(notes), List.copyOf(future));
	}

	/** An entity without a predicate cue ("who is Bosse"): its facts are the channel. */
	private Structured entityOnly(Query q, Instant asOf, Instant now, boolean includeHistory) {
		// With another entity in the query, the owner's own facts carry no signal ("what did I say about Anna").
		List<Entity> subjects = q.spotted().size() > 1 ? q.others(entities.owner().id()) : q.spotted();
		var all = new ArrayList<Fact>();
		for (Entity e : subjects) {
			for (Fact f : facts.factsOf(e.id())) {
				boolean holds = asOf != null
						? (!"pending".equals(f.status()) && !"corrected".equals(f.status()) && f.mayHoldAt(asOf))
						: ("current".equals(f.state(now)) || (includeHistory && !"pending".equals(f.status())));
				if (holds) {
					all.add(f);
				}
			}
		}
		Entity first = q.spotted().getFirst();
		return new Structured("entity", first.ref(), first.name(), null, null, List.copyOf(all), List.of(), List.of());
	}

	/** The basis on which a bound decides the question false, or null when it does not. */
	private String decides(Fact b, List<Entity> xs, List<String> residual, List<String> notes) {
		switch (b.mode()) {
		case "negated" -> {
			return negationCovers(b, xs, residual) ? "negation" : null;
		}
		case "closure" -> {
			for (Entity x : xs) {
				if (Containment.kindMatches(b.objectText(), x.type())
						&& facts.assertedTouching(b.subjectId(), b.predicate(), x.id()).isEmpty()) {
					return "closure";
				}
			}
			return null;
		}
		case "only" -> {
			for (Entity x : xs) {
				if (b.objectId() == null || !Names.isPlace(x.type()) || x.id() == b.objectId()) {
					continue;
				}
				Relation r = containment.of(x.id(), b.objectId()).relation();
				if (r == Relation.DISJOINT) {
					return "restriction";
				}
				if (r == Relation.UNKNOWN) {
					notes.add("whether " + x.name() + " is within " + entities.nameOf(b.objectId()) + " is not known");
				}
			}
			return null;
		}
		default -> {
			return null;
		}
		}
	}

	/** Whether a bound could cover what the question names: its object, its class, or its kind of thing. */
	private static boolean covers(Fact b, List<Entity> xs, List<String> residual) {
		switch (b.mode()) {
		case "negated" -> {
			if (negationCovers(b, xs, residual)) {
				return true;
			}
			if (b.objectText() != null) {
				// A class negation ("anything in Sweden") covers a question that shares a word with it beyond the filler.
				Set<String> words = new HashSet<>(Names.contentTokens(b.objectText()));
				words.removeAll(Query.POLAR_FILLER);
				if (residual.stream().anyMatch(words::contains)) {
					return true;
				}
				for (Entity x : xs) {
					if (Names.identityTokens(x.name(), x.type()).stream().anyMatch(words::contains)) {
						return true;
					}
				}
			}
			return false;
		}
		case "closure" -> {
			return xs.stream().anyMatch(x -> Containment.kindMatches(b.objectText(), x.type()));
		}
		case "only" -> {
			return xs.stream().anyMatch(x -> Names.isPlace(x.type()));
		}
		default -> {
			return true;
		}
		}
	}

	/** The fact is about x: x is its subject or object, or its object lies within x (a property in a canton). */
	private boolean touches(Fact f, Entity x) {
		if (f.subjectId() == x.id() || (f.objectId() != null && f.objectId() == x.id())) {
			return true;
		}
		return f.objectId() != null && Names.isPlace(x.type()) && containment.ancestors(f.objectId()).contains(x.id());
	}

	/**
	 * A negation covers the question when its object is the named entity, or its literal class names the entity
	 * ("anything in Sweden" for Sweden), or, with no entity named, the class's own words are all in the question.
	 */
	private static boolean negationCovers(Fact b, List<Entity> xs, List<String> residual) {
		for (Entity x : xs) {
			if (b.objectId() != null && b.objectId() == x.id()) {
				return true;
			}
			if (b.objectText() != null) {
				List<String> ids = Names.identityTokens(x.name(), x.type());
				if (!ids.isEmpty() && new HashSet<>(Names.tokens(b.objectText())).containsAll(ids)) {
					return true;
				}
			}
		}
		if (xs.isEmpty() && b.objectText() != null) {
			List<String> words = Names.contentTokens(b.objectText()).stream().filter(t -> !Query.POLAR_FILLER.contains(t))
					.toList();
			return !words.isEmpty() && residual.containsAll(words);
		}
		return false;
	}

	/** The side of the relation the entity is on, from the cue, else from its type against domain and range. */
	static String direction(Cue cue, Entity e) {
		if (!"any".equals(cue.direction())) {
			return cue.direction();
		}
		Predicate p = cue.predicate();
		if (p.symmetric()) {
			return "any";
		}
		boolean inDomain = p.acceptsSubject(e.type()) && !"unknown".equals(e.type());
		boolean inRange = p.acceptsObject(e.type()) && !"unknown".equals(e.type());
		if (inDomain && !inRange) {
			return "subject";
		}
		if (inRange && !inDomain) {
			return "object";
		}
		return "any";
	}

	/**
	 * Containment reached from the matched facts' objects: "where does Mattias live" → Schübelbach → Kanton Schwyz
	 * → Switzerland, so "which canton" is answered from structure (EVALUATION.md F3, one to two hops).
	 */
	private List<Fact> chain(List<Fact> matched, Instant asOf, Instant now) {
		var out = new ArrayList<Fact>();
		var seen = new HashSet<Long>();
		var frontier = new ArrayList<Long>();
		for (Fact f : matched) {
			if (f.objectId() != null && entities.get(f.objectId()).map(x -> Names.isPlace(x.type())).orElse(false)) {
				frontier.add(f.objectId());
			}
		}
		for (int hop = 0; hop < CHAIN_HOPS && !frontier.isEmpty(); hop++) {
			var next = new ArrayList<Long>();
			for (Long id : frontier) {
				if (!seen.add(id)) {
					continue;
				}
				for (Fact f : facts.probe(id, "located_in", asOf, now, false)) {
					if (f.subjectId() == id && f.objectId() != null && out.stream().noneMatch(x -> x.id() == f.id())) {
						out.add(f);
						next.add(f.objectId());
					}
				}
			}
			frontier = next;
		}
		return out;
	}
}
