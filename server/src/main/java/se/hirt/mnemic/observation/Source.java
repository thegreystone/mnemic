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
package se.hirt.mnemic.observation;

import se.hirt.mnemic.protocol.MnemicException;

import java.util.Set;

/**
 * Provenance of an observation (DESIGN.md, Knowledge model: Source). {@code kind} is closed because derivation kinds
 * are validated against it (a {@code document} source cannot yield an {@code explicit} fact, EVALUATION.md E4).
 *
 * @param kind
 *            user | assistant | conversation | document | connector | correction
 * @param ref
 *            document path, message id, conversation/session id
 * @param chunk
 *            index within {@code ref} when the caller chunked a larger source
 * @param assistant
 *            client name/version, when known
 * @param session
 *            caller-supplied session id, when known
 */
public record Source(String kind, String ref, Integer chunk, String assistant, String session) {

	public static final Set<String> KINDS = Set.of("user", "assistant", "conversation", "document", "connector",
			"correction");

	public static Source user() {
		return new Source("user", null, null, null, null);
	}

	public Source {
		if (kind == null || !KINDS.contains(kind)) {
			throw MnemicException.invalidArgument("source.kind must be one of " + KINDS + ", got '" + kind
					+ "'. Example: {\"kind\": \"user\"} for something the user said.");
		}
	}
}
