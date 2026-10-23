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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The data home as configured: a leading {@code ~} is the user's home directory, anything else stands as written. */
class MnemicMainTest {

	@Test
	void aLeadingTildeIsTheHomeDirectory() {
		assertEquals("/home/alice/.mnemic", MnemicMain.expandHome("~/.mnemic", "/home/alice"));
		assertEquals("C:\\Users\\Alice\\.mnemic", MnemicMain.expandHome("~\\.mnemic", "C:\\Users\\Alice"));
		assertEquals("/home/alice", MnemicMain.expandHome("~", "/home/alice"));
	}

	@Test
	void otherPathsStandAsWritten() {
		assertEquals("/data/mnemic", MnemicMain.expandHome("/data/mnemic", "/home/alice"));
		assertEquals("C:\\Users\\Alice\\.mnemic", MnemicMain.expandHome("C:\\Users\\Alice\\.mnemic", "/home/x"));
		assertEquals("~tmp/x", MnemicMain.expandHome("~tmp/x", "/home/alice"), "a name that merely starts with ~");
	}
}
