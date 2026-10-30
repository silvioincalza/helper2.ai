# helper2.ai — instructions for coding assistants

helper2.ai is a metasearch for the AI ecosystem (tools, models, MCP servers, agents, prompts,
libraries, papers, news): one page, one box, fast. An AI guide answers only from retrieved
results, with quotes checked by code. Every change must serve this goal; flag anything else
as scope creep.

## Stack and layout

- `backend/` — Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Java 25 + Kotlin, Gradle
- `frontend/` — React 19, Vite, TypeScript, Vitest, Playwright
- `api/openapi.yaml` — contract first (OpenAPI 3.1); the frontend generates types from it
- PostgreSQL 18 (full-text + pgvector), Redis 8 (disposable cache)
- `deploy/` — Helm charts for staging and prod
- `docs/adr/` — one ADR per decision; `docs/roadmap.md` — every open TODO
- LLMs: NVIDIA models only (Nemotron on Nebius Token Factory)

## Commands

```
cd backend && ./gradlew test        # unit + integration (Postgres, Redis in containers)
cd backend && ./gradlew liveTest    # opt-in, real external APIs
cd frontend && npm test -- --run && npm run lint && npm run typecheck
```

Run the relevant tests before calling a change done.

## How to work

- Modular monolith, hexagonal: domain and use cases in the core, adapters for DB, LLM, HTTP, queue.
  One explicit use case per business action. Lightweight DDD: ubiquitous language, clear modules.
- TDD for domain logic, BDD for user-visible behaviour, integration tests for adapters.
- Before adding a library or tool: compare 2-3 candidates (fit, maintenance, licence, lock-in,
  operational cost), recommend one, state the trade-off.
- Lasting decision → new ADR in `docs/adr/` (next number), linked from `docs/adr/README.md`.
- Every ADR has a flowchart (the decisions in the code) and a sequence diagram (the call path),
  as D2 sources in `docs/diagrams/` (`flow-NNNN-<slug>.d2`, `sequence-NNNN-<slug>.d2`) with the
  rendered SVG committed next to them, linked from the ADR and both README indexes. When the
  decision changes the module structure or the runtime, also update `software-architecture` and/or
  `infrastructure`. A decision with no runtime path (e.g. versioning) says so in one line instead
  of a sequence diagram. Each `.d2` names the code it describes; update it in the same change as
  that code.
- Architecture or design work → run the AI architecture check below.
- Orchestration for multi-step flows with compensation; choreography for loose side effects.
  SSE for server streaming; WebSocket only for real two-way interaction.
- Adopt new storage, queues or streaming only for a concrete, named need.
- Performance: measure first (profile, JMH, load test, EXPLAIN ANALYZE), set latency budgets
  (p50/p95/p99), show numbers before and after. No optimisation without a measured problem.
- Privacy and terms: apply GDPR, Swiss nFADP, ePrivacy, EU AI Act, DSA and source/API terms.
  Draft and review legal texts, but mark every point that needs a qualified lawyer.
- Security: STRIDE threat model per new feature; OWASP Top 10 and OWASP LLM Top 10
  (prompt injection, data leakage); dependency scanning, SBOM, image signing; checks in CI.
  Least privilege, validation at every boundary, no secrets in code or logs.
- UX: a user journey for every user-visible feature (entry, steps, empty/loading/error
  states, exit). Gamification only when it gets users to a good answer faster; no dark patterns.

## Working with Silvio

- Specialist agents (`.claude/agents/`: architect, metasearch, performance, security,
  privacy-legal, ux-journeys) can work on one task together. One coordinator gives them the task,
  shares their findings, resolves conflicts and merges the result. Use several agents only for
  work that crosses roles; a small task stays with one agent.
- Silvio is always in the loop: present the plan, trade-offs and open questions, and wait for his
  decision before implementing anything that is not trivial or that is hard to undo.
- Explain everything in simple words first, then the technical detail.
- Write documentation as Silvio would write it: his voice, plain short sentences, the style and
  structure of the existing docs.

## Branches and worktrees

- Every session works in its own git worktree, on its own branch from the latest `origin/main`:
  `git worktree add .claude/worktrees/<branch> -b <branch> origin/main` (git-ignored folder).
  The main checkout stays on `main` and clean: no session edits or switches branches there.
- A branch that needs another unmerged branch is stacked on it. Say so in the PR description:
  `Depends on #N`.
- After a PR merges, update every open worktree that depends on it, direct dependents first:
  - clean worktree: rebase onto `origin/main` (a stacked branch after a squash merge:
    `git rebase --onto origin/main <old-base>`), run its tests, push with `--force-with-lease`,
    and retarget its PR to `main`;
  - worktree with uncommitted work, or owned by a running session: do not touch it; add a line
    to `.private/notes/worktrees.md` so its owner rebases at a safe point.
- When a branch is merged, remove its worktree (`git worktree remove`) and delete the branch.
- `/sync-worktrees` does the update steps above.

## AI architecture check

For architecture, design or review work, report each layer as covered, gap (must fix) or
deferred (safe for now, say why).

| # | Layer | Look for |
|---|---|---|
| 1 | Model | model choice per task, NVIDIA-only rule, fallback when the provider is down |
| 2 | Agent Framework | agent loop, state, tool calling, workflow logic |
| 3 | Orchestration | durable steps, retries, timeouts, scheduling |
| 4 | Tool & API | MCP, external APIs, function calling, scoped access |
| 5 | Memory | session state, user context, what persists and for how long |
| 6 | Knowledge & RAG | retrieval, vector search, re-ranking, source grounding |
| 7 | Data | schema, migrations, object storage, provenance |
| 8 | Browser & Computer Use | any page or UI automation, and its limits |
| 9 | Guardrails | input validation, output checks, quote/evidence gates, policy |
| 10 | Observability | traces, model ids per answer, logs without secrets or PII |
| 11 | Evaluation | eval sets, adversarial set, regression on model or prompt change |
| 12 | Deployment | Helm, image, config, rollout and rollback |
| 13 | Security & Identity | authN/authZ, least privilege, secrets in OpenBao |
| 14 | Human-in-the-Loop | review queues, approval for irreversible actions |
| 15 | Cost & Reliability | caps, caching, rate limits, circuit breakers, degraded mode |

## Hard rules

- Config and secrets: `H2_` prefix; secrets live in OpenBao, never in the repo.
- The deploy stays generic: no dependency on a specific cluster or infra repo.
- No backward compatibility with removed code or migrations unless asked.
- Never add AI attribution, "Generated with" footers or Co-Authored-By to commits, PRs,
  comments, docs or code.
- Every change reaches `main` through a pull request. Open and merge it with `gh` from Silvio's
  machine (never through a tool that adds an AI footer), then delete the branch.
- AI-tool setup and settings are committed (`AGENTS.md`, `CLAUDE.md`, `.claude/settings.json`,
  `.claude/agents/`, `.claude/commands/`). AI notes, reviews, memory and machine-local settings
  never are: they go in the git-ignored `.private/` folder (or `CLAUDE.local.md`,
  `.claude/settings.local.json`).
