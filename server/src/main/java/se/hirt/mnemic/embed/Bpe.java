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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-pair encoding as {@code tokenizer.json} describes it. The file's own pipeline is followed: the normalizer
 * (string replacement), the pre-tokenizer (a regex split into words, a split on a delimiter, or the GPT-2
 * byte-to-character map, in any sequence), merges applied by rank inside each word, byte fallback for characters the
 * vocabulary lacks, and the post-processor's template for the special tokens around the sequence. Anything the file
 * asks for that is not implemented is an error at load, never a silent difference.
 */
public final class Bpe implements Tokenizer {

	private final Map<String, Integer> vocab = new HashMap<>();
	private final Map<String, Integer> ranks = new HashMap<>();
	private final int size;
	private final boolean byteFallback;
	private final boolean ignoreMerges;
	private final boolean fuseUnk;
	private final Integer unk;
	private final int[] prefix;
	private final int[] suffix;
	private final List<String[]> replacements = new ArrayList<>(); // normalizer: pattern, replacement
	private final List<Step> steps = new ArrayList<>(); // pre-tokenizer
	private final Map<String, int[]> cache = new ConcurrentHashMap<>(); // one tokenizer, several engines

	private record Step(String kind, Pattern regex, String delimiter, String behavior, boolean prefixSpace) {
	}

	private static final String[] BYTE_CHARS = byteChars();

	Bpe(JsonNode root) {
		JsonNode model = root.get("model");
		JsonNode v = model.get("vocab");
		int max = -1;
		for (var e : v.properties()) {
			vocab.put(e.getKey(), e.getValue().asInt());
			max = Math.max(max, e.getValue().asInt());
		}
		for (JsonNode t : root.path("added_tokens")) {
			vocab.put(t.path("content").asText(), t.path("id").asInt());
			max = Math.max(max, t.path("id").asInt());
		}
		size = max + 1;
		int rank = 0;
		for (JsonNode m : model.path("merges")) {
			String a, b;
			if (m.isArray()) {
				a = m.get(0).asText();
				b = m.get(1).asText();
			} else {
				String s = m.asText();
				int sp = s.indexOf(' ');
				a = s.substring(0, sp);
				b = s.substring(sp + 1);
			}
			ranks.putIfAbsent(a + "\0" + b, rank++);
		}
		byteFallback = model.path("byte_fallback").asBoolean(false);
		ignoreMerges = model.path("ignore_merges").asBoolean(false);
		fuseUnk = model.path("fuse_unk").asBoolean(false);
		JsonNode unkNode = model.get("unk_token");
		unk = unkNode == null || unkNode.isNull() ? null : vocab.get(unkNode.asText());
		readNormalizer(root.get("normalizer"));
		readPreTokenizer(root.get("pre_tokenizer"));
		JsonNode post = root.get("post_processor");
		var before = new ArrayList<Integer>();
		var after = new ArrayList<Integer>();
		if (post != null && !post.isNull()) {
			if (!"TemplateProcessing".equals(post.path("type").asText())) {
				throw new IllegalArgumentException("Unsupported post_processor " + post.path("type").asText());
			}
			boolean seen = false;
			for (JsonNode piece : post.path("single")) {
				if (piece.has("Sequence")) {
					seen = true;
				} else if (piece.has("SpecialToken")) {
					String id = piece.path("SpecialToken").path("id").asText();
					for (JsonNode n : post.path("special_tokens").path(id).path("ids")) {
						(seen ? after : before).add(n.asInt());
					}
				}
			}
		}
		prefix = before.stream().mapToInt(Integer::intValue).toArray();
		suffix = after.stream().mapToInt(Integer::intValue).toArray();
	}

	private void readNormalizer(JsonNode n) {
		if (n == null || n.isNull()) {
			return;
		}
		switch (n.path("type").asText()) {
		case "Sequence" -> n.path("normalizers").forEach(this::readNormalizer);
		case "Replace" -> {
			JsonNode p = n.path("pattern");
			String pattern = p.has("String") ? Pattern.quote(p.path("String").asText()) : p.path("Regex").asText();
			replacements.add(new String[] {pattern, n.path("content").asText()});
		}
		default -> throw new IllegalArgumentException("Unsupported normalizer " + n.path("type").asText());
		}
	}

	private void readPreTokenizer(JsonNode n) {
		if (n == null || n.isNull()) {
			return;
		}
		switch (n.path("type").asText()) {
		case "Sequence" -> n.path("pretokenizers").forEach(this::readPreTokenizer);
		case "Split" -> {
			JsonNode p = n.path("pattern");
			String behavior = n.path("behavior").asText("Isolated");
			if (p.has("Regex")) {
				steps.add(new Step("split", Pattern.compile(p.path("Regex").asText(), Pattern.UNICODE_CHARACTER_CLASS),
						null, behavior, false));
			} else {
				steps.add(new Step("split", null, p.path("String").asText(), behavior, false));
			}
		}
		case "ByteLevel" -> steps.add(new Step("bytes", n.path("use_regex").asBoolean(false) ? GPT2 : null, null, null,
				n.path("add_prefix_space").asBoolean(false)));
		default -> throw new IllegalArgumentException("Unsupported pre_tokenizer " + n.path("type").asText());
		}
	}

	private static final Pattern GPT2 = Pattern.compile(
			"'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
			Pattern.UNICODE_CHARACTER_CLASS);

	@Override
	public int size() {
		return size;
	}

