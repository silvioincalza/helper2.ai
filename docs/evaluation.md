# Evaluation

How search and the guide are measured, and how a guide run is made and read. Every ranking, merge, routing or prompt change is
judged the same way: on fixed sets, with labels given by people, against a stored baseline. The decisions are in
[ADR 0046](adr/operations/0046-evaluation-guide-and-search.md); the files and the judging steps are in [eval/README.md](eval/README.md).

## How search and the guide are measured

### Query sets

Every set is fixed before its first run and never tuned on results.

| Set | File | Size |
|---|---|---|
| Search | `docs/eval/search-queries.json` | 115 queries in 11 groups: navigational, MCP server for a task, model for a task, library for a language, paper, news and events, Italian and other languages, typos and synonyms, nonsense control, the 11 home examples and 20 everyday tasks in plain words (the last two sent with their audience `path`) |
| Search by meaning | `docs/eval/semantic-gold.json` | 20 paraphrase queries with few words in common with their answers, for vector search and its cutoff |
| Guide | `docs/eval/guide-queries.json` | 51 questions: 21 core ones (the nightly run) and 30 marked `extended`; 6 must stay empty |
| Guide intents | `docs/eval/guide-intents.json` | 19 intent cases and 7 ask-back cases, each with its pass rule |
| Guide, hostile questions | `docs/eval/guide-adversarial.json` | 13 hostile questions |
| Similar questions | `docs/eval/guide-similar.json` | 55 question pairs: 25 that should share an answer, 30 near misses that must not |
| Setup guide | `docs/eval/setup-resources.json` | 15 resources |

### Labels

Labels are decided by people only. A model may propose a grade; a person confirms or corrects each one. A second
person grades a random 20 % of the search pool without seeing the first grades, and the report states Cohen's kappa
between the two.

| Grade | Meaning |
|---|---|
| 3 | The answer: what an expert would hand over first for this query. |
| 2 | Relevant: does what the query asks; a user would be glad to see it. |
| 1 | Marginal: on topic but not what was asked (wrong type, wrong language, a fork, a tutorial instead of the tool). |
| 0 | Not relevant, spam, or a dead link. |

Search grades are in `docs/eval/search-qrels.tsv` (`query_id`, `url`, `grade`), guide grades in
`docs/eval/guide-qrels.tsv`. The pool to judge is the top 20 of the baseline and of every variant, plus the 10
nearest vectors; `evalTest` writes the part with no grade to `backend/eval-data/search-pool.tsv`. A file that holds
only its header has no labels: the test or script that reads it skips its relevance part with a message.

### Metrics

| What | Metric |
|---|---|
| Search | nDCG@10 (gain 2^grade − 1), MRR and Recall@20 (relevant = grade 2 or 3), duplicates in the top 10, the unjudged share of the top 10, and per source the marginal Recall@20 and the ΔnDCG@10 of leaving it out |
| A search change against the baseline | paired bootstrap over queries (10,000 resamples, fixed seed): the 95 % interval of the mean ΔnDCG@10 |
| Home examples | a result graded 3 or 2 in the top 3 of each example |
| Search by meaning | nDCG@10 and Recall@10 for each vector cutoff from 0.40 to 0.90 |
| Guide | answer rate, questions that must stay empty and did, P@3 and nDCG@3 of the AI picks, the share of unanswered questions with a candidate graded relevant, picks kept and rejected by the validator, reason and constraint checks, latency p50 / p90 / max, 429s, flips between runs |
| Hostile questions | pass or fail per case: a forbidden text in an AI-written field fails it |
| Similar questions | no false reuse at the threshold, on recorded embeddings |

### The gate a change must pass

- **Search, in every pull request:** `RankingBaselineTest` (part of `test`) fails when `Ranking.kt`,
  `ResultMerger.kt`, `QueryAnalyzer.kt` or `Vitality.kt` changed since `docs/eval/search-baseline.json` was
  measured. So a ranking change merges only with a new measurement committed with it.
