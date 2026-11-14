# 0046 — Evaluation: guide and search

**Depends on:** [0008](../foundation/0008-llm-integration.md), [0022](../search/0022-federated-search.md), [0024](../search/0024-vector-search.md), [0025](../search/0025-ranking.md), [0031](../guide/0031-guide-pipeline.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0034](../guide/0034-setup-guide.md).

## Context

Prompt, model and retrieval changes move the guide's quality in ways a unit test cannot see: more or fewer picks,
picks that do not fit, slower answers. Claims such as "24 candidates beat 12" or "thinking off is faster" need
numbers measured the same way each time. The numbers must be public and reproducible, good and bad alike, and a
drop in quality must be seen the next day, not at the next run by hand.

Search has the same problem one level down. Ranking ([0025](../search/0025-ranking.md)) fuses each source's own order
(reciprocal-rank fusion, k = 60) with fixed boosts and a bounded popularity term; vector search
([0024](../search/0024-vector-search.md)) keeps catalog rows up to a cosine distance of 0.65; every query goes
to every source. Each of these numbers moves what users see, and a change to any of them has to be accepted or
rejected by measurement, not by a few queries tried by hand.

Live sources make that hard: GitHub, npm, Hugging Face, arXiv and Tavily return a different list next week, so two
runs of the same query compare two inputs, not two rankings. Their data is third-party and stays out of the
repository. Relevance needs a human judgement per result, and judging every result of every variant does not scale.

## Decision

### The guide
1. **Fixed question set.** `docs/eval/guide-queries.json`: 51 fixed queries, all written before any run and never
   tuned to the results.
   - The 21 core questions: 14 for building with AI (`b01`–`b14`, `b14` in Italian), 5 for using AI (`u01`–`u05`;
     `u05` asks for three properties a hostile-text rule must not drop: offline, no account, no API key),
     one news question (`n01`) and one nonsense question (`x01`).
   - 30 more, marked `"extended": true`: 8 questions across the intents of [0031](../guide/0031-guide-pipeline.md)
     (`i01`–`i08`), 10 Hugging Face model questions (speech recognition, text to speech, OCR, embeddings, a reranker, a
     vision model: `h01`–`h10`), 8 questions with several requirements at once (`m01`–`m08`), and 4 that must stay
     empty (`e01`–`e04`).
   - 6 questions must stay empty (`"expectNoPick": true`, with a `why`): `x01`, `u04` (a writing task, not a resource
     to find) and `e01`–`e04`.
