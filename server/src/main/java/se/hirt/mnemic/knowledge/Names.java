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
		int len = s.length();
		if (len == 0) {
			return "";
		}
		// Fast path for plain printable ASCII + standard ASCII whitespace (no NFD/regex/map overhead).
		boolean ascii = true;
		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			if (c >= 0x7F || (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f' && c != 0x0B)) {
				ascii = false;
				break;
			}
		}
		if (ascii) {
			return normAscii(s, len);
		}
		String d = Normalizer.normalize(s, Normalizer.Form.NFD);
		d = MARKS.matcher(d).replaceAll("");
		String lower = d.toLowerCase(Locale.ROOT);
		var sb = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			String f = FOLD.get(c);
			if (f == null) {
				sb.append(c);
			} else {
				sb.append(f);
			}
		}
		return WS.matcher(sb.toString()).replaceAll(" ").trim();
	}

	private static String normAscii(String s, int len) {
		var sb = new StringBuilder(len);
		boolean prevSpace = true; // suppresses leading whitespace
		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
				if (!prevSpace) {
					sb.append(' ');
					prevSpace = true;
				}
			} else {
				if (c >= 'A' && c <= 'Z') {
					c = (char) (c + ('a' - 'A'));
				}
				sb.append(c);
				prevSpace = false;
			}
		}
		int outLen = sb.length();
		if (outLen > 0 && sb.charAt(outLen - 1) == ' ') {
			sb.setLength(outLen - 1);
		}
		return sb.toString();
	}

	/** Normalised word tokens, possessive {@code 's} removed. */
	/**
	 * Whether a two-letter token is a name where it is written: capitalised in {@code source} and no stopword. "Bo" in
	 * "Bo Berg" or "Is Bo home?" is; "it", "an", and a lowercase "bo" are not. Longer words carry identity by length;
	 * one letter never does (2026-09-26).
	 */
	public static boolean looksLikeName(String token, String source) {
		if (token == null || source == null || token.length() != 2 || STOPWORDS.contains(token)
				|| !token.chars().allMatch(Character::isLetter)) {
			return false;
		}
		String first = token.substring(0, 1).toUpperCase(Locale.ROOT);
		Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(first) + "(?i:"
				+ Pattern.quote(token.substring(1)) + ")(?![\\p{L}\\p{N}])");
		return p.matcher(source).find();
	}

	public static List<String> tokens(String s) {
		var out = new ArrayList<String>();
		var m = tokenMatcher(s);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	/**
	 * The matcher behind {@link #tokens}, over the same normalised text, so a caller can read where each token stands
	 * and what lies between two of them (a comma, say); the offsets are into {@link #tokenised}.
	 */
	public static java.util.regex.Matcher tokenMatcher(String s) {
		return TOKEN.matcher(tokenised(s));
	}

	/** The text as {@link #tokens} reads it: normalised, possessives removed, punctuation still in place. */
	public static String tokenised(String s) {
		String n = norm(s);
		if (n.indexOf('\'') >= 0) {
			n = n.replace("'s ", " ");
			if (n.endsWith("'s")) {
				n = n.substring(0, n.length() - 2);
			}
		}
		if (n.indexOf('’') >= 0) {
			n = n.replace("’s ", " ");
			if (n.endsWith("’s")) {
				n = n.substring(0, n.length() - 2);
			}
		}
		return n;
	}

	/** Content tokens: {@link #tokens} minus stopwords and single letters. */
	public static List<String> contentTokens(String s) {
		return tokens(s).stream().filter(t -> t.length() > 1 && !STOPWORDS.contains(t)).toList();
	}

	private static final Pattern POSSESSOR = Pattern.compile("([\\p{L}\\p{N}]+)['’]s?(?=\\s+\\S)");

	/**
	 * The possessors in a name, normalised: "Marcus" in "Marcus's iPad", "Andreas" in "Andreas' bike". A thing named
	 * after its owner is not the owner, so these words say whose the thing is, not what it is. A name that ends in the
	 * possessive with nothing after it ("McDonald's", "Kellogg's") names the thing itself and has no possessor.
	 */
	public static List<String> possessors(String s) {
		if (s == null || (s.indexOf('\'') < 0 && s.indexOf('’') < 0)) {
			return List.of();
		}
		var out = new ArrayList<String>();
		var m = POSSESSOR.matcher(norm(s));
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

}
