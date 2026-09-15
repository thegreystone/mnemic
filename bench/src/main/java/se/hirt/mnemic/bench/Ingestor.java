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
package se.hirt.mnemic.bench;

import se.hirt.mnemic.Engine;
import se.hirt.mnemic.bench.LongMemEval.Question;
import se.hirt.mnemic.bench.LongMemEval.Session;
import se.hirt.mnemic.bench.LongMemEval.Turn;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.recall.RecallResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Turns a question's haystack into observations, optionally with proposals from an {@link ApiProposer} (the
 * facts-as-keys ablation), and a recall result back into session ids for retrieval metrics. Granularity is the first
 * ablation the literature asks for: sessions index best, turns read best.
 */
public final class Ingestor {

	public enum Granularity {
		SESSION, TURN
	}

	/** What one question's ingestion produced. */
	public record Ingested(int observations, int facts, int proposalsFailed) {
	}

	private Ingestor() {
	}

	public static int ingest(Engine engine, Question q, Granularity granularity) {
		try {
			return ingest(engine, q, granularity, null).observations();
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e); // unreachable without a proposer
		}
	}

	/** One observation to store: the text, its provenance, and when it was observed. */
	private record Item(String text, Source source, Instant observedAt) {
	}

	/** The observations a question produces, as {text, observation date} pairs, in haystack order. */
	public static List<String[]> prompts(Question q, Granularity granularity) {
		return items(q, granularity).stream()
				.map(it -> new String[] {it.text(), it.observedAt().toString().substring(0, 10)}).toList();
	}

	private static List<Item> items(Question q, Granularity granularity) {
		var items = new ArrayList<Item>();
		for (int i = 0; i < q.haystack().size(); i++) {
			Session s = q.haystack().get(i);
			Instant observedAt = s.date() != null ? s.date() : syntheticDate(q, i);
			switch (granularity) {
			case SESSION -> items.add(
					new Item(render(s.turns()), new Source("conversation", s.id(), null, "bench", null), observedAt));
			case TURN -> {
				for (int t = 0; t < s.turns().size(); t++) {
					Turn turn = s.turns().get(t);
					if (turn.content() == null || turn.content().isBlank()) {
						continue; // the cleaned dataset still contains a few empty turns
					}
					String kind = "assistant".equals(turn.role()) ? "assistant" : "user";
					items.add(new Item(turn.content(), new Source(kind, s.id(), t, "bench", null), observedAt));
				}
			}
			}
		}
		return items;
	}

	/** Ingests every haystack session in order; with a proposer, each observation carries its proposal. */
	public static Ingested ingest(Engine engine, Question q, Granularity granularity, ApiProposer proposer)
			throws IOException, InterruptedException {
		int failedBefore = proposer == null ? 0 : proposer.failed();
		List<Item> items = items(q, granularity);
		// Proposals are fetched in parallel (cached ones return at once); the engine stores them in order, so
		// arrival order in the store is the order of the haystack, as it would be in a real conversation.
		List<CompletableFuture<Optional<Proposal>>> proposals = proposer == null ? null
				: proposer.prefetch(items.stream().map(Item::text).toList(),
						items.stream().map(it -> it.observedAt().toString().substring(0, 10)).toList());
		int facts = 0;
		for (int i = 0; i < items.size(); i++) {
			Item it = items.get(i);
			Proposal proposal = null;
			if (proposals != null) {
				try {
					proposal = proposals.get(i).get().orElse(null);
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof UncheckedIOException uio) {
						throw uio.getCause();
					}
					throw new IllegalStateException(cause);
				}
			}
			Engine.RememberOutcome out = engine.remember(it.text(), it.source(), it.observedAt(), proposal,
					proposal == null ? null : Proposal.CURRENT_SPEC_VERSION, null);
			facts += out.applied().facts().size();
		}
		return new Ingested(items.size(), facts, proposer == null ? 0 : proposer.failed() - failedBefore);
	}

	/** Session ids in rank order, de-duplicated, from the hits' {@code source.ref}. */
	public static List<String> rankedSessions(RecallResult result) {
		var seen = new LinkedHashSet<String>();
		for (RecallResult.Hit h : result.hits()) {
			String ref = h.observation().source().ref();
			if (ref != null) {
				seen.add(ref);
			}
		}
		return new ArrayList<>(seen);
	}

	static String render(List<Turn> turns) {
		var sb = new StringBuilder();
		for (Turn t : turns) {
			sb.append(t.role()).append(": ").append(t.content()).append("\n\n");
		}
		return sb.toString().trim();
	}

	/** Haystack order as a monotone timestamp, one day apart, ending before the question date when known. */
	private static Instant syntheticDate(Question q, int index) {
		Instant end = q.date() != null ? q.date() : Instant.parse("2024-01-01T00:00:00Z");
		return end.minusSeconds(86_400L * (q.haystack().size() - index));
	}
}
