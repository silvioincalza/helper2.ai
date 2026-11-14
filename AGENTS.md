# helper2.ai — instructions for coding assistants

helper2.ai is a metasearch for the AI ecosystem (tools, models, MCP servers, agents, prompts,
libraries, papers, news): one page, one box, fast. An AI guide answers only from retrieved
results, with quotes checked by code. Every change must serve this goal; flag anything else
as scope creep.

## Stack and layout

- `backend/` — Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Java 25 + Kotlin, Gradle multi-project:
  shared code in `libs/`, two apps in `apps/public` and `apps/backoffice` (ADR 0043), plugins and versions
  in `build-logic/`
- `frontend/` — React 19, Vite, TypeScript, Vitest
- `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` — contract first (OpenAPI 3.1); backend and frontend generate from them
- PostgreSQL 18 (full-text + pgvector), Redis 8 (disposable cache)
- `deploy/` — Helm charts for staging and prod
- `docs/adr/<context>/` — one ADR per decision, numbered in the order the system is built
- The backlog is the GitHub issues of this repository (epics, features, stories, tasks). `docs/roadmap.md` is generated
  from the open issues: never edit it by hand
- LLMs: NVIDIA models only (Nemotron on Nebius Token Factory)

## Commands

```
cd backend && ./gradlew test        # unit + integration (Postgres, Redis in containers)
cd backend && ./gradlew spotlessApply  # format Java and Kotlin (CI runs spotlessCheck)
cd backend && ./gradlew liveTest    # opt-in, real external APIs
cd frontend && npm test -- --run && npm run lint && npm run typecheck
cd frontend && npm run format        # Prettier (CI runs format:check)
cd frontend && npm run knip          # dead code and unused dependencies, strict (CI runs it)
```

Run the relevant tests before calling a change done.

## How to work

### Architecture and code

- Modular monolith (single repo; the style is used when the user asks or the project is one repo),
  hexagonal: domain and use cases in the core, adapters for DB, LLM, HTTP, queue.
  One explicit use case per business action. Lightweight DDD: ubiquitous language, clear modules.
- TDD for domain logic, BDD for user-visible behaviour, integration tests for adapters.
- Configuration only through `@ConfigurationProperties` records (one per module or concern); no `@Value`, no
  `Environment.getProperty` (ADR 0007).
- Code format (ADR 0047): Google style for the backend, google-java-format for Java and ktfmt
  Google style for Kotlin, through Spotless; Prettier for the frontend. Run `./gradlew spotlessApply`
  and `npm run format` before a commit; CI fails on unformatted code.
- Clean code: a use case reads top-down as the business action. Small methods with one level of
  abstraction each, names that say the intent, early returns, no flag arguments. Split a long
  method into named steps instead of adding comments.
