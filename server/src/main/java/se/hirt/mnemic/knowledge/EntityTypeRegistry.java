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
import se.hirt.mnemic.proposal.Proposal.EntityTypeDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The kinds of thing an entity can be: seeded, extensible from a proposal, corrected through {@code correct}. A type
 * carries the synonyms that map a proposed type onto it ({@code company} → {@code organization}), the words that say
 * what kind of thing a name is rather than which one ("Kanton" in "Kanton Luzern", dropped before fuzzy matching), and
 * an optional parent: a country is a place, so everything that accepts a place accepts a country. A type nobody
 * registered is registered from its first use, without a parent until somebody says what kind of thing it is, so
 * nothing is refused for its type; once a type is registered, entities that were typed by one of its synonyms take its
 * name.
 * <p>
 * The table is read once at start into hash maps (by name, by synonym, the set of type words); every write goes to the
 * database and into the maps in the same call, so lookups never touch the database and the next start sees everything
 * this one registered.
 */
public final class EntityTypeRegistry {

	/**
	 * A registered type. {@code typeWords} are affixes of the same thing ("Kanton", "GmbH"), dropped from a name's
	 * identity; {@code kinds} are words that name a kind of it ("hotel" of place, "clinic" of organization, "dog" of
	 * animal): a type proposed under one of them is placed under this type from its first use instead of being asked
	 * about, and keeps every word of its name, a hotel named after its town being another thing than the town. Both
	 * lists grow through a definition or a correction, as the store learns the user's world.
	 */
	public record EntityType(String name, String description, String parent, List<String> synonyms,
			List<String> typeWords, List<String> kinds, Long definedBy, boolean seed, boolean inferred,
			boolean disjoint) {
	}

	/** A definition that states nothing beyond the name. */
	static boolean bare(EntityTypeDef d) {
		return d.description() == null && (d.parent() == null || d.parent().isBlank()) && d.synonyms().isEmpty()
				&& d.typeWords().isEmpty() && d.kinds().isEmpty() && d.disjoint() == null;
	}

	public static final String UNKNOWN = "unknown";

	private final Database db;
	private final Map<String, EntityType> byName = new LinkedHashMap<>();
	private final Map<String, String> bySynonym = new HashMap<>();
	private final Set<String> typeWords = new HashSet<>();
	/** Seed rows read with no kinds column value at all: filled from the seed once, at this start. */
	private final Set<String> kindsUnset = new HashSet<>();

	public EntityTypeRegistry(Database db) {
		this.db = db;
		load();
		for (EntityType e : seed()) {
			EntityType stored = byName.get(e.name());
			if (stored == null) {
				insert(e);
			} else if (stored.inferred() && !stored.seed()) {
				// The store registered the name from use before the seed existed ("animal" on 2026-09-23): the seed's
				// words and description arrive, the parent the store chose stays, and the row is a seed from now on.
				var merged = new EntityType(stored.name(),
						stored.description() == null ? e.description() : stored.description(), stored.parent(),
						union(stored.synonyms(), e.synonyms()), union(stored.typeWords(), e.typeWords()),
						stored.kinds().isEmpty() ? e.kinds() : stored.kinds(), stored.definedBy(), true, false,
						stored.disjoint() || e.disjoint());
				db.write(tx -> tx.update("""
						UPDATE entity_type SET description = ?, synonyms = ?, type_words = ?, kinds = ?, seed = 1,
						                       inferred = 0, disjoint = ? WHERE name = ?""", merged.description(),
						Vocabulary.json(merged.synonyms()), Vocabulary.json(merged.typeWords()),
						Vocabulary.json(merged.kinds()), merged.disjoint() ? 1 : 0, merged.name()));
				index(merged);
			} else if (kindsUnset.contains(e.name()) && !e.kinds().isEmpty()) {
				// A store from before kinds existed: the seed's words are written once, and from then on the row
				// is the store's own, however the user changes it (a list emptied on purpose stays empty).
				String json = Vocabulary.json(e.kinds());
				db.write(tx -> tx.update("UPDATE entity_type SET kinds = ? WHERE name = ?", json, e.name()));
				index(new EntityType(stored.name(), stored.description(), stored.parent(), stored.synonyms(),
						stored.typeWords(), e.kinds(), stored.definedBy(), stored.seed(), stored.inferred(),
						stored.disjoint()));
			}
		}
		kindsUnset.clear();
		// Entities typed with a type nobody registered (a store from before 2.3): registered from use now.
		for (Row r : db.read(tx -> tx.query("SELECT DISTINCT type FROM entity WHERE merged_into IS NULL"))) {
			String type = r.str("type");
			if (type != null && !UNKNOWN.equals(type) && !byName.containsKey(key(type))) {
				registerInferred(type, null);
			}
		}
	}

