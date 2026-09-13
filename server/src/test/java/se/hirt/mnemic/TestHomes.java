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

import se.hirt.mnemic.Engine.RememberOutcome;
import se.hirt.mnemic.knowledge.FactService.Resolve;
import se.hirt.mnemic.observation.ObservationService.Remembered;
import se.hirt.mnemic.observation.Source;
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.proposal.Proposal.*;
import se.hirt.mnemic.recall.RecallResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helpers: a fresh data home per test in {@code java.io.tmpdir}, an {@link Engine} without any container, and the
 * shorthand the scenario tests read like EVALUATION.md. {@link P} builds the structured proposal a well-behaved model
 * would send.
 */
public final class TestHomes {

	public static final int SOFT_LIMIT = 4000;
	public static final String OWNER = "Mattias Sandell";

	/** Placeholder identities, never the author's real ones (2026-09-10): a surname, an address, a GitHub handle. */
	public static final List<String> OWNER_IDENTITY = List.of("Sandell", "mattias@example.com", "sandell-example",
			"@sandell_example");

	private TestHomes() {
	}

	public static Path fresh(String name) {
		try {
			return Files.createTempDirectory("mnemic-" + name);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Engine engine(Path home) {
		return new Engine(home, "test", SOFT_LIMIT, OWNER, Clock.systemUTC(), null, 20, OWNER_IDENTITY);
	}

	public static Engine engine(String name) {
		return engine(fresh(name));
	}

	/** A store whose fact layer is rendered in {@code lang}. */
	public static Engine engine(Path home, se.hirt.mnemic.knowledge.Lang lang) {
		return new Engine(home, "test", SOFT_LIMIT, OWNER, Clock.systemUTC(), null, 20, OWNER_IDENTITY, null,
				(se.hirt.mnemic.embed.Embedder) null, lang);
	}

	/** An engine whose clock is fixed, for scenarios that reason about elapsed time (EVALUATION.md C7). */
	public static Engine engine(String name, Instant now) {
		return new Engine(fresh(name), "test", SOFT_LIMIT, OWNER, Clock.fixed(now, ZoneOffset.UTC), null, 20,
				OWNER_IDENTITY);
	}

	/** {@code remember(observation: "...")} with default source and observation time, no proposal. */
	public static Remembered remember(Engine e, String text) {
		return e.observations().remember(text, Source.user(), null, null, null, null);
	}

	public static Remembered remember(Engine e, String text, Instant observedAt) {
		return e.observations().remember(text, Source.user(), observedAt, null, null, null);
	}

	public static RememberOutcome remember(Engine e, String text, P proposal) {
		return remember(e, text, Source.user(), proposal);
	}

	public static RememberOutcome remember(Engine e, String text, Source source, P proposal) {
		return e.remember(text, source, null, proposal.build(), Proposal.CURRENT_SPEC_VERSION, null);
	}

	public static RememberOutcome remember(Engine e, String text, Instant observedAt, P proposal) {
		return e.remember(text, Source.user(), observedAt, proposal.build(), Proposal.CURRENT_SPEC_VERSION, null);
	}

	/** {@code remember} that also answers open questions (EVALUATION.md B2, D3). */
	public static RememberOutcome remember(Engine e, String text, P proposal, Resolve... resolves) {
		return e.remember(text, Source.user(), null, proposal == null ? null : proposal.build(),
				Proposal.CURRENT_SPEC_VERSION, null, List.of(resolves));
	}

	/** {@code recall(query: "...")} with the default budget. */
	public static RecallResult recall(Engine e, String query) {
		return e.recall().recall(query, null, 800, 10);
	}

	public static RecallResult recall(Engine e, String query, Instant asOf) {
		return e.recall().recall(query, asOf, 800, 10);
	}

	public static P proposal() {
		return new P();
	}

	/** A fact reference with every field, for the temporal scenarios. */
	public static FactRef fact(
			String subject, String predicate, String object, String qualifier, String scope,
			String start, String end, Boolean ended, List<String> derivedFrom, String derivationKind) {
		ValidTime vt = start == null && end == null ? null : new ValidTime(start, end, null);
		return new FactRef(subject, predicate, object, qualifier, scope, vt, ended, derivedFrom,
				derivationKind == null ? null : new Derivation(derivationKind), null, null, null);
	}

	/** Proposal builder: {@code proposal().entity("e1", "Hooli", "organization").fact("self", "works_at", "e1")}. */
	public static final class P {
		private final List<EntityRef> entities = new ArrayList<>();
		private final List<EventRef> events = new ArrayList<>();
		private final List<FactRef> facts = new ArrayList<>();
		private final List<PredicateDef> predicates = new ArrayList<>();

		public P entity(String ref, String name, String type) {
			entities.add(new EntityRef(ref, name, type, List.of()));
			return this;
		}

		public P entity(String ref, String name, String type, String... aliases) {
			entities.add(new EntityRef(ref, name, type, List.of(aliases)));
			return this;
		}

		public P event(String ref, String type, String start, String... participants) {
			events.add(new EventRef(ref, type, List.of(participants),
					start == null ? null : new ValidTime(start, null, null)));
			return this;
		}

		/** A fact about the owner. */
		public P fact(String predicate, String object) {
			return fact("self", predicate, object);
		}

		public P fact(String subject, String predicate, String object) {
			facts.add(new FactRef(subject, predicate, object, null, null, null, null, List.of(), null, null));
			return this;
		}

		public P fact(FactRef fact) {
			facts.add(fact);
			return this;
		}

		public P predicate(PredicateDef def) {
			predicates.add(def);
			return this;
		}

		public Proposal build() {
			return new Proposal(Proposal.CURRENT_SPEC_VERSION, List.copyOf(entities), List.copyOf(events),
					List.copyOf(facts), List.copyOf(predicates));
		}
	}
}
