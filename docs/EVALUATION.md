# Evaluation

Scenarios that Mnemic must handle, expressed as sequences of MCP tool calls
with expected results. Each scenario is implemented as a test tagged
`@Scenario("X1")` under `server/src/test/java/se/hirt/mnemic/scenario/`;
`ScenarioCoverageTest` parses the headings here, reports which scenarios have
no test in `target/scenario-coverage.txt`, and fails when a test names a
scenario this document does not have. Each scenario starts from an empty data
home unless stated otherwise.

Conventions:

- Tool calls are written `tool(args)`; only the arguments relevant to the
  scenario are shown. `remember` takes the observation as `text`; its source
  is flat (`source_kind`, `source_ref`, `source_chunk`); questions are
  answered with `resolve: [{ question_id, choice }]`; `forget` takes
  `observation_id` and `keep_entities`; `history` takes an entity.
- `recall` returns a text block. Its verdict line reads `matched`, `MISS`,
  `KNOWN FALSE`, `NOT YET`, `events`, `entity`, or `unresolved`; the channels
  are `structured`, `keys` (the fact channel, `facts` in the fused ranking),
  `lexical`, `semantic`, and `upcoming`. There is no graph channel; the
  `located_in` chain of a matched place is shown on a `via:` line.
- Expected results state *what must hold*, not exact response bodies. Ids are
  symbolic (`ent-Hooli`) and resolve to whatever id Mnemic assigns.
- `T0` is the observation timestamp of the first call; later calls use
  `T0+…`. Valid times are in the observation text unless given explicitly.
- Every `remember` must store the observation verbatim. This is not repeated
  per scenario.

## Metrics

The scenarios are pass/fail tests. The numbers come from the `bench` module,
which runs LongMemEval (see "External benchmarks" at the end) and reports:

| Metric                        | Definition                                                                                     |
|-------------------------------|------------------------------------------------------------------------------------------------|
| `session_recall_at_5`, `_10`  | mean over the answerable questions of the share of a question's evidence sessions in the top 5 (10) hits, with a bootstrap interval |
| `recall_at_5_by_type`         | the same per LongMemEval question type                                                         |
| `structured_states`           | how often the verdict was `matched`, `MISS`, `KNOWN FALSE`, ... across the questions          |
| `recall_ms_p50/p95`, `ingest_ms_p50`, `context_tokens_mean` | latency and the size of what a reader is handed                  |
| `accuracy`, `accuracy_by_type` | judged answers that are correct / questions, only when a reader model answers from the recall |
| `abstention_recall`           | `_abs` questions the reader correctly declined / `_abs` questions                              |
| `refusals_on_answerable`      | answerable questions the reader declined / answerable questions                                |
| `causes`                      | each answer classed `CORRECT`, `RETRIEVAL_MISS` (evidence not in context), `ANSWER_ERROR` (it was), or `ABSTENTION_MISS` |

Runs differ by `--proposer` (none, a local model, an API model) and by
granularity (session or turn); the difference between a run without a
proposer and one with it is what the fact layer contributes.

---

## A. Basic remember / recall

### A1. Store and recall a simple fact

```text
remember(text: "I work at Hooli.")
```

Expect: entity `ent-Mattias` (type person, resolved from first person), entity
`ent-Hooli` (type organization), fact `works_at(ent-Mattias, ent-Hooli)`
with `status: current`, valid start `unknown`, `derived_from` = the
observation (asserted, no event).

```text
recall(query: "where does Mattias work")
```

Expect: `works_at(ent-Mattias, ent-Hooli)` is the top result. The response
includes its confidence and valid time.

### A2. Recall with no matching knowledge

```text
recall(query: "what is Mattias's favourite colour")
```

Expect: empty result set, no fabricated fact, no error.

### A3. Proposal-less remember is stored and queued

```text
remember(text: "Met Anna for coffee, she now leads the platform team at Acme.")
```

Expect: observation stored; no facts created; observation appears in
`consolidate(dry_run: true)` backlog.

---

## B. Entity resolution

### B1. Alias and case variants resolve to one entity

```text
remember(text: "I work at Hooli.")
remember(text: "HooLi moved me to the platform org.")
remember(text: "HLI is headquartered in New York.", proposal: { entities: [ HLI, alias Hooli ] })
```

Expect: exactly one organization entity for Hooli. `HooLi` folds into
`Hooli` by normalisation and is not stored as a separate alias; `HLI` is,
the third proposal naming `Hooli` as its alias. Fact
`located_in(ent-Hooli, ent-NewYork)`.

### B2. Ambiguous match returns a question, not a guess

```text
remember(text: "I met Anna Lindqvist at the conference.")
remember(text: "Anna is joining Hooli next month.", proposal: { facts: [ works_at(Anna, Hooli) ] })
```

Expect: second call returns `questions` containing an `entity_resolution`
for "Anna" with candidates `ent-AnnaLindqvist` and `new`. No new `Anna`
entity created yet; the fact is held `pending`. Observation stored.

```text
remember(text: "Yes, the same Anna.",
         resolve: [{ question_id: <q>, choice: "ent-AnnaLindqvist" }])
```

Expect: the held fact is applied against `ent-AnnaLindqvist` and is
`current`; `Anna` becomes one of her aliases; question closed.

### B3. Same name, different type, stays separate

```text
remember(text: "I'm reading Java Concurrency in Practice.")
remember(text: "Java is the language Mnemic is written in.")
```

Expect: two entities: a book and a technology. No merge.

### B4. Type mismatch overrides string similarity

```text
remember(text: "Viggo started school today.")
remember(text: "We evaluated Viggo, a load-testing tool.")
```

Expect: two entities (person, technology). No merge, no question.

### B5. First person resolves to the owner

Data home opened with `MNEMIC_OWNER="Mattias Sandell"`.

```text
remember(text: "I live in Schübelbach.")
remember(text: "Mattias moved there in 2014.")
recall(query: "where does Mattias Sandell live")
```

Expect: one person entity with aliases `I`, `me`, `Mattias`, `Mattias Sandell`;
one `lives_in` fact; the recall returns it via the structured channel.

---

## C. Temporal semantics

### C1. Event closes a functional fact

```text
remember(text: "I worked at Initrode from 2010.")
remember(text: "I joined Hooli in 2018.")
```

Expect: `works_at(Mattias, Initrode)` valid `[2010, 2018)`, status
`superseded`, supersession record pointing at event `joined(Mattias, Hooli)`.
`works_at(Mattias, Hooli)` valid `[2018, ∞)`, status `current`. Precision
`year` on all bounds. No question raised — the event explains the change.

```text
recall(query: "where does Mattias work")
```

Expect: Hooli fact only, unless `include_history: true`.

```text
recall(query: "where did Mattias work", as_of: "2015-06-01")
```

Expect: Initrode fact only.

### C2. Contradiction without an explaining event raises a conflict

```text
remember(text: "I work at Hooli.")
remember(text: "I work at Acme.")
```

Expect: second call stores the observation, stores the Acme `works_at` fact
as `pending`, never `current`, and returns a `questions` entry of kind
`conflict` with both facts. `works_at(Mattias, Hooli)` remains `current`.
Nothing is overwritten. The answers are `ended` (Hooli ended, Acme takes
over), `supersede`, `reject` (drop Acme), `reinterpret` (D3), and `wrong`
(Hooli was never so).

### C3. Non-functional predicates accumulate

```text
remember(text: "I'm a member of the Platform Fellows.")
remember(text: "I'm a member of the Kestrel project.")
```

Expect: two `member_of` facts, both `current`, no conflict.

### C4. Relative time resolves against observation time

```text
remember(text: "I moved to Switzerland twelve years ago.")   # T0 = 2026-09-06
```

Expect: event `moved(Mattias, Switzerland)` valid start `2014`, precision
`year`. Fact `lives_in(Mattias, Switzerland)` valid `[2014, ∞)`.

### C5. Precision is never fabricated

```text
remember(text: "I started at Nordvik sometime in the late nineties.")
```

Expect: valid start with `precision: unknown` or an explicit interval
`[1997, 1999]`, never a fabricated exact date.

### C6. Re-confirmation raises confidence without a new event

```text
remember(text: "I work at Hooli.")           # T0
remember(text: "Still at Hooli, busy week.")  # T0 + 400d
```

Expect: one `works_at` fact; `last_confirmed` = T0+400d; confidence at
T0+400d is ≥ confidence it would have had without the second observation.
No new event.

### C7. Open-ended facts are annotated by predicate volatility, not decayed

Starting from A1 with no further observations, query at T0+3y:

```text
recall(query: "where does Mattias work")
```

Expect: fact still returned as `current`, annotated `(3y ago), likely
changed`. Its confidence is the same as at T0: confidence never depends on
the clock; staleness is an annotation on a volatile predicate.

```text
remember(text: "I was born in Uppsala.")    # T0
recall(query: "where was Mattias born")                # T0 + 3y
```

Expect: no `likely changed` and no `confirmed` annotation (non-volatile
predicate); the same confidence as the `works_at` fact.

### C8. Late-arriving event closes an existing fact retroactively

```text
remember(text: "I work at Hooli.")                       # T0
remember(text: "Back in 2016 I left Initrode to consult.")   # T0 + 1d
remember(text: "I worked at Initrode until then.")           # T0 + 1d
```

Expect: `works_at(Mattias, Initrode)` closed at 2016; Hooli fact untouched;
no conflict raised.

### C9. Sequential history with unknown bounds does not conflict

```text
remember(text: "I co-founded Nordvik Virtual Machines in 1998. I later worked in the Runtime Platform Group at Initrode. I work at Hooli now.")
```

Expect: three `works_at` facts. Nordvik `[1998, unknown)` with `ended: true`;
Initrode `[unknown, unknown)` with `ended: true`; Hooli `[unknown, ∞)`
current. **No conflict question.** Entity `RuntimePlatformGroup` with
`part_of(RuntimePlatformGroup, Initrode)`.

```text
recall(query: "where did Mattias work in 2005", as_of: "2005-01-01")
```

Expect: Nordvik and Initrode facts both returned, each flagged
`bounds: partial`; Hooli not returned; no fact fabricated for the gap. The
`founded` event is returned alongside.

### C14. Events supersede in chronological order, not call order

```text
remember(text: "I joined Hooli in March 2019.", proposal: { events: [ joined(self, Hooli) 2019-03 ] })
remember(text: "I founded Nordvik Data Consulting & Multimedia HB in 1996.",
         proposal: { events: [ founded(self, Nordvik HB) 1996 ] })
remember(text: "I founded Nordvik Software Solutions AB in September 1998.",
         proposal: { events: [ founded(self, Nordvik AB) 1998-09 ] })
```

Expect: the Hooli fact is untouched (no end date 23 years before its
start); the HB fact, which precedes it, is stored ended at the nearest later
start with `end_source: sequence`, the inference C1 draws when the later
fact arrives second, and keeps that 2019-03 end when the AB arrives
afterwards; the AB is ended at 2019-03 the same way; no question. The same
sentences in chronological order end the HB at 1998 and the AB at 2019. An
event closes only a fact whose interval it falls inside. An earlier job
whose explicit end overlaps the later one's start is not sequenced: it
raises a `conflict` question and the later fact is `pending`.

### C13. An event opens the facts its type declares

