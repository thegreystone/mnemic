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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The language of the fact layer (MNEMIC_LANGUAGE). Observations stay in whatever language they were said; facts are
 * rendered from templates, and the templates, the temporal suffixes, the words around a negation, a restriction, or a
 * closure, and the family qualifiers are the language's. Switching the store's language re-renders every fact from what
 * is stored; nothing is lost, since the words are never the record.
 */
public enum Lang {
	EN("en"), DE("de");

	private final String code;

	Lang(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	/** {@code en} or {@code de}; anything else is refused so a typo cannot render a store in the wrong words. */
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
		throw new IllegalArgumentException("Unsupported language '" + code + "'; use en or de.");
	}

	/**
	 * The forms an English word of a question may be the plural of, the word itself first: "companies" → company,
	 * "boxes" → box, "vehicles" → vehicle. Which one names a kind is for the type registry to say.
	 */
	public static List<String> singulars(String word) {
		var out = new ArrayList<String>();
		out.add(word);
		if (word == null || word.length() < 4) {
			return out;
		}
		if (word.endsWith("ies")) {
			out.add(word.substring(0, word.length() - 3) + "y");
		}
		if (word.endsWith("es")) {
			out.add(word.substring(0, word.length() - 2));
		}
		if (word.endsWith("s") && !word.endsWith("ss")) {
			out.add(word.substring(0, word.length() - 1));
		}
		return out;
	}

	// ── temporal suffixes ──

	public String since(String start) {
		return this == DE ? " (seit " + start + ")" : " (since " + start + ")";
	}

	public String fromEnded(String start) {
		return this == DE ? " (von " + start + ", beendet)" : " (from " + start + ", ended)";
	}

	public String until(String end) {
		return this == DE ? " (bis " + end + ")" : " (until " + end + ")";
	}

	public String ended() {
		return this == DE ? " (beendet)" : " (ended)";
	}

	/** The words a suffix may start with, for stripping one before a new suffix is appended. */
	public static String suffixWords() {
		return "since|from|until|ended|seit|von|bis|beendet";
	}

	// ── the words around a fact ──

	public String believed() {
		return this == DE ? " (vermutet)" : " (believed)";
	}

	public String notPrefix() {
		return this == DE ? "nicht: " : "not: ";
	}

	public String onlyWithin(String bound) {
		return this == DE ? "nur innerhalb von " + bound : "only within " + bound;
	}

	public String closure(String subject, String verbPhrase, String type) {
		return this == DE
				? "Was " + subject + " an " + typePlural(type) + " " + verbPhrase + ", ist vollständig erfasst"
				: "what " + subject + " " + verbPhrase + " among " + typePlural(type) + " is completely recorded";
	}

	private static final Map<String, String> PLURAL_DE = Map.ofEntries(Map.entry("place", "Orten"),
			Map.entry("country", "Ländern"), Map.entry("person", "Personen"),
			Map.entry("organization", "Organisationen"), Map.entry("project", "Projekten"),
			Map.entry("product", "Produkten"), Map.entry("team", "Teams"), Map.entry("thing", "Dingen"),
			Map.entry("technology", "Technologien"), Map.entry("domain", "Domains"), Map.entry("event", "Ereignissen"));

	public String typePlural(String type) {
		if (this == DE) {
			return PLURAL_DE.getOrDefault(type, type);
		}
		return switch (type) {
		case "person" -> "people";
		default -> type.endsWith("s") ? type
				: type.endsWith("y") && !type.matches(".*[aeiou]y") ? type.substring(0, type.length() - 1) + "ies"
						: type.endsWith("ch") || type.endsWith("sh") || type.endsWith("x") ? type + "es" : type + "s";
		};
	}

	private static final Map<String, String> QUALIFIER_DE = Map.ofEntries(Map.entry("mother", "Mutter"),
			Map.entry("father", "Vater"), Map.entry("parent", "Elternteil"), Map.entry("mom", "Mutter"),
			Map.entry("dad", "Vater"), Map.entry("stepmother", "Stiefmutter"), Map.entry("stepfather", "Stiefvater"),
			Map.entry("stepparent", "Stiefelternteil"), Map.entry("brother", "Bruder"),
			Map.entry("sister", "Schwester"), Map.entry("sibling", "Geschwister"),
			Map.entry("half-brother", "Halbbruder"), Map.entry("half-sister", "Halbschwester"),
			Map.entry("half-sibling", "Halbgeschwister"), Map.entry("stepbrother", "Stiefbruder"),
			Map.entry("stepsister", "Stiefschwester"), Map.entry("stepsibling", "Stiefgeschwister"),
			Map.entry("twin", "Zwilling"), Map.entry("twin brother", "Zwillingsbruder"),
			Map.entry("twin sister", "Zwillingsschwester"), Map.entry("wife", "Ehefrau"),
			Map.entry("husband", "Ehemann"), Map.entry("spouse", "Ehepartner"), Map.entry("partner", "Partner"),
			Map.entry("girlfriend", "Freundin"), Map.entry("boyfriend", "Freund"), Map.entry("fiancé", "Verlobter"),
			Map.entry("fiancée", "Verlobte"), Map.entry("step-parent", "Stiefelternteil"),
			Map.entry("grandmother", "Großmutter"), Map.entry("grandfather", "Großvater"),
			Map.entry("grandparent", "Großelternteil"),
			Map.entry("maternal grandmother", "Großmutter mütterlicherseits"),
			Map.entry("maternal grandfather", "Großvater mütterlicherseits"),
			Map.entry("maternal grandparent", "Großelternteil mütterlicherseits"),
			Map.entry("paternal grandmother", "Großmutter väterlicherseits"),
			Map.entry("paternal grandfather", "Großvater väterlicherseits"),
			Map.entry("paternal grandparent", "Großelternteil väterlicherseits"), Map.entry("aunt", "Tante"),
			Map.entry("uncle", "Onkel"), Map.entry("aunt or uncle", "Tante oder Onkel"),
			Map.entry("cousin", "Cousin oder Cousine"), Map.entry("mother-in-law", "Schwiegermutter"),
			Map.entry("father-in-law", "Schwiegervater"), Map.entry("parent-in-law", "Schwiegerelternteil"),
			Map.entry("son-in-law", "Schwiegersohn"), Map.entry("daughter-in-law", "Schwiegertochter"),
			Map.entry("child-in-law", "Schwiegerkind"), Map.entry("brother-in-law", "Schwager"),
			Map.entry("sister-in-law", "Schwägerin"), Map.entry("sibling-in-law", "Schwager oder Schwägerin"));

	/** A family qualifier in the language's words; the stored value stays the vocabulary's (English) term. */
	public String qualifier(String qualifier) {
		if (qualifier == null || this == EN) {
			return qualifier;
		}
		return QUALIFIER_DE.getOrDefault(qualifier.toLowerCase(Locale.ROOT), qualifier);
	}
}
