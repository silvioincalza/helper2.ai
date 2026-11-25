# Evaluation sets

Every set here is fixed before the first run and not tuned on results. Labels (grades, 0/1 judgements) are decided
by people only (a model may propose a grade; a person confirms or corrects each one, ADR 0046); a file that holds only its header has no labels yet, and the test or script that reads it skips
its relevance part with a message.

| File | What | Read by |
|---|---|---|
| `search-queries.json` | 116 search queries in 11 groups: 84 typed in the box, the 11 home examples (group `home-example`, each with the `path` it is sent with) and 21 everyday queries (group `everyday`, path `USE_AI`) ([ADR 0046](../docs/architecture/decisions/ADR-0046-evaluation-guide-and-search.md)) | `./gradlew evalRecord`, `evalTest` |
| `semantic-gold.json` | 20 paraphrase queries for vector search and its cutoff (ADR 0024, 0046) | same |
| `search-qrels.tsv` | graded judgements for both sets: `query_id`, `url`, `grade` 0–3 | `evalTest` |
| `search-qrels-second.tsv` | the second judge's grades of a 20 % sample (same format, optional) | `evalTest` (Cohen's kappa) |
| `search-baseline.json` | nDCG@10 the gate compares with, and the snapshots and labels it was measured on (step 5) | `evalTest` |
| `guide-queries.json` | 60 guide questions: 21 core ones (the nightly run) and 39 marked `extended`; 6 must stay empty (`expectNoPick`, with a `why`) ([ADR 0046](../docs/architecture/decisions/ADR-0046-evaluation-guide-and-search.md)) | `scripts/eval-guide.mjs` (`--core`: the 21; the nightly run fails below a pass share of 0.8, `--min-pass 0.8`) |
| `guide-intents.json` | 19 intent cases and 7 ask-back cases with pass rules ([ADR 0031](../docs/architecture/decisions/ADR-0031-guide-pipeline.md), intents §10) | `scripts/eval-guide.mjs --intents` |
| `guide-adversarial.json` | 13 hostile questions | `scripts/eval-guide.mjs --adversarial` |
| `guide-qrels.tsv` | graded judgements of guide picks: `query_id` (guide or intent case id), `url`, `grade` 0–3 | `scripts/eval-guide.mjs` (P@3, nDCG@3) |
| `guide-similar.json` | 55 question pairs for similar-question reuse: 25 that should share an answer, 30 near misses that must not, each side with the reading a correct plan gives it ([ADR 0032](../docs/architecture/decisions/ADR-0032-guide-cost-guard-and-answer-cache.md)) | `GuideSimilarEvaluationTest` (in `test`) |
| `guide-similar-vectors.json` | the query embeddings of those questions, recorded once | written by `./gradlew evalRecord --tests '*GuideSimilarRecorder'` |
| `classifier-labels.tsv` | items labelled AI-related or not, for `Classification.isAiRelated` | `ClassificationLabelsTest` |
| `research-labels.tsv` | review candidates the admin decided (keep or reject), with the catalog values of the kept ones ([ADR 0048](../docs/architecture/decisions/ADR-0048-review-research-assistant.md) §10) | `ResearchEvalRecorder` (`evalRecord`) |
| `research-labels-manual.tsv` | open candidates a person labelled by hand, same columns | same |
| `research-report-<model>.md`, `research-baseline-<model>.json` | the last research eval of each model | written by `ResearchEvalRecorder` |
| `setup-resources.json` | 15 resources for the setup guide ([ADR 0034](../docs/architecture/decisions/ADR-0034-setup-guide.md)) | `scripts/eval-setup.mjs` |

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

1. **Record once.** With the catalog database to measure (`SA_DATABASE_URL`), `SA_GITHUB_TOKEN`, `SA_LIVE_WEB_API_KEY` and
   `SA_NVIDIA_API_KEY` in the repo-root `.env`: `cd backend && ./gradlew evalRecord`. One live search per query,
   135 queries, about 11 minutes with the default 4 s pause (`SA_EVAL_PAUSE`, an ISO duration such as `PT4S`; the guide script has its own pause,
   `SA_EVAL_PAUSE_MS`, in milliseconds). Queries that already have a snapshot are
   skipped; delete `backend/eval-data/search-snapshots/` to record a new set.
   **Starter set.** To judge less first, record every fourth query of `search-queries.json` (20 queries, all nine
   groups, chosen by position, not by result): `SA_EVAL_ONLY=nav01,nav05,nav09,mcp03,mcp07,mcp11,mod03,mod07,lib01,lib05,lib09,pap03,pap07,new03,new07,lng03,lng07,typ01,typ05,non01 ./gradlew evalRecord`.
   The metrics then cover these 20; the rest are recorded and judged later in the same way.
2. **Pool.** `./gradlew evalTest` writes `backend/eval-data/search-pool.tsv`: every result in the top 20 of the baseline
   or of any source left out, plus the 10 nearest vectors, that has no grade yet.
