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
package se.hirt.mnemic.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The two texts that tell an assistant how to work with the store, each kept in one file under {@code protocol/} and
 * served whole from there. The instructions are what every client is told when it connects (the {@code instructions}
 * field of the MCP initialize reply, which clients put in the system prompt); the guide is the detail behind them,
 * returned by {@code inspect('guide')} and named in the instructions.
 */
public final class Protocol {

	private static volatile String instructions;
	private static volatile String guide;

	private Protocol() {
	}

	/** The memory protocol: the loop and the standing rules, for every conversation. */
	public static String instructions() {
		String s = instructions;
		if (s == null) {
			instructions = s = read("protocol/instructions.md");
		}
		return s;
	}

	/** The proposal guide: reading an utterance and growing the vocabulary, fetched when writing a proposal. */
	public static String guide() {
		String s = guide;
		if (s == null) {
			guide = s = read("protocol/guide.md");
		}
		return s;
	}

	private static String read(String resource) {
		try (InputStream in = Protocol.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " missing from the server resources");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
