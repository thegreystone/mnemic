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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The language of the fact layer (MNEMIC_LANGUAGE). Observations stay in whatever language they were said; facts are
 * rendered from templates, and the templates, the temporal suffixes, the words around a negation, a restriction, or a
 * closure, and the family qualifiers live in {@code lang/<code>.json} resources loaded through {@link LanguagePack}.
 * Switching the store's language re-renders every fact from what is stored; nothing is lost, since the words are never
 * the record.
 */
public enum Lang {
	EN("en"), DE("de"), SV("sv"), FR("fr"), ES("es");

	private final String code;

	Lang(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	/** The resource-backed cue and template pack for this language. */
	public LanguagePack pack() {
		return LanguagePack.of(this);
	}

	/**
	 * {@code en}, {@code de}, {@code sv}, {@code fr}, or {@code es}; anything else is refused so a typo cannot render a
	 * store in the wrong words.
	 */
	public static Lang of(String code) {
		if (code == null || code.isBlank()) {
			return EN;
		}
		String c = code.trim().toLowerCase(Locale.ROOT);
		for (Lang l : values()) {
			if (l.code.equals(c)) {
				return l;
			}
		}
		throw new IllegalArgumentException("Unsupported language '" + code + "'; use en, de, sv, fr, or es.");
	}

	/**
	 * The forms an English word of a question may be the plural of, the word itself first: "companies" → company,
	 * "boxes" → box, "vehicles" → vehicle. Which one names a kind is for the type registry to say.
	 */
	public static List<String> singulars(String word) {
		return singulars(word, EN);
	}

	/**
	 * The forms a word of a question in {@code lang} may be the plural or definite form of, the word itself first:
	 * applies English rules plus {@code lang}'s suffix rules from {@link LanguagePack}.
	 */
	public static List<String> singulars(String word, Lang lang) {
		var out = new LinkedHashSet<String>();
		out.add(word);
		if (word == null || word.length() < 4) {
			return new ArrayList<>(out);
		}
		applyPluralRules(word, LanguagePack.of(EN).pluralSuffixes(), out);
		if (lang != null && lang != EN) {
			applyPluralRules(word, LanguagePack.of(lang).pluralSuffixes(), out);
		}
		return new ArrayList<>(out);
	}

	private static void applyPluralRules(String word, List<LanguagePack.PluralRule> rules, LinkedHashSet<String> out) {
		for (LanguagePack.PluralRule rule : rules) {
			String candidate = rule.apply(word);
			if (candidate != null && !candidate.isEmpty()) {
				out.add(candidate);
			}
		}
	}

	// ── temporal suffixes ──

	public String since(String start) {
		return pack().fact().formatSince(start);
	}

	public String fromEnded(String start) {
		return pack().fact().formatFromEnded(start);
	}

	public String until(String end) {
		return pack().fact().formatUntil(end);
	}

	public String ended() {
		return pack().fact().ended();
	}

	/** The words a suffix may start with, for stripping one before a new suffix is appended. */
	public static String suffixWords() {
		return LanguagePack.allSuffixWordsRegex();
	}

	// ── the words around a fact ──

	public String believed() {
		return pack().fact().believed();
	}

	public String notPrefix() {
		return pack().fact().notPrefix();
	}

	public String onlyWithin(String bound) {
		return pack().fact().formatOnlyWithin(bound);
	}

	public String closure(String subject, String verbPhrase, String type) {
		return pack().fact().formatClosure(subject, verbPhrase, typePlural(type));
	}

	public String typePlural(String type) {
		String mapped = pack().fact().typePlurals().get(type);
		if (mapped != null) {
			return mapped;
		}
		if (this != EN) {
			return type;
		}
		return type.endsWith("s") ? type
				: type.endsWith("y") && !type.matches(".*[aeiou]y") ? type.substring(0, type.length() - 1) + "ies"
						: type.endsWith("ch") || type.endsWith("sh") || type.endsWith("x") ? type + "es" : type + "s";
	}

	/** A family qualifier in the language's words; the stored value stays the vocabulary's (English) term. */
	public String qualifier(String qualifier) {
		if (qualifier == null || this == EN) {
			return qualifier;
		}
		return pack().fact().qualifiers().getOrDefault(qualifier.toLowerCase(Locale.ROOT), qualifier);
	}
}
