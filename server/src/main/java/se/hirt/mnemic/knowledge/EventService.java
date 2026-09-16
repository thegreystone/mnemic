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
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Events: occurrences with participants and a date, stored once each, and their effects on facts through the
 * {@link EventTypeRegistry}. An event closes the facts it ends and, when its type ends an entity, every open fact of
 * that entity; a fact an event opens is minted by {@link FactService}.
 */
public final class EventService {

	/** A stored event: its id, and whether its effects apply (a new row, or an undated one that just got its date). */
	record Stored(long id, boolean effects) {
	}

	private final Database db;
	private final EventTypeRegistry types;
	private final FactLedger ledger;
	private final Lang lang;

	EventService(Database db, EventTypeRegistry types, FactLedger ledger, Lang lang) {
		this.lang = lang;
		this.db = db;
		this.types = types;
		this.ledger = ledger;
	}

	public Optional<Event> get(long id) {
		return db.read(tx -> {
			List<Event> l = events(tx, tx.query("SELECT * FROM event WHERE id = ?", id));
			return l.isEmpty() ? Optional.empty() : Optional.of(l.getFirst());
		});
	}

	public List<Event> eventsOf(long entityId) {
		return db.read(tx -> events(tx, tx.query("""
				SELECT e.* FROM event e JOIN event_participant p ON p.event_id = e.id WHERE p.entity_id = ?
				ORDER BY e.id""", entityId)));
	}

	public List<Event> eventsOfType(long entityId, String type) {
		return eventsOf(entityId).stream().filter(ev -> ev.type().equalsIgnoreCase(type)).toList();
	}

	public List<Event> ofType(String type) {
		return db.read(tx -> events(tx, tx.query("SELECT * FROM event WHERE type = ? ORDER BY id", type)));
	}

	public List<Event> eventsOfObservation(long observationId) {
		return db.read(
				tx -> events(tx, tx.query("SELECT * FROM event WHERE observation_id = ? ORDER BY id", observationId)));
	}

	/** Events whose rendering matches the FTS query, dated at or before {@code asOf}; they are keys like facts. */
	public List<Event> lexical(String match, Instant asOf, int limit) {
		if (match == null || match.isBlank()) {
			return List.of();
		}
		String day = asOf == null ? null : asOf.toString().substring(0, 10);
		return db.read(tx -> events(tx, tx.query("""
				SELECT e.* FROM event_fts x JOIN event e ON e.id = x.rowid
				WHERE event_fts MATCH ? ORDER BY bm25(event_fts), e.id DESC LIMIT ?""", match, limit * 2))).stream()
				.filter(ev -> day == null || ev.validStart() == null || ev.validStart().compareTo(day) <= 0)
				.limit(limit).toList();
	}

	/** The event type a question names, registered or merely stored. */
	public Optional<String> cue(List<String> tokens) {
		List<String> stored = db
				.read(tx -> tx.query("SELECT DISTINCT type FROM event").stream().map(r -> r.str("type")).toList());
		return types.cue(tokens, stored);
	}

	/** Whether an event type opens, closes, or supersedes facts of the predicate. */
	public boolean touches(String eventType, String predicate) {
		return types.touches(eventType, predicate);
	}

	static List<Event> events(Tx tx, List<Row> rows) {
		var out = new ArrayList<Event>();
		for (Row r : rows) {
			List<Long> parts = tx.query(
					"SELECT entity_id FROM event_participant WHERE event_id = ? ORDER BY position IS NULL, position, entity_id",
					r.lng("id")).stream().map(x -> x.lng("entity_id")).toList();
			out.add(Event.from(r, parts));
		}
		return out;
	}

	/** The sentence for an event of a type over these participants, with the store's temporal suffix. */
	public String render(String type, List<String> participants, Bounds b) {
		return types.render(type, participants) + b.suffix(false, lang);
	}

	/** Recomputes the renderings of every event of a type, after its template changed; the count. */
	public int rerender(String type) {
		return db.write(tx -> rerender(tx, tx.query("SELECT * FROM event WHERE type = ?", type)));
	}

	/** Every event, after a migration or a language change. */
	public int rerenderAll() {
		return db.write(tx -> rerender(tx, tx.query("SELECT * FROM event")));
	}

	private int rerender(Tx tx, List<Row> rows) {
		int n = 0;
		for (Event ev : events(tx, rows)) {
			List<String> names = ev.participants().stream().map(id -> FactRenderer.nameIn(tx, id)).toList();
			Bounds b = new Bounds(ev.validStart(), ev.validStartPrecision(), null, ev.validEnd(),
					ev.validEndPrecision(), null);
			tx.update("UPDATE event SET rendering = ? WHERE id = ?", render(ev.type(), names, b), ev.id());
			n++;
		}
		return n;
	}

	/** An event on record that closes the predicate and has both the subject and the object as participants. */
	Optional<Event> closingEvent(String predicate, long subjectId, long objectId) {
		for (EventType t : types.all()) {
			if (!t.closes().contains(predicate)) {
				continue;
			}
			List<Event> found = db.read(tx -> events(tx, tx.query("""
					SELECT e.* FROM event e
					JOIN event_participant s ON s.event_id = e.id AND s.entity_id = ?
					JOIN event_participant o ON o.event_id = e.id AND o.entity_id = ?
					WHERE e.type = ? ORDER BY e.id""", subjectId, objectId, t.name())));
			if (!found.isEmpty()) {
				return Optional.of(found.getFirst());
			}
		}
		return Optional.empty();
	}

