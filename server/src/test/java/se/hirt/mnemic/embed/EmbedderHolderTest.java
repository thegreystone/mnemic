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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.embed;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.fresh;
import static se.hirt.mnemic.TestHomes.options;

/** Lifecycle safety and retry-after-failure behavior of {@link EmbedderHolder}. */
class EmbedderHolderTest {

	@Test
	void failedStartupCanBeRetriedAndConsolidateTriggersRetry() throws Exception {
		Path models = fresh("holder-retry");
		AtomicInteger attempts = new AtomicInteger();
		try (EmbedderHolder holder = new EmbedderHolder(models, List.of(), () -> {
			int n = attempts.incrementAndGet();
			throw new IOException("offline attempt " + n);
		}, models, "test-model", e -> {
		})) {
			assertFalse(holder.await(5_000));
			assertEquals("failed", holder.state().state());
			assertEquals(1, attempts.get());

			// Direct retry re-runs the background worker.
			assertTrue(holder.retryIfFailed());
			assertFalse(holder.await(5_000));
			assertEquals("failed", holder.state().state());
			assertEquals(2, attempts.get());

			// Engine.consolidate(dryRun=true) does not retry; consolidate(dryRun=false) does.
			try (Engine e = new Engine(options(fresh("holder-consolidate")).withEmbedder(holder))) {
				e.consolidate(true);
				assertEquals(2, attempts.get(), "dryRun must not trigger background retry");
				e.consolidate(false);
				assertFalse(holder.await(5_000));
				assertEquals(3, attempts.get(), "non-dryRun consolidate retries a failed embedder");
			}
		}
		assertFalse(EmbedderHolder.off("disabled").retryIfFailed());
	}

	@Test
	void closeDuringLoadingPreventsCallbacksAndFurtherRetries() throws Exception {
		Path models = fresh("holder-close");
		CountDownLatch resolving = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean callbackCalled = new AtomicBoolean();
		EmbedderHolder holder = new EmbedderHolder(models, List.of(), () -> {
			resolving.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new IOException("aborted");
		}, models, "test-model", e -> callbackCalled.set(true));

		assertTrue(resolving.await(2, TimeUnit.SECONDS));
		holder.close();
		release.countDown();
		holder.await(2_000);

		assertNull(holder.get());
		assertFalse(callbackCalled.get());
		assertFalse(holder.retryIfFailed(), "closed holder refuses further retries");
	}
}
