# Local models as fact proposers

How to run a local model as the proposer in the benchmark, what was measured on an RTX 5080 (16 GB), and a
catalogue of the ways it failed. The proposer plays the assistant's part: it reads one conversation session
with the extraction spec and returns the structured proposal a real assistant would attach to `remember`.
Mnemic itself never calls a model; this is the harness.

The twenty-question comparisons were generated on 2026-09-07 and 2026-09-08 against the first 20 questions of
LongMemEval_s (980 sessions, all `single-session-user`) on engine M3 and recorded from reruns from cache on
2026-09-09 (the `-c`, `-d`, `-f`, and `-q8-b` directories); the stratified, hard-106, and full-500
runs followed on 2026-09-08 to 2026-09-09, and one rerun on 2026-09-11 under a longer extraction spec (noted
where it applies). Run names refer to `bench/results/`. Dollar figures are at September 2026 list prices.

## Setup

LM Studio is the path that has been exercised; any OpenAI-compatible server works through
`openai-compatible:<model>@http://host/v1`, and Ollama through `ollama:<model>`.

```text
lms server start --port 1234
lms get <catalog-name>[@quant] -y                 # e.g. qwen/qwen3.5-9b@q8_0; resumes on timeout when repeated
lms load <model> --context-length 65536 --parallel 4 --identifier proposer -y
cd bench
mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/<name> --proposer lmstudio:proposer --workers 4 --limit 20"
mvn -q exec:java -Dexec.args="compare --a results/m3-facts-pilot-d --b results/<name>"
```

`bench/scripts/local-proposers.sh "<model>=<run-name>[:<context>:<parallel>]" ...` does all of that for a
list of models, unattended.

What the provider (`OpenAiCompatibleProvider` in the server module) does for a local endpoint:

- sends `temperature 0`, `max_tokens 6144` (4096 for hosted OpenAI), and `reasoning_effort` from
  `--reasoning-effort` / `-Dmnemic.reasoning_effort` / `MNEMIC_REASONING_EFFORT`, default `none`;
- if the server rejects the reasoning field with a 400, resends without it and never sends it again;
- strips a `<think>…</think>` block from the content when the server does not separate reasoning;
- raises an error when the reply ended on the token limit with no content (all reasoning);
- for LM Studio, appends what `/api/v0/models` reports behind the identifier (publisher, architecture,
  quantization) to the model id, so `lmstudio:proposer` becomes
  `lmstudio:proposer[qwen/qwen35/Q4_K_M]` in `config.json` and in the cache key.

Replies are cached under `bench/cache/proposals` by model id, spec text, and session text. A run against an
already-seen model is free; a spec edit invalidates everything.

## Results on the same 20 questions

The community fine-tunes in the table (mradermacher's derestricted 27B, DavidAU's gpt-oss, and the HauhauCS
9B that entry 15 of the catalogue describes) were not chosen for the abliteration: the 27B was the only 27B
quantization on disk that fit the card, the gpt-oss fine-tune was tried before the official release, which
shares its defect (entry 5), and the 9B was what `lms load` picked by prefix. Official weights were used
wherever they fit.

