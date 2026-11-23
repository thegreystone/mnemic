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
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.embed;

import se.hirt.mnemic.persistence.Database;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	private record ItemKey(String kind, long id) {
	}

	/** Compact entry stored in a contiguous array for fast cache-friendly scanning. */
	private static final class CachedItem {
		final String kind;
		final long id;
		final float[] first;
		final float[][] extra; // null for single-chunk items (the vast majority)

		CachedItem(String kind, long id, float[] first, float[][] extra) {
			this.kind = kind;
			this.id = id;
			this.first = first;
			this.extra = extra;
		}
	}

	public static final String OBSERVATION = "observation";
	public static final String FACT = "fact";

	private final Database db;
	private final Clock clock;
	/**
	 * Decoded vectors for the active model, keyed by item and guarded by {@link Database#withLock}. Coherent across
	 * local writes, rolled-back transactions, and external process commits via {@code PRAGMA data_version}.
	 */
	private String cachedModel;
	private long cachedDataVersion = -1;
	private Map<ItemKey, List<float[]>> cache;
	private CachedItem[] scanArray;
	private long cacheLoads;

	public VectorStore(Database db) {
		this(db, Clock.systemUTC());
	}

	public VectorStore(Database db, Clock clock) {
		this.db = db;
		this.clock = clock == null ? Clock.systemUTC() : clock;
	}

	public void put(String kind, long id, String model, float[] vector) {
		putChunks(kind, id, model, List.of(vector));
	}

	/** Replaces the item's vectors under the model with one row per chunk. */
	public void putChunks(String kind, long id, String model, List<float[]> vectors) {
		db.withLock(() -> {
			boolean nested = db.inWriteTransaction();
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
							clock.instant().toString());
				}
				return null;
			});
			if (nested) {
				invalidateCache();
			} else if (cache != null && Objects.equals(cachedModel, model)) {
				if (db.dataVersion() != cachedDataVersion) {
					invalidateCache();
				} else {
					ItemKey key = new ItemKey(kind, id);
					if (vectors.isEmpty()) {
						cache.remove(key);
					} else {
						var copy = new ArrayList<float[]>(vectors.size());
						for (float[] vector : vectors) {
							copy.add(vector.clone());
						}
						cache.put(key, copy);
					}
					scanArray = null;
				}
			}
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
		if (k <= 0) {
			return List.of();
		}
		return db.withLock(() -> {
			CachedItem[] items = scanArrayFor(model);
			var best = new PriorityQueue<Hit>(k + 1, (a, b) -> Float.compare(a.score(), b.score()));
			float minScore = Float.NEGATIVE_INFINITY;
			for (CachedItem item : items) {
				float itemScore = dot(query, item.first);
				float[][] extra = item.extra;
				if (extra != null) {
					for (float[] chunk : extra) {
						float s = dot(query, chunk);
						if (s > itemScore) {
							itemScore = s;
						}
					}
				}
				if (best.size() < k) {
					best.add(new Hit(item.kind, item.id, itemScore));
					if (best.size() == k) {
						minScore = best.peek().score();
					}
				} else if (itemScore > minScore) {
					best.poll();
					best.add(new Hit(item.kind, item.id, itemScore));
					minScore = best.peek().score();
				}
			}
			var out = new ArrayList<>(best);
			out.sort((a, b) -> Float.compare(b.score(), a.score()));
			return out;
		});
	}

	private CachedItem[] scanArrayFor(String model) {
		if (db.inWriteTransaction()) {
			return toScanArray(loadVectors(model));
		}
		long dv = db.dataVersion();
		if (cache == null || !Objects.equals(cachedModel, model) || cachedDataVersion != dv) {
			cache = loadVectors(model);
			scanArray = toScanArray(cache);
			cachedModel = model;
			cachedDataVersion = dv;
			cacheLoads++;
		} else if (scanArray == null) {
			scanArray = toScanArray(cache);
		}
		return scanArray;
	}

	private static CachedItem[] toScanArray(Map<ItemKey, List<float[]>> map) {
		var list = new ArrayList<CachedItem>(map.size());
		for (Map.Entry<ItemKey, List<float[]>> e : map.entrySet()) {
			List<float[]> chunks = e.getValue();
			if (chunks.isEmpty()) {
				continue;
			}
			float[] first = chunks.getFirst();
			float[][] extra = null;
			if (chunks.size() > 1) {
				extra = new float[chunks.size() - 1][];
				for (int i = 1; i < chunks.size(); i++) {
					extra[i - 1] = chunks.get(i);
				}
			}
			ItemKey key = e.getKey();
			list.add(new CachedItem(key.kind(), key.id(), first, extra));
		}
		return list.toArray(CachedItem[]::new);
	}

	private Map<ItemKey, List<float[]>> loadVectors(String model) {
		var out = new HashMap<ItemKey, List<float[]>>();
		db.read(tx -> {
			tx.queryEach("SELECT item_kind, item_id, vec FROM embedding WHERE model = ?", rs -> {
				ItemKey key = new ItemKey(rs.getString(1), rs.getLong(2));
				byte[] blob = rs.getBytes(3);
				if (blob != null) {
					out.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(decode(blob));
				}
			}, model);
			return null;
		});
		return out;
	}

	private void invalidateCache() {
		cache = null;
		scanArray = null;
		cachedModel = null;
		cachedDataVersion = -1;
	}

	long cacheLoads() {
		return db.withLock(() -> cacheLoads);
	}

	private static float[] decode(byte[] blob) {
		ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
		int n = blob.length / 4;
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = buf.getFloat(i * 4);
		}
		return out;
	}

	/** 4-way unrolled dot product with independent accumulators to expose FMA ILP and SIMD vectorisation. */
	private static float dot(float[] q, float[] v) {
		int n = Math.min(q.length, v.length);
		int limit = n & ~3;
		float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
		int i = 0;
		for (; i < limit; i += 4) {
			s0 = Math.fma(q[i], v[i], s0);
			s1 = Math.fma(q[i + 1], v[i + 1], s1);
			s2 = Math.fma(q[i + 2], v[i + 2], s2);
			s3 = Math.fma(q[i + 3], v[i + 3], s3);
		}
		float s = (s0 + s1) + (s2 + s3);
		for (; i < n; i++) {
			s = Math.fma(q[i], v[i], s);
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
		return db.withLock(() -> {
			int removed = db.write(tx -> tx.update("DELETE FROM embedding WHERE model <> ?", model));
			if (cache != null && !Objects.equals(cachedModel, model)) {
				invalidateCache();
			}
			return removed;
		});
	}

	/** Every vector of one kind, under every model: a change in how they are made, the backfill remakes them. */
	public void dropKind(String kind) {
		db.withLock(() -> {
			boolean nested = db.inWriteTransaction();
			db.write(tx -> {
				tx.update("DELETE FROM embedding WHERE item_kind = ?", kind);
				return null;
			});
			if (nested || (cache != null && db.dataVersion() != cachedDataVersion)) {
				invalidateCache();
			} else if (cache != null) {
				cache.keySet().removeIf(k -> k.kind().equals(kind));
				scanArray = null;
			}
		});
	}

	/** Removes the observation's vectors and the vectors of facts that no longer exist; called after the rows went. */
	public void forget(long observationId) {
		db.withLock(() -> {
			db.write(tx -> {
				tx.update("DELETE FROM embedding WHERE item_kind = 'observation' AND item_id = ?", observationId);
				tx.update("DELETE FROM embedding WHERE item_kind = 'fact' AND item_id NOT IN (SELECT id FROM fact)");
				return null;
			});
			invalidateCache();
		});
	}
}
