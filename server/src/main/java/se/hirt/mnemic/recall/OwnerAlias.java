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
package se.hirt.mnemic.recall;

import se.hirt.mnemic.knowledge.Lang;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner alias for the vector channel (2026-09-11, from the embedder bake-off). A first-person question sits
 * far from a third-person fact rendering ("which company employs me?" against "Mattias works at Hooli") and no
 * embedding model knows who "I" is; the other channels have the owner's alias table, the vectors did not.
 *
 * <p>Two directions were tried. {@link #withOwner} rewrites the question with the owner's name, and taking the
 * better of the two questions per item lowered recall on the bake-off's sample set for every model but one:
 * the name is a strong token, so every fact rendered with it drew close to every first-person question.
 * {@link #firstPerson} goes the other way: a fact about the owner is embedded a second time as the owner would
 * say it ("I work at Hooli"), a vector without a name in it, and the question stays as asked. The grammar is
 * heuristic (the verb after the name loses its third-person ending), which sentence embeddings forgive.
 */
public final class OwnerAlias {

	private OwnerAlias() {
	}

	// ── question side ──

	private static final Pattern PERSON = Pattern.compile(
			"\\b(?:I|me|myself|ich|mich|mir|jag|mig)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern POSSESSIVE = Pattern.compile(
			"\\b(?:my|mine|mein|meine|meinem|meinen|meiner|meines|min|mitt|mina)\\b",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern CONTRACTION = Pattern.compile("\\bI'(m|ve|d|ll)\\b");

	/**
	 * The question with the owner's name in place of the first person, or null when the question has no first
	 * person in it, or the owner has no name to give.
	 */
	public static String withOwner(String query, String owner) {
		if (query == null || owner == null || owner.isBlank()) {
			return null;
		}
		String name = owner.strip();
		String out = CONTRACTION.matcher(query).replaceAll(m -> name + switch (m.group(1)) {
			case "m" -> " is";
			case "ve" -> " has";
			case "d" -> " would";
			default -> " will";
		});
		out = POSSESSIVE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(genitive(name, m.group())));
		out = PERSON.matcher(out).replaceAll(m -> Matcher.quoteReplacement(name));
		if (out.equals(query)) {
			return null;
		}
		String q = Pattern.quote(name);
		return out.replaceAll("\\bam " + q + "\\b", "is " + name).replaceAll("\\b" + q + " am\\b", name + " is")
				.replaceAll("\\bdo " + q + "\\b", "does " + name).replaceAll("\\bhave " + q + "\\b", "has " + name)
				.replaceAll("\\bbin " + q + "\\b", "ist " + name).replaceAll("\\bhabe " + q + "\\b", "hat " + name);
	}

	/** {@code Mattias'} / {@code Anna's} in English, {@code Annas} / {@code Mattias'} in German and Swedish. */
	private static String genitive(String name, String possessive) {
		String p = possessive.toLowerCase(Locale.ROOT);
		boolean english = p.equals("my") || p.equals("mine");
		boolean sibilant = name.endsWith("s") || name.endsWith("x") || name.endsWith("z");
		if (english) {
			return sibilant ? name + "'" : name + "'s";
		}
		return sibilant ? name + "'" : name + "s";
	}

	// ── rendering side ──

	private static final Map<String, String> VERB_EN = Map.of("is", "am", "has", "have", "does", "do", "was", "was");
	private static final Map<String, String> VERB_DE = Map.of("ist", "bin", "hat", "habe", "war", "war", "kann", "kann",
			"mag", "mag", "wird", "werde", "muss", "muss", "will", "will");

	/**
	 * A fact rendering as the owner would say it: the owner's name as subject becomes "I" with the verb to
	 * match, as possessive "my", as object "me"; null when the rendering does not mention the owner.
	 */
	public static String firstPerson(String rendering, String owner, Lang lang) {
		if (rendering == null || owner == null || owner.isBlank() || !rendering.contains(owner.strip())) {
			return null;
		}
		String name = owner.strip();
		String q = Pattern.quote(name);
		boolean de = lang == Lang.DE;
		String out = rendering;
		if (out.startsWith(name + "'s ") || out.startsWith(name + "' ")) {
			out = (de ? "mein " : "my ") + out.substring(out.indexOf(' ') + 1);
		} else if (de && out.startsWith(name + "s ")) {
			out = "mein " + out.substring(name.length() + 2);
		} else if (out.startsWith(name + " ")) {
			String rest = out.substring(name.length() + 1);
			out = (de ? "ich " : "I ") + conjugate(rest, de);
		}
		out = out.replaceAll("(?<=\\s)" + q + "'s?(?=\\s)", de ? "mein" : "my").replaceAll("(?<=\\s)" + q + "s(?=\\s)", de ? "mein" : "my")
				.replaceAll("(?<=\\s)" + q + "(?=[\\s,.;:)]|$)", de ? "mich" : "me");
		return out.equals(rendering) ? null : Character.toUpperCase(out.charAt(0)) + out.substring(1);
	}

	/** The first verb-looking word of the rest of the sentence into the first person; the rest untouched. */
	private static String conjugate(String rest, boolean de) {
		String[] words = rest.split(" ", 3);
		for (int i = 0; i < Math.min(2, words.length); i++) {
			String w = words[i];
			String lower = w.toLowerCase(Locale.ROOT);
			String v = de ? VERB_DE.get(lower) : VERB_EN.get(lower);
			if (v == null) {
				v = de ? firstPersonDe(lower) : firstPersonEn(lower);
			}
			if (v != null) {
				words[i] = v;
				return String.join(" ", words);
			}
		}
		return rest;
	}

	/** works→work, lives→live, uses→use, goes→go, tries→try; a word without a third-person ending is not a verb. */
	private static String firstPersonEn(String w) {
		if (w.length() < 3 || !w.endsWith("s") || w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) {
			return null;
		}
		if (w.endsWith("ies")) {
			return w.substring(0, w.length() - 3) + "y";
		}
		if (w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes") || w.endsWith("zes") || w.endsWith("oes")) {
			return w.substring(0, w.length() - 2);
		}
		return w.substring(0, w.length() - 1);
	}

	/** arbeitet→arbeite, wohnt→wohne, besitzt→besitze, kennt→kenne; the -t ending is the third person's. */
	private static String firstPersonDe(String w) {
		if (w.length() < 4 || !w.endsWith("t")) {
			return null;
		}
		return w.endsWith("et") ? w.substring(0, w.length() - 2) + "e" : w.substring(0, w.length() - 1) + "e";
	}
}
