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
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.OrtLibrary;
import se.hirt.mnemic.embed.OrtProbe;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.EventTypeRegistry;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactQueries;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactService.EntityOut;
import se.hirt.mnemic.knowledge.FactService.EventOut;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.FactService.PredicateOut;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.Rule;
import se.hirt.mnemic.knowledge.Supersession;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed view records and JSON reply mappers for {@link MnemicTools}. Every view serializes explicitly to a {@link Map}
 * via {@code toMap()} so Jackson never relies on reflection in GraalVM native images.
 */
final class ToolViews {

	static final int LIST_CAP = 25;

	private static final Set<String> SELF_ALIASES = Set.of("I", "me", "my", "myself", "self", "the user");

	private final Engine engine;

	ToolViews(Engine engine) {
		this.engine = engine;
	}

	// ── fluent map builder ───────────────────────────────────────────────

	private static final class Fields {
		private final Map<String, Object> map = new LinkedHashMap<>();

		Fields put(String key, Object value) {
			map.put(key, value);
			return this;
		}

		Fields putAll(Map<String, Object> entries) {
			map.putAll(entries);
			return this;
		}

		Fields putIfNotNull(String key, Object value) {
			if (value != null) {
				map.put(key, value);
			}
			return this;
		}

		Fields putIfNonEmpty(String key, Collection<?> value) {
			if (value != null && !value.isEmpty()) {
				map.put(key, value);
			}
			return this;
		}

		Fields putIfNonEmpty(String key, Map<?, ?> value) {
			if (value != null && !value.isEmpty()) {
				map.put(key, value);
			}
			return this;
		}

		Fields putIfTrue(String key, boolean condition) {
			if (condition) {
				map.put(key, true);
			}
			return this;
		}

		Fields putCapped(String key, List<Map<String, Object>> list) {
			capped(map, key, list);
			return this;
		}

		Map<String, Object> build() {
			return map;
		}
	}

	/**
	 * A consolidate list in the reply: the first {@value #LIST_CAP} entries, newest first as the engine orders them,
	 * and {@code <key>_more} with the count of the rest, which the report page holds. The model's context is not the
	 * place for a store's whole backlog (2026-09-27).
	 */
	static void capped(Map<String, Object> out, String key, List<Map<String, Object>> list) {
		if (list.size() <= LIST_CAP) {
			out.put(key, list);
			return;
		}
		out.put(key, list.subList(0, LIST_CAP));
		out.put(key + "_more", list.size() - LIST_CAP);
	}

	// ── typed view records ───────────────────────────────────────────────

	record FactSummary(String id, String predicate, String standing, String rendering, String direction) {
		Map<String, Object> toMap() {
			return new Fields().put("id", id).put("predicate", predicate).put("standing", standing)
					.put("rendering", rendering).putIfNotNull("direction", direction).build();
		}
	}

	record ChangeView(String kind, String reason, String by, String event, String observation, String closedAt,
			String recordedAt) {
		static ChangeView from(Supersession x) {
			return new ChangeView(x.kind(), x.reason(), x.supersededById() == null ? null : "f-" + x.supersededById(),
					x.eventId() == null ? null : "evt-" + x.eventId(),
					x.observationId() == null ? null : "obs-" + x.observationId(), x.closedAt(), x.recordedAt());
		}

		Map<String, Object> toMap() {
			return new Fields().put("kind", kind).put("reason", reason).put("by", by).put("event", event)
					.put("observation", observation).put("closed_at", closedAt).put("recorded_at", recordedAt).build();
		}
	}

	record RepeatMemoView(String session, String connection, int sessions, long writes, String lastRead) {
		Map<String, Object> toMap() {
			return new Fields().put("session", session).put("connection", connection).put("sessions", sessions)
					.put("writes", writes).put("last_read", lastRead).build();
		}
	}

	record StatusSnapshot(long schemaVersion, String ownerName, List<String> ownerAliases, long observations,
			long observationsRetired, long facts, long derivedFacts, int predicates, long pendingProposals,
			List<String> pendingProposalIds, long openQuestions, long vectors, long missingObservations,
			long missingFacts) {
	}

