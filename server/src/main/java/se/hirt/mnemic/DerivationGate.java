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

import se.hirt.mnemic.knowledge.FactService.Applied;
import se.hirt.mnemic.knowledge.FactService.Corrected;
import se.hirt.mnemic.knowledge.FactService.EventOut;
import se.hirt.mnemic.knowledge.FactService.FactOut;
import se.hirt.mnemic.knowledge.FactService.Removed;
import se.hirt.mnemic.knowledge.Knowledge;
import se.hirt.mnemic.proposal.Proposal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a write-path mutation could affect any derived predicate so {@link se.hirt.mnemic.knowledge.Deriver}
 * only runs when relevant predicates, events, or closures changed.
 */
final class DerivationGate {

	private final Knowledge knowledge;

	DerivationGate(Knowledge knowledge) {
		this.knowledge = knowledge;
	}

	boolean needsDerivation(Proposal proposal, Applied applied, List<Map<String, Object>> resolved) {
		if (resolved != null && !resolved.isEmpty()) {
			return true;
		}
		if (proposal != null && (!proposal.predicates().isEmpty() || !proposal.closures().isEmpty())) {
			return true;
		}
		Set<String> relevant = knowledge.deriver().relevantPredicates();
		if (relevant.isEmpty() || applied == null) {
			return false;
		}
		for (FactOut f : applied.facts()) {
			if (relevant.contains(f.predicate())) {
				return true;
			}
		}
		for (EventOut ev : applied.events()) {
			if (eventAffectsDerivation(ev.type(), relevant)) {
				return true;
			}
		}
		for (Map<String, Object> s : applied.superseded()) {
			Object pred = s.get("predicate");
			if (pred == null || relevant.contains(String.valueOf(pred))) {
				return true;
			}
		}
		return false;
	}

	boolean needsDerivation(Removed removed) {
		if (removed == null) {
			return false;
		}
		if (removed.reopened() > 0 || removed.eventsUpdated() || !removed.events().isEmpty()) {
			return true;
		}
		if (removed.predicates().isEmpty()) {
			return false;
		}
		Set<String> relevant = knowledge.deriver().relevantPredicates();
		for (String p : removed.predicates()) {
			if (relevant.contains(p)) {
				return true;
			}
		}
		return false;
	}

	boolean needsDerivation(Corrected corrected) {
		if (corrected == null) {
			return false;
		}
		Set<String> relevant = knowledge.deriver().relevantPredicates();
		if (relevant.isEmpty()) {
			return false;
		}
		return (corrected.original() != null && relevant.contains(corrected.original().predicate()))
				|| (corrected.replacement() != null && relevant.contains(corrected.replacement().predicate()));
	}

	boolean eventAffectsDerivation(String type, Set<String> relevant) {
		if (relevant.isEmpty()) {
			return false;
		}
		return knowledge.eventTypes().get(type)
				.map(et -> et.endsEntity() || et.opens().stream().anyMatch(relevant::contains)
						|| et.closes().stream().anyMatch(relevant::contains)
						|| et.supersedes().stream().anyMatch(relevant::contains))
				.orElse(false);
	}
}