| Proposer                                                  | recall@5  | recall@10 | paired vs Haiku (@5) | facts / question | unparseable replies       | wall time                                  | run                         |
|-----------------------------------------------------------|-----------|-----------|----------------------|------------------|---------------------------|--------------------------------------------|-----------------------------|
| Haiku 4.5, 8 workers, about $5.70                         | 1.000     | 1.000     |                      | 231              | 2 of 980                  | 13 min                                     | `m3-facts-pilot-f`          |
| Qwen 3.5 9B official, Q4_K_M, 4 slots                     | 0.950     | 1.000     | −0.05 [−0.15, 0.00]  | 259              | 0 after truncation repair | 59 min                                     | `m3-facts-local-qwen9b-c`   |
| Gemma 3 12B instruct, Q4_K_M, 2 slots of 12k              | 0.950     | 1.000     | −0.05 [−0.15, 0.00]  | 131              | 5 of 980                  | about 1.5 h at the slot counts that worked | `m3-facts-local-gemma12b-c` |
| Qwen 3.5 27B derestricted (mradermacher), Q3_K_S, 2 slots | 0.950     | 1.000     | −0.05 [−0.15, 0.00]  | 178              | 216 of 980                | 12.4 h                                     | `m3-facts-local-qwen27b-d`  |
| gpt-oss 20B heretic (DavidAU), Q5_1                       | abandoned |           |                      |                  |                           | projected 2 days                           |                             |
| Qwen 3.5 35B-A3B official, 22 GB                          | abandoned |           |                      |                  |                           | 50 s per session per slot                  |                             |
| Qwen 3.5 9B official, Q8_0, 2 slots                       | 0.950     | 1.000     | −0.05 [−0.15, 0.00]  | 214              | 3 of 980                  | 86 min                                     | `m3-facts-local-qwen9b-q8-b`  |
| no proposer (lexical only)                                | 0.950     | 1.000     |                      | 0                |                           | 1 min                                      | `m3-lexical`                |

The Q8 answers the quantization question for the 9B: identical recall per question to the Q4, fewer facts
(214 against 259, the Q8 is a little terser), three unparseable replies against none, and 86 minutes at two
slots against 59 at four. Quantization was not what separated the 9B from Haiku.

Every row above was rerun from its cached proposals on the engine as fixed by the stratified 120 (owner-cue
rule); none of the twenty-question numbers moved, which is what the per-question diffs predicted. Every row
was produced by the same parser: after the last parser change (a bare string for `derivation`)
each proposer was rerun from its cached replies, which takes about a minute per model, so the failure counts
are comparable. Gemma's count fell from 54 to 5 in that rerun and its facts per question rose from 121 to
131; nothing else moved.

The local models that finished all tie lexical and each other per question and sit one question behind
Haiku: "what was my previous occupation?", where Haiku wrote the earlier job as an `ended` fact
(`the user previously worked as marketing specialist (ended)`) and the local models wrote only the current
role. The key channel matched Haiku's rendering on "previous"; without it the observation text ranks that
session sixth. Twenty questions of one type separate gross failures from working pipelines; they do not
separate proposers of similar quality.

## The stratified 120

Twenty questions of each of the six LongMemEval types, 5,452 unique sessions, run on 2026-09-08 with Haiku
(`m3-facts-haiku-120`, 69 minutes at 8 workers, about $25 for the 4,470 sessions not already cached) and with
the 9B at Q4 (`m3-facts-local-qwen9b-120`, four slots, 319 minutes, the two runs side by side on the same
machine). Both were then rerun from their cached proposals on the engine as fixed below, so the numbers here
are the same ranking rules for everyone. The comparison is paired per question against the 500-question
lexical run, which covers the same 120.

Two metrics appear in this document and they are not the same thing. `bench metrics` reports **mean
recall@k**, the average over questions of the share of evidence sessions in the top k. `bench compare`
reports the paired difference in **full recall@k**, the share of questions whose evidence sessions were all
in the top k, with a bootstrap interval. For single-session types the two coincide; for multi-session and
temporal questions with several evidence sessions they do not, and the paired difference is the stricter one.

### What the first results said, and what they changed

Both proposers came out **below** lexical on the first pass. Haiku: full recall@5 −0.017 [−0.067, 0.033],
full recall@10 −0.033 [−0.067, −0.008]. The 9B: −0.033 [−0.067, −0.008] and −0.050 [−0.092, −0.017]. Two
causes, both in the structured channel and both visible in `bench show` transcripts:

1. **A predicate cue on the owner is not a key unless the predicate is functional.** "Can you remind me what
   two-factor authentication methods you mentioned" spots the owner and the cue `uses`; the probe returned all
   20 of the owner's `uses` facts (scikit-learn, cycling, Down Dog, an IKEA bulb…) and, at weight 2, ranked
   every session holding one above the answer, which fell out of the shown list. The pilot fix (entry 1 of the
   catalogue) covered only the cue-less case. Now a fact from the owner's own probe ranks and anchors only when
   the cue is functional (`works_at`, `lives_in`: one current value, a real answer); facts that touch another
   entity the query named stay.
