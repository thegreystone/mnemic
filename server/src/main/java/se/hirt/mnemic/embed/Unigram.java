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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Unigram (sentencepiece) tokenizer read from a Hugging Face {@code tokenizer.json}, pure Java (DECISIONS.md
 * §4: no native tokenizer). This is the tokenizer of XLM-RoBERTa and so of the granite multilingual embedders:
 * NFKC normalisation, the Metaspace pre-tokenizer (spaces become {@code ▁} and one is prefixed), then Viterbi
 * segmentation over the whole text by piece log-probabilities, unknown characters falling to {@code <unk>}.
 * Special tokens are added as the post-processor template says: {@code <s>} before, {@code </s>} after.
 *
 * <p>The precompiled character map inside the file's normalizer (sentencepiece's own NFKC table) is not
 * applied; Java's NFKC stands in for it. The two differ on a handful of code points, none of them letters
 * of the languages this store is used in.
 */
public final class Unigram implements Tokenizer {

	private static final char SPACE = '▁';

	private final Map<String, Integer> ids = new HashMap<>();
	private final double[] scores;
	private final int maxPiece;
	private final int unk;
	private final int bos;
	private final int eos;
	private final double minScore;
	/** Without a WhitespaceSplit step, Metaspace keeps a trailing space as one {@code ▁} piece; with it, none. */
	private final boolean whitespaceSplit;

	public Unigram(Path tokenizerJson) throws IOException {
		JsonNode root = new ObjectMapper().readTree(Files.readString(tokenizerJson));
		JsonNode model = root.get("model");
		if (model == null || !"Unigram".equals(model.path("type").asText())) {
			throw new IOException("Not a Unigram tokenizer: " + tokenizerJson);
		}
		JsonNode vocab = model.get("vocab");
		scores = new double[vocab.size()];
		int longest = 1;
		double min = 0;
		for (int i = 0; i < vocab.size(); i++) {
			String piece = vocab.get(i).get(0).asText();
			double score = vocab.get(i).get(1).asDouble();
			ids.put(piece, i);
			scores[i] = score;
			longest = Math.max(longest, piece.length());
			min = Math.min(min, score);
		}
		maxPiece = longest;
		minScore = min;
		unk = model.path("unk_id").asInt(3);
		int b = 0, e = 2;
		for (JsonNode t : root.path("added_tokens")) {
			if ("<s>".equals(t.path("content").asText())) {
				b = t.path("id").asInt();
			} else if ("</s>".equals(t.path("content").asText())) {
				e = t.path("id").asInt();
			}
		}
		bos = b;
		eos = e;
		whitespaceSplit = root.path("pre_tokenizer").toString().contains("WhitespaceSplit");
	}

	@Override
	public int size() {
		return scores.length;
	}

	/** Token ids for one text, with the special tokens, cut to {@code maxTokens}. */
	@Override
	public int[] encode(String text, int maxTokens) {
		List<Integer> pieces = segment(normalise(text, !whitespaceSplit));
		int body = Math.min(pieces.size(), maxTokens - 2);
		var out = new int[body + 2];
		out[0] = bos;
		for (int i = 0; i < body; i++) {
			out[i + 1] = pieces.get(i);
		}
		out[body + 1] = eos;
		return out;
	}

	/** NFKC, then the Metaspace pre-tokenizer: a leading {@code ▁} and one for every space. */
	static String normalise(String text) {
		return normalise(text, false);
	}

	/** As above; {@code keepTrailing} keeps a trailing space as a final {@code ▁}, as Metaspace alone does. */
	static String normalise(String text, boolean keepTrailing) {
		String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
		String n = nfkc.strip();
		boolean trailing = keepTrailing && !n.isEmpty() && nfkc.length() > 0 && Character.isWhitespace(nfkc.charAt(nfkc.length() - 1));
		var sb = new StringBuilder(n.length() + 2);
		sb.append(SPACE);
		boolean space = false;
		for (int i = 0; i < n.length(); i++) {
			char c = n.charAt(i);
			if (Character.isWhitespace(c)) {
				space = true;
				continue;
			}
			if (space) {
				sb.append(SPACE);
				space = false;
			}
			sb.append(c);
		}
		if (trailing) {
			sb.append(SPACE);
		}
		return sb.toString();
	}

	/** Viterbi over the text: the segmentation with the highest total log-probability. */
	List<Integer> segment(String text) {
		int n = text.length();
		double[] best = new double[n + 1];
		int[] backPiece = new int[n + 1];
		int[] backStart = new int[n + 1];
		java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
		best[0] = 0;
		for (int start = 0; start < n; start++) {
			if (best[start] == Double.NEGATIVE_INFINITY) {
				continue;
			}
			boolean any = false;
			int limit = Math.min(n, start + maxPiece);
			for (int end = start + 1; end <= limit; end++) {
				Integer id = ids.get(text.substring(start, end));
				if (id == null) {
					continue;
				}
				any = true;
				double score = best[start] + scores[id];
				if (score > best[end]) {
					best[end] = score;
					backPiece[end] = id;
					backStart[end] = start;
				}
			}
			if (!any || best[start + 1] == Double.NEGATIVE_INFINITY) {
				// No piece starts here: one code point becomes <unk>, at a penalty below every real piece.
				int end = start + Character.charCount(text.codePointAt(start));
				double score = best[start] + minScore - 10;
				if (score > best[end]) {
					best[end] = score;
					backPiece[end] = unk;
					backStart[end] = start;
				}
			}
		}
		var out = new ArrayList<Integer>();
		for (int at = n; at > 0; at = backStart[at]) {
			out.add(backPiece[at]);
		}
		java.util.Collections.reverse(out);
		return out;
	}
}
