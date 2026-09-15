/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.embed;

import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.persistence.Row;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Vectors beside the rows they describe: one row per item chunk and model, so two embedders can coexist and a change of
 * model is an incremental re-embedding, never a migration. Search is a scan over the model's vectors with a dot
 * product, which is exact and, at the sizes a personal store reaches, faster than an index would be to maintain.
 */
public final class VectorStore {

	/** A nearest neighbour: what kind of row, which one, and its cosine to the query. */
	public record Hit(String kind, long id, float score) {
	}

	public static final String OBSERVATION = "observation";
	public static final String FACT = "fact";

	private final Database db;

	public VectorStore(Database db) {
		this.db = db;
	}

	public void put(String kind, long id, String model, float[] vector) {
		putChunks(kind, id, model, List.of(vector));
	}

	/** Replaces the item's vectors under the model with one row per chunk. */
	public void putChunks(String kind, long id, String model, List<float[]> vectors) {
		db.write(tx -> {
			tx.update("DELETE FROM embedding WHERE item_kind = ? AND item_id = ? AND model = ?", kind, id, model);
			int chunk = 0;
			for (float[] vector : vectors) {
				ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
				for (float f : vector) {
					buf.putFloat(f);
				}
				tx.update("""
						INSERT INTO embedding(item_kind, item_id, model, chunk, dims, vec, created_at)
						VALUES (?,?,?,?,?,?,?)""", kind, id, model, chunk++, vector.length, buf.array(),
						Instant.now().toString());
			}
			return null;
		});
	}

	public boolean has(String kind, long id, String model) {
		return db.read(
				tx -> tx.queryLong("SELECT COUNT(*) FROM embedding WHERE item_kind = ? AND item_id = ? AND model = ?",
						kind, id, model)) > 0;
	}

	public long count(String model) {
		return db.read(tx -> tx.queryLong("SELECT COUNT(*) FROM embedding WHERE model = ?", model));
	}

	/** Observations that have no vector under the model yet: the backlog status shows. */
	public long missingObservationCount(String model) {
		return db.read(tx -> tx.queryLong("""
				SELECT COUNT(*) FROM observation o WHERE o.forgotten_at IS NULL AND NOT EXISTS
				(SELECT 1 FROM embedding e WHERE e.item_kind = 'observation' AND e.item_id = o.id AND e.model = ?)""",
				model));
	}

	/** Accepted facts that have no vector under the model yet. */
	public long missingFactCount(String model) {
		return db.read(tx -> tx.queryLong("""
				SELECT COUNT(*) FROM fact f WHERE f.status IN ('current', 'superseded') AND NOT EXISTS
				(SELECT 1 FROM embedding e WHERE e.item_kind = 'fact' AND e.item_id = f.id AND e.model = ?)""", model));
	}

	/** The {@code k} nearest items of the model to {@code query}, best first; an item scores by its best chunk. */
	public List<Hit> search(String model, float[] query, int k) {
		List<Row> rows = db
				.read(tx -> tx.query("SELECT item_kind, item_id, vec FROM embedding WHERE model = ?", model));
		var bestByItem = new HashMap<String, Hit>();
		for (Row r : rows) {
			byte[] blob = (byte[]) r.get("vec");
			float score = dot(query, blob);
			String key = r.str("item_kind") + ":" + r.lng("item_id");
			Hit prior = bestByItem.get(key);
			if (prior == null || score > prior.score()) {
				bestByItem.put(key, new Hit(r.str("item_kind"), r.lng("item_id"), score));
			}
		}
		var best = new PriorityQueue<Hit>(k + 1, (a, b) -> Float.compare(a.score(), b.score()));
		for (Hit h : bestByItem.values()) {
			if (best.size() < k) {
				best.add(h);
			} else if (h.score() > best.peek().score()) {
				best.poll();
				best.add(h);
			}
		}
		var out = new ArrayList<>(best);
		out.sort((a, b) -> Float.compare(b.score(), a.score()));
		return out;
	}

	private static float dot(float[] q, byte[] blob) {
		ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
		int n = Math.min(q.length, blob.length / 4);
		float s = 0;
		for (int i = 0; i < n; i++) {
			s += q[i] * buf.getFloat(i * 4);
		}
		return s;
	}

	/** Observations not yet embedded under the model, oldest first. */
	public List<Long> missingObservations(String model, int limit) {
		return db.read(tx -> tx.query("""
				SELECT o.id FROM observation o WHERE o.forgotten_at IS NULL AND NOT EXISTS
				(SELECT 1 FROM embedding e WHERE e.item_kind = 'observation' AND e.item_id = o.id AND e.model = ?)
				ORDER BY o.id LIMIT ?""", model, limit)).stream().map(r -> r.lng("id")).toList();
	}

	/** Accepted facts not yet embedded under the model, oldest first. */
	public List<Long> missingFacts(String model, int limit) {
		return db.read(tx -> tx.query("""
				SELECT f.id FROM fact f WHERE f.status IN ('current', 'superseded') AND NOT EXISTS
				(SELECT 1 FROM embedding e WHERE e.item_kind = 'fact' AND e.item_id = f.id AND e.model = ?)
				ORDER BY f.id LIMIT ?""", model, limit)).stream().map(r -> r.lng("id")).toList();
	}

	/** The vectors of every other model: unusable once the store's model changed; the number removed. */
	public int dropOtherModels(String model) {
		return db.write(tx -> tx.update("DELETE FROM embedding WHERE model <> ?", model));
	}

	/** Every vector of one kind, under every model: a change in how they are made, the backfill remakes them. */
	public void dropKind(String kind) {
		db.write(tx -> {
			tx.update("DELETE FROM embedding WHERE item_kind = ?", kind);
			return null;
		});
	}

	/** Removes the observation's vectors and the vectors of facts that no longer exist; called after the rows went. */
	public void forget(long observationId) {
		db.write(tx -> {
			tx.update("DELETE FROM embedding WHERE item_kind = 'observation' AND item_id = ?", observationId);
			tx.update("DELETE FROM embedding WHERE item_kind = 'fact' AND item_id NOT IN (SELECT id FROM fact)");
			return null;
		});
	}
}
