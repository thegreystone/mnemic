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
import se.hirt.mnemic.knowledge.Lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The owner alias for the vector channel: the first person and the owner's name, both ways, three languages. */
class OwnerAliasTest {

	@Test
	void aQuestionCanCarryTheOwnersName() {
		assertEquals("Which company employs Mattias?", OwnerAlias.withOwner("Which company employs me?", "Mattias"));
		assertEquals("Where does Mattias live?", OwnerAlias.withOwner("Where do I live?", "Mattias"));
		assertEquals("Who is Mattias married to?", OwnerAlias.withOwner("Who am I married to?", "Mattias"));
		assertEquals("Where is Mattias' home?", OwnerAlias.withOwner("Where is my home?", "Mattias"));
		assertEquals("What is Anna's job title?", OwnerAlias.withOwner("What is my job title?", "Anna"));
		assertEquals("Anna is leaning toward a Zenit", OwnerAlias.withOwner("I'm leaning toward a Zenit", "Anna"));
		assertEquals("Bei welcher Bank ist Mattias?", OwnerAlias.withOwner("Bei welcher Bank bin ich?", "Mattias"));
		assertEquals("Wo ist Annas Zuhause?", OwnerAlias.withOwner("Wo ist mein Zuhause?", "Anna"));
		assertEquals("Vilken bank använder Mattias?", OwnerAlias.withOwner("Vilken bank använder jag?", "Mattias"));
		assertNull(OwnerAlias.withOwner("Where does Anna live?", "Mattias"));
		assertNull(OwnerAlias.withOwner("Which iPad has minimal storage?", "Mattias"));
		assertNull(OwnerAlias.withOwner("Where do I live?", null));
	}

	@Test
	void aRenderingAboutTheOwnerCanBeSaidInTheFirstPerson() {
		assertEquals("I work at Hooli (since 2018)", OwnerAlias.firstPerson("Mattias works at Hooli (since 2018)", "Mattias", Lang.EN));
		assertEquals("I live in Rüschlikon", OwnerAlias.firstPerson("Alex Berg lives in Rüschlikon", "Alex Berg", Lang.EN));
		assertEquals("I am considering a sabbatical", OwnerAlias.firstPerson("Mattias is considering a sabbatical", "Mattias", Lang.EN));
		assertEquals("I do not own a boat", OwnerAlias.firstPerson("Mattias does not own a boat", "Mattias", Lang.EN));
		assertEquals("I use Neovim", OwnerAlias.firstPerson("Mattias uses Neovim", "Mattias", Lang.EN));
		assertEquals("I dislike cilantro", OwnerAlias.firstPerson("Mattias dislikes cilantro", "Mattias", Lang.EN));
		assertEquals("I hold the role of Senior Staff Engineer at Hooli",
				OwnerAlias.firstPerson("Mattias holds the role of Senior Staff Engineer at Hooli", "Mattias", Lang.EN));
		assertEquals("I was born in Lund", OwnerAlias.firstPerson("Mattias was born in Lund", "Mattias", Lang.EN));
		assertEquals("I only own property in Switzerland", OwnerAlias.firstPerson("Mattias only owns property in Switzerland", "Mattias", Lang.EN));
		assertEquals("Anna is my sister", OwnerAlias.firstPerson("Anna is Mattias's sister", "Mattias", Lang.EN));
		assertEquals("Sofia is married to me", OwnerAlias.firstPerson("Sofia is married to Mattias", "Mattias", Lang.EN));
		assertEquals("My birthday is on the 3rd of May", OwnerAlias.firstPerson("Mattias's birthday is on the 3rd of May", "Mattias", Lang.EN));
		assertEquals("Ich arbeite bei Hooli (seit 2018)", OwnerAlias.firstPerson("Mattias arbeitet bei Hooli (seit 2018)", "Mattias", Lang.DE));
		assertEquals("Ich wohne in Rüschlikon", OwnerAlias.firstPerson("Mattias wohnt in Rüschlikon", "Mattias", Lang.DE));
		assertEquals("Anna ist mein Schwester", OwnerAlias.firstPerson("Anna ist Mattias' Schwester", "Mattias", Lang.DE));
		assertNull(OwnerAlias.firstPerson("Anna lives in Gothenburg", "Mattias", Lang.EN));
		assertNull(OwnerAlias.firstPerson("Mattias works at Hooli", null, Lang.EN));
	}
}