	/** Reads the table again, after a change made beside the registry. */
	public synchronized void reload() {
		byName.clear();
		bySynonym.clear();
		typeWords.clear();
		load();
	}

	public synchronized List<EntityType> all() {
		return List.copyOf(byName.values());
	}

	public synchronized Optional<EntityType> get(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(byName.get(key(name)));
	}

	/** The registered name for a proposed type, through its synonyms; unknown types pass through lowercased. */
	public synchronized String canonical(String type) {
		if (type == null || type.isBlank()) {
			return UNKNOWN;
		}
		String t = key(type);
		return byName.containsKey(t) ? t : bySynonym.getOrDefault(t, t);
	}

	/** Registered names are lowercase with underscores: "Sports club" and "sports_club" are one type. */
	private static String key(String name) {
		return Names.norm(name).replace(' ', '_');
	}

	/** Types with no parent: the kinds a new type can be a kind of. */
	public synchronized List<String> roots() {
		return byName.values().stream().filter(t -> t.parent() == null).map(EntityType::name).toList();
	}

	/** The type and its ancestors, nearest first: {@code country, place}; a synonym ("cats") reads as its type. */
	public synchronized List<String> lineage(String type) {
		var out = new ArrayList<String>();
		String at = type == null ? UNKNOWN : canonical(type);
		while (at != null && !out.contains(at)) {
			out.add(at);
			EntityType e = byName.get(at);
			at = e == null ? null : e.parent();
		}
		return out;
	}

	/** Whether {@code type} is {@code kind} or nests within it: a country is a place. */
	public boolean isA(String type, String kind) {
		return lineage(type).contains(kind);
	}

	/** The same kind of thing: equal types, or the same root (a town and a country resolve against each other). */
	public boolean sameKind(String a, String b) {
		return a.equals(b) || lineage(a).getLast().equals(lineage(b).getLast());
	}

	/**
	 * A kind nobody has placed: registered from use with no parent and no description, or not registered at all. It
	 * separates no identities until the user says what it is a kind of, or a kind of its own.
	 */
	public synchronized boolean unplaced(String type) {
		if (type == null || type.isBlank() || UNKNOWN.equals(type)) {
			return false;
		}
		EntityType e = byName.get(canonical(type));
		return e == null || (e.inferred() && e.parent() == null);
	}

	/**
	 * Whether a thing of type {@code a} may be the one proposed as {@code b}: the same kind, no kind given, or a kind
	 * nobody has placed (a "vehicle" registered from use is not known to differ from a thing, so the car typed once as
	 * either is one car).
	 */
	public boolean compatible(String a, String b) {
		return UNKNOWN.equals(a) || UNKNOWN.equals(b) || sameKind(a, b) || unplaced(a) || unplaced(b);
	}

	/**
	 * The registered type a word of a question names ("vehicles", "companies", "objects"): by name, synonym, or type
	 * word, singular or plural.
	 */
	public synchronized Optional<String> kindNamedBy(String word) {
		for (String w : Lang.singulars(word)) {
			String k = key(w);
			if (byName.containsKey(k)) {
				return Optional.of(k);
			}
			if (bySynonym.containsKey(k)) {
				return Optional.of(bySynonym.get(k));
			}
			for (EntityType t : byName.values()) {
				if (t.typeWords().contains(k)) {
					return Optional.of(t.name());
				}
			}
		}
		return Optional.empty();
	}

	/** A word that says what kind of thing something is (Inc, Kanton, Team) rather than which one, for any type. */
	public synchronized boolean isTypeWord(String token) {
		return typeWords.contains(token);
	}

	/** Content tokens of a name that identify it: the type words of its kind are removed. */
	public synchronized List<String> identityTokens(String name, String type) {
		var drop = new HashSet<String>();
		for (String kind : lineage(canonical(type))) {
			EntityType e = byName.get(kind);
			if (e != null) {
				drop.addAll(e.typeWords());
			}
		}
		List<String> kept = Names.contentTokens(name).stream().filter(t -> !drop.contains(t)).toList();
		return kept.isEmpty() ? Names.contentTokens(name) : kept; // "Kanton" alone stays "kanton"
	}

