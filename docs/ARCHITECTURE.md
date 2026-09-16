# Mnemic Architecture

How the code is put together: modules, packages, the two paths through the engine, the schema, and where to add
things. What Mnemic is for and why the knowledge model looks the way it does is in [DESIGN.md](DESIGN.md); how
observations become facts is in [EXTRACTION.md](EXTRACTION.md); building and testing is in
[DEVELOPMENT.md](DEVELOPMENT.md).

## Modules

```text
mnemic-parent
├── server   the MCP server: Quarkus, SQLite, the embedder; packaged as an uber-jar and a native image
└── bench    the LongMemEval harness; depends on the server jar, defines no providers of its own
```

The server has no runtime dependency on any vendor SDK. Everything that talks to a language model goes through the
`ModelProvider` service-provider interface in `se.hirt.mnemic.model`, over `java.net.http`.

## Layers

```text
MnemicTools ─── the MCP tool surface (argument parsing, reply shapes); ToolSupport wraps errors
     │
   Engine ───── one instance per data home; the operations that span services (remember, correct, consolidate)
     │
     ├── observation   ObservationService: the verbatim store
     ├── knowledge     entities, predicates, events, facts, questions; the write path and the read path
     ├── recall        query analysis, the channels, fusion, rendering
     ├── embed         the in-process embedder (ONNX Runtime over FFM), tokenizers, vectors, model fetching
     ├── proposal      the proposal shape and its parser; the optional server-side proposer
     ├── model         the ModelProvider SPI and its two HTTP implementations
     └── persistence   one SQLite connection, transactions, forward-only migrations
```

Dependencies point downwards only. `knowledge` and `recall` know nothing about MCP or Quarkus; `persistence` knows
nothing about the domain. The engine is constructed without a container by the scenario tests and the bench, so the
code path that is measured is the code path that ships.

## The engine

`Engine` is opened from an `Engine.Options` record (data home, owner, clock, proposer, embedder, language) and owns
one `Database`. It wires the knowledge layer through `Knowledge.open(...)`, which builds the services in dependency
order and keeps the package-private helpers (`FactLedger`, `FactQuestions`, `ConflictCheck`) inside the package.
The engine exposes the read-side services to the tool surface and the tests, and keeps the write side to itself.

`EngineProducer` is the CDI wiring: it reads `MnemicConfig`, sets up the proposer and the `EmbedderHolder`, and
produces the one engine per process. The holder starts the model download or load on a daemon thread, so the server
answers within milliseconds of starting and the semantic channel joins when the model is ready.

## The log and the projection

Two layers, with a contract between them. The **log** is the observations: text stored verbatim and never
edited, a source, a time, and the reading the assistant attached (the proposal, stored beside the text). Correction
records are entries in the log too: `correct` on a fact creates an observation of source kind `correction` whose
reading names the fact it corrects by key (subject, predicate, object, qualifier, scope, mode), the reason, and the
replacement. The **projection** is everything derived from the log: entities, facts, events, questions. Every fact
points back at the observations that stated it.

The rule that follows: anything that changes knowledge is a log entry, and the projection can be re-derived from the
log. Two operations keep it so.

- **Re-reading.** `remember(observation_id, proposal)` on an observation that already has a reading replaces the
  reading (`Engine.reread`): what the old reading produced is taken back (`FactService.forgetDerived`, which also
  undoes the closures those facts and events caused: a fact an event closed or a fact superseded is current again,
  an entity a `died` event ended exists again), the observation keeps its id, text, date, and provenance, and the
  new reading applies. This is for a reading that was wrong (a mis-filed event, a sentence where the type goes, a
  wrong object). `correct` is for when the user says the world is otherwise: new information, dated now, with its
  own record. A correction record itself is never re-read.
- **Rebuilding.** `consolidate(rebuild: true)` (`Engine.rebuild`) takes every derived row back, newest entry first,
  then reads every observation again in order: correction records do their work again against the fact their key
  now names (or, when none matches, keep what the user stated as a fact of the record and are reported as
  `unmatched`), and answers once given to an observation's questions are given again when the same question comes
  back. Entities keep their ids; facts and events get new ones. The projection is therefore a cache of the log.

Fact and event ids are handles for a conversation; observation ids last. `forget` is the only operation that removes
a log entry, and it shares the undoing with re-reading; it also removes the aliases the observation added to entities
it did not create, and forgets the correction records that corrected its facts, since those restate them. Answers to
questions are not log entries: they live on the questions and are replayed from there, so `remember(resolve)` alone
records nothing.

Stores from before readings were kept on correction records (2.2) get them at open: `FactService.backfillCorrectionReadings`
reads the supersession row the record wrote and the record's own fact, so a rebuild replays those corrections too. A
2.2 reading that names an undefined predicate with the `x:` prefix replays as the bare name. A rebuild reproduces
what the readings and corrections say; what `consolidate` had shaped by housekeeping (an end dated by a later
closing event, folded duplicates) is derived again by the write path's own rules and can differ in a detail such as
the precision of a sequence end. On a real store of 29 observations and 11 corrections the rebuilt projection
matched the original fact for fact, with one end date at year rather than day precision.

