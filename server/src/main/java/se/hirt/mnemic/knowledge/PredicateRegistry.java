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
import se.hirt.mnemic.embed.Embedding;
import se.hirt.mnemic.knowledge.PredicateCueMatcher.Nearest;
import se.hirt.mnemic.knowledge.PredicateCueMatcher.Similar;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * The predicate registry: SQL persistence and cache lifecycle for predicates, resolution of proposed names to existing
 * predicates, rule/implication validation, and corrections with a change log (EVALUATION.md J5). Seed definitions live
 * in {@link PredicateSeeds} ({@code knowledge/seed-predicates.json}), group hierarchy and CRUD in
 * {@link PredicateGroups}, and lexical/semantic cue matching in {@link PredicateCueMatcher}.
 */
public final class PredicateRegistry {

	/**
	 * How a proposed predicate resolved. {@code predicate} is null and {@code candidate} set when the match is
	 * plausible but not confident (EVALUATION.md J3): the caller decides.
	 */
	public record Resolution(Predicate predicate, String how, Predicate candidate, List<Predicate> alternatives) {
		public Resolution(Predicate predicate, String how, Predicate candidate) {
			this(predicate, how, candidate, List.of());
		}

		public boolean ambiguous() {
			return "ambiguous".equals(how);
		}

		/** The caller must decide: a similar or an ambiguous match (J2, J3). */
		public boolean asks() {
			return predicate == null && candidate != null;
		}
	}

	/**
	 * A predicate cue found in a query, with the qualifier when the trigger term was one ("mother"). {@code direction}
	 * is the side of the predicate the spotted entity is on: {@code object} for a qualifier or a lexicon term of a
	 * same-type relation ("Mattias's father": the term names the subject, so the entity is the object), {@code subject}
	 * for an inverse term ("Mattias's children"), {@code any} otherwise, in which case the probe decides by the
	 * entity's type against domain and range.
	 */
	public record Cue(Predicate predicate, String qualifier, String term, String direction, String group) {
		/** A cue on the predicate's own vocabulary. */
		public Cue(Predicate predicate, String qualifier, String term, String direction) {
			this(predicate, qualifier, term, direction, null);
		}

		/** Whether the cue reached the predicate through a group word ("family"), not its own vocabulary. */
		public boolean viaGroup() {
			return group != null;
		}
	}

