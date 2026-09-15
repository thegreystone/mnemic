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
package se.hirt.mnemic.recall;

import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A question taken apart once for every channel: the entities it names, the predicate cues, its content terms with the
 * owner's names removed (they occur in nearly every rendering and carry no signal), the terms beyond the entities,
 * whether it is a yes/no question, in the past tense, or about what is coming, and what it names specifically.
 *
 * @param terms
 *            distinct content tokens minus the owner's aliases
 * @param entityTokens
 *            tokens of every spotted entity's aliases and the owner's
 * @param beyondEntities
 *            terms that are not an entity token
 * @param residual
 *            for a yes/no question, what it asks about beyond the entities, the cue, and filler ("any", "still")
 * @param named
 *            capitalised words that are not an entity: the specific things the question is about
 * @param fts
 *            the FTS5 match expression over the terms
 */
public record Query(String text, List<Entity> spotted, List<Cue> cues, Set<String> ownerTokens, List<String> terms,
		Set<String> entityTokens, List<String> beyondEntities, boolean polar, boolean past, boolean forward,
		Set<String> cueTokens, List<String> residual, List<String> named, String fts) {

	private static final Set<String> POLAR = Set.of("does", "do", "did", "is", "are", "was", "were", "has", "have",
			"had", "can", "could", "will", "would", "should");
	private static final Set<String> PAST = Set.of("did", "was", "were", "had");
	private static final Pattern FORWARD = Pattern.compile(
			"\\b(?:about to|upcoming|next|soon|going to|will|plan(?:s|ned|ning)?|scheduled|due|coming up|pick(?:ing)? up)\\b",
			Pattern.CASE_INSENSITIVE);
	/** Words that narrow nothing in a yes/no question. */
	static final Set<String> POLAR_FILLER = Set.of("any", "anything", "anyone", "anywhere", "still", "yet", "ever",
			"really", "actually", "currently", "now", "already", "also", "some", "something", "someone", "there",
			"here", "just", "even", "own");

	public static Query analyse(
		String text, EntityService entities, PredicateRegistry predicates, EntityTypeRegistry types) {
		List<Entity> spotted = entities.spot(text);
		List<Cue> cues = predicates.cues(text);
		var ownerTokens = new HashSet<String>();
		for (String a : entities.aliases(entities.owner().id())) {
			ownerTokens.addAll(Names.tokens(a));
		}
		List<String> terms = Names.contentTokens(text).stream().filter(t -> !ownerTokens.contains(t)).distinct()
				.toList();
		var entityTokens = new HashSet<>(ownerTokens);
		for (Entity e : spotted) {
			for (String a : entities.aliases(e.id())) {
				entityTokens.addAll(Names.tokens(a));
			}
		}
		List<String> beyondEntities = terms.stream().filter(t -> !entityTokens.contains(t)).toList();
		boolean polar = isPolar(text);
		Set<String> cueTokens = cues.isEmpty() ? Set.of() : new HashSet<>(Names.tokens(cues.getFirst().term()));
		List<String> residual = polar
				? beyondEntities.stream().filter(t -> !cueTokens.contains(t) && !POLAR_FILLER.contains(t)).toList()
				: List.of();
		return new Query(text, spotted, cues, ownerTokens, terms, entityTokens, beyondEntities, polar, isPast(text),
				isForward(text), cueTokens, residual, namedThings(text, entityTokens, types),
				ftsQuery(text, ownerTokens));
	}

	/** The spotted entities other than the owner. */
	public List<Entity> others(long ownerId) {
		return spotted.stream().filter(e -> e.id() != ownerId).toList();
	}

	/** A yes/no question: "does Mattias own property in Sweden", "has the car been registered". */
	static boolean isPolar(String query) {
		String[] words = query.trim().toLowerCase(Locale.ROOT).split("[^a-z']+");
		return words.length > 0 && POLAR.contains(words[0]);
	}

	/** "Did Mattias work at Initrode": ended facts are the answer, and nothing is decided false from the present. */
	static boolean isPast(String query) {
		String[] words = query.trim().toLowerCase(Locale.ROOT).split("[^a-z']+");
		return words.length > 0 && PAST.contains(words[0]);
	}

	/** A question about what is coming: the upcoming channel's cue. */
	static boolean isForward(String query) {
		return query != null && FORWARD.matcher(query).find();
	}

	/**
	 * Capitalised words of the query that are not its first word and not an entity or owner name: the specific things
	 * the question names ("my apartment in Shinjuku"), lowercased for matching against renderings. The cue predicate's
	 * vocabulary is not excluded: "Software Engineer Manager" is a title whose every word counts.
	 */
	static List<String> namedThings(String query, Set<String> entityTokens, EntityTypeRegistry types) {
		var out = new ArrayList<String>();
		String[] words = query.trim().split("\\s+");
		for (int i = 1; i < words.length; i++) {
			String w = words[i].replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
			boolean innerCapital = false; // iPad, macOS: a brand, not prose
			for (int c = 1; c < w.length(); c++) {
				if (Character.isUpperCase(w.charAt(c))) {
					innerCapital = true;
					break;
				}
			}
			if (w.length() < 2 || (!Character.isUpperCase(w.charAt(0)) && !innerCapital)) {
				continue;
			}
			if (w.equals("I") || w.length() <= 3 && w.chars().allMatch(Character::isUpperCase)) {
				continue; // "I" and acronyms like "AI" are not named things
			}
			for (String t : Names.tokens(w)) {
				if (t.length() >= 2 && !entityTokens.contains(t) && !Names.STOPWORDS.contains(t) && !types.isTypeWord(t)
						&& !out.contains(t)) {
					out.add(t);
				}
			}
		}
		return out;
	}

	/**
	 * Each content token OR-joined, quoted so FTS5 syntax in a question cannot break the query. Tokens of four letters
	 * or more also match as prefixes ("lead" finds "leading"), which stands in for stemming without the damage an
	 * English stemmer does to German and Swedish. Owner-alias tokens are dropped when anything else remains.
	 */
	static String ftsQuery(String query, Set<String> ownerTokens) {
		List<String> tokens = Names.contentTokens(query);
		List<String> kept = tokens.stream().filter(t -> !ownerTokens.contains(t)).toList();
		if (kept.isEmpty()) {
			kept = tokens;
		}
		var terms = new LinkedHashSet<String>();
		for (String t : kept) {
			terms.add(t.length() >= 4 ? "\"" + t + "\"*" : "\"" + t + "\"");
		}
		return String.join(" OR ", terms);
	}

	/** Distinct query terms present in a rendering, with the same prefix rule as the FTS query. */
	static int matchedTerms(String rendering, List<String> queryTerms) {
		List<String> words = Names.tokens(rendering);
		int n = 0;
		for (String t : queryTerms) {
			boolean hit = t.length() >= 4 ? words.stream().anyMatch(w -> w.startsWith(t)) : words.contains(t);
			if (hit) {
				n++;
			}
		}
		return n;
	}
}
