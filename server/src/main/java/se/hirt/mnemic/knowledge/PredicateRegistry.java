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
	static boolean bare(PredicateDef d) {
		return d.description() == null && d.domain() == null && d.range() == null && d.functional() == null
				&& d.functionalScope() == null && d.symmetric() == null && d.inverse() == null && d.volatility() == null
				&& d.lexicon().isEmpty() && d.render() == null && d.qualifiers().isEmpty() && d.aliases().isEmpty()
				&& d.renders().isEmpty();
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
		cues.sort((a, b) -> Integer.compare(b.term().length(), a.term().length()));
		return cues;
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
			return existing.isInferred() && !bare(def) ? define(existing, def) : existing;
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
				def.aliases(), List.of(), observationId, false, bare(def));
		insert(p);
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
		var changes = new ArrayList<String[]>();
		for (Map.Entry<String, Object> e : replacement.entrySet()) {
			String old;
			switch (e.getKey()) {
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
					+ "functional, symmetric, volatility.");
			}
			changes.add(new String[] {e.getKey(), old,
					e.getValue() instanceof List<?> ? json(strings(e.getValue())) : String.valueOf(e.getValue())});
		}
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
							description = ?, inverse_lexicon = ?, domain = ?, range = ?, inferred = 0 WHERE name = ?""",
					r, json(l), json(q), f ? 1 : 0, sym ? 1 : 0, v, d, json(inv), json(dom), json(rng), p.name());
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

	private void seedIfMissing() {
		Set<String> existing = load().keySet();
		for (Predicate p : seed()) {
			if (!existing.contains(p.name())) {
				insert(p);
			}
		}
	}

	/** The seed vocabulary (EXTRACTION.md). It is the initial content of the registry, nothing more. */
	static List<Predicate> seed() {
		return List.of(
				seed("works_at", "Subject works for object organization.", List.of("person"), List.of("organization"),
						true, null, false, "high",
						List.of("work", "works", "worked", "working", "employer", "employed", "job", "company"),
						"{subject} works at {object}", List.of()),
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
						"{subject} is {object}'s {qualifier|parent}",
						List.of("mother", "father", "stepmother", "stepfather", "mom", "dad"),
						List.of("child", "children", "kid", "kids", "son", "sons", "daughter", "daughters",
								"offspring")),
				seed("spouse_of",
						"Subject is married to object, and the qualifier names the subject's role (wife, husband). Stored once, found from both sides.",
						List.of("person"), List.of("person"), true, null, true, "low",
						List.of("spouse", "married", "marry", "partner"), "{subject} is {object}'s {qualifier|spouse}",
						List.of("wife", "husband")),
				seed("sibling_of",
						"Subject is a sibling of object, and the qualifier names the subject's role (brother, half-sister). Stored once, found from both sides.",
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
				render, qualifiers, List.of(), inverseLexicon, null, true, false);
	}

	// ── persistence ──────────────────────────────────────────────────────

	private Map<String, Predicate> load() {
		if (cache == null) {
			var map = new LinkedHashMap<String, Predicate>();
			for (Row r : db.read(tx -> tx.query("SELECT * FROM predicate ORDER BY seed DESC, name"))) {
				map.put(r.str("name"), from(r));
			}
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
					map.put(p.name(), new Predicate(p.name(), p.description(), p.domain(), p.range(), p.functional(),
							p.functionalScope(), p.symmetric(), p.inverse(), p.volatility(), lexicon, r.str("render"),
							p.qualifiers(), p.aliases(), inverse, p.definedBy(), p.seed(), p.isInferred()));
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
				                      defined_by, seed, inferred, created_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", p.name(), p.description(), json(p.domain()),
				json(p.range()), p.functional() ? 1 : 0, p.functionalScope(), p.symmetric() ? 1 : 0, p.inverse(),
				p.volatility(), json(p.lexicon()), p.render(), json(p.qualifiers()), json(p.aliases()),
				json(p.inverseLexicon()), p.definedBy(), p.seed() ? 1 : 0, p.isInferred() ? 1 : 0,
				Instant.now().toString());
		return null;
	}

	private static Predicate from(Row r) {
		return new Predicate(r.str("name"), r.str("description"), list(r.str("domain")), list(r.str("range")),
				r.lng("functional") == 1, r.str("functional_scope"), r.lng("symmetric") == 1, r.str("inverse"),
				r.str("volatility"), list(r.str("lexicon")), r.str("render"), list(r.str("qualifiers")),
				list(r.str("aliases")), list(r.str("inverse_lexicon")), r.lngOrNull("defined_by"), r.lng("seed") == 1,
				r.lng("inferred") == 1);
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