## The write path: an observation becomes facts

```text
remember(text, source, proposal)
  ObservationService.remember      store the text verbatim (idempotency key, duplicate detection)
  QuestionResolver.resolve         apply the caller's answers to open questions first
  FactService.apply                the proposal, entity by entity, event by event, fact by fact
  Engine.embed                     vectors for the observation and the new facts, when an embedder is present
```

`FactService.apply` is the one place a proposal turns into rows:

0. **Vocabulary** the proposal defines (`event_types`, `entity_types`) is registered first, so what follows can
   use it; a definition that names an unknown predicate or parent is skipped with a warning. A term the proposal
   uses without a definition (an event type, an entity type, a bare predicate) produces a `suggestion` in the reply
   with a definition skeleton, so the assistant can ask the user and define it next time.
1. **Entities** resolve through `EntityService`: first person → the owner; exact name or alias; then a fuzzy step
   gated by name entropy. A near match becomes an `entity_resolution` question and every fact that mentions the
   name is held in the question's payload (`FactQuestions.heldProposal`).
2. **Events** are stored once each (`EventService.store` dedupes by type, participants, and date) and their effects
   applied (`EventService.applyEffects`): the facts a type closes end at the event's date, and a type that ends an
   entity closes every open fact of it. A type that opens a predicate (`purchased` → `owns`) supplies the fact when
   the proposal did not state it.
3. **Facts** resolve their predicate through `PredicateRegistry` (exact, alias, a name similar by its words or,
   with the embedding model loaded, by meaning, which becomes a `predicate_resolution` question, or a registration
   from this first use), their operands, and their qualifier; `Bounds`
   normalises valid time against the observation date; `FactRenderer` produces the sentence. Inside one
   transaction the row is either a restatement (`FactLedger.corroborate`) or new, in which case `ConflictCheck`
   decides whether it stands, is sequenced behind a later value, supersedes an older one through its event, or is
   stored `pending` behind a `conflict` question. Containment gaps raise `containment` questions instead of
   contradictions.
4. **Closures** are facts of mode `closure` so that history and conflicts apply to them unchanged.

Every status change of a fact goes through `FactLedger` (`fact_source` for provenance, `supersession` for the
change), and nothing is deleted except by `forget`. Corrections (`FactService.correct`, `FactService.retract`) build a
replacement proposal from the original row and send it through the same path, so history reads the same whether a
fact was corrected by the user or superseded by an event.

`Consolidator` is the housekeeping pass: merges entities that share an alias, dates ended facts from closing events
that arrived later, settles entity questions whose subject now exists, and folds duplicate facts and events.

## The read path: a question becomes a block

```text
recall(query, as_of, budget)
  Query.analyse           entities spotted, predicate cues, terms, yes/no and tense, the FTS expression
  StructuredProbe.probe   entity × cue over valid time → matched | miss | future | known_false | entity | unresolved
  channels                structured, keys (fact and event renderings), lexical (observation text), semantic, upcoming
  fuse                    reciprocal rank fusion over observations; the structured and upcoming channels weigh double
  budget                  hits within the token budget, facts first when the text does not fit
  RecallRenderer.render   the verdict line, the channels line, bounds, events, then the hits with their facts
```

Every channel produces observation ids; facts anchor the observations they came from (`fact_source`), so a fact
restated in a later conversation reaches that conversation too. `as_of` is applied inside each channel: facts by
their valid time (`FactQueries.probe`, `knownBy`), observations by when they were observed. The gates in
`RecallService.gates` keep the owner's own facts from ranking under a cue that names dozens of them; the reasons and
the measurements behind each gate are on the methods.

`Briefing` is the recall without a query: the owner's best-corroborated facts, recently touched entities, open
questions, within the budget.

## The knowledge package

| Class | Role |
|---|---|
| `EntityService` | entities and aliases: the owner, the resolution ladder, query-time spotting, merges |
| `PredicateRegistry` | the seed vocabulary, caller definitions, resolution of proposed names, cues, corrections |
| `EventTypeRegistry` | which event types open, close, supersede, or end an entity; caller definitions, corrections |
| `EntityTypeRegistry` | the kinds of entity: synonyms, type words, nesting (a country is a place); caller definitions, corrections |
| `Vocabulary` | what the registries share: JSON list columns, list-valued corrections, the change log (package-private) |
| `EventService` | events stored once, their effects on facts, lookups |
| `QuestionService` | storage of the question queue |
| `FactService` | the write path: a proposal to rows, corrections, retractions, forgetting |
| `FactQueries` | the read path: by id, entity, observation; the probe over valid time; history; the review list |
| `FactRenderer` | the words of a fact from its columns; re-rendering after template or language changes |
| `FactLedger` | provenance links and status changes (package-private) |
| `ConflictCheck` | whether a new fact can stand beside what is on record (package-private) |
| `FactQuestions` | the questions the write path asks (package-private) |
| `QuestionResolver` | applying the caller's answers |
| `Consolidator` | housekeeping |
| `Containment` | where places lie, from `located_in` facts |
| `Bounds` | a valid-time interval with precision and provenance; parsing of stated and relative dates |
| `Names`, `Lang` | text normalisation; the language of the fact layer |
| `Knowledge` | wires the above over one database |