	record RebuiltView(int observations, int corrections, int answers, List<String> unmatched, List<String> noOps,
			long factsBefore, long factsAfter, long openQuestionsBefore, List<String> questions) {
		static RebuiltView from(Engine.Rebuilt r) {
			return new RebuiltView(r.observations(), r.corrections(), r.answers(), r.unmatched(), r.noOps(),
					r.factsBefore(), r.factsAfter(), r.openQuestionsBefore(), r.questions());
		}

		Map<String, Object> toMap() {
			return new Fields().put("observations", observations).put("corrections", corrections)
					.put("answers", answers).put("unmatched", unmatched).put("no_op", noOps)
					.put("facts_before", factsBefore).put("facts_after", factsAfter)
					.put("open_questions_before", openQuestionsBefore).put("open_questions_after", questions.size())
					.put("questions", questions).build();
		}
	}

	// ── remember & attach views ──────────────────────────────────────────

	Map<String, Object> remember(RememberOutcome o, Proposal.Parsed parsed) {
		Applied a = o.applied();
		var warnings = new ArrayList<>(o.observation().warnings());
		if (parsed != null) {
			warnings.addAll(parsed.warnings());
		}
		warnings.addAll(a.warnings());

		Fields out = new Fields().put("observation_id", "obs-" + o.observation().observationId())
				.put("replayed", o.observation().replayed()).put("proposal_source", o.proposalSource())
				.put("duplicate_text", o.observation().duplicateText());
		putApplied(out, a);
		out.putIfNonEmpty("resolved", o.resolved());
		if (o.derived() != null && !o.derived().nothing()) {
			out.put("derived", o.derived().toMap());
		}
		out.put("warnings", warnings).put("pending_proposals", o.observation().pendingProposals());
		return out.build();
	}

	Map<String, Object> attached(long observationId, Engine.Reading reading, Proposal.Parsed parsed) {
		Applied a = reading.applied();
		Fields out = new Fields().put("observation_id", "obs-" + observationId);
		if (reading.replaced()) {
			out.put("replaced",
					new Fields().put("facts", reading.removed().facts()).put("events", reading.removed().events())
							.put("reopened_facts", reading.removed().reopened()).build());
		}
		putApplied(out, a);
		var warnings = new ArrayList<>(parsed.warnings());
		warnings.addAll(a.warnings());
		out.put("warnings", warnings).put("pending_proposals", engine.observations().pendingProposals());
		return out.build();
	}

	private void putApplied(Fields out, Applied a) {
		Map<String, Object> stored = new Fields()
				.put("entities", a.entities().stream().map(ToolViews::entityOutMap).toList())
				.put("events", a.events().stream().map(ToolViews::eventOutMap).toList())
				.put("facts", a.facts().stream().map(this::factOutMap).toList()).build();
		out.put("stored", stored);
		if (!a.predicates().isEmpty()) {
			out.put("predicates", a.predicates().stream().map(ToolViews::predicateOutMap).toList());
		}
		out.putIfNonEmpty("definitions", a.definitions());
		out.put("superseded", a.superseded()).put("questions", a.questions());
	}

	private static Map<String, Object> entityOutMap(EntityOut e) {
		return Map.of("ref", e.ref(), "id", e.id(), "name", e.name(), "resolution", e.resolution(), "score", e.score());
	}

	private static Map<String, Object> eventOutMap(EventOut e) {
		return Map.of("ref", e.ref(), "id", e.id(), "type", e.type());
	}

	private static Map<String, Object> predicateOutMap(PredicateOut p) {
		return Map.of("proposed", p.proposed(), "resolution", p.resolution(), "id", p.id());
	}

	private Map<String, Object> factOutMap(FactOut f) {
		long factId = MnemicTools.parseId(f.id(), "f-");
		String direction = engine.facts().get(factId).map(this::otherWayRound).orElse(null);
		return new Fields().put("id", f.id()).put("predicate", f.predicate()).put("rendering", f.rendering())
				.put("standing", standingOf(factId)).put("corroborated", f.corroborated())
				.putIfNotNull("direction", direction).build();
	}

	// ── correct views ────────────────────────────────────────────────────

