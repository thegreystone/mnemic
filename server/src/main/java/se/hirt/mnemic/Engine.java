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

import se.hirt.mnemic.knowledge.Lang;

import se.hirt.mnemic.embed.VectorStore;

import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;

import se.hirt.mnemic.knowledge.*;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactService.History;
import se.hirt.mnemic.knowledge.FactService.Resolve;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallService;
import se.hirt.mnemic.recall.TokenEstimator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every service over one {@link Database}. Constructed once per process by CDI ({@link EngineProducer}), or directly by
 * scenario tests and the benchmark harness without any container, so the same code path is measured and tested. The
 * clock is injectable so scenarios about elapsed time are deterministic (EVALUATION.md C7).
 */
public final class Engine implements AutoCloseable {

	private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(Engine.class);

	public static final String DB_FILE = "mnemic.db";

	/**
	 * The outcome of {@code remember}: the observation, what its proposal produced (empty without one), and what
	 * answering questions did.
	 */
	public record RememberOutcome(Remembered observation, Applied applied, List<Map<String, Object>> resolved,
	                              String proposalSource) {
		/** {@code assistant}, {@code server:<model id>}, or {@code none}. */
		public RememberOutcome(Remembered observation, Applied applied, List<Map<String, Object>> resolved) {
			this(observation, applied, resolved, "none");
		}
	}

	/** What {@code consolidate} found and did (EXTRACTION.md, Layer 3). */
	public record Consolidation(long pendingProposals, List<Map<String, Object>> backlog,
	                            List<Map<String, Object>> openQuestions,
	                            List<Map<String, Object>> suggestedRegistrations, List<Map<String, Object>> merges,
	                            int reclosed, List<Map<String, Object>> proposed,
	                            List<Map<String, Object>> resolvedQuestions, List<Map<String, Object>> review,
	                            List<String> retired, int embedded, List<Map<String, Object>> duplicates) {
	}

	private static final Applied NOTHING = new Applied(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of());

	private final Lang lang;
	private final EmbedderHolder holder;
	private final VectorStore vectors;
	private final Path home;
	private final String version;
	private final Clock clock;
	private final Database db;
	private final ObservationService observations;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EntityService entities;
	private final QuestionService questions;
	private final FactService facts;
	private final RecallService recall;
	private final ModelProposer proposer;
	private final int proposerBatch;

