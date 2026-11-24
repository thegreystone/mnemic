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

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.ToolViews.RepeatMemoView;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.Question;
import se.hirt.mnemic.knowledge.QuestionResolver.Resolve;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;
import se.hirt.mnemic.protocol.Protocol;
import se.hirt.mnemic.recall.RecallResult;
import se.hirt.mnemic.recall.RecallService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The MCP tool surface over the {@link Engine}: seven tools, one id scheme. Argument parsing, dispatch, and read-memo
 * deduplication live here; JSON reply shaping lives in {@link ToolViews} and descriptions live in
 * {@link ToolDescriptions}.
 */
public class MnemicTools {

	private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(MnemicTools.class);

	static final int LIST_CAP = ToolViews.LIST_CAP;

	@Inject
	Engine engine;

	/** The last read each session made, with the write generation it saw; see {@link #unlessRepeated}. */
	private final ConcurrentHashMap<String, String> lastRead = new ConcurrentHashMap<>();

	/** Bumped by every write, in any session, so a read after one is never a repeat. */
	private final AtomicLong writes = new AtomicLong();

	static final String REPEATED_NOTE = "(the same call as the one above, with nothing changed since: its result is "
			+ "the one already shown; reply to the user from it, or make a different call)";

	@Inject
	MnemicConfig config;

	private ToolViews views() {
		return new ToolViews(engine);
	}

