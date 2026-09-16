# Mnemic Design

## Overview

Mnemic is a local-first persistent knowledge substrate for AI assistants. It exposes durable knowledge through MCP while
remaining independent of any particular model, assistant, or application.

The core design combines structured, temporal, provenance-aware knowledge with structured, lexical, semantic, and
temporal retrieval. Graph retrieval beyond place containment chains is a goal, not built as of 2026-09-12.

## Design goals

- Local-first operation with no required cloud service or database server.
- Zero-infrastructure deployment using a native executable and an embedded data store.
- Model- and assistant-independent MCP interface.
- Preserve original observations separately from facts inferred from them.
- Treat knowledge as temporal rather than silently overwriting history.
- Make provenance and confidence first-class properties.
- Support hybrid retrieval using structured, lexical, vector, graph, and temporal information.
- Keep derived indexes disposable and rebuildable from canonical persisted data.
- Minimize the filesystem and process privileges required by an AI assistant.

## Deployment model

Mnemic targets Java, Quarkus, and GraalVM Native Image. The normal deployment consists of an Mnemic executable and one
or more independent Mnemic data homes.

The executable installation and data homes are deliberately separate:

```text
/opt/mnemic/
└── mnemic

/home/alice/.mnemic/
├── mnemic.db          the store: observations, facts, events, questions, predicates, embeddings, FTS
├── mnemic.log         the server log (stdout is the MCP transport, so nothing is written to the console)
└── models/            fetched embedding models and the ONNX Runtime library, shared by every data home
                       on the machine (MNEMIC_MODELS_DIR; ~/.mnemic/models even when the data home is elsewhere)

/home/alice/work-mnemic/
├── mnemic.db
└── mnemic.log
```

Search indexes are tables inside `mnemic.db` (FTS5 and the `embedding` table); there is no separate index
directory. A data home is selected, in this order, by the `mnemic.home` system property, the `MNEMIC_HOME`
environment variable, or the default `~/.mnemic`; there is no command-line flag. The working directory is never
used (desktop hosts start the server with an arbitrary one).

### Binary/data separation

Separating the executable from its data is a security and operational design principle, not merely a directory
convention.

A single Mnemic binary should be installable and updatable independently of all knowledge bases that use it. Multiple
MCP configurations may invoke the same binary with different data homes.

An AI assistant using Mnemic should normally interact with the knowledge base exclusively through the MCP interface. It
should not require direct filesystem access to the executable directory. Keeping writable or assistant-accessible data
homes separate from the binary reduces the risk that an agent capable of manipulating its knowledge files can also
replace, corrupt, or otherwise modify the program that will subsequently be executed.

The binary location should therefore normally be read-only from the perspective of the assistant, while access to each
data home can be granted according to the intended scope of that Mnemic.

This separation also enables distinct personal, work, project, or experimental Mnemic data homes without duplicating the
executable.

## High-level architecture

```text
Sources / assistants
        |
        v
      MCP API
        |
        v
 Knowledge Engine
   |    |    |
   |    |    +-- ingestion / consolidation
   |    +------- temporal + graph reasoning
   +------------ hybrid recall
        |
        v
      SQLite
   |     |      |
 facts  FTS   embeddings
 events
```

SQLite is the canonical persistent store. The FTS tables are maintained by triggers, and the `embedding` table is
refilled in the background once the model loads (rows stored before it was ready, or under an earlier model), with
`consolidate` filling whatever is still missing; both are derived data. No cached graph structures or summaries
exist as of 2026-09-12.

## Knowledge model

The canonical model, as implemented (schema 18):

- **Entity** — an identifiable person, project, organization, place, concept, or other subject, with a type and
  aliases; the owner is an entity that first-person references resolve to.
- **Observation** — original information presented to Mnemic, kept verbatim with its source kind (user, assistant,
  conversation, document, connector), source reference, chunk index, and observed-at time, plus the caller's
  structured proposal as sent. An observation can be retired (marked wrong or superseded, kept) or forgotten (removed,
  a dated tombstone remains).
- **Fact** — a structured assertion: subject, predicate, object, optional qualifier and scope, valid time, a mode
  (asserted, negated, only-restriction, closure), a state (future, current, ended), a status (current, superseded,
  corrected, pending), confidence, and provenance through the observations that support it. Supersession and
  correction are recorded as rows, not as overwrites.
