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

import se.hirt.mnemic.knowledge.PredicateRegistry.Group;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Predicate group hierarchy, CRUD, change log, and seed-group adoption. All mutating and cache-reading methods expect
 * the caller ({@link PredicateRegistry}) to hold {@link Database#withLock}.
 */
final class PredicateGroups {

	private final Database db;
	private Map<String, Group> cache = Map.of();

	PredicateGroups(Database db) {
		this.db = db;
	}

	/** Reloads the group cache from {@code predicate_group}. */
	void reload() {
		var out = new LinkedHashMap<String, Group>();
		for (Row r : db.read(tx -> tx.query("SELECT * FROM predicate_group ORDER BY seed DESC, name"))) {
			out.put(r.str("name"),
					new Group(r.str("name"), r.str("description"), PredicateRegistry.list(r.str("lexicon")),
							PredicateRegistry.list(r.str("groups")), r.lng("seed") == 1));
		}
		cache = out;
	}

	Map<String, Group> snapshot() {
		return cache;
	}

	List<Group> all() {
		return List.copyOf(cache.values());
	}

	Optional<Group> get(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(cache.get(groupName(name)));
	}

	/** The groups within a group, transitively, the group itself first; a cycle is never walked twice. */
	List<String> groupsUnder(String name) {
		var out = new ArrayList<String>();
		var frontier = new ArrayDeque<String>();
		frontier.add(groupName(name));
		while (!frontier.isEmpty()) {
			String g = frontier.poll();
			if (out.contains(g)) {
				continue;
			}
			out.add(g);
			for (Group child : cache.values()) {
				if (child.groups().contains(g)) {
					frontier.add(child.name());
				}
			}
		}
		return out;
	}

	/**
	 * The predicates in a group, including those in the groups within it: the stated relations first, the derived ones
	 * after them, each in registry order, so that a group's verdict leads with what somebody said.
	 */
	List<Predicate> membersOf(String name, Collection<Predicate> predicates, Function<String, List<Rule>> rulesOf) {
		List<String> under = groupsUnder(name);
		var out = new ArrayList<Predicate>();
		for (boolean derived : new boolean[] {false, true}) {
			for (Predicate p : predicates) {
				if (p.groups().stream().anyMatch(under::contains) && rulesOf.apply(p.name()).isEmpty() != derived) {
					out.add(p);
				}
			}
		}
		return out;
	}

	static String groupName(String name) {
		return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
	}

	static List<String> groupNames(List<String> names) {
		var out = new ArrayList<String>();
		for (String n : names) {
			if (n == null || n.isBlank()) {
				continue;
			}
			String g = groupName(n);
			if (!out.contains(g)) {
				out.add(g);
			}
		}
		return out;
	}

	/**
	 * The cue words a group's name supplies: a one-word name gives the word and its lemma ("pets", "pet"); a name of
	 * several words gives the phrase ("close family"), never its words apart, or "close family" would answer to
	 * "family" and every other group with a word in common.
	 */
	static List<String> groupWords(String name) {
		String phrase = name.replace('_', ' ').trim();
		return phrase.contains(" ") ? List.of(phrase) : Predicate.lexiconOf(phrase);
	}

	/** A group named for the first time registers itself, with the words of its name as its cue words. */
	void registerFromUse(List<String> names, Runnable invalidate, Runnable ensureLoaded) {
		ensureLoaded.run();
		for (String n : names) {
			if (!cache.containsKey(n)) {
				db.write(tx -> tx.insert(
						"INSERT INTO predicate_group(name, description, lexicon, groups, seed, created_at) VALUES (?,?,?,?,?,?)",
						n, null, PredicateRegistry.json(groupWords(n)), "[]", 0, Instant.now().toString()));
				invalidate.run();
				ensureLoaded.run();
			}
		}
	}

	/**
	 * Corrects a group: {@code description}, {@code lexicon} (its cue words, in any language: matching is blind to it),
	 * and {@code groups} (the groups it belongs to; a group that would contain itself is refused). Every change is
	 * logged.
	 */
	Group update(
		String name, Map<String, Object> replacement, String reason, Runnable invalidate, Runnable ensureLoaded) {
		ensureLoaded.run();
		Group g = get(name).orElseThrow(() -> MnemicException.notFound("No group " + name));
		String description = g.description();
		List<String> lexicon = baseLexicon(g.name());
		List<String> parents = g.groups();
		var changes = new ArrayList<String[]>();
		for (Map.Entry<String, Object> e : replacement.entrySet()) {
			String old;
			switch (e.getKey()) {
			case "description" -> {
				old = description;
				description = String.valueOf(e.getValue());
			}
			case "lexicon" -> {
				old = PredicateRegistry.json(lexicon);
				lexicon = lower(PredicateRegistry.strings(e.getValue()));
			}
			case "groups" -> {
				old = PredicateRegistry.json(parents);
				parents = groupNames(PredicateRegistry.strings(e.getValue()));
				for (String parent : parents) {
					if (parent.equals(g.name()) || groupsUnder(g.name()).contains(parent)) {
						throw MnemicException.invalidArgument(
								"group '" + g.name() + "' cannot belong to '" + parent + "': it would contain itself.");
					}
				}
				registerFromUse(parents, invalidate, ensureLoaded);
			}
			default -> throw MnemicException.invalidArgument(
					"Unknown group property '" + e.getKey() + "'; correctable: description, lexicon, groups.");
			}
			changes.add(new String[] {e.getKey(), old, e.getValue() instanceof List<?>
					? PredicateRegistry.json(PredicateRegistry.strings(e.getValue())) : String.valueOf(e.getValue())});
		}
		final String d = description;
		final List<String> l = lexicon;
		final List<String> p = parents;
		db.write(tx -> {
			tx.update("UPDATE predicate_group SET description = ?, lexicon = ?, groups = ? WHERE name = ?", d,
					PredicateRegistry.json(l), PredicateRegistry.json(p), g.name());
			for (String[] c : changes) {
				tx.insert(
						"INSERT INTO predicate_group_change(group_name, field, old_value, new_value, reason, changed_at) "
								+ "VALUES (?,?,?,?,?,?)",
						g.name(), c[0], c[1], c[2], reason, Instant.now().toString());
			}
			return null;
		});
		invalidate.run();
		ensureLoaded.run();
		return get(g.name()).orElseThrow();
	}

	List<Map<String, Object>> changes(String name) {
		return db.read(
				tx -> tx.query("SELECT * FROM predicate_group_change WHERE group_name = ? ORDER BY id", groupName(name))
						.stream().map(r -> {
							var m = new LinkedHashMap<String, Object>();
							m.put("field", r.str("field"));
							m.put("old", r.str("old_value"));
							m.put("new", r.str("new_value"));
							m.put("reason", r.str("reason"));
							m.put("changed_at", r.str("changed_at"));
							return (Map<String, Object>) m;
						}).toList());
	}

	/**
	 * The seed groups exist in every store, and a predicate named as a seed member carries its seed group when
	 * {@link #claimable} says so, so a store from before groups existed takes them at this start. A store that defined
	 * engaged_to before the seed knew it holds the same relation under the same name; its owner can still take it out
	 * of the group by correction, and a definition that named other groups is left as it is.
	 */
	void seedIfMissing(Supplier<Map<String, Predicate>> loadPredicates, Runnable invalidate) {
		loadPredicates.get();
		// The predicates whose groups the owner set by correction: one read, whatever the number of members.
		var regrouped = new HashSet<String>();
		for (Row r : db
				.read(tx -> tx.query("SELECT DISTINCT predicate FROM predicate_change WHERE field = 'groups'"))) {
			regrouped.add(r.str("predicate"));
		}
		for (PredicateSeeds.SeedGroup s : PredicateSeeds.groups()) {
			if (!cache.containsKey(s.name())) {
				db.write(tx -> {
					tx.insert(
							"INSERT INTO predicate_group(name, description, lexicon, groups, seed, created_at) "
									+ "VALUES (?,?,?,?,?,?)",
							s.name(), s.description(), PredicateRegistry.json(s.lexicon()), "[]", 1,
							Instant.now().toString());
					return null;
				});
				invalidate.run();
			}
			for (String member : s.members()) {
				Predicate p = loadPredicates.get().get(member);
				if (p == null || p.groups().contains(s.name()) || !claimable(p, member, regrouped)) {
					continue;
				}
				var groups = new ArrayList<>(p.groups());
				groups.add(s.name());
				db.write(tx -> tx.update("UPDATE predicate SET groups = ? WHERE name = ?",
						PredicateRegistry.json(groups), member));
				invalidate.run();
			}
		}
	}

	/**
	 * Whether a seed group may claim the predicate under a seed member's name. A seed predicate: always. One the store
	 * defined itself: only when nothing was said about its groups (none given in its definition, no correction on its
	 * log) and its domain and range overlap the seed's, so a same-named relation of another kind (a partner_of between
	 * people and companies) stays out.
	 */
	private static boolean claimable(Predicate p, String member, Set<String> regrouped) {
		if (regrouped.contains(member)) {
			return false;
		}
		return p.seed() || (p.groups().isEmpty() && PredicateSeeds.sameRelationAsSeed(p, member));
	}

	/** A group's cue words as stored, without the language's merged in. */
	private List<String> baseLexicon(String group) {
		return db.read(tx -> tx.query("SELECT lexicon FROM predicate_group WHERE name = ?", group)).stream()
				.map(r -> PredicateRegistry.list(r.str("lexicon"))).findFirst().orElse(List.of());
	}

	private static List<String> lower(List<String> words) {
		return words.stream().map(w -> w.trim().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList();
	}
}