2. **Anchoring facts cost budget.** Every hit carried up to six of those owner facts as anchors, at about 20
   tokens each, which displaced roughly one session per question at the 4,000-token budget. Owner-probe facts
   no longer anchor. A diagnostic run with fact lines free of charge showed the remaining gap at @10 was
   ranking, not budget; capping anchors at three instead of six changed nothing.

### Results on the fixed engine

Mean recall per type (`m3-lexical`, `m3-facts-haiku-120-e`, `m3-facts-local-qwen9b-120-b`):

| Type | n | lexical @5 | Haiku @5 | 9B @5 | lexical @10 | Haiku @10 | 9B @10 |
|---|---|---|---|---|---|---|---|
| single-session-user | 20 | 0.950 | 1.000 | 0.950 | 1.000 | 1.000 | 1.000 |
| single-session-assistant | 20 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| single-session-preference | 20 | 0.850 | 0.850 | 0.850 | 0.900 | 0.900 | 0.900 |
| multi-session | 20 | 0.657 | 0.678 | 0.640 | 0.821 | 0.821 | 0.804 |
| temporal-reasoning | 20 | 0.929 | 0.917 | 0.929 | 0.967 | 0.954 | 0.967 |
| knowledge-update | 20 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| **all** | 120 | 0.898 | 0.907 | 0.895 | 0.948 | 0.946 | 0.945 |

Paired differences in full recall, n=120:

| | full recall@5 | full recall@10 |
|---|---|---|
| Haiku − lexical | **+0.025 [0.000, 0.058]** | −0.008 [−0.025, 0.000] |
| 9B − lexical | 0.000 [0.000, 0.000] | −0.008 [−0.025, 0.000] |
| 9B − Haiku | −0.025 [−0.058, 0.000] | 0.000 [−0.025, 0.025] |

Per question: Haiku changed five outcomes at @5 against lexical, three gained (two multi-session, one
single-session-user) and two lost (one multi-session, one temporal), and lost one temporal question at @10.
The 9B changed exactly one, a multi-session question it lost (`b5ef892d`, evidence ranks 1, 8, 2 → 4, none,
5). Facts per question: Haiku 230, the 9B 255; unparseable replies: Haiku 5 of 5,452, the 9B 8.

What this says:

- **Facts as keys do no harm and a small good, once the owner is kept out of the ranking.** Before the fix
  they cost three to six questions in 120; after it, the better proposer gains three net and the local one is
  level. The three fixes that made this true (pilot entries 1 to 3 and the two above) all concern the same
  thing: the owner is the subject of nearly every fact, so anything that ranks by "facts about the owner" ranks
  by observation order.
- **The gains are where the literature said they would be**, multi-session questions whose evidence sits in
  several sessions that share a fact rendering, and only with the proposer that writes the sharper facts.
  Haiku's "the user previously worked as marketing specialist (ended)" is the pattern: a reading of the
  passage, not a list of its nouns.
- **The local 9B is a safe proposer, not a better retriever.** It never made retrieval worse than lexical by
  more than one question in 120, at no cost, at 16 sessions a minute; it also never made it better. On this
  card it is the right default for the hybrid mode, with Haiku as the choice when a few points of recall on
  multi-session questions are worth about $5 per thousand sessions.
- **Twenty per type is the floor.** The intervals are wide enough that the 9B's one lost question shows as
  0.000 [0.000, 0.000] at @5 while its @10 interval does not exclude zero. The full 500, about $110 at Haiku
  prices and a day of GPU time for the 9B, is what would separate the two proposers on the multi-session slice.

