# Benchmarks

Two benches, both in the `bench` module. The retrieval bench asks whether the store holds the answer:
LongMemEval_s, 500 questions, session-level recall of the evidence sessions. The usage bench asks whether an
assistant given the server's tools and protocol gets facts in and out over a scripted conversation. Result
directories live under `bench/results/` and are not committed; every run is reproducible from its `config.json`.
Numbers in brackets are 95% percentile-bootstrap intervals (2,000 resamples, fixed seed). A change counts as a
gain only when the paired bootstrap of the per-question difference excludes zero. Oracle-variant and
self-judged numbers are never reported. The history of how these numbers were reached is in the git log of
this file.

State as of 2026-09-25, branch `api-review`.

## Protocol

- **Retrieval data**: `longmemeval_s_cleaned.json` (`xiaowu0162/longmemeval-cleaned`, MIT), stored outside the
  repository. 500 questions; 470 are answerable and carry evidence sessions, 30 are abstentions ("you never
  told me that") and are scored on the structured verdict instead.
- **Ingestion**: one fresh data home per question; sessions ingested in haystack order with `observed_at` set to
  the haystack date, one observation per session. With a proposer, each session's proposal is read into facts and
  events; proposals are cached under `bench/cache/proposals` by model and spec, so a rerun on new engine code
  pays no model time.
- **Recall**: `recall(question, as_of = question_date, max_tokens = 4000)`; recall@k is whether every evidence
  session is among the top k candidates. No reader and no judge on the retrieval numbers here.
- **Usage bench**: `bench usage` starts the runner jar over stdio per scenario, gives the assistant the server's
  initialize instructions and its tools, plays the script, and a judge model grades each answer against the
  expected one (LongMemEval's per-type judge prompts; abstentions by the memory rule: not known counts, asserting
  an answer does not). Statements are checked for a `remember` with a reading, questions for a `recall` before
  the answer, and a step that runs out of eight tool calls ends with no reply. The assistant holds the tools as
  its vendor's native tool calls (`--protocol tools`, the default) or writes one JSON object a turn
  (`--protocol text`, kept for older runs).
- **Comparison**: `bench compare --a A --b B`, a paired bootstrap over per-question labels.

## Retrieval: LongMemEval_s, session recall, n = 470

| Run | Date | Engine | recall@5 | recall@10 | vs lexical @5 | vs lexical @10 |
|---|---|---|---|---|---|---|
| `m0-session` | 2026-09-07 | FTS5 BM25 over observations, nothing else | 0.857 [0.830, 0.883] | 0.895 [0.871, 0.918] | | |
| `api-review-lexical` | 2026-09-24 | this branch, no proposer: structured probe, BM25 over renderings and observations, RRF | 0.862 [0.835, 0.888] | 0.891 [0.865, 0.915] | 0 | 0 |
| `v031-facts-local-qwen9b-500` | 2026-09-22 | 0.3.1 release, facts from the local Qwen 3.5 9B (Q4_K_M) | 0.889 [0.864, 0.911] | 0.919 [0.896, 0.939] | **+0.030 [+0.009, +0.053]** | **+0.034 [+0.015, +0.055]** |
| `api-review-facts-local-qwen9b-500` | 2026-09-25 | this branch, the same 9B proposals (23,717 of 23,867 from the cache) | 0.889 [0.865, 0.912] | 0.920 [0.898, 0.940] | **+0.032 [+0.011, +0.055]** | **+0.036 [+0.017, +0.057]** |

The lexical engine on this branch is the same as before, question for question (paired difference 0.000
against the 2026-09-07 lexical run). With facts, the branch is one question better than the 0.3.1 release at
each cutoff and none worse (paired +0.002 [0.000, +0.006] at both), so the branch's changes, which are about
what an assistant does with the store, neither gain nor lose at the retrieval level. The 150 proposals not in
the cache were regenerated; 34 failed on the model's context size, as in the earlier runs. Recall p50 was 9 ms
without facts and 31 ms with about 290 facts a question, both measured while other benches shared the machine.

Per type, recall@5, this branch:

| Type | n | lexical | facts (9B) |
|---|---|---|---|
| single-session-user | 64 | 0.984 | 0.984 |
| single-session-assistant | 56 | 1.000 | 0.982 |
| single-session-preference | 30 | 0.867 | 0.867 |
| multi-session | 121 | 0.863 | 0.867 |
| temporal-reasoning | 127 | 0.666 | 0.782 |
| knowledge-update | 72 | 0.986 | 0.965 |

Facts help where the question needs a date or a change (temporal reasoning, +0.116) and cost a question or
two where a rendering outranks the session that said it.

**Which proposer.** On the stratified 120 (20 per type), paired against lexical at @5: Haiku 4.5 +0.025
[0.000, +0.058], the local 9B 0.000 [0.000, 0.000]. On the 106 questions where lexical misses an evidence
session, Haiku +0.160 [+0.094, +0.236] and the 9B level with Haiku (−0.028 [−0.104, +0.047]). The 9B is the
regression proposer: free, and within a question of Haiku on the average. Full-500 runs with a paid proposer
have not been made.

**The semantic channel.** Default: `granite-embedding-311m-multilingual-r2` (8-bit on x86), chunk vectors over
observations and fact renderings, weight 1.0, the owner alias applied to first-person renderings. On the
stratified 120 (2026-09-12): without the channel 0.902 / 0.944, with it 0.934 / 0.988, paired +0.042 [0.000,
+0.083] at @5 and +0.083 [+0.042, +0.133] at @10. Not re-measured on this branch; the runs above are without
an embedder. The bake-off that chose the model is in `bench/results/bakeoff/personal-notes.json`.

**Abstentions.** On the 30 questions whose answer is "not told", the structured verdict on the 9B run was
entity-without-cue 14, events 10, MISS 6, and never `matched` or `KNOWN FALSE`: the store claims no answer it
does not have.

## The usage bench: 23 scenarios, 114 steps, 63 questions

Eleven questions are abstentions, eight scenarios span a session break (the server restarted on the same
store, the conversation empty), 43 steps are statements. The script is `bench/usage/scenarios.json`.

| Model | Protocol | Run | Right /63 | Abstentions /11 | Statements stored /43 | Cap hits | Parse failures | Calls a step | Cost |
|---|---|---|---|---|---|---|---|---|---|
| Opus 5.5 | text | `usage-opus55-s6b`, 2026-09-23 | **63** | 11 | 43 | 0 | 0 | 1.5 | about $3.90 with the judge |
| Haiku 4.5 | text | `usage-haiku45-s6b`, 2026-09-23 | 59 | 11 | 43 | 0 | 34 | 1.2 | about $0.65 plus the judge |
| Qwen 3.5 9B | text | `usage-qwen9b-s7`, 2026-09-24 | 52 (53) | 10 | 43 | 8 | 67 | 2.5 | none |
| Qwen 3.5 9B | native tools | `usage-qwen9b-s9`, 2026-09-24 | 61 | 11 | 37 | 0 | 0 | 0.8 | none |
| Qwen 3.5 9B | native tools, final tree | `usage-qwen9b-s10`, 2026-09-24 | 60 (61) | 10 | 38 | 0 | 0 | 0.7 | none |

The judge is Opus 5.5 for the Claude rows and the 9B itself for the Qwen rows, whose verdicts were checked by
hand; the number in parentheses is the hand count where it differs (a correct abstention or a partial answer
the small judge refused). Cost is at list price for assistant and judge together. The Claude rows are on the
text protocol and predate this branch's last changes (the direction line on stored facts, the note on a
repeated read, the fuller name on an entity answer); they are the reference until rerun natively.