```text
remember(text: "I bought Bergstrasse 7 in November 2025.",
         proposal: { events: [ { type: "purchased", participants: ["self", "Bergstrasse 7"], valid_time: { start: "2025-11" } } ] })
remember(text: "I joined Hooli in 2018.",
         proposal: { events: [ joined(self, Hooli) 2018 ], facts: [ works_at Hooli derived_from the event ] })
remember(text: "I joined the Profiler project in 2020.",
         proposal: { events: [ joined(self, Profiler (project)) ] })
```

Expect: the purchase alone yields one `owns` fact, valid from the event's
date and linked to it. A fact stated alongside its event is stored once, not
twice. When a type opens several predicates (`joined`: `works_at`,
`member_of`) the participants' types pick one: a project cannot be a
`works_at` object, so joining a project opens `member_of` only, with no
question. A fact that names the event's organization alongside the event is
one fact with one corroboration, not a corroborated duplicate.

### C12. A fact valid from a later date is not yet so

```text
# today is 2026-09-10
remember(text: "I collect the Zenit 4 from the dealer on 17 September 2026.",
         proposal: { facts: [ { predicate: "owns", object: "Zenit 4", valid_time: { start: "2026-09-17" } } ] })
recall(query: "does Mattias own the Zenit 4 yet")
recall(query: "what does Mattias own")
recall(query: "does Mattias own the Zenit 4", as_of: 2026-09-18)
```

Expect: the fact is accepted (`status: current`) but its state is `future`.
Today's yes/no question gets the verdict `NOT YET` with the fact, the days
until the start, and the hint to ask with `as_of`; it is neither `matched`
nor a MISS. "what does Mattias own" lists it as `upcoming`, not as owned.
With `as_of` on or after the start date, or once the clock passes it, the
same question is `matched`, annotated "planned, not confirmed since it was
due" until the fact is restated after its date; `consolidate` lists such
plans first in `review` with `due: true` and `planned_for`. The briefing
does not list a plan as a possession and the review queue does not ask to
confirm one before its date.

### C10. Multiple roles are not a conflict

```text
remember(text: "I'm Director of Engineering at Hooli.")
remember(text: "I'm the project lead for OpenJDK Kestrel.")
```

Expect: two `holds_role` facts, both `current`, qualified by different
organizations. No conflict.

```text
remember(text: "I've been promoted to VP of Engineering at Hooli.",
         proposal: { events: [ promoted(self, Hooli) 2024 ], facts: [ holds_role VP of Engineering at Hooli, derived_from the event ] })
```

Expect: the Hooli `holds_role` fact is superseded by the event (same
organization); the Kestrel fact is untouched. Without the event this would be
a C2 conflict.

### C11. Death closes the entity's open facts

```text
remember(text: "My stepfather Bosse lives in Uppsala.")
remember(text: "Bosse died in 2014.")
recall(query: "where does Bosse live")
```

Expect: both of Bosse's open facts (`lives_in`, `parent_of`) are closed by
the `died` event: `ended: true`, end `2014`, standing `ended` (closed, not
replaced or corrected). A plain recall is a MISS, nothing
being current; with `include_history: true` the fact is returned marked
ended.

---

## D. Correction and forgetting

### D1. Correct preserves history

```text
remember(text: "I live in Zürich.")
correct(fact_id: <lives_in Zürich>, replacement: { object: "Schübelbach" },
        reason: "wrong town")
```

Expect: original fact status `corrected`, replacement fact `current`,
supersession record with the reason. Original observation still stored.

```text
inspect(ref: ent-Mattias, history: true)
```

Expect: both `lives_in` facts, in order, with the correction record and a
reference to the original observation. The correction is itself stored as
an observation of source kind `correction`.

### D2. Forget removes, and says so

```text
remember(text: "My passport number is 123.")
forget(observation_id: <o>)
```

Expect: the derived facts and their embeddings are gone; the observation
row remains blanked (`forgotten: true`, empty text) and the text survives
nowhere in the database file. `history` for `ent-Mattias` has no entries and
one tombstone (`observation_id`, `forgotten_at`). `recall(query:
"passport")` returns nothing. A second `forget` is a no-op.

### D4. Forget can keep the entities it created, for a re-seed

```text
remember(text: "I own Bergstrasse 7 in Schübelbach.")   # creates the house and the town
forget(observation_id: obs-N, keep_entities: true)
remember(text: "I own Bergstrasse 7 in Schübelbach.", proposal: …a better one)
```

Expect: with `keep_entities` the town and the house keep their ids and
aliases, every fact derived from the observation is gone, and the second
`remember` binds to the same entities with no question and no renumbering.
Without it (the default) entities nothing else references are removed with
the observation, as privacy requires (family O). An entity referenced by a
fact from another observation is never removed either way.

### D5. A superseded fact can be corrected

```text
remember(text: "I worked at Initrode from 2010.")
remember(text: "I joined Hooli in 2018.")          # closes Initrode at 2018
correct(fact_id: <Initrode>, replacement: { valid_time: { start: "2010", end: "2016" } }, reason: "left earlier")
```

Expect: the superseded Initrode fact is marked corrected and its replacement
is stored with the 2016 end, accepted and in the ended state; a second
correction of the now-corrected row is refused. History can be wrong, and
an event that closed a fact at the wrong date is undone this way rather
than by forgetting the observation.

### D6. A fact said in two conversations survives forgetting one

```text
remember(text: "I work at Hooli.")                # obs-1, f-1
remember(text: "As I said, I work at Hooli.")     # obs-2, corroborates f-1
inspect(ref: Mattias, history: true)                         # f-1: observations [obs-1, obs-2]
forget(observation_id: obs-1)
forget(observation_id: obs-2)
```

Expect: every observation that stated or corroborated a fact is on record
and listed under `observations` in `history`. Forgetting obs-1 keeps f-1,
re-homed to obs-2 with one corroboration fewer, still recalled; forgetting
obs-2 then removes it, since nothing else said it. Forgetting a
conversation forgets what only it said. (The same links let recall reach a
later restatement, which the semantic ablation showed was often the
answer.)

### D7. A fact that was never true is retracted

```text
remember(text: "I decided to replace the BCN3D Sigma, leaning toward the H2D.", proposal: { facts: [ decided "replace the BCN3D Sigma" ] })
correct(fact_id: f-1, replacement: { wrong: true }, reason: "that was a leaning, never a decision")
recall(query: "what did Mattias decide")
```

Expect (also through the `retract` tool, which takes the fact and the
reason): the fact marked `corrected` with no replacement (`retracted: true`
in the reply), gone from recall, present in history with a `retraction`
supersession carrying the reason; the correction record does not join the
proposal backlog. A correction's record and its replacement fact are
embedded when made, like a remember, not at the next consolidate. Before
this, a mistaken fact could only be replaced by another fact, never
withdrawn.

### D8. A wrong observation is retired, not forgotten

```text
remember(text: "Dad is best reached on Slack these days.")                                # obs-1
remember(text: "Correction: Dad is on WhatsApp, not Slack.")                               # obs-2
correct(target: obs-1, replacement: { retired: true, superseded_by: obs-2 }, reason: "said Slack; obs-2 says WhatsApp")
recall(query: "how do I reach Dad")
```

Expect: obs-1 keeps its text and history, leaves the proposal backlog,
and is not a hit; with `include_history` it is a hit prefixed
`[retired: …; superseded by obs-2]`. Its facts, if it had any, are left to
`correct`: the reply names them (`facts_citing`), and `inspect` marks the
observation as retired beside them, so a fact's provenance never dead-ends
unexplained. `status` counts retired observations beside the live count.
Retiring it again is refused; `correct(obs-1, { retired: false })`
reinstates it (back in the backlog if it never had a reading); `forget`
remains the way to remove it. Before this the only
way to clear a wrong note was to forget it, which destroyed the record of
the mistake.

### D3. Forget is not the default for contradictions

```text
remember(text: "I work at Hooli.")
remember(text: "I work at Acme.")             # raises conflict
remember(text: "Sorry, I meant I *consult* for Acme.",
         resolve: [{ question_id: <q>, choice: "reinterpret" }],
         proposal: { facts: [ { predicate: "consults_for", object: "Acme" } ] })
```

Expect: `works_at(Mattias, Hooli)` still `current`; `consults_for` is
registered from this use and its fact created; the earlier Acme observation is retained, and its pending fact
becomes `rejected` with an `invalidation` supersession whose reason names
the reinterpretation.

---

## E. Provenance and confidence

### E1. Independent sources raise confidence more than repetition

Not yet implemented (2026-09-12); no test.

```text
remember(text: "Anna leads the platform team.", source_kind: "user")
remember(text: "Anna leads the platform team.", source_kind: "user")
```

Expect: one fact; confidence c₁.

Fresh data home:

```text
remember(text: "Anna leads the platform team.", source_kind: "user")
remember(text: "Anna Lindqvist, Head of Platform.",
         source_kind: "document", source_ref: "org-chart.pdf")
```

Expect: one fact; confidence c₂ > c₁.

### E2. Low-reliability source yields low-confidence fact

Not yet implemented (2026-09-12); no test. Neither a source reliability nor
a `min_confidence` on `recall` exists today; the scenario needs both.

```text
remember(text: "I think Anna moved to Berlin, not sure.",
         source_kind: "user", <reliability 0.4>)
```

Expect: `lives_in(Anna, Berlin)` with confidence below the default threshold
for `recall` inclusion unless the threshold is lowered.

### E4. Derivation kind is recorded and validated

```text
remember(text: "I work at Hooli.", source_kind: "user", proposal: { facts: [ works_at Hooli ] })
remember(text: "Org chart: Mattias Sandell, Director of Engineering.",
         source_kind: "document", source_ref: "orgchart.pdf",
         proposal: { facts: [ { predicate: "holds_role", object: "Director of Engineering", scope: "Hooli",
                                derivation: { kind: "explicit" } } ] })
```

Expect: first fact `derivation.kind: explicit`; second call rejects
`explicit` for a document source and stores the fact as `extracted`, with a
warning in the response.

### E5. Connector observations do not become facts on their own

```text
remember(text: <email body>, source_kind: "connector", source_ref: "<msg-id>")
```

Expect: observation stored; no facts; backlog count incremented; a proposal
supplied with a connector observation is rejected (connectors cannot propose).

### E3. Caller confidence is evidence, not truth

```text
remember(text: "Mattias was born in Uppsala.",
         proposal: { facts: [ { predicate: "born_in", object: "Uppsala", caller_confidence: 0.99 } ] })
```

Expect: fact stored with Mnemic-computed confidence; the caller's number is
kept on the fact row as evidence about the caller (one corroboration),
never used as the confidence.

### E6. A belief is marked and ranks below a statement

```text
remember(text: "I think the HB became the AB in 1998.",
         proposal: { facts: [ { subject: "Nordvik HB", predicate: "related_to", object: "Nordvik AB",
                                qualifier: "believed to be the same company, converted from HB to AB",
                                caller_confidence: 0.5 } ] })
correct(fact_id: f-N, replacement: { caller_confidence: 1.0 }, reason: "Mattias confirmed it")
remember(text: "I lead Kestrel as its steward.", proposal: { facts: [ { predicate: "leads", object: "Kestrel", qualifier: "steward" } ] })
```

