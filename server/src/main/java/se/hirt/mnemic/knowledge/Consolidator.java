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

import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Housekeeping over stored knowledge (EXTRACTION.md, Layer 3; EVALUATION.md G2, G3, J6): merges entities that share an
 * alias, closes facts whose closing event arrived later, settles entity questions whose subject now exists, folds
 * duplicate facts and events, and lists what a caller should look at. A dry run reports without changing anything.
 */
public final class Consolidator {

	/** What consolidation did or would do. */
	public record Outcome(List<Map<String, Object>> merges, int reclosed, List<Map<String, Object>> inferredVocabulary,
			List<Map<String, Object>> similarVocabulary, List<Map<String, Object>> descriptiveEvents,
			List<Map<String, Object>> resolvedQuestions, List<Map<String, Object>> review,
			List<Map<String, Object>> duplicates, int removedEntities) {
	}

	private final Database db;
	private final EntityService entities;
	private final PredicateRegistry predicates;
	private final EventTypeRegistry eventTypes;
	private final EntityTypeRegistry entityTypes;
	private final EventService events;
	private final FactQueries facts;
	private final QuestionResolver resolver;
	private final FactLedger ledger;
	private final FactRenderer renderer;

	Consolidator(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EntityTypeRegistry entityTypes, EventService events, FactQueries facts, QuestionResolver resolver,
			FactLedger ledger, FactRenderer renderer) {
		this.db = db;
		this.entities = entities;
		this.predicates = predicates;
		this.eventTypes = eventTypes;
		this.entityTypes = entityTypes;
		this.events = events;
		this.facts = facts;
		this.resolver = resolver;
		this.ledger = ledger;
		this.renderer = renderer;
	}

	public Outcome consolidate(boolean dryRun) {
		List<Map<String, Object>> merges = mergeSharedAliases(dryRun);
		int reclosed = recloseByEvents(dryRun);
		List<Map<String, Object>> resolved = resolver.settleExactSubjects(dryRun);
		var duplicates = new ArrayList<Map<String, Object>>();
		foldDuplicateFacts(dryRun, duplicates);
		foldDuplicateEvents(dryRun, duplicates);
		int removed = dryRun ? 0 : entities.removeOrphansOfForgotten();
		if (!dryRun) {
			forgetDefinedBy();
		}
		return new Outcome(merges, reclosed, inferredVocabulary(), predicates.closePairs(), descriptiveEvents(),
				resolved, facts.review(5), duplicates, removed);
	}

	/**
	 * Vocabulary registered from use that nobody has described yet, with how much rests on it, for the caller to settle
	 * with the user (EVALUATION.md J6): predicates, event types, and entity types.
	 */
	private List<Map<String, Object>> inferredVocabulary() {
		var out = new ArrayList<>(predicates.inferred());
		for (var t : eventTypes.all()) {
			if (t.inferred()) {
				long n = db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM event WHERE type = ?", t.name()));
				out.add(Map.of("event_type", t.name(), "uses", n));
			}
		}
		for (var t : entityTypes.all()) {
			if (t.inferred()) {
				long n = db.read(tx -> tx
						.queryLong("SELECT COUNT(*) FROM entity WHERE type = ? AND merged_into IS NULL", t.name()));
				out.add(Map.of("entity_type", t.name(), "uses", n));
			}
		}
		return out;
	}

	/** Vocabulary defined by an observation since forgotten stays, but no longer points at a tombstone. */
	private void forgetDefinedBy() {
		int n = db.write(tx -> {
			int changed = 0;
			for (String table : List.of("predicate", "event_type", "entity_type")) {
				changed += tx.update("UPDATE " + table + " SET defined_by = NULL WHERE defined_by IN "
						+ "(SELECT id FROM observation WHERE forgotten_at IS NOT NULL)");
			}
			return changed;
		});
		if (n > 0) {
			predicates.reload();
			eventTypes.reload();
			entityTypes.reload();
		}
	}

	/**
	 * Events whose type is a sentence rather than a type: the reading put the description where the type goes. Each
	 * names the observation to re-read with a proper type and the detail in the text.
	 */
	private List<Map<String, Object>> descriptiveEvents() {
		var out = new ArrayList<Map<String, Object>>();
		for (Row r : db.read(tx -> tx.query("SELECT id, type, observation_id FROM event ORDER BY id"))) {
			String type = r.str("type");
			if (type != null && !EventTypeRegistry.typeLike(type) && eventTypes.get(type).isEmpty()) {
				var m = new LinkedHashMap<String, Object>();
				m.put("event", "evt-" + r.lng("id"));
				m.put("type", type);
				m.put("observation", "obs-" + r.lng("observation_id"));
				out.add(m);
			}
		}
		return out;
	}

	private List<Map<String, Object>> mergeSharedAliases(boolean dryRun) {
		var merges = new ArrayList<Map<String, Object>>();
		for (long[] pair : entities.duplicateAliasPairs()) {
			Entity a = entities.get(pair[0]).orElseThrow();
			Entity b = entities.get(pair[1]).orElseThrow();
			if (a.id() == b.id()) {
				continue;
			}
			if (dryRun) {
				merges.add(Map.of("would_merge", b.ref(), "into", a.ref(), "reason", "shared alias"));
			} else {
				merges.add(entities.merge(b.id(), a.id(), null, "consolidate: shared alias"));
			}
		}
		return merges;
	}

