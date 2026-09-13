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
import se.hirt.mnemic.proposal.Proposal;
import se.hirt.mnemic.protocol.MnemicException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shapes real models send that are one honest step from the spec (seen in the first Haiku pilot). */
class ProposalParseTest {

	@Test
	void listObjectBecomesOneFactPerElement() {
		Proposal p = Proposal.parse("""
		                            {"facts": [{"subject": "self", "predicate": "prefers", "object": ["e1", "e2", "e3"]},
		                                       {"subject": ["e4", "e5"], "predicate": "knows", "object": "self"}]}""");
		assertEquals(5, p.facts().size());
		assertEquals("e2", p.facts().get(1).object());
		assertEquals("e5", p.facts().get(4).subject());
		assertEquals("knows", p.facts().get(4).predicate());
	}

	@Test
	void unknownKeysAreNamedAndCertaintyIsRead() {
		Proposal.Parsed p = Proposal.parseWithWarnings("""
				{"facts": [{"subject": "self", "predicate": "uses", "object": "Probe Target Alpha",
				            "certainty": "believed", "confidence": 0.4, "mood": "tentative"}],
				 "notes": "ignored", "entities": [{"ref": "e1", "name": "X", "type": "thing", "nickname": "x"}]}""");
		assertEquals(0.4, p.proposal().facts().getFirst().callerConfidence(), 1e-9, "confidence wins when both are given");
		assertEquals(3, p.warnings().size(), p.warnings().toString());
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("'mood' on facts[0]")), p.warnings().toString());
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("'notes' on proposal")), p.warnings().toString());
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("'nickname' on entities[0]")), p.warnings().toString());
		Proposal.Parsed word = Proposal.parseWithWarnings("""
				{"facts": [{"subject": "self", "predicate": "uses", "object": "X", "certainty": "believed"}]}""");
		assertEquals(0.5, word.proposal().facts().getFirst().callerConfidence(), 1e-9);
		assertTrue(word.warnings().isEmpty(), word.warnings().toString());
	}

	@Test
	void repeatedKeyKeepsTheLastValue() {
		Proposal p = Proposal.parse("""
		                            {"facts": [{"subject": "self", "predicate": "works_at", "object": "e1",
		                                        "valid_time": {"start": "1980s", "end": "1998", "precision": "year", "precision": "year"}}]}""");
		assertEquals("year", p.facts().getFirst().validTime().precision());
	}

	@Test
	void truncatedReplyKeepsTheCompleteFacts() {
		Proposal p = Proposal.parse("""
		                            {"spec_version": 1,
		                             "entities": [{"ref": "e1", "name": "Anna", "type": "person"}],
		                             "facts": [
		                               {"subject": "self", "predicate": "knows", "object": "e1"},
		                               {"subject": "self", "predicate": "prefers", "object": "yoga, \\"hot\\" style"},
		                               {"subject": "self", "predicate": "prefers", "object": "push-u""");
		assertEquals(2, p.facts().size(), "the cut-off third fact is dropped");
		assertEquals("yoga, \"hot\" style", p.facts().get(1).object());
		assertEquals(1, p.entities().size());
	}

	@Test
	void truncationInsideTheFirstArrayStillYieldsTheEntities() {
		Proposal p = Proposal.parse("""
		                            {"entities": [{"ref": "e1", "name": "Anna", "type": "person"}, {"ref": "e2", "name": "Bo""");
		assertEquals(1, p.entities().size());
		assertEquals(0, p.facts().size());
	}

	@Test
	void bareStringDerivationIsItsKind() {
		Proposal p = Proposal.parse("""
		                            {"facts": [{"subject": "self", "predicate": "prefers", "object": "tea", "derivation": "inferred"},
		                                       {"subject": "self", "predicate": "prefers", "object": "milk", "derivation": {"kind": "explicit"}}]}""");
		assertEquals("inferred", p.facts().get(0).derivation().kind());
		assertEquals("explicit", p.facts().get(1).derivation().kind());
	}

	@Test
	void malformedJsonIsStillAnError() {
		assertThrows(MnemicException.class, () -> Proposal.parse("{\"facts\": [{\"object\": place}]}"));
	}
}