	/**
	 * A group of predicates a question names with one word: "family" for the kinship predicates. Its cue words are the
	 * words of its name until a description or correction adds more; in a store of another language the group's words
	 * in that language join them.
	 */
	public record Group(String name, String description, List<String> lexicon, List<String> groups, boolean seed) {
		/** The group as a reply shows it: its description, cue words, and the groups it belongs to. */
		public Map<String, Object> toMap() {
			var m = new LinkedHashMap<String, Object>();
			m.put("description", description);
			m.put("lexicon", lexicon);
			m.put("groups", groups);
			return m;
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<String>> LIST = new TypeReference<>() {
	};

	private final Database db;
	private final Lang lang;
	private final EntityTypeRegistry entityTypes;
	private final PredicateCueMatcher matcher;
	private final PredicateGroups groups;
	/** The negation templates of the language, by predicate; empty for the base language. */
	private final Map<String, String> negated = new HashMap<>();
	private Map<String, Predicate> cache;
	private long dataVersion = -1;
	/** The rules of the derived predicates, loaded with the cache. */
	private Map<String, List<Rule>> rules = Map.of();
	/** What each predicate's terms imply about the subject's attributes, loaded with the cache. */
	private Map<String, Map<String, Map<String, String>>> implies = Map.of();
	/** What each predicate ends: the predicates whose open facts a new fact of it closes (decided ends considering). */
	private Map<String, List<String>> ends = Map.of();
	/** Seed predicates that took over a same-named predicate registered from use at this start. */
	private final List<String> adopted = new ArrayList<>();

	/** {@code lang}: the store's language; its templates and cue words are loaded over the base (English) ones. */
	public PredicateRegistry(Database db, Lang lang, EntityTypeRegistry entityTypes) {
		this(db, lang, entityTypes, () -> null);
	}

	public PredicateRegistry(Database db, Lang lang, EntityTypeRegistry entityTypes, Supplier<Embedding> embedding) {
		this.db = db;
		this.entityTypes = entityTypes;
		this.lang = lang;
		this.matcher = new PredicateCueMatcher(db, embedding);
		this.groups = new PredicateGroups(db);
		seedIfMissing();
		seedRendersIfMissing();
		groups.seedIfMissing(this::load, this::invalidate);
		seedLastingIfMissing();
		fillLexicons();
	}

	private void invalidate() {
		cache = null;
	}

	/** A predicate from before names supplied their own words (a 2.2 {@code x:} predicate) gets them now. */
	private void fillLexicons() {
		for (Predicate p : List.copyOf(load().values())) {
			if (!p.seed() && p.lexicon().isEmpty()) {
				List<String> words = Predicate.lexiconOf(p.name());
				if (!words.isEmpty()) {
					db.write(tx -> tx.update("UPDATE predicate SET lexicon = ? WHERE name = ?", json(words), p.name()));
					cache = null;
				}
			}
		}
	}

	public Lang lang() {
		return lang;
	}

	/** The language's negation template for a predicate, or null for the generic rule. */
	public String negatedTemplate(String predicate) {
		return db.withLock(() -> {
			load();
			return negated.get(predicate);
		});
	}

	/**
	 * Seeds the per-language rendering templates and cue words from {@link LanguagePack} for every language that
	 * defines them. Stored in {@code predicate_render}, not compiled in, so a user can correct them like any other
	 * template (correct on the predicate).
	 */
	private void seedRendersIfMissing() {
		db.write(tx -> {
			for (LanguagePack pack : LanguagePack.all()) {
				for (LanguagePack.PredicateRender r : pack.predicateRenders()) {
					tx.update(
							"""
									INSERT OR IGNORE INTO predicate_render(name, language, render, negated, lexicon, inverse_lexicon)
									SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM predicate WHERE name = ?)""",
							r.name(), pack.code(), r.render(), r.negated(), json(r.lexicon()), json(r.inverseLexicon()),
							r.name());
				}
			}
			return null;
		});
		cache = null;
	}

	/**
	 * Records a caller-supplied template for a language: {@code spec} is the template, or an object with
	 * {@code render}, {@code negated}, {@code lexicon}, and {@code inverse_lexicon}. Applied when the store is in that
	 * language.
	 */
	public void putRender(String predicate, String language, Object spec) {
		db.withLock(() -> {
			String render = spec instanceof Map<?, ?> m ? text(m.get("render")) : text(spec);
			if (render == null || render.isBlank()) {
				throw MnemicException.invalidArgument("renders." + language + " needs a 'render' template, e.g. "
						+ "\"{subject} betreut {object}\".");
			}
			String neg = spec instanceof Map<?, ?> m ? text(m.get("negated")) : null;
			List<String> lexicon = spec instanceof Map<?, ?> m && m.get("lexicon") != null ? strings(m.get("lexicon"))
					: List.of();
			List<String> inverse = spec instanceof Map<?, ?> m && m.get("inverse_lexicon") != null
					? strings(m.get("inverse_lexicon")) : List.of();
			db.write(tx -> tx.update("""
					INSERT OR REPLACE INTO predicate_render(name, language, render, negated, lexicon, inverse_lexicon)
					VALUES (?,?,?,?,?,?)""", predicate, language, render, neg, json(lexicon), json(inverse)));
			cache = null;
		});
	}

	private void putRenders(String predicate, Map<String, Object> renders) {
		for (Map.Entry<String, Object> r : renders.entrySet()) {
			putRender(predicate, language(r.getKey()), r.getValue());
		}
	}

	/** The template on record for a language, or null. */
	private String renderIn(String predicate, String language) {
		return db.read(tx -> tx
				.queryOne("SELECT render FROM predicate_render WHERE name = ? AND language = ?", predicate, language)
				.map(r -> r.str("render")).orElse(null));
	}

	private static String language(String code) {
		try {
			return Lang.of(code).code();
		} catch (IllegalArgumentException e) {
			throw MnemicException.invalidArgument(e.getMessage());
		}
	}

	private static String text(Object o) {
		return o == null ? null : String.valueOf(o);
	}

	// ── read ─────────────────────────────────────────────────────────────

	/** Reads the table again, after a change made beside the registry. */
	public void reload() {
		db.withLock(() -> {
			cache = null;
			matcher.clearVectors();
		});
	}

	public List<Predicate> all() {
		return db.withLock(() -> List.copyOf(load().values()));
	}

	/**
	 * What a predicate's qualifiers or literal values imply about the subject's attributes: term → attribute → value
	 * (parent_of: mother → gender female; gender: woman → gender female). Empty when it declares none.
	 */
	public Map<String, Map<String, String>> impliesOf(String name) {
		return db.withLock(() -> {
			load();
			return implies.getOrDefault(name, Map.of());
		});
	}

	/**
	 * The predicates a new fact of this one ends, for the same subject and object: {@code decided} ends
	 * {@code considering}. Empty when it ends nothing.
	 */
	public List<String> endsOf(String name) {
		return db.withLock(() -> {
			load();
			return ends.getOrDefault(name, List.of());
		});
	}

	/**
	 * Parses an {@code implies} value: an object from a qualifier or literal value to the attributes it fixes, each
	 * attribute a registered predicate. Terms and values are lowercased.
	 */
	public Map<String, Map<String, String>> parseImplies(String name, Object implies) {
		return db.withLock(() -> {
			if (implies == null) {
				return Map.of();
			}
			if (!(implies instanceof Map<?, ?> m)) {
				throw MnemicException
						.invalidArgument("'implies' takes an object from a qualifier or value to the attributes "
								+ "it fixes, e.g. {\"mother\": {\"gender\": \"female\"}}.");
			}
			var out = new LinkedHashMap<String, Map<String, String>>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				String term = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
				if (!(e.getValue() instanceof Map<?, ?> assigns) || assigns.isEmpty()) {
					throw MnemicException.invalidArgument("'implies' for '" + term + "' on '" + name
							+ "' takes an object of attribute predicate to value, e.g. {\"gender\": \"female\"}.");
				}
				var a = new LinkedHashMap<String, String>();
				for (Map.Entry<?, ?> x : assigns.entrySet()) {
					String attribute = String.valueOf(x.getKey()).trim().toLowerCase(Locale.ROOT);
					if (!load().containsKey(attribute)) {
						throw MnemicException.invalidArgument(
								"'implies' on '" + name + "' names unknown attribute predicate '" + attribute
										+ "': register it first (a predicate whose facts give a literal value).");
					}
					a.put(attribute, String.valueOf(x.getValue()).trim().toLowerCase(Locale.ROOT));
				}
				out.put(term, a);
			}
			return out;
		});
	}

	private static String impliesJson(Map<String, Map<String, String>> implies) {
		try {
			return implies.isEmpty() ? null : JSON.writeValueAsString(implies);
		} catch (Exception e) {
			throw MnemicException.internal("implies json", e);
		}
	}

	private static Map<String, Map<String, String>> impliesFromJson(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.readValue(json, new TypeReference<Map<String, Map<String, String>>>() {
			});
		} catch (Exception e) {
			throw MnemicException.internal("implies json", e);
		}
	}

	private void setImplies(String name, Map<String, Map<String, String>> parsed) {
		db.write(tx -> tx.update("UPDATE predicate SET implies = ? WHERE name = ?", impliesJson(parsed), name));
		cache = null;
	}

	/** The predicates whose facts nest the subject inside the object: the chains bounds and closures are checked on. */
	public List<String> containmentPredicates() {
		return db.withLock(() -> load().values().stream().filter(Predicate::containment).map(Predicate::name).toList());
	}

	public boolean isContainment(String name) {
		return db.withLock(() -> {
			Predicate p = load().get(name);
			return p != null && p.containment();
		});
	}

	/**
	 * Whether a thing of a type (given as its lineage) is one things lie within: some containment predicate names its
	 * kind as its range. A containment predicate whose range is anything (part_of) nests what it is given but declares
	 * no kind a container, so a domain name or a printer is not asked where it lies.
	 */
	public boolean canContain(List<String> lineage) {
		return db.withLock(() -> load().values().stream()
				.anyMatch(p -> p.containment() && !p.range().contains("*") && p.acceptsObject(lineage)));
	}

