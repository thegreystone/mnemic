/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-language query cues, fact-rendering templates, family qualifiers, plural rules, and seed predicate translations,
 * loaded from classpath resources ({@code lang/<code>.json}). Parsed via {@link JsonNode} so GraalVM native images need
 * no reflection metadata.
 */
public final class LanguagePack {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern NEVER = Pattern.compile("(?!)");
	private static final Set<String> UNIVERSAL_OF = Set.of("de", "du", "di");

	/** A morphological suffix rule turning a plural/definite word into a candidate singular form. */
	public record PluralRule(String suffix, String replace, int minLength, String notEnding) {
		/** Applies the rule when {@code word} qualifies, returning the candidate form or null. */
		public String apply(String word) {
			if (word == null || word.length() < minLength || !word.endsWith(suffix)) {
				return null;
			}
			if (notEnding != null && !notEnding.isEmpty() && word.endsWith(notEnding)) {
				return null;
			}
			return word.substring(0, word.length() - suffix.length()) + replace;
		}
	}

	/** One seed predicate's localized rendering templates and cue vocabulary. */
	public record PredicateRender(String name, String render, String negated, List<String> lexicon,
			List<String> inverseLexicon) {
	}

	/** Language-specific question heuristics used by {@code Query} and {@code StructuredProbe}. */
	/**
	 * {@code tenseFiller}: words that only place a question in the present ("current", "now"), which the valid-time
	 * rules already do; as search terms they matched every observation that said "current" about anything.
	 * {@code homographs}: a word and the words before which it is another word ("own" after "my", "his": the adjective,
	 * not the verb the {@code owns} cue reads). {@code kindCopulaPattern}: "what are my cars", the kind after the
	 * copula. {@code determiners}: the articles and possessives that make a role word a common noun ("the mother", "his
	 * brother"); without one, a role word stands as a name does ("what car does mom drive") and is the owner's.
	 */
	public record QueryCues(Set<String> markers, Set<String> questionWords, Set<String> ofWords, Set<String> andWords,
			Set<String> polar, Set<String> past, Set<String> perfectAuxiliaries, Set<String> pastParticiples,
			Set<String> pastMarkers, String ambiguousWhereWasWord, Set<String> ambiguousWhereWasVerbs,
			Pattern forwardPattern, Set<String> polarFiller, Set<String> kindFiller, Pattern kindPhrasePattern,
			Set<String> selfPronouns, Set<String> tenseFiller, Map<String, Set<String>> homographs,
			Pattern kindCopulaPattern, Set<String> determiners) {

		/**
		 * Whether {@code first} followed by {@code second} is an ambiguous where/was opener acting as "where" (e.g.
		 * Swedish "Var bor Mattias?" vs "Var Mattias hemma?"), so it is neither polar nor past.
		 */
		public boolean isAmbiguousWhereWas(String first, String second) {
			return ambiguousWhereWasWord != null && ambiguousWhereWasWord.equals(first) && second != null
					&& ambiguousWhereWasVerbs.contains(second);
		}
	}

	/** Language-specific wording around rendered facts (temporal suffixes, qualifiers, closures, prepositions). */
	public record FactTemplates(String since, String fromEnded, String until, String ended, List<String> suffixWords,
			String believed, String notPrefix, String onlyWithin, String closure, List<String> objectPrepositions,
			Map<String, String> typePlurals, Map<String, String> typeAliases, Map<String, String> qualifiers) {

		public String formatSince(String start) {
			return since.replace("{start}", start);
		}

		public String formatFromEnded(String start) {
			return fromEnded.replace("{start}", start);
		}

		public String formatUntil(String end) {
			return until.replace("{end}", end);
		}

		public String formatOnlyWithin(String bound) {
			return onlyWithin.replace("{bound}", bound);
		}

		public String formatClosure(String subject, String verbPhrase, String typePlural) {
			return closure.replace("{subject}", subject).replace("{verb_phrase}", verbPhrase).replace("{type_plural}",
					typePlural);
		}
	}

	private final Lang lang;
	private final String code;
	private final String name;
	private final boolean capitalizesNouns;
	private final QueryCues query;
	private final FactTemplates fact;
	private final List<PluralRule> pluralSuffixes;
	private final Map<String, List<String>> groupLexicons;
	private final List<PredicateRender> predicateRenders;

