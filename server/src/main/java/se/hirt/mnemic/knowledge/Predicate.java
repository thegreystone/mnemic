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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A registered predicate (EXTRACTION.md, Predicate registry). Layer 2 reasons over these properties, never over the
 * name. {@code x:} predicates are stored with wildcard domain and range, an empty lexicon, and a rendering derived from
 * the name; they are reachable lexically only.
 *
 * @param functionalScope
 *            {@code null}, or {@code "scope"} meaning functional per (subject, scope entity)
 * @param volatility
 *            low | medium | high; drives the staleness annotation, never ranking
 * @param render
 *            template with {@code {subject} {object} {scope} {qualifier|default}} and optional {@code [[ ... ]]}
 *            segments that vanish when a placeholder inside them is empty
 */
public record Predicate(String name, String description, List<String> domain, List<String> range, boolean functional,
		String functionalScope, boolean symmetric, String inverse, String volatility, List<String> lexicon,
		String render, List<String> qualifiers, List<String> aliases, List<String> inverseLexicon, Long definedBy,
		boolean seed) {

	/**
	 * Qualifiers that name the same relation from the two sides of it: brother and sister are one relation seen from a
	 * male and a female subject. A symmetric fact is stored once, with the qualifier describing its subject; asked from
	 * the other side ("Mattias's half-sister" against "Mattias is Clara's half-brother") the question's qualifier must
	 * match the stored one's family, since the other person's gender is not on record.
	 */
	private static final List<Set<String>> QUALIFIER_FAMILIES = List.of(Set.of("brother", "sister", "sibling"),
			Set.of("half-brother", "half-sister", "half-sibling"), Set.of("stepbrother", "stepsister", "stepsibling"),
			Set.of("twin", "twin brother", "twin sister"), Set.of("wife", "husband", "spouse", "partner"),
			Set.of("mother", "father", "parent", "mom", "dad"), Set.of("stepmother", "stepfather", "stepparent"));

	/**
	 * Days without confirmation after which an open fact on this predicate is called likely changed. A timeless
	 * predicate (low volatility: born_in, parent_of) never is.
	 */
	public int stalenessDays() {
		return switch (volatility == null ? "medium" : volatility) {
		case "high" -> 180;
		case "medium" -> 365;
		default -> Integer.MAX_VALUE;
		};
	}

	/** Whether facts under this predicate age at all: anything but low volatility. */
	public boolean ages() {
		return !"low".equals(volatility);
	}

	public static boolean sameQualifierFamily(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		String x = a.trim().toLowerCase(Locale.ROOT);
		String y = b.trim().toLowerCase(Locale.ROOT);
		if (x.equals(y)) {
			return true;
		}
		return QUALIFIER_FAMILIES.stream().anyMatch(f -> f.contains(x) && f.contains(y));
	}

	/** Domain and range share a concrete type: a relation between like things, where a term names a side. */
	public boolean sameType() {
		return !domain.contains("*") && !range.contains("*") && domain.stream().anyMatch(range::contains);
	}

	public boolean isExtended() {
		return name.startsWith("x:");
	}

	public boolean literalRange() {
		return range.contains("literal");
	}

	public boolean acceptsSubject(String type) {
		return domain.contains("*") || domain.contains(type) || "unknown".equals(type)
				|| (Names.isPlace(type) && domain.contains("place"));
	}

	public boolean acceptsObject(String type) {
		return range.contains("*") || range.contains(type) || "unknown".equals(type)
				|| (Names.isPlace(type) && range.contains("place"));
	}

	private static final Set<String> AUXILIARY = Set.of("is", "are", "was", "were", "has", "had", "does", "did", "can",
			"will", "would", "should");
	private static final Map<String, String> IRREGULAR = Map.of("decided", "decide", "died", "die", "has", "have",
			"moved", "move", "founded", "found", "married", "marry", "left", "leave");

	/**
	 * The negation of a rendered fact (EVALUATION.md, family Q): "{subject} owns {object}" → "{subject} does not own
	 * {object}", "{subject} was born in" → "was not born in". {@code negatedTemplate} is the language's own template
	 * when the registry has one; otherwise the English rule applies, and a template that does not start with the
	 * subject, a verb the rule cannot lemmatise, or another language falls back to a "not:" prefix before the positive
	 * rendering, so nothing reads as asserted.
	 */
	public String renderNegated(
		Lang lang, String negatedTemplate, String subject, String object, String scope, String qualifier) {
		if (negatedTemplate != null && !negatedTemplate.isBlank()) {
			return new Predicate(name, description, domain, range, functional, functionalScope, symmetric, inverse,
					volatility, lexicon, negatedTemplate, qualifiers, aliases, inverseLexicon, definedBy, seed)
					.render(subject, object, scope, qualifier);
		}
		String full = render(subject, object, scope, qualifier);
		if (lang != Lang.EN) {
			return lang.notPrefix() + full;
		}
		String prefix = subject + " ";
		if (!full.startsWith(prefix)) {
			return "not: " + full;
		}
		String rest = full.substring(prefix.length());
		int sp = rest.indexOf(' ');
		String word = sp < 0 ? rest : rest.substring(0, sp);
		String after = sp < 0 ? "" : rest.substring(sp);
		String lw = word.toLowerCase(Locale.ROOT);
		if (AUXILIARY.contains(lw)) {
			return prefix + word + " not" + after;
		}
		String lemma = lemma(lw);
		if (lemma == null) {
			return "not: " + full;
		}
		return prefix + (lw.endsWith("ed") ? "did not " : "does not ") + lemma + after;
	}

	static String lemma(String verb) {
		if (IRREGULAR.containsKey(verb)) {
			return IRREGULAR.get(verb);
		}
		if (verb.endsWith("ies")) {
			return verb.substring(0, verb.length() - 3) + "y";
		}
		for (String suffix : new String[] {"ches", "shes", "sses", "xes", "zes", "oes"}) {
			if (verb.endsWith(suffix)) {
				return verb.substring(0, verb.length() - 2);
			}
		}
		if (verb.endsWith("s")) {
			return verb.substring(0, verb.length() - 1);
		}
		if (verb.endsWith("ed")) {
			return verb.substring(0, verb.length() - 2);
		}
		return null;
	}

	/** An exclusive restriction: "{subject} owns only within {object}". */
	public String renderOnly(Lang lang, String subject, String bound, String scope, String qualifier) {
		return render(subject, lang.onlyWithin(bound), scope, qualifier);
	}

	/** A completeness marker: "what {subject} owns among places is completely recorded". */
	public String renderClosure(Lang lang, String subject, String type) {
		return lang.closure(subject, verbPhrase(), type);
	}

	/** The template's verb phrase: "{subject} works at {object}" → "works at". */
	public String verbPhrase() {
		String t = render.replace("[[", "").replace("]]", "").replace("{subject}", "").replace("{object}", "")
				.replace("{scope}", "").replaceAll("\\{qualifier[^}]*}", "").replace("'s", "");
		return t.replaceAll("\\s+", " ").trim();
	}

	/** Renders one fact; temporal suffixes are appended by the caller. */
	public String render(String subject, String object, String scope, String qualifier) {
		String out = render;
		var sb = new StringBuilder();
		int i = 0;
		while (i < out.length()) {
			int open = out.indexOf("[[", i);
			if (open < 0) {
				sb.append(out, i, out.length());
				break;
			}
			int close = out.indexOf("]]", open);
			if (close < 0) {
				sb.append(out, i, out.length());
				break;
			}
			sb.append(out, i, open);
			String segment = out.substring(open + 2, close);
			boolean empty = (segment.contains("{scope}") && isBlank(scope))
					|| (segment.contains("{object}") && isBlank(object))
					|| (segment.contains("{qualifier}") && isBlank(qualifier));
			if (!empty) {
				sb.append(segment);
			}
			i = close + 2;
		}
		out = sb.toString();
		out = out.replace("{subject}", nz(subject)).replace("{object}", nz(object)).replace("{scope}", nz(scope));
		int q = out.indexOf("{qualifier");
		while (q >= 0) {
			int end = out.indexOf('}', q);
			String ph = out.substring(q + 1, end);
			String dflt = ph.contains("|") ? ph.substring(ph.indexOf('|') + 1) : "";
			String value = isBlank(qualifier) ? dflt : qualifier;
			out = out.substring(0, q) + value + out.substring(end + 1);
			q = out.indexOf("{qualifier");
		}
		return out.replaceAll("\\s+", " ").trim();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
