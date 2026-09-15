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
package se.hirt.mnemic.proposal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import se.hirt.mnemic.protocol.MnemicException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The caller's structured reading of an observation, under extraction spec version 1 (EXTRACTION.md, Layer 1): flat
 * arrays with string refs, no unions. A proposal is a claim to be checked, never truth; every field is optional except
 * what a fact minimally needs.
 * <p>
 * Subjects and objects are written as an entity ref ({@code "e1"}), an inline entity name ({@code "Anna Lindqvist"}),
 * or {@code "self"} / {@code "I"} / {@code "me"} for the owner. Objects of predicates whose range is {@code literal}
 * are plain strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// Jackson builds these records reflectively, which the native image only allows for registered types.
@RegisterForReflection(targets = {Proposal.class, Proposal.EntityRef.class, Proposal.EventRef.class,
		Proposal.FactRef.class, Proposal.ValidTime.class, Proposal.Derivation.class, Proposal.PredicateDef.class})
public record Proposal(@JsonProperty("spec_version")
Integer specVersion, List<EntityRef> entities, List<EventRef> events, List<FactRef> facts,
		List<PredicateDef> predicates, List<ClosureRef> closures) {

	public Proposal(Integer specVersion, List<EntityRef> entities, List<EventRef> events, List<FactRef> facts,
			List<PredicateDef> predicates) {
		this(specVersion, entities, events, facts, predicates, List.of());
	}

	public static final int CURRENT_SPEC_VERSION = 1;

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

	public Proposal {
		entities = entities == null ? List.of() : entities;
		events = events == null ? List.of() : events;
		facts = facts == null ? List.of() : facts;
		predicates = predicates == null ? List.of() : predicates;
		closures = closures == null ? List.of() : closures;
	}

	/** A parsed proposal with what the parser had to say about it: keys it did not know and ignored. */
	public record Parsed(Proposal proposal, List<String> warnings) {
	}

	public static Proposal parse(String json) {
		return parseWithWarnings(json).proposal();
	}

	public static Parsed parseWithWarnings(String json) {
		try {
			return parsed(MAPPER.readTree(json));
		} catch (JsonProcessingException truncated) {
			// A reply cut off by the model's output limit: keep every complete top-level element, drop the partial
			// one, close what is open. Nothing is invented.
			String repaired = repairTruncated(json);
			if (repaired != null) {
				try {
					return parsed(MAPPER.readTree(repaired));
				} catch (Exception ignored) {
					// fall through to the original error
				}
			}
			throw MnemicException.invalidArgument("'proposal' is not a valid proposal: " + truncated.getMessage()
					+ ". Example: {\"facts\": [{\"subject\": \"self\", \"predicate\": \"works_at\", \"object\": \"Hooli\"}]}");
		} catch (Exception e) {
			throw MnemicException.invalidArgument("'proposal' is not a valid proposal: " + e.getMessage()
					+ ". Example: {\"facts\": [{\"subject\": \"self\", \"predicate\": \"works_at\", \"object\": \"Hooli\"}]}");
		}
	}

	public static Proposal from(Map<String, Object> map) {
		return fromWithWarnings(map).proposal();
	}

	public static Parsed fromWithWarnings(Map<String, Object> map) {
		try {
			return parsed(MAPPER.valueToTree(map));
		} catch (JsonProcessingException e) {
			throw MnemicException.invalidArgument("'proposal' is not a valid proposal: " + e.getMessage());
		}
	}

	private static Parsed parsed(JsonNode root) throws JsonProcessingException {
		JsonNode tree = normalise(root);
		List<String> warnings = unknownKeys(tree);
		return new Parsed(MAPPER.treeToValue(tree, Proposal.class), warnings);
	}

	private static final Map<String, Set<String>> KNOWN = Map.of("",
			Set.of("spec_version", "entities", "events", "facts", "predicates", "closures"), "entities",
			Set.of("ref", "name", "type", "aliases"), "events", Set.of("ref", "type", "participants", "valid_time"),
			"facts",
			Set.of("subject", "predicate", "object", "qualifier", "scope", "valid_time", "ended", "derived_from",
					"derivation", "caller_confidence", "negated", "only"),
			"predicates",
			Set.of("name", "description", "domain", "range", "functional", "functional_scope", "symmetric", "inverse",
					"volatility", "lexicon", "render", "qualifiers", "aliases"),
			"closures", Set.of("subject", "predicate", "type"), "valid_time", Set.of("start", "end", "precision"),
			"derivation", Set.of("kind"));

	/**
	 * Keys the spec does not define are ignored by the reader; every ignored key is named, with the keys that exist
	 * there, so a caller learns that a nuance it sent did not land.
	 */
	static List<String> unknownKeys(JsonNode root) {
		var out = new ArrayList<String>();
		if (root == null || !root.isObject()) {
			return out;
		}
		unknownIn(root, "", "proposal", out);
		for (String section : List.of("entities", "events", "facts", "predicates", "closures")) {
			JsonNode list = root.get(section);
			if (list == null || !list.isArray()) {
				continue;
			}
			int i = 0;
			for (JsonNode item : list) {
				String where = section + "[" + i++ + "]";
				if (item.isObject()) {
					unknownIn(item, section, where, out);
					for (String nested : List.of("valid_time", "derivation")) {
						JsonNode n = item.get(nested);
						if (n != null && n.isObject()) {
							unknownIn(n, nested, where + "." + nested, out);
						}
					}
				}
			}
		}
		return out;
	}

	private static void unknownIn(JsonNode node, String kind, String where, List<String> out) {
		Set<String> known = KNOWN.get(kind);
		var names = new ArrayList<String>();
		node.fieldNames().forEachRemaining(names::add);
		for (String name : names) {
			if (!known.contains(name)) {
				out.add("Unknown key '" + name + "' on " + where + " was ignored; keys the spec defines there: "
						+ String.join(", ", new TreeSet<>(known)) + ".");
			}
		}
	}

	/**
	 * Cuts an unterminated JSON object back to the end of the last element that closed at depth 2 (an entity, event,
	 * fact, or predicate inside a top-level array), then closes the array and the object. Returns null when no such
	 * element exists or the text is not an object.
	 */
	static String repairTruncated(String json) {
		if (json == null) {
			return null;
		}
		String s = json.trim();
		if (!s.startsWith("{")) {
			return null;
		}
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		int lastComplete = -1;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			switch (c) {
			case '"' -> inString = true;
			case '{', '[' -> depth++;
			case '}', ']' -> {
				depth--;
				if (depth == 2 && c == '}') {
					lastComplete = i;
				}
				if (depth == 0) {
					return null; // it closed after all: not a truncation problem
				}
			}
			default -> {
			}
			}
		}
		if (lastComplete < 0) {
			return null;
		}
		return s.substring(0, lastComplete + 1) + "]}";
	}

	/**
	 * Shapes models produce that the spec does not ask for but that have one honest reading: a fact whose
	 * {@code object} (or {@code subject}) is a list becomes one fact per element; a repeated key keeps its last value
	 * (Jackson's tree reader already does); a bare string for {@code derivation} is its kind; {@code confidence} and
	 * {@code certainty} are read as {@code caller_confidence}.
	 */
	static JsonNode normalise(JsonNode root) {
		if (root == null || !root.isObject()) {
			return root;
		}
		JsonNode facts = root.get("facts");
		if (facts == null || !facts.isArray()) {
			return root;
		}
		var expanded = MAPPER.createArrayNode();
		for (JsonNode f : facts) {
			if (f.isObject() && f.get("derivation") != null && f.get("derivation").isTextual()) {
				((ObjectNode) f).set("derivation", MAPPER.createObjectNode().put("kind", f.get("derivation").asText()));
			}
			if (f.isObject()) {
				var o = (ObjectNode) f;
				if (o.get("caller_confidence") == null) {
					if (o.get("confidence") != null && o.get("confidence").isNumber()) {
						o.put("caller_confidence", o.get("confidence").asDouble());
					} else if (o.get("certainty") != null && o.get("certainty").isTextual()) {
						Double c = certainty(o.get("certainty").asText());
						if (c != null) {
							o.put("caller_confidence", c);
						}
					} else if (o.get("certainty") != null && o.get("certainty").isNumber()) {
						o.put("caller_confidence", o.get("certainty").asDouble());
					}
				}
				o.remove("confidence");
				o.remove("certainty");
			}
			expanded.addAll(expand(f, "subject"));
		}
		var result = MAPPER.createArrayNode();
		for (JsonNode f : expanded) {
			result.addAll(expand(f, "object"));
		}
		((ObjectNode) root).set("facts", result);
		return root;
	}

	/** Words a caller uses for how sure the user was, as a number the spec defines. */
	static Double certainty(String word) {
		return switch (word.trim().toLowerCase(Locale.ROOT)) {
		case "stated", "firm", "certain", "sure", "confirmed", "known" -> 1.0;
		case "believed", "belief", "thinks", "think", "unsure", "uncertain", "probable", "likely", "recollection" ->
			0.5;
		case "guess", "guessed", "maybe", "possible", "unlikely", "speculation" -> 0.3;
		default -> null;
		};
	}

	private static List<JsonNode> expand(JsonNode fact, String field) {
		JsonNode v = fact.get(field);
		if (v == null || !v.isArray() || !fact.isObject()) {
			return List.of(fact);
		}
		var out = new ArrayList<JsonNode>();
		for (JsonNode item : v) {
			var copy = ((ObjectNode) fact).deepCopy();
			copy.set(field, item);
			out.add(copy);
		}
		return out;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EntityRef(String ref, String name, String type, List<String> aliases) {
		public EntityRef {
			aliases = aliases == null ? List.of() : aliases;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EventRef(String ref, String type, List<String> participants, @JsonProperty("valid_time")
	ValidTime validTime) {
		public EventRef {
			participants = participants == null ? List.of() : participants;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FactRef(String subject, String predicate, String object, String qualifier, String scope,
			@JsonProperty("valid_time")
			ValidTime validTime, Boolean ended, @JsonProperty("derived_from")
			List<String> derivedFrom, Derivation derivation, @JsonProperty("caller_confidence")
			Double callerConfidence, Boolean negated, Boolean only) {
		public FactRef {
			derivedFrom = derivedFrom == null ? List.of() : derivedFrom;
		}

		public FactRef(String subject, String predicate, String object, String qualifier, String scope,
				ValidTime validTime, Boolean ended, List<String> derivedFrom, Derivation derivation,
				Double callerConfidence) {
			this(subject, predicate, object, qualifier, scope, validTime, ended, derivedFrom, derivation,
					callerConfidence, null, null);
		}

		/** {@code asserted}, {@code negated}, or {@code only} (family Q). */
		public String mode() {
			return Boolean.TRUE.equals(negated) ? "negated" : Boolean.TRUE.equals(only) ? "only" : "asserted";
		}
	}

	/**
	 * A completeness marker (family Q): the recorded facts under {@code predicate} for {@code subject} whose objects
	 * are of {@code type} are all of them. "Those are all the properties I own": owns, place.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClosureRef(String subject, String predicate, String type) {
	}

	/** {@code start}/{@code end} are ISO dates or partial dates ("2018", "2018-03"); {@code precision} is explicit. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ValidTime(String start, String end, String precision) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Derivation(String kind) {
	}

	/** A caller-defined predicate (EXTRACTION.md, Predicate registry). */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PredicateDef(String name, String description, String domain, String range, Boolean functional,
			@JsonProperty("functional_scope")
			String functionalScope, Boolean symmetric, String inverse, String volatility, List<String> lexicon,
			String render, List<String> qualifiers, List<String> aliases) {
		public PredicateDef {
			lexicon = lexicon == null ? List.of() : lexicon;
			qualifiers = qualifiers == null ? List.of() : qualifiers;
			aliases = aliases == null ? List.of() : aliases;
		}
	}
}