- **Search, on the recorded snapshots:** `./gradlew evalTest` fails when nDCG@10 falls more than 0.005 below the
  baseline. The snapshots hold third-party data and stay in `backend/eval-data/` (git-ignored), so this gate runs on
  the workstation, not in CI. With no snapshots, labels or baseline it is skipped with a message.
- **Search, to accept a change:** the 95 % interval of ΔnDCG@10 lies above 0; or quality holds (the interval
  contains 0, the unjudged share of the top 10 is not higher) and latency or cost go down. Never while one group of
  queries is lower with an interval below 0.
- **Guide, every night on staging** (`guide-eval.yml`, never on a pull request, never on prod): the core set fails
  below a pass share of 0.8 (`--core --min-pass 0.8`); the hostile set fails on any case.
- **Similar-question reuse and the AI classifier, in every pull request:** `GuideSimilarEvaluationTest` and
  `ClassificationLabelsTest` are part of `test`.

### Record a snapshot

1. With the catalog database to measure (`H2_DATABASE_URL`), `H2_GITHUB_TOKEN`, `H2_LIVE_WEB_API_KEY` and
   `H2_NVIDIA_API_KEY` in the repository-root `.env`: `cd backend && ./gradlew evalRecord`. One live search per
   query through the production `SearchResources`, with no cache and no catalog write; one JSON file per query in
   `backend/eval-data/search-snapshots/`. A query that has a file is not recorded again: delete the folder to record
   a new set.
2. `./gradlew evalTest` replays the snapshots offline through the production merge and ranking and writes the pool.
3. Judge the pool with the grades above and append the first three columns to `docs/eval/search-qrels.tsv`.
4. `H2_EVAL_WRITE_BASELINE=true ./gradlew evalTest` stores `docs/eval/search-baseline.json`: nDCG@10 and the
   fingerprints of the snapshots, the labels and the ranking code it was measured on. A new recording comes with a
   new baseline and a new pool.

