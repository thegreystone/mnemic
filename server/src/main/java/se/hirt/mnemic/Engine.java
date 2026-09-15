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
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EventService;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.FactQueries.History;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactService.FactOut;
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
import se.hirt.mnemic.recall.OwnerAlias;
import se.hirt.mnemic.recall.RecallService;
import se.hirt.mnemic.recall.TokenEstimator;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
			EmbedderHolder embedder, Lang lang) {

		public static Options of(Path home, String version) {
			return new Options(home, version, 4000, null, Clock.systemUTC(), null, 20, List.of(), null,
					new EmbedderHolder(null), Lang.EN);
		}

		public Options withSoftLimit(int chars) {
			return new Options(home, version, chars, ownerName, clock, proposer, proposerBatch, ownerIdentity,
					vecLibrary, embedder, lang);
		}

		/** {@code identity}: the owner's other names, addresses, and handles, seeded as aliases. */
		public Options withOwner(String name, List<String> identity) {
			return new Options(home, version, observationSoftLimitChars, name, clock, proposer, proposerBatch, identity,
					vecLibrary, embedder, lang);
		}

		public Options withOwner(String name) {
			return withOwner(name, List.of());
		}

		public Options withClock(Clock clock) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, embedder, lang);
		}

		/**
		 * A server-side proposer for the hybrid mode, reading up to {@code batch} backlog observations per consolidate.
		 */
		public Options withProposer(ModelProposer proposer, int batch) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, batch,
					ownerIdentity, vecLibrary, embedder, lang);
		}

		/** The sqlite-vec loadable library to load at open. */
		public Options withVecLibrary(String library) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, library, embedder, lang);
		}

		/** An embedder the caller owns and closes, so one model can serve many engines. */
		public Options withEmbedder(Embedder embedder) {
			return withEmbedder(new EmbedderHolder(embedder));
		}

		/** The embedder's lifecycle, which may deliver the embedder after the engine has started. */
		public Options withEmbedder(EmbedderHolder holder) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, holder, lang);
		}

		/** The language of the fact layer; a change re-renders every fact at open. */
		public Options withLang(Lang lang) {
			return new Options(home, version, observationSoftLimitChars, ownerName, clock, proposer, proposerBatch,
					ownerIdentity, vecLibrary, embedder, lang);
		}
	}

	/**
	 * The outcome of {@code remember}: the observation, what its proposal produced (empty without one), what answering
	 * questions did, and who proposed: {@code assistant}, {@code server:<model id>}, or {@code none}.
	 */
	public record RememberOutcome(Remembered observation, Applied applied, List<Map<String, Object>> resolved,
			String proposalSource) {
	}

	/** What {@code consolidate} found and did (EXTRACTION.md, Layer 3). */
	public record Consolidation(long pendingProposals, List<Map<String, Object>> backlog,
			List<Map<String, Object>> openQuestions, List<Map<String, Object>> suggestedRegistrations,
			List<Map<String, Object>> merges, int reclosed, List<Map<String, Object>> proposed,
			List<Map<String, Object>> resolvedQuestions, List<Map<String, Object>> review, List<String> retired,
			int embedded, List<Map<String, Object>> duplicates) {
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
				options.clock());
		this.vectors = new VectorStore(db);
		this.recall = new RecallService(db, knowledge.entities(), knowledge.predicates(), knowledge.facts(),
				knowledge.events(), knowledge.containment(), TokenEstimator.CHARS_PER_TOKEN, options.clock(), vectors,
				options.embedder());
		this.briefing = recall.briefing(knowledge.questions());
		String renderedIn = db.meta("language");
		if (db.migrated() > 0 || !options.lang().code().equals(renderedIn)) {
			// A migration may change how facts read, and so does the language: every rendering follows from what is
			// stored, since the words were never the record.
			knowledge.renderer().rerenderAll();
			db.setMeta("language", options.lang().code());
		}
		if (!VECTOR_SCHEME.equals(db.meta("vector_scheme"))) {
			vectors.dropKind(VectorStore.FACT); // the backfill makes them again from the renderings
			db.setMeta("vector_scheme", VECTOR_SCHEME);
		}
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
			embed(obs, applied);
			return new RememberOutcome(r, applied, resolved, "assistant");
		}
		embed(obs, Applied.NOTHING);
		ModelProposer proposer = options.proposer();
		if (answerOnly || proposer == null || proposer.mode() != ModelProposer.Mode.SYNC) {
			return new RememberOutcome(r, Applied.NOTHING, resolved, "none");
		}
		ModelProposer.Result made = proposer.propose(obs.text(), obs.observedAt());
		if (!made.ok()) {
			return new RememberOutcome(r, Applied.NOTHING.withWarning(made.warning()), resolved, "none");
		}
		Applied applied = applyServerProposal(obs, made.proposal());
		embed(obs, applied);
		return new RememberOutcome(r, applied, resolved, "server:" + proposer.id());
	}

	/**
	 * A structured reading for an observation already stored without one (EVALUATION.md I5): a connector's email, a
	 * note remembered in a hurry. The proposal is attached to that observation, its facts carry that observation as
	 * their provenance, its rows are embedded, and it leaves the backlog. An observation that already has a reading is
	 * refused: its facts are corrected, not proposed again.
	 */
	public Applied propose(long observationId, Proposal proposal) {
		Observation obs = observations.get(observationId)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + observationId));
		if (obs.forgotten()) {
			throw MnemicException.conflict("obs-" + observationId + " is forgotten.", Map.of());
		}
		if (obs.proposalJson() != null && !"{}".equals(obs.proposalJson())) {
			throw MnemicException.conflict("obs-" + observationId
					+ " already has a structured reading; correct its facts " + "instead of proposing again.",
					Map.of("observation", obs.ref()));
		}
		observations.attachProposal(obs.id(), Json.write(proposal), Proposal.CURRENT_SPEC_VERSION, null);
		Observation stored = observations.get(obs.id()).orElseThrow();
		Applied applied = knowledge.factService().apply(stored, proposal);
		embed(stored, applied);
		return applied;
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
		knowledge.factService().forgetDerived(observationId, keepEntities);
		vectors.forget(observationId); // after: a re-homed fact keeps its vector, a deleted one loses it
		return observations.forget(observationId);
	}

	// ── corrections ─────────────────────────────────────────────────────

	/**
	 * Corrects a fact without destroying history (EVALUATION.md D1): the reason becomes a correction observation, the
	 * replacement is derived from it, the original is marked {@code corrected} and linked. A replacement of
	 * {@code {"wrong": true}} retracts the fact instead (D7).
	 */
	public Corrected correct(long factId, Map<String, Object> replacement, String reason) {
		String about = knowledge.facts().get(factId).map(f -> f.rendering()).orElse("f-" + factId);
		String why = reason == null || reason.isBlank() ? "" : ": " + reason;
		if (replacement != null && Boolean.TRUE.equals(replacement.get("wrong"))) {
			Observation record = correctionRecord("Retraction of " + about + why, factId);
			Corrected retracted = knowledge.factService().retract(factId, reason, record);
			embedMissing(50);
			return retracted;
		}
		if (replacement == null || replacement.isEmpty()) {
			throw MnemicException.invalidArgument("'replacement' must name what changes, e.g. {\"object\": "
					+ "\"Schübelbach\"} or {\"valid_time\": {\"start\": \"2014\"}} or {\"ended\": true}.");
		}
		Observation record = correctionRecord("Correction of " + about + why + " → " + Json.write(replacement), factId);
		Corrected corrected = knowledge.factService().correct(factId, replacement, reason, record);
		embedMissing(50);
		return corrected;
	}

	/**
	 * A correction record carries its change in itself: nothing to propose from its text, so it never joins the
	 * backlog.
	 */
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
		var after = knowledge.predicates().update(name, replacement, reason);
		int rerendered = knowledge.renderer().rerender(name);
		var out = new LinkedHashMap<String, Object>();
		out.put("predicate", name);
		out.put("before", Map.of("render", before.render(), "lexicon", before.lexicon(), "qualifiers",
				before.qualifiers(), "functional", before.functional()));
		out.put("after", Map.of("render", after.render(), "lexicon", after.lexicon(), "qualifiers", after.qualifiers(),
				"functional", after.functional()));
		out.put("rerendered_facts", rerendered);
		out.put("changes", knowledge.predicates().changes(name));
		return out;
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
		return new Consolidation(observations.pendingProposals(), backlog, open, c.suggestedRegistrations(), c.merges(),
				c.reclosed(), proposed, c.resolvedQuestions(), c.review(), retired, embedded, c.duplicates());
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

	public EntityService entities() {
		return knowledge.entities();
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