3. **Judge.** Fill the `grade` column, keep the first three columns and append them to `search-qrels.tsv`. Expect
   25–30 pooled results per query; at about 10 s per result, 80 search queries take about 5 hours, the 11 home examples
   about 45 minutes, the 21 everyday queries about 1.5 hours, the 20 semantic queries about 1.5 hours.
4. **Second judge.** Pick 20 % of the pooled lines at random (`shuf -n` over the pool), have a second person grade them
   without seeing the first grades, and save them as `search-qrels-second.tsv`. The report states Cohen's kappa; below
   0.6, agree on the grade definitions above and re-judge the disagreements.
5. **Baseline.** `SA_EVAL_WRITE_BASELINE=true ./gradlew evalTest` stores `search-baseline.json`. From then on
   `./gradlew evalTest` fails when nDCG@10 drops more than 0.005 below it.
6. **Compare a change.** Run `evalTest` before and after; the report has per-source ablations with a 95 % paired
   bootstrap interval. TREC files (`baseline.run`, `without-<source>.run`, `search.qrels`) work with
   `ranx` or `trec_eval` for any other comparison. Their score column follows the order users see, lanes included
   (ADR 0025 §8), because both tools rank by score and ignore the rank column. Accept per ADR 0046 §17. A change that brings new results into the
   top 20 extends the pool: judge them before comparing.

## Home examples (`home-example`)

The examples under the home search box are the first search many visitors make, so each one is in the set with the
words and the `path` it sends (`frontend/src/lib/examples.ts`). A change to the pool changes this group in the same
pull request. Judge them like any query. The bar for an example is higher than nDCG: a result graded 3 or 2 in its
top 3. The report lists each `hex` query with its best grade in the top 3 (section "Home examples"); one that loses
its top-3 answer after a ranking change is a reason to stop that change, or to take the example out of the pool.

## Everyday queries (`everyday`)

The first 80 queries are mostly what a developer types. The everyday group adds 20 in the plain words of someone who
uses AI apps and does not build them ("translate a PDF", "make a logo", "free alternative to ChatGPT"), with a few typos,
short forms and Italian or German. They are searched on the `USE_AI` path. The report shows them as their own group,
so a ranking change cannot help developers and hurt this slice without it showing.

## Guide relevance (P@3, nDCG@3)

`scripts/eval-guide.mjs` writes the AI picks no judge has graded to `backend/eval-data/guide-pool.tsv` (origin
`pick`), and for every question with no answer the 24 results of its own search terms (origin `candidate <rank>`).
Grade them with the table above and append the first three columns to `guide-qrels.tsv`. A first run of the 50
questions leaves about 100 picks and, for each question with no answer, up to 24 candidates: count on 2–3 hours.
Later runs only add what changed. Then `node scripts/eval-guide.mjs --rescore` rebuilds the report with the new
grades from the saved answers (`backend/eval-data/guide-runs.json`), with no new request.

## Guide: run and reports

`scripts/eval-guide.mjs` writes `guide-run.md` (the core questions), `guide-adversarial-run.md` (`--adversarial`) and
`guide-intents-run.md` (`--intents`); `scripts/eval-setup.mjs` writes `setup-run.md`. Each report states its date, address,
models and how to run it. In `guide-run.md` the section under "Notes on this run" is hand-written and kept by the script;
the rest is regenerated. A new run overwrites the report; git keeps the earlier ones.

## Guide: the clean baseline

The baseline decides whether query fan-out (C) or an extra guide round (D) get built at all, so it runs once, clean,
on staging, before either. Never on prod.

Before the run, for that day only, in staging's `app.env` or chart values (back to the usual values the day after):

| Setting | For the run | Staging today | Why |
|---|---|---|---|
| `SA_GUIDE_CACHE_TTL` (`app.guideCacheTtl`) | `PT1S` | `PT1S` | every answer is a real model call |
| `SA_GUIDE_DAILY_MODEL_CALLS` (`app.dailyLimits.guideModelCalls`) | 1000 | 600 | 50 questions x 3 runs x 3 calls = 450, up to 900 with retries |
| `SA_GUIDE_CLIENT_DAILY_QUESTIONS` (`app.dailyLimits.guideQuestions`) | at least 200 | 1000 | 150 questions from one address |
| `SA_LIVE_WEB_GUEST_GUIDE` (`app.dailyLimits.webSearches.guide`) | at least 200 | 1000 | a question that needs the web must not meet the per-visitor limit of 3 |
| `SA_LIVE_WEB_GUEST_SEARCH` (`app.dailyLimits.webSearches.search`) | at least 200 | 1000 | the pool searches of questions with no answer |
| `SA_LIVE_WEB_DAILY_CREDITS` (`app.dailyLimits.webSearchCredits`) | 400 | 180 | web searches of the guide and of the pool searches |