Open work: [S10.4.2](https://github.com/silvioincalza/helper2.ai/issues/348) (the first ten results of the snapshot set graded) and
[S10.4.3](https://github.com/silvioincalza/helper2.ai/issues/349) (guide relevance judged by a second person).

## The guide run

No run is recorded in this repository yet: the first one is made after the release 2026.11.3. A run reports the
figures of "Metrics" above, per question and in total.

### How to run

`node scripts/eval-guide.mjs <baseUrl>` against a backend started with `H2_GUIDE_CACHE_TTL=PT1S` (staging), so every answer is a real model call and the questions are sent exactly as written.
`--core` runs the 21 core questions only (the nightly run); `--repeat <n>` runs the set n times to count flips.
Each question counts against the per-address daily quota (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`) and spends about 3 of the guide's daily model calls (`H2_GUIDE_DAILY_MODEL_CALLS`); the backend under test sets both to cover the run (see "Clean baseline" below).
`--intents` runs the intent cases (it writes `docs/evaluation-intents.md`), `--adversarial` the hostile questions (it writes `docs/evaluation-adversarial.md`).
The script stops and leaves its report unchanged on the first answer with the `GUIDE_BUDGET_SPENT` notice (the guide's model budget is used up; exit code 3; the message says when it opens again), on the first with `GUIDE_NETWORK_QUOTA` (the runner's network has used its questions; exit code 4) or `GUIDE_DAILY_QUOTA` (the address has; exit code 1), or after 3 answers in a row without a model and without such a notice (the provider is down; exit code 1). Each log line names the notices of its answer.
In the report, the section under "Notes on this run" is hand-written and kept by the script; the rest is regenerated.

### Clean baseline

The baseline decides whether query fan-out (C) or an extra guide round (D) get built at all, so it runs once, clean,
on staging, before either. Never on prod.

Before the run, for that day only, in staging's `app.env` or chart values (back to the usual values the day after):

| Setting | For the run | Staging today | Why |
|---|---|---|---|
| `H2_GUIDE_CACHE_TTL` (`app.guideCacheTtl`) | `PT1S` | `PT1S` | every answer is a real model call |
| `H2_GUIDE_DAILY_MODEL_CALLS` (`app.dailyLimits.guideModelCalls`) | 1000 | 600 | 50 questions x 3 runs x 3 calls = 450, up to 900 with retries |
| `H2_GUIDE_CLIENT_DAILY_QUESTIONS` (`app.dailyLimits.guideQuestions`) | at least 200 | 1000 | 150 questions from one address |
| `H2_LIVE_WEB_GUEST_GUIDE` (`app.dailyLimits.webSearches.guide`) | at least 200 | 1000 | a question that needs the web must not meet the per-visitor limit of 3 |
| `H2_LIVE_WEB_GUEST_SEARCH` (`app.dailyLimits.webSearches.search`) | at least 200 | 1000 | the pool searches of questions with no answer |
| `H2_LIVE_WEB_DAILY_CREDITS` (`app.dailyLimits.webSearchCredits`) | 400 | 180 | web searches of the guide and of the pool searches |

The backend needs its GitHub token (`H2_GITHUB_TOKEN`): the script keeps to 25 live GitHub searches a minute, which only
an authenticated token allows. Start after the nightly run (01:41 UTC) has finished.

The run, from the repository root (about 45 minutes):

```
node scripts/eval-guide.mjs "$H2_STAGING_URL" --repeat 3
```

Then:

1. Grade `backend/eval-data/guide-pool.tsv` with the grades of [eval/README.md](eval/README.md): the AI picks, and the
   candidates of every question with no answer. Append the first three columns to `docs/eval/guide-qrels.tsv`,
   then delete the pool: the script never overwrites a pool with grades in it.
2. `node scripts/eval-guide.mjs --rescore` rebuilds this report from the saved answers
   (`backend/eval-data/guide-runs.json`) with the new grades, with no new request.
3. Write the notes.

What the figures decide:

- **Choosing or retrieval.** When most questions with no answer already have a candidate graded relevant, the guide
  found the resource and did not pick it: fix the chooser and the planner's wording, not C or D. When few have one,
  retrieval is the gap and C is worth trying.
- **Noise.** A change counts only when it is bigger than the flips between the baseline's own runs.
- **C (fan-out)** goes ahead only if, on the same set and settings: the answer rate goes up, P@3 does not go down, the
  share of relevant candidates goes up, p90 grows by 1.5 s at most, and there are zero 429s.
- **D (extra round)** goes ahead only if: the answer rate goes up, P@3 does not go down, there are zero 429s, and the
  p90 of the questions that trigger the extra round, reported on its own, stays under the target set before D is built.

### How to read a run

- "Answered" means the model gave an answer of the question's kind: an AI pick, a comparison, an explanation, or a dated record. "Expected empty" questions pass when they stay empty; each one says why in the query file.
- "Checks confirmed" is confirmed / total constraint checks over the AI picks. Not confirmed means the resource's own text does not show it, not that it is false.
- "(cache)" marks an answer served from the cache (the backend was not run with `H2_GUIDE_CACHE_TTL=PT1S`); its latency is left out of the percentiles.
- P@3 is the share of the first three AI picks graded relevant (2 or 3) in [eval/guide-qrels.tsv](eval/guide-qrels.tsv); nDCG@3 weighs them by grade. Picks not graded yet are written to `backend/eval-data/guide-pool.tsv` and count as not relevant.
- "Candidates graded relevant": for each question with no answer, the script sends the answer's search terms to `GET /api/v1/search` (24 results) and writes them to the pool. It is close to the guide's own 24 candidates, not the same list (no intent filter, sort or web query). Once graded, it tells a retrieval problem (no good candidate) from a choosing problem (a good candidate the model left out).
- A flip is a question answered in one run and not in another; "top-flip" is a first AI pick that differs between runs.
- "Kept" counts only AI-reasoned picks that passed the evidence check. Plain search results shown as a fallback count 0.
- "Top pick" is the first AI-reasoned pick; "no AI pick" means the model chose nothing or everything was rejected.
- `b14` is in Italian, to check that the planning model turns any language into English search terms.
