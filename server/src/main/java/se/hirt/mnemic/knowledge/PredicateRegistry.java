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
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * The predicate registry: the seed vocabulary (EXTRACTION.md), caller definitions, resolution of proposed names to
 * existing predicates (name, alias, lexicon, description overlap), query-time cues (lexicon term → predicate and
 * qualifier), and corrections with a change log (EVALUATION.md J5). Predicates are cached in memory and reloaded on
 * change; the table is small.
 */
public final class PredicateRegistry {

	/**
	 * How a proposed predicate resolved. {@code predicate} is null and {@code candidate} set when the match is
	 * plausible but not confident (EVALUATION.md J3): the caller decides.
	 */
	public record Resolution(Predicate predicate, String how, Predicate candidate) {
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
	public record Cue(Predicate predicate, String qualifier, String term, String direction) {
	}

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<String>> LIST = new TypeReference<>() {
	};
	private static final int SIMILAR_MIN_OVERLAP = 2;
	/**
	 * When a name is taken for an existing predicate by meaning. Sentence embedders score every pair of short phrases
	 * highly (the granite model puts unrelated predicates at about 0.84), so the signal is the lead of the nearest
	 * predicate over the next one as much as its score: a real match leads by 0.045 or more, noise by under 0.02 (see
	 * {@code VocabularyCalibrationTest}). Asked as similar above the first pair, as ambiguous above the second.
	 */
	static final float SEMANTIC_SIMILAR = 0.88f;
	static final float SEMANTIC_SIMILAR_LEAD = 0.04f;
	static final float SEMANTIC_AMBIGUOUS = 0.86f;
	static final float SEMANTIC_AMBIGUOUS_LEAD = 0.02f;

	private final Database db;
	private final Lang lang;
	private final EntityTypeRegistry entityTypes;
	/** The embedding model, when one is loaded; null means names are compared by their words only. */
	private final Supplier<Embedding> embedding;
	/** The negation templates of the language, by predicate; empty for the base language. */
	private final Map<String, String> negated = new HashMap<>();
	/** One vector per predicate for {@code vectorModel}, computed when first needed. */
	private final Map<String, float[]> vectors = new HashMap<>();
	private String vectorModel;
	private Map<String, Predicate> cache;
	/** The rules of the derived predicates, loaded with the cache. */
	private Map<String, List<Rule>> rules = Map.of();
	/** What each predicate's terms imply about the subject's attributes, loaded with the cache. */
	private Map<String, Map<String, Map<String, String>>> implies = Map.of();

	/** {@code lang}: the store's language; its templates and cue words are loaded over the base (English) ones. */
	public PredicateRegistry(Database db, Lang lang, EntityTypeRegistry entityTypes) {
		this(db, lang, entityTypes, () -> null);
	}

	public PredicateRegistry(Database db, Lang lang, EntityTypeRegistry entityTypes, Supplier<Embedding> embedding) {
		this.db = db;
		this.entityTypes = entityTypes;
		this.lang = lang;
		this.embedding = embedding;
		seedIfMissing();
		seedRendersIfMissing();
		fillLexicons();
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
		load();
		return negated.get(predicate);
	}

	/**
	 * German templates, negations, and cue words for the seed predicates. The base row of a predicate keeps its English
	 * template; a language row overrides the rendering and adds cue words when it is the store's language. Stored, not
	 * compiled in, so a user can correct them like any other template (correct on the predicate).
	 */
	private static final List<String[]> RENDERS_DE = List.of(
			new String[] {"works_at", "{subject} arbeitet bei {object}", "{subject} arbeitet nicht bei {object}",
					"arbeitet,arbeiten,arbeitgeber,angestellt,job,firma,stelle,tätig", ""},
			new String[] {"holds_role", "{subject} hat die Rolle {object}[[ bei {scope}]]",
					"{subject} hat nicht die Rolle {object}[[ bei {scope}]]",
					"rolle,titel,position,leiter,leiterin,direktor,direktorin,ingenieur,geschäftsführer", ""},
			new String[] {"leads", "{subject} leitet {object}", "{subject} leitet {object} nicht",
					"leitet,leiten,führt,verantwortet,verantwortlich", ""},
			new String[] {"lives_in", "{subject} wohnt in {object}", "{subject} wohnt nicht in {object}",
					"wohnt,wohnen,lebt,leben,zuhause,wohnort,wohnhaft", ""},
			new String[] {"born_in", "{subject} wurde in {object} geboren", "{subject} wurde nicht in {object} geboren",
					"geboren,geburtsort,gebürtig", ""},
			new String[] {"member_of", "{subject} ist Mitglied von {object}",
					"{subject} ist nicht Mitglied von {object}", "mitglied,verein,mitgliedschaft", ""},
			new String[] {"parent_of", "{subject} ist {qualifier|Elternteil} von {object}",
					"{subject} ist nicht {qualifier|Elternteil} von {object}", "mutter,vater,eltern,elternteil",
					"kind,kinder,sohn,tochter,söhne,töchter"},
			new String[] {"spouse_of", "{subject} ist {qualifier|Ehepartner} von {object}",
					"{subject} ist nicht {qualifier|Ehepartner} von {object}",
					"verheiratet,ehefrau,ehemann,ehepartner,heiratete,frau,mann", ""},
			new String[] {"gender", "{subject} ist {object}", "{subject} ist nicht {object}",
					"geschlecht,männlich,weiblich,mann,frau", ""},
			new String[] {"partner_of", "{subject} ist {qualifier|Partner} von {object}",
					"{subject} ist nicht {qualifier|Partner} von {object}",
					"partner,partnerin,freund,freundin,lebensgefährte,lebensgefährtin,zusammen", ""},
			new String[] {"engaged_to", "{subject} ist mit {object} verlobt",
					"{subject} ist nicht mit {object} verlobt", "verlobt,verlobte,verlobter,verlobung", ""},
			new String[] {"step_parent_of", "{subject} ist {qualifier|Stiefelternteil} von {object}",
					"{subject} ist nicht {qualifier|Stiefelternteil} von {object}",
					"stiefmutter,stiefvater,stiefeltern", "stiefkind,stiefsohn,stieftochter"},
			new String[] {"grandparent_of", "{subject} ist {qualifier|Großelternteil} von {object}",
					"{subject} ist nicht {qualifier|Großelternteil} von {object}",
					"großmutter,großvater,großeltern,oma,opa,grossmutter,grossvater",
					"enkel,enkelin,enkelkind,enkelkinder"},
			new String[] {"aunt_uncle_of", "{subject} ist {qualifier|Tante oder Onkel} von {object}",
					"{subject} ist nicht {qualifier|Tante oder Onkel} von {object}", "tante,onkel", "neffe,nichte"},
			new String[] {"cousin_of", "{subject} ist {qualifier|Cousin} von {object}",
					"{subject} ist nicht {qualifier|Cousin} von {object}", "cousin,cousine,cousins,vetter,kusine", ""},
			new String[] {"in_law_of", "{subject} ist {qualifier|angeheiratet} mit {object}",
					"{subject} ist nicht {qualifier|angeheiratet} mit {object}",
					"schwiegermutter,schwiegervater,schwiegereltern,schwiegersohn,schwiegertochter,schwager,schwägerin",
					""},
			new String[] {"sibling_of", "{subject} ist {qualifier|Geschwister} von {object}",
					"{subject} ist nicht {qualifier|Geschwister} von {object}",
					"bruder,schwester,geschwister,halbbruder,halbschwester,stiefbruder,stiefschwester,zwilling", ""},
			new String[] {"owns", "{subject} besitzt {object}", "{subject} besitzt {object} nicht",
					"besitzt,besitzen,gekauft,kaufte,kaufen,erworben,eigentum,eigentümer", ""},
			new String[] {"prefers", "{subject} bevorzugt {object}", "{subject} bevorzugt {object} nicht",
					"bevorzugt,lieber,liebling,lieblings,gefällt,mag", ""},
			new String[] {"dislikes", "{subject} mag {object} nicht", null, "hasst,abneigung,verabscheut", ""},
			new String[] {"uses", "{subject} verwendet {object}", "{subject} verwendet {object} nicht",
					"verwendet,benutzt,nutzt,einsatz,arbeitet mit", ""},
			new String[] {"decided", "{subject} hat entschieden: {object}", null,
					"entschieden,entscheidung,beschlossen,gewählt,entschied", ""},
			new String[] {"considering", "{subject} erwägt {object}", "{subject} erwägt {object} nicht",
					"erwägt,überlegt,plant,vorhaben,absicht,tendiert,erwägen", ""},
			new String[] {"related_to", "{subject} steht in Beziehung zu {object}[[ ({qualifier})]]", null,
					"verwandt,beziehung,bezug", ""},
			new String[] {"knows", "{subject} kennt {object}[[ ({qualifier})]]", "{subject} kennt {object} nicht",
					"kennt,kennen,kollege,kollegin,traf,getroffen,bekannt", ""},
			new String[] {"part_of", "{subject} ist Teil von {object}", "{subject} ist nicht Teil von {object}",
					"teil,innerhalb", ""},
			new String[] {"located_in", "{subject} liegt in {object}", "{subject} liegt nicht in {object}",
					"liegt,befindet,sitz,standort,hauptsitz,gelegen", ""});

