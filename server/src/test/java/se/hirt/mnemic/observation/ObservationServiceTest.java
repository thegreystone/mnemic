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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.observation;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.engine;
import static se.hirt.mnemic.TestHomes.remember;

/** Unit tests for {@link ObservationService} batched lookups. */
class ObservationServiceTest {

	@Test
	void retiredAmongHandlesEmptyNullDuplicatesAndMixedStates() {
		try (Engine e = engine("obs-retired-among")) {
			ObservationService obs = e.observations();
			assertTrue(obs.retiredAmong(null).isEmpty());
			assertTrue(obs.retiredAmong(List.of()).isEmpty());

			long live = remember(e, "Live observation.").observationId();
			long retired = remember(e, "Retired observation.").observationId();
			long forgotten = remember(e, "Forgotten observation.").observationId();
			assertTrue(obs.retire(retired, "superseded", live));
			assertTrue(obs.forget(forgotten));

			var input = new ArrayList<Long>();
			input.add(live);
			input.add(retired);
			input.add(retired); // duplicate
			input.add(forgotten);
			input.add(999_999L); // missing
			input.add(null);

			assertEquals(Set.of(retired), obs.retiredAmong(input));
		}
	}

	@Test
	void retiredAmongBatchesAcrossMoreThanFiveHundredIds() {
		try (Engine e = engine("obs-retired-batch")) {
			ObservationService obs = e.observations();
			var allIds = new ArrayList<Long>();
			var expectedRetired = new HashSet<Long>();

			// Insert 620 observations directly in a single transaction to cross the 500-ID chunk boundary quickly.
			e.database().write(tx -> {
				for (int i = 0; i < 620; i++) {
					boolean retire = (i % 13 == 0);
					long id = tx.insert(
							"""
									INSERT INTO observation(text, source_kind, observed_at, recorded_at, content_hash, retired_at)
									VALUES (?, 'user', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', ?, ?)""",
							"Note " + i, "hash-" + i, retire ? "2026-01-02T00:00:00Z" : null);
					allIds.add(id);
					if (retire) {
						expectedRetired.add(id);
					}
				}
				return null;
			});

			assertTrue(expectedRetired.size() > 40);
			assertEquals(expectedRetired, obs.retiredAmong(allIds));
		}
	}
}