	/** The containment predicate that nests a thing of one kind inside a thing of another, or the first there is. */
	public Optional<Predicate> containmentFor(List<String> inner, List<String> outer) {
		return db.withLock(() -> {
			List<Predicate> within = load().values().stream().filter(Predicate::containment).toList();
			return within.stream()
					.filter(p -> !p.domain().contains("*") && !p.range().contains("*") && p.acceptsSubject(inner)
							&& p.acceptsObject(outer))
					.findFirst().or(() -> within.stream().filter(p -> p.acceptsSubject(inner) && p.acceptsObject(outer))
							.findFirst())
					.or(() -> within.stream().findFirst());
		});
	}

	/** The rules that define a derived predicate (family K); none for an asserted one. */
	public List<Rule> rulesOf(String name) {
		return db.withLock(() -> {
			load();
			return rules.getOrDefault(name, List.of());
		});
	}

	/** The predicates that are derived: defined by rules over other predicates. */
	public List<Predicate> derived() {
		return db.withLock(() -> load().values().stream().filter(p -> !rulesOf(p.name()).isEmpty()).toList());
	}

	/**
	 * Checks rules before they are stored (K9, K10): every hop names a registered predicate, no hop names the predicate
	 * itself or one that rests on it, each hop's range fits the next one's domain, and no hop ends in a literal, which
	 * cannot be walked.
	 */
	private void validateRules(String name, List<Rule> rules) {
		for (Rule rule : rules) {
			if (rule.attribute() != null && !load().containsKey(rule.attribute())) {
				throw MnemicException.invalidArgument("a rule for '" + name + "' chooses its qualifier by unknown "
						+ "attribute predicate '" + rule.attribute() + "': register it first.");
			}
			Predicate prev = null;
			boolean prevInverse = false;
			for (Rule.Hop h : rule.path()) {
				if (h.predicate().equals(name)) {
					throw MnemicException.invalidArgument("a rule for '" + name + "' names itself: a derived predicate "
							+ "may not be recursive; bound the repetition instead, e.g. parent_of{1,4}.");
				}
				Predicate p = load().get(h.predicate());
				if (p == null) {
					throw MnemicException.invalidArgument(
							"a rule for '" + name + "' walks unknown predicate '" + h.predicate() + "'.");
				}
				if (restsOn(h.predicate(), name, new HashSet<>())) {
					throw MnemicException.invalidArgument("a rule for '" + name + "' walks '" + h.predicate()
							+ "', which rests on '" + name + "': derived predicates may not be recursive.");
				}
				if (prev != null) {
					List<String> out = prevInverse ? prev.domain() : prev.range();
					List<String> in = h.inverse() ? p.range() : p.domain();
					if (!compatible(out, in)) {
						throw MnemicException.invalidArgument("a rule for '" + name + "' cannot walk '" + prev.name()
								+ "' then '" + h.predicate() + "': the " + (prevInverse ? "domain" : "range") + " of '"
								+ prev.name() + "' (" + String.join("/", out) + ") does not fit the "
								+ (h.inverse() ? "range" : "domain") + " of '" + h.predicate() + "' ("
								+ String.join("/", in) + ").");
					}
				}
				if ((h.inverse() ? p.domain() : p.range()).contains("literal")) {
					throw MnemicException.invalidArgument("a rule for '" + name + "' walks '" + h + "', which ends "
							+ "in a literal rather than an entity and cannot be walked further.");
				}
				prev = p;
				prevInverse = h.inverse();
			}
			for (String n : rule.not()) {
				String bare = n.startsWith("^") ? n.substring(1) : n;
				if (!load().containsKey(bare)) {
					throw MnemicException
							.invalidArgument("a rule for '" + name + "' excludes unknown predicate '" + bare + "'.");
				}
			}
		}
	}