	/** Facts flagged ended without a date whose closing event is on record: the event dates the end. */
	private int recloseByEvents(boolean dryRun) {
		int reclosed = 0;
		for (Row r : db.read(tx -> tx.query(
				"SELECT * FROM fact WHERE status = 'current' AND ended = 1 AND valid_end IS NULL AND object_id IS NOT NULL"))) {
			Fact f = Fact.from(r);
			Optional<Event> closing = events.closingEvent(f.predicate(), f.subjectId(), f.objectId());
			if (closing.isPresent() && closing.get().validStart() != null) {
				reclosed++;
				if (!dryRun) {
					Event ev = closing.get();
					db.write(tx -> {
						ledger.close(tx, f, null, "event", "consolidate: closing event found", ev.id(),
								ev.observationId(), ev.validStart(), ev.validStartPrecision(), "current");
						return null;
					});
				}
			}
		}
		return reclosed;
	}

	/**
	 * The same fact stated twice with a differently worded free-text qualifier becomes one fact with a corroboration,
	 * keeping the fuller wording.
	 */
	private void foldDuplicateFacts(boolean dryRun, List<Map<String, Object>> duplicates) {
		for (Row r : db.read(tx -> tx.query("""
				SELECT a.id AS keep, b.id AS drop_id FROM fact a JOIN fact b ON b.subject_id = a.subject_id
				AND b.predicate = a.predicate AND COALESCE(b.object_id, -1) = COALESCE(a.object_id, -1)
				AND COALESCE(lower(b.object_text), '') = COALESCE(lower(a.object_text), '')
				AND COALESCE(b.scope_id, -1) = COALESCE(a.scope_id, -1) AND b.mode = a.mode AND b.id > a.id
				WHERE a.status = 'current' AND b.status = 'current' AND a.ended = 0 AND b.ended = 0
				AND a.valid_end IS NULL AND b.valid_end IS NULL ORDER BY a.id, b.id"""))) {
			long keep = r.lng("keep");
			long drop = r.lng("drop_id");
			Fact kept = facts.get(keep).orElse(null);
			Fact dropped = facts.get(drop).orElse(null);
			if (kept == null || dropped == null || !kept.current() || !dropped.current()) {
				continue; // folded already in this pass
			}
			Predicate pred = predicates.get(kept.predicate()).orElse(null);
			if (pred == null || !FactService.freeQualifier(pred)) {
				continue;
			}
			boolean takeWording = FactService.fuller(dropped.qualifier(), kept.qualifier());
			duplicates.add(Map.of(dryRun ? "would_fold" : "folded", dropped.ref(), "into", kept.ref(), "reason",
					"the same fact, its qualifier worded differently"
							+ (takeWording ? "; the fuller wording of " + dropped.ref() + " is kept" : "")));
			if (!dryRun) {
				db.write(tx -> {
					tx.update("UPDATE fact SET status = 'corrected', superseded_by = ? WHERE id = ?", keep, drop);
					if (takeWording) {
						tx.update("UPDATE fact SET qualifier = ? WHERE id = ?", dropped.qualifier(), keep);
						renderer.rerender(tx, keep);
					}
					FactLedger.corroborate(tx, keep, dropped.observationId(), null);
					FactLedger.supersession(tx, drop, keep, "duplicate", "consolidate: the same fact worded twice",
							null, dropped.observationId(), null);
					return null;
				});
			}
		}
	}

	/** The same event with and without its date becomes the dated one; between two alike, the earlier stays. */
	private void foldDuplicateEvents(boolean dryRun, List<Map<String, Object>> duplicates) {
		for (Row r : db.read(tx -> tx
				.query("""
						SELECT a.id AS first, b.id AS second FROM event a JOIN event b ON b.type = a.type AND b.id > a.id
						WHERE a.valid_start IS NULL OR b.valid_start IS NULL OR a.valid_start = b.valid_start ORDER BY a.id, b.id"""))) {
			Optional<Event> a = events.get(r.lng("first"));
			Optional<Event> b = events.get(r.lng("second"));
			if (a.isEmpty() || b.isEmpty() || a.get().participants().size() != b.get().participants().size()
					|| !a.get().participants().containsAll(b.get().participants())) {
				continue;
			}
			Event keep = a.get().validStart() == null && b.get().validStart() != null ? b.get() : a.get();
			Event drop = keep == a.get() ? b.get() : a.get();
			duplicates.add(Map.of(dryRun ? "would_fold" : "folded", drop.ref(), "into", keep.ref(), "reason",
					"the same event, " + (drop.validStart() == null ? "undated" : "dated the same")));
			if (!dryRun) {
				db.write(tx -> {
					tx.update("UPDATE fact SET event_id = ? WHERE event_id = ?", keep.id(), drop.id());
					tx.update("UPDATE supersession SET event_id = ? WHERE event_id = ?", keep.id(), drop.id());
					tx.update("DELETE FROM event_participant WHERE event_id = ?", drop.id());
					tx.update("DELETE FROM event WHERE id = ?", drop.id());
					return null;
				});
			}
		}
	}
}