	@Override
	public int[] encode(String text, int maxTokens) {
		String t = text == null ? "" : text;
		for (String[] r : replacements) {
			t = t.replaceAll(r[0], Matcher.quoteReplacement(r[1]));
		}
		List<String> pieces = List.of(t);
		for (Step s : steps) {
			pieces = apply(s, pieces);
		}
		var ids = new ArrayList<Integer>();
		for (String piece : pieces) {
			if (piece.isEmpty()) {
				continue;
			}
			for (int id : word(piece)) {
				if (fuseUnk && unk != null && id == unk && !ids.isEmpty() && ids.getLast() == unk) {
					continue;
				}
				ids.add(id);
			}
		}
		int body = Math.max(0, Math.min(ids.size(), maxTokens - prefix.length - suffix.length));
		var out = new int[prefix.length + body + suffix.length];
		System.arraycopy(prefix, 0, out, 0, prefix.length);
		for (int i = 0; i < body; i++) {
			out[prefix.length + i] = ids.get(i);
		}
		System.arraycopy(suffix, 0, out, prefix.length + body, suffix.length);
		return out;
	}

	private static List<String> apply(Step s, List<String> pieces) {
		var out = new ArrayList<String>();
		for (String p : pieces) {
			if ("bytes".equals(s.kind())) {
				String in = s.prefixSpace() && !p.startsWith(" ") ? " " + p : p;
				if (s.regex() != null) {
					Matcher m = s.regex().matcher(in);
					while (m.find()) {
						out.add(bytesToChars(m.group()));
					}
				} else {
					out.add(bytesToChars(in));
				}
			} else if (s.regex() != null) {
				Matcher m = s.regex().matcher(p);
				int at = 0;
				while (m.find()) {
					if (m.start() > at) {
						out.add(p.substring(at, m.start()));
					}
					if (!"Removed".equals(s.behavior())) {
						out.add(m.group());
					}
					at = m.end();
				}
				if (at < p.length()) {
					out.add(p.substring(at));
				}
			} else {
				String d = s.delimiter();
				int at = 0;
				int idx;
				while ((idx = p.indexOf(d, at)) >= 0) {
					switch (s.behavior()) {
					case "MergedWithPrevious" -> out.add(p.substring(at, idx + d.length()));
					case "MergedWithNext" -> {
						if (idx > at) {
							out.add(p.substring(at, idx));
						}
						int next = p.indexOf(d, idx + d.length());
						out.add(p.substring(idx, next < 0 ? p.length() : next));
						at = next < 0 ? p.length() : next;
						continue;
					}
					case "Removed" -> {
						if (idx > at) {
							out.add(p.substring(at, idx));
						}
					}
					default -> { // Isolated
						if (idx > at) {
							out.add(p.substring(at, idx));
						}
						out.add(d);
					}
					}
					at = idx + d.length();
				}
				if (at < p.length()) {
					out.add(p.substring(at));
				}
			}
		}
		return out;
	}

	/** The merges applied inside one pre-tokenized word, cached. */
	private int[] word(String piece) {
		int[] hit = cache.get(piece);
		if (hit != null) {
			return hit;
		}
		int[] ids = merge(piece);
		if (cache.size() < 50_000) {
			cache.put(piece, ids);
		}
		return ids;
	}

	private int[] merge(String piece) {
		if (ignoreMerges) {
			Integer whole = vocab.get(piece);
			if (whole != null) {
				return new int[] {whole};
			}
		}
		var initial = new ArrayList<String>();
		piece.codePoints().forEach(cp -> {
			String s = new String(Character.toChars(cp));
			if (byteFallback && !vocab.containsKey(s)) {
				for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
					initial.add(String.format("<0x%02X>", b & 0xFF));
				}
			} else {
				initial.add(s);
			}
		});
		List<String> syms = initial;
		while (syms.size() > 1) {
			int bestRank = Integer.MAX_VALUE;
			int bestAt = -1;
			for (int i = 0; i + 1 < syms.size(); i++) {
				Integer r = ranks.get(syms.get(i) + "\0" + syms.get(i + 1));
				if (r != null && r < bestRank) {
					bestRank = r;
					bestAt = i;
				}
			}
			if (bestAt < 0) {
				break;
			}
			String merged = syms.get(bestAt) + syms.get(bestAt + 1);
			// every occurrence of the pair, left to right
			var next = new ArrayList<String>(syms.size());
			for (int i = 0; i < syms.size(); i++) {
				if (i + 1 < syms.size() && syms.get(i).equals(syms.get(bestAt))
						&& syms.get(i + 1).equals(syms.get(bestAt + 1))) {
					next.add(merged);
					i++;
				} else {
					next.add(syms.get(i));
				}
			}
			syms = next;
		}
		var out = new int[syms.size()];
		for (int i = 0; i < syms.size(); i++) {
			Integer id = vocab.get(syms.get(i));
			if (id == null) {
				if (unk == null) {
					throw new IllegalStateException("No token for '" + syms.get(i) + "' and no unk token");
				}
				id = unk;
			}
			out[i] = id;
		}
		return out;
	}

	/** GPT-2's map from bytes to printable characters, so every byte string is a string of vocabulary characters. */
	private static String bytesToChars(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		var sb = new StringBuilder(bytes.length);
		for (byte b : bytes) {
			sb.append(BYTE_CHARS[b & 0xFF]);
		}
		return sb.toString();
	}

	private static String[] byteChars() {
		var out = new String[256];
		var direct = new LinkedHashMap<Integer, Integer>();
		for (int b = '!'; b <= '~'; b++) {
			direct.put(b, b);
		}
		for (int b = 0xA1; b <= 0xAC; b++) {
			direct.put(b, b);
		}
		for (int b = 0xAE; b <= 0xFF; b++) {
			direct.put(b, b);
		}
		int n = 0;
		for (int b = 0; b < 256; b++) {
			Integer c = direct.get(b);
			if (c == null) {
				c = 256 + n++;
			}
			out[b] = new String(Character.toChars(c));
		}
		return out;
	}
}
