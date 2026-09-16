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
package se.hirt.mnemic.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.recall.RecallResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Opens a copy of a real store (the directory named by {@code MNEMIC_STORE_COPY}, which the engine migrates in place,
 * so never a live one) and prints what the upgrade made of it: schema, vocabulary registered from use, pairs close in
 * meaning when the embedder is set, renderings, open questions, and a few recalls. Asserts nothing about the data; the
 * report is for a person to read. Skipped unless the variable is set.
 */
class StoreCopyReportTest {

	@Test
	void reportOnAStoreCopy() throws Exception {
		String copy = System.getenv("MNEMIC_STORE_COPY");
		Assumptions.assumeTrue(copy != null && Files.exists(Path.of(copy, "mnemic.db")), "MNEMIC_STORE_COPY not set");
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		Embedder emb = lib != null && model != null
				? new Embedder(Path.of(lib), Path.of(model), Path.of(model).getFileName().toString()) : null;
		Engine.Options options = Engine.Options.of(Path.of(copy), "report");
		String owner = System.getenv("MNEMIC_OWNER"); // as the server is configured, or the owner is "the user"
		if (owner != null && !owner.isBlank()) {
			options = options.withOwner(owner, List.of());
		}
		if (emb != null) {
			options = options.withEmbedder(emb);
		}
		try (Engine e = new Engine(options)) {
			System.out.println("== schema " + e.database().schemaVersion() + ", migrated " + e.database().migrated()
					+ ", owner " + e.entities().owner().name() + ", facts " + e.facts().count() + ", questions open "
					+ e.questions().openCount());
			System.out.println("== predicates (non-seed)");
			e.predicates().all().stream().filter(p -> !p.seed()).forEach(p -> System.out.println("  " + p.name()
					+ " inferred=" + p.isInferred() + " lexicon=" + p.lexicon() + " render=" + p.render()));
			System.out.println("== event types (non-seed)");
			e.eventTypes().all().stream().filter(t -> !t.seed()).forEach(t -> System.out.println("  " + t.name()
					+ " inferred=" + t.inferred() + " lexicon=" + t.lexicon() + " render=" + t.render()));
			System.out.println("== entity types (non-seed)");
			e.entityTypes().all().stream().filter(t -> !t.seed()).forEach(
					t -> System.out.println("  " + t.name() + " inferred=" + t.inferred() + " parent=" + t.parent()));
			System.out.println("== events as rendered after the upgrade");
			for (Event ev : e.events().eventsOf(e.entities().owner().id())) {
				System.out.println("  " + ev.ref() + " " + ev.type() + " :: " + ev.rendering());
			}
			var c = e.consolidate(true);
			System.out.println("== inferred_vocabulary " + c.inferredVocabulary().size());
			for (Map<String, Object> m : c.inferredVocabulary()) {
				System.out.println("  " + m);
			}
			System.out.println("== similar_vocabulary " + c.similarVocabulary());
			System.out.println("== review " + c.review().size() + ", duplicates " + c.duplicates().size() + ", merges "
					+ c.merges().size());
			for (Question q : e.questions().open(20)) {
				System.out.println("  open " + q.ref() + " " + q.kind() + " " + q.subject());
			}
			for (String query : List.of("where do I work", "where do I live", "what did I decide", "what do I own",
					"who are my children", "what car did I order", "when did I get my driving licence",
					"what did I build", "wo arbeite ich")) {
				RecallResult r = e.recall().recall(query, null, 600, 5);
				System.out.println("== recall: " + query + " -> " + r.structured().state()
						+ (r.structured().predicate() != null ? " " + r.structured().predicate() : "") + ", hits "
						+ r.hits().size());
				r.structured().facts().stream().limit(3).forEach(f -> System.out.println("     " + f.rendering()));
				r.events().stream().limit(3).forEach(ev -> System.out.println("     event " + ev.rendering()));
				r.hits().stream().limit(3).forEach(h -> System.out.println("     hit " + h.channels() + " obs-"
						+ h.observation().id() + " " + excerpt(h.observation().text())));
			}
			System.out.println("== facts of the owner, first 40");
			e.facts().factsOf(e.entities().owner().id()).stream().limit(40)
					.forEach(f -> System.out.println("  " + f.ref() + " " + f.status() + " " + f.rendering()));
			System.out.println("== briefing");
			System.out.println(e.briefing(600));
			if ("true".equals(System.getenv("MNEMIC_STORE_REBUILD"))) {
				// The projection rebuilt from the log, compared with itself before: the same current knowledge?
				List<String> before = e.facts().factsOf(e.entities().owner().id()).stream().filter(Fact::current)
						.map(Fact::rendering).sorted().toList();
				long questionsBefore = e.questions().openCount();
				var rebuilt = e.rebuild();
				System.out.println("== rebuilt " + rebuilt);
				List<String> after = e.facts().factsOf(e.entities().owner().id()).stream().filter(Fact::current)
						.map(Fact::rendering).sorted().toList();
				System.out.println("== owner facts before " + before.size() + ", after " + after.size()
						+ ", open questions before " + questionsBefore + ", after " + e.questions().openCount());
				before.stream().filter(x -> !after.contains(x)).forEach(x -> System.out.println("  lost: " + x));
				after.stream().filter(x -> !before.contains(x)).forEach(x -> System.out.println("  new:  " + x));
				e.questions().open(50).forEach(q -> System.out.println("  asks: " + q.kind() + " " + q.subject()));
			}
		} finally {
			if (emb != null) {
				emb.close();
			}
		}
	}

	private static String excerpt(String text) {
		return text.length() > 90 ? text.substring(0, 90).replace('\n', ' ') + "…" : text.replace('\n', ' ');
	}

}
