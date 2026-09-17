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
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.RecallResult.Structured;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The structured channel: the spotted entities and the first predicate cue looked up directly over valid time. The
 * verdict is {@code matched}, {@code miss} (entity and predicate understood, no such fact: never evidence of no),
 * {@code future}, {@code known_false} (decided by a negation, a restriction, a closure, or the one current value of a
 * functional predicate), {@code entity} (no cue), or {@code unresolved}.
 */
final class StructuredProbe {

	private static final int CHAIN_HOPS = 2;

	private final EntityService entities;
	private final FactQueries facts;
	private final Containment containment;
	private final EntityTypeRegistry types;
	private final PredicateRegistry predicates;

	StructuredProbe(EntityService entities, FactQueries facts, Containment containment, EntityTypeRegistry types,
			PredicateRegistry predicates) {
		this.predicates = predicates;
		this.entities = entities;
		this.facts = facts;
		this.containment = containment;
		this.types = types;
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
		var notes = new ArrayList<String>();
		for (Entity e : q.spotted()) {
			String direction = direction(cue, e, q.text());
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
						|| Predicate.qualifierWithin(cue.qualifier(), f.qualifier())
						|| (f.qualifier() == null && cue.predicate().qualifiers().isEmpty())
						|| (cue.predicate().symmetric()
								&& Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier()));
				if (qualifierOk) {
					if (matched.stream().noneMatch(m -> m.id() == f.id())) {
						matched.add(f);
						// "aunt" asked, "aunt or uncle" on record: it answers, and says what is not on record.
						if (cue.qualifier() != null && f.qualifier() != null
								&& !cue.qualifier().equalsIgnoreCase(f.qualifier())) {
							notes.add(f.ref() + " is recorded as '" + f.qualifier() + "', which covers '"
									+ cue.qualifier() + "' without settling it");
						}
					}
				} else {
					near.add(f);
				}
			}
		}
		// A fact held pending under this predicate (a conflict nobody has answered) contests the verdict: said.
		for (Entity e : q.spotted()) {
			for (Fact p : facts.pending(e.id(), cue.predicate().name())) {
				notes.add("pending on " + cue.predicate().name() + ", a conflict awaiting an answer: " + p.rendering()
						+ " [" + p.ref() + "]");
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
		// A question is about the things it names: only facts touching each of them (directly, or through the
		// place it lies in) answer it. "Does Mattias own a boat" is not answered by his apartments, and "how many
		// Raspberry Pi 5 do I own" not by the Raspberry Pi 4.
		if (!xs.isEmpty()) {
			// Every thing named, where the members of one family ("raspberry pi": the Pi 4 and the Pi 5) count as
			// one thing a fact about either touches.
			var families = new LinkedHashMap<String, List<Entity>>();
			for (Entity x : q.spotted()) {
				families.computeIfAbsent(EntityService.familyKey(x.name()), k -> new ArrayList<>()).add(x);
			}
			// A yes/no question is about its subject too ("does Mattias work at Initrode" is not answered by
			// Anna's job there); an open one names the owner as a possessive as often as not ("where is my
			// raspberry pi"), so there only the other things must be touched.
			long ownerId = entities.owner().id();
			List<List<Entity>> required = families.values().stream()
					.filter(g -> q.polar() || g.stream().noneMatch(x -> x.id() == ownerId)).toList();
			java.util.function.Predicate<Fact> about = f -> required.stream()
					.allMatch(g -> g.stream().anyMatch(x -> touches(f, x)));
			var dropped = matched.stream().filter(f -> !about.test(f)).toList();
			matched.removeAll(dropped);
			near.addAll(0, dropped);
			future.removeIf(f -> !about.test(f));
		}
		if (q.polar()) {
			if (xs.isEmpty() && !q.residual().isEmpty()) {
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
							if (predicates.canContain(types.lineage(x.type())) && x.id() != b.objectId()
									&& containment.of(x.id(), b.objectId()).relation() == Relation.UNKNOWN) {
								notes.add("whether " + x.name() + " is within " + entities.nameOf(b.objectId())
										+ " is not known");
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
						&& f.subjectId() == decidedBy.subjectId()
						&& Objects.equals(f.objectId(), decidedBy.objectId())) {
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
		if (!q.kinds().isEmpty() && !matched.isEmpty() && decidedBy == null && !cue.predicate().literalRange()) {
			// "What motorcycles does Mattias own": the kind asked for is checked against the type of the other side
			// of each fact. A kind on record narrows the answer, the rest becoming near-misses; one the store never
			// heard of cannot, and the verdict says so instead of passing the facts off as the answer (F19).
			var asked = new ArrayList<String>();
			var unknown = new ArrayList<String>();
			for (String k : q.kinds()) {
				types.kindNamedBy(k).ifPresentOrElse(t -> {
					if (!asked.contains(t)) {
						asked.add(t);
					}
				}, () -> unknown.add(k));
			}
			String under = cue.predicate().name() + " for " + entity.name();
			if (asked.isEmpty()) {
				// The store cannot evaluate the word, so it neither narrows nor denies: the facts are everything
				// recorded, and the note says none is classified as what was asked.
				notes.add("'" + String.join("', '", unknown) + "' names no kind of thing on record; the "
						+ matched.size() + (matched.size() == 1 ? " fact" : " facts") + " under " + under
						+ (matched.size() == 1 ? " is" : " are") + " everything recorded, none of "
						+ (matched.size() == 1 ? "it" : "them") + " classified so");
			} else {
				var kept = new ArrayList<Fact>();
				var other = new ArrayList<Fact>();
				var unsure = new ArrayList<String>();
				for (Fact f : matched) {
					String t = otherType(f, q.spotted());
					if (t != null && asked.stream().anyMatch(k -> types.isA(t, k))) {
						kept.add(f);
						continue;
					}
					other.add(f);
					// What the store cannot classify (no kind, or a kind nobody has placed) may be one: said.
					if (t == null || EntityTypeRegistry.UNKNOWN.equals(t)) {
						unsure.add(otherName(f, q.spotted()) + " (no kind on record)");
					} else if (types.unplaced(t)) {
						unsure.add(otherName(f, q.spotted()) + " (" + t + ", a kind nobody has placed)");
					}
				}
				if (kept.isEmpty()) {
					notes.add("nothing under " + under + " is recorded as a " + String.join(" or ", asked) + "; the "
							+ other.size() + (other.size() == 1 ? " fact" : " facts") + " of other kinds "
							+ (other.size() == 1 ? "is" : "are") + " listed as near-misses");
				} else if (!other.isEmpty()) {
					notes.add(other.size() + (other.size() == 1 ? " fact" : " facts") + " under " + under
							+ " of other kinds than " + String.join(" or ", asked) + " listed as near-misses");
				}
				if (!unsure.isEmpty()) {
					notes.add("may be one: " + String.join("; ", unsure));
				}
				if (!unknown.isEmpty()) {
					notes.add("'" + String.join("', '", unknown) + "' names no kind of thing on record");
				}
				near.addAll(0, other);
				matched.clear();
				matched.addAll(kept);
			}
		}
		// A present-tense miss beside facts that ended: named, so the reader does not take "no current value" for
		// "nothing known".
		var ended = new ArrayList<Fact>();
		if (matched.isEmpty() && decidedBy == null && !includeHistory && asOf == null) {
			for (Entity e : q.spotted()) {
				String direction = direction(cue, e, q.text());
				for (Fact f : facts.probe(e.id(), cue.predicate().name(), null, now, true)) {
					if (!"ended".equals(f.state(now)) || "pending".equals(f.status())
							|| ("subject".equals(direction) && f.subjectId() != e.id())
							|| ("object".equals(direction) && (f.objectId() == null || f.objectId() != e.id()))) {
						continue;
					}
					ended.add(f);
				}
			}
		}
		String state = decidedBy != null ? "known_false"
				: matched.isEmpty() ? (future.isEmpty() ? "miss" : "future") : "matched";
		List<Fact> chain = matched.isEmpty() ? List.of() : chain(matched, asOf, now);
		return new Structured(state, entity.ref(), entity.name(), cue.predicate().name(), cue.qualifier(),
				List.copyOf(matched), List.copyOf(near), chain, List.copyOf(bounds), decidedBy, basis,
				List.copyOf(notes), List.copyOf(future), List.copyOf(ended));
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
				if (types.isA(x.type(), b.objectText())
						&& facts.assertedTouching(b.subjectId(), b.predicate(), x.id()).isEmpty()) {
					return "closure";
				}
			}
			return null;
		}
		case "only" -> {
			for (Entity x : xs) {
				if (b.objectId() == null || !predicates.canContain(types.lineage(x.type())) || x.id() == b.objectId()) {
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
	private boolean covers(Fact b, List<Entity> xs, List<String> residual) {
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
					if (types.identityTokens(x.name(), x.type()).stream().anyMatch(words::contains)) {
						return true;
					}
				}
			}
			return false;
		}
		case "closure" -> {
			return xs.stream().anyMatch(x -> types.isA(x.type(), b.objectText()));
		}
		case "only" -> {
			return xs.stream().anyMatch(x -> predicates.canContain(types.lineage(x.type())));
		}
		default -> {
			return true;
		}
		}
	}

	/** The fact is about x: x is its subject or object, or its object lies within x (a property in a canton). */
	/** The type of the side of a fact the question did not name; null for a literal object. */
	private String otherType(Fact f, List<Entity> spotted) {
		Long otherId = otherId(f, spotted);
		return otherId == null ? null : entities.get(otherId).map(Entity::type).orElse(null);
	}

	/** The name of the side of a fact the question did not name; the literal itself for a literal object. */
	private String otherName(Fact f, List<Entity> spotted) {
		Long otherId = otherId(f, spotted);
		return otherId == null ? String.valueOf(f.objectText()) : entities.nameOf(otherId);
	}

	private static Long otherId(Fact f, List<Entity> spotted) {
		boolean subjectAsked = spotted.stream().anyMatch(x -> x.id() == f.subjectId());
		return subjectAsked ? f.objectId() : Long.valueOf(f.subjectId());
	}

	private boolean touches(Fact f, Entity x) {
		if (f.subjectId() == x.id() || (f.objectId() != null && f.objectId() == x.id())
				|| (f.scopeId() != null && f.scopeId() == x.id())) {
			return true;
		}
		return f.objectId() != null && predicates.canContain(types.lineage(x.type()))
				&& containment.ancestors(f.objectId()).contains(x.id());
	}

	/**
	 * A negation covers the question when its object is the named entity, or its literal class names the entity
	 * ("anything in Sweden" for Sweden), or, with no entity named, the class's own words are all in the question.
	 */
	private boolean negationCovers(Fact b, List<Entity> xs, List<String> residual) {
		for (Entity x : xs) {
			if (b.objectId() != null && b.objectId() == x.id()) {
				return true;
			}
			if (b.objectText() != null) {
				List<String> ids = types.identityTokens(x.name(), x.type());
				if (!ids.isEmpty() && new HashSet<>(Names.tokens(b.objectText())).containsAll(ids)) {
					return true;
				}
			}
		}
		if (xs.isEmpty() && b.objectText() != null) {
			List<String> words = Names.contentTokens(b.objectText()).stream()
					.filter(t -> !Query.POLAR_FILLER.contains(t)).toList();
			return !words.isEmpty() && residual.containsAll(words);
		}
		return false;
	}

	/**
	 * The side of the relation the entity is on: from the question's word order when it says ("who mentors Mattias":
	 * the verb before the name makes Mattias the object; "who does Mattias mentor": the name between the auxiliary and
	 * the verb makes Mattias the subject), else from the cue, else from the entity's type against domain and range.
	 */
	private String direction(Cue cue, Entity e, String question) {
		String bySyntax = directionBySyntax(cue, e, question);
		if (bySyntax != null) {
			return bySyntax;
		}
		if (!"any".equals(cue.direction())) {
			return cue.direction();
		}
		Predicate p = cue.predicate();
		if (p.symmetric()) {
			return "any";
		}
		List<String> lineage = types.lineage(e.type());
		boolean inDomain = p.acceptsSubject(lineage) && !"unknown".equals(e.type());
		boolean inRange = p.acceptsObject(lineage) && !"unknown".equals(e.type());
		if (inDomain && !inRange) {
			return "subject";
		}
		if (inRange && !inDomain) {
			return "object";
		}
		return "any";
	}

	private static final Set<String> AUXILIARIES = Set.of("does", "did", "do");

	/**
	 * {@code subject} for "does <entity> <term>" ("who does Mattias mentor"), else null. Word order before the name is
	 * not used: "who mentors Mattias" and "wo arbeitet Mattias" put the name on opposite sides.
	 */
	static String directionBySyntax(Cue cue, Entity e, String question) {
		List<String> toks = Names.tokens(question);
		List<String> term = Names.tokens(cue.term());
		Set<String> entity = new HashSet<>(Names.tokens(e.name()));
		if (term.isEmpty() || entity.isEmpty()) {
			return null;
		}
		for (int i = 0; i + 1 < toks.size(); i++) {
			if (!AUXILIARIES.contains(toks.get(i)) || !entity.contains(toks.get(i + 1))) {
				continue;
			}
			int j = i + 2;
			while (j < toks.size() && entity.contains(toks.get(j))) {
				j++;
			}
			if (j + term.size() <= toks.size() && toks.subList(j, j + term.size()).equals(term)) {
				return "subject";
			}
		}
		return null;
	}

	/**
	 * Containment reached from the matched facts' objects: "where does Mattias live" → Schübelbach → Kanton Schwyz →
	 * Switzerland, so "which canton" is answered from structure (EVALUATION.md F3, one to two hops).
	 */
	private List<Fact> chain(List<Fact> matched, Instant asOf, Instant now) {
		var out = new ArrayList<Fact>();
		var seen = new HashSet<Long>();
		var frontier = new ArrayList<Long>();
		for (Fact f : matched) {
			if (f.objectId() != null && entities.get(f.objectId())
					.map(x -> predicates.canContain(types.lineage(x.type()))).orElse(false)) {
				frontier.add(f.objectId());
			}
		}
		for (int hop = 0; hop < CHAIN_HOPS && !frontier.isEmpty(); hop++) {
			var next = new ArrayList<Long>();
			for (Long id : frontier) {
				if (!seen.add(id)) {
					continue;
				}
				for (String within : predicates.containmentPredicates()) {
					for (Fact f : facts.probe(id, within, asOf, now, false)) {
						if (f.subjectId() == id && f.objectId() != null
								&& out.stream().noneMatch(x -> x.id() == f.id())) {
							out.add(f);
							next.add(f.objectId());
						}
					}
				}
			}
			frontier = next;
		}
		return out;
	}
}
