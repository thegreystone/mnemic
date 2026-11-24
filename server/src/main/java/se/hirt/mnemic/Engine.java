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

import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.Embedding;
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.Containment;
import se.hirt.mnemic.knowledge.Deriver;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.EventService;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.FactQueries.History;
import se.hirt.mnemic.knowledge.FactService;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactService.Removed;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.knowledge.QuestionService;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.Briefing;
import se.hirt.mnemic.recall.RecallService;
import se.hirt.mnemic.recall.TokenEstimator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates every service over one {@link Database} and the operations that span them. Constructed once per process
 * by CDI ({@link EngineProducer}), or directly by scenario tests and the benchmark harness without any container, so
 * the same code path is measured and tested. The clock is injectable so scenarios about elapsed time are deterministic.
 * Corrections, maintenance/rebuilds, semantic indexing, and derivation gating are delegated to package-private
 * collaborators ({@link CorrectionCoordinator}, {@link StoreMaintenance}, {@link SemanticIndexer},
 * {@link DerivationGate}).
 */
public final class Engine implements AutoCloseable {

	public static final String DB_FILE = "mnemic.db";

	/** The vector scheme: 2 since a fact about the owner carries a first-person vector too. */
	static final String VECTOR_SCHEME = SemanticIndexer.VECTOR_SCHEME;
	static final String LAST_CONSOLIDATION = StoreMaintenance.LAST_CONSOLIDATION;

