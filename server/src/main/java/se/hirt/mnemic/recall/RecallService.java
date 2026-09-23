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
package se.hirt.mnemic.recall;

import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.Containment;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.EventService;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.QuestionService;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult.Hit;
import se.hirt.mnemic.recall.RecallResult.Structured;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Retrieval, not question answering (EXTRACTION.md, Recall). Channels: the structured probe (entity spotting plus
 * predicate cues, looked up over valid time), the keys (fact and event renderings matched lexically), lexical BM25 over
 * observation text, the semantic channel (the question's vector against every observation's and fact's), and the
 * upcoming channel for questions about what is coming. Candidates are observations; channels are fused with reciprocal
 * rank fusion; every hit returns the observation window with the facts that anchored it. {@code as_of} is a pre-filter
 * inside every channel, never a post-filter; current facts outrank ended and superseded ones; confidence never depends
 * on the clock, staleness is an annotation.
 */
public final class RecallService {

	private static final int CANDIDATES = 100;
	/** The block's token budget when the caller gives none: room for a grouped verdict and a few observations. */
	public static final int DEFAULT_MAX_TOKENS = 1500;
	/** How long recall waits for a model that is loading from disk before answering without the channel. */
	private static final long LOAD_WAIT_MS = Long.getLong("mnemic.semantic.load-wait-ms", 20_000L);
	private static final int RRF_K = 60;
	private static final double STRUCTURED_WEIGHT = 2.0;
	/**
	 * The semantic channel's fusion weight, measured on the stratified 120 (BENCHMARKS.md): with one vector per chunk
	 * it votes as an equal, +0.050 [0.017, 0.092] recall at both 5 and 10. The system properties are bench knobs.
	 */
	private static final double SEMANTIC_WEIGHT = Double
			.parseDouble(System.getProperty("mnemic.semantic.weight", "1.0"));
	private static final String SEMANTIC_KINDS = System.getProperty("mnemic.semantic.kinds", "both");
	/** At most this many observations enter the fusion from the semantic channel, and only above this cosine. */
	private static final int SEMANTIC_TOP = Integer.parseInt(System.getProperty("mnemic.semantic.top", "100"));
	private static final float SEMANTIC_MIN = Float.parseFloat(System.getProperty("mnemic.semantic.min", "0"));
	private static final int MAX_FACTS_PER_HIT = 6;
	private static final int MAX_EVENTS = 10;

	/** The observations each channel ranked, best first, and the facts that anchor each observation. */
	private static final class Channels {
		final List<Long> structured = new ArrayList<>();
		final List<Long> keys = new ArrayList<>();
		final List<Long> lexical = new ArrayList<>();
		final List<Long> semantic = new ArrayList<>();
		final List<Long> upcoming = new ArrayList<>();
		final Set<Long> nearMiss = new LinkedHashSet<>();
		final Map<Long, LinkedHashSet<Fact>> anchors = new LinkedHashMap<>();
		final List<Event> events = new ArrayList<>();
		/** Events that matched the query text itself, not just an entity's name. */
		final Set<Long> lexicalEventIds = new HashSet<>();

		static void rank(List<Long> channel, long observationId) {
			if (!channel.contains(observationId)) {
				channel.add(observationId);
			}
		}

		void anchor(long observationId, Fact f) {
			anchors.computeIfAbsent(observationId, k -> new LinkedHashSet<>()).add(f);
		}

		void rankAndAnchor(List<Long> channel, Fact f, List<Long> observationIds) {
			for (long id : observationIds) {
				rank(channel, id);
				anchor(id, f);
			}
		}
	}

	/**
	 * What the structured verdict is allowed to rank. The owner is the subject of nearly every fact, so their facts
	 * under an undirected, non-functional cue ("what does Mattias use") would push the oldest observations to the top
	 * (measured: −0.10 recall@5 on the first facts-as-keys pilot, −0.033 recall@10 on the stratified 120); a directed
	 * cue ("Mattias's father"), a functional one ("works_at"), a cue that is the whole question ("what does Mattias
	 * own"), or a yes/no question that names something are answers and rank.
	 */
	private record Gates(String cueDirection, boolean answerCue, boolean ownerOnly) {
	}

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final FactQueries facts;
	private final EventService events;
	private final TokenEstimator tokens;
	private final Clock clock;
	private final VectorStore vectors;
	private final EmbedderHolder holder;
	private final EntityTypeRegistry types;
	private final StructuredProbe probe;
	private final RecallRenderer renderer;

	/** {@code vectors} and {@code holder} carry the semantic channel; the holder may deliver its embedder later. */
	public RecallService(Database db, EntityService entities, PredicateRegistry predicates, FactQueries facts,
			EventService events, Containment containment, TokenEstimator tokens, Clock clock, VectorStore vectors,
			EmbedderHolder holder, EntityTypeRegistry types) {
		this.db = db;
		this.types = types;
		this.entities = entities;
		this.predicates = predicates;
		this.facts = facts;
		this.events = events;
		this.tokens = tokens;
		this.clock = clock;
		this.vectors = vectors;
		this.holder = holder;
		this.probe = new StructuredProbe(entities, facts, containment, types, predicates);
		this.renderer = new RecallRenderer(predicates, facts, holder);
	}

	/** The briefing for a recall without a query, sharing this service's annotations. */
	public Briefing briefing(QuestionService questions) {
		return new Briefing(entities, facts, questions, tokens, clock, renderer);
	}

	public RecallResult recall(String query, Instant asOf, int maxTokens, int limit) {
		return recall(query, asOf, maxTokens, limit, false);
	}

	public RecallResult recall(String query, Instant asOf, int maxTokens, int limit, boolean includeHistory) {
		if (query == null || query.isBlank()) {
			throw MnemicException.invalidArgument("'query' is blank; omit it entirely for the session briefing. "
					+ "Example: {\"query\": \"where does Mattias work\"}");
		}
		if (maxTokens <= 0) {
			throw MnemicException.invalidArgument("'max_tokens' must be positive, e.g. " + DEFAULT_MAX_TOKENS + ".");
		}
		Instant now = clock.instant();
		Query q = Query.analyse(query, entities, predicates, types);
		Structured s = probe.probe(q, asOf, now, includeHistory);
		Gates g = gates(q, s);
		var ch = new Channels();
		structuredChannel(s, q, g, ch);
		keysChannel(q, s, g, asOf, now, includeHistory, ch);
		entityEvents(q, s, asOf, ch);
		s = eventCue(q, s, g, asOf, ch);
		lexicalChannel(q, asOf, ch);
		if ("miss".equals(s.state()) && ch.events.stream().anyMatch(ev -> ch.lexicalEventIds.contains(ev.id()))) {
			// A MISS over an events line that answers the question told a well-behaved assistant to say "I don't
			// know". Only events that matched the query text count; an entity's own events stay context.
			s = s.withState("events");
		}
		semanticChannel(q, asOf, now, includeHistory, ch);
		upcomingChannel(q, asOf, now, ch);
		Ranking ranking = fuse(ch);
		List<Hit> hits = budget(ranking, ch, q, s, maxTokens, limit, includeHistory);
		String text = renderer.render(query, asOf, now, s, ch.events, hits, ranking.order.size(), ranking.used,
				maxTokens, ranking.truncated, q.polar());
		return new RecallResult(query, asOf, s, List.copyOf(ch.events), List.copyOf(hits), ranking.order.size(),
				ranking.used, maxTokens, ranking.truncated, text);
	}

	private Gates gates(Query q, Structured s) {
		long ownerId = entities.owner().id();
		boolean functionalCue = s.predicate() != null
				&& predicates.get(s.predicate()).map(Predicate::functional).orElse(false);
		String cueDirection = directionOf(q, s.predicate());
		boolean cueIsTheQuestion = !q.cues().isEmpty() && s.predicate() != null && !"entity".equals(s.state())
				&& q.beyondEntities().stream().allMatch(q.cueTokens()::contains);
		boolean narrowed = q.polar() && (!q.residual().isEmpty() || !q.others(ownerId).isEmpty());
		boolean answerCue = functionalCue || !"any".equals(cueDirection) || cueIsTheQuestion || narrowed;
		boolean ownerOnly = q.others(ownerId).isEmpty() && ("entity".equals(s.state()) || !answerCue);
		return new Gates(cueDirection, answerCue, ownerOnly);
	}

	/** The side of the relation the question's cue on {@code predicate} names, {@code any} when it names none. */
	private static String directionOf(Query q, String predicate) {
		if (predicate == null) {
			return "any";
		}
		for (Query.Bound b : q.bound()) {
			if (b.cue().predicate().name().equals(predicate)) {
				return b.cue().direction();
			}
		}
		return "any";
	}

	/**
	 * Whether a key under a cued predicate stands the way some cue on it asks: for each cue on the predicate, the fact
	 * has that cue's subject (or, when the cue binds nobody, any spotted entity) on the side the cue names. A question
	 * that names both sides ("Konrad's children and Mattias's parents") admits each side for its own subject and no
	 * more.
	 */
	private static boolean keyStandsAsAsked(Fact f, Query q) {
		boolean cued = false;
		for (Query.Bound b : q.bound()) {
			if (!b.cue().predicate().name().equals(f.predicate())) {
				continue;
			}
			cued = true;
			if ("any".equals(b.cue().direction())) {
				return true;
			}
			List<Entity> subjects = b.hasSubject() ? b.subjects() : q.spotted();
			for (Entity e : subjects) {
				boolean isSubject = f.subjectId() == e.id();
				boolean isObject = f.objectId() != null && f.objectId() == e.id();
				if ("subject".equals(b.cue().direction()) ? isSubject : isObject) {
					return true;
				}
			}
		}
		return !cued;
	}

	/** Channel 1: the structured verdicts' facts rank their observations and anchor them, the primary one first. */
	private void structuredChannel(Structured primary, Query q, Gates g, Channels ch) {
		for (Structured s : primary.all()) {
			structuredVerdict(s, q, g, ch);
		}
	}

	private void structuredVerdict(Structured s, Query q, Gates g, Channels ch) {
		Set<Long> others = new HashSet<>();
		for (Entity e : q.others(entities.owner().id())) {
			others.add(e.id());
		}
		for (Fact f : s.facts()) {
			// A fact from the owner's own probe under a non-answering cue neither ranks nor anchors: "the user uses
			// NumPy" under every hit is budget, not evidence. Facts touching another named entity stay.
			boolean ownersProbe = !g.answerCue() && !others.contains(f.subjectId())
					&& (f.objectId() == null || !others.contains(f.objectId()));
			if (g.ownerOnly() || ownersProbe) {
				continue;
			}
			Channels.rank(ch.structured, f.observationId());
			ch.anchor(f.observationId(), f);
		}
		if (s.decidedBy() != null) {
			// A known-false verdict is decided by one fact: it ranks first whatever the owner gates say.
			Fact d = s.decidedBy();
			ch.structured.remove(d.observationId());
			ch.structured.addFirst(d.observationId());
			ch.anchor(d.observationId(), d);
		}
		for (Fact b : s.bounds()) {
			ch.anchor(b.observationId(), b);
		}
		for (Fact f : s.future()) {
			Channels.rank(ch.structured, f.observationId());
			ch.anchor(f.observationId(), f);
		}
		if (!g.ownerOnly()) {
			// Chain facts supplement the matched facts; when those are kept out of the ranking, so are these, or the
			// traversal displaces the answer it was meant to explain (the "via:" line still shows them).
			for (Fact f : s.chain()) {
				Channels.rank(ch.structured, f.observationId());
				ch.anchor(f.observationId(), f);
			}
		}
		for (Fact f : s.nearMisses()) {
			ch.nearMiss.add(f.observationId());
			ch.anchor(f.observationId(), f);
		}
	}

	/**
	 * Channel 2: fact and event renderings that match the query. A key must carry two distinct query terms when the
	 * query has three or more: on renderings this short one shared word is noise that outranked exact observation
	 * matches (−0.10 recall@5). Every observation that stated or corroborated a fact is reached.
	 */
	private void keysChannel(
		Query q, Structured s, Gates g, Instant asOf, Instant now, boolean includeHistory, Channels ch) {
		int needed = q.terms().size() >= 3 ? 2 : 1;
		for (Fact f : facts.lexical(q.fts(), asOf, now, includeHistory, CANDIDATES)) {
			if (Query.matchedTerms(f.rendering(), q.terms()) < needed) {
				continue;
			}
			// The keys obey the direction the verdict applied: under "Mattias's father" a rendering with Mattias on
			// the wrong side, or about someone else's mother, is out.
			if (!keyStandsAsAsked(f, q)) {
				continue;
			}
			ch.rankAndAnchor(ch.keys, f, facts.observationsOf(f.id()));
		}
		for (Event ev : events.lexical(q.fts(), asOf, CANDIDATES)) {
			if (Query.matchedTerms(ev.rendering(), q.terms()) < needed) {
				continue;
			}
			for (long o : events.observationsOf(ev.id())) {
				Channels.rank(ch.keys, o);
			}
			if (ch.events.size() < MAX_EVENTS) {
				ch.events.add(ev);
				// An event answers the question only when it matches a term beyond the entities' own names: "born"
				// in "when were the children born", not "Bosse" in "where does Bosse live".
				if (!q.beyondEntities().isEmpty() && Query.matchedTerms(ev.rendering(), q.beyondEntities()) >= 1) {
					ch.lexicalEventIds.add(ev.id());
				}
			}
		}
	}

	/**
	 * Events of the entities the query names, within the time window. The owner's events are everything, so theirs come
	 * along only when they explain the predicate the structured probe matched (EVALUATION.md C9).
	 */
	private void entityEvents(Query q, Structured s, Instant asOf, Channels ch) {
		for (Entity e : q.spotted()) {
			boolean owner = e.id() == entities.owner().id();
			if (owner && s.predicate() == null) {
				continue;
			}
			for (Event ev : events.eventsOf(e.id())) {
				if (ch.events.size() >= MAX_EVENTS) {
					break;
				}
				if (owner && !events.touches(ev.type(), s.predicate())) {
					continue;
				}
				if (within(ev, asOf) && ch.events.stream().noneMatch(x -> x.id() == ev.id())) {
					ch.events.add(ev);
				}
			}
		}
	}

	/** With {@code as_of}, only events dated on or before it: an undated event is in no year (EVALUATION.md T24). */
	private static boolean within(Event ev, Instant asOf) {
		return asOf == null
				|| (ev.validStart() != null && ev.validStart().compareTo(RecallRenderer.DAY.format(asOf)) <= 0);
	}

	/**
	 * An event cue ("when did Mattias buy his house") is the purchased event with its date. Registered types answer
	 * through their lexicon, a type nobody registered through its own name. The cued events rank first with the
	 * structured channel and take the verdict unless a directed or functional fact answered already.
	 */
	private Structured eventCue(Query q, Structured s, Gates g, Instant asOf, Channels ch) {
		Optional<String> cue = events.cue(eventTerms(q));
		if (cue.isEmpty()) {
			return s;
		}
		var cued = new ArrayList<Event>();
		for (Entity e : q.spotted()) {
			for (Event ev : events.eventsOfType(e.id(), cue.get())) {
				if (within(ev, asOf) && cued.stream().noneMatch(x -> x.id() == ev.id())) {
					cued.add(ev);
				}
			}
		}
		if (cued.isEmpty()) {
			return s;
		}
		ch.events.removeIf(ev -> cued.stream().anyMatch(c -> c.id() == ev.id()));
		ch.events.addAll(0, cued);
		while (ch.events.size() > MAX_EVENTS) {
			ch.events.removeLast();
		}
		var ranked = new ArrayList<Long>();
		for (Event ev : cued) {
			for (long o : events.observationsOf(ev.id())) {
				Channels.rank(ranked, o);
			}
		}
		for (Long id : ch.structured) {
			Channels.rank(ranked, id);
		}
		ch.structured.clear();
		ch.structured.addAll(ranked);
		boolean answered = ("matched".equals(s.state()) && g.answerCue()) || s.knownFalse()
				|| "future".equals(s.state());
		return answered ? s : s.withState("events");
	}

	/**
	 * The query's terms without the words that name a thing it spotted or that thing's kind: in "the balance on the
	 * mortgage", "mortgage" is the loan, not the mortgage_renewal event whose type shares the word (2026-09-23). The
	 * event's own words ("renewal", "confirm") still cue it.
	 */
	private static List<String> eventTerms(Query q) {
		var own = new HashSet<String>();
		for (Entity e : q.spotted()) {
			own.addAll(Names.tokens(e.name()));
			own.addAll(Names.tokens(e.type().replace('_', ' ')));
		}
		return q.terms().stream().filter(t -> !own.contains(t)).toList();
	}

	/** Channel 3: BM25 over observation text, observed by {@code asOf}. */
	private void lexicalChannel(Query q, Instant asOf, Channels ch) {
		if (q.fts().isEmpty()) {
			return;
		}
		List<Row> rows = db.read(tx -> asOf == null ? tx.query("""
				SELECT o.id FROM observation_fts f JOIN observation o ON o.id = f.rowid
				WHERE observation_fts MATCH ? AND o.forgotten_at IS NULL
				ORDER BY bm25(observation_fts), o.id DESC LIMIT ?""", q.fts(), CANDIDATES) : tx.query("""
				SELECT o.id FROM observation_fts f JOIN observation o ON o.id = f.rowid
				WHERE observation_fts MATCH ? AND o.forgotten_at IS NULL AND o.observed_at <= ?
				ORDER BY bm25(observation_fts), o.id DESC LIMIT ?""", q.fts(), asOf.toString(), CANDIDATES));
		for (Row r : rows) {
			ch.lexical.add(r.lng("id"));
		}
	}

	/**
	 * Channel 4: the question's vector against every observation's and fact's. A fact hit anchors its observations like
	 * a key hit; an observation hit ranks on its own. The same valid-time and observation-time rules apply. A model
	 * already on disk is waited for briefly after a start; a download never is. A runtime fault costs the semantic
	 * hits, never the answer.
	 */
	private void semanticChannel(Query q, Instant asOf, Instant now, boolean includeHistory, Channels ch) {
		if (holder != null && holder.get() == null && "loading".equals(holder.state().state())) {
			try {
				holder.await(LOAD_WAIT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Embedder embedder = holder == null ? null : holder.get();
		if (embedder == null || vectors == null) {
			return;
		}
		try {
			float[] vector = embedder.embedQuery(q.text());
			for (VectorStore.Hit h : vectors.search(embedder.id(), vector, CANDIDATES)) {
				if (!"both".equals(SEMANTIC_KINDS) && !SEMANTIC_KINDS.equals(h.kind())) {
					continue;
				}
				if (h.score() < SEMANTIC_MIN || ch.semantic.size() >= SEMANTIC_TOP) {
					break; // hits arrive best first: below the floor, or past the cap, nothing more enters
				}
				if (VectorStore.FACT.equals(h.kind())) {
					Fact f = facts.get(h.id()).orElse(null);
					if (f == null || "pending".equals(f.status()) || "corrected".equals(f.status())) {
						continue;
					}
					boolean ok = asOf != null ? f.mayHoldAt(asOf)
							: ("current".equals(f.state(now)) || "future".equals(f.state(now)) || includeHistory);
					if (ok) {
						ch.rankAndAnchor(ch.semantic, f, facts.observationsOf(f.id()));
					}
				} else if (asOf == null || observedBy(h.id(), asOf)) {
					Channels.rank(ch.semantic, h.id());
				}
			}
		} catch (RuntimeException e) {
			// the channel is an addition
		}
	}

	/** Whether the observation was made by {@code asOf}: the observation-time filter of the text channels. */
	private boolean observedBy(long observationId, Instant asOf) {
		return db.read(tx -> tx.queryLong(
				"SELECT COUNT(*) FROM observation WHERE id = ? AND observed_at <= ? AND forgotten_at IS NULL",
				observationId, asOf.toString())) > 0;
	}

	/**
	 * Channel 5: a question about what is coming ranks the observations behind future-dated facts of the entities in
	 * it, or the owner's, with the structured channel's weight, so a car collected next week is not lost to an old
	 * robot on the word "vehicle".
	 */
	private void upcomingChannel(Query q, Instant asOf, Instant now, Channels ch) {
		if (asOf != null || !q.forward()) {
			return;
		}
		List<Entity> about = q.spotted().isEmpty() ? List.of(entities.owner()) : q.spotted();
		for (Entity e : about) {
			for (Fact f : facts.factsOf(e.id())) {
				if ("future".equals(f.state(now))) {
					ch.rankAndAnchor(ch.upcoming, f, facts.observationsOf(f.id()));
				}
			}
		}
	}

	/** The fused order, with each observation's score and the channels that produced it; budget fields follow. */
	private static final class Ranking {
		final Map<Long, Double> score = new LinkedHashMap<>();
		final Map<Long, LinkedHashSet<String>> channels = new LinkedHashMap<>();
		List<Long> order = List.of();
		int used;
		boolean truncated;

		void add(List<Long> ranked, String channel, double weight) {
			for (int i = 0; i < ranked.size(); i++) {
				long id = ranked.get(i);
				score.merge(id, weight / (RRF_K + i + 1), Double::sum);
				channels.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(channel);
			}
		}
	}

	private static Ranking fuse(Channels ch) {
		var r = new Ranking();
		r.add(ch.structured, "structured", STRUCTURED_WEIGHT);
		r.add(ch.keys, "facts", 1.0);
		r.add(ch.lexical, "lexical", 1.0);
		r.add(ch.semantic, "semantic", SEMANTIC_WEIGHT);
		r.add(ch.upcoming, "upcoming", STRUCTURED_WEIGHT);
		for (Long id : ch.nearMiss) {
			if (!r.score.containsKey(id)) {
				r.score.put(id, 0.0);
				r.channels.computeIfAbsent(id, k -> new LinkedHashSet<>()).add("near-miss");
			}
		}
		r.order = r.score.entrySet().stream().sorted((a, b) -> {
			int c = Double.compare(b.getValue(), a.getValue());
			return c != 0 ? c : Long.compare(b.getKey(), a.getKey());
		}).map(Map.Entry::getKey).toList();
		return r;
	}

	/**
	 * Fits the ranked candidates into the budget. A candidate that does not fit whole is shown as its fact lines alone
	 * before it is dropped: the fact is the answer, the prose is the evidence. A retired observation was wrong or
	 * superseded; history shows it, flagged, recall does not.
	 */
	private List<Hit> budget(
		Ranking r, Channels ch, Query q, Structured s, int maxTokens, int limit, boolean includeHistory) {
		var hits = new ArrayList<Hit>();
		// The verdicts carry their facts, which the block shows before any observation: they are paid for first.
		// Under a hit the same fact appears again, with its annotations (state, confidence, belief, when it was
		// confirmed), which the verdict line does not carry.
		for (Structured v : s.all()) {
			for (Fact f : RecallRenderer.verdictFacts(v)) {
				r.used += tokens.estimate(f.rendering()) + 8;
			}
		}
		for (Long id : r.order) {
			if (hits.size() >= limit) {
				r.truncated = true;
				break;
			}
			Observation o = observation(id);
			if (o == null || (o.retired() && !includeHistory)) {
				continue;
			}
			List<Fact> anchor = new ArrayList<>(ch.anchors.getOrDefault(o.id(), new LinkedHashSet<>()));
			if (anchor.size() > MAX_FACTS_PER_HIT) {
				anchor = anchor.subList(0, MAX_FACTS_PER_HIT);
			}
			String shown = RecallRenderer.shown(o, anchor, q.text());
			if (o.retired()) {
				shown = "[retired: " + o.retiredReason()
						+ (o.supersededBy() != null ? "; superseded by obs-" + o.supersededBy() : "") + "] " + shown;
			}
			int factCost = 24;
			for (Fact f : anchor) {
				factCost += tokens.estimate(f.rendering()) + 16;
			}
			int cost = factCost + tokens.estimate(shown);
			List<String> channels = List.copyOf(r.channels.get(o.id()));
			boolean nearMissOnly = channels.equals(List.of("near-miss"));
			if (r.used + cost <= maxTokens) {
				r.used += cost;
				hits.add(new Hit(o, channels, r.score.get(id), shown, cost, anchor, nearMissOnly));
			} else if (!anchor.isEmpty() && r.used + factCost <= maxTokens) {
				r.used += factCost;
				r.truncated = true;
				hits.add(new Hit(o, channels, r.score.get(id), "", factCost, anchor, nearMissOnly));
			} else {
				r.truncated = true;
			}
		}
		return hits;
	}

	private Observation observation(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ? AND forgotten_at IS NULL", id)
				.map(Observation::from).orElse(null));
	}
}
