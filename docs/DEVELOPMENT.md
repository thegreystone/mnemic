# Developing Mnemic

Building, testing, benchmarking, and where the design lives. End-user installation and use are in the
[README](../README.md).

## Layout

Two Maven modules under `mnemic-parent`:

- `server` — the MCP server: Quarkus 3 with the `quarkus-mcp-server-stdio` extension, SQLite through
  `sqlite-jdbc` (FTS5 built in), Jackson, JUnit 5, and the ONNX Runtime shared library for the build platform,
  unpacked from the runtime's Maven artifact at build time and carried as a resource (hash-pinned in
  `OrtLibrary`; `OrtLibraryTest` fails the build if artifact and pin disagree). The `ModelProvider` SPI and its
  two implementations (`AnthropicHttpProvider`, raw HTTP; `OpenAiCompatibleProvider` for `openai`, `lmstudio`,
  `ollama`, and `openai-compatible`) live here too, under `se.hirt.mnemic.model`, registered in
  `META-INF/services`; no vendor SDK is on the class path. Packaged as an uber-jar and as a GraalVM native image.
- `bench` — the benchmark harness: LongMemEval loading, proposers, readers, judges, metrics, the embedder
  bake-off, and the proposal cache. It resolves models through the server's SPI and defines no providers of its
  own.

How the code is organised, package by package, with the write path and the read path through the engine:
[ARCHITECTURE.md](ARCHITECTURE.md).

Design documents:

- [DESIGN.md](DESIGN.md) — goals, deployment model, knowledge model, technology choices
- [EXTRACTION.md](EXTRACTION.md) — how observations become facts without a server-side model; predicate
  registry; recall
- [EVALUATION.md](EVALUATION.md) — scenarios and expected results, each implemented as a tagged test
- [research/literature-survey.md](research/literature-survey.md) — the literature behind the design
- [BENCHMARKS.md](BENCHMARKS.md) — every benchmark run and ablation, with run ids and intervals
- [LOCAL-MODELS.md](LOCAL-MODELS.md) — local models as fact proposers: setup, measurements, failure catalogue

## Building

Requires JDK 25 and Maven 3.9. A native image additionally needs GraalVM 25 (`GRAALVM_HOME`) and, on Windows, the
Visual Studio 2022 C++ toolchain.

```text
mvn package                                        # both modules, all tests (about fifteen seconds)
mvn package -Dnative -DskipTests -pl server -am    # native executable in server/target/
mvn -pl server test-compile failsafe:integration-test -Dnative.image.path=target/mnemic-server-<version>-runner.exe
```

The path to the native image is relative to `server/`, where failsafe runs (`target/...`, not `server/target/...`);
`<version>` is the `revision` property of the parent POM, and the suffix is `.exe` on Windows only. The same wire
test runs against the runner jar with `-Drunner.jar=target/mnemic-server-<version>-runner.jar` in place of the
native path, after `mvn package`: it checks that the initialize reply carries the memory protocol as its
`instructions` and that `inspect('guide')` returns the proposal guide, alongside the handshake and a remember and
recall round trip.

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) with the Eclipse formatter profile in
`config/formatter/mnemic-formatting.xml` (tabs, 120 columns). `spotless:check` runs in the `validate` phase, so an
unformatted file fails `mvn package`; `mvn spotless:apply` reformats both modules. Point your IDE at the same
profile (IntelliJ reads it through the Eclipse Code Formatter plugin) to avoid a round trip.

`server/target/scenario-coverage.txt` lists which scenarios from [EVALUATION.md](EVALUATION.md) are implemented
after every build. The native sanity IT starts the binary over stdio, exercises the legacy `initialize` and the
stateless `server/discover` handshakes, and checks that `ServiceLoader` finds the model providers inside the
image.

Do not run two Maven invocations against the same module at once; a native build rewrites `server/target`
while it runs, and a bench run reads the installed server jar from `~/.m2`, so `mvn install` while a bench
JVM is running can hand it a half-written jar.

## The protocol texts

