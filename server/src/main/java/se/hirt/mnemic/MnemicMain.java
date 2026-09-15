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

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point. MCP over STDIO is a UTF-8 protocol, but a piped {@code System.out} defaults to the platform encoding on
 * Windows (cp1252), which mangles every non-ASCII character. The STDIO transport captures {@code System.out} during
 * runtime init, so it has to be rewrapped before Quarkus boots. Stdin is fine: the default charset has been UTF-8 since
 * JDK 18.
 * <p>
 * Desktop MCP hosts on Windows may spawn the server with {@code C:\WINDOWS\system32} as the working directory,
 * so the working directory is never relied on. Quarkus lists {@code ${user.dir}/config} during boot, and
 * {@code system32\config} exists but cannot be listed, so boot fails with {@code AccessDeniedException}. If the working
 * directory's {@code config} entry cannot be listed, {@code user.dir} is redirected to the data home before Quarkus
 * starts. Nothing in the server resolves paths relative to the working directory.
 */
@QuarkusMain
public class MnemicMain {

	public static void main(String... args) {
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
		ensureScannableWorkingDirectory();
		Quarkus.run(args);
	}

	private static void ensureScannableWorkingDirectory() {
		String userDir = System.getProperty("user.dir");
		if (userDir == null || canList(Paths.get(userDir, "config"))) {
			return;
		}
		Path home = home();
		try {
			Files.createDirectories(home);
		} catch (IOException e) {
			return; // Quarkus will report the real problem
		}
		System.setProperty("user.dir", home.toAbsolutePath().toString());
	}

	/** True when the path is absent, or is a directory that can be listed. Mirrors what Quarkus does at boot. */
	private static boolean canList(Path dir) {
		if (!Files.exists(dir) || !Files.isDirectory(dir)) {
			return true;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			return true;
		} catch (IOException | SecurityException e) {
			return false;
		}
	}

	/** Same resolution order as {@code mnemic.home}: system property, {@code MNEMIC_HOME}, then {@code ~/.mnemic}. */
	static Path home() {
		String configured = System.getProperty("mnemic.home");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("MNEMIC_HOME");
		}
		if (configured == null || configured.isBlank()) {
			configured = System.getProperty("user.home") + "/.mnemic";
		}
		return Paths.get(configured);
	}
}