	Map<String, Object> correctedFact(Corrected c, String reason) {
		Fields out = new Fields().put("original", factSummary(c.original(), false).toMap());
		switch (c.kind()) {
		case "retracted" ->
			out.put("retracted", true).put("reason", reason == null ? "never true" : reason).put("replacement", null);
		case "retired" ->
			// Retired: the statement steps back behind the derived fact that covers it (superseded by it).
			out.put("retired", true).put("superseded_by", c.replacement() == null ? null : c.replacement().ref())
					.put("covered_by", c.replacement() == null ? null : factSummary(c.replacement(), false).toMap())
					.put("reason", reason == null ? "a derivation covers it" : reason);
		default -> out.put("replacement", factSummary(c.replacement(), true).toMap());
		}
		return out.build();
	}

	Map<String, Object> reinstatedObservation(Observation o) {
		return new Fields().put("reinstated", o.ref())
				.put("pending_proposals", engine.observations().pendingProposals()).build();
	}

	Map<String, Object> retiredObservation(Observation o, List<String> citing) {
		Fields out = new Fields().put("retired", o.ref()).put("reason", o.retiredReason())
				.put("superseded_by", o.supersededBy() == null ? null : "obs-" + o.supersededBy())
				.put("facts_citing", citing);
		if (!citing.isEmpty()) {
			out.put("note", ToolDescriptions.FACTS_CITING_NOTE);
		}
		out.put("pending_proposals", engine.observations().pendingProposals());
		return out.build();
	}

	// ── consolidate view ─────────────────────────────────────────────────

	Map<String, Object> consolidate(Consolidation c, boolean dryRun, List<String> findings, Path reportPath) {
		Fields out = new Fields().put("dry_run", dryRun).put("findings", findings).put("report", reportPath.toString())
				.putCapped("merges", c.merges()).putCapped("name_collisions", c.nameCollisions())
				.put("reclosed_facts", c.reclosed()).put("pending_proposals", c.pendingProposals())
				.putCapped("proposed", c.proposed()).putCapped("resolved_questions", c.resolvedQuestions())
				.putCapped("backlog", c.backlog()).putCapped("open_questions", c.openQuestions())
				.putCapped("inferred_vocabulary", c.inferredVocabulary())
				.putCapped("similar_vocabulary", c.similarVocabulary())
				.putCapped("descriptive_events", c.descriptiveEvents())
				.putCapped("unused_vocabulary", c.unusedVocabulary())
				.putCapped("unresolved_derivations", c.unresolvedDerivations())
				.putCapped("misfiled_relations", c.misfiledRelations())
				.putCapped("attribute_unknown", c.attributeUnknown()).put("removed_entities", c.removedEntities());
		if (c.rebuilt() != null) {
			out.put("rebuilt", RebuiltView.from(c.rebuilt()).toMap());
		}
		out.putCapped("review", c.review()).put("retired", c.retired()).put("embedded", c.embedded())
				.putCapped("duplicates", c.duplicates()).putCapped("repairs", c.repairs())
				.putIfNonEmpty("not_retired", c.notRetired());
		return out.build();
	}

	// ── status view ──────────────────────────────────────────────────────