**Re-run on 2026-09-11 (`m3-facts-local-qwen9b-120-g`; engine of 2026-09-10/11: family Q, chronology,
future facts, owner identity).** Same 120 questions, same 9B at Q4_K_M, the spec
now two sections longer. Session recall@5 0.907 [0.860, 0.946], @10 0.951
[0.915, 0.978]. Against `m3-lexical`: +0.017 [0.000, 0.042] at 5, 0.000 at
10. Against the previous local run on the same questions: +0.008
[−0.017, 0.033] at 5, 0.000 at 10, so a day of verdict and write-time
changes moved no question either way. Two operational notes from the run:
the model was found loaded at `--context-length 16384 --parallel 4`, 4k per
slot, the setting entry 2 rules out; the longer spec overflowed it and every
proposal failed until the model was reloaded at 40k over four slots, 10k per
slot, which held for every session of this run (11.5 GB of the card). Which
context the 2026-09-08/09 runs were loaded with is not recorded (`config.json`
carries no context field and `stratified.sh` loads no model), so the setup
section's slots × 16k stays the rule. And the stratified script's output
filter hid those failures, which is why the monitor now prints each type's
completion line with its failure count.

## The hard 106: where a proposer matters

The 106 answerable questions on which plain lexical retrieval does not find every evidence session in its top
five (`bench/data/hard-lexical.json`): 63 temporal-reasoning, 36 multi-session, 7 others. Selected on the
lexical result alone, so the selection is independent of any proposer; by construction lexical's full recall@5
is zero here and the numbers below are the ceiling of what a proposer can add, not the average
(the average is the stratified 120 above). Haiku, 8 workers, 55 minutes, about $20 for the 3,878 sessions it
had not seen (`m3-facts-haiku-hard`, 2026-09-09).

| | n | lexical mean @5 | Haiku mean @5 | lexical mean @10 | Haiku mean @10 | lexical full @10 | Haiku full @10 |
|---|---|---|---|---|---|---|---|
| temporal-reasoning | 63 | 0.328 | **0.540** | 0.420 | **0.652** | 0.16 | 0.38 |
| multi-session | 36 | 0.539 | 0.565 | 0.706 | 0.720 | 0.39 | 0.42 |
| others | 7 | 0.143 | 0.286 | 0.429 | 0.429 | 0.29 | 0.29 |
| all | 106 | 0.387 | 0.532 | 0.518 | 0.660 | 0.25 | 0.38 |

Paired difference in full recall, Haiku − lexical: **+0.160 [+0.094, +0.236]** at @5, **+0.142 [+0.066,
+0.217]** at @10, n=106. Per question: 26 better and 2 worse at @5, 24 better and 1 worse at @10.

Where it comes from. Of the 26 improved questions, 17 are in the `entity` state (the owner spotted, no
predicate cue), so the gain is the key channel: fact and event renderings that name the things the question
names. "Which device did I get first, the Samsung Galaxy S22 or the Dell XPS 13" finds both sessions through
`the user owns Samsung Galaxy S22 (since 2023-03)` and its twin, where the observation text alone ranked
sessions full of other device talk. Several questions had one or no lexical candidates at all (the query is
all stopwords and owner aliases once "I" and "my" are dropped); the keys supplied candidates where there were
none. The two losses are the same two questions the stratified 120 already showed (`b5ef892d`, `b46e15ed`).

Multi-session moved less than temporal: those questions need several evidence sessions and the keys find the
one that names the entity, not the ones that merely continue the topic. That is the M4 graph expansion's job.

The same 106 through the local 9B, from the full-500 run below:

| | n | lexical mean @5 | 9B mean @5 | Haiku mean @5 | lexical mean @10 | 9B mean @10 | Haiku mean @10 |
|---|---|---|---|---|---|---|---|
| temporal-reasoning | 63 | 0.328 | 0.521 | 0.540 | 0.420 | 0.621 | 0.652 |
| multi-session | 36 | 0.539 | 0.536 | 0.565 | 0.706 | 0.697 | 0.720 |
| others | 7 | 0.143 | 0.143 | 0.286 | 0.429 | 0.429 | 0.429 |
| all | 106 | 0.387 | 0.501 | 0.532 | 0.518 | 0.634 | 0.660 |