	private boolean restsOn(String predicate, String target, Set<String> seen) {
		if (!seen.add(predicate)) {
			return false;
		}
		for (Rule r : rulesOf(predicate)) {
			for (Rule.Hop h : r.path()) {
				if (h.predicate().equals(target) || restsOn(h.predicate(), target, seen)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether a thing of some type in {@code out} can be a thing of some type in {@code in}. */
	private boolean compatible(List<String> out, List<String> in) {
		if (out.contains("literal") || in.contains("literal")) {
			return false;
		}
		if (out.contains("*") || in.contains("*")) {
			return true;
		}
		for (String a : out) {
			for (String b : in) {
				if (a.equals(b) || entityTypes.isA(a, b) || entityTypes.isA(b, a)) {
					return true;
				}
			}
		}
		return false;
	}

	private void setRules(String name, List<Rule> parsed) {
		db.write(tx -> tx.update("UPDATE predicate SET rule = ? WHERE name = ?",
				parsed.isEmpty() ? null : Rule.toJson(parsed), name));
		cache = null;
	}

	public Optional<Predicate> get(String name) {
		return db.withLock(() -> {
			if (name == null) {
				return Optional.empty();
			}
			Map<String, Predicate> all = load();
			Predicate p = all.get(name);
			if (p != null) {
				return Optional.of(p);
			}
			String n = Names.norm(name);
			for (Predicate q : all.values()) {
				if (q.aliases().stream().anyMatch(a -> Names.norm(a).equals(n))) {
					return Optional.of(q);
				}
			}
			return Optional.empty();
		});
	}

	/**
	 * Resolves a proposed predicate name, with an optional definition: exact name, alias, a similar existing predicate
	 * (same domain/range, at least two content tokens shared across name, description, and lexicon, J2), an ambiguous
	 * one (exactly one token shared, J3), or a registration.
	 */
	public Resolution resolve(String name, PredicateDef def, Long observationId, List<String> warnings) {
		return db.withLock(() -> {
			if (name == null || name.isBlank()) {
				throw MnemicException
						.invalidArgument("A fact needs a 'predicate'. Example: \"predicate\": \"works_at\"");
			}
			String target = name.startsWith("x:") ? name.substring(2) : name;
			Optional<Predicate> exact = get(target);
			if (exact.isPresent()) {
				return new Resolution(exact.get(), target.equals(exact.get().name()) ? "exact" : "alias", null);
			}
			PredicateDef resolvedDef = def != null ? def : new PredicateDef(target, null, null, null, null, null, null,
					null, null, List.of(), null, List.of(), List.of());
			List<String> domain = types(resolvedDef.domain());
			List<String> range = types(resolvedDef.range());
			Collection<Predicate> predicates = load().values();
			Similar similar = PredicateCueMatcher.similar(resolvedDef, domain, range, predicates);
			Nearest near = matcher.nearest(PredicateCueMatcher.embeddingText(resolvedDef), domain, range, null,
					predicates);
			if (similar != null && (similar.overlap() >= PredicateCueMatcher.SIMILAR_MIN_OVERLAP
					|| resolvedDef.description() == null)) {
				return new Resolution(null, "similar", similar.predicate(),
						PredicateCueMatcher.closest(similar, near, similar.predicate()));
			}
			boolean described = resolvedDef.description() != null && !resolvedDef.description().isBlank();
			if (similar != null && similar.overlap() == 1 && !described) {
				return new Resolution(null, "ambiguous", similar.predicate(),
						PredicateCueMatcher.closest(similar, near, similar.predicate()));
			}
			if (near != null && near.similar()) {
				return new Resolution(null, "semantic", near.predicate(),
						PredicateCueMatcher.closest(similar, near, near.predicate()));
			}
			if (near != null && near.ambiguous() && !described) {
				return new Resolution(null, "ambiguous", near.predicate(),
						PredicateCueMatcher.closest(similar, near, near.predicate()));
			}
			Predicate neighbour = similar != null && similar.overlap() == 1 ? similar.predicate()
					: near != null && near.ambiguous() ? near.predicate() : null;
			return new Resolution(register(resolvedDef, observationId), bare(resolvedDef) ? "inferred" : "registered",
					neighbour);
		});
	}

	/** Whether a definition states anything beyond its name, rules, and implications. */
	static boolean statesMoreThanAdditions(PredicateDef d) {
		return d.description() != null || d.domain() != null || d.range() != null || d.functional() != null
				|| d.functionalScope() != null || d.symmetric() != null || d.inverse() != null || d.volatility() != null
				|| !d.lexicon().isEmpty() || d.render() != null || !d.qualifiers().isEmpty() || !d.aliases().isEmpty()
				|| !d.renders().isEmpty() || d.containment() != null || d.groups() != null || d.lasting() != null
				|| !d.inverseLexicon().isEmpty();
	}

	/** A definition that states nothing beyond the name. */
	static boolean bare(PredicateDef d) {
		return d.description() == null && d.domain() == null && d.range() == null && d.functional() == null
				&& d.functionalScope() == null && d.symmetric() == null && d.inverse() == null && d.volatility() == null
				&& d.lexicon().isEmpty() && d.render() == null && d.qualifiers().isEmpty() && d.aliases().isEmpty()
				&& d.renders().isEmpty() && d.definedAs() == null && d.implies() == null && d.containment() == null
				&& d.groups() == null && d.lasting() == null && d.inverseLexicon().isEmpty();
	}

	// ── cues & meaning (delegated to PredicateCueMatcher) ────────────────

	static String embeddingText(Predicate p) {
		return PredicateCueMatcher.embeddingText(p);
	}

	/**
	 * Predicates registered from use that lie close in meaning to another predicate, each pair once:
	 * {@code {predicate, close_to, score}}. Empty without a model.
	 */
	public List<Map<String, Object>> closePairs() {
		return db.withLock(() -> matcher.closePairs(load().values()));
	}

	/** Predicate cues in a query: lexicon, qualifier, group, and semantic cues, longest first. */
	public List<Cue> cues(String query) {
		return cues(query, Set.of());
	}

	/**
	 * The cues of a question whose entity names are already known: {@code excluded} holds the tokens of the entities
	 * spotted in it, which no phrase compared by meaning may contain.
	 */
	public List<Cue> cues(String query, Set<String> excluded) {
		return db.withLock(() -> {
			Collection<Predicate> predicates = load().values();
			return matcher.cues(query, excluded, predicates, groups.snapshot().values(),
					name -> groups.membersOf(name, predicates, this::rulesOf));
		});
	}

	/** Whether a phrase's nearest predicate is a real match, by its score and its lead over the next one. */
	public static boolean semanticCue(float score, float lead) {
		return PredicateCueMatcher.semanticCue(score, lead);
	}

	/** Whether a predicate's own vocabulary (qualifiers, lexicon) names a role, whole or by one of its words. */
	public static boolean namesRole(Predicate p, String role) {
		return PredicateCueMatcher.namesRole(p, role);
	}

	/**
	 * The predicate a free-text role belongs to (partner → partner_of, aunt → aunt_uncle_of): one with a vocabulary of
	 * its own that names the role, the whole role before a word of it. Empty when none does.
	 */
	public Optional<Predicate> dedicatedFor(String role) {
		return db.withLock(() -> PredicateCueMatcher.dedicatedFor(role, load().values()));
	}

	/** Predicates registered from use and not yet described, with the facts that use them (J6). */
	public List<Map<String, Object>> inferred() {
		return db.withLock(() -> {
			var out = new ArrayList<Map<String, Object>>();
			for (Predicate p : load().values()) {
				if (!p.isInferred()) {
					continue;
				}
				List<Row> rows = db.read(tx -> tx.query(
						"SELECT COUNT(*) AS n, GROUP_CONCAT(DISTINCT observation_id) AS obs FROM fact WHERE predicate = ?",
						p.name()));
				var m = new LinkedHashMap<String, Object>();
				m.put("predicate", p.name());
				m.put("uses", rows.getFirst().lng("n"));
				String obs = rows.getFirst().str("obs");
				m.put("observations",
						obs == null ? List.of() : Arrays.stream(obs.split(",")).map(x -> "obs-" + x).toList());
				out.add(m);
			}
			out.sort((a, b) -> Long.compare((Long) b.get("uses"), (Long) a.get("uses")));
			return out;
		});
	}

	// ── write ────────────────────────────────────────────────────────────

	/**
	 * Folds one predicate into another: its facts are re-keyed, its name and aliases become aliases of the target,
	 * event types that named it name the target, and its registration goes. The count of facts moved.
	 */
	public int merge(String from, String into, String reason) {
		return db.withLock(() -> {
			Predicate f = Optional.ofNullable(load().get(from))
					.orElseThrow(() -> MnemicException.notFound("No predicate " + from));
			Predicate t = get(into).orElseThrow(() -> MnemicException.notFound("No predicate " + into));
			if (f.name().equals(t.name())) {
				throw MnemicException.invalidArgument("'" + from + "' and '" + into + "' are the same predicate.");
			}
			if (f.seed()) {
				throw MnemicException.invalidArgument("'" + from + "' is a seed predicate; merge the other way round.");
			}
			var aliases = new ArrayList<>(t.aliases());
			for (String a : concat(List.of(f.name()), f.aliases())) {
				if (!aliases.contains(a)) {
					aliases.add(a);
				}
			}
			int moved = db.write(tx -> {
				int n = tx.update("UPDATE fact SET predicate = ? WHERE predicate = ?", t.name(), f.name());
				tx.update("UPDATE question SET predicate = ? WHERE predicate = ?", t.name(), f.name());
				tx.update("DELETE FROM predicate WHERE name = ?", f.name());
				tx.update("UPDATE predicate SET aliases = ?, inferred = 0 WHERE name = ?", json(aliases), t.name());
				tx.insert(
						"INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at) "
								+ "VALUES (?,?,?,?,?,?)",
						t.name(), "merged", f.name(), t.name(), reason, Instant.now().toString());
				return n;
			});
			matcher.evictVector(f.name());
			matcher.evictVector(t.name());
			cache = null;
			return moved;
		});
	}

	private static List<String> concat(List<String> a, List<String> b) {
		var out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	public Predicate register(PredicateDef def, Long observationId) {
		return db.withLock(() -> {
			if (def.name() == null || def.name().isBlank()) {
				throw MnemicException.invalidArgument("A predicate definition needs a 'name'.");
			}
			String name = def.name().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
			Predicate existing = load().get(name);
			if (existing != null) {
				if (existing.isInferred() && !bare(def)) {
					return define(existing, def);
				}
				var additions = new LinkedHashMap<String, Object>();
				if (def.implies() != null) {
					additions.put("implies", def.implies());
				}
				if (def.definedAs() != null) {
					additions.put("defined_as", def.definedAs());
				}
				return additions.isEmpty() ? existing : update(name, additions,
						observationId == null ? "defined again" : "defined in obs-" + observationId);
			}
			List<String> domain = types(def.domain());
			List<String> range = types(def.range());
			String render = def.render() != null && !def.render().isBlank() ? def.render()
					: "{subject} " + name.replace('_', ' ') + " {object}[[ ({qualifier})]]";
			List<String> lexicon = def.lexicon().isEmpty() ? Predicate.lexiconOf(name) : def.lexicon();
			List<String> grpNames = def.groups() == null ? List.of() : PredicateGroups.groupNames(def.groups());
			var p = new Predicate(name, def.description(), domain, range, Boolean.TRUE.equals(def.functional()),
					def.functionalScope(), Boolean.TRUE.equals(def.symmetric()), def.inverse(),
					def.volatility() == null ? "medium" : def.volatility(), lexicon, render, def.qualifiers(),
					def.aliases(), def.inverseLexicon(), observationId, false, bare(def),
					Boolean.TRUE.equals(def.containment()), grpNames,
					def.lasting() != null ? def.lasting() : range.contains("literal"), def.lasting() != null);
			List<Rule> parsed = def.definedAs() == null ? List.of() : Rule.parse(def.definedAs());
			validateRules(name, parsed);
			Map<String, Map<String, String>> implied = parseImplies(name, def.implies());
			groups.registerFromUse(grpNames, this::invalidate, this::load);
			for (String t : domain) {
				registerTypeFromUse(t, observationId);
			}
			for (String t : range) {
				registerTypeFromUse(t, observationId);
			}
			insert(p);
			if (!parsed.isEmpty()) {
				setRules(name, parsed);
			}
			if (!implied.isEmpty()) {
				setImplies(name, implied);
			}
			putRenders(name, def.renders());
			return get(name).orElseThrow();
		});
	}

	/**
	 * A definition for a predicate registered from use: every property the definition states replaces the inferred one.
	 */
	private Predicate define(Predicate p, PredicateDef def) {
		var replacement = new LinkedHashMap<String, Object>();
		if (def.description() != null) {
			replacement.put("description", def.description());
		}
		if (def.domain() != null) {
			replacement.put("domain", def.domain());
		}
		if (def.range() != null) {
			replacement.put("range", def.range());
		}
		if (def.functional() != null) {
			replacement.put("functional", def.functional());
		}
		if (def.symmetric() != null) {
			replacement.put("symmetric", def.symmetric());
		}
		if (def.volatility() != null) {
			replacement.put("volatility", def.volatility());
		}
		if (!def.lexicon().isEmpty()) {
			replacement.put("lexicon", def.lexicon());
		}
		if (def.render() != null && !def.render().isBlank()) {
			replacement.put("render", def.render());
		}
		if (!def.qualifiers().isEmpty()) {
			replacement.put("qualifiers", def.qualifiers());
		}
		if (!def.renders().isEmpty()) {
			replacement.put("renders", def.renders());
		}
		if (def.definedAs() != null) {
			replacement.put("defined_as", def.definedAs());
		}
		if (def.implies() != null) {
			replacement.put("implies", def.implies());
		}
		if (def.containment() != null) {
			replacement.put("containment", def.containment());
		}
		if (def.groups() != null) {
			replacement.put("groups", def.groups());
		}
		if (def.lasting() != null) {
			replacement.put("lasting", def.lasting());
		}
		if (!def.inverseLexicon().isEmpty()) {
			replacement.put("inverse_lexicon", def.inverseLexicon());
		}
		return update(p.name(), replacement, "defined after registration from use");
	}

	/**
	 * Corrects a predicate property (J5): {@code render}, {@code lexicon}, {@code qualifiers}, {@code functional},
	 * {@code symmetric}, {@code volatility}, {@code description}, {@code domain}, {@code range}. Every change is
	 * logged; the caller re-renders the facts.
	 */
	public Predicate update(String name, Map<String, Object> replacement, String reason) {
		return db.withLock(() -> {
			Predicate p = get(name).orElseThrow(() -> MnemicException.notFound("No predicate " + name));
			String render = p.render();
			List<String> lexicon = p.lexicon();
			List<String> qualifiers = p.qualifiers();
			List<String> inverseLexicon = p.inverseLexicon();
			boolean functional = p.functional();
			boolean symmetric = p.symmetric();
			String volatility = p.volatility();
			String description = p.description();
			List<String> domain = p.domain();
			List<String> range = p.range();
			String rule = null;
			String newImplies = null;
			boolean impliesChanged = false;
			boolean containment = p.containment();
			boolean lasting = p.lasting();
			boolean lastingStated = p.lastingStated();
			List<String> grpList = p.groups();
			List<String> newEnds = null;
			var changes = new ArrayList<String[]>();
			for (Map.Entry<String, Object> e : replacement.entrySet()) {
				String old;
				switch (e.getKey()) {
				case "ends" -> {
					old = json(endsOf(p.name()));
					newEnds = new ArrayList<>();
					for (String q : strings(e.getValue())) {
						Predicate target = get(q).orElseThrow(() -> MnemicException
								.invalidArgument("'ends' names predicates; there is no predicate '" + q + "'."));
						if (target.name().equals(p.name())) {
							throw MnemicException
									.invalidArgument("A predicate does not end itself: a later value of the "
											+ "same relation is what 'functional' decides.");
						}
						if (!newEnds.contains(target.name())) {
							newEnds.add(target.name());
						}
					}
				}
				case "groups" -> {
					old = json(grpList);
					grpList = PredicateGroups.groupNames(strings(e.getValue()));
					groups.registerFromUse(grpList, this::invalidate, this::load);
				}
				case "defined_as" -> {
					old = Rule.toJson(rulesOf(p.name()));
					List<Rule> parsed = Rule.parse(e.getValue());
					validateRules(p.name(), parsed);
					rule = Rule.toJson(parsed);
				}
				case "implies" -> {
					old = String.valueOf(impliesJson(impliesOf(p.name())));
					newImplies = impliesJson(parseImplies(p.name(), e.getValue()));
					impliesChanged = true;
				}
				case "containment" -> {
					old = String.valueOf(containment);
					containment = Boolean.parseBoolean(String.valueOf(e.getValue()));
				}
				case "lasting" -> {
					old = String.valueOf(lasting);
					lasting = Boolean.parseBoolean(String.valueOf(e.getValue()));
					lastingStated = true;
				}
				case "render" -> {
					old = render;
					render = String.valueOf(e.getValue());
				}
				case "domain" -> {
					old = json(domain);
					domain = types(e.getValue() instanceof List<?> l ? String.join("|", strings(l))
							: String.valueOf(e.getValue()));
				}
				case "range" -> {
					old = json(range);
					range = types(e.getValue() instanceof List<?> l ? String.join("|", strings(l))
							: String.valueOf(e.getValue()));
				}
				case "symmetric" -> {
					old = String.valueOf(symmetric);
					symmetric = Boolean.parseBoolean(String.valueOf(e.getValue()));
				}
				case "lexicon" -> {
					old = json(lexicon);
					lexicon = strings(e.getValue());
				}
				case "qualifiers" -> {
					old = json(qualifiers);
					qualifiers = strings(e.getValue());
				}
				case "inverse_lexicon" -> {
					old = json(inverseLexicon);
					inverseLexicon = strings(e.getValue());
				}
				case "functional" -> {
					old = String.valueOf(functional);
					functional = Boolean.parseBoolean(String.valueOf(e.getValue()));
				}
				case "volatility" -> {
					old = volatility;
					volatility = String.valueOf(e.getValue());
				}
				case "description" -> {
					old = description;
					description = String.valueOf(e.getValue());
				}
				case "renders" -> {
					if (!(e.getValue() instanceof Map<?, ?> byLanguage)) {
						throw MnemicException.invalidArgument("'renders' takes an object keyed by language, e.g. "
								+ "{\"de\": {\"render\": \"{subject} betreut {object}\"}}.");
					}
					for (Map.Entry<?, ?> r : byLanguage.entrySet()) {
						String language = language(String.valueOf(r.getKey()));
						String before = renderIn(p.name(), language);
						putRender(p.name(), language, r.getValue());
						changes.add(new String[] {"renders." + language, before, renderIn(p.name(), language)});
					}
					continue;
				}
				default -> throw MnemicException.invalidArgument("Unknown predicate property '" + e.getKey()
						+ "'; correctable: description, domain, range, render, renders, lexicon, inverse_lexicon, qualifiers, "
						+ "functional, symmetric, volatility, defined_as, implies, containment, groups, lasting, ends.");
				}
				changes.add(new String[] {e.getKey(), old,
						"defined_as".equals(e.getKey()) ? rule
								: "implies".equals(e.getKey()) ? String.valueOf(newImplies)
										: e.getValue() instanceof List<?> ? json(strings(e.getValue()))
												: String.valueOf(e.getValue())});
			}
			final String implied = newImplies;
			final boolean setImplied = impliesChanged;
			final boolean cont = containment;
			final String newRule = rule;
			final String r = render;
			final List<String> l = lexicon;
			final List<String> q = qualifiers;
			final List<String> inv = inverseLexicon;
			final boolean f = functional;
			final boolean sym = symmetric;
			final String v = volatility;
			final String d = description;
			final List<String> dom = domain;
			final List<String> rng = range;
			final List<String> grp = grpList;
			final boolean last = lasting;
			final boolean said = lastingStated;
			final List<String> endsNow = newEnds;
			db.write(tx -> {
				tx.update(
						"""
								UPDATE predicate SET render = ?, lexicon = ?, qualifiers = ?, functional = ?, symmetric = ?, volatility = ?,
								description = ?, inverse_lexicon = ?, domain = ?, range = ?, containment = ?, groups = ?, lasting = ?,
								lasting_stated = ?, inferred = 0 WHERE name = ?""",
						r, json(l), json(q), f ? 1 : 0, sym ? 1 : 0, v, d, json(inv), json(dom), json(rng),
						cont ? 1 : 0, json(grp), last ? 1 : 0, said ? 1 : 0, p.name());
				if (newRule != null) {
					tx.update("UPDATE predicate SET rule = ? WHERE name = ?", newRule, p.name());
				}
				if (setImplied) {
					tx.update("UPDATE predicate SET implies = ? WHERE name = ?", implied, p.name());
				}
				if (endsNow != null) {
					tx.update("UPDATE predicate SET ends = ? WHERE name = ?", json(endsNow), p.name());
				}
				for (String[] c : changes) {
					tx.insert(
							"INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at) "
									+ "VALUES (?,?,?,?,?,?)",
							p.name(), c[0], c[1], c[2], reason, Instant.now().toString());
				}
				return null;
			});
			matcher.evictVector(p.name());
			cache = null;
			return get(p.name()).orElseThrow();
		});
	}

	/**
	 * A predicate registered from an entity's attribute shorthand takes a value, not a thing: its range becomes literal
	 * and it lasts, as attributes do (a colour, a size, a plate). Only a predicate registered from use that holds no
	 * fact with an entity as its object is changed, and the change is logged; anything else is returned as it stands.
	 */
	public Predicate literalFromUse(String name, String reason) {
		return db.withLock(() -> {
			Predicate p = get(name).orElseThrow(() -> MnemicException.notFound("No predicate " + name));
			if (p.literalRange() || !p.isInferred()) {
				return p;
			}
			long entityObjects = db.read(tx -> tx
					.queryLong("SELECT COUNT(*) FROM fact WHERE predicate = ? AND object_id IS NOT NULL", p.name()));
			if (entityObjects > 0) {
				return p;
			}
			db.write(tx -> {
				tx.update("UPDATE predicate SET range = ?, lasting = 1 WHERE name = ?", json(List.of("literal")),
						p.name());
				tx.insert(
						"INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at) "
								+ "VALUES (?,?,?,?,?,?)",
						p.name(), "range", json(p.range()), json(List.of("literal")), reason, Instant.now().toString());
				return null;
			});
			matcher.evictVector(p.name());
			cache = null;
			return get(p.name()).orElseThrow();
		});
	}

	public List<Map<String, Object>> changes(String name) {
		return db.read(tx -> tx.query("SELECT * FROM predicate_change WHERE predicate = ? ORDER BY id", name).stream()
				.map(r -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("field", r.str("field"));
					m.put("old", r.str("old_value"));
					m.put("new", r.str("new_value"));
					m.put("reason", r.str("reason"));
					m.put("changed_at", r.str("changed_at"));
					return (Map<String, Object>) m;
				}).toList());
	}

	/** Records {@code alias} as another name for {@code p}: the caller confirmed a similar match (J2). */
	public void addAlias(Predicate p, String alias) {
		db.withLock(() -> {
			var aliases = new ArrayList<>(p.aliases());
			if (!aliases.contains(alias)) {
				aliases.add(alias);
				db.write(tx -> tx.update("UPDATE predicate SET aliases = ? WHERE name = ?", json(aliases), p.name()));
				cache = null;
			}
		});
	}

	// ── groups (delegated to PredicateGroups) ────────────────────────────

	/** Every group, seed first. */
	public List<Group> groups() {
		return db.withLock(() -> {
			load();
			return groups.all();
		});
	}

	public Optional<Group> group(String name) {
		return db.withLock(() -> {
			load();
			return groups.get(name);
		});
	}

	/** The groups within a group, transitively, the group itself first; a cycle is never walked twice. */
	public List<String> groupsUnder(String name) {
		return db.withLock(() -> {
			load();
			return groups.groupsUnder(name);
		});
	}

	/**
	 * The predicates in a group, including those in the groups within it: the stated relations first, the derived ones
	 * after them, each in registry order, so that a group's verdict leads with what somebody said.
	 */
	public List<Predicate> membersOf(String name) {
		return db.withLock(() -> {
			Collection<Predicate> predicates = load().values();
			return groups.membersOf(name, predicates, this::rulesOf);
		});
	}

	static String groupName(String name) {
		return PredicateGroups.groupName(name);
	}

	static List<String> groupWords(String name) {
		return PredicateGroups.groupWords(name);
	}

	/**
	 * Corrects a group: {@code description}, {@code lexicon} (its cue words, in any language: matching is blind to it),
	 * and {@code groups} (the groups it belongs to; a group that would contain itself is refused). Every change is
	 * logged.
	 */
	public Group updateGroup(String name, Map<String, Object> replacement, String reason) {
		return db.withLock(() -> groups.update(name, replacement, reason, this::invalidate, this::load));
	}

	public List<Map<String, Object>> groupChanges(String name) {
		return groups.changes(name);
	}

	// ── seed adoption ────────────────────────────────────────────────────

	static List<Predicate> seed() {
		return PredicateSeeds.predicates();
	}

	public List<String> adoptedAtStart() {
		return db.withLock(() -> List.copyOf(adopted));
	}

	private void seedIfMissing() {
		Map<String, Predicate> existing = load();
		for (Predicate p : PredicateSeeds.predicates()) {
			Predicate had = existing.get(p.name());
			if (had == null) {
				insert(p);
			} else if (had.isInferred()) {
				// A caller stated the relation before the seed knew it, and the registry inferred a bare definition
				// from the name ("{subject} gender {object}"). The seed's definition takes over; the facts stay.
				db.write(tx -> tx.update(
						"""
								UPDATE predicate SET description = ?, domain = ?, range = ?, functional = ?, functional_scope = ?,
								                     symmetric = ?, volatility = ?, lexicon = ?, render = ?, qualifiers = ?,
								                     inverse_lexicon = ?, lasting = ?, lasting_stated = 1, seed = 1, inferred = 0
								WHERE name = ?""",
						p.description(), json(p.domain()), json(p.range()), p.functional() ? 1 : 0, p.functionalScope(),
						p.symmetric() ? 1 : 0, p.volatility(), json(p.lexicon()), p.render(), json(p.qualifiers()),
						json(p.inverseLexicon()), p.lasting() ? 1 : 0, p.name()));
				adopted.add(p.name());
				cache = null;
			}
		}
		for (Map.Entry<String, List<Map<String, Object>>> e : PredicateSeeds.rules().entrySet()) {
			Predicate p = load().get(e.getKey());
			if (p == null || !p.seed()) {
				continue;
			}
			List<Rule> wanted = Rule.parse(e.getValue());
			if (wanted.equals(rulesOf(e.getKey()))) {
				continue;
			}
			boolean userSet = db.read(tx -> tx.queryLong(
					"SELECT COUNT(*) FROM predicate_change WHERE predicate = ? AND field = 'defined_as'",
					e.getKey())) > 0;
			if (!userSet) {
				validateRules(e.getKey(), wanted);
				setRules(e.getKey(), wanted);
			}
		}
		for (Map.Entry<String, Map<String, Object>> e : PredicateSeeds.implies().entrySet()) {
			Predicate p = load().get(e.getKey());
			if (p == null || !p.seed()) {
				continue;
			}
			Map<String, Map<String, String>> wanted = parseImplies(e.getKey(), e.getValue());
			if (wanted.equals(impliesOf(e.getKey()))) {
				continue;
			}
			boolean userSet = db.read(tx -> tx.queryLong(
					"SELECT COUNT(*) FROM predicate_change WHERE predicate = ? AND field = 'implies'", e.getKey())) > 0;
			if (!userSet) {
				setImplies(e.getKey(), wanted);
			}
		}
	}

	/**
	 * A predicate named as a lasting seed carries the flag unless the user changed it (a change on its log), so a store
	 * from before the property existed takes it at this start.
	 */
	private void seedLastingIfMissing() {
		var userSet = new HashSet<String>();
		for (Row r : db
				.read(tx -> tx.query("SELECT DISTINCT predicate FROM predicate_change WHERE field = 'lasting'"))) {
			userSet.add(r.str("predicate"));
		}
		var claim = new ArrayList<String>();
		for (String name : PredicateSeeds.lasting()) {
			Predicate p = load().get(name);
			if (p != null && !p.lasting() && !userSet.contains(name) && PredicateSeeds.sameRelationAsSeed(p, name)) {
				claim.add(name);
			}
		}
		if (!claim.isEmpty()) {
			db.write(tx -> {
				for (String name : claim) {
					tx.update("UPDATE predicate SET lasting = 1, lasting_stated = 1 WHERE name = ?", name);
				}
				return null;
			});
			cache = null;
		}
	}

	// ── persistence ──────────────────────────────────────────────────────

	private Map<String, Predicate> load() {
		long dv = db.dataVersion();
		if (cache == null || dv != dataVersion) {
			var map = new LinkedHashMap<String, Predicate>();
			var ruleMap = new HashMap<String, List<Rule>>();
			var impliesMap = new HashMap<String, Map<String, Map<String, String>>>();
			var endsMap = new HashMap<String, List<String>>();
			for (Row r : db.read(tx -> tx.query("SELECT * FROM predicate ORDER BY seed DESC, name"))) {
				map.put(r.str("name"), from(r));
				List<String> endsOf = list(r.str("ends"));
				if (!endsOf.isEmpty()) {
					endsMap.put(r.str("name"), endsOf);
				}
				if (r.str("rule") != null) {
					ruleMap.put(r.str("name"), Rule.fromJson(r.str("rule")));
				}
				if (r.str("implies") != null) {
					impliesMap.put(r.str("name"), impliesFromJson(r.str("implies")));
				}
			}
			rules = ruleMap;
			implies = impliesMap;
			ends = endsMap;
			negated.clear();
			if (lang != Lang.EN) {
				for (Row r : db
						.read(tx -> tx.query("SELECT * FROM predicate_render WHERE language = ?", lang.code()))) {
					Predicate p = map.get(r.str("name"));
					if (p == null) {
						continue;
					}
					var lexicon = new ArrayList<>(p.lexicon());
					for (String t : list(r.str("lexicon"))) {
						if (!lexicon.contains(t)) {
							lexicon.add(t);
						}
					}
					var inverse = new ArrayList<>(p.inverseLexicon());
					for (String t : list(r.str("inverse_lexicon"))) {
						if (!inverse.contains(t)) {
							inverse.add(t);
						}
					}
					map.put(p.name(),
							new Predicate(p.name(), p.description(), p.domain(), p.range(), p.functional(),
									p.functionalScope(), p.symmetric(), p.inverse(), p.volatility(), lexicon,
									r.str("render"), p.qualifiers(), p.aliases(), inverse, p.definedBy(), p.seed(),
									p.isInferred(), p.containment(), p.groups(), p.lasting(), p.lastingStated()));
					if (r.str("negated") != null) {
						negated.put(p.name(), r.str("negated"));
					}
				}
			}
			cache = map;
			groups.reload();
			matcher.clearVectors();
			dataVersion = dv;
		}
		return cache;
	}

	private void insert(Predicate p) {
		db.write(tx -> insert(tx, p));
		cache = null;
	}

	private static Object insert(Tx tx, Predicate p) {
		tx.insert(
				"""
						INSERT INTO predicate(name, description, domain, range, functional, functional_scope, symmetric,
						                      inverse, volatility, lexicon, render, qualifiers, aliases, inverse_lexicon,
						                      defined_by, seed, inferred, created_at, containment, groups, lasting, lasting_stated)
						VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
				p.name(), p.description(), json(p.domain()), json(p.range()), p.functional() ? 1 : 0,
				p.functionalScope(), p.symmetric() ? 1 : 0, p.inverse(), p.volatility(), json(p.lexicon()), p.render(),
				json(p.qualifiers()), json(p.aliases()), json(p.inverseLexicon()), p.definedBy(), p.seed() ? 1 : 0,
				p.isInferred() ? 1 : 0, Instant.now().toString(), p.containment() ? 1 : 0, json(p.groups()),
				p.lasting() ? 1 : 0, p.lastingStated() ? 1 : 0);
		if (p.seed() && PredicateSeeds.ends().containsKey(p.name())) {
			tx.update("UPDATE predicate SET ends = ? WHERE name = ?", json(PredicateSeeds.ends().get(p.name())),
					p.name());
		}
		return null;
	}

	private static Predicate from(Row r) {
		return new Predicate(r.str("name"), r.str("description"), list(r.str("domain")), list(r.str("range")),
				r.lng("functional") == 1, r.str("functional_scope"), r.lng("symmetric") == 1, r.str("inverse"),
				r.str("volatility"), list(r.str("lexicon")), r.str("render"), list(r.str("qualifiers")),
				list(r.str("aliases")), list(r.str("inverse_lexicon")), r.lngOrNull("defined_by"), r.lng("seed") == 1,
				r.lng("inferred") == 1, r.lng("containment") == 1, list(r.str("groups")), r.lng("lasting") == 1,
				r.lng("lasting_stated") == 1);
	}

	private void registerTypeFromUse(String type, Long observationId) {
		if (!"*".equals(type) && !"literal".equals(type) && entityTypes.get(type).isEmpty()) {
			entityTypes.registerInferred(type, observationId);
		}
	}

	private List<String> types(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of("*");
		}
		var out = new ArrayList<String>();
		for (String t : csv.split("[|,/]")) {
			String v = t.trim();
			if (!v.isEmpty()) {
				out.add("*".equals(v) || "literal".equals(v) ? v : entityTypes.canonical(v));
			}
		}
		return out.isEmpty() ? List.of("*") : out;
	}

	static List<String> strings(Object value) {
		if (value instanceof List<?> l) {
			return l.stream().map(String::valueOf).toList();
		}
		return List.of(String.valueOf(value).split("\\s*,\\s*"));
	}

	static String json(List<String> l) {
		try {
			return JSON.writeValueAsString(l);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}

	static List<String> list(String json) {
		try {
			return json == null ? List.of() : JSON.readValue(json, LIST);
		} catch (Exception e) {
			throw MnemicException.internal("json", e);
		}
	}
}