	Map<String, Object> status(MnemicConfig config, RepeatMemoView memo) {
		Embedder emb = engine.embedder();
		// Share one read transaction across all status queries to avoid ~12 BEGIN/ROLLBACK round-trips.
		StatusSnapshot snap = engine.database().read(tx -> {
			long schemaVersion = engine.database().schemaVersion();
			var owner = engine.entities().owner();
			List<String> ownerAliases = engine.entities().aliases(owner.id()).stream()
					.filter(a -> !SELF_ALIASES.contains(a)).distinct().toList();
			var obsCounts = engine.observations().counts();
			var factCounts = engine.facts().counts();
			int predicateCount = engine.predicates().all().size();
			List<String> pendingIds = obsCounts.pendingProposals() > 0
					? engine.observations().pendingProposalIds(25).stream().map(id -> "obs-" + id).toList() : List.of();
			long openQuestions = engine.questions().openCount();
			long vectors = emb != null ? engine.vectors().count(emb.id()) : 0;
			long missingObs = emb != null ? engine.vectors().missingObservationCount(emb.id()) : 0;
			long missingFacts = emb != null ? engine.vectors().missingFactCount(emb.id()) : 0;
			return new StatusSnapshot(schemaVersion, owner.name(), ownerAliases, obsCounts.total(), obsCounts.retired(),
					factCounts.stated(), factCounts.derived(), predicateCount, obsCounts.pendingProposals(), pendingIds,
					openQuestions, vectors, missingObs, missingFacts);
		});

		EmbedderHolder holder = engine.embedderHolder();
		EmbedderHolder.State st = holder.state();
		Fields out = new Fields().put("version", engine.version()).put("repeat_memo", memo.toMap())
				.put("home", engine.home().toAbsolutePath().toString()).put("schema_version", snap.schemaVersion())
				.put("owner", snap.ownerName()).put("language", engine.lang().code())
				.put("owner_aliases", snap.ownerAliases()).put("observations", snap.observations())
				.put("observations_retired", snap.observationsRetired()).put("facts", snap.facts())
				.put("derived_facts", snap.derivedFacts()).put("predicates", snap.predicates())
				.put("pending_proposals", snap.pendingProposals())
				.put("pending_proposal_ids", snap.pendingProposalIds());
		if (snap.pendingProposals() > 0) {
			out.put("pending_proposals_note", ToolDescriptions.PENDING_PROPOSALS_NOTE);
		}
		out.put("proposer", engine.proposer() == null ? "assistant" : engine.proposer().id())
				.put("proposer_mode", engine.proposer() == null ? null : engine.proposer().mode().name().toLowerCase())
				.put("open_questions", snap.openQuestions()).put("model_providers", ModelProvider.available())
				.put("ort", ortDescription(config, holder, emb)).put("ort_bundled", OrtLibrary.describe())
				.put("channels", channelsView(st)).put("embedder", embedderView(holder, st, emb, snap));
		return out.build();
	}

	private static String ortDescription(MnemicConfig config, EmbedderHolder holder, Embedder emb) {
		if (config.ortLibrary().isPresent()) {
			return OrtProbe.describe(config.ortLibrary().get()) + (emb != null ? ", in use by the embedder" : "");
		}
		if (holder.library() != null) {
			return "ONNX Runtime " + (emb != null ? emb.runtimeVersion() + " " : "") + "at " + holder.library()
					+ " (written from the build)";
		}
		return "not loaded yet (the embedder's runtime; see embedder.state)";
	}

	private static Map<String, Object> channelsView(EmbedderHolder.State st) {
		String semantic = switch (st.state()) {
		case "ready" -> "on";
		case "downloading" ->
			"downloading " + st.percent() + "% (" + (st.received() >> 20) + " of " + (st.total() >> 20) + " MB)";
		case "loading" -> "loading the model";
		case "failed" -> "failed: " + st.detail();
		default -> "off (" + st.detail() + ")";
		};
		return new Fields().put("structured", "on").put("keys", "on").put("lexical", "on").put("semantic", semantic)
				.build();
	}

	private static Map<String, Object> embedderView(
		EmbedderHolder holder, EmbedderHolder.State st, Embedder emb, StatusSnapshot snap) {
		Fields m = new Fields().put("state", st.state());
		if ("downloading".equals(st.state())) {
			m.put("percent", st.percent()).put("received_mb", st.received() >> 20).put("total_mb", st.total() >> 20);
		}
		m.put("detail", st.detail()).putIfNonEmpty("files", holder.files()).putIfNotNull("library",
				holder.library() == null ? null : holder.library().toString());
		if (emb != null) {
			// Rows still without a vector: non-zero while the backfill runs, or after a consolidate was skipped.
			Map<String, Object> backlog = new Fields().put("observations", snap.missingObservations())
					.put("facts", snap.missingFacts()).build();
			m.put("model", emb.id()).put("dims", emb.dims()).put("runtime", emb.runtimeVersion())
					.put("ms", emb.probeMs()).put("vectors", snap.vectors()).put("backlog", backlog);
		}
		return m.build();
	}

	// ── inspect views ────────────────────────────────────────────────────

