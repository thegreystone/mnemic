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

import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.VectorStore;
import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.observation.Observation;
import se.hirt.mnemic.observation.ObservationService;
import se.hirt.mnemic.persistence.Database;
import se.hirt.mnemic.recall.OwnerAlias;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintains semantic embeddings for observations and facts in {@link VectorStore}, including first-person owner aliases
 * ({@link OwnerAlias}), scheme upgrades, and missing-vector backfill. Inference runs outside SQLite write transactions
 * so the database lock is never held during embedding.
 */
final class SemanticIndexer {

	private static final Logger LOG = Logger.getLogger(SemanticIndexer.class);

	/** The vector scheme: 2 since a fact about the owner carries a first-person vector too ({@link OwnerAlias}). */
	static final String VECTOR_SCHEME = "2";
	/** Bench knob: {@code -Dmnemic.owner-alias=false} embeds owner facts without the first-person vector. */
	private static final boolean OWNER_ALIAS = !"false"
			.equalsIgnoreCase(System.getProperty("mnemic.owner-alias", "true"));

	private final VectorStore vectors;
	private final ObservationService observations;
	private final Knowledge knowledge;
	private final EmbedderHolder embedder;
	private final Lang lang;

	SemanticIndexer(VectorStore vectors, ObservationService observations, Knowledge knowledge, EmbedderHolder embedder,
			Lang lang) {
		this.vectors = vectors;
		this.observations = observations;
		this.knowledge = knowledge;
		this.embedder = embedder;
		this.lang = lang;
	}

	/** Drops stale fact vectors when the vector scheme has changed so backfill rebuilds them from renderings. */
	void ensureScheme(Database db) {
		if (!VECTOR_SCHEME.equals(db.meta("vector_scheme"))) {
			vectors.dropKind(VectorStore.FACT);
			db.setMeta("vector_scheme", VECTOR_SCHEME);
		}
	}

	/** Retries loading a previously failed embedder before a maintenance pass. */
	void retryIfFailed() {
		embedder.retryIfFailed();
	}

	/**
	 * The observation's text and each new fact's rendering, embedded as they are stored. A failure here never fails the
	 * remember; consolidate backfills what is missing.
	 */
	void embed(Observation obs, Applied applied) {
		Embedder current = embedder.get();
		if (current == null) {
			return;
		}
		try {
			if (!vectors.has(VectorStore.OBSERVATION, obs.id(), current.id())) {
				vectors.putChunks(VectorStore.OBSERVATION, obs.id(), current.id(), current.embedChunks(obs.text()));
			}
			for (FactOut f : applied.facts()) {
				long id = Long.parseLong(f.id().substring(2));
				if (!vectors.has(VectorStore.FACT, id, current.id())) {
					vectors.putChunks(VectorStore.FACT, id, current.id(), factVectors(current, f.rendering()));
				}
			}
		} catch (RuntimeException e) {
			LOG.warnf("Embedding skipped for %s: %s", obs.ref(), e.getMessage());
		}
	}

	/** Embeds what has no vector yet, up to {@code limit} rows of each kind; returns the number embedded. */
	int embedMissing(int limit) {
		Embedder current = embedder.get();
		if (current == null) {
			return 0;
		}
		int n = 0;
		for (long id : vectors.missingObservations(current.id(), limit)) {
			observations.get(id).ifPresent(
					o -> vectors.putChunks(VectorStore.OBSERVATION, id, current.id(), current.embedChunks(o.text())));
			n++;
		}
		for (long id : vectors.missingFacts(current.id(), limit)) {
			knowledge.facts().get(id).ifPresent(
					f -> vectors.putChunks(VectorStore.FACT, id, current.id(), factVectors(current, f.rendering())));
			n++;
		}
		return n;
	}

	/** Re-embeds all missing observations and facts in batches until none remain or progress stalls. */
	void embedAllMissing(int batchSize) {
		Embedder current = embedder.get();
		if (current == null) {
			return;
		}
		String model = current.id();
		long missing = vectors.missingObservationCount(model) + vectors.missingFactCount(model);
		while (missing > 0 && embedMissing(batchSize) > 0) {
			long next = vectors.missingObservationCount(model) + vectors.missingFactCount(model);
			if (next >= missing) {
				break;
			}
			missing = next;
		}
	}

	/**
	 * A fact's vectors: its rendering, and for a fact that names the owner the rendering as the owner would say it, so
	 * a first-person question lands on it; the item scores by its better vector.
	 */
	private List<float[]> factVectors(Embedder current, String rendering) {
		var out = new ArrayList<float[]>();
		out.add(current.embed(rendering));
		String mine = OWNER_ALIAS ? OwnerAlias.firstPerson(rendering, knowledge.entities().owner().name(), lang) : null;
		if (mine != null) {
			out.add(current.embed(mine));
		}
		return out;
	}
}
