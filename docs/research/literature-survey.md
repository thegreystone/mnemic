# Long-Term Memory for LLM Agents: Literature Survey for the Mnemic Design Review

Date: 2026-09-07. Prepared with Claude for the Mnemic design review. The project was then still called Engram; project
references below say Mnemic, and the rename recommended in B.17 and C was carried out on 2026-09-07.

## 0. Scope and method

Mnemic is a local-first MCP memory server (Java/GraalVM native image, SQLite + FTS5 + optional local vectors).
Knowledge model: observation -> event -> fact with a predicate registry; bitemporal facts; functional-predicate
conflicts
surfaced as questions; caller-proposed extraction validated deterministically; hybrid recall (structured probe + BM25 +
vectors + graph, RRF) with token-budgeted context and an explicit "structured miss" signal; deferred consolidation.
The assistant reasons, the server retrieves. Evaluation plan: LongMemEval_s + abstention, synthetic gold set, scenario
tests.

This survey was done with web search and fetching of primary sources (arXiv, GitHub, vendor blogs). Where a number
comes from a vendor's own blog rather than a peer-reviewed or independently reproduced source, it is flagged.

## A. Benchmarks

Note on method: sources were read directly on 2026-09-07 (arXiv abstracts and HTML, arXiv API listings, vendor
blogs); numbers were transcribed from those pages, not re-derived, so verify before citing. Numbers from vendor
blogs are marked [vendor].

### A.1 LongMemEval (arXiv 2410.10813, Oct 2024, ICLR 2025)

- 500 questions; five abilities: information extraction, multi-session reasoning, temporal reasoning, knowledge updates,
  abstention. Seven question types (single-session-user, single-session-assistant, single-session-preference,
  multi-session,
  temporal-reasoning, knowledge-update, plus 30 abstention questions built by removing the evidence).
- Settings: S ~115k tokens/problem (~40-50 sessions); M ~500 sessions (~1.5M tokens); oracle = evidence sessions only.
- Headline: commercial assistants lose 30-60% vs oracle. GPT-4o offline reading (oracle) 0.918; ChatGPT (GPT-4o) 0.577;
  Coze 0.330.
- Abstention scoring: "I don't know"-type answers are correct iff the evidence really is absent.
- The paper's own retrieval ablations (the numbers Mnemic needs) are reported in B2.Q1/Q2/Q7.
- Trust: high. Human-curated, explicit temporal and update questions, evidence-session labels enable Recall@k. Weakness:
  single
  domain (personal assistant chat), 500 questions -> 95% CI roughly +/-2-3 pp, so differences under ~3 pp are noise
  (2608.12888 reports 93.2 +/- 3.3 on S and 89.3 +/- 6.0 on M, illustrating the CI width).
- Saturation: by mid-2026 many self-reported systems claim 90-97% on LongMemEval_s (see table A.7), i.e. the S variant
  is
  nearly saturated for well-engineered retrieval + frontier readers. The M variant (1.5M tokens) and abstention remain
  discriminative. A "LongMemEval-V2" is referenced by 2608.10502 (details not verified).

### A.2 LoCoMo (arXiv 2402.17753, Feb 2024)

- 10 conversations, ~300 turns / ~9k tokens avg (16-26k tokens with timestamps), up to 35 sessions; ~1,986 QA over 5
  categories (single-hop, multi-hop, temporal, open-domain/commonsense, adversarial).
- Known problems (Zep blog, 2025 [vendor], and MemoryAgentBench 2507.05257): conversations are far too short to stress a
  modern context window (a plain full-context GPT-4o-mini baseline scores ~73% vs Mem0's best ~68% in Zep's re-run);
  category 5 (adversarial) is often "unusable due to missing ground-truth answers"; questions referencing images whose
  content is absent from captions; speaker mis-attribution and underspecified questions; category numbering differs
  between
  the paper and the released JSON (which is why Mem0's and others' tables disagree on which category is "temporal").
  Different papers silently drop category 5, use different judges (F1/BLEU vs LLM-judge "J"), and different answer
  models, so cross-paper LoCoMo numbers are NOT comparable.
- Trust: low-to-medium for absolute claims; usable only for within-paper ablations with a fixed harness.

### A.3 MemoryAgentBench (arXiv 2507.05257, Jul 2025, rev. Jun 2026)

- Four competencies: accurate retrieval (AR), test-time learning (TTL), long-range understanding (LRU), selective
  forgetting / conflict resolution (SF). Contexts 103k-1.44M tokens, fed incrementally in multi-turn form.
- Results (Table 3): GPT-4o-128k 58.1 AR / 50.0 TTL / 54.9 LRU / 32.5 SF; GPT-5-mini-400k 74.4 / 48.6 / 66.2 / 53.0;
  Claude-3.7-Sonnet 59.7 / 53.9 / 62.2 / 22.5. RAG: BM25 60.5 AR vs text-embedding-3-small 53.8 / -large 54.6 (lexical
  beats
  dense on AR). HippoRAG-v2 65.1 AR (best AR of any method). Mem0 32.6 AR / 21.2 / 20.7 / 10.0; MemGPT 34.3 / 40.8 /
  22.4 /
  15.5; MIRIX 47.5 / 24.1 / 25.4 / 8.0; MemoRAG 34.5 / 45.1 / 30.0 / 14.0.
- Lessons: (i) commercial "agentic memory" products underperform plain BM25 on accurate retrieval by ~25-30 pp; (ii)
  selective
  forgetting (knowledge update with multi-hop) is unsolved: <=28% for every method; (iii) long-context models dominate
  the
  holistic tasks (TTL, LRU) that chunk retrieval cannot cover.
- Trust: high as a diagnostic; the datasets are largely re-purposed long-context sets, so "conversation" realism is
  modest.

### A.4 MemBench (arXiv 2506.21605, Jun 2025, ACL Findings 2025)

- Factual vs reflective memory x participation vs observation scenarios; metrics for effectiveness, efficiency,
  capacity.
  Useful for the "reflective" (inference over memories) axis; headline numbers not extracted here.

### A.5 PersonaMem (arXiv 2504.14225, Apr 2025, COLM 2025) and PersonaMem-v2

- 180+ simulated histories, up to 60 sessions, 15 personalization tasks; tests tracking of evolving preferences.
- Frontier models (GPT-4.1, o4-mini, GPT-4.5, o1, Gemini-2.0) reach only ~50% by direct prompting. PersonaMem-v2 is used
  by
  several 2026 systems (2608.29953, 2607.05794). Strong signal for "preference drift" (knowledge-update-like) questions.

### A.6 MemoryBank (arXiv 2305.10250, May 2023)

- Early system+benchmark (SiliconFriend) with Ebbinghaus forgetting-curve retention. Evaluation is small,
  LLM/human-rated
  companionship dialogues; not a trustworthy quantitative benchmark today.

### A.7 BEAM (arXiv 2510.27246, Oct 2025, rev. Feb 2026)

