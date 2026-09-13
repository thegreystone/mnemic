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
package se.hirt.mnemic.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Local reasoning models: what the provider does with a thinking block and how the effort is chosen. */
class OpenAiCompatibleProviderTest {

	@Test
	void thinkingBlocksAreStripped() {
		assertEquals("{\"ok\": true}", OpenAiCompatibleProvider.stripThinking(
				"<think>\nLet me consider the JSON.\n</think>\n\n{\"ok\": true}"));
		assertEquals("{\"ok\": true}", OpenAiCompatibleProvider.stripThinking("{\"ok\": true}"));
		assertEquals("", OpenAiCompatibleProvider.stripThinking(null));
		assertEquals("a b", OpenAiCompatibleProvider.stripThinking("<thinking>x</thinking>a <think>y</think>b"));
	}

	@Test
	void unreachableEndpointGivesNoDescription() {
		assertEquals("", OpenAiCompatibleProvider.describeLoaded("http://127.0.0.1:9/v1", "proposer"));
		assertEquals(false, OpenAiCompatibleProvider.serverAnswers("http://127.0.0.1:9/v1"));
	}

	@Test
	void reasoningEffortDefaultsToNone() {
		String before = System.getProperty("mnemic.reasoning_effort");
		try {
			System.clearProperty("mnemic.reasoning_effort");
			assertEquals(System.getenv().getOrDefault("MNEMIC_REASONING_EFFORT", "none"),
					OpenAiCompatibleProvider.reasoningEffort());
			System.setProperty("mnemic.reasoning_effort", "low");
			assertEquals("low", OpenAiCompatibleProvider.reasoningEffort());
		} finally {
			if (before == null) {
				System.clearProperty("mnemic.reasoning_effort");
			} else {
				System.setProperty("mnemic.reasoning_effort", before);
			}
		}
	}
}
