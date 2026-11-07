# Helper2.ai

**A metasearch engine only for the AI world** — tools, models, MCP servers, agents, prompts, libraries,
papers and news — with **one page and one box**. Typing searches every source; **Ask the guide** answers
on the same page. The guide runs on **NVIDIA Nemotron on Nebius Token Factory** and can only recommend
what it actually found, with a quote that code has checked word for word.

Helper2.ai searches a **disclosed set of sources** ([docs/sources.md](docs/sources.md)), shows what
happened to each one on every search, and never fills in dates, popularity or facts that no source stated.

Helper2.ai is a product, built and run by Silvio Incalza. The only instance is **https://helper2.ai**
(no account needed). Video: https://youtu.be/4r0Zg2YUaEs

| | |
|---|---|
| What is built and why | [ADRs, one per feature](docs/adr/README.md) · [architecture and diagrams](docs/architecture.md) |
| What is still open | [roadmap](docs/roadmap.md) |
| Product | [user journeys](docs/user-journeys.md) · [sources](docs/sources.md) · [costs](docs/cost-analysis.md) |
| Quality | [testing](docs/testing.md) · [guide evaluation](docs/evaluation.md) · [AI literacy](docs/ai-literacy.md) |
| Stack | Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Java 25 + Kotlin · React 19, Vite · PostgreSQL 18 (full-text + pgvector) · Redis 8 · contract-first [OpenAPI 3.1](api/openapi.yaml) |

## How Nemotron and Token Factory are used

**Retrieval first, Nemotron second, a deterministic validator last.** The model never decides what is true.

| Where | Model role | What code enforces |
|---|---|---|
| **AI guide** (`POST /api/v1/guide/stream`) | 1. **Nemotron 3.5 Lightning** turns the need, in any language, into search terms and hard requirements.<br>2. Federated search retrieves up to 24 real candidates.<br>3. **Nemotron 3.5 Super** picks at most 3 of them, with a quote and a reason.<br>4. A second Super call checks each reason against its quote. | A pick must name a retrieved candidate (keys `c1…c24`, so ids can't be invented). Its quote must appear word for word in that candidate's text (≥ 40 characters and 6 words, or the whole text). Rejected suggestions are counted and shown. |
| **AI Resource Scout** (background) | Super proposes metadata, relationships and a summary for newly indexed resources. | Every claim must cite retrieved evidence with an exact excerpt. Model dates are never accepted. Disagreement with a source goes to human review. |
| **Search** | Lightning translates non-English queries; NVIDIA `nemotron-3.5-embed-1b` embeddings power vector search. | Daily caps; translation is disclosed as a query expansion. |

- Calls go through Spring AI's OpenAI-compatible client, base URL `https://api.tokenfactory.nebius.com/v1` (the `/v1` is required).
- Every answer records the provider and model ids; the page shows them.
- No key, provider down or credits exhausted: the guide shows the top real search results (`LLM_UNAVAILABLE`), and search keeps working.
- Cost guard: 10 questions per minute and 30 new questions per day per client, at most 8 questions at a time, 600 model calls per day in total, AI answers cached 24 hours ([ADR 0017](docs/adr/guide/0017-guide-cost-guard.md)).
- **Tavily** adds web results to search and to the guide (only with `H2_LIVE_WEB_API_KEY`). A stored answer is reused
  first, and each visitor gets a few live web searches a day ([ADR 0037](docs/adr/search/0037-tavily-store-and-daily-quota.md)).

Feedback on Token Factory and Nemotron: [docs/hackathon/feedback.md](docs/hackathon/feedback.md).

## Hackathon submission

Nebius x NVIDIA Global AI Hackathon (Devpost), track **Best Apps and Agents**. First commit 22 Oct 2026.
Dates, rules and links: [docs/hackathon/](docs/hackathon/README.md).

Latest evaluation ([docs/evaluation.md](docs/evaluation.md), 29 Oct 2026, live demo, cache bypassed):
20 of 20 questions answered; an AI pick on 11 of 19 real questions and none on the nonsense question;
21 of 24 reasons supported by the check; p50 5.6 s, p90 7.6 s. Adversarial set
([docs/evaluation-adversarial.md](docs/evaluation-adversarial.md)): 8 of 8 hostile questions handled safely.

## Known limitations

- **A quote proves the source said it, not that it is true.** Fit to the need is judged by the model.
- **Everyday-tool coverage is thin.** Developer resources are much better covered.
- **A new question takes a few seconds**: three model calls plus a federated search. A repeated question is instant
  from the cache.
- **Results can be cached or partial** when a source hits its rate limit; the source panel says so.
- **English UI.** Queries work in any language ([ADR 0011](docs/adr/search/0011-language-aware-search.md)).

Every known limit, with its ADR: [roadmap § 9](docs/roadmap.md#9-known-limits).

## Run locally

These steps are for reviewing the code. Users do not need them: the product runs at https://helper2.ai.

Prerequisites: Java 25 (Gradle downloads it if missing), Node 26, Docker or Podman.

From the repository root:

```bash
cp .env.example .env          # optional: H2_NEBIUS_API_KEY, H2_NVIDIA_API_KEY, H2_GITHUB_TOKEN, H2_LIVE_WEB_API_KEY
podman compose up -d postgres redis
```

Terminal 1 (backend, stays in the foreground):

```bash
cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev,llm-nebius,oauth-github --spring.docker.compose.enabled=false'
```

Terminal 2 (frontend):

```bash
cd frontend && npm ci && npm run dev    # http://localhost:5173, proxies /api, /oauth2, /login, /logout to :8080
```

- Without `llm-nebius` or a key, everything runs and the guide answers in degraded mode.
- Model ids: list them with `curl -s https://api.tokenfactory.nebius.com/v1/models -H "Authorization: Bearer $H2_NEBIUS_API_KEY"`, then set `H2_NEBIUS_REASONING_MODEL` (default `nvidia/Nemotron-3.5-Super-VL`) and `H2_NEBIUS_FAST_MODEL` (default `nvidia/Nemotron-3_5-Lightning`; empty = the reasoning model does its work too).
- Fixture data instead of real sources: profiles `demo,dev` (labeled DEMO everywhere).
- Sign-in: a GitHub OAuth App with profile `oauth-github` (`H2_GITHUB_OAUTH_CLIENT_ID`/`_SECRET`, callback `http://localhost:5173/login/oauth2/code/github`). Put your numeric GitHub id in `H2_ADMINS=github:<id>` to open the admin page.

## Test and evaluate

```bash
(cd backend && ./gradlew test)             # unit + integration (Testcontainers)
(cd frontend && npm test -- --run)
node scripts/eval-guide.mjs http://localhost:8080   # needs a running backend; 20 fixed guide questions, writes docs/evaluation.md
```

Details and known gaps: [docs/testing.md](docs/testing.md).

## How helper2.ai runs

The Helm chart runs on any Kubernetes cluster ([deploy/k8s/README.md](deploy/k8s/README.md)). helper2.ai's
staging and production use it, deployed by GitHub Actions ([deploy/k8s/CI.md](deploy/k8s/CI.md)).

## License

Copyright 2026 Silvio Incalza. Licensed under [Apache-2.0](LICENSE).