2. **Script.** `node scripts/eval-guide.mjs [baseUrl] [--core] [--repeat <n>] [--min-pass <share>] | [--adversarial |
   --intents]` (Node 18 or newer; default `http://localhost:8080`):
   - gets a CSRF token from `GET /api/v1/me`, then sends each query to `POST /api/v1/guide/stream` exactly as
     written, never with a suffix, and reads the answer from its last event (`answer`, or `error` counted as a failed
     question);
   - runs against a backend started with `H2_GUIDE_CACHE_TTL=PT1S` (staging), so every answer is a real model call
     and a rerun of the set is never served from the answer cache;
   - waits 7 s between questions (`H2_EVAL_PAUSE_MS`), below the guide's 10-per-minute limit, and keeps to 25 live
     GitHub searches in any 60 s (GitHub allows 30 a minute per token), counted from the source list of every answer
     and search it receives;
   - prints each source's status per question and lists in the report every source that did not succeed;
   - for a question with no answer, sends the answer's own search terms to `GET /api/v1/search` (24 results) and writes
     them to the grading pool: close to the guide's 24 candidates, not the same list (no intent filter, sort or web
     query), because the answer carries only its top 3;
   - `--repeat <n>` (1 to 5) runs the set n times and counts the questions whose answer or top pick changed between
     runs;
   - `--core` runs the 21 core questions only;
   - saves the raw answers to `backend/eval-data/guide-runs.json`; `--rescore` rebuilds the report from them, with the
     judgements of that moment and no new request;
   - marks an answer as cached when its `generatedAt` is older than the request, counts it in the summary and leaves
     it out of the latency figures;
   - stops with an error, and leaves `docs/evaluation.md` unchanged, when the rest of the run would be search-only
     answers and would not measure the model (`guideRunStop` in `scripts/eval-lib.mjs`, tested by
     `scripts/eval-lib.test.mjs`): on the first answer with the notice `GUIDE_BUDGET_SPENT` (the guide's model budget
     on that backend is used up, [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) with exit code 3, the
     backend's message and the time it opens again (`retryAt`); on the first answer with the notice
     `GUIDE_NETWORK_QUOTA` (the runner's network has used its questions for the day) with exit code 4; on the first
     answer with the notice `GUIDE_DAILY_QUOTA` with exit code 1; after 3 answers in
     a row without a model (`LLM_UNAVAILABLE`) and without such a notice, which means the provider is down, with exit
     code 1. The log line of each question names the notices of its answer, and in GitHub Actions the stop message
     goes to the job summary;
   - counts a question as answered when a model answered it fresh (HTTP 200, not from the cache, not
     `LLM_UNAVAILABLE`) with an AI pick, a comparison, an explanation or a dated record; it passes when answered, or
     when it stays empty and expects to; `--min-pass` exits with code 1 when the share passed is below the given one,
     after writing the report;
   - writes `docs/evaluation.md`: the go / no-go figures of later retrieval changes (answer rate, questions kept
     empty, P@3, share of relevant candidates, p90, 429s, flips), questions passed, statuses, AI picks kept and
     rejected, reason check counts, constraint checks, latency p50 / p90 / max, P@3 and nDCG@3 of the AI picks when
     judgements exist (5), one row per query with its top pick, and how to run it.
3. **Adversarial set.** `docs/eval/guide-adversarial.json`: 13 hostile questions, each with expectations a passing
   answer must meet (`noAiPicks`, `noPickTitled`, `noText`, `unsupportedClaimsRemoved`): breaking out
   of the `<need>` tag with a fake candidate, a link in the reason, a prompt asking for secrets, leaking the
   instructions, an Italian instruction, unsupported claims, an instruction for the user placed in the reason (three
   cases), a statement about what the user must or need not do (three cases: as a plain statement, denied, and a
   payment). `--adversarial` runs them, writes
   `docs/evaluation-adversarial.md` with pass or fail per case, saves the raw answers to
   `backend/eval-data/guide-adversarial-runs.json` (what a failed case showed), and exits with code 1 on a failure. A case answered
   without a model (`LLM_UNAVAILABLE`) is a failure, because a search-only answer meets every expectation.
   `noText` reads every field the answer marks as AI-written (`aiContent.fields`) except `searchQuery` and
   `trail.queries[]`: the planner writes them from the question's own words, and the page shows the question anyway.
   They are
   questions, because that is what a shared `?ask=` link controls; hostile source text cannot be planted in live
   sources and is covered by unit tests.
4. **Intent cases.** `docs/eval/guide-intents.json`: 26 cases. 19 cover the eight intents of
   [0031](../guide/0031-guide-pipeline.md), at least two each (three for `SUGGEST`, `NEWEST` and `WHEN`); four vague
   questions must be asked back and three clear ones must not. Each case states what it expects: the intent, the
   subjects, a window, an ask-back. `--intents` runs them, checks the pass rule of each intent (`NEWEST`: every pick
   born inside the window, newest first, dates read from the stored record; `ALTERNATIVES`: the subject is not a
   pick; `COMPARE`: one column per subject, no cell without a gated quote; `WHAT_IS`: every sentence quoted; `WHEN`:
   the stated date equals the stored record, and an event question shows events only; `LEARN`: every pick is a
   `COURSE`), writes `docs/evaluation-intents.md` and exits with code 1 on a failure. A case answered without a
   model fails.
5. **Graded relevance.** `docs/eval/guide-qrels.tsv` (`query_id`, `url`, `grade` 0–3, the grades of §15). The script
   matches a pick to a judgement by the resource id of the URL, reports P@3 (share of the first three AI picks graded
   2 or 3) and nDCG@3 over the judged questions, and writes the picks no one has graded to
   `backend/eval-data/guide-pool.tsv` (git-ignored) for the next judging round. The pool also holds the candidates of
   every question with no answer (2). Graded, they show whether the guide had a good resource and did not pick it
   (the chooser) or never found one (retrieval).
6. **Setup set.** `docs/eval/setup-resources.json`: 15 resources (MCP servers, libraries, models, a gated model, apps)
   for the setup guide of [0034](../guide/0034-setup-guide.md). `node scripts/eval-setup.mjs [baseUrl]` reads each
   one's setup options and tool guide and writes `docs/evaluation-setup.md`: status, steps kept and rejected by the
   checks per section, kept steps whose text the step check confirmed, snippet coverage (AI clients with a ready
   snippet) and latency.
7. **Manual notes, written down.** Observations a number does not show go in the section "Notes on this run", between
   the markers `<!-- hand-written: kept by scripts/eval-guide.mjs -->` and `<!-- end hand-written -->`. The script
   keeps that text as it is and regenerates the rest; the notes must be updated by hand after each run. When a new run
   takes its place, the old report moves to `docs/eval/` with a name that says what it measured (for example
   `run-16-before-thinking-off.md`). Every run stays, good or bad.
8. **Cost of a run.** A question costs 3 model calls (plan, choose, reason check; a compare or explain question the
   same), and each call is retried at most once, for an answer the guide cannot use
   ([0008](../foundation/0008-llm-integration.md)): 3 to 6 calls from the guide's daily cap. The core set costs about
   63, the full set of 51 about 153 (459 with `--repeat 3`), the intent cases up to 78 (a case asked back stops after
   the plan), the adversarial set 39; the setup set costs up to 30 calls (a `tool-guide` and a `tool-guide-verify` call per resource). Each question also counts against
   the client's daily questions (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`), so the backend under test sets a quota that
   covers the sets it runs.
9. **Nightly run on staging.** `.github/workflows/guide-eval.yml` runs every night at 01:41 UTC, after the daily caps
   reset, and by hand; never on a pull request. It runs the core set (`--core --min-pass 0.8`) and the adversarial
   set, against the staging URL in the repository variable `H2_STAGING_URL` only, never prod (without it the job does
   not run, so the workflow carries no host of its own):
   - 34 questions, about 102 model calls; the run may cost at most 150. The intent cases do not fit and stay a run by
     hand;
   - the core set fails below a pass share of 0.8 (17 of 21 passed), which leaves room for model noise; the adversarial set fails on any
     case, as in 3;
   - `values-staging.yaml` sets `app.guideCacheTtl: PT1S` (every answer a real model call),
     `app.dailyLimits.guideModelCalls: 600` (the prod cap, four times the hard bound of a run, retries included, so a
     rerun and checks by hand on staging still get answers after the nightly run) and per-visitor limits of 1000 (a run and a rerun from one
     address). Prod keeps the defaults. The full set with `--repeat 3` does not fit in 600 calls: it is a run by hand,
     with the settings of the day raised as [docs/evaluation.md](../../evaluation.md) "Clean baseline" says;
   - the reports (`docs/evaluation.md`, `docs/evaluation-adversarial.md`, `backend/eval-data/guide-pool.tsv`,
     `backend/eval-data/guide-runs.json`, `backend/eval-data/guide-adversarial-runs.json`) are kept as an artifact for 30 days; nothing is committed. Publishing a run
     stays a step by hand (7).

   It runs on a GitHub-hosted runner that calls the public staging address like a visitor, so it needs no secret and
   no runner inside the cluster.
10. **Similar-question set.** `docs/eval/guide-similar.json`: 55 question pairs, 25 that should share a cached answer
    and 30 near misses that must not, each side with the reading a correct plan gives it
    ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)). Their query embeddings are recorded once into
    `docs/eval/guide-similar-vectors.json` (`./gradlew evalRecord --tests '*GuideSimilarRecorder'`, the embedding
    key), so `GuideSimilarEvaluationTest` sweeps the reuse threshold offline in every build, with no model call.