- 100 conversations, 2,000 validated questions, dialogues up to 10M tokens; targets a wide range of memory abilities
  beyond
  recall (contradiction, abstention, summarization, preference following, etc.). Companion system LIGHT (episodic +
  working
  memory + scratchpad) improves 3.5-12.7% over strongest baselines; 1M-context models degrade as dialogues lengthen.
- Trust: promising and, unlike LongMemEval_s, unsaturated; too new for a stable leaderboard. ArborMem (2608.17534)
  reports
  on a BEAM-100K slice.

### A.8 2026 benchmarks (arXiv API sweep, Jun-Sep 2026)

- MemOps (2607.12893): reframes memory as lifecycle operations (add/update/delete) - directly relevant to Mnemic's write
  path.
- UTILMEM (2608.30508): evidence-utilization diagnostic across five domains (does the reader use retrieved evidence?).
- Keep It InMind (2607.24368): implicit-association blind spot - questions whose evidence has no lexical/semantic
  overlap
  with the query; a stress test for structured-probe + graph recall.
- "When Users Don't Ask" (2609.03467): context-driven (proactive) memory retrieval.
- MEMPROBE (2606.24595): hidden user-state recovery. MemFuse (2608.18704): multi-source fusion.
- "Harness the Memory" (2608.15008) and MemDelta (2606.29914): controlled substrate comparisons and confound analyses
  (see B2 for their findings).
- Security benchmarks (MemSecBench 2607.27080; 2608.21230 shows poisoning 1.2% of a LongMemEval corpus drops accuracy
  0.850
  -> 0.300) - relevant to Mnemic's "caller-proposed extraction validated by the server" stance.

## B. Architectures: design, results, lesson for Mnemic

Format per entry: design / results / lesson. "J" = LLM-as-judge accuracy. LoCoMo numbers are not comparable across
papers (see A.2); LongMemEval_s numbers are comparable only when the answer model is stated.

### B.1 Zep / Graphiti (arXiv 2501.13956, Jan 2025)

- Design: temporal knowledge graph; episode -> entity -> community subgraphs; edges carry a bitemporal model
  (t_valid/t_invalid on the event timeline, t'_created/t'_expired on the transaction timeline). Entity resolution by
  embedding + full-text candidate search then LLM merge; edge invalidation by an LLM comparing a new edge with
  semantically
  related existing edges, newer wins. Hybrid retrieval (cosine + BM25 + graph BFS), RRF or cross-encoder reranking.
- Results (LongMemEval_s, paper Table 3): GPT-4o full-context 60.2% -> Zep 71.2%; GPT-4o-mini 55.4% -> 63.8%. Per type
  (GPT-4o, full-context -> Zep): SS-preference 20.0 -> 56.7; SS-assistant 94.6 -> 80.4 (drop); temporal 45.1 -> 62.4;
  multi-session 44.3 -> 57.9; knowledge-update 78.2 -> 83.3; SS-user 81.4 -> 92.9. Latency 28.9 s -> 2.58 s.
  DMR 94.8% vs MemGPT 93.4%.
- Lesson: the bitemporal edge model is the closest published analogue to Mnemic's facts and it measurably helps temporal
  and update questions; but graph extraction LOSES on single-session-assistant questions (-14 to -17 pp) because the
  assistant's own verbatim output is not preserved as a fact. Mnemic must keep raw observations retrievable (see B2.Q3).

### B.2 Mem0 / Mem0g (arXiv 2504.19413, Apr 2025)

- Design: an LLM extracts candidate facts from each message pair (with rolling summary + recent messages as context),
  then
  an LLM tool call decides ADD/UPDATE/DELETE/NOOP against semantically similar existing memories. Mem0g adds an
  entity/relation graph. Original messages are not the retrieval unit.
- Results (LoCoMo, GPT-4o-mini judge "J"): Mem0 66.9 overall (single-hop 67.1, multi-hop 51.2, open-domain 72.9,
  temporal
  55.5); Mem0g 68.4 (temporal 58.1, open-domain 75.7); Zep as run by Mem0 66.0; OpenAI memory 52.9; full-context
  72.9 (!);
  best RAG ~61. Search latency p50/p95 0.148/0.200 s (Mem0), 0.476/0.657 s (Mem0g); total p95 1.44 s vs 17.1 s
  full-context.
  ~7k tokens/conversation stored (Mem0) vs 14k (Mem0g).
- Criticisms: (i) Mem0's own table shows full-context (72.9) beating Mem0 (66.9) - the headline "26% over OpenAI" is
  against the weakest baseline; (ii) Zep's re-run found three harness errors (both speakers given the user role,
  timestamps
  appended to text instead of created_at, sequential searches) and re-scored Zep at 75.14 +/- 0.17 [vendor];
  (iii) MemoryAgentBench (independent) measures Mem0 at 32.6% accurate retrieval vs BM25 60.5%; (iv) MemDelta (
  2606.29914)
  finds Mem0 statistically indistinguishable from plain cloud RAG (72.7 vs 73.9, p = 1.0) at ~50x the cost.
- Lesson: LLM-mediated ADD/UPDATE/DELETE at write time is expensive and, on independent evaluation, does not beat
  lexical
  retrieval of raw text. The "2 pp from the graph" (Mem0 -> Mem0g) is within noise.

### B.3 A-MEM (arXiv 2502.12110, Feb 2025, NeurIPS 2025)

- Design: Zettelkasten-style notes (content + LLM-generated context/keywords/tags), LLM-proposed links to existing
  notes,
  and "memory evolution" (new notes can rewrite attributes of old ones).
- Results (LoCoMo F1, GPT-4o-mini): A-MEM multi-hop 27.0 / temporal 45.9 / open-domain 12.1 / single-hop 44.7 /
  adversarial 50.0 vs the plain LoCoMo baseline 25.0 / 18.4 / 12.0 / 40.4 / 69.2; MemoryBank (Ebbinghaus decay) 5.0 /
  9.7 /
  5.6 / 6.6 / 7.4. Ablation: removing link generation + evolution drops multi-hop F1 27.0 -> 9.7. 1.2-2.5k tokens/op.
  Independent runs: 39.8 J single-hop in Mem0's harness; 61.4 J overall in Nemori's.
- Lesson: linking/evolution matters for multi-hop within this design, but A-MEM as a whole is beaten by full-context and
  by
  simpler systems in every independent harness; adversarial (abstention-like) accuracy is LOWER than the no-memory
  baseline
  (50.0 vs 69.2) - a warning that aggressive memory can hurt abstention.

### B.4 MemOS (arXiv 2507.03724, Jul 2025)

- Design: "MemCube" units carrying content + provenance/versioning metadata; unifies plaintext, activation (KV-cache)
  and
  parametric memory with a scheduler. Vendor evaluation claims first place in every LoCoMo category over MIRIX, Mem0,
  Zep,
  Memobase, MemU, Supermemory (MemOS-1031 release); per-category numbers are in the paper's Tables 3-6 (not extracted) -
  treat as vendor-run.
- Lesson: provenance + version metadata per unit is sound and matches Mnemic's observation/event lineage; the
  parametric/KV parts are irrelevant to a server that never calls a model.

