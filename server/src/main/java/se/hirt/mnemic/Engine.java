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
import se.hirt.mnemic.knowledge.Rule;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.EventService;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.FactQueries.History;
import se.hirt.mnemic.knowledge.FactService;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.knowledge.FactService.Removed;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.Predicate;
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
import se.hirt.mnemic.recall.OwnerAlias;
import se.hirt.mnemic.recall.RecallService;
import se.hirt.mnemic.recall.TokenEstimator;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Every service over one {@link Database}, and the operations that span them. Constructed once per process by CDI
 * ({@link EngineProducer}), or directly by scenario tests and the benchmark harness without any container, so the same
 * code path is measured and tested. The clock is injectable so scenarios about elapsed time are deterministic.
 */
public final class Engine implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(Engine.class);

	public static final String DB_FILE = "mnemic.db";

	/** How an engine is opened; {@link #of(Path, String)} gives the defaults, the {@code with} methods override. */
	public record Options(Path home, String version, int observationSoftLimitChars, String ownerName, Clock clock,
			ModelProposer proposer, int proposerBatch, List<String> ownerIdentity, String vecLibrary,
			EmbedderHolder embedder, Lang lang, Supplier<Embedding> vocabulary) {

		public static Options of(Path home, String version) {
			return new Options(home, version, 4000, null, Clock.systemUTC(), null, 20, List.of(), null,
					new EmbedderHolder(null), Lang.EN, null);
		}

		public Options withSoftLimit(int chars) {
			return new Options(home, version, chars, ownerName, clock, proposer, proposerBatch, ownerIdentity,
					vecLibrary, embedder, lang, vocabulary);
		}

		/** {@code identity}: the owner's other names, addresses, and handles, seeded as aliases. */
		public Options withOwner(String name, List<String> identity) {
			return new Options(home, version, observationSoftLimitChars, name, clock, proposer, proposerBatch, identity,
					vecLibrary, embedder, lang, vocabulary);
		}

		public Options withOwner(String name) {
			return withOwner(name, List.of());
		}

		public Options withClock(Clock clock) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, embedder, lang, vocabulary);
		}

		/**
		 * A server-side proposer for the hybrid mode, reading up to {@code batch} backlog observations per consolidate.
		 */
		public Options withProposer(ModelProposer proposer, int batch) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, batch,
					ownerIdentity, vecLibrary, embedder, lang, vocabulary);
		}

		/** The sqlite-vec loadable library to load at open. */
		public Options withVecLibrary(String library) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, library, embedder, lang, vocabulary);
		}

		/** An embedder the caller owns and closes, so one model can serve many engines. */
		public Options withEmbedder(Embedder embedder) {
			return withEmbedder(new EmbedderHolder(embedder));
		}

		/** The embedder's lifecycle, which may deliver the embedder after the engine has started. */
		public Options withEmbedder(EmbedderHolder holder) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, holder, lang, vocabulary);
		}

		/** The model that compares vocabulary by meaning, in place of the store's embedder; for tests. */
		public Options withVocabularyEmbedding(Embedding vocabulary) {
			return withVocabularyEmbedding(() -> vocabulary);
		}

		/** As above, for a model that may arrive, change, or go away while the engine runs. */
		public Options withVocabularyEmbedding(Supplier<Embedding> vocabulary) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, embedder, lang, vocabulary);
		}

		/** The language of the fact layer; a change re-renders every fact at open. */
		public Options withLang(Lang lang) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, embedder, lang, vocabulary);
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

	/** What {@code consolidate} found and did (EXTRACTION.md, Layer 3). */
	public record Consolidation(long pendingProposals, List<Map<String, Object>> backlog,
			List<Map<String, Object>> openQuestions, List<Map<String, Object>> inferredVocabulary,
			List<Map<String, Object>> similarVocabulary, List<Map<String, Object>> descriptiveEvents,
			List<Map<String, Object>> unusedVocabulary, List<Map<String, Object>> unresolvedDerivations,
			List<Map<String, Object>> misfiledRelations, List<Map<String, Object>> attributeUnknown,
			List<Map<String, Object>> merges, List<Map<String, Object>> nameCollisions, int reclosed,
			List<Map<String, Object>> proposed, List<Map<String, Object>> resolvedQuestions,
			List<Map<String, Object>> review, List<String> retired, int embedded, List<Map<String, Object>> duplicates,
			Rebuilt rebuilt, int removedEntities) {
	}

	/** The vector scheme: 2 since a fact about the owner carries a first-person vector too ({@link OwnerAlias}). */
	static final String VECTOR_SCHEME = "2";
	/** Bench knob: {@code -Dmnemic.owner-alias=false} embeds owner facts without the first-person vector. */
	private static final boolean OWNER_ALIAS = !"false"
			.equalsIgnoreCase(System.getProperty("mnemic.owner-alias", "true"));

	private final Options options;
	private final Database db;
	private final Knowledge knowledge;
	private final ObservationService observations;
	private final VectorStore vectors;
	private final RecallService recall;
	private final Briefing briefing;

	public Engine(Options options) {
		this.options = options;
		this.db = new Database(options.home().resolve(DB_FILE), options.vecLibrary());
		this.observations = new ObservationService(db, options.observationSoftLimitChars());
		this.knowledge = Knowledge.open(db, options.lang(), options.ownerName(), options.ownerIdentity(),
				options.clock(), options.vocabulary() != null ? options.vocabulary() : () -> options.embedder().get());
		this.vectors = new VectorStore(db);
		this.recall = new RecallService(db, knowledge.entities(), knowledge.predicates(), knowledge.facts(),
				knowledge.events(), knowledge.containment(), TokenEstimator.CHARS_PER_TOKEN, options.clock(), vectors,
				options.embedder(), knowledge.entityTypes());
		this.briefing = recall.briefing(knowledge.questions());
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
		if (!VECTOR_SCHEME.equals(db.meta("vector_scheme"))) {
			vectors.dropKind(VectorStore.FACT); // the backfill makes them again from the renderings
			db.setMeta("vector_scheme", VECTOR_SCHEME);
		}
		// A seed that took over a predicate registered from use: its facts read the seed's way from now on.
		for (String adopted : knowledge.predicates().adoptedAtStart()) {
			knowledge.renderer().rerender(adopted);
		}
		// A store from before the rules, or one whose rules changed, gets its derived facts now (family K).
		knowledge.deriver().derive();
	}

	// ── remember ────────────────────────────────────────────────────────

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
			embed(obs, applied);
			return new RememberOutcome(r, applied, resolved, "assistant", derive());
		}
		embed(obs, Applied.NOTHING);
		ModelProposer proposer = options.proposer();
		if (answerOnly || proposer == null || proposer.mode() != ModelProposer.Mode.SYNC) {
			return new RememberOutcome(r, Applied.NOTHING, resolved, "none", resolved.isEmpty() ? null : derive());
		}
		ModelProposer.Result made = proposer.propose(obs.text(), obs.observedAt());
		if (!made.ok()) {
			return new RememberOutcome(r, Applied.NOTHING.withWarning(made.warning()), resolved, "none");
		}
		Applied applied = applyServerProposal(obs, made.proposal());
		embed(obs, applied);
		return new RememberOutcome(r, applied, resolved, "server:" + proposer.id(), derive());
	}

	/** A reading given to an observation: what it produced, and what the reading it replaced had produced. */
	public record Reading(Applied applied, Removed removed, boolean replaced) {
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
					obs.ref() + " is a correction record; correct the fact it produced " + "instead of re-reading it.");
		}
		boolean replaced = obs.proposalJson() != null && !"{}".equals(obs.proposalJson());
		Removed removed = Removed.NONE;
		if (replaced) {
			removed = knowledge.factService().forgetDerived(obs.id(), true);
			vectors.forget(obs.id());
		}
		observations.attachProposal(obs.id(), Json.write(proposal), Proposal.CURRENT_SPEC_VERSION, null);
		Observation stored = observations.get(obs.id()).orElseThrow();
		Applied applied = knowledge.factService().apply(stored, proposal);
		embed(stored, applied);
		derive();
		return new Reading(applied, removed, replaced);
	}

	/** {@link #reread}, for callers that only need what the reading produced. */
	public Applied propose(long observationId, Proposal proposal) {
		return reread(observationId, proposal).applied();
	}

	/** What a rebuild did: the log entries re-derived, and what could not be done again. */
	public record Rebuilt(int observations, int corrections, int answers, List<String> unmatched, List<String> noOps,
			long factsBefore, long factsAfter, long openQuestionsBefore, List<String> questions) {
	}

	/**
	 * Re-derives the projection from the log: every observation with a reading is read again in order, correction
	 * records do their work again against the fact their key now names, and the answers once given to an observation's
	 * questions are given again when the same question comes back. Entities keep their ids; facts and events get new
	 * ones. What could not be replayed (a correction whose fact no longer exists) is reported.
	 */
	public Rebuilt rebuild() {
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
			Applied applied = knowledge.factService().apply(o, Proposal.parse(o.proposalJson()));
			embed(o, applied);
			answers += replayAnswers(o, prior.getOrDefault(o.id(), List.of()));
			n++;
		}
		embedMissing(500);
		derive();
		// Questions the replay asked and no earlier answer settled: a change of state the reply names, so a
		// maintenance run does not leave surprises to be counted.
		List<String> open = knowledge.questions().open(500).stream().map(Question::ref).toList();
		return new Rebuilt(n, corrections, answers, unmatched, noOps, before, knowledge.facts().count(), openBefore,
				open);
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

	/** Stores the server-made proposal on the observation, with its provenance, then applies it. */
	private Applied applyServerProposal(Observation obs, Proposal p) {
		observations.attachProposal(obs.id(), Json.write(p), Proposal.CURRENT_SPEC_VERSION, options.proposer().id());
		return knowledge.factService().apply(observations.get(obs.id()).orElseThrow(), p);
	}

	// ── observations ────────────────────────────────────────────────────

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
		// A correction restates the fact it corrects: forgetting the fact for privacy forgets its corrections too.
		List<Long> records = knowledge.factService().correctionRecordsOf(observationId);
		knowledge.factService().forgetDerived(observationId, keepEntities);
		vectors.forget(observationId); // after: a re-homed fact keeps its vector, a deleted one loses it
		boolean forgotten = observations.forget(observationId);
		for (long record : records) {
			forget(record, keepEntities);
		}
		if (!keepEntities) {
			// An entity created by an earlier forgotten observation may have lost its last reference just now.
			knowledge.entities().removeOrphansOfForgotten();
		}
		derive();
		return forgotten;
	}

	/** Removes an entity nothing names (EVALUATION.md T20): a duplicate the resolver left, once nothing cites it. */
	public Map<String, Object> forgetEntity(long entityId) {
		return knowledge.entities().remove(entityId);
	}

	/**
	 * Corrects an entity: its name, its type, the aliases it keeps ({@code aliases} is the list to keep; the entity's
	 * own name and the owner's identity always stay), or {@code merge_into}, which folds it into the entity named
	 * (facts, events, and aliases move; the id keeps resolving to the survivor). The user says what a thing is called;
	 * a wrong fuzzy match earlier is undone by dropping the alias it left.
	 */
	public Map<String, Object> correctEntity(long entityId, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"aliases\": [\"Hooli\", \"Hooli Inc\"]} or {\"name\": \"Hooli AG\"} or "
				+ "{\"type\": \"organization\"} or {\"merge_into\": \"ent-7\"}");
		var before = knowledge.entities().get(entityId)
				.orElseThrow(() -> MnemicException.notFound("No entity ent-" + entityId));
		List<String> aliasesBefore = knowledge.entities().aliases(before.id());
		var rest = new LinkedHashMap<String, Object>(replacement);
		Object mergeInto = rest.remove("merge_into");
		Map<String, Object> merge = null;
		long id = before.id();
		if (mergeInto != null) {
			Entity into = knowledge.entities().byRef(String.valueOf(mergeInto)).orElseThrow(() -> MnemicException
					.notFound("No entity '" + mergeInto + "' to merge " + before.ref() + " into."));
			if (into.id() == before.id()) {
				throw MnemicException.invalidArgument(before.ref() + " cannot be merged into itself.");
			}
			if (before.id() == knowledge.entities().owner().id()) {
				throw MnemicException.invalidArgument(
						"The owner is never merged away; merge the other entity into " + before.ref() + " instead.");
			}
			merge = knowledge.entities().merge(before.id(), into.id(), null,
					"correct: " + (reason == null || reason.isBlank() ? "the same thing twice" : reason));
			id = into.id();
		}
		var after = rest.isEmpty() ? knowledge.entities().get(id).orElseThrow()
				: knowledge.entities().correct(id, rest);
		int rerendered = 0;
		if (!after.name().equals(before.name()) && merge == null) {
			rerendered = knowledge.renderer().rerenderMentioning(id);
		}
		if (merge != null) {
			derive();
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("entity", after.ref());
		out.put("before", entityState(before, aliasesBefore));
		out.put("after", entityState(after, knowledge.entities().aliases(id)));
		if (merge != null) {
			out.put("merged", merge);
		}
		out.put("rerendered_facts", rerendered);
		out.put("reason", reason);
		return out;
	}

	/** Answers open questions without recording an observation: the answers live on the questions themselves. */
	public List<Map<String, Object>> answer(List<Resolve> resolves) {
		List<Map<String, Object>> resolved = knowledge.resolver().resolve(resolves, options.clock().instant());
		embedMissing(50);
		derive();
		return resolved;
	}

	// ── corrections ─────────────────────────────────────────────────────

	/**
	 * Corrects a fact without destroying history (EVALUATION.md D1): the reason becomes a correction observation, the
	 * replacement is derived from it, the original is marked {@code corrected} and linked. A replacement of
	 * {@code {"wrong": true}} retracts the fact instead (D7).
	 */
	private static final Set<String> FACT_PROPERTIES = Set.of("subject", "predicate", "object", "qualifier", "scope",
			"valid_time", "ended", "caller_confidence", "wrong", "redundant");

	public Corrected correct(long factId, Map<String, Object> replacement, String reason) {
		String about = knowledge.facts().get(factId).map(f -> f.rendering()).orElse("f-" + factId);
		String why = reason == null || reason.isBlank() ? "" : ": " + reason;
		if (replacement != null) {
			for (String key : replacement.keySet()) {
				if (!FACT_PROPERTIES.contains(key)) {
					throw MnemicException.invalidArgument("Unknown fact property '" + key + "'; correctable: subject, "
							+ "predicate, object, qualifier, scope, valid_time, ended, caller_confidence; or "
							+ "{\"wrong\": true} to withdraw, {\"redundant\": true} to retire a fact a derivation "
							+ "covers.");
				}
			}
		}
		if (replacement != null && Boolean.TRUE.equals(replacement.get("redundant"))) {
			Observation record = correctionRecord("Retirement of " + about + why, factId);
			Corrected retired = recorded(record, () -> knowledge.factService().retire(factId, reason, record));
			embedMissing(50);
			derive();
			return retired;
		}
		if (replacement != null && Boolean.TRUE.equals(replacement.get("wrong"))) {
			Observation record = correctionRecord("Retraction of " + about + why, factId);
			Corrected retracted = recorded(record, () -> knowledge.factService().retract(factId, reason, record));
			embedMissing(50);
			derive();
			return retracted;
		}
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'replacement' must name what changes, e.g. {\"object\": "
					+ "\"Schübelbach\"} or {\"valid_time\": {\"start\": \"2014\"}} or {\"ended\": true}.");
		}
		Observation record = correctionRecord("Correction of " + about + why + " → " + Json.write(replacement), factId);
		Corrected corrected = recorded(record,
				() -> knowledge.factService().correct(factId, replacement, reason, record));
		embedMissing(50);
		derive();
		return corrected;
	}

	/**
	 * A correction stands in the log only when it did something: one refused (nothing changed, no such predicate) takes
	 * its record with it, so a rebuild never replays what was never valid.
	 */
	private Corrected recorded(Observation record, Supplier<Corrected> correction) {
		try {
			return correction.get();
		} catch (MnemicException e) {
			forget(record.id(), true);
			throw e;
		}
	}

	/**
	 * A correction record carries its change in itself: nothing to propose from its text, so it never joins the
	 * backlog.
	 */
	private static boolean definesOnly(Proposal p) {
		return p.facts().isEmpty() && p.events().isEmpty() && p.entities().isEmpty() && p.closures().isEmpty()
				&& !(p.predicates().isEmpty() && p.eventTypes().isEmpty() && p.entityTypes().isEmpty());
	}

	private Observation correctionRecord(String text, long factId) {
		Remembered r = observations.remember(text, new Source("correction", "f-" + factId, null, null, null),
				options.clock().instant(), "{}", null, null);
		return observations.get(r.observationId()).orElseThrow();
	}

	/**
	 * Corrects a predicate's definition (EVALUATION.md J5): the change is logged with its reason and every fact under
	 * the predicate is re-rendered.
	 */
	public Map<String, Object> correctPredicate(String name, Map<String, Object> replacement, String reason) {
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'replacement' must name what changes, e.g. {\"render\": "
					+ "\"{object} is {subject}'s {qualifier|parent}\"} or {\"lexicon\": [\"mother\", \"father\"]}.");
		}
		var before = knowledge.predicates().get(name)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + name));
		if (replacement.get("merge_into") != null) {
			return mergePredicate(before, String.valueOf(replacement.get("merge_into")), replacement, reason);
		}
		var after = knowledge.predicates().update(name, replacement, reason);
		int rerendered = knowledge.renderer().rerender(name);
		var out = new LinkedHashMap<String, Object>();
		out.put("predicate", name);
		out.put("before", Map.of("render", before.render(), "lexicon", before.lexicon(), "qualifiers",
				before.qualifiers(), "functional", before.functional()));
		out.put("after", Map.of("render", after.render(), "lexicon", after.lexicon(), "qualifiers", after.qualifiers(),
				"functional", after.functional()));
		out.put("rerendered_facts", rerendered);
		// A predicate that just became functional has facts stored as if it were not: they are checked now.
		out.put("rechecked_conflicts",
				!before.functional() && after.functional() ? knowledge.factService().recheckFunctional(name) : 0);
		out.put("changes", knowledge.predicates().changes(name));
		if (replacement.containsKey("defined_as")) {
			out.put("defined_as", knowledge.predicates().rulesOf(name).stream().map(Rule::toMap).toList());
		}
		if (replacement.containsKey("implies")) {
			out.put("implies", knowledge.predicates().impliesOf(name));
		}
		if (replacement.containsKey("defined_as") || replacement.containsKey("implies")) {
			out.put("derived", derive().toMap());
		}
		return out;
	}

	/** Folds one predicate into another: {@code correct(pred:coaches, {merge_into: "mentors"})}. */
	private Map<String, Object> mergePredicate(
		Predicate from, String into, Map<String, Object> replacement, String reason) {
		if (replacement.size() > 1) {
			throw MnemicException.invalidArgument("'merge_into' stands alone; correct the target afterwards.");
		}
		var target = knowledge.predicates().get(into)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + into));
		int moved = knowledge.predicates().merge(from.name(), target.name(), reason);
		knowledge.eventTypes().renamePredicate(from.name(), target.name(), reason);
		knowledge.renderer().rerender(target.name());
		var out = new LinkedHashMap<String, Object>();
		out.put("predicate", from.name());
		out.put("merged_into", target.name());
		out.put("merged_facts", moved);
		out.put("aliases", knowledge.predicates().get(target.name()).orElseThrow().aliases());
		out.put("changes", knowledge.predicates().changes(target.name()));
		return out;
	}

	/** Corrects an event type's definition; the change is logged with its reason. */
	public Map<String, Object> correctEventType(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"closes\": [\"works_at\"]} or {\"lexicon\": [\"quit\", \"resigned\"]}");
		var before = knowledge.eventTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No event type " + name));
		var after = knowledge.eventTypes().update(name, replacement, reason,
				p -> knowledge.predicates().get(p).isPresent());
		int rerendered = knowledge.events().rerender(after.name());
		var out = new LinkedHashMap<String, Object>();
		out.put("event_type", after.name());
		out.put("before", eventTypeMap(before));
		out.put("after", eventTypeMap(after));
		out.put("rerendered_events", rerendered);
		out.put("changes", knowledge.eventTypes().changes(after.name()));
		return out;
	}

	/** Corrects an entity type's definition; the change is logged with its reason. */
	public Map<String, Object> correctEntityType(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"parent\": \"place\"} or {\"type_words\": [\"kanton\", \"canton\"]}");
		var before = knowledge.entityTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No entity type " + name));
		var after = knowledge.entityTypes().update(name, replacement, reason);
		var out = new LinkedHashMap<String, Object>();
		out.put("entity_type", after.name());
		out.put("before", typeMap(before));
		out.put("after", typeMap(after));
		out.put("changes", knowledge.entityTypes().changes(after.name()));
		return out;
	}

	/**
	 * Corrects a predicate group ({@code correct(group:family, {lexicon: [...]})}): its description, cue words, words
	 * in another language, or the groups it belongs to. The change is logged with its reason.
	 */
	public Map<String, Object> correctGroup(String name, Map<String, Object> replacement, String reason) {
		requireReplacement(replacement, "{\"lexicon\": [\"family\", \"relatives\"]} or {\"groups\": [\"family\"]}");
		var before = knowledge.predicates().group(name).orElseThrow(() -> MnemicException.notFound("No group " + name));
		var after = knowledge.predicates().updateGroup(before.name(), replacement, reason);
		var out = new LinkedHashMap<String, Object>();
		out.put("group", after.name());
		out.put("before", groupMap(before));
		out.put("after", groupMap(after));
		out.put("members", knowledge.predicates().membersOf(after.name()).stream().map(Predicate::name).toList());
		out.put("changes", knowledge.predicates().groupChanges(after.name()));
		return out;
	}

	private static Map<String, Object> groupMap(PredicateRegistry.Group g) {
		var m = new LinkedHashMap<String, Object>();
		m.put("description", g.description());
		m.put("lexicon", g.lexicon());
		m.put("groups", g.groups());
		return m;
	}

	private static Map<String, Object> eventTypeMap(EventTypeRegistry.EventType t) {
		var m = new LinkedHashMap<String, Object>();
		m.put("opens", t.opens());
		m.put("closes", t.closes());
		m.put("supersedes", t.supersedes());
		m.put("ends_entity", t.endsEntity());
		m.put("lexicon", t.lexicon());
		m.put("render", t.render());
		return m;
	}

	private static Map<String, Object> typeMap(EntityTypeRegistry.EntityType t) {
		var m = new LinkedHashMap<String, Object>();
		m.put("parent", t.parent());
		m.put("synonyms", t.synonyms());
		m.put("type_words", t.typeWords());
		return m;
	}

	private static void requireReplacement(Map<String, Object> replacement, String example) {
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'replacement' must name what changes, e.g. " + example + ".");
		}
	}

	public History history(long entityId, String predicate) {
		return knowledge.facts().history(entityId, predicate);
	}

	// ── recall and consolidation ────────────────────────────────────────

	/** The session briefing for a recall without a query (EVALUATION.md F10). */
	public String briefing(int maxTokens) {
		return briefing.render(maxTokens);
	}

	public Consolidation consolidate(boolean dryRun) {
		return consolidate(dryRun, List.of());
	}

	/**
	 * Housekeeping (EXTRACTION.md, Layer 3; EVALUATION.md G2, G3, J6): merges, re-closing, and the lists a caller needs
	 * to finish what Mnemic cannot do alone. {@code dryRun} reports without changing anything; {@code retire} names
	 * observations with nothing to propose (an answer, a note), taken out of the backlog. In hybrid mode the configured
	 * model reads a batch of the backlog per call.
	 */
	public Consolidation consolidate(boolean dryRun, List<Long> retire) {
		return consolidate(dryRun, retire, false);
	}

	/** {@code rebuild}: re-derive the projection from the log first (never on a dry run). */
	public Consolidation consolidate(boolean dryRun, List<Long> retire, boolean rebuild) {
		Rebuilt rebuilt = rebuild && !dryRun ? rebuild() : null;
		var retired = new ArrayList<String>();
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
		var c = knowledge.consolidator().consolidate(dryRun);
		if (!dryRun) {
			derive();
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
		List<Map<String, Object>> open = knowledge.questions().open(20).stream().map(q -> q.toMap()).toList();
		return new Consolidation(observations.pendingProposals(), backlog, open, c.inferredVocabulary(),
				c.similarVocabulary(), c.descriptiveEvents(), c.unusedVocabulary(), c.unresolvedDerivations(),
				c.misfiledRelations(), c.attributeUnknown(), c.merges(), c.nameCollisions(), c.reclosed(), proposed,
				c.resolvedQuestions(), c.review(), retired, embedded, c.duplicates(), rebuilt, c.removedEntities());
	}

	private List<Map<String, Object>> proposeBacklog() {
		ModelProposer proposer = options.proposer();
		if (proposer == null) {
			return List.of();
		}
		var proposed = new ArrayList<Map<String, Object>>();
		for (Observation o : observations.backlog(options.proposerBatch())) {
			var m = new LinkedHashMap<String, Object>();
			m.put("observation", o.ref());
			ModelProposer.Result made = proposer.propose(o.text(), o.observedAt());
			if (made.ok()) {
				Applied a = applyServerProposal(o, made.proposal());
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

	// ── the semantic channel's vectors ──────────────────────────────────

	/**
	 * The observation's text and each new fact's rendering, embedded as they are stored. A failure here never fails the
	 * remember; consolidate backfills what is missing.
	 */
	private void embed(Observation obs, Applied applied) {
		Embedder embedder = options.embedder().get();
		if (embedder == null) {
			return;
		}
		try {
			if (!vectors.has(VectorStore.OBSERVATION, obs.id(), embedder.id())) {
				vectors.putChunks(VectorStore.OBSERVATION, obs.id(), embedder.id(), embedder.embedChunks(obs.text()));
			}
			for (FactOut f : applied.facts()) {
				long id = Long.parseLong(f.id().substring(2));
				if (!vectors.has(VectorStore.FACT, id, embedder.id())) {
					vectors.putChunks(VectorStore.FACT, id, embedder.id(), factVectors(embedder, f.rendering()));
				}
			}
		} catch (RuntimeException e) {
			LOG.warnf("Embedding skipped for %s: %s", obs.ref(), e.getMessage());
		}
	}

	/**
	 * A fact's vectors: its rendering, and for a fact that names the owner the rendering as the owner would say it, so
	 * a first-person question lands on it; the item scores by its better vector.
	 */
	private List<float[]> factVectors(Embedder embedder, String rendering) {
		var out = new ArrayList<float[]>();
		out.add(embedder.embed(rendering));
		String mine = OWNER_ALIAS
				? OwnerAlias.firstPerson(rendering, knowledge.entities().owner().name(), options.lang()) : null;
		if (mine != null) {
			out.add(embedder.embed(mine));
		}
		return out;
	}

	/** Embeds what has no vector yet, up to {@code limit} rows of each kind; the number embedded. */
	public int embedMissing(int limit) {
		Embedder embedder = options.embedder().get();
		if (embedder == null) {
			return 0;
		}
		int n = 0;
		for (long id : vectors.missingObservations(embedder.id(), limit)) {
			observations.get(id).ifPresent(
					o -> vectors.putChunks(VectorStore.OBSERVATION, id, embedder.id(), embedder.embedChunks(o.text())));
			n++;
		}
		for (long id : vectors.missingFacts(embedder.id(), limit)) {
			knowledge.facts().get(id).ifPresent(
					f -> vectors.putChunks(VectorStore.FACT, id, embedder.id(), factVectors(embedder, f.rendering())));
			n++;
		}
		return n;
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

	private static Map<String, Object> entityState(Entity e, List<String> aliases) {
		var m = new LinkedHashMap<String, Object>();
		m.put("name", e.name());
		m.put("type", e.type());
		m.put("aliases", aliases);
		return m;
	}

	/** Brings the derived facts in line with the current facts and rules (family K). */
	public Deriver.Outcome derive() {
		return knowledge.deriver().derive();
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
