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
package se.hirt.mnemic.knowledge;

import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.FactRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Facade over Layer 2 fact-write operations (EXTRACTION.md, Layer 2): proposal application ({@link ProposalApplier}),
 * corrections, retractions, and correction-log replay ({@link FactCorrector}), and observation forgetting and reopening
 * ({@link FactForgetter}).
 */
public final class FactService {

	/**
	 * What a proposal produced. Every id is reported so the caller can refer to it later; {@code definitions} names the
	 * vocabulary the proposal defined or that was registered from its first use here.
	 */
	public record Applied(List<EntityOut> entities, List<EventOut> events, List<FactOut> facts,
			List<PredicateOut> predicates, List<Map<String, Object>> questions, List<String> warnings,
			List<Map<String, Object>> superseded, List<Map<String, Object>> definitions) {
		public static final Applied NOTHING = new Applied(List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of());

		public Applied withWarning(String warning) {
			var all = new ArrayList<>(warnings);
			all.add(warning);
			return new Applied(entities, events, facts, predicates, questions, all, superseded, definitions);
		}
	}

	public record EntityOut(String ref, String id, String name, String resolution, double score) {
	}

	public record EventOut(String ref, String id, String type) {
	}

	public record FactOut(String id, String predicate, String rendering, String status, boolean corroborated) {
	}

	public record PredicateOut(String proposed, String resolution, String id) {
	}

	/**
	 * A correction's outcome; {@code replacement} is null for a retraction. {@code kind}: corrected (a replacement
	 * stands), retracted (never true), or retired (a derivation covers it).
	 */
	public record Corrected(Fact original, Fact replacement, String kind) {
	}

	/** What taking an observation's reading back removed: its own facts and events, and the closures it had caused. */
	public record Removed(List<Map<String, Object>> facts, List<Map<String, Object>> events, int reopened,
			Set<String> predicates, boolean eventsUpdated) {
		public static final Removed NONE = new Removed(List.of(), List.of(), 0, Set.of(), false);
	}

	/** The resolved parts of one fact to store. */
	record Operands(Entity subject, Predicate predicate, Entity object, String objectText, String qualifier,
			Entity scope, String mode) {
		long objectId() {
			return object == null ? -1 : object.id();
		}

		String objectTextKey() {
			return objectText == null ? "" : objectText.toLowerCase(Locale.ROOT);
		}

		long scopeId() {
			return scope == null ? -1 : scope.id();
		}

		String objectName() {
			return object != null ? object.name() : objectText;
		}

		boolean asserted() {
			return "asserted".equals(mode);
		}
	}

	private final FactRenderer renderer;
	private final ConflictCheck conflicts;
	private final ProposalApplier applier;
	private final FactCorrector corrector;
	private final FactForgetter forgetter;

	FactService(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EventService events, QuestionService questions, FactQueries queries, FactQuestions asks,
			FactRenderer renderer, FactLedger ledger, EntityTypeRegistry types, Deriver deriver) {
		this.renderer = renderer;
		this.conflicts = new ConflictCheck(eventTypes, types, predicates);
		this.applier = new ProposalApplier(db, entities, predicates, eventTypes, events, questions, asks, renderer,
				ledger, conflicts, types);
		this.corrector = new FactCorrector(db, entities, predicates, eventTypes, events, queries, renderer, deriver,
				applier);
		this.forgetter = new FactForgetter(db, entities, eventTypes, events, renderer);
	}

	// ── apply a proposal ─────────────────────────────────────────────────

	public Applied apply(Observation obs, Proposal p) {
		return applier.apply(obs, p, Map.of(), Set.of());
	}

	/** Apply with pre-bound references (used when resuming a proposal held on an entity question). */
	public Applied apply(Observation obs, Proposal p, Map<String, Entity> bound) {
		return applier.apply(obs, p, bound, Set.of());
	}

	/**
	 * Places any existing entities that carry this type's name as a kind-bearing name (e.g. "his terrier" once terrier
	 * is a known type) under it, and re-renders their facts. Returns the names of the entities moved.
	 */
	public List<String> placeNamedKinds(String typeName) {
		return applier.placeNamedKinds(typeName);
	}

	/**
	 * Runs a newly-defined event type's effects over every event already recorded under it — what happens when the user
	 * answers an unknown-event-type question with its effects.
	 */
	public Map<String, Object> applyEffectsOf(String type) {
		return applier.applyEffectsOf(type);
	}

	/**
	 * Checks current facts of a predicate that has just become functional: where a subject holds more than one under
	 * the same qualifier, opens a conflict question for each pair that does not already have one.
	 */
	public int recheckFunctional(String name) {
		return applier.recheckFunctional(name);
	}

	/** Re-renders every fact that mentions {@code entityId} as subject, object, or scope. */
	public int rerenderMentioning(long entityId) {
		return renderer.rerenderMentioning(entityId);
	}

