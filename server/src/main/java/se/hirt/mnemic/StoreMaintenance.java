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
import se.hirt.mnemic.Engine.Rebuilt;
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Coordinates maintenance passes over the store: rebuilding the projection from the verbatim observation log, running
 * consolidation housekeeping (retirements, vector backfill, server-side backlog proposals, finding ordering), and
 * tracking the consolidation watermark.
 */
final class StoreMaintenance {

	static final String LAST_CONSOLIDATION = "last_consolidation_observation";
	private static final Pattern OBS_REF = Pattern.compile("obs-(\\d+)");
	private static final Pattern ANY_REF = Pattern.compile("(?:f|evt|ent|q)-(\\d+)");

	private final Database db;
	private final ObservationService observations;
	private final Knowledge knowledge;
	private final VectorStore vectors;
	private final SemanticIndexer indexer;
	private final ModelProposer proposer;
	private final int proposerBatch;

	StoreMaintenance(Database db, ObservationService observations, Knowledge knowledge, VectorStore vectors,
			SemanticIndexer indexer, ModelProposer proposer, int proposerBatch) {
		this.db = db;
		this.observations = observations;
		this.knowledge = knowledge;
		this.vectors = vectors;
		this.indexer = indexer;
		this.proposer = proposer;
		this.proposerBatch = proposerBatch;
	}

	/**
	 * Re-derives the projection from the log: every observation with a reading is read again in order, correction
	 * records do their work again against the fact their key now names, and the answers once given to an observation's
	 * questions are given again when the same question comes back. Entities keep their ids; facts and events get new
	 * ones. What could not be replayed (a correction whose fact no longer exists) is reported.
	 */
	Rebuilt rebuild() {
		Rebuilt rebuilt = Engine.runAtomic(db, knowledge, () -> {
			long before = knowledge.facts().count();
			long openBefore = knowledge.questions().openCount();
			List<Observation> log = observations.all().stream()
					.filter(o -> !o.retired() && o.proposalJson() != null && !"{}".equals(o.proposalJson())).toList();
			// The answers on record, kept before the questions go with the projection.
			var prior = new HashMap<Long, List<Question>>();
			for (Observation o : log) {
				prior.put(o.id(), knowledge.questions().ofObservation(o.id()));
			}
			// A clean projection first, newest entry back, so nothing derived from a later reading outlives a rebuild.
			for (Observation o : log.reversed()) {
				knowledge.factService().forgetDerived(o.id(), true);
				vectors.forget(o.id());
				knowledge.questions().deleteOf(o.id());
			}
			int n = 0;
			int corrections = 0;
			int answers = 0;
			var unmatched = new ArrayList<String>();
			var noOps = new ArrayList<String>();
			for (Observation o : log) {
				if ("correction".equals(o.source().kind())) {
					switch (knowledge.factService().replayCorrection(o)) {
					case "replayed" -> corrections++;
					case "no_op" -> noOps.add(o.ref());
					default -> unmatched.add(o.ref());
					}
					continue;
				}
				knowledge.factService().apply(o, Proposal.parse(o.proposalJson()));
				answers += replayAnswers(o, prior.getOrDefault(o.id(), List.of()));
				n++;
			}
			knowledge.deriver().derive();
			// Questions the replay asked and no earlier answer settled: a change of state the reply names, so a
			// maintenance run does not leave surprises to be counted.
			List<String> open = knowledge.questions().open(500).stream().map(Question::ref).toList();
			return new Rebuilt(n, corrections, answers, unmatched, noOps, before, knowledge.facts().count(), openBefore,
					open);
		});
		// Re-embed after the rebuilt projection has committed so inference does not hold the SQLite write lock.
		indexer.embedAllMissing(500);
		return rebuilt;
	}

