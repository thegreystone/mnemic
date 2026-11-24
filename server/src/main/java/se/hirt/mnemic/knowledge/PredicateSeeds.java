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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * The seed predicate catalogue loaded from {@code knowledge/seed-predicates.json}: the initial predicates, their
 * derivation rules, term implications, endings, lasting/containment flags, and seed groups. Parsed via Jackson's tree
 * model ({@link JsonNode}) so GraalVM native images need no reflection metadata.
 */
final class PredicateSeeds {

	private static final String RESOURCE = "knowledge/seed-predicates.json";
	private static final ObjectMapper JSON = new ObjectMapper();

	/** One seed group: its description, cue words (from language packs), and seed members. */
	record SeedGroup(String name, String description, List<String> lexicon, List<String> members) {
	}

	private static final Catalogue CATALOGUE = load();

	private record Catalogue(List<Predicate> predicates, Map<String, Predicate> byName, Map<String, List<String>> ends,
			Set<String> lasting, Map<String, List<Map<String, Object>>> rules, Map<String, Map<String, Object>> implies,
			List<SeedGroup> groups) {
	}

	private PredicateSeeds() {
	}

	/** The seed vocabulary (EXTRACTION.md). It is the initial content of the registry, nothing more. */
	static List<Predicate> predicates() {
		return CATALOGUE.predicates();
	}

	/** The seed catalogue by name, built once: read at every start and never changes. */
	static Map<String, Predicate> byName() {
		return CATALOGUE.byName();
	}

	/**
	 * The seed's endings: a decision about a thing, or owning it, ends considering it. A purchase opens owns, so it
	 * ends a consideration through owns, with nothing said about purchases.
	 */
	static Map<String, List<String>> ends() {
		return CATALOGUE.ends();
	}

	/**
	 * The seed relations a participant's death does not end: kin by blood or by record, and what is derived from them.
	 * A marriage, a partnership, an engagement, a job, a home, a membership end with the person.
	 */
	static Set<String> lasting() {
		return CATALOGUE.lasting();
	}

	/**
	 * The kinship rules (family K): what the seed vocabulary derives from parent_of, spouse_of, partner_of, and
	 * sibling_of.
	 */
	static Map<String, List<Map<String, Object>>> rules() {
		return CATALOGUE.rules();
	}

	/**
	 * What the seed vocabulary's terms imply about the subject (family K): a mother is female, a husband male. Sibling
	 * terms imply nothing here, since a reading too often attaches them to the wrong side; a store may add them through
	 * {@code correct(pred:sibling_of, {implies})}.
	 */
	static Map<String, Map<String, Object>> implies() {
		return CATALOGUE.implies();
	}

	/**
	 * The seed groups (family K): "family" holds the kinship predicates, so that one word in a question reaches them
	 * all. A store adds groups of its own through a predicate's definition or a correction.
	 */
	static List<SeedGroup> groups() {
		return CATALOGUE.groups();
	}

	/**
	 * Whether a predicate under a seed member's name is the seed's relation: the seed itself, or a store's own
	 * definition whose domain and range overlap the seed's, so that a same-named relation of another kind (a partner_of
	 * between people and companies) is not taken for it.
	 */
	static boolean sameRelationAsSeed(Predicate p, String seedName) {
		if (p.seed()) {
			return true;
		}
		Predicate seed = byName().get(seedName);
		return seed != null && PredicateCueMatcher.overlaps(p.domain(), seed.domain())
				&& PredicateCueMatcher.overlaps(p.range(), seed.range());
	}

