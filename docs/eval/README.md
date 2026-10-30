# Evaluation sets

Every set here is fixed before the first run and not tuned on results. Labels (grades, 0/1 judgements) are written
by people only; a file that holds only its header has no labels yet, and the test or script that reads it skips
its relevance part with a message.

| File | What | Read by |
|---|---|---|
| `search-queries.json` | 80 search queries in 9 groups ([ADR 0036](../adr/0036-search-evaluation-harness.md)) | `./gradlew evalRecord`, `evalTest` |
| `semantic-gold.json` | 20 paraphrase queries for vector search and its cutoff (ADR 0012, 0036) | same |
| `search-qrels.tsv` | graded judgements for both sets: `query_id`, `url`, `grade` 0–3 | `evalTest` |
| `search-qrels-second.tsv` | the second judge's grades of a 20 % sample (same format, optional) | `evalTest` (Cohen's kappa) |
| `search-baseline.json` | nDCG@10 the gate compares with, and the snapshots and labels it was measured on | `evalTest` |
| `guide-queries.json` | 20 guide questions ([ADR 0030](../adr/0030-guide-evaluation.md)) | `scripts/eval-guide.mjs` |
| `guide-intents.json` | 16 intent cases and 6 ask-back cases with pass rules ([ADR 0032](../adr/0032-guide-intents.md) §10) | `scripts/eval-guide.mjs --intents` |
| `guide-adversarial.json` | 8 hostile questions | `scripts/eval-guide.mjs --adversarial` |
| `guide-qrels.tsv` | graded judgements of guide picks: `query_id` (guide or intent case id), `url`, `grade` 0–3 | `scripts/eval-guide.mjs` (P@3, nDCG@3) |
| `classifier-labels.tsv` | items labelled AI-related or not, for `Classification.isAiRelated` | `ClassificationLabelsTest` |
| `setup-resources.json` | 15 resources for the setup guide ([ADR 0031](../adr/0031-setup-guide.md)) | `scripts/eval-setup.mjs` |

Snapshots, judging pools, TREC files and reports are third-party data and stay in `backend/eval-data/` (git-ignored).

## Grades

| Grade | Meaning |
|---|---|
| 3 | The answer: what an expert would hand over first for this query. |
| 2 | Relevant: does what the query asks; a user would be glad to see it. |
| 1 | Marginal: on topic but not what was asked (wrong type, wrong language, a fork, a tutorial instead of the tool). |
| 0 | Not relevant, spam, or a dead link. |

Judge from the result's own page (README, model card, package page), not from its title. Nonsense queries have no
relevant result: a 0 for each pooled result is the label.

## Search: record, judge, compare

1. **Record once.** With the catalog database to measure (`H2_DATABASE_URL`), `H2_GITHUB_TOKEN`, `H2_TAVILY_API_KEY` and
   `H2_NVIDIA_API_KEY` in the repo-root `.env`: `cd backend && ./gradlew evalRecord`. One live search per query,
   100 queries, about 8 minutes with the default 4 s pause (`H2_EVAL_PAUSE`). Queries that already have a snapshot are
   skipped; delete `backend/eval-data/search-snapshots/` to record a new set.
2. **Pool.** `./gradlew evalTest` writes `backend/eval-data/search-pool.tsv`: every result in the top 20 of the baseline
   or of any source left out, plus the 10 nearest vectors, that has no grade yet.
3. **Judge.** Fill the `grade` column, keep the first three columns and append them to `search-qrels.tsv`. Expect
   25–30 pooled results per query; at about 10 s per result, 80 search queries take about 5 hours, the 20 semantic
   queries about 1.5 hours.
4. **Second judge.** Pick 20 % of the pooled lines at random (`shuf -n` over the pool), have a second person grade them
   without seeing the first grades, and save them as `search-qrels-second.tsv`. The report states Cohen's kappa; below
   0.6, agree on the grade definitions above and re-judge the disagreements.
5. **Baseline.** `H2_EVAL_WRITE_BASELINE=true ./gradlew evalTest` stores `search-baseline.json`. From then on
   `./gradlew evalTest` fails when nDCG@10 drops more than 0.005 below it.
6. **Compare a change.** Run `evalTest` before and after; the report has per-source ablations with a 95 % paired
   bootstrap interval. TREC files (`baseline.run`, `without-<source>.run`, `search.qrels`) work with
   `ranx` or `trec_eval` for any other comparison. Accept per ADR 0036 §6. A change that brings new results into the
   top 20 extends the pool: judge them before comparing.

## Guide relevance (P@3, nDCG@3)

`scripts/eval-guide.mjs` writes the AI picks no judge has graded to `backend/eval-data/guide-pool.tsv`. Grade them
with the table above and append the first three columns to `guide-qrels.tsv`: about 60 picks per run (20 questions,
up to 3 picks), about 15 minutes. Later runs only add the picks that changed.

## Classifier (`classifier-labels.tsv`)

Columns: `source` (github, npm, tavily), `title`, `description`, `labels` (topics or keywords, comma-separated),
`ai_related` (1, 0, or empty while not judged). Take about 200 items as the sources return them, before the AI gate:
about 70 from GitHub search, 70 from npm search and 60 from Tavily, for the queries of `search-queries.json`, and
label each by hand (about 1.5 hours). Then add two lines with the floor the rules must keep, for example
`# min-precision 0.90` and `# min-recall 0.80`, chosen from the first measurement. `ClassificationLabelsTest` (part of
`test`) then fails when a rule change goes below them.
