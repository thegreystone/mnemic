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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.recall;

import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityService.Mention;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.LanguagePack;
import se.hirt.mnemic.knowledge.LanguagePack.QueryCues;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.Predicate;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A question taken apart once for every channel: the entities it names, the predicate cues, its content terms with the
 * owner's names removed (they occur in nearly every rendering and carry no signal), the terms beyond the entities,
 * whether it is a yes/no question, in the past tense, or about what is coming, and what it names specifically.
 *
 * @param terms
 *            distinct content tokens minus the owner's aliases
 * @param entityTokens
 *            tokens of every spotted entity's aliases and the owner's
 * @param beyondEntities
 *            terms that are not an entity token
 * @param residual
 *            for a yes/no question, what it asks about beyond the entities, the cue, and filler ("any", "still")
 * @param named
 *            capitalised words that are not an entity: the specific things the question is about
 * @param fts
 *            the FTS5 match expression over the terms
 * @param kinds
 *            the kind of thing a question asks for, between its question word and its verb ("what motorcycles does
 *            Mattias own"): words that are no entity, cue, or filler
 * @param language
 *            the detected language of the question, falling back to the store's language on a tie
 */
public record Query(String text, List<Entity> spotted, List<Cue> cues, Set<String> ownerTokens, List<String> terms,
		Set<String> entityTokens, List<String> beyondEntities, boolean polar, boolean past, boolean forward,
		Set<String> cueTokens, List<String> residual, List<String> named, String fts, List<String> kinds,
		List<Bound> bound, Lang language) {

	/**
	 * A cue the probe runs, read off the question's structure: {@code subjects} are the entities whose name stands
	 * right before the cue word ("Anna's siblings"; every member when a family name spots several) or right after it
	 * with "of" ("the siblings of Anna"), empty when the question does not say; {@code others} are the entities named
	 * in the cue's stretch of the question, from its subject up to the next cue's, the subjects themselves left out;
	 * {@code start} and {@code end} are the cue word's token positions. A word after "and" with no name of its own
	 * shares the subject before it ("Anna's parents and siblings"); a word the question uses twice is bound at each
	 * occurrence that has a subject; a word inside an entity's name is not a cue at all.
	 */
	public record Bound(Cue cue, List<Entity> subjects, List<Entity> others, int start, int end) {
		public boolean hasSubject() {
			return !subjects.isEmpty();
		}
	}

	/** Unicode-aware word tokenisation preserving diacritics (ä, ö, ü, ß, å) and inner apostrophes. */
	private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

	/**
	 * Whether a comma stands between token {@code index - 1} and token {@code index} of the text: "parents, siblings"
	 * lists two relations of one subject, "children born" does not. The tokens drop punctuation, so the text is read.
	 */
	static boolean commaBefore(String text, int index) {
		String read = Names.tokenised(text);
		Matcher m = Names.tokenMatcher(text);
		int end = -1;
		for (int i = 0; m.find(); i++) {
			if (i == index) {
				return end >= 0 && read.substring(end, m.start()).contains(",");
			}
			end = m.end();
		}
		return false;
	}

	public static Query analyse(
		String text, EntityService entities, PredicateRegistry predicates, EntityTypeRegistry types) {
		Lang language = detectLanguage(text, predicates.lang());
		Entity owner = entities.owner();
		List<EntityService.Mention> mentions = withoutRoleWords(text, entities.mentions(text, language),
				predicates.all());
		var spottedById = new LinkedHashMap<Long, Entity>();
		for (EntityService.Mention m : mentions) {
			spottedById.putIfAbsent(m.entity().id(), m.entity());
		}
		List<Entity> spotted = new ArrayList<>(spottedById.values());
		var ownerTokens = new HashSet<String>();
		for (String a : entities.aliases(owner.id())) {
			ownerTokens.addAll(Names.tokens(a));
		}
		var entityTokens = new HashSet<>(ownerTokens);
		for (Entity e : spotted) {
			for (String a : entities.aliases(e.id())) {
				entityTokens.addAll(Names.tokens(a));
			}
		}
		// The names found first, so that a phrase compared by meaning never lies inside one of them.
		List<Cue> cues = predicates.cues(text, entityTokens);
		// Words no search should use: "current" places the question in the present, which the valid-time rules do
		// already, and as a term it ranked every observation that called anything current above the answer; "own" in
		// "my own" is the adjective, not the relation (2026-11-24).
		Set<String> unsearched = new HashSet<>(LanguagePack.of(language).query().tenseFiller());
		unsearched.addAll(adjectivalOnly(text));
		List<String> terms = withoutUnless(
				Names.contentTokens(text).stream().filter(t -> !ownerTokens.contains(t)).distinct().toList(),
				unsearched);
		List<String> beyondEntities = terms.stream().filter(t -> !entityTokens.contains(t)).toList();
		boolean polar = isPolar(text, language);
		List<Bound> bound = bind(text, cues, mentions, spotted, owner, LanguagePack.of(language).query().determiners());
		if (spotted.stream().noneMatch(e -> e.id() == owner.id())
				&& bound.stream().anyMatch(b -> b.subjects().stream().anyMatch(e -> e.id() == owner.id()))) {
			spotted.add(owner); // "mom" alone: the owner is who the question is about
		}
		var cueTokens = new HashSet<String>();
		for (Bound b : bound) {
			cueTokens.addAll(Names.tokens(b.cue().term()));
		}
		Set<String> polarFiller = LanguagePack.of(language).query().polarFiller();
		List<String> residual = polar
				? beyondEntities.stream().filter(t -> !cueTokens.contains(t) && !polarFiller.contains(t)).toList()
				: List.of();
		// A relation word names no thing, however it is written: "Familie" in "Mattias' Familie" is the cue, not a
		// name the facts must mention.
		List<String> named = namedThings(text, entityTokens, types, language).stream()
				.filter(t -> !cueTokens.contains(t)).toList();
		List<String> kinds = kindsAskedFor(text, entityTokens, cueTokens, cues, language);
		if (kinds.isEmpty() && bound.isEmpty()) {
			// "What are my current cars": no relation named and no verb after the kind; what follows the copula is
			// the kind asked for, which the probe looks for under any relation.
			kinds = kindsAfterCopula(text, entityTokens, language);
		}
		return new Query(text, spotted, cues, ownerTokens, terms, entityTokens, beyondEntities, polar,
				isPast(text, language), isForward(text, language), cueTokens, residual, named,
				ftsQuery(text, ownerTokens, unsearched), kinds, bound, language);
	}

	/**
	 * The mentions that are not a person's role standing as the relation. "mother" in "who is my mother" or
	 * "Alexander's mother" is parent_of even when it is also the alias of someone's mother, and "mom" alone is the
	 * owner's mother (see {@link #bind}); read as a name, the word swallowed the relation, and every question about
	 * anyone's mother lost its verdict once "mother" was an alias of the owner's (2026-11-25).
	 */
	static List<Mention> withoutRoleWords(String text, List<Mention> mentions, Collection<Predicate> predicates) {
		if (mentions.isEmpty()) {
			return mentions;
		}
		List<String> tokens = Names.tokens(text);
		return mentions.stream()
				.filter(m -> !isRoleWord(String.join(" ", tokens.subList(m.start(), m.end())), predicates)).toList();
	}

	/**
	 * Whether the words are a person's role under a relation between people, as its vocabulary writes it or in the
	 * plural ("mother", "mom", "brothers", "children").
	 */
	static boolean isRoleWord(String words, Collection<Predicate> predicates) {
		var forms = new HashSet<String>();
		forms.add(words);
		if (words.indexOf(' ') < 0) {
			for (Lang l : Lang.values()) {
				forms.addAll(Lang.singulars(words, l));
			}
		}
		for (Predicate p : predicates) {
			if (!betweenPeople(p)) {
				continue;
			}
			for (List<String> vocabulary : List.of(p.qualifiers(), p.lexicon(), p.inverseLexicon())) {
				for (String term : vocabulary) {
					if (forms.contains(String.join(" ", Names.tokens(term)))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** A relation between people: a role in it is a person, who can be asked about in turn. */
	static boolean betweenPeople(Predicate p) {
		return p.domain().contains("person") && p.range().contains("person");
	}

	/**
	 * Whether a role word stands alone in the question, as a name stands: "what car does mom drive", "who is dad". One
	 * person's role, in the singular as the relation writes it, with no possessor, article, or "of" around it, and not
	 * after another role word ("my mother's sister" is the sister of the mother). Such a word is the owner's: no
	 * personal memory says "mom" of anyone else's mother. "does Mattias have brothers" and "a brother" are not.
	 */
	static boolean standsAlone(
		Cue c, List<String> tokens, int start, int end, List<Object[]> placed, Set<String> ofWords,
		Set<String> determiners) {
		if (c.group() != null || c.qualifier() == null || !betweenPeople(c.predicate())
				|| !Names.tokens(c.term()).equals(Names.tokens(c.qualifier()))) {
			return false;
		}
		if (start > 0 && determiners.contains(tokens.get(start - 1))) {
			return false;
		}
		if (end < tokens.size() && ofWords.contains(tokens.get(end))) {
			return false;
		}
		for (Object[] other : placed) {
			if ((int) other[2] == start) {
				return false;
			}
		}
		return true;
	}

	/** The same question in the present tense: a path through a role goes through the roles held now. */
	Query inPresent() {
		return !past ? this : new Query(text, spotted, cues, ownerTokens, terms, entityTokens, beyondEntities, polar,
				false, forward, cueTokens, residual, named, fts, kinds, bound, language);
	}

	/** The list without the dropped words, unless nothing else would remain. */
	private static List<String> withoutUnless(List<String> words, Set<String> dropped) {
		List<String> kept = words.stream().filter(t -> !dropped.contains(t)).toList();
		return kept.isEmpty() ? words : kept;
	}

	/**
	 * Whether token {@code i} is a homograph standing as its other word: "own" after "my" is the adjective. Read in
	 * every language's list, since the text may be in another language than the question.
	 */
	static boolean asOtherWord(List<String> tokens, int i) {
		if (i <= 0 || i >= tokens.size()) {
			return false;
		}
		Set<String> before = LanguagePack.allHomographs().get(tokens.get(i));
		return before != null && before.contains(tokens.get(i - 1));
	}

	/** The homographs a text uses only as their other word ("what is my own number": "own" is no verb in it). */
	static Set<String> adjectivalOnly(String text) {
		List<String> tokens = Names.tokens(text);
		var other = new HashSet<String>();
		var plain = new HashSet<String>();
		for (int i = 0; i < tokens.size(); i++) {
			if (!LanguagePack.allHomographs().containsKey(tokens.get(i))) {
				continue;
			}
			(asOtherWord(tokens, i) ? other : plain).add(tokens.get(i));
		}
		other.removeAll(plain);
		return other;
	}

	/**
	 * Detects the language of a question from its function words and opening cue words, preserving diacritics so
	 * cross-language homographs ("was", "hat", "für"/"fur", "var") stay separated. Falls back to {@code fallback} when
	 * no language leads.
	 */
	static Lang detectLanguage(String text, Lang fallback) {
		Lang def = fallback == null ? Lang.EN : fallback;
		List<String> rawWords = unicodeWordsRaw(text);
		if (rawWords.isEmpty()) {
			return def;
		}
		boolean hasLowercase = false;
		for (String raw : rawWords) {
			if (raw.chars().anyMatch(Character::isLowerCase)) {
				hasLowercase = true;
				break;
			}
		}
		Map<Lang, Integer> scores = new EnumMap<>(Lang.class);
		for (Lang l : Lang.values()) {
			scores.put(l, 0);
		}
		for (String raw : rawWords) {
			// Skip all-uppercase acronyms (MIT, CEO, AI) in mixed-case text so they do not match lowercase markers.
			if (hasLowercase && raw.length() >= 2 && raw.chars().allMatch(Character::isUpperCase)) {
				continue;
			}
			String w = raw.toLowerCase(Locale.ROOT);
			for (LanguagePack pack : LanguagePack.all()) {
				if (pack.query().markers().contains(w)) {
					scores.merge(pack.lang(), 2, Integer::sum);
				}
			}
		}
		// A position-0 word unique to one language's polar/question openers resolves short questions ("Hat Mattias
		// Kinder?", "Var Mattias hemma?").
		String first = rawWords.getFirst().toLowerCase(Locale.ROOT);
		Lang uniqueOpener = null;
		int openerHits = 0;
		for (LanguagePack pack : LanguagePack.all()) {
			QueryCues q = pack.query();
			if (q.polar().contains(first) || q.questionWords().contains(first)) {
				uniqueOpener = pack.lang();
				openerHits++;
			}
		}
		if (openerHits == 1 && uniqueOpener != null) {
			scores.merge(uniqueOpener, 1, Integer::sum);
		}
		if (rawWords.size() > 1) {
			String second = rawWords.get(1).toLowerCase(Locale.ROOT);
			for (LanguagePack pack : LanguagePack.all()) {
				if (pack.query().isAmbiguousWhereWas(first, second)) {
					scores.merge(pack.lang(), 2, Integer::sum);
				}
			}
		}
		Lang best = def;
		int bestScore = 0;
		boolean tied = false;
		for (Map.Entry<Lang, Integer> e : scores.entrySet()) {
			int s = e.getValue();
			if (s > bestScore) {
				best = e.getKey();
				bestScore = s;
				tied = false;
			} else if (s > 0 && s == bestScore) {
				tied = true;
			}
		}
		return bestScore == 0 || tied ? def : best;
	}

	/** The polar-filler words for the language of {@code text}, falling back to {@code fallback}. */
	static Set<String> polarFillerFor(String text, Lang fallback) {
		return LanguagePack.of(detectLanguage(text, fallback)).query().polarFiller();
	}

	/**
	 * The cues the probe runs, each at the place in the question it was found. Cues come longest term first; one is
	 * taken when its word is not already taken by another cue's, except that the members of one group share their word
	 * ("family" reaches every kinship predicate). Each is then bound to the entity the question puts it with, and given
	 * the other entities of its stretch of the question.
	 */
	static List<Bound> bind(String text, List<Cue> cues, List<Mention> mentions, List<Entity> spotted) {
		return bind(text, cues, mentions, spotted, null, Set.of());
	}

	/**
	 * As {@link #bind(String, List, List, List)}, and a role word that {@link #standsAlone} ("what car does mom drive")
	 * is bound to {@code self}, the owner; with a null one, none is.
	 */
	static List<Bound> bind(
		String text, List<Cue> cues, List<Mention> mentions, List<Entity> spotted, Entity self,
		Set<String> determiners) {
		Set<String> ofWords = LanguagePack.allOfWords();
		Set<String> andWords = LanguagePack.allAndWords();
		List<String> tokens = Names.tokens(text);
		String[] takenBy = new String[tokens.size()]; // the group that took a token, "" for a predicate's own word
		var placed = new ArrayList<Object[]>(); // cue, start, end
		for (Cue c : cues) {
			List<String> term = Names.tokens(c.term());
			if (term.isEmpty()) {
				continue;
			}
			String owner = c.group() == null ? "" : c.group();
			for (int i = 0; i + term.size() <= tokens.size(); i++) {
				if (!tokens.subList(i, i + term.size()).equals(term)) {
					continue;
				}
				// A predicate's own word is taken once; the members of a group share theirs, and a group word may
				// stand on a word a predicate also owns ("family" for the family doctor and for the kin alike).
				boolean free = true;
				for (int k = i; k < i + term.size(); k++) {
					boolean blocked = takenBy[k] != null
							&& (owner.isEmpty() || (!takenBy[k].isEmpty() && !takenBy[k].equals(owner)));
					if (blocked) {
						free = false;
						break;
					}
				}
				// A word inside an entity's name ("Family Office AB") is that name, not a relation asked about.
				for (Mention m : mentions) {
					if (m.start() <= i && m.end() >= i + term.size()) {
						free = false;
						break;
					}
				}
				// "my own phone": the adjective, not the owns relation.
				for (int k = i; k < i + term.size() && free; k++) {
					free = !asOtherWord(tokens, k);
				}
				if (!free) {
					continue;
				}
				for (int k = i; k < i + term.size(); k++) {
					if (takenBy[k] == null || owner.isEmpty()) {
						takenBy[k] = owner;
					}
				}
				placed.add(new Object[] {c, i, i + term.size()});
			}
		}
		if (placed.isEmpty()) {
			return List.of();
		}
		// The subjects of each cue, and where its stretch of the question begins: at the subject's name when it
		// stands before the word, else at the word.
		var subjects = new ArrayList<List<Entity>>();
		var anchors = new int[placed.size()];
		for (int p = 0; p < placed.size(); p++) {
			int start = (int) placed.get(p)[1];
			int end = (int) placed.get(p)[2];
			var before = new ArrayList<Entity>();
			var after = new ArrayList<Entity>();
			int beforeStart = start;
			for (Mention m : mentions) {
				if (m.end() == start) {
					before.add(m.entity());
					beforeStart = m.start();
				}
				if (end < tokens.size() && ofWords.contains(tokens.get(end)) && m.start() == end + 1) {
					after.add(m.entity());
				}
			}
			subjects.add(List.copyOf(before.isEmpty() ? after : before));
			anchors[p] = before.isEmpty() ? start : beforeStart;
		}
		// "Malin's parents and siblings", "Anna's parents, siblings and cousins": a word with no name of its own that
		// follows another relation word after "and" or a comma shares that word's subject, down the whole list. In
		// the question's order, so a subject passes along it. "Children born" is no list: nothing joins the two.
		var byStart = new ArrayList<Integer>();
		for (int p = 0; p < placed.size(); p++) {
			byStart.add(p);
		}
		byStart.sort((a, b) -> Integer.compare((int) placed.get(a)[1], (int) placed.get(b)[1]));
		for (int p : byStart) {
			int start = (int) placed.get(p)[1];
			if (!subjects.get(p).isEmpty() || start < 1) {
				continue;
			}
			int previousEnd = andWords.contains(tokens.get(start - 1)) ? start - 1
					: commaBefore(text, start) ? start : -1;
			if (previousEnd < 0) {
				continue;
			}
			for (int o = 0; o < placed.size(); o++) {
				if ((int) placed.get(o)[2] == previousEnd && !subjects.get(o).isEmpty()) {
					subjects.set(p, subjects.get(o));
					break;
				}
			}
		}
		// "What car does mom drive": a role word standing alone is the owner's. After the lists, so "Anna's mother
		// and dad" stays Anna's.
		if (self != null) {
			for (int p = 0; p < placed.size(); p++) {
				if (subjects.get(p).isEmpty() && standsAlone((Cue) placed.get(p)[0], tokens, (int) placed.get(p)[1],
						(int) placed.get(p)[2], placed, ofWords, determiners)) {
					subjects.set(p, List.of(self));
				}
			}
		}
		// A word the question uses twice is asked twice when each time it has a subject of its own; without one it
		// is asked once, and not at all when the same relation is asked with a subject elsewhere.
		var kept = new ArrayList<Integer>();
		for (int p = 0; p < placed.size(); p++) {
			if (!subjects.get(p).isEmpty() && !groupCopyOfOwnWord(placed, subjects, p)) {
				kept.add(p);
			}
		}
		for (int p = 0; p < placed.size(); p++) {
			String name = ((Cue) placed.get(p)[0]).predicate().name();
			if (subjects.get(p).isEmpty()
					&& kept.stream().noneMatch(k -> ((Cue) placed.get(k)[0]).predicate().name().equals(name))) {
				kept.add(p);
			}
		}
		// Sorted by place in the question, the stretches partition it; the entities of each stretch, minus the cue's
		// own subject, are what the cue's facts are asked to touch. The members of one group stand at the same word
		// and share a stretch.
		var stretches = new ArrayList<List<Integer>>();
		var byPlace = new ArrayList<>(kept);
		byPlace.sort((a, b) -> Integer.compare(anchors[a], anchors[b]));
		for (int p : byPlace) {
			if (!stretches.isEmpty() && anchors[stretches.getLast().getFirst()] == anchors[p]) {
				stretches.getLast().add(p);
			} else {
				stretches.add(new ArrayList<>(List.of(p)));
			}
		}
		var out = new ArrayList<Bound>();
		for (int k = 0; k < stretches.size(); k++) {
			List<Integer> stretch = stretches.get(k);
			int from = k == 0 ? 0 : anchors[stretch.getFirst()];
			int to = k + 1 < stretches.size() ? anchors[stretches.get(k + 1).getFirst()] : tokens.size();
			var others = new ArrayList<Entity>();
			for (Mention m : mentions) {
				if (m.start() >= from && m.start() < to && others.stream().noneMatch(e -> e.id() == m.entity().id())) {
					others.add(m.entity());
				}
			}
			for (int p : stretch) {
				List<Entity> subject = subjects.get(p);
				var own = new ArrayList<>(others);
				own.removeIf(e -> subject.stream().anyMatch(x -> x.id() == e.id()));
				out.add(new Bound((Cue) placed.get(p)[0], subject, List.copyOf(own), (int) placed.get(p)[1],
						(int) placed.get(p)[2]));
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Whether a group's copy of a relation ("family" reaching parent_of) asks what the relation's own word already asks
	 * of the same subject ("Christian's children and family"): asked once. With another subject ("Alexander's children
	 * and Christian's family") both stand.
	 */
	private static boolean groupCopyOfOwnWord(List<Object[]> placed, List<List<Entity>> subjects, int p) {
		Cue c = (Cue) placed.get(p)[0];
		if (c.group() == null) {
			return false;
		}
		Set<Long> mine = subjects.get(p).stream().map(Entity::id).collect(Collectors.toSet());
		for (int o = 0; o < placed.size(); o++) {
			Cue other = (Cue) placed.get(o)[0];
			if (o != p && other.group() == null && other.predicate().name().equals(c.predicate().name())
					&& subjects.get(o).stream().map(Entity::id).collect(Collectors.toSet()).equals(mine)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The kind of thing a question asks for: the words between the question word ("what"/"which", "welche"/"was für",
	 * "vilken"/"vilka") and the verb that are no entity, no word of the cue predicate's vocabulary, and no filler.
	 */
	static List<String> kindsAskedFor(String query, Set<String> entityTokens, Set<String> cueTokens, List<Cue> cues) {
		return kindsAskedFor(query, entityTokens, cueTokens, cues, detectLanguage(query, Lang.EN));
	}

	static List<String> kindsAskedFor(
		String query, Set<String> entityTokens, Set<String> cueTokens, List<Cue> cues, Lang language) {
		QueryCues qc = LanguagePack.of(language).query();
		Matcher m = qc.kindPhrasePattern().matcher(query == null ? "" : query);
		if (!m.find()) {
			return List.of();
		}
		var vocabulary = new HashSet<String>();
		for (Cue c : cues) {
			for (String term : c.predicate().lexicon()) {
				vocabulary.addAll(Names.tokens(term));
			}
			for (String q : c.predicate().qualifiers()) {
				vocabulary.addAll(Names.tokens(q));
			}
		}
		return kindWords(m.group(1), entityTokens, cueTokens, vocabulary, qc, language);
	}

	/**
	 * "What are my current cars", "which are Anna's bikes": with no relation named, the words after the question word
	 * and its verb that are no entity, filler, or tense word.
	 */
	static List<String> kindsAfterCopula(String query, Set<String> entityTokens, Lang language) {
		QueryCues qc = LanguagePack.of(language).query();
		Matcher m = qc.kindCopulaPattern().matcher(query == null ? "" : query);
		if (!m.find()) {
			return List.of();
		}
		return kindWords(m.group(1), entityTokens, Set.of(), Set.of(), qc, language);
	}

	private static List<String> kindWords(
		String phrase, Set<String> entityTokens, Set<String> cueTokens, Set<String> vocabulary, QueryCues qc,
		Lang language) {
		var out = new ArrayList<String>();
		for (String t : Names.tokens(phrase)) {
			if (t.length() < 2 || entityTokens.contains(t) || cueTokens.contains(t) || Names.STOPWORDS.contains(t)
					|| qc.polarFiller().contains(t) || qc.kindFiller().contains(t) || qc.tenseFiller().contains(t)
					|| qc.selfPronouns().contains(t) || out.contains(t)
					|| Lang.singulars(t, language).stream().anyMatch(vocabulary::contains)) {
				continue;
			}
			out.add(t);
		}
		return out;
	}

	/** The spotted entities other than the owner. */
	public List<Entity> others(long ownerId) {
		return spotted.stream().filter(e -> e.id() != ownerId).toList();
	}

	/**
	 * A yes/no question: "does Mattias own property in Sweden", "Besitzt Mattias ein Boot?", "Äger Mattias en båt?".
	 */
	static boolean isPolar(String query) {
		return isPolar(query, detectLanguage(query, Lang.EN));
	}

	static boolean isPolar(String query, Lang language) {
		List<String> words = unicodeWordsLower(query);
		if (words.isEmpty()) {
			return false;
		}
		QueryCues qc = LanguagePack.of(language).query();
		String first = words.getFirst();
		if (qc.isAmbiguousWhereWas(first, words.size() > 1 ? words.get(1) : null)) {
			return false;
		}
		return qc.polar().contains(first);
	}

	/** "Did Mattias work at Initrode": ended facts are the answer, and nothing is decided false from the present. */
	static boolean isPast(String query) {
		return isPast(query, detectLanguage(query, Lang.EN));
	}

	static boolean isPast(String query, Lang language) {
		List<String> words = unicodeWordsLower(query);
		if (words.isEmpty()) {
			return false;
		}
		QueryCues qc = LanguagePack.of(language).query();
		String first = words.getFirst();
		if (qc.isAmbiguousWhereWas(first, words.size() > 1 ? words.get(1) : null)) {
			return false;
		}
		if (qc.past().contains(first)) {
			return true;
		}
		boolean seenAux = false;
		for (String w : words) {
			if (seenAux && qc.pastParticiples().contains(w)) {
				return true;
			}
			if (qc.perfectAuxiliaries().contains(w)) {
				seenAux = true;
			}
		}
		for (String w : words) {
			if (qc.pastMarkers().contains(w)) {
				return true;
			}
		}
		return false;
	}

	/** A question about what is coming: the upcoming channel's cue. */
	static boolean isForward(String query) {
		return isForward(query, detectLanguage(query, Lang.EN));
	}

	static boolean isForward(String query, Lang language) {
		return query != null && LanguagePack.of(language).query().forwardPattern().matcher(query).find();
	}

	/**
	 * Capitalised words of the query that are not its first word and not an entity or owner name: the specific things
	 * the question names ("my apartment in Shinjuku"), lowercased for matching against renderings. In languages that
	 * capitalise all common nouns (German), only words with an inner capital (brand names such as "iPad" or "macOS")
	 * are treated as named things, avoiding false misses on ordinary nouns ("Wann war mein letzter Zahnarzttermin?").
	 */
	static List<String> namedThings(String query, Set<String> entityTokens, EntityTypeRegistry types) {
		return namedThings(query, entityTokens, types, detectLanguage(query, Lang.EN));
	}

	static List<String> namedThings(String query, Set<String> entityTokens, EntityTypeRegistry types, Lang language) {
		boolean capitalizesNouns = LanguagePack.of(language).capitalizesNouns();
		var out = new ArrayList<String>();
		String[] words = query.trim().split("\\s+");
		for (int i = 1; i < words.length; i++) {
			String w = words[i].replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
			boolean innerCapital = false; // iPad, macOS: a brand, not prose
			for (int c = 1; c < w.length(); c++) {
				if (Character.isUpperCase(w.charAt(c))) {
					innerCapital = true;
					break;
				}
			}
			if (w.length() < 2) {
				continue;
			}
			if (capitalizesNouns) {
				if (!innerCapital) {
					continue;
				}
			} else if (!Character.isUpperCase(w.charAt(0)) && !innerCapital) {
				continue;
			}
			if (w.equals("I") || w.length() <= 3 && w.chars().allMatch(Character::isUpperCase)) {
				continue; // "I" and acronyms like "AI" are not named things
			}
			for (String t : Names.tokens(w)) {
				if (t.length() >= 2 && !entityTokens.contains(t) && !Names.STOPWORDS.contains(t) && !types.isTypeWord(t)
						&& !out.contains(t)) {
					out.add(t);
				}
			}
		}
		return out;
	}

	private static List<String> unicodeWordsRaw(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		var out = new ArrayList<String>();
		Matcher m = WORD.matcher(text);
		while (m.find()) {
			String w = stripOuterApostrophes(m.group());
			if (!w.isEmpty()) {
				out.add(w);
			}
		}
		return out;
	}

	private static List<String> unicodeWordsLower(String text) {
		List<String> raw = unicodeWordsRaw(text);
		if (raw.isEmpty()) {
			return List.of();
		}
		var out = new ArrayList<String>(raw.size());
		for (String w : raw) {
			out.add(w.toLowerCase(Locale.ROOT));
		}
		return out;
	}

	private static String stripOuterApostrophes(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && s.charAt(start) == '\'') {
			start++;
		}
		while (end > start && s.charAt(end - 1) == '\'') {
			end--;
		}
		return s.substring(start, end);
	}

	/**
	 * Each content token OR-joined, quoted so FTS5 syntax in a question cannot break the query. Tokens of four letters
	 * or more also match as prefixes ("lead" finds "leading"), which stands in for stemming without the damage an
	 * English stemmer does to German and Swedish. Owner-alias tokens are dropped when anything else remains.
	 */
	static String ftsQuery(String query, Set<String> ownerTokens) {
		return ftsQuery(query, ownerTokens, Set.of());
	}

	/**
	 * As above, without the {@code unsearched} words unless nothing else remains. A homograph matches only where it is
	 * not its other word: "own" in a text that says "her own account" is not the verb the question asked with.
	 */
	static String ftsQuery(String query, Set<String> ownerTokens, Set<String> unsearched) {
		List<String> tokens = Names.contentTokens(query);
		List<String> kept = tokens.stream().filter(t -> !ownerTokens.contains(t)).toList();
		if (kept.isEmpty()) {
			kept = tokens;
		}
		kept = withoutUnless(kept, unsearched);
		var terms = new LinkedHashSet<String>();
		for (String t : kept) {
			String term = t.length() >= 4 ? "\"" + t + "\"*" : "\"" + t + "\"";
			Set<String> before = LanguagePack.allHomographs().get(t);
			if (before != null && !before.isEmpty()) {
				term = "(" + term + " NOT ("
						+ String.join(" OR ", before.stream().sorted().map(b -> "\"" + b + " " + t + "\"").toList())
						+ "))";
			}
			terms.add(term);
		}
		return String.join(" OR ", terms);
	}

	/**
	 * Distinct query terms present in a rendering, with the same prefix rule as the FTS query; a homograph counts only
	 * where it is not its other word ("own mobile number" after a possessive).
	 */
	static int matchedTerms(String rendering, List<String> queryTerms) {
		List<String> words = Names.tokens(rendering);
		int n = 0;
		for (String t : queryTerms) {
			boolean hit = LanguagePack.allHomographs().containsKey(t) ? usedAsItself(rendering, t)
					: t.length() >= 4 ? words.stream().anyMatch(w -> w.startsWith(t)) : words.contains(t);
			if (hit) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Whether a text the full-text index matched did so only through homographs standing as their other word: no other
	 * term is in it, read as the index splits words, and each homograph in it is the other word.
	 */
	static boolean matchedOnlyAsOtherWords(String text, List<String> terms) {
		List<String> words = List.of(Names.norm(text == null ? "" : text).split("[^\\p{L}\\p{N}]+"));
		for (String t : terms) {
			boolean hit = LanguagePack.allHomographs().containsKey(t) ? usedAsItself(text, t)
					: t.length() >= 4 ? words.stream().anyMatch(w -> w.startsWith(t)) : words.contains(t);
			if (hit) {
				return false;
			}
		}
		return true;
	}

	private static final Map<String, Pattern> WHOLE_WORD = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * What opens a parenthesis, a quotation, or a label: a word right after one begins a noun phrase ("(own mobile
	 * number)", a reading's {@code "qualifier":"own mobile number"}), not a clause with the verb in it.
	 */
	private static final String OPENERS = "([{\"“‘'«:";

	/**
	 * Whether a text uses a homograph as itself somewhere: not after one of the words that make it another word, read
	 * as the full-text index splits words ("Mattias's own" is "mattias", "s", "own"), and not right after an opening
	 * parenthesis, quotation mark, or colon, where a fact's qualifier stands ("has phone … (own mobile number)").
	 */
	static boolean usedAsItself(String text, String word) {
		String n = Names.norm(text == null ? "" : text);
		Set<String> before = LanguagePack.allHomographs().getOrDefault(word, Set.of());
		Matcher m = WHOLE_WORD
				.computeIfAbsent(word,
						w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])"))
				.matcher(n);
		while (m.find()) {
			String head = n.substring(0, m.start()).stripTrailing();
			if (!head.isEmpty() && OPENERS.indexOf(head.charAt(head.length() - 1)) >= 0) {
				continue;
			}
			String[] previous = head.split("[^\\p{L}\\p{N}]+");
			if (previous.length > 0 && before.contains(previous[previous.length - 1])) {
				continue;
			}
			return true;
		}
		return false;
	}
}