What the remaining misses are:

- **Qwen, native tools**: the model says "I've recorded that" without calling `remember`, five or six statements
  a run; one of those is the SQLite decision, unknown after the break. The car decision, where the order is on
  record and the model answers "no final decision". The text protocol never lost a statement, since every turn
  had to be an explicit action; it lost eight steps to the call cap instead.
- **Qwen, text**: identical recall calls repeated to the cap, and its own JSON: braces dropped, a bare string
  for an argument object, a bracket missing after an array. Native tool calls remove all of it.
- **Haiku, text**: `parent_of` written with self as the parent (eight of nine times), which the direction line
  now turns around, and a careless reading of a recall block that was in front of it.
- **Opus**: none.

Cap hits are statement or question turns that ran out of eight tool calls. On the text protocol Qwen's numbers
swing by ten between runs of the same code; native runs have so far been within one.

## Not measured on this branch

- The Claude models on the native tool protocol and on the final tree (paid; the last measured state is above).
- The semantic channel on the branch (needs the embedder in the environment).

## Running them

```
mvn -B -q install -DskipTests
cd bench
mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/<name> --granularity session --reader none [--proposer lmstudio:proposer]"
mvn -q exec:java -Dexec.args="usage --script usage/scenarios.json --server ../server/target/mnemic-server-<rev>-runner.jar --assistant lmstudio:proposer --judge anthropic:claude-opus-5-5 --out results/<name>"
cd .. && mvn -q -pl bench exec:java -Dexec.args="compare --a bench/results/<a> --b bench/results/<b>"
```

`--resume true` on the usage bench keeps finished scenarios and replays a half-done one. A local model runs
through LM Studio with a model loaded under the alias `proposer`.