Records `Entity`, `Fact`, `Event`, `Question`, `Supersession`, `Predicate` are the rows as the rest of the code
sees them; `Fact.state(now)` is the one place that turns valid time into `current`, `future`, or `ended`.

## Schema

One SQLite file per data home, WAL mode, opened through one connection guarded by a lock; the second process on
the same file is left to SQLite's own locking. `Migrations` applies `db/migration/V*.sql` forward only, records
checksums, and refuses a database written by a newer binary.

| Table | Holds |
|---|---|
| `observation` | the verbatim text, its source, both timestamps, the proposal it came with, retirement |
| `entity`, `entity_alias`, `entity_merge` | entities, every name they go by, and the record of merges |
| `predicate`, `predicate_render`, `predicate_change` | the registry, per-language templates, the change log |
| `event_type`, `event`, `event_participant` | the event vocabulary and the events |
| `entity_type`, `vocabulary_change` | the entity kinds, and the change log of event type and entity type corrections |
| `fact`, `fact_source`, `supersession` | facts, the observations behind each, every status change |
| `question` | the queue of what the caller must decide |
| `forgotten_link` | tombstones: which entities a forgotten observation had facts about |
| `embedding` | one vector per chunk, item, and model |
| `store_meta` | the language and vector scheme the store was last rendered with |

`observation_fts`, `fact_fts`, and `event_fts` are external-content FTS5 tables kept by triggers; they are
disposable and rebuilt by `Database.rebuildIndexes`.

## The semantic channel

`Embedder` runs a sentence-embedding model through the ONNX Runtime C API over foreign-function downcalls
(`OrtRuntime`), with the tokenizer read from the model's own `tokenizer.json` (`Unigram` for sentencepiece
vocabularies, `Bpe` for byte-level merges). The runtime library ships inside the build (`OrtLibrary`, pinned by
hash); the model is fetched on first use (`ModelFetcher`, pinned by hash) into a models directory shared by every
data home. `VectorStore` keeps vectors beside their rows and searches by scan; sqlite-vec is loaded when configured.
`OwnerAlias` gives a fact about the owner a second, first-person vector so a first-person question lands on it.

## Extension points

- **A language model vendor**: one `ModelProvider` implementation and one line in
  `META-INF/services/se.hirt.mnemic.model.ModelProvider`. The server ships Anthropic and OpenAI-compatible
  (LM Studio, Ollama, OpenAI) providers.
- **A predicate**: a definition in a proposal, or a seed entry in `PredicateRegistry.seed()`; German templates in
  `RENDERS_DE`.
- **An event type or an entity type**: a definition in a proposal (`event_types`, `entity_types`), corrected
  through `correct`; or a seed entry in the registry's `seed()`. The three registries read their table once at
  start into hash maps and write through on every registration and correction, so the next start sees what this
  one defined.
- **A language of the fact layer**: a `Lang` constant with its suffixes and words, templates in
  `PredicateRegistry`, and first-person rules in `OwnerAlias`.
- **A recall channel**: a method on `RecallService` that ranks observation ids into `Channels` and a line in
  `fuse`.
- **A migration**: the next `Vnnn__name.sql`, listed in `Migrations.MIGRATIONS`; never edit an applied one.

## Invariants the code keeps

- Observations are stored verbatim and never rewritten; `forget` blanks the text and leaves a tombstone.
- A fact row is never deleted by a correction; it is closed or marked, and the reason is written down.
- Valid time decides what held when; observation time only says since when a fact could have been known.
- Confidence comes from provenance, never from the clock; staleness is an annotation.
- What Mnemic cannot decide it asks: an ambiguous name or predicate holds the fact, a conflict stores it pending.
- Renderings are derived from columns and can always be recomputed; the words are never the record.

## Tests

Scenario tests under `server/src/test/.../scenario` are tagged with the scenario ids of
[EVALUATION.md](EVALUATION.md) and drive the engine without a container through `TestHomes`. `MnemicToolsTest` is
the one `@QuarkusTest`. `NativeImageSanityIT` starts the native binary over stdio. The bench module's
`HarnessTest` covers ingestion, metrics, and the proposer plumbing with a scripted model.