- **Event** — something that happened at a point or over an interval, with a type and participants. Event types come
  from a registry and say which facts they open, close, or supersede.
- **Question** — what Mnemic could not decide alone (an ambiguous entity, a similar predicate, a conflicting fact, an
  uncertain place containment), held until the assistant answers.
- **Predicate registry** — the vocabulary facts are written in: seed predicates plus caller-defined ones, each with
  description, domain, range, functional and symmetric flags, allowed qualifiers, volatility, aliases, a lexicon of
  cue words, and a render template per language.

Relations are not a separate object: a fact under a relational predicate is the typed connection between two
entities. Provenance is carried by the observation's source columns and the fact-to-observation links; there is no
separate Source object.

### Confidence

Fact confidence is computed, not stored: a base by source kind (a user's statement or correction 0.80, a
conversation 0.75, a document 0.70, anything else 0.60), lowered for an inferred derivation, raised a little for
each further observation that corroborates the fact (up to four), and capped by the confidence the caller stated
("I think" lowers it, nothing raises it). Facts on predicates that change with time show when they were last
confirmed and are flagged once that is old. A per-source reliability attribute from which an observation's confidence would
be derived was part of the original design and is not built as of 2026-09-12.

### Events versus Facts

Events are *occurrences*: they happen once, at a point or over a bounded interval in valid time, and typically have
participants ("Mattias joins Hooli", "the team decides to use SQLite"). Facts are *states*: they hold over an interval
in valid time and have a subject and predicate ("Mattias works at Hooli", "Mnemic uses SQLite").