### B.5 MemoryOS (arXiv 2506.06326, May 2025)

- Design: STM -> MTM (dialogue-chain FIFO pages) -> LPM (persona/profile) hierarchy with heat-based promotion.
- Results: +49.1% F1 / +46.2% BLEU-1 over baselines on LoCoMo with GPT-4o-mini (paper); independent (Nemori harness,
  GPT-4.1-mini) 60.6 J vs full-context 80.6.
- Lesson: OS-style paging hierarchies have not shown independent gains; heat/recency promotion is not a substitute for
  retrieval quality.

### B.6 LightMem (arXiv 2510.18866, Oct 2025, ICLR 2026)

- Design: sensory buffer (compression + topic segmentation) -> STM topic summaries -> LTM updated offline ("
  sleep-time").
- Results (LongMemEval): +7.7% (GPT backbone) / +29.3% (Qwen) accuracy over baselines with 38x/20.9x fewer tokens and
  30x/55.5x fewer API calls; online-only costs 106-117x fewer tokens.
- Lesson: deferring expensive consolidation off the request path is free accuracy-wise and dominant cost-wise - direct
  support for Mnemic's "consolidate" as a deferred job.

### B.7 Nemori (arXiv 2508.03341, Aug 2025, rev. Apr 2026)

- Design: episodic segmentation of the stream into narrative episodes + semantic knowledge distilled by prediction error
  (store what the model could not have predicted), rather than heuristic importance scores.
- Results (LoCoMo J, GPT-4.1-mini): Nemori 80.8 = full-context 80.6 > Mem0 66.3 > Zep 61.6 > A-MEM 61.4 > MemoryOS 60.6.
  LongMemEval_s: +16.7 pp (GPT-4o-mini) / +13.7 pp (GPT-4.1-mini) over full-context with 95-96% fewer tokens. Build cost
  373 LLM calls / 323k tokens per conversation vs Mem0 1,602 calls / 1.69M tokens. Ablation: dropping semantic retrieval
  -25.1%, dropping episodic retrieval -11.0%; episode window 5-40 messages changes accuracy < 1 pp.
- Lesson: (a) both episode text and distilled facts are needed, facts more so; (b) episode boundary granularity is a
  second-order knob; (c) "store the surprise" is a principled write filter Mnemic's caller could implement.

### B.8 Mem-alpha (arXiv 2509.25911, Sep 2025) and Memory-R1 (arXiv 2508.19828, Aug 2025)

- Design: RL-trained memory managers. Memory-R1 trains an ADD/UPDATE/DELETE/NOOP manager + answer agent with PPO/GRPO
  from
  only 152 QA pairs; Mem-alpha trains construction over core/episodic/semantic stores and generalizes from 30k to 400k+
  token histories.
- Results (Memory-R1, LoCoMo, LLaMA-3.1-8B): J 62.7 (GRPO) vs Mem0 45.7; removing manager RL 45.0 -> 37.5 F1.
- Lesson: the write-policy decision (what to add/update/delete) is learnable and is where accuracy is won or lost.
  Mnemic
  delegates this to the caller; the server's deterministic validation should therefore give the caller strong signals
  (existing conflicting fact, predicate constraints) rather than silently accepting.

### B.9 ReasoningBank (arXiv 2509.25140, Sep 2025, ICLR 2026)

- Design: distills reusable reasoning strategies from both successful and failed trajectories; MaTTS scales experience.
  Beats raw-trajectory and success-only memory on WebArena/Mind2Web/SWE-Bench-Verified (effectiveness up, steps down).
- Lesson: procedural/experiential memory is a distinct store from facts; out of scope for a personal-fact server.

### B.10 MemTool (arXiv 2507.21428, Jul 2025)

- Design: short-term management of tool context (add/remove MCP tools) in autonomous / workflow / hybrid modes;
  reasoning
  models remove 90-94% of stale tools, mid-size models 0-60%.
- Lesson: relevant to Mnemic as an MCP server: keep the tool surface small; weaker callers cannot manage many tools.

### B.11 HippoRAG 2 (arXiv 2502.14802, Feb 2025, ICML 2025)

- Design: passage nodes + OpenIE triples in one graph, Personalized PageRank seeded by the query, "recognition memory"
  filter on seed nodes, dense-sparse integration.
- Results: +7 pp on associative (multi-hop) QA over NV-Embed-v2; GraphRAG/LightRAG/RAPTOR fall BELOW plain RAG on
  factual
  QA, HippoRAG 2 does not. MemoryAgentBench: best accurate-retrieval score of any method (65.1%); on the same benchmark
  ReFind's plain lexical agent search beats it (58.2 vs 53.2 mean, GPT-4o-mini).
- Lesson: graphs that keep passages as first-class nodes avoid the factual-QA regression that triple-only graphs suffer.
  Mnemic's "facts point back to observations" is the right shape.

### B.12 MIRIX (arXiv 2507.07957, Jul 2025)

- Design: six typed stores (core, episodic, semantic, procedural, resource, knowledge vault) with a multi-agent router.
- Results: 85.4% LoCoMo (own harness); independent MemoryAgentBench 47.5 AR / 8.0 SF.
- Lesson: many typed stores do not fix retrieval; typing helps routing only when the query type is predictable.

### B.13 EverMemOS (arXiv 2601.02163, Jan 2026)

- Design: MemCells (episodic trace + atomic facts + time-bounded "foresight"), consolidated into thematic MemScenes,
  agentic reconstructive recollection. Claims SOTA on LoCoMo and LongMemEval; numbers not in abstract or README.
- Lesson: same episodic + semantic + profile triad as Nemori/Hindsight/LeanMem - now the consensus shape.

### B.14 Memoria (arXiv 2512.12686, Dec 2025)

- Design: session-level summaries + weighted user-model KG. No comparable public numbers.
- Lesson: user-profile KGs are cheap; keep them as a view over facts rather than a separate store.

### B.15 H-MEM (arXiv 2507.22925, Jul 2025)

- Design: multi-level abstraction index where each vector carries positional pointers to sub-memories (routing instead
  of
  exhaustive similarity). Beats five baselines on LoCoMo (numbers not extracted).

### B.16 Hindsight (arXiv 2512.12818, Dec 2025)

- Design: four networks (world facts, experiences, entity summaries, evolving beliefs); retain/recall/reflect; four-way
  parallel retrieval (dense + BM25 + spreading-activation graph + temporal filter) merged with RRF, cross-encoder
  rerank,
  token-budget cut.
- Results (LongMemEval_s): 83.6% with a 20B open model (full-context of the same model 39.0%), 89.0% (120B), 91.4% (
  Gemini-3);
  per type (Gemini-3): SS-user 97.1, SS-assistant 96.4, SS-pref 80.0, multi-session 87.2, temporal 91.0, KU 94.9. LoCoMo
  89.6.
- Lesson: the closest published validation of Mnemic's recall stack (structured + BM25 + dense + graph, RRF, budget).
  A reranker and a small open reader are both viable locally.

### B.17 ENGRAM (arXiv 2511.12960, Nov 2025) - NAME COLLISION

- Design: three typed stores (episodic, semantic, procedural) + a router + plain dense top-k per type, set-merged.
  Claims SOTA LoCoMo and +15 pp over full-context on LongMemEval at ~1% of tokens, explicitly "without knowledge graphs
  or multi-stage retrieval".
- Lesson: (a) an arXiv paper and open code already use the name "ENGRAM" for a memory system - rename or disambiguate
  before publishing; (b) another data point that typed simple retrieval gets most of the gain.

### B.18 Other 2026 systems with LongMemEval_s claims (see table B.20)

- ReFind (2608.12888): agent-controlled lexical search over unmodified chat logs, session-aware rank fusion, local
  context
  expansion, temporal narrowing; 93.2 +/- 3.3 (S) and 89.3 +/- 6.0 (M) with GPT-5-mini, "no LLM-based index
  construction".
- SodaMem (2608.08055): typed FactEvents with mandatory provenance spans, mention/occurrence/validity times,
  SUPERSEDES/CONTRADICTS/UPDATES edges, hybrid lexical-dense index, planner-reader loop; 92.8% (464/500) at $
  0.0016/question
  with deepseek-v4-flash as reader and judge (self-judged).
- LycheeMemory V2 (2608.12990): segment-level consolidation; 92.2% (GPT-4.1-mini); 76-86% cheaper to build than A-MEM.
- LeanMem (2608.03463): routes content to profile / event / verbatim-record stores by compressibility and volatility;
  up to +15.1 pp over baselines at lowest cost (GPT-4.1-mini, Qwen3-8B).
- MemSIF (2608.01742): structured interaction memory + dual-track facts (schema-guided CoreFacts written eagerly;
  ActiveFacts formed on demand and promoted when multiply supported); +2.9-6.2 pp over strongest baseline on S.
- Agent Zero Memory (2608.29606): timeline + entity-event graph + hierarchical documents, "citation lock" (answers may
  cite
  only retrieved evidence); 95.6% LongMemEval, 93.6% LoCoMo; accuracy varies 3.4 pp across eight readers, cost 30x.
- Oracle Agent Memory (2607.13157): database-native (relational, vector, text, graph in one DB); 93.8%, 10.7x fewer
  tokens.
- Wontopos Tablet 2 (2608.23920): dense-only, no lexical, no LLM in retrieval; 95.7% [93.4, 97.1]; notes that changing
  only the reader moves the score 2.0 pp.
- FluctlightDB (2608.12365): 97.6% "retrieval harness" / 97.4% end-to-end with its own reader/judge; authors say
  protocols
  differ from leaderboards - not comparable.
- D2ACCI (2608.17756): 90.9%; contributes paired-bootstrap evaluation, protected slices, and keeps BM25/RRF as a
  monitored feature flag; five ablations +1.9 to +3.7 pp at p <= .003.
- Selective Forgetting graph (2608.28978): graph memory LOSES to a flat vector baseline on LongMemEval (token-F1 0.417
  vs
  0.468, paired bootstrap delta -0.050, 95% CI [-0.085, -0.016]); on questions needing a prior assistant turn, judged
  correctness falls 0.911 -> 0.607 because decomposition "discards the surface form".
- Graph-native bitemporal store on Neo4j (2607.26520): current-state semantic search R@10 46.7% overall; time-travel
  path
  80% on knowledge-update but 37.5% on temporal reasoning (down from 50%) due to "post-filter dilution".
- RippleMem (2608.13334): event-centric graph + associative expansion, +11.9 pp on S over its baseline, 30x cheaper
  graph.
- Supra Cognitive Modes (2607.19096): routed modes; 86.0% LongMemEval; LoCoMo adversarial abstention 68.6%.
- Hindsight Memory-PRM (2608.29605): fixed shared-reader protocol: 79.0% LongMemEval, 77.5% LoCoMo (an apples-to-apples
  reference for what a modest reader achieves).

### B.19 Surveys and meta-evaluations

- "Rethinking Memory in AI" (2505.00675, May 2025, rev. Dec 2025): six operations - consolidation, updating, indexing,
  forgetting, retrieval, compression - a useful checklist for Mnemic's API surface.
- "Memory in the Age of AI Agents" (2512.13564, Dec 2025, rev. Jan 2026): forms (token/parametric/latent) x functions
  (factual/experiential/working) x dynamics (formation/evolution/retrieval); catalogues benchmarks and frameworks.
- "Harness the Memory" (2608.15008, Aug 2026): controlled comparison of substrates (dense/sparse indices, text records,
  structural, hierarchical, refinement, parametric, activation) across four suites and three backbones: no substrate
  dominates; broad retrieval helps factual QA, hurts sequential decision-making; substrates that win at moderate history
  length become costly/unstable at long horizons; recommends substrate routing.
- MemDelta (2606.29914, Jun 2026): confound audit - embedding choice shifts LongMemEval_s by +/-6.2 pp; results reverse
  across Claude/Gemini/GPT-4o-mini readers; Claude Sonnet refused 63% of full-context queries; GPT-4o-mini full-context
  49.8% vs verbatim RAG 47.2%; agent self-managed memory 42% < basic retrieval 47%.

### B.20 SOTA table, LongMemEval_s (end-to-end QA accuracy on 500 questions; self-reported unless noted)

| System                                        | Reader / answer model          | Acc.               | Date     | Notes                                              |
|-----------------------------------------------|--------------------------------|--------------------|----------|----------------------------------------------------|
| Full-context (Zep paper)                      | GPT-4o-mini                    | 55.4               | Jan 2025 | 115k tokens in context                             |
| Full-context (Zep paper; reused by Hindsight) | GPT-4o                         | 60.2               | Jan 2025 |                                                    |
| Full-context (MemDelta)                       | GPT-4o-mini                    | 49.8               | Jun 2026 | controlled re-run; verbatim RAG 47.2               |
| Full-context                                  | gpt-oss-20B                    | 39.0               | Dec 2025 | Hindsight paper                                    |
| Full-context (MemDelta)                       | Gemini 2.5 Flash               | 70.0               | Jun 2026 | RAG with same reader 56.0                          |
| Full-context (MemDelta)                       | Claude Sonnet                  | 14.0               | Jun 2026 | 63% of errors are explicit refusals; RAG 44.7      |
| Oracle retrieval (evidence sessions only)     | GPT-4o                         | 91.8               | Oct 2024 | LongMemEval paper: ceiling for a perfect retriever |
| Zep                                           | GPT-4o                         | 71.2               | Jan 2025 |                                                    |
| Hindsight Memory-PRM (fixed shared reader)    | shared reader                  | 79.0               | Aug 2026 | protocol-controlled                                |
| Supermemory                                   | GPT-4o / GPT-5 / Gemini-3      | 81.6 / 84.6 / 85.2 | 2025     | vendor, as reported in Hindsight                   |
| Hindsight                                     | gpt-oss-20B / 120B / Gemini-3  | 83.6 / 89.0 / 91.4 | Dec 2025 | open code                                          |
| LazyMem-4B                                    | 4B open model                  | 85 (judge)         | Jul 2026 |                                                    |
| post-graph-rag (PostgreSQL bitemporal)        | n/s                            | 85.8               | Aug 2026 |                                                    |
| Supra Cognitive Modes                         | n/s                            | 86.0               | Jul 2026 |                                                    |
| Mi-Memory                                     | n/s                            | 87.5               | Jul 2026 |                                                    |
| D2ACCI                                        | n/s                            | 90.9               | Aug 2026 | paired bootstrap                                   |
| HOM-AIMOS (MutMem paper)                      | n/s                            | 91.8               | Aug 2026 | 459/500                                            |
| Agentic Context Management (Maximem Synap)    | n/s                            | 92.0               | Jul 2026 |                                                    |
| LycheeMemory V2                               | GPT-4.1-mini                   | 92.2               | Aug 2026 |                                                    |
| SodaMem                                       | deepseek-v4-flash (also judge) | 92.8               | Aug 2026 | self-judged                                        |
| ReFind (agent lexical search, no index build) | GPT-5-mini                     | 93.2 +/- 3.3       | Aug 2026 | M: 89.3 +/- 6.0                                    |
| Oracle Agent Memory                           | n/s                            | 93.8               | Jul 2026 |                                                    |
| Agent Zero Memory                             | best of 8 readers              | 95.6               | Aug 2026 | 3.4 pp spread across readers                       |
| Wontopos Tablet 2                             | n/s                            | 95.7 [93.4, 97.1]  | Aug 2026 | dense-only                                         |
| FluctlightDB                                  | own reader/judge               | 97.4               | Jul 2026 | NOT comparable (own protocol)                      |

Reading of the table: (1) a 95% CI on 500 questions is about +/-2.5 pp, so everything from ~92 to ~96 is one cluster;
(2) the reader model alone moves scores by 2-3.4 pp (Wontopos, Agent Zero) and the embedding choice by +/-6 pp (
MemDelta);
(3) the oracle-retrieval ceiling with GPT-4o was 91.8 in 2024, so 2026 systems above it are riding stronger readers as
much as better retrieval; (4) the most informative single row is ReFind: lexical search over raw logs with an agent in
the
loop matches the structured systems. On LongMemEval_s a memory system buys ~30 pp over full-context for GPT-4o-class
readers, but structure per se is not what buys it. No published full-context number for a 1M-context frontier reader
(GPT-5, Gemini 2.5/3 Pro) on LongMemEval_s was found in the fetched sources; MemDelta reports only that Gemini gains
+14 pp from full context relative to its RAG condition, and BEAM / MemoryAgentBench show 1M-context models degrading
with
length (GPT-5-mini 400k: 74.4 AR on MemoryAgentBench).

### B.21 LoCoMo reference numbers (J / LLM-judge; harness differs per row)

| System                          | Reader       | Score       | Source                         |
|---------------------------------|--------------|-------------|--------------------------------|
| Full-context                    | GPT-4o-mini  | 72.9        | Mem0 paper                     |
| Full-context                    | GPT-4.1-mini | 80.6        | Nemori paper                   |
| Mem0 / Mem0g                    | GPT-4o-mini  | 66.9 / 68.4 | Mem0 paper                     |
| Zep (Mem0 harness / Zep re-run) | GPT-4o-mini  | 66.0 / 75.1 | Mem0 paper / Zep blog [vendor] |
| Letta filesystem agent          | GPT-4o-mini  | 74.0        | Letta blog [vendor]            |
| Nemori                          | GPT-4.1-mini | 80.8        | Nemori paper                   |
| MIRIX                           | n/s          | 85.4        | MIRIX paper                    |
| Hindsight                       | Gemini-3     | 89.6        | Hindsight paper                |
| Backboard                       | n/s          | 90.0        | as claimed, cited by Hindsight |
| Agent Zero Memory               | n/s          | 93.6        | Aug 2026                       |
| Memory-R1 (GRPO)                | LLaMA-3.1-8B | 62.7        | Memory-R1 paper (8B reader)    |

LoCoMo is saturated for strong readers (full-context 80.6 with a mini model) and mostly measures the judge and harness.

## B2. Evidence questions 1-12

Confidence labels: [strong] = independent replication or controlled ablation with CIs; [medium] = one controlled
study or consistent pattern across papers; [weak] = single self-reported result or benchmark-specific.

### Q1. Does fact/triple extraction beat retrieving raw chunks / summaries? Under which question types?

- LongMemEval's own ablation (2410.10813, Table 3, Stella retriever, GPT-4o reader): session-level K=V R@5 0.706 / R@10
  0.783; round(turn)-level K=V R@5 0.582 / R@10 0.692; fact-level alone underperforms "due to information loss". Adding
  extracted facts as extra KEYS while keeping the round as the VALUE (K=V+fact) lifts round-level R@5 0.582 -> 0.644 and
  end-to-end QA by ~5.4 pp. Fact decomposition "consistently improves" only the multi-session category. [strong]
- Independent negative results for structure-as-the-store: MemoryAgentBench Mem0 32.6 AR vs BM25 60.5 (2507.05257);
  MemDelta Mem0 72.7 vs verbatim cloud-RAG 73.9, p = 1.0, at 50x cost (2606.29914); graph memory vs flat vectors on
  LongMemEval token-F1 0.417 vs 0.468, delta -0.050, CI [-0.085, -0.016] (2608.28978); Zep loses 14-17 pp on
  single-session-assistant vs full context (2501.13956). [strong]
- Positive results are for HYBRID stores: Nemori (episodes + distilled facts; removing facts -25%, removing episodes
  -11%),
  Hindsight (facts + experiences + entities + beliefs, 91.4), SodaMem (FactEvents with mandatory provenance spans,
  92.8),
  LeanMem (profile + events + verbatim records), MemSIF (interactions + dual-track facts), HippoRAG 2 (passages +
  triples).
  "On the Structural Memory of LLM Agents" (2412.15266): chunks, triples, atomic facts and summaries each win different
  tasks; the MIXED store is most robust to noise; iterative retrieval beats single-shot everywhere. [medium-strong]
- Verdict: facts as an INDEX and as a structured probe help (multi-session, temporal, knowledge-update); facts as the
  ONLY
  store hurt (single-session-assistant, verbatim preferences, anything depending on surface form). ReFind (93.2 +/- 3.3
  with zero index construction) shows most of the gain is recoverable from lexical search over raw logs plus an agent
  loop.

### Q2. Time-stamping / temporal query expansion

- LongMemEval time-aware query expansion (Table 4, temporal subset): round-level R@5 0.421 -> 0.526 (+11.3 pp abs.),
  session-level +6.8 pp, with GPT-4o extracting the time range; Llama-8B "struggles to generate accurate time ranges",
  so
  the benefit depends on a strong extractor. [strong]
- Zep bitemporal edges: temporal-reasoning 45.1 -> 62.4 (GPT-4o) and 36.5 -> 54.1 (mini); KU 78.2 ->
  83.3. [medium; vendor paper]
- Neo4j bitemporal store (2607.26520): time-travel path 80% on KU but drops temporal-reasoning 50 -> 37.5 by
  "post-filter dilution" - a hard time filter applied after semantic top-k starves the candidate set. [weak, n = 60]
- ReFind's "temporal narrowing" is one of its four controls; Hindsight has a temporal filter with date normalization;
  SodaMem separates mention time, occurrence time and validity. [medium]
- Verdict: store both the utterance time and the (possibly imprecise) valid time; expand temporal queries into ranges;
  apply time as a scoring feature or as a pre-filter on a WIDE candidate set, never as a post-filter on top-k.

### Q3. Store facts AND original text; index small units, return surrounding context

- LongMemEval: "K=V+fact" (index facts, return the round) is the best configuration; returning facts only
  loses. [strong]
- Selective-forgetting graph: assistant-turn questions 0.911 -> 0.607 when turns are decomposed. [strong]
- Zep SS-assistant regression (-14 to -17 pp) has the same cause. MemDelta: verbatim RAG SS-assistant 89.3 vs full 80.4.
- ReFind's "local context expansion" (return neighbours of a hit) and SodaMem's mandatory provenance spans, Agent Zero's
  "citation lock", MemIR's split of raw evidence / retrieval cues / truth-bearing claims (2605.25869) all implement
  this.
- Verdict: [strong] Mnemic's observation -> event -> fact lineage with recall returning the source observation window is
  the design the evidence favours. The fact should carry the span offsets.

### Q4. Hybrid lexical+dense vs dense alone; RRF vs learned fusion; rerankers

- Lexical alone is a strong baseline: BM25 60.5 vs dense 53.8-54.6 on MemoryAgentBench AR (2507.05257); ReFind is
  lexical
  only (93.2). ZenBrain notes LoCoMo's substring-F1 favours BM25 (metric artefact). [strong]
- "Harness the Memory" (2608.15008): dense M1 0.540 > sparse M2 0.470 on LoCoMo P4 (Qwen3-8B); structural graph+vector
  hybrid M5 0.648 but 10-100x slower (27.8 s vs 0.33 s). [medium]
- MemDelta: swapping MiniLM for a cloud embedding = +6.2 pp (47.2 -> 53.4), as large as most "architecture"
  claims. [strong]
- Hindsight: dense + BM25 + graph + temporal, RRF, then cross-encoder rerank, then budget cut (91.4). D2ACCI keeps
  "BM25/RRF" as a monitored feature flag with paired-bootstrap gains +1.9-3.7 pp per ablation. Wontopos reaches 95.7
  with
  dense only, so hybrid is not necessary at the top with a strong embedder. [medium]
- No paper in this sweep isolates RRF vs a learned fusion, and none isolates the reranker's contribution with CIs.
  LongMemEval's own retriever ablation (Appendix E.2) exists but could not be extracted from the fetched text. [gap]
- Verdict: hybrid + RRF is safe and cheap (SQLite FTS5 + local vectors), the embedder quality is the bigger lever, and a
  local cross-encoder reranker is worth an ablation but unproven in the literature.

### Q5. Knowledge-update / contradiction handling

- LongMemEval KU is the easiest type for full-context (78.2 GPT-4o) and Zep only adds +5 (83.3); Hindsight 94.9 (
  Gemini-3).
  MemDelta: full-context 71.8 > RAG 62.8 on KU with GPT-4o-mini - retrieval can HURT updates because it surfaces the
  stale value with equal rank. [strong]
- MemoryAgentBench selective forgetting: <= 28% for every method on multi-hop SF; long-context up to 78%
  single-hop. [strong]
- Mechanisms: Zep LLM edge invalidation with newer-wins; Mem0 ADD/UPDATE/DELETE via LLM; SodaMem SUPERSEDES /
  CONTRADICTS /
  UPDATES edges; Neo4j bitemporal 80% KU via time-travel; "When does belief-based memory help" (2606.22030): Bayesian
  belief updating barely beats last-write-wins on standard benchmarks (they rarely contain contradictions) but wins when
  per-observation reliability is estimated from epistemic language; MemOps (2607.12893) finds long-context models weak
  at
  reconstructing ordered memory-state trajectories and session-level retrieval better than turn-level for lifecycle ops.
- Verdict: [medium] deterministic functional-predicate conflicts with valid-time ordering (Mnemic) are what SodaMem and
  Zep
  do with an LLM; returning the conflict as a question is untested in the literature but consistent with the
  reliability-conditional finding. Retrieval must rank the CURRENT fact above superseded ones by default and expose
  history on request.

### Q6. Abstention

- LongMemEval: 30 false-premise questions; scored correct only on refusal. Per-type abstention numbers for baseline
  systems could not be extracted from the fetched text (Figure 3b gives only totals: GPT-4o oracle 0.870 vs S 0.606).
- A-MEM adversarial F1 50.0 vs no-memory baseline 69.2 - adding memory reduced correct abstention (2502.12110). [medium]
- Supra Cognitive Modes: 68.6% LoCoMo adversarial abstention with routed lexical/dense/graph modes (2607.19096). [weak]
- MemDelta: Claude Sonnet refused 63% of full-context queries that were answerable (14.0% accuracy) - abstention
  calibration is reader-dependent and can dominate the score. [strong]
- Agent Zero "citation lock" (answers may cite only retrieved evidence) and SodaMem's citable-evidence loop are the only
  designs explicitly built for grounded refusal; neither reports an abstention-slice number.
- Verdict: [weak-medium] no paper measures a retriever "not found" signal directly. The indirect evidence (A-MEM,
  MemDelta)
  says the reader over-answers when handed plausible-but-wrong evidence, so an explicit "structured miss" plus low-score
  signal is the right lever; Mnemic should measure it on the 30 LongMemEval abstention questions plus its own set,
  because nothing in the literature will.

### Q7. Granularity: turn vs session vs summary vs fact

- LongMemEval: session R@5 0.706 > round 0.582 for retrieval, but rounds read better ("decomposing sessions into rounds
  significantly enhances reading") -> retrieve at session, present at round. [strong]
- MemOps: session-level retrieval > turn-level for lifecycle operations. [medium]
- SeCom (2502.05589): topic segments beat both turn and session on LoCoMo and Long-MT-Bench+; LLMLingua-2 compression as
  denoising helps at all granularities. LycheeMemory V2: segment-level consolidation 92.2 at 76-86% lower build cost
  than
  per-turn. Nemori: episode window 5-40 messages changes accuracy < 1 pp. [medium]
- Retain-or-Consolidate (2607.17545): consolidation wins by up to +48 pp absolute when the token budget is tight and
  LOSES to raw retention when the budget is generous; merge/abstract across notes beats local rewrite. [medium]
- Verdict: index at segment/session, return turn windows, keep summaries as a budget fallback, not as the primary store.

### Q8. Entity resolution / coreference - measured effect

- Only one quantified result found: dedup-based consolidation 97.2% retention precision, 58% store reduction, +21.8 pp
  over baseline (VSCode issue dataset) and +13.3 pp preference recall on LongMemEval_s (2605.08538). [weak]
- Zep does embedding + full-text candidate search then LLM merge; no ablation. MemIR (2605.25869) shows typed
  provenance (user vs assistant vs third party) improves source-tracking tasks, no numbers in abstract. DeepRefine
  (2605.10488) lists unresolved coreference as a compounding KB defect.
- Verdict: [weak] no controlled measurement; the literature treats resolution as necessary hygiene. Mnemic's predicate
  domain/range typing plus lexicon is a deterministic substitute; measure it with the synthetic gold set.

### Q9. Write policy: per turn vs end of session vs explicit; sleep-time; consolidation

- Per-turn LLM write is the cost sink: Mem0 1,602 calls / 1.69M tokens per LoCoMo conversation vs Nemori 373 / 323k
  (2508.03341); MemDelta Mem0 write path ~120 min, 1,000+ calls, $0.50+ per instance vs $0.01 for RAG (
  2606.29914). [strong]
- Sleep-time compute (2504.13171): ~5x less test-time compute, +13-18 pp on stateful GSM/AIME, 2.5x amortization across
  related queries; benefit tracks query predictability. LightMem: offline consolidation, 38x fewer tokens, +7.7
  pp. [strong]
- Segment/session-level batching beats per-turn (LycheeMemory, SeCom, MemOps). Retain-or-Consolidate: consolidate only
  under budget pressure. MemSIF: eager schema-guided CoreFacts + lazy ActiveFacts promoted when multiply
  supported. [medium]
- Verdict: [strong] write raw observations synchronously and cheaply; derive facts at segment/session boundaries; run
  consolidation deferred. Mnemic's "consolidate" is well supported; the caller-proposed extraction should be batched.

### Q10. Confidence / decay: does forgetting help or hurt?

- Decay-based systems are the WORST rows in independent tables: MemoryBank (Ebbinghaus) F1 5.0-9.7 per category vs 40.4
  for the no-memory LoCoMo baseline in A-MEM's harness (2502.12110). This is the most likely source of the earlier run's
  "forgetting-curve management drops task performance" finding; a second candidate is CAMeR (2607.20458): "time-driven
  baselines (Oblivion, SuperLocalMemory) collapse to near-zero weights over 100 rounds" and "the keyword gate, not
  learnable decay, is the primary performance driver". [medium]
- Neutral results: pruning 9.8% of a 27k-node graph by recency/frequency/degree/age changed token-F1 by +0.001
  (CI [-0.015, +0.016]) and judged correctness by -1.6 (2608.28978); ZenBrain NoDecay ablation costs P@5 0.002. [medium]
- Nemori: learned "surprise" beats heuristic importance/emotion scores (73.0 vs 52.0 J). MemoryAgentBench: selective
  forgetting is unsolved (<= 28%). D2ACCI "Forget Guard" +1.9-3.7 pp - i.e. NOT forgetting helps. [medium]
- Verdict: [medium] time-decay of retrievability hurts or does nothing; supersession (valid-time end) is the only
  forgetting
  with evidence behind it. Mnemic's `ended` flag + volatility class is the right primitive; do not add recency decay to
  the ranking beyond a tie-breaker.

### Q11. Multi-hop over memory graphs at personal scale vs retrieving more chunks

- Graph wins: HippoRAG 2 +7 pp associative QA and best AR on MemoryAgentBench (65.1); A-MEM links +17 pp multi-hop F1
  in its own ablation; Mem0g +2 pp (noise); RippleMem +11.9 pp on S over its own baseline; Harness M5 0.648 vs 0.540 at
  10-100x latency. [medium]
- Graph loses or ties: 2608.28978 (-0.05 F1 vs flat vectors, CI excludes 0); Zep on SS-assistant; ReFind's lexical loop
  beats HippoRAG 2 on MemoryAgentBench (58.2 vs 53.2); "Harness" shows k rises monotonically with QA accuracy on LoCoMo,
  i.e. more chunks is the cheap way to more hops; 2412.15266 iterative retrieval beats single-shot everywhere. [strong]
- Keep It InMind (2607.24368): implicit-association questions (tree-nut allergy -> macarons) - six vector/graph/agentic
  systems max 14.4% vs 84.0% with the memory in context; 8x embedding size does not close it. Graph traversal in
  principle
  addresses this; no system yet does. [strong]
- Verdict: at personal scale, 1-2 hop expansion over a typed graph from lexical/dense seeds (HippoRAG-2 style, with
  passages as nodes) is worth having; deep traversal is not. Iterative retrieval by the caller is the proven substitute.

### Q12. Latency / cost per memory operation

- Search: Mem0 p50/p95 0.148/0.200 s, Mem0g 0.476/0.657 s, Zep 0.513/0.778 s (Mem0 harness) or 0.632 s p95 (Zep re-run);
  end-to-end Zep 2.58 s vs full-context 28.9 s (GPT-4o); Nemori 3.05 s vs 5.8 s full-context. Sparse BM25 0.33 s vs
  graph
  hybrid 27.8 s per query (Harness). [strong]
- Write: Mem0 ~$0.50+ and 1,000+ LLM calls per 115k-token history; Nemori 373 calls; RAG $0.01 and 0 calls (MemDelta).
  A-MEM 1.2-2.5k tokens per op. LightMem 30-55x fewer calls than baselines. SodaMem $0.0016 per question end-to-end.
- Verdict: a deterministic server that never calls a model is 2-3 orders of magnitude cheaper on the write path than
  every LLM-managed system, and sub-second search is table stakes.

## C. Implications for Mnemic (ranked)

1. Keep raw observations as the retrieval VALUE; use facts as KEYS and probes. [supports, strong] LongMemEval K=V+fact
   (+5.4 pp; R@5 0.582 -> 0.644), Zep -14 to -17 pp on assistant turns, 2608.28978 assistant-turn 0.911 -> 0.607. The
   fact must carry span offsets into the observation and recall must return the surrounding window.
2. Structured memory is not the headline win; the reader, the embedder and iterative search are. [contradicts a "facts
   beat chunks" premise, strong] ReFind 93.2 +/- 3.3 with no index build; MemDelta +6.2 pp from an embedding swap and
   Mem0 = RAG at 50x cost; MemoryAgentBench BM25 60.5 vs Mem0 32.6. Ship the hybrid text index first and prove each
   structured feature with a paired ablation (D2ACCI protocol) on a fixed reader.
3. Bitemporal facts with an `ended` flag and valid-time precision are justified, but time must be a ranking feature or a
   wide pre-filter, not a post-filter on top-k. [supports with caveat, medium] Zep temporal +17 pp; LongMemEval
   time-aware
   expansion +11.3 pp R@5; Neo4j bitemporal 50 -> 37.5 from post-filter dilution.
4. Conflicts on functional predicates returned as questions is plausible but unmeasured. [weak] Every published system
   auto-resolves with newer-wins (Zep, Mem0, SodaMem); 2606.22030 shows reliability-conditioned updating only pays when
   observations differ in trustworthiness. Default ranking must still prefer the current fact (MemDelta: RAG lost 9 pp
   on
   KU by resurfacing stale values). Measure on LongMemEval KU + MemoryAgentBench SF (currently <= 28% for everyone).
5. Deferred consolidation and batched (segment/session) extraction, never per-turn LLM writes. [supports, strong]
   LightMem 38x tokens / +7.7 pp; sleep-time compute 5x / +13-18 pp; Nemori 4.3x fewer calls than Mem0 at higher
   accuracy;
   Retain-or-Consolidate: consolidate only when the budget is tight.
6. Do not decay retrievability by age; forget only by supersession. [supports, medium] MemoryBank (Ebbinghaus) is the
   worst baseline in A-MEM's table; CAMeR time-decay collapses; pruning by recency/frequency is neutral at best
   (2608.28978); D2ACCI "Forget Guard" +1.9-3.7 pp.
7. Hybrid BM25 + vectors with RRF is the right default; a local cross-encoder reranker is an ablation, not a
   requirement. [supports, medium] Hindsight/D2ACCI/SodaMem use it; Wontopos hits 95.7 dense-only; no paper isolates RRF
   vs learned fusion or the reranker with CIs - Mnemic's ablation would be a contribution.
8. Graph recall: 1-2 hop expansion from seeds with observations as nodes (HippoRAG-2 shape), plus caller-driven
   iterative
   retrieval; not deep traversal. [mixed, medium] +7 pp associativity (HippoRAG 2) vs -5 F1 for triple-only graphs
   (2608.28978) and 10-100x latency (Harness). The implicit-association gap (max 14.4% vs 84%, Keep It InMind) is the
   open problem a predicate registry with typed inference could address - worth a scenario test.
9. Abstention needs its own measurement; the "structured miss" signal is the right idea but has no published evidence
   either way. [weak] A-MEM shows memory can lower correct refusal (50 vs 69); MemDelta shows reader refusal behaviour
   dominates (Sonnet 63% refusals). Use LongMemEval's 30 abstention items plus a larger synthetic set, and report
   precision/recall of "not found" separately from QA accuracy.
10. Evaluation plan: LongMemEval_s is near-saturated (92-96 cluster, +/-2.5 pp CI); report with a fixed open reader
    (Hindsight Memory-PRM style, ~79 with a modest reader; Hindsight 83.6 with gpt-oss-20B), paired bootstrap, per-type
    slices, and write-path cost per instance. Add LongMemEval_m (ReFind 89.3 +/- 6.0), MemoryAgentBench SF, and
    BEAM-100K
    for headroom; treat LoCoMo as a smoke test only (label issues, full-context 80.6 with a mini model). [strong]

Also, done 2026-09-07: the rename. "ENGRAM" (arXiv 2511.12960, Nov 2025, typed episodic/semantic/procedural memory, +15 pp over full-context
on
LongMemEval) is already an open memory system, and 2605.08538 uses "engram maturation" as a mechanism name.

Where evidence is weak or benchmark-specific: Q4 (fusion/reranker isolation), Q6 (abstention signal), Q8 (entity
resolution), and the conflict-as-question policy (Q5) are all untested; and every LongMemEval_s number above 90 is
self-reported on a benchmark where the reader alone moves results 2-3.4 pp.

## References (arXiv id or URL, date)

- LongMemEval 2410.10813 (Oct 2024, ICLR 2025); GitHub xiaowu0162/LongMemEval
- LoCoMo 2402.17753 (Feb 2024); LoCoMo-Plus 2602.10715 (Feb 2026)
- MemoryAgentBench 2507.05257 (Jul 2025, rev. Jun 2026); MemBench 2506.21605 (Jun 2025); PersonaMem 2504.14225 (Apr
  2025);
  MemoryBank 2305.10250 (May 2023); BEAM 2510.27246 (Oct 2025, rev. Feb 2026)
- 2026 benchmarks: MemOps 2607.12893; UTILMEM 2608.30508; Keep It InMind 2607.24368; MEMPROBE 2606.24595; MemFuse
  2608.18704; GroupMemBench 2605.14498; "When Users Don't Ask" 2609.03467; MemSecBench 2607.27080; memory poisoning
  2608.21230
- Zep/Graphiti 2501.13956 (Jan 2025); blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/;
  blog.getzep.com/state-of-the-art-agent-memory/
- Mem0 2504.19413 (Apr 2025); A-MEM 2502.12110 (Feb 2025); MemOS 2507.03724 (Jul 2025), github.com/MemTensor/MemOS;
  MemoryOS 2506.06326 (May 2025); LightMem 2510.18866 (Oct 2025); Nemori 2508.03341 (Aug 2025); Mem-alpha 2509.25911
  (Sep 2025); Memory-R1 2508.19828 (Aug 2025); ReasoningBank 2509.25140 (Sep 2025); MemTool 2507.21428 (Jul 2025);
  HippoRAG 2 2502.14802 (Feb 2025); MIRIX 2507.07957 (Jul 2025); EverMemOS 2601.02163 (Jan 2026); Memoria 2512.12686
  (Dec 2025); H-MEM 2507.22925 (Jul 2025); Hindsight 2512.12818 (Dec 2025); Hindsight Memory-PRM 2608.29605 (Aug 2026);
  ENGRAM 2511.12960 (Nov 2025)
- 2026 systems: ReFind 2608.12888; SodaMem 2608.08055; LycheeMemory V2 2608.12990; LeanMem 2608.03463; MemSIF
  2608.01742;
  Agent Zero Memory 2608.29606; Oracle Agent Memory 2607.13157; Wontopos Tablet 2 2608.23920; FluctlightDB 2608.12365;
  D2ACCI 2608.17756; Selective Forgetting graph 2608.28978; Graph-native bitemporal store 2607.26520; post-graph-rag
  2608.24921; RippleMem 2608.13334; Supra Cognitive Modes 2607.19096; AtomMem 2606.19847; MemIR 2605.25869;
  Nous / belief-based memory 2606.22030; CAMeR 2607.20458; ZenBrain 2604.23878; Human-Inspired Memory Architecture
  2605.08538; ECHO 2608.21755; LazyMem 2607.22690; Mi-Memory 2607.18975; MutMem 2608.02843; Agentic Context Management
  2607.21503
- Method/meta: Sleep-time Compute 2504.13171 (Apr 2025); SeCom 2502.05589 (Feb 2025); Structural Memory of LLM Agents
  2412.15266 (Dec 2024); Retain or Consolidate 2607.17545 (Jul 2026); Harness the Memory 2608.15008 (Aug 2026); MemDelta
  2606.29914 (Jun 2026)
- Surveys: Rethinking Memory in AI 2505.00675 (May 2025); Memory in the Age of AI Agents 2512.13564 (Dec 2025)
- Vendor: letta.com/blog/benchmarking-ai-agent-memory (LoCoMo filesystem agent 74.0%, GPT-4o-mini)