The backend needs its GitHub token (`SA_GITHUB_TOKEN`): the script keeps to 25 live GitHub searches a minute, which only
an authenticated token allows. Start after the nightly run (01:41 UTC) has finished.

The run, from the repository root (about 45 minutes):

```
node scripts/eval-guide.mjs "$SA_STAGING_URL" --repeat 3
```

Then:

1. Grade `backend/eval-data/guide-pool.tsv` with the grades above: the AI picks, and the
   candidates of every question with no answer. Append the first three columns to `eval/guide-qrels.tsv`,
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

## Guide: how to read a run

- "Answered" means the model gave an answer of the question's kind: an AI pick, a comparison, an explanation, or a dated record. "Expected empty" questions pass when they stay empty; each one says why in the query file.
- "Checks confirmed" is confirmed / total constraint checks over the AI picks. Not confirmed means the resource's own text does not show it, not that it is false.
- "(cache)" marks an answer served from the cache (the backend was not run with `SA_GUIDE_CACHE_TTL=PT1S`); its latency is left out of the percentiles.
- P@3 is the share of the first three AI picks graded relevant (2 or 3) in [guide-qrels.tsv](guide-qrels.tsv); nDCG@3 weighs them by grade. Picks not graded yet are written to `backend/eval-data/guide-pool.tsv` and count as not relevant.
- "Candidates graded relevant": for each question with no answer, the script sends the answer's search terms to `GET /api/v1/search` (24 results) and writes them to the pool. It is close to the guide's own 24 candidates, not the same list (no intent filter, sort or web query). Once graded, it tells a retrieval problem (no good candidate) from a choosing problem (a good candidate the model left out).
- A flip is a question answered in one run and not in another; "top-flip" is a first AI pick that differs between runs.
- "Kept" counts only AI-reasoned picks that passed the evidence check. Plain search results shown as a fallback count 0.
- "Top pick" is the first AI-reasoned pick; "no AI pick" means the model chose nothing or everything was rejected.
- `b14` is in Italian, to check that the planning model turns any language into English search terms.

## Review research (`research-labels.tsv`)

The verdict labels are the admin's own decisions in the review queue. The expected values of a kept item are the
catalog's values of the resource it became (type, URL, title, licence, pricing, open source): they come from the
sources, not from a person, so a miss on them is a reason to look, not a proof. Columns: `id`, `item_url`, `kind`,
`title_found`, `type_found`, `decision` (KEEP, REJECT or DUPLICATE; empty: not labelled, skipped), then the expected
values, empty when unknown.

1. **Export the decisions.** Read-only, from the database with the most decisions (prod):
   `{ echo 'BEGIN READ ONLY;'; cat scripts/research-eval-labels.sql; echo 'COMMIT;'; } | kubectl -n searchavo-prod exec -i postgres-0 -- sh -c 'psql -q -U "$POSTGRES_USER" -d "${POSTGRES_DB:-$POSTGRES_USER}"' > eval/research-labels.tsv`.
   It holds public page URLs and titles only.
2. **Label more by hand.** The queue has few rejects. The same command with `scripts/research-eval-pool.sql` writes
   120 open candidates, about 14 per type, to `backend/eval-data/research-pool.tsv`. Decide each one as you would in
   the queue, fill the expected values you can check on the page, and move the labelled rows to
   `research-labels-manual.tsv`. The set decides nothing below 100 items and 20 rejects.
3. **Run.** In the repo-root `.env`: `SA_NEBIUS_API_KEY`, `SA_LIVE_WEB_API_KEY`, `SA_GITHUB_TOKEN`. Then, once per model:
   `cd backend && SA_RESEARCH_MODEL=FAST SA_RESEARCH_DAILY_PAGE_READS=1000 ./gradlew :apps:backoffice:evalRecord --tests '*ResearchEvalRecorder'`
   and the same with `SA_RESEARCH_MODEL=REASONING`. Each item takes up to 14 model calls and spends live web credits.
   `SA_EVAL_ONLY=r001,r002` runs some items only; `SA_EVAL_PAUSE` (default `PT2S`) is the pause between items. Without
   a model key the test is skipped.
4. **Read.** `eval/research-report-<model>.md` (the numbers and each item), `research-baseline-<model>.json`, and
   every step of every run in `backend/eval-data/research-traces/<model>/`.

## Classifier (`classifier-labels.tsv`)

Columns: `source` (github, npm, hackernews, live-web), `title`, `description`, `labels` (topics or keywords,
comma-separated), `ai_related` (1, 0, or empty while not judged). A Hacker News row has no description: the source
checks the title only. Take about 200 items as the sources return them, before the AI gate: about 70 from GitHub
search, 70 from npm search and 60 from a web source (Hacker News or Tavily), for the queries of `search-queries.json`, and
label each by hand (about 1.5 hours). Then add two lines with the floor the rules must keep, for example
`# min-precision 0.90` and `# min-recall 0.80`, chosen from the first measurement. `ClassificationLabelsTest` (part of
`test`) then fails when a rule change goes below them.
