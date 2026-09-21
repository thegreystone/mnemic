# Benchmarks

Every row is a run of the `bench` module under the protocol in the section below. Runs are reproducible from
their `config.json`; the result directories live under `bench/results/` and are not committed. Numbers in brackets
are 95% percentile-bootstrap intervals (2,000 resamples, fixed seed). A change counts as a gain only when the paired
bootstrap of the per-question difference excludes zero.

Oracle-variant numbers (the LongMemEval oracle variant) and self-judged numbers are never reported here. The
bench's `engine_version` label in `config.json` was not bumped for M2 and M3, so the `m2-lexical` and
`m3-lexical` rows below say `m1` there; the engine is the one named in the row.

## Protocol

Written down once so every run is comparable (this section moved here from the implementation plan):

- **Datasets**: `longmemeval_s_cleaned.json` from the
  `xiaowu0162/longmemeval-cleaned` Hugging Face dataset (MIT); the `m`
  variant from the same dataset is not yet used. Never `oracle`. Stored
  outside the repository; the path is a CLI argument.
- **Ingestion**: one fresh data home per question; sessions ingested in
  haystack order with `observed_at` set to the haystack date; granularity is
  a flag (`session` default, `turn` for the ablation).
- **Recall**: `recall(question, as_of = question_date, max_tokens = budget)`;
  budget is a flag, default 4000 tokens.
- **Reader**: a fixed open model through an OpenAI-compatible endpoint (LM
  Studio locally is fine); model id, temperature 0, and the reading prompt
  are recorded in `config.json`. `--reader none` skips the reader and reports
  retrieval metrics only.
- **Judge**: LongMemEval's per-type judge prompts; the bench also writes
  `hypotheses.jsonl` in the official format so the official
  `evaluate_qa.py` can be run for reported numbers.
- **Metrics**: accuracy per question type and overall with 95% bootstrap
  intervals; abstention precision and recall on `_abs` items; retrieval
  recall@5 and recall@10 at session level against `answer_session_ids`;
  p50 and p95 recall latency; tokens per query; ingestion seconds per
  question. Every wrong answer is classified RETRIEVAL_MISS (reference not in
  context) or ANSWER_ERROR (reference in context), and a wrong answer to an
  abstention item ABSTENTION_MISS (`Diagnose.java`).
- **Comparison**: `bench compare --a A --b B` runs a paired bootstrap over the
  per-question labels of two runs with identical question sets and reports
  the difference with its interval. A change is reported as a gain only if
  the interval excludes zero.
- **Reporting**: this document carries a table per dataset with run
  id, date, engine version (the `engine_version` field in `config.json` is a
  fixed label, `m3`, not read from the engine; no git commit is recorded),
  flags, reader, and every metric above. Oracle
  numbers and self-judged numbers are never reported.


## LongMemEval_s — retrieval only (no reader, no judge)

Dataset `longmemeval_s_cleaned.json` (500 questions; 470 answerable, 30 abstention). Session recall@k is the
fraction of a question's `answer_session_ids` found among the top-k distinct sessions returned by `recall`, with
`as_of` set to the question date and a 4,000-token budget. Abstention items are excluded from retrieval metrics, as
in the official evaluation.

| Run          | Date       | Engine          | Channels                                                                                                                                                              | Granularity | recall@5                 | recall@10                | recall p50/p95 | ingest p50 | context tokens |
|--------------|------------|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|--------------------------|--------------------------|----------------|------------|----------------|
| `m0-session` | 2026-09-07 | M0              | FTS5 BM25 over observations                                                                                                                                           | session     | **0.857** [0.830, 0.883] | **0.895** [0.871, 0.918] | 1 ms / 2 ms    | 44 ms      | 3,836          |
| `m0-turn`    | 2026-09-07 | M0              | FTS5 BM25 over observations                                                                                                                                           | turn        | 0.839 [0.810, 0.866]     | 0.869 [0.843, 0.894]     | 1 ms / 2 ms    | 147 ms     | 3,782          |
| `m1-lexical` | 2026-09-07 | M1, no proposer | structured probe (no facts) + BM25 over renderings + BM25 over observations, RRF; owner aliases dropped from the lexical query; prefix matching on tokens ≥ 4 letters | session     | 0.862 [0.835, 0.888]     | 0.891 [0.865, 0.915]     | 2 ms / 5 ms    | 29 ms      | 3,849          |
| `m2-lexical` | 2026-09-07 | M2, no proposer | as `m1-lexical`; valid-time probes, supersession, event effects (inactive without facts)                                                                              | session     | 0.862 [0.835, 0.888]     | 0.891 [0.865, 0.915]     | 2 ms / 4 ms    | 29 ms      | 3,849          |
| `m3-lexical` | 2026-09-07 | M3, no proposer | as `m2-lexical`; question queue, ambiguity band, merges, consolidate (inactive without facts)                                                                         | session     | 0.862 [0.835, 0.888]     | 0.891 [0.865, 0.915]     | 3 ms / 6 ms    | 34 ms      | 3,849          |
| `m3-facts-local-qwen9b-500` | 2026-09-09 | M3 (owner-cue fix), proposer `lmstudio:qwen/qwen3.5-9b` Q4_K_M | structured probe + fact and event keys + BM25 over observations, RRF | session | **0.885** [0.861, 0.909] | **0.917** [0.897, 0.938] | 3 ms / 6 ms | 151 s (4 local slots) | 3,762 |
| `st-facts-local-qwen9b-20` | 2026-09-16 | branch `simplified-tools` (seven tools, vocabulary registered from use, semantic predicate match, schema 22), proposer `lmstudio:qwen/qwen3.5-9b` Q4_K_M | as `m3-facts-local-qwen9b-500` | session | 0.950 [0.850, 1.000] n=20 | 1.000 [1.000, 1.000] n=20 | 5 ms / 13 ms | 217 s (4 local slots, proposals regenerated) | 277 |

Per question type, recall@5:

| Type                      | n   | `m0-session`         | `m0-turn`            |
|---------------------------|-----|----------------------|----------------------|
| single-session-user       | 64  | 1.000                | 1.000                |
| single-session-assistant  | 56  | 1.000                | 1.000                |
| knowledge-update          | 72  | 0.979 [0.951, 1.000] | 0.986 [0.965, 1.000] |
| single-session-preference | 30  | 0.867 [0.733, 0.967] | 0.733 [0.567, 0.900] |
| multi-session             | 121 | 0.844 [0.795, 0.889] | 0.818 [0.768, 0.866] |
| temporal-reasoning        | 127 | 0.661 [0.594, 0.727] | 0.649 [0.580, 0.718] |

