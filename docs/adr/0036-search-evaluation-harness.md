# 0036 — Search evaluation harness: frozen snapshots, pooled graded judgements, offline replay

**Status.** Accepted (2026-10-30). **Builds on:** [0008](0008-federated-search.md), [0009](0009-ranking.md),
[0012](0012-vector-search-pgvector.md), [0030](0030-guide-evaluation.md)

## Context
Ranking ([0009](0009-ranking.md)) fuses each source's own order (reciprocal-rank fusion, k = 60) with fixed boosts and a
bounded popularity term; vector search ([0012](0012-vector-search-pgvector.md)) keeps catalog rows up to a cosine
distance of 0.65; every query goes to every source. Each of these numbers moves what users see, and a change to any
of them has to be accepted or rejected by measurement, not by a few queries tried by hand.

Live sources make that hard: GitHub, npm, Hugging Face, arXiv and Tavily return a different list next week, so two
runs of the same query compare two inputs, not two rankings. Their data is third-party and stays out of the
repository. Relevance needs a human judgement per result, and judging every result of every variant does not scale.

## Decision
1. **Fixed query set.** `docs/eval/search-queries.json`: 80 queries written before the first run and not tuned on
   results. Groups: navigational (10), MCP server for a task (12), model for a task (10), library for a language (10),
   paper (8), news and events (8), Italian and other languages (10), typos and synonyms (8), nonsense control (4).
   The guide questions of [0030](0030-guide-evaluation.md) that fit are reused (`from` names the guide id).
   `docs/eval/semantic-gold.json` adds 20 paraphrase queries with few words in common with their answers, for vector
   search and its cutoff.
2. **Snapshots, recorded once.** `./gradlew evalRecord` (JUnit tag `eval-record`) boots the application with the
   repo-root `.env` and runs each query once through the production `SearchResources` with every configured source,
   no cache and no catalog writes. It keeps each source's ranked list (rank, URL, canonical and repository URL, title,
   description, type, audience, tags, popularity), its outcome and latency, and, when vector search is on, the 50
   nearest catalog vectors with their distance and no cutoff. One JSON file per query in `backend/eval-data/`
   (git-ignored). A query with a file is not recorded again: the snapshot set stays frozen until its folder is deleted.
3. **Offline replay.** The evaluation runs production `ResultMerger.merge` and `Ranking.rank` on the snapshots: no
   network, same input, same order. A variant is a different `Ranking`, a different merge, or a source left out.
4. **Pooled graded judgements.** `docs/eval/search-qrels.tsv` (`query_id`, `url`, `grade` 0–3: 0 not relevant,
   1 marginal, 2 relevant, 3 the answer). The pool to judge is the union of the top 20 of the baseline and of every
   variant, plus the 10 nearest vectors at any distance; `evalTest` writes the unjudged part to
   `backend/eval-data/search-pool.tsv`. A grade attaches to every URL of a merged result (identity, each source's URL
   or repository), so a result found by two sources is judged once. A second judge grades a random 20 % of the pool
   blind; Cohen's kappa between the two is reported with the results. Labels are written by people only; the set
   starts empty.
5. **Metrics, in Kotlin.** nDCG@10 (gain 2^grade − 1), MRR and Recall@20 (relevant = grade ≥ 2), duplicates in the
   top 10 (same name under two URLs), unjudged share of the top 10, and per source the marginal Recall@20 and ΔnDCG@10
   of leaving it out. A comparison between two runs uses a paired bootstrap over queries (10 000 resamples, fixed
   seed) and reports the 95 % confidence interval of the mean difference. TREC run and qrels files are written next to
   the report for ad-hoc analysis with `ranx` or `trec_eval`; no Python runs in the build.
6. **Acceptance rule.** A ranking, merge or routing change is accepted when the 95 % interval of ΔnDCG@10 lies above 0,
   or when it holds quality (interval contains 0, unjudged share in the top 10 not higher) and lowers latency or cost.
7. **Gate.** `./gradlew evalTest` (JUnit tag `eval`) writes `backend/eval-data/search-report.md` and fails when nDCG@10
   falls more than 0.005 below `docs/eval/search-baseline.json`. The baseline names the snapshots and judgements it was
   measured on (fingerprints); with other snapshots or labels, or with no snapshots or no labels at all, the gate is
   skipped with a message, never failed. `H2_EVAL_WRITE_BASELINE=true` stores a new baseline. The default `test` task
   excludes both tags.
8. **Semantic cutoff sweep.** The same report replays the vector cutoff from 0.40 to 0.90 on the recorded neighbours:
   nDCG@10 and Recall@10 on the judged queries, results kept per query, and results kept for the nonsense queries,
   which a good cutoff keeps at 0.

### Routing
Which sources a query is sent to is decided from this harness's ablation data: per source, how often it reaches the
top 10, its marginal Recall@20, its ΔnDCG@10 with interval, and its p50 latency, per query group. A source whose
absence costs no measurable quality for a group is not called for that group.

## Consequences
- Every ranking and routing change is compared on the same frozen input with the same labels, and the report states
  the uncertainty, not only the difference.
- Third-party data never enters git: snapshots, pools and reports live in `backend/eval-data/` on the machine that
  recorded them, and are deleted with it. The query set and the labels (URLs and grades) are committed.
- Snapshots age: a frozen set measures ranking, not today's sources. Re-recording changes the input, so the baseline
  and the judging pool are redone with it.
- Pooled judging leaves results no variant put in its top 20 unjudged; a new variant can surface them, which the
  unjudged share of the top 10 shows. The pool is extended before such a variant is compared.
- Labelling is human work: about 80 queries × 25–30 pooled results, roughly 5 hours for one judge, plus 20 % for the
  second.
- Recording needs the real stack: a catalog database with the index to measure, the GitHub token, the Tavily key and
  the embedding key. Tavily credits are spent once per recorded query.

## Alternatives considered
- **Live runs per variant** — the sources change between runs, so a difference mixes ranking and data.
- **Python (ranx, pytrec_eval) in the build** — a second toolchain for about 150 lines of metrics; TREC files keep
  them available ad hoc.
- **A model as judge** — it shares the blind spots of the models the product uses; useful later only once checked
  against human labels on this set.
- **Click logs (online evaluation)** — no click data and no retention decision for query logs yet.

## Code
- `backend/src/test/kotlin/ai/helper2/eval/` — `SearchSnapshot.kt` (snapshot, recording, replay),
  `SearchEvaluation.kt` (scores, ablation, pool, report, TREC export), `RelevanceMetrics.kt`, `CutoffSweep.kt`,
  `SearchSnapshotRecorder.kt` (`eval-record`), `SearchRelevanceEvalTest.kt` (`eval`, gate), `EvalFiles.kt`.
- `backend/build.gradle.kts` — tasks `evalRecord` and `evalTest`; `test` excludes both tags.
- `docs/eval/search-queries.json`, `docs/eval/semantic-gold.json`, `docs/eval/search-qrels.tsv`,
  `docs/eval/README.md` (how to record, judge and compare).

## Diagrams
[flow](../diagrams/flow-0036-search-evaluation-harness.svg) · [sequence](../diagrams/sequence-0036-search-evaluation-harness.svg)
