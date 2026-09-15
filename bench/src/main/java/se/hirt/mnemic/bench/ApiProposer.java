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
package se.hirt.mnemic.bench;

import se.hirt.mnemic.model.ChatModel;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.proposal.Proposal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Plays the assistant's part in the benchmark: sends each observation with the extraction spec to a model and parses
 * the proposal it returns. Replies go through a {@link ProposalCache} when one is given, so a paid run is never
 * repeated; {@link #prefetch} sends many observations at once with a bounded number of workers, the engine itself stays
 * single-threaded. Failures to parse are counted, never fatal: the observation is still stored without a proposal, as
 * in production.
 */
public final class ApiProposer implements AutoCloseable {

	private static final int MAX_CONSECUTIVE_ERRORS = 10;

	private final ChatModel model;
	private final String spec;
	private final ProposalCache cache;
	private final boolean refreshFailed;
	private final ExecutorService workers;
	private final AtomicInteger attempted = new AtomicInteger();
	private final AtomicInteger failed = new AtomicInteger();
	private final AtomicInteger cached = new AtomicInteger();
	private final AtomicInteger refreshed = new AtomicInteger();
	private final AtomicInteger errors = new AtomicInteger();
	private final AtomicInteger consecutiveErrors = new AtomicInteger();
	private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

	public ApiProposer(ChatModel model) {
		this(model, null, 1);
	}

	public ApiProposer(ChatModel model, ProposalCache cache, int workers) {
		this(model, cache, workers, false);
	}

	/** {@code refreshFailed}: a cached reply that does not parse is requested again and overwritten. */
	public ApiProposer(ChatModel model, ProposalCache cache, int workers, boolean refreshFailed) {
		this.model = model;
		this.spec = ModelProposer.spec();
		this.cache = cache;
		this.refreshFailed = refreshFailed;
		this.workers = Executors.newFixedThreadPool(Math.max(1, workers), r -> {
			Thread t = new Thread(r, "proposer");
			t.setDaemon(true);
			return t;
		});
	}

	public String model() {
		return model.id();
	}

	public int attempted() {
		return attempted.get();
	}

	public int failed() {
		return failed.get();
	}

	/** Replies served from the cache, no call made. */
	public int cached() {
		return cached.get();
	}

	/** Calls the endpoint refused or could not complete (counted in {@link #failed()} too, never cached). */
	public int errors() {
		return errors.get();
	}

	/** Cached replies that did not parse and were requested again ({@code --refresh-failed}). */
	public int refreshed() {
		return refreshed.get();
	}

	public Optional<Proposal> propose(String observation, String observedAt) throws IOException, InterruptedException {
		attempted.incrementAndGet();
		String user = ModelProposer.userMessage(observation, observedAt);
		String reply = null;
		String key = cache == null ? null : ProposalCache.key(model.id(), spec, user);
		if (cache != null) {
			reply = cache.get(key).orElse(null);
			if (reply != null && refreshFailed && parse(reply).isEmpty()) {
				refreshed.incrementAndGet();
				reply = null;
			}
			if (reply != null) {
				cached.incrementAndGet();
			}
		}
		if (reply == null) {
			// One call per distinct request even when the same session is queued twice at once.
			CompletableFuture<String> mine = new CompletableFuture<>();
			CompletableFuture<String> owner = key == null ? mine : inFlight.putIfAbsent(key, mine);
			if (owner != null && owner != mine) {
				try {
					reply = owner.get();
					cached.incrementAndGet();
				} catch (ExecutionException e) {
					throw new IOException(e.getCause());
				}
			} else {
				try {
					reply = model.chat(spec, user);
					if (cache != null) {
						cache.put(key, reply);
					}
					mine.complete(reply);
					consecutiveErrors.set(0);
				} catch (IOException e) {
					// One session the model cannot take (context exceeded, a transient 5xx) is stored without a
					// proposal, as in production, and is not cached so a later run asks again. A run of errors means
					// the endpoint is gone, and that is fatal.
					mine.completeExceptionally(e);
					failed.incrementAndGet();
					errors.incrementAndGet();
					System.err.println("  proposal failed (" + errors.get() + "): " + firstLine(e.getMessage()));
					if (consecutiveErrors.incrementAndGet() >= MAX_CONSECUTIVE_ERRORS) {
						throw new IOException(
								MAX_CONSECUTIVE_ERRORS + " proposals in a row failed; last: " + e.getMessage(), e);
					}
					return Optional.empty();
				} catch (RuntimeException | InterruptedException e) {
					mine.completeExceptionally(e);
					throw e;
				} finally {
					if (key != null) {
						inFlight.remove(key, mine);
					}
				}
			}
		}
		Optional<Proposal> parsed = parse(reply);
		if (parsed.isEmpty()) {
			failed.incrementAndGet();
		}
		return parsed;
	}

	private static Optional<Proposal> parse(String reply) {
		try {
			return Optional.of(Proposal.parse(ModelProposer.extractJson(reply)));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Starts every proposal on the worker pool; results arrive in the order given. */
	public List<CompletableFuture<Optional<Proposal>>> prefetch(List<String> observations, List<String> observedAt) {
		return IntStream.range(0, observations.size()).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
			try {
				return propose(observations.get(i), observedAt.get(i));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}, workers)).toList();
	}

	@Override
	public void close() {
		workers.shutdown();
		try {
			workers.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		String line = nl >= 0 ? s.substring(0, nl) : s;
		return line.length() > 200 ? line.substring(0, 200) + "…" : line;
	}
}
