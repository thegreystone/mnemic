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
package se.hirt.mnemic.knowledge;

import se.hirt.mnemic.persistence.Row;

import java.time.Instant;

/**
 * A structured assertion with subject, predicate, object (entity or literal), optional qualifier and scope, valid time
 * with precision and provenance, status, derivation kind, and its anchor in the source observation (DESIGN.md,
 * Knowledge model; DECISIONS.md §2.1, §2.6).
 * <p>
 * {@code status} is what happened to the row: {@code current} (accepted), {@code superseded} (replaced because an event
 * or a later fact closed it), {@code corrected} (replaced by {@code correct}), {@code pending} (held behind an open
 * question), {@code invalidated} (a base fact of a derivation changed). Whether an accepted fact still holds is a
 * matter of valid time, see {@link #state(Instant)}.
 */
public record Fact(long id, long subjectId, String predicate, Long objectId, String objectText, String qualifier,
                   Long scopeId, String validStart, String validStartPrecision, String validEnd,
                   String validEndPrecision, boolean ended, String status, String derivationKind, long observationId,
                   Long eventId, Integer spanStart, Integer spanEnd, String rendering, Integer specVersion,
                   int corroborations, String lastConfirmed, String startSource, String endSource, Long supersededBy,
                   Long questionId, String mode, Double callerConfidence) {

	/** Below this the caller said it was a belief, not a statement; the rendering says so (family E). */
	public static final double BELIEVED_BELOW = 0.6;

	public boolean believed() {
		return callerConfidence != null && callerConfidence < BELIEVED_BELOW;
	}

	/** {@code asserted}, {@code negated}, {@code only}, or {@code closure} (family Q). */
	public boolean asserted() {
		return "asserted".equals(mode);
	}

	public String ref() {
		return "f-" + id;
	}

	/** Accepted and not replaced; says nothing about whether the interval has ended. */
	public boolean current() {
		return "current".equals(status);
	}

	/**
	 * {@code current}, {@code future}, {@code ended}, or the non-current status. Future when the valid start is after
	 * today: a recorded plan ("owns the Zenit 4 from 2026-09-17") is not yet so, and a verdict that called it
	 * current asserted a delivery a week early (2026-09-10). Ended when the flag is set or the end has passed.
	 */
	public String state(Instant now) {
		if (!current()) {
			return status;
		}
		if (validStart != null && validStart.compareTo(day(now)) > 0) {
			return "future";
		}
		if (ended || (validEnd != null && validEnd.compareTo(day(now)) <= 0)) {
			return "ended";
		}
		return "current";
	}

	/**
	 * A plan whose date has passed without a word since: the start is on record, it is not in the future, and the
	 * last confirmation predates it. "Collect the car on the 17th" read as current on the 18th on the strength of
	 * the plan alone (2026-09-10). A restatement after the date, or a correction, clears it.
	 */
	public boolean due(Instant now) {
		return current() && !ended && validStart != null && validStart.compareTo(day(now)) <= 0
				&& lastConfirmed != null && lastConfirmed.compareTo(validStart) < 0;
	}

	/** Days from {@code now} until the valid start, for a future fact. */
	public long daysUntilStart(Instant now) {
		return java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(day(now)),
				java.time.LocalDate.parse(validStart.length() >= 10 ? validStart.substring(0, 10) : validStart + "-01-01".substring(validStart.length() - 4)));
	}

	/** True when the interval may contain {@code asOf}: unknown bounds do not exclude (EVALUATION.md C9). */
	public boolean mayHoldAt(Instant asOf) {
		String d = day(asOf);
		if (validStart != null && validStart.compareTo(d) > 0) {
			return false;
		}
		return validEnd == null || validEnd.compareTo(d) > 0;
	}

	/** True when a bound needed to place the fact at {@code asOf} is unknown. */
	public boolean partialBoundsAt(Instant asOf) {
		return validStart == null || (validEnd == null && ended);
	}

	static String day(Instant t) {
		return t.toString().substring(0, 10);
	}

	static Fact from(Row r) {
		return new Fact(r.lng("id"), r.lng("subject_id"), r.str("predicate"), r.lngOrNull("object_id"),
				r.str("object_text"), r.str("qualifier"), r.lngOrNull("scope_id"), r.str("valid_start"),
				r.str("valid_start_precision"), r.str("valid_end"), r.str("valid_end_precision"), r.lng("ended") == 1,
				r.str("status"), r.str("derivation_kind"), r.lng("observation_id"), r.lngOrNull("event_id"),
				r.intOrNull("span_start"), r.intOrNull("span_end"), r.str("rendering"), r.intOrNull("spec_version"),
				(int) r.lng("corroborations"), r.str("last_confirmed"), r.str("start_source"), r.str("end_source"),
				r.lngOrNull("superseded_by"), r.lngOrNull("question_id"), r.str("mode"), r.dblOrNull("caller_confidence"));
	}
}