	Map<String, Object> entity(Entity e) {
		List<Fact> entityFacts = engine.facts().factsOf(e.id());
		// What the entity is, in one value each: its current facts under functional predicates that take a literal.
		var attributes = new LinkedHashMap<String, Object>();
		for (Fact f : entityFacts) {
			if (f.current() && f.subjectId() == e.id() && f.scopeId() == null && f.objectText() != null
					&& engine.predicates().get(f.predicate()).map(p -> p.functional() && p.functionalScope() == null)
							.orElse(false)) {
				attributes.putIfAbsent(f.predicate(), f.objectText());
			}
		}
		var facts = new ArrayList<Map<String, Object>>();
		for (Fact f : entityFacts) {
			Fields m = new Fields().put("id", f.ref()).put("predicate", f.predicate()).put("rendering", f.rendering())
					.put("standing", standing(f)).put("derivation", f.derivationKind());
			putProvenance(m, f);
			m.put("corroborations", f.corroborations()).put("last_confirmed", f.lastConfirmed());
			facts.add(m.build());
		}
		return new Fields().put("id", e.ref()).put("name", e.name()).put("type", e.type())
				.put("aliases", engine.entities().aliases(e.id())).putIfNonEmpty("attributes", attributes)
				.put("facts", facts).put("events", engine.events().eventsOf(e.id()).stream().map(this::event).toList())
				.build();
	}

	Map<String, Object> entityHistory(Entity e, String predicate) {
		FactQueries.History h = engine.history(e.id(), predicate);
		var entries = new ArrayList<Map<String, Object>>();
		for (var entry : h.entries()) {
			Map<String, Object> m = factDetail(entry.fact());
			m.put("changes", entry.supersessions().stream().map(x -> ChangeView.from(x).toMap()).toList());
			entries.add(m);
		}
		return new Fields()
				.put("entity",
						Map.of("id", e.ref(), "name", e.name()))
				.put("facts", entries).put("tombstones", h.tombstones().stream().map(t -> Map.of("kind", "forgotten",
						"observation", "obs-" + t.observationId(), "forgotten_at", t.forgottenAt())).toList())
				.build();
	}

	Map<String, Object> fact(long id) {
		Fact f = engine.facts().get(id).orElseThrow(() -> MnemicException.notFound("No fact f-" + id));
		Map<String, Object> m = factDetail(f);
		m.put("subject", Map.of("id", "ent-" + f.subjectId(), "name", engine.entities().nameOf(f.subjectId())));
		m.put("object", f.objectId() != null
				? Map.of("id", "ent-" + f.objectId(), "name", engine.entities().nameOf(f.objectId())) : f.objectText());
		m.put("qualifier", f.qualifier());
		m.put("scope", f.scopeId() == null ? null : engine.entities().nameOf(f.scopeId()));
		m.put("mode", f.mode());
		m.put("corroborations", f.corroborations());
		m.put("last_confirmed", f.lastConfirmed());
		m.put("caller_confidence", f.callerConfidence());
		m.put("confidence", engine.facts().confidence(f));
		m.put("changes", engine.facts().supersessionsOf(f.id()).stream().map(x -> ChangeView.from(x).toMap()).toList());
		return m;
	}

	private Map<String, Object> factDetail(Fact f) {
		Fields m = new Fields().put("id", f.ref()).put("predicate", f.predicate()).put("rendering", f.rendering())
				.put("standing", standing(f)).put("valid_start", f.validStart()).put("valid_end", f.validEnd())
				.put("start_source", f.startSource()).put("end_source", f.endSource())
				.put("derivation", f.derivationKind());
		Map<String, Object> derivation = engine.deriver().derivationOf(f.id());
		if (!derivation.isEmpty()) {
			m.put("derived".equals(f.derivationKind()) ? "derived_by" : "corroborated_by", derivation);
		}
		m.putIfNotNull("unification", engine.deriver().unificationOf(f));
		putProvenance(m, f);
		m.put("superseded_by", f.supersededBy() == null ? null : "f-" + f.supersededBy());
		return m.build();
	}

	Map<String, Object> observation(long id) {
		Observation o = engine.observations().get(id)
				.orElseThrow(() -> MnemicException.notFound("No observation obs-" + id));
		Fields m = new Fields().put("id", o.ref()).put("source", o.source().kind());
		if (o.source().ref() != null) {
			m.put("source_ref", o.source().ref() + (o.source().chunk() != null ? "#" + o.source().chunk() : ""));
		}
		m.put("observed_at", o.observedAt().toString()).put("recorded_at", o.recordedAt().toString())
				.put("forgotten", o.forgotten())
				.put("has_reading", o.proposalJson() != null && !"{}".equals(o.proposalJson()));
		if (o.retired()) {
			m.put("retired", true).put("retired_reason", o.retiredReason()).put("superseded_by",
					o.supersededBy() == null ? null : "obs-" + o.supersededBy());
		}
		m.put("text", o.text())
				.put("facts", engine.facts().factsOfObservation(id).stream()
						.map(f -> Map.of("id", f.ref(), "rendering", f.rendering(), "standing", standing(f))).toList())
				.put("events", engine.events().eventsOfObservation(id).stream().map(this::event).toList());
		return m.build();
	}

