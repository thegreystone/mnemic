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
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.LanguagePack;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.RecallResult.Structured;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

	/**
	 * Every relation the question names is probed where the question puts it ("Mattias's parents and Anna's siblings":
	 * parent_of for Mattias, sibling_of for Anna; "Mattias's family": every kinship predicate for Mattias). The first
	 * relation in the question that answered is the primary verdict; the rest come along as {@code also}. A relation
	 * the question tied to nobody and that found nothing is left out, since its word may not have meant the relation at
	 * all; a member of a group stays, so the group's verdict can say what was not found under it.
	 */
	Structured probe(Query q, Instant asOf, Instant now, boolean includeHistory) {
		if (q.spotted().isEmpty()) {
			return new Structured("unresolved", null, null, null, null, List.of(), List.of(), List.of());
		}
		if (q.bound().isEmpty()) {
			Structured byKind = kindOnly(q, asOf, now, includeHistory);
			return byKind != null ? byKind : entityOnly(q, asOf, now, includeHistory);
		}
		// In the question's order: the verdict is the first relation the question names that answered.
		List<Asked> asked = throughRoles(q,
				q.bound().stream().sorted((a, b) -> Integer.compare(a.start(), b.start())).toList(), asOf, now);
		List<Query.Bound> bound = asked.stream().map(Asked::bound).toList();
		var verdicts = new ArrayList<Structured>();
		// A literal may be checked against several entities and cues. Read its mentions once per recall, so
		// subsequent recalls still see new aliases and entity merges.
		var literalMentions = new HashMap<String, List<Entity>>();
		for (Asked a : asked) {
			verdicts.add(probeOne(q, a.bound(), asOf, now, includeHistory, literalMentions).withVia(a.via()));
		}
		Structured primary = verdicts.stream().filter(Structured::answered).findFirst().orElse(verdicts.getFirst());
		var also = new ArrayList<Structured>();
		for (int i = 0; i < verdicts.size(); i++) {
			Structured v = verdicts.get(i);
			if (v == primary || (!v.answered() && !bound.get(i).hasSubject() && v.group() == null)) {
				continue;
			}
			also.add(v);
		}
		return primary.withAlso(also);
	}

	/** A relation the question asks, with the facts its path took to the subject ("mom" to the owner's mother). */
	private record Asked(Query.Bound bound, List<Fact> via) {
	}

	/** Tokens a path from a role word to the relation after it may skip: "what car does my mother usually drive". */
	private static final int PATH_GAP = 2;

	/**
	 * "What car does mom drive", "where does my mother's sister live": a person's role with a subject ("my mother", or
	 * "mom" as the owner's), followed closely by a relation the question gives no subject of its own, is a path, not
	 * the question. The role is resolved to the people who hold it now, and they are the next relation's subjects; the
	 * facts the path took are shown with its verdict. Before, the relation after the role was asked of the owner, and
	 * the kind asked for ("car") was looked for under parent_of. A role nobody holds on record stays the question, its
	 * miss the answer, and the relation after it is dropped: it was asked of someone the store does not know.
	 */
	private List<Asked> throughRoles(Query q, List<Query.Bound> bound, Instant asOf, Instant now) {
		var asked = new ArrayList<Asked>();
		for (Query.Bound b : bound) {
			asked.add(new Asked(b, List.of()));
		}
		List<String> tokens = Names.tokens(q.text());
		for (int i = 0; i + 1 < asked.size(); i++) {
			Asked role = asked.get(i);
			Query.Bound next = asked.get(i + 1).bound();
			if (!isPath(role.bound(), next, tokens, q.entityTokens())) {
				continue;
			}
			var path = new ArrayList<Fact>();
			List<Entity> holders = holders(q, role.bound(), asOf, now, path);
			if (holders.isEmpty()) {
				asked.remove(i + 1);
			} else {
				var via = new ArrayList<>(role.via());
				via.addAll(path);
				asked.set(i + 1,
						new Asked(new Query.Bound(next.cue(), holders, next.others(), next.start(), next.end()),
								List.copyOf(via)));
				asked.remove(i);
			}
			i--; // the role, or the relation that took its place, against what follows it now
		}
		return asked;
	}

	/** Whether {@code next} is asked of whoever holds {@code role}: see {@link #throughRoles}. */
	private boolean isPath(Query.Bound role, Query.Bound next, List<String> tokens, Set<String> entityTokens) {
		Cue c = role.cue();
		if (!role.hasSubject() || next.hasSubject() || c.group() != null || !Query.betweenPeople(c.predicate())
				|| next.start() < role.end() || next.start() - role.end() > PATH_GAP) {
			return false;
		}
		Set<String> andWords = LanguagePack.allAndWords();
		for (int k = role.end(); k < next.start(); k++) {
			if (andWords.contains(tokens.get(k)) || entityTokens.contains(tokens.get(k))) {
				return false;
			}
		}
		List<String> person = types.lineage("person");
		return next.cue().predicate().acceptsSubject(person) || next.cue().predicate().acceptsObject(person);
	}

	/** The people who hold the role for its subjects now; the facts that say so are added to {@code path}. */
	private List<Entity> holders(Query q, Query.Bound role, Instant asOf, Instant now, List<Fact> path) {
		var matched = new ArrayList<Fact>();
		Query present = q.inPresent();
		for (Entity e : role.subjects()) {
			probeFacts(present, role.cue(), e, direction(role.cue(), e, q.text()), asOf, now, false, matched,
					new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		}
		var holders = new LinkedHashMap<Long, Entity>();
		Set<Long> subjects = role.subjects().stream().map(Entity::id).collect(Collectors.toSet());
		for (Fact f : matched) {
			Long other = subjects.contains(f.subjectId()) ? f.objectId() : Long.valueOf(f.subjectId());
			if (other == null || subjects.contains(other)) {
				continue;
			}
			entities.get(other).ifPresent(x -> {
				holders.putIfAbsent(x.id(), x);
				path.add(f);
			});
		}
		return List.copyOf(holders.values());
	}

	/** One relation, for the subject the question binds it to, or for every spotted entity when it binds none. */
	private Structured probeOne(
		Query q, Query.Bound bound, Instant asOf, Instant now, boolean includeHistory,
		Map<String, List<Entity>> literalMentions) {
		Cue cue = bound.cue();
		List<Entity> subjects = bound.hasSubject() ? bound.subjects() : q.spotted();
		var matched = new ArrayList<Fact>();
		var near = new ArrayList<Fact>();
		var future = new ArrayList<Fact>();
		var notes = new ArrayList<String>();
		for (Entity e : subjects) {
			probeFacts(q, cue, e, direction(cue, e, q.text()), asOf, now, includeHistory, matched, near, future, notes);
		}
		// "The plate for the Polestar": the name lands on the maker, an organization, and plates are a vehicle's.
		// When nothing was found and the named thing is of a kind the predicate does not apply to, a namesake of
		// the fitting kind ("Polestar 4", the car) is what the question meant, on the side it fits.
		var readAs = new HashSet<Long>(); // the named things a namesake stood in for: no longer what the question is about
		if (matched.isEmpty() && near.isEmpty() && future.isEmpty()) {
			List<Alternate> alternates = namesakesThatFit(cue, subjects);
			if (!alternates.isEmpty()) {
				for (Alternate a : alternates) {
					probeFacts(q, cue, a.entity(), a.side(), asOf, now, includeHistory, matched, near, future, notes);
				}
				if (!matched.isEmpty() || !near.isEmpty() || !future.isEmpty()) {
					Entity named = subjects.getFirst();
					Alternate first = alternates.getFirst();
					List<String> kinds = "object".equals(first.side()) ? cue.predicate().range()
							: cue.predicate().domain();
					notes.add("'" + named.name() + "' read as " + first.entity().name() + ": " + cue.predicate().name()
							+ " is of " + article(kinds.getFirst()) + " " + String.join(" or ", kinds) + ", and "
							+ named.name() + " is " + article(named.type()) + " " + named.type());
					subjects.forEach(e -> readAs.add(e.id()));
					subjects = alternates.stream().map(Alternate::entity).toList();
				}
			}
		}
		Entity entity = subjects.getFirst();
		// A fact held pending under this predicate (a conflict nobody has answered) contests the verdict: said.
		for (Entity e : subjects) {
			for (Fact p : facts.pending(e.id(), cue.predicate().name())) {
				notes.add("pending on " + cue.predicate().name() + ", a conflict awaiting an answer: " + p.rendering()
						+ " [" + p.ref() + "]");
			}
		}
		// The bounds on what the spotted subjects have under this predicate. A question that names a thing shows
		// only the bounds that could cover it; a question about the whole predicate shows them all.
		var bounds = new ArrayList<Fact>();
		for (Entity e : subjects) {
			bounds.addAll(facts.bounds(e.id(), cue.predicate().name(), now));
		}
		// The other things the question names beside this relation's subject; the owner is a possessive as often
		// as not ("where is my raspberry pi") and narrows nothing.
		long ownerId = entities.owner().id();
		List<Entity> xs = bound.others().stream().filter(e -> e.id() != ownerId && !readAs.contains(e.id())).toList();
		if (!xs.isEmpty() || !q.residual().isEmpty()) {
			bounds.removeIf(x -> !covers(x, xs, q.residual(), q.language()));
		}
		Fact decidedBy = null;
		String basis = null;
		// A question is about the things it names: only facts touching them (directly, or through the place it lies
		// in) answer it. "Does Mattias own a boat" is not answered by his apartments, and "how many Raspberry Pi 5 do
		// I own" not by the Raspberry Pi 4. A yes/no question holds for all of them at once; an open one lists them
		// ("Mattias's parents Konrad and Gunilla"), and a fact touching any of them answers it.
		if (!xs.isEmpty()) {
			// Every thing named, where the members of one family ("raspberry pi": the Pi 4 and the Pi 5) count as
			// one thing a fact about either touches.
			var families = new LinkedHashMap<String, List<Entity>>();
			for (Entity x : xs) {
				families.computeIfAbsent(EntityService.familyKey(x.name()), k -> new ArrayList<>()).add(x);
			}
			List<List<Entity>> required = List.copyOf(families.values());
			java.util.function.Predicate<Fact> about = f -> q.polar()
					? required.stream().allMatch(g -> g.stream().anyMatch(x -> touches(f, x, literalMentions)))
					: required.stream().anyMatch(g -> g.stream().anyMatch(x -> touches(f, x, literalMentions)));
			var dropped = matched.stream().filter(f -> !about.test(f)).toList();
			matched.removeAll(dropped);
			near.addAll(0, dropped);
			future.removeIf(f -> !about.test(f));
			if (matched.isEmpty() && !dropped.isEmpty()) {
				boolean literals = dropped.stream().allMatch(f -> f.objectId() == null);
				notes.add("the " + dropped.size() + " " + cue.predicate().name()
						+ (dropped.size() == 1 ? " fact" : " facts") + " for " + entity.name() + " touch"
						+ (dropped.size() == 1 ? "es" : "") + " none of "
						+ String.join(", ", xs.stream().map(Entity::name).toList())
						+ (literals ? " by link or by name in their wording" : "") + " (listed as near-misses)");
			}
		}
		if (q.polar()) {
			if (xs.isEmpty() && !q.residual().isEmpty()) {
				matched.removeIf(f -> Names.tokens(f.rendering()).stream().noneMatch(q.residual()::contains));
				future.removeIf(f -> Names.tokens(f.rendering()).stream().noneMatch(q.residual()::contains));
			}
			if (!q.past() && matched.isEmpty() && future.isEmpty() && (!xs.isEmpty() || !q.residual().isEmpty())) {
				for (Fact b : bounds) {
					basis = decides(b, xs, q.residual(), q.language(), notes);
					if (basis != null) {
						decidedBy = b;
						break;
					}
				}
				// A functional predicate has one current value: the question named another, so the answer is no.
				if (decidedBy == null && cue.predicate().functional()) {
					for (Entity e : subjects) {
						for (Fact f : facts.probe(e.id(), cue.predicate().name(), asOf, now, false)) {
							if (f.subjectId() == e.id() && "current".equals(f.state(now))
									&& xs.stream().noneMatch(x -> touches(f, x, literalMentions))) {
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
		String narrowedTo = null;
		if (!q.kinds().isEmpty() && !matched.isEmpty() && decidedBy == null && !cue.predicate().literalRange()) {
			// "What motorcycles does Mattias own": the kind asked for is checked against the type of the other side
			// of each fact. A kind on record narrows the answer, the rest becoming near-misses; one the store never
			// heard of cannot, and the verdict says so instead of passing the facts off as the answer (F19).
			var asked = new ArrayList<String>();
			var unknown = new ArrayList<String>();
			for (String k : q.kinds()) {
				types.kindNamedBy(k, q.language()).ifPresentOrElse(t -> {
					if (!asked.contains(t)) {
						asked.add(t);
					}
				}, () -> unknown.add(k));
			}
			String under = cue.predicate().name() + " for " + entity.name();
			// The side asked about: the relation's subjects, who may be no name in the question ("mom" read as the
			// owner's mother), and the names it holds.
			var askedSide = new ArrayList<>(subjects);
			askedSide.addAll(q.spotted());
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
					String t = otherType(f, askedSide);
					if (t != null && asked.stream().anyMatch(k -> types.isA(t, k))) {
						kept.add(f);
						continue;
					}
					other.add(f);
					// What the store cannot classify (no kind, or a kind nobody has placed) may be one: said. A web
					// address written as words is a site or a profile, never a car (2026-11-24).
					String name = otherName(f, askedSide);
					if (Names.isWebAddress(name)) {
						continue;
					}
					if (t == null || EntityTypeRegistry.UNKNOWN.equals(t)) {
						unsure.add(name + " (no kind on record)");
					} else if (types.unplaced(t)) {
						unsure.add(name + " (" + t + ", a kind nobody has placed)");
					}
				}
				if (!kept.isEmpty()) {
					// Narrowed to what was asked for: an answer, which ranks even for the owner (RecallService gates).
					narrowedTo = String.join(" or ", asked);
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
			for (Entity e : subjects) {
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
		if ("miss".equals(state) && near.isEmpty() && ended.isEmpty()) {
			// "who owns the parser module" when the record says responsible_for: the miss is real, and the reader
			// should know which relations the thing does have, so a near synonym is not read as "nothing known"
			// (2026-09-23). The owner's own record is long and mostly beside the point, so it is left out.
			for (Entity e : subjects) {
				if (e.id() == entities.owner().id()) {
					continue;
				}
				List<String> under = facts.factsOf(e.id()).stream().filter(Fact::current).map(Fact::predicate)
						.filter(p -> !p.equals(cue.predicate().name())).distinct().sorted().limit(6).toList();
				if (!under.isEmpty()) {
					notes.add(e.name() + " is on record under " + String.join(", ", under) + ", not "
							+ cue.predicate().name());
				}
			}
		}
		List<Fact> chain = matched.isEmpty() ? List.of() : chain(matched, asOf, now);
		return new Structured(state, entity.ref(), entity.name(), cue.predicate().name(), cue.qualifier(),
				List.copyOf(matched), List.copyOf(near), chain, List.copyOf(bounds), decidedBy, basis,
				List.copyOf(notes), List.copyOf(future), List.copyOf(ended)).withGroup(cue.group())
				.withKind(narrowedTo);
	}

	/** The facts of one entity under the cue, sorted into matched, near (the qualifier differs), and future. */
	private void probeFacts(
		Query q, Cue cue, Entity e, String direction, Instant asOf, Instant now, boolean includeHistory,
		List<Fact> matched, List<Fact> near, List<Fact> future, List<String> notes) {
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
			boolean qualifierOk;
			String note = null;
			boolean symmetricRole = cue.predicate().symmetric() && cue.qualifier() != null && f.objectId() != null
					&& f.subjectId() != f.objectId() && (f.subjectId() == e.id() || f.objectId() == e.id());
			if (symmetricRole) {
				// A symmetric relation is stored once, its qualifier describing the subject. The role asked for
				// ("Marcus's sister") belongs to the other person: when the possessor is the subject, the stored
				// word says nothing about them, and their gender on record decides; when the possessor is the
				// object, the stored word is theirs and is read with its gender (2026-09-25).
				long otherId = f.subjectId() == e.id() ? f.objectId() : f.subjectId();
				String asked = impliedGender(cue.predicate(), cue.qualifier());
				if (f.objectId() == e.id()) {
					String stored = impliedGender(cue.predicate(), f.qualifier());
					// "sister" asked, "half-sister" stored: a sister, and the note says which kind.
					qualifierOk = cue.qualifier().equalsIgnoreCase(f.qualifier())
							|| Predicate.qualifierWithin(cue.qualifier(), f.qualifier())
							|| cue.qualifier().equalsIgnoreCase(Predicate.headOf(f.qualifier()))
							|| (Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier())
									&& (asked == null || stored == null || asked.equals(stored)));
					if (qualifierOk && !cue.qualifier().equalsIgnoreCase(f.qualifier())) {
						note = f.ref() + " is recorded as '" + f.qualifier() + "'"
								+ (asked != null && stored == null
										? ", which does not say whether " + entities.nameOf(otherId) + " is "
												+ article(cue.qualifier()) + " " + cue.qualifier()
										: "");
					}
				} else if (asked == null) {
					qualifierOk = Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier())
							|| Predicate.qualifierWithin(cue.qualifier(), f.qualifier());
				} else {
					String gender = genderOf(otherId, asOf, now);
					String other = entities.nameOf(otherId);
					// The degree must fit: a plain word ("sister") takes any degree, "half-sister" only its own.
					boolean degreeFits = Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier())
							|| cue.qualifier().equalsIgnoreCase(Predicate.headOf(cue.qualifier()));
					if (gender != null) {
						qualifierOk = degreeFits && asked.equals(gender);
						if (qualifierOk) {
							note = f.ref() + " records " + e.name() + "'s role; " + other + " is " + gender
									+ " on record";
						}
					} else {
						// The gender is not on record and is not invented: the same degree from the other side
						// answers, and the note says what it does not settle.
						qualifierOk = Predicate.sameQualifierFamily(cue.qualifier(), f.qualifier());
						note = f.ref() + " records " + e.name() + "'s role, not " + other + "'s, and " + other
								+ "'s gender is not on record";
					}
				}
			} else {
				boolean sameRole = sameRole(cue.predicate(), cue.qualifier(), f.qualifier());
				qualifierOk = cue.qualifier() == null || cue.qualifier().equalsIgnoreCase(f.qualifier())
						|| Predicate.qualifierWithin(cue.qualifier(), f.qualifier()) || sameRole
						|| (f.qualifier() == null && cue.predicate().qualifiers().isEmpty());
				// "aunt" asked, "aunt or uncle" on record: it answers, and says what is not on record.
				if (qualifierOk && !sameRole && cue.qualifier() != null && f.qualifier() != null
						&& !cue.qualifier().equalsIgnoreCase(f.qualifier())) {
					note = f.ref() + " is recorded as '" + f.qualifier() + "', which covers '" + cue.qualifier()
							+ "' without settling it";
				}
			}
			if (qualifierOk) {
				if (matched.stream().noneMatch(m -> m.id() == f.id())) {
					matched.add(f);
					if (note != null) {
						notes.add(note);
					}
				}
			} else {
				near.add(f);
				if (note != null && notes.stream().noneMatch(note::equals)) {
					notes.add(note);
				}
			}
		}
	}

	/**
	 * Two words for one role: of one family of qualifiers, saying the same gender ("mom" asked, "mother" on record).
	 * Before, "who is my mom" was a miss beside the fact that said "mother" (2026-11-25).
	 */
	private boolean sameRole(Predicate p, String asked, String stored) {
		if (asked == null || stored == null || !Predicate.sameQualifierFamily(asked, stored)) {
			return false;
		}
		String a = impliedGender(p, asked);
		return a != null && a.equals(impliedGender(p, stored));
	}

	/**
	 * The gender a role word says under a predicate: first what the predicate's own {@code implies} declares for the
	 * word or its head ("paternal grandmother" by "grandmother"), which a definition sets in any language; then the
	 * seed words. Null when the word says none ("sibling", "partner").
	 */
	private String impliedGender(Predicate p, String word) {
		if (word == null || word.isBlank()) {
			return null;
		}
		Map<String, Map<String, String>> implies = predicates.impliesOf(p.name());
		for (String key : List.of(word.trim().toLowerCase(Locale.ROOT), Predicate.headOf(word))) {
			Map<String, String> assigns = implies.get(key);
			if (assigns != null && assigns.get("gender") != null) {
				return genderValue(assigns.get("gender"));
			}
		}
		return Predicate.impliedGender(word);
	}

	/** A gender value as recorded ("female", "woman", "f") read through the gender predicate's own implications. */
	private String genderValue(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		Map<String, String> assigns = predicates.impliesOf("gender").get(value.trim().toLowerCase(Locale.ROOT));
		if (assigns != null && assigns.get("gender") != null) {
			return assigns.get("gender").trim().toLowerCase(Locale.ROOT);
		}
		return Predicate.impliedGender(value);
	}

	/** The gender on record for an entity ("female", "male"), read from its current gender fact; null when none. */
	private String genderOf(long entityId, Instant asOf, Instant now) {
		for (Fact g : facts.probe(entityId, "gender", asOf, now, false)) {
			if (g.subjectId() == entityId && g.objectText() != null && !"future".equals(g.state(now))) {
				String gender = genderValue(g.objectText());
				if (gender != null) {
					return gender;
				}
			}
		}
		return null;
	}

	/** A namesake of a named thing, and the side of the relation it fits. */
	private record Alternate(Entity entity, String side) {
	}

	/**
	 * Namesakes of the named things that the predicate applies to, when the named things themselves are of a kind it
	 * does not: the members of the same name family ("Polestar 4" for "Polestar", "Hans Nordvik" for "Nordvik") whose
	 * type is in the domain, as subjects, or in the range, as objects. A named thing that fits either side has no
	 * namesake looked for: it was asked about and has nothing, which is a miss. A relation that takes anything on a
	 * side never sends a name there.
	 */
	private List<Alternate> namesakesThatFit(Cue cue, List<Entity> subjects) {
		var out = new ArrayList<Alternate>();
		List<String> domain = cue.predicate().domain();
		List<String> range = cue.predicate().range();
		boolean openDomain = domain.contains("*") || domain.contains("literal");
		boolean openRange = range.contains("*") || range.contains("literal");
		for (Entity e : subjects) {
			if (types.unplaced(e.type())) {
				continue;
			}
			boolean fitsDomain = !openDomain && domain.stream().anyMatch(k -> types.isA(e.type(), k));
			boolean fitsRange = !openRange && range.stream().anyMatch(k -> types.isA(e.type(), k));
			if (fitsDomain || fitsRange || (openDomain && openRange)) {
				continue;
			}
			for (Entity mate : entities.namesakes(e.name())) {
				if (out.stream().anyMatch(a -> a.entity().id() == mate.id())) {
					continue;
				}
				if (!openDomain && domain.stream().anyMatch(k -> types.isA(mate.type(), k))) {
					out.add(new Alternate(mate, "subject"));
				} else if (!openRange && range.stream().anyMatch(k -> types.isA(mate.type(), k))) {
					out.add(new Alternate(mate, "object"));
				}
			}
		}
		return out;
	}

	private static String article(String word) {
		return word != null && !word.isEmpty() && "aeiou".indexOf(Character.toLowerCase(word.charAt(0))) >= 0 ? "an"
				: "a";
	}

	/** An entity without a predicate cue ("who is Bosse"): its facts are the channel. */
	private Structured entityOnly(Query q, Instant asOf, Instant now, boolean includeHistory) {
		var all = new ArrayList<Fact>();
		for (Entity e : unnamedRelationSubjects(q)) {
			for (Fact f : facts.factsOf(e.id())) {
				if (holds(f, asOf, now, includeHistory)) {
					all.add(f);
				}
			}
		}
		Entity first = q.spotted().getFirst();
		return new Structured("entity", first.ref(), first.name(), null, null, List.copyOf(all), List.of(), List.of());
	}

	/**
	 * The subjects of a question that names no relation: with another entity in it, the owner's own facts carry no
	 * signal ("what did I say about Anna").
	 */
	private List<Entity> unnamedRelationSubjects(Query q) {
		return q.spotted().size() > 1 ? q.others(entities.owner().id()) : q.spotted();
	}

	private static boolean holds(Fact f, Instant asOf, Instant now, boolean includeHistory) {
		return asOf != null ? (!"pending".equals(f.status()) && !"corrected".equals(f.status()) && f.mayHoldAt(asOf))
				: ("current".equals(f.state(now)) || (includeHistory && !"pending".equals(f.status())));
	}

	/**
	 * "What are my current cars" names no relation, only a kind of thing on record: the answer is the subject's
	 * asserted facts whose other side is of that kind, under whatever relation they stand (the cars owned, the car
	 * driven). Before, no cue meant no verdict, and the cars came back 4th, 9th, and 10th behind every observation that
	 * said "current" (2026-11-24). A kind the store never heard of, or nothing of the kind on record, leaves the
	 * question to the entity's facts as before: null.
	 */
	private Structured kindOnly(Query q, Instant asOf, Instant now, boolean includeHistory) {
		if (q.kinds().isEmpty()) {
			return null;
		}
		var asked = new ArrayList<String>();
		for (String k : q.kinds()) {
			types.kindNamedBy(k, q.language()).filter(t -> !asked.contains(t)).ifPresent(asked::add);
		}
		if (asked.isEmpty()) {
			return null;
		}
		List<Entity> subjects = unnamedRelationSubjects(q);
		var matched = new ArrayList<Fact>();
		for (Entity e : subjects) {
			for (Fact f : facts.factsOf(e.id())) {
				if (!f.asserted() || !holds(f, asOf, now, includeHistory)
						|| matched.stream().anyMatch(m -> m.id() == f.id())) {
					continue;
				}
				Long otherId = f.subjectId() == e.id() ? f.objectId() : Long.valueOf(f.subjectId());
				if (otherId == null || otherId == e.id()) {
					continue;
				}
				String t = entities.get(otherId).map(Entity::type).orElse(null);
				if (t != null && asked.stream().anyMatch(k -> types.isA(t, k))) {
					matched.add(f);
				}
			}
		}
		if (matched.isEmpty()) {
			return null;
		}
		Entity first = subjects.getFirst();
		String relations = String.join(", ", matched.stream().map(Fact::predicate).distinct().toList());
		return new Structured("matched", first.ref(), first.name(), relations, null, List.copyOf(matched), List.of(),
				List.of()).withKind(String.join(" or ", asked));
	}

	/** The basis on which a bound decides the question false, or null when it does not. */
	private String decides(Fact b, List<Entity> xs, List<String> residual, Lang language, List<String> notes) {
		switch (b.mode()) {
		case "negated" -> {
			return negationCovers(b, xs, residual, language) ? "negation" : null;
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
	private boolean covers(Fact b, List<Entity> xs, List<String> residual, Lang language) {
		switch (b.mode()) {
		case "negated" -> {
			if (negationCovers(b, xs, residual, language)) {
				return true;
			}
			if (b.objectText() != null) {
				// A class negation ("anything in Sweden") covers a question that shares a word with it beyond the filler.
				Set<String> words = new HashSet<>(Names.contentTokens(b.objectText()));
				words.removeAll(Query.polarFillerFor(b.objectText(), language));
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

	/**
	 * Whether a fact is about an entity: as its subject, object, or scope, through the place its object lies in, or,
	 * when its object is a literal, by naming the entity in it. A decision or a plan is words ("insure the Polestar
	 * with Zurich rather than Helvetia"), and a question about Zurich is answered by the decisions that name it; by
	 * link alone every literal fact touched nothing and was a near-miss (2026-10-23). Use the same name recognition as
	 * the question, so "Zurich Insurance" does not also name the separate city "Zürich".
	 */
	private boolean touches(Fact f, Entity x, Map<String, List<Entity>> literalMentions) {
		if (f.subjectId() == x.id() || (f.objectId() != null && f.objectId() == x.id())
				|| (f.scopeId() != null && f.scopeId() == x.id())) {
			return true;
		}
		if (f.objectId() == null) {
			return f.objectText() != null && literalMentions.computeIfAbsent(f.objectText(), entities::spot).stream()
					.anyMatch(e -> e.id() == x.id());
		}
		return predicates.canContain(types.lineage(x.type())) && containment.ancestors(f.objectId()).contains(x.id());
	}

	/**
	 * A negation covers the question when its object is the named entity, or its literal class names the entity
	 * ("anything in Sweden" for Sweden), or, with no entity named, the class's own words are all in the question.
	 */
	private boolean negationCovers(Fact b, List<Entity> xs, List<String> residual, Lang language) {
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
			Set<String> filler = Query.polarFillerFor(b.objectText(), language);
			List<String> words = Names.contentTokens(b.objectText()).stream().filter(t -> !filler.contains(t)).toList();
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