	/** How an engine is opened; {@link #of(Path, String)} gives the defaults, the {@code with} methods override. */
	public record Options(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, EmbedderHolder embedder, Lang lang,
			Supplier<Embedding> vocabulary) {

		public static Options of(Path home, String version) {
			return new Options(home, version, 4000, null, Clock.systemUTC(), null, 20, List.of(),
					new EmbedderHolder(null), Lang.EN, null);
		}

		public Options withSoftLimit(int chars) {
			return new Options(home, version, chars, ownerName, clock, proposer, proposerBatch, ownerIdentity, embedder,
					lang, vocabulary);
		}

		/** {@code identity}: the owner's other names, addresses, and handles, seeded as aliases. */
		public Options withOwner(String name, List<String> identity) {
			return new Options(home, version, observationSoftLimitChars, name, clock, proposer, proposerBatch, identity,
					embedder, lang, vocabulary);
		}

		public Options withOwner(String name) {
			return withOwner(name, List.of());
		}

		public Options withClock(Clock clock) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, embedder, lang, vocabulary);
		}

		/**
		 * A server-side proposer for the hybrid mode, reading up to {@code batch} backlog observations per consolidate.
		 */
		public Options withProposer(ModelProposer proposer, int batch) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, batch,
					ownerIdentity, embedder, lang, vocabulary);
		}

		/** An embedder the caller owns and closes, so one model can serve many engines. */
		public Options withEmbedder(Embedder embedder) {
			return withEmbedder(new EmbedderHolder(embedder));
		}

		/** The embedder's lifecycle, which may deliver the embedder after the engine has started. */
		public Options withEmbedder(EmbedderHolder holder) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, holder, lang, vocabulary);
		}

		/** The model that compares vocabulary by meaning, in place of the store's embedder; for tests. */
		public Options withVocabularyEmbedding(Embedding vocabulary) {
			return withVocabularyEmbedding(() -> vocabulary);
		}

		/** As above, for a model that may arrive, change, or go away while the engine runs. */
		public Options withVocabularyEmbedding(Supplier<Embedding> vocabulary) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, embedder, lang, vocabulary);
		}

		/** The language of the fact layer; a change re-renders every fact at open. */
		public Options withLang(Lang lang) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, embedder, lang, vocabulary);
		}
	}

	/**
	 * The outcome of {@code remember}: the observation, what its proposal produced (empty without one), what answering
	 * questions did, and who proposed: {@code assistant}, {@code server:<model id>}, or {@code none}.
	 */
	public record RememberOutcome(Remembered observation, Applied applied, List<Map<String, Object>> resolved,
			String proposalSource, Deriver.Outcome derived) {
		public RememberOutcome(Remembered observation, Applied applied, List<Map<String, Object>> resolved,
				String proposalSource) {
			this(observation, applied, resolved, proposalSource, null);
		}
	}

	/** A reading given to an observation: what it produced, and what the reading it replaced had produced. */
	public record Reading(Applied applied, Removed removed, boolean replaced) {
	}

	/** What a rebuild did: the log entries re-derived, and what could not be done again. */
	public record Rebuilt(int observations, int corrections, int answers, List<String> unmatched, List<String> noOps,
			long factsBefore, long factsAfter, long openQuestionsBefore, List<String> questions) {
	}

	/** What {@code consolidate} found and did (EXTRACTION.md, Layer 3). */
	public record Consolidation(long pendingProposals, List<Map<String, Object>> backlog,
			List<Map<String, Object>> openQuestions, List<Map<String, Object>> inferredVocabulary,
			List<Map<String, Object>> similarVocabulary, List<Map<String, Object>> descriptiveEvents,
			List<Map<String, Object>> unusedVocabulary, List<Map<String, Object>> unresolvedDerivations,
			List<Map<String, Object>> misfiledRelations, List<Map<String, Object>> attributeUnknown,
			List<Map<String, Object>> merges, List<Map<String, Object>> nameCollisions, int reclosed,
			List<Map<String, Object>> proposed, List<Map<String, Object>> resolvedQuestions,
			List<Map<String, Object>> review, List<String> retired, int embedded, List<Map<String, Object>> duplicates,
			Rebuilt rebuilt, int removedEntities, List<Map<String, Object>> repairs,
			List<Map<String, Object>> notRetired) {
	}

	private final Options options;
	private final Database db;
	private final Knowledge knowledge;
	private final ObservationService observations;
	private final VectorStore vectors;
	private final RecallService recall;
	private final Briefing briefing;
	private final DerivationGate derivationGate;
	private final SemanticIndexer indexer;
	private final CorrectionCoordinator corrections;
	private final StoreMaintenance maintenance;

	public Engine(Options options) {
		this.options = options;
		this.db = new Database(options.home().resolve(DB_FILE));
		this.observations = new ObservationService(db, options.observationSoftLimitChars(), options.clock());
		this.knowledge = Knowledge.open(db, options.lang(), options.ownerName(), options.ownerIdentity(),
				options.clock(), options.vocabulary() != null ? options.vocabulary() : () -> options.embedder().get());
		this.vectors = new VectorStore(db, options.clock());
		this.recall = new RecallService(db, knowledge.entities(), knowledge.predicates(), knowledge.facts(),
				knowledge.events(), knowledge.containment(), TokenEstimator.CHARS_PER_TOKEN, options.clock(), vectors,
				options.embedder(), knowledge.entityTypes());
		this.briefing = recall.briefing(knowledge.questions());
		this.derivationGate = new DerivationGate(knowledge);
		this.indexer = new SemanticIndexer(vectors, observations, knowledge, options.embedder(), options.lang());
		this.corrections = new CorrectionCoordinator(db, observations, knowledge, options.clock(), indexer,
				derivationGate);
		this.maintenance = new StoreMaintenance(db, observations, knowledge, vectors, indexer, options.proposer(),
				options.proposerBatch());

		reconcileOnOpen();
	}

	private void reconcileOnOpen() {
		String renderedIn = db.meta("language");
		if (db.migrated() > 0 || !options.lang().code().equals(renderedIn)) {
			// A migration may change how facts read, and so does the language: every rendering follows from what is
			// stored, since the words were never the record.
			knowledge.renderer().rerenderAll();
			knowledge.events().rerenderAll();
			db.setMeta("language", options.lang().code());
		}
		// Records from before readings were kept on them get one from what they did, so a rebuild can replay them.
		knowledge.factService().backfillCorrectionReadings();
		// Events retyped from a sentence before events kept a detail get the sentence back as their detail.
		knowledge.events().backfillRetypedDetails();
		indexer.ensureScheme(db);
		// A seed that took over a predicate registered from use: its facts read the seed's way from now on.
		for (String adopted : knowledge.predicates().adoptedAtStart()) {
			knowledge.renderer().rerender(adopted);
		}
		// A store from before the rules, or one whose rules changed, gets its derived facts now (family K).
		knowledge.deriver().derive();
	}

	// ── remember and re-read ────────────────────────────────────────────

	/**
	 * Stores the observation verbatim, then validates and applies the proposal if there is one. The observation is
	 * stored even when the proposal fails validation.
	 */
	public RememberOutcome remember(
		String text, Source source, Instant observedAt, Proposal proposal, Integer specVersion, String idempotencyKey) {
		return remember(text, source, observedAt, proposal, specVersion, idempotencyKey, List.of());
	}

	/**
	 * As above, answering open questions first: a {@code reinterpret} answer rejects the pending fact and this call's
	 * proposal is what replaces it; other answers apply what the question held. An answer with no proposal of its own
	 * has nothing to extract and does not join the backlog. In hybrid mode with a synchronous proposer, the configured
	 * model reads an observation that arrived without a proposal.
	 */
	public RememberOutcome remember(
		String text, Source source, Instant observedAt, Proposal proposal, Integer specVersion, String idempotencyKey,
		List<Resolve> resolves) {
		boolean answerOnly = proposal == null && resolves != null && !resolves.isEmpty();
		String proposalJson = proposal != null ? Json.write(proposal) : answerOnly ? "{}" : null;
		Remembered r = observations.remember(text, source, observedAt, proposalJson, specVersion, idempotencyKey);
		if (r.replayed()) {
			return new RememberOutcome(r, Applied.NOTHING, List.of(), "none");
		}
		Observation obs = observations.get(r.observationId()).orElseThrow();
		List<Map<String, Object>> resolved = resolves == null || resolves.isEmpty() ? List.of()
				: knowledge.resolver().resolve(obs, resolves);
		if (proposal != null) {
			Applied applied = knowledge.factService().apply(obs, proposal);
			if (definesOnly(proposal) && applied.definitions().isEmpty() && !applied.warnings().isEmpty()) {
				// Nothing but vocabulary was offered and none of it was taken: no observation to keep.
				forget(obs.id(), true);
				throw MnemicException.invalidArgument("The proposal defines vocabulary only, and none of it was "
						+ "accepted: " + String.join(" ", applied.warnings()));
			}
			indexer.embed(obs, applied);
			Deriver.Outcome derived = derivationGate.needsDerivation(proposal, applied, resolved) ? derive()
					: Deriver.Outcome.NOTHING;
			return new RememberOutcome(r, applied, resolved, "assistant", derived);
		}
		indexer.embed(obs, Applied.NOTHING);
		ModelProposer proposer = options.proposer();
		if (answerOnly || proposer == null || proposer.mode() != ModelProposer.Mode.SYNC) {
			return new RememberOutcome(r, Applied.NOTHING, resolved, "none", resolved.isEmpty() ? null : derive());
		}
		ModelProposer.Result made = proposer.propose(obs.text(), obs.observedAt());
		if (!made.ok()) {
			return new RememberOutcome(r, Applied.NOTHING.withWarning(made.warning()), resolved, "none");
		}
		Applied applied = applyServerProposal(observations, knowledge, proposer, obs, made.proposal());
		indexer.embed(obs, applied);
		Deriver.Outcome derived = derivationGate.needsDerivation(made.proposal(), applied, resolved) ? derive()
				: Deriver.Outcome.NOTHING;
		return new RememberOutcome(r, applied, resolved, "server:" + proposer.id(), derived);
	}

	/**
	 * The reading of an observation (EVALUATION.md I5, S27): the proposal is attached to it, its facts carry that
	 * observation as their provenance, its rows are embedded, and it leaves the backlog. An observation that already
	 * has a reading gets this one instead: what the old reading produced is taken back first, closures it caused
	 * undone, and the text keeps its id, date, and provenance. The observation is the source of truth; a reading is how
	 * it was understood, and can be understood again.
	 */
	public Reading reread(long observationId, Proposal proposal) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (obs.forgotten()) {
			throw MnemicException.conflict("obs-" + observationId + " is forgotten.", Map.of());
		}
		if ("correction".equals(obs.source().kind())) {
			throw MnemicException.invalidArgument(
					obs.ref() + " is a correction record; correct the fact it produced instead of re-reading it.");
		}
		boolean replaced = obs.proposalJson() != null && !"{}".equals(obs.proposalJson());
		record RereadResult(Observation stored, Applied applied, Removed removed) {
		}
		RereadResult result = runAtomic(db, knowledge, () -> {
			Removed removed = Removed.NONE;
			if (replaced) {
				removed = knowledge.factService().forgetDerived(obs.id(), true);
				vectors.forget(obs.id());
			}
			observations.attachProposal(obs.id(), Json.write(proposal), Proposal.CURRENT_SPEC_VERSION, null);
			Observation stored = observations.get(obs.id()).orElseThrow();
			Applied applied = knowledge.factService().apply(stored, proposal);
			if (derivationGate.needsDerivation(removed)
					|| derivationGate.needsDerivation(proposal, applied, List.of())) {
				derive();
			}
			return new RereadResult(stored, applied, removed);
		});
		indexer.embed(result.stored(), result.applied());
		return new Reading(result.applied(), result.removed(), replaced);
	}

	/** {@link #reread}, for callers that only need what the reading produced. */
	public Applied propose(long observationId, Proposal proposal) {
		return reread(observationId, proposal).applied();
	}

	/** Answers open questions without recording an observation: the answers live on the questions themselves. */
	public List<Map<String, Object>> answer(List<Resolve> resolves) {
		List<Map<String, Object>> resolved = knowledge.resolver().resolve(resolves, options.clock().instant());
		indexer.embedMissing(50);
		if (!resolved.isEmpty()) {
			derive();
		}
		return resolved;
	}

	// ── observations and forgetting ─────────────────────────────────────

	/**
	 * Retires an observation that was wrong or superseded (EVALUATION.md D8); its facts are left to retract or correct.
	 */
	public Observation retireObservation(long observationId, String reason, Long supersededBy) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (obs.forgotten()) {
			throw MnemicException.conflict("obs-" + observationId + " is forgotten.", Map.of());
		}
		if (obs.retired()) {
			throw MnemicException.conflict("obs-" + observationId + " is already retired: " + obs.retiredReason(),
					Map.of());
		}
		if (supersededBy != null && observations.get(supersededBy).isEmpty()) {
			throw MnemicException.notFound("No observation obs-" + supersededBy + " to supersede it");
		}
		observations.retire(observationId, reason == null || reason.isBlank() ? "retired" : reason, supersededBy);
		return observations.get(observationId).orElseThrow();
	}

	/** Undoes a retirement: the observation is live again, back in the backlog if it never had a reading. */
	public Observation reinstateObservation(long observationId) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (!obs.retired()) {
			throw MnemicException.conflict("obs-" + observationId + " is not retired.", Map.of());
		}
		observations.reinstate(observationId);
		return observations.get(observationId).orElseThrow();
	}

	/** Hard forget of an observation and everything derived from it (EVALUATION.md D2, I2). */
	public boolean forget(long observationId) {
		return forget(observationId, false);
	}

	/** {@code keepEntities}: a re-seed keeps the entities the observation created (ids, aliases); privacy does not. */
	public boolean forget(long observationId, boolean keepEntities) {
		boolean forgotten = db.write(tx -> forgetOne(observationId, keepEntities));
		if (forgotten) {
			// Scrub deleted tokens from FTS5 shadow tables and truncate -wal immediately so forgotten plaintext does
			// not survive on disk while the server stays running.
			db.rebuildIndexes();
			db.checkpoint();
		}
		return forgotten;
	}

	private boolean forgetOne(long observationId, boolean keepEntities) {
		// A correction restates the fact it corrects: forgetting the fact for privacy forgets its corrections too.
		List<Long> records = knowledge.factService().correctionRecordsOf(observationId);
		Removed removed = knowledge.factService().forgetDerived(observationId, keepEntities);
		vectors.forget(observationId); // after: a re-homed fact keeps its vector, a deleted one loses it
		boolean forgotten = observations.forget(observationId);
		for (long record : records) {
			forgetOne(record, keepEntities);
		}
		if (!keepEntities) {
			// An entity created by an earlier forgotten observation may have lost its last reference just now.
			knowledge.entities().removeOrphansOfForgotten();
		}
		if (derivationGate.needsDerivation(removed)) {
			derive();
		}
		return forgotten;
	}

	/** Removes an entity nothing names (EVALUATION.md T20): a duplicate the resolver left, once nothing cites it. */
	public Map<String, Object> forgetEntity(long entityId) {
		return knowledge.entities().remove(entityId);
	}

	// ── corrections (delegated to CorrectionCoordinator) ────────────────

	public Map<String, Object> correctEntity(long entityId, Map<String, Object> replacement, String reason) {
		return corrections.correctEntity(entityId, replacement, reason);
	}

	public Corrected correct(long factId, Map<String, Object> replacement, String reason) {
		return corrections.correct(factId, replacement, reason);
	}

	public Map<String, Object> correctEvent(long eventId, Map<String, Object> replacement, String reason) {
		return corrections.correctEvent(eventId, replacement, reason);
	}

	public Map<String, Object> correctPredicate(String name, Map<String, Object> replacement, String reason) {
		return corrections.correctPredicate(name, replacement, reason);
	}

	public Map<String, Object> correctEventType(String name, Map<String, Object> replacement, String reason) {
		return corrections.correctEventType(name, replacement, reason);
	}

	public Map<String, Object> correctEntityType(String name, Map<String, Object> replacement, String reason) {
		return corrections.correctEntityType(name, replacement, reason);
	}

	public Map<String, Object> correctGroup(String name, Map<String, Object> replacement, String reason) {
		return corrections.correctGroup(name, replacement, reason);
	}

	// ── maintenance, rebuild, and reporting ─────────────────────────────

	public Rebuilt rebuild() {
		return maintenance.rebuild();
	}

	public Consolidation consolidate(boolean dryRun) {
		return consolidate(dryRun, List.of());
	}

	public Consolidation consolidate(boolean dryRun, List<Long> retire) {
		return consolidate(dryRun, retire, false);
	}

	/** {@code rebuild}: re-derive the projection from the log first (never on a dry run). */
	public Consolidation consolidate(boolean dryRun, List<Long> retire, boolean rebuild) {
		return maintenance.consolidate(dryRun, retire, rebuild);
	}

	/** The newest observation the previous consolidation had seen; 0 before the first. */
	public long lastConsolidationMark() {
		return maintenance.lastConsolidationMark();
	}

	static List<Map<String, Object>> recentFirst(List<Map<String, Object>> entries, long mark) {
		return StoreMaintenance.recentFirst(entries, mark);
	}

	/** Embeds what has no vector yet, up to {@code limit} rows of each kind; the number embedded. */
	public int embedMissing(int limit) {
		return indexer.embedMissing(limit);
	}

	/** The session briefing for a recall without a query (EVALUATION.md F10). */
	public String briefing(int maxTokens) {
		String text = briefing.render(maxTokens);
		// The last report, in one line: what consolidate found and where to read it.
		Optional<String> report = report();
		if (report.isPresent()) {
			String head = report.get().lines().findFirst().orElse("");
			int at = head.indexOf(" — ");
			String date = at < 0 ? "" : head.substring(at + 3).replaceAll(" \\(.*$", "");
			String count = report.get().lines().filter(l -> l.startsWith("## Things to look at ("))
					.map(l -> l.replaceAll("^.*\\((\\d+)\\).*$", "$1")).findFirst().orElse("?");
			text = text + "\nreport of " + date + ": " + count + " things to look at; inspect('report') reads it\n";
		}
		return text;
	}

	/** Writes the report page consolidate produces into the data home; its path. */
	public Path writeReport(Consolidation c, boolean dryRun) {
		Path path = home().resolve(Report.FILE);
		try {
			Files.writeString(path, Report.render(this, c, dryRun, options.clock().instant()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw MnemicException.internal("Could not write " + path + ": " + e.getMessage(), e);
		}
		return path;
	}

	/** The findings of a consolidation in words, as the report lists them. */
	public List<String> findings(Consolidation c) {
		return Report.findings(c);
	}

	/** The last report page written, if any. */
	public Optional<String> report() {
		Path path = home().resolve(Report.FILE);
		if (!Files.exists(path)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public History history(long entityId, String predicate) {
		return knowledge.facts().history(entityId, predicate);
	}

	/** Brings the derived facts in line with the current facts and rules (family K). */
	public Deriver.Outcome derive() {
		return knowledge.deriver().derive();
	}

	// ── package-private shared helpers ──────────────────────────────────

	/**
	 * Runs a multi-step mutation + derivation atomically in one transaction and restores in-memory registry caches if
	 * the transaction rolls back.
	 */
	static <T> T runAtomic(Database db, Knowledge knowledge, Supplier<T> work) {
		try {
			return db.write(tx -> work.get());
		} catch (Throwable t) {
			reloadRegistries(knowledge, t);
			throw t;
		}
	}

	private static void reloadRegistries(Knowledge knowledge, Throwable failure) {
		try {
			knowledge.predicates().reload();
			knowledge.eventTypes().reload();
			knowledge.entityTypes().reload();
			knowledge.entities().reloadOwner();
		} catch (Throwable reloadFailure) {
			failure.addSuppressed(reloadFailure);
		}
	}

	/** Stores the server-made proposal on the observation, with its provenance, then applies it. */
	static Applied applyServerProposal(
		ObservationService observations, Knowledge knowledge, ModelProposer proposer, Observation obs, Proposal p) {
		observations.attachProposal(obs.id(), Json.write(p), Proposal.CURRENT_SPEC_VERSION, proposer.id());
		return knowledge.factService().apply(observations.get(obs.id()).orElseThrow(), p);
	}

	private static boolean definesOnly(Proposal p) {
		return p.facts().isEmpty() && p.events().isEmpty() && p.entities().isEmpty() && p.closures().isEmpty()
				&& !(p.predicates().isEmpty() && p.eventTypes().isEmpty() && p.entityTypes().isEmpty());
	}

	// ── accessors ───────────────────────────────────────────────────────

	public Path home() {
		return options.home();
	}

	public String version() {
		return options.version();
	}

	public Clock clock() {
		return options.clock();
	}

	public Lang lang() {
		return options.lang();
	}

	/** The configured proposer, or null: the assistant proposes. */
	public ModelProposer proposer() {
		return options.proposer();
	}

	public Embedder embedder() {
		return options.embedder().get();
	}

	public EmbedderHolder embedderHolder() {
		return options.embedder();
	}

	public Database database() {
		return db;
	}

	public VectorStore vectors() {
		return vectors;
	}

	public ObservationService observations() {
		return observations;
	}

	public PredicateRegistry predicates() {
		return knowledge.predicates();
	}

	public EventTypeRegistry eventTypes() {
		return knowledge.eventTypes();
	}

	/** Where things lie, along the containment predicates. */
	public Containment containment() {
		return knowledge.containment();
	}

	public EntityTypeRegistry entityTypes() {
		return knowledge.entityTypes();
	}

	public EntityService entities() {
		return knowledge.entities();
	}

	public Deriver deriver() {
		return knowledge.deriver();
	}

	/** The fact layer's write side: readings, corrections, and what a proposal did. */
	public FactService factService() {
		return knowledge.factService();
	}

	public FactQueries facts() {
		return knowledge.facts();
	}

	public EventService events() {
		return knowledge.events();
	}

	public QuestionService questions() {
		return knowledge.questions();
	}

	public RecallService recall() {
		return recall;
	}

	/** A proposal from a map as the MCP layer receives it, with the keys the parser ignored named. */
	public static Proposal.Parsed proposalWithWarnings(Map<String, Object> map) {
		return map == null ? null : Proposal.fromWithWarnings(map);
	}

	@Override
	public void close() {
		db.close();
	}
}
