# Mnemic proposal guide

The rules behind the protocol the server states when a client connects: how to read an utterance into a
`proposal`, how to grow the vocabulary, and what the questions mean. `inspect('guide')` returns this text.

## Reading an utterance

- Write literal objects, qualifiers, and new names in the store's language (`status` names it); keep the
  observation in the user's own words.
- Give facts their `valid_time` (ISO, honest `precision`) and link a change to the event that explains it
  (`joined`, `left`, `moved`, `died`). `ended: true` marks a state that is over. Ask with `as_of` for "in
  2015" questions and `include_history` for the past tense.
- What the user says is not so: a fact with `negated: true` ('I don't own a boat'), a fact with `only: true`
  whose object is a place ('I only own property in Switzerland'), or a closure ('those are all my
  properties'); never a fact with the bound as its object.
- A qualifier describes the subject's role toward the object: parent_of(Anna, self, mother) says Anna is
  the mother. Symmetric relations are stored once from either side. `scope` is the organization of a role.
  A belief carries `caller_confidence` below 1.
- Kinship: record parents, marriages, partners, and siblings; grandparents, aunts and uncles, cousins,
  in-laws, and step-parents are derived from them. State a derived relation only when the user gives it
  without the chain behind it. A person's gender is a `gender` fact.
- An event type is a verb or two words (`inherited`, `purchased_property`); a sentence where the type goes
  is stored as a plain occurrence and never becomes vocabulary.

## Vocabulary

- A predicate, event type, or entity type the registry lacks registers itself from its first use; the
  reply's `definitions` says what the store assumed (`inferred`) and how to change it with `correct`. A
  definition is needed only to say more than the name does. Rare predicate keys: `functional_scope`,
  `symmetric`, `inverse`, `qualifiers`, `aliases`, `renders` (templates per language), `defined_as` (rules of
  a derived relation), `implies` (what a qualifier says about an attribute).
- A type spelled with a word in a registered type's `kinds` (hotel: place; clinic: organization; dog:
  animal), a plural of a registered type, or a compound headed by one is placed without a question. Teach a
  family at once: `entity_types: [{name: vehicle, parent: thing, kinds: [car, van, truck]}]`, or
  `correct('type:vehicle', {kinds: [...]})`, which replaces the list. `inspect('type:place')` shows a type's
  kinds; `definitions` says what placed a type (`placed_by`, `placed`).
- Name the `groups` a new predicate belongs to (kinship is in `family`), so one word in a question reaches
  it with its kin. Say `lasting: true` for a relation a participant's end does not end (parents, siblings,
  attributes); a job, a home, or a marriage is not lasting. Put the words for the object's side in
  `inverse_lexicon`.

## Questions

- `conflict`: a one-valued predicate already has a value. Answer `ended` (the earlier one ended, date
  unknown), `supersede` (the new one replaces it from now), `reject` (the new one is wrong), `reinterpret`
  (you misread it; the corrected proposal comes in the same call), `wrong` (the earlier record was an error),
  or `both` (one value lies within the other on record, a street within its town).
- `entity_resolution`, `predicate_resolution`: the facts that depend on it are held until you answer with a
  candidate id or `new`. `type_kind`: a root kind or `none`. `type_mismatch`: `kind:<type>`, `retype:<type>`,
  or `dismiss`. `event_effect`: `opens:<predicate>`, `closes:<predicate>`, `ends_entity`, or `none`.
  `containment`: `yes` or `no`.
- Answers go as `resolve: [{question_id: "q-3", choice: ...}]` on the next `remember`, or alone in a
  `remember` with no text. The reply names what an answer opened under `questions` too.

## Housekeeping

- When you misread an observation, `remember(observation_id, proposal)` replaces your reading; the text
  keeps its id and date. `correct` is for when the user says the world is otherwise.
- `consolidate` merges entities later shown to be the same, closes what a later event ended, and lists what
  needs you: observations without a reading, open questions, `name_collisions`, vocabulary worth a
  definition, and `review`, the open facts longest unconfirmed on things that change.
