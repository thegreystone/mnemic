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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mnemic.TestHomes.engine;

/** EngineProducer startup backfill synchronization and path expansion. */
class EngineProducerTest {

	@Test
	void expandedPathExpandsLeadingTilde() {
		String home = System.getProperty("user.home");
		assertEquals(Path.of(home, "models"), EngineProducer.expandedPath("~/models"));
		assertEquals(Path.of(home), EngineProducer.expandedPath("~"));
		assertNull(MnemicMain.expandHome(null));
	}

	@Test
	void backfillWaitsForEngineReadyInsteadOfSilentlySkipping() throws Exception {
		EngineProducer producer = new EngineProducer();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();

		Thread worker = new Thread(() -> {
			started.countDown();
			try {
				producer.backfill(null);
			} catch (Throwable t) {
				error.set(t);
			} finally {
				finished.countDown();
			}
		});
		worker.setDaemon(true);
		worker.start();

		assertTrue(started.await(2, TimeUnit.SECONDS));
		assertFalse(finished.await(80, TimeUnit.MILLISECONDS),
				"backfill must wait on engineReady rather than returning immediately when engine is not yet constructed");
		producer.completeEngine(null);
		assertTrue(finished.await(2, TimeUnit.SECONDS), "backfill resumes once engineReady completes");
		assertNull(error.get());
	}

	@Test
	void closeCancelsEngineReadyAndUnblocksWaitingBackfill() throws Exception {
		EngineProducer producer = new EngineProducer();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();

		Thread worker = new Thread(() -> {
			started.countDown();
			try {
				producer.backfill(null);
			} catch (Throwable t) {
				error.set(t);
			} finally {
				finished.countDown();
			}
		});
		worker.setDaemon(true);
		worker.start();

		assertTrue(started.await(2, TimeUnit.SECONDS));
		assertFalse(finished.await(80, TimeUnit.MILLISECONDS));
		try (Engine e = engine("producer-close")) {
			producer.close(e);
		}
		assertTrue(finished.await(2, TimeUnit.SECONDS), "close cancels engineReady and unblocks backfill cleanly");
		assertNull(error.get());
	}
}
