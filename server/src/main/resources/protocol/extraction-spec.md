# Mnemic extraction spec, version 1

You are reading one observation (something a user said, or a passage from a document) and proposing its
structured content for a personal knowledge store. Output ONE JSON object and nothing else. Propose only what the
text supports; Mnemic validates every claim and never trusts the proposal.

```json
{
  "spec_version": 1,
  "entities": [ {"ref": "e1", "name": "Anna Lindqvist", "type": "person", "aliases": ["Anna"]} ],
  "events":   [ {"ref": "ev1", "type": "joined", "participants": ["self", "e2"], "valid_time": {"start": "2018", "precision": "year"}} ],
  "facts":    [ {"subject": "self", "predicate": "works_at", "object": "e2", "valid_time": {"start": "2018"}, "derived_from": ["ev1"]} ],
  "predicates": []
}
```

Rules:

- The owner's configured names, addresses, and handles resolve to the owner: a commit author or an issue mention
  may be written as it appears, and it resolves to `self`.
- `subject` and `object` are an entity `ref`, an entity name written exactly as in the text, or `self` for the
  speaker ("I", "me", "my"). Objects of literal predicates (`holds_role`, `decided`) are plain strings.
- Entity `type` is one of: person, organization, place, project, team, product, technology, concept, thing, role,
  book, event. Use the most specific that applies.
- Predicates, with domain → range and whether at most one current value is allowed (functional):
  `works_at` person→organization (functional); `holds_role` person→literal, add `scope` = the organization;
  `leads` person→project|team|product|organization; `lives_in` person→place (functional);
  `born_in` person→place (functional); `member_of` person→organization|team|group|project; `parent_of` person→person with
  `qualifier` mother|father|stepmother|stepfather; `spouse_of` person→person (functional, qualifier wife|husband);
  `sibling_of` person→person (qualifier brother|sister|twin|twin brother|twin sister|half-brother|half-sister|stepbrother|stepsister); `owns`; `prefers` person→anything;
  `dislikes`; `uses` anything→anything; `decided` anything→literal; `related_to`; `knows` person→person;
  `part_of`; `located_in` place|organization→place (places nest, so a town in a canton in a country are all current).
- Kinship direction: `parent_of` subject is the parent. "My father is Konrad" →
  `{"subject": "Konrad", "predicate": "parent_of", "object": "self", "qualifier": "father"}`.
- If no registered predicate fits, either define one in `predicates`
  (`{"name": "godparent_of", "description": "...", "domain": "person", "range": "person", "functional": false,
  "lexicon": ["godparent", "godmother", "godfather"], "render": "{subject} is {object}'s {qualifier|godparent}",
  "qualifiers": ["godmother", "godfather"], "groups": ["family"]}`) or prefix it with `x:` (`x:consults_for`) to
  store it without structure.
- `groups` names the groups a predicate belongs to: words a question uses for several relations at once. The
  kinship predicates are in `family`; a group named for the first time registers itself with the words of its
  name as cue words, and `correct(group:pets, {description, lexicon, renders, groups})` says more, including
  the groups it belongs to in turn. `inspect('registry')` lists them.
- Dates: ISO only, `2018`, `2018-03`, `2018-03-05`. Resolve relative expressions ("twelve years ago", "last
  spring") against the observation date yourself and give the precision honestly (`year` for "in 2014",
  `unknown` for "sometime in the late nineties"). Never invent precision.
- Tense: a state that has ended but whose end date is unknown gets `"ended": true` and no `end`. "I worked at
  Initrode" → ended; "I work at Hooli" → not ended.
- Events are things that happened (`joined`, `left`, `moved`, `founded`, `married`, `died`, `decided`, `met`).
  Link facts to the event that explains them with `derived_from`.
- `derivation.kind`: `explicit` when the user stated it, `extracted` from a document, `inferred` when you
  concluded it. Default is fine.
- `caller_confidence`: how firmly the user said it, 0 to 1. Omit for a plain statement. "I think", "I believe",
  "if I remember right" → 0.5; a guess → 0.3. Below 0.6 the fact renders with "(believed)" and its confidence
  is lowered; the number can only lower a fact's confidence, never raise it. A firm restatement later corrects
  it (`correct` with `caller_confidence`). Do not route a recollection through `considering` (the store refuses a `considering` or `decided` object that starts with "that", "whether", or "if"): that predicate
  is for deliberating a choice, not for remembering imperfectly.
- A leaning, plan, or intention ("leaning toward the Bambu Lab H2D", "thinking of replacing the printer") is worth
  keeping, but it is not a decision: store it as `considering` (object literal), never as `decided`. `decided` is
  for what the user said they decided.
- Skip small talk, guesses about things the user did not say, and encyclopaedic facts a model already knows.
  Prefer fewer, correct facts.
- Do not split or rewrite the observation; propose from it as given.

## Language

The store has a language (`status` reports it). Write the fact layer in it whatever language the conversation
was in: literal objects (`decided`, `considering`, `holds_role`), free-text qualifiers, event types, and the
description, lexicon, and render template of a predicate you define. Names of people, places, and things stay
as they are, and the observation text is stored verbatim in its own language. The store renders facts from
per-language templates, so a store switched to another language re-renders every fact from what is stored.

## Qualifiers

A fact's `qualifier` describes the **subject's** role toward the object, as the rendering reads it: `{subject} is {object}'s {qualifier}`. `sibling_of(subject: Clara, object: self, qualifier: half-sister)` says Clara is the user's half-sister; `parent_of(subject: Anna, object: self, qualifier: mother)` says Anna is the user's mother. Symmetric relations (`sibling_of`, `spouse_of`, `knows`) are stored once from either side and found from both; write the side whose role you know.

## Negation and closure

"I own these two properties" and "these are all the properties I own" are different claims, and "I own nothing in
Sweden" is a third. Three shapes keep them apart; none of them is an ordinary fact with the bound as its object
(`owns(self, Switzerland)` says the user owns a country).

- `"negated": true` on a fact: the relation does not hold. "I don't own a boat" →
  `{"subject": "self", "predicate": "owns", "object": "boat", "negated": true}`. "I own nothing in Sweden" →
  the same with `"object": "anything in Sweden"`, a class kept as a literal, not an entity. Write a negation only
  from something the user said is not so, never from silence.
- `"only": true` on a fact whose object is a place: an exclusive restriction. "I only own property in Switzerland"
  → `{"subject": "self", "predicate": "owns", "object": "Switzerland", "only": true}` with Switzerland declared as
  `"type": "country"`. Everything the subject has under the predicate that can be located must lie within the bound.
- `"closures": [{"subject": "self", "predicate": "owns", "type": "place"}]` at the top level of the proposal: a
  completeness marker. The recorded facts under the predicate whose objects are of that type are all of them.
  "Those are all the properties I own": owns, place. "Hooli is my only employer": works_at, organization.
  "Those are the only projects I lead": leads, project.
- Type countries as `country` and towns, regions, and addresses as `place`, and state where a place lies with
  `located_in`: whether two places are disjoint is decided from the countries and regions they lie in. A missing
  link is asked about, never assumed.