	private void seedRendersIfMissing() {
		db.write(tx -> {
			for (String[] r : RENDERS_DE) {
				tx.update(
						"""
								INSERT OR IGNORE INTO predicate_render(name, language, render, negated, lexicon, inverse_lexicon)
								SELECT ?, 'de', ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM predicate WHERE name = ?)""",
						r[0], r[1], r[2], json(List.of(r[3].split(","))),
						json(r[4].isEmpty() ? List.of() : List.of(r[4].split(","))), r[0]);
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
	public synchronized void putRender(String predicate, String language, Object spec) {
		String render = spec instanceof Map<?, ?> m ? text(m.get("render")) : text(spec);
		if (render == null || render.isBlank()) {
			throw MnemicException.invalidArgument(
					"renders." + language + " needs a 'render' template, e.g. " + "\"{subject} betreut {object}\".");
		}
		String negated = spec instanceof Map<?, ?> m ? text(m.get("negated")) : null;
		List<String> lexicon = spec instanceof Map<?, ?> m && m.get("lexicon") != null ? strings(m.get("lexicon"))
				: List.of();
		List<String> inverse = spec instanceof Map<?, ?> m && m.get("inverse_lexicon") != null
				? strings(m.get("inverse_lexicon")) : List.of();
		db.write(tx -> tx.update("""
				INSERT OR REPLACE INTO predicate_render(name, language, render, negated, lexicon, inverse_lexicon)
				VALUES (?,?,?,?,?,?)""", predicate, language, render, negated, json(lexicon), json(inverse)));
		cache = null;
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
	public synchronized void reload() {
		cache = null;
	}

	public synchronized List<Predicate> all() {
		return List.copyOf(load().values());
	}

	/**
	 * What a predicate's qualifiers or literal values imply about the subject's attributes: term → attribute → value
	 * (parent_of: mother → gender female; gender: woman → gender female). Empty when it declares none.
	 */
	public synchronized Map<String, Map<String, String>> impliesOf(String name) {
		load();
		return implies.getOrDefault(name, Map.of());
	}

	/**
	 * Parses an {@code implies} value: an object from a qualifier or literal value to the attributes it fixes, each
	 * attribute a registered predicate. Terms and values are lowercased.
	 */
	public synchronized Map<String, Map<String, String>> parseImplies(String name, Object implies) {
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
	public synchronized List<String> containmentPredicates() {
		return load().values().stream().filter(Predicate::containment).map(Predicate::name).toList();
	}

	public synchronized boolean isContainment(String name) {
		Predicate p = load().get(name);
		return p != null && p.containment();
	}

	/**
	 * Whether a thing of a type (given as its lineage) is one things lie within: some containment predicate names its
	 * kind as its range. A containment predicate whose range is anything (part_of) nests what it is given but declares
	 * no kind a container, so a domain name or a printer is not asked where it lies.
	 */
	public synchronized boolean canContain(List<String> lineage) {
		return load().values().stream()
				.anyMatch(p -> p.containment() && !p.range().contains("*") && p.acceptsObject(lineage));
	}

	/** The containment predicate that nests a thing of one kind inside a thing of another, or the first there is. */
	public synchronized Optional<Predicate> containmentFor(List<String> inner, List<String> outer) {
		List<Predicate> within = load().values().stream().filter(Predicate::containment).toList();
		// The predicate made for these kinds first (unit_of: department → organization), then any that takes them
		// (part_of: anything → anything), then whatever there is.
		return within.stream()
				.filter(p -> !p.domain().contains("*") && !p.range().contains("*") && p.acceptsSubject(inner)
						&& p.acceptsObject(outer))
				.findFirst()
				.or(() -> within.stream().filter(p -> p.acceptsSubject(inner) && p.acceptsObject(outer)).findFirst())
				.or(() -> within.stream().findFirst());
	}

	/** The rules that define a derived predicate (family K); none for an asserted one. */
	public synchronized List<Rule> rulesOf(String name) {
		load();
		return rules.getOrDefault(name, List.of());
	}

	/** The predicates that are derived: defined by rules over other predicates. */
	public synchronized List<Predicate> derived() {
		return load().values().stream().filter(p -> !rulesOf(p.name()).isEmpty()).toList();
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
				String bare = n.startsWith("^") ? n.substring(1) : n; // ^pred: the fact must not run the other way
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

	public synchronized Optional<Predicate> get(String name) {
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
	}

	/**
	 * Resolves a proposed predicate name, with an optional definition: exact name, alias, a similar existing predicate
	 * (same domain/range, at least two content tokens shared across name, description, and lexicon, J2), an ambiguous
	 * one (exactly one token shared, J3), or a registration. Similar and ambiguous are both returned as a candidate for
	 * the caller to confirm, never applied: token overlap cannot see meaning, and a restriction defined by the caller
	 * would otherwise be mapped onto the relation it restricts. Confirming a similar candidate records the name as an
	 * alias, so it is asked once. A bare name without a definition is compared by its words alone and registered from
	 * this use when nothing on record shares one: everything is inferred from the name, and a description can follow
	 * through {@code correct}.
	 */
	public synchronized Resolution resolve(String name, PredicateDef def, Long observationId, List<String> warnings) {
		if (name == null || name.isBlank()) {
			throw MnemicException.invalidArgument("A fact needs a 'predicate'. Example: \"predicate\": \"works_at\"");
		}
		if (name.startsWith("x:")) {
			name = name.substring(2); // how a 2.2 reading spelled a predicate it had not defined
		}
		Optional<Predicate> exact = get(name);
		if (exact.isPresent()) {
			return new Resolution(exact.get(), name.equals(exact.get().name()) ? "exact" : "alias", null);
		}
		if (def == null) {
			def = new PredicateDef(name, null, null, null, null, null, null, null, null, List.of(), null, List.of(),
					List.of());
		}
		Similar similar = similar(def);
		if (similar != null && (similar.overlap() >= SIMILAR_MIN_OVERLAP || def.description() == null)) {
			return new Resolution(null, "similar", similar.predicate());
		}
		if (similar != null && similar.overlap() == 1) {
			return new Resolution(null, "ambiguous", similar.predicate());
		}
		Nearest near = nearest(embeddingText(def), types(def.domain()), types(def.range()), null);
		if (near != null && near.similar()) {
			return new Resolution(null, "semantic", near.predicate());
		}
		if (near != null && near.ambiguous()) {
			return new Resolution(null, "ambiguous", near.predicate());
		}
		return new Resolution(register(def, observationId), bare(def) ? "inferred" : "registered", null);
	}

	/** A definition that states nothing beyond the name. */
	/** Whether a definition states anything beyond its name, rules, and implications. */
	static boolean statesMoreThanAdditions(PredicateDef d) {
		return d.description() != null || d.domain() != null || d.range() != null || d.functional() != null
				|| d.functionalScope() != null || d.symmetric() != null || d.inverse() != null || d.volatility() != null
				|| !d.lexicon().isEmpty() || d.render() != null || !d.qualifiers().isEmpty() || !d.aliases().isEmpty()
				|| !d.renders().isEmpty() || d.containment() != null;
	}

	static boolean bare(PredicateDef d) {
		return d.description() == null && d.domain() == null && d.range() == null && d.functional() == null
				&& d.functionalScope() == null && d.symmetric() == null && d.inverse() == null && d.volatility() == null
				&& d.lexicon().isEmpty() && d.render() == null && d.qualifiers().isEmpty() && d.aliases().isEmpty()
				&& d.renders().isEmpty() && d.definedAs() == null && d.implies() == null && d.containment() == null;
	}

	// ── meaning ──────────────────────────────────────────────────────────

	/** The closest predicate by meaning, its score, and its lead over the runner-up. */
	record Nearest(Predicate predicate, float score, float lead) {
		boolean similar() {
			return score >= SEMANTIC_SIMILAR && lead >= SEMANTIC_SIMILAR_LEAD;
		}

		boolean ambiguous() {
			return score >= SEMANTIC_AMBIGUOUS && lead >= SEMANTIC_AMBIGUOUS_LEAD;
		}
	}

	/** What a predicate means, as text for the embedding model: its name's words, its description, its lexicon. */
	static String embeddingText(Predicate p) {
		return embeddingText(p.name(), p.description(), p.lexicon());
	}

	/** A bare definition is embedded with the lexicon it would get, so it reads like the registered predicates do. */
	private static String embeddingText(PredicateDef d) {
		return embeddingText(d.name(), d.description(),
				d.lexicon().isEmpty() && d.name() != null ? Predicate.lexiconOf(d.name()) : d.lexicon());
	}

	private static String embeddingText(String name, String description, List<String> lexicon) {
		var sb = new StringBuilder(name == null ? "" : name.replace('_', ' '));
		if (description != null) {
			sb.append(". ").append(description);
		}
		if (!lexicon.isEmpty()) {
			sb.append(". ").append(String.join(", ", lexicon));
		}
		return sb.toString();
	}

	/**
	 * The registered predicate closest in meaning to a text, among those whose domain and range overlap the given ones
	 * and that are not {@code except}; null when no model is loaded or the registry is empty. Vectors are computed once
	 * per predicate and model and dropped when the predicate changes.
	 */
	private synchronized Nearest nearest(String text, List<String> domain, List<String> range, String except) {
		Embedding emb = embedding.get();
		if (emb == null) {
			return null;
		}
		if (!emb.id().equals(vectorModel)) {
			vectors.clear();
			vectorModel = emb.id();
		}
		float[] q = emb.embed(text);
		Predicate best = null;
		float bestScore = 0;
		float second = 0;
		for (Predicate p : load().values()) {
			if (p.name().equals(except) || p.literalRange() || !overlaps(p.domain(), domain)
					|| !overlaps(p.range(), range)) {
				continue;
			}
			float[] v = vectors.computeIfAbsent(p.name(), k -> emb.embed(embeddingText(p)));
			float score = Embedding.dot(q, v);
			if (score > bestScore) {
				second = bestScore;
				best = p;
				bestScore = score;
			} else if (score > second) {
				second = score;
			}
		}
		return best == null ? null : new Nearest(best, bestScore, bestScore - second);
	}

	/**
	 * Predicates registered from use that lie close in meaning to another predicate, each pair once:
	 * {@code {predicate, close_to, score}}. Empty without a model.
	 */
	public synchronized List<Map<String, Object>> closePairs() {
		var out = new ArrayList<Map<String, Object>>();
		var seen = new HashSet<String>();
		for (Predicate p : load().values()) {
			if (!p.isInferred()) {
				continue;
			}
			Nearest n = nearest(embeddingText(p), p.domain(), p.range(), p.name());
			if (n == null || !n.ambiguous()) {
				continue;
			}
			String pair = p.name().compareTo(n.predicate().name()) < 0 ? p.name() + "|" + n.predicate().name()
					: n.predicate().name() + "|" + p.name();
			if (!seen.add(pair)) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("predicate", p.name());
			m.put("close_to", n.predicate().name());
			m.put("score", Math.round(n.score() * 100) / 100.0);
			out.add(m);
		}
		return out;
	}

	/**
	 * Folds one predicate into another: its facts are re-keyed, its name and aliases become aliases of the target,
	 * event types that named it name the target, and its registration goes. The count of facts moved.
	 */
	public synchronized int merge(String from, String into, String reason) {
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
		vectors.remove(f.name());
		vectors.remove(t.name());
		cache = null;
		return moved;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		var out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	/**
	 * Predicate cues in a query: lexicon and qualifier terms, longest first, one cue per predicate. Terms are compared
	 * token by token, so a hyphenated qualifier (half-brother) matches the query's tokens.
	 */
	public synchronized List<Cue> cues(String query) {
		String q = " " + String.join(" ", Names.tokens(query)) + " ";
		var out = new LinkedHashMap<String, Cue>();
		var freeCued = new HashSet<String>();
		var viaInverse = new HashSet<String>();
		Map<String, List<String>> inUse = qualifiersInUse();
		for (Predicate p : load().values()) {
			Cue best = null;
			// A predicate with a vocabulary is cued by it; one that takes free text is cued by the qualifiers actually
			// stored under it ("cousin" on related_to), from either side.
			boolean free = p.qualifiers().isEmpty();
			for (String term : free ? inUse.getOrDefault(p.name(), List.of()) : p.qualifiers()) {
				if (free) {
					if (q.contains(" " + String.join(" ", Names.tokens(term)) + " ")
							&& (best == null || term.length() > best.term().length())) {
						best = new Cue(p, term, term, "any");
						freeCued.add(p.name());
					}
					continue;
				}
				if (q.contains(" " + String.join(" ", Names.tokens(term)) + " ")
						&& (best == null || term.length() > best.term().length())) {
					best = new Cue(p, term, term, p.symmetric() ? "any" : "object");
				}
			}
			for (String term : p.inverseLexicon()) {
				if (q.contains(" " + String.join(" ", Names.tokens(term)) + " ")
						&& (best == null || term.length() > best.term().length())) {
					best = new Cue(p, null, term, p.symmetric() ? "any" : "subject");
					viaInverse.add(p.name());
				}
			}
			if (best == null) {
				var terms = new ArrayList<>(p.lexicon());
				for (String alias : p.aliases()) {
					terms.addAll(Predicate.lexiconOf(alias)); // a name the caller once used, in any language
				}
				for (String term : terms) {
					if (q.contains(" " + String.join(" ", Names.tokens(term)) + " ")
							&& (best == null || term.length() > best.term().length())) {
						best = new Cue(p, null, term, !p.symmetric() && p.sameType() ? "object" : "any");
					}
				}
			}
			if (best != null) {
				out.put(p.name(), best);
			}
		}
		var cues = new ArrayList<>(out.values());
		// Longest term first; at equal length a predicate's own vocabulary outranks a free qualifier in use, so
		// "stepfather" reaches step_parent_of before a related_to[stepfather] somebody once stored.
		// Longest term first; at equal length a word in a predicate's own vocabulary outranks the same word known
		// only as another predicate's inverse ("nephews" reaches nibling_of before aunt_uncle_of), and both outrank
		// a free qualifier in use.
		cues.sort((a, b) -> {
			int byLength = Integer.compare(b.term().length(), a.term().length());
			if (byLength != 0) {
				return byLength;
			}
			int byOwn = Boolean.compare(viaInverse.contains(a.predicate().name()),
					viaInverse.contains(b.predicate().name()));
			return byOwn != 0 ? byOwn
					: Boolean.compare(freeCued.contains(a.predicate().name()), freeCued.contains(b.predicate().name()));
		});
		return cues;
	}

	/** Whether a predicate's own vocabulary (qualifiers, lexicon) names a role, whole or by one of its words. */
	public static boolean namesRole(Predicate p, String role) {
		if (role == null || role.isBlank()) {
			return false;
		}
		String r = role.trim().toLowerCase(Locale.ROOT);
		var words = new ArrayList<>(List.of(r));
		words.addAll(List.of(r.split("\\s+")));
		for (String w : words) {
			if (p.qualifiers().contains(w) || p.lexicon().contains(w)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The predicate a free-text role belongs to (partner → partner_of, aunt → aunt_uncle_of): one with a vocabulary of
	 * its own that names the role, the whole role before a word of it. Empty when none does.
	 */
	public synchronized Optional<Predicate> dedicatedFor(String role) {
		if (role == null || role.isBlank()) {
			return Optional.empty();
		}
		String r = role.trim().toLowerCase(Locale.ROOT);
		List<Predicate> dedicated = load().values().stream().filter(p -> !p.qualifiers().isEmpty()).toList();
		for (Predicate p : dedicated) {
			if (p.qualifiers().contains(r) || p.lexicon().contains(r)) {
				return Optional.of(p);
			}
		}
		for (Predicate p : dedicated) {
			if (namesRole(p, r)) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}

	/** The qualifiers stored on current facts, by predicate: the words a free-text qualifier gave a relation. */
	private Map<String, List<String>> qualifiersInUse() {
		var out = new HashMap<String, List<String>>();
		for (Row r : db.read(tx -> tx.query(
				"SELECT DISTINCT predicate, qualifier FROM fact WHERE qualifier IS NOT NULL AND status = 'current'"))) {
			out.computeIfAbsent(r.str("predicate"), k -> new ArrayList<>()).add(r.str("qualifier"));
		}
		return out;
	}

	/** Predicates registered from use and not yet described, with the facts that use them (J6). */
	public synchronized List<Map<String, Object>> inferred() {
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
	}

	// ── write ────────────────────────────────────────────────────────────

	public synchronized Predicate register(PredicateDef def, Long observationId) {
		if (def.name() == null || def.name().isBlank()) {
			throw MnemicException.invalidArgument("A predicate definition needs a 'name'.");
		}
		String name = def.name().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		Predicate existing = load().get(name);
		if (existing != null) {
			if (existing.isInferred() && !bare(def)) {
				return define(existing, def);
			}
			// A predicate already defined keeps its definition, but a proposal may add what the derivations rest
			// on: rules and implications. Silence here once cost a caller a round of debugging.
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
				: "{subject} " + name.replace('_', ' ') + " {object}";
		// Without cue words the name supplies them: its words become the search terms.
		List<String> lexicon = def.lexicon().isEmpty() ? Predicate.lexiconOf(name) : def.lexicon();
		var p = new Predicate(name, def.description(), domain, range, Boolean.TRUE.equals(def.functional()),
				def.functionalScope(), Boolean.TRUE.equals(def.symmetric()), def.inverse(),
				def.volatility() == null ? "medium" : def.volatility(), lexicon, render, def.qualifiers(),
				def.aliases(), List.of(), observationId, false, bare(def), Boolean.TRUE.equals(def.containment()));
		List<Rule> parsed = def.definedAs() == null ? List.of() : Rule.parse(def.definedAs());
		validateRules(name, parsed);
		Map<String, Map<String, String>> implied = parseImplies(name, def.implies());
		insert(p);
		if (!parsed.isEmpty()) {
			setRules(name, parsed);
		}
		if (!implied.isEmpty()) {
			setImplies(name, implied);
		}
		putRenders(name, def.renders());
		return get(name).orElseThrow();
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
		return update(p.name(), replacement, "defined after registration from use");
	}

	/**
	 * Corrects a predicate property (J5): {@code render}, {@code lexicon}, {@code qualifiers}, {@code functional},
	 * {@code symmetric}, {@code volatility}, {@code description}, {@code domain}, {@code range}. Every change is
	 * logged; the caller re-renders the facts.
	 */
	public synchronized Predicate update(String name, Map<String, Object> replacement, String reason) {
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
		var changes = new ArrayList<String[]>();
		for (Map.Entry<String, Object> e : replacement.entrySet()) {
			String old;
			switch (e.getKey()) {
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
					+ "functional, symmetric, volatility, defined_as, implies, containment.");
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
		db.write(tx -> {
			tx.update(
					"""
							UPDATE predicate SET render = ?, lexicon = ?, qualifiers = ?, functional = ?, symmetric = ?, volatility = ?,
							description = ?, inverse_lexicon = ?, domain = ?, range = ?, containment = ?, inferred = 0
							WHERE name = ?""",
					r, json(l), json(q), f ? 1 : 0, sym ? 1 : 0, v, d, json(inv), json(dom), json(rng), cont ? 1 : 0,
					p.name());
			if (newRule != null) {
				tx.update("UPDATE predicate SET rule = ? WHERE name = ?", newRule, p.name());
			}
			if (setImplied) {
				tx.update("UPDATE predicate SET implies = ? WHERE name = ?", implied, p.name());
			}
			for (String[] c : changes) {
				tx.insert("INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at) "
						+ "VALUES (?,?,?,?,?,?)", p.name(), c[0], c[1], c[2], reason, Instant.now().toString());
			}
			return null;
		});
		vectors.remove(p.name());
		cache = null;
		return get(p.name()).orElseThrow();
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
	public synchronized void addAlias(Predicate p, String alias) {
		var aliases = new ArrayList<>(p.aliases());
		if (!aliases.contains(alias)) {
			aliases.add(alias);
			db.write(tx -> tx.update("UPDATE predicate SET aliases = ? WHERE name = ?", json(aliases), p.name()));
			cache = null;
		}
	}

	record Similar(Predicate predicate, int overlap) {
	}

	/** The best existing predicate with compatible domain and range, by content-token overlap; null when none. */
	private Similar similar(PredicateDef def) {
		List<String> domain = types(def.domain());
		List<String> range = types(def.range());
		var proposedTokens = new HashSet<String>();
		proposedTokens.addAll(Names.contentTokens(def.name() == null ? "" : def.name().replace('_', ' ')));
		proposedTokens.addAll(Names.contentTokens(def.description()));
		for (String l : def.lexicon()) {
			proposedTokens.addAll(Names.contentTokens(l));
		}
		Predicate best = null;
		int bestScore = 0;
		boolean tie = false;
		for (Predicate p : load().values()) {
			if (!overlaps(p.domain(), domain) || !overlaps(p.range(), range)) {
				continue;
			}
			var tokens = new HashSet<String>();
			tokens.addAll(Names.contentTokens(p.name().replace('_', ' ')));
			tokens.addAll(Names.contentTokens(p.description()));
			for (String l : p.lexicon()) {
				tokens.addAll(Names.contentTokens(l));
			}
			tokens.retainAll(proposedTokens);
			int score = tokens.size();
			if (score > bestScore) {
				best = p;
				bestScore = score;
				tie = false;
			} else if (score == bestScore && score > 0) {
				tie = true;
			}
		}
		return best == null || tie ? null : new Similar(best, bestScore);
	}

	private static boolean overlaps(List<String> a, List<String> b) {
		if (a.contains("*") || b.contains("*")) {
			return true;
		}
		for (String x : a) {
			if (b.contains(x)) {
				return true;
			}
		}
		return false;
	}

	// ── seed ─────────────────────────────────────────────────────────────

	/** Seed predicates that took over a same-named predicate registered from use at this start. */
	private final List<String> adopted = new ArrayList<>();

	public synchronized List<String> adoptedAtStart() {
		return List.copyOf(adopted);
	}

	private void seedIfMissing() {
		Map<String, Predicate> existing = load();
		for (Predicate p : seed()) {
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
								                     inverse_lexicon = ?, seed = 1, inferred = 0 WHERE name = ?""",
						p.description(), json(p.domain()), json(p.range()), p.functional() ? 1 : 0, p.functionalScope(),
						p.symmetric() ? 1 : 0, p.volatility(), json(p.lexicon()), p.render(), json(p.qualifiers()),
						json(p.inverseLexicon()), p.name()));
				adopted.add(p.name());
				cache = null;
			}
		}
		// The seed rules (family K): a seed predicate carries the seed's rules unless the user replaced them (a
		// defined_as change on its log), so a store from before a rule existed or changed takes it at this start.
		for (Map.Entry<String, List<Map<String, Object>>> e : seedRules().entrySet()) {
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
		// The seed implications, the same way.
		for (Map.Entry<String, Map<String, Object>> e : seedImplies().entrySet()) {
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

	/** One rule of a seed predicate: a path, the qualifier, its gendered forms, and predicates that must not hold. */
	private static Map<String, Object> rule(String path, String qualifier, String female, String male, String ... not) {
		var m = new LinkedHashMap<String, Object>();
		m.put("path", List.of(path.split(",\\s*")));
		if (not.length > 0) {
			m.put("not", List.of(not));
		}
		if (qualifier != null) {
			m.put("qualifier", qualifier);
		}
		if (female != null || male != null) {
			var values = new LinkedHashMap<String, String>();
			if (female != null) {
				values.put("female", female);
			}
			if (male != null) {
				values.put("male", male);
			}
			var by = new LinkedHashMap<String, Object>();
			by.put("attribute", "gender");
			by.put("values", values);
			m.put("by", by);
		}
		return m;
	}

	private static Map<String, Object> withPaths(
		Map<String, Object> rule, Integer minPaths, Integer maxPaths, Integer minDegree) {
		if (minPaths != null) {
			rule.put("min_paths", minPaths);
		}
		if (maxPaths != null) {
			rule.put("max_paths", maxPaths);
		}
		if (minDegree != null) {
			rule.put("min_degree", minDegree);
		}
		return rule;
	}

	/** {@code term → gender} pairs as an implies map. */
	private static Map<String, Object> genders(String ... termAndGender) {
		var m = new LinkedHashMap<String, Object>();
		for (int i = 0; i + 1 < termAndGender.length; i += 2) {
			m.put(termAndGender[i], Map.of("gender", termAndGender[i + 1]));
		}
		return m;
	}

	/**
	 * What the seed vocabulary's terms imply about the subject (family K): a mother is female, a husband male. Sibling
	 * terms imply nothing here, since a reading too often attaches them to the wrong side; a store may add them through
	 * correct(pred:sibling_of, {implies}).
	 */
	static Map<String, Map<String, Object>> seedImplies() {
		var out = new LinkedHashMap<String, Map<String, Object>>();
		out.put("gender", genders("female", "female", "male", "male", "woman", "female", "man", "male", "f", "female",
				"m", "male", "girl", "female", "boy", "male"));
		out.put("parent_of", genders("mother", "female", "father", "male", "mom", "female", "dad", "male"));
		out.put("step_parent_of", genders("stepmother", "female", "stepfather", "male"));
		out.put("spouse_of", genders("wife", "female", "husband", "male"));
		out.put("partner_of", genders("girlfriend", "female", "boyfriend", "male"));
		out.put("engaged_to", genders("fiancée", "female", "fiancé", "male"));
		out.put("grandparent_of",
				genders("grandmother", "female", "grandfather", "male", "maternal grandmother", "female",
						"paternal grandmother", "female", "maternal grandfather", "male", "paternal grandfather",
						"male"));
		out.put("aunt_uncle_of", genders("aunt", "female", "uncle", "male"));
		out.put("in_law_of", genders("mother-in-law", "female", "father-in-law", "male", "sister-in-law", "female",
				"brother-in-law", "male", "daughter-in-law", "female", "son-in-law", "male"));
		return out;
	}

	/**
	 * The kinship rules (family K): what the seed vocabulary derives from parent_of, spouse_of, partner_of, and
	 * sibling_of. Written from the subject to the object: a grandparent is a parent of a parent of the object, with the
	 * side named after whose parent the middle person is.
	 */
	static Map<String, List<Map<String, Object>>> seedRules() {
		var rules = new LinkedHashMap<String, List<Map<String, Object>>>();
		rules.put("grandparent_of",
				List.of(rule("parent_of, parent_of[mother]", "maternal grandparent", "maternal grandmother",
						"maternal grandfather"),
						rule("parent_of, parent_of[father]", "paternal grandparent", "paternal grandmother",
								"paternal grandfather"),
						rule("parent_of, parent_of", "grandparent", "grandmother", "grandfather")));
		// Two shared parents: siblings. One, with two parents known on each side: half siblings. Too little known
		// of either: siblings, and no word about it.
		rules.put("sibling_of", List.of(
				withPaths(rule("^parent_of, parent_of", "sibling", "sister", "brother"), 2, null, null),
				withPaths(rule("^parent_of, parent_of", "half-sibling", "half-sister", "half-brother"), null, 1, 2),
				rule("^parent_of, parent_of", "sibling", null, null)));
		rules.put("step_parent_of",
				List.of(rule("spouse_of, parent_of", "step-parent", "stepmother", "stepfather", "parent_of"),
						rule("partner_of, parent_of", "step-parent", "stepmother", "stepfather", "parent_of")));
		rules.put("aunt_uncle_of", List.of(rule("sibling_of, parent_of", "aunt or uncle", "aunt", "uncle", "parent_of"),
				rule("spouse_of, sibling_of, parent_of", "aunt or uncle", "aunt", "uncle", "parent_of")));
		rules.put("cousin_of", List.of(rule("^parent_of, sibling_of, parent_of", "cousin", null, null, "sibling_of")));
		rules.put("in_law_of",
				List.of(rule("parent_of, spouse_of", "parent-in-law", "mother-in-law", "father-in-law"),
						rule("spouse_of, ^parent_of", "child-in-law", "daughter-in-law", "son-in-law"),
						rule("sibling_of, spouse_of", "sibling-in-law", "sister-in-law", "brother-in-law"),
						rule("spouse_of, sibling_of", "sibling-in-law", "sister-in-law", "brother-in-law")));
		return rules;
	}

	/** The seed vocabulary (EXTRACTION.md). It is the initial content of the registry, nothing more. */
	static List<Predicate> seed() {
		return List.of(
				seed("works_at", "Subject works for object organization.", List.of("person"), List.of("organization"),
						true, null, false, "high", List.of("work", "works", "worked", "working", "employer", "employed",
								"job", "company"),
						"{subject} works at {object}", List.of()),
				seed("gender",
						"Subject's gender, as a literal: female, male, or the word given. The kinship rules read it ahead of the roles the subject holds, so state it when a person is nobody's mother, father, wife, or husband on record.",
						List.of("person"), List.of("literal"), true, null, false, "low",
						List.of("gender", "male", "female", "man", "woman", "sex"), "{subject} is {object}", List.of()),
				seed("holds_role", "Subject holds the role (object, literal) at the scope organization.",
						List.of("person"), List.of("literal"), true, "scope", false, "high",
						List.of("role", "title", "position", "director", "vp", "manager", "engineer", "ceo", "cto"),
						"{subject} holds the role {object}[[ at {scope}]]", List.of()),
				seed("leads", "Subject leads object project, team, product, or organization.", List.of("person"),
						List.of("project", "team", "product", "organization"), false, null, false, "high",
						List.of("lead", "leads", "leading", "led", "head", "heads"), "{subject} leads {object}",
						List.of()),
				seed("lives_in", "Subject lives in object place.", List.of("person"), List.of("place"), true, null,
						false, "high", List.of("live", "lives", "lived", "living", "home", "reside", "resides"),
						"{subject} lives in {object}", List.of()),
				seed("born_in", "Subject was born in object place.", List.of("person"), List.of("place"), true, null,
						false, "low", List.of("born", "birthplace"), "{subject} was born in {object}", List.of()),
				seed("member_of", "Subject is a member of object organization or group.", List.of("person"),
						List.of("organization", "team", "group", "project"), false, null, false, "medium",
						List.of("member", "membership", "belongs"), "{subject} is a member of {object}", List.of()),
				seed("parent_of",
						"Subject is a parent of object, and the qualifier names the subject's role (mother, father).",
						List.of("person"), List.of("person"), false, null, false, "low", List.of("parent", "parents"),
						"{subject} is {object}'s {qualifier|parent}", List.of("mother", "father", "mom", "dad"),
						List.of("child", "children", "kid", "kids", "son", "sons", "daughter", "daughters",
								"offspring")),
				seed("spouse_of",
						"Subject is married to object, and the qualifier names the subject's role (wife, husband). Stored once, found from both sides.",
						List.of("person"), List.of("person"), true, null, true, "low",
						List.of("spouse", "married", "marry"), "{subject} is {object}'s {qualifier|spouse}",
						List.of("wife", "husband")),
				seed("partner_of",
						"Subject and object are a couple without being married, and the qualifier names the subject's role (girlfriend, boyfriend, partner). Stored once, found from both sides; a wedding ends it and opens spouse_of.",
						List.of("person"), List.of("person"), true, null, true, "medium",
						List.of("partner", "partners", "girlfriend", "boyfriend", "sambo", "couple", "together"),
						"{subject} is {object}'s {qualifier|partner}", List.of("girlfriend", "boyfriend", "partner")),
				seed("engaged_to",
						"Subject is engaged to marry object. Stored once, found from both sides; a wedding ends it.",
						List.of("person"), List.of("person"), true, null, true, "high",
						List.of("engaged", "engagement", "fiancé", "fiancée", "fiance", "fiancee", "betrothed"),
						"{subject} is engaged to {object}", List.of("fiancé", "fiancée")),
				seed("step_parent_of",
						"Subject is a step-parent of object: the spouse or partner of a parent, and not a parent. The qualifier names the subject's role (stepmother, stepfather).",
						List.of("person"), List.of("person"), false, null, false, "low",
						List.of("stepparent", "step-parent", "stepmother", "stepfather", "stepmom", "stepdad"),
						"{subject} is {object}'s {qualifier|step-parent}", List.of("stepmother", "stepfather"),
						List.of("stepchild", "stepchildren", "stepson", "stepdaughter")),
				seed("grandparent_of",
						"Subject is a parent of a parent of object; derived from parent_of, never stated when the parents are known. The qualifier names the subject's role and side (paternal grandmother).",
						List.of("person"), List.of("person"), false, null, false, "low", List.of("grandparent",
								"grandparents", "grandmother", "grandfather", "grandma", "grandpa", "granny"),
						"{subject} is {object}'s {qualifier|grandparent}",
						List.of("grandmother", "grandfather", "maternal grandmother", "paternal grandmother",
								"maternal grandfather", "paternal grandfather"),
						List.of("grandchild", "grandchildren", "grandson", "granddaughter")),
				seed("aunt_uncle_of",
						"Subject is a sibling of a parent of object, or married to one; derived from sibling_of, parent_of, and spouse_of. The qualifier names the subject's role (aunt, uncle).",
						List.of("person"), List.of("person"), false, null, false, "low",
						List.of("aunt", "aunts", "uncle", "uncles", "auntie"),
						"{subject} is {object}'s {qualifier|aunt or uncle}", List.of("aunt", "uncle"),
						List.of("nephew", "nephews", "niece", "nieces")),
				seed("cousin_of",
						"Subject and object are children of siblings; derived from parent_of and sibling_of. Stored once, found from both sides.",
						List.of("person"), List.of("person"), false, null, true, "low",
						List.of("cousin", "cousins", "first cousin"), "{subject} is {object}'s {qualifier|cousin}",
						List.of("cousin")),
				seed("in_law_of",
						"Subject is related to object through object's marriage or a sibling's; derived from spouse_of, parent_of, and sibling_of. The qualifier names the subject's role (mother-in-law, son-in-law, brother-in-law).",
						List.of("person"), List.of("person"), false, null, false, "medium",
						List.of("in-law", "in-laws", "mother-in-law", "father-in-law", "parents-in-law", "son-in-law",
								"daughter-in-law", "brother-in-law", "sister-in-law"),
						"{subject} is {object}'s {qualifier|in-law}",
						List.of("mother-in-law", "father-in-law", "parent-in-law", "son-in-law", "daughter-in-law",
								"child-in-law", "brother-in-law", "sister-in-law", "sibling-in-law"),
						List.of()),
				seed("sibling_of",
						"Subject is a sibling of object, and the qualifier names the subject's role (brother, half-sister). Stored once, found from both sides; derived from parent_of when a parent is shared.",
						List.of("person"), List.of("person"), false, null, true, "low", List.of("sibling", "siblings"),
						"{subject} is {object}'s {qualifier|sibling}",
						List.of("brother", "sister", "twin", "twin brother", "twin sister", "half-brother",
								"half-sister", "stepbrother", "stepsister")),
				seed("owns", "Subject owns object.", List.of("*"), List.of("*"), false, null, false, "medium",
						List.of("own", "owns", "owned", "buy", "bought", "purchase", "purchased", "acquired"),
						"{subject} owns {object}", List.of()),
				seed("prefers", "Subject prefers object.", List.of("person"), List.of("*"), false, null, false,
						"medium", List.of("prefer", "prefers", "favourite", "favorite", "likes", "like", "enjoys"),
						"{subject} prefers {object}", List.of()),
				seed("dislikes", "Subject dislikes object.", List.of("person"), List.of("*"), false, null, false,
						"medium", List.of("dislike", "dislikes", "hates", "hate"), "{subject} dislikes {object}",
						List.of()),
				seed("uses", "Subject uses object technology or thing.", List.of("*"), List.of("*"), false, null, false,
						"medium", List.of("use", "uses", "using", "targets", "target", "built"),
						"{subject} uses {object}", List.of()),
				seed("decided", "Subject made the decision object (literal).", List.of("*"), List.of("literal"), false,
						null, false, "low",
						List.of("decide", "decided", "decision", "chose", "choice", "pick", "picked"),
						"{subject} decided {object}", List.of()),
				seed("considering",
						"Subject is considering object (literal): a leaning, plan, or intention, not a decision.",
						List.of("*"), List.of("literal"), false, null, false, "high",
						List.of("considering", "consider", "considers", "leaning", "intend", "intends", "intention",
								"plan", "plans", "planning", "weighing", "thinking"),
						"{subject} is considering {object}", List.of()),
				seed("related_to", "Subject is related to object (generic).", List.of("*"), List.of("*"), false, null,
						true, "medium", List.of("related", "relation"),
						"{subject} is related to {object}[[ ({qualifier})]]", List.of()),
				seed("knows", "Subject knows object person.", List.of("person"), List.of("person"), false, null, true,
						"medium", List.of("know", "knows", "met", "colleague"),
						"{subject} knows {object}[[ ({qualifier})]]", List.of()),
				seed("part_of", "Subject is part of object.", List.of("*"), List.of("*"), false, null, false, "medium",
						List.of("part", "within"), "{subject} is part of {object}", List.of()),
				seed("located_in",
						"Subject place or organization is located in object place, and places nest "
								+ "(a town in a canton in a country), so several current values are expected.",
						List.of("place", "organization"), List.of("place"), false, null, false, "low",
						List.of("located", "headquartered", "headquarters", "based"),
						"{subject} is located in {object}", List.of()));
	}

	private static Predicate seed(
		String name, String description, List<String> domain, List<String> range, boolean functional, String scope,
		boolean symmetric, String volatility, List<String> lexicon, String render, List<String> qualifiers) {
		return seed(name, description, domain, range, functional, scope, symmetric, volatility, lexicon, render,
				qualifiers, List.of());
	}

	private static Predicate seed(
		String name, String description, List<String> domain, List<String> range, boolean functional, String scope,
		boolean symmetric, String volatility, List<String> lexicon, String render, List<String> qualifiers,
		List<String> inverseLexicon) {
		return new Predicate(name, description, domain, range, functional, scope, symmetric, null, volatility, lexicon,
				render, qualifiers, List.of(), inverseLexicon, null, true, false, CONTAINMENT_SEEDS.contains(name));
	}

	/** The seed predicates whose facts nest the subject inside the object. */
	private static final Set<String> CONTAINMENT_SEEDS = Set.of("located_in", "part_of");

	// ── persistence ──────────────────────────────────────────────────────

	private Map<String, Predicate> load() {
		if (cache == null) {
			var map = new LinkedHashMap<String, Predicate>();
			var ruleMap = new HashMap<String, List<Rule>>();
			var impliesMap = new HashMap<String, Map<String, Map<String, String>>>();
			for (Row r : db.read(tx -> tx.query("SELECT * FROM predicate ORDER BY seed DESC, name"))) {
				map.put(r.str("name"), from(r));
				if (r.str("rule") != null) {
					ruleMap.put(r.str("name"), Rule.fromJson(r.str("rule")));
				}
				if (r.str("implies") != null) {
					impliesMap.put(r.str("name"), impliesFromJson(r.str("implies")));
				}
			}
			rules = ruleMap;
			implies = impliesMap;
			negated.clear();
			if (lang != Lang.EN) {
				// The store's language: its template replaces the base rendering, its cue words join the base ones.
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
									p.isInferred(), p.containment()));
					if (r.str("negated") != null) {
						negated.put(p.name(), r.str("negated"));
					}
				}
			}
			cache = map;
		}
		return cache;
	}

	private void insert(Predicate p) {
		db.write(tx -> insert(tx, p));
		cache = null;
	}

	private static Object insert(Tx tx, Predicate p) {
		tx.insert("""
				INSERT INTO predicate(name, description, domain, range, functional, functional_scope, symmetric,
				                      inverse, volatility, lexicon, render, qualifiers, aliases, inverse_lexicon,
				                      defined_by, seed, inferred, created_at, containment)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", p.name(), p.description(), json(p.domain()),
				json(p.range()), p.functional() ? 1 : 0, p.functionalScope(), p.symmetric() ? 1 : 0, p.inverse(),
				p.volatility(), json(p.lexicon()), p.render(), json(p.qualifiers()), json(p.aliases()),
				json(p.inverseLexicon()), p.definedBy(), p.seed() ? 1 : 0, p.isInferred() ? 1 : 0,
				Instant.now().toString(), p.containment() ? 1 : 0);
		return null;
	}

	private static Predicate from(Row r) {
		return new Predicate(r.str("name"), r.str("description"), list(r.str("domain")), list(r.str("range")),
				r.lng("functional") == 1, r.str("functional_scope"), r.lng("symmetric") == 1, r.str("inverse"),
				r.str("volatility"), list(r.str("lexicon")), r.str("render"), list(r.str("qualifiers")),
				list(r.str("aliases")), list(r.str("inverse_lexicon")), r.lngOrNull("defined_by"), r.lng("seed") == 1,
				r.lng("inferred") == 1, r.lng("containment") == 1);
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

	private static List<String> strings(Object value) {
		if (value instanceof List<?> l) {
			return l.stream().map(String::valueOf).toList();
		}
		return List.of(String.valueOf(value).split("\\s*,\\s*"));
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