### Ablation 1: granularity (`m0-turn` − `m0-session`)

Paired difference in recall@5 over the 470 answerable questions: **−0.015 [−0.034, 0.004]**. The interval includes
zero; session-level indexing is not measurably better on this metric, though it is never worse and ingests three
times faster. This agrees with the LongMemEval paper's own finding that sessions index best while smaller units read
best. Default stays `session`; the reader-side question (present turns, not sessions) is deferred to the first
reader-judged run.

### Ablation 2: M1 recall machinery without facts (`m1-lexical` − `m0-session`)

Paired difference in recall@5: **+0.013 [−0.002, 0.030]**; recall@10: +0.002 [−0.009, 0.013]. Not a
measurable change, which is the expected result: with no proposer the fact channels are empty and only the
lexical changes (owner aliases dropped from the query, prefix matching in place of stemming) act. Per type,
multi-session rose 0.844 → 0.863 and single-session-user fell 1.000 → 0.984 (one question), so the two changes
pull in different directions; both are kept until the facts-as-keys run says otherwise.

Structured states over the 500 questions: 388 `entity` (the speaker was spotted, no predicate cue), 92 `miss`
(entity and predicate cue resolved, nothing known), 20 `unresolved`. With no facts every state is empty; the
distribution shows how often the query analysis finds a cue on real questions.

### Regression: M2 engine without facts (`m2-lexical` − `m1-lexical`)

Paired difference: **0.000 [0.000, 0.000]** on recall@5 and recall@10, identical per question. The time
machinery (valid-time probes, supersession, event effects) changes nothing on the retrieval path until facts
exist, as intended. Run `m2-lexical`, 2026-09-07, same configuration as `m1-lexical`.

### Regression: M3 engine without facts (`m3-lexical` − `m2-lexical`)

Paired difference: **0.000 [0.000, 0.000]** on recall@5 and recall@10, identical per question; recall@5
0.862 [0.835, 0.888], recall@10 0.891 [0.865, 0.915], n=470, recall p50 3 ms, ingest p50 34 ms. The question
queue, the entity ambiguity band, merges, and consolidation touch only the write path and the structured
channel, which stays empty without a proposer. Run `m3-lexical`, 2026-09-07, same configuration as
`m1-lexical`.

### Facts as keys, pilot (`m3-facts-pilot-d` − `m3-lexical`, 20 questions)

The first run with a proposer: `anthropic:claude-haiku-4-5` reads every session with the extraction spec
(`bench run --proposer ... --limit 20 --workers 8`, 2026-09-07). The 20 questions are the first in the file,
all `single-session-user`, 980 sessions, 231 facts per question, 2 of 980 proposals unparseable after the
parser learned two model habits (a list in `object`, a repeated key). Cost is real, so proposals are cached by
model, spec, and session (`bench/cache/proposals`); the four runs below re-used the same 980 replies.

| Run                | Change                                                 | recall@5  | recall@10 | paired vs `m3-lexical` (@5) |
|--------------------|--------------------------------------------------------|-----------|-----------|-----------------------------|
| `m3-facts-pilot`   | facts and the owner's entity probe as ranking channels | 0.850     | 0.900     | −0.100 [−0.300, 0.050]      |
| `m3-facts-pilot-b` | the owner alone is no longer a ranking key             | 0.900     | 1.000     | −0.050 [−0.200, 0.100]      |
| `m3-facts-pilot-c` | lenient proposal parsing (5 more sessions with facts)  | 0.900     | 1.000     | −0.050 [−0.200, 0.100]      |
| `m3-facts-pilot-d` | a key must share two query terms; events are keys      | **1.000** | **1.000** | **+0.050 [0.000, 0.150]**   |

Three design errors surfaced, each visible in one `bench show` transcript:

1. **The owner is not a key.** "What did I…" spots the owner; without a predicate cue the entity probe
   returned every fact about them, and the structured channel (weight 2) pushed the oldest observations to
   the top. The verdict still reports the state; the owner's facts no longer rank.
2. **One shared word is not a key match.** Renderings are short, so BM25 rewarded "the user owns local artist"
   for "local community theater" above the exact observation. A rendering now has to carry two distinct query
   terms when the query has three or more.
3. **Events are keys.** "What play did I attend" was extracted as `attended(user, The Glass Menagerie)`, an
   event with no fact beside it, and the key channel searched facts only. Event renderings now have their own
   FTS5 index (schema 5) and rank like facts.

Twenty questions of one type prove the pipeline and catch gross errors; they say nothing yet about the
multi-session and temporal slices where the literature predicts the gain. The next step is a stratified subset.

Cost, measured from the cached replies rather than guessed: 980 sessions were 3.35M input and 0.47M output
tokens (Haiku averages 475 output tokens per session), about $5.70 at Haiku 4.5 list prices (September
2026; every dollar figure in this document is at those prices). The full 500 questions
(19,195 unique sessions) extrapolate to roughly $110, not the $150 estimated before the pilot.

### Branch `simplified-tools` (2026-09-16)

The first 20 questions with the local 9B, engine from the `simplified-tools` branch: the seven-tool surface,
predicates, event types, and entity types registered from their first use, a partial definition counting as a
definition, semantic matching of bare predicate names through the store's embedder, and the 2.2 upgrade path.
Paired against `m3-facts-local-qwen9b-c` (same questions, same model): recall@5 diff 0.000 [0.000, 0.000],
recall@10 diff 0.000, n=20. The proposal cache did not hit (the resource text in the installed jar differs from the
cached key by line endings, most likely), so the proposals were regenerated by the model at temperature 0 and the
run took an hour; 277 facts per question against 259. One question failed on the first pass: the regenerated
proposal held a fact with no predicate beside a purchase, which the check for facts an event opens did not expect
(a latent fault on `main` as well). Fixed, with a regression test; the question was added on the second pass from
the cache.

### Local proposers

The local-model runs, their setup, and everything that went wrong are collected in
[LOCAL-MODELS.md](LOCAL-MODELS.md); the sections below are the per-run records.