	/**
	 * Registers a type from its first use: no parent, no words, no description. An existing name is returned as it is.
	 */
	public synchronized EntityType registerInferred(String name, Long observationId) {
		String n = key(name);
		if (byName.containsKey(n)) {
			return byName.get(n);
		}
		var e = new EntityType(n, null, null, List.of(), List.of(), List.of(), observationId, false, true, false);
		insert(e);
		return e;
	}

	/**
	 * Where a proposed type landed without a question: the type, what placed it in words, and whether the type is new
	 * ({@code created}) or an existing one the spelling named (a plural of a registered type or of its synonym).
	 */
	public record Placement(EntityType type, String how, boolean created) {
	}

	/**
	 * Places a type from its use when its spelling says where it goes: a plural of a registered type, or of one of its
	 * synonyms, is that type ("cats" → cat, "companies" → organization; the plural kept as a synonym, entities
	 * retyped); a word in a registered type's {@code kinds}, singular or plural, becomes a new type under it ("hotel"
	 * under place, "cats" the type cat under animal); a compound is placed under what its head names, a registered type
	 * or synonym first ("football team" under team), a kind second ("boutique hotel" under place), under its own name.
	 * What the store creates stays inferred, so a definition or a correction can move it. Empty when nothing in the
	 * spelling says, or when two types claim the word, and the caller asks.
	 */
	public synchronized Optional<Placement> placeFromUse(String name, Long observationId) {
		String n = key(name);
		if (n.isBlank() || UNKNOWN.equals(n) || byName.containsKey(n) || bySynonym.containsKey(n)) {
			return Optional.empty();
		}
		for (String form : Lang.singulars(n)) {
			Optional<EntityType> named = named(form);
			if (named.isPresent() && !form.equals(n)) {
				EntityType t = named.get();
				String why = "'" + n + "' is the plural of " + form
						+ (form.equals(t.name()) ? "" : ", a synonym of " + t.name());
				return Optional.of(new Placement(update(t.name(), Map.of("synonyms", plus(t.synonyms(), n)), why, true),
						why, false));
			}
		}
		for (String form : Lang.singulars(n)) {
			Optional<String> parent = kindNamedByWord(form);
			if (parent.isPresent()) {
				var e = new EntityType(form, "A kind of " + parent.get() + ".", parent.get(),
						form.equals(n) ? List.of() : List.of(n), List.of(), List.of(), observationId, false, true,
						false);
				insert(e);
				adopt(e);
				return Optional.of(new Placement(e, "'" + form + "' is a kind of " + parent.get(), true));
			}
		}
		int cut = n.lastIndexOf('_');
		if (cut > 0) {
			for (String form : Lang.singulars(n.substring(cut + 1))) {
				Optional<String> parent = named(form).map(EntityType::name).or(() -> kindNamedByWord(form));
				if (parent.isPresent() && !parent.get().equals(n)) {
					var e = new EntityType(n, "A kind of " + parent.get() + ".", parent.get(), List.of(), List.of(),
							List.of(), observationId, false, true, false);
					insert(e);
					return Optional.of(new Placement(e,
							"'" + form + "', the head of '" + n + "', is a kind of " + parent.get(), true));
				}
			}
		}
		return Optional.empty();
	}

	/** The registered type a word names outright: by name or by synonym. */
	private Optional<EntityType> named(String word) {
		if (byName.containsKey(word)) {
			return Optional.of(byName.get(word));
		}
		return Optional.ofNullable(bySynonym.get(word)).map(byName::get);
	}

	/**
	 * Places the types registered from use, and not yet placed, that a type's kinds now name ("van" after vehicle is
	 * defined with kinds [van, truck]): each becomes a kind of it, logged as such. Returns the names placed, so the
	 * caller can settle the questions that asked what they were.
	 */
	public synchronized List<String> adoptKinds(String typeName) {
		EntityType parent = byName.get(key(typeName));
		if (parent == null) {
			return List.of();
		}
		var placed = new ArrayList<String>();
		for (String word : parent.kinds()) {
			if (!kindNamedByWord(word).filter(parent.name()::equals).isPresent()) {
				continue; // another type claims the word too: nothing is placed, as from use
			}
			for (EntityType t : List.copyOf(byName.values())) {
				boolean named = t.name().equals(word) || Lang.singulars(t.name()).contains(word);
				if (named && t.inferred() && t.parent() == null && !t.name().equals(parent.name())
						&& !lineage(parent.name()).contains(t.name())) {
					update(t.name(), Map.of("parent", parent.name()), "'" + word + "' is a kind of " + parent.name(),
							true);
					placed.add(t.name());
				}
			}
		}
		return placed;
	}