	@Tool(name = "remember", description = ToolDescriptions.REMEMBER, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	ToolResponse remember(@ToolArg(description = "The observation, verbatim. Required unless observation_id is given.")
	Optional<String> text,
		@ToolArg(description = "An observation already stored without a reading (obs-48): the proposal is attached to "
				+ "it instead of a new text")
		Optional<String> observation_id,
		@ToolArg(description = "user | assistant | conversation | document | connector (default user). A connector "
				+ "observation is text only: give it its reading afterwards with remember(observation_id, proposal)")
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
		writes.incrementAndGet();
		return ToolSupport.json("remember", () -> {
			Proposal.Parsed parsed = Engine.proposalWithWarnings(proposal);
			List<Resolve> resolves = (resolve == null ? List.<Map<String, Object>> of() : resolve).stream().map(m -> {
				Object id = m.containsKey("question_id") ? m.get("question_id") : m.get("question");
				if (id == null) {
					throw MnemicException.invalidArgument("Each resolve entry needs 'question_id' (q-N) and 'choice'.");
				}
				return new Resolve(String.valueOf(id),
						m.get("choice") == null ? null : String.valueOf(m.get("choice")));
			}).toList();
			if (observation_id.isPresent()) {
				if (text.isPresent() && !text.get().isBlank()) {
					throw MnemicException.invalidArgument("Pass either 'text' (a new observation) or 'observation_id' "
							+ "(a reading for one already stored), not both.");
				}
				if (parsed == null) {
					throw MnemicException.invalidArgument("'proposal' is required with observation_id: the structured "
							+ "reading of the observation.");
				}
				// Answers given with a re-reading are given first, so that what they create (a new entity, an alias)
				// is there when the new reading resolves its names. Ignoring them silently sent one assistant round
				// the same question three times (2026-09-22).
				Set<Long> before = openQuestionIds();
				List<Map<String, Object>> answered = resolves.isEmpty() ? List.of() : engine.answer(resolves);
				long obsId = parseId(observation_id.get(), "obs-");
				Engine.Reading reading = engine.reread(obsId, parsed.proposal());
				Map<String, Object> out = views().attached(obsId, reading, parsed);
				if (!answered.isEmpty()) {
					out.put("resolved", answered);
				}
				questionsOpened(out, before);
				return out;
			}
			if ((text.isEmpty() || text.get().isBlank()) && parsed == null && !resolves.isEmpty()) {
				// Answers alone: nothing to observe, the answers live on the questions. What an answer opens (a held
				// fact that conflicts, a predicate it names) is reported here like any other question: an assistant
				// found one only through status (2026-09-23).
				Set<Long> before = openQuestionIds();
				var out = new LinkedHashMap<String, Object>();
				out.put("resolved", engine.answer(resolves));
				questionsOpened(out, before);
				out.put("pending_proposals", engine.observations().pendingProposals());
				return out;
			}
			Source source = new Source(source_kind.orElse("user"), source_ref.orElse(null), source_chunk.orElse(null),
					null, session.orElse(null));
			Set<Long> before = openQuestionIds();
			RememberOutcome o = engine.remember(text.orElse(null), source,
					observed_at.map(MnemicTools::instant).orElse(null), parsed == null ? null : parsed.proposal(),
					spec_version.orElse(null), idempotency_key.orElse(null), resolves);
			Map<String, Object> out = views().remember(o, parsed);
			questionsOpened(out, before);
			return out;
		});
	}

	/** The ids of the questions open now, taken before a call so that what the call opens can be reported. */
	private Set<Long> openQuestionIds() {
		return engine.questions().open(500).stream().map(Question::id).collect(Collectors.toSet());
	}

	/**
	 * Puts every question open now that was not open before the call under 'questions', beside those the reply already
	 * lists: one place to look, whatever opened them (a proposal, an answer applying a held fact, a correction).
	 */
	@SuppressWarnings("unchecked")
	private void questionsOpened(Map<String, Object> out, Set<Long> before) {
		var listed = new ArrayList<Map<String, Object>>();
		Object already = out.get("questions");
		if (already instanceof List<?> l) {
			l.forEach(q -> listed.add((Map<String, Object>) q));
		}
		Set<Object> ids = listed.stream().map(q -> q.get("id")).collect(Collectors.toSet());
		for (Question q : engine.questions().open(500)) {
			if (!before.contains(q.id()) && !ids.contains(q.ref())) {
				listed.add(q.toMap());
			}
		}
		if (!listed.isEmpty() || out.containsKey("questions")) {
			out.put("questions", listed);
		}
	}

	@Tool(name = "recall", description = ToolDescriptions.RECALL, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse recall(
		@ToolArg(description = "The question or topic, in natural language. Omit for the session briefing.")
		Optional<String> query,
		@ToolArg(description = "Knowledge as it stood at this ISO instant, date, month, or year (a coarse one means "
				+ "its end): facts by their valid time, observations by when they were observed")
		Optional<String> as_of,
		@ToolArg(description = "Token budget for the returned block (default " + RecallService.DEFAULT_MAX_TOKENS + ")")
		Optional<Integer> max_tokens, @ToolArg(description = "Maximum number of items (default 10)")
		Optional<Integer> limit,
		@ToolArg(description = "Also return ended and superseded facts (default false); past tense in the "
				+ "question usually means yes")
		Optional<Boolean> include_history, McpConnection connection) {
		return unlessRepeated(connection, "recall", List.of(query, as_of, max_tokens, limit, include_history),
				() -> ToolSupport.text("recall", () -> {
					if (query.isEmpty() || query.get().isBlank()) {
						return engine.briefing(max_tokens.orElse(RecallService.DEFAULT_MAX_TOKENS));
					}
					RecallResult r = engine.recall().recall(query.get(), as_of.map(MnemicTools::instant).orElse(null),
							max_tokens.orElse(RecallService.DEFAULT_MAX_TOKENS), limit.orElse(10),
							include_history.orElse(false));
					return r.text();
				}));
	}

	@Tool(name = "inspect", description = ToolDescriptions.INSPECT, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse inspect(
		@ToolArg(description = "ent-12, a name or alias, f-12, obs-12, evt-3, q-3, pred:works_at, event:joined, "
				+ "type:place, group:family, 'registry', 'guide', or 'report' (the page consolidate wrote)")
		String ref,
		@ToolArg(description = "For an entity: every fact that ever touched it, with its changes and tombstones "
				+ "(default false)")
		Optional<Boolean> history,
		@ToolArg(description = "For an entity with history: only this predicate, e.g. works_at")
		Optional<String> predicate, McpConnection connection) {
		return unlessRepeated(connection, "inspect", List.of(String.valueOf(ref), history, predicate),
				() -> ToolSupport.json("inspect", () -> {
					String r = ref == null ? "" : ref.trim();
					if (r.isEmpty()) {
						throw MnemicException
								.invalidArgument("'ref' is required: ent-12, a name, f-12, obs-12, evt-3, q-3, "
										+ "pred:works_at, event:joined, type:place, group:family, 'registry', 'guide', or 'report'.");
					}
					ToolViews v = views();
					if (r.equalsIgnoreCase("registry")) {
						return v.registry();
					}
					if (r.equalsIgnoreCase("guide")) {
						return Map.of("title", "Mnemic proposal guide", "text", Protocol.guide());
					}
					if (r.equalsIgnoreCase("report")) {
						String page = engine.report().orElseThrow(() -> MnemicException
								.notFound("No report yet: consolidate writes one (report.md in the data home)."));
						return Map.of("title", "Mnemic report", "path", engine.home().resolve(Report.FILE).toString(),
								"text", page);
					}
					if (r.startsWith("f-")) {
						return v.fact(parseId(r, "f-"));
					}
					if (r.startsWith("obs-")) {
						return v.observation(parseId(r, "obs-"));
					}
					if (r.startsWith("evt-")) {
						Event ev = engine.events().get(parseId(r, "evt-"))
								.orElseThrow(() -> MnemicException.notFound("No event " + r));
						return v.event(ev);
					}
					if (r.startsWith("q-")) {
						return engine.questions().get(parseId(r, "q-")).map(Question::toMap)
								.orElseThrow(() -> MnemicException.notFound("No question " + r));
					}
					if (r.startsWith("pred:")) {
						return v.predicateEntry(r.substring(5));
					}
					if (r.startsWith("event:")) {
						return v.eventTypeEntry(r.substring(6));
					}
					if (r.startsWith("type:")) {
						return v.entityTypeEntry(r.substring(5));
					}
					if (r.startsWith("group:")) {
						return v.groupEntry(r.substring(6));
					}
					Entity e = engine.entities().byRef(r).orElseThrow(() -> MnemicException
							.notFound("No entity matches '" + r + "'. Try recall with the name to see what is known."));
					return history.orElse(false) ? v.entityHistory(e, predicate.orElse(null)) : v.entity(e);
				}));
	}

	@Tool(name = "correct", description = ToolDescriptions.CORRECT, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse correct(
		@ToolArg(description = "What to correct, as inspect names it: f-12, obs-51, ent-12, evt-3, pred:parent_of, "
				+ "event:purchased, type:canton, group:family")
		String ref,
		@ToolArg(description = "The keys that change and their new values; {\"wrong\": true} withdraws a fact, "
				+ "{\"retired\": true|false} retires or reinstates an observation")
		Map<String, Object> changes, @ToolArg(description = "Why, in the user's words")
		Optional<String> reason) {
		writes.incrementAndGet();
		return ToolSupport.json("correct", () -> {
			String t = ref == null ? "" : ref.trim();
			String why = reason.orElse(null);
			Set<Long> before = openQuestionIds();
			Map<String, Object> out = corrected(t, changes, why);
			questionsOpened(out, before);
			return out;
		});
	}

	private Map<String, Object> corrected(String t, Map<String, Object> replacement, String why) {
		if (t.startsWith("f-")) {
			Corrected c = engine.correct(parseId(t, "f-"), replacement, why);
			return views().correctedFact(c, why);
		}
		if (t.startsWith("obs-")) {
			return correctedObservation(parseId(t, "obs-"), replacement, why);
		}
		if (t.startsWith("evt-")) {
			return engine.correctEvent(parseId(t, "evt-"), replacement, why);
		}
		if (t.startsWith("ent-")) {
			return engine.correctEntity(parseId(t, "ent-"), replacement, why);
		}
		if (t.startsWith("pred:")) {
			return engine.correctPredicate(t.substring(5), replacement, why);
		}
		if (t.startsWith("event:")) {
			return engine.correctEventType(t.substring(6), replacement, why);
		}
		if (t.startsWith("type:")) {
			return engine.correctEntityType(t.substring(5), replacement, why);
		}
		if (t.startsWith("group:")) {
			return engine.correctGroup(t.substring(6), replacement, why);
		}
		if (!t.isEmpty() && engine.predicates().get(t).isPresent()) {
			// A bare name is an entity everywhere else; a predicate is named as inspect names it.
			throw MnemicException.invalidArgument("'" + t + "' is a predicate: correct it as pred:" + t + ".");
		}
		throw MnemicException
				.invalidArgument("'" + t + "' names nothing to correct; pass f-12, obs-51, ent-12, pred:parent_of, "
						+ "evt-3, event:purchased, type:canton, or group:family.");
	}

	private Map<String, Object> correctedObservation(long id, Map<String, Object> replacement, String reason) {
		if (replacement == null || !(replacement.get("retired") instanceof Boolean retired)) {
			throw MnemicException.invalidArgument("An observation is corrected with {\"retired\": true} (optionally "
					+ "\"superseded_by\": \"obs-52\") or {\"retired\": false}.");
		}
		if (!retired) {
			return views().reinstatedObservation(engine.reinstateObservation(id));
		}
		Object by = replacement.get("superseded_by");
		Observation o = engine.retireObservation(id, reason, by == null ? null : parseId(String.valueOf(by), "obs-"));
		// The facts this observation produced stay, and cite it: the caller decides whether they stand.
		List<String> citing = engine.facts().factsOfObservation(id).stream().filter(Fact::current).map(Fact::ref)
				.toList();
		return views().retiredObservation(o, citing);
	}

	@Tool(name = "forget", description = ToolDescriptions.FORGET, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
	ToolResponse forget(
		@ToolArg(description = "What to forget: an observation (obs-12), or an entity no fact or event names (ent-12)")
		String ref, @ToolArg(required = false, description = ToolDescriptions.FORGET_KEEP_ENTITIES)
		Optional<Boolean> keep_entities) {
		writes.incrementAndGet();
		return ToolSupport.json("forget", () -> {
			String target = ref == null ? "" : ref.trim();
			if (target.startsWith("ent-")) {
				return engine.forgetEntity(parseId(target, "ent-"));
			}
			long id = parseId(target, "obs-");
			boolean removed = engine.forget(id, keep_entities.orElse(false));
			return Map.of("observation_id", "obs-" + id, "removed", removed, "kept_entities",
					keep_entities.orElse(false));
		});
	}

	@Tool(name = "consolidate", description = ToolDescriptions.CONSOLIDATE, annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse consolidate(@ToolArg(description = "Report what would change without changing it (default false)")
	Optional<Boolean> dry_run, @ToolArg(required = false, description = ToolDescriptions.CONSOLIDATE_RETIRE)
	Optional<List<String>> retire, @ToolArg(required = false, description = ToolDescriptions.CONSOLIDATE_REBUILD)
	Optional<Boolean> rebuild) {
		writes.incrementAndGet();
		return ToolSupport.json("consolidate", () -> {
			boolean dryRun = dry_run.orElse(false);
			List<Long> retireIds = retire.orElse(List.of()).stream().map(r -> parseId(r, "obs-")).toList();
			var c = engine.consolidate(dryRun, retireIds, rebuild.orElse(false));
			// The page: what was found, in words with the call that fixes each, and where the whole report is.
			return views().consolidate(c, dryRun, engine.findings(c), engine.writeReport(c, dryRun));
		});
	}

	@Tool(name = "status", description = ToolDescriptions.STATUS, annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse status(McpConnection connection) {
		return ToolSupport.json("status", () -> {
			// The repeat memo as this session sees it: which session this is, how many the process holds, and the
			// last read it memoised, so that a client whose repeats never get the note can be understood.
			String session = sessionKey(connection);
			var memo = new RepeatMemoView(session, connection == null ? null : connection.id(), lastRead.size(),
					writes.get(), lastRead.get(session));
			return views().status(config, memo);
		});
	}

	/**
	 * The key a read is memoised under. A client whose requests arrive as transient connections (Claude Code over
	 * stdio: a new id every request, "transient/4", "transient/5") is one client all the same, since stdio carries one;
	 * a lasting connection keeps its own memo. No connection at all (the tests) is one session too.
	 */
	static String sessionKey(McpConnection connection) {
		return connection == null || connection.isTransient() ? "" : connection.id();
	}

	/**
	 * A read repeated verbatim with nothing written since returns a note in place of its result. The result would be
	 * the same, and a weaker model that re-issues a call until it runs out of calls stops on the note where it did not
	 * stop on the block (Qwen 9B on the usage bench, 2026-09-24: cap hits 8 to 4, and the stronger models never repeat
	 * a call). One memo per session and read; a write in any session clears them all. A caller with no connection (the
	 * tests) is one session.
	 */
	private ToolResponse unlessRepeated(
		McpConnection connection, String tool, List<?> args, Supplier<ToolResponse> call) {
		String session = sessionKey(connection);
		String signature = tool + "|" + writes.get() + "|" + args;
		LOG.debugf("read memo %s: session '%s', %s", signature.equals(lastRead.get(session)) ? "hit" : "miss", session,
				signature);
		if (signature.equals(lastRead.get(session))) {
			return "recall".equals(tool) ? ToolSupport.text(tool, () -> REPEATED_NOTE)
					: ToolSupport.json(tool, () -> Map.of("repeated", true, "note", REPEATED_NOTE));
		}
		ToolResponse response = call.get();
		// An error is worth repeating: its message is what tells the caller what to change.
		if (response.isError()) {
			lastRead.remove(session);
		} else {
			lastRead.put(session, signature);
		}
		return response;
	}

	static void capped(Map<String, Object> out, String key, List<Map<String, Object>> list) {
		ToolViews.capped(out, key, list);
	}

	static long parseId(String ref, String prefix) {
		try {
			return Long.parseLong(ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref);
		} catch (NumberFormatException e) {
			throw MnemicException.invalidArgument("'" + ref + "' is not an id like " + prefix + "12.");
		}
	}

	/**
	 * Accepts an ISO instant, a date, a year-month, or a year; anything coarser than an instant means the end of that
	 * span in UTC, so {@code as_of: "2015"} asks what held at the close of 2015.
	 */
	static Instant instant(String s) {
		String t = s == null ? "" : s.strip();
		try {
			return Instant.parse(t);
		} catch (DateTimeParseException ignored) {
			// try the coarser forms
		}
		try {
			LocalDate day;
			if (t.matches("\\d{4}")) {
				day = Year.parse(t).atDay(1).withDayOfYear(Year.parse(t).length());
			} else if (t.matches("\\d{4}-\\d{2}")) {
				day = YearMonth.parse(t).atEndOfMonth();
			} else {
				day = LocalDate.parse(t);
			}
			return day.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			throw MnemicException.invalidArgument("'" + s
					+ "' is not an ISO-8601 instant, date, month, or year. Examples: 2015, 2015-06, 2015-06-01, 2026-09-06T10:12:00Z");
		}
	}
}
