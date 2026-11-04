# 0030 — Guide evaluation: fixed question set, script, published results, nightly run on staging

**Status.** Accepted. **Builds on:** [0015](../guide/0015-ai-guide-evidence-gate.md), [0016](../guide/0016-guide-reason-check.md), [0017](../guide/0017-guide-cost-guard.md)

## Context
Prompt, model and retrieval changes move the guide's quality in ways a unit test cannot see: more or fewer picks,
picks that do not fit, slower answers. Claims such as "24 candidates beat 12" or "thinking off is faster" need
numbers measured the same way each time. The numbers must be public and reproducible, good and bad alike, and a
drop in quality must be seen the next day, not at the next run by hand.

## Decision
1. **Fixed question set.** `docs/eval/guide-queries.json`: 50 fixed queries, all written before any run and never
   tuned to the results.
   - The 20 core questions: 14 for building with artificial intelligence (AI) (`b01`–`b14`, `b14` in Italian), 4 for
     using AI (`u01`–`u04`), one news question (`n01`) and one nonsense question (`x01`).
   - 30 more, marked `"extended": true`: 8 questions across the intents of [0032](../guide/0032-guide-intents.md)
     (`i01`–`i08`), 10 Hugging Face model questions (speech recognition, text to speech, OCR, embeddings, a reranker, a
     vision model: `h01`–`h10`), 8 questions with several requirements at once (`m01`–`m08`), and 4 that must stay
     empty (`e01`–`e04`).
   - 6 questions must stay empty (`"expectNoPick": true`, with a `why`): `x01`, `u04` (a writing task, not a resource
     to find) and `e01`–`e04`.
2. **Script.** `node scripts/eval-guide.mjs [baseUrl] [--intents | --adversarial | --min-pass <share>]` (Node 18 or
   newer; default `http://localhost:8080`):
   - gets a cross-site request forgery (CSRF) token from `GET /api/v1/me`, then sends each query to `POST /api/v1/guide/stream` exactly as written, never with a suffix, and reads the answer from its last event (`answer`, or `error` counted as a failed question);
   - runs against a backend started with `H2_GUIDE_CACHE_TTL=PT1S` (staging), so every answer is a real model call
     and a rerun of the set is never served from the answer cache;
   - waits 7 s between questions, below the guide's 10-per-minute limit, and keeps to 25 live GitHub searches in any
     60 s (GitHub allows 30 a minute per token), counted from the source list of every answer and search it receives;
   - prints each source's status per question and lists in the report every source that did not succeed;
   - for a question with no answer, sends the answer's own search terms to `GET /api/v1/search` (24 results) and writes
     them to the grading pool: close to the guide's 24 candidates, not the same list (no intent filter, sort or web
     query), because the answer carries only its top 3;
   - `--repeat <n>` runs the set n times and counts the questions whose answer or top pick changed between runs;
   - `--core` runs the 20 core questions only;
   - saves the raw answers to `backend/eval-data/guide-runs.json`; `--rescore` rebuilds the report from them, with the
     judgements of that moment and no new request;
   - marks an answer as cached when its `generatedAt` is older than the request, counts it in the summary and leaves it out of the latency figures;
   - stops with an error, and leaves `docs/evaluation.md` unchanged, as soon as an answer carries the notice
     `GUIDE_DAILY_QUOTA`, or after 3 answers in a row without a model (`LLM_UNAVAILABLE`: the daily model calls are
     spent or the provider is down): the rest of the run would be search-only answers and would not measure the model;
   - counts a question as answered when a model answered it fresh (HTTP 200, not from the cache, not
     `LLM_UNAVAILABLE`) with an AI pick, a comparison, an explanation or a dated record; it passes when answered, or
     when it stays empty and expects to; `--min-pass` exits with code 1 when the share passed is below the given one,
     after writing the report;
   - writes `docs/evaluation.md`: the go / no-go figures of later retrieval changes (answer rate, questions kept
     empty, P@3, share of relevant candidates, p90, 429s, flips), questions passed, statuses, AI picks kept and rejected, reason check counts, constraint checks,
     latency p50 / p90 / max, P@3 and nDCG@3 of the AI picks when judgements exist (item 5), one row per query with
     its top pick, and how to run it.
