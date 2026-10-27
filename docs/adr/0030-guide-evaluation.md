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
2. **Script.** `node scripts/eval-guide.mjs [baseUrl] [--fresh[=TEXT]]` (Node 18 or newer; default
   `http://localhost:8080`):
   - gets a cross-site request forgery (CSRF) token from `GET /api/v1/me`, then sends each query to `POST /api/v1/guide`, the JavaScript Object Notation (JSON) endpoint;
   - waits 7 s between questions, below the guide's 10-per-minute limit;
   - `--fresh` appends a suffix to every query, so the 24-hour answer cache is bypassed and real model calls are measured;
   - marks an answer as cached when its `generatedAt` is older than the request, and leaves it out of the latency figures;
   - stops with an error, and leaves `docs/evaluation.md` unchanged, as soon as an answer carries the notice
     `GUIDE_DAILY_QUOTA`: the rest of the run would be search-only answers and would not measure the model;
   - writes `docs/evaluation.md`: statuses, AI picks kept and rejected, reason check counts, constraint checks,
     latency p50 / p90 / max, one row per query with its top pick, and how to run it.
3. **Adversarial set.** `docs/eval/guide-adversarial.json`: 8 hostile questions, each with expectations a passing
   answer must meet (`noPrompt`, `noPickTitled`, `noText`, `noPromptText`, `unsupportedClaimsRemoved`): breaking out
   of the `<need>` tag with a fake candidate, a link in the reason, a prompt asking for secrets, leaking the
   instructions, an Italian instruction, unsupported claims. `--adversarial` runs them, writes
   `docs/evaluation-adversarial.md` with pass or fail per case, and exits with code 1 on a failure. A case answered
   without a model (`LLM_UNAVAILABLE`) is a failure, because a search-only answer meets every expectation. They are questions,
   because that is what a shared `?ask=` link controls; hostile source text cannot be planted in live sources and is
   covered by unit tests.
4. **Manual part, written down.** Relevance of each top pick is judged by hand from title and quote. These notes go
   in the section "Notes on this run", between the markers `<!-- hand-written: kept by scripts/eval-guide.mjs -->` and
   `<!-- end hand-written -->`. The script keeps that text as it is and regenerates the rest; the notes must be
   updated by hand after each run. A run that is replaced moves to `docs/eval/` with a name that
   says what it measured (for example `run-16-before-thinking-off.md`). Runs that went badly stay.
5. **Results of 2026-10-27** (from [docs/evaluation.md](../evaluation.md), against `https://helper2.ai`, thinking off,
   with the reason check): 20 / 20 answered; status OK 12, PARTIAL 8; 31 AI picks kept, 2 rejected; reason check 30
   supported, 1 removed, 0 not checked; 3 constraint checks confirmed, 0 not confirmed; latency p50 10870 ms,
   p90 17412 ms, max 17838 ms. An AI pick on 13 of 19 real queries (previous run, thinking on: 17); 12 of the 13 top
   picks judged relevant, one not checked. The vector index was at 5 % during this run, and Token Factory was overloaded.

## Consequences
- Every change to prompts, models or retrieval can be compared on the same 20 questions.
- Bad results are visible next to good ones; the report states its conditions (date, commit, models, index size).
- 20 questions is a small sample: a difference of one or two queries is noise.
- Relevance is judged by one person, the owner. It is a judgment, not a measurement.
- A fresh run costs up to 3 model calls per question (more with retries) from the guide's daily cap, and 20 of the
  client's 30 daily questions (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`). A second fresh run from the same address on the
  same day in Coordinated Universal Time (UTC) stops after 10 questions. So: one run per address and UTC day, or a
  local backend with a higher quota.
- The script uses the JSON endpoint, so it does not measure the stream or the browser.
- The hand-written notes survive a run, but they still describe the previous run until someone updates them.

## Alternatives considered
- Automatic relevance scoring by a large language model (LLM), "LLM as judge" — the judge has the same blind spots as the chooser.
- A large public benchmark — not about finding AI resources; no fit with the guide's task.
- Only unit tests with a fake model — they test the gate, not the answer quality.

## Code
- `scripts/eval-guide.mjs` — the runner and report writer.
- `docs/eval/guide-queries.json` — the fixed question set.
- `docs/evaluation.md` — the latest run; `docs/eval/run-*.md` — earlier runs.

## Diagrams
[flow](../diagrams/flow-0030-guide-evaluation.svg) · Sequence: none, because the script is one loop of Hypertext Transfer Protocol (HTTP) calls.

## Roadmap
- [Guide relevance judged by a second person](../roadmap.md#quality-gates)
- [Browser end-to-end journeys for the guide](../roadmap.md#quality-gates)