	// ── correction & retraction ──────────────────────────────────────────

	/**
	 * Replaces a fact without destroying history (EVALUATION.md D1): the original is marked {@code corrected}, the
	 * replacement is derived from the correction observation through the same path as any fact, and the two are linked.
	 */
	public Corrected correct(long factId, Map<String, Object> replacement, String reason, Observation correction) {
		return corrector.correct(factId, replacement, reason, correction);
	}

	/**
	 * The fact as a correction reading states it: the original's values, the replacement's over them. With an empty
	 * replacement, the fact as it stands.
	 */
	public FactRef readingOf(Fact original, Map<String, Object> replacement) {
		return corrector.readingOf(original, replacement);
	}

	/** Whether two readings state the same fact: the same terms and bounds, whatever the precision noted. */
	public static boolean sameStatement(FactRef a, FactRef b) {
		return FactCorrector.sameStatement(a, b);
	}

	/**
	 * Withdraws a fact that was never true (EVALUATION.md D7): marked corrected with no replacement, the reason
	 * recorded as a retraction, so it leaves recall and stays in history.
	 */
	public Corrected retract(long factId, String reason, Observation correction) {
		return corrector.retract(factId, reason, correction);
	}

	/**
	 * Retires a stated fact a derivation now covers: it was true, and it still is, but the record derives it from the
	 * facts behind it, so the statement steps back as {@code superseded} by the derived fact (or by nothing named, when
	 * none covers it at the moment), with the reason on its history.
	 */
	public Corrected retire(long factId, String reason, Observation correction) {
		return corrector.retire(factId, reason, correction);
	}

	/**
	 * The reading of a correction record: the fact it is about, named by its key rather than its id, the reason, and
	 * the replacement it states (none for a retraction). What a rebuild needs to do it again.
	 */
	public Map<String, Object> reading(String kind, Fact about, String reason, List<FactRef> facts) {
		return corrector.reading(kind, about, reason, facts);
	}

	/** Moves an event and the facts that hang on its date, and refreshes their renderings. */
	public EventService.Redated redate(long eventId, Bounds b, Observation record) {
		return corrector.redate(eventId, b, record);
	}

	/** Gives an event another type; the event itself is re-rendered, and its new type's effects are noted. */
	public Map<String, Object> retype(long eventId, String type, Observation record) {
		return corrector.retype(eventId, type, null, Map.of(), record);
	}

	/** Gives an event another type and/or detail. */
	public Map<String, Object> retype(long eventId, String type, String detail, Observation record) {
		return corrector.retype(eventId, type, detail, Map.of(), record);
	}

	/** Gives an event another type, detail, and/or participant roles. */
	public Map<String, Object> retype(
		long eventId, String type, String detail, Map<Long, String> roles, Observation record) {
		return corrector.retype(eventId, type, detail, roles, record);
	}

	/**
	 * Replays a correction observation's reading during a rebuild against whatever fact or event now matches its key.
	 */
	public String replayCorrection(Observation record) {
		return corrector.replayCorrection(record);
	}

	/** Roles given by participant name ({@code {"Calle Wilund": "with"}}) as roles by entity id. */
	public Map<Long, String> rolesByName(Event ev, Object given) {
		return corrector.rolesByName(ev, given);
	}

	/**
	 * Correction records from before readings were stored on them get one from their supersession row. Idempotent.
	 */
	public int backfillCorrectionReadings() {
		return corrector.backfillCorrectionReadings();
	}

	// ── forgetting & package-private helpers ─────────────────────────────

	/** Whether one entity lies within the other on record (a house within its town), in either direction. */
	boolean nested(Tx tx, long a, long b) {
		return conflicts.nested(tx, a, b);
	}

	/**
	 * Removes what an observation produced (EVALUATION.md D2, family O). A fact another observation also stated
	 * survives, re-homed there with one corroboration fewer. {@code keepEntities} leaves the entities the observation
	 * created in place, for a re-seed of the same text; forgetting for privacy removes those nothing else references.
	 */
	public Removed forgetDerived(long observationId, boolean keepEntities) {
		return forgetter.forgetDerived(observationId, keepEntities);
	}

	/** The correction records that corrected or retracted this observation's facts: they restate what it said. */
	public List<Long> correctionRecordsOf(long observationId) {
		return forgetter.correctionRecordsOf(observationId);
	}

	static boolean readsAsDescription(String name) {
		return ProposalApplier.readsAsDescription(name);
	}

	static boolean sameWording(String a, String b) {
		return ProposalApplier.sameWording(a, b);
	}

	static boolean freeQualifier(Predicate p) {
		return ProposalApplier.freeQualifier(p);
	}

	static boolean fuller(String candidate, String current) {
		return ProposalApplier.fuller(candidate, current);
	}
}