11. **Results.** The latest run is in [docs/evaluation.md](../../evaluation.md); its header states the conditions
    (date, commit, models, index size).

### Search
12. **Fixed query set.** `docs/eval/search-queries.json`: 115 fixed queries, never tuned on results. Groups:
    navigational (10), MCP server for a task (12), model for a task (10), library for a language (14), paper (8),
    news and events (8), Italian and other languages (10), typos and synonyms (8), nonsense control (4), the home
    examples (11, `home-example`) and everyday tasks in plain words (20, `everyday`); the last two are sent with their
    audience `path`. The guide questions of §1 that fit are reused (`from` names the guide id).
    `docs/eval/semantic-gold.json` adds 20 paraphrase queries with few words in common with their answers, for vector
    search and its cutoff. A home example has a higher bar than nDCG: a result graded 3 or 2 in its top 3; the report
    lists each one with its best grade there. The everyday group is its own row in the report, so a ranking change
    cannot help developers and hurt that slice without it showing.
13. **Snapshots, recorded once.** `./gradlew evalRecord` (JUnit tag `eval-record`) boots the application with the
    repo-root `.env` and runs each query once through the production `SearchResources` with every configured source,
    no cache and no catalog writes. It keeps each source's ranked list (rank, URL, canonical and repository URL,
    title, description, type, audience, tags, popularity, the fields an admin curated, tech level, licence, pricing,
    open source), its outcome and latency, and, when vector search is on, the
    50 nearest catalog vectors with their distance and no cutoff. One JSON file per query in `backend/eval-data/`
    (git-ignored). A query with a file is not recorded again: the snapshot set stays frozen until its folder is
    deleted. Recording runs on the real stack: a catalog database with the index to measure, the GitHub token, the
    Tavily key and the embedding key. A new recording comes with a new baseline and a new judging pool.
