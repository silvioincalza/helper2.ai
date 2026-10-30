# 0030 — Guide evaluation: fixed question set, script, published results

**Status.** Accepted (2026-10-27). **Builds on:** [0015](0015-ai-guide-evidence-gate.md), [0016](0016-guide-reason-check.md), [0017](0017-guide-cost-guard.md)

## Context
Prompt, model and retrieval changes move the guide's quality in ways a unit test cannot see: more or fewer picks,
picks that do not fit, slower answers. Claims such as "24 candidates beat 12" or "thinking off is faster" need
numbers measured the same way each time. The numbers must be public and reproducible, including the bad ones.

## Decision
1. **Fixed question set.** `docs/eval/guide-queries.json`: 20 queries written before the first run and not tuned
   since. 14 for building with artificial intelligence (AI) (`b01`–`b14`, `b14` in Italian), 4 for using AI (`u01`–`u04`), one news question
   (`n01`) and one nonsense question (`x01`, whose honest answer is no AI pick).
2. **Script.** `node scripts/eval-guide.mjs [baseUrl] [--intents | --adversarial]` (Node 18 or newer; default
   `http://localhost:8080`):
   - gets a cross-site request forgery (CSRF) token from `GET /api/v1/me`, then sends each query to `POST /api/v1/guide`, the JavaScript Object Notation (JSON) endpoint, exactly as written;
   - runs against a backend started with `H2_GUIDE_CACHE_TTL=PT1S` (staging), so every answer is a real model call
     and a rerun of the set is never served from the answer cache;
   - waits 7 s between questions, below the guide's 10-per-minute limit;
   - marks an answer as cached when its `generatedAt` is older than the request, counts it in the summary and leaves it out of the latency figures;
   - stops with an error, and leaves `docs/evaluation.md` unchanged, as soon as an answer carries the notice
     `GUIDE_DAILY_QUOTA`: the rest of the run would be search-only answers and would not measure the model;
   - writes `docs/evaluation.md`: statuses, AI picks kept and rejected, reason check counts, constraint checks,
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
4. **Intent cases.** `docs/eval/guide-intents.json`: two questions per intent of [0032](0032-guide-intents.md) (16),
   three vague questions that must be asked back and three clear ones that must not. Each case states its expected
   intent, subjects, window and ask-back. `--intents` runs them, checks the pass rule of each intent (`NEWEST`: every
   pick born inside the window, newest first, dates read from the stored record; `ALTERNATIVES`: the subject is not a
   pick; `COMPARE`: one column per subject, no cell without a gated quote; `WHAT_IS`: every sentence quoted; `WHEN`: the
   stated date equals the stored record; `LEARN`: every pick is a `COURSE`), writes `docs/evaluation-intents.md` and
   exits with code 1 on a failure. A case answered without a model fails.
5. **Graded relevance.** `docs/eval/guide-qrels.tsv` (`query_id`, `url`, `grade` 0–3, the grades of
   [0036](0036-search-evaluation-harness.md)). The script matches a pick to a judgement by the resource id of the
   URL, reports P@3 (share of the first three AI picks graded 2 or 3) and nDCG@3 over the judged questions, and writes
   the picks no one has graded to `backend/eval-data/guide-pool.tsv` (git-ignored) for the next judging round.
6. **Setup set.** `docs/eval/setup-resources.json`: 15 resources (MCP servers, libraries, models, a gated model, apps)
   for the setup guide of [0031](0031-setup-guide.md). `node scripts/eval-setup.mjs [baseUrl]` reads each one's setup
   options and tool guide and writes `docs/evaluation-setup.md`: status, steps kept and rejected by the checks per
   section, snippet coverage (AI clients with a ready snippet) and latency.
7. **Manual notes, written down.** Observations a number does not show go
   in the section "Notes on this run", between the markers `<!-- hand-written: kept by scripts/eval-guide.mjs -->` and
   `<!-- end hand-written -->`. The script keeps that text as it is and regenerates the rest; the notes must be
   updated by hand after each run. A run that is replaced moves to `docs/eval/` with a name that
   says what it measured (for example `run-16-before-thinking-off.md`). Runs that went badly stay.
8. **Results of 2026-10-27** (from [docs/evaluation.md](../evaluation.md), against `https://helper2.ai`, thinking off,
   with the reason check): 20 / 20 answered; status OK 12, PARTIAL 8; 31 AI picks kept, 2 rejected; reason check 30
   supported, 1 removed, 0 not checked; 3 constraint checks confirmed, 0 not confirmed; latency p50 10870 ms,
   p90 17412 ms, max 17838 ms. An AI pick on 13 of 19 real queries (previous run, thinking on: 17); 12 of the 13 top
   picks judged relevant, one not checked. The vector index was at 5 % during this run, and Token Factory was overloaded.

## Consequences
- Every change to prompts, models or retrieval can be compared on the same 20 questions.
- Bad results are visible next to good ones; the report states its conditions (date, commit, models, index size).
- 20 questions is a small sample: a difference of one or two queries is noise.
- Relevance is graded by people, per pick, with fixed grade definitions; P@3 and nDCG@3 cover only judged
  questions, and a pick not graded yet counts as not relevant.
- A run costs up to 3 model calls per question (more with retries) from the guide's daily cap: about 60 for the
  default set, 66 for the intent cases, 24 for the adversarial set; the setup set costs up to 15 tool-guide calls.
  Each question also counts against the client's daily questions (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`), so the
  backend under test sets a quota that covers the sets it runs.
- The script uses the JSON endpoint, so it does not measure the stream or the browser.
- The hand-written notes survive a run, but they still describe the previous run until someone updates them.

## Alternatives considered
- Automatic relevance scoring by a large language model (LLM), "LLM as judge" — the judge has the same blind spots as the chooser.
- A large public benchmark — not about finding AI resources; no fit with the guide's task.
- Only unit tests with a fake model — they test the gate, not the answer quality.

## Code
- `scripts/eval-guide.mjs` — the guide runner and report writer; `scripts/eval-setup.mjs` — the setup runner;
  `scripts/eval-lib.mjs` — session token, resource id, judgements, P@k and nDCG@k shared by both.
- `docs/eval/guide-queries.json`, `guide-intents.json`, `guide-adversarial.json`, `guide-qrels.tsv`,
  `setup-resources.json` — the fixed sets and the judgements; `docs/eval/README.md` — grades and how to judge.
- `docs/evaluation.md`, `docs/evaluation-intents.md`, `docs/evaluation-adversarial.md`, `docs/evaluation-setup.md` —
  the latest runs; `docs/eval/run-*.md` — earlier runs.

## Diagrams
[flow](../diagrams/flow-0030-guide-evaluation.svg) · Sequence: none, because the script is one loop of Hypertext Transfer Protocol (HTTP) calls.

## Roadmap
- [Guide relevance judged by a second person](../roadmap.md#quality-gates)
- [Browser end-to-end journeys for the guide](../roadmap.md#quality-gates)
