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
import se.hirt.mnemic.proposal.Proposal.EventTypeDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which event types open and close which predicates, and how an event reads. Three effects:
 * <ul>
 * <li>{@code opens}: the event supplies a fact the proposal did not state ({@code purchased} → {@code owns}).</li>
 * <li>{@code supersedes}: a fact derived from the event replaces the other current values of a functional predicate
 * ({@code joined} → the previous {@code works_at} ends at the event time; EVALUATION.md C1).</li>
 * <li>{@code closes}: the event ends the fact whose subject and object both take part ({@code left(Mattias, Initrode)}
 * ends {@code works_at(Mattias, Initrode)}; C8), whether the event arrives before or after the fact.</li>
 * <li>{@code ends_entity}: the participant's open facts end and its {@code existed} interval closes ({@code died};
 * C11).</li>
 * </ul>
 * A type's {@code render} template turns the participants into a sentence ({@code {subject} bought {object}}); a type
 * without one renders as {@code type(participants)}. Seeded, extensible from a proposal or from an event's first use,
 * corrected through {@code correct}. The table is read once at start into a hash map and every write goes to the
 * database and the map in the same call.
 */
public final class EventTypeRegistry {

	public record EventType(String name, String description, List<String> opens, List<String> closes,
			List<String> supersedes, boolean endsEntity, boolean seed, List<String> lexicon, Long definedBy,
			String render, boolean inferred) {
	}

	/** A definition that states nothing beyond the name. */
	static boolean bare(EventTypeDef d) {
		return d.description() == null && d.opens().isEmpty() && d.closes().isEmpty() && d.supersedes().isEmpty()
				&& d.endsEntity() == null && d.lexicon().isEmpty() && d.render() == null;
	}

	private final Database db;
	private final Map<String, EventType> byName = new LinkedHashMap<>();

	public EventTypeRegistry(Database db) {
		this.db = db;
		load();
		for (EventType t : seed()) {
			if (!byName.containsKey(t.name())) {
				insert(t);
			}
		}
	}

