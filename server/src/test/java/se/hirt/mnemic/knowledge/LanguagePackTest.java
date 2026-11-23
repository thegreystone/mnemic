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
package se.hirt.mnemic.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies classpath language packs ({@code lang/en.json}, {@code lang/de.json}, {@code lang/sv.json},
 * {@code lang/fr.json}, {@code lang/es.json}).
 */
class LanguagePackTest {

	@Test
	void everyLangLoadsAWellFormedPackFromClasspathResources() {
		assertEquals(5, Lang.values().length);
		for (Lang lang : Lang.values()) {
			LanguagePack pack = LanguagePack.of(lang);
			assertNotNull(pack);
			assertEquals(lang, pack.lang());
			assertEquals(lang.code(), pack.code());
			assertFalse(pack.query().markers().isEmpty(), lang.code() + " markers");
			assertFalse(pack.query().polar().isEmpty(), lang.code() + " polar");
			assertFalse(pack.query().past().isEmpty(), lang.code() + " past");
			assertFalse(pack.query().polarFiller().isEmpty(), lang.code() + " polar_filler");
			assertFalse(pack.query().kindFiller().isEmpty(), lang.code() + " kind_filler");
			assertFalse(pack.query().selfPronouns().isEmpty(), lang.code() + " self_pronouns");
			assertFalse(pack.fact().suffixWords().isEmpty(), lang.code() + " suffix_words");
			assertFalse(pack.fact().objectPrepositions().isEmpty(), lang.code() + " object_prepositions");
			assertFalse(pack.groupLexicons().getOrDefault("family", List.of()).isEmpty(),
					lang.code() + " family group");
		}
		assertTrue(LanguagePack.of(Lang.DE).capitalizesNouns());
		assertFalse(LanguagePack.of(Lang.EN).capitalizesNouns());
		assertFalse(LanguagePack.of(Lang.SV).capitalizesNouns());
		assertFalse(LanguagePack.of(Lang.FR).capitalizesNouns());
		assertFalse(LanguagePack.of(Lang.ES).capitalizesNouns());
	}

	@Test
	void everyNonEnglishPackCoversEverySeedPredicateAndQualifier() {
		Set<String> seedNames = PredicateRegistry.seed().stream().map(Predicate::name).collect(Collectors.toSet());
		Set<String> deQualifiers = LanguagePack.of(Lang.DE).fact().qualifiers().keySet();
		for (Lang lang : List.of(Lang.DE, Lang.SV, Lang.FR, Lang.ES)) {
			Set<String> rendered = LanguagePack.of(lang).predicateRenders().stream()
					.map(LanguagePack.PredicateRender::name).collect(Collectors.toSet());
			assertEquals(seedNames, rendered, lang.code() + " predicate_renders should match seed predicates");
			assertEquals(deQualifiers, LanguagePack.of(lang).fact().qualifiers().keySet(),
					lang.code() + " pack should translate the same family qualifier set");
		}
	}

	@Test
	void languagePackDrivesSuffixesQualifiersAndLanguageAwareSingulars() {
		String suffixes = Lang.suffixWords();
		assertTrue(suffixes.contains("since"));
		assertTrue(suffixes.contains("seit"));
		assertTrue(suffixes.contains("sedan"));
		assertTrue(suffixes.contains("avslutad"));
		assertTrue(suffixes.contains("depuis"));
		assertTrue(suffixes.contains("terminé"));
		assertTrue(suffixes.contains("desde"));
		assertTrue(suffixes.contains("finalizado"));

		assertEquals("mormor", Lang.SV.qualifier("maternal grandmother"));
		assertEquals("Großmutter mütterlicherseits", Lang.DE.qualifier("maternal grandmother"));
		assertEquals("grand-mère maternelle", Lang.FR.qualifier("maternal grandmother"));
		assertEquals("abuela materna", Lang.ES.qualifier("maternal grandmother"));
		assertEquals("maternal grandmother", Lang.EN.qualifier("maternal grandmother"));

		assertEquals("platser", Lang.SV.typePlural("place"));
		assertEquals("Orten", Lang.DE.typePlural("place"));
		assertEquals("lieux", Lang.FR.typePlural("place"));
		assertEquals("lugares", Lang.ES.typePlural("place"));
		assertEquals("places", Lang.EN.typePlural("place"));

		// One-arg singulars remains English-only so English noun heuristics do not over-strip.
		assertFalse(Lang.singulars("carer").contains("car"));
		assertTrue(Lang.singulars("companies").contains("company"));
		// Language-aware singulars strips German, Swedish, French, and Spanish plural endings.
		assertTrue(Lang.singulars("fahrzeuge", Lang.DE).contains("fahrzeug"));
		assertTrue(Lang.singulars("autos", Lang.DE).contains("auto"));
		assertTrue(Lang.singulars("bilar", Lang.SV).contains("bil"));
		assertTrue(Lang.singulars("bilarna", Lang.SV).contains("bil"));
		assertTrue(Lang.singulars("fordonen", Lang.SV).contains("fordon"));
		assertTrue(Lang.singulars("voitures", Lang.FR).contains("voiture"));
		assertTrue(Lang.singulars("animaux", Lang.FR).contains("animal"));
		assertTrue(Lang.singulars("bateaux", Lang.FR).contains("bateau"));
		assertTrue(Lang.singulars("coches", Lang.ES).contains("coche"));
		assertTrue(Lang.singulars("animales", Lang.ES).contains("animal"));
		assertTrue(Lang.singulars("peces", Lang.ES).contains("pez"));
	}
}
