# Helper2.ai

**A metasearch engine only for the AI world** — tools, models, MCP servers, agents, prompts, libraries,
papers and news — with **one page and one box**. Typing searches every source; **Ask the guide** answers
on the same page. The guide runs on **NVIDIA Nemotron on Nebius Token Factory** and can only recommend
what it actually found, with a quote that code has checked word for word.

Helper2.ai searches a **disclosed set of sources** ([docs/sources.md](docs/sources.md)), shows what
happened to each one on every search, and never fills in dates, popularity or facts that no source stated.

Helper2.ai is a product, built and run by Silvio Incalza. The only instance is **https://helper2.ai**
(no account needed). Video: _to be added_

| | |
|---|---|
| What is built and why | [ADRs, one per feature](docs/adr/README.md) · [architecture and diagrams](docs/architecture.md) |
| What is still open | [roadmap](docs/roadmap.md) |
| Product | [user journeys](docs/user-journeys.md) · [sources](docs/sources.md) · [costs](docs/cost-analysis.md) |
| Quality | [testing](docs/testing.md) · [guide evaluation](docs/evaluation.md) |
| Stack | Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Java 25 + Kotlin · React 19, Vite · PostgreSQL 18 (full-text + pgvector) · Redis 8 · contract-first [OpenAPI 3.1](api/openapi.yaml) |

## How Nemotron and Token Factory are used

**Retrieval first, Nemotron second, a deterministic validator last.** The model never decides what is true.

| Where | Model role | What code enforces |
|---|---|---|
| **AI guide** (`POST /api/v1/guide/stream`) | 1. **Nemotron Nano** turns the need, in any language, into search terms and hard requirements.<br>2. Federated search retrieves up to 24 real candidates.<br>3. **Nemotron 3 Super** picks at most 3 of them, with a quote and a reason.<br>4. A second Super call checks each reason against its quote. | A pick must name a retrieved candidate (keys `c1…c24`, so ids can't be invented). Its quote must appear word for word in that candidate's text (≥ 40 characters and 6 words, or the whole text). Rejected suggestions are counted and shown. |
| **AI Resource Scout** (background) | Super proposes metadata, relationships and a summary for newly indexed resources. | Every claim must cite retrieved evidence with an exact excerpt. Model dates are never accepted. Disagreement with a source goes to human review. |
| **Search** | Nano translates non-English queries; NVIDIA `nemotron-3.5-embed-1b` embeddings power vector search. | Daily caps; translation is disclosed as a query expansion. |

- Calls go through Spring AI's OpenAI-compatible client, base URL `https://api.tokenfactory.nebius.com/v1` (the `/v1` is required).
- Every answer records the provider and model ids; the page shows them.
- No key, provider down or credits exhausted: the guide shows the top real search results (`LLM_UNAVAILABLE`), and search keeps working.
- Cost guard: 10 questions per minute and 30 new questions per day per client, at most 8 questions at a time, 600 model calls per day in total, AI answers cached 24 hours ([ADR 0017](docs/adr/0017-guide-cost-guard.md)).
- **Tavily** is a live source for AI news (only with `H2_TAVILY_API_KEY`), reported in the source panel like any other source.

Feedback on Token Factory and Nemotron: [docs/hackathon/feedback.md](docs/hackathon/feedback.md).

## Hackathon submission

Nebius x NVIDIA Global AI Hackathon (Devpost), track **Best Apps and Agents**. First commit 22 Oct 2026.

Latest evaluation ([docs/evaluation.md](docs/evaluation.md), 27 Oct 2026, live demo, cache bypassed):
20 of 20 questions answered; an AI pick on 13 of 19 real questions and none on the nonsense question;
top pick relevant on 12 of 13 (judged by hand); 30 of 31 reasons supported by the check; p50 10.9 s, p90 17.4 s.

## Known limitations

- **A quote proves the source said it, not that it is true.** A description written to be picked passes the evidence
  check; only the license comes from source metadata. Kept picks are counted by search rank to watch for this.
- **Only the quote is proven.** The reason is checked by a second model call, which is a judgment and can miss a claim ([ADR 0016](docs/adr/0016-guide-reason-check.md)). Fit to the need is judged by the model.
- **Vector search covers only what Helper2 has indexed**, and the index is still being built. Live sources are keyword-only.
- **Everyday-tool coverage is thin.** No consumer AI-tool catalog allows reuse; developer resources are much better covered.
- **Short descriptions** (common on npm and GitHub) give the guide little to quote, so good candidates can be rejected.
- **A new question takes about 11 s** on the live demo (median 10.9 s, p90 17.4 s: three model calls plus a federated search). A repeated question is instant from the cache.
- **The guide says "nothing fits" more often with thinking off** (a pick on 13 of 19 questions instead of 17); `H2_GUIDE_THINK_CHOOSE=true` turns thinking back on for the choice.
- **Live sources have rate limits** (GitHub, Hugging Face, arXiv 1 request per 3 s); results can be cached or partial, and the source panel says so.
- **English UI.** Queries work in any language ([ADR 0011](docs/adr/0011-language-aware-search.md)).

## Run locally

These steps are for reviewing the code. Users do not need them: the product runs at https://helper2.ai.

Prerequisites: Java 25 (Gradle downloads it if missing), Node 24, Docker or Podman.

From the repository root:

```bash
cp .env.example .env          # optional: H2_NEBIUS_API_KEY, H2_NVIDIA_API_KEY, H2_GITHUB_TOKEN, H2_TAVILY_API_KEY
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
- Model ids: list them with `curl -s https://api.tokenfactory.nebius.com/v1/models -H "Authorization: Bearer $H2_NEBIUS_API_KEY"`, then set `H2_NEBIUS_REASONING_MODEL` (default `nvidia/nemotron-3-super-120b-a12b`) and `H2_NEBIUS_FAST_MODEL` (default `nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B`; empty = the reasoning model does its work too).
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
