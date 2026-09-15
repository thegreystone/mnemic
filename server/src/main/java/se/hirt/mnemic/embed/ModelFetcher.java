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
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * Fetches the embedding model on first use: the model graph and its tokenizer from the publisher's Hugging Face
 * repository. Data only; the runtime library that executes it is part of the build ({@link OrtLibrary}). Every file is
 * pinned by SHA-256 and written beside its target under a {@code .part} name until the hash checks, so a partial or
 * tampered download never becomes a model. One lock file per models directory keeps two servers on one machine from
 * fetching the same files twice. Nothing here blocks a server's start: {@link EmbedderHolder} runs it in the
 * background.
 */
public final class ModelFetcher {

	/** One file to fetch, verify, and place. */
	public record Item(String name, URI url, String sha256, long size, Path target) {
		/** The download in flight for this item, if any. */
		public Path part() {
			return target.resolveSibling(target.getFileName() + ".part");
		}
	}

	/** The default model, chosen in the embedder bake-off (BENCHMARKS.md): granite's 311m multilingual r2. */
	public static final String MODEL_ID = "granite-embedding-311m-multilingual-r2";

	/** The quantised build is tuned for x86 (AVX2); an ARM machine takes the full-precision graph. */
	static boolean quantised() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return !(arch.contains("aarch64") || arch.contains("arm64"));
	}

	/** Where the model and runtime live, shared by every data home on the machine: {@code ~/.mnemic/models}. */
	public static Path defaultModelsDir() {
		return Path.of(System.getProperty("user.home"), ".mnemic", "models");
	}

	/**
	 * The two files, pinned: what IBM publishes on Hugging Face (the hashes are the ones Hugging Face records for
	 * them). On x86 the graph is the 8-bit AVX2 build (313 MB, the same recall as the full-precision one on both the
	 * sample set and LongMemEval); on ARM the full-precision build (1.25 GB). {@code modelBase} lets a mirror laid out
	 * like the repository stand in; the hashes stay.
	 */
	public static List<Item> plan(Path modelsDir, String modelBase) {
		Path modelDir = modelsDir.resolve(MODEL_ID);
		String base = modelBase != null ? modelBase.replaceAll("/+$", "")
				: "https://huggingface.co/ibm-granite/" + MODEL_ID + "/resolve/main";
		Item graph = quantised()
				? new Item("model.onnx", URI.create(base + "/onnx/model_quint8_avx2.onnx"),
						"f1fdd44e7e1ac51f12ab7957c7bd092e064d596c288513bf9d326842f669edee", 313_421_909L,
						modelDir.resolve("model.onnx"))
				: new Item("model.onnx", URI.create(base + "/onnx/model.onnx"),
						"75f9f258bf5013f5fe8a4dad61dd0fd16ac0cbaa7a106e3d3f41c2d04a42d541", 1_247_170_481L,
						modelDir.resolve("model.onnx"));
		return List.of(graph,
				new Item("tokenizer.json", URI.create(base + "/tokenizer.json"),
						"0087c868b33bad550a78a08d19798cfd7f713cde4f020803b8f51f405503e15f", 33_384_821L,
						modelDir.resolve("tokenizer.json")));
	}

	/** True when every target of the plan is in place. */
	public static boolean complete(List<Item> plan) {
		return plan.stream().allMatch(i -> Files.exists(i.target()));
	}

	/** Bytes the plan will download, for progress. */
	public static long totalBytes(List<Item> plan) {
		return plan.stream().filter(i -> !Files.exists(i.target())).mapToLong(Item::size).sum();
	}

	/**
	 * Fetches every missing item under the directory's lock. {@code progress} receives cumulative bytes received.
	 * Throws on a network failure or a hash mismatch; what was completed stays, what was not is removed.
	 */
	public static void fetch(Path modelsDir, List<Item> plan, LongConsumer progress) throws IOException {
		Files.createDirectories(modelsDir);
		Path lockFile = modelsDir.resolve(".lock");
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock()) {
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
			long done = 0;
			for (Item item : plan) {
				if (Files.exists(item.target())) {
					continue; // another process got here first, or an earlier run
				}
				Files.createDirectories(item.target().getParent());
				Path part = item.part();
				long base = done;
				try {
					download(http, item, part, received -> progress.accept(base + received));
					done += item.size();
					String actual = sha256(part);
					if (!actual.equalsIgnoreCase(item.sha256())) {
						Files.deleteIfExists(part);
						throw new IOException(item.name() + " did not match its published hash (got " + actual
								+ ", expected " + item.sha256() + "); the download was discarded.");
					}
					Files.move(part, item.target(), StandardCopyOption.ATOMIC_MOVE,
							StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException | RuntimeException e) {
					Files.deleteIfExists(part);
					throw e;
				}
			}
		}
	}

	private static void download(HttpClient http, Item item, Path to, LongConsumer progress) throws IOException {
		if ("file".equalsIgnoreCase(item.url().getScheme())) {
			// A local mirror, or a test: no network.
			Files.copy(Path.of(item.url()), to, StandardCopyOption.REPLACE_EXISTING);
			progress.accept(Files.size(to));
			return;
		}
		try {
			HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(item.url()).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() / 100 != 2) {
				throw new IOException(
						"Fetching " + item.name() + " from " + item.url() + " returned " + resp.statusCode());
			}
			long received = 0;
			try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(to)) {
				byte[] buf = new byte[1 << 16];
				int n;
				long lastReport = 0;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					received += n;
					if (received - lastReport >= (1 << 20)) {
						progress.accept(received);
						lastReport = received;
					}
				}
			}
			progress.accept(received);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching " + item.name());
		}
	}

	static String sha256(Path file) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) > 0) {
					md.update(buf, 0, n);
				}
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