	public Engine(Path home, String version, int observationSoftLimitChars) {
		this(home, version, observationSoftLimitChars, null, Clock.systemUTC());
	}

	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName) {
		this(home, version, observationSoftLimitChars, ownerName, Clock.systemUTC());
	}

	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock) {
		this(home, version, observationSoftLimitChars, ownerName, clock, null, 20);
	}

	/** With a server-side proposer for the hybrid mode; {@code proposer} may be null. */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, 20);
	}

	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch, List.of());
	}

	/** {@code ownerIdentity}: the owner's configured aliases, addresses, and handles (MnemicConfig). */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch, ownerIdentity, null);
	}

	/** {@code vecLibrary}: the sqlite-vec loadable library to load at open, or null (P2). */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, String vecLibrary) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch, ownerIdentity,
				vecLibrary, null);
	}

	/**
	 * {@code embedder}: the in-process embedder (M4), or null for a store without a semantic channel. The engine
	 * uses it and does not own it: the caller closes it, so one model can serve many engines (the bench).
	 */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, String vecLibrary,
			Embedder embedder) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch, ownerIdentity,
				vecLibrary, embedder, Lang.EN);
	}

	/** {@code lang}: the language of the fact layer (MNEMIC_LANGUAGE); a change re-renders every fact at open. */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, String vecLibrary,
			Embedder embedder, Lang lang) {
		this(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch, ownerIdentity,
				vecLibrary, new EmbedderHolder(embedder), lang);
	}

	/** {@code holder}: the embedder's lifecycle, which may deliver the embedder after the engine has started. */
	public Engine(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, String vecLibrary,
			EmbedderHolder holder, Lang lang) {
		this.lang = lang;
		this.holder = holder;
		this.proposer = proposer;
		this.proposerBatch = proposerBatch;
		this.home = home;
		this.version = version;
		this.clock = clock;
		this.db = new Database(home.resolve(DB_FILE), vecLibrary);
		this.observations = new ObservationService(db, observationSoftLimitChars);
		this.predicates = new PredicateRegistry(db, lang);
		this.eventTypes = new EventTypeRegistry(db);
		this.entities = new EntityService(db, ownerName, ownerIdentity);
		this.questions = new QuestionService(db);
		this.facts = new FactService(db, entities, predicates, eventTypes, questions);
		this.facts.useClock(clock);
		this.vectors = new VectorStore(db);
		this.recall = new RecallService(db, entities, predicates, facts, questions, TokenEstimator.CHARS_PER_TOKEN,
				clock, vectors, holder);
		String renderedIn = db.meta("language");
		if (db.migrated() > 0 || !lang.code().equals(renderedIn)) {
			// A migration may change how facts read (a seed template, a suffix), and so does the language: every
			// rendering follows, from what is stored. The words were never the record.
			facts.rerenderAll();
			db.setMeta("language", lang.code());
		}
		if (!VECTOR_SCHEME.equals(db.meta("vector_scheme"))) {
			// The fact vectors are made differently now: drop them, the backfill makes them again from the renderings.
			vectors.dropKind(VectorStore.FACT);
			db.setMeta("vector_scheme", VECTOR_SCHEME);
		}
	}

	/**
	 * Stores the observation verbatim, then validates and applies the proposal if there is one. The observation is
	 * stored even when the proposal fails validation (EXTRACTION.md: the observation is still stored).
	 */
	public RememberOutcome remember(
			String text, Source source, Instant observedAt, Proposal proposal,
			Integer specVersion, String idempotencyKey) {
		return remember(text, source, observedAt, proposal, specVersion, idempotencyKey, List.of());
	}

	/**
	 * As above, answering open questions first (DECISIONS.md §2.7): a {@code reinterpret} answer rejects the pending
	 * fact and this call's proposal is what replaces it; other answers apply what the question held.
	 */
	public RememberOutcome remember(
			String text, Source source, Instant observedAt, Proposal proposal,
			Integer specVersion, String idempotencyKey, List<Resolve> resolves) {
		// An answer to a question with no proposal of its own has nothing to extract: it does not join the backlog
		// of observations waiting for a proposal (2026-09-10, nine answers and corrections sat there for good).
		boolean answerOnly = proposal == null && resolves != null && !resolves.isEmpty();
		String proposalJson = proposal != null ? Json.write(proposal) : answerOnly ? "{}" : null;
		Remembered r = observations.remember(text, source, observedAt, proposalJson, specVersion, idempotencyKey);
		if (r.replayed()) {
			return new RememberOutcome(r, NOTHING, List.of());
		}
		Observation obs = observations.get(r.observationId()).orElseThrow();
		List<Map<String, Object>> resolved =
				resolves == null || resolves.isEmpty() ? List.of() : facts.resolve(obs, resolves);
		if (proposal != null) {
			Applied applied = facts.apply(obs, proposal);
			embed(obs, applied);
			return new RememberOutcome(r, applied, resolved, "assistant");
		}
		embed(obs, NOTHING);
		if (answerOnly || proposer == null || proposer.mode() != ModelProposer.Mode.SYNC) {
			return new RememberOutcome(r, NOTHING, resolved, "none");
		}
		// Hybrid mode, sync: the configured model reads the observation in the assistant's place.
		ModelProposer.Result made = proposer.propose(obs.text(), obs.observedAt());
		if (!made.ok()) {
			return new RememberOutcome(r, withWarning(NOTHING, made.warning()), resolved, "none");
		}
		Applied applied = applyServerProposal(obs, made.proposal());
		embed(obs, applied);
		return new RememberOutcome(r, applied, resolved, "server:" + proposer.id());
	}

	/**
	 * The semantic channel's keys (M4): the observation's text and each new fact's rendering, embedded as they are
	 * stored. A failure here never fails the remember; consolidate backfills what is missing.
	 */
	private void embed(Observation obs, Applied applied) {
		Embedder embedder = holder.get();
		if (embedder == null) {
			return;
		}
		try {
			if (!vectors.has(VectorStore.OBSERVATION, obs.id(), embedder.id())) {
				vectors.putChunks(VectorStore.OBSERVATION, obs.id(), embedder.id(), embedder.embedChunks(obs.text()));
			}
			for (FactService.FactOut f : applied.facts()) {
				long id = Long.parseLong(f.id().substring(2));
				if (!vectors.has(VectorStore.FACT, id, embedder.id())) {
					vectors.putChunks(VectorStore.FACT, id, embedder.id(), factVectors(embedder, f.rendering()));
				}
			}
		} catch (RuntimeException e) {
			LOG.warnf("Embedding skipped for %s: %s", obs.ref(), e.getMessage());
		}
	}

	/** The vector scheme: 2 since the owner alias (a fact about the owner carries a first-person vector too). */
	static final String VECTOR_SCHEME = "2";
	/** Bench knob: {@code -Dmnemic.owner-alias=false} embeds owner facts without the first-person vector. */
	private static final boolean OWNER_ALIAS = !"false".equalsIgnoreCase(System.getProperty("mnemic.owner-alias", "true"));

	/**
	 * A fact's vectors: its rendering, and for a fact that names the owner the rendering as the owner would say
	 * it (OwnerAlias), so a first-person question lands on it; the item scores by its better vector.
	 */
	private List<float[]> factVectors(Embedder embedder, String rendering) {
		var out = new ArrayList<float[]>();
		out.add(embedder.embed(rendering));
		String mine = OWNER_ALIAS ? se.hirt.mnemic.recall.OwnerAlias.firstPerson(rendering, entities.owner().name(), lang) : null;
		if (mine != null) {
			out.add(embedder.embed(mine));
		}
		return out;
	}

	/** Embeds what has no vector yet, up to {@code limit} rows of each kind; the number embedded. */
	public int embedMissing(int limit) {
		Embedder embedder = holder.get();
		if (embedder == null) {
			return 0;
		}
		int n = 0;
		for (long id : vectors.missingObservations(embedder.id(), limit)) {
			observations.get(id).ifPresent(o -> vectors.putChunks(VectorStore.OBSERVATION, id, embedder.id(), embedder.embedChunks(o.text())));
			n++;
		}
		for (long id : vectors.missingFacts(embedder.id(), limit)) {
			facts.get(id).ifPresent(f -> vectors.putChunks(VectorStore.FACT, id, embedder.id(), factVectors(embedder, f.rendering())));
			n++;
		}
		return n;
	}

	public Embedder embedder() {
		return holder.get();
	}

	public EmbedderHolder embedderHolder() {
		return holder;
	}

	public Lang lang() {
		return lang;
	}

	public VectorStore vectors() {
		return vectors;
	}

	/** Stores the server-made proposal on the observation (with its provenance), then applies it. */
	/**
	 * A structured reading for an observation already stored without one (2026-09-12, I5): a connector's email, a
	 * note remembered in a hurry. The proposal is attached to that observation, its facts carry that observation
	 * as their provenance, its rows are embedded, and it leaves the backlog. An observation that already has a
	 * reading is refused: its facts are corrected, not proposed again.
	 */
	public Applied propose(long observationId, Proposal proposal) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (obs.forgotten()) {
			throw MnemicException.conflict("obs-" + observationId + " is forgotten.", Map.of());
		}
		if (obs.proposalJson() != null && !"{}".equals(obs.proposalJson())) {
			throw MnemicException.conflict("obs-" + observationId + " already has a structured reading; correct its facts "
					+ "instead of proposing again.", Map.of("observation", obs.ref()));
		}
		observations.attachProposal(obs.id(), Json.write(proposal), Proposal.CURRENT_SPEC_VERSION, null);
		Observation stored = observations.get(obs.id()).orElseThrow();
		Applied applied = facts.apply(stored, proposal);
		embed(stored, applied);
		return applied;
	}

	/** Retires an observation that was wrong or superseded (D8); the facts it produced are left to retract or correct. */
	public Observation retireObservation(long observationId, String reason, Long supersededBy) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (obs.forgotten()) {
			throw MnemicException.conflict("obs-" + observationId + " is forgotten.", Map.of());
		}
		if (obs.retired()) {
			throw MnemicException.conflict("obs-" + observationId + " is already retired: " + obs.retiredReason(), Map.of());
		}
		if (supersededBy != null && observations.get(supersededBy).isEmpty()) {
			throw MnemicException.notFound("No observation obs-" + supersededBy + " to supersede it");
		}
		observations.retire(observationId, reason == null || reason.isBlank() ? "retired" : reason, supersededBy);
		return observations.get(observationId).orElseThrow();
	}

	/** Undoes a retirement (D8): the observation is live again, back in the backlog if it never had a reading. */
	public Observation reinstateObservation(long observationId) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (!obs.retired()) {
			throw MnemicException.conflict("obs-" + observationId + " is not retired.", Map.of());
		}
		observations.reinstate(observationId);
		return observations.get(observationId).orElseThrow();
	}


	private Applied applyServerProposal(Observation obs, Proposal p) {
		observations.attachProposal(obs.id(), Json.write(p), Proposal.CURRENT_SPEC_VERSION, proposer.id());
		Observation stored = observations.get(obs.id()).orElseThrow();
		return facts.apply(stored, p);
	}

	private static Applied withWarning(Applied a, String warning) {
		var warnings = new java.util.ArrayList<>(a.warnings());
		warnings.add(warning);
		return new Applied(a.entities(), a.events(), a.facts(), a.predicates(), a.questions(), warnings,
				a.superseded());
	}

	/** The configured proposer, or null: the assistant proposes. */
	public ModelProposer proposer() {
		return proposer;
	}

	/** The session briefing for a recall without a query (EVALUATION.md F10). */
	public String briefing(int maxTokens) {
		return recall.briefing(maxTokens);
	}

	/**
	 * Housekeeping (EXTRACTION.md, Layer 3; EVALUATION.md G2, G3, J6): merges, re-closing, and the lists a caller needs
	 * to finish what Mnemic cannot do alone. {@code dryRun} reports without changing anything.
	 */
	public Consolidation consolidate(boolean dryRun) {
		return consolidate(dryRun, List.of());
	}

	/**
	 * {@code retire}: observations the caller says have nothing to propose (an answer, a note), taken out of the
	 * backlog. Correction records that predate 2026-09-10 are retired on their own.
	 */
	public Consolidation consolidate(boolean dryRun, List<Long> retire) {
		var retired = new java.util.ArrayList<String>();
		if (!dryRun) {
			for (long id : retire) {
				if (observations.retire(id)) {
					retired.add("obs-" + id);
				}
			}
			for (Observation o : observations.backlog(200)) {
				if ("correction".equals(o.source().kind()) && observations.retire(o.id())) {
					retired.add(o.ref());
				}
			}
		}
		int embedded = dryRun ? 0 : embedMissing(500);
		FactService.Consolidated c = facts.consolidate(dryRun);
		// Hybrid mode: the backlog is what the configured model reads, up to a batch per call, in either mode
		// (deferred by design, sync when an earlier attempt failed).
		var proposed = new java.util.ArrayList<Map<String, Object>>();
		if (proposer != null && !dryRun) {
			for (Observation o : observations.backlog(proposerBatch)) {
				var m = new java.util.LinkedHashMap<String, Object>();
				m.put("observation", o.ref());
				ModelProposer.Result made = proposer.propose(o.text(), o.observedAt());
				if (made.ok()) {
					Applied a = applyServerProposal(o, made.proposal());
					m.put("proposer", proposer.id());
					m.put("facts", a.facts().stream().map(FactService.FactOut::id).toList());
					m.put("questions", a.questions().stream().map(q -> q.get("id")).toList());
					if (!a.warnings().isEmpty()) {
						m.put("warnings", a.warnings());
					}
				} else {
					m.put("warning", made.warning());
				}
				proposed.add(m);
			}
		}
		List<Map<String, Object>> backlog = observations.backlog(20).stream().map(o -> {
			var m = new java.util.LinkedHashMap<String, Object>();
			m.put("observation", o.ref());
			m.put("source", o.source().kind());
			m.put("observed_at", o.observedAt().toString());
			m.put("excerpt", o.text().length() > 160 ? o.text().substring(0, 160) + "…" : o.text());
			return (Map<String, Object>) m;
		}).toList();
		List<Map<String, Object>> open = questions.open(20).stream().map(q -> q.toMap()).toList();
		return new Consolidation(observations.pendingProposals(), backlog, open, c.suggestedRegistrations(), c.merges(),
				c.reclosed(), proposed, c.resolvedQuestions(), c.review(), retired, embedded, c.duplicates());
	}

	/**
	 * Corrects a predicate's definition (EVALUATION.md J5): the change is logged with its reason and every fact under
	 * the predicate is re-rendered.
	 */
	public Map<String, Object> correctPredicate(String name, Map<String, Object> replacement, String reason) {
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument(
					"'replacement' must name what changes, e.g. {\"render\": " + "\"{object} is {subject}'s {qualifier|parent}\"} or {\"lexicon\": [\"mother\", \"father\"]}.");
		}
		var before = predicates.get(name).orElseThrow(() -> MnemicException.notFound("No predicate " + name));
		var after = predicates.update(name, replacement, reason);
		int rerendered = facts.rerender(name);
		var out = new java.util.LinkedHashMap<String, Object>();
		out.put("predicate", name);
		out.put("before",
				Map.of("render", before.render(), "lexicon", before.lexicon(), "qualifiers", before.qualifiers(),
						"functional", before.functional()));
		out.put("after", Map.of("render", after.render(), "lexicon", after.lexicon(), "qualifiers", after.qualifiers(),
				"functional", after.functional()));
		out.put("rerendered_facts", rerendered);
		out.put("changes", predicates.changes(name));
		return out;
	}

	/**
	 * Corrects a fact without destroying history (EVALUATION.md D1): the reason becomes a correction observation, the
	 * replacement is derived from it, the original is marked {@code corrected} and linked.
	 */
	public Corrected correct(long factId, Map<String, Object> replacement, String reason) {
		if (replacement != null && Boolean.TRUE.equals(replacement.get("wrong"))) {
			// Never true: no replacement, the fact leaves recall, the reason stays in history (D7).
			String text = "Retraction of " + facts.get(factId).map(f -> f.rendering()).orElse("f-" + factId)
					+ (reason == null || reason.isBlank() ? "" : ": " + reason);
			Remembered r = observations.remember(text, new Source("correction", "f-" + factId, null, null, null),
					clock.instant(), "{}", null, null);
			Corrected retracted = facts.retract(factId, reason, observations.get(r.observationId()).orElseThrow());
			embedMissing(50);
			return retracted;
		}
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument(
					"'replacement' must name what changes, e.g. {\"object\": " + "\"Schübelbach\"} or {\"valid_time\": {\"start\": \"2014\"}} or {\"ended\": true}.");
		}
		String text = "Correction of " + facts.get(factId).map(f -> f.rendering()).orElse("f-" + factId) + (
				reason == null || reason.isBlank() ? "" : ": " + reason) + " → " + Json.write(replacement);
		// A correction record carries its change in the correction itself: nothing to propose from its text.
		Remembered r = observations.remember(text, new Source("correction", "f-" + factId, null, null, null),
				clock.instant(), "{}", null, null);
		Observation obs = observations.get(r.observationId()).orElseThrow();
		Corrected corrected = facts.correct(factId, replacement, reason, obs);
		// The correction record and the replacement fact get their vectors now, like a remember does
		// (2026-09-11: they waited for the next consolidate).
		embedMissing(50);
		return corrected;
	}

	public History history(long entityId, String predicate) {
		return facts.history(entityId, predicate);
	}

	/** Hard forget of an observation and everything derived from it (EVALUATION.md D2, I2). */
	public boolean forget(long observationId) {
		return forget(observationId, false);
	}

	/** {@code keepEntities}: a re-seed keeps the entities the observation created (ids, aliases); privacy does not. */
	public boolean forget(long observationId, boolean keepEntities) {
		facts.forgetDerived(observationId, keepEntities);
		vectors.forget(observationId); // after: a re-homed fact keeps its vector, a deleted one loses it
		return observations.forget(observationId);
	}

	public Path home() {
		return home;
	}

	public String version() {
		return version;
	}

	public Clock clock() {
		return clock;
	}

	public Database database() {
		return db;
	}

	public ObservationService observations() {
		return observations;
	}

	public PredicateRegistry predicates() {
		return predicates;
	}

	public EventTypeRegistry eventTypes() {
		return eventTypes;
	}

	public EntityService entities() {
		return entities;
	}

	public FactService facts() {
		return facts;
	}

	public QuestionService questions() {
		return questions;
	}

	public RecallService recall() {
		return recall;
	}

	/** Convenience for tests and tools: a proposal from a map as the MCP layer receives it. */
	public static Proposal proposal(Map<String, Object> map) {
		return map == null ? null : Proposal.from(map);
	}

	/** As {@link #proposal(Map)}, with the keys the parser ignored named (never silently). */
	public static Proposal.Parsed proposalWithWarnings(Map<String, Object> map) {
		return map == null ? null : Proposal.fromWithWarnings(map);
	}

	@Override
	public void close() {
		db.close();
	}
}
