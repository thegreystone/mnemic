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

import java.time.Clock;
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
	/** Across types, or with none given, a match on letters alone (no shared name part) must be this close to ask. */
	static final double LETTERS_ACROSS_TYPES = 0.60;

	private final Database db;
	private final EntityTypeRegistry types;
	private final Clock clock;
	private volatile Entity owner;
	private long ownerDataVersion;
	/** Cached mention index for {@link #mentions}, guarded by {@link Database#withLock}. */
	private MentionIndex mentionIndex;
	private long mentionDataVersion = -1;
	private long mentionLocalVersion = -1;
	private long mentionIndexLoads;
	/**
	 * Pure-function feature cache for fuzzy alias matching, keyed by {@code (alias, entityType)} and cleared whenever
	 * {@link EntityTypeRegistry} changes. Safe across uncommitted write transactions and rollbacks because features
	 * depend only on the alias text, entity type, and type registry definitions.
	 */
	private static final int ALIAS_FEATURES_MAX = 16_384;
	/** The seed kind a web address with no kind given is created as. */
	static final String WEBSITE = "website";
	private final Map<AliasKey, AliasFeatures> aliasFeaturesCache = new HashMap<>();
	private long aliasFeaturesTypesVersion = -1;

	private record AliasKey(String alias, String type) {
	}

	private record AliasFeatures(String norm, List<String> identityTokens, List<String> possessors,
			Set<String> trigrams) {
	}

	/** {@code ownerIdentity}: configured aliases, e-mail addresses, and handles, seeded as aliases of the owner. */
	private final Set<String> ownerIdentityNorms;

	private final PredicateRegistry predicates;

	public EntityService(Database db, EntityTypeRegistry types, PredicateRegistry predicates, String ownerName,
			List<String> ownerIdentity) {
		this(db, types, predicates, ownerName, ownerIdentity, Clock.systemUTC());
	}

	public EntityService(Database db, EntityTypeRegistry types, PredicateRegistry predicates, String ownerName,
			List<String> ownerIdentity, Clock clock) {
		this.db = db;
		this.types = types;
		this.predicates = predicates;
		this.clock = clock == null ? Clock.systemUTC() : clock;
		this.owner = ensureOwner(ownerName, ownerIdentity);
		this.ownerDataVersion = db.dataVersion();
		this.ownerIdentityNorms = ownerIdentity.stream().map(Names::norm).collect(Collectors.toSet());
	}

	public Entity owner() {
		return db.withLock(() -> {
			long dv = db.dataVersion();
			if (dv != ownerDataVersion) {
				db.read(tx -> tx.queryOne("SELECT * FROM entity WHERE id = ?", owner.id()).map(Entity::from))
						.ifPresent(updated -> this.owner = updated);
				this.ownerDataVersion = dv;
			}
			return owner;
		});
	}

	/** Refreshes the cached owner row from the database (e.g. after a rolled-back transaction). */
	public void reloadOwner() {
		db.withLock(() -> {
			db.read(tx -> tx.queryOne("SELECT * FROM entity WHERE id = ?", owner.id()).map(Entity::from))
					.ifPresent(updated -> this.owner = updated);
			this.ownerDataVersion = db.dataVersion();
		});
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
		return resolve(name, type, aliases, observationId, distinctFrom, Set.of());
	}

	/**
	 * As above, with the kinds the proposal's facts expect of the entity ({@code expected}: the domains and ranges of
	 * the predicates that use it; empty when anything goes). They stand in for a type nobody gave, so a town that is
	 * the object of {@code lives_in} is a place, is not confused with an insurer of the same letters, and is created as
	 * a place.
	 */
	public Resolved resolve(
		String name, String type, List<String> aliases, Long observationId, Set<Long> distinctFrom,
		Set<String> expected) {
		if (name == null || name.isBlank()) {
			throw MnemicException.invalidArgument(
					"An entity needs a 'name'. Example: {\"name\": \"Anna Lindqvist\", \"type\": \"person\"}");
		}
		String norm = Names.norm(name);
		if (SELF.contains(norm)) {
			return new Resolved(owner(), "owner", 1.0, List.of());
		}
		String given = types.canonical(type);
		// The expectation stands in for a type nobody gave, and shapes the candidates for a kind nobody has placed;
		// a placed type the caller gave is the caller's to give.
		Set<String> want = EntityTypeRegistry.UNKNOWN.equals(given) || types.unplaced(given) ? expected : Set.of();
		String t = EntityTypeRegistry.UNKNOWN.equals(given) && want.size() == 1 ? want.iterator().next() : given;
		return db.write(tx -> {
			// Several things carry this very name ("Mona" for a mother and a colleague): which one is meant is asked,
			// at every mention. An earlier answer was about that mention; the next may mean the other (2026-11-24).
			// The kind the mention gives counts: "Java" typed a place is the island, whatever language shares the name.
			List<Entity> carriers = exactMatches(tx, norm, List.of()).stream()
					.filter(e -> !distinctFrom.contains(e.id())).toList();
			List<Entity> fitting = carriers.stream().filter(e -> types.compatible(e.type(), given)).toList();
			if (fitting.size() > 1) {
				return ambiguous(tx, name, norm, t, distinctFrom, want, fitting);
			}
			// The same name on record, of a kind this mention could not be: the caller cannot know what type a
			// thing was first registered under, so the exact matches are the first candidates. With one such thing,
			// the question is about its kind, asked once; an answer naming it settles later mentions.
			List<Entity> exact = exactMatches(tx, norm, aliases).stream().filter(e -> !distinctFrom.contains(e.id()))
					.toList();
			// Exact names are matched under the type the caller gave, or none: the expectation shapes what is
			// fuzzy and what is new, never whether the one Hooli on record is Hooli.
			List<Entity> fits = exact.stream().filter(e -> types.compatible(e.type(), given)).toList();
			List<Entity> among = fits.isEmpty() ? exact : fits;
			Entity match = null;
			String how = "alias";
			if (!among.isEmpty() && (fits.isEmpty() || !oneKind(fits))) {
				Optional<Entity> settled = among.size() == 1 ? answeredAmong(tx, norm, among) : Optional.empty();
				if (settled.isEmpty()) {
					return ambiguous(tx, name, norm, t, distinctFrom, want, among);
				}
				match = settled.get();
				how = "answered";
			}
			if (match == null) {
				match = byAlias(tx, norm, given).orElse(null);
			}
			if (match == null) {
				// No thing carries the name, but one or more carry an alias it is given: a name may be shared, so an
				// alias does not say which thing this is. Asked, with "new" beside them.
				List<Entity> byAliases = fits.stream().filter(e -> !carriers.contains(e)).toList();
				if (!byAliases.isEmpty()) {
					return ambiguous(tx, name, norm, t, distinctFrom, want, byAliases);
				}
			}
			// An alias the name's entity is given that another thing also carries is the alias of both: nothing is
			// merged on a shared name. Whether they are one thing is asked (ProposalApplier, a same_entity question).
			if (match != null && "alias".equals(how)) {
				// "Peter" on record and a "Peter Andersson" the user said was someone else: a bare "Peter" now fits
				// either, so it is asked, at every mention (2026-09-24, every mention since 2026-11-24).
				List<Entity> rivals = distinguishedFuller(tx, name, norm, t, match, distinctFrom);
				if (!rivals.isEmpty()) {
					var candidates = new ArrayList<Candidate>();
					candidates.add(new Candidate(match, 1.0));
					for (Entity r : rivals) {
						candidates.add(new Candidate(r, 0.5));
					}
					return new Resolved(null, "ambiguous", 1.0, candidates);
				}
			}
			if (match == null) {
				List<Candidate> fuzzy = fuzzy(tx, name, norm, t, distinctFrom, want);
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

	/** A question over {@code exact} (each at 1.0), with the fuzzy candidates after them and "new" beside. */
	private Resolved ambiguous(
		Tx tx, String name, String norm, String type, Set<Long> distinctFrom, Set<String> want, List<Entity> exact) {
		var candidates = new ArrayList<Candidate>();
		for (Entity o : exact) {
			candidates.add(new Candidate(o, 1.0));
		}
		for (Candidate c : fuzzy(tx, name, norm, type, distinctFrom, want)) {
			if (candidates.stream().noneMatch(x -> x.entity().id() == c.entity().id())) {
				candidates.add(c);
			}
		}
		return new Resolved(null, "ambiguous", 1.0, candidates);
	}

	/** Live entities that carry the name exactly, as their name or an alias, by id. */
	public List<Entity> carriers(String name) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		return db.read(tx -> exactMatches(tx, Names.norm(name), List.of()));
	}

	/**
	 * Live entities other than {@code entityId} that carry one of {@code aliases} exactly, of a kind that could be the
	 * same thing: the ones a shared alias leaves the question open for.
	 */
	public Map<Entity, String> sharingAliases(long entityId, List<String> aliases) {
		Entity self = get(entityId).orElse(null);
		if (self == null || aliases == null || aliases.isEmpty()) {
			return Map.of();
		}
		var out = new LinkedHashMap<Entity, String>();
		db.read(tx -> {
			for (String a : aliases) {
				if (a == null || a.isBlank() || SELF.contains(Names.norm(a))) {
					continue;
				}
				for (Entity e : exactMatches(tx, Names.norm(a), List.of())) {
					if (e.id() != self.id() && e.id() != owner.id() && types.compatible(e.type(), self.type())
							&& out.keySet().stream().noneMatch(x -> x.id() == e.id())) {
						out.put(e, a.trim());
					}
				}
			}
			return null;
		});
		return out;
	}

	/** Live entities whose name or an alias is the name or one of the aliases exactly, by id. */
	private static List<Entity> exactMatches(Tx tx, String norm, List<String> aliases) {
		var norms = new LinkedHashSet<String>();
		norms.add(norm);
		for (String a : aliases) {
			if (a != null && !a.isBlank()) {
				norms.add(Names.norm(a));
			}
		}
		String in = String.join(",", norms.stream().map(n -> "'" + n.replace("'", "''") + "'").toList());
		return tx.query(
				"SELECT DISTINCT e.* FROM entity_alias a JOIN entity e ON e.id = a.entity_id WHERE a.alias_norm IN ("
						+ in + ") AND e.merged_into IS NULL ORDER BY e.id")
				.stream().map(Entity::from).toList();
	}

	/** Whether every entity could be the same kind of thing as every other. */
	private boolean oneKind(List<Entity> es) {
		for (int i = 0; i < es.size(); i++) {
			for (int j = i + 1; j < es.size(); j++) {
				if (!types.compatible(es.get(i).type(), es.get(j).type())) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Whether {@code fuller} says more than {@code shorter} about the same thing: its identity tokens include all of
	 * the other's and at least one more ("Peter Andersson" over "Peter"; not "Oskar Nyberg" over "Konrad Nyberg").
	 */
	public boolean saysMore(String fuller, String shorter, String type) {
		List<String> more = types.identityTokens(fuller, type).stream().filter(t -> identity(t, fuller)).toList();
		List<String> less = types.identityTokens(shorter, type).stream().filter(t -> identity(t, shorter)).toList();
		return !less.isEmpty() && more.size() > less.size() && more.containsAll(less);
	}

	/**
	 * A mention that says more than the entity's name ("Peter Andersson" confirmed to be the "Peter" on record) gives
	 * the entity its fuller name; the old name stays an alias. The name renamed from, or null when nothing changed.
	 */
	public String adoptFullerName(long id, String mention) {
		Entity e = get(id).orElseThrow(() -> MnemicException.notFound("No entity ent-" + id));
		if (e.id() == owner.id() || !saysMore(mention, e.name(), e.type())) {
			return null;
		}
		correct(id, Map.of("name", mention.trim()));
		return e.name();
	}

	/**
	 * Live entities of a compatible kind whose name says more than {@code name} and which an answer of "new" set apart
	 * from it: the reason a bare mention that exactly matches {@code match} is still asked.
	 */
	private List<Entity> distinguishedFuller(
		Tx tx, String name, String norm, String type, Entity match, Set<Long> distinctFrom) {
		var out = new ArrayList<Entity>();
		for (Row r : tx.query("SELECT subject FROM question WHERE kind = 'entity_resolution' "
				+ "AND status = 'answered' AND lower(answer) = 'new' ORDER BY id DESC")) {
			String subject = r.str("subject");
			if (subject == null || Names.norm(subject).equals(norm)) {
				continue;
			}
			Optional<Entity> other = byAlias(tx, Names.norm(subject), type);
			if (other.isEmpty() || other.get().id() == match.id() || other.get().id() == owner.id()
					|| distinctFrom.contains(other.get().id()) || !types.compatible(other.get().type(), type)
					|| out.stream().anyMatch(e -> e.id() == other.get().id())) {
				continue;
			}
			if (saysMore(other.get().name(), name, other.get().type())) {
				out.add(other.get());
			}
		}
		return out;
	}

	/** The entity an earlier answer to the same question named, if it is one of {@code among}: asked once. */
	private static Optional<Entity> answeredAmong(Tx tx, String norm, List<Entity> among) {
		for (Row r : tx.query("SELECT subject, answer FROM question WHERE kind = 'entity_resolution' "
				+ "AND status = 'answered' AND answer LIKE 'ent-%' ORDER BY id DESC")) {
			if (!norm.equals(Names.norm(r.str("subject")))) {
				continue;
			}
			long id;
			try {
				id = Long.parseLong(r.str("answer").substring(4));
			} catch (NumberFormatException e) {
				continue;
			}
			Entity chosen = tx.queryOne("SELECT * FROM entity WHERE id = ?", id).map(Entity::from).orElse(null);
			while (chosen != null && chosen.mergedInto() != null) {
				chosen = get(tx, chosen.mergedInto());
			}
			if (chosen != null) {
				long survivor = chosen.id();
				Optional<Entity> hit = among.stream().filter(e -> e.id() == survivor).findFirst();
				if (hit.isPresent()) {
					return hit;
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Corrects an entity's {@code name}, {@code type}, or {@code aliases} (the list to keep). The name itself and the
	 * owner's configured identity are never dropped; an alias that names another entity is not taken.
	 */
	public Entity correct(long id, Map<String, Object> replacement) {
		Entity updated = db.write(tx -> {
			get(id).orElseThrow(() -> MnemicException.notFound("No entity ent-" + id));
			for (Map.Entry<String, Object> c : replacement.entrySet()) {
				switch (c.getKey()) {
				case "name" -> {
					String name = String.valueOf(c.getValue()).trim();
					if (name.isEmpty()) {
						throw MnemicException.invalidArgument("'name' must not be empty.");
					}
					tx.update("UPDATE entity SET name = ? WHERE id = ?", name, id);
					addAliases(tx, id, List.of(name), null);
				}
				case "type" -> retype(id, String.valueOf(c.getValue()));
				case "aliases" -> {
					if (!(c.getValue() instanceof List<?> keep)) {
						throw MnemicException.invalidArgument("'aliases' takes the list of aliases to keep.");
					}
					Set<String> kept = keep.stream().map(x -> Names.norm(String.valueOf(x)))
							.collect(Collectors.toSet());
					String own = Names.norm(get(id).orElseThrow().name());
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
				}
				case "add_aliases" -> {
					// Another name for the thing ("Konrad" for Konrad Nyberg), added without listing every name kept.
					if (!(c.getValue() instanceof List<?> more) || more.isEmpty()) {
						throw MnemicException.invalidArgument(
								"'add_aliases' takes the names to add, e.g. {\"add_aliases\": [\"Konrad\"]}.");
					}
					addAliases(tx, id, more.stream().map(String::valueOf).toList(), null);
				}
				default -> throw MnemicException.invalidArgument("Unknown entity property '" + c.getKey()
						+ "'; correctable: name, type, aliases (the list to keep), add_aliases.");
				}
			}
			return get(id).orElseThrow();
		});
		if (id == owner.id()) {
			db.withLock(() -> {
				this.owner = updated;
				this.ownerDataVersion = db.dataVersion();
			});
		}
		return updated;
	}

	/**
	 * Records {@code alias} as another name of the entity, and nothing else: no resolution, so an entity that already
	 * carries the name is never merged into this one. Two things may share a name; an answered question settles which
	 * one a later mention means. True when the name is new for the entity.
	 */
	public boolean addAlias(long entityId, String alias, Long observationId) {
		if (alias == null || alias.isBlank() || SELF.contains(Names.norm(alias))) {
			return false;
		}
		return db.write(tx -> {
			boolean had = tx.queryLong("SELECT COUNT(*) FROM entity_alias WHERE entity_id = ? AND alias_norm = ?",
					entityId, Names.norm(alias)) > 0;
			addAliases(tx, entityId, List.of(alias), observationId);
			return !had;
		});
	}

	/**
	 * Entities from before web addresses were typed: an untyped name that is a web address becomes a website. Run at
	 * start; returns how many changed.
	 */
	public int typeWebAddresses() {
		int changed = db.write(tx -> {
			int n = 0;
			for (Row r : tx.query("SELECT id, name FROM entity WHERE type = ? AND merged_into IS NULL",
					EntityTypeRegistry.UNKNOWN)) {
				if (r.lng("id") != owner.id() && Names.isWebAddress(r.str("name"))) {
					n += tx.update("UPDATE entity SET type = ? WHERE id = ?", WEBSITE, r.lng("id"));
				}
			}
			return n;
		});
		return changed;
	}

	/** Changes an entity's type: the answer to a type mismatch that blamed the entity. */
	public void retype(long id, String type) {
		String t = types.canonical(type);
		db.write(tx -> tx.update("UPDATE entity SET type = ? WHERE id = ?", t, id));
		if (id == owner.id()) {
			reloadOwner();
		}
	}

	/** Creates an entity outright (used when a question is answered with "new"). */
	public Entity create(String name, String type, List<String> aliases, Long observationId) {
		return db.write(tx -> create(tx, name, types.canonical(type), aliases, observationId));
	}

	private Entity create(Tx tx, String name, String given, List<String> aliases, Long observationId) {
		// A web address with no kind given is a website, never a thing that "may be" a car (2026-11-24).
		String type = EntityTypeRegistry.UNKNOWN.equals(given) && Names.isWebAddress(name) ? WEBSITE : given;
		long id = tx.insert("INSERT INTO entity(name, type, created_from, created_at) VALUES (?,?,?,?)", name.trim(),
				type, observationId, clock.instant().toString());
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
			if (types.compatible(e.type(), type)) {
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
	private List<Candidate> fuzzy(
		Tx tx, String name, String norm, String type, Set<Long> distinctFrom, Set<String> expected) {
		long tv = types.refreshAndVersion();
		if (tv != aliasFeaturesTypesVersion) {
			aliasFeaturesCache.clear();
			aliasFeaturesTypesVersion = tv;
		}
		List<String> tokens = types.identityTokensFresh(name, type).stream().filter(t -> identity(t, name)).toList();
		if (tokens.isEmpty()) {
			return List.of(); // nothing that identifies: "it", "AL" written lowercase, a stopword
		}
		Set<String> grams = trigrams(norm);
		// "Marcus's iPad" is not Marcus: the possessor names whose the thing is, not what it is. Against a name with
		// no possessive the possessor does not count as shared; against one with another possessor the things differ.
		List<String> possessors = Names.possessors(name);
		// Stream live entities without Row boxing and filter incompatible types before decoding any alias strings.
		var compatible = new LinkedHashMap<Long, Entity>();
		tx.queryEach("""
				SELECT id, name, type, created_from, merged_into FROM entity
				WHERE merged_into IS NULL AND id <> ?
				ORDER BY id""", rs -> {
			long id = rs.getLong(1);
			if (distinctFrom.contains(id)) {
				return; // declared distinct by the same proposal
			}
			String eType = rs.getString(3);
			if (!types.compatibleFresh(eType, type)) {
				return;
			}
			// The facts say what kind of thing this is (the object of lives_in is a place): a candidate of a
			// placed kind that is none of them is not offered.
			if (!expected.isEmpty() && !EntityTypeRegistry.UNKNOWN.equals(eType) && !types.unplacedFresh(eType)
					&& expected.stream().noneMatch(k -> types.isAFresh(eType, k))) {
				return;
			}
			long cfVal = rs.getLong(4);
			Long createdFrom = rs.wasNull() ? null : cfVal;
			long miVal = rs.getLong(5);
			Long mergedInto = rs.wasNull() ? null : miVal;
			compatible.put(id, new Entity(id, rs.getString(2), eType, createdFrom, mergedInto));
		}, owner.id());
		if (compatible.isEmpty()) {
			return List.of();
		}
		var aliasesByEntity = new HashMap<Long, LinkedHashSet<String>>(compatible.size());
		tx.queryEach("SELECT entity_id, alias FROM entity_alias ORDER BY id", rs -> {
			long eid = rs.getLong(1);
			if (!compatible.containsKey(eid)) {
				return;
			}
			String alias = rs.getString(2);
			if (alias != null) {
				aliasesByEntity.computeIfAbsent(eid, k -> new LinkedHashSet<>()).add(alias);
			}
		});
		var out = new ArrayList<Candidate>();
		List<String> words = null;
		Set<String> queryNumbers = null;
		Boolean queryCanContain = null;
		for (Entity e : compatible.values()) {
			Collection<String> entityAliases = aliasesByEntity.get(e.id());
			if (entityAliases == null) {
				continue;
			}
			double best = 0;
			for (String alias : entityAliases) {
				AliasFeatures feat = aliasFeatures(alias, e.type());
				String an = feat.norm();
				List<String> at = feat.identityTokens();
				List<String> aliasPossessors = feat.possessors();
				List<String> mine = tokens;
				List<String> theirs = at;
				if (!possessors.isEmpty() && aliasPossessors.isEmpty()) {
					mine = tokens.stream().filter(t -> !possessors.contains(t)).toList();
				} else if (possessors.isEmpty() && !aliasPossessors.isEmpty()) {
					theirs = at.stream().filter(t -> !aliasPossessors.contains(t)).toList(); // "Marcus" is not the iPad
				}
				long shared = 0;
				for (String tok : mine) {
					if (theirs.contains(tok)) {
						shared++;
					}
				}
				double tokenScore = shared == 0 ? 0 : (double) shared / Math.max(tokens.size(), at.size());
				// Malin's iPad is not Marcus's iPad, and a name that shares only its possessor's letters with
				// another is not that other: neither may reach a question on letters alone.
				boolean otherOwner = !possessors.isEmpty() && !aliasPossessors.isEmpty()
						&& !possessors.equals(aliasPossessors);
				boolean possessorOnly = shared == 0 && (mine != tokens || theirs != at);
				// "Oskar Nyberg" against "Konrad Nyberg": two full names whose leading tokens differ share a
				// family name, not an identity. "Anna" against "Anna Lindqvist" stays ambiguous.
				boolean leadingDiffers = tokens.size() >= 2 && at.size() >= 2
						&& !tokens.getFirst().equals(at.getFirst()) && !tokens.getFirst().startsWith(at.getFirst())
						&& !at.getFirst().startsWith(tokens.getFirst());
				if (leadingDiffers) {
					tokenScore = Math.min(tokenScore, AMBIGUOUS - 0.1);
				}
				double gramScore = an.length() >= 4 ? jaccard(grams, feat.trigrams()) : 0;
				// "Lo Berg" against "Bo Berg": the letters agree because the family name is most of them, but a
				// two-letter name has no room for a typo, so a different one is a different person. Three letters
				// and up keep the typo question ("Ewa Berg" against "Eva Berg").
				if (leadingDiffers && (tokens.getFirst().length() <= 2 || at.getFirst().length() <= 2)) {
					gramScore = Math.min(gramScore, AMBIGUOUS - 0.1);
				}
				double score = Math.max(tokenScore, gramScore);
				if (otherOwner || possessorOnly) {
					score = Math.min(score, AMBIGUOUS - 0.1);
				}
				// "coffee" against a project "Coff-E": no name part shared and not the same kind of thing. Letters
				// alone raise a question across types only when the spellings nearly agree.
				if (tokenScore == 0 && !type.equals(e.type()) && gramScore < LETTERS_ACROSS_TYPES) {
					score = 0;
				}
				// One name says more than the other: a version ("Raspberry Pi 5" against "Raspberry Pi"), or a place
				// word that names another level ("Luzern" against "Kanton Luzern"). Asked, never merged.
				if (score > MERGE - 0.01) {
					if (queryNumbers == null) {
						queryNumbers = numbers(name);
					}
					boolean oneSaysMore = (!tokens.equals(at) && (tokens.containsAll(at) || at.containsAll(tokens)))
							|| !queryNumbers.equals(numbers(alias));
					boolean placeWordDiffers = false;
					if (!oneSaysMore && tokens.equals(at)) {
						if (words == null) {
							words = Names.contentTokens(name);
						}
						if (!words.equals(Names.contentTokens(alias))) {
							if (queryCanContain == null) {
								queryCanContain = predicates.canContain(types.lineageFresh(type));
							}
							placeWordDiffers = queryCanContain || predicates.canContain(types.lineageFresh(e.type()));
						}
					}
					if (oneSaysMore || placeWordDiffers) {
						score = Math.min(score, MERGE - 0.01);
					}
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

	private AliasFeatures aliasFeatures(String alias, String entityType) {
		var key = new AliasKey(alias, entityType);
		AliasFeatures cached = aliasFeaturesCache.get(key);
		if (cached != null) {
			return cached;
		}
		if (aliasFeaturesCache.size() >= ALIAS_FEATURES_MAX) {
			aliasFeaturesCache.clear();
		}
		String an = Names.norm(alias);
		List<String> at = types.identityTokensFresh(alias, entityType).stream().filter(t -> identity(t, alias))
				.toList();
		List<String> aliasPossessors = Names.possessors(alias);
		Set<String> aliasGrams = an.length() >= 4 ? trigrams(an) : Set.of();
		AliasFeatures feat = new AliasFeatures(an, at, aliasPossessors, aliasGrams);
		aliasFeaturesCache.put(key, feat);
		return feat;
	}

	/** The numbers in a name, which content tokens drop: the "5" that makes a Raspberry Pi 5 another thing. */
	private static Set<String> numbers(String name) {
		return Names.tokens(name).stream().filter(t -> t.chars().allMatch(Character::isDigit))
				.collect(java.util.stream.Collectors.toSet());
	}

	/**
	 * A token that identifies: three letters or more, a number ("5" in "Raspberry Pi 5"), or a two-letter word that
	 * stands as a name where it is written ("Bo" in "Bo Berg"; see {@link Names#looksLikeName}).
	 */
	private static boolean identity(String token, String source) {
		return token.length() >= 3 || token.chars().allMatch(Character::isDigit) || Names.looksLikeName(token, source);
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
		Set<String> small = a.size() <= b.size() ? a : b;
		Set<String> large = small == a ? b : a;
		long inter = 0;
		for (String g : small) {
			if (large.contains(g)) {
				inter++;
			}
		}
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

	/** Each alias once: an address or a handle is stored in its word form as well, under the same alias. */
	private static List<String> aliasesOf(Tx tx, long entityId) {
		return tx.query("SELECT alias FROM entity_alias WHERE entity_id = ? GROUP BY alias ORDER BY MIN(id)", entityId)
				.stream().map(r -> r.str("alias")).toList();
	}

	/** Two live entities of kinds that cannot be one thing, sharing the name {@code alias}. */
	public record Collision(Entity a, Entity b, String alias) {
	}

	/**
	 * Live entities other than {@code id} that share one of its names and are of a kind it is not: the same name given
	 * to two things (the same car typed as a placed kind and as another), which resolution keeps apart.
	 */
	public List<Entity> homonymsOf(long id) {
		return db.read(tx -> {
			Entity e = get(tx, id);
			var out = new LinkedHashMap<Long, Entity>();
			for (Row r : tx.query("""
					SELECT o.* FROM entity_alias a JOIN entity_alias b ON a.alias_norm = b.alias_norm
					JOIN entity o ON o.id = b.entity_id
					WHERE a.entity_id = ? AND b.entity_id <> ? AND o.merged_into IS NULL ORDER BY o.id""", id, id)) {
				Entity o = Entity.from(r);
				if (!types.compatible(o.type(), e.type())) {
					out.putIfAbsent(o.id(), o);
				}
			}
			return List.copyOf(out.values());
		});
	}

	/** Pairs of live entities sharing a name that consolidate does not merge itself: listed, for the caller. */
	public List<Collision> nameCollisions() {
		return db.read(tx -> {
			var out = new ArrayList<Collision>();
			for (Row r : tx.query("""
					SELECT a.entity_id AS x, b.entity_id AS y, MIN(a.alias) AS alias FROM entity_alias a
					JOIN entity_alias b ON a.alias_norm = b.alias_norm AND a.entity_id < b.entity_id
					JOIN entity ex ON ex.id = a.entity_id JOIN entity ey ON ey.id = b.entity_id
					WHERE ex.merged_into IS NULL AND ey.merged_into IS NULL
					AND ex.type <> ey.type AND ex.type <> 'unknown' AND ey.type <> 'unknown'
					GROUP BY a.entity_id, b.entity_id ORDER BY a.entity_id, b.entity_id""")) {
				out.add(new Collision(get(tx, r.lng("x")), get(tx, r.lng("y")), r.str("alias")));
			}
			return out;
		});
	}

	/**
	 * Removes an entity nothing names: no fact of any standing, no event, no merge refers to it. One that facts name is
	 * refused with them listed; the owner is never removed. The id is gone for good.
	 */
	public Map<String, Object> remove(long id) {
		return db.write(tx -> {
			Entity e = get(tx, id);
			if (e.mergedInto() != null) {
				throw MnemicException.invalidArgument(e.ref() + " was merged into ent-" + e.mergedInto()
						+ " and is only a forwarding id now; there is nothing to remove.");
			}
			if (e.id() == owner.id()) {
				throw MnemicException.invalidArgument(e.ref() + " is the owner and is never removed.");
			}
			List<String> facts = tx
					.query("SELECT id FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ? " + "ORDER BY id",
							e.id(), e.id(), e.id())
					.stream().map(r -> "f-" + r.lng("id")).toList();
			long events = tx.queryLong("SELECT COUNT(*) FROM event_participant WHERE entity_id = ?", e.id());
			long mergedIn = tx.queryLong("SELECT COUNT(*) FROM entity WHERE merged_into = ?", e.id());
			if (!facts.isEmpty() || events > 0 || mergedIn > 0) {
				var named = new ArrayList<String>();
				if (!facts.isEmpty()) {
					named.add(facts.size() + (facts.size() == 1 ? " fact (" : " facts (")
							+ String.join(", ", facts.size() > 8 ? facts.subList(0, 8) : facts)
							+ (facts.size() > 8 ? ", ..." : "") + ")");
				}
				if (events > 0) {
					named.add(events + (events == 1 ? " event" : " events"));
				}
				if (mergedIn > 0) {
					named.add(mergedIn + (mergedIn == 1 ? " entity merged into it" : " entities merged into it"));
				}
				throw MnemicException.invalidArgument(e.ref() + " '" + e.name() + "' is named by "
						+ String.join(", ", named) + ". Forget the observations behind them, or, if it duplicates "
						+ "another entity, fold it into that one with correct(" + e.ref()
						+ ", {\"merge_into\": \"ent-N\"}).");
			}
			List<String> aliases = aliasesOf(tx, e.id());
			tx.update("DELETE FROM entity_alias WHERE entity_id = ?", e.id());
			tx.update("DELETE FROM entity WHERE id = ?", e.id());
			var m = new LinkedHashMap<String, Object>();
			m.put("entity", e.ref());
			m.put("name", e.name());
			m.put("type", e.type());
			m.put("aliases", aliases);
			m.put("removed", true);
			return m;
		});
	}

	// ── merges ───────────────────────────────────────────────────────────

	/** Merges {@code from} into {@code into}: facts, events, aliases move; the source row is kept and marked. */
	public Map<String, Object> merge(long fromId, long intoId, Long observationId, String reason) {
		return db.write(tx -> merge(tx, get(tx, fromId), get(tx, intoId), observationId, reason));
	}

	private Map<String, Object> merge(Tx tx, Entity from, Entity into, Long observationId, String reason) {
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
				clock.instant().toString());
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

	/** A name two entities both carry, for saying which one they share. */
	public String sharedAlias(long a, long b) {
		return db.read(tx -> tx.queryOne("""
				SELECT x.alias FROM entity_alias x JOIN entity_alias y ON x.alias_norm = y.alias_norm
				WHERE x.entity_id = ? AND y.entity_id = ? ORDER BY x.id LIMIT 1""", a, b).map(r -> r.str("alias"))
				.orElse(""));
	}

	/**
	 * Pairs of distinct live entities sharing an alias with compatible types: consolidate asks whether they are one.
	 */
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
			return Optional.of(owner());
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
		return spot(query, null);
	}

	/** Like {@link #spot(String)}, using {@code queryLang}'s first-person pronouns when non-null. */
	public List<Entity> spot(String query, Lang queryLang) {
		var found = new LinkedHashMap<Long, Entity>();
		for (Mention m : mentions(query, queryLang)) {
			found.putIfAbsent(m.entity().id(), m.entity());
		}
		return new ArrayList<>(found.values());
	}

	/** An entity mentioned in a query, at the token positions {@code [start, end)} of {@link Names#tokens}. */
	public record Mention(Entity entity, int start, int end) {
	}

	/**
	 * Snapshot of all live entities and aliases for fast in-memory {@link #mentions(String)} spotting without
	 * per-n-gram database round-trips. Refreshed automatically on any local or external commit.
	 */
	private record MentionIndex(int maxNgram, Map<String, List<Entity>> byAlias,
			Map<String, Entity> uniquePersonFirstName, Map<String, List<Entity>> byFamily) {
	}

	/**
	 * The mentions behind {@link #spot}: each entity with where its name stands in the query's tokens, longest names
	 * first, so a question's structure ("Anna's siblings") can be read off the positions.
	 */
	public List<Mention> mentions(String query) {
		return mentions(query, null);
	}

	/**
	 * Like {@link #mentions(String)}, using {@code queryLang}'s first-person pronouns when non-null so German
	 * ({@code mein}/{@code ich}) and Swedish ({@code jag}/{@code min}) spot the owner without Swedish preposition
	 * {@code i} ("in") colliding with English {@code I}.
	 */
	public List<Mention> mentions(String query, Lang queryLang) {
		List<String> tokens = Names.tokens(query);
		if (tokens.isEmpty()) {
			return List.of();
		}
		Set<String> selfWords = queryLang == null ? SELF : LanguagePack.of(queryLang).query().selfPronouns();
		if (selfWords.isEmpty()) {
			selfWords = SELF;
		}
		Set<String> effectiveSelf = selfWords;
		return db.withLock(() -> {
			MentionIndex idx = mentionIndex();
			Entity self = owner();
			var found = new ArrayList<Mention>();
			boolean[] used = new boolean[tokens.size()];
			// A fixed word limit could expose a shorter name inside a longer one: "Zurich" in
			// "Zurich Insurance Company of Switzerland". Bound the search by the names actually on record.
			for (int n = Math.min(idx.maxNgram(), tokens.size()); n >= 1; n--) {
				for (int i = 0; i + n <= tokens.size(); i++) {
					if (anyUsed(used, i, n)) {
						continue;
					}
					String gram = String.join(" ", tokens.subList(i, i + n));
					if (effectiveSelf.contains(gram)) {
						found.add(new Mention(self, i, i + n));
						mark(used, i, n);
						continue;
					}
					if (Names.STOPWORDS.contains(gram)) {
						continue;
					}
					List<Entity> hits = idx.byAlias().getOrDefault(gram, List.of());
					boolean nameLike = gram.length() >= 3 || Names.looksLikeName(gram, query);
					if (hits.isEmpty() && n == 1 && nameLike) {
						Entity person = idx.uniquePersonFirstName().get(gram);
						if (person != null) {
							hits = List.of(person);
						}
					}
					if (hits.isEmpty() && nameLike) {
						List<String> words = tokens.subList(i, i + n);
						if (!words.stream()
								.allMatch(w -> Names.STOPWORDS.contains(w) || MODEL_NUMBER.matcher(w).matches())) {
							hits = idx.byFamily().getOrDefault(gram, List.of());
						}
					}
					if (!hits.isEmpty()) {
						for (Entity e : hits) {
							found.add(new Mention(e, i, i + n));
						}
						mark(used, i, n);
					}
				}
			}
			return found;
		});
	}

	private MentionIndex mentionIndex() {
		if (db.inWriteTransaction()) {
			return loadMentionIndex();
		}
		long dv = db.dataVersion();
		long lv = db.localVersion();
		if (mentionIndex == null || mentionDataVersion != dv || mentionLocalVersion != lv) {
			mentionIndex = loadMentionIndex();
			mentionDataVersion = dv;
			mentionLocalVersion = lv;
			mentionIndexLoads++;
		}
		return mentionIndex;
	}

	private MentionIndex loadMentionIndex() {
		return db.read(tx -> {
			int[] maxNgram = {1};
			var byAlias = new HashMap<String, List<Entity>>();
			var uniquePersonFirstName = new HashMap<String, Entity>();
			var ambiguousFirstNames = new HashSet<String>();
			var byFamily = new HashMap<String, List<Entity>>();
			long[] lastEntityId = {-1};
			Entity[] current = {null};
			tx.queryEach("""
					SELECT e.id, e.name, e.type, e.created_from, e.merged_into, a.alias_norm
					FROM entity e
					LEFT JOIN entity_alias a ON a.entity_id = e.id
					WHERE e.merged_into IS NULL
					ORDER BY e.id, a.id""", rs -> {
				long id = rs.getLong(1);
				if (id != lastEntityId[0]) {
					long cfVal = rs.getLong(4);
					Long createdFrom = rs.wasNull() ? null : cfVal;
					long miVal = rs.getLong(5);
					Long mergedInto = rs.wasNull() ? null : miVal;
					current[0] = new Entity(id, rs.getString(2), rs.getString(3), createdFrom, mergedInto);
					lastEntityId[0] = id;
					if ("person".equals(current[0].type()) && current[0].name() != null) {
						String lower = current[0].name().toLowerCase(Locale.ROOT);
						int sp = lower.indexOf(' ');
						if (sp > 0) {
							String first = lower.substring(0, sp);
							if (!ambiguousFirstNames.contains(first)) {
								if (uniquePersonFirstName.containsKey(first)) {
									uniquePersonFirstName.remove(first);
									ambiguousFirstNames.add(first);
								} else {
									uniquePersonFirstName.put(first, current[0]);
								}
							}
						}
					}
				}
				String aliasNorm = rs.getString(6);
				if (aliasNorm != null) {
					int spaces = 0;
					for (int k = 0; k < aliasNorm.length(); k++) {
						if (aliasNorm.charAt(k) == ' ') {
							spaces++;
						}
					}
					if (spaces + 1 > maxNgram[0]) {
						maxNgram[0] = spaces + 1;
					}
					addUniqueEntity(byAlias, aliasNorm, current[0]);
					List<String> aliasTokens = Names.tokens(aliasNorm);
					List<String> stripped = aliasTokens.stream().filter(w -> !MODEL_NUMBER.matcher(w).matches())
							.toList();
					if (!stripped.isEmpty() && stripped.size() < aliasTokens.size()) {
						addUniqueEntity(byFamily, String.join(" ", stripped), current[0]);
					}
				}
			});
			return new MentionIndex(maxNgram[0], byAlias, uniquePersonFirstName, byFamily);
		});
	}

	private static void addUniqueEntity(Map<String, List<Entity>> map, String key, Entity entity) {
		List<Entity> list = map.computeIfAbsent(key, ignored -> new ArrayList<>(1));
		if (list.isEmpty() || list.getLast().id() != entity.id()) {
			list.add(entity);
		}
	}

	long mentionIndexLoads() {
		return db.withLock(() -> mentionIndexLoads);
	}

	/** A version or model number: "5", "4b", "v2", "mk3". */
	private static final java.util.regex.Pattern MODEL_NUMBER = java.util.regex.Pattern
			.compile("\\d+[a-z]?|v\\d+|mk\\d+");

	/** The family a name belongs to: its words with the model numbers removed ("Raspberry Pi 5" → "raspberry pi"). */
	public static String familyKey(String name) {
		return String.join(" ", Names.tokens(name).stream().filter(w -> !MODEL_NUMBER.matcher(w).matches()).toList());
	}

	/**
	 * The namesakes of a name: every entity with a longer name that carries these words as a run, "Polestar 4" and
	 * "Hans Nordvik" for "Polestar" and "Nordvik". What a question may mean by a name when the thing that carries the
	 * name exactly cannot be what it asks about. The owner is never one.
	 */
	public List<Entity> namesakes(String name) {
		List<String> words = Names.tokens(name);
		if (words.isEmpty()) {
			return List.of();
		}
		String gram = String.join(" ", words);
		String needle = gram.replace("%", "").replace("_", "");
		var out = new LinkedHashMap<Long, Entity>();
		for (Row r : db.read(tx -> tx.query("""
				SELECT e.*, a.alias_norm AS alias_norm FROM entity_alias a JOIN entity e ON e.id = a.entity_id
				WHERE a.alias_norm LIKE '%' || ? || '%' AND a.alias_norm <> ? AND e.merged_into IS NULL AND e.id <> ?
				ORDER BY e.id""", needle, gram, owner.id()))) {
			List<String> at = Names.tokens(r.str("alias_norm"));
			if (at.size() > words.size() && containsRun(at, words)) {
				Entity e = Entity.from(r);
				out.putIfAbsent(e.id(), e);
			}
		}
		return new ArrayList<>(out.values());
	}

	private static boolean containsRun(List<String> tokens, List<String> run) {
		for (int i = 0; i + run.size() <= tokens.size(); i++) {
			if (tokens.subList(i, i + run.size()).equals(run)) {
				return true;
			}
		}
		return false;
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
			String effectiveName = name;
			if (existing.isPresent()) {
				id = existing.get().lng("id");
				if (!existing.get().str("name").equals(name) && !"the user".equals(name)) {
					tx.update("UPDATE entity SET name = ? WHERE id = ?", name, id);
				} else {
					effectiveName = existing.get().str("name");
				}
			} else {
				id = tx.insert("INSERT INTO entity(name, type, created_from, created_at) VALUES (?,?,NULL,?)", name,
						"person", clock.instant().toString());
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
			return new Entity(id, effectiveName, "person", null, null);
		});
	}
}