	public synchronized Optional<EventType> get(String name) {
		return name == null ? Optional.empty()
				: Optional.ofNullable(byName.get(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_')));
	}

	public synchronized List<EventType> all() {
		return List.copyOf(byName.values());
	}

	/** Whether an event type opens, closes, or supersedes facts of the predicate. */
	public boolean touches(String eventType, String predicate) {
		return get(eventType).map(t -> t.opens().contains(predicate) || t.closes().contains(predicate)
				|| t.supersedes().contains(predicate)).orElse(false);
	}

	/** Whether a fact opened by an event of this type replaces the predicate's other current values. */
	public boolean supersedes(String eventType, String predicate) {
		return get(eventType).map(t -> t.supersedes().contains(predicate)).orElse(false);
	}

	/**
	 * The sentence for an event: the type's template with {@code {subject}} the first participant and {@code {object}}
	 * the others. A {@code [[ ... ]]} segment vanishes when there is no object ({@code {subject} was born[[ in
	 * {object}]]}); a template that names no object gets the others in parentheses. Without a template,
	 * {@code type(a, b)}.
	 */
	public synchronized String render(String type, List<String> participants) {
		EventType t = byName.get(type);
		String template = t == null ? null : t.render();
		if (template == null || template.isBlank()) {
			return type + "(" + String.join(", ", participants) + ")";
		}
		String subject = participants.isEmpty() ? "" : participants.getFirst();
		String object = participants.size() < 2 ? "" : String.join(", ", participants.subList(1, participants.size()));
		String out = OPTIONAL.matcher(template)
				.replaceAll(m -> object.isEmpty() ? "" : Matcher.quoteReplacement(m.group(1)));
		out = out.replace("{subject}", subject).replace("{object}", object);
		if (!object.isEmpty() && !template.contains("{object}")) {
			out += " (" + object + ")";
		}
		return out.replaceAll("\\s+", " ").trim();
	}

	private static final Pattern OPTIONAL = Pattern.compile("\\[\\[(.*?)]]");

	/**
	 * Registers a caller-defined type; an existing name is returned as it is. {@code registered} says which predicates
	 * exist, since a type may only open, close, or supersede those.
	 */
	public synchronized EventType register(
		EventTypeDef def, Long observationId, java.util.function.Predicate<String> registered) {
		if (def.name() == null || def.name().isBlank()) {
			throw MnemicException.invalidArgument("An event type definition needs a 'name'.");
		}
		String name = def.name().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		EventType existing = byName.get(name);
		if (existing != null) {
			if (!existing.inferred() || bare(def)) {
				return existing;
			}
			// A definition for a type registered from use: what it states replaces what was inferred.
			var replacement = new LinkedHashMap<String, Object>();
			if (def.description() != null) {
				replacement.put("description", def.description());
			}
			if (!def.opens().isEmpty()) {
				replacement.put("opens", def.opens());
			}
			if (!def.closes().isEmpty()) {
				replacement.put("closes", def.closes());
			}
			if (!def.supersedes().isEmpty()) {
				replacement.put("supersedes", def.supersedes());
			}
			if (def.endsEntity() != null) {
				replacement.put("ends_entity", def.endsEntity());
			}
			if (!def.lexicon().isEmpty()) {
				replacement.put("lexicon", def.lexicon());
			}
			if (def.render() != null && !def.render().isBlank()) {
				replacement.put("render", def.render());
			}
			return update(name, replacement, "defined after registration from use", registered);
		}
		for (String p : concat(def.opens(), def.closes(), def.supersedes())) {
			if (!registered.test(p)) {
				throw MnemicException.invalidArgument("Event type '" + name + "' names an unknown predicate '" + p
						+ "'; define it in 'predicates' first.");
			}
		}
		var t = new EventType(name, def.description(), def.opens(), def.closes(), def.supersedes(),
				Boolean.TRUE.equals(def.endsEntity()), false, lower(def.lexicon()), observationId,
				template(def.render()), false);
		insert(t);
		return t;
	}

	/**
	 * Registers a type from its first use: no effects, the name's words as lexicon, a template made from the name, and
	 * no description until somebody answers what it does. An existing name is returned as it is.
	 */
	public synchronized EventType registerInferred(String name, Long observationId) {
		String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (byName.containsKey(n)) {
			return byName.get(n);
		}
		var t = new EventType(n, null, List.of(), List.of(), List.of(), false, false, Predicate.lexiconOf(n),
				observationId, "{subject} " + n.replace('_', ' ') + " {object}", true);
		insert(t);
		return t;
	}

	/**
	 * Corrects {@code description}, {@code opens}, {@code closes}, {@code supersedes}, {@code ends_entity},
	 * {@code lexicon}, or {@code render}; every change is logged. The caller re-renders the type's events.
	 */
	public synchronized EventType update(
		String name, Map<String, Object> replacement, String reason, java.util.function.Predicate<String> registered) {
		EventType t = get(name).orElseThrow(() -> MnemicException.notFound("No event type " + name));
		String description = t.description();
		List<String> opens = t.opens();
		List<String> closes = t.closes();
		List<String> supersedes = t.supersedes();
		boolean endsEntity = t.endsEntity();
		List<String> lexicon = t.lexicon();
		String render = t.render();
		var changes = new ArrayList<String[]>();
		for (Map.Entry<String, Object> c : replacement.entrySet()) {
			String old;
			String value = Vocabulary.text(c.getValue());
			switch (c.getKey()) {
			case "description" -> {
				old = description;
				description = value;
			}
			case "opens" -> {
				old = Vocabulary.json(opens);
				opens = predicates(c.getValue(), registered);
			}
			case "closes" -> {
				old = Vocabulary.json(closes);
				closes = predicates(c.getValue(), registered);
			}
			case "supersedes" -> {
				old = Vocabulary.json(supersedes);
				supersedes = predicates(c.getValue(), registered);
			}
			case "ends_entity" -> {
				old = String.valueOf(endsEntity);
				endsEntity = Boolean.parseBoolean(value);
			}
			case "lexicon" -> {
				old = Vocabulary.json(lexicon);
				lexicon = lower(Vocabulary.strings(c.getValue()));
			}
			case "render" -> {
				old = render;
				render = template(value);
			}
			default -> throw MnemicException.invalidArgument("Unknown event type property '" + c.getKey()
					+ "'; correctable: description, opens, closes, supersedes, ends_entity, lexicon, render.");
			}
			changes.add(new String[] {c.getKey(), old, value});
		}
		var updated = new EventType(t.name(), description, opens, closes, supersedes, endsEntity, t.seed(), lexicon,
				t.definedBy(), render, false);
		db.write(tx -> {
			tx.update(
					"""
							UPDATE event_type SET description = ?, opens = ?, closes = ?, supersedes = ?, ends_entity = ?, lexicon = ?,
							                      render = ?, inferred = 0 WHERE name = ?""",
					updated.description(), Vocabulary.json(updated.opens()), Vocabulary.json(updated.closes()),
					Vocabulary.json(updated.supersedes()), updated.endsEntity() ? 1 : 0,
					Vocabulary.json(updated.lexicon()), updated.render(), updated.name());
			Vocabulary.logChanges(tx, "event_type", updated.name(), changes, reason);
			return null;
		});
		byName.put(updated.name(), updated);
		return updated;
	}

	public List<Map<String, Object>> changes(String name) {
		return db.read(tx -> Vocabulary.changes(tx, "event_type", name));
	}

	/** After two predicates merged: every type that opened, closed, or superseded the old name names the new one. */
	public synchronized void renamePredicate(String from, String into, String reason) {
		for (EventType t : List.copyOf(byName.values())) {
			var replacement = new LinkedHashMap<String, Object>();
			if (t.opens().contains(from)) {
				replacement.put("opens", rename(t.opens(), from, into));
			}
			if (t.closes().contains(from)) {
				replacement.put("closes", rename(t.closes(), from, into));
			}
			if (t.supersedes().contains(from)) {
				replacement.put("supersedes", rename(t.supersedes(), from, into));
			}
			if (!replacement.isEmpty()) {
				update(t.name(), replacement, reason, p -> true);
			}
		}
	}

	private static List<String> rename(List<String> names, String from, String into) {
		return names.stream().map(n -> n.equals(from) ? into : n).distinct().toList();
	}

	/** A template must name the subject, or an event would read the same whoever took part. */
	private static String template(String render) {
		if (render == null || render.isBlank()) {
			return null;
		}
		if (!render.contains("{subject}")) {
			throw MnemicException.invalidArgument(
					"An event type's render must contain {subject}, e.g. \"{subject} " + "inherited {object}\".");
		}
		return render.trim();
	}

	private static List<String> predicates(Object value, java.util.function.Predicate<String> registered) {
		List<String> names = Vocabulary.strings(value).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
		for (String p : names) {
			if (!registered.test(p)) {
				throw MnemicException.invalidArgument("'" + p + "' is not a registered predicate.");
			}
		}
		return names;
	}

	private static List<String> lower(List<String> words) {
		return words.stream().map(Names::norm).filter(w -> !w.isEmpty()).distinct().toList();
	}

	@SafeVarargs
	private static List<String> concat(List<String> ... lists) {
		var out = new ArrayList<String>();
		for (List<String> l : lists) {
			out.addAll(l);
		}
		return out;
	}

	/**
	 * The event type a question names, if any: a registered type through its lexicon ("buy" → purchased), an
	 * unregistered one through its own name, longest term first. {@code storedTypes} are the type names present in the
	 * store, so an event a caller recorded under a type nobody registered is still reachable.
	 */
	public synchronized Optional<String> cue(List<String> tokens, List<String> storedTypes) {
		String best = null;
		int bestLen = 0;
		for (EventType t : byName.values()) {
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
			// when a registered type with that lexicon term ("purchased") is one of its words.
			for (String word : Names.contentTokens(stored)) {
				for (String tok : tokens) {
					// "inherit" names the stored type "inherited": a prefix either way, four letters or more.
					boolean hit = tok.equals(word) || (tok.length() >= 4 && word.length() >= 4
							&& (word.startsWith(tok) || tok.startsWith(word)));
					if (!hit) {
						EventType reg = byName.get(word);
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

	// ── seed ─────────────────────────────────────────────────────────────

	static List<EventType> seed() {
		List<String> employment = List.of("works_at", "holds_role", "member_of");
		return List.of(
				type("joined", "Subject started at object organization.", List.of("works_at", "member_of"), List.of(),
						List.of("works_at"), List.of("joined", "join", "started"), "{subject} joined {object}"),
				type("hired", "Subject was hired by object organization.", List.of("works_at", "holds_role"), List.of(),
						List.of("works_at"), List.of("hired"), "{subject} was hired by {object}"),
				type("founded", "Subject founded object organization.", List.of("works_at", "owns"), List.of(),
						List.of("works_at"), List.of("founded", "found"), "{subject} founded {object}"),
				type("left", "Subject left object organization.", List.of(), employment, List.of(),
						List.of("left", "quit", "resigned"), "{subject} left {object}"),
				type("retired", "Subject retired from object organization.", List.of(), employment, List.of(),
						List.of("retired", "retire"), "{subject} retired from {object}"),
				type("promoted", "Subject took a new role at object organization.", List.of("holds_role"), List.of(),
						List.of("holds_role"), List.of("promoted", "promotion"), "{subject} was promoted at {object}"),
				type("moved", "Subject moved to object place.", List.of("lives_in"), List.of(), List.of("lives_in"),
						List.of("moved", "move", "relocated"), "{subject} moved to {object}"),
				type("married", "Subject married object.", List.of("spouse_of"), List.of(), List.of("spouse_of"),
						List.of("married", "marry", "wedding"), "{subject} married {object}"),
				type("divorced", "Subject and object divorced.", List.of(), List.of("spouse_of"), List.of(),
						List.of("divorced", "divorce"), "{subject} and {object} divorced"),
				type("born", "Subject was born in object place.", List.of("born_in"), List.of(), List.of(),
						List.of("born", "birth", "birthday"), "{subject} was born[[ in {object}]]"),
				type("decided", "Subject made a decision.", List.of("decided"), List.of(), List.of(),
						List.of("decided", "decide", "decision"), "{subject} decided"),
				type("met", "Subject met object.", List.of("knows"), List.of(), List.of(), List.of("met", "meet"),
						"{subject} met {object}"),
				type("purchased", "Subject bought object.", List.of("owns"), List.of(), List.of(),
						List.of("purchased", "purchase", "bought", "buy", "acquired", "acquire"),
						"{subject} bought {object}"),
				type("sold", "Subject sold object.", List.of(), List.of("owns"), List.of(),
						List.of("sold", "sell", "selling"), "{subject} sold {object}"),
				new EventType("died", "Subject died; open facts about the subject end.", List.of(), List.of(),
						List.of(), true, true, List.of("died", "death", "passed"), null, "{subject} died", false),
				new EventType("dissolved", "Subject organization ceased to exist.", List.of(), List.of(), List.of(),
						true, true, List.of("dissolved"), null, "{subject} was dissolved", false));
	}

	private static EventType type(
		String name, String description, List<String> opens, List<String> closes, List<String> supersedes,
		List<String> lexicon, String render) {
		return new EventType(name, description, opens, closes, supersedes, false, true, lexicon, null, render, false);
	}

	// ── persistence ──────────────────────────────────────────────────────

	private void load() {
		for (Row r : db.read(tx -> tx.query("SELECT * FROM event_type ORDER BY seed DESC, name"))) {
			byName.put(r.str("name"),
					new EventType(r.str("name"), r.str("description"), Vocabulary.list(r.str("opens")),
							Vocabulary.list(r.str("closes")), Vocabulary.list(r.str("supersedes")),
							r.lng("ends_entity") == 1, r.lng("seed") == 1, Vocabulary.list(r.str("lexicon")),
							r.lngOrNull("defined_by"), r.str("render"), r.lng("inferred") == 1));
		}
	}

	private void insert(EventType t) {
		db.write(tx -> tx.insert("""
				INSERT INTO event_type(name, description, opens, closes, supersedes, ends_entity, lexicon, defined_by,
				                       seed, render, inferred, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
				t.name(), t.description(), Vocabulary.json(t.opens()), Vocabulary.json(t.closes()),
				Vocabulary.json(t.supersedes()), t.endsEntity() ? 1 : 0, Vocabulary.json(t.lexicon()), t.definedBy(),
				t.seed() ? 1 : 0, t.render(), t.inferred() ? 1 : 0, Instant.now().toString()));
		byName.put(t.name(), t);
	}
}
