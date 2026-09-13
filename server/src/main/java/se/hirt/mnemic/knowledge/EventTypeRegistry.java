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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.*;

/**
 * Which event types open and close which predicates (DECISIONS.md §2.10). Three effects:
 * <ul>
 * <li>{@code supersedes}: a fact derived from the event replaces the other current values of a functional predicate
 * ({@code joined} → the previous {@code works_at} ends at the event time; EVALUATION.md C1).</li>
 * <li>{@code closes}: the event ends the fact whose subject and object both take part ({@code left(Mattias, Initrode)}
 * ends {@code works_at(Mattias, Initrode)}; C8), whether the event arrives before or after the fact.</li>
 * <li>{@code ends_entity}: the participant's open facts end and its {@code existed} interval closes ({@code died};
 * C11).</li>
 * </ul>
 * Unknown event types are stored as plain occurrences with no effect on facts.
 */
public final class EventTypeRegistry {

	public record EventType(String name, String description, List<String> opens, List<String> closes,
	                        List<String> supersedes, boolean endsEntity, boolean seed, List<String> lexicon) {
	}

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<String>> LIST = new TypeReference<>() {
	};

	private final Database db;
	private Map<String, EventType> cache;

	public EventTypeRegistry(Database db) {
		this.db = db;
		seedIfMissing();
	}

	public synchronized Optional<EventType> get(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(load().get(name.toLowerCase(Locale.ROOT)));
	}

	public synchronized List<EventType> all() {
		return List.copyOf(load().values());
	}

	static List<EventType> seed() {
		List<String> employment = List.of("works_at", "holds_role", "member_of");
		return List.of(
				type("joined", "Subject started at object organization.", List.of("works_at", "member_of"), List.of(),
						List.of("works_at"), List.of("joined", "join", "started")),
				type("hired", "Subject was hired by object organization.", List.of("works_at", "holds_role"), List.of(),
						List.of("works_at"), List.of("hired")),
				type("founded", "Subject founded object organization.", List.of("works_at", "owns"), List.of(),
						List.of("works_at"), List.of("founded", "found")),
				type("left", "Subject left object organization.", List.of(), employment, List.of(),
						List.of("left", "quit", "resigned")),
				type("retired", "Subject retired from object organization.", List.of(), employment, List.of(),
						List.of("retired", "retire")),
				type("promoted", "Subject took a new role at object organization.", List.of("holds_role"), List.of(),
						List.of("holds_role"), List.of("promoted", "promotion")),
				type("moved", "Subject moved to object place.", List.of("lives_in"), List.of(), List.of("lives_in"),
						List.of("moved", "move", "relocated")),
				type("married", "Subject married object.", List.of("spouse_of"), List.of(), List.of("spouse_of"),
						List.of("married", "marry", "wedding")),
				type("divorced", "Subject and object divorced.", List.of(), List.of("spouse_of"), List.of(),
						List.of("divorced", "divorce")),
				type("born", "Subject was born in object place.", List.of("born_in"), List.of(), List.of(),
						List.of("born", "birth", "birthday")),
				type("decided", "Subject made a decision.", List.of("decided"), List.of(), List.of(),
						List.of("decided", "decide", "decision")),
				type("met", "Subject met object.", List.of("knows"), List.of(), List.of(), List.of("met", "meet")),
				type("purchased", "Subject bought object.", List.of("owns"), List.of(), List.of(),
						List.of("purchased", "purchase", "bought", "buy", "acquired", "acquire")),
				type("sold", "Subject sold object.", List.of(), List.of("owns"), List.of(),
						List.of("sold", "sell", "selling")),
				new EventType("died", "Subject died; open facts about the subject end.", List.of(), List.of(),
						List.of(), true, true, List.of("died", "death", "passed")),
				new EventType("dissolved", "Subject organization ceased to exist.", List.of(), List.of(), List.of(),
						true, true, List.of("dissolved")));
	}

	private static EventType type(
			String name, String description, List<String> opens, List<String> closes,
			List<String> supersedes, List<String> lexicon) {
		return new EventType(name, description, opens, closes, supersedes, false, true, lexicon);
	}

	/**
	 * The event type a question names, if any: a registered type through its lexicon ("buy" → purchased), an
	 * unregistered one through its own name, longest term first. {@code storedTypes} are the type names present
	 * in the store, so an event a caller recorded under a type nobody registered is still reachable.
	 */
	public synchronized Optional<String> cue(List<String> tokens, List<String> storedTypes) {
		String best = null;
		int bestLen = 0;
		for (EventType t : load().values()) {
			for (String term : t.lexicon()) {
				if (tokens.contains(Names.norm(term)) && term.length() > bestLen) {
					best = t.name();
					bestLen = term.length();
				}
			}
			if (tokens.contains(t.name()) && t.name().length() > bestLen) {
				best = t.name();
				bestLen = t.name().length();
			}
		}
		for (String stored : storedTypes) {
			// A stored type is matched word by word: "purchased property" is named by "purchased", and by "buy"
			// when a registered type with that lexicon term ("purchased") is one of its words (2026-09-10).
			for (String word : Names.contentTokens(stored)) {
				for (String tok : tokens) {
					// "inherit" names the stored type "inherited": a prefix either way, four letters or more.
					boolean hit = tok.equals(word) || (tok.length() >= 4 && word.length() >= 4
							&& (word.startsWith(tok) || tok.startsWith(word)));
					if (!hit) {
						EventType reg = load().get(word);
						hit = reg != null && reg.lexicon().stream().anyMatch(term -> Names.norm(term).equals(tok));
					}
					if (hit && word.length() > bestLen) {
						best = stored;
						bestLen = word.length();
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private void seedIfMissing() {
		Map<String, EventType> existing = load();
		for (EventType t : seed()) {
			if (!existing.containsKey(t.name())) {
				db.write(tx -> tx.insert("INSERT INTO event_type(name, description, opens, closes, supersedes, ends_entity, "
						+ "lexicon, seed, created_at) VALUES (?,?,?,?,?,?,?,1,?)", t.name(), t.description(), json(t.opens()),
						json(t.closes()), json(t.supersedes()), t.endsEntity() ? 1 : 0, json(t.lexicon()),
						Instant.now().toString()));
			}
		}
		cache = null;
	}

	private Map<String, EventType> load() {
		if (cache == null) {
			var map = new LinkedHashMap<String, EventType>();
			for (Row r : db.read(tx -> tx.query("SELECT * FROM event_type ORDER BY name"))) {
				map.put(r.str("name"),
						new EventType(r.str("name"), r.str("description"), list(r.str("opens")), list(r.str("closes")),
								list(r.str("supersedes")), r.lng("ends_entity") == 1, r.lng("seed") == 1,
								list(r.str("lexicon"))));
			}
			cache = map;
		}
		return cache;
	}

	private static String json(List<String> l) {
		try {
			return JSON.writeValueAsString(l);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}

	private static List<String> list(String json) {
		try {
			return json == null ? List.of() : JSON.readValue(json, LIST);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}
}