	/** The one registered type whose kinds hold the word; empty when none or several do. */
	private Optional<String> kindNamedByWord(String word) {
		String found = null;
		for (EntityType t : byName.values()) {
			if (t.kinds().contains(word)) {
				if (found != null) {
					return Optional.empty();
				}
				found = t.name();
			}
		}
		return Optional.ofNullable(found);
	}

	private static List<String> union(List<String> a, List<String> b) {
		var out = new ArrayList<>(a);
		for (String x : b) {
			if (!out.contains(x)) {
				out.add(x);
			}
		}
		return out;
	}

	private static List<String> plus(List<String> list, String item) {
		var out = new ArrayList<>(list);
		if (!out.contains(item)) {
			out.add(item);
		}
		return out;
	}

	/** Registers a caller-defined type; an existing name is returned as it is. */
	public synchronized EntityType register(EntityTypeDef def, Long observationId) {
		if (def.name() == null || def.name().isBlank()) {
			throw MnemicException.invalidArgument("An entity type definition needs a 'name'.");
		}
		String name = key(def.name());
		EntityType existing = byName.get(name);
		if (existing != null) {
			if (!existing.inferred() || bare(def)) {
				return existing;
			}
			// A definition for a type registered from use: what it states replaces what was inferred.
			var replacement = new LinkedHashMap<String, Object>();
			if (def.description() != null) {
				replacement.put("description", def.description());
			}
			if (def.parent() != null && !def.parent().isBlank()) {
				replacement.put("parent", def.parent());
			}
			if (!def.synonyms().isEmpty()) {
				replacement.put("synonyms", def.synonyms());
			}
			if (!def.typeWords().isEmpty()) {
				replacement.put("type_words", def.typeWords());
			}
			if (!def.kinds().isEmpty()) {
				replacement.put("kinds", def.kinds());
			}
			if (def.disjoint() != null) {
				replacement.put("disjoint", def.disjoint());
			}
			return update(name, replacement, "defined after registration from use");
		}
		String parent = def.parent() == null || def.parent().isBlank() ? null : canonical(def.parent());
		if (parent != null && !byName.containsKey(parent)) {
			throw MnemicException.invalidArgument("Entity type '" + name + "' names an unknown parent '" + def.parent()
					+ "'; register the parent first or leave it out.");
		}
		var e = new EntityType(name, def.description(), parent, lower(def.synonyms()), lower(def.typeWords()),
				lower(def.kinds()), observationId, false, false, Boolean.TRUE.equals(def.disjoint()));
		insert(e);
		adopt(e);
		return e;
	}

	/** Entities typed by one of the type's synonyms before it was registered take the registered name. */
	private void adopt(EntityType e) {
		for (String synonym : e.synonyms()) {
			db.write(tx -> tx.update("UPDATE entity SET type = ? WHERE type = ?", e.name(), synonym));
		}
	}

	/**
	 * Corrects {@code description}, {@code parent}, {@code synonyms}, {@code type_words}, {@code kinds}, or
	 * {@code disjoint}; every change is logged.
	 */
	public synchronized EntityType update(String name, Map<String, Object> replacement, String reason) {
		return update(name, replacement, reason, false);
	}

