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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime library is part of the build: this is the gate that fails the build when the artifact Maven unpacked and
 * the pin in the code disagree, so no library ever runs that the code did not name.
 */
class OrtLibraryTest {

	@Test
	void theBundledRuntimeMatchesItsPin() throws Exception {
		Assumptions.assumeTrue(OrtLibrary.platform() != null, "no ONNX Runtime build for this platform");
		assertTrue(OrtLibrary.bundled(),
				"the build must carry " + OrtLibrary.resource() + " (Maven unpacks it from the artifact)");
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream in = OrtLibrary.class.getClassLoader().getResourceAsStream(OrtLibrary.resource())) {
			assertNotNull(in);
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
		}
		assertEquals(OrtLibrary.sha256(), HexFormat.of().formatHex(md.digest()),
				"the bundled library is the pinned one");
		assertTrue(OrtLibrary.describe().contains("bundled"), OrtLibrary.describe());
	}

	@Test
	void installWritesTheLibraryOnceAndRepairsATamperedCopy() throws Exception {
		Assumptions.assumeTrue(OrtLibrary.platform() != null && OrtLibrary.bundled(), "no bundled runtime");
		Path models = Files.createTempDirectory("mnemic-ort");
		models.toFile().deleteOnExit();
		Path lib = OrtLibrary.install(models);
		assertEquals(OrtLibrary.target(models), lib);
		assertTrue(Files.exists(lib));
		assertEquals(OrtLibrary.sha256(), ModelFetcher.sha256(lib));
		var written = Files.getLastModifiedTime(lib);
		// A second install finds the file good and leaves it alone.
		assertEquals(lib, OrtLibrary.install(models));
		assertEquals(written, Files.getLastModifiedTime(lib));
		// A file that is not the pinned library is replaced, never loaded.
		Files.writeString(lib, "not the runtime", StandardCharsets.UTF_8);
		assertEquals(lib, OrtLibrary.install(models));
		assertEquals(OrtLibrary.sha256(), ModelFetcher.sha256(lib));
		try (var s = Files.list(lib.getParent())) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "no partial files remain");
		}
	}
}