Expect: the fact renders "Nordvik HB is related to Nordvik AB (believed to be
the same company, converted from HB to AB) (believed)"; its confidence is
0.50, the caller's number flooring the computed 0.80; recall annotates
`believed 0.50`. After the correction the replacement renders without
"(believed)" at 0.80. A qualifier given to a predicate whose template has
no slot (`leads`) is stored and reported in `warnings`, never dropped in
silence.

## F. Hybrid recall

### F1. Lexical match without embeddings

Data home with no embedding model.

```text
remember(text: "We decided to use SQLite for Mnemic's storage.")
remember(text: "Lunch was good.")
recall(query: "SQLite")
```

Expect: the SQLite observation is the only hit (no proposal, so no fact;
the lexical channel alone finds it). Works with FTS only.

### F2. Semantic match with embeddings

Data home with the embedding model configured (`MNEMIC_ORT_LIBRARY`,
`MNEMIC_EMBED_MODEL`; the test runs only then).

```text
remember(text: "My accounts are at Nordbank, the savings and the salary account.")
remember(text: "The 3D printer needs a new nozzle, the old one is clogged.")
remember(text: "I work at Hooli.", proposal: { facts: [ works_at Hooli ] })
recall(query: "where do I bank")
recall(query: "Wo ist mein Geld?")
forget(observation_id: <the Nordbank one>)
```

Expect: every observation and fact is embedded as it is stored (three
observations, one fact, the fact with two vectors: its rendering and its
first-person form, F16); "where do I bank", which shares no word with the
stored text, returns the Nordbank observation first with `semantic`
among its channels; the German question does the same; after `forget` the
observation's vector is gone and it is not found; `consolidate` reports
`embedded: 0` when nothing is missing. Further: a long observation whose
relevant sentence lies far past its first 512 tokens is found through the
chunk that holds it (one vector per chunk); with several observations
close in meaning to a banking question, an exact hit on a rare word stays
first (with one vector per chunk the channel votes as an equal, at fusion
weight 1.0; a question sharing no word with the store is answered by the
channel alone); a store filled before the model was configured is
embedded by `consolidate`, which reports the count, and `forget` removes
every chunk of an observation.

### F3. Containment is followed from a matched place

```text
remember(text: "I live in Schübelbach.")
remember(text: "Schübelbach is in Kanton Schwyz.")
remember(text: "Kanton Schwyz is in Switzerland.")
recall(query: "where does Mattias live")
```

Expect: `matched` on the `lives_in` fact, with the two `located_in` hops
on a `via:` line: "via: Schübelbach is located in Kanton Schwyz;
Kanton Schwyz is located in Switzerland". The canton's observation is a
hit ranked by the structured channel, not a lexical stray. There is no
graph channel; this chain is the only traversal recall does.

### F10. Briefing

Data home with facts about the owner, two active projects, one open conflict
question.

```text
recall(max_tokens: 600)
```

Expect: no error without `query`; owner facts first, then the two projects,
the open question included; ≤ 600 tokens; no caller-supplied importance
consulted.

### F4. Context budget is respected

Data home with 60 observations about Mattias.

```text
recall(query: "tell me about Mattias", max_tokens: 300)
```

Expect: `tokens_used` ≤ 300 and the response marked "truncated by budget".
A fact still fits when its observation does not: a long observation
carrying a `decided` fact, recalled with `max_tokens: 200`, is `matched`
with the fact rendered and the prose "omitted for budget"; with a larger
budget the prose is shown.

### F5. Structured miss is reported, not papered over

```text
remember(text: "My father is Konrad.")
remember(text: "My stepfather was Torsten Björk, Bosse to the family.")
recall(query: "who is Mattias's mother")
```

Expect: the verdict line reads `structured: MISS` for `ent-Mattias` ·
`parent_of[mother]`. The father and stepfather facts are listed under it as
near-misses (same entity and predicate, other qualifier). No fact with
qualifier `mother` is returned or fabricated.

### F6. Kinship qualifier matches structurally

Continuing from F5:

```text
recall(query: "who is Mattias's father")
```

Expect: `parent_of(Konrad, Mattias) [father]` returned via the structured
channel, verdict `matched`; the father's observation is the first hit and
the stepfather fact is listed as a near-miss.

### F7. Alias captured at write time resolves at read time

Continuing from F5:

```text
recall(query: "who is Bosse")
```

Expect: entity `ent-TorstenBjork` (name `Torsten Björk`, alias `Bosse`) with its
facts.

### F8. A predicate registered from use cues on its own words only

```text
remember(text: "I'm leading the Profiler team at Hooli.",
         proposal: { facts: [ { predicate: "responsible_for", object: "Profiler team" } ] })
recall(query: "what does Mattias lead")
```

Expect: the fact is returned via the lexical channel (observation text
matches "leading"). The verdict is not `matched` — the predicate's lexicon
is the words of its name, and "lead" is not one of them; "what is Mattias
responsible for" matches structurally. With the core `leads` predicate
proposed instead, the structured channel matches.

### F9. Events are returned with facts

```text
remember(text: "I joined Hooli in 2018.")
recall(query: "where does Mattias work")
```

Expect: the `works_at` fact and the `joined` event both in the response,
the event linked from the fact's `derived_from`.

### F11. A yes/no question is never headlined as answered

```text
remember(text: "I bought the Lindenhof apartment in Willisau in 2024.")
remember(text: "I relocated from Sweden to Switzerland in 2014. I own nothing in Sweden.")
recall(query: "does Mattias own any property in Sweden")
```

Expect: the verdict never says the matching events or facts "are the
answer". A miss on a yes/no question is qualified "not evidence of no
(yes/no question: nothing recorded says no either)": a record supports yes
only if it meets every condition in the question, and an absent fact is not
a no. The same shape without the polar opener ("what does Mattias own")
keeps its ordinary verdict. A recorded negation, restriction, or closure
turns the same question into `KNOWN FALSE` (family Q).

### F12. An open fact says when it was last confirmed

```text
remember(text: "As of late August the Zenit was not registered yet.", observed_at: 2026-08-27)
recall(query: "what does Mattias own")   # on 2026-09-10
```

Expect: the fact's annotation reads `confirmed 2026-08-27 (14d ago)` and the
observation line reads `observed 2026-08-27 (14d ago)`. Past the predicate's
staleness threshold (180 days high volatility, 365 medium) the annotation
adds `likely changed`. A timeless predicate (`born_in`) carries no age.

### F14. A question about a named thing no fact mentions is a MISS

```text
remember(text: "I live in an apartment in Harajuku.")
recall(query: "How long have I been living in my current apartment in Shinjuku?")
recall(query: "where do I live")
```

Expect: the first recall is a MISS whose near-miss line shows "lives in
Harajuku" and whose verdict says the question names shinjuku, which none
of the facts mention; the second is `matched`. A capitalised word that is
not the first word, not an entity or owner name, and not in the cue
predicate's vocabulary is a named thing, as is a word with a capital inside
it (`iPad`); a role ("Software Engineer Manager") no fact mentions misses
the same way.

### F13. Consolidate lists the facts longest without confirmation

```text
remember(text: "I work at Hooli.")                 # observed 2026-08-27
remember(text: "I was born in Uppsala.")         # observed 2026-08-27
remember(text: "I use Neovim these days.")         # observed 2026-12-01
consolidate(dry_run: true)                          # on 2026-12-05
```

Expect: `review` holds the open facts on predicates that age, oldest
confirmation first, at most five, each with its rendering, confirmation
date, age in days, and `likely_changed`: here one entry, "Mattias Sandell works
at Hooli", `likely_changed: false`. `born_in` is not listed. Neither is the
Neovim fact: a fact confirmed within two weeks, or within a third of its
predicate's staleness (sixty days for a high-volatility predicate, a
hundred and twenty for medium), is not listed; nothing said this week
needs confirming.

### F15. A fact restated later is found through the later conversation too

```text
remember(text: "I work at Hooli.", proposal: { facts: [ works_at Hooli ] })          # obs-1
remember(text: "Still at Hooli, on the profiler team these days.", proposal: same)      # obs-2, corroborates
recall(query: "where does Mattias work")
```

Expect: `matched`, and both conversations among the hits, the later one
anchored by the fact (its channels include `structured` or `facts`), with
the fact shown under it. Before the observation links of D6 a fact reached
only the conversation that first stated it.

### F16. A first-person question reaches a third-person fact through the owner alias

Runs only with the embedding model configured, like F2.

```text
remember(text: "Hooli is where the paycheck comes from, since 2018.", proposal: { facts: [ works_at Hooli ] })
remember(text: "The 3D printer needs a new nozzle, the old one is clogged.")
recall(query: "Which company employs me?")
```