3. **Adversarial set.** `docs/eval/guide-adversarial.json`: 8 hostile questions, each with expectations a passing
   answer must meet (`noPrompt`, `noPickTitled`, `noText`, `noPromptText`, `unsupportedClaimsRemoved`): breaking out
   of the `<need>` tag with a fake candidate, a link in the reason, a prompt asking for secrets, leaking the
   instructions, an Italian instruction, unsupported claims. `--adversarial` runs them, writes
   `docs/evaluation-adversarial.md` with pass or fail per case, and exits with code 1 on a failure. A case answered
   without a model (`LLM_UNAVAILABLE`) is a failure, because a search-only answer meets every expectation. They are questions,
   because that is what a shared `?ask=` link controls; hostile source text cannot be planted in live sources and is
   covered by unit tests.
4. **Intent cases.** `docs/eval/guide-intents.json`: two questions per intent of [0032](../guide/0032-guide-intents.md) (16),
   three vague questions that must be asked back and three clear ones that must not. Each case states its expected
   intent, subjects, window and ask-back. `--intents` runs them, checks the pass rule of each intent (`NEWEST`: every
   pick born inside the window, newest first, dates read from the stored record; `ALTERNATIVES`: the subject is not a
   pick; `COMPARE`: one column per subject, no cell without a gated quote; `WHAT_IS`: every sentence quoted; `WHEN`: the
   stated date equals the stored record; `LEARN`: every pick is a `COURSE`), writes `docs/evaluation-intents.md` and
   exits with code 1 on a failure. A case answered without a model fails.
5. **Graded relevance.** `docs/eval/guide-qrels.tsv` (`query_id`, `url`, `grade` 0–3, the grades of
   [0036](0036-search-evaluation-harness.md)). The script matches a pick to a judgement by the resource id of the
   URL, reports P@3 (share of the first three AI picks graded 2 or 3) and nDCG@3 over the judged questions, and writes
   the picks no one has graded to `backend/eval-data/guide-pool.tsv` (git-ignored) for the next judging round. The
   pool also holds the candidates of every question with no answer (item 2). Graded, they show whether the guide
   had a good resource and did not pick it (the chooser) or never found one (retrieval).
6. **Setup set.** `docs/eval/setup-resources.json`: 15 resources (MCP servers, libraries, models, a gated model, apps)
   for the setup guide of [0031](../guide/0031-setup-guide.md). `node scripts/eval-setup.mjs [baseUrl]` reads each one's setup
   options and tool guide and writes `docs/evaluation-setup.md`: status, steps kept and rejected by the checks per
   section, snippet coverage (AI clients with a ready snippet) and latency.
7. **Manual notes, written down.** Observations a number does not show go
   in the section "Notes on this run", between the markers `<!-- hand-written: kept by scripts/eval-guide.mjs -->` and
   `<!-- end hand-written -->`. The script keeps that text as it is and regenerates the rest; the notes must be
   updated by hand after each run. When a new run takes its place, the old report moves to `docs/eval/` with a name
   that says what it measured (for example `run-16-before-thinking-off.md`). Every run stays, good or bad.
8. **Cost of a run.** A question costs 3 model calls (plan, choose, reason check; a compare or explain question the
   same), and each call is retried at most once, for an answer the guide cannot use
   ([0014](../guide/0014-llm-integration.md)): 3 to 6 calls from the guide's daily cap. The core set costs about
   60, the full set of 50 about 150 (450 with `--repeat 3`), the intent cases 66, the adversarial set 24; the setup set costs up to 15 tool-guide calls. Each question
   also counts against the client's daily questions (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`), so the backend under test
   sets a quota that covers the sets it runs.
