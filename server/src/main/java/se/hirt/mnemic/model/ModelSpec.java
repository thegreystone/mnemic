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

/**
 * A model reference as written in configuration or on a command line: {@code provider:model[@endpoint]}, for example
 * {@code anthropic:claude-haiku-4-5}, {@code openai:gpt-4o-mini}, {@code lmstudio:qwen3-4b},
 * {@code openai-compatible:my-model@http://host:8080/v1}. A key never appears here, only the name of the environment
 * variable that holds it; providers know their conventional variable names.
 */
public record ModelSpec(String provider, String model, String endpoint, String apiKeyEnv) {

	public static ModelSpec parse(String text, String apiKeyEnvOverride) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("model spec is empty; expected provider:model[@endpoint]");
		}
		String s = text.trim();
		String endpoint = null;
		int at = s.indexOf('@');
		if (at > 0) {
			endpoint = s.substring(at + 1);
			s = s.substring(0, at);
		}
		int colon = s.indexOf(':');
		if (colon <= 0) {
			throw new IllegalArgumentException(
					"model spec '" + text + "' needs a provider prefix, e.g. " + "anthropic:claude-haiku-4-5, openai:gpt-4o-mini, lmstudio:<model-id>");
		}
		return new ModelSpec(s.substring(0, colon).toLowerCase(), s.substring(colon + 1), endpoint, apiKeyEnvOverride);
	}

	/** The first of the candidate environment variables that is set, or null. */
	public static String key(String override, String... conventional) {
		if (override != null && !override.isBlank()) {
			return System.getenv(override);
		}
		for (String name : conventional) {
			String v = System.getenv(name);
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}
}