Expect: the fact is rendered in the third person ("Mattias Sandell works at
Hooli") and embedded twice, the second time as the owner would say it ("I
work at Hooli"); the first-person question
is closer to the second vector than to the first, and the observation
behind the fact is the first hit, through the `semantic` channel. The
question is never rewritten (rewriting it with the owner's name lowered
recall for eight models of nine on the bake-off's sample set).

### F17. Recall says what the answer is based on

```text
# an engine whose embedder is off, downloading, loading, or failed
remember(text: "The 3D printer needs a new nozzle, the old one is clogged.")
recall(query: "what is wrong with the printer")
```

Expect: a `channels:` line after the verdict naming the channels that had
their say (`structured, keys, lexical`) and the semantic channel's state
when it did not (`semantic off (…)`, `unavailable (model downloading,
42%; retrieval is partial until it lands)`, `loading`, or `failed: …`),
so a caller knows the retrieval was partial. When the structured channel
had nothing to say (no entity and predicate cue), the line also names what
ranked the hits shown: `the hits below were ranked by lexical`.

### F18. A question about what is coming ranks the upcoming fact first

```text
remember(text: "In 2015 I designed and built Brewbot, an autonomous robotic vehicle for the office.")
remember(text: "The Zenit 4 is ready at the dealer; pickup is next week.",
         proposal: { facts: [ owns Zenit 4, valid_time.start = today + 6 days ] })
recall(query: "what vehicle am I about to collect")
```

Expect: the Zenit observation first, its channels including
`upcoming`, the fact still disclosed as `upcoming: … not yet so`. A
question with a forward cue ("about to", "next", "soon", "going to",
"will", "planned", "scheduled", "due") adds a fifth channel that ranks the
observations behind the future-dated facts of the entities in the
question (or the owner's) at the structured channel's weight; without the
cue ("which vehicle did I build") the robot wins on its word, as before.

## G. Consolidation

### G1. Spec version upgrade re-derives

Not yet implemented (2026-09-12); no test. `spec_version` is accepted and
stored on the observation and fact rows, but nothing re-derives on a bump
and there is no `derived_under` field.

```text
remember(text: "I work at Hooli.", proposal: { spec_version: 1, … })
# bump current spec to 2
consolidate()
```

Expect: facts derived under spec 1 re-derived; results identical for this
input; the stored spec version updated to 2; original observation unchanged.

### G2. Later alias merges earlier duplicates

```text
remember(text: "I met Anna Lindqvist.")
remember(text: "AL and I talked about the profiler.")   # "AL" is too short to fuzzy-match: created, no question
remember(text: "Anna — AL to her friends — is visiting.",
         proposal: { entities: [ Anna Lindqvist, aliases AL, Anna ] })
consolidate()
```

Expect: the third call merges the `AL` entity into Anna Lindqvist
(`resolution: merged`); one entity, aliases `AL` and `Anna`, the old id
following the merge; facts from the second observation re-pointed to it;
the merge recorded (`merges`). `consolidate()` afterwards has nothing left
to merge.

### G3. Consolidate without a model does not fabricate

Data home with no local model, client without sampling.

```text
remember(text: "Met Anna for coffee, she now leads the platform team at Acme.")
consolidate()
```

Expect: observation remains in backlog; no facts created; `consolidate`
reports `pending_proposals: 1`.

### G4. Answers and corrections never join the backlog

```text
remember(text: "I left Hooli, I just don't remember when.", resolve: [{ question_id: q-1, choice: "ended" }])
correct(fact_id: f-1, replacement: { valid_time: { start: "2019" } })
remember(text: "Just a note to self.")
consolidate(retire: ["obs-4"])
```

Expect: `pending_proposals` stays 0 after the answer and after the
correction, both having nothing to extract; the plain note counts 1 until
`consolidate` retires it, after which it keeps its text and history and
`retired` names it. Correction records written before this rule are
retired by the next `consolidate` on their own.

## H. Deployment and isolation

### H1. Separate data homes are isolated

```text
# home A
remember(text: "Project X is secret.")
# home B
recall(query: "Project X")
```

Expect: empty.

### H2. Indexes are rebuildable

```text
remember(text: "I work at Hooli.")
# drop the FTS index rows from the database
# rebuild the indexes
recall(query: "Hooli")
```

Expect: the observation is found again; the indexes are rebuilt from the
rows in `mnemic.db` (the test deletes the FTS rows and calls the rebuild
in-process; there is no separate index directory).

### H4. The owner's configured identity resolves to the owner

```text
# MNEMIC_OWNER="Mattias Sandell", MNEMIC_OWNER_ALIASES=Sandell, MNEMIC_OWNER_EMAILS=mattias@example.com,
# MNEMIC_OWNER_GITHUB=sandell-example, MNEMIC_OWNER_HANDLES=@sandell_example
remember(text: "I work at Hooli.")
recall(query: "where does sandell-example work")
remember(text: "Commit by mattias@example.com: switched the build to Maven.",
         proposal: { facts: [ { subject: "mattias@example.com", predicate: "uses", object: "Maven" } ] })
```

Expect: the handle, the address, the surname alone, and the @-handle in a
query each spot the owner and the recall is `matched`; the proposal's
subject resolves to the owner by alias, no second person is created, and
the fact is the owner's; the owner entity's aliases include the handle.
`status` lists the identity under `owner_aliases`. Tests use placeholder
identities, never the author's real ones.

### H3. Binary directory is never touched

Not yet implemented (2026-09-12); no test.

Run any scenario with the binary directory read-only.
Expect: no writes attempted outside the data home.

---

## I. Granularity

### I1. One observation yields many facts

```text
remember(text: "My wife is Marit. My father is Konrad. We moved from Sweden to Schübelbach in 2014.")
```

Expect: one observation; entities Marit, Konrad, Sweden, Schübelbach;
facts `spouse_of`, `parent_of [father]`, `lives_in(Mattias, Schübelbach) [2014, ∞)`,
`lives_in(Mattias, Sweden) [unknown, 2014)`; event `moved`. All facts share
the same `derived_from` observation id.

### I2. Forgetting the observation removes all derived facts

Continuing from I1:

```text
forget(observation_id: <o>)
recall(query: "who is Mattias married to")
```

Expect: empty; every fact derived from the observation gone; the entities
nothing else references (the wife, the father) removed with it, the owner
kept.

### I3. Pre-chopped sentences lose coreference — and Mnemic does not guess

```text
remember(text: "The war lasted six years.")
remember(text: "It ended in 1945.")
```

Expect: two observations; no entity created for "It"; second observation has
no proposal and sits in the consolidation backlog. No fact links "1945" to
"the war". (Documents that the caller, not Mnemic, is responsible for passing
coherent observations.)

### I4. Oversized observation is accepted with a warning

```text
remember(text: <12,000 characters of text>)
```

Expect: stored; response carries a `warnings` entry recommending chunking;
proposal processed normally.

### I5. A connector's observation gets its facts from the assistant, afterwards

```text
remember(text: "<an email>", source_kind: connector, source_ref: "home/INBOX/943905")     # no proposal: refused with one
remember(observation_id: obs-48, proposal: { events: [ paid(self, Tomas) 2026-09-11 ], facts: [...] })
```

Expect: the observation stored without a reading and listed in
`pending_proposal_ids`; the proposal attached to it afterwards, its facts
and events carrying that observation as their provenance, its rows
embedded, and the observation gone from the backlog. A second proposal for
the same observation is refused (its facts are corrected, not proposed
again). Before this the only reading a connector's observation could get
was a configured proposer's, and a fact the assistant read out of it had to
hang on an observation of the assistant's own, leaving the email pending
for good.

## J. Predicate extensibility

### J1. Caller defines a new predicate on the fly

```text
remember(text: "My godmother is Hedvig.",
         proposal: {
           predicates: [ { name: "godparent_of", description: "Subject sponsored object at baptism or equivalent.",
                           domain: "person", range: "person", functional: false,
                           lexicon: ["godparent", "godmother", "godfather"],
                           render: "{subject} is {object}'s {qualifier|godparent}",
                           qualifiers: ["godmother", "godfather"] } ],
           facts: [ { subject: "Hedvig", predicate: "godparent_of", object: "self", qualifier: "godmother" } ] })
recall(query: "who is Mattias's godmother")
```

Expect: predicate registered with `defined_by` pointing at the observation;
fact stored; recall is `matched` via the structured channel on predicate
`godparent_of`.

### J2. Synonym predicate resolves to an existing one

```text
remember(text: "I work at Hooli.")
remember(text: "Mattias is employed by Hooli.",
         proposal: { predicates: [ { name: "employed_by", description: "Subject works for object organization.",
                                     domain: "person", range: "organization", functional: true } ],
                     facts: [ { predicate: "employed_by", object: "Hooli" } ] })
```

Expect: no new predicate and nothing asserted. The
response reports `predicates: [{ proposed: "employed_by", resolution:
"similar", id: "works_at" }]` and a `predicate_resolution` question whose
message shows both descriptions, with candidates `works_at` and `new`; the
fact is held. Answering `works_at` records `employed_by` as an alias of
`works_at` and applies the fact as one `works_at` fact with `last_confirmed`
updated; the next `employed_by` resolves through the alias without a
question. Token overlap cannot see meaning, so a similar match is never
applied on its own (J7).

### J3. Ambiguous predicate match asks

```text
remember(text: "I advise Acme on their profiler.",
         proposal: { predicates: [ { name: "advises", description: "Subject gives professional advice on work matters to object.",
                                     domain: "person", range: "organization", functional: false } ],
                     facts: [ { predicate: "advises", object: "Acme" } ] })
```

Expect: `works_at` is a near match (same domain/range, the description
sharing "work") but not confident; response returns a `questions` entry of kind
`predicate_resolution` with candidates `works_at` and `new`. Observation
stored; fact held pending. Resolving with `new` registers `advises`.

### J4. Domain/range mismatch is a question

```text
remember(text: "Hooli is my godmother.",
         proposal: { facts: [ { subject: "Hooli", predicate: "godparent_of", object: "self" } ] })
```

Expect: `godparent_of` has domain `person`; `Hooli` is an organization;
response returns a question of kind `type_mismatch` that holds the fact,
with candidates `retype:person` (Hooli is a person after all) and
`dismiss`; a type registered from use also offers `kind:person` (every
such thing is a kind of person). No fact created until answered (S20).

### J5. Predicate correction propagates

Continuing from J1, after several `godparent_of` facts:

```text
correct(predicate: "godparent_of", replacement: { render: "{object}'s {qualifier|godparent} is {subject}" },
        reason: "better rendering")
```

Expect: renderings of all facts under the predicate regenerated (the reply
counts them, `rerendered_facts`); the change is listed under the predicate's
`changes`; a structured recall still matches.

### J7. A restriction defined as a new predicate is not collapsed into an existing one

```text
remember(text: "I own Bergstrasse 7 in Schübelbach, Kanton Schwyz, Switzerland.")
remember(text: "Mattias only owns properties in Switzerland.",
         proposal: { predicates: [ { name: "real_estate_confined_to",
                                     description: "All of the subject's real estate lies within the object; the subject owns no property anywhere else.",
                                     domain: "person", range: "place", lexicon: ["real estate", "property", "confined", "owns", "own"],
                                     render: "{subject}'s real estate is confined to {object}" } ],
                     facts: [ { predicate: "real_estate_confined_to", object: "Switzerland" } ] })
```

Expect: the definition shares words with `owns` and is a `similar` match, so
a `predicate_resolution` question with candidates `owns` and `new` is
raised and nothing is asserted; "what does Mattias own" still reports one
fact. Answering `new` registers the predicate and stores "Mattias Sandell's real
estate is confined to Switzerland"; `owns` never gains the alias. A silent
match here would store "Mattias Sandell owns Switzerland" as a current fact.

### J8. Owning a place that contains something already owned is flagged

```text
remember(text: "I own the Lindenhof apartment in Willisau, Kanton Luzern, Switzerland.")
remember(text: "Mattias only owns properties in Switzerland.",
         proposal: { facts: [ { predicate: "owns", object: "Switzerland" } ] })
```

Expect: the fact is stored, the caller asserted it, but the response carries
a warning naming the contained object ("Switzerland contains Lindenhof
apartment, which Mattias Sandell already owns") and saying that a claim that
everything the subject owns lies within a place is a restriction, not
ownership of the place. Owning a second place nothing contains is not
flagged. Works through current `located_in` facts, up to six hops.

### J9. A free-text qualifier is wording, not identity

```text
remember(text: "Nordvik HB became Nordvik Software Solutions AB, I believe.",
         proposal: { facts: [ related_to(Nordvik HB, Nordvik Software Solutions AB)[believed to be the same company, converted from HB to AB] ] })
remember(text: "As I said, Nordvik HB turned into Nordvik Software Solutions AB.",
         proposal: { facts: [ related_to(...)[believed to be the same company, HB converted to AB, unconfirmed] ] })
```

Expect: one fact, corroborated twice, carrying the fuller wording (the
second, which says more; a restatement that says less or only re-cases
leaves the wording alone), the reply showing the wording taken. A
predicate with a qualifier vocabulary (parent_of: mother, father) keys its
facts by qualifier; one without (related_to, knows) does not. `consolidate`
folds duplicates from before this rule the same way (`duplicates` in its
reply names the fact whose wording was kept).

### J10. The same event with and without its date is one event

```text
remember(text: "I co-founded Nordvik Virtual Machines in 1998.", proposal: { events: [ co-founded(self, AVM) 1998 ] })
remember(text: "Back when I co-founded Nordvik Virtual Machines…", proposal: { events: [ co-founded(self, AVM) ] })
```

Expect: the second proposal returns the first event's id. An undated event
recorded first takes its date from a later dated one (rendering and
effects follow); a different date is a different event. `consolidate`
folds the undated duplicates from before this rule into the dated ones.

### J11. A recollection is not a plan

```text
remember(text: "I think Nordvik Virtual Machines was created around September 1998.",
         proposal: { facts: [ considering "that Nordvik Virtual Machines was created around 1998-09" ] })
```

Expect: no fact, and a warning that `considering` (and `decided`) take a
plan or an option as object, never a statement, with the way to store a
recollection: the fact itself, with `caller_confidence` when unsure.

### J6. Predicates registered from use are listed until described

```text
remember(… proposal: { facts: [ { predicate: "mentors", object: "Anna" } ] })
remember(… proposal: { facts: [ { predicate: "mentors", object: "Erik" } ] })
remember(… proposal: { facts: [ { predicate: "mentors", object: "Sara" } ] })
consolidate(dry_run: true)
```

Expect: report includes `inferred_vocabulary: [{ predicate: "mentors",
uses: 3, observations: [...] }]`; nothing changes until a description is
supplied through `correct` or a later definition.

---

## K. Derived predicates

Not yet implemented (2026-09-12); none of K1–K10 has a test. The
derivation fields these scenarios describe (`defined_as`, `derivation.rule`
and `derivation.base`, `via`, `corroborated_by`, the `invalidated` status)
do not exist in the current proposal schema, whose `derivation` has only a
`kind` (`explicit`, `extracted`, `inferred`).

### K1. Rule derives a fact and it is recallable

Not yet implemented (2026-09-12); no test.

```text
remember(text: "My father is Konrad.")
remember(text: "Konrad's mother was Astrid.")
recall(query: "who is Mattias's grandmother")
```

Expect: derived fact `grandparent_of(Astrid, Mattias) [grandmother, paternal]`
with `derivation: { rule: "grandparent_of", base: [<f-father>, <f-Astrid>] }`;
returned via the structured channel, verdict `matched`. Confidence ≤ min of
the two base facts.

### K2. Derived fact is invalidated when a base fact changes

Not yet implemented (2026-09-12); no test.

Continuing from K1:

```text
correct(fact_id: <f-Astrid>, replacement: { subject: "Signe" }, reason: "wrong name")
recall(query: "who is Mattias's grandmother")
```

Expect: the Astrid-derived fact is gone (status `invalidated`, not
`corrected` — corrections apply to what the user said); a new derived fact for
Signe exists; `history` shows the invalidation linked to the correction.

### K3. Asserted derived relation with unknown side is stored unresolved

Not yet implemented (2026-09-12); no test.

```text
remember(text: "Signe is my grandmother.")
```

Expect: asserted fact `grandparent_of(Signe, Mattias) [grandmother]` with
`derivation: unresolved`; **no** intermediate parent entity created; no
question.

```text
recall(query: "who is Mattias's grandmother")
```

Expect: the asserted fact returned, marked `asserted`, `derivation: unresolved`.

### K4. Partial constraint is kept

Not yet implemented (2026-09-12); no test.

```text
remember(text: "Signe is my paternal grandmother.")
```

Expect: as K3 plus `via: parent_of[father]`.

### K5. Unification corroborates without replacing

Not yet implemented (2026-09-12); no test.

Continuing from K4:

```text
remember(text: "My father is Konrad.")
remember(text: "Konrad's mother is Signe.")
consolidate()
```

Expect: a derived `grandparent_of(Signe, Mattias)` now exists; the asserted
fact from K4 is linked to it (`corroborated_by`), remains `asserted`, and its
confidence increases. `recall` returns one merged result showing both
provenance paths, not two facts.

### K6. Complete contradicting chain raises a conflict

Not yet implemented (2026-09-12); no test.

```text
remember(text: "My father is Konrad and my mother is Gunilla.")
remember(text: "Konrad's mother is Astrid. Gunilla's mother is Anna.")
remember(text: "Signe is my grandmother.")
consolidate()
```

Expect: both grandmother chains are complete and neither yields Signe; a
`conflict` question is raised listing the asserted fact and the two derived
facts. The asserted fact is stored regardless.

### K7. Incomplete chain does not raise a conflict

Not yet implemented (2026-09-12); no test.

```text
remember(text: "My father is Konrad.")
remember(text: "Signe is my grandmother.")
consolidate()
```

Expect: no question — the maternal side is unknown, so Signe may still fit.
The asserted fact stays `unresolved`.

### K8. Rule with valid-time intersection

Not yet implemented (2026-09-12); no test.

```text
remember(text: "I worked at Initrode from 2010 to 2018.")
remember(text: "Anna worked at Initrode from 2015 to 2020.")
recall(query: "who were Mattias's colleagues")
```

Expect: with `colleague_of` defined as sharing `works_at` with overlapping
valid time, derived fact `colleague_of(Mattias, Anna)` valid `[2015, 2018)`,
`ended: true`.

### K9. Unbounded recursion is refused

Not yet implemented (2026-09-12); no test.

```text
remember(text: "…", proposal: { predicates: [ { name: "ancestor_of",
         defined_as: [ { path: ["parent_of+"] } ] } ] })
```

Expect: predicate rejected with an error naming the unbounded repetition;
`parent_of{1,4}` in its place is accepted. Observation stored regardless.

### K10. Rule type-check failure

Not yet implemented (2026-09-12); no test.

```text
remember(text: "…", proposal: { predicates: [ { name: "grand_employer_of",
         defined_as: [ { path: ["works_at", "parent_of"] } ] } ] })
```

Expect: rejected — range of `works_at` is organization, domain of
`parent_of` is person.

---

## M. Time filtering

### M1. `as_of` filters observations before ranking, not after

```text
remember(text: "Mattias works at Initrode on the profiler.")                  # observed 2015-03-01
remember(text: "Mattias works on profiler item <i> at Hooli.") × 95        # observed 2026-01-01
recall(query: "where does Mattias work", as_of: "2015-06-01")
```

Expect: exactly one hit, the Initrode observation, and a candidate count of
one: the 95 later observations never entered the candidate list, so a
store where almost everything is from the wrong period still returns the
right item.

### M2. `as_of` filters facts inside the structured probe

```text
remember(text: "I worked at Initrode from 2010 to 2018.", proposal: { facts: [ works_at Initrode [2010, 2018) ] })
remember(text: "Mattias prefers profiler topic <i>.", proposal: { facts: [ prefers topic <i> ] }) × 60
recall(query: "where did Mattias work", as_of: "2015-06-01")
recall(query: "where did Mattias work", as_of: "2019-06-01")
```

Expect: the first recall is `matched` on the Initrode fact despite the sixty
later facts; the second is a MISS, the interval having ended in 2018. The
time filter runs inside the probe, not on a fused top-k.

## N. Concurrency

### N1. Two processes on one data home see each other's writes

```text
# engine A and engine B open the same data home
remember(text: "Written by the first process.")     # through A
remember(text: "Written by the second process.")    # through B
```

Expect: both engines count two observations; A finds "second" and B finds
"first", one hit each.

---

## Q. Negation and closure

Decided with the K family after two probes against a real store and the
`real_estate_confined_to` incident (J7); the tests are `NegationTest`
(Q1–Q13, all live). The claim "I own these two properties" was storable
before this; "these are all the properties I own" and "I own nothing in
Sweden" were not, and both are common: all my mail is on these two
accounts, only these people have keys, I have no other employer. Three
shapes cover them:

- `"negated": true` on a fact: the subject does not stand in the relation to
  the object. The object is an entity or a literal class ("anything in
  Sweden"). Renders as a negation (`{subject} does not own {object}`; a
  predicate without a negated template renders `not: ` + rendering).
- `"only": true` on a fact whose object is a place: an exclusive restriction.
  Every asserted fact under the predicate for the subject whose object can
  be located must lie, through the `located_in` chain, within the bound;
  objects that cannot be located (a domain, a printer) are outside the class.
  Renders `{subject} owns only within {object}`.
- `closures: [{ subject, predicate, type }]`: a completeness marker. The
  recorded facts under the predicate for the subject whose objects have that
  type are all of them. Renders `what {subject} owns among places is
  completely recorded`.

Rules shared by all three: none is listed among the subject's positive facts
and each appears on a `bounds:` line under the verdict; the briefing lists
what is so, never what is not; a contradiction on the same key is a
`conflict` question with the existing answers (`ended`, `wrong`, `reject`)
in both arrival orders; containment the chain cannot decide is a
`containment` question (answers `yes`, `no`), never an assertion; two
entities typed `country` are disjoint. The verdict state `known_false`
(headline `KNOWN FALSE`) carries the fact or restriction that decides it.

### Q1. A negated fact is known false, not unknown

```text
recall(query: "does Mattias own a boat")                         # MISS, "not evidence of no"
remember(text: "I do not own a boat.",
         proposal: { facts: [ { predicate: "owns", object: "boat", negated: true } ] })
recall(query: "does Mattias own a boat")
```

Expect: the fact renders "Mattias Sandell does not own boat"; the second recall
has state `known_false`, a `KNOWN FALSE` headline naming the fact, and no
"not evidence of no".

### Q2. A negated fact is not listed among the positive ones

```text
# two owned Swiss properties, then "I do not own a boat." as in Q1
recall(query: "what does Mattias own")
```

Expect: `matched` with two facts; a line `bounds: Mattias Sandell does not own
boat`; the briefing does not contain "does not own".

### Q3. A class negation answers the polar question over the class

```text
remember(text: "I relocated from Sweden to Switzerland in 2014. I own nothing in Sweden.",
         proposal: { events: [ relocated ], facts: [ { predicate: "owns", object: "anything in Sweden", negated: true } ] })
recall(query: "does Mattias own any property in Sweden")
```

Expect: `known_false` with "does not own anything in Sweden"; the Swiss
purchase and the move are context, never "the answer".

### Q4. A negation against a positive fact is a conflict, not an overwrite

```text
remember(text: "I own a boat.")
remember(text: "I do not own a boat.", proposal: { facts: [ { … negated: true } ] })
```

Expect: a `conflict` question ("no longer" and "never did" differ) with
choices `ended`, `wrong`, `reject`; the negation is `pending`, the positive
fact `current`. Answering `ended` closes the positive fact and makes the
negation current; the polar question is then `known_false` and the earlier
ownership is visible as history.

### Q5. A positive fact against a negation is a conflict whose `ended` answer closes the negation

```text
remember(text: "I do not own a boat.", …negated)
remember(text: "I bought a boat.", proposal: { facts: [ owns boat ], events: [ purchased ] })
```

Expect: a `conflict` question; after `ended` the polar question is
`matched` with one fact, no `KNOWN FALSE`, and no `bounds:` line, the ended
negation being history.

### Q6. An exclusive restriction answers no outside its bound and asks where the chain is incomplete

```text
# Lindenhof apartment → Willisau → Kanton Luzern → Switzerland (country);
# Bergstrasse 7 → Schübelbach → Kanton Schwyz (chain stops); Sweden exists as a `country` entity
remember(text: "I only own properties in Switzerland.",
         proposal: { facts: [ { predicate: "owns", object: "Switzerland", only: true } ] })
```

Expect: renders "Mattias Sandell owns only within Switzerland" and is `current`;
one question, kind `containment`, naming Kanton Schwyz (K7: an incomplete
chain is not a conflict). "does Mattias own any property in Sweden" (Sweden a
`country`) is `known_false` "by restriction". "does Mattias own property in
Kanton Schwyz" is `matched` but says the containment is not known. Answering
the containment question `yes` stores `Kanton Schwyz located_in Switzerland`
and the "not known" disappears.

### Q7. A fact outside an exclusive restriction is a conflict

```text
# the restriction of Q6, then
remember(text: "I bought a cabin in Sälen, Sweden.",
         proposal: { facts: [ owns Sälen cabin, Sälen cabin located_in Sälen, Sälen located_in Sweden (country) ] })
```

Expect: a `conflict` question, the cabin `pending`. After `ended` (the
restriction held until now) "what does Mattias own" lists three facts with
no `bounds:` line and the Sweden question is `matched`.

### Q8. An exclusive restriction over places leaves other kinds of object alone

```text
# two Swiss properties, then "I own the domain server.example.se and a Bambu Lab printer."
remember(text: "I only own properties in Switzerland.", …only)
```

Expect: the domain and the printer cannot be located, so neither a conflict
nor a question about them; the only question is the Kanton Schwyz chain.
"what does Mattias own" lists four facts and the `bounds:` line.

### Q9. A completeness marker turns an absent fact into a no

```text
# two Swiss properties, then
remember(text: "Those are all the properties I own.",
         proposal: { closures: [ { subject: "self", predicate: "owns", type: "place" } ] })
recall(query: "does Mattias own any property in Sweden")
recall(query: "does Mattias own a boat")
```

Expect: the Sweden question is `known_false` "by closure", with the line
`bounds: what Mattias Sandell owns among places is completely recorded`; the
boat question stays a MISS, a boat being outside the class. A later
`owns` fact with a place object is a `conflict` against the closure with
the usual answers (`ended`: the list was complete until then).

### Q13. The bounds line is scoped to what the question names

```text
# two Swiss properties, "I have no shop machines" (negated, class), "only in Switzerland" (only), "I own a Zenit 4"
recall(query: "does Mattias own the Zenit 4")
recall(query: "does Mattias own a shop lathe")
recall(query: "what does Mattias own")
```

Expect: no `bounds:` line under the car question, since neither bound could
cover a car; the machines negation but not the Swiss bound under the lathe
question; every bound under the whole-predicate question. The yes/no note
varies with what is below: "nothing recorded says no either" when no bound
applies, "the bounds below do not decide it" when one is listed.

### Q10. A functional predicate answers no for another value

```text
remember(text: "I work at Hooli.")
recall(query: "does Mattias work at Acme")      # Acme known or unknown
```

Expect: `known_false` with "one current value, Mattias Sandell works at
Hooli". "does Mattias work at Hooli" is `matched`. A non-functional
predicate never decides this way: "does Mattias lead Kubernetes" against one
`leads` fact is a MISS.

### Q11. A past-tense question is answered by history and never decided false

```text
remember(text: "I worked at Initrode until 2018, then joined Hooli.")
recall(query: "did Mattias work at Initrode")
recall(query: "does Mattias work at Initrode")
```

Expect: the past-tense question is `matched` by the ended Initrode fact, with
no `KNOWN FALSE`; the present-tense one is `known_false` by the one current
value.

### Q12. A closure stays in its lane

```text
remember(text: "I lead OpenJDK Kestrel and the Profiler team; that is all I lead.",
         proposal: { facts: [ leads Kestrel (project), leads Profiler (product) ],
                     closures: [ { subject: "self", predicate: "leads", type: "project" } ] })
remember(text: "Kubernetes is a project; Anna leads it.")
```

Expect: "does Mattias lead Kubernetes" is `known_false` by closure; "does
Mattias lead OpenJDK Kestrel" is `matched`; a product outside the closed class
("does Mattias lead Sentinel") is a MISS; "does Anna lead Kubernetes" is
`matched`, her facts being outside Mattias's closure. Repeating the closure
corroborates it rather than storing a second one.

---

## R. Language

The fact layer has a language (`MNEMIC_LANGUAGE`, `en` or `de`):
templates, temporal suffixes, and the words around a negation, a restriction,
a closure, or a belief are the language's; family qualifiers are stored as
the vocabulary's English word and rendered in the language's. Observations
stay in the language they were said in. Switching the language re-renders
every fact from what is stored.

### R1. A German store renders facts in German

```text
# MNEMIC_LANGUAGE=de
remember(text: "Ich arbeite seit 2018 bei Hooli.", proposal: { facts: [ works_at Hooli since 2018 ] })
remember(text: "Marit ist Oskars Mutter.", proposal: { facts: [ parent_of(Marit Nyberg, Oskar Nyberg, mother) ] })
remember(text: "Ich besitze kein Boot.", proposal: { facts: [ owns Boot, negated ] })
remember(text: "Ich glaube, ich wohne in Zug.", proposal: { facts: [ lives_in Zug, caller_confidence: 0.5 ] })
recall(query: "wo arbeitet Mattias")
```

Expect: "Mattias Sandell arbeitet bei Hooli (seit 2018)", "Marit Nyberg ist
Mutter von Oskar Nyberg" with the qualifier stored as `mother`, "Mattias
Sandell besitzt Boot nicht" from the language's negation template, the belief
rendered "Mattias Sandell wohnt in Zug (vermutet)"; the German question cues
`works_at` through the language's cue words, and an English question still
does.

### R2. Switching the language re-renders every fact

```text
# open with en, remember "I worked at Initrode from 2010 until 2018."; reopen with de; reopen with en
```

Expect: "Mattias Sandell works at Initrode (2010 – 2018)", then "Mattias Sandell
arbeitet bei Initrode (2010 – 2018)" after the reopen with no change to the
row, then the English rendering again; `store_meta` records the language
the facts were last rendered in.

### R4. A defined predicate carries templates for other languages

```text
# MNEMIC_LANGUAGE=de
remember(text: "Ich betreue Anna.", proposal: { predicates: [ { name: "mentors", render: "{subject} mentors {object}",
  renders: { de: { render: "{subject} betreut {object}", negated: "{subject} betreut {object} nicht", lexicon: ["betreut"] } } } ],
  facts: [ mentors Anna Lindqvist ] })
correct(predicate: "mentors", replacement: { renders: { de: "{subject} ist Mentor von {object}" } })
```

Expect: "Mattias Sandell betreut Anna Lindqvist", a negated fact from the
German negation template, the German cue word reaching the predicate in a
question, the correction re-rendering the facts and logged as `renders.de`,
and a template for a language the store does not know refused with a
warning.

### R5. Registering from use in a German store

```text
remember(text: "Ich betreue Anna.", proposal: { facts: [ betreut(self, Anna) ] })
remember(text: "Ich habe 2019 die Hütte geerbt.", proposal: { events: [ geerbt(self, die Hütte) 2019 ] })
```

Expect: `betreut` is registered from use with its own word as lexicon and
`{subject} betreut {object}` as template, so the fact reads "Mattias Sandell
betreut Anna Lindqvist" and "wen betreut Mattias" matches structurally; the
event type `geerbt` renders from its name and asks `event_effect` like any
other. A later definition with `renders.de` completes the predicate
(`definitions` says `defined`, with `rerendered_facts`) and the stored fact
re-renders through the German template.

### R6. A German name close in meaning to an English predicate is asked about

Expect: with the embedder loaded, `betreut` after `mentors` raises the
`predicate_resolution` question with `mentors` as a `semantic` candidate,
exactly as an English near-synonym would. Answering with `mentors` records
`betreut` as its alias, so the German name resolves silently from then on,
and the alias cues the predicate: "wen betreut Mattias" matches `mentors`.
Calibration against the real model (`VocabularyCalibrationTest`, German
names against a German store): `wohnt_in`, `arbeitet_bei`, `verheiratet_mit`,
`kennt` score as semantic; `angestellt_bei`, `vater_von`, `leitet` as
ambiguous; nothing unrelated is flagged.

### R3. An unknown language is refused

Expect: `fr` is refused at start (not a known language); unset means `en`.

## S. Vocabularies

Predicates, event types, and entity types are registries: seeded by the
server, extended from a proposal or from a term's first use, corrected
through `correct`, listed by `inspect('registry')`. A registration is kept
for good and is there at the next start. A term used before anyone defined
it is registered with everything inferred from its name, and the store asks
once what it means (S13–S15, S18–S20); a definition arriving later completes
it (S16).

### S1. An event type defined in a proposal takes effect

```text
remember(text: "I inherited the cabin in Sälen from my grandmother.", proposal: {
  event_types: [ { name: "inherited", opens: ["owns"], lexicon: ["inherited", "inherit"] } ],
  entities: [ e1 "the Sälen cabin" (place) ], events: [ inherited(self, e1) ] })
inspect(ref: "event:inherited")
```

Expect: the event type is registered (`definitions` names it), the event
opens `owns` so "Mattias Sandell owns the Sälen cabin" is stored, and
`inspect` shows `inherited` with origin `defined` and the observation that
defined it.

### S2. A defined vocabulary survives a restart

Expect: after closing and reopening the store, `inherited` is still
registered, a new event of that type still opens `owns`, and the entity type
of S3 still nests within `place`.

### S3. An entity type with a parent nests within it

```text
remember(text: "I live in Kanton Schwyz.", proposal: {
  entity_types: [ { name: "canton", parent: "place", synonyms: ["kanton"], type_words: ["kanton", "canton"] } ],
  entities: [ e1 "Kanton Schwyz" (kanton) ], facts: [ lives_in e1 ] })
remember(text: "Kanton Luzern is next door.", proposal: { entities: [ e1 "Kanton Luzern" (canton) ] })
```

Expect: `kanton` resolves to `canton`, `lives_in` (range `place`) accepts
the canton, and "Kanton Luzern" is a new entity rather than a question
against "Kanton Schwyz": the shared word is a type word, not an identity.

### S4. A definition that names the unknown is skipped, not stored

Expect: an event type whose `opens` names an unregistered predicate, and an
entity type whose `parent` is unregistered, are skipped with a warning; the
rest of the proposal is applied and nothing is registered.

### S6. A restriction over a caller-defined place kind decides a question

```text
remember(proposal: { entity_types: [ canton within place ], entities: [ Kanton Schwyz (canton), Switzerland (country) ],
  facts: [ Kanton Schwyz located_in Switzerland, owns Kanton Schwyz only: true ] })
recall(query: "does Mattias own anything in Sweden")       # Sweden (country) known
remember(text: "I own a flat in Kanton Luzern.", proposal: { entities: [ Kanton Luzern (canton) ], facts: [ Kanton Luzern located_in Switzerland, owns e1 ] })
```

Expect: KNOWN FALSE by restriction, since Sweden and Kanton Schwyz reach
different countries; the flat in Kanton Luzern is a `conflict` question,
since the two cantons meet under Switzerland on different branches.

### S7. A defined event type closes a fact that arrives later

Expect: with `gave_away` defined to close `owns`, an event
`gave_away(self, the boat)` dated 2021 followed by "I own the boat since
2015" stores the fact as `(2015 – 2021)`, ended by the event.

### S8. A defined lexicon answers a question with the event

Expect: after `inherited(self, the cabin)` dated 2019 under a type whose
lexicon is "inherited, inherit", `recall("when did Mattias inherit the
cabin")` has the events verdict with that event on the events line.

### S9. An entity typed by a synonym before its type existed takes the registered name

Expect: an entity stored with type `kanton` while no such type is
registered keeps `kanton`; registering `canton` with synonym `kanton`
retypes it to `canton`, and `lives_in` (range `place`) now accepts it.

### S10. A definition travels with a held proposal

Expect: a proposal that defines `inherited` and names an ambiguous "Anna"
as the heir holds the event behind the entity question; the type is
registered at once, and answering the question applies the event, whose
type opens `owns` for the chosen Anna.

### S11. A defined type that ends an entity closes its open facts

Expect: with `wound_up` defined with `ends_entity`, the event
`wound_up(Nordvik AB)` dated 2023 ends "Nordvik AB is located in
Stockholm" at 2023 and sets the organization's `existed_end`; the owner's
`works_at Nordvik AB` is not the organization's own fact and stays.

### S12. Consolidate honours a corrected event type

Expect: an event of a type that closed nothing when it happened, followed
by a correction that makes the type close `owns`, dates the end of a fact
flagged `ended` without a date at the next `consolidate` (`reclosed: 1`).

### S13. An unregistered event type registers from use and asks what it does

```text
remember(text: "I inherited the cabin in 2019.", proposal: { entities: [ the cabin (place) ], events: [ inherited(self, the cabin) 2019 ] })
```

Expect: the event is stored with no effect on facts; `inherited` is
registered with the words of its name as lexicon and `{subject} inherited
{object}` as template (`definitions` names it with resolution `inferred`);
names are spelled one way (`Purchased Property` and `co-founded` become
`purchased_property` and `co_founded`); a sentence where the type goes
("dealer confirmed receipt of the payment", more than three words or two
content words, or a number) is not a type: the event is stored as a plain
occurrence, nothing is registered or asked, and a warning says so;
the reply carries one `event_effect` question whose candidates are
`opens:<p>` and `closes:<p>` for every predicate whose domain and range fit
the participants (`owns`, not `works_at` for a place), `ends_entity` when
the event has one participant, and `none`. Nothing is held. A second event
of the type before the answer asks nothing.

### S14. An unregistered entity type registers from use and asks what kind it is

Expect: entities typed `canton` while no such type is registered are stored
as written; `canton` is registered without a parent; one `type_kind`
question offers the root types (`place`, `organization`, …, not `country`)
and `none`, however many entities of that type the proposal names. Later
entities of the type ask nothing.

### S15. A bare predicate registers from use

Expect: a fact under `mentors` with no definition is stored under `mentors`
with a warning; the predicate has wildcard domain and range, the lexicon
`[mentors, mentor]`, and no description, so "who does Mattias mentor"
matches structurally. The reply's `definitions` entry carries `inferred`:
the assumed domain, range, direction, functional, volatility, lexicon, and
template, and the `correct` call that changes them. Stating domain and
range in the same proposal's `predicates` makes the registration `defined`
with nothing inferred. `correct(pred:mentors, {description, domain, range})`
completes it later; the name then resolves exactly, with no warning.

### S16. Consolidate lists vocabulary registered from use until it is defined

Expect: `inferred_vocabulary` names each predicate, event type, and entity
type registered from use with its number of uses (and the observations for a
predicate). A definition arriving in a later proposal for a term registered
from use completes it (`definitions` says `defined`) instead of being
ignored as a duplicate, and the entry disappears.

### S18. Answering what an event type does applies to its stored events

```text
remember(text: "I inherited the cabin in 2019.", proposal: { events: [ inherited(self, the cabin) 2019 ] })   # q-1 event_effect
remember(text: "I inherited the boat in 2021.", proposal: { events: [ inherited(self, the boat) 2021 ] })
remember(text: "Inheriting made them mine.", resolve: [{ question_id: q-1, choice: "opens:owns" }])
```

Expect: `inherited` now opens `owns` (and supersedes it when the predicate
is functional); the answer's `applied` reports both stored events and the
two facts they opened, dated from the events; the type is no longer listed
as inferred; a later `inherited` event opens `owns` directly and asks
nothing. `closes:<p>` ends the matching facts at the events' dates,
`ends_entity` closes the participant, `none` records a plain occurrence.

### S19. Answering what kind an entity type is makes it accepted where its parent is

```text
remember(text: "I live in Kanton Schwyz.", proposal: { entities: [ Kanton Schwyz (canton) ], facts: [ lives_in(self, Kanton Schwyz) ] })   # q-1 type_kind, q-2 type_mismatch
remember(text: "A canton is a Swiss region.", resolve: [{ question_id: q-1, choice: "place" }])
```

Expect: `canton` gets `place` as parent; the `type_mismatch` question the
parent resolves is settled in the same answer and its held fact stored
("Mattias Sandell lives in Kanton Schwyz"); no question stays open; a later
`born_in` a canton asks nothing.

### S20. A type mismatch is answered by retyping or by a new kind

Expect: an entity typed `organization` by mistake under `parent_of` raises a
`type_mismatch` whose candidates are `retype:person` and `dismiss` (a seeded
type is never re-parented); `retype:person` retypes the entity and stores
the held fact. For a type registered from use the candidates also include
`kind:place`, which sets the parent, stores the held fact, and settles the
open `type_kind` question for the type. `dismiss` drops the fact.

### S21. A partial definition counts as a definition

Expect: a `predicates` entry with no description but `functional: true` and
`range: person` completes a predicate registered from use (`definitions`
says `defined`, with `rerendered_facts` and, when `functional` came on,
`rechecked_conflicts`): it is no longer listed as inferred, the stated fields
replace the inferred ones, and the unstated ones (domain, the name's
lexicon) stay as inferred. A first use carrying such a partial definition is
`registered`, not `inferred`.

### S22. Making a predicate functional re-checks its facts

```text
remember(text: "I share a flat with Anna.", proposal: { facts: [ shares_a_flat_with(self, Anna) ] })
remember(text: "I share a flat with Erik.", proposal: { facts: [ shares_a_flat_with(self, Erik) ] })   # both current
correct(target: "pred:shares_a_flat_with", replacement: { functional: true })
```

Expect: the reply carries `rechecked_conflicts: 1`; the earlier fact stays
current, the later one becomes `pending` behind a `conflict` question of the
usual shape, answered like any other (`supersede` closes the earlier one).
A correction that does not turn `functional` on re-checks nothing.

### S23. A bare name close in meaning to a registered predicate is asked about

```text
remember(text: "I mentor Anna.", proposal: { facts: [ mentors(self, Anna) ] })
remember(text: "I coach Erik.", proposal: { facts: [ coaches(self, Erik) ] })      # q-1 predicate_resolution
remember(text: "Same thing.", resolve: [{ question_id: q-1, choice: "mentors" }])
```

Expect: with an embedder available, `coaches` shares no word with `mentors`
but lies close to it in meaning, so the fact is held behind a
`predicate_resolution` question whose first candidate is `mentors` with
`match: semantic`, plus `new`. Answering with `mentors` stores the fact
under `mentors` and records `coaches` as its alias, so the next `coaches`
resolves silently. A name close to nothing registers from use as before.
Without an embedder only word overlap is compared, as before.

### S24. Consolidate reports close predicates, and correct merges them

Expect: two predicates that lie close in meaning (after `new` was answered
to S23's question, or when both were registered before the embedder
loaded) are listed by `consolidate` under `similar_vocabulary` as
`{predicate, close_to, score}`. `correct(pred:coaches, {merge_into:
"mentors"})` re-keys and re-renders every fact under `coaches`, records the
name as an alias of `mentors`, removes the `coaches` registration, logs the
merge on `mentors`, and the pair disappears from the report.

### S25. An inferred direction is corrected at once and recall follows it

```text
remember(text: "Anna mentors me.", proposal: { facts: [ mentors(Anna, self) ] })      # definitions: inferred domain/range *
remember(text: "I mentor Erik.", proposal: { facts: [ mentors(self, Erik) ] })
recall(query: "who is Mattias's mentor")                                              # both facts: no direction on record
correct(target: "pred:mentors", replacement: { domain: "person", range: "person" })
recall(query: "who is Mattias's mentor")                                              # Anna mentors Mattias
```

Expect: the reply to the first remember shows the assumed wildcard domain
and range under `definitions[].inferred`. "Who does Mattias mentor" says
which side Mattias is on and returns his mentee alone; "who mentors Mattias"
and "Mattias's mentor" return both facts until the sides are known. The correction takes effect at once: the predicate is no
longer inferred, its facts are re-rendered, "Mattias's mentor" returns only
the fact where Mattias is the object, and an organization as a mentee is a
`type_mismatch` from then on.

### S26. A store from 2.2 opens with its vocabulary registered from use

Expect: a store at schema 18 holding an `x:consults_for` predicate with no
lexicon, an event of the unregistered type "purchased property", and an
entity of the unregistered type `canton` opens under this release with the
predicate renamed `consults_for` (facts, renders, changes, and questions
following), the words of its name as lexicon, the event type spelled
`purchased_property` and registered from use, a sentence-long type spelled
the same way but left unregistered, the entity type registered from use, the
three registered terms listed under `inferred_vocabulary`, and no question
asked at start. A second open changes nothing, and `consolidate(rebuild: true)`
re-derives the same store from its 2.2 readings: the `x:` prefix in an old
reading is dropped, the sentence-typed event stays an occurrence, and the
only question raised is `event_effect` for the two-word type nobody has
described, asked once.

### S27. A reading is replaced and the observation keeps its identity

```text
remember(text: "I ordered a red bicycle from Nordvik Cycles in March.", proposal: { events: [ "ordered a red bicycle from the shop in town"(self, Nordvik Cycles) ] })   # obs-1
remember(observation_id: obs-1, proposal: { entities: [ the red bicycle (thing) ], events: [ ordered(self, the red bicycle) 2025-03 ], facts: [ buys_from(self, Nordvik Cycles) ] })
```

Expect: the second call replaces the reading rather than being refused. What
the old reading produced is taken back and listed under `replaced` (facts,
events, `reopened_facts`); the observation keeps its id, text, and observed
date; the new facts carry it as provenance; entities keep their ids; a fact
another observation also stated survives there with one corroboration
fewer; open questions of the observation are dismissed and raised afresh
by the new reading. A correction record is refused: it is not a reading of
the world. Rule of thumb: re-read when the reading was wrong; `correct` when
the user says the world is otherwise.

### S28. Closures are undone when their cause goes

Expect: a fact an event closed (`left` ending `works_at`), superseded (a
`moved` event's `lives_in` replacing the earlier one), or corrected is
current again when the observation behind that event or fact is re-read or
forgotten; its end is cleared when the closure gave it, the supersession
record goes, and an entity a `died` event ended exists again. `forget` shares
the same undoing.

### S29. A rebuild re-derives the projection from the log

```text
consolidate(rebuild: true)
```

Expect: every observation with a reading is read again in order; correction
records do their work again against the fact their key now names; answers
once given to an observation's questions are given again when the same
question comes back; entities keep their ids, facts and events get new ones;
the current knowledge is the same as before; a second rebuild changes
nothing. `rebuilt` reports observations, corrections, answers, and
`unmatched`: correction records whose fact no longer exists, left for a
person. Never on a dry run.

### S30. A correction record carries its reading

Expect: the observation a correction or retraction creates stores a reading:
`corrects` or `retracts` with the fact's key (subject, predicate, object,
qualifier, scope, mode), the reason, and the replacement fact (none for a
retraction). It is what a rebuild replays, and it takes the record out of
the proposal backlog.

### S31. Events typed by a sentence are reported for re-reading

Expect: `consolidate` lists under `descriptive_events` every event whose
type is a sentence rather than a type, with the observation to re-read; a
re-read with a proper type removes the entry.

### S17. An event type template renders its events

Expect: with `render: "{subject} inherited {object}"`, the event reads
"Mattias Sandell inherited the cabin (since 2019)" on the events line;
correcting the template re-renders the type's events (`rerendered_events`)
and survives a restart; a template without `{subject}` is refused; seed
types have templates ("Mattias Sandell joined Hooli"). A type without a
template renders as `type(participants)`.

### S5. A vocabulary correction is logged

```text
correct(event_type: "inherited", replacement: { closes: ["owns"] }, reason: "an inheritance ends the giver's ownership")
correct(entity_type: "canton", replacement: { type_words: ["kanton", "canton", "ct"] })
```

Expect: `before` and `after` in the reply, one logged change per field with
its reason, and the corrected definition in force at once and after a
restart.

## External benchmarks

Beyond the hand-written scenarios above, the `bench` module runs
**LongMemEval** and reports the metrics listed at the top of this document.
Only the `s` variant (distractor sessions present) is reported; `oracle`
numbers isolate answer composition from retrieval and are not meaningful
for a memory system. Besides the full set, the **hard 106** subset (the
answerable questions where lexical retrieval alone misses at least one
evidence session in its top five) shows what a proposer adds where lexical
fails. The abstention questions (`_abs`) are reported separately as
`abstention_recall`: a correct abstention requires the structured-miss
signal to reach the answer model, and a fabricated answer there is classed
`ABSTENTION_MISS`. Results and the ablations are in
[BENCHMARKS.md](BENCHMARKS.md).

**Competitor runs** have not been done. The intent is to run scenarios C1,
C2, C9, and F5 against Memento (`shane-farkas/memento-memory`) and the
reference MCP memory server, adapting calls to each tool surface, as the
minimum evidence for the differences the internal competitive analysis
claims in temporal reasoning, conflict handling, unknown bounds, and the
structured miss; that document is a source reading, not a run.

## T. Field feedback

What a day of real use against the installed binary found (2026-09-16), each
fixed with the case that found it.

### T1. A fact stated beside the event that opens it takes the event

```text
remember(text: "I work at Acme.", proposal: { facts: [ works_at(self, Acme) ] })
remember(text: "I joined Globex in March 2024.", proposal: { events: [ joined(self, Globex) 2024-03 ], facts: [ works_at(self, Globex) ] })
```

Expect: no conflict question. The stated fact names no `derived_from`, but
the proposal's `joined` opens `works_at` between the same participants, so
the fact takes the event: it starts at the event's date with
`start_source: event`, and the earlier job is superseded at that date, as
it would be had the fact been derived from the event.

### T2. Answers need no observation

```text
remember(resolve: [{ question_id: q-5, choice: "ent-7" }])
```

Expect: accepted with neither `text` nor `observation_id`; the answers are
applied against each question's own observation, the closing date of a
`supersede` is today, `resolved` is returned, and no observation is
recorded. Answers live on the questions.

### T3. A name that says more is asked about, not merged; forget takes an alias back

Expect: "Raspberry Pi 5" against a known "Raspberry Pi", and "Luzern"
against a known "Kanton Luzern", are `entity_resolution` questions (a
number is an identity token; a place word names another level), and no
alias is added until the answer. "Hooli Inc" against "Hooli" still merges
silently, since an organization's type word is a suffix of the same thing.
Forgetting the observation that added an alias to an existing entity
removes the alias; the entity's own name stays.

### T4. Forgetting a fact forgets its corrections

Expect: `forget` on the observation behind a fact also forgets the
correction records that corrected or retracted it, recursively, since a
correction restates the fact; no fact, record, or recall hit survives.

### T5. A role at an organization ends with the employment

Expect: when `works_at(s, o)` ends, whatever ended it (an event, a conflict
answer, a correction), every open fact of `s` scoped to `o` under a
predicate functional per scope (`holds_role`) ends at the same date with
`end_source: dependency`; an unrelated closure leaves it alone.

### T6. A stored qualifier cues its predicate

Expect: "who is Mattias's cousin" matches `related_to` structurally through
the qualifier `cousin` stored on a fact, returning only the fact with that
qualifier; the same for "former partner". Free-text qualifiers in use are
cues, from either side.

### T7. Consolidate removes what forgetting left behind

Expect: an entity created by a forgotten observation is removed as soon as
the `forget` that took its last reference runs, even when that is a later
observation than the one that created it; a `forget` with `keep_entities`
leaves it for `consolidate`, which sweeps the same way (`removed_entities`),
never on a dry run and never the owner; vocabulary a forgotten observation
defined stays, with `defined_by` cleared.

### T8. An entity is corrected by id

```text
correct(target: "ent-12", replacement: { aliases: ["Kanton Luzern", "LU"] }, reason: "the city is not the canton")
```

Expect: `aliases` is the list to keep, so the alias a wrong match left
("Luzern" on Kanton Luzern) is dropped and the next "Luzern" is asked
about; the entity's own name and the owner's configured identity never
drop; `name` renames and re-renders every fact that mentions the entity;
`type` retypes.

### T9. A present-tense miss names the ended facts, and a fact has one standing

```text
remember(text: "Anna worked at Initrode from 2019 to 2022.", proposal: { facts: [ works_at(Anna, Initrode) 2019 – 2022 ] })
recall(query: "where does Anna work")
```

Expect: the verdict is MISS, worded "no current value; 1 ended fact:
Anna Lindqvist works at Initrode (2019 – 2022) [f-1]; pass include_history
to have them returned", never "no such fact is known" while an ended fact
of that subject and predicate exists; with `include_history` the ended
facts are the match, and `as_of` a date inside the interval matches the
one then current. In every reply a fact carries one `standing`: current,
ended, or future by its dates while the record holds it, else superseded,
corrected, pending, or rejected; the old pair `status` and `state`, which
disagreed on any fact whose dates had passed, is gone from the surface.

### T10. A closure completes a registered class

```text
remember(text: "That is the only place I live.", proposal: { closures: [{ subject: self, predicate: lives_in, type: "exhaustive" }] })
```

Expect: the closure is skipped and the reply warns, naming the unknown
type and listing the registered ones ("Closure skipped: closure over
unknown entity type 'exhaustive': a closure completes a class of things,
one of [country, organization, person, place, ...]; define a new type
under 'entity_types' first"); nothing is stored for it, as for a fact over
an unknown predicate. A synonym of a registered type (`nation`) completes
that type (`among countries`).

### T11. A restriction reads grammatically

```text
remember(text: "I only live in Switzerland.", proposal: { facts: [{ subject: self, predicate: lives_in, object: e1, only: true }] })
```

Expect: "Mattias Sandell lives only within Switzerland", never "lives in
only within": a template whose own preposition leads to the object gives it
up to the restriction's. `owns` keeps "owns only within Switzerland"; in
German "wohnt nur innerhalb von Schweiz".

### T12. A miss does not blame the other entity's events on the subject

```text
remember(text: "Anna joined Initrode in 2024.", proposal: { events: [ joined(Anna, Initrode) 2024 ] })
recall(query: "does Mattias work at Initrode")
```

Expect: MISS; Initrode's event is still listed as context, but the verdict
says "the 1 event on the next line involves what else the question names,
not Mattias Sandell", not "1 event about Mattias Sandell may explain why".
Once an event the subject took part in exists ("I left Initrode in 2020"),
the verdict counts that one: "1 event about Mattias Sandell on the next
line may explain why".

### T13. Letters alone do not raise a question across types

```text
remember(text: "Sandvik is a customer.", proposal: { entities: [ Sandvik (organization) ] })
remember(text: "I know Sandvol.", proposal: { facts: [ knows(self, "Sandvol") ] })
```

Expect: no entity-resolution question; "Sandvol" is created. It shares no
name part with "Sandvik" and gave no type, and a trigram score of 0.45
across types (or with none) is not enough to ask: letters alone must
agree to 0.60 there. The same letters with the same type still ask
("Sandvig", organization), and a shared name part asks whatever the type
("Anna" against Anna Lindqvist).

### T14. Consolidate lists vocabulary nothing uses whose definition was forgotten

```text
remember(text: "I built a robot called Coff-E.", proposal: { entity_types: [ robot ], event_types: [ built ], entities: [ Coff-E (robot) ], events: [ built(self, Coff-E) 2025 ] })
forget(id: "obs-1")
consolidate(dry_run: true)
```

Expect: `unused_vocabulary` lists `event_type: built` and
`entity_type: robot`, each with `defined_by: "obs-1 (forgotten)"` and
`uses: 0`; while the observation stood they were not listed. A real run
unanchors the definitions and still lists them (`defined_by: null`).
Seeded and inferred vocabulary never appears here (inferred terms have
their own list); nothing is removed on its own.

### T15. A couple who are not married

```text
remember(text: "Anna and Bo live together.", proposal: { facts: [ partner_of(Bo, Anna, boyfriend) ] })
remember(text: "Anna married Bo in June 2024.", proposal: { events: [ married(Anna, Bo) 2024-06 ] })
recall(query: "who is Anna's partner")
recall(query: "who is Anna married to")
```

Expect: `partner_of` is a seed predicate (symmetric, functional, medium
volatility; qualifiers girlfriend, boyfriend, partner), so the couple is
not filed under `related_to`. The wedding closes the partnership at
2024-06 although it was stated from Bo's side, and opens `spouse_of`. The
partner question is a MISS naming the ended fact; the marriage question
matches. `separated` ends a partnership the same way.

### T16. An engagement

```text
remember(text: "Anna and Bo got engaged in 2023.", proposal: { events: [ engaged(Anna, Bo) 2023 ] })
remember(text: "Anna married Bo in June 2024.", proposal: { events: [ married(Anna, Bo) 2024-06 ] })
```

Expect: `engaged` opens `engaged_to` (a seed predicate: symmetric,
functional, high volatility) from 2023; the wedding ends it at 2024-06 and
opens `spouse_of`. No ad-hoc definition and no "did you mean spouse_of"
question.

### T17. A step-parent

```text
remember(text: "Lars is my stepfather.", proposal: { facts: [ step_parent_of(Lars, self, stepfather) ] })
```

Expect: "Lars is Mattias Sandell's stepfather" under `step_parent_of`, a
seed predicate of its own (person → person, not functional, not symmetric),
so a step-parent never counts as a parent when siblings or grandparents are
derived from `parent_of`. `parent_of` no longer lists stepmother and
stepfather among its qualifiers, and its lexicon cues only parents. In
German: "Lars ist Stiefvater von Mattias Sandell".

### T18. An undated death ends facts without inventing a date

```text
remember(text: "Bosse lived in Zug.", proposal: { facts: [ lives_in(Bosse, Zug) ] })
remember(text: "Bosse has died.", proposal: { events: [ died(Bosse) ] })
```

Expect: the `lives_in` fact is ended (`ended: true`, standing `ended`) with
no end date, its `end_source` `entity_ended`; the entity's `existed_end`
stays unset. Nothing is closed "at now". A later date for the death fills
the end in through the event.