	/**
	 * Housekeeping (EXTRACTION.md, Layer 3; EVALUATION.md G2, G3, J6): merges, re-closing, and the lists a caller needs
	 * to finish what Mnemic cannot do alone. {@code dryRun} reports without changing anything; {@code retire} names
	 * observations with nothing to propose (an answer, a note), taken out of the backlog. In hybrid mode the configured
	 * model reads a batch of the backlog per call. {@code rebuild} re-derives the projection from the log first (never
	 * on a dry run).
	 */
	Consolidation consolidate(boolean dryRun, List<Long> retire, boolean rebuild) {
		Rebuilt rebuilt = rebuild && !dryRun ? rebuild() : null;
		var retired = new ArrayList<String>();
		var notRetired = new ArrayList<Map<String, Object>>();
		if (!dryRun) {
			for (long id : retire) {
				if (observations.retire(id)) {
					retired.add("obs-" + id);
				} else {
					// Said, not dropped: a note that was never in the backlog came back as an empty list (2026-09-28).
					notRetired.add(Map.of("observation", "obs-" + id, "why", whyNotRetired(id)));
				}
			}
			for (Observation o : observations.backlog(200)) {
				if ("correction".equals(o.source().kind()) && observations.retire(o.id())) {
					retired.add(o.ref());
				}
			}
		}
		if (!dryRun) {
			indexer.retryIfFailed();
		}
		int embedded = dryRun ? 0 : indexer.embedMissing(500);
		var c = knowledge.consolidator().consolidate(dryRun);
		if (!dryRun) {
			knowledge.deriver().derive();
		}
		List<Map<String, Object>> proposed = dryRun ? List.of() : proposeBacklog();
		List<Map<String, Object>> backlog = observations.backlog(20).stream().map(o -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("observation", o.ref());
			m.put("source", o.source().kind());
			m.put("observed_at", o.observedAt().toString());
			m.put("excerpt", o.text().length() > 160 ? o.text().substring(0, 160) + "…" : o.text());
			return (Map<String, Object>) m;
		}).toList();
		List<Map<String, Object>> open = knowledge.questions().open(20).stream().map(Question::toMap).toList();
		// Newest first, and what arrived since the last consolidation flagged, so a capped reply and the page show
		// what the reader has not seen before what it has (2026-09-27).
		long mark = lastConsolidationMark();
		var out = new Consolidation(observations.pendingProposals(), recentFirst(backlog, mark),
				recentFirst(open, mark), recentFirst(c.inferredVocabulary(), mark),
				recentFirst(c.similarVocabulary(), mark), recentFirst(c.descriptiveEvents(), mark),
				recentFirst(c.unusedVocabulary(), mark), recentFirst(c.unresolvedDerivations(), mark),
				recentFirst(c.misfiledRelations(), mark), recentFirst(c.attributeUnknown(), mark), c.sameEntity(),
				recentFirst(c.nameCollisions(), mark), c.reclosed(), proposed, c.resolvedQuestions(),
				recentFirst(c.review(), mark), retired, embedded, recentFirst(c.duplicates(), mark), rebuilt,
				c.removedEntities(), c.repairs(), notRetired);
		if (!dryRun) {
			db.setMeta(LAST_CONSOLIDATION, String.valueOf(observations.newestId()));
		}
		return out;
	}

	/** The newest observation the previous consolidation had seen; 0 before the first. */
	long lastConsolidationMark() {
		String v = db.meta(LAST_CONSOLIDATION);
		try {
			return v == null ? 0 : Long.parseLong(v);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Findings ordered newest first, by the observation they cite (or the newest id of any kind they name), each marked
	 * {@code new: true} when its observation came after the previous consolidation.
	 */
	static List<Map<String, Object>> recentFirst(List<Map<String, Object>> entries, long mark) {
		var scored = new ArrayList<Map.Entry<Map<String, Object>, long[]>>();
		for (Map<String, Object> e : entries) {
			long obs = 0;
			long any = 0;
			for (Object v : e.values()) {
				String text = String.valueOf(v);
				var m = OBS_REF.matcher(text);
				while (m.find()) {
					obs = Math.max(obs, Long.parseLong(m.group(1)));
				}
				var a = ANY_REF.matcher(text);
				while (a.find()) {
					any = Math.max(any, Long.parseLong(a.group(1)));
				}
			}
			var copy = new LinkedHashMap<String, Object>(e);
			if (obs > mark) {
				copy.put("new", true);
			}
			scored.add(Map.entry(copy, new long[] {obs > mark ? 1 : 0, obs, any}));
		}
		scored.sort((x, y) -> {
			for (int i = 0; i < 3; i++) {
				int c = Long.compare(y.getValue()[i], x.getValue()[i]);
				if (c != 0) {
					return c;
				}
			}
			return 0;
		});
		return scored.stream().map(Map.Entry::getKey).toList();
	}

	/** Answers once given to this observation's questions, given again to the questions its re-reading raised. */
	private int replayAnswers(Observation o, List<Question> prior) {
		int n = 0;
		for (Question q : knowledge.questions().open(500)) {
			if (q.observationId() == null || q.observationId() != o.id()) {
				continue;
			}
			Optional<Question> earlier = prior.stream()
					.filter(p -> "answered".equals(p.status()) && p.kind().equals(q.kind())
							&& Objects.equals(p.subject(), q.subject()) && Objects.equals(p.predicate(), q.predicate()))
					.findFirst();
			if (earlier.isEmpty()) {
				continue;
			}
			try {
				knowledge.resolver().resolve(o, List.of(new Resolve(q.ref(), earlier.get().answer())));
				n++;
			} catch (MnemicException e) {
				// The answer no longer applies (a candidate gone): the question stays open for a person.
			}
		}
		return n;
	}

	/** Why consolidate's retire left an observation as it was, with the call that does what was likely meant. */
	private String whyNotRetired(long id) {
		Optional<Observation> o = observations.get(id);
		if (o.isEmpty()) {
			return "no such observation";
		}
		if (o.get().forgotten()) {
			return "forgotten already";
		}
		return "not waiting for a reading, so there was no backlog to take it out of; to take it out of recall, "
				+ "correct(obs-" + id + ", {\"retired\": true})";
	}

	private List<Map<String, Object>> proposeBacklog() {
		if (proposer == null) {
			return List.of();
		}
		var proposed = new ArrayList<Map<String, Object>>();
		for (Observation o : observations.backlog(proposerBatch)) {
			var m = new LinkedHashMap<String, Object>();
			m.put("observation", o.ref());
			ModelProposer.Result made = proposer.propose(o.text(), o.observedAt());
			if (made.ok()) {
				Applied a = Engine.applyServerProposal(observations, knowledge, proposer, o, made.proposal());
				m.put("proposer", proposer.id());
				m.put("facts", a.facts().stream().map(FactOut::id).toList());
				m.put("questions", a.questions().stream().map(q -> q.get("id")).toList());
				if (!a.warnings().isEmpty()) {
					m.put("warnings", a.warnings());
				}
			} else {
				m.put("warning", made.warning());
			}
			proposed.add(m);
		}
		return proposed;
	}
}
