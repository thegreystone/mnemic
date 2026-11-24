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

import se.hirt.mnemic.knowledge.EventTypeRegistry.EventType;
import se.hirt.mnemic.knowledge.FactService.Removed;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Removes what an observation produced (EVALUATION.md D2, family O) and reopens facts closed or superseded by the
 * removed facts and events.
 */
final class FactForgetter {

	private final Database db;
	private final EntityService entities;
	private final EventTypeRegistry eventTypes;
	private final EventService events;
	private final FactRenderer renderer;

	FactForgetter(Database db, EntityService entities, EventTypeRegistry eventTypes, EventService events,
			FactRenderer renderer) {
		this.db = db;
		this.entities = entities;
		this.eventTypes = eventTypes;
		this.events = events;
		this.renderer = renderer;
	}

	/**
	 * Removes what an observation produced (EVALUATION.md D2, family O). A fact another observation also stated
	 * survives, re-homed there with one corroboration fewer. {@code keepEntities} leaves the entities the observation
	 * created in place, for a re-seed of the same text; forgetting for privacy removes those nothing else references.
	 */
	Removed forgetDerived(long observationId, boolean keepEntities) {
		var undated = new ArrayList<Long>();
		Removed removed = db.write(tx -> {
			tx.update("""
					INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
					SELECT observation_id, subject_id FROM fact WHERE observation_id = ?""", observationId);
			tx.update("""
					INSERT OR IGNORE INTO forgotten_link(observation_id, entity_id)
					SELECT observation_id, object_id FROM fact WHERE observation_id = ? AND object_id IS NOT NULL""",
					observationId);
			if (!keepEntities) {
				tx.update("DELETE FROM question WHERE observation_id = ?", observationId);
			} else {
				tx.update("UPDATE question SET status = 'dismissed', answer = 'forgotten' WHERE observation_id = ? "
						+ "AND status = 'open'", observationId);
			}
			var facts = new ArrayList<Map<String, Object>>();
			var goneFacts = new ArrayList<Long>();
			var removedPredicates = new LinkedHashSet<String>();
			for (Row r : tx.query("SELECT * FROM fact WHERE observation_id = ?", observationId)) {
				Fact f = Fact.from(r);
				List<Long> others = FactLedger.observationsOf(tx, f.id()).stream().filter(o -> o != observationId)
						.toList();
				if (!others.isEmpty()) {
					tx.update(
							"UPDATE fact SET observation_id = ?, corroborations = MAX(1, corroborations - 1) WHERE id = ?",
							others.getFirst(), f.id());
				} else {
					goneFacts.add(f.id());
					removedPredicates.add(f.predicate());
					facts.add(Map.of("id", f.ref(), "rendering", f.rendering(), "standing",
							"current".equals(f.status()) ? f.state(Instant.now()) : f.status()));
				}
			}
			for (Row src : tx.query("SELECT event_id, kind FROM event_source WHERE observation_id = ?",
					observationId)) {
				long evId = src.lng("event_id");
				List<Long> others = tx
						.query("SELECT observation_id FROM event_source WHERE event_id = ? "
								+ "AND observation_id <> ? ORDER BY observation_id", evId, observationId)
						.stream().map(r -> r.lng("observation_id")).toList();
				if (others.isEmpty()) {
					continue;
				}
				tx.update("DELETE FROM event_source WHERE event_id = ? AND observation_id = ?", evId, observationId);
				tx.update("UPDATE event SET observation_id = ? WHERE id = ? AND observation_id = ?", others.getFirst(),
						evId, observationId);
				if ("dated".equals(src.str("kind"))) {
					reopen(tx, List.of(), List.of(evId));
					this.events.undate(tx, evId);
					undated.add(evId);
				}
			}
			var removedEvents = new ArrayList<Map<String, Object>>();
			var goneEvents = new ArrayList<Long>();
			for (Event ev : EventService.events(tx,
					tx.query("SELECT * FROM event WHERE observation_id = ?", observationId))) {
				goneEvents.add(ev.id());
				removedEvents.add(Map.of("id", ev.ref(), "type", ev.type(), "rendering", ev.rendering()));
				if (eventTypes.get(ev.type()).map(EventType::endsEntity).orElse(false)
						&& !ev.participants().isEmpty()) {
					tx.update("UPDATE entity SET existed_end = NULL WHERE id = ?", ev.participants().getFirst());
				}
			}
			int reopened = reopen(tx, goneFacts, goneEvents);
			for (Row al : tx.query("SELECT a.id, a.alias, a.alias_norm, e.name FROM entity_alias a JOIN entity e "
					+ "ON e.id = a.entity_id WHERE a.source_observation = ?", observationId)) {
				if (Names.norm(al.str("name")).equals(al.str("alias_norm"))) {
					continue;
				}
				String needle = "\""
						+ al.str("alias").toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
				Long elsewhere = tx.query(
						"SELECT id FROM observation WHERE id <> ? AND forgotten_at IS NULL AND proposal_json "
								+ "IS NOT NULL AND instr(lower(proposal_json), ?) > 0 ORDER BY id LIMIT 1",
						observationId, needle).stream().map(r -> r.lng("id")).findFirst().orElse(null);
				if (elsewhere != null) {
					tx.update("UPDATE entity_alias SET source_observation = ? WHERE id = ?", elsewhere, al.lng("id"));
				} else {
					tx.update("DELETE FROM entity_alias WHERE id = ?", al.lng("id"));
				}
			}
			tx.update("DELETE FROM fact_source WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM fact WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM event_source WHERE observation_id = ?", observationId);
			tx.update("DELETE FROM event WHERE observation_id = ?", observationId);
			return new Removed(facts, removedEvents, reopened, Set.copyOf(removedPredicates), !undated.isEmpty());
		});
		for (long evId : undated) {
			events.get(evId).ifPresent(ev -> {
				Optional<Observation> home = db
						.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", ev.observationId()))
						.map(Observation::from);
				List<Entity> participants = ev.acting().stream().map(id -> entities.get(id).orElse(null))
						.filter(Objects::nonNull).toList();
				if (home.isPresent() && participants.size() == ev.acting().size()) {
					events.applyEffects(ev.id(), ev.type(), participants, Bounds.NONE, home.get(), new ArrayList<>());
				}
			});
		}
		if (!keepEntities) {
			entities.removeOrphansCreatedBy(observationId);
		}
		return removed;
	}