	Map<String, Object> event(Event ev) {
		return new Fields().put("id", ev.ref()).put("type", ev.type()).putIfNotNull("detail", ev.detail())
				.put("rendering", ev.rendering())
				.put("participants",
						ev.participants().stream()
								.map(id -> new Fields().put("id", "ent-" + id).put("name", engine.entities().nameOf(id))
										.putIfNotNull("role", ev.roleOf(id)).build())
								.toList())
				.put("valid_start", ev.validStart()).put("observation", "obs-" + ev.observationId())
				.put("observations", engine.events().observationsOf(ev.id()).stream().map(o -> "obs-" + o).toList())
				.build();
	}

	// ── registry views ───────────────────────────────────────────────────

	Map<String, Object> registry() {
		return new Fields().put("predicates", engine.predicates().all().stream().map(this::predicateMap).toList())
				.put("groups", engine.predicates().groups().stream().map(this::groupMap).toList())
				.put("event_types", engine.eventTypes().all().stream().map(ToolViews::eventTypeMap).toList())
				.put("entity_types", engine.entityTypes().all().stream().map(ToolViews::entityTypeMap).toList())
				.build();
	}

	Map<String, Object> groupEntry(String name) {
		PredicateRegistry.Group g = engine.predicates().group(name)
				.orElseThrow(() -> MnemicException.notFound("No group " + name));
		Map<String, Object> m = groupMap(g);
		m.put("changes", engine.predicates().groupChanges(g.name()));
		return m;
	}

	/** A group: its words, the groups it belongs to, and its members, the predicates in groups under it included. */
	private Map<String, Object> groupMap(PredicateRegistry.Group g) {
		return new Fields().put("id", "group:" + g.name()).put("name", g.name()).putAll(g.toMap())
				.put("members", engine.predicates().membersOf(g.name()).stream().map(Predicate::name).toList())
				.put("origin", g.seed() ? "seed" : "defined").build();
	}

	Map<String, Object> predicateEntry(String name) {
		Predicate p = engine.predicates().get(name).orElseThrow(() -> MnemicException.notFound("No predicate " + name));
		Map<String, Object> m = predicateMap(p);
		m.put("changes", engine.predicates().changes(p.name()));
		return m;
	}

	Map<String, Object> eventTypeEntry(String name) {
		EventTypeRegistry.EventType t = engine.eventTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No event type " + name));
		Map<String, Object> m = eventTypeMap(t);
		m.put("changes", engine.eventTypes().changes(t.name()));
		return m;
	}

	Map<String, Object> entityTypeEntry(String name) {
		EntityTypeRegistry.EntityType t = engine.entityTypes().get(name)
				.orElseThrow(() -> MnemicException.notFound("No entity type " + name));
		Map<String, Object> m = entityTypeMap(t);
		m.put("changes", engine.entityTypes().changes(t.name()));
		return m;
	}

	private Map<String, Object> predicateMap(Predicate p) {
		List<Rule> rules = engine.predicates().rulesOf(p.name());
		return new Fields().put("id", "pred:" + p.name()).put("name", p.name()).put("description", p.description())
				.putIfNonEmpty("defined_as", rules.stream().map(Rule::toMap).toList())
				.putIfNonEmpty("implies", engine.predicates().impliesOf(p.name()))
				.putIfNonEmpty("ends", engine.predicates().endsOf(p.name())).put("domain", p.domain())
				.put("range", p.range()).put("functional", p.functional()).putIfTrue("containment", p.containment())
				.putIfNotNull("functional_scope", p.functionalScope()).put("symmetric", p.symmetric())
				.put("volatility", p.volatility()).put("lasting", p.lasting())
				.putIfTrue("lasting_assumed", !p.lastingStated()).putIfNonEmpty("qualifiers", p.qualifiers())
				.put("render", p.render()).putIfNonEmpty("aliases", p.aliases()).put("lexicon", p.lexicon())
				.putIfNonEmpty("groups", p.groups())
				.put("origin", p.seed() ? "seed" : p.isInferred() ? "inferred" : "defined")
				.putIfNotNull("defined_by", p.definedBy() == null ? null : "obs-" + p.definedBy()).build();
	}

