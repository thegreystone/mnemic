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
 * registered passes through unchanged, so nothing is refused for its type; once a type is registered, entities that
 * were typed by one of its synonyms take its name.
 * <p>
 * The table is read once at start into hash maps (by name, by synonym, the set of type words); every write goes to the
 * database and into the maps in the same call, so lookups never touch the database and the next start sees everything
 * this one registered.
 */
public final class EntityTypeRegistry {

	public record EntityType(String name, String description, String parent, List<String> synonyms,
			List<String> typeWords, Long definedBy, boolean seed) {
	}

	public static final String UNKNOWN = "unknown";

	private final Database db;
	private final Map<String, EntityType> byName = new LinkedHashMap<>();
	private final Map<String, String> bySynonym = new HashMap<>();
	private final Set<String> typeWords = new HashSet<>();

	public EntityTypeRegistry(Database db) {
		this.db = db;
		load();
		for (EntityType e : seed()) {
			if (!byName.containsKey(e.name())) {
				insert(e);
			}
		}
	}

	public synchronized List<EntityType> all() {
		return List.copyOf(byName.values());
	}

	public synchronized Optional<EntityType> get(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(byName.get(Names.norm(name)));
	}

	/** The registered name for a proposed type, through its synonyms; unknown types pass through lowercased. */
	public synchronized String canonical(String type) {
		if (type == null || type.isBlank()) {
			return UNKNOWN;
		}
		String t = Names.norm(type);
		return byName.containsKey(t) ? t : bySynonym.getOrDefault(t, t);
	}

	/** The type and its ancestors, nearest first: {@code country, place}. */
	public synchronized List<String> lineage(String type) {
		var out = new ArrayList<String>();
		String at = type == null ? UNKNOWN : type;
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

	/** Registers a caller-defined type; an existing name is returned as it is. */
	public synchronized EntityType register(EntityTypeDef def, Long observationId) {
		if (def.name() == null || def.name().isBlank()) {
			throw MnemicException.invalidArgument("An entity type definition needs a 'name'.");
		}
		String name = Names.norm(def.name()).replace(' ', '_');
		if (byName.containsKey(name)) {
			return byName.get(name);
		}
		String parent = def.parent() == null || def.parent().isBlank() ? null : canonical(def.parent());
		if (parent != null && !byName.containsKey(parent)) {
			throw MnemicException.invalidArgument("Entity type '" + name + "' names an unknown parent '" + def.parent()
					+ "'; register the parent first or leave it out.");
		}
		var e = new EntityType(name, def.description(), parent, lower(def.synonyms()), lower(def.typeWords()),
				observationId, false);
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
	 * Corrects {@code description}, {@code parent}, {@code synonyms}, or {@code type_words}; every change is logged.
	 */
	public synchronized EntityType update(String name, Map<String, Object> replacement, String reason) {
		EntityType e = get(name).orElseThrow(() -> MnemicException.notFound("No entity type " + name));
		String description = e.description();
		String parent = e.parent();
		List<String> synonyms = e.synonyms();
		List<String> words = e.typeWords();
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
			default -> throw MnemicException.invalidArgument("Unknown entity type property '" + c.getKey()
					+ "'; correctable: description, parent, synonyms, type_words.");
			}
			changes.add(new String[] {c.getKey(), old, value});
		}
		var updated = new EntityType(e.name(), description, parent, synonyms, words, e.definedBy(), e.seed());
		db.write(tx -> {
			tx.update("UPDATE entity_type SET description = ?, parent = ?, synonyms = ?, type_words = ? WHERE name = ?",
					updated.description(), updated.parent(), Vocabulary.json(updated.synonyms()),
					Vocabulary.json(updated.typeWords()), updated.name());
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
								"stiftung", "forening")),
				seed("place", "A town, region, address, or other location.", null,
						List.of("city", "town", "region", "location", "village"),
						List.of("kanton", "canton", "county", "province", "region", "state", "district", "lake",
								"mount", "mountain", "river", "island", "city", "town", "village", "municipality",
								"kommun", "gemeinde", "stadt", "bezirk", "landkreis", "lan", "sjo", "berg", "see")),
				seed("country", "A country; two different countries never overlap.", "place", List.of("nation"),
						List.of()),
				seed("project", "A project or initiative.", null, List.of(),
						List.of("project", "projekt", "initiative", "program", "programme")),
				seed("team", "A team or group of people.", null, List.of("group"),
						List.of("team", "group", "squad", "unit", "department")),
				seed("product", "A product.", null, List.of(), List.of()),
				seed("technology", "A tool, library, language, or platform.", null,
						List.of("tool", "library", "language", "framework", "software", "database", "platform"),
						List.of()),
				seed("event", "A conference, meeting, trip, or other occasion.", null, List.of(),
						List.of("conference", "meeting", "summit", "workshop", "festival", "trip", "review")),
				seed("thing", "A physical object.", null, List.of("object", "item"), List.of()),
				seed("domain", "A field of knowledge or activity.", null, List.of("field", "area"), List.of()));
	}

	private static EntityType seed(
		String name, String description, String parent, List<String> synonyms, List<String> typeWords) {
		return new EntityType(name, description, parent, synonyms, typeWords, null, true);
	}

	// ── persistence ──────────────────────────────────────────────────────

	private void load() {
		for (Row r : db.read(tx -> tx.query("SELECT * FROM entity_type ORDER BY seed DESC, name"))) {
			index(new EntityType(r.str("name"), r.str("description"), r.str("parent"),
					Vocabulary.list(r.str("synonyms")), Vocabulary.list(r.str("type_words")), r.lngOrNull("defined_by"),
					r.lng("seed") == 1));
		}
	}

	private void insert(EntityType e) {
		db.write(tx -> tx.insert("""
				INSERT INTO entity_type(name, description, parent, synonyms, type_words, defined_by, seed, created_at)
				VALUES (?,?,?,?,?,?,?,?)""", e.name(), e.description(), e.parent(), Vocabulary.json(e.synonyms()),
				Vocabulary.json(e.typeWords()), e.definedBy(), e.seed() ? 1 : 0, Instant.now().toString()));
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