The 9B beats lexical on 21 of the 106 and loses on 1. Against Haiku, paired: −0.028 [−0.104, +0.047] at @5,
−0.009 [−0.075, +0.066] at @10, 10 questions better and 15 worse: not distinguishable at this size. The
temporal gain, the large one, is a property of facts with valid time used as keys, and the local model
supplies it in full; Haiku's remaining edge is on multi-session and on the handful of odd questions, and it
is within noise here.

## The full 500

All 500 LongMemEval_s questions through the local Qwen 3.5 9B at Q4 (`m3-facts-local-qwen9b-500`, four slots,
18.1 hours, 23,867 proposals of which 6,015 came from the cache, 35 unparseable, 246 facts per question,
2026-09-09). Paired against `m3-lexical` on the 470 answerable questions:

| Type | n | lexical @5 | 9B @5 | lexical @10 | 9B @10 | better | worse |
|---|---|---|---|---|---|---|---|
| single-session-user | 64 | 0.984 | 0.984 | 1.000 | 1.000 | 0 | 0 |
| single-session-assistant | 56 | 1.000 | 1.000 | 1.000 | 1.000 | 0 | 0 |
| single-session-preference | 30 | 0.867 | 0.867 | 0.900 | 0.900 | 0 | 0 |
| multi-session | 121 | 0.863 | 0.862 | 0.913 | 0.910 | 1 | 1 |
| temporal-reasoning | 127 | 0.666 | **0.758** | 0.712 | **0.812** | 20 | 1 |
| knowledge-update | 72 | 0.986 | 0.979 | 0.986 | 0.986 | 0 | 1 |
| **all** | 470 | 0.862 | **0.885** | 0.891 | **0.917** | 21 | 3 |

Paired difference in full recall, 9B − lexical: **+0.026 [+0.009, +0.043]** at @5 and **+0.030 [+0.015,
+0.047]** at @10, n=470. This is the facts-as-keys gate (milestone M1), passed with both intervals clear of
zero, on the full benchmark, with a proposer that costs nothing to run. The gain is concentrated where the
design said it would be: temporal questions, where a fact's valid time is the key the observation text does
not carry, and it comes with no loss elsewhere (three questions worse in 470). Multi-session is unchanged and
is M4's problem.

For scale, the LongMemEval paper's session-level dense retriever scored 0.706 recall@5 and 0.809 recall@10 on
the same task; Mnemic's lexical baseline alone is 0.862 / 0.891, and with the local proposer 0.885 / 0.917.

## Failure catalogue

Each entry: what was tried, how it failed, how it was diagnosed, and what changed because of it.

### 1. A model larger than the card: Qwen 3.5 35B-A3B (22 GB)

Loaded with LM Studio's automatic offload on a 16 GB card. One proposal per 50 seconds per slot, GPU at
93 percent, VRAM full. The mixture-of-experts design (3B active parameters) does not help when the expert
weights live in system memory. Abandoned after two replies. Rule: the weights must fit in VRAM with room for
the KV cache of every parallel slot; LM Studio's `lms load --estimate-only` gives the number.

### 2. Context split across slots: "Context size has been exceeded"

`--context-length 16384 --parallel 4` gives each slot 4k tokens, not 16k; the first session over that size
failed the whole question with a 400. Sessions run to 5k tokens at the 95th percentile, the spec is about
1k, and the answer up to 1.5k, so the context length passed to `lms load` must be slots × 16k. The harness
now treats an endpoint error on one session as an observation stored without a proposal rather than a dead
question (counted as `endpoint errors`, never cached, fatal after ten in a row).

### 3. Replies cut off at the output limit: Qwen 3.5 9B

21 of 22 unparseable replies from the 9B were JSON truncated at `max_tokens` 4096: the model writes
pretty-printed JSON and lists every item it can find. Diagnosed by running `Proposal.parse` over the cached
replies and grouping the error messages ("Unexpected end-of-input: expected close marker for Array").
Changes: `Proposal.repairTruncated` keeps every complete element of a cut-off reply and drops the partial
one; local endpoints get 6,144 output tokens; `--refresh-failed` re-asks for cached replies that still do not
parse. Zero failures after that, facts per question up from 237 to 276, recall unchanged.

