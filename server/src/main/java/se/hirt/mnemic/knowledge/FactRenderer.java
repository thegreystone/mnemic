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
import se.hirt.mnemic.persistence.Row;
import se.hirt.mnemic.persistence.Tx;
import se.hirt.mnemic.protocol.MnemicException;

/**
 * The words of a fact: the predicate's template filled in for the fact's mode, a belief marker when the caller was
 * unsure, and the temporal suffix. The rendering is derived from the columns and never the record, so it can be
 * recomputed whenever a template, the language, or a bound changes.
 */
public final class FactRenderer {

	private final Database db;
	private final PredicateRegistry predicates;
	private final Lang lang;

	public FactRenderer(Database db, PredicateRegistry predicates) {
		this.db = db;
		this.predicates = predicates;
		this.lang = predicates.lang();
	}

	public Lang lang() {
		return lang;
	}

	/** The sentence for a fact in its mode: the template, its negation, its restriction, or the closure sentence. */
	public String sentence(Predicate pred, String mode, String subject, String object, String scope, String qualifier) {
		String q = lang.qualifier(qualifier);
		return switch (mode == null ? "asserted" : mode) {
		case "negated" -> pred.renderNegated(lang, predicates.negatedTemplate(pred.name()), subject, object, scope, q);
		case "only" -> pred.renderOnly(lang, subject, object, scope, q);
		case "closure" -> pred.renderClosure(lang, subject, object);
		default -> pred.render(subject, object, scope, q);
		};
	}

	/** The marker a belief carries, or nothing for a statement. */
	String believed(Double callerConfidence) {
		return callerConfidence != null && callerConfidence < Fact.BELIEVED_BELOW ? lang.believed() : "";
	}

	/** Recomputes one fact's rendering from its columns and stores it; the new rendering. */
	String rerender(Tx tx, long factId) {
		Fact f = Fact.from(tx.queryOne("SELECT * FROM fact WHERE id = ?", factId).orElseThrow());
		return rerender(tx, f, predicates.get(f.predicate()).orElseThrow());
	}

	private String rerender(Tx tx, Fact f, Predicate p) {
		String objectName = f.objectId() != null ? nameIn(tx, f.objectId()) : f.objectText();
		String rendering = sentence(p, f.mode(), nameIn(tx, f.subjectId()), objectName,
				f.scopeId() == null ? null : nameIn(tx, f.scopeId()), f.qualifier()) + believed(f.callerConfidence())
				+ Bounds.of(f).suffix(f.ended(), lang);
		tx.update("UPDATE fact SET rendering = ? WHERE id = ?", rendering, f.id());
		return rendering;
	}

	/** Recomputes the renderings of every fact that mentions an entity, after its name changed; the count. */
	public int rerenderMentioning(long entityId) {
		return db.write(tx -> {
			int n = 0;
			for (Row r : tx.query("SELECT * FROM fact WHERE subject_id = ? OR object_id = ? OR scope_id = ?", entityId,
					entityId, entityId)) {
				rerender(tx, r.lng("id"));
				n++;
			}
			return n;
		});
	}

	/** Re-renders every fact under a predicate, after its template changed (EVALUATION.md J5); the count. */
	public int rerender(String predicate) {
		Predicate p = predicates.get(predicate)
				.orElseThrow(() -> MnemicException.notFound("No predicate " + predicate));
		return db.write(tx -> {
			int n = 0;
			for (Row r : tx.query("SELECT * FROM fact WHERE predicate = ?", p.name())) {
				rerender(tx, Fact.from(r), p);
				n++;
			}
			return n;
		});
	}

	/**
	 * Every fact under every predicate: after a migration or a language change, so no rendering predates its template.
	 */
	public int rerenderAll() {
		int n = 0;
		for (Predicate p : predicates.all()) {
			n += rerender(p.name());
		}
		return n;
	}

	static String nameIn(Tx tx, long entityId) {
		return tx.queryOne("SELECT name FROM entity WHERE id = ?", entityId).map(r -> r.str("name"))
				.orElse("ent-" + entityId);
	}
}