#### Qwen 3.5 9B (`m3-facts-local-qwen9b-c` vs `m3-facts-pilot-f`, same 20 questions)

`lmstudio:qwen/qwen3.5-9b` (Q4_K_M, 6.55 GB, LM Studio, four parallel slots of 16k context on an RTX 5080,
`reasoning_effort: none`), against the cached Haiku proposals on identical questions, engine, and spec.

|                                        | Haiku 4.5                   | Qwen 3.5 9B local         |
|----------------------------------------|-----------------------------|---------------------------|
| recall@5                               | 1.000                       | 0.950 [0.850, 1.000]      |
| recall@10                              | 1.000                       | 1.000                     |
| paired difference @5 (local − Haiku)   |                             | −0.050 [−0.150, 0.000]    |
| paired difference @5 (local − lexical) |                             | 0.000 [0.000, 0.000]      |
| facts per question                     | 231                         | 259                       |
| unparseable replies                    | 2 of 980                    | 22 of 980 before, 0 after |
| ingest per question, p50               | 39 s (8 workers, first run) | 175 s (4 slots, first run) |
| cost for 20 questions                  | about $5.70                 | none                      |

Two findings from the local run:

1. **Output limits truncate local replies.** 21 of the 22 failures were JSON cut off at `max_tokens`; the 9B
   model writes pretty-printed JSON and lists everything. The parser now keeps every complete element of a
   truncated reply and drops the partial one (`Proposal.repairTruncated`), local endpoints get 6,144 output
   tokens, and `--refresh-failed` re-asked the two that were still broken. Facts per question rose from 237 to
   276 on that run (259 under the final parser, `m3-facts-local-qwen9b-c`, the figure in the table) with no
   change in recall: the truncated tails were not where the answers were.
2. **What separates the models is reading, not volume.** The single question the local model loses,
   "what was my previous occupation?", is one where Haiku wrote an `ended` fact for the earlier job and the 9B
   model wrote only the current one. Everything else is identical at recall@10.

A first version of this experiment with `qwen/qwen3.5-35b-a3b` (22 GB, spilling from a 16 GB card) produced
one proposal per 50 seconds per slot and was abandoned; a model that fits in GPU memory is the first
requirement, before any question of quality.

#### Local 27B (`m3-facts-local-qwen27b-d`, same 20 questions; generated 2026-09-07/08, recorded from the 2026-09-09 rerun from cache)

`mradermacher/Qwen-3.5-27B-Derestricted` at Q3_K_S (13.0 GB, the only 27B on disk that fits), two slots of
16k, thinking off. 12.4 hours for 980 sessions: the model writes 1,076 output tokens per session against
Haiku's 475, and the card decodes a dense 27B at about 28 tokens per second in aggregate whatever the slot
count. Recall@5 0.950, recall@10 1.000, identical per question to the 9B and to lexical, one question behind
Haiku. 216 of 980 replies were unparseable, almost all the same defect: the object key `"ref":` omitted
(`{"e_gallipoli", "name": "Gallipoli campaign", ...}`), which no repair short of guessing can fix. Facts per
question fell to 178. Whether that is the Q3 quantization or the abliteration is not separable on this card;
the official Q4_K_M release is 17.5 GB and does not fit.

The run also exposed three engine defects on sloppy proposals, each of which had aborted a whole question:
an entity without a name, the same ambiguous name declared twice, and an event naming a blank participant.
All three are warnings now, and a ref-shaped name (`e7`) that no entity declares is a dangling reference
rather than a new entity. A question that errors also counts toward `--limit` now, so a subset is the same
questions on every run; the first 27B attempt had silently substituted three later questions.

#### Gemma 3 12B and Qwen 3.5 9B at Q8 (`m3-facts-local-gemma12b-c`, `m3-facts-local-qwen9b-q8-b`; generated 2026-09-08, recorded from the 2026-09-09 reruns from cache)

Both official releases, both fit the card, both 0.950 / 1.000 with the same per-question ranks as the 9B at
Q4. Gemma (Q4_K_M, two slots of 12k after four slots spilled and two 16k slots crashed) produced 131 facts
per question with 5 unparseable replies; the 9B at Q8_0 (two slots of 16k, 86 minutes) produced 214 with 3.
The 9B at Q4 had produced 259 with none. Neither more parameters, a different family, nor a higher
quantization moved the one question that separates the local models from Haiku. Every local row and the
Haiku row were rerun from cache under the same parser before being recorded (LOCAL-MODELS.md).

#### gpt-oss 20B, abandoned (2026-09-08)

`DavidAU/openai-gpt-oss-20b-heretic` at Q5_1 (15.7 GB) loaded with two slots and answered a short probe in
2.9 s. On real sessions it is unusable for this task, for two reasons that hold for the gpt-oss family rather
than this fine-tune:

1. **Reasoning cannot be turned off.** `reasoning_effort: none` is accepted but the model still thinks: 535
   reasoning tokens for 184 tokens of answer on a medium session, and on the longest session of the first
   question it spent all 6,144 output tokens reasoning and returned no answer (`finish_reason: length`,
   164 s). The extraction spec's answer is a few hundred tokens; the deliberation is five to thirty times that.
2. **Two concurrent requests break its output format.** With two slots busy, both requests fail after four
   minutes with LM Studio's "output does not match the expected peg-native format" (the Harmony channel
   syntax), which reached the harness as a 400. The run had produced 24 replies in 76 minutes before it was
   stopped; the projection was two days.

Single-slot, capped at 6,144 tokens, it would take on the order of ten hours for 980 sessions and silently
lose the longest ones. A model that must reason before it writes is the wrong shape for a fixed-format
extraction task on a 16 GB card; the official `openai/gpt-oss-20b` (MXFP4, 12 GB) shares the first problem
and was not tried.

An earlier 27B result was discarded: both local models had been loaded under the LM Studio identifier
`proposer`, and the cache key saw only that alias, so the "27B" run had reused the 9B's replies. The model id
now carries publisher, architecture, and quantization from `/api/v0/models`.

### Stratified 120 (`m3-facts-haiku-120-e`, `m3-facts-local-qwen9b-120-b`, 2026-09-08 to 09-09)