9. **Nightly run on staging.** `.github/workflows/guide-eval.yml` runs every night at 01:41 Coordinated Universal
   Time (UTC), after the daily caps reset, and by hand; never on a pull request. It runs the core set
   (`--core --min-pass 0.5`) and the adversarial set, against the staging URL in the repository variable `H2_STAGING_URL` only, never prod (without it the job
   does not run, so the workflow carries no host of its own):
   - 28 questions, about 84 model calls; the run may cost at most 150. The intent cases (66 more) do not fit and
     stay a run by hand;
   - the default set fails below 10 of 20 passed: the last published run passed 11 (12 with the answer that came
     from the cache), so the threshold leaves room for two questions of model noise; the adversarial set fails on
     any case, as in item 3;
   - `values-staging.yaml` sets `app.guideCacheTtl: PT1S` (every answer a real model call),
     `app.dailyLimits.guideModelCalls: 150` (the hard bound of a run, retries included; staging visitors share what
     the run leaves) and per-visitor limits of 1000 (a run and a rerun from one address). Prod keeps the defaults.
     The full set with `--repeat 3` does not fit in 150 calls: it is a run by hand, with the settings of the day
     raised as [docs/evaluation.md](../../evaluation.md) "Clean baseline" says;
   - the reports (`docs/evaluation.md`, `docs/evaluation-adversarial.md`, `backend/eval-data/guide-pool.tsv`) are
     kept as an artifact for 30 days; nothing is committed. Publishing a run stays a step by hand (item 7).
   It runs on a GitHub-hosted runner that calls the public staging address like a visitor, so it needs no secret
   and no runner inside the cluster. A Kubernetes CronJob in the staging release would need an image with Node and
   the scripts, an `evaluation` switch in the chart that every other cluster carries, and a volume or an upload
   credential to keep the report; a GitHub schedule keeps the chart generic and keeps the report for free.
10. **Similar-question set.** `docs/eval/guide-similar.json`: 55 question pairs, 25 that should share a cached
   answer and 30 near misses that must not, each side with the reading a correct plan gives it
   ([0041](../guide/0041-guide-answer-cache.md)). Their query embeddings are recorded once into
   `docs/eval/guide-similar-vectors.json` (`./gradlew evalRecord --tests '*GuideSimilarRecorder'`, the embedding key),
   so `GuideSimilarEvaluationTest` sweeps the reuse threshold offline in every build, with no model call.
11. **Results.** The latest run is in [docs/evaluation.md](../../evaluation.md); its header states the conditions
   (date, commit, models, index size).

## Consequences
- Every change to prompts, models or retrieval can be compared on the same 50 questions; the nightly run keeps
  the 20 core ones, so its threshold keeps its meaning.
- A question with no answer leaves its candidates in the pool, so a person can tell a choosing problem from a
  retrieval problem before anyone builds more retrieval.
- Bad results are visible next to good ones; the report states its conditions (date, commit, models, index size).
- Relevance is graded by people, per pick, with fixed grade definitions.
- Hostile questions and intent cases have their own pass rules; a failure ends the script with exit code 1.
- A drop in the guide's quality on `main` fails the nightly run within a day, on staging, before a release.
- The run depends on the staging address being reachable from GitHub's runners and on staging's `app.env`.
- The hand-written notes survive a regenerated report.

## Alternatives considered
- Automatic relevance scoring by a large language model (LLM), "LLM as judge" — the judge has the same blind spots as the chooser.
- A large public benchmark — not about finding AI resources; no fit with the guide's task.
- Only unit tests with a fake model — they test the gate, not the answer quality.
- A run after every deploy to staging — several deploys a day would spend 84 calls each; one run a night tests
  what `main` holds that day.
- A Kubernetes CronJob in the staging release — see item 9.
- The intent cases in the nightly run — 150 calls in all, with no room for a retry.

## Code
- `.github/workflows/guide-eval.yml` — the nightly run on staging.
- `scripts/eval-guide.mjs` — the guide runner, pass rate and report writer; `scripts/eval-setup.mjs` — the setup runner;
  `scripts/eval-lib.mjs` — session token, resource id, judgements, P@k and nDCG@k shared by both.
- `docs/eval/guide-queries.json`, `guide-intents.json`, `guide-adversarial.json`, `guide-qrels.tsv`,
  `guide-similar.json`, `guide-similar-vectors.json`, `setup-resources.json` — the fixed sets and the judgements; `docs/eval/README.md` — grades and how to judge.
- `docs/evaluation.md`, `docs/evaluation-intents.md`, `docs/evaluation-adversarial.md`, `docs/evaluation-setup.md` —
  the latest runs; `docs/eval/run-*.md` — earlier runs.

## Diagrams
[flow](../../diagrams/flow-0030-guide-evaluation.svg) · [sequence](../../diagrams/sequence-0030-guide-evaluation.svg)

## Roadmap
- [Guide relevance judged by a second person](../../roadmap.md#quality-gates)
- [Browser end-to-end journeys for the guide](../../roadmap.md#quality-gates)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
