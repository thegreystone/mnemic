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
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Entities and their aliases: the owner, the resolution ladder, query-time spotting, and merges.
 * <p>
 * The ladder (EXTRACTION.md, Entity resolution): first-person references → the owner; exact normalised match of the
 * name or any proposed alias, with type compatibility; then a fuzzy step gated by name entropy (nothing shorter than
 * three letters, nothing made only of stopwords) scoring shared name tokens and character trigrams. At or above
 * {@value #MERGE} the entity is reused and the name added as an alias; between {@value #AMBIGUOUS} and {@value #MERGE}
 * the candidates are returned as a question (EVALUATION.md B2); below, a new entity is created. When the name matches
 * one entity and a proposed alias another, the two are merged (G2), with the move recorded so it can be reviewed and
 * undone.
 */
public final class EntityService {

	public record Candidate(Entity entity, double score) {
	}

	/** How a proposed entity resolved. {@code entity} is null and {@code candidates} filled when ambiguous. */
	public record Resolved(Entity entity, String how, double score, List<Candidate> candidates) {
		public boolean ambiguous() {
			return "ambiguous".equals(how);
		}
	}

	public static final Set<String> SELF = Set.of("self", "i", "me", "my", "myself", "mine", "the user", "user");
	static final double MERGE = 0.85;
	static final double AMBIGUOUS = 0.40;
	private static final int MAX_NGRAM = 4;

	private final Database db;
	private final EntityTypeRegistry types;
	private final Entity owner;

	/** {@code ownerIdentity}: configured aliases, e-mail addresses, and handles, seeded as aliases of the owner. */
	private final Set<String> ownerIdentityNorms;

	public EntityService(Database db, EntityTypeRegistry types, String ownerName, List<String> ownerIdentity) {
		this.db = db;
		this.types = types;
		this.owner = ensureOwner(ownerName, ownerIdentity);
		this.ownerIdentityNorms = ownerIdentity.stream().map(Names::norm).collect(Collectors.toSet());
	}

	public Entity owner() {
		return owner;
	}

	// ── resolution ───────────────────────────────────────────────────────

	public Resolved resolve(String name, String type, List<String> aliases, Long observationId) {
		return resolve(name, type, aliases, observationId, Set.of());
	}

	/**
	 * Ids of existing entities whose name or an alias equals one of the given names exactly. A proposal that declares
	 * two entities asserts they are distinct; the caller passes the other one's ids as {@code distinctFrom} so an alias
	 * like "the Willisau apartment" cannot make "Willisau" ambiguous.
	 */
	public Set<Long> exactIds(String name, List<String> aliases, String type) {
		var out = new HashSet<Long>();
		if (name == null || name.isBlank() || SELF.contains(Names.norm(name))) {
			return out;
		}
		String t = types.canonical(type);
		db.read(tx -> {
			byAlias(tx, Names.norm(name), t).ifPresent(e -> out.add(e.id()));
			for (String a : aliases) {
				if (a != null && !a.isBlank()) {
					byAlias(tx, Names.norm(a), t).ifPresent(e -> out.add(e.id()));
				}
			}
			return null;
		});
		return out;
	}

	/**
	 * As {@link #resolve(String, String, List, Long)}, never proposing an entity in {@code distinctFrom} as ambiguous.
	 */
	public Resolved resolve(
		String name, String type, List<String> aliases, Long observationId, Set<Long> distinctFrom) {
		if (name == null || name.isBlank()) {
			throw MnemicException.invalidArgument(
					"An entity needs a 'name'. Example: {\"name\": \"Anna Lindqvist\", \"type\": \"person\"}");
		}
		String norm = Names.norm(name);
		if (SELF.contains(norm)) {
			return new Resolved(owner, "owner", 1.0, List.of());
		}
		String t = types.canonical(type);
		return db.write(tx -> {
			Optional<Entity> byName = byAlias(tx, norm, t);
			Entity match = byName.orElse(null);
			String how = "alias";
			for (String a : aliases) {
				if (a == null || a.isBlank()) {
					continue;
				}
				Optional<Entity> byA = byAlias(tx, Names.norm(a), t);
				if (byA.isEmpty()) {
					continue;
				}
				if (match == null) {
					match = byA.get();
				} else if (byA.get().id() != match.id()) {
					// The name names one entity and an alias another: later evidence says they are the same (G2).
					merge(tx, byA.get(), match, observationId, "alias '" + a + "' of '" + name + "' matched both");
					how = "merged";
				}
			}
			if (match == null) {
				List<Candidate> fuzzy = fuzzy(tx, name, norm, t, distinctFrom);
				if (!fuzzy.isEmpty() && fuzzy.getFirst().score() >= MERGE) {
					match = fuzzy.getFirst().entity();
					how = "fuzzy";
				} else if (!fuzzy.isEmpty() && fuzzy.getFirst().score() >= AMBIGUOUS) {
					return new Resolved(null, "ambiguous", fuzzy.getFirst().score(), fuzzy);
				}
			}
			if (match != null) {
				// A typeless match takes the type it is now given, and a place named as a country becomes one: the
				// more specific kind wins (family Q).
				if (("unknown".equals(match.type()) && !"unknown".equals(t))
						|| (!t.equals(match.type()) && types.isA(t, match.type()))) {
					tx.update("UPDATE entity SET type = ? WHERE id = ?", t, match.id());
					match = new Entity(match.id(), match.name(), t, match.createdFrom(), match.mergedInto());
				}
				addAliases(tx, match.id(), aliases, observationId);
				if (!Names.norm(match.name()).equals(norm)) {
					addAliases(tx, match.id(), List.of(name), observationId);
				}
				return new Resolved(match, how, 1.0, List.of());
			}
			return new Resolved(create(tx, name, t, aliases, observationId), "created", 1.0, List.of());
		});
	}

	/**
	 * Corrects an entity's {@code name}, {@code type}, or {@code aliases} (the list to keep). The name itself and the
	 * owner's configured identity are never dropped; an alias that names another entity is not taken.
	 */
	public Entity correct(long id, Map<String, Object> replacement) {
		Entity e = get(id).orElseThrow(() -> MnemicException.notFound("No entity ent-" + id));
		for (Map.Entry<String, Object> c : replacement.entrySet()) {
			switch (c.getKey()) {
			case "name" -> {
				String name = String.valueOf(c.getValue()).trim();
				if (name.isEmpty()) {
					throw MnemicException.invalidArgument("'name' must not be empty.");
				}
				db.write(tx -> {
					tx.update("UPDATE entity SET name = ? WHERE id = ?", name, id);
					addAliases(tx, id, List.of(name), null);
					return null;
				});
			}
			case "type" -> retype(id, String.valueOf(c.getValue()));
			case "aliases" -> {
				if (!(c.getValue() instanceof List<?> keep)) {
					throw MnemicException.invalidArgument("'aliases' takes the list of aliases to keep.");
				}
				Set<String> kept = keep.stream().map(x -> Names.norm(String.valueOf(x))).collect(Collectors.toSet());
				String own = Names.norm(get(id).orElseThrow().name());
				db.write(tx -> {
					for (Row r : tx.query(
							"SELECT id, alias_norm, source_observation FROM entity_alias WHERE entity_id = ?", id)) {
						String norm = r.str("alias_norm");
						// The owner's seeded aliases (first person, first name, configured identity) are never dropped.
						boolean protectedName = norm.equals(own)
								|| (id == owner.id() && (r.lngOrNull("source_observation") == null
										|| SELF.contains(norm) || ownerIdentityNorms.contains(norm)));
						if (!kept.contains(norm) && !protectedName) {
							tx.update("DELETE FROM entity_alias WHERE id = ?", r.lng("id"));
						}
					}
					var add = keep.stream().map(String::valueOf).toList();
					addAliases(tx, id, add, null);
					return null;
				});
			}
			default -> throw MnemicException
					.invalidArgument("Unknown entity property '" + c.getKey() + "'; correctable: name, type, aliases.");
			}
		}
		return get(id).orElseThrow();
	}

	/** Changes an entity's type: the answer to a type mismatch that blamed the entity. */
	public void retype(long id, String type) {
		String t = types.canonical(type);
		db.write(tx -> tx.update("UPDATE entity SET type = ? WHERE id = ?", t, id));
	}

	/** Creates an entity outright (used when a question is answered with "new"). */
	public Entity create(String name, String type, List<String> aliases, Long observationId) {
		return db.write(tx -> create(tx, name, types.canonical(type), aliases, observationId));
	}

	private static Entity create(Tx tx, String name, String type, List<String> aliases, Long observationId) {
		long id = tx.insert("INSERT INTO entity(name, type, created_from, created_at) VALUES (?,?,?,?)", name.trim(),
				type, observationId, Instant.now().toString());
		var all = new ArrayList<String>(aliases);
		all.add(name.trim());
		addAliases(tx, id, all, observationId);
		return new Entity(id, name.trim(), type, observationId, null);
	}

	private Optional<Entity> byAlias(Tx tx, String norm, String type) {
		List<Row> rows = tx.query("""
				SELECT e.* FROM entity_alias a JOIN entity e ON e.id = a.entity_id
				WHERE a.alias_norm = ? AND e.merged_into IS NULL ORDER BY e.id""", norm);
		Entity typeless = null;
		for (Row r : rows) {
			Entity e = Entity.from(r);
			if ("unknown".equals(type) || types.sameKind(e.type(), type)) {
				return Optional.of(e);
			}
			if ("unknown".equals(e.type())) {
				typeless = e;
			}
		}
		return Optional.ofNullable(typeless);
	}

	/**
	 * Fuzzy candidates of a compatible type, best first. Score is the larger of the shared-token ratio (a first name
	 * against a full name scores 0.5) and the character-trigram Jaccard. The entropy gate refuses names shorter than
	 * three letters or made only of stopwords, so "AL" or "it" never fuzzy-match anything.
	 */
	private List<Candidate> fuzzy(Tx tx, String name, String norm, String type, Set<Long> distinctFrom) {
		List<String> tokens = types.identityTokens(name, type).stream().filter(EntityService::identity).toList();
		if (norm.length() < 3 || tokens.isEmpty()) {
			return List.of();
		}
		List<String> words = Names.contentTokens(name);
		Set<String> grams = trigrams(norm);
		var out = new ArrayList<Candidate>();
		for (Row r : tx.query("SELECT * FROM entity WHERE merged_into IS NULL AND id <> ?", owner.id())) {
			Entity e = Entity.from(r);
			if (distinctFrom.contains(e.id())) {
				continue; // declared distinct by the same proposal
			}
			if (!"unknown".equals(type) && !"unknown".equals(e.type()) && !types.sameKind(e.type(), type)) {
				continue;
			}
			double best = 0;
			for (String alias : aliasesOf(tx, e.id())) {
				String an = Names.norm(alias);
				List<String> at = types.identityTokens(alias, e.type()).stream().filter(EntityService::identity)
						.toList();
				long shared = tokens.stream().filter(at::contains).count();
				double tokenScore = shared == 0 ? 0 : (double) shared / Math.max(tokens.size(), at.size());
				// "Oskar Nyberg" against "Konrad Nyberg": two full names whose leading tokens differ share a
				// family name, not an identity. "Anna" against "Anna Lindqvist" stays ambiguous.
				if (tokens.size() >= 2 && at.size() >= 2 && !tokens.getFirst().equals(at.getFirst())
						&& !tokens.getFirst().startsWith(at.getFirst())
						&& !at.getFirst().startsWith(tokens.getFirst())) {
					tokenScore = Math.min(tokenScore, AMBIGUOUS - 0.1);
				}
				double gramScore = an.length() >= 4 ? jaccard(grams, trigrams(an)) : 0;
				double score = Math.max(tokenScore, gramScore);
				// One name says more than the other: a version ("Raspberry Pi 5" against "Raspberry Pi"), or a place
				// word that names another level ("Luzern" against "Kanton Luzern"). Asked, never merged.
				boolean oneSaysMore = (!tokens.equals(at) && (tokens.containsAll(at) || at.containsAll(tokens)))
						|| !numbers(name).equals(numbers(alias));
				boolean placeWordDiffers = tokens.equals(at) && !words.equals(Names.contentTokens(alias))
						&& (types.isA(type, "place") || types.isA(e.type(), "place"));
				if (oneSaysMore || placeWordDiffers) {
					score = Math.min(score, MERGE - 0.01);
				}
				best = Math.max(best, score);
			}
			if (best >= AMBIGUOUS) {
				out.add(new Candidate(e, Math.round(best * 100) / 100.0));
			}
		}
		out.sort((a, b) -> Double.compare(b.score(), a.score()));
		return out;
	}

	/** The numbers in a name, which content tokens drop: the "5" that makes a Raspberry Pi 5 another thing. */
	private static Set<String> numbers(String name) {
		return Names.tokens(name).stream().filter(t -> t.chars().allMatch(Character::isDigit))
				.collect(java.util.stream.Collectors.toSet());
	}

	/** A token that identifies: three letters or more, or a number ("5" in "Raspberry Pi 5"). */
	private static boolean identity(String token) {
		return token.length() >= 3 || token.chars().allMatch(Character::isDigit);
	}

	static Set<String> trigrams(String s) {
		var out = new HashSet<String>();
		String padded = "  " + s + " ";
		for (int i = 0; i + 3 <= padded.length(); i++) {
			out.add(padded.substring(i, i + 3));
		}
		return out;
	}

	private static double jaccard(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		long inter = a.stream().filter(b::contains).count();
		return (double) inter / (a.size() + b.size() - inter);
	}

	private static void addAliases(Tx tx, long entityId, List<String> aliases, Long observationId) {
		for (String a : aliases) {
			if (a == null || a.isBlank()) {
				continue;
			}
			tx.update("INSERT OR IGNORE INTO entity_alias(entity_id, alias, alias_norm, source_observation) "
					+ "VALUES (?,?,?,?)", entityId, a.trim(), Names.norm(a), observationId);
			// Queries are matched as word grams, so an address or a handle ("alice@example.com", "@alice_example")
			// is also stored in its word form ("alice example com") and spotted when written in a question.
			String words = String.join(" ", Names.tokens(a));
			if (!words.isEmpty() && !words.equals(Names.norm(a))) {
				tx.update("INSERT OR IGNORE INTO entity_alias(entity_id, alias, alias_norm, source_observation) "
						+ "VALUES (?,?,?,?)", entityId, a.trim(), words, observationId);
			}
		}
	}

	private static List<String> aliasesOf(Tx tx, long entityId) {
		return tx.query("SELECT alias FROM entity_alias WHERE entity_id = ? ORDER BY id", entityId).stream()
				.map(r -> r.str("alias")).toList();
	}

	// ── merges ───────────────────────────────────────────────────────────

	/** Merges {@code from} into {@code into}: facts, events, aliases move; the source row is kept and marked. */
	public Map<String, Object> merge(long fromId, long intoId, Long observationId, String reason) {
		return db.write(tx -> merge(tx, get(tx, fromId), get(tx, intoId), observationId, reason));
	}

	private static Map<String, Object> merge(Tx tx, Entity from, Entity into, Long observationId, String reason) {
		if (from.id() == into.id()) {
			return Map.of();
		}
		List<Long> facts = tx.query("""
				SELECT id FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ?""", from.id(), from.id(),
				from.id()).stream().map(r -> r.lng("id")).toList();
		tx.update("UPDATE fact SET subject_id = ? WHERE subject_id = ?", into.id(), from.id());
		tx.update("UPDATE fact SET object_id = ? WHERE object_id = ?", into.id(), from.id());
		tx.update("UPDATE fact SET scope_id = ? WHERE scope_id = ?", into.id(), from.id());
		tx.update("UPDATE OR IGNORE event_participant SET entity_id = ? WHERE entity_id = ?", into.id(), from.id());
		tx.update("DELETE FROM event_participant WHERE entity_id = ?", from.id());
		List<String> aliases = aliasesOf(tx, from.id());
		addAliases(tx, into.id(), aliases, observationId);
		addAliases(tx, into.id(), List.of(from.name()), observationId);
		tx.update("DELETE FROM entity_alias WHERE entity_id = ?", from.id());
		tx.update("UPDATE entity SET merged_into = ? WHERE id = ?", into.id(), from.id());
		if ("unknown".equals(into.type()) && !"unknown".equals(from.type())) {
			tx.update("UPDATE entity SET type = ? WHERE id = ?", from.type(), into.id());
		}
		// Re-render facts that now name the surviving entity.
		for (Row r : tx.query("SELECT id, rendering FROM fact WHERE id IN ("
				+ String.join(",", facts.stream().map(String::valueOf).toList()) + ")")) {
			tx.update("UPDATE fact SET rendering = ? WHERE id = ?",
					r.str("rendering").replace(from.name(), into.name()), r.lng("id"));
		}
		long id = tx.insert(
				"""
						INSERT INTO entity_merge(from_id, into_id, from_name, moved_facts, moved_aliases, observation_id, reason,
						                         merged_at) VALUES (?,?,?,?,?,?,?,?)""",
				from.id(), into.id(), from.name(), Json.write(facts), Json.write(aliases), observationId, reason,
				Instant.now().toString());
		var m = new LinkedHashMap<String, Object>();
		m.put("merge", "merge-" + id);
		m.put("from", from.ref());
		m.put("from_name", from.name());
		m.put("into", into.ref());
		m.put("into_name", into.name());
		m.put("moved_facts", facts.stream().map(f -> "f-" + f).toList());
		m.put("reason", reason);
		return m;
	}

	public List<Map<String, Object>> merges() {
		return db.read(tx -> tx.query("SELECT * FROM entity_merge ORDER BY id").stream().map(r -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("merge", "merge-" + r.lng("id"));
			m.put("from", "ent-" + r.lng("from_id"));
			m.put("from_name", r.str("from_name"));
			m.put("into", "ent-" + r.lng("into_id"));
			m.put("reason", r.str("reason"));
			m.put("merged_at", r.str("merged_at"));
			return (Map<String, Object>) m;
		}).toList());
	}

	/** Pairs of distinct live entities sharing an alias with compatible types: consolidate merges them. */
	public List<long[]> duplicateAliasPairs() {
		return db.read(tx -> tx.query("""
				SELECT a.entity_id AS x, b.entity_id AS y FROM entity_alias a JOIN entity_alias b
				ON a.alias_norm = b.alias_norm AND a.entity_id < b.entity_id
				JOIN entity ex ON ex.id = a.entity_id JOIN entity ey ON ey.id = b.entity_id
				WHERE ex.merged_into IS NULL AND ey.merged_into IS NULL
				AND (ex.type = ey.type OR ex.type = 'unknown' OR ey.type = 'unknown')""").stream()
				.map(r -> new long[] {r.lng("x"), r.lng("y")}).toList());
	}

	// ── lookup ───────────────────────────────────────────────────────────

	private static Entity get(Tx tx, long id) {
		return tx.queryOne("SELECT * FROM entity WHERE id = ?", id).map(Entity::from)
				.orElseThrow(() -> MnemicException.notFound("No entity ent-" + id));
	}

	/** Follows merges: a merged-away id resolves to its survivor. */
	public Optional<Entity> get(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM entity WHERE id = ?", id).map(Entity::from)
				.map(e -> e.mergedInto() == null ? e : get(tx, e.mergedInto())));
	}

	/** The entity's name, or its ref when there is no such entity. */
	public String nameOf(long id) {
		return get(id).map(Entity::name).orElse("ent-" + id);
	}

	/** {@code ent-12}, or a name/alias (first match by id). */
	public Optional<Entity> byRef(String ref) {
		if (ref == null || ref.isBlank()) {
			return Optional.empty();
		}
		if (ref.startsWith("ent-")) {
			try {
				return get(Long.parseLong(ref.substring(4)));
			} catch (NumberFormatException e) {
				return Optional.empty();
			}
		}
		String norm = Names.norm(ref);
		if (SELF.contains(norm)) {
			return Optional.of(owner);
		}
		return db.read(tx -> byAlias(tx, norm, "unknown"));
	}

	public List<String> aliases(long entityId) {
		return db.read(tx -> aliasesOf(tx, entityId));
	}

	/**
	 * Entities mentioned in a query: word n-grams (longest first) matched exactly against the alias table, plus a
	 * single token that is the unique first name of a person. First-person references resolve to the owner.
	 */
	public List<Entity> spot(String query) {
		List<String> tokens = Names.tokens(query);
		var found = new LinkedHashMap<Long, Entity>();
		boolean[] used = new boolean[tokens.size()];
		for (int n = Math.min(MAX_NGRAM, tokens.size()); n >= 1; n--) {
			for (int i = 0; i + n <= tokens.size(); i++) {
				if (anyUsed(used, i, n)) {
					continue;
				}
				String gram = String.join(" ", tokens.subList(i, i + n));
				if (SELF.contains(gram)) {
					found.putIfAbsent(owner.id(), owner);
					mark(used, i, n);
					continue;
				}
				if (Names.STOPWORDS.contains(gram)) {
					continue;
				}
				List<Entity> hits = db.read(tx -> tx.query("""
						SELECT DISTINCT e.* FROM entity_alias a JOIN entity e ON e.id = a.entity_id
						WHERE a.alias_norm = ? AND e.merged_into IS NULL ORDER BY e.id""", gram).stream()
						.map(Entity::from).toList());
				if (hits.isEmpty() && n == 1 && gram.length() >= 3) {
					hits = db.read(tx -> tx.query("""
							SELECT e.* FROM entity e WHERE e.type = 'person' AND e.merged_into IS NULL
							AND (lower(e.name) LIKE ? || ' %')""", gram).stream().map(Entity::from).toList());
					if (hits.size() > 1) {
						hits = List.of(); // ambiguous first name: no guess
					}
				}
				if (!hits.isEmpty()) {
					for (Entity e : hits) {
						found.putIfAbsent(e.id(), e);
					}
					mark(used, i, n);
				}
			}
		}
		return new ArrayList<>(found.values());
	}

	private static boolean anyUsed(boolean[] used, int i, int n) {
		for (int k = i; k < i + n; k++) {
			if (used[k]) {
				return true;
			}
		}
		return false;
	}

	private static void mark(boolean[] used, int i, int n) {
		for (int k = i; k < i + n; k++) {
			used[k] = true;
		}
	}

	/** Entities with the most recent activity, for the briefing: by latest observation touching their facts. */
	public List<Entity> active(int limit) {
		return db.read(tx -> tx.query("""
				SELECT e.*, MAX(o.observed_at) AS last FROM entity e
				JOIN fact f ON f.subject_id = e.id OR f.object_id = e.id
				JOIN observation o ON o.id = f.observation_id
				WHERE e.merged_into IS NULL AND e.id <> ? AND f.status = 'current'
				GROUP BY e.id ORDER BY last DESC LIMIT ?""", owner.id(), limit).stream().map(Entity::from).toList());
	}

	/** Removes entities created by an observation that nothing references any more (forget cascade). */
	public int removeOrphansCreatedBy(long observationId) {
		return db.write(tx -> tx.update(
				"""
						DELETE FROM entity WHERE created_from = ?
						AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.subject_id = entity.id OR f.object_id = entity.id OR f.scope_id = entity.id)
						AND NOT EXISTS (SELECT 1 FROM event_participant p WHERE p.entity_id = entity.id)""",
				observationId));
	}

	/**
	 * Entities created by a forgotten observation that nothing refers to any more: the last reference may have gone
	 * after the observation did, when nothing looked again. The count removed.
	 */
	public int removeOrphansOfForgotten() {
		return db.write(tx -> tx.update(
				"""
						DELETE FROM entity WHERE merged_into IS NULL AND id <> ?
						AND created_from IN (SELECT id FROM observation WHERE forgotten_at IS NOT NULL)
						AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.subject_id = entity.id OR f.object_id = entity.id OR f.scope_id = entity.id)
						AND NOT EXISTS (SELECT 1 FROM event_participant p WHERE p.entity_id = entity.id)
						AND NOT EXISTS (SELECT 1 FROM entity m WHERE m.merged_into = entity.id)""",
				owner.id()));
	}

	// ── owner ────────────────────────────────────────────────────────────

	private Entity ensureOwner(String ownerName, List<String> ownerIdentity) {
		String name = ownerName == null || ownerName.isBlank() ? "the user" : ownerName.trim();
		return db.write(tx -> {
			Optional<Row> existing = tx.queryOne(
					"SELECT * FROM entity WHERE created_from IS NULL AND type = 'person' ORDER BY id LIMIT 1");
			long id;
			if (existing.isPresent()) {
				id = existing.get().lng("id");
				if (!existing.get().str("name").equals(name) && !"the user".equals(name)) {
					tx.update("UPDATE entity SET name = ? WHERE id = ?", name, id);
				}
			} else {
				id = tx.insert("INSERT INTO entity(name, type, created_from, created_at) VALUES (?,?,NULL,?)", name,
						"person", Instant.now().toString());
			}
			var aliases = new LinkedHashSet<String>(List.of(name, "I", "me", "my", "myself", "self", "the user"));
			String[] parts = name.split("\\s+");
			if (parts.length > 1 && !"the user".equals(name)) {
				aliases.add(parts[0]); // a first name; "the" is not one
			}
			// The configured identity (nicknames, addresses, handles): a commit author or an issue mention names
			// the owner too. A surname alone is not an alias unless configured, since it is shared with family.
			aliases.addAll(ownerIdentity);
			addAliases(tx, id, new ArrayList<>(aliases), null);
			return new Entity(id, name, "person", null, null);
		});
	}
}