	private LanguagePack(Lang lang, String code, String name, boolean capitalizesNouns, QueryCues query,
			FactTemplates fact, List<PluralRule> pluralSuffixes, Map<String, List<String>> groupLexicons,
			List<PredicateRender> predicateRenders) {
		this.lang = lang;
		this.code = code;
		this.name = name;
		this.capitalizesNouns = capitalizesNouns;
		this.query = query;
		this.fact = fact;
		this.pluralSuffixes = pluralSuffixes;
		this.groupLexicons = groupLexicons;
		this.predicateRenders = predicateRenders;
	}

	public Lang lang() {
		return lang;
	}

	public String code() {
		return code;
	}

	public String name() {
		return name;
	}

	/** Whether this language capitalizes common nouns (true for German), affecting proper-noun extraction. */
	public boolean capitalizesNouns() {
		return capitalizesNouns;
	}

	public QueryCues query() {
		return query;
	}

	public FactTemplates fact() {
		return fact;
	}

	public List<PluralRule> pluralSuffixes() {
		return pluralSuffixes;
	}

	public Map<String, List<String>> groupLexicons() {
		return groupLexicons;
	}

	public List<PredicateRender> predicateRenders() {
		return predicateRenders;
	}

	/** The language pack for {@code lang}; defaults to {@link Lang#EN} if null. */
	public static LanguagePack of(Lang lang) {
		return Registry.PACKS.get(lang == null ? Lang.EN : lang);
	}

	/** All loaded language packs, in {@link Lang} declaration order. */
	public static Collection<LanguagePack> all() {
		return Registry.PACKS.values();
	}

	/** Possessor-after words across all languages ("of", "von", "av", ...) plus universal Romance particles. */
	public static Set<String> allOfWords() {
		return Registry.ALL_OF;
	}

	/** Conjunction words across all languages ("and", "or", "und", "oder", "och", "eller", ...). */
	public static Set<String> allAndWords() {
		return Registry.ALL_AND;
	}

	/**
	 * Homographs across all languages: a word, and the words before which it is another word ("own" after "my"). A text
	 * searched may be in any language, whatever the question's.
	 */
	public static Map<String, Set<String>> allHomographs() {
		return Registry.HOMOGRAPHS;
	}

	/** Pipe-joined temporal suffix starter words across all languages. */
	public static String allSuffixWordsRegex() {
		return Registry.SUFFIX_WORDS_REGEX;
	}

	/** Pattern matching a trailing object preposition before {@code {object}} across all languages. */
	public static Pattern allObjectPrepositionsPattern() {
		return Registry.OBJECT_PREPOSITION;
	}

	/** Merged distinct cue words for a seed predicate group across all loaded languages. */
	public static List<String> groupLexicon(String groupName) {
		var out = new LinkedHashSet<String>();
		for (LanguagePack p : all()) {
			out.addAll(p.groupLexicons().getOrDefault(groupName, List.of()));
		}
		return List.copyOf(out);
	}

	/** Resolves a normalized localized entity-type word (e.g. German "firma", Swedish "foretag") to its seed type. */
	public static Optional<String> typeAlias(String normalizedWord, Lang preferred) {
		if (normalizedWord == null || normalizedWord.isEmpty()) {
			return Optional.empty();
		}
		if (preferred != null) {
			String hit = of(preferred).fact().typeAliases().get(normalizedWord);
			if (hit != null) {
				return Optional.of(hit);
			}
		}
		for (LanguagePack p : all()) {
			String hit = p.fact().typeAliases().get(normalizedWord);
			if (hit != null) {
				return Optional.of(hit);
			}
		}
		return Optional.empty();
	}

	// ── lazy registry holder (avoids enum static-init cycles) ────────────

	private static final class Registry {
		static final Map<Lang, LanguagePack> PACKS = loadAll();
		static final Set<String> ALL_OF = buildAllOf();
		static final Set<String> ALL_AND = buildAllAnd();
		static final Map<String, Set<String>> HOMOGRAPHS = buildHomographs();

		private static Map<String, Set<String>> buildHomographs() {
			var out = new LinkedHashMap<String, Set<String>>();
			for (LanguagePack p : PACKS.values()) {
				p.query().homographs().forEach((word, before) -> out.merge(word, before, (a, b) -> {
					var both = new LinkedHashSet<>(a);
					both.addAll(b);
					return Set.copyOf(both);
				}));
			}
			return Map.copyOf(out);
		}