### 4. Thinking that cannot be switched off the usual ways: Qwen 3.5

Qwen 3.5 reasons by default and spent the entire output budget on it, returning empty content
(`completion_tokens_details.reasoning_tokens` equal to the limit). `chat_template_kwargs: {enable_thinking:
false}` and a `/no_think` prefix did nothing on LM Studio; `reasoning_effort: "none"` worked. `response_format:
json_object` is rejected by LM Studio ("must be json_schema or text"). The provider sends the reasoning field
by default and falls back on rejection.

### 5. Thinking that cannot be switched off at all: gpt-oss 20B

`reasoning_effort: none` is accepted but the model still deliberates: 535 reasoning tokens for 184 tokens of
answer on a medium session, all 6,144 tokens on the longest session of the first question with no answer
at all (164 s, `finish_reason: length`). With two parallel slots, both requests failed together after four
minutes with LM Studio's "The model produced output that does not match the expected peg-native format",
the Harmony channel syntax, reaching the harness as a 400. The run had 24 replies after 76 minutes and was
stopped. This is the model family's shape, not the fine-tune: the official release behaves the same on the
first point. A model that must reason before it writes is the wrong tool for a fixed-format extraction under
a token budget.

### 6. A low quantization of an abliterated fine-tune: Qwen 3.5 27B derestricted, Q3_K_S

The only 27B on disk that fit. Two defects: it dropped the object key `"ref":` in 22 percent of replies
(`{"e_gallipoli", "name": "Gallipoli campaign", ...}`), which no repair short of guessing can fix, and it
wrote replies 2.3 times longer than Haiku's (1,076 output tokens against 475). A dense 27B decodes at about
28 tokens per second in aggregate on this card whatever the slot count, so the run took 12.4 hours and
produced fewer usable facts than the 9B for the same recall. Whether the JSON damage comes from Q3 or from
the abliteration is not separable here; the official Q4_K_M is 17.5 GB and does not fit. Derestriction adds
nothing to this task in any case: not one refusal occurred in 2,000 hosted and local sessions.

### 7. Two models under one alias: the cache-key collision

Both local models were loaded under the LM Studio identifier `proposer`, and the cache key was built from
`lmstudio:proposer`. The first "27B" run reused every one of the 9B's cached replies and reported the 9B's
numbers as the 27B's; it was noticed because the run finished in a minute with 980 cache hits. The provider
now asks `/api/v0/models` what is loaded behind an identifier and folds publisher, architecture, and
quantization into the model id. A run's `config.json` shows the resolved id.

### 8. Sloppy proposals that aborted whole questions

The 27B's proposals exposed three engine defects, each of which had thrown out of `remember` and lost the
question (`errors.jsonl`): an entity without a name, the same ambiguous name declared twice in one
proposal, and an event naming a blank participant. All three are warnings now; a ref-shaped name (`e7`)
that no entity declares is a dangling reference rather than a new entity called e7. These will arrive from
real assistants too.

### 9. A subset that was not the same subset

A question that errored did not count toward `--limit`, so the next question in the file took its place and
the "20 questions" of the first 27B run were not the pilot's 20 (`bench compare` reported n=17). Errored
questions count now.

### 10. Downloads that time out

`lms get` on a slow link fails with "Download failed: Timed-out" and resumes from the partial file when
repeated; the pipeline script retries up to eight times. LM Studio's daemon also keeps a download going after
the CLI process is killed, which is how a 17.5 GB Q4_K_M 27B arrived unasked. The catalog does not offer
every quantization (`@q3_k_m` for Qwen 3.5 27B does not exist), and `-y` picks the variant LM Studio deems
best for the machine, which can be one that does not fit whole.

### 11. KV cache that does not scale like the weights: Gemma 3 12B

