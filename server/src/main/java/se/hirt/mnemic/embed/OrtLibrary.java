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

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * The ONNX Runtime shared library, carried inside the build (2026-09-11). Code is never downloaded at run time:
 * the library for the platform the binary was built on is unpacked from the runtime's Maven Central artifact
 * when the server is built, pinned here by SHA-256 (a test fails the build when the artifact and the pin
 * disagree), and written to the models directory on first start because a shared library has to be a file
 * before the loader can open it. What is fetched at run time is the model, which is data.
 */
public final class OrtLibrary {

	public static final String VERSION = "1.29.0";

	private record Build(String platform, String file, String sha256) {
	}

	/** The four builds the artifact ships, hashed from onnxruntime-1.29.0.jar as published on Maven Central. */
	private static final List<Build> BUILDS = List.of(
			new Build("win-x64", "onnxruntime.dll", "c1bae2b15344db7e27ad4ec07d1408630d290700d0db031d973e954e46eabf48"),
			new Build("linux-x64", "libonnxruntime.so", "5715f06d8992ca8eeeddcce43df3a7d38f97d537052126f558e912cb312460ca"),
			new Build("linux-aarch64", "libonnxruntime.so", "a27d21126db312aa8f02f3d5eaebe466e991f51f469882e6d0407d5a8b64afda"),
			new Build("osx-aarch64", "libonnxruntime.dylib", "07c5a23fecedb27d9325b1b2ba0c87830173f87b64edf2b294e32931af5c09cb"));

	private OrtLibrary() {
	}

	/** The artifact's name for this machine, or null when it ships no build for it. */
	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");
		if (os.contains("win")) {
			return arm ? null : "win-x64";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return arm ? "osx-aarch64" : null;
		}
		if (os.contains("linux")) {
			return arm ? "linux-aarch64" : "linux-x64";
		}
		return null;
	}

	private static Build build() {
		String platform = platform();
		return platform == null ? null : BUILDS.stream().filter(b -> b.platform().equals(platform)).findFirst().orElse(null);
	}

	/** The classpath resource holding the library for this machine, or null. */
	public static String resource() {
		Build b = build();
		return b == null ? null : "ai/onnxruntime/native/" + b.platform() + "/" + b.file();
	}

	/** The pinned hash of this machine's library, or null. */
	public static String sha256() {
		Build b = build();
		return b == null ? null : b.sha256();
	}

	/** True when this build carries the library for the machine it runs on. */
	public static boolean bundled() {
		String r = resource();
		return r != null && OrtLibrary.class.getClassLoader().getResource(r) != null;
	}

	/** Where the library lives once written: {@code <models>/onnxruntime-<version>/<file>}. */
	public static Path target(Path modelsDir) {
		Build b = build();
		return b == null ? null : modelsDir.resolve("onnxruntime-" + VERSION).resolve(b.file());
	}

	/** One line for status: what this build carries for this machine. */
	public static String describe() {
		String platform = platform();
		if (platform == null) {
			return "no ONNX Runtime build for " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
		}
		return "ONNX Runtime " + VERSION + " for " + platform + (bundled() ? ", bundled" : ", not bundled in this build");
	}

	/**
	 * The library as a file, written from the build's copy when it is missing or does not match its pin, under the
	 * models directory's lock. Idempotent and quick when the file is there: one hash of the file.
	 */
	public static Path install(Path modelsDir) throws IOException {
		Build b = build();
		if (b == null) {
			throw new IOException(describe() + "; set MNEMIC_ORT_LIBRARY to a library you provide.");
		}
		if (!bundled()) {
			throw new IOException(describe() + "; set MNEMIC_ORT_LIBRARY to a library you provide.");
		}
		Path target = target(modelsDir);
		if (Files.exists(target) && b.sha256().equalsIgnoreCase(ModelFetcher.sha256(target))) {
			return target;
		}
		Files.createDirectories(modelsDir);
		try (FileChannel channel = FileChannel.open(modelsDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock()) {
			if (Files.exists(target) && b.sha256().equalsIgnoreCase(ModelFetcher.sha256(target))) {
				return target; // another process wrote it while we waited
			}
			Files.createDirectories(target.getParent());
			Path part = target.resolveSibling(b.file() + ".part");
			try (InputStream in = OrtLibrary.class.getClassLoader().getResourceAsStream(resource())) {
				Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			}
			String actual = ModelFetcher.sha256(part);
			if (!b.sha256().equalsIgnoreCase(actual)) {
				Files.deleteIfExists(part);
				throw new IOException("The bundled " + b.file() + " does not match its pin (got " + actual + "); refusing to use it.");
			}
			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return target;
		}
	}
}