Twenty questions per type, Haiku and the local Qwen 3.5 9B (Q4) on identical questions, paired against
`m3-lexical`. The first pass of both came out below lexical (Haiku −0.017 / −0.033, 9B −0.033 / −0.050 in
full recall at @5 / @10); the cause was the structured channel ranking and anchoring the owner's facts under
non-functional cues such as `uses` and `prefers`, fixed from the cached proposals. On the fixed engine:

| paired difference, full recall | @5 | @10 |
|---|---|---|
| Haiku − lexical | **+0.025 [0.000, 0.058]** | −0.008 [−0.025, 0.000] |
| 9B − lexical | 0.000 [0.000, 0.000] | −0.008 [−0.025, 0.000] |
| 9B − Haiku | −0.025 [−0.058, 0.000] | 0.000 [−0.025, 0.025] |

Mean recall@5 over the 120: lexical 0.898, Haiku 0.907, 9B 0.895; multi-session 0.657 / 0.678 / 0.640.
Haiku cost about $25; the 9B took 319 minutes. Note that `compare` reports full recall (all evidence sessions
in the top k) while `metrics` reports mean recall; they coincide only for single-session types. Per-type
table, per-question changes, and the reading of the result in LOCAL-MODELS.md, "The stratified 120".

### The full 500 with the local 9B (`m3-facts-local-qwen9b-500`, 2026-09-09): the M1 gate

Paired against `m3-lexical`, n=470: full recall@5 **+0.026 [+0.009, +0.043]**, full recall@10 **+0.030
[+0.015, +0.047]**; mean recall@5 0.862 → 0.885, @10 0.891 → 0.917. Temporal-reasoning 0.666 → 0.758 at @5
with 20 questions better and 1 worse; every other type within one question of lexical. 18.1 hours on the
same card, no API cost, 35 of 23,867 proposals unparseable. On the hard 106 the 9B is level with Haiku
(−0.028 [−0.104, +0.047]). Per-type table in LOCAL-MODELS.md, "The full 500".

### The hard 106 with Haiku (`m3-facts-haiku-hard`, 2026-09-09)

The 106 answerable questions where `m3-lexical` misses at least one evidence session in its top five (63
temporal, 36 multi-session, 7 others), selected on the lexical result only. Haiku's proposals lift full recall
by **+0.160 [+0.094, +0.236]** at @5 and **+0.142 [+0.066, +0.217]** at @10, n=106, 26 questions better and 2
worse at @5; temporal-reasoning mean recall@5 0.328 → 0.540. About $20. Read as the ceiling of what a
proposer adds on the questions where lexical fails, not as an average; the unbiased average is the
stratified 120. Details and mechanism in LOCAL-MODELS.md, "The hard 106".

### Reading the M0 baseline (written 2026-09-07)

The reading of `m0-session` before any proposer ran; the sections above record what happened next. Facts as
keys were tried first, and the slice they moved was temporal reasoning (0.666 → 0.758 on the full 500), not
multi-session, which stayed where it was.

- The raw lexical channel is a strong baseline, as the literature survey predicted: the LongMemEval paper's
  session-level dense retriever scored 0.706 at recall@5 with the same key-equals-value setup.
- The single-session slices are saturated at 1.000; nothing structured can improve them and anything that lowers
  them is a regression.
- **Temporal reasoning is the slice to move** (0.661). Observation text carries no dates; the session date lives
  only in `observed_at`. The literature's time-aware query expansion (+11.3 points recall@5 on this slice with a
  strong extractor) and prefixing the observation date into the indexed text were the first two things to try, as
  paired ablations, before any fact extraction.
- Multi-session (0.844) was where facts-as-keys were predicted to help (M1 gate).
- Context averages 3,800 of a 4,000-token budget, so the reader will see almost everything the budget allows;
  precision at the top of the list matters more than recall once a reader is attached.

## Not yet run

- Reader-judged accuracy (needs a fixed open reader through an OpenAI-compatible endpoint; `bench run --reader`,
  then `bench judge`). Per-type accuracy, abstention precision/recall, and the RETRIEVAL_MISS / ANSWER_ERROR split
  are produced by `bench metrics` once `judged.jsonl` exists.
- LongMemEval_m, MemoryAgentBench selective forgetting, BEAM-100K.

## The abstention slice (2026-09-11)

LongMemEval keeps 30 questions whose reference answer is "you never told me
that": the store holds related things (a cat, not the hamster asked about;
Harajuku, not Shinjuku). They are excluded from retrieval recall, so the
gate here is the structured verdict: it must never claim an answer.
`bench run --abstention true` selects them. On the local Qwen 3.5 9B
(`m4-abstention-local-qwen9b-c`):

| Verdict | Count |
|---|---|
| entity, no predicate cue | 14 |
| events | 10 |
| MISS | 6 |
| matched | 0 |
| KNOWN FALSE | 0 |

The first run had five `matched`: a fact under the right predicate about a
different named thing (Harajuku for Shinjuku, a Senior Software Engineer for
a Software Engineer Manager, headphones for an iPad). The named-thing rule
(EVALUATION.md F14) turns those into a MISS with the fact as a near-miss.

## The semantic channel, first ablation (2026-09-11)

Stratified 120, same engine, proposals from the cache, the in-process
granite-embedding-107m-multilingual embedder on and off
(`m4-noembed-local-qwen9b-120` vs `m4-embed2-local-qwen9b-120`); the absolute
rows are mean session recall (`bench metrics`), the difference is paired full
recall (`bench compare`):

| | @5 | @10 |
|---|---|---|
| without (mean) | 0.905 | 0.951 |
| with (mean) | 0.887 | 0.947 |
| difference (full, paired) | −0.008 [−0.058, 0.042] | +0.017 [−0.033, 0.075] |

Six wins against five losses at 5, seven against five at 10, counting every
question whose recall changed. The wins are
multi-session aggregation questions; the losses are single-session exact
questions where sessions similar in meaning displaced the one exact match. A
benchmark session is one observation of thousands of tokens and the embedder
reads its first 512, so an observation vector describes the opening of a
session, while fact vectors are short and precise.

Ten variants followed the same day, every one paired against the same
no-channel run on the same questions and proposals (`m4-embed-*-120`). The
differences are full recall; the wins and losses count every question whose
recall changed, so a partial gain counts as a win there and not in the
difference:

| Variant | @5 | @10 | wins / losses @5 |
|---|---|---|---|
| both kinds, weight 1 | −0.008 [−0.058, 0.042] | +0.017 [−0.033, 0.075] | 6 / 5 |
| both, weight 0.5 | +0.008 [−0.033, 0.058] | +0.008 [−0.042, 0.058] | |
| both, cap 10 sessions | −0.017 [−0.075, 0.042] | −0.008 [−0.067, 0.050] | |
| both, cap 5 sessions | −0.042 [−0.108, 0.017] | +0.017 [−0.025, 0.058] | |
| both, cosine ≥ 0.6 | −0.033 [−0.092, 0.025] | +0.008 [−0.042, 0.067] | |
| facts only | −0.150 [−0.217, −0.083] | −0.183 [−0.258, −0.117] | 3 / 20 |
| facts only, cap 10 | −0.225 [−0.300, −0.150] | −0.233 [−0.317, −0.150] | |
| observations only | −0.008 [−0.075, 0.058] | +0.008 [−0.050, 0.067] | |
| both, weight 0.1 | 0.000 [−0.042, 0.042] | +0.017 [0.000, 0.042] | 5 / 3 |
| **both, weight 0.05** | **+0.008 [0.000, 0.025]** | 0.000 | **4 / 0** |

Two lessons. With reciprocal-rank fusion a session found only by meaning at
rank one outranks a session found only by words at rank two, so at equal
weight the channel displaces exact hits; caps and floors make that worse by
withholding the semantic score from the exact session while boosting the
nearest few. At a weight of 0.05 the channel can only add sessions the exact
channels missed, never displace one they found: four wins and no losses at
5, one win and no losses at 10. That is the default. The facts-only rows are
the second lesson: a fact restated in several sessions is one row belonging
to the first session that stated it, so a vector hit on a fact points at the
first occurrence and boosts the wrong session. The key channel carries the
same bias inside the baseline. A link table recording every observation that
corroborated a fact fixes both, and is the provenance a user asks for
("which conversations said this"). Chunked observation vectors, several per
long observation, were the other lever, and they changed the channel's
character.

## The semantic channel with chunked vectors (2026-09-11, second pass)

Schema 16: one vector per chunk of about 300 tokens, an item scoring by its
best chunk, and every observation behind a fact reachable. New baseline
without the channel on that engine (`m4-links-noembed-120`): 0.907 at 5,
0.951 at 10, unchanged. Paired against it, same questions and proposals:

| Chunked vectors, weight | @5 | @10 | wins / losses @5 | @10 |
|---|---|---|---|---|
| 0.05 | 0.000 [0.000, 0.000] | +0.008 [0.000, 0.025] | 1 / 0 | 1 / 0 |
| 0.25 | +0.017 [0.000, 0.042] | +0.025 [0.000, 0.058] | 4 / 0 | 5 / 0 |
| **1.0** | **+0.050 [0.017, 0.092]** | **+0.050 [0.017, 0.092]** | **9 / 1** | **6 / 0** |

With whole-session vectors, full weight lost ground; with chunk vectors it
is a significant gain at both cutoffs, the first the channel has shown.
Seven of the nine wins at 5 are multi-session aggregation questions, the
rest temporal ordering and one preference question lexical never found;
the one loss is a temporal question with one of four sessions displaced.
The default weight is 1.0. Absolute session recall with the channel at full
weight is 0.938 [0.899, 0.971] at 5 and 0.968 [0.935, 0.992] at 10, the
highest of any run on this set at that date (the bake-off runs below, on
proposals regenerated under a later spec, go higher).


## The embedder bake-off (2026-09-11): P4

Fourteen candidates (twelve in the results file, two from a separate pass, marked) through the server's own
tokenizer and runtime (`bench bakeoff`), on
`bench/bakeoff/personal-notes.json`: two hundred
personal-note texts as a store holds them (first-person observations and third-person fact renderings), each
with a paraphrased question in English, German, and Swedish; sixty-four of the texts sit in groups that share
a name and differ in one thing. A question is scored against all two hundred texts by cosine. The table
gives recall at 1 and 3 and the mean reciprocal rank averaged over the three languages, the share of group
questions that rank their own text above the others in the group, and per language recall at 1. One
question is 0.005 of a language and 0.0017 of the average; a difference of 0.02 in the average is twelve
questions. Milliseconds are the median per sentence on the CPU (four threads), fp32 unless the name says
int8. Sizes are MiB of `model.onnx` (plus external weights where the export keeps them beside the graph).
The results file is `bench/results/bakeoff/personal-notes.json`. The
results predate a text-only edit of the sample file on 2026-09-12 (names in the examples); the numbers have
not been re-measured on the edited texts.

**Who wrote the set.** Texts and English questions: the assistant. German and Swedish for the first sixty
items: the assistant. German and Swedish for the other 140: the local Qwen3.5 9B, translating the English
question, after a check on the first sixty where its translations scored within 0.03 of the assistant's for
every model with the same ranking. Fifty-eight of its Swedish questions and two German ones were corrected
by hand where it produced a non-word, a literal mistranslation, or "Vil" for "Vilken". The same model asked
to write the questions itself, rather than translate, wrote easier ones (more of the note's words survived;
every model scored 0.05 to 0.13 higher on them, same ranking), so translation of an authored paraphrase is
the division of labour: the paraphrasing stays with the author, the languages with the model.

