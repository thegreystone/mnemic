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
import se.hirt.mnemic.protocol.Json;
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
	public record Outcome(List<Map<String, Object>> merges, List<Map<String, Object>> nameCollisions, int reclosed,
			List<Map<String, Object>> inferredVocabulary, List<Map<String, Object>> similarVocabulary,
			List<Map<String, Object>> descriptiveEvents, List<Map<String, Object>> unusedVocabulary,
			List<Map<String, Object>> unresolvedDerivations, List<Map<String, Object>> misfiledRelations,
			List<Map<String, Object>> attributeUnknown, List<Map<String, Object>> resolvedQuestions,
			List<Map<String, Object>> review, List<Map<String, Object>> duplicates, int removedEntities,
			List<Map<String, Object>> repairs) {
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
	private final QuestionService questions;
	private final Deriver deriver;

	Consolidator(Database db, EntityService entities, PredicateRegistry predicates, EventTypeRegistry eventTypes,
			EntityTypeRegistry entityTypes, EventService events, FactQueries facts, QuestionResolver resolver,
			FactLedger ledger, FactRenderer renderer, QuestionService questions, Deriver deriver) {
		this.questions = questions;
		this.deriver = deriver;
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
		int reclosed = recloseByEvents(dryRun) + endByPredicates(dryRun);
		List<Map<String, Object>> resolved = resolver.settleExactSubjects(dryRun);
		var duplicates = new ArrayList<Map<String, Object>>();
		foldDuplicateFacts(dryRun, duplicates, null);
		foldDuplicateEvents(dryRun, duplicates);
		var repairs = new ArrayList<Map<String, Object>>();
		valuesStoredAsThings(dryRun, repairs);
		descriptionsStoredAsThings(dryRun, repairs);
		int removed = dryRun ? 0 : entities.removeOrphansOfForgotten();
		if (!dryRun) {
			forgetDefinedBy();
		}
		return new Outcome(merges, nameCollisions(), reclosed, inferredVocabulary(), predicates.closePairs(),
				descriptiveEvents(), unusedVocabulary(), unresolvedDerivations(dryRun), misfiledRelations(),
				deriver.attributeUnknown(), resolved, facts.review(5), duplicates, removed, repairs);
	}

	/**
	 * Folds the duplicates a merge left: the facts of the surviving entity that now say the same thing twice ("leads
	 * Engram" became "leads mnemic" beside the "leads mnemic" on record, 2026-09-28). What was folded.
	 */
	public List<Map<String, Object>> foldDuplicatesOf(long entityId) {
		var out = new ArrayList<Map<String, Object>>();
		foldDuplicateFacts(false, out, entityId);
		return out;
	}

	/**
	 * A fact under a predicate that takes a value, whose object is an entity instead (a gender "male" stored as a thing
	 * by an early reading): the value becomes the text it names, and the entity, when nothing else names it, goes.
	 */
	private void valuesStoredAsThings(boolean dryRun, List<Map<String, Object>> repairs) {
		List<Row> rows = db.read(tx -> tx.query("SELECT f.id, f.predicate, f.object_id, e.name FROM fact f "
				+ "JOIN entity e ON e.id = f.object_id WHERE f.object_id IS NOT NULL ORDER BY f.id"));
		for (Row r : rows) {
			Predicate p = predicates.get(r.str("predicate")).orElse(null);
			if (p == null || !p.literalRange() || p.mixedRange()) {
				continue; // a predicate that takes a thing as well keeps it
			}
			long factId = r.lng("id");
			long entityId = r.lng("object_id");
			String value = r.str("name");
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", "f-" + factId);
			m.put(dryRun ? "would_repair" : "repaired", p.name() + " takes a value; the entity " + value + " (ent-"
					+ entityId + ") becomes the value \"" + value + "\"");
			repairs.add(m);
			if (!dryRun) {
				db.write(tx -> {
					tx.update("UPDATE fact SET object_text = ?, object_id = NULL WHERE id = ?", value, factId);
					renderer.rerender(tx, factId);
					return null;
				});
				removeIfUnnamed(entityId);
			}
		}
	}

	/**
	 * An entity of no known kind whose name is a description ("being challenged with contrary evidence rather than
	 * agreement"), named only as the object of relations that take anything: an early reading minted it for a phrase.
	 * The phrase becomes the facts' text and the entity goes; the renderings do not change.
	 */
	private void descriptionsStoredAsThings(boolean dryRun, List<Map<String, Object>> repairs) {
		List<Row> rows = db.read(tx -> tx
				.query("SELECT e.id, e.name FROM entity e WHERE e.type = 'unknown' " + "AND e.merged_into IS NULL "
						+ "AND NOT EXISTS (SELECT 1 FROM event_participant p WHERE p.entity_id = e.id) "
						+ "AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.subject_id = e.id OR f.scope_id = e.id) "
						+ "AND EXISTS (SELECT 1 FROM fact f WHERE f.object_id = e.id) ORDER BY e.id"));
		for (Row r : rows) {
			long entityId = r.lng("id");
			String name = r.str("name");
			if (!FactService.readsAsDescription(name) || entityId == entities.owner().id()) {
				continue;
			}
			List<Row> named = db.read(tx -> tx.query("SELECT id, predicate FROM fact WHERE object_id = ?", entityId));
			boolean anything = named.stream().allMatch(f -> predicates.get(f.str("predicate"))
					.map(p -> p.range().contains("*") && !p.literalRange()).orElse(false));
			if (!anything) {
				continue;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("entity", "ent-" + entityId);
			m.put(dryRun ? "would_repair" : "repaired", "\"" + name + "\" is a description, not a thing: kept as the "
					+ "text of " + named.stream().map(f -> "f-" + f.lng("id")).toList());
			repairs.add(m);
			if (!dryRun) {
				db.write(tx -> tx.update("UPDATE fact SET object_text = ?, object_id = NULL WHERE object_id = ?", name,
						entityId));
				removeIfUnnamed(entityId);
			}
		}
	}

	/** Removes an entity nothing names any more: no fact, no event. */
	private void removeIfUnnamed(long entityId) {
		long named = db.read(tx -> tx.queryLong("SELECT (SELECT COUNT(*) FROM fact WHERE subject_id = ? OR "
				+ "object_id = ? OR scope_id = ?) + (SELECT COUNT(*) FROM event_participant WHERE entity_id = ?)",
				entityId, entityId, entityId, entityId));
		if (named == 0) {
			entities.remove(entityId);
		}
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
				var m = new LinkedHashMap<String, Object>();
				m.put("event_type", t.name());
				m.put("uses", n);
				if (t.definedBy() != null) {
					m.put("since", "obs-" + t.definedBy());
				}
				out.add(m);
			}
		}
		for (var t : entityTypes.all()) {
			if (t.inferred()) {
				long n = db.read(tx -> tx
						.queryLong("SELECT COUNT(*) FROM entity WHERE type = ? AND merged_into IS NULL", t.name()));
				var m = new LinkedHashMap<String, Object>();
				m.put("entity_type", t.name());
				m.put("uses", n);
				if (t.definedBy() != null) {
					m.put("since", "obs-" + t.definedBy());
				}
				out.add(m);
			}
		}
		return out;
	}

	/**
	 * Vocabulary nothing rests on whose definition is gone: not seeded, not inferred, defined by an observation since
	 * forgotten (or anchored to none), and used by no fact, event, entity, or child type. Listed for the caller to
	 * settle; nothing removes it on its own.
	 */
	private List<Map<String, Object>> unusedVocabulary() {
		var out = new ArrayList<Map<String, Object>>();
		for (var p : predicates.all()) {
			if (!p.seed() && !p.isInferred() && orphanDefinition(p.definedBy())
					&& count("SELECT COUNT(*) FROM fact WHERE predicate = ?", p.name()) == 0) {
				out.add(unused("predicate", p.name(), p.definedBy()));
			}
		}
		for (var t : eventTypes.all()) {
			if (!t.seed() && !t.inferred() && orphanDefinition(t.definedBy())
					&& count("SELECT COUNT(*) FROM event WHERE type = ?", t.name()) == 0) {
				out.add(unused("event_type", t.name(), t.definedBy()));
			}
		}
		for (var t : entityTypes.all()) {
			if (!t.seed() && !t.inferred() && orphanDefinition(t.definedBy())
					&& count("SELECT COUNT(*) FROM entity WHERE type = ? AND merged_into IS NULL", t.name()) == 0
					&& entityTypes.all().stream().noneMatch(x -> t.name().equals(x.parent()))) {
				out.add(unused("entity_type", t.name(), t.definedBy()));
			}
		}
		return out;
	}

	private boolean orphanDefinition(Long definedBy) {
		return definedBy == null
				|| count("SELECT COUNT(*) FROM observation WHERE id = ? AND forgotten_at IS NOT NULL", definedBy) > 0;
	}

	private long count(String sql, Object arg) {
		return db.read(tx -> tx.queryLong(sql, arg));
	}

	private static Map<String, Object> unused(String kind, String name, Long definedBy) {
		var m = new LinkedHashMap<String, Object>();
		m.put(kind, name);
		m.put("defined_by", definedBy == null ? null : "obs-" + definedBy + " (forgotten)");
		m.put("uses", 0);
		return m;
	}

	/**
	 * Asserted facts on derived predicates that no chain reaches (K3–K7): each with what the chains say. When the
	 * chains from the fact's object are complete and end elsewhere, the record contradicts the statement and a
	 * {@code derivation} question is raised once; an incomplete chain leaves room, and nothing is asked.
	 */
	private List<Map<String, Object>> unresolvedDerivations(boolean dryRun) {
		var out = new ArrayList<Map<String, Object>>();
		for (Predicate p : predicates.derived()) {
			for (Row r : db.read(tx -> tx.query("SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND "
					+ "object_id IS NOT NULL AND mode = 'asserted' AND derivation_kind <> 'derived' ORDER BY id",
					p.name()))) {
				Fact f = Fact.from(r);
				if ("corroborated".equals(deriver.unificationOf(f))) {
					continue;
				}
				Deriver.Chains chains = deriver.chainsFor(f);
				var m = new LinkedHashMap<String, Object>();
				m.put("fact", f.ref());
				m.put("rendering", f.rendering());
				m.put("chain", chains.complete() ? "complete" : "incomplete");
				List<Fact> derived = derivedFor(f);
				m.put("derived", derived.stream().map(Fact::ref).toList());
				if (chains.contradicts(f)) {
					m.put("question", dryRun ? null : askDerivation(f, derived));
				}
				out.add(m);
			}
		}
		return out;
	}

	/**
	 * Stated related_to facts whose role a predicate of its own names (partner → partner_of, aunt → aunt_uncle_of):
	 * each with that predicate, the derived fact that already covers the pair when one does (a shadow: retire the
	 * statement with {@code correct(f-N, {redundant: true})}), and otherwise the move to make
	 * ({@code correct(f-N, {predicate: ...})}), so the store holds stated primitives and what follows from them.
	 */
	private List<Map<String, Object>> misfiledRelations() {
		var out = new ArrayList<Map<String, Object>>();
		// A generic relation: anything to anything, its qualifier free text. related_to is the seed's; any other
		// registered the same way counts the same.
		List<String> generic = predicates.all().stream().filter(
				p -> p.qualifiers().isEmpty() && p.domain().equals(List.of("*")) && p.range().equals(List.of("*")))
				.map(Predicate::name).toList();
		var rows = new ArrayList<Row>();
		for (String g : generic) {
			rows.addAll(db.read(tx -> tx.query("SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND "
					+ "qualifier IS NOT NULL AND object_id IS NOT NULL AND derivation_kind <> 'derived' ORDER BY id",
					g)));
		}
		for (Row r : rows) {
			Fact f = Fact.from(r);
			Optional<Predicate> target = predicates.dedicatedFor(f.qualifier());
			if (target.isEmpty()) {
				continue;
			}
			Optional<Fact> covered = deriver.covering(f);
			var m = new LinkedHashMap<String, Object>();
			m.put("fact", f.ref());
			m.put("rendering", f.rendering());
			m.put("qualifier", f.qualifier());
			m.put("predicate", target.get().name());
			m.put("derived", covered.map(Fact::ref).orElse(null));
			m.put("observation", "obs-" + f.observationId());
			m.put("hint",
					covered.isPresent()
							? "correct(\"" + f.ref() + "\", {\"redundant\": true}): " + covered.get().rendering()
									+ " covers it"
							: "correct(\"" + f.ref() + "\", {\"predicate\": \"" + target.get().name()
									+ "\"}), or re-read obs-" + f.observationId() + " with it");
			out.add(m);
		}
		return out;
	}

	/** The derived rows of the same predicate that end at the fact's object. */
	private List<Fact> derivedFor(Fact f) {
		return db.read(tx -> tx.query(
				"SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND "
						+ "derivation_kind = 'derived' AND (object_id = ? OR subject_id = ?) ORDER BY id",
				f.predicate(), f.objectId(), f.objectId())).stream().map(Fact::from).toList();
	}

	private String askDerivation(Fact f, List<Fact> derived) {
		Optional<Row> asked = db.read(tx -> tx.queryOne(
				"SELECT id FROM question WHERE kind = 'derivation' AND fact_id = ? ORDER BY id DESC", f.id()));
		if (asked.isPresent()) {
			return "q-" + asked.get().lng("id");
		}
		var c = new ArrayList<Map<String, Object>>();
		c.add(Map.of("n", 1, "id", "keep", "label", "the statement stands; the record's chains are missing something"));
		c.add(Map.of("n", 2, "id", "wrong", "label", "the statement was wrong; the record's chains are right"));
		String others = derived.isEmpty() ? "no one"
				: String.join("; ", derived.stream().map(Fact::rendering).toList());
		String message = "\"" + f.rendering() + "\" was stated, but " + f.predicate() + " is derived from other facts, "
				+ "and every chain from " + entities.nameOf(f.objectId()) + " is complete and reaches " + others
				+ ". Ask the user which is right.";
		String payload = Json.write(Map.of("fact", f.ref(), "derived", derived.stream().map(Fact::ref).toList()));
		return questions
				.create("derivation", null, f.id(), entities.nameOf(f.subjectId()), f.predicate(), c, payload, message)
				.ref();
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

	/**
	 * Live entities of kinds that cannot be one thing, sharing a name: the same thing typed twice, or two things that
	 * happen to share a name. Never merged unasked; listed with the correction that folds them if they are one.
	 */
	private List<Map<String, Object>> nameCollisions() {
		var out = new ArrayList<Map<String, Object>>();
		for (EntityService.Collision c : entities.nameCollisions()) {
			var m = new LinkedHashMap<String, Object>();
			m.put("shared", c.alias());
			m.put("entity", c.a().ref());
			m.put("name", c.a().name());
			m.put("type", c.a().type());
			m.put("and", c.b().ref());
			m.put("and_name", c.b().name());
			m.put("and_type", c.b().type());
			m.put("if_one", "correct(" + c.b().ref() + ", {\"merge_into\": \"" + c.a().ref() + "\"})");
			out.add(m);
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
	/**
	 * What a predicate's {@code ends} would have closed, had it been said when the fact arrived: for every current fact
	 * of a predicate that ends others, the open facts of those with the same subject and object (the object matched as
	 * the thing, or by name where one side said it in words) that began no later. A store from before a predicate ended
	 * anything catches up here. The count closed, or that would be.
	 */
	private int endByPredicates(boolean dryRun) {
		int n = 0;
		for (Predicate p : predicates.all()) {
			List<String> endsOf = predicates.endsOf(p.name());
			if (endsOf.isEmpty()) {
				continue;
			}
			for (Row r : db
					.read(tx -> tx.query(
							"SELECT * FROM fact WHERE predicate = ? AND status = 'current' "
									+ "AND mode = 'asserted' AND derivation_kind <> 'derived' ORDER BY id",
							p.name()))) {
				Fact later = Fact.from(r);
				java.util.Set<String> names = new java.util.HashSet<>();
				if (later.objectId() != null) {
					entities.aliases(later.objectId()).forEach(x -> names.add(Names.norm(x)));
				} else if (later.objectText() != null) {
					names.add(Names.norm(later.objectText()));
				}
				for (String q : endsOf) {
					for (Row o : db.read(tx -> tx.query("SELECT * FROM fact WHERE predicate = ? AND subject_id = ? "
							+ "AND status = 'current' AND ended = 0 AND valid_end IS NULL AND mode = 'asserted' "
							+ "AND derivation_kind <> 'derived'", q, later.subjectId()))) {
						Fact earlier = Fact.from(o);
						boolean same = earlier.objectId() != null
								? later.objectId() != null && earlier.objectId().equals(later.objectId())
								: earlier.objectText() != null && names.contains(Names.norm(earlier.objectText()));
						if (!same || (earlier.validStart() != null && later.validStart() != null
								&& earlier.validStart().compareTo(later.validStart()) > 0)) {
							continue;
						}
						n++;
						if (!dryRun) {
							db.write(tx -> {
								ledger.close(tx, earlier, later.id(), "ended_by",
										"consolidate: " + p.name() + " ends " + q, null, later.observationId(),
										later.validStart(), later.validStartPrecision(), "current");
								return null;
							});
						}
					}
				}
			}
		}
		return n;
	}

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
	private void foldDuplicateFacts(boolean dryRun, List<Map<String, Object>> duplicates, Long about) {
		long e = about == null ? -1 : about;
		for (Row r : db.read(tx -> tx.query("""
				SELECT a.id AS keep, b.id AS drop_id FROM fact a JOIN fact b ON b.subject_id = a.subject_id
				AND b.predicate = a.predicate AND COALESCE(b.object_id, -1) = COALESCE(a.object_id, -1)
				AND COALESCE(lower(b.object_text), '') = COALESCE(lower(a.object_text), '')
				AND COALESCE(b.scope_id, -1) = COALESCE(a.scope_id, -1) AND b.mode = a.mode AND b.id > a.id
				WHERE a.status = 'current' AND b.status = 'current' AND a.ended = 0 AND b.ended = 0
				AND a.derivation_kind <> 'derived' AND b.derivation_kind <> 'derived'
				AND a.valid_end IS NULL AND b.valid_end IS NULL
				AND (? = -1 OR a.subject_id = ? OR a.object_id = ? OR a.scope_id = ?)
				ORDER BY a.id, b.id""", e, e, e, e))) {
			long keep = r.lng("keep");
			long drop = r.lng("drop_id");
			Fact kept = facts.get(keep).orElse(null);
			Fact dropped = facts.get(drop).orElse(null);
			if (kept == null || dropped == null || !kept.current() || !dropped.current()) {
				continue; // folded already in this pass
			}
			Predicate pred = predicates.get(kept.predicate()).orElse(null);
			if (pred == null) {
				continue;
			}
			// Free text is wording, and two wordings of one thing are one fact; a vocabulary qualifier is identity, so
			// only the same one makes a duplicate (a merge makes those).
			boolean same = FactService.freeQualifier(pred)
					? FactService.sameWording(kept.qualifier(), dropped.qualifier())
					: java.util.Objects.equals(kept.qualifier(), dropped.qualifier());
			if (!same) {
				continue;
			}
			boolean takeWording = FactService.freeQualifier(pred)
					&& FactService.fuller(dropped.qualifier(), kept.qualifier());
			boolean worded = !java.util.Objects.equals(kept.qualifier(), dropped.qualifier());
			duplicates.add(Map.of(dryRun ? "would_fold" : "folded", dropped.ref(), "into", kept.ref(), "reason",
					(worded ? "the same fact, its qualifier worded differently" : "the same fact twice")
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
					if (keep.detail() == null && drop.detail() != null) {
						tx.update("UPDATE event SET detail = ? WHERE id = ?", drop.detail(), keep.id());
					}
					tx.update("UPDATE supersession SET event_id = ? WHERE event_id = ?", keep.id(), drop.id());
					tx.update(
							"INSERT OR IGNORE INTO event_source(event_id, observation_id, kind) "
									+ "SELECT ?, observation_id, kind FROM event_source WHERE event_id = ?",
							keep.id(), drop.id());
					tx.update("DELETE FROM event_participant WHERE event_id = ?", drop.id());
					tx.update("DELETE FROM event WHERE id = ?", drop.id());
					return null;
				});
				if (keep.detail() == null && drop.detail() != null) {
					events.rerender(keep.type());
				}
			}
		}
	}
}