The canonical inputs are Observations (what was presented to Mnemic, in transaction time) and the proposals that
came with them, from which Events (what happened, in valid time) and Facts are stored. Every observation keeps its
proposal verbatim so that facts could be re-derived; as of 2026-09-12 what is rebuilt automatically is narrower: fact
renderings are regenerated after every migration and when the store's language or a predicate's template changes,
and event effects (opening, closing, superseding facts) are applied when the event is stored or found later by
`consolidate`. A Fact may also be directly asserted by an Observation with no known originating Event ("I work at
Hooli"); such Facts record that they are asserted rather than event-derived.

The most important temporal property of an Event is when it happened, not when Mnemic learned of it. Both are retained.

Corrections normally preserve history. Explicit forgetting is a distinct operation that can physically remove knowledge
when requested.

## Retrieval

Recall is hybrid rather than synonymous with vector search. Four channels run on every query, a fifth when the
question asks what is coming, and their candidate observations are fused with reciprocal rank fusion (k = 60), the
structured and upcoming channels counting double:

- **structured** — entity spotting and predicate cues resolve to a direct fact lookup over valid time; this channel
  also produces the verdict (matched, MISS, KNOWN FALSE, NOT YET, events) and the bounds line;
- **keys** — BM25 over fact and event renderings ("facts as keys");
- **lexical** — BM25 over observation text;
- **semantic** — cosine similarity between the query embedding and the stored observation and fact embeddings,
  at most 100 candidates; absent, with the answer marked partial, while the model is downloading or loading;
- **upcoming** — only when the query carries a cue such as "about to", "next", or "soon": the observations behind
  future-dated facts of the entities named in the question (the owner's when none is), so a plan outranks an old
  observation that shares its words.

Temporal constraints (`as_of`) apply inside every channel rather than as a post-filter; current facts outrank ended
and superseded ones; provenance and confidence are reported with each hit; the result is cut to a token budget.
Graph use is limited to place containment (`located_in` chains, six hops) and to entity merges; general graph
traversal is not built as of 2026-09-12.

The `recall` tool hides this machinery from the calling model; `status` reports which channels answer right now.

## MCP surface

The observation is the source of truth and a reading is how it was understood. `remember(observation_id, proposal)`
gives an observation its reading, or replaces the one it has: what the old reading produced is taken back, closures
it caused are undone, and the text keeps its id and date. `correct` records that the user says the world is
otherwise; it is a log entry of its own, never an edit of a derived row. `consolidate(rebuild: true)` re-derives the
whole projection from the log. See ARCHITECTURE.md, "The log and the projection".

The MCP surface is seven tools:

```text
remember   recall   inspect   correct   forget   consolidate   status
```

`remember` stores an observation with its reading, or attaches a reading to one stored without (`observation_id`);
`inspect` shows anything by id or name; `correct` changes anything by id, including withdrawing a fact or retiring
an observation. Every stored thing has one kind of id (`obs-`, `f-`, `ent-`, `evt-`, `q-`, `pred:`, `event:`,
`type:`), so the assistant chooses a target, never a tool. Each tool's description tells the assistant when to use it
and when not to; the schemas are in `MnemicTools.java` and summarised for users in the [README](../README.md).

## Persistence and vector search

SQLite is the current choice for canonical storage because it is embedded, transactional, portable, mature, and
compatible with the zero-infrastructure deployment goal.

Vector search is local and, as decided after measurement, an exact scan: one float32 vector per observation chunk
and per fact-rendering variant, per model, in the `embedding` table, scored by dot product in Java. At personal-knowledge scale this
is faster than an approximate-nearest-neighbour index would be to maintain. The sqlite-vec extension can be loaded
(`MNEMIC_VEC_LIBRARY`; `status` reports its version) and was proven to work inside the native image, but it is not
used for search as of 2026-09-12; it is the fallback for a store that outgrows the scan. The vectors are an
acceleration structure: `consolidate` re-embeds whatever the current model has not seen.

## Embeddings

Embeddings are computed locally, inside the server process, by ONNX Runtime running
`ibm-granite/granite-embedding-311m-multilingual-r2` (the 8-bit AVX2 graph on x86, the full-precision graph on ARM),
chosen in a bake-off of fourteen candidates on English, German, and Swedish questions. The runtime library is part
of the build; only the model files are fetched, on first start, from Hugging Face or a configured mirror, and every
file is checked against a hash pinned in the code. There is no embedding-provider SPI and no support for an external
embedding service as of 2026-09-12; nothing is ever sent to one. `MNEMIC_EMBED=off` runs without the semantic
channel.

## Security and privacy

Mnemic contains potentially sensitive personal knowledge and should operate with least privilege.

Key principles include:

- local storage by default;
- explicit data-home boundaries;
- separation of executable and writable knowledge data;
- no requirement for assistants to access the binary directory;
- provenance for stored knowledge;
- explicit distinction between correction/supersession and deletion;
- no mandatory network dependency;
- ability to operate fully offline: the embedding model can come from a mirror or a pre-placed directory
  (`MNEMIC_EMBED_MODEL_URL`, `MNEMIC_EMBED_MODEL`), or the semantic channel can be switched off (`MNEMIC_EMBED=off`).

## Technology choices

As built (versions from the POMs, 2026-09-12):

- Java 25, GraalVM 25 for the native image
- Quarkus 3.39.2 with `quarkus-mcp-server-stdio` 2.0.0 (MCP over stdio)
- SQLite through `sqlite-jdbc` 3.53.4.0, FTS5 included; schema migrations with checksums, applied in place
- ONNX Runtime 1.29.0, bundled per platform at build time, called through the Java FFM API
- an exact float32 vector scan in Java; sqlite-vec loadable but unused for search
- one local embedding model; no pluggable embedding providers (see Embeddings)

These choices give a small, portable, zero-infrastructure executable consistent with Mnemic's local-first design.

## Status

The design work this document originally left open has been done and is recorded, with the measurements that
decided it, in the project's internal decision record (the measurements are in [BENCHMARKS.md](BENCHMARKS.md)): the schema (eighteen migrations), temporal and provenance semantics,
ingestion and consolidation, hybrid recall ranking, the tool schemas, the local embedder and vector scan, the
data-home layout and migrations, the memory protocol (`protocol/guide.md` and `protocol/extraction-spec.md` in the
server resources), and staleness flagged from the time since a fact was last confirmed (the "absence of events"
question: such facts are annotated with their last confirmation and listed by `consolidate` for review; confidence
itself does not decay).

Still open as of 2026-09-12: an explicit permissions and threat model beyond the principles under Security and
privacy, and backup and recovery beyond copying `mnemic.db`.