	/**
	 * As above; {@code keepInferred} leaves a type registered from use as inferred, for a change the store makes on its
	 * own (a plural noted as a synonym, a placement under a kind), so that a definition can still move it.
	 */
	private synchronized EntityType update(
		String name, Map<String, Object> replacement, String reason, boolean keepInferred) {
		EntityType e = get(name).orElseThrow(() -> MnemicException.notFound("No entity type " + name));
		String description = e.description();
		String parent = e.parent();
		List<String> synonyms = e.synonyms();
		List<String> words = e.typeWords();
		List<String> kinds = e.kinds();
		boolean disjoint = e.disjoint();
		var changes = new ArrayList<String[]>();
		for (Map.Entry<String, Object> c : replacement.entrySet()) {
			String old;
			String value = Vocabulary.text(c.getValue());
			switch (c.getKey()) {
			case "description" -> {
				old = description;
				description = value;
			}
			case "parent" -> {
				old = parent;
				parent = value == null || value.isBlank() ? null : canonical(value);
				if (parent != null && (!byName.containsKey(parent) || lineage(parent).contains(e.name()))) {
					throw MnemicException.invalidArgument("'" + value
							+ "' is not a registered entity type, or would nest " + e.name() + " within itself.");
				}
			}
			case "synonyms" -> {
				old = Vocabulary.json(synonyms);
				synonyms = lower(Vocabulary.strings(c.getValue()));
			}
			case "type_words" -> {
				old = Vocabulary.json(words);
				words = lower(Vocabulary.strings(c.getValue()));
			}
			case "kinds" -> {
				old = Vocabulary.json(kinds);
				kinds = lower(Vocabulary.strings(c.getValue()));
			}
			case "disjoint" -> {
				old = String.valueOf(disjoint);
				disjoint = Boolean.parseBoolean(String.valueOf(c.getValue()));
			}
			default -> throw MnemicException.invalidArgument("Unknown entity type property '" + c.getKey()
					+ "'; correctable: description, parent, synonyms, type_words, kinds, disjoint.");
			}
			changes.add(new String[] {c.getKey(), old, value});
		}
		boolean inferred = keepInferred && e.inferred();
		var updated = new EntityType(e.name(), description, parent, synonyms, words, kinds, e.definedBy(), e.seed(),
				inferred, disjoint);
		db.write(tx -> {
			tx.update(
					"UPDATE entity_type SET description = ?, parent = ?, synonyms = ?, type_words = ?, kinds = ?, "
							+ "disjoint = ?, inferred = ? WHERE name = ?",
					updated.description(), updated.parent(), Vocabulary.json(updated.synonyms()),
					Vocabulary.json(updated.typeWords()), Vocabulary.json(updated.kinds()), updated.disjoint() ? 1 : 0,
					inferred ? 1 : 0, updated.name());
			Vocabulary.logChanges(tx, "entity_type", updated.name(), changes, reason);
			return null;
		});
		index(updated);
		adopt(updated);
		return updated;
	}

	public List<Map<String, Object>> changes(String name) {
		return db.read(tx -> Vocabulary.changes(tx, "entity_type", name));
	}

	private static List<String> lower(List<String> words) {
		return words.stream().map(Names::norm).filter(w -> !w.isEmpty()).distinct().toList();
	}

	// ── seed ─────────────────────────────────────────────────────────────

	/** The seed vocabulary: English, German, and Swedish type words, and the usual company suffixes. */
	static List<EntityType> seed() {
		return List.of(seed("person", "A human being.", null, List.of("human", "people", "individual"), List.of()),
				seed("organization", "A company, institution, or other body.", null,
						List.of("company", "org", "organisation", "employer", "firm"),
						List.of("company", "corporation", "corp", "inc", "ltd", "llc", "plc", "gmbh", "ag", "ab", "oy",
								"asa", "sa", "bv", "nv", "group", "holding", "holdings", "foundation", "institute",
								"university", "school", "bank", "agency", "department", "ministry", "verein",
								"stiftung", "forening"),
						List.of("university", "school", "kindergarten", "bank", "insurer", "agency", "ministry",
								"hospital", "clinic", "practice", "dealer", "dealership", "club", "church", "shop",
								"store", "restaurant", "cafe", "publisher", "charity", "association", "startup")),
				seed("place", "A town, region, address, or other location.", null,
						List.of("city", "town", "region", "location", "village"),
						List.of("kanton", "canton", "county", "province", "region", "state", "district", "lake",
								"mount", "mountain", "river", "island", "city", "town", "village", "municipality",
								"kommun", "gemeinde", "stadt", "bezirk", "landkreis", "lan", "sjo", "berg", "see"),
						List.of("canton", "kanton", "county", "province", "district", "municipality", "address",
								"apartment", "flat", "house", "home", "cottage", "cabin", "venue", "hotel", "hostel",
								"building", "room", "office", "street", "square", "park", "airport", "station",
								"harbour", "harbor", "beach", "farm", "island", "lake", "mountain", "river")),
				disjointSeed("country", "A country; two different countries never overlap.", "place", List.of("nation"),
						List.of()),
				seed("project", "A project or initiative.", null, List.of(),
						List.of("project", "projekt", "initiative", "program", "programme")),
				seed("team", "A team or group of people.", null, List.of("group"),
						List.of("team", "group", "squad", "unit", "department")),
				seed("product", "A product.", null, List.of(), List.of()),
				seed("technology", "A tool, library, language, or platform.", null,
						List.of("tool", "library", "language", "framework", "software", "database", "platform"),
						List.of(),
						List.of("app", "application", "api", "protocol", "compiler", "runtime", "module", "component",
								"package", "repository", "plugin", "extension")),
				seed("event", "A conference, meeting, trip, or other occasion.", null, List.of(),
						List.of("conference", "meeting", "summit", "workshop", "festival", "trip", "review"),
						List.of("wedding", "party", "concert", "holiday", "vacation", "exam", "appointment")),
				seed("thing", "A physical object.", null, List.of("object", "item"), List.of(),
						List.of("car", "vehicle", "bike", "bicycle", "motorcycle", "boat", "phone", "laptop",
								"computer", "printer", "device", "gadget", "machine", "instrument", "robot", "camera",
								"watch", "appliance")),
				seed("animal", "An animal: a pet, livestock, wildlife.", null, List.of("pet", "animals"), List.of(),
						List.of("dog", "cat", "horse", "pony", "bird", "rabbit", "hamster", "puppy", "kitten", "fish",
								"cow", "sheep", "goat", "chicken")),
				seed("domain", "A field of knowledge or activity.", null, List.of("field", "area"), List.of()));
	}

