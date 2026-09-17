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
import se.hirt.mnemic.protocol.MnemicException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One way a derived predicate holds (EVALUATION.md family K, EXTRACTION.md "Derived predicates"): a {@code path} of
 * hops from the subject to the object, walked over the current facts. A hop is a predicate, optionally bound to a
 * qualifier ({@code parent_of[mother]}) and optionally walked against its direction ({@code ^parent_of}: from the
 * object of a fact to its subject, so "child of"). Bounded repetition ({@code parent_of{1,4}}) expands to one rule per
 * length; unbounded repetition is refused, since materialising it never ends and a valid time over it means nothing.
 * {@code not} names predicates that must not hold directly between the two ends (a step-parent is the spouse of a
 * parent who is not a parent); {@code min_paths} and {@code max_paths} bound how many distinct paths must exist (full
 * siblings share two parents, half siblings one); {@code min_degree} asks that both ends have at least that many
 * neighbours along their end hop (half siblings are only called so when both have two known parents); {@code qualifier}
 * is what the derived fact carries, and {@code by} chooses it instead by the value of an attribute of the subject
 * ({@code {"attribute": "gender", "values": {"female": "aunt", "male": "uncle"}}}): the attribute is any predicate
 * whose facts give the subject a literal value, read as EXTRACTION.md describes, ahead of what other facts' qualifiers
 * imply. Nothing about any particular attribute is known here.
 * <p>
 * A predicate's {@code defined_as} is a list of rules, tried in order for each pair: the first rule that holds names
 * the qualifier, so the specific rules go first.
 */
