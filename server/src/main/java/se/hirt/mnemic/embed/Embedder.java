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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The in-process embedder: a sentence-embedding model run through ONNX Runtime, the tokenizer read from the model's own
 * {@code tokenizer.json}. One instance per store, one session, used from one thread at a time (the engine serialises
 * its work). {@link #id()} names the model so vectors are keyed by what made them.
 * <p>
 * Pooling, prefixes, and normalisation follow the model card through a {@link Spec}: the granite embedders take the
 * first token (CLS) of the last hidden state, the e5 and MiniLM families the mean over the tokens, e5 wants
 * {@code query: } and {@code passage: } in front of its texts, arctic {@code query: } on questions only. Every vector
 * is L2-normalised, so cosine similarity is a dot product. Texts stored go through {@link #embed}; questions through
 * {@link #embedQuery}, which differ only by the prefix.
 */
public final class Embedder implements AutoCloseable {

	/** Tokens per text; the model's position table holds 512 and the texts here are sentences to paragraphs. */
	public static final int MAX_TOKENS = 512;

	/** What the model card says about pooling ({@code cls} or {@code mean}) and the prefixes it was trained with. */
	public record Spec(String pooling, String queryPrefix, String passagePrefix) {
		public static final Spec CLS = new Spec("cls", "", "");

		/** The spec for a model by its id (its Hugging Face name without the organisation). */
		public static Spec forModel(String id) {
			String m = id == null ? "" : id.toLowerCase(Locale.ROOT);
			if (m.startsWith("multilingual-e5") || m.startsWith("e5-")) {
				return new Spec("mean", "query: ", "passage: ");
			}
			if (m.contains("minilm") || m.startsWith("paraphrase-") || m.startsWith("all-")) {
				return new Spec("mean", "", "");
			}
			if (m.startsWith("snowflake-arctic")) {
				return new Spec("cls", "query: ", "");
			}
			return CLS; // granite (both generations), bge-m3
		}
	}

	private final OrtRuntime runtime;
	private final Tokenizer tokenizer;
	private final MemorySegment env;
	private final MemorySegment session;
	private final String[] inputNames;
	private final String outputName;
	private final boolean pooledOutput;
	private final Spec spec;
	private final String id;
	private final int dims;

	/**
	 * @param library
	 *            the ONNX Runtime shared library
	 * @param modelDir
	 *            a directory holding {@code model.onnx} and {@code tokenizer.json}
	 * @param modelId
	 *            the name the vectors are keyed by, e.g. {@code granite-embedding-107m-multilingual}; it also picks the
	 *            {@link Spec}
	 */
	public Embedder(Path library, Path modelDir, String modelId) throws IOException {
		this(library, modelDir, modelId, Spec.forModel(modelId));
	}

	public Embedder(Path library, Path modelDir, String modelId, Spec spec) throws IOException {
		Path model = modelDir.resolve("model.onnx");
		Path tok = modelDir.resolve("tokenizer.json");
		if (!Files.exists(model) || !Files.exists(tok)) {
			throw new IOException("Embedding model needs model.onnx and tokenizer.json in " + modelDir);
		}
		this.spec = spec;
		this.tokenizer = Tokenizer.load(tok);
		this.runtime = new OrtRuntime(library);
		this.env = runtime.createEnv("mnemic");
		this.session = runtime.createSession(env, model,
				Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
		this.inputNames = runtime.inputNames(session);
		String[] outputs = runtime.outputNames(session);
		// A sentence-transformers export pools for us; a plain export gives the token rows.
		String pooled = null, tokens = null;
		for (String o : outputs) {
			if ("sentence_embedding".equals(o)) {
				pooled = o;
			} else if ("last_hidden_state".equals(o) || "token_embeddings".equals(o)) {
				tokens = o;
			}
		}
		this.pooledOutput = pooled != null;
		this.outputName = pooled != null ? pooled
				: tokens != null ? tokens : outputs.length > 0 ? outputs[0] : "last_hidden_state";
		this.id = modelId;
		this.dims = embed("dimension probe").length;
	}

	public String id() {
		return id;
	}

	public int dims() {
		return dims;
	}

	public Spec spec() {
		return spec;
	}

	public String runtimeVersion() {
		return runtime.version();
	}

	/** The unit-length embedding of a text that is stored (an observation chunk, a fact rendering). */
	public float[] embed(String text) {
		return vector(spec.passagePrefix() + (text == null ? "" : text));
	}

	/** The unit-length embedding of a question, with the prefix the model wants on the query side. */
	public float[] embedQuery(String text) {
		return vector(spec.queryPrefix() + (text == null ? "" : text));
	}

	private float[] vector(String text) {
		int[] tokens = tokenizer.encode(text, MAX_TOKENS);
		long[] ids = new long[tokens.length];
		long[] mask = new long[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			ids[i] = tokens[i];
			mask[i] = 1;
		}
		var inputs = new long[inputNames.length][];
		for (int i = 0; i < inputNames.length; i++) {
			inputs[i] = switch (inputNames[i]) {
			case "input_ids" -> ids;
			case "attention_mask" -> mask;
			default -> new long[tokens.length]; // token_type_ids: zeros
			};
		}
		OrtRuntime.Result r = runtime.run(session, inputNames, inputs, outputName);
		int hidden = (int) r.shape()[r.shape().length - 1];
		float[] v;
		if (pooledOutput || r.shape().length < 3 || "cls".equals(spec.pooling())) {
			// [1][hidden] as pooled, or the first row of [1][tokens][hidden]: the CLS token.
			v = Arrays.copyOf(r.data(), hidden);
		} else {
			// Mean over the token rows; every token is real (no padding in a batch of one).
			int rows = (int) r.shape()[1];
			v = new float[hidden];
			float[] d = r.data();
			for (int t = 0; t < rows; t++) {
				for (int h = 0; h < hidden; h++) {
					v[h] += d[t * hidden + h];
				}
			}
			for (int h = 0; h < hidden; h++) {
				v[h] /= rows;
			}
		}
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < v.length; i++) {
				v[i] = (float) (v[i] / norm);
			}
		}
		return v;
	}

	/** One vector per chunk of a long text, so a long observation is not represented by its first 512 tokens. */
	public List<float[]> embedChunks(String text) {
		var out = new ArrayList<float[]>();
		for (String chunk : chunks(text)) {
			out.add(embed(chunk));
		}
		return out;
	}

	/** About 300 tokens per chunk; the text is cut at paragraph or sentence ends where it can be. */
	static final int CHUNK_CHARS = 1200;

	/**
	 * Splits a text into pieces of at most {@link #CHUNK_CHARS} characters, preferring a paragraph break, then a
	 * sentence end, in the last third of the window. A short text is one chunk; an empty one is one empty chunk.
	 */
	public static List<String> chunks(String text) {
		String t = text == null ? "" : text.strip();
		var out = new ArrayList<String>();
		if (t.length() <= CHUNK_CHARS) {
			out.add(t);
			return out;
		}
		int at = 0;
		while (at < t.length()) {
			int end = Math.min(t.length(), at + CHUNK_CHARS);
			if (end < t.length()) {
				int floor = at + CHUNK_CHARS * 2 / 3;
				int cut = t.lastIndexOf("\n\n", end);
				if (cut < floor) {
					cut = t.lastIndexOf("\n", end);
				}
				if (cut < floor) {
					int best = -1;
					for (String mark : new String[] {". ", "! ", "? "}) {
						best = Math.max(best, t.lastIndexOf(mark, end));
					}
					cut = best < 0 ? -1 : best + 1;
				}
				if (cut >= floor) {
					end = cut;
				}
			}
			String piece = t.substring(at, end).strip();
			if (!piece.isEmpty()) {
				out.add(piece);
			}
			at = end;
		}
		return out;
	}

	/** Cosine similarity of two unit vectors. */
	public static float dot(float[] a, float[] b) {
		float s = 0;
		for (int i = 0; i < a.length; i++) {
			s += a[i] * b[i];
		}
		return s;
	}

	@Override
	public void close() {
		runtime.releaseSession(session);
		runtime.releaseEnv(env);
	}
}