		static final String SUFFIX_WORDS_REGEX = buildSuffixWordsRegex();
		static final Pattern OBJECT_PREPOSITION = buildObjectPrepositionPattern();

		private static Map<Lang, LanguagePack> loadAll() {
			var map = new EnumMap<Lang, LanguagePack>(Lang.class);
			for (Lang lang : Lang.values()) {
				map.put(lang, load(lang));
			}
			return map;
		}

		private static Set<String> buildAllOf() {
			var out = new LinkedHashSet<>(UNIVERSAL_OF);
			for (LanguagePack p : PACKS.values()) {
				out.addAll(p.query().ofWords());
			}
			return Set.copyOf(out);
		}

		private static Set<String> buildAllAnd() {
			var out = new LinkedHashSet<String>();
			for (LanguagePack p : PACKS.values()) {
				out.addAll(p.query().andWords());
			}
			return Set.copyOf(out);
		}

		private static String buildSuffixWordsRegex() {
			var out = new LinkedHashSet<String>();
			for (LanguagePack p : PACKS.values()) {
				out.addAll(p.fact().suffixWords());
			}
			return String.join("|", out);
		}

		private static Pattern buildObjectPrepositionPattern() {
			var preps = new LinkedHashSet<String>();
			for (LanguagePack p : PACKS.values()) {
				preps.addAll(p.fact().objectPrepositions());
			}
			List<String> sorted = new ArrayList<>(preps);
			sorted.sort(Comparator.comparingInt(String::length).reversed());
			var alts = new ArrayList<String>(sorted.size());
			for (String prep : sorted) {
				alts.add(Pattern.quote(prep));
			}
			return Pattern.compile("\\s+(?:" + String.join("|", alts) + ")\\s+(\\[\\[\\s*)?\\{object}",
					Pattern.UNICODE_CHARACTER_CLASS);
		}
	}

	// ── JSON parsing ─────────────────────────────────────────────────────