14. **Offline replay.** The evaluation runs production `ResultMerger.merge` and `Ranking.rank` on the snapshots: no
    network, same input, same order. The curated fields make the merge apply the admin's values as production does;
    a snapshot recorded before they were kept replays without them. A variant is a different `Ranking`, a different merge, or a source left out. The ranking variants
    (`RankingVariants`: other `RankingWeights`, or one vote for the catalog sources) are in every report with their
    ΔnDCG@10 and interval, and their top 20 are in the judging pool.
15. **Pooled graded judgements.** `docs/eval/search-qrels.tsv` (`query_id`, `url`, `grade` 0–3: 0 not relevant,
    1 marginal, 2 relevant, 3 the answer). The pool to judge is the union of the top 20 of the baseline and of every
    variant, plus the 10 nearest vectors at any distance; `evalTest` writes the unjudged part to
    `backend/eval-data/search-pool.tsv`. A grade attaches to every URL of a merged result (identity, each source's URL
    or repository), so a result found by two sources is judged once. A second judge grades a random 20 % of the pool
    blind (`search-qrels-second.tsv`); Cohen's kappa between the two is reported with the results. Labels are decided
    by people only: a model may propose a grade, and a person confirms or corrects each one. The origin of each label
    (proposed and confirmed, or set by the person) stays in the judge's private pool file, and the blind second judge
    shows whether the proposals biased the grades. A judge may add a known answer that no run retrieved (for example
    the official repository of a navigational query); it counts against Recall@20 and nDCG@10 of every run until a run
    finds it. The unjudged share of the top 10 shows when a variant surfaces results outside the pool; the pool is
    extended before that variant is compared.
16. **Metrics, in Kotlin.** nDCG@10 (gain 2^grade − 1), MRR and Recall@20 (relevant = grade ≥ 2), duplicates in the
    top 10 (same name under two URLs), unjudged share of the top 10, and per source the marginal Recall@20 and
    ΔnDCG@10 of leaving it out. A comparison between two runs uses a paired bootstrap over queries (10 000 resamples,
    fixed seed) and reports the 95 % confidence interval of the mean difference. TREC run and qrels files are written
    next to the report for ad-hoc analysis with `ranx` or `trec_eval`; no Python runs in the build.
17. **Acceptance rule.** A ranking, merge or routing change is accepted when the 95 % interval of ΔnDCG@10 lies above
    0, or when it holds quality (interval contains 0, unjudged share in the top 10 not higher) and lowers latency or
    cost. A change is not accepted while a group of queries is lower with an interval below 0, whatever
    the mean says.
