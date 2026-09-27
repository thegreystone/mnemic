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
package se.hirt.mnemic;

import se.hirt.mnemic.Engine.Consolidation;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.observation.Observation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page consolidate writes into the data home, {@code report.md}: the store as a person would read it, and the
 * things consolidate found that someone should look at, each with the call that fixes it. A report with links into the
 * database, never a dump of it: every section is capped, the derived facts are counted rather than listed, and the page
 * stops at {@value #MAX_CHARS} characters, which a model reads in one {@code inspect('report')} (2026-09-27).
 */
final class Report {

	static final String FILE = "report.md";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
	static final int MAX_CHARS = 24_000;
	static final int STATED_FACTS = 40;
	static final int ENTITIES = 20;
	static final int FACTS_PER_ENTITY = 8;
	static final int PER_LIST = 25;
	static final int CHANGES = 10;
	static final int EXCERPT = 160;

	private Report() {
	}

	/** The findings in words, each with what to do about it; what the consolidate reply carries beside the path. */
	static List<String> findings(Consolidation c) {
		var out = new ArrayList<String>();
		for (String line : lines(c)) {
			out.add(line);
		}
		return out;
	}

	/** "new: " marks a finding whose observation came after the previous consolidation. */
	private static String mark(Map<String, Object> m, String line) {
		return Boolean.TRUE.equals(m.get("new")) ? "new: " + line : line;
	}

	private static List<String> lines(Consolidation c) {
		var out = new ArrayList<String>();
		for (Map<String, Object> m : c.backlog()) {
			out.add(mark(m, m.get("observation") + " (" + m.get("source") + ", " + str(m.get("observed_at"), 10)
					+ ") has no reading: \"" + m.get("excerpt") + "\" — give it facts with remember(observation_id: "
					+ m.get("observation") + ", proposal), or set it aside with correct(" + m.get("observation")
					+ ", {retired: true})"));
		}
		for (Map<String, Object> m : c.openQuestions()) {
			out.add(mark(m, m.get("id") + " [" + m.get("kind") + "] " + m.get("message")
					+ " — answer with remember(resolve: [{question_id: " + m.get("id") + ", choice}])"));
		}
		for (Map<String, Object> m : c.inferredVocabulary()) {
			String kind = m.containsKey("predicate") ? "predicate" : m.containsKey("event_type") ? "event type"
					: m.containsKey("entity_type") ? "entity type" : "vocabulary";
			Object name = m.getOrDefault("predicate", m.getOrDefault("event_type", m.get("entity_type")));
			String ref = m.containsKey("predicate") ? "pred:" + name
					: m.containsKey("event_type") ? "event:" + name : "type:" + name;
			out.add(mark(m,
					"the " + kind + " '" + name + "' was registered from use (" + m.getOrDefault("uses", 0)
							+ " uses) and nothing has been said about it — describe it with correct('" + ref
							+ "', {description, ...}), or fold it with {merge_into}"));
		}
		for (Map<String, Object> m : c.similarVocabulary()) {
			out.add(mark(m,
					"'" + m.get("predicate") + "' lies close in meaning to '" + m.get("close_to") + "' ("
							+ m.get("score") + ") — if they are one relation, correct('pred:" + m.get("predicate")
							+ "', {merge_into: '" + m.get("close_to") + "'})"));
		}
		for (Map<String, Object> m : c.descriptiveEvents()) {
			out.add(mark(m, "event " + m.get("event") + " (" + m.get("observation") + ") has a sentence as its type: '"
					+ m.get("type") + "' — reread the observation with remember(observation_id, proposal) giving the "
					+ "event a type of a word or two"));
		}
		for (Map<String, Object> m : c.unusedVocabulary()) {
			String kind = m.containsKey("predicate") ? "predicate"
					: m.containsKey("event_type") ? "event type" : "entity type";
			Object name = m.getOrDefault("predicate", m.getOrDefault("event_type", m.get("entity_type")));
			String ref = m.containsKey("predicate") ? "pred:" + name
					: m.containsKey("event_type") ? "event:" + name : "type:" + name;
			out.add(mark(m, "the " + kind + " '" + name + "' is used by nothing and the observation that defined it "
					+ "is gone — harmless; it stays available, inspect('" + ref + "') shows it"));
		}
		for (Map<String, Object> m : c.unresolvedDerivations()) {
			out.add(mark(m,
					m.get("fact") + " \"" + m.get("rendering") + "\" was stated but the relations it follows from are "
							+ "not all on record (chain " + m.get("chain")
							+ ") — state the missing ones, or leave it as said"));
		}
		for (Map<String, Object> m : c.misfiledRelations()) {
			out.add(mark(m, "possibly misfiled: " + entry(m)));
		}
		for (Map<String, Object> m : c.attributeUnknown()) {
			out.add(mark(m,
					m.get("name") + "'s " + m.get("attribute") + " is not on record and " + m.get("neutral_relations")
							+ (Integer.valueOf(1).equals(m.get("neutral_relations")) ? " relation reads"
									: " relations read")
							+ " without it — state it with a " + m.get("attribute") + " fact"));
		}
		for (Map<String, Object> m : c.nameCollisions()) {
			out.add(mark(m, "name collision: " + entry(m)));
		}
		for (Map<String, Object> m : c.duplicates()) {
			out.add(mark(m, "possible duplicate: " + entry(m) + " — fold with correct(ent-N, {merge_into: ent-M})"));
		}
		for (Map<String, Object> m : c.review()) {
			out.add(mark(m, "to review: " + entry(m)));
		}
		return out;
	}

	/** The page. */
	static String render(Engine e, Consolidation c, boolean dryRun, Instant now) {
		Entity owner = e.entities().owner();
		var sb = new StringBuilder();
		sb.append("# Mnemic report for ").append(owner.name()).append(" — ").append(DAY.format(now))
				.append(dryRun ? " (dry run)" : " (consolidated)").append('\n');
		sb.append("\nWritten by consolidate; the ids are what inspect, correct, and forget take. ");
		sb.append("Sections are capped: this is a report on the store, not the store.\n");

		List<String> findings = findings(c);
		sb.append("\n## Things to look at (").append(findings.size()).append(")\n\n");
		if (findings.isEmpty()) {
			sb.append("Nothing.\n");
		}
		list(sb, findings, PER_LIST);

		sb.append("\n## About ").append(owner.name()).append("\n\n");
		List<Fact> mine = e.facts().factsOf(owner.id()).stream().filter(f -> "current".equals(f.state(now)))
				.filter(Fact::asserted).toList();
		List<Fact> stated = mine.stream().filter(f -> !"derived".equals(f.derivationKind())).toList();
		var derived = new LinkedHashMap<String, Integer>();
		for (Fact f : mine) {
			if ("derived".equals(f.derivationKind())) {
				derived.merge(f.predicate(), 1, Integer::sum);
			}
		}
		List<Fact> shown = e.facts().briefingFacts(owner.id(), now, STATED_FACTS).stream()
				.filter(f -> !"derived".equals(f.derivationKind())).toList();
		for (Fact f : shown) {
			sb.append("- ").append(f.rendering()).append(" [").append(f.ref()).append(", obs-")
					.append(f.observationId()).append("]\n");
		}
		if (stated.size() > shown.size()) {
			sb.append("- and ").append(stated.size() - shown.size()).append(" more stated facts: recall by topic\n");
		}
		if (!derived.isEmpty()) {
			var parts = new ArrayList<String>();
			derived.entrySet().stream().sorted(Map.Entry.<String, Integer> comparingByValue().reversed())
					.forEach(d -> parts.add(d.getValue() + " " + d.getKey()));
			sb.append("- derived, not stated: ").append(String.join(", ", parts)).append('\n');
		}

		sb.append("\n## People and things\n\n");
		int entities = 0;
		for (Entity x : e.entities().active(ENTITIES + 1)) {
			if (x.id() == owner.id()) {
				continue;
			}
			if (entities++ >= ENTITIES) {
				break;
			}
			List<Fact> facts = e.facts().factsOf(x.id()).stream().filter(f -> "current".equals(f.state(now)))
					.filter(Fact::asserted).sorted(Comparator
							.comparing((Fact f) -> "derived".equals(f.derivationKind())).thenComparing(Fact::id))
					.toList();
			sb.append("### ").append(x.name()).append(" (").append(x.type()).append(", ").append(x.ref()).append(")\n");
			int n = 0;
			for (Fact f : facts) {
				if (n++ >= FACTS_PER_ENTITY) {
					sb.append("- and ").append(facts.size() - FACTS_PER_ENTITY).append(" more: inspect('")
							.append(x.ref()).append("')\n");
					break;
				}
				sb.append("- ").append(f.rendering()).append(" [").append(f.ref())
						.append("derived".equals(f.derivationKind()) ? ", derived" : ", obs-" + f.observationId())
						.append("]\n");
			}
			if (facts.isEmpty()) {
				sb.append("- no current facts\n");
			}
		}
		if (entities == 0) {
			sb.append("Nothing yet.\n");
		}

		List<Question> open = e.questions().open(PER_LIST + 1);
		sb.append("\n## Open questions (").append(e.questions().openCount()).append(")\n\n");
		if (open.isEmpty()) {
			sb.append("None.\n");
		}
		var questions = new ArrayList<String>();
		for (Question q : open) {
			questions.add(q.ref() + " [" + q.kind() + "]: " + q.message() + " — remember(resolve: [{question_id: "
					+ q.ref() + ", choice}])");
		}
		list(sb, questions, PER_LIST);

		sb.append("\n## Pending readings (").append(c.pendingProposals()).append(")\n\n");
		if (c.backlog().isEmpty()) {
			sb.append("None.\n");
		}
		var pending = new ArrayList<String>();
		for (Map<String, Object> m : c.backlog()) {
			pending.add(m.get("observation") + " (" + m.get("source") + ", " + str(m.get("observed_at"), 10) + "): \""
					+ m.get("excerpt") + "\"");
		}
		list(sb, pending, PER_LIST);

		sb.append("\n## Recent changes\n\n");
		List<Observation> changes = e.observations().all().stream()
				.filter(o -> "correction".equals(o.source().kind()) && !o.forgotten())
				.sorted(Comparator.comparing(Observation::observedAt).reversed()).limit(CHANGES).toList();
		if (changes.isEmpty()) {
			sb.append("None.\n");
		}
		for (Observation o : changes) {
			String text = o.text().length() > EXCERPT ? o.text().substring(0, EXCERPT) + "…" : o.text();
			sb.append("- ").append(DAY.format(o.observedAt())).append(" (").append(o.ref()).append("): ")
					.append(text.replace('\n', ' ')).append('\n');
		}
		if (sb.length() > MAX_CHARS) {
			sb.setLength(MAX_CHARS);
			sb.append("\n\n(cut at ").append(MAX_CHARS).append(" characters; the ids above reach the rest)\n");
		}
		return sb.toString();
	}

	private static void list(StringBuilder sb, List<String> items, int cap) {
		int n = 0;
		for (String item : items) {
			if (n++ >= cap) {
				sb.append("- and ").append(items.size() - cap).append(" more\n");
				break;
			}
			sb.append("- ").append(item).append('\n');
		}
	}

	/** A finding whose shape is not known here, as "key: value" pairs. */
	private static String entry(Map<String, Object> m) {
		var parts = new ArrayList<String>();
		m.forEach((k, v) -> parts.add(k + " " + v));
		return String.join(", ", parts);
	}

	private static String str(Object o, int max) {
		String s = o == null ? "" : o.toString();
		return s.length() > max ? s.substring(0, max) : s;
	}
}