	private static Map<String, Object> eventTypeMap(EventTypeRegistry.EventType t) {
		return new Fields().put("id", "event:" + t.name()).put("name", t.name()).put("description", t.description())
				.putIfNonEmpty("opens", t.opens()).putIfNonEmpty("closes", t.closes())
				.putIfNonEmpty("supersedes", t.supersedes()).putIfTrue("ends_entity", t.endsEntity())
				.putIfNotNull("render", t.render()).put("lexicon", t.lexicon())
				.put("origin", t.seed() ? "seed" : t.inferred() ? "inferred" : "defined")
				.putIfNotNull("defined_by", t.definedBy() == null ? null : "obs-" + t.definedBy()).build();
	}

	private static Map<String, Object> entityTypeMap(EntityTypeRegistry.EntityType t) {
		return new Fields().put("id", "type:" + t.name()).put("name", t.name()).put("description", t.description())
				.putIfNotNull("parent", t.parent()).putIfNonEmpty("synonyms", t.synonyms())
				.putIfNonEmpty("type_words", t.typeWords()).putIfNonEmpty("kinds", t.kinds())
				.putIfTrue("disjoint", t.disjoint())
				.put("origin", t.seed() ? "seed" : t.inferred() ? "inferred" : "defined")
				.putIfNotNull("defined_by", t.definedBy() == null ? null : "obs-" + t.definedBy()).build();
	}

	// ── shared helpers ───────────────────────────────────────────────────

	/**
	 * Where a fact stands, in one word: by its dates when the record still holds it (current, ended, future), else what
	 * the record did to it (superseded, corrected, pending, rejected).
	 */
	private String standing(Fact f) {
		return "current".equals(f.status()) ? f.state(engine.clock().instant()) : f.status();
	}

	private String standingOf(long factId) {
		return engine.facts().get(factId).map(this::standing).orElse(null);
	}

	private FactSummary factSummary(Fact f, boolean withDirection) {
		return new FactSummary(f.ref(), f.predicate(), standing(f), f.rendering(),
				withDirection ? otherWayRound(f) : null);
	}

	/** The observations behind a fact: its home, every one that stated or corroborated it, and those since retired. */
	private void putProvenance(Fields m, Fact f) {
		List<Long> observations = engine.facts().observationsOf(f.id());
		m.put("observation", "obs-" + f.observationId());
		m.put("observations", observations.stream().map(o -> "obs-" + o).toList());
		Set<Long> retired = engine.observations().retiredAmong(observations);
		if (!retired.isEmpty()) {
			m.put("observations_retired", retired.stream().sorted().map(o -> "obs-" + o).toList());
		}
	}

	/**
	 * For a fact that could stand the other way round, the call that switches it, or null. The relation has a direction
	 * (an entity on each side, not symmetric) and its domain and range admit the swap, which is read from the registry,
	 * so a predicate the user defined or one registered from first use is covered as the seed kinship is. In the usage
	 * bench two of three models wrote "my father is Konrad" with the parent as the object; the rendering told them it
	 * was wrong, and they spent every call restating the values on record because nothing told them the move
	 * (2026-09-24).
	 */
	private String otherWayRound(Fact f) {
		if (f.objectId() == null) {
			return null;
		}
		Predicate p = engine.predicates().get(f.predicate()).orElse(null);
		if (p == null || p.symmetric()) {
			return null;
		}
		Entity subject = engine.entities().get(f.subjectId()).orElse(null);
		Entity object = engine.entities().get(f.objectId()).orElse(null);
		if (subject == null || object == null || subject.id() == object.id()) {
			return null;
		}
		EntityTypeRegistry types = engine.entityTypes();
		if (!p.acceptsSubject(types.lineage(object.type())) || !p.acceptsObject(types.lineage(subject.type()))) {
			return null;
		}
		return "to switch to the other way around use correct(\"" + f.ref() + "\", {\"subject\": \"" + object.name()
				+ "\", \"object\": \"" + subject.name() + "\"})";
	}
}