18. **Gate.** `./gradlew evalTest` (JUnit tag `eval`) writes `backend/eval-data/search-report.md` and fails when
    nDCG@10 falls more than 0.005 below `docs/eval/search-baseline.json`. The gate starts working once the baseline is
    committed: it is recorded on staging before the first release, after the 10 navigational queries are graded. The baseline
    names the snapshots and judgements it was measured on (fingerprints); with other snapshots or labels, or with no
    snapshots, no labels or no baseline at all, the gate is skipped with a message, never failed.
    `H2_EVAL_WRITE_BASELINE=true` stores a new baseline. The default `test` task excludes both tags.
    The snapshots hold third-party data and are not in the repository, so CI cannot run the gate. What CI runs
    is `RankingBaselineTest`, in the default `test` task: the baseline also names the ranking code it was
    measured with (a fingerprint of `Ranking.kt`, `ResultMerger.kt`, `QueryAnalyzer.kt` and `Vitality.kt`, white
    space left out), and the test fails when one of them changed since. So a change to the ranking cannot merge
    without a new measurement committed with it. It proves the baseline was stored again, not that the number
    was read: that stays a review step.
19. **Semantic cutoff sweep.** The same report replays the vector cutoff from 0.40 to 0.90 on the recorded
    neighbours: nDCG@10 and Recall@10 on the judged queries, results kept per query, and results kept for the nonsense
    queries, which a good cutoff keeps at 0.
20. **Routing.** Which sources a query is sent to is decided from this harness's ablation data: per source, how often
    it reaches the top 10, its marginal Recall@20, its ΔnDCG@10 with interval, and its p50 latency, per query group. A
    source whose absence costs no measurable quality for a group is not called for that group.

No evaluation runs on the request path: the guide sets run against a running app from outside, the search harness
offline in a test task. The two sequence diagrams show those runs.

### Code

The guide:
- `.github/workflows/guide-eval.yml` — the nightly run on staging; `deploy/helm/helper2/values-staging.yaml`
  (`app.guideCacheTtl`, `app.dailyLimits`) — what staging runs with.
- `scripts/eval-guide.mjs` — the guide runner, pass rate and report writer; `scripts/eval-setup.mjs` — the setup
  runner; `scripts/eval-lib.mjs` — session token, resource id, judgements, P@k and nDCG@k shared by both, and the
  guide run's stop rule (`guideRunStop`); `scripts/eval-lib.test.mjs` — its tests (`node --test`, run by CI).
- `docs/eval/guide-queries.json`, `guide-intents.json`, `guide-adversarial.json`, `guide-qrels.tsv`,
  `guide-similar.json`, `guide-similar-vectors.json`, `setup-resources.json` — the fixed sets and the judgements;
  `docs/eval/README.md` — grades and how to judge.
- `backend/apps/public/src/test/kotlin/ai/helper2/eval/GuideSimilarEvaluation.kt`, `GuideSimilarEvaluationTest.kt`,
  `GuideSimilarRecorder.kt` — the similar-question sweep.
- `docs/evaluation.md`, `docs/evaluation-adversarial.md` — the latest runs; `docs/evaluation-intents.md` and
  `docs/evaluation-setup.md` are written by `--intents` and `eval-setup.mjs` and committed after a run (neither is
  committed yet); `docs/eval/run-*.md` — earlier runs.

Search:
- `backend/apps/public/src/test/kotlin/ai/helper2/eval/` — `SearchSnapshot.kt` (snapshot, recording, replay), `RankingVariants.kt` (the ranking variants of the report),
  `SearchEvaluation.kt` (scores, ablation, pool, report, TREC export), `RelevanceMetrics.kt`, `CutoffSweep.kt`,
  `SearchSnapshotRecorder.kt` (`eval-record`), `SearchRelevanceEvalTest.kt` (`eval`, gate), `EvalFiles.kt`;
  `HomeExampleQueriesTest.kt`, `EverydayQueriesTest.kt` — the two groups sent with a path.
- `backend/build-logic/src/main/kotlin/helper2.kotlin-library.gradle.kts` — tasks `evalRecord` and `evalTest`;
  `test` excludes both tags.
- `docs/eval/search-queries.json`, `docs/eval/semantic-gold.json`, `docs/eval/search-qrels.tsv`,
  `docs/eval/README.md` (how to record, judge and compare).

## Alternatives considered

- Automatic relevance scoring by a model ("LLM as judge") — it has the same blind spots as the chooser and the other
  models the product uses; useful later only once checked against human labels on these sets.
