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

import se.hirt.mnemic.persistence.Database;

import java.time.Clock;
import java.util.List;

/**
 * The knowledge layer over one database, wired once: the registries, entities, events, the question queue, the fact
 * reads and writes, the resolver of answers, and consolidation. The write path ({@link FactService}) and the read path
 * ({@link FactQueries}) are separate so that recall and the tool surface depend on reads only.
 */
public record Knowledge(EntityService entities, EntityTypeRegistry entityTypes, PredicateRegistry predicates,
		EventTypeRegistry eventTypes, EventService events, QuestionService questions, FactQueries facts,
		FactService factService, QuestionResolver resolver, Consolidator consolidator, FactRenderer renderer,
		Containment containment) {

	public static Knowledge open(Database db, Lang lang, String ownerName, List<String> ownerIdentity, Clock clock) {
		var entityTypes = new EntityTypeRegistry(db);
		var predicates = new PredicateRegistry(db, lang, entityTypes);
		var eventTypes = new EventTypeRegistry(db);
		var entities = new EntityService(db, entityTypes, ownerName, ownerIdentity);
		var questions = new QuestionService(db);
		var renderer = new FactRenderer(db, predicates);
		var ledger = new FactLedger(renderer);
		var events = new EventService(db, eventTypes, ledger);
		var facts = new FactQueries(db, predicates, clock);
		var asks = new FactQuestions(questions, entities, facts);
		var factService = new FactService(db, entities, predicates, eventTypes, events, questions, facts, asks,
				renderer, ledger, entityTypes);
		var resolver = new QuestionResolver(db, entities, predicates, questions, factService, ledger);
		var consolidator = new Consolidator(db, entities, predicates, events, facts, resolver, ledger, renderer);
		return new Knowledge(entities, entityTypes, predicates, eventTypes, events, questions, facts, factService,
				resolver, consolidator, renderer, new Containment(db));
	}
}
