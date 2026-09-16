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
package se.hirt.mnemic.observation;

import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The verbatim store. Observations are never split, never rewritten, and only blanked by an explicit {@code forget}
 * (EXTRACTION.md, Granularity; EVALUATION.md D2). A proposal-less observation is stored and counted as pending for
 * {@code consolidate} (EVALUATION.md A3).
 */
public final class ObservationService {

	/** Result of a {@code remember}. {@code replayed} means the idempotency key matched an earlier call. */
	public record Remembered(long observationId, boolean replayed, boolean duplicateText, List<String> warnings,
			long pendingProposals) {
	}

	private final Database db;
	private final int softLimitChars;

	public ObservationService(Database db, int softLimitChars) {
		this.db = db;
		this.softLimitChars = softLimitChars;
	}

	public Remembered remember(
		String text, Source source, Instant observedAt, String proposalJson, Integer specVersion,
		String idempotencyKey) {
		if (text == null || text.isBlank()) {
			throw MnemicException.invalidArgument(
					"'text' is required and must not be blank. Example: {\"text\": \"I joined Hooli in 2018.\"}");
		}
		Source src = source == null ? Source.user() : source;
		if ("connector".equals(src.kind()) && proposalJson != null) {
			throw MnemicException.invalidArgument("Connector observations arrive without a proposal; connectors create "
					+ "observations, never facts (EXTRACTION.md). Store it without one, then read it and give it its facts with "
					+ "remember(observation_id, proposal), or let a configured proposer handle it in consolidate.");
		}
		Instant observed = observedAt == null ? Instant.now() : observedAt;
		String hash = Json.hashText(text);
		var warnings = new ArrayList<String>();
		if (text.length() > softLimitChars) {
			warnings.add("Observation is " + text.length() + " characters, above the soft limit of " + softLimitChars
					+ ". It was stored whole. Chunk documents by section or paragraph and set source.ref and "
					+ "source.chunk so the chunks stay linked; forget and re-derivation work per observation.");
		}
		return db.write(tx -> {
			if (idempotencyKey != null && !idempotencyKey.isBlank()) {
				Optional<Row> existing = tx.queryOne("SELECT id FROM observation WHERE idempotency_key = ?",
						idempotencyKey);
				if (existing.isPresent()) {
					return new Remembered(existing.get().lng("id"), true, false, List.of(), pending(tx));
				}
			}
			boolean duplicate = tx.queryLong(
					"SELECT COUNT(*) FROM observation WHERE content_hash = ? AND forgotten_at IS NULL", hash) > 0;
			long id = tx.insert("""
					INSERT INTO observation(text, source_kind, source_ref, source_chunk, assistant, session,
					                        observed_at, recorded_at, proposal_json, spec_version, content_hash,
					                        idempotency_key)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", text, src.kind(), src.ref(), src.chunk(), src.assistant(),
					src.session(), observed.toString(), Instant.now().toString(), proposalJson, specVersion, hash,
					idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
			return new Remembered(id, false, duplicate, List.copyOf(warnings), pending(tx));
		});
	}

	/** Stores a proposal the server's configured model made for an observation that arrived without one. */
	public void attachProposal(long id, String proposalJson, Integer specVersion, String proposer) {
		db.write(
				tx -> tx.update("UPDATE observation SET proposal_json = ?, spec_version = ?, proposer = ? WHERE id = ?",
						proposalJson, specVersion, proposer, id));
	}

	/** The configured model that proposed for the observation, or null when the assistant did (or nobody). */
	public String proposerOf(long id) {
		return db.read(tx -> tx.queryOne("SELECT proposer FROM observation WHERE id = ?", id)
				.map(r -> r.str("proposer")).orElse(null));
	}

	public Optional<Observation> get(long id) {
		return db.read(tx -> tx.queryOne("SELECT * FROM observation WHERE id = ?", id).map(Observation::from));
	}

	/** Observations with no proposal yet, oldest first: the consolidate backlog (EVALUATION.md A3, G3). */
	/** The whole log in order, forgotten entries excluded. */
	public List<Observation> all() {
		return db.read(tx -> tx.query("SELECT * FROM observation WHERE forgotten_at IS NULL ORDER BY id").stream()
				.map(Observation::from).toList());
	}

	public List<Observation> backlog(int limit) {
		return db.read(tx -> tx.query("SELECT * FROM observation WHERE proposal_json IS NULL AND forgotten_at IS NULL "
				+ "ORDER BY id LIMIT ?", limit).stream().map(Observation::from).toList());
	}

	public long count() {
		return db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM observation WHERE forgotten_at IS NULL"));
	}

	/**
	 * Retired observations: still stored, out of recall; shown beside the count so a corrected store does not look
	 * smaller.
	 */
	public long retiredCount() {
		return db.read(tx -> tx
				.queryLong("SELECT COUNT(*) FROM observation WHERE forgotten_at IS NULL AND retired_at IS NOT NULL"));
	}

	/** The ids of the retired observations among the given ones. */
	public Set<Long> retiredAmong(Collection<Long> ids) {
		var out = new HashSet<Long>();
		for (long id : ids) {
			get(id).filter(Observation::retired).ifPresent(o -> out.add(id));
		}
		return out;
	}

	/**
	 * Undoes a retirement: the note is live again and, if it never had a reading, back in the backlog. False when it
	 * was not retired.
	 */
	public boolean reinstate(long id) {
		return db.write(tx -> tx.update("""
				UPDATE observation SET retired_at = NULL, retired_reason = NULL, superseded_by = NULL,
				                       proposal_json = CASE WHEN proposal_json = '{}' THEN NULL ELSE proposal_json END
				WHERE id = ? AND forgotten_at IS NULL AND retired_at IS NOT NULL""", id)) > 0;
	}

	/** Marks an observation as having nothing to propose ({@code {}}), so it leaves the backlog. */
	public boolean retire(long id) {
		return db.write(tx -> tx.update(
				"UPDATE observation SET proposal_json = '{}' WHERE id = ? AND proposal_json IS NULL AND forgotten_at IS NULL",
				id)) > 0;
	}

	/**
	 * Retires an observation that was recorded wrongly or superseded (EVALUATION.md D8): the text and history stay, the
	 * reason and the superseding observation are recorded, it leaves the backlog and, unless history is asked for,
	 * recall. Its facts are not touched. False when it is already retired or forgotten.
	 */
	public boolean retire(long id, String reason, Long supersededBy) {
		return db.write(tx -> tx.update("""
				UPDATE observation SET retired_at = ?, retired_reason = ?, superseded_by = ?,
				                       proposal_json = COALESCE(proposal_json, '{}')
				WHERE id = ? AND forgotten_at IS NULL AND retired_at IS NULL""", Instant.now().toString(), reason,
				supersededBy, id)) > 0;
	}

	public long pendingProposals() {
		return db.read(this::pending);
	}

	/** The observations still without a proposal, oldest first, up to {@code limit}: status names them. */
	public List<Long> pendingProposalIds(int limit) {
		return db.read(tx -> tx.query(
				"SELECT id FROM observation WHERE proposal_json IS NULL AND forgotten_at IS NULL ORDER BY id LIMIT ?",
				limit)).stream().map(r -> r.lng("id")).toList();
	}

	private long pending(Tx tx) {
		return tx.queryLong("SELECT COUNT(*) FROM observation WHERE proposal_json IS NULL AND forgotten_at IS NULL");
	}

	/**
	 * Hard forget: blanks the text (the FTS trigger removes the index entry) and leaves a dated tombstone
	 * (EVALUATION.md D2). What was derived from it is removed by the fact layer before this is called.
	 */
	public boolean forget(long id) {
		return db.write(
				tx -> tx.update("UPDATE observation SET text = '', proposal_json = NULL, forgotten_at = ? WHERE id = ? "
						+ "AND forgotten_at IS NULL", Instant.now().toString(), id) == 1);
	}
}
