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

import se.hirt.mnemic.knowledge.Entity;
import se.hirt.mnemic.knowledge.EntityService;
import se.hirt.mnemic.knowledge.EntityService.Mention;
import se.hirt.mnemic.knowledge.EntityTypeRegistry;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.knowledge.Names;
import se.hirt.mnemic.knowledge.PredicateRegistry;
import se.hirt.mnemic.knowledge.PredicateRegistry.Cue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public record Query(String text, List<Entity> spotted, List<Cue> cues, Set<String> ownerTokens, List<String> terms,
		Set<String> entityTokens, List<String> beyondEntities, boolean polar, boolean past, boolean forward,
		Set<String> cueTokens, List<String> residual, List<String> named, String fts, List<String> kinds,
		List<Bound> bound) {

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

	/** Words that put the possessor after the cue word: "the father of Anna", "Vater von Anna". */
	private static final Set<String> OF = Set.of("of", "von", "der", "des", "de", "du", "di");
	/** Words that join two relations of one subject: "Anna's parents and siblings", "Eltern und Geschwister". */
	private static final Set<String> AND = Set.of("and", "or", "und", "oder", "och", "eller");

	private static final Set<String> POLAR = Set.of("does", "do", "did", "is", "are", "was", "were", "has", "have",
			"had", "can", "could", "will", "would", "should");
	private static final Set<String> PAST = Set.of("did", "was", "were", "had");
	private static final Pattern FORWARD = Pattern.compile(
			"\\b(?:about to|upcoming|next|soon|going to|will|plan(?:s|ned|ning)?|scheduled|due|coming up|pick(?:ing)? up)\\b",
			Pattern.CASE_INSENSITIVE);
	/** Words that narrow nothing in a yes/no question. */
	static final Set<String> POLAR_FILLER = Set.of("any", "anything", "anyone", "anywhere", "still", "yet", "ever",
			"really", "actually", "currently", "now", "already", "also", "some", "something", "someone", "there",
			"here", "just", "even", "own");
	/** "what kind of car", "what else", "which other things": words about the asking, not the kind. */
	static final Set<String> KIND_FILLER = Set.of("kind", "kinds", "sort", "sorts", "type", "types", "else", "other",
			"others", "more", "many", "much", "exactly", "precisely", "all", "different", "various", "new", "old",
			"current", "former", "previous", "past", "recent", "latest", "main", "specific", "particular");
	/** "what motorcycles does Mattias own": the kind asked for lies between the question word and the verb. */
	private static final Pattern KIND_PHRASE = Pattern.compile(
			"^\\s*(?:what|which)\\s+(.+?)\\s+(?:does|do|did|is|are|was|were|has|have|had|will|would|can|could)\\b",
			Pattern.CASE_INSENSITIVE);

	public static Query analyse(
		String text, EntityService entities, PredicateRegistry predicates, EntityTypeRegistry types) {
		List<Entity> spotted = entities.spot(text);
		List<Cue> cues = predicates.cues(text);
		var ownerTokens = new HashSet<String>();
		for (String a : entities.aliases(entities.owner().id())) {
			ownerTokens.addAll(Names.tokens(a));
		}
		List<String> terms = Names.contentTokens(text).stream().filter(t -> !ownerTokens.contains(t)).distinct()
				.toList();
		var entityTokens = new HashSet<>(ownerTokens);
		for (Entity e : spotted) {
			for (String a : entities.aliases(e.id())) {
				entityTokens.addAll(Names.tokens(a));
			}
		}
		List<String> beyondEntities = terms.stream().filter(t -> !entityTokens.contains(t)).toList();
		boolean polar = isPolar(text);
		List<Bound> bound = bind(text, cues, entities.mentions(text), spotted);
		var cueTokens = new HashSet<String>();
		for (Bound b : bound) {
			cueTokens.addAll(Names.tokens(b.cue().term()));
		}
		List<String> residual = polar
				? beyondEntities.stream().filter(t -> !cueTokens.contains(t) && !POLAR_FILLER.contains(t)).toList()
				: List.of();
		// A relation word names no thing, however it is written: "Familie" in "Mattias' Familie" is the cue, not a
		// name the facts must mention.
		List<String> named = namedThings(text, entityTokens, types).stream().filter(t -> !cueTokens.contains(t))
				.toList();
		return new Query(text, spotted, cues, ownerTokens, terms, entityTokens, beyondEntities, polar, isPast(text),
				isForward(text), cueTokens, residual, named, ftsQuery(text, ownerTokens),
				kindsAskedFor(text, entityTokens, cueTokens, cues), bound);
	}

	/**
	 * The cues the probe runs, each at the place in the question it was found. Cues come longest term first; one is
	 * taken when its word is not already taken by another cue's, except that the members of one group share their word
	 * ("family" reaches every kinship predicate). Each is then bound to the entity the question puts it with, and given
	 * the other entities of its stretch of the question.
	 */
	static List<Bound> bind(String text, List<Cue> cues, List<Mention> mentions, List<Entity> spotted) {
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
				if (end < tokens.size() && OF.contains(tokens.get(end)) && m.start() == end + 1) {
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
			int previousEnd = AND.contains(tokens.get(start - 1)) ? start - 1 : commaBefore(text, start) ? start : -1;
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
		// A word the question uses twice is asked twice when each time it has a subject of its own; without one it
		// is asked once, and not at all when the same relation is asked with a subject elsewhere.
		var kept = new ArrayList<Integer>();
		for (int p = 0; p < placed.size(); p++) {
			if (!subjects.get(p).isEmpty()) {
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
	 * The kind of thing a question asks for: the words between "what" or "which" and the verb that are no entity, no
	 * word of the cue predicate's vocabulary, and no filler. "What motorcycles does Mattias own" asks for motorcycles;
	 * "what does Mattias own" asks for nothing in particular.
	 */
	static List<String> kindsAskedFor(String query, Set<String> entityTokens, Set<String> cueTokens, List<Cue> cues) {
		Matcher m = KIND_PHRASE.matcher(query == null ? "" : query);
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
		var out = new ArrayList<String>();
		for (String t : Names.tokens(m.group(1))) {
			if (t.length() < 2 || entityTokens.contains(t) || cueTokens.contains(t) || Names.STOPWORDS.contains(t)
					|| POLAR_FILLER.contains(t) || KIND_FILLER.contains(t) || out.contains(t)
					|| Lang.singulars(t).stream().anyMatch(vocabulary::contains)) {
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

	/** A yes/no question: "does Mattias own property in Sweden", "has the car been registered". */
	static boolean isPolar(String query) {
		String[] words = query.trim().toLowerCase(Locale.ROOT).split("[^a-z']+");
		return words.length > 0 && POLAR.contains(words[0]);
	}

	/** "Did Mattias work at Initrode": ended facts are the answer, and nothing is decided false from the present. */
	static boolean isPast(String query) {
		String[] words = query.trim().toLowerCase(Locale.ROOT).split("[^a-z']+");
		return words.length > 0 && PAST.contains(words[0]);
	}

	/** A question about what is coming: the upcoming channel's cue. */
	static boolean isForward(String query) {
		return query != null && FORWARD.matcher(query).find();
	}

	/**
	 * Capitalised words of the query that are not its first word and not an entity or owner name: the specific things
	 * the question names ("my apartment in Shinjuku"), lowercased for matching against renderings. The cue predicate's
	 * vocabulary is not excluded: "Software Engineer Manager" is a title whose every word counts.
	 */
	static List<String> namedThings(String query, Set<String> entityTokens, EntityTypeRegistry types) {
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
			if (w.length() < 2 || (!Character.isUpperCase(w.charAt(0)) && !innerCapital)) {
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

	/**
	 * Each content token OR-joined, quoted so FTS5 syntax in a question cannot break the query. Tokens of four letters
	 * or more also match as prefixes ("lead" finds "leading"), which stands in for stemming without the damage an
	 * English stemmer does to German and Swedish. Owner-alias tokens are dropped when anything else remains.
	 */
	static String ftsQuery(String query, Set<String> ownerTokens) {
		List<String> tokens = Names.contentTokens(query);
		List<String> kept = tokens.stream().filter(t -> !ownerTokens.contains(t)).toList();
		if (kept.isEmpty()) {
			kept = tokens;
		}
		var terms = new LinkedHashSet<String>();
		for (String t : kept) {
			terms.add(t.length() >= 4 ? "\"" + t + "\"*" : "\"" + t + "\"");
		}
		return String.join(" OR ", terms);
	}

	/** Distinct query terms present in a rendering, with the same prefix rule as the FTS query. */
	static int matchedTerms(String rendering, List<String> queryTerms) {
		List<String> words = Names.tokens(rendering);
		int n = 0;
		for (String t : queryTerms) {
			boolean hit = t.length() >= 4 ? words.stream().anyMatch(w -> w.startsWith(t)) : words.contains(t);
			if (hit) {
				n++;
			}
		}
		return n;
	}
}
