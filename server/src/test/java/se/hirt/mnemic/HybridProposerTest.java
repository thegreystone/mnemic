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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine.Consolidation;
import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.model.ScriptedModelProvider;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.ModelProposer;
import se.hirt.mnemic.proposal.ModelProposer.Mode;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mnemic.TestHomes.OWNER;
import static se.hirt.mnemic.TestHomes.SOFT_LIMIT;
import static se.hirt.mnemic.TestHomes.fresh;
import static se.hirt.mnemic.TestHomes.proposal;

/**
 * The hybrid mode: a model the server is configured with proposes when the assistant did not (README, "Hybrid
 * mode"). The assistant's own proposal always wins; a failure is a warning, never a lost observation.
 */
class HybridProposerTest {

	private static Engine engine(String name, String spec, Mode mode) {
		return new Engine(fresh(name), "test", SOFT_LIMIT, OWNER, Clock.systemUTC(),
				ModelProposer.configure(spec, null, mode));
	}

	@BeforeEach
	void reset() {
		ScriptedModelProvider.CALLS.set(0);
	}

	@Test
	void syncModeProposesInsideRemember() {
		try (Engine e = engine("hybrid-sync", "scripted:canned", Mode.SYNC)) {
			RememberOutcome o = e.remember("I joined Hooli in 2018.", Source.user(), null, null, null, null);
			assertEquals("server:scripted:canned", o.proposalSource());
			assertEquals(1, o.applied().facts().size(), o.applied().toString());
			assertEquals("works_at", o.applied().facts().getFirst().predicate());
			assertEquals(1, ScriptedModelProvider.CALLS.get());
			assertEquals(0, e.observations().pendingProposals(), "no backlog: the server proposed");
			assertNotNull(e.observations().get(o.observation().observationId()).orElseThrow().proposalJson(),
					"the proposal is stored with the observation for re-derivation");
			assertEquals("scripted:canned", e.observations().proposerOf(o.observation().observationId()));
		}
	}

	@Test
	void theAssistantsOwnProposalWins() {
		try (Engine e = engine("hybrid-host", "scripted:canned", Mode.SYNC)) {
			RememberOutcome o = e.remember("I live in Zürich.", Source.user(), null,
					proposal().entity("e1", "Zürich", "place").fact("self", "lives_in", "e1").build(), 1, null);
			assertEquals("assistant", o.proposalSource());
			assertEquals("lives_in", o.applied().facts().getFirst().predicate());
			assertEquals(0, ScriptedModelProvider.CALLS.get(), "the configured model was not asked");
			assertNull(e.observations().proposerOf(o.observation().observationId()));
		}
	}

	@Test
	void deferredModeLeavesItToConsolidate() {
		try (Engine e = engine("hybrid-deferred", "scripted:canned", Mode.DEFERRED)) {
			RememberOutcome o = e.remember("I joined Hooli in 2018.", Source.user(), null, null, null, null);
			assertEquals("none", o.proposalSource());
			assertTrue(o.applied().facts().isEmpty());
			assertEquals(1, e.observations().pendingProposals());
			assertEquals(0, ScriptedModelProvider.CALLS.get());

			Consolidation dry = e.consolidate(true);
			assertEquals(1, dry.backlog().size());
			assertTrue(dry.proposed().isEmpty(), "dry run proposes nothing");
			assertEquals(0, ScriptedModelProvider.CALLS.get());

			Consolidation c = e.consolidate(false);
			assertEquals(1, c.proposed().size(), c.toString());
			assertEquals(1, ScriptedModelProvider.CALLS.get());
			assertEquals(0, e.observations().pendingProposals());
			assertEquals(1, e.facts().count());
			assertEquals("scripted:canned", e.observations().proposerOf(o.observation().observationId()));
		}
	}

	@Test
	void anUnreachableProposerIsAWarningNotAnError() {
		try (Engine e = engine("hybrid-broken", "scripted:broken", Mode.SYNC)) {
			RememberOutcome o = e.remember("I joined Hooli in 2018.", Source.user(), null, null, null, null);
			assertEquals("none", o.proposalSource());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("could not be reached")),
					o.applied().warnings().toString());
			assertEquals(1, e.observations().pendingProposals(), "still in the backlog for a later consolidate");
			assertFalse(e.observations().get(o.observation().observationId()).isEmpty(), "observation stored");
		}
	}

	@Test
	void proseInsteadOfJsonIsAWarning() {
		try (Engine e = engine("hybrid-garbage", "scripted:garbage", Mode.SYNC)) {
			RememberOutcome o = e.remember("I joined Hooli in 2018.", Source.user(), null, null, null, null);
			assertEquals("none", o.proposalSource());
			assertTrue(o.applied().warnings().stream().anyMatch(w -> w.contains("not a proposal")),
					o.applied().warnings().toString());
			assertEquals(1, e.observations().pendingProposals());
		}
	}

	@Test
	void connectorObservationsAreProposedByTheServerToo() {
		try (Engine e = engine("hybrid-connector", "scripted:canned", Mode.SYNC)) {
			Source mail = new Source("connector", "imap:42", null, null, null);
			RememberOutcome o = e.remember("Subject: welcome\n\nI joined Hooli in 2018.", mail, null, null, null, null);
			assertEquals("server:scripted:canned", o.proposalSource(),
					"connectors may not propose, but the server's configured model may read what they bring");
			assertEquals(1, o.applied().facts().size());
		}
	}
}
