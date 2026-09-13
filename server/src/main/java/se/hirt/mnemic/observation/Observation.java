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
package se.hirt.mnemic.observation;

import se.hirt.mnemic.persistence.Row;

import java.time.Instant;

/**
 * What was presented to Mnemic, verbatim, with its provenance and both timestamps (DESIGN.md: the unit of provenance,
 * forgetting, and re-derivation).
 */
public record Observation(long id, String text, Source source, Instant observedAt, Instant recordedAt,
                          String proposalJson, Integer specVersion, boolean forgotten, String retiredAt,
                          String retiredReason, Long supersededBy) {

	/** The shape before retirement existed (schema 18): not forgotten, not retired. */
	public Observation(long id, String text, Source source, Instant observedAt, Instant recordedAt, String proposalJson,
			Integer specVersion, boolean forgotten) {
		this(id, text, source, observedAt, recordedAt, proposalJson, specVersion, forgotten, null, null, null);
	}

	public String ref() {
		return "obs-" + id;
	}

	/** Recorded wrongly or superseded: kept, but not a hit unless history is asked for. */
	public boolean retired() {
		return retiredAt != null;
	}

	public static Observation from(Row r) {
		Source source = new Source(r.str("source_kind"), r.str("source_ref"), r.intOrNull("source_chunk"),
				r.str("assistant"), r.str("session"));
		return new Observation(r.lng("id"), r.str("text"), source, Instant.parse(r.str("observed_at")),
				Instant.parse(r.str("recorded_at")), r.str("proposal_json"), r.intOrNull("spec_version"),
				r.str("forgotten_at") != null, r.str("retired_at"), r.str("retired_reason"), r.lngOrNull("superseded_by"));
	}
}