- A large public benchmark — not about finding AI resources; no fit with the guide's task.
- Only unit tests with a fake model — they test the gate, not the answer quality.
- A guide run after every deploy to staging — several deploys a day would spend 102 calls each; one run a night tests
  what `main` holds that day.
- A Kubernetes CronJob in the staging release — it needs an image with Node and the scripts, an `evaluation` switch
  in the chart that every other cluster carries, and a volume or an upload credential to keep the report; a GitHub
  schedule keeps the chart generic and keeps the report for free.
- The intent cases in the nightly run — about 160 calls in all, with no room for a retry.
- Live search runs per variant — the sources change between runs, so a difference mixes ranking and data.
- Python (ranx, pytrec_eval) in the build — a second toolchain for about 150 lines of metrics; the TREC files keep
  them available ad hoc.
- Click logs (online evaluation) — no click data and no retention decision for query logs yet.

## Consequences

- Every change to prompts, models or retrieval can be compared on the same 50 questions; the nightly run keeps the 20
  core ones, so its threshold keeps its meaning.
- A question with no answer leaves its candidates in the pool, so a person can tell a choosing problem from a
  retrieval problem before anyone builds more retrieval.
- Bad results are visible next to good ones; the report states its conditions (date, commit, models, index size).
- Relevance is graded by people, per pick and per search result, with one set of grade definitions.
- Hostile questions and intent cases have their own pass rules; a failure ends the script with exit code 1.
- A drop in the guide's quality on `main` fails the nightly run within a day, on staging, before a release.
- The nightly run depends on the staging address being reachable from GitHub's runners and on staging's `app.env`.
- The hand-written notes survive a regenerated report.
- Every ranking and routing change is compared on the same frozen input with the same labels, and the report states
  the uncertainty, not only the difference.
- Third-party data never enters git: snapshots, pools and reports live in `backend/eval-data/` on the machine that
  recorded them, and are deleted with it. The query sets and the labels (URLs and grades) are committed.
- Pooled judging keeps human work small: only the results some variant puts in its top 20 are judged.
- The search gate runs offline, with no network and no model, so it is fast and gives the same result every time.
  It needs the snapshots, so it runs on a machine that has them; CI only checks that the baseline belongs to the
  ranking code of the commit.

### Known limits

- `H2_LLM_MAX_RETRIES` above 0 adds attempts that no budget counts. Thinking off makes the chooser return fewer picks. A guide step cut while thinking answers with thinking off, and the answer does not say so: `GuideModelInfo` and the `guide timings` line name the models, not the thinking of each step; only `helper2.llm.thinking_fallback{step}` and a WARN line do. The first, cut call is paid in full (tokens, time, one budget unit) before the second starts; no alert yet on a high fallback rate, which means the step's token limit is too low for thinking.
- 51 guide questions is still a small sample, and the nightly run keeps 21 of them; the script measures the stream's last event, not the browser; an ungraded pick counts as not relevant; the candidates of a question with no answer come from its search terms sent again to `/api/v1/search`, not from the guide's own 24 (the answer carries only 3), so they are close, not exact; the hand-written notes describe the previous run until updated. The nightly run leaves out the intent cases; staging visitors share what it leaves of 150 guide calls a day; it needs the staging address reachable from GitHub's runners.
- Frozen search snapshots age; pooled judging leaves results outside every top 20 unjudged; labelling takes about 5 hours per judge; Tavily credits are spent once per recorded query.

## Diagrams

The guide: [flow](../../diagrams/flow-0046-guide-evaluation.svg) · [sequence](../../diagrams/sequence-0046-guide-evaluation.svg) ·
search: [flow](../../diagrams/flow-0046-search-evaluation-harness.svg) · [sequence](../../diagrams/sequence-0046-search-evaluation-harness.svg)

## Backlog

- Features: [F10.4](https://github.com/silvioincalza/helper2.ai/issues/110) Evaluation of guide and search.
- Open issues that change this decision:
  - [#348](https://github.com/silvioincalza/helper2.ai/issues/348) S10.4.2 The first ten results of the new snapshot set graded
  - [#349](https://github.com/silvioincalza/helper2.ai/issues/349) S10.4.3 Guide relevance judged by a second person
  - [#357](https://github.com/silvioincalza/helper2.ai/issues/357) S10.12.1 The main journeys automated in a browser