| Model | Dims | MiB | ms | R@1 | R@3 | MRR | Group | en | de | sv |
|---|---|---|---|---|---|---|---|---|---|---|
| granite-embedding-107m-multilingual (default until 2026-09-12) | 384 | 408 | 2.9 | 0.547 | 0.712 | 0.650 | 0.79 | 0.640 | 0.640 | 0.360 |
| granite-embedding-278m-multilingual | 768 | 1060 | 16.7 | 0.622 | 0.800 | 0.723 | 0.87 | 0.700 | 0.665 | 0.500 |
| granite-embedding-97m-multilingual-r2 | 384 | 371 | 6.9 | 0.537 | 0.753 | 0.662 | 0.79 | 0.560 | 0.560 | 0.490 |
| granite-embedding-97m-multilingual-r2-int8 † | 384 | 93 | 4.4 | 0.498 | 0.715 | 0.627 | 0.77 | 0.550 | 0.505 | 0.440 |
| granite-embedding-311m-multilingual-r2 | 768 | 1189 | 22.8 | 0.670 | **0.850** | 0.771 | 0.86 | 0.715 | 0.655 | 0.640 |
| granite-embedding-311m-multilingual-r2-int8 † | 768 | 298 | 12.9 | 0.662 | 0.840 | 0.762 | 0.84 | 0.690 | 0.645 | 0.650 |
| multilingual-e5-small | 384 | 448 | 5.6 | 0.495 | 0.697 | 0.615 | 0.80 | 0.560 | 0.490 | 0.435 |
| multilingual-e5-small-int8 | 384 | 112 | 3.1 | 0.477 | 0.667 | 0.595 | 0.77 | 0.510 | 0.480 | 0.440 |
| multilingual-e5-base | 768 | 1058 | 16.5 | 0.645 | 0.808 | 0.740 | 0.88 | 0.635 | 0.635 | 0.665 |
| paraphrase-multilingual-MiniLM-L12-v2 | 384 | 448 | 5.3 | 0.543 | 0.733 | 0.657 | 0.84 | 0.560 | 0.530 | 0.540 |
| paraphrase-multilingual-MiniLM-L12-v2-int8 | 384 | 112 | 2.9 | 0.525 | 0.725 | 0.645 | 0.81 | 0.525 | 0.510 | 0.540 |
| snowflake-arctic-embed-m-v2.0 | 768 | 1169 | 20.5 | 0.670 | 0.815 | 0.756 | 0.87 | **0.775** | 0.730 | 0.505 |
| snowflake-arctic-embed-m-v2.0-int8 | 768 | 296 | 8.4 | 0.642 | 0.800 | 0.736 | 0.85 | 0.755 | **0.740** | 0.430 |
| bge-m3 | 1024 | 2162 | 48.7 | **0.700** | 0.848 | **0.785** | **0.91** | 0.690 | 0.695 | **0.715** |

† measured in a separate pass whose results file was not kept.

Not run: Qwen3-Embedding-0.6B (a 2.4 GB fp32 export with last-token pooling and a third tokenizer family,
too heavy for an in-process default), EmbeddingGemma (gated and Gemma-licensed: a user-supplied option, not
the bundled one), potion (a static table, not a transformer; the 107m already runs in 3 ms so the fast tier
has no problem to solve). Every tokenizer was checked against the Hugging Face library's ids on twelve
sentences before the run (`TokenizerReferenceTest`): the Unigram implementation matches the seven XLM-R
vocabularies exactly, the new byte-level BPE matches the two ModernBERT r2 tokenizers exactly. A first pass
on the sixty assistant-written items alone gave the same ordering with scores 0.2 higher (fewer candidates,
gentler paraphrases); the set was grown because the top three sat within one question of each other there.

What the table says:

- **The current model is the weakest on Swedish** by a wide margin: 0.360, against 0.640 for the 311m r2
  and 0.715 for bge-m3. English and German are fine. A store in Swedish, or a Swedish question to an
  English store, is what the 107m cannot do.
- **Two models lead**: bge-m3 (0.700) and the granite 311m r2 (0.670, the best at 3), eighteen questions
  apart. bge-m3 costs 2.2 GB and 49 ms a sentence. The 311m r2 is the most even across languages and its
  int8 build loses nothing measurable (0.662) at 298 MiB (313 MB) and 13 ms: a smaller download than the
  107m it replaces.
- **arctic-m-v2 equals the 311m r2 on the average and leads on English and German**, then falls to 0.505 on
  Swedish; e5-base is the reverse, second on Swedish and last of the large models on English. The 278m sits
  0.048 behind the 311m r2, nearly all of it Swedish.
- **The 97m r2 is the 107m's equal on the average** with much better Swedish (0.49 against 0.36) and worse
  English; its int8 loses 0.04.
- **int8 costs little: 0.008 on the 311m r2, 0.018 on MiniLM and on e5-small, and 0.028 on arctic (all of
  it Swedish).**
- **The misses every model shares are the store's, not the model's**: a first-person question against a
  third-person rendering ("which company employs me?" against "Alex works at Hooli"). That led to the
  owner alias below.

### The owner alias for vectors (2026-09-11)

Four ways of telling the vector channel who "I" is, measured on the same set with `OwnerAliasSampleTest`
(recall at 1 averaged over the three languages; twenty-six of the two hundred texts name the owner):

| Model | plain | question rewritten, better of two | question vectors averaged | rendering in the first person |
|---|---|---|---|---|
| granite 107m | 0.547 | 0.378 | 0.470 | **0.572** |
| granite 278m | 0.622 | 0.415 | 0.507 | **0.643** |
| granite 311m r2 | 0.670 | 0.478 | 0.623 | **0.702** |
| granite 97m r2 | 0.537 | 0.387 | 0.492 | **0.587** |
| e5-base | 0.645 | 0.625 | 0.620 | **0.675** |
| e5-small | 0.495 | 0.403 | 0.430 | **0.508** |
| MiniLM-L12 | **0.543** | 0.417 | 0.512 | 0.518 |
| arctic-m-v2 | 0.670 | 0.518 | 0.608 | **0.688** |
| bge-m3 | **0.700** | 0.527 | 0.653 | 0.692 |

Rewriting the question with the owner's name and taking the better similarity per item, the obvious move,
lowers recall for every model, by 0.02 to 0.19: the name is a strong token, so every fact rendered with it
draws close to every first-person question, and the wrong Alex fact wins. Averaging the two question
vectors is worse than plain everywhere. The rendering side wins for seven of nine and costs the other two
under 0.03: a fact that names the owner is embedded a second time as the owner would say it ("I work at
Hooli (since 2018)", "Anna is my sister"), a vector with no name in it, and the item takes its better
vector as a chunked observation does. The question stays as asked. That is what the engine does now (vector
scheme 2; an existing store drops its fact vectors once and the backfill remakes them). On the sixty-item
first pass the gains were larger (+0.04 to +0.07) and no model lost. Its effect on LongMemEval is measured
in the next section.

### The gate on LongMemEval (2026-09-12)

The candidates that survived the sample set, on the stratified 120 with the local 9B's proposals (regenerated
under the current extraction spec, then cached) and the engine as it ships (owner alias, upcoming channel,
the engine of commit `53f5500`). One no-channel baseline, then each model as the semantic channel at weight 1;
every run the full 120 (one question was lost to an excerpt bug, fixed, and filled in). Paired differences
with 95 % bootstrap intervals. Ingest is the median per bench question, some forty sessions embedded; the
harness ingests one question at a time, and the machine load differed between runs (below).