public record Rule(List<Hop> path, List<String> not, Integer minPaths, Integer maxPaths, String qualifier,
		String attribute, Map<String, String> byValue, Integer minDegree) {

	/** A hop of a path. */
	public record Hop(String predicate, String qualifier, boolean inverse) {
		@Override
		public String toString() {
			return (inverse ? "^" : "") + predicate + (qualifier == null ? "" : "[" + qualifier + "]");
		}
	}

	/** The longest path a rule may walk, after repetition is expanded. */
	public static final int MAX_PATH = 6;

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern HOP = Pattern
			.compile("^(\\^)?([a-z][a-z0-9_]*)(?:\\[([^\\]]+)])?(?:\\{(\\d+),(\\d+)})?([+*])?$");

	public Rule {
		path = List.copyOf(path);
		not = not == null ? List.of() : List.copyOf(not);
		byValue = byValue == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(byValue));
		if (attribute != null && byValue.isEmpty()) {
			throw MnemicException.invalidArgument("'by' names attribute '" + attribute + "' but no values.");
		}
		if (attribute == null && !byValue.isEmpty()) {
			throw MnemicException.invalidArgument("'by' gives values but names no attribute.");
		}
	}

	/**
	 * The qualifier a derived fact carries: the one for the subject's attribute value when {@code by} names it and the
	 * value is known, else the plain one.
	 */
	public String qualifierFor(String value) {
		return value != null && byValue.containsKey(value) ? byValue.get(value) : qualifier;
	}

	/** Whether a derived qualifier is one {@code by} chose: the plain one, given for want of a value, is not. */
	public boolean chosenByValue(String derivedQualifier) {
		return attribute != null && byValue.containsValue(derivedQualifier);
	}

	/**
	 * Parses a {@code defined_as} value: a rule object, a list of them, or a path written as a string
	 * ({@code "parent_of, parent_of"}).
	 */
	public static List<Rule> parse(Object definedAs) {
		if (definedAs == null) {
			return List.of();
		}
		List<?> items = definedAs instanceof List<?> l ? l : List.of(definedAs);
		var out = new ArrayList<Rule>();
		for (Object item : items) {
			if (item instanceof String s) {
				out.addAll(expand(List.of(s.split(",")), List.of(), null, null, null, null, Map.of(), null));
			} else if (item instanceof Map<?, ?> m) {
				Object path = m.get("path");
				if (path == null) {
					throw MnemicException
							.invalidArgument("a rule needs a 'path', e.g. {\"path\": [\"parent_of\", \"parent_of\"]}.");
				}
				List<String> hops = path instanceof List<?> l ? l.stream().map(String::valueOf).toList()
						: List.of(String.valueOf(path).split(","));
				List<String> not = m.get("not") == null ? List.of() : strings(m.get("not"));
				String attribute = null;
				var byValue = new LinkedHashMap<String, String>();
				if (m.get("by") != null) {
					if (!(m.get("by") instanceof Map<?, ?> by) || !(by.get("values") instanceof Map<?, ?> values)
							|| by.get("attribute") == null || String.valueOf(by.get("attribute")).isBlank()) {
						throw MnemicException.invalidArgument("'by' takes an object naming an attribute predicate and "
								+ "the qualifier for each of its values, e.g. {\"attribute\": \"gender\", \"values\": "
								+ "{\"female\": \"aunt\", \"male\": \"uncle\"}}.");
					}
					attribute = String.valueOf(by.get("attribute")).trim().toLowerCase(Locale.ROOT);
					for (Map.Entry<?, ?> v : values.entrySet()) {
						byValue.put(String.valueOf(v.getKey()).trim().toLowerCase(Locale.ROOT),
								String.valueOf(v.getValue()));
					}
					if (byValue.isEmpty()) {
						throw MnemicException
								.invalidArgument("'by' names no values for attribute '" + attribute + "'.");
					}
				} else if (m.get("by_gender") != null) {
					// The first spelling, kept for stores and callers that use it: by the attribute 'gender'.
					if (!(m.get("by_gender") instanceof Map<?, ?> g)) {
						throw MnemicException.invalidArgument("'by_gender' takes an object, e.g. {\"female\": "
								+ "\"grandmother\", \"male\": \"grandfather\"}.");
					}
					attribute = "gender";
					for (Map.Entry<?, ?> ge : g.entrySet()) {
						byValue.put(String.valueOf(ge.getKey()).trim().toLowerCase(Locale.ROOT),
								String.valueOf(ge.getValue()));
					}
				}
				out.addAll(expand(hops, not, count(m.get("min_paths")), count(m.get("max_paths")),
						m.get("qualifier") == null ? null : String.valueOf(m.get("qualifier")), attribute, byValue,
						count(m.get("min_degree"))));
			} else {
				throw MnemicException.invalidArgument("'defined_as' takes a rule object or a list of them, e.g. "
						+ "[{\"path\": [\"parent_of\", \"parent_of\"], \"qualifier\": \"grandparent\"}].");
			}
		}
		if (out.isEmpty()) {
			throw MnemicException.invalidArgument("'defined_as' names no rule.");
		}
		return List.copyOf(out);
	}

	/** Rules from their stored form; null or blank is none. */
	public static List<Rule> fromJson(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return parse(JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {
			}));
		} catch (MnemicException e) {
			throw e;
		} catch (Exception e) {
			throw MnemicException.internal("rule json", e);
		}
	}

	public static String toJson(List<Rule> rules) {
		try {
			return JSON.writeValueAsString(rules.stream().map(Rule::toMap).toList());
		} catch (Exception e) {
			throw MnemicException.internal("rule json", e);
		}
	}

	/** The rule as a caller writes it, for {@code inspect} and the log. */
	public Map<String, Object> toMap() {
		var m = new LinkedHashMap<String, Object>();
		m.put("path", path.stream().map(Hop::toString).toList());
		if (!not.isEmpty()) {
			m.put("not", not);
		}
		if (minPaths != null) {
			m.put("min_paths", minPaths);
		}
		if (maxPaths != null) {
			m.put("max_paths", maxPaths);
		}
		if (qualifier != null) {
			m.put("qualifier", qualifier);
		}
		if (attribute != null) {
			var by = new LinkedHashMap<String, Object>();
			by.put("attribute", attribute);
			by.put("values", byValue);
			m.put("by", by);
		}
		if (minDegree != null) {
			m.put("min_degree", minDegree);
		}
		return m;
	}

	@Override
	public String toString() {
		return String.join(", ", path.stream().map(Hop::toString).toList());
	}

	// ── parsing ──────────────────────────────────────────────────────────

	/** A hop with its repetition, before expansion. */
	private record Piece(Hop hop, int min, int max) {
	}

	private static List<Rule> expand(
		List<String> hops, List<String> not, Integer minPaths, Integer maxPaths, String qualifier, String attribute,
		Map<String, String> byValue, Integer minDegree) {
		var pieces = new ArrayList<Piece>();
		for (String raw : hops) {
			String h = raw.trim().toLowerCase(Locale.ROOT);
			if (h.isEmpty()) {
				continue;
			}
			Matcher m = HOP.matcher(h);
			if (!m.matches()) {
				throw MnemicException.invalidArgument("'" + raw.trim() + "' is not a hop: write a predicate name, "
						+ "optionally ^ before it to walk it backwards, [qualifier] after it, or {min,max} to repeat "
						+ "it, e.g. ^parent_of[mother] or parent_of{1,4}.");
			}
			if (m.group(6) != null) {
				throw MnemicException.invalidArgument("unbounded repetition '" + raw.trim() + "' is refused: a "
						+ "derived predicate is materialised, so name a bound, e.g. " + m.group(2) + "{1,4}.");
			}
			int min = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
			int max = m.group(5) == null ? 1 : Integer.parseInt(m.group(5));
			if (min < 1 || max < min || max > MAX_PATH) {
				throw MnemicException.invalidArgument("repetition '" + raw.trim() + "' must be {min,max} with "
						+ "1 <= min <= max <= " + MAX_PATH + ".");
			}
			pieces.add(new Piece(new Hop(m.group(2), m.group(3) == null ? null : m.group(3).trim(), m.group(1) != null),
					min, max));
		}
		if (pieces.isEmpty()) {
			throw MnemicException.invalidArgument("a rule's 'path' names no hop.");
		}
		var out = new ArrayList<Rule>();
		expand(pieces, 0, new ArrayList<>(), out, not, minPaths, maxPaths, qualifier, attribute, byValue, minDegree);
		return out;
	}

	private static void expand(
		List<Piece> pieces, int at, List<Hop> sofar, List<Rule> out, List<String> not, Integer minPaths,
		Integer maxPaths, String qualifier, String attribute, Map<String, String> byValue, Integer minDegree) {
		if (at == pieces.size()) {
			if (sofar.size() > MAX_PATH) {
				throw MnemicException.invalidArgument(
						"a path may walk at most " + MAX_PATH + " hops; this one walks " + sofar.size() + ".");
			}
			out.add(new Rule(List.copyOf(sofar), not, minPaths, maxPaths, qualifier, attribute, byValue, minDegree));
			return;
		}
		Piece p = pieces.get(at);
		for (int n = p.min(); n <= p.max(); n++) {
			var next = new ArrayList<>(sofar);
			for (int i = 0; i < n; i++) {
				next.add(p.hop());
			}
			expand(pieces, at + 1, next, out, not, minPaths, maxPaths, qualifier, attribute, byValue, minDegree);
		}
	}

	private static List<String> strings(Object o) {
		if (o instanceof List<?> l) {
			return l.stream().map(x -> String.valueOf(x).trim().toLowerCase(Locale.ROOT)).toList();
		}
		return List.of(String.valueOf(o).trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
	}

	private static Integer count(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Number n) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(o).trim());
		} catch (NumberFormatException e) {
			throw MnemicException.invalidArgument("'min_paths' and 'max_paths' take a whole number, not '" + o + "'.");
		}
	}
}