	private static Catalogue load() {
		try (InputStream in = PredicateSeeds.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing seed predicate resource: " + RESOURCE);
			}
			JsonNode root = JSON.readTree(in);
			var predicates = new ArrayList<Predicate>();
			var byName = new LinkedHashMap<String, Predicate>();
			var ends = new LinkedHashMap<String, List<String>>();
			var lasting = new LinkedHashSet<String>();
			var rules = new LinkedHashMap<String, List<Map<String, Object>>>();
			var implies = new LinkedHashMap<String, Map<String, Object>>();

			JsonNode predsNode = root.get("predicates");
			if (predsNode != null && predsNode.isArray()) {
				for (JsonNode n : predsNode) {
					String name = n.get("name").asText();
					boolean containment = n.path("containment").asBoolean(false);
					boolean isLasting = n.path("lasting").asBoolean(false);
					Predicate p = new Predicate(name, textOrNull(n.get("description")), stringList(n.get("domain")),
							stringList(n.get("range")), n.path("functional").asBoolean(false),
							textOrNull(n.get("functional_scope")), n.path("symmetric").asBoolean(false), null,
							n.path("volatility").asText("medium"), stringList(n.get("lexicon")),
							n.get("render").asText(), stringList(n.get("qualifiers")), List.of(),
							stringList(n.get("inverse_lexicon")), null, true, false, containment, List.of(), isLasting,
							true);
					predicates.add(p);
					byName.put(name, p);
					if (isLasting) {
						lasting.add(name);
					}
					List<String> endsList = stringList(n.get("ends"));
					if (!endsList.isEmpty()) {
						ends.put(name, endsList);
					}
					JsonNode impliesNode = n.get("implies");
					if (impliesNode != null && impliesNode.isObject()) {
						implies.put(name, parseImpliesMap(impliesNode));
					}
					JsonNode rulesNode = n.get("rules");
					if (rulesNode != null && rulesNode.isArray()) {
						rules.put(name, parseRulesList(rulesNode));
					}
				}
			}

			var groups = new ArrayList<SeedGroup>();
			JsonNode groupsNode = root.get("groups");
			if (groupsNode != null && groupsNode.isArray()) {
				for (JsonNode n : groupsNode) {
					String name = n.get("name").asText();
					groups.add(new SeedGroup(name, textOrNull(n.get("description")), LanguagePack.groupLexicon(name),
							stringList(n.get("members"))));
				}
			}

			return new Catalogue(List.copyOf(predicates), Collections.unmodifiableMap(byName),
					Collections.unmodifiableMap(ends), Collections.unmodifiableSet(lasting),
					Collections.unmodifiableMap(rules), Collections.unmodifiableMap(implies), List.copyOf(groups));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot load seed predicate resource " + RESOURCE, e);
		}
	}

	private static Map<String, Object> parseImpliesMap(JsonNode node) {
		var out = new LinkedHashMap<String, Object>();
		for (var e : node.properties()) {
			var assigns = new LinkedHashMap<String, String>();
			for (var a : e.getValue().properties()) {
				assigns.put(a.getKey(), a.getValue().asText());
			}
			out.put(e.getKey(), Collections.unmodifiableMap(assigns));
		}
		return Collections.unmodifiableMap(out);
	}

	private static List<Map<String, Object>> parseRulesList(JsonNode array) {
		var list = new ArrayList<Map<String, Object>>();
		for (JsonNode r : array) {
			var m = new LinkedHashMap<String, Object>();
			m.put("path", stringList(r.get("path")));
			List<String> not = stringList(r.get("not"));
			if (!not.isEmpty()) {
				m.put("not", not);
			}
			String qualifier = textOrNull(r.get("qualifier"));
			if (qualifier != null) {
				m.put("qualifier", qualifier);
			}
			if (r.has("min_paths")) {
				m.put("min_paths", r.get("min_paths").asInt());
			}
			if (r.has("max_paths")) {
				m.put("max_paths", r.get("max_paths").asInt());
			}
			if (r.has("min_degree")) {
				m.put("min_degree", r.get("min_degree").asInt());
			}
			JsonNode byNode = r.get("by");
			if (byNode != null && byNode.isObject()) {
				var by = new LinkedHashMap<String, Object>();
				by.put("attribute", byNode.get("attribute").asText());
				var values = new LinkedHashMap<String, String>();
				for (var v : byNode.get("values").properties()) {
					values.put(v.getKey(), v.getValue().asText());
				}
				by.put("values", Collections.unmodifiableMap(values));
				m.put("by", Collections.unmodifiableMap(by));
			}
			list.add(Collections.unmodifiableMap(m));
		}
		return List.copyOf(list);
	}

	private static String textOrNull(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}

	private static List<String> stringList(JsonNode node) {
		if (node == null || !node.isArray() || node.isEmpty()) {
			return List.of();
		}
		var out = new ArrayList<String>(node.size());
		for (JsonNode item : node) {
			out.add(item.asText());
		}
		return List.copyOf(out);
	}
}