| Run | recall@5 | recall@10 | vs no channel @5 | vs no channel @10 | vs 107m @5 | vs 107m @10 | ingest p50 |
|---|---|---|---|---|---|---|---|
| no channel | 0.902 | 0.944 | | | | | |
| granite 107m | 0.944 | 0.972 | +0.050 [0.017, 0.092] | +0.058 [0.025, 0.100] | | | 12 s |
| granite 107m, no owner alias | 0.939 | 0.978 | +0.058 [0.017, 0.100] | +0.075 [0.033, 0.125] | +0.008 [−0.017, 0.033] | +0.017 [−0.008, 0.050] | 11 s |
| granite 278m | 0.936 | 0.972 | +0.050 [0.008, 0.101] | +0.050 [0.008, 0.101] | 0.000 [−0.025, 0.025] | −0.008 [−0.025, 0.000] | 57 s |
| granite 97m r2 | 0.941 | 0.974 | +0.042 [0.008, 0.084] | +0.067 [0.025, 0.118] | −0.008 [−0.033, 0.017] | +0.008 [−0.017, 0.033] | 25 s |
| granite 311m r2 | 0.919 | 0.983 | +0.025 [−0.017, 0.067] | +0.075 [0.033, 0.125] | −0.025 [−0.058, 0.000] | +0.017 [0.000, 0.042] | 83 s |
| granite 311m r2 int8 | 0.934 | 0.988 | +0.042 [0.000, 0.083] | +0.083 [0.042, 0.133] | −0.008 [−0.042, 0.025] | +0.025 [0.000, 0.058] | 208 s |

What it says:

- **Every granite model adds the same four to eight points on English**, and none beats another: the widest
  paired difference against the 107m is the 311m r2's −0.025 at 5 (interval touching zero) against +0.017 at
  10. The channel is one of four fused by rank; on an English benchmark the models' differences wash out.
- **int8 is the fp32's equal**: +0.017 [−0.017, 0.050] at 5, +0.008 [0.000, 0.025] at 10 against its own
  full-precision run. The sample set said the same (0.662 against 0.670).
- **Every cell is at n=120.** Two "vs no channel" cells were first computed at n=119, before the excerpt-bug
  question was filled in, and were rerun on 2026-09-12 (107m at 10: +0.058 [0.025, 0.100]; 311m r2 at 10:
  +0.075 [0.033, 0.125]).
- **The owner alias does nothing measurable here**: +0.008 and +0.017 in favour of the run without it, both
  intervals through zero. LongMemEval's owner is "the user" and its questions rarely hinge on an owner fact
  rendered in the third person; the alias's gain is on the personal-note set (+0.02 to +0.05 for the granite
  models), which is the store's actual use. It stays on.
- **The ingest column is not like for like.** The fp32 runs (107m, 278m, 97m r2, 311m r2, and the baseline)
  were started within twelve minutes of each other and ran as five concurrent JVMs on one machine; the int8
  run ran on its own later, and the bench's `--workers` setting (2 for the fp32 runs, 4 for the int8) only
  sizes the proposal-fetch pool, which served everything from the cache. So the 208 s a question for the int8
  against the fp32's 83 s says nothing about the builds; the serial per-sentence measurement on the sample
  set does, and there the int8 is the faster one (13 ms a sentence against 23 ms). A remember with a few
  chunks costs tens of milliseconds either way; a re-embedding of a store of a few hundred rows, seconds.

