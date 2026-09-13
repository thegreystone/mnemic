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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-process tokenizers against the models' own: token ids produced by the Hugging Face {@code tokenizers}
 * library for twelve sentences (English, German, Swedish, odd whitespace, punctuation, CJK, emoji) from each
 * candidate's {@code tokenizer.json}, recorded in {@code reference-tokens.json}. A model whose tokens differ
 * would be embedded by a different model than the one measured. Runs when MNEMIC_BAKEOFF_MODELS names a
 * directory holding {@code <model>/tokenizer.json} for the models in the reference file.
 */
class TokenizerReferenceTest {

	@Test
	void everyTokenizerMatchesTheReferenceIds() throws Exception {
		String dir = System.getenv("MNEMIC_BAKEOFF_MODELS");
		Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "MNEMIC_BAKEOFF_MODELS not set");
		JsonNode ref = new ObjectMapper().readTree(getClass().getResourceAsStream("/embed/reference-tokens.json"));
		var sentences = new ArrayList<String>();
		ref.path("sentences").forEach(n -> sentences.add(n.asText()));
		var problems = new ArrayList<String>();
		int checked = 0;
		var models = ref.path("models").fields();
		while (models.hasNext()) {
			var e = models.next();
			Path tok = Path.of(dir, e.getKey(), "tokenizer.json");
			if (!Files.exists(tok)) {
				continue;
			}
			Tokenizer t = Tokenizer.load(tok);
			checked++;
			for (int i = 0; i < sentences.size(); i++) {
				List<Integer> expected = new ArrayList<>();
				e.getValue().get(i).forEach(n -> expected.add(n.asInt()));
				int[] actual = t.encode(sentences.get(i), 512);
				if (!expected.equals(Arrays.stream(actual).boxed().toList())) {
					problems.add(e.getKey() + " #" + i + " '" + sentences.get(i).replace("\n", "\\n").replace("\t", "\\t") + "'\n    expected "
							+ expected + "\n    actual   " + Arrays.toString(actual));
				}
			}
		}
		Assumptions.assumeTrue(checked > 0, "no tokenizer.json under " + dir);
		System.out.println("tokenizers checked: " + checked + ", mismatches: " + problems.size());
		problems.forEach(System.out::println);
		assertTrue(problems.isEmpty(), problems.size() + " tokenizations differ from the reference:\n" + String.join("\n", problems));
	}
}