	private static LanguagePack load(Lang lang) {
		String path = "/lang/" + lang.code() + ".json";
		try (InputStream in = LanguagePack.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing language resource on classpath: " + path);
			}
			JsonNode root = JSON.readTree(in);
			String code = reqText(root, "code", path);
			String name = reqText(root, "name", path);
			boolean capitalizesNouns = root.path("capitalizes_nouns").asBoolean(false);
			QueryCues query = parseQuery(root.path("query"), path);
			FactTemplates fact = parseFact(root.path("fact"), path);
			List<PluralRule> pluralSuffixes = parsePluralRules(root.path("plural_suffixes"));
			Map<String, List<String>> groupLexicons = parseGroupLexicons(root.path("group_lexicons"));
			List<PredicateRender> predicateRenders = parsePredicateRenders(root.path("predicate_renders"), path);
			return new LanguagePack(lang, code, name, capitalizesNouns, query, fact, pluralSuffixes, groupLexicons,
					predicateRenders);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load language resource " + path, e);
		}
	}

	private static QueryCues parseQuery(JsonNode node, String path) {
		Set<String> markers = lowerSet(node.path("markers"));
		Set<String> questionWords = lowerSet(node.path("question_words"));
		Set<String> ofWords = normSet(node.path("of"));
		Set<String> andWords = normSet(node.path("and"));
		Set<String> polar = lowerSet(node.path("polar"));
		Set<String> past = lowerSet(node.path("past"));
		Set<String> perfectAuxiliaries = lowerSet(node.path("perfect_auxiliaries"));
		Set<String> pastParticiples = lowerSet(node.path("past_participles"));
		Set<String> pastMarkers = lowerSet(node.path("past_markers"));
		JsonNode ambig = node.path("ambiguous_where_was");
		String ambigWord = ambig.isObject() && ambig.hasNonNull("word")
				? ambig.get("word").asText().trim().toLowerCase(Locale.ROOT) : null;
		Set<String> ambigVerbs = ambig.isObject() ? lowerSet(ambig.path("followed_by_verbs")) : Set.of();
		Pattern forward = compileWordAlternatives(stringList(node.path("forward")));
		Set<String> polarFiller = normSet(node.path("polar_filler"));
		Set<String> kindFiller = normSet(node.path("kind_filler"));
		Pattern kindPhrase = compileKindPhrase(stringList(node.path("kind_leading_prepositions")),
				stringList(node.path("kind_question_words")), stringList(node.path("kind_verbs")));
		Pattern kindCopula = compileKindCopula(stringList(node.path("kind_leading_prepositions")),
				stringList(node.path("kind_question_words")), stringList(node.path("kind_verbs")));
		Set<String> selfPronouns = normSet(node.path("self_pronouns"));
		Set<String> tenseFiller = normSet(node.path("tense_filler"));
		var homographs = new LinkedHashMap<String, Set<String>>();
		for (Map.Entry<String, JsonNode> e : node.path("homographs").properties()) {
			homographs.put(Names.norm(e.getKey()), normSet(e.getValue()));
		}
		Set<String> determiners = normSet(node.path("determiners"));
		return new QueryCues(markers, questionWords, ofWords, andWords, polar, past, perfectAuxiliaries,
				pastParticiples, pastMarkers, ambigWord, ambigVerbs, forward, polarFiller, kindFiller, kindPhrase,
				selfPronouns, tenseFiller, Map.copyOf(homographs), kindCopula, determiners);
	}

	private static FactTemplates parseFact(JsonNode node, String path) {
		String since = reqText(node, "since", path);
		String fromEnded = reqText(node, "from_ended", path);
		String until = reqText(node, "until", path);
		String ended = reqText(node, "ended", path);
		List<String> suffixWords = stringList(node.path("suffix_words"));
		String believed = reqText(node, "believed", path);
		String notPrefix = reqText(node, "not_prefix", path);
		String onlyWithin = reqText(node, "only_within", path);
		String closure = reqText(node, "closure", path);
		List<String> objectPrepositions = stringList(node.path("object_prepositions"));
		Map<String, String> typePlurals = stringMap(node.path("type_plurals"), false);
		Map<String, String> typeAliases = normKeyMap(node.path("type_aliases"));
		Map<String, String> qualifiers = stringMap(node.path("qualifiers"), true);
		return new FactTemplates(since, fromEnded, until, ended, suffixWords, believed, notPrefix, onlyWithin, closure,
				objectPrepositions, typePlurals, typeAliases, qualifiers);
	}

	private static List<PluralRule> parsePluralRules(JsonNode arr) {
		if (!arr.isArray()) {
			return List.of();
		}
		var out = new ArrayList<PluralRule>();
		for (JsonNode n : arr) {
			String suffix = n.path("suffix").asText("");
			if (suffix.isEmpty()) {
				continue;
			}
			String replace = n.path("replace").asText("");
			int minLength = n.path("min_length").asInt(4);
			String notEnding = n.hasNonNull("not_ending") ? n.get("not_ending").asText() : null;
			out.add(new PluralRule(suffix, replace, minLength, notEnding));
		}
		return List.copyOf(out);
	}

	private static Map<String, List<String>> parseGroupLexicons(JsonNode obj) {
		if (!obj.isObject()) {
			return Map.of();
		}
		var out = new LinkedHashMap<String, List<String>>();
		for (Map.Entry<String, JsonNode> e : obj.properties()) {
			out.put(e.getKey(), stringList(e.getValue()));
		}
		return Map.copyOf(out);
	}

	private static List<PredicateRender> parsePredicateRenders(JsonNode arr, String path) {
		if (!arr.isArray()) {
			return List.of();
		}
		var out = new ArrayList<PredicateRender>();
		for (JsonNode n : arr) {
			String name = reqText(n, "name", path);
			String render = reqText(n, "render", path);
			String negated = n.hasNonNull("negated") ? n.get("negated").asText() : null;
			List<String> lexicon = stringList(n.path("lexicon"));
			List<String> inverse = stringList(n.path("inverse_lexicon"));
			out.add(new PredicateRender(name, render, negated, lexicon, inverse));
		}
		return List.copyOf(out);
	}

	private static Pattern compileWordAlternatives(List<String> phrases) {
		if (phrases.isEmpty()) {
			return NEVER;
		}
		List<String> sorted = new ArrayList<>(phrases);
		sorted.sort(Comparator.comparingInt(String::length).reversed());
		var alts = new ArrayList<String>(sorted.size());
		for (String p : sorted) {
			alts.add(quotePhrase(p));
		}
		return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + String.join("|", alts) + ")(?![\\p{L}\\p{N}])",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	}

	private static Pattern compileKindPhrase(List<String> preps, List<String> questionWords, List<String> verbs) {
		if (questionWords.isEmpty() || verbs.isEmpty()) {
			return NEVER;
		}
		var sb = new StringBuilder("^[^\\p{L}\\p{N}]*");
		if (!preps.isEmpty()) {
			sb.append("(?:(?:").append(joinQuotedPhrases(preps)).append(")\\s+)?");
		}
		sb.append("(?:").append(joinQuotedPhrases(questionWords)).append(")\\s+(.+?)\\s+(?:")
				.append(joinQuotedPhrases(verbs)).append(")(?![\\p{L}\\p{N}])");
		return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	}

	/** "what are my current cars": the question word, a verb, and the rest of the question, which names the kind. */
	private static Pattern compileKindCopula(List<String> preps, List<String> questionWords, List<String> verbs) {
		if (questionWords.isEmpty() || verbs.isEmpty()) {
			return NEVER;
		}
		var sb = new StringBuilder("^[^\\p{L}\\p{N}]*");
		if (!preps.isEmpty()) {
			sb.append("(?:(?:").append(joinQuotedPhrases(preps)).append(")\\s+)?");
		}
		sb.append("(?:").append(joinQuotedPhrases(questionWords)).append(")\\s+(?:").append(joinQuotedPhrases(verbs))
				.append(")\\s+(.+)$");
		return Pattern.compile(sb.toString(),
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);
	}

	private static String joinQuotedPhrases(List<String> phrases) {
		List<String> sorted = new ArrayList<>(phrases);
		sorted.sort(Comparator.comparingInt(String::length).reversed());
		var alts = new ArrayList<String>(sorted.size());
		for (String p : sorted) {
			alts.add(quotePhrase(p));
		}
		return String.join("|", alts);
	}

	private static String quotePhrase(String phrase) {
		String[] parts = phrase.trim().split("\\s+");
		var quoted = new ArrayList<String>(parts.length);
		for (String part : parts) {
			quoted.add(Pattern.quote(part));
		}
		return String.join("\\s+", quoted);
	}

	private static String reqText(JsonNode node, String field, String path) {
		if (!node.hasNonNull(field)) {
			throw new IllegalStateException("Missing required field '" + field + "' in " + path);
		}
		return node.get(field).asText();
	}

	private static List<String> stringList(JsonNode arr) {
		if (!arr.isArray()) {
			return List.of();
		}
		var out = new ArrayList<String>(arr.size());
		for (JsonNode n : arr) {
			String v = n.asText("").trim();
			if (!v.isEmpty()) {
				out.add(v);
			}
		}
		return List.copyOf(out);
	}

	private static Set<String> lowerSet(JsonNode arr) {
		if (!arr.isArray()) {
			return Set.of();
		}
		var out = new LinkedHashSet<String>();
		for (JsonNode n : arr) {
			String v = n.asText("").trim().toLowerCase(Locale.ROOT);
			if (!v.isEmpty()) {
				out.add(v);
			}
		}
		return Set.copyOf(out);
	}

	private static Set<String> normSet(JsonNode arr) {
		if (!arr.isArray()) {
			return Set.of();
		}
		var out = new LinkedHashSet<String>();
		for (JsonNode n : arr) {
			String v = Names.norm(n.asText(""));
			if (!v.isEmpty()) {
				out.add(v);
			}
		}
		return Set.copyOf(out);
	}

	private static Map<String, String> stringMap(JsonNode obj, boolean lowercaseKeys) {
		if (!obj.isObject()) {
			return Map.of();
		}
		var out = new LinkedHashMap<String, String>();
		for (Map.Entry<String, JsonNode> e : obj.properties()) {
			String k = lowercaseKeys ? e.getKey().toLowerCase(Locale.ROOT) : e.getKey();
			out.put(k, e.getValue().asText());
		}
		return Map.copyOf(out);
	}

	private static Map<String, String> normKeyMap(JsonNode obj) {
		if (!obj.isObject()) {
			return Map.of();
		}
		var out = new LinkedHashMap<String, String>();
		for (Map.Entry<String, JsonNode> e : obj.properties()) {
			String k = Names.norm(e.getKey());
			if (!k.isEmpty()) {
				out.put(k, e.getValue().asText());
			}
		}
		return Map.copyOf(out);
	}
}
