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

import se.hirt.mnemic.embed.Embedding;
import se.hirt.mnemic.knowledge.Names;

import java.util.Map;

/**
 * A stand-in for the embedding model, for tests that need meaning without a model. The mentoring words in English and
 * German ("mentor", "betreut") share one direction and the coaching words ("coach", "coacht") lie close to it; every
 * other word gets its own direction, so unrelated names never look alike. A second instance with
 * {@code related = false} keeps the coaching words apart, standing in for a different model whose vectors must not be
 * mixed with the first one's.
 */
public final class FixedEmbedding implements Embedding {

	private static final int DIMS = 512;
	private static final Map<String, float[]> MENTORING = Map.of("mentor", axis(1f, 0f), "mentors", axis(1f, 0f),
			"mentoring", axis(1f, 0f), "betreut", axis(1f, 0f), "betreuen", axis(1f, 0f), "betreuung", axis(1f, 0f));
	private static final Map<String, float[]> COACHING = Map.of("coach", axis(0.94f, 0.34f), "coaches",
			axis(0.94f, 0.34f), "coaching", axis(0.94f, 0.34f), "coacht", axis(0.94f, 0.34f), "coachen",
			axis(0.94f, 0.34f));

	private final String id;
	private final boolean related;

	public FixedEmbedding() {
		this("fixed-test", true);
	}

	public FixedEmbedding(String id, boolean related) {
		this.id = id;
		this.related = related;
	}

	private static float[] axis(float x, float y) {
		float[] v = new float[DIMS];
		v[0] = x;
		v[1] = y;
		return v;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public float[] embed(String text) {
		float[] v = new float[DIMS];
		for (String t : Names.tokens(text)) {
			float[] k = MENTORING.get(t);
			if (k == null && related) {
				k = COACHING.get(t);
			}
			if (k != null) {
				for (int i = 0; i < DIMS; i++) {
					v[i] += k[i];
				}
			} else {
				v[2 + Math.floorMod(t.hashCode(), DIMS - 2)] += 1f;
			}
		}
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		if (norm == 0) {
			v[2] = 1f;
			return v;
		}
		for (int i = 0; i < DIMS; i++) {
			v[i] /= (float) Math.sqrt(norm);
		}
		return v;
	}
}
