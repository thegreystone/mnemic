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

import se.hirt.mnemic.embed.Embedding;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.knowledge.PredicateRegistry.Group;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.Proposal.PredicateDef;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Query-time predicate cue matching, lexical similarity, and embedding-based semantic matching. All cache-touching
 * methods expect the caller ({@link PredicateRegistry}) to hold {@link Database#withLock}.
 */
final class PredicateCueMatcher {

	static final int SIMILAR_MIN_OVERLAP = 2;
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
	/**
	 * When a phrase of a question cues a predicate by meaning: a score of 0.87 with a lead of 0.04 over the next
	 * predicate, or 0.91 with a lead of 0.03. A phone word reaches has_phone at 0.88 to 0.94 in English, German, and
	 * Swedish alike with a lead of 0.05 or more ("handynummer" lowercased, as the tokeniser hands it over, is the
	 * lowest at 0.880), "employer" at 0.912 with 0.038, while question words, verbs, and unrelated nouns lead by 0.022
	 * at most ("address" on located_in: 0.883, lead 0.022; "vad har" on owns: 0.880, lead 0.020). Kinship words score
	 * high but lead by under 0.02, since the family predicates lie close together, and are left to their lexicons and
	 * the family group (see {@code CueCalibrationTest}).
	 */
	static final float CUE_SEMANTIC = 0.87f;
	static final float CUE_SEMANTIC_LEAD = 0.04f;
	static final float CUE_SEMANTIC_HIGH = 0.91f;
	static final float CUE_SEMANTIC_HIGH_LEAD = 0.03f;
	/** How many phrases of one question are compared by meaning at most, and how many phrase vectors are kept. */
	private static final int CUE_PHRASES_MAX = 24;
	private static final int PHRASE_VECTORS_MAX = 512;

	/** The best match by meaning, how far ahead of the next it lies, and the next ones that are close too. */
	record Nearest(Predicate predicate, float score, float lead, List<Predicate> alternatives) {
		boolean similar() {
			return score >= SEMANTIC_SIMILAR && lead >= SEMANTIC_SIMILAR_LEAD;
		}

		boolean ambiguous() {
			return score >= SEMANTIC_AMBIGUOUS && lead >= SEMANTIC_AMBIGUOUS_LEAD;
		}
	}

	/** The best match by shared words, how many they share, and the next ones that share any. */
	record Similar(Predicate predicate, int overlap, List<Predicate> alternatives) {
	}

	private final Database db;
	/** The embedding model, when one is loaded; null means names are compared by their words only. */
	private final Supplier<Embedding> embedding;
	/** One vector per predicate for {@code vectorModel}, computed when first needed. */
	private final Map<String, float[]> vectors = new HashMap<>();
	/** The vectors of the question phrases compared most recently, for {@code vectorModel}; phrases recur. */
	private final Map<String, float[]> phraseVectors = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
			return size() > PHRASE_VECTORS_MAX;
		}
	};
	private String vectorModel;
	/** Cached distinct qualifiers on current facts, invalidated on local or external commits. */
	private Map<String, List<String>> qualifiersInUseCache;
	private long qualifiersDataVersion = -1;
	private long qualifiersLocalVersion = -1;

	PredicateCueMatcher(Database db, Supplier<Embedding> embedding) {
		this.db = db;
		this.embedding = embedding;
	}

	void clearVectors() {
		vectors.clear();
	}

	void evictVector(String predicateName) {
		vectors.remove(predicateName);
	}

	/** What a predicate means, as text for the embedding model: its name's words, its description, its lexicon. */
	static String embeddingText(Predicate p) {
		return embeddingText(p.name(), p.description(), p.lexicon());
	}

	/** A bare definition is embedded with the lexicon it would get, so it reads like the registered predicates do. */
	static String embeddingText(PredicateDef d) {
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
	Nearest nearest(
		String text, List<String> domain, List<String> range, String except, Collection<Predicate> predicates) {
		Embedding emb = embedding.get();
		if (emb == null) {
			return null;
		}
		ensureModel(emb);
		float[] q = emb.embed(text);
		var scored = new ArrayList<Map.Entry<Predicate, Float>>();
		for (Predicate p : predicates) {
			if (p.name().equals(except) || p.literalRange() || !overlaps(p.domain(), domain)
					|| !overlaps(p.range(), range)) {
				continue;
			}
			float[] v = vectors.computeIfAbsent(p.name(), k -> emb.embed(embeddingText(p)));
			scored.add(Map.entry(p, Embedding.dot(q, v)));
		}
		if (scored.isEmpty()) {
			return null;
		}
		scored.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
		float bestScore = scored.getFirst().getValue();
		float second = scored.size() > 1 ? scored.get(1).getValue() : 0;
		// The next ones close enough to be asked about alongside the best, at most two.
		var alternatives = new ArrayList<Predicate>();
		for (int i = 1; i < scored.size() && alternatives.size() < 2; i++) {
			if (scored.get(i).getValue() >= SEMANTIC_AMBIGUOUS) {
				alternatives.add(scored.get(i).getKey());
			}
		}
		return new Nearest(scored.getFirst().getKey(), bestScore, bestScore - second, List.copyOf(alternatives));
	}

	/**
	 * The relations to offer beside the chosen candidate when a name is asked about: the next closest by words and,
	 * with a model loaded, by meaning, at most three in all, so the caller can say which of the closest will do rather
	 * than only yes or no to one.
	 */
	static List<Predicate> closest(Similar similar, Nearest near, Predicate candidate) {
		var out = new ArrayList<Predicate>();
		var seen = new HashSet<String>();
		seen.add(candidate.name());
		var pool = new ArrayList<Predicate>();
		if (similar != null) {
			pool.add(similar.predicate());
			pool.addAll(similar.alternatives());
		}
		if (near != null && near.ambiguous()) {
			// The best by meaning counts only when it is close enough to have been asked about on its own; the next
			// ones were kept only when they were.
			pool.add(near.predicate());
			pool.addAll(near.alternatives());
		}
		for (Predicate p : pool) {
			if (seen.add(p.name()) && out.size() < 3) {
				out.add(p);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Predicates registered from use that lie close in meaning to another predicate, each pair once:
	 * {@code {predicate, close_to, score}}. Empty without a model.
	 */
	List<Map<String, Object>> closePairs(Collection<Predicate> predicates) {
		var out = new ArrayList<Map<String, Object>>();
		var seen = new HashSet<String>();
		for (Predicate p : predicates) {
			if (!p.isInferred()) {
				continue;
			}
			Nearest n = nearest(embeddingText(p), p.domain(), p.range(), p.name(), predicates);
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
	 * A term of a relation's vocabulary as the question writes it: the term itself, or, for a one-word term, a word of
	 * the question that is its plural in any language pack ("who are my brothers" for the qualifier "brother", which
	 * the exact match missed while "who is my brother" answered, 2026-11-24). Null when the question has neither.
	 */
	private static String asWritten(String q, List<String> tokens, String key) {
		if (key.isEmpty()) {
			return null;
		}
		if (q.contains(" " + key + " ")) {
			return key;
		}
		if (key.indexOf(' ') >= 0) {
			return null;
		}
		for (String t : tokens) {
			if (t.length() <= key.length()) {
				continue;
			}
			for (Lang l : Lang.values()) {
				if (Lang.singulars(t, l).contains(key)) {
					return t;
				}
			}
		}
		return null;
	}

	/**
	 * The cues of a question whose entity names are already known: {@code excluded} holds the tokens of the entities
	 * spotted in it, which no phrase compared by meaning may contain.
	 */
	List<Cue> cues(
		String query, Set<String> excluded, Collection<Predicate> predicates, Collection<Group> groups,
		Function<String, List<Predicate>> membersOf) {
		List<String> tokens = Names.tokens(query);
		String q = " " + String.join(" ", tokens) + " ";
		var out = new ArrayList<Cue>();
		var cued = new HashSet<String>(); // predicates the question names by their own words
		var freeCued = new HashSet<Cue>();
		var viaInverse = new HashSet<Cue>();
		Map<String, List<String>> inUse = qualifiersInUse();
		for (Predicate p : predicates) {
			var terms = new LinkedHashMap<String, Cue>(); // each word of the predicate's vocabulary in the question
			// A predicate with a vocabulary is cued by it; one that takes free text is cued by the qualifiers actually
			// stored under it ("cousin" on related_to), from either side.
			boolean free = p.qualifiers().isEmpty();
			for (String term : free ? inUse.getOrDefault(p.name(), List.of()) : p.qualifiers()) {
				String key = String.join(" ", Names.tokens(term));
				String written = asWritten(q, tokens, key);
				if (written == null || terms.containsKey(key)) {
					continue;
				}
				Cue c = new Cue(p, term, written.equals(key) ? term : written,
						free || p.symmetric() ? "any" : "object");
				terms.put(key, c);
				if (free) {
					freeCued.add(c);
				}
			}
			for (String term : p.inverseLexicon()) {
				String key = String.join(" ", Names.tokens(term));
				String written = asWritten(q, tokens, key);
				if (written == null || terms.containsKey(key)) {
					continue;
				}
				Cue c = new Cue(p, null, written.equals(key) ? term : written, p.symmetric() ? "any" : "subject");
				terms.put(key, c);
				viaInverse.add(c);
			}
			var own = new ArrayList<>(p.lexicon());
			for (String alias : p.aliases()) {
				own.addAll(Predicate.lexiconOf(alias)); // a name the caller once used, in any language
			}
			// A predicate's own words are mostly verbs whose forms it lists; only role words take a plural here.
			for (String term : own) {
				String key = String.join(" ", Names.tokens(term));
				if (!q.contains(" " + key + " ") || terms.containsKey(key)) {
					continue;
				}
				terms.put(key, new Cue(p, null, term, !p.symmetric() && p.sameType() ? "object" : "any"));
			}
			if (!terms.isEmpty()) {
				out.addAll(terms.values());
				cued.add(p.name());
			}
		}
		// A group word ("family") cues every predicate under the group, the ones the question also names by their
		// own words included: "Alexander's children and Christian's family" asks parent_of twice, once each. The
		// binding drops the group's copy where it would ask the same relation of the same subject twice. The group
		// says nothing about the side, so the entity's type decides it.
		for (Group g : groups) {
			String term = null;
			for (String t : g.lexicon()) {
				if (q.contains(" " + String.join(" ", Names.tokens(t)) + " ")
						&& (term == null || t.length() > term.length())) {
					term = t;
				}
			}
			if (term == null) {
				continue;
			}
			for (Predicate p : membersOf.apply(g.name())) {
				out.add(new Cue(p, null, term, "any", g.name()));
			}
		}
		semanticCues(query, excluded, out, cued, predicates);
		// Longest term first; at equal length a word in a predicate's own vocabulary outranks the same word known
		// only as another predicate's inverse ("nephews" reaches nibling_of before aunt_uncle_of), both outrank a
		// free qualifier in use, and a group word comes after them all.
		out.sort((a, b) -> {
			int byLength = Integer.compare(b.term().length(), a.term().length());
			if (byLength != 0) {
				return byLength;
			}
			int byGroup = Boolean.compare(a.viaGroup(), b.viaGroup());
			if (byGroup != 0) {
				return byGroup;
			}
			int byOwn = Boolean.compare(viaInverse.contains(a), viaInverse.contains(b));
			return byOwn != 0 ? byOwn : Boolean.compare(freeCued.contains(a), freeCued.contains(b));
		});
		return out;
	}

	/**
	 * Cues by meaning for the phrases of a question that no vocabulary matched: each window of one or two tokens
	 * outside entity names, stopwords, and the words already cued is embedded and compared with every predicate's
	 * vector, the literal ones included, and cues the nearest predicate when its score and its lead over the next are
	 * those of a real match.
	 */
	private void semanticCues(
		String query, Set<String> excluded, List<Cue> out, Set<String> cued, Collection<Predicate> predicates) {
		Embedding emb = embedding.get();
		if (emb == null) {
			return;
		}
		List<String> tokens = Names.tokens(query);
		var taken = new HashSet<>(excluded);
		for (Cue c : out) {
			taken.addAll(Names.tokens(c.term()));
		}
		var phrases = new LinkedHashSet<String>();
		for (int width = 2; width >= 1; width--) {
			for (int i = 0; i + width <= tokens.size(); i++) {
				List<String> window = tokens.subList(i, i + width);
				boolean usable = window.stream()
						.noneMatch(t -> taken.contains(t) || t.length() < 2 || Names.STOPWORDS.contains(t));
				if (usable) {
					phrases.add(String.join(" ", window));
				}
			}
		}
		if (phrases.isEmpty()) {
			return;
		}
		ensureModel(emb);
		List<Predicate> all = List.copyOf(predicates);
		var matched = new HashSet<String>();
		int compared = 0;
		for (String phrase : phrases) {
			if (compared >= CUE_PHRASES_MAX) {
				break;
			}
			List<String> words = Names.tokens(phrase);
			if (words.stream().anyMatch(matched::contains)) {
				continue;
			}
			compared++;
			float[] q = phraseVectors.computeIfAbsent(phrase, emb::embed);
			Predicate best = null;
			float bestScore = 0, second = 0;
			for (Predicate p : all) {
				float[] v = vectors.computeIfAbsent(p.name(), k -> emb.embed(embeddingText(p)));
				float s = Embedding.dot(q, v);
				if (s > bestScore) {
					second = bestScore;
					bestScore = s;
					best = p;
				} else if (s > second) {
					second = s;
				}
			}
			if (best == null || !semanticCue(bestScore, bestScore - second) || cued.contains(best.name())) {
				continue;
			}
			out.add(new Cue(best, null, phrase, !best.symmetric() && best.sameType() ? "object" : "any"));
			cued.add(best.name());
			matched.addAll(words);
		}
	}

	/** Whether a phrase's nearest predicate is a real match, by its score and its lead over the next one. */
	static boolean semanticCue(float score, float lead) {
		return (score >= CUE_SEMANTIC && lead >= CUE_SEMANTIC_LEAD)
				|| (score >= CUE_SEMANTIC_HIGH && lead >= CUE_SEMANTIC_HIGH_LEAD);
	}

	/** Drops the vectors computed for another model, so that a swapped model is never compared with the old one's. */
	private void ensureModel(Embedding emb) {
		if (!emb.id().equals(vectorModel)) {
			vectors.clear();
			phraseVectors.clear();
			vectorModel = emb.id();
		}
	}

	/** Whether a predicate's own vocabulary (qualifiers, lexicon) names a role, whole or by one of its words. */
	static boolean namesRole(Predicate p, String role) {
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
	static Optional<Predicate> dedicatedFor(String role, Collection<Predicate> predicates) {
		if (role == null || role.isBlank()) {
			return Optional.empty();
		}
		String r = role.trim().toLowerCase(Locale.ROOT);
		List<Predicate> dedicated = predicates.stream().filter(p -> !p.qualifiers().isEmpty()).toList();
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

	/** The best existing predicate with compatible domain and range, by content-token overlap; null when none. */
	static Similar similar(
		PredicateDef def, List<String> domain, List<String> range, Collection<Predicate> predicates) {
		// The vocabulary, the name's words and the lexicon, is compared with lemmas on both sides, so "leans toward"
		// meets the "leaning" of considering through "lean". The prose of a description is compared as written: its
		// words ("parent", "role", "from") are shared by many relations and lemmas would only add to that.
		var proposedVocabulary = new HashSet<String>();
		proposedVocabulary.addAll(Names.contentTokens(def.name() == null ? "" : def.name().replace('_', ' ')));
		for (String l : def.lexicon()) {
			proposedVocabulary.addAll(Names.contentTokens(l));
		}
		// A bare name is only words, so it is read generously: with lemmas, against qualifiers too, and a word of
		// the relation's own name counting twice. A defined name is compared as it always was: name, lexicon, and
		// description as written, so that a definition is not mistaken for a seed it merely mentions.
		boolean bare = def.description() == null;
		Set<String> proposedWords = bare ? withLemmas(proposedVocabulary) : new HashSet<>(proposedVocabulary);
		var proposedProse = new HashSet<>(proposedVocabulary);
		proposedProse.addAll(Names.contentTokens(def.description()));
		var scored = new ArrayList<Map.Entry<Predicate, Integer>>();
		for (Predicate p : predicates) {
			if (!overlaps(p.domain(), domain) || !overlaps(p.range(), range)) {
				continue;
			}
			// A relation's vocabulary: its name's words, its lexicon, and its qualifiers ("brother" reaches
			// sibling_of). A word shared with the name itself counts twice: "parent" is parent_of before it is the
			// "parent-in-law" of in_law_of.
			Set<String> nameWords = new HashSet<>(Names.contentTokens(p.name().replace('_', ' ')));
			if (bare) {
				nameWords = withLemmas(nameWords);
			}
			var vocabulary = new HashSet<String>(nameWords);
			for (String l : p.lexicon()) {
				vocabulary.addAll(Names.contentTokens(l));
			}
			if (bare) {
				for (String q : p.qualifiers()) {
					vocabulary.addAll(Names.contentTokens(q));
				}
			}
			Set<String> shared = bare ? withLemmas(vocabulary) : new HashSet<>(vocabulary);
			shared.retainAll(proposedWords);
			int score = shared.size();
			if (bare) {
				for (String w : shared) {
					if (nameWords.contains(w)) {
						score++;
					}
				}
			}
			if (!bare) {
				var prose = new HashSet<>(vocabulary);
				prose.addAll(Names.contentTokens(p.description()));
				prose.retainAll(proposedProse);
				prose.removeAll(shared);
				score += prose.size();
			}
			if (score > 0) {
				scored.add(Map.entry(p, score));
			}
		}
		if (scored.isEmpty()) {
			return null;
		}
		scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		int bestScore = scored.getFirst().getValue();
		boolean tie = scored.size() > 1 && scored.get(1).getValue() == bestScore;
		if (tie && def.description() != null) {
			// A defined name that reads equally like several relations reads like none of them, as before; a bare
			// name has only its words, so its tied ones are offered together and the caller says.
			return null;
		}
		var alternatives = new ArrayList<Predicate>();
		for (int i = 1; i < scored.size() && alternatives.size() < 2; i++) {
			alternatives.add(scored.get(i).getKey());
		}
		return new Similar(scored.getFirst().getKey(), bestScore, List.copyOf(alternatives));
	}

	/** A token set with the lemma of every word added, so "leans" meets "leaning" through "lean". */
	private static Set<String> withLemmas(Set<String> tokens) {
		var out = new HashSet<String>(tokens);
		for (String t : tokens) {
			String lemma = Predicate.lemma(t);
			if (lemma != null && lemma.length() > 2) {
				out.add(lemma);
			}
		}
		return out;
	}

	static boolean overlaps(List<String> a, List<String> b) {
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

	/** The qualifiers stored on current facts, by predicate: the words a free-text qualifier gave a relation. */
	private Map<String, List<String>> qualifiersInUse() {
		if (db.inWriteTransaction()) {
			return loadQualifiersInUse();
		}
		long dv = db.dataVersion();
		long lv = db.localVersion();
		if (qualifiersInUseCache == null || qualifiersDataVersion != dv || qualifiersLocalVersion != lv) {
			qualifiersInUseCache = loadQualifiersInUse();
			qualifiersDataVersion = dv;
			qualifiersLocalVersion = lv;
		}
		return qualifiersInUseCache;
	}

	private Map<String, List<String>> loadQualifiersInUse() {
		var out = new HashMap<String, List<String>>();
		db.read(tx -> {
			tx.queryEach(
					"SELECT DISTINCT predicate, qualifier FROM fact WHERE qualifier IS NOT NULL AND status = 'current'",
					rs -> {
						String pred = rs.getString(1);
						String qual = rs.getString(2);
						out.computeIfAbsent(pred, k -> new ArrayList<>()).add(qual);
					});
			return null;
		});
		return out;
	}
}