	private static EntityType seed(
		String name, String description, String parent, List<String> synonyms, List<String> typeWords) {
		return seed(name, description, parent, synonyms, typeWords, List.of());
	}

	private static EntityType seed(
		String name, String description, String parent, List<String> synonyms, List<String> typeWords,
		List<String> kinds) {
		return new EntityType(name, description, parent, synonyms, typeWords, kinds, null, true, false, false);
	}

	/** A seed kind whose members never overlap one another. */
	private static EntityType disjointSeed(
		String name, String description, String parent, List<String> synonyms, List<String> typeWords) {
		return new EntityType(name, description, parent, synonyms, typeWords, List.of(), null, true, false, true);
	}

	/** Whether two different things of the type (or of a kind it nests within) never overlap. */
	public synchronized boolean isDisjoint(String type) {
		for (String t : lineage(canonical(type))) {
			EntityType e = byName.get(t);
			if (e != null && e.disjoint()) {
				return true;
			}
		}
		return false;
	}

	// ── persistence ──────────────────────────────────────────────────────

	private void load() {
		for (Row r : db.read(tx -> tx.query("SELECT * FROM entity_type ORDER BY seed DESC, name"))) {
			String kinds = r.str("kinds");
			if (kinds == null && r.lng("seed") == 1) {
				kindsUnset.add(r.str("name"));
			}
			index(new EntityType(r.str("name"), r.str("description"), r.str("parent"),
					Vocabulary.list(r.str("synonyms")), Vocabulary.list(r.str("type_words")),
					kinds == null ? List.of() : Vocabulary.list(kinds), r.lngOrNull("defined_by"), r.lng("seed") == 1,
					r.lng("inferred") == 1, r.lng("disjoint") == 1));
		}
	}

	private void insert(EntityType e) {
		db.write(tx -> tx.insert("""
				INSERT INTO entity_type(name, description, parent, synonyms, type_words, kinds, defined_by, seed,
				                        inferred, created_at, disjoint) VALUES (?,?,?,?,?,?,?,?,?,?,?)""", e.name(),
				e.description(), e.parent(), Vocabulary.json(e.synonyms()), Vocabulary.json(e.typeWords()),
				Vocabulary.json(e.kinds()), e.definedBy(), e.seed() ? 1 : 0, e.inferred() ? 1 : 0,
				Instant.now().toString(), e.disjoint() ? 1 : 0));
		index(e);
	}

	/** Puts an entry into the maps, replacing what was there under its name. */
	private void index(EntityType e) {
		EntityType before = byName.put(e.name(), e);
		if (before != null) {
			before.synonyms().forEach(bySynonym::remove);
		}
		e.synonyms().forEach(s -> bySynonym.putIfAbsent(s, e.name()));
		typeWords.clear();
		byName.values().forEach(t -> typeWords.addAll(t.typeWords()));
	}
}
