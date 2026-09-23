# Mnemic proposal guide

The rules behind the protocol the server states when a client connects: how to read an utterance into a
`proposal`, how to grow the vocabulary, and what the housekeeping calls do. `inspect('guide')` returns this
text.

## Reading an utterance

- Write the literal parts in the store's language (`status` names it); keep the observation in the user's
  own words.
- Give facts their `valid_time` (ISO dates, honest `precision`) and link a change to the event that explains
  it (`joined`, `left`, `moved`, `promoted`, `died`). `ended: true` marks a state that is over. Ask with
  `as_of` for "in 2015" questions and `include_history` for the past tense.
- A leaning or an intention is `considering`, not `decided`.
- What the user says is not so goes in as a negated fact, a restriction (`only`), or a closure, never as a
  fact with the bound as its object.
- Kinship: record parents, marriages, partners, and siblings. Grandparents, aunts and uncles, cousins,
  in-laws, and step-parents are derived from `parent_of`, `spouse_of`, `partner_of`, and `sibling_of`; state
  a derived relation only when the user gives it without the chain behind it. A person's gender is a
  `gender` fact (or `gender` on the entity entry), read ahead of what other facts imply.
- An event type is a verb or two words (`inherited`, `purchased_property`). A sentence where the type goes
  is stored as a plain occurrence and never becomes vocabulary; put the detail in the text.

## Vocabulary

- A predicate, event type, or entity type the registry lacks registers itself from its first use. The
  reply's `definitions` names it with `inferred`: the domain, range, direction, template, and effects the
  store assumed. State what you know in the same proposal (`predicates`, `event_types`, `entity_types`),
  correct an assumption you can see is wrong (`correct('pred:name', {...})`), or answer the question the
  store asks about what it means.
- A type spelled with a word in a registered type's `kinds` (a hotel is a kind of place, a clinic of
  organization, a dog of animal) is placed under it from its first use, reported, not asked about; so is a
  plural of a registered type. Teach the store a family at once by giving a type its `kinds`
  (`entity_types: [{name: vehicle, parent: thing, kinds: [car, van, truck]}]`, or
  `correct('type:vehicle', {kinds: [...]})`, which replaces the list, so repeat what should stay) rather
  than answering one kind at a time; `inspect('type:place')` shows a type's kinds. The reply's `definitions`
  says what placed a type (`placed_by`) and which waiting types a definition placed (`placed`).
- Name the `groups` a new predicate belongs to (the kinship predicates are in `family`), so one word in a
  question reaches it with its kin. A new group registers itself; `correct('group:pets', {...})` gives it
  words, a description, or outer groups.
- Say `lasting: true` for a relation that the end of a participant (a death, a dissolved organization) does
  not end: parents, siblings, and attributes are lasting; a job, a home, or a marriage is not.
- Put the words for the object's side in `inverse_lexicon`. A predicate may say what its qualifiers imply
  about an attribute (`implies`); a rule may choose its qualifier `by` any attribute predicate.
- `inspect('registry')` shows what relations, event types, and entity types exist; `status` only counts
  them. `consolidate` lists observations worth re-reading under `descriptive_events`.
- A `conflict` question means a predicate that allows one current value already has one. Say whether the
  earlier one `ended` (date unknown), the new one should `supersede` it from now, the new one is wrong
  (`reject`), you misread it (`reinterpret`, then send the corrected proposal in the same call), or the
  earlier record was simply an error (`wrong`). A finer value beside a coarser one (a street address beside
  the town it lies in) is one thing at two granularities, not a conflict: with the containment on record
  (`located_in`, `part_of`) no question is asked, and an open one takes `both`.
- An `entity_resolution` or `predicate_resolution` question holds the facts that depend on it until you
  answer with a candidate id or "new".

## Housekeeping

- Answers to the store's questions go as `resolve: [{question_id: "q-3", choice: ...}]` on the next
  `remember`, or alone in a `remember` with no text when there is nothing else to record. Answer yourself
  when the answer is plain (inheriting opens owns); bring only real doubt to the user, in your own words.
- When you misread an observation, `remember(observation_id, proposal)` replaces your reading; the text keeps
  its id and date. `correct` is for when the user says the world is otherwise.
- `consolidate` merges entities later shown to be the same, closes what a later event ended, and lists what
  needs you: observations without a reading, open questions, `name_collisions` (two kinds of thing under one
  name: fold them with `correct(ent-N, {merge_into})` if they are one, or leave them), predicates used often
  enough to deserve a definition, and `review`, the open facts longest unconfirmed on things that change
  (jobs, homes, ownership). Confirming those beats trusting all.
- The briefing (`recall` with no query) gives the owner's best-known facts, recently touched entities, and
  open questions.
