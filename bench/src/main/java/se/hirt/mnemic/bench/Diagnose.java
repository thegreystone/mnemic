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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies a wrong answer (Memento's {@code diagnose_failures.py} idea, PLAN.md): the reference answer was in the
 * context the reader saw (ANSWER_ERROR) or it was not (RETRIEVAL_MISS). Abstention items are ABSTENTION_MISS when the
 * reader answered instead of declining. The heuristic is a substring check, so treat it as a triage signal.
 */
public final class Diagnose {

	public enum Cause {
		CORRECT, RETRIEVAL_MISS, ANSWER_ERROR, ABSTENTION_MISS
	}

	private static final Pattern REFUSAL = Pattern.compile(
			"(do not|don't|does not|doesn't|cannot|can't|no) (have|contain|find|include|know)|not (available|" + "mentioned|provided|enough|sufficient)|no information|unable to|insufficient",
			Pattern.CASE_INSENSITIVE);

	private Diagnose() {
	}

	public static Cause classify(boolean correct, boolean abstention, String reference, String context) {
		if (correct) {
			return Cause.CORRECT;
		}
		if (abstention) {
			return Cause.ABSTENTION_MISS;
		}
		return referenceInContext(reference, context) ? Cause.ANSWER_ERROR : Cause.RETRIEVAL_MISS;
	}

	/** True when the hypothesis reads as a refusal; used for abstention precision on answerable items. */
	public static boolean looksLikeRefusal(String hypothesis) {
		return hypothesis != null && REFUSAL.matcher(hypothesis).find();
	}

	static boolean referenceInContext(String reference, String context) {
		if (reference == null || context == null) {
			return false;
		}
		String ref = normalise(reference);
		if (ref.isEmpty()) {
			return false;
		}
		String ctx = normalise(context);
		if (ctx.contains(ref)) {
			return true;
		}
		// Multi-part references ("X and Y", "X, Y"): count it present when every part of three or more chars is.
		String[] parts = ref.split(",| and ");
		int considered = 0;
		for (String p : parts) {
			String t = p.trim();
			if (t.length() >= 3) {
				considered++;
				if (!ctx.contains(t)) {
					return false;
				}
			}
		}
		return considered > 0;
	}

	private static String normalise(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ,]", " ").replaceAll("\\s+", " ").trim();
	}
}