	/** The correction records that corrected or retracted this observation's facts: they restate what it said. */
	List<Long> correctionRecordsOf(long observationId) {
		return db.read(tx -> tx.query("""
				SELECT DISTINCT s.observation_id AS id FROM supersession s
				JOIN fact f ON f.id = s.fact_id JOIN observation o ON o.id = s.observation_id
				WHERE f.observation_id = ? AND s.kind IN ('correction', 'retraction') AND o.source_kind = 'correction'
				AND o.forgotten_at IS NULL""", observationId)).stream().map(r -> r.lng("id")).toList();
	}

	/**
	 * Undoes what the facts and events being removed did to other facts: a fact they closed, superseded, or corrected
	 * is current again, its end cleared when that closure gave it, and the record of the closure goes. The count.
	 */
	private int reopen(Tx tx, List<Long> goneFacts, List<Long> goneEvents) {
		if (goneFacts.isEmpty() && goneEvents.isEmpty()) {
			return 0;
		}
		String facts = goneFacts.isEmpty() ? "-1"
				: goneFacts.stream().map(String::valueOf).collect(Collectors.joining(","));
		String evs = goneEvents.isEmpty() ? "-1"
				: goneEvents.stream().map(String::valueOf).collect(Collectors.joining(","));
		int n = 0;
		for (Row s : tx.query("SELECT * FROM supersession WHERE superseded_by_id IN (" + facts + ") OR event_id IN ("
				+ evs + ") ORDER BY id DESC")) {
			long factId = s.lng("fact_id");
			if (goneFacts.contains(factId)) {
				continue;
			}
			Optional<Row> row = tx.queryOne("SELECT * FROM fact WHERE id = ?", factId);
			if (row.isEmpty()) {
				continue;
			}
			Fact f = Fact.from(row.get());
			String closedAt = s.str("closed_at");
			boolean endFromThis = f.validEnd() != null && f.validEnd().equals(closedAt) && f.endSource() != null
					&& !"stated".equals(f.endSource()) && !"resolved".equals(f.endSource());
			String status = "superseded".equals(f.status()) || "corrected".equals(f.status()) ? "current" : f.status();
			tx.update("UPDATE fact SET status = ?, superseded_by = NULL WHERE id = ?", status, factId);
			if (endFromThis) {
				tx.update("UPDATE fact SET valid_end = NULL, valid_end_precision = NULL, end_source = NULL, ended = 0 "
						+ "WHERE id = ?", factId);
			}
			tx.update("DELETE FROM supersession WHERE id = ?", s.lng("id"));
			renderer.rerender(tx, factId);
			n++;
		}
		return n;
	}
}
