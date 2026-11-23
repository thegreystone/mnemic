/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.recall;

import org.junit.jupiter.api.Test;
import se.hirt.mnemic.Engine;
import se.hirt.mnemic.TestHomes;
import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService.Mention;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;
import se.hirt.mnemic.recall.Query.Bound;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for multilingual query analysis: language detection, cross-language homographs, German noun capitalisation
 * in {@link Query#namedThings}, Unicode-safe polar/past/forward detection, and kind extraction across English, German,
 * Swedish, French, and Spanish.
 */
class MultilingualQueryTest {

	@Test
	void detectsQueryLanguageAndSeparatesCrossLanguageHomographs() {
		assertEquals(Lang.EN, Query.detectLanguage("When did Mattias join Hooli?", Lang.DE));
		assertEquals(Lang.EN, Query.detectLanguage("Was Mattias at Hooli?", Lang.DE));
		assertEquals(Lang.EN, Query.detectLanguage("Does Mattias own a hat?", Lang.DE));
		assertEquals(Lang.EN, Query.detectLanguage("Does Mattias live in Schübelbach?", Lang.DE));
		assertEquals(Lang.EN, Query.detectLanguage("Did Mattias study at MIT?", Lang.DE));
		assertEquals(Lang.EN, Query.detectLanguage("Where is Zürich?", Lang.DE));

		assertEquals(Lang.DE, Query.detectLanguage("Wann war mein letzter Zahnarzttermin?", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Was besitzt Mattias?", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Hat Mattias Kinder?", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Hat Mattias ein Boot?", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Könnte Mattias morgen kommen?", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Welche Autos besitzt Mattias?", Lang.EN));

		assertEquals(Lang.SV, Query.detectLanguage("Äger Mattias en båt?", Lang.EN));
		assertEquals(Lang.SV, Query.detectLanguage("Var bor Mattias?", Lang.EN));
		assertEquals(Lang.SV, Query.detectLanguage("Var Mattias hemma?", Lang.EN));
		assertEquals(Lang.SV, Query.detectLanguage("Jobbade Mattias på Initrode?", Lang.EN));
		assertEquals(Lang.SV, Query.detectLanguage("Vilka fordon äger Mattias?", Lang.EN));
		assertEquals(Lang.SV, Query.detectLanguage("Måste Mattias resa snart?", Lang.EN));

		assertEquals(Lang.FR, Query.detectLanguage("Où habite Mattias ?", Lang.EN));
		assertEquals(Lang.FR, Query.detectLanguage("Est-ce que Mattias possède un bateau ?", Lang.EN));
		assertEquals(Lang.FR, Query.detectLanguage("Est-ce que Mattias a travaillé chez Initrode ?", Lang.EN));
		assertEquals(Lang.FR, Query.detectLanguage("Quelles voitures possède Mattias ?", Lang.EN));
		assertEquals(Lang.FR, Query.detectLanguage("Qui est la famille d'Oskar ?", Lang.EN));

		assertEquals(Lang.ES, Query.detectLanguage("¿Dónde vive Mattias?", Lang.EN));
		assertEquals(Lang.ES, Query.detectLanguage("¿Tiene Mattias un barco?", Lang.EN));
		assertEquals(Lang.ES, Query.detectLanguage("¿Trabajó Mattias en Initrode?", Lang.EN));
		assertEquals(Lang.ES, Query.detectLanguage("¿Qué coches tiene Mattias?", Lang.EN));
		assertEquals(Lang.ES, Query.detectLanguage("¿Quién es la familia de Oskar?", Lang.EN));

		// Ties / bare names fall back to the store language.
		assertEquals(Lang.EN, Query.detectLanguage("Mattias Sandell", Lang.EN));
		assertEquals(Lang.DE, Query.detectLanguage("Mattias Sandell", Lang.DE));
		assertEquals(Lang.SV, Query.detectLanguage("Mattias Sandell", Lang.SV));
		assertEquals(Lang.FR, Query.detectLanguage("Mattias Sandell", Lang.FR));
		assertEquals(Lang.ES, Query.detectLanguage("Mattias Sandell", Lang.ES));
	}

	@Test
	void germanCapitalisedNounsAreNotTreatedAsProperNamesWhileBrandsAndNonGermanNamesStay() {
		try (Engine e = TestHomes.engine("multilingual-named")) {
			Set<String> owner = Set.of("mattias", "sandell");
			assertEquals(List.of(), Query.namedThings("Wann war mein letzter Zahnarzttermin?", owner, e.entityTypes()));
			assertEquals(List.of(), Query.namedThings("Welche Autos besitzt Mattias?", owner, e.entityTypes()));
			assertEquals(List.of(), Query.namedThings("Hat Mattias ein Boot?", owner, e.entityTypes()));
			assertEquals(List.of(), Query.namedThings("Hat Mattias Kinder?", owner, e.entityTypes()));
			assertEquals(List.of("ipad"), Query.namedThings("Ist mein iPad im Büro?", owner, e.entityTypes()),
					"inner-capital brand names remain named things in German, ordinary nouns do not");

			assertEquals(List.of("hooli"), Query.namedThings("When did Mattias join Hooli?", owner, e.entityTypes()));
			assertEquals(List.of("schubelbach"),
					Query.namedThings("Does Mattias live in Schübelbach?", owner, e.entityTypes()),
					"English questions naming umlaut places keep proper-noun detection");
			assertEquals(List.of("hooli"), Query.namedThings("När började Mattias på Hooli?", owner, e.entityTypes()),
					"Swedish does not capitalise common nouns, so proper names stay");
			assertEquals(List.of("hooli"), Query.namedThings("Quand Mattias a rejoint Hooli ?", owner, e.entityTypes()),
					"French does not capitalise common nouns, so proper names stay");
			assertEquals(List.of("hooli"),
					Query.namedThings("¿Cuándo trabajó Mattias en Hooli?", owner, e.entityTypes()),
					"Spanish does not capitalise common nouns, so proper names stay");
		}
	}

	@Test
	void unicodeSafePolarAndPastHeuristicsAcrossAllFiveLanguages() {
		// English
		assertTrue(Query.isPolar("Does Mattias own a boat?"));
		assertTrue(Query.isPolar("Was Mattias at Hooli?"));
		assertTrue(Query.isPast("Was Mattias at Hooli?"));
		assertTrue(Query.isPast("Did Mattias work at Initrode?"));
		assertFalse(Query.isPast("Does Mattias own a boat?"));

		// German: umlauts, V1 verbs, Perfekt vs present, and "Was" = what (NOT polar/past!)
		assertTrue(Query.isPolar("Besitzt Mattias ein Boot?"));
		assertTrue(Query.isPolar("Hat Mattias ein Boot?"));
		assertTrue(Query.isPolar("Könnte Mattias kommen?"));
		assertTrue(Query.isPolar("\"Könnte Mattias kommen?\""), "leading punctuation does not break first-word check");
		assertTrue(Query.isPolar("Müsste Mattias das wissen?"));
		assertFalse(Query.isPolar("Was besitzt Mattias?"), "German 'was' means 'what', not English 'was'");
		assertFalse(Query.isPast("Was besitzt Mattias?"));
		assertFalse(Query.isPolar("Wann war mein letzter Zahnarzttermin?"));

		assertTrue(Query.isPast("War Mattias bei Initrode?"));
		assertTrue(Query.isPast("Arbeitete Mattias bei Initrode?"));
		assertTrue(Query.isPast("Hat Mattias bei Initrode gearbeitet?"), "German Perfekt past");
		assertTrue(Query.isPast("Ist Mattias in Lund geboren?"));
		assertTrue(Query.isPast("Hat Mattias früher ein Boot besessen?"));
		assertFalse(Query.isPast("Hat Mattias ein Boot?"), "present-tense German possession stays non-past");
		assertFalse(Query.isPast("Hat Mattias Geschwister?"), "Ge- noun is not mistaken for a past participle");
		assertFalse(Query.isPast("Besitzt Mattias ein Boot?"));

		// Swedish: å/ä/ö, V1 verbs, perfekt vs present, and "Var" = where vs was
		assertTrue(Query.isPolar("Äger Mattias en båt?"));
		assertTrue(Query.isPolar("Är Mattias gift?"));
		assertTrue(Query.isPolar("Måste Mattias arbeta?"));
		assertTrue(Query.isPolar("Har Mattias barn?"));
		assertTrue(Query.isPolar("Jobbade Mattias på Initrode?"));
		assertTrue(Query.isPast("Jobbade Mattias på Initrode?"));
		assertTrue(Query.isPast("Ägde Mattias en båt?"));
		assertTrue(Query.isPast("Har Mattias jobbat på Initrode?"), "Swedish perfekt past");
		assertFalse(Query.isPast("Har Mattias en båt?"));
		assertFalse(Query.isPast("Äger Mattias en båt?"));

		assertFalse(Query.isPolar("Var bor Mattias?"), "Swedish 'var' + verb means 'where', not polar");
		assertFalse(Query.isPast("Var bor Mattias?"));
		assertTrue(Query.isPolar("Var Mattias hemma?"), "Swedish 'var' + subject means 'was'");
		assertTrue(Query.isPast("Var Mattias hemma?"));

		// French: Est-ce que, V1 inversion, passé composé vs present, and non-past born/married
		assertTrue(Query.isPolar("Est-ce que Mattias possède un bateau ?"));
		assertTrue(Query.isPolar("Possède Mattias un bateau ?"));
		assertTrue(Query.isPolar("Est-ce que Mattias a travaillé chez Initrode ?"));
		assertTrue(Query.isPast("Est-ce que Mattias a travaillé chez Initrode ?"), "French passé composé");
		assertTrue(Query.isPast("Mattias travaillait chez Initrode ?"), "French imparfait");
		assertFalse(Query.isPast("Est-ce que Mattias possède un bateau ?"));
		assertFalse(Query.isPast("Où est né Mattias ?"), "birthplace is timeless, not closed past");
		assertFalse(Query.isPolar("Où habite Mattias ?"));

		// Spanish: leading ¿, verb-first polar, preterite & pretérito perfecto vs present
		assertTrue(Query.isPolar("¿Tiene Mattias un barco?"));
		assertTrue(Query.isPolar("¿Posee Mattias un barco?"));
		assertTrue(Query.isPolar("¿Trabajó Mattias en Initrode?"));
		assertTrue(Query.isPast("¿Trabajó Mattias en Initrode?"), "Spanish preterite");
		assertTrue(Query.isPast("¿Ha trabajado Mattias en Initrode?"), "Spanish pretérito perfecto");
		assertTrue(Query.isPast("¿Dónde ha trabajado Mattias?"), "Spanish compound past in wh-question");
		assertFalse(Query.isPast("¿Tiene Mattias un barco?"));
		assertFalse(Query.isPast("¿Dónde nació Mattias?"), "birthplace is timeless, not closed past");
		assertFalse(Query.isPolar("¿Dónde vive Mattias?"));
	}

	@Test
	void forwardDetectionCoversAllFiveLanguagesWithDiacritics() {
		assertTrue(Query.isForward("What vehicle am I about to pick up?"));
		assertTrue(Query.isForward("When is my next appointment?"));
		assertFalse(Query.isForward("Where does Mattias live?"));

		assertTrue(Query.isForward("Welches Fahrzeug hole ich bald ab?"));
		assertTrue(Query.isForward("Wann ist der nächste Zahnarzttermin?"));
		assertTrue(Query.isForward("Was steht demnächst an?"));
		assertTrue(Query.isForward("Was ist für kommende Woche geplant?"));
		assertFalse(Query.isForward("Wo wohnt Mattias?"));

		assertTrue(Query.isForward("Vilket fordon ska jag snart hämta?"));
		assertTrue(Query.isForward("När är nästa tandläkarbesök?"));
		assertTrue(Query.isForward("Vilka kommande möten har jag?"));
		assertFalse(Query.isForward("Var bor Mattias?"));

		assertTrue(Query.isForward("Quel véhicule vais-je récupérer bientôt ?"));
		assertTrue(Query.isForward("Quand est mon prochain rendez-vous ?"));
		assertTrue(Query.isForward("Quels événements à venir ai-je ?"));
		assertFalse(Query.isForward("Où habite Mattias ?"));

		assertTrue(Query.isForward("¿Qué vehículo voy a recoger pronto?"));
		assertTrue(Query.isForward("¿Cuándo es mi próxima cita?"));
		assertTrue(Query.isForward("¿Qué está previsto para el futuro?"));
		assertFalse(Query.isForward("¿Dónde vive Mattias?"));
	}

	@Test
	void kindPhrasesAndPolarFillersWorkPerLanguageWithoutCrossLanguageLeakage() {
		try (Engine e = TestHomes.engine("multilingual-kinds")) {
			Set<String> owner = Set.of("mattias", "sandell");
			assertEquals(List.of("motorcycles"),
					Query.kindsAskedFor("What motorcycles does Mattias own?", owner, Set.of("own"), List.of()));
			assertEquals(List.of("country"),
					Query.kindsAskedFor("In which country does Mattias live?", owner, Set.of("live"), List.of()));
			assertEquals(List.of(), Query.kindsAskedFor("What does Mattias own?", owner, Set.of("own"), List.of()));

			assertEquals(List.of("autos"),
					Query.kindsAskedFor("Welche Autos besitzt Mattias?", owner, Set.of("besitzt"), List.of()));
			assertEquals(List.of("fahrzeuge"), Query.kindsAskedFor("Welche anderen Fahrzeuge besitzt Mattias?", owner,
					Set.of("besitzt"), List.of()));
			assertEquals(List.of("auto"),
					Query.kindsAskedFor("Was für ein Auto besitzt Mattias?", owner, Set.of("besitzt"), List.of()));
			assertEquals(List.of("bank"),
					Query.kindsAskedFor("Bei welcher Bank arbeitet Mattias?", owner, Set.of("arbeitet"), List.of()));
			assertEquals(List.of("fahrzeug"),
					Query.kindsAskedFor("Welches Fahrzeug hole ich bald ab?", owner, Set.of(), List.of()));

			assertEquals(List.of("bilar"),
					Query.kindsAskedFor("Vilka bilar äger Mattias?", owner, Set.of("ager"), List.of()));
			assertEquals(List.of("fordon"),
					Query.kindsAskedFor("Vilka andra fordon äger Mattias?", owner, Set.of("ager"), List.of()));
			assertEquals(List.of("bil"),
					Query.kindsAskedFor("Vad för slags bil äger Mattias?", owner, Set.of("ager"), List.of()));
			assertEquals(List.of("foretag"),
					Query.kindsAskedFor("På vilket företag jobbar Mattias?", owner, Set.of("jobbar"), List.of()));

			assertEquals(List.of("voitures"),
					Query.kindsAskedFor("Quelles voitures possède Mattias ?", owner, Set.of("possede"), List.of()));
			assertEquals(List.of("vehicules"), Query.kindsAskedFor("Quels autres véhicules possède Mattias ?", owner,
					Set.of("possede"), List.of()));
			assertEquals(List.of("pays"),
					Query.kindsAskedFor("Dans quel pays habite Mattias ?", owner, Set.of("habite"), List.of()));

			assertEquals(List.of("coches"),
					Query.kindsAskedFor("¿Qué coches tiene Mattias?", owner, Set.of("tiene"), List.of()));
			assertEquals(List.of("vehiculos"),
					Query.kindsAskedFor("¿Qué otros vehículos tiene Mattias?", owner, Set.of("tiene"), List.of()));
			assertEquals(List.of("pais"),
					Query.kindsAskedFor("¿En qué país vive Mattias?", owner, Set.of("vive"), List.of()));

			// Cross-language homograph safety: English "hat" is NOT stripped by German polar_filler "hat".
			Query hatQuery = Query.analyse("Does Mattias own a hat?", e.entities(), e.predicates(), e.entityTypes());
			assertEquals(Lang.EN, hatQuery.language());
			assertTrue(hatQuery.polar());
			assertTrue(hatQuery.residual().contains("hat"),
					"English 'hat' must remain in residual: " + hatQuery.residual());

			// German "ein" and "noch" ARE stripped in German polar queries, leaving "boot".
			Query bootQuery = Query.analyse("Besitzt Mattias noch ein Boot?", e.entities(), e.predicates(),
					e.entityTypes());
			assertEquals(Lang.DE, bootQuery.language());
			assertTrue(bootQuery.polar());
			assertEquals(List.of("boot"), bootQuery.residual());
			assertEquals(List.of(), bootQuery.named());

			// Swedish "en" and "fortfarande" ARE stripped in Swedish polar queries, leaving "bat".
			Query batQuery = Query.analyse("Äger Mattias fortfarande en båt?", e.entities(), e.predicates(),
					e.entityTypes());
			assertEquals(Lang.SV, batQuery.language());
			assertTrue(batQuery.polar());
			assertEquals(List.of("bat"), batQuery.residual());

			// French "est", "ce", "que", "un", "encore" ARE stripped, leaving "bateau".
			Query bateauQuery = Query.analyse("Est-ce que Mattias possède encore un bateau ?", e.entities(),
					e.predicates(), e.entityTypes());
			assertEquals(Lang.FR, bateauQuery.language());
			assertTrue(bateauQuery.polar());
			assertEquals(List.of("bateau"), bateauQuery.residual());

			// Spanish "todavía" and "un" ARE stripped, leaving "barco".
			Query barcoQuery = Query.analyse("¿Tiene Mattias todavía un barco?", e.entities(), e.predicates(),
					e.entityTypes());
			assertEquals(Lang.ES, barcoQuery.language());
			assertTrue(barcoQuery.polar());
			assertEquals(List.of("barco"), barcoQuery.residual());
		}
	}

	@Test
	void bindsPossessorsAfterOfAndConjunctionsAcrossLanguages() {
		Entity anna = new Entity(2, "Anna Lindqvist", "person", null, null);
		Predicate parentOf = new Predicate("parent_of", null, List.of("person"), List.of("person"), false, null, false,
				null, "low", List.of(), "{subject} parent_of {object}", List.of(), List.of(), List.of(), null, true,
				false, false);
		Predicate siblingOf = new Predicate("sibling_of", null, List.of("person"), List.of("person"), false, null,
				false, null, "low", List.of(), "{subject} sibling_of {object}", List.of(), List.of(), List.of(), null,
				true, false, false);

		String svOf = "föräldrar till Anna";
		List<Bound> svBound = Query.bind(svOf, List.of(new Cue(parentOf, null, "föräldrar", "subject")),
				List.of(new Mention(anna, 2, 3)), List.of(anna));
		assertEquals(List.of(anna), svBound.getFirst().subjects());

		String svAnd = "Annas föräldrar och syskon";
		List<Bound> svList = Query.bind(svAnd,
				List.of(new Cue(parentOf, null, "föräldrar", "subject"), new Cue(siblingOf, null, "syskon", "any")),
				List.of(new Mention(anna, 0, 1)), List.of(anna));
		assertEquals(2, svList.size());
		assertTrue(svList.stream().allMatch(b -> b.subjects().equals(List.of(anna))));

		String deOf = "Vater von Anna";
		List<Bound> deBound = Query.bind(deOf, List.of(new Cue(parentOf, null, "vater", "object")),
				List.of(new Mention(anna, 2, 3)), List.of(anna));
		assertEquals(List.of(anna), deBound.getFirst().subjects());

		// French elided "d'Anna" tokenizes as ["parents", "d", "anna"] -> "d" is in French "of".
		String frOf = "parents d'Anna";
		List<Bound> frBound = Query.bind(frOf, List.of(new Cue(parentOf, null, "parents", "subject")),
				List.of(new Mention(anna, 2, 3)), List.of(anna));
		assertEquals(List.of(anna), frBound.getFirst().subjects());

		String esOf = "padres de Anna";
		List<Bound> esBound = Query.bind(esOf, List.of(new Cue(parentOf, null, "padres", "subject")),
				List.of(new Mention(anna, 2, 3)), List.of(anna));
		assertEquals(List.of(anna), esBound.getFirst().subjects());
	}
}