Two files under `server/src/main/resources/protocol/` tell an assistant how to work with the store, each served
whole from one place and never repeated elsewhere. `instructions.md` is the memory protocol; a config source
(`InstructionsConfigSource`) hands it to the MCP library as the `instructions` of the initialize reply, which
Claude Code puts into the system prompt (Claude Desktop and claude.ai connectors drop the field, as of
September 2026). It has to stay under 2,000 characters: Claude Code holds every configured server's
instructions in one block of about 4 KB and cuts the rest silently
([anthropics/claude-code#43474](https://github.com/anthropics/claude-code/issues/43474)), so a longer text
loses its end whenever other servers are configured; a test enforces the bound. `guide.md` is the proposal
guide, returned by `inspect('guide')`; the instructions say when to fetch it. `extraction-spec.md` is the
proposal format, used by the bench's model proposer.

## Tests

- Unit and scenario tests run with `mvn package`. Scenario tests are annotated with `@Scenario("C2")` and map
  one to one onto [EVALUATION.md](EVALUATION.md); the coverage report is generated from those annotations.
- `MnemicToolsTest` is the one `@QuarkusTest`, exercising the tool layer inside the container.
- `HybridProposerTest` uses a scripted `ModelProvider` registered only in the test tree
  (`server/src/test/resources/META-INF/services`), so no model is needed.
- Migrations: `MigrationsTest` verifies the list, checksums (whitespace-normalised, so a reformat is harmless),
  and refusal of a newer schema. Never edit an applied migration; add the next one.
- Some tests need what the build does not ship and skip when it is absent. With the ONNX Runtime library
  (`MNEMIC_ORT_LIBRARY`) and an embedding model directory (`MNEMIC_EMBED_MODEL`, holding `model.onnx` and
  `tokenizer.json`) in the environment, `EmbedderTest`, `SemanticRecallTest`, `OwnerAliasSampleTest`, and the
  embedding integration tests in `NativeImageSanityIT` run. `MNEMIC_VEC_LIBRARY` (the sqlite-vec loadable
  library) gates only the sqlite-vec integration test. `MNEMIC_BAKEOFF_MODELS` (a directory of model folders)
  gates `TokenizerReferenceTest` and widens `OwnerAliasSampleTest`. `MNEMIC_EMBED_MIRROR` (a directory laid out
  like the model's Hugging Face repository) gates the two first-use download tests: scenario F2 in
  `SemanticRecallTest` and the one in `NativeImageSanityIT`.

## Benchmark harness

The harness runs from the `bench` module against a local LongMemEval file (not in the repository; MIT-licensed
on Hugging Face as `xiaowu0162/longmemeval-cleaned`, saved as `bench/data/longmemeval_s_cleaned.json`).

```text
mvn -q install -DskipTests                                       # the bench resolves the server jar from ~/.m2
cd bench
mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/m0"
mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/m1 --proposer anthropic:claude-haiku-4-5"
mvn -q exec:java -Dexec.args="compare --a results/m0 --b results/m1"
mvn -q exec:java -Dexec.args="show --data data/longmemeval_s_cleaned.json --question 58bf7951 --proposer lmstudio:proposer"
```

Commands: `run` (resumable: questions already in `retrieval.jsonl` are skipped), `metrics`, `compare` (paired
bootstrap), `show` (one question end to end, the recall block a reader would see), `judge` (reader-judged
accuracy, not yet used), `rekey` (migrate cached replies between model ids), `bakeoff` (rank embedding models on
the sample set in `bench/bakeoff/personal-notes.json`; the runs behind the default-model choice in
[BENCHMARKS.md](BENCHMARKS.md)). The built-in usage text (`bench` with no arguments) lists only `run`, `judge`,
`metrics`, and `compare`.

Two metrics, not one: `metrics` reports **mean recall@k**; `compare` reports the paired difference in **full
recall@k**, the share of questions whose evidence sessions were all retrieved. They coincide only for
single-session question types.

Options, per command (from `Bench.java`):

- `run --data FILE --out DIR`: `--granularity session|turn`, `--k`, `--budget`, `--limit N`, `--types t1,t2`,
  `--abstention true` (only the 30 questions whose answer is "not known"), `--proposer MODEL`,
  `--reader MODEL|none`, `--api-key-env NAME`, `--workers N` (parallel proposals, default 8), `--cache DIR`
  (default `cache/proposals`), `--refresh-failed`, `--reasoning-effort none|low|medium|high` (local reasoning
  models), and `--embed-model DIR --ort-library LIB` (gives every engine of the run the semantic channel; without
  them a run is lexical and structured only).
- `show --data FILE --question ID`: `--proposer`, `--api-key-env`, `--granularity`, `--k`, `--budget`, `--workers`,
  `--cache`, `--reasoning-effort`.
- `metrics --run DIR`; `judge --run DIR --judge MODEL [--api-key-env NAME]`; `compare --a DIR --b DIR`.
- `rekey --data FILE --from MODEL --to MODEL`: `--granularity`, `--limit`, `--types`, `--cache`.
- `bakeoff --models DIR1,DIR2,... --ort-library LIB`: `--samples FILE` (default `bench/bakeoff/personal-notes.json`,
  relative to the working directory), `--repeat N` (default 3), `--out FILE` (write the table as JSON).

Proposals are cached under `bench/cache/proposals`, keyed by model id, extraction spec, and session text, so a
paid proposer never reads the same session twice and later engine versions reuse the same replies; a different
model or an edited spec gets its own entries. For LM Studio the model id carries what the server reports behind
an identifier (`lmstudio:proposer[qwen/qwen35/Q4_K_M]`), because an alias alone let two models share a cache
once.

Models are named `provider:model[@endpoint]` and resolved through the `ModelProvider` SPI in the server module:
`anthropic:claude-haiku-4-5`, `openai:gpt-4o-mini`, `lmstudio:<model-id>`, `ollama:<model-id>`,
`openai-compatible:<model>@http://host/v1`. Keys come from `API_KEY_ANTHROPIC` or `ANTHROPIC_API_KEY`, and
`API_KEY_OPENAI` or `OPENAI_API_KEY`, or from the variable named by `--api-key-env`; never from files in the
repository. Local models need no key.

## The usage bench

`bench usage` measures something the retrieval bench cannot: whether an assistant that is handed the server's own
tool descriptions and the memory protocol actually gets a memory in and out. A model plays the assistant over the
scripted conversations in `bench/usage/scenarios.json` (the fictional owner Mattias Sandell; twelve scenarios,
forty steps). Each scenario starts a real server over stdio on a fresh data home, exactly as a client would, and
the assistant is given what a real client gives it: the server's instructions from the initialize reply and the
tools it publishes (the proposal guide is not pasted in; the assistant fetches it with `inspect('guide')`). At each step the model either calls a tool (`{"tool": ...,
"arguments": {...}}`) or answers the user (`{"reply": ...}`); a judge grades the answers against the script's
expected ones, abstention questions with the `_abs` rule of the retrieval bench.

```text
cd bench
mvn -q exec:java -Dexec.args="usage --script usage/scenarios.json --server ../server/target/mnemic-server-0.3.1-SNAPSHOT-runner.jar --assistant lmstudio:proposer --judge lmstudio:proposer --out results/usage-qwen --reasoning-effort none"
mvn -q exec:java -Dexec.args="usage --script usage/scenarios.json --server ../server/target/mnemic-server-0.3.1-SNAPSHOT-runner.jar --assistant anthropic:claude-sonnet-5 --judge anthropic:claude-sonnet-5 --out results/usage-sonnet"
```

Options: `--server` (the runner jar or the native binary), `--assistant MODEL`, `--judge MODEL|none`,
`--api-key-env NAME`, `--limit N` (first N scenarios), `--only ID` (one scenario), `--embed on|off` (default
off: the semantic channel is not what is measured), `--max-calls N` (tool calls allowed per step, default 8),
`--reasoning-effort` as for `run`.

The run writes `config.json`, `usage.jsonl` (one record per step: every call with its arguments and the head of
its result, the reply, the recall verdict, the judge's verdict), and `summary.json`. The summary's numbers say how
the tools were used, not only whether the answer was right: `remember_rate` (statements that led to a `remember`, or
to a `correct` or `forget`, which is how "no, that was wrong" is written down) and `remembered_with_reading` (the
`remember` carried a proposal, as the protocol asks), `recall_first_rate` (questions where a
`recall` came before the answer), `accuracy`, `abstention_correct`, `wrong_answers_on_a_miss` (an answer given
although the last recall said MISS: the number that should be zero), `tool_calls_per_step`, and
`protocol_slips` (turns that did not follow the JSON protocol and were read leniently: a `{"recall": {...}}`
shape, a missing closing brace, a reply whose JSON broke, the model's own tool-call syntax, or plain prose; the
raw text of each is kept under `slipped` in the step's record). A step `{"break": true}` ends the conversation:
the server is started afresh on the same data home and the assistant meets an empty transcript, so the
questions after it can only be answered from the store. Those are counted on their own as
`questions_after_break`, `recall_first_rate_after_break` (a `recall` in the answering turn itself),
`recall_in_conversation_rate_after_break` (a `recall` in that turn or earlier in the same conversation, whose
block is still in front of the model), and `accuracy_after_break`; within one conversation an assistant may
fairly answer from what was just said, after a break it cannot. Anthropic runs also report
`api_usage`, the tokens billed, with the system prompt cached across calls.

Scripts: `bench/scripts/local-proposers.sh` downloads, loads, runs, and compares a list of LM Studio models on
the pilot questions; `bench/scripts/stratified.sh` runs N questions of every type for one proposer and compares
against the lexical baseline; `bench/scripts/stratified-embed.sh <proposer|none> <run> <without-run> <model-dir>
<ort-library> [per-type N] [workers]` does the same with the semantic channel on and compares against a run
without it. All three are Windows-and-Git-Bash shaped.

### Local models

[LOCAL-MODELS.md](LOCAL-MODELS.md) is the full account: setup through LM Studio's `lms`, what fits on a 16 GB
card, results for a hosted model and four local ones on identical questions, and a catalogue of fifteen ways
local proposers failed and what changed because of each. The short version, on the same 20 questions:

| Proposer | recall@5 | recall@10 | facts / question | unparseable | wall time | cost |
|---|---|---|---|---|---|---|
| `anthropic:claude-haiku-4-5`, 8 workers | 1.000 | 1.000 | 231 | 2 of 980 | 13 min | about $5.70 |
| `qwen/qwen3.5-9b` Q4_K_M, RTX 5080, 4 slots | 0.950 | 1.000 | 259 | 0 | 59 min | electricity |
| same 9B at Q8_0, 2 slots | 0.950 | 1.000 | 214 | 3 | 86 min | electricity |
| Gemma 3 12B Q4_K_M, 2 slots of 12k | 0.950 | 1.000 | 131 | 5 | about 1.5 h | electricity |
| Qwen 3.5 27B derestricted Q3_K_S, 2 slots | 0.950 | 1.000 | 178 | 216 | 12.4 h | electricity |
| lexical only | 0.950 | 1.000 | 0 | | 1 min | nothing |

On the stratified 120 (20 per type), Haiku ends +0.025 [0.000, 0.058] full recall@5 over lexical and the 9B
level with it, after a structured-channel defect the run exposed was fixed. On the full 500 the 9B is
+0.026 [+0.009, +0.043] over lexical at @5 and +0.030 [+0.015, +0.047] at @10 (n=470), the gain sitting in
temporal-reasoning (0.666 → 0.758); on the 106 questions lexical misses, it is level with Haiku
(LOCAL-MODELS.md, "The full 500" and "The hard 106").

## Releases

`.github/workflows/release.yml` builds the uber-jar and native binaries for linux-x86_64, linux-aarch64,
macos-aarch64, and windows-x86_64 on a tag, runs the native sanity IT on each, and names the binaries
`mnemic-<version>-<platform>`.

### MCP Bundles

The release also packs an [MCP Bundle](https://github.com/anthropics/mcpb) (`.mcpb`) for macOS and Windows,
the two platforms that have Claude Desktop. A bundle is the binary under `server/` plus a `manifest.json`
filled in from [`mcpb/manifest.json`](../mcpb/manifest.json) (`__VERSION__`, `__BINARY__`, and `__PLATFORM__`
are substituted). The manifest maps the extension settings onto the `MNEMIC_*` variables from the README, so
the bundle and the manual configuration behave the same. Packing is [`mcpb/pack.sh`](../mcpb/pack.sh), run on
the runner that built the binary so the executable bit survives on macOS.

A **universal** bundle, `mnemic-<version>-universal.mcpb`, carries the macOS and Windows binaries with
[`mcpb/manifest-universal.json`](../mcpb/manifest-universal.json) choosing one at launch; it is what a
plugin-marketplace entry points at, since a plugin can reference only one bundle. Linux is left out, as
Claude Desktop does not run there. It is packed on Linux by [`mcpb/pack-universal.sh`](../mcpb/pack-universal.sh).
The manual `Bundles` workflow (`.github/workflows/bundle.yml`) re-packs any of these for an already published
release, for instance after a binary on the release was replaced.
