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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The embedder's lifecycle, so a server starts in milliseconds whether or not the model is on disk. States: {@code off}
 * (disabled), {@code downloading} with a percentage and the bytes, {@code loading}, {@code ready}, and {@code failed}
 * with the reason. The engine and recall read {@link #get()} on every use and take whatever is there; once the embedder
 * arrives, {@code onReady} embeds what was stored before it, so an existing store catches up on its own. The runtime
 * library is never fetched: it comes from the build ({@link OrtLibrary}) or from configuration.
 */
public final class EmbedderHolder implements AutoCloseable {

	public record State(String state, int percent, String detail, long received, long total) {
		public State(String state, int percent, String detail) {
			this(state, percent, detail, 0, 0);
		}

		public static State off(String why) {
			return new State("off", 0, why);
		}
	}

	/** Where the runtime library comes from; resolved on the worker so a slow disk never delays the start. */
	@FunctionalInterface
	public interface LibrarySource {
		Path resolve() throws IOException;
	}

	private final AtomicReference<Embedder> embedder = new AtomicReference<>();
	private volatile State state = State.off("not configured");
	private volatile Path library;
	private volatile boolean closed;
	private final Path modelsDir;
	private final List<ModelFetcher.Item> plan;
	private final LibrarySource librarySource;
	private final Path modelDir;
	private final String modelId;
	private final Consumer<Embedder> onReady;
	private Thread worker;

	/** A holder around an embedder the caller built and owns, or around nothing. */
	public EmbedderHolder(Embedder fixed) {
		this.modelsDir = null;
		this.librarySource = null;
		this.modelDir = null;
		this.modelId = null;
		this.onReady = e -> {
		};
		this.plan = List.of();
		if (fixed != null) {
			embedder.set(fixed);
			state = new State("ready", 100, fixed.id());
		}
	}

	/** A holder that stays off, with the reason status shows. */
	public static EmbedderHolder off(String why) {
		EmbedderHolder h = new EmbedderHolder((Embedder) null);
		h.state = State.off(why);
		return h;
	}

	/**
	 * Loads the model from {@code library} and {@code modelDir} in the background, fetching the model first through
	 * {@code plan} into {@code modelsDir} when it is missing. {@code onReady} runs on the worker once the embedder
	 * exists (the engine uses it to embed what was stored before).
	 */
	public EmbedderHolder(Path modelsDir, List<ModelFetcher.Item> plan, LibrarySource library, Path modelDir,
			String modelId, Consumer<Embedder> onReady) {
		this.modelsDir = modelsDir;
		this.plan = plan;
		this.librarySource = library;
		this.modelDir = modelDir;
		this.modelId = modelId;
		this.onReady = onReady;
		startWorker();
	}

	private synchronized void startWorker() {
		if (closed) {
			return;
		}
		state = new State(ModelFetcher.complete(plan) ? "loading" : "downloading", 0, modelId);
		worker = new Thread(this::run, "mnemic-embedder");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Retries fetching and loading in the background if the previous attempt failed (e.g. the machine started offline).
	 * Returns true when a new attempt was started.
	 */
	public synchronized boolean retryIfFailed() {
		if (closed || librarySource == null || !"failed".equals(state.state())) {
			return false;
		}
		startWorker();
		return true;
	}

	private void run() {
		try {
			if (closed) {
				return;
			}
			this.library = librarySource.resolve();
			if (closed) {
				return;
			}
			if (!ModelFetcher.complete(plan)) {
				long total = Math.max(1, ModelFetcher.totalBytes(plan));
				String detail = "fetching " + (total >> 20) + " MB into " + modelsDir;
				state = new State("downloading", 0, detail, 0, total);
				ModelFetcher.fetch(modelsDir, plan, received -> {
					if (!closed) {
						state = new State("downloading", (int) Math.min(99, received * 100 / total), detail, received,
								total);
					}
				});
			}
			if (closed) {
				return;
			}
			state = new State("loading", 100, modelId);
			Embedder e = new Embedder(this.library, modelDir, modelId);
			synchronized (this) {
				if (closed) {
					e.close();
					return;
				}
				embedder.set(e);
				state = new State("ready", 100,
						modelId + ", " + e.dims() + " dimensions, ONNX Runtime " + e.runtimeVersion());
			}
			if (!closed) {
				onReady.accept(e);
			}
		} catch (Throwable t) {
			synchronized (this) {
				if (!closed) {
					state = new State("failed", 0,
							t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
				}
			}
		}
	}

	/** The embedder, or null until it is ready. */
	public Embedder get() {
		return embedder.get();
	}

	public State state() {
		return state;
	}

	/** The runtime library in use, once resolved. */
	public Path library() {
		return library;
	}

	/** Each file of the plan and where it stands: {@code done}, {@code fetching}, or {@code pending}. */
	public Map<String, String> files() {
		var out = new LinkedHashMap<String, String>();
		for (ModelFetcher.Item item : plan) {
			out.put(item.name(),
					Files.exists(item.target()) ? "done" : Files.exists(item.part()) ? "fetching" : "pending");
		}
		return out;
	}

	/** Waits up to {@code millis} for the background load; returns whether the embedder is ready. */
	public boolean await(long millis) throws InterruptedException {
		Thread w;
		synchronized (this) {
			w = worker;
		}
		if (w != null) {
			w.join(millis);
		}
		return embedder.get() != null;
	}

	@Override
	public void close() {
		Embedder e;
		Thread w;
		synchronized (this) {
			closed = true;
			e = embedder.getAndSet(null);
			w = worker;
		}
		if (w != null) {
			w.interrupt();
		}
		if (e != null) {
			e.close();
		}
	}
}