- No comments in code, config or scripts: names, small methods and ADRs say why. Only tool directives stay
  (shebangs, `# syntax=`, `eslint-disable-next-line`, `shellcheck disable`, the Makefile's `## ` help lines).
  Env files (`.env.example`, `deploy/k8s/app.env.example` and other dot files) are setup guides for people: their
  comments say what each key is and how to make its value.
- Code and docs state the rule in force, never who decided it or when, and never what it replaced. Decision history
  goes to memory in `.private/`.

### Choices and decisions

- Versions: use the latest compatible release of every dependency and tool. Ask Silvio before
  picking a version, and say why when the latest does not work.
- Before adding a library or tool: compare 2-3 candidates (fit, maintenance, licence, lock-in,
  operational cost), recommend one, state the trade-off. Revisit an existing feature's choice
  only when the feature changes or has a real problem.
- Architecture choices: compare 2-3 realistic options on simplicity, coupling, testability,
  operational cost, failure modes, security, reversibility and cognitive load for a one-person
  team. Recommend one.
- Lasting decision → new ADR in `docs/adr/<context>/` (next number), linked from `docs/adr/README.md`.
  It states the decision in force, in the present tense, as the code is, with these sections in this order: Context,
  Decision, Alternatives considered, Consequences, Diagrams, Backlog. Known limits go in its Consequences. Its
  Backlog section lists the features it decides and the open issues that change it.
- Open work is a backlog issue. Docs and ADRs point at issue IDs (`S10.12.1`), never at a roadmap section.
- Every ADR has a flowchart (the decisions in the code) and a sequence diagram (the call path),
  as D2 sources in `docs/diagrams/` (`flow-NNNN-<slug>.d2`, `sequence-NNNN-<slug>.d2`) with the
  rendered SVG committed next to them, linked from the ADR and both README indexes. When the
  decision changes the module structure or the runtime, also update the `architecture-*` view of the
  module that changed (and `architecture-context` if modules change) and/or `infrastructure`. A decision with no runtime path (e.g. versioning) says so in one line instead
  of a sequence diagram. Each `.d2` names the code it describes; update it in the same change as
  that code.
- Architecture or design work → run the AI architecture check below.

### Data, messaging and runtime

- Orchestration for multi-step flows with compensation; choreography for loose side effects.
  SSE for server streaming; WebSocket only for real two-way interaction.
- Adopt new storage, queues or streaming only for a concrete, named need.
- Every data flow: decide storage (relational, vector, graph), caching and retention period.
- Schema, until the first release tag (v2026.11.3): every change edits `V1__schema.sql`. Never add a `V2__` migration
  (ADR 0004 §5).

### Performance

- Performance: measure first (profile, JMH, load test, EXPLAIN ANALYZE), set latency budgets
  (p50/p95/p99), show numbers before and after. No optimisation without a measured problem.

### Privacy, terms and AI regulation

- Privacy and terms: apply GDPR, Swiss nFADP, ePrivacy, EU AI Act, DSA and source/API terms.
  Draft and review legal texts, but mark every point that needs a qualified lawyer, and list
  those points before the text is published.
- A change to a data flow (new field, source, processor, cookie, browser-storage key, log or
  retention period) updates the Privacy page in the same change. A new rule for users updates
  the Terms page. The feature is not done until the pages match the code.
- EU AI Act Art. 50: every AI-written text shown to users is labelled in the UI and marked
  machine-readable in the API and the HTML. A new AI feature without both is not done.
- No fine-tuning or substantial model change without a new scope check: it can make helper2.ai
  a general-purpose AI model provider, with much heavier duties.
- Keep the AI regulation gap analysis current. Re-run it when the EU AI Act, its guidance or the
  Code of Practice change, and when the Swiss AI act goes to consultation (planned spring 2027).

### Security

- Security: STRIDE threat model per new feature; OWASP Top 10 and OWASP LLM Top 10
  (prompt injection, data leakage); dependency scanning, SBOM, image signing; checks in CI.
  Least privilege, validation at every boundary, no secrets in code or logs.

### UX

- UX: a user journey for every user-visible feature (entry, steps, empty/loading/error
  states, exit). Gamification only when it gets users to a good answer faster; no dark patterns.

## Working with Silvio

- Specialist agents (`.claude/agents/`: architect, metasearch, performance, security,
  privacy-legal, ux-journeys, ui-design) can work on one task together. One coordinator gives them the task,
  shares their findings, resolves conflicts and merges the result. Use several agents only for
  work that crosses roles; a small task stays with one agent.
- Silvio is always in the loop: present the plan, trade-offs and open questions, and wait for his
  decision before implementing anything that is not trivial or that is hard to undo.
- Keep working when the next step does not need Silvio's input. Do not stop to ask for a go-ahead
  on routine steps.
- Stop and ask Silvio before:
  - deleting important data;
  - force-pushing (this includes `--force-with-lease` after a rebase);
  - modifying external systems;
  - performing destructive operations;
  - making a decision that needs his approval.
- Fix the review comments on a PR (automated review findings included) without waiting to be asked.
- Explain everything in simple words first, then the technical detail.
- Write documentation as Silvio would write it: his voice, plain short sentences, the style and
  structure of the existing docs.

## Branches and worktrees

- Every session works in its own git worktree, on its own branch from the latest `origin/main`:
  `git worktree add .claude/worktrees/<folder> -b <branch> origin/main` (git-ignored folder).
  `<folder>` is the branch name with every `/` replaced by `-` (`fix/review-findings` →
  `fix-review-findings`), so every worktree sits directly under `.claude/worktrees/`.
  The main checkout stays on `main` and clean: no session edits or switches branches there.
- A branch is named `<prefix>/<short-name>`, lower-case prefix, with one of: `feature/`, `fix/`, `docs/`, `chore/`,
  `ci/`, `refactor/`, `test/`, `release/` (a release line, `release/YYYY.N`), `replay/` (a replay pull request).
  CI fails a pull request from a branch with another prefix (`.github/scripts/branch-name.sh`).
- Do not use `git stash` in a worktree: the stash list is shared by every worktree of the repo, so another
  session can pop your changes. Set work aside with a temporary commit instead.
- A branch that needs another unmerged branch is stacked on it. Say so in the PR description:
  `Depends on #N`.
- After a PR merges, update every open worktree that depends on it, direct dependents first:
  - clean worktree: rebase onto `origin/main` (a stacked branch after a squash merge:
    `git rebase --onto origin/main <old-base>`), run its tests, ask Silvio, then push with `--force-with-lease`
    and retarget its PR to `main`;
  - worktree with uncommitted work, or owned by a running session: do not touch it; add a line
    to `.private/notes/worktrees.md` so its owner rebases at a safe point.
- When Silvio has merged a branch, remove its worktree (`git worktree remove`) and delete the branch.
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
- Infra baseline (do not reopen without a concrete reason): Kubernetes, Helm charts for staging
  and prod, own image registry, OpenBao for secrets, ingress-nginx, GitHub as identity
  provider, servers in the EU, admin back office only over VPN.
- No backward compatibility with removed code or migrations unless asked.
- Never add AI attribution, "Generated with" footers or Co-Authored-By to commits, PRs,
  comments, docs or code.
- Every change reaches `main` through a pull request. Open it with `gh` from Silvio's machine
  (never through a tool that adds an AI footer). Its description closes the stories it builds, one `Closes #n`
  line each. Silvio reviews and merges it himself: never
  merge to `main`, never push to `main` directly, never deploy to prod.
- This holds even when Silvio asks for a merge in a session ("merge", "merge all"). Get the PR
  ready (checks green, review threads answered), then give him the command to run, one line per
  PR: `gh pr merge <n> --squash --delete-branch`. For a stack, give the order. Never use
  `gh pr merge`, `gh stack merge` or auto-merge yourself.
- AI-tool setup and settings are committed (`AGENTS.md`, `CLAUDE.md`, `.claude/settings.json`,
  `.claude/agents/`, `.claude/commands/`). AI notes, reviews, plans, memory and machine-local
  settings never are: they go in the git-ignored `.private/` folder (or `CLAUDE.local.md`,
  `.claude/settings.local.json`). Plans, including the refactor plan and the AI scaffold map,
  live in `.private/plans/`. Product prompts the app runs are code and stay in the repo.
