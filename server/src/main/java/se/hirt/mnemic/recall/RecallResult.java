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
package se.hirt.mnemic.recall;

import se.hirt.mnemic.knowledge.Event;
import se.hirt.mnemic.knowledge.Fact;
import se.hirt.mnemic.observation.Observation;

import java.time.Instant;
import java.util.List;

/**
 * What {@code recall} returns: the structured-probe outcome, events touching the spotted entities, the ranked and
 * budgeted hits (an observation window with the facts that anchored it), and the text block the model reads
 * (EXTRACTION.md, Channel provenance and the structured miss).
 *
 * @param candidates
 * 		number of distinct observations any channel produced before the budget
 * @param truncated
 * 		true when the budget cut off candidates
 */
public record RecallResult(String query, Instant asOf, Structured structured, List<Event> events, List<Hit> hits,
                           int candidates, int tokensUsed, int maxTokens, boolean truncated, String text) {

	/**
	 * The structured channel's verdict. {@code state} is {@code matched} (entity and predicate resolved, fact found),
	 * {@code miss} (both resolved, no such fact; the primary guard against near-miss answers), {@code future},
	 * {@code known_false} (decided by {@code decidedBy} on {@code basis}: negation, restriction, closure, or
	 * functional), {@code events}, {@code entity} (an entity was spotted without a predicate cue; its facts are the
	 * channel), or {@code unresolved} (no entity in the query). {@code chain} holds containment facts reached from the
	 * matched objects (a town's canton, its country); {@code bounds} the negations, restrictions, and closures on the
	 * probed subject and predicate; {@code notes} are appended to the verdict line.
	 */
	public record Structured(String state, String entity, String entityName, String predicate, String qualifier,
	                         List<Fact> facts, List<Fact> nearMisses, List<Fact> chain, List<Fact> bounds,
	                         Fact decidedBy, String basis, List<String> notes, List<Fact> future) {
		public Structured(String state, String entity, String entityName, String predicate, String qualifier,
		                  List<Fact> facts, List<Fact> nearMisses, List<Fact> chain) {
			this(state, entity, entityName, predicate, qualifier, facts, nearMisses, chain, List.of(), null, null,
					List.of(), List.of());
		}

		public boolean matched() {
			return "matched".equals(state);
		}

		public boolean knownFalse() {
			return "known_false".equals(state);
		}

		public Structured withState(String newState) {
			return new Structured(newState, entity, entityName, predicate, qualifier, facts, nearMisses, chain, bounds,
					decidedBy, basis, notes, future);
		}
	}

	/**
	 * @param channels
	 * 		the channels that produced this hit, in rank order
	 * @param score
	 * 		the fused score; higher is better
	 * @param shown
	 * 		the text placed in the block: the whole observation when it fits, else an excerpt
	 * @param facts
	 * 		the facts from this observation that anchored the hit (structured or lexical)
	 * @param nearMiss
	 * 		true when this hit is here only as a near miss of the structured probe
	 */
	public record Hit(Observation observation, List<String> channels, double score, String shown, int tokens,
	                  List<Fact> facts, boolean nearMiss) {
	}
}