At 7.3 GB of weights the 12B looked like the roomiest model of the set, and with four slots of 16k it filled
the card to 15.6 GB and ran at 15 percent GPU utilization: 4.1 replies a minute against the 9B's 16.6, ten
questions in 130 minutes. Gemma 3's attention keeps a much larger cache per token than Qwen's, so the same
context budget that left the 9B four gigabytes of headroom pushed the 12B into system memory. Reloaded with
two slots of 16k it fit at 13 GB, then crashed and unloaded itself the moment two long requests ran
together (one request got a 400, `lms ps` showed nothing loaded). Two slots of 12k held: 12.5 GB, about
90 tokens per second aggregate, stable across pairs of real sessions. Rule: budget the KV cache per
architecture, not per parameter count, and test two concurrent requests with real session lengths before
starting a run.

### 12. A spec field sent in the wrong shape: `"derivation": "explicit"`

Of the first 29 of Gemma's 54 unparseable replies that were inspected, 27 sent `derivation` as a bare string
where the spec has `{"kind": "explicit"}`. It has one honest reading, so the parser now accepts it (the
rerun from cache left 5 failures of the 54). The other two were a colon in place of a comma inside an array,
which stays an error.

### 13. An alias with nothing behind it: the Q8 run that was not

The pipeline's first Q8 attempt ran before the download had finished; `lms load` failed, nothing was loaded,
and LM Studio answered the alias `proposer` anyway by loading a default model on demand. With no loaded model
to describe, the id fell back to the bare `lmstudio:proposer`, which was the 9B Q4's original cache key, so
the "Q8 run" finished in one minute with 980 cache hits and the Q4's numbers. The provider now refuses an
LM Studio alias that has nothing loaded behind it while the server is up.

### 14. A second quantization of the same model cannot be loaded by key

`lms ls --variants` lists `qwen/qwen3.5-9b@q8_0` next to `@q4_k_m`, and `lms get` accepts the `@quant`
form, but `lms load` in this LM Studio build finds no model for it (and, given no `--yes`, drops into an
interactive picker that hangs a script). `lms ls --json` and `/api/v0/models` list only the default variant
too, so a script cannot even tell whether the file exists except by looking in the models folder. Workaround:
move the second variant's `.gguf` into its own folder (`<publisher>/<Name>-Q8-GGUF/`), after which it indexes
as a separate model (`qwen3.5-9b@q8_0` here) and loads normally, without the vision projector, which the
proposer does not need. The pipeline script checks for a quantization by file for the same reason.

### 15. `lms load` matches by prefix and takes the first hit

With `-y`, `lms load qwen3.5-9b@q8_0` loaded `qwen3.5-9b-uncensored-hauhaucs-aggressive@q8_0`, a community
fine-tune that happened to share the prefix, and a rerun meant for the official Q8 spent twenty minutes
generating proposals with the wrong model before `lms ps` gave it away. The provider's identity suffix kept
those replies out of the official model's cache (`[HauhauCS/qwen35/Q8_0]`), which is the case the suffix exists
for. After the folder move of entry 14, LM Studio listed the official file under the full-path key
`lmstudio-community/qwen3.5-9b-q8-gguf/qwen3.5-9b-q8_0.gguf`, which is unambiguous and loads. Rule: after
`lms load`, check `lms ps` and the run's `config.json` before trusting a result, and prefer a key nothing else
on the disk starts with.

## Choosing a model for this task

In order of what mattered:

1. **It fits in VRAM with the KV cache for every slot.** Nothing else matters if it does not.
2. **It does not reason, or its reasoning can be turned off.** Check `reasoning_tokens` in the usage block of
   one probe request before running anything.
3. **It writes JSON the parser accepts.** Run twenty sessions, then `Proposal.parse` over the cached replies;
   a model that drops keys is out regardless of size.
4. **Official weights over community fine-tunes, and Q4 or better.** The one 27B run says quantization and
   abliteration cost more than parameters bought.
5. **Throughput is decode-bound.** Slots share the card's memory bandwidth; a 9B at four slots did 980
   sessions in an hour, a dense 27B in twelve.