	/**
	 * Stores an event, unless the same event is on record: the same type and participants, undated or dated the same,
	 * is one event, and a date the record lacked is filled in. A different date is a different event.
	 */
	Stored store(String type, List<Entity> participants, Bounds b, String rendering, Observation obs) {
		Optional<Event> same = sameEvent(type, participants, b.start());
		if (same.isPresent()) {
			Event e = same.get();
			boolean dating = e.validStart() == null && b.start() != null;
			if (dating) {
				db.write(tx -> tx.update(
						"""
								UPDATE event SET valid_start = ?, valid_start_precision = ?, valid_end = ?, valid_end_precision = ?,
								                 rendering = ? WHERE id = ?""",
						b.start(), b.startPrecision(), b.end(), b.endPrecision(), rendering, e.id()));
			}
			return new Stored(e.id(), dating);
		}
		long id = db.write(tx -> {
			long eid = tx.insert("""
					INSERT INTO event(type, observation_id, valid_start, valid_start_precision, valid_end,
					                  valid_end_precision, rendering, created_at) VALUES (?,?,?,?,?,?,?,?)""", type,
					obs.id(), b.start(), b.startPrecision(), b.end(), b.endPrecision(), rendering,
					Instant.now().toString());
			for (int i = 0; i < participants.size(); i++) {
				tx.update("INSERT OR IGNORE INTO event_participant(event_id, entity_id, position) VALUES (?,?,?)", eid,
						participants.get(i).id(), i);
			}
			return eid;
		});
		return new Stored(id, true);
	}

	private Optional<Event> sameEvent(String type, List<Entity> participants, String start) {
		List<Long> ids = participants.stream().map(Entity::id).toList();
		List<Long> candidates = db.read(tx -> tx.query(
				"SELECT id FROM event WHERE type = ? AND (valid_start IS NULL OR ? IS NULL OR valid_start = ?) ORDER BY id",
				type, start, start)).stream().map(r -> r.lng("id")).toList();
		for (long id : candidates) {
			Optional<Event> e = get(id);
			if (e.isPresent() && e.get().participants().size() == ids.size()
					&& e.get().participants().containsAll(ids)) {
				return e;
			}
		}
		return Optional.empty();
	}

	/**
	 * What an event does to the facts on record: closes the facts of the predicates its type closes between the first
	 * participant and each other one, and ends every open fact of the first participant when the type ends an entity.
	 * Only a fact whose interval the event falls inside is closed; each closure is reported.
	 */
	void applyEffects(
		long eventId, String type, List<Entity> participants, Bounds b, Observation obs,
		List<Map<String, Object>> superseded) {
		Optional<EventType> et = types.get(type);
		if (et.isEmpty() || participants.isEmpty()) {
			return;
		}
		Entity subject = participants.getFirst();
		db.write(tx -> {
			for (String pred : et.get().closes()) {
				// A symmetric fact is stored once, from whichever side said it: partner_of(Bo, Anna) ends at
				// married(Anna, Bo) as surely as at married(Bo, Anna).
				boolean symmetric = ledger.symmetric(pred);
				for (int i = 1; i < participants.size(); i++) {
					for (Row r : tx.query(
							"""
									SELECT * FROM fact WHERE predicate = ? AND status = 'current' AND valid_end IS NULL
									AND ((subject_id = ? AND (object_id = ? OR scope_id = ?)) OR (? AND subject_id = ? AND object_id = ?))""",
							pred, subject.id(), participants.get(i).id(), participants.get(i).id(), symmetric ? 1 : 0,
							participants.get(i).id(), subject.id())) {
						Fact f = Fact.from(r);
						if (startsAfter(f, b)) {
							continue;
						}
						ledger.close(tx, f, null, "event", null, eventId, obs.id(), b.start(), b.startPrecision(),
								"current");
						superseded.add(closedOut(f, eventId, b));
					}
				}
			}
			if (et.get().endsEntity()) {
				tx.update("UPDATE entity SET existed_end = ? WHERE id = ?", b.start(), subject.id());
				for (Row r : tx.query("""
						SELECT * FROM fact WHERE subject_id = ? AND status = 'current' AND valid_end IS NULL
						AND ended = 0""", subject.id())) {
					Fact f = Fact.from(r);
					if (startsAfter(f, b)) {
						continue;
					}
					ledger.close(tx, f, null, "entity_ended", type + " " + Bounds.show(b.start(), b.startPrecision()),
							eventId, obs.id(), b.start(), b.startPrecision(), "current");
					superseded.add(closedOut(f, eventId, b));
				}
			}
			return null;
		});
	}

	/** The fact began after the event: the event cannot have ended it. */
	private static boolean startsAfter(Fact f, Bounds b) {
		return f.validStart() != null && b.start() != null && f.validStart().compareTo(b.start()) > 0;
	}

	private static Map<String, Object> closedOut(Fact f, long eventId, Bounds b) {
		var m = new LinkedHashMap<String, Object>();
		m.put("fact_id", f.ref());
		m.put("predicate", f.predicate());
		m.put("rendering", f.rendering());
		m.put("ended_at", Bounds.show(b.start(), b.startPrecision()));
		m.put("event", "evt-" + eventId);
		return m;
	}
}
