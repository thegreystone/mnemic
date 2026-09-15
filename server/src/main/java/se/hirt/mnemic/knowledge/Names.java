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

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Text normalisation shared by entity aliases, predicate lexicons, and query analysis. */
public final class Names {

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern WS = Pattern.compile("\\s+");
	private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
	private static final Map<Character, String> FOLD = Map.of('ß', "ss", 'ø', "o", 'æ', "ae", 'œ', "oe", 'ð', "d", 'þ',
			"th", 'ł', "l");

	/** Words that carry no lexical signal in queries or predicate descriptions. */
	public static final Set<String> STOPWORDS = Set.of("the", "a", "an", "of", "to", "in", "on", "at", "is", "are",
			"was", "were", "be", "and", "or", "for", "with", "about", "what", "who", "where", "when", "which", "how",
			"did", "does", "do", "i", "me", "my", "we", "our", "you", "your", "it", "that", "this", "tell", "subject",
			"object", "by", "s", "his", "her", "their", "whom", "whose");

	private Names() {
	}

	/** Lowercase, diacritics folded (Ä→a, ø→o, ß→ss), whitespace collapsed, trimmed. */
	public static String norm(String s) {
		if (s == null) {
			return "";
		}
		String d = Normalizer.normalize(s, Normalizer.Form.NFD);
		d = MARKS.matcher(d).replaceAll("");
		var sb = new StringBuilder(d.length());
		for (char c : d.toLowerCase(Locale.ROOT).toCharArray()) {
			String f = FOLD.get(c);
			sb.append(f == null ? String.valueOf(c) : f);
		}
		return WS.matcher(sb.toString()).replaceAll(" ").trim();
	}

	/** Normalised word tokens, possessive {@code 's} removed. */
	public static List<String> tokens(String s) {
		var out = new ArrayList<String>();
		var m = TOKEN.matcher(norm(s).replace("'s ", " ").replace("’s ", " ").replaceAll("['’]s$", ""));
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	/** Content tokens: {@link #tokens} minus stopwords and single letters. */
	public static List<String> contentTokens(String s) {
		return tokens(s).stream().filter(t -> t.length() > 1 && !STOPWORDS.contains(t)).toList();
	}

	/**
	 * Words that say what kind of thing an entity is rather than which one: "Kanton Luzern" and "Kanton Schwyz"
	 * share "kanton" and nothing that identifies. Dropped from the identity tokens of a name of that type before
	 * fuzzy matching. English, German, Swedish, and the usual company suffixes.
	 */
	private static final Map<String, Set<String>> TYPE_WORDS = Map.of(
			"place", Set.of("kanton", "canton", "county", "province", "region", "state", "district", "lake", "mount",
					"mountain", "river", "island", "city", "town", "village", "municipality", "kommun", "gemeinde", "stadt",
					"bezirk", "landkreis", "lan", "sjo", "berg", "see"),
			"organization", Set.of("company", "corporation", "corp", "inc", "ltd", "llc", "plc", "gmbh", "ag", "ab",
					"oy", "asa", "sa", "bv", "nv", "group", "holding", "holdings", "foundation", "institute", "university",
					"school", "bank", "agency", "department", "ministry", "verein", "stiftung", "forening"),
			"project", Set.of("project", "projekt", "initiative", "program", "programme"),
			"team", Set.of("team", "group", "squad", "unit", "department"),
			"event", Set.of("conference", "meeting", "summit", "workshop", "festival", "trip", "review"));

	/**
	 * A country is a place that decides disjointness (family Q): two different countries do not overlap, so
	 * "only within Switzerland" answers "in Sweden" with no. Everything that accepts a place accepts a country.
	 */
	public static boolean isPlace(String type) {
		return "place".equals(type) || "country".equals(type);
	}

	/** The same kind of thing: equal types, or both places (a town and a country resolve against each other). */
	public static boolean sameKind(String a, String b) {
		return a.equals(b) || (isPlace(a) && isPlace(b));
	}

	/** A word that says what kind of thing something is (Inc, Kanton, Team) rather than which one, for any type. */
	public static boolean isTypeWord(String token) {
		for (Set<String> words : TYPE_WORDS.values()) {
			if (words.contains(token)) {
				return true;
			}
		}
		return false;
	}

	/** Content tokens of a name that identify it: type words for the given type are removed. */
	public static List<String> identityTokens(String name, String type) {
		String kind = type(type);
		Set<String> drop = TYPE_WORDS.getOrDefault(isPlace(kind) ? "place" : kind, Set.of());
		List<String> kept = contentTokens(name).stream().filter(t -> !drop.contains(t)).toList();
		return kept.isEmpty() ? contentTokens(name) : kept; // "Kanton" alone stays "kanton"
	}

	/** Canonical entity type for common synonyms; unknown types pass through lowercased. */
	public static String type(String type) {
		if (type == null || type.isBlank()) {
			return "unknown";
		}
		String t = norm(type);
		return switch (t) {
			case "company", "org", "organisation", "employer", "firm" -> "organization";
			case "city", "town", "region", "location", "village" -> "place";
			case "nation" -> "country";
			case "human", "people", "individual" -> "person";
			case "tool", "library", "language", "framework", "software", "database", "platform" -> "technology";
			case "team", "group" -> "team";
			default -> t;
		};
	}
}
