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

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.OrtLibrary;
import se.hirt.mnemic.embed.OrtProbe;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.recall.RecallResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The MCP tool surface over the {@link Engine}: argument parsing, the JSON shape of each reply, and nothing else.
 * Descriptions live in {@link ToolDescriptions}.
 */
public class MnemicTools {

	private static final Set<String> SELF_ALIASES = Set.of("I", "me", "my", "myself", "self", "the user");

	@Inject
	Engine engine;

	@Inject
	MnemicConfig config;

	@Tool(name = "remember", description = ToolDescriptions.REMEMBER, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	ToolResponse remember(@ToolArg(description = "The observation, verbatim. Required.")
	String text, @ToolArg(description = "user | assistant | conversation | document | connector (default user)")
	Optional<String> source_kind,
		@ToolArg(description = "Document path, message id, or conversation id the text came from")
		Optional<String> source_ref,
		@ToolArg(description = "Chunk index within source_ref when a larger source was split")
		Optional<Integer> source_chunk, @ToolArg(description = "Your session id, if you have one")
		Optional<String> session,
		@ToolArg(description = "When this was said, ISO-8601 instant or date (default now). Use the conversation "
				+ "date when replaying older history.")
		Optional<String> observed_at, @ToolArg(required = false, description = ToolDescriptions.REMEMBER_PROPOSAL)
		Map<String, Object> proposal,
		@ToolArg(description = "Extraction spec version the proposal follows (current: 1)")
		Optional<Integer> spec_version,
		@ToolArg(description = "Client-generated key; repeating a call with the same key returns the same id")
		Optional<String> idempotency_key, @ToolArg(required = false, description = ToolDescriptions.REMEMBER_RESOLVE)
		List<Map<String, Object>> resolve) {
		return ToolSupport.json("remember", () -> {
			Source source = new Source(source_kind.orElse("user"), source_ref.orElse(null), source_chunk.orElse(null),
					null, session.orElse(null));
			List<Resolve> resolves = (resolve == null ? List.<Map<String, Object>> of() : resolve).stream().map(m -> {
				Object id = m.containsKey("question_id") ? m.get("question_id") : m.get("question");
				if (id == null) {
					throw MnemicException.invalidArgument("Each resolve entry needs 'question_id' (q-N) and 'choice'.");
				}
				return new Resolve(String.valueOf(id),
						m.get("choice") == null ? null : String.valueOf(m.get("choice")));
			}).toList();
			Proposal.Parsed parsed = Engine.proposalWithWarnings(proposal);
			RememberOutcome o = engine.remember(text, source, observed_at.map(MnemicTools::instant).orElse(null),
					parsed == null ? null : parsed.proposal(), spec_version.orElse(null), idempotency_key.orElse(null),
					resolves);
			Applied a = o.applied();
			var out = new LinkedHashMap<String, Object>();
			out.put("observation_id", "obs-" + o.observation().observationId());
			out.put("replayed", o.observation().replayed());
			out.put("proposal_source", o.proposalSource());
			out.put("duplicate_text", o.observation().duplicateText());
			out.put("stored", stored(a));
			out.put("predicates", a.predicates().stream()
					.map(p -> Map.of("proposed", p.proposed(), "resolution", p.resolution(), "id", p.id())).toList());
			out.put("superseded", a.superseded());
			out.put("questions", a.questions());
			if (!a.definitions().isEmpty()) {
				out.put("definitions", a.definitions());
			}
			if (!a.suggestions().isEmpty()) {
				out.put("suggestions", a.suggestions());
			}
			if (!o.resolved().isEmpty()) {
				out.put("resolved", o.resolved());
			}
			var warnings = new ArrayList<>(o.observation().warnings());
			if (parsed != null) {
				warnings.addAll(parsed.warnings());
			}
			warnings.addAll(a.warnings());
			out.put("warnings", warnings);
			out.put("pending_proposals", o.observation().pendingProposals());
			return out;
		});
	}

	@Tool(name = "recall", description = ToolDescriptions.RECALL, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse recall(
		@ToolArg(description = "The question or topic, in natural language. Omit for the session briefing.")
		Optional<String> query,
		@ToolArg(description = "Knowledge as it stood at this ISO date/instant: facts by their valid time, "
				+ "observations by when they were observed")
		Optional<String> as_of, @ToolArg(description = "Token budget for the returned block (default 800)")
		Optional<Integer> max_tokens, @ToolArg(description = "Maximum number of items (default 10)")
		Optional<Integer> limit,
		@ToolArg(description = "Also return ended and superseded facts (default false); past tense in the "
				+ "question usually means yes")
		Optional<Boolean> include_history) {
		return ToolSupport.text("recall", () -> {
			if (query.isEmpty() || query.get().isBlank()) {
				return engine.briefing(max_tokens.orElse(800));
			}
			RecallResult r = engine.recall().recall(query.get(), as_of.map(MnemicTools::instant).orElse(null),
					max_tokens.orElse(800), limit.orElse(10), include_history.orElse(false));
			return r.text();
		});
	}

	@Tool(name = "history", description = ToolDescriptions.HISTORY, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse history(@ToolArg(description = "Name, alias, or id (ent-N) of the entity")
	String entity, @ToolArg(description = "Only this predicate, e.g. works_at")
	Optional<String> predicate) {
		return ToolSupport.json("history", () -> {
			Entity e = engine.entities().byRef(entity)
					.orElseThrow(() -> MnemicException.notFound("No entity matches '" + entity + "'."));
			var h = engine.history(e.id(), predicate.orElse(null));
			var out = new LinkedHashMap<String, Object>();
			out.put("entity", Map.of("id", e.ref(), "name", e.name()));
			var entries = new ArrayList<Map<String, Object>>();
			for (var entry : h.entries()) {
				Fact f = entry.fact();
				var m = new LinkedHashMap<String, Object>();
				m.put("id", f.ref());
				m.put("predicate", f.predicate());
				m.put("rendering", f.rendering());
				m.put("status", f.status());
				m.put("state", f.state(engine.clock().instant()));
				m.put("valid_start", f.validStart());
				m.put("valid_end", f.validEnd());
				m.put("start_source", f.startSource());
				m.put("end_source", f.endSource());
				m.put("derivation", f.derivationKind());
				provenance(m, f);
				m.put("superseded_by", f.supersededBy() == null ? null : "f-" + f.supersededBy());
				m.put("changes", entry.supersessions().stream().map(x -> {
					var c = new LinkedHashMap<String, Object>();
					c.put("kind", x.kind());
					c.put("reason", x.reason());
					c.put("by", x.supersededById() == null ? null : "f-" + x.supersededById());
					c.put("event", x.eventId() == null ? null : "evt-" + x.eventId());
					c.put("observation", x.observationId() == null ? null : "obs-" + x.observationId());
					c.put("closed_at", x.closedAt());
					c.put("recorded_at", x.recordedAt());
					return c;
				}).toList());
				entries.add(m);
			}
			out.put("facts", entries);
			out.put("tombstones", h.tombstones().stream().map(t -> Map.of("kind", "forgotten", "observation",
					"obs-" + t.observationId(), "forgotten_at", t.forgottenAt())).toList());
			return out;
		});
	}

	@Tool(name = "correct", description = ToolDescriptions.CORRECT, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse correct(@ToolArg(description = "The fact to correct, e.g. f-12 (omit when correcting a predicate)")
	Optional<String> fact_id, @ToolArg(description = ToolDescriptions.CORRECT_REPLACEMENT)
	Map<String, Object> replacement, @ToolArg(description = "Why, in the user's words")
	Optional<String> reason, @ToolArg(description = "The predicate to correct instead of a fact, e.g. parent_of")
	Optional<String> predicate, @ToolArg(description = "The event type to correct instead of a fact, e.g. purchased")
	Optional<String> event_type, @ToolArg(description = "The entity type to correct instead of a fact, e.g. place")
	Optional<String> entity_type) {
		return ToolSupport.json("correct", () -> {
			if (predicate.isPresent()) {
				return engine.correctPredicate(predicate.get(), replacement, reason.orElse(null));
			}
			if (event_type.isPresent()) {
				return engine.correctEventType(event_type.get(), replacement, reason.orElse(null));
			}
			if (entity_type.isPresent()) {
				return engine.correctEntityType(entity_type.get(), replacement, reason.orElse(null));
			}
			if (fact_id.isEmpty()) {
				throw MnemicException.invalidArgument(
						"Pass 'fact_id' (f-12), 'predicate' (parent_of), 'event_type' (purchased), or 'entity_type' (place).");
			}
			Corrected c = engine.correct(parseId(fact_id.get(), "f-"), replacement, reason.orElse(null));
			var out = new LinkedHashMap<String, Object>();
			out.put("original", factSummary(c.original()));
			if (c.replacement() == null) {
				out.put("retracted", true);
				out.put("replacement", null);
			} else {
				out.put("replacement", factSummary(c.replacement()));
			}
			return out;
		});
	}

	@Tool(name = "propose", description = ToolDescriptions.PROPOSE, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	ToolResponse propose(@ToolArg(description = "The observation, e.g. obs-48")
	String observation_id,
		@ToolArg(description = "Structured proposal, as for remember: {entities, events, facts, predicates, closures}")
		Map<String, Object> proposal) {
		return ToolSupport.json("propose", () -> {
			Proposal.Parsed parsed = Engine.proposalWithWarnings(proposal);
			if (parsed == null) {
				throw MnemicException
						.invalidArgument("'proposal' is required: the structured reading of the observation.");
			}
			long id = parseId(observation_id, "obs-");
			Applied a = engine.propose(id, parsed.proposal());
			var out = new LinkedHashMap<String, Object>();
			out.put("observation_id", "obs-" + id);
			out.put("stored", stored(a));
			out.put("superseded", a.superseded());
			out.put("questions", a.questions());
			if (!a.definitions().isEmpty()) {
				out.put("definitions", a.definitions());
			}
			if (!a.suggestions().isEmpty()) {
				out.put("suggestions", a.suggestions());
			}
			var warnings = new ArrayList<>(parsed.warnings());
			warnings.addAll(a.warnings());
			out.put("warnings", warnings);
			out.put("pending_proposals", engine.observations().pendingProposals());
			return out;
		});
	}

	@Tool(name = "retire", description = ToolDescriptions.RETIRE, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse retire(@ToolArg(description = "The observation to retire, e.g. obs-51")
	String observation_id, @ToolArg(description = "Why, in the user's words")
	Optional<String> reason, @ToolArg(description = "The observation that supersedes it, e.g. obs-52")
	Optional<String> superseded_by,
		@ToolArg(description = "true to undo a retirement: the observation is live again (and back in pending_proposals if it never had a reading)")
		Optional<Boolean> undo) {
		return ToolSupport.json("retire", () -> {
			long id = parseId(observation_id, "obs-");
			var out = new LinkedHashMap<String, Object>();
			if (undo.orElse(false)) {
				out.put("reinstated", engine.reinstateObservation(id).ref());
				out.put("pending_proposals", engine.observations().pendingProposals());
				return out;
			}
			Observation o = engine.retireObservation(id, reason.orElse(null),
					superseded_by.map(s -> parseId(s, "obs-")).orElse(null));
			out.put("retired", o.ref());
			out.put("reason", o.retiredReason());
			out.put("superseded_by", o.supersededBy() == null ? null : "obs-" + o.supersededBy());
			// The facts this observation produced stay, and cite it: the caller decides whether they stand.
			List<String> citing = engine.facts().factsOfObservation(id).stream().filter(Fact::current).map(Fact::ref)
					.toList();
			out.put("facts_citing", citing);
			if (!citing.isEmpty()) {
				out.put("note", ToolDescriptions.FACTS_CITING_NOTE);
			}
			out.put("pending_proposals", engine.observations().pendingProposals());
			return out;
		});
	}

	@Tool(name = "retract", description = ToolDescriptions.RETRACT, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse retract(@ToolArg(description = "The fact to withdraw, e.g. f-91")
	String fact_id, @ToolArg(description = "Why it was never true, in the user's words")
	Optional<String> reason) {
		return ToolSupport.json("retract", () -> {
			Corrected c = engine.correct(parseId(fact_id, "f-"), Map.of("wrong", true), reason.orElse(null));
			var out = new LinkedHashMap<String, Object>();
			out.put("retracted", factSummary(c.original()));
			out.put("reason", reason.orElse("never true"));
			return out;
		});
	}

	@Tool(name = "get_entity", description = ToolDescriptions.GET_ENTITY, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse getEntity(@ToolArg(description = "Name, alias, or id (ent-N) of the entity")
	String entity) {
		return ToolSupport.json("get_entity", () -> {
			Entity e = engine.entities().byRef(entity).orElseThrow(() -> MnemicException
					.notFound("No entity matches '" + entity + "'. Try recall with the name to see what is known."));
			var out = new LinkedHashMap<String, Object>();
			out.put("id", e.ref());
			out.put("name", e.name());
			out.put("type", e.type());
			out.put("aliases", engine.entities().aliases(e.id()));
			var facts = new ArrayList<Map<String, Object>>();
			for (Fact f : engine.facts().factsOf(e.id())) {
				var m = new LinkedHashMap<String, Object>();
				m.put("id", f.ref());
				m.put("predicate", f.predicate());
				m.put("rendering", f.rendering());
				m.put("status", f.status());
				m.put("derivation", f.derivationKind());
				provenance(m, f);
				m.put("corroborations", f.corroborations());
				m.put("last_confirmed", f.lastConfirmed());
				facts.add(m);
			}
			out.put("facts", facts);
			var events = new ArrayList<Map<String, Object>>();
			for (Event ev : engine.events().eventsOf(e.id())) {
				events.add(Map.of("id", ev.ref(), "type", ev.type(), "rendering", ev.rendering(), "observation",
						"obs-" + ev.observationId()));
			}
			out.put("events", events);
			return out;
		});
	}

	@Tool(name = "forget", description = ToolDescriptions.FORGET, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
	ToolResponse forget(@ToolArg(description = "The observation id, e.g. obs-12")
	String observation_id, @ToolArg(required = false, description = ToolDescriptions.FORGET_KEEP_ENTITIES)
	Optional<Boolean> keep_entities) {
		return ToolSupport.json("forget", () -> {
			long id = parseId(observation_id, "obs-");
			boolean removed = engine.forget(id, keep_entities.orElse(false));
			return Map.of("observation_id", "obs-" + id, "removed", removed, "kept_entities",
					keep_entities.orElse(false));
		});
	}

	@Tool(name = "consolidate", description = ToolDescriptions.CONSOLIDATE, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse consolidate(@ToolArg(description = "Report what would change without changing it (default false)")
	Optional<Boolean> dry_run, @ToolArg(required = false, description = ToolDescriptions.CONSOLIDATE_RETIRE)
	Optional<List<String>> retire) {
		return ToolSupport.json("consolidate", () -> {
			List<Long> retireIds = retire.orElse(List.of()).stream().map(r -> parseId(r, "obs-")).toList();
			var c = engine.consolidate(dry_run.orElse(false), retireIds);
			var out = new LinkedHashMap<String, Object>();
			out.put("dry_run", dry_run.orElse(false));
			out.put("merges", c.merges());
			out.put("reclosed_facts", c.reclosed());
			out.put("pending_proposals", c.pendingProposals());
			out.put("proposed", c.proposed());
			out.put("resolved_questions", c.resolvedQuestions());
			out.put("backlog", c.backlog());
			out.put("open_questions", c.openQuestions());
			out.put("suggested_registrations", c.suggestedRegistrations());
			out.put("review", c.review());
			out.put("retired", c.retired());
			out.put("embedded", c.embedded());
			out.put("duplicates", c.duplicates());
			return out;
		});
	}

	@Tool(name = "list_predicates", description = ToolDescriptions.LIST_PREDICATES, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse list_predicates() {
		return ToolSupport.json("list_predicates", () -> {
			var out = new LinkedHashMap<String, Object>();
			var preds = new ArrayList<Map<String, Object>>();
			for (var p : engine.predicates().all()) {
				var m = new LinkedHashMap<String, Object>();
				m.put("name", p.name());
				m.put("description", p.description());
				m.put("domain", p.domain());
				m.put("range", p.range());
				m.put("functional", p.functional());
				if (p.functionalScope() != null) {
					m.put("functional_scope", p.functionalScope());
				}
				m.put("symmetric", p.symmetric());
				m.put("volatility", p.volatility());
				if (!p.qualifiers().isEmpty()) {
					m.put("qualifiers", p.qualifiers());
				}
				m.put("render", p.render());
				if (!p.aliases().isEmpty()) {
					m.put("aliases", p.aliases());
				}
				m.put("lexicon", p.lexicon());
				m.put("origin", p.seed() ? "seed" : p.isExtended() ? "extended" : "defined");
				preds.add(m);
			}
			out.put("predicates", preds);
			var types = new ArrayList<Map<String, Object>>();
			for (var t : engine.eventTypes().all()) {
				var m = new LinkedHashMap<String, Object>();
				m.put("name", t.name());
				m.put("description", t.description());
				if (!t.opens().isEmpty()) {
					m.put("opens", t.opens());
				}
				if (!t.closes().isEmpty()) {
					m.put("closes", t.closes());
				}
				if (!t.supersedes().isEmpty()) {
					m.put("supersedes", t.supersedes());
				}
				if (t.endsEntity()) {
					m.put("ends_entity", true);
				}
				if (t.render() != null) {
					m.put("render", t.render());
				}
				m.put("origin", t.seed() ? "seed" : "defined");
				if (t.definedBy() != null) {
					m.put("defined_by", "obs-" + t.definedBy());
				}
				types.add(m);
			}
			out.put("event_types", types);
			var entityTypes = new ArrayList<Map<String, Object>>();
			for (var t : engine.entityTypes().all()) {
				var m = new LinkedHashMap<String, Object>();
				m.put("name", t.name());
				m.put("description", t.description());
				if (t.parent() != null) {
					m.put("parent", t.parent());
				}
				if (!t.synonyms().isEmpty()) {
					m.put("synonyms", t.synonyms());
				}
				if (!t.typeWords().isEmpty()) {
					m.put("type_words", t.typeWords());
				}
				m.put("origin", t.seed() ? "seed" : "defined");
				if (t.definedBy() != null) {
					m.put("defined_by", "obs-" + t.definedBy());
				}
				entityTypes.add(m);
			}
			out.put("entity_types", entityTypes);
			return out;
		});
	}

	@Tool(name = "status", description = ToolDescriptions.STATUS, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse status() {
		return ToolSupport.json("status", () -> {
			var out = new LinkedHashMap<String, Object>();
			out.put("version", engine.version());
			out.put("home", engine.home().toAbsolutePath().toString());
			out.put("schema_version", engine.database().schemaVersion());
			out.put("owner", engine.entities().owner().name());
			out.put("language", engine.lang().code());
			out.put("owner_aliases", engine.entities().aliases(engine.entities().owner().id()).stream()
					.filter(a -> !SELF_ALIASES.contains(a)).distinct().toList());
			out.put("observations", engine.observations().count());
			out.put("observations_retired", engine.observations().retiredCount());
			out.put("facts", engine.facts().count());
			out.put("predicates", engine.predicates().all().size());
			long pending = engine.observations().pendingProposals();
			out.put("pending_proposals", pending);
			out.put("pending_proposal_ids",
					engine.observations().pendingProposalIds(25).stream().map(id -> "obs-" + id).toList());
			if (pending > 0) {
				out.put("pending_proposals_note", ToolDescriptions.PENDING_PROPOSALS_NOTE);
			}
			out.put("proposer", engine.proposer() == null ? "assistant" : engine.proposer().id());
			out.put("proposer_mode", engine.proposer() == null ? null : engine.proposer().mode().name().toLowerCase());
			out.put("open_questions", engine.questions().openCount());
			out.put("model_providers", ModelProvider.available());
			String vec = engine.database().vecVersion();
			out.put("vec",
					vec == null
							? "scan (sqlite-vec not loaded; exact search over every vector, fine for a personal store)"
							: "sqlite-vec " + vec);
			EmbedderHolder holder = engine.embedderHolder();
			EmbedderHolder.State st = holder.state();
			Embedder emb = engine.embedder();
			if (config.ortLibrary().isPresent()) {
				out.put("ort",
						OrtProbe.describe(config.ortLibrary().get()) + (emb != null ? ", in use by the embedder" : ""));
			} else if (holder.library() != null) {
				out.put("ort", "ONNX Runtime " + (emb != null ? emb.runtimeVersion() + " " : "") + "at "
						+ holder.library() + " (written from the build)");
			} else {
				out.put("ort", "not loaded yet (the embedder's runtime; see embedder.state)");
			}
			out.put("ort_bundled", OrtLibrary.describe());
			var channels = new LinkedHashMap<String, Object>();
			channels.put("structured", "on");
			channels.put("keys", "on");
			channels.put("lexical", "on");
			channels.put("semantic", switch (st.state()) {
			case "ready" -> "on";
			case "downloading" ->
				"downloading " + st.percent() + "% (" + (st.received() >> 20) + " of " + (st.total() >> 20) + " MB)";
			case "loading" -> "loading the model";
			case "failed" -> "failed: " + st.detail();
			default -> "off (" + st.detail() + ")";
			});
			out.put("channels", channels);
			var m = new LinkedHashMap<String, Object>();
			m.put("state", st.state());
			if ("downloading".equals(st.state())) {
				m.put("percent", st.percent());
				m.put("received_mb", st.received() >> 20);
				m.put("total_mb", st.total() >> 20);
			}
			m.put("detail", st.detail());
			if (!holder.files().isEmpty()) {
				m.put("files", holder.files());
			}
			if (holder.library() != null) {
				m.put("library", holder.library().toString());
			}
			if (emb != null) {
				long t0 = System.nanoTime();
				int dims = emb.embed("status probe").length;
				m.put("model", emb.id());
				m.put("dims", dims);
				m.put("runtime", emb.runtimeVersion());
				m.put("ms", Math.round((System.nanoTime() - t0) / 1_000_00) / 10.0);
				m.put("vectors", engine.vectors().count(emb.id()));
				// Rows still without a vector: non-zero while the backfill runs, or after a consolidate was skipped.
				var backlog = new LinkedHashMap<String, Object>();
				backlog.put("observations", engine.vectors().missingObservationCount(emb.id()));
				backlog.put("facts", engine.vectors().missingFactCount(emb.id()));
				m.put("backlog", backlog);
			}
			out.put("embedder", m);
			return out;
		});
	}

	/** What a proposal stored: entities, events, and facts with their resolutions. */
	private static Map<String, Object> stored(Applied a) {
		var stored = new LinkedHashMap<String, Object>();
		stored.put("entities", a.entities().stream().map(e -> Map.of("ref", e.ref(), "id", e.id(), "name", e.name(),
				"resolution", e.resolution(), "score", e.score())).toList());
		stored.put("events",
				a.events().stream().map(e -> Map.of("ref", e.ref(), "id", e.id(), "type", e.type())).toList());
		stored.put("facts", a.facts().stream().map(f -> Map.of("id", f.id(), "predicate", f.predicate(), "rendering",
				f.rendering(), "status", f.status(), "corroborated", f.corroborated())).toList());
		return stored;
	}

	private static Map<String, Object> factSummary(Fact f) {
		return Map.of("id", f.ref(), "status", f.status(), "rendering", f.rendering());
	}

	/** The observations behind a fact: its home, every one that stated or corroborated it, and those since retired. */
	private void provenance(Map<String, Object> m, Fact f) {
		List<Long> observations = engine.facts().observationsOf(f.id());
		m.put("observation", "obs-" + f.observationId());
		m.put("observations", observations.stream().map(o -> "obs-" + o).toList());
		Set<Long> retired = engine.observations().retiredAmong(observations);
		if (!retired.isEmpty()) {
			m.put("observations_retired", retired.stream().sorted().map(o -> "obs-" + o).toList());
		}
	}

	static long parseId(String ref, String prefix) {
		try {
			return Long.parseLong(ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref);
		} catch (NumberFormatException e) {
			throw MnemicException.invalidArgument("'" + ref + "' is not an id like " + prefix + "12.");
		}
	}

	/** Accepts an ISO instant or a plain date; a date means the end of that day in UTC for {@code as_of}. */
	static Instant instant(String s) {
		try {
			return Instant.parse(s);
		} catch (DateTimeParseException ignored) {
			try {
				return LocalDate.parse(s).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
			} catch (DateTimeParseException e) {
				throw MnemicException.invalidArgument(
						"'" + s + "' is not an ISO-8601 instant or date. Examples: 2015-06-01, 2026-09-06T10:12:00Z");
			}
		}
	}
}