**Decision**: the default becomes granite-embedding-311m-multilingual-r2, the 8-bit build on x86 (313 MB) and
the full-precision one on ARM (1.25 GB), for the multilingual result: Swedish recall at 1 on the sample set
0.640 against the 107m's 0.360, German and English level, no loss on LongMemEval, a smaller download than
before. bge-m3 was the sample set's best by eighteen questions at 2.2 GB and 49 ms a sentence, and was not
worth that for a personal store. A store made with the 107m re-embeds itself on the first start with the new
model (the old model's vectors are dropped, the backfill remakes them).

## The usage bench (2026-09-20)

A second bench, `bench usage`, asks not whether the store holds the answer but whether an assistant given the
server's tool descriptions and the memory protocol gets it in and out; the protocol and the numbers it reports
are in [DEVELOPMENT.md](DEVELOPMENT.md#the-usage-bench). The script has twelve scenarios and forty steps under
`bench/usage/scenarios.json`.

Smoke run, local Qwen 3.5 9B at Q8 as assistant and judge, first two scenarios (`usage-qwen-smoke`): every
statement was remembered with a reading and every question was preceded by a recall, and no answer was right.
The model wrote `parent_of` the wrong way round (`self` as subject, the father as object), read the rendering
that said so, and then spent its call budget on `correct` calls it could not form. That is a finding about the
9B as an assistant, not about the store, and it is what the bench is for.

First full run, Sonnet 5 as assistant and judge (`usage-sonnet`, 2026-09-21): 18 of 20 questions right, all three
abstentions right, every scenario 100% but one. The one is `family-group`, 0 of 2, and that was the harness: the
model's `remember` calls came back one closing brace short, the bench read them as replies, and the family facts
were never stored. The parser now closes unbalanced brackets (counted as a slip), so the run is to be repeated.
Tool use: 17 of 20 statements led to a `remember` with a reading (the three that did not were the two lost calls
and a step the model merged into the next), 15 of 20 questions had a `recall` before the answer (the rest were
answered from the conversation just had, which is fair), 1.7 tool calls a step, 2 answers on a MISS (both in the
broken scenario). The repeat with the fixed parser (`usage-sonnet-2`): 20 of 20, every statement written down (nineteen
by `remember` with a reading, the correction by `correct`, which the metric first counted against it), 15 of 20 questions recalled before the
answer (the other five were answered from the conversation just had), 1.5 tool calls a step, no answer on a MISS.
The 12 slips left are the model answering in plain prose instead of the JSON wrapper, which the harness reads
and counts. Fable 5.1 as assistant and judge (`usage-fable`, 2026-09-21): 20 of 20, a `recall` before every one of the 20
questions, no protocol slip at all, 1.4 tool calls a step, no answer on a MISS. Nineteen of twenty statements
were written down; the twentieth ("the benchmark data lives under bench/data") it declined on purpose, calling it
a repository convention rather than a memory, which is a defensible reading. Things seen only in this run: it
answered the boat entity question the store raised (`resolve` in the same `remember`), and after registering
`breed` and `prefers` from use it went back with `correct` on `pred:breed` (domain, range) and `pred:prefers` (a
render template), which is the register-from-use loop working as meant. Cost: 115 requests, 116k input, 799k
cache reads, 12k output, about $2.50.

A third Sonnet run (`usage-sonnet-3`) tested a reworded harness rule and made things worse: 36 slips against
12, all of them prose replies without the JSON wrapper, a recall before only 12 of 20 answers, and 18 of 20
right (one answer was a bolded tool name followed by JSON, which the parser now reads; the other is the judge
refusing "No, you don't own a boat. You do own a Volvo V60." against "No, you have said you do not own a
boat"). The rule went back to the wording of the second run, with one example of each shape and the line
that even a one-line answer goes inside the reply object, and moved to the end of the prompt after the
tools. The same run also had the guide rewritten; from here the bench gives the assistant what a client gets,
the server's instructions and its tools, and the proposal guide only through `inspect('guide')`.

The fourth Sonnet run (`usage-sonnet-4`), with the rule moved and the protocol served the way a client gets it:
20 of 20, every statement written down, 15 slips (all prose replies, plus one tool name followed by a fenced
JSON block, which the parser now reads), a recall before 12 of 20 answers, 1.3 tool calls a step, about
$0.47. Sonnet never fetched the guide with `inspect('guide')` although the instructions say to before the
first proposal; its proposals were right anyway on this script. Fable recalled before all 20 answers; Sonnet
answers from the conversation when the statement was made a few turns earlier, which the script allows since
no scenario spans a session break. That break is the next scenario to write.

The fifth Sonnet run (`usage-sonnet-5`), with the protocol served as the initialize instructions in their
final, shorter form: 19 of 20, every statement written down, 11 slips, a recall before 12 of 20 answers,
about $0.59. The one miss was the judge, not the model: "I don't have any record of you having a brother or
any siblings" was graded wrong under LongMemEval's abstention rule, so the usage bench now judges abstentions
by its own rule (no record, not known, or not told counts; asserting an answer does not), and the retrieval
bench keeps LongMemEval's. Sonnet fetched the guide for the first time, at the pet scenario, where it met a
predicate the vocabulary lacked. One tool error: it wrote `ended: "2020"` on a fact, and the reply was a
Jackson type message; a proposal error now names the field in the caller's terms, `'facts[0].ended' takes a
boolean (got "2020")`, with the hint that the date goes in `valid_time.end`.

Fable 5.1 again, with the protocol as the server now serves it (`usage-fable-2`): 20 of 20, a recall before
every answer, every statement written down, 1.6 tool calls a step, and the guide fetched with
`inspect('guide')` in eleven of twelve scenarios before the first proposal, as the instructions ask. Its five
slips were its own tool-call syntax (`<invoke name="recall">`) with a made-up result written under it, which a
real client would have executed; the parser now reads the call and drops the invented result. The trace
found two server defects, both fixed with tests. A proposal defined the entity type `dog` with parent
`animal` and defined `animal` after it, so `dog` was refused for an unknown parent and registered from use
with no kind, which raised two questions that need not have been asked; types in one proposal now register
parents first whatever the order. Then, answering four questions in one call, the answer to the type's kind
settled the mismatch under it, the batch failed on that item as already answered after the earlier answers
had been applied, and each retry failed on the next one; an already-answered question in a batch is now
reported in its item, with the standing answer, and the rest of the batch goes through.

A third Fable run to confirm those fixes (`usage-fable-3`): 20 of 20, a recall before 19 of 20 answers, the
guide fetched in all twelve scenarios, one slip (its own tool syntax, now read as the call), about $4.60 with
1.9 tool calls a step, since it read more. The pet scenario went through with no conflict error and turned
up two smaller things, both fixed with a test. The predicate `has_pet` was defined with range `animal` and no
type of that name existed, so the mismatch question offered `kind:animal` and then refused it; a type a
predicate's domain or range names now exists from that definition on, and choosing an offered kind registers
it in any case. And the definition wrote `domain: ["person"]` as a list, which the schema took as a string; a
list is now read as the same statement.

The sixth Sonnet run (`usage-sonnet-6`), on the same binary as the third Fable run: 20 of 20, all three
abstentions right under the memory rule, every statement written down, ten slips of which nine were prose
replies, about $0.59. Twice it wrote a date into `ended` (`"2018-03"`, `"2020"`), got the hint, and
recovered at the cost of a call each time; a date there now reads as the fact's end date, since it means
nothing else, and a string that is no date still gets the hint. The guide was fetched in four scenarios.

The script grew to fifteen scenarios and fifty-seven steps on 2026-09-21 with three that span a session
break (`{"break": true}`: the server restarted on the same store, the conversation empty), so that the
questions after it measure memory use rather than context use. Sonnet 5 on it (`usage-sonnet-7`): 27 of 27,
all four abstentions right, every statement written down, about $0.69. After a break it recalled in the
answering turn for 5 of 7 questions and had recalled earlier in the same conversation for the other 2, so
every post-break answer came from the store; the correction stated in a conversation that never saw the
original fact was found by recall and made with `correct`, and "did I ever say I lived somewhere else" was
answered from the corrected fact's history. Thirteen slips, prose replies.

Fable 5.1 on the same fifteen scenarios (`usage-fable-4`): 27 of 27, a recall in the answering turn for
every one of the 27 questions and for all 7 after a break, every statement written down, the guide fetched in
all fifteen scenarios, two slips (its own tool syntax, read as the calls), no tool errors, 1.7 tool calls a
step, about $5.50. It opened each conversation with the briefing as the instructions ask, and for "did I
ever say I lived somewhere else" it asked with history included and answered from the corrected fact.

One server finding came out of the traces and is fixed: `as_of: "2015"` was refused, and a year or
a month is now taken as the end of that span. Cost, with the system prompt cached: 126 requests, 141k input tokens,
889k cache reads, 17k output, about $0.65.

