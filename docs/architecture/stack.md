# Stack

**For the agent:** read before writing code. This file is the AI Unified Process record of the technical layers,
independent of how the team documents its architecture. The Layers table says which layer skill builds and tests
each technical layer: `/use-case-implement` runs the Skill column bottom-up, `/use-case-test` runs the Test skill
column from the integration layer up, and `/use-case-coverage` searches for the markers in the last table.

**Last updated:** 2026-11-20

## Layers

| Layer       | Technology                                        | Skill            | Test skill            | Location |
|-------------|---------------------------------------------------|------------------|-----------------------|----------|
| Migration   | Flyway, PostgreSQL 18 (full-text, pgvector)       | `flyway-schema`  | –                     | `backend/libs/schema/src/main/resources/db/migration` |
| Persistence | Spring JDBC (`JdbcClient`, hand-written SQL)      | –                | –                     | `ai.helper2.<module>.internal` (`Jdbc*` adapters) in `backend/libs/core` |
| Client      | Spring `RestClient`, Spring AI                    | `spring-client`  | `spring-client-test`  | `ai.helper2.sources.*`, `ai.helper2.platform.llm`, `ai.helper2.catalog.internal` |
| Service     | Spring Boot 4.1, Spring Modulith, Java 25 + Kotlin | `spring-service` | `spring-service-test` | `ai.helper2.<module>.usecase` in `backend/libs/core` |
| API         | Spring MVC REST, contract-first OpenAPI 3.1       | `spring-rest`    | `spring-rest-test`    | `ai.helper2.<module>.web` in `backend/apps/public` and `backend/apps/backoffice` |
| UI          | React 19, Vite, TypeScript (Vitest)               | –                | –                     | `frontend/src/features` (public site), `frontend/src/admin` (back office) |
| E2E         | –                                                 | –                | –                     | –        |

**Integration layer:**

| Use case offered through | Tested in | Test skill            |
|--------------------------|-----------|-----------------------|
| UI                       | API       | `spring-rest-test`    |
| API                      | API       | `spring-rest-test`    |
| Service                  | Service   | `spring-service-test` |

The UI is a client-side React application: it reaches the service through the REST API, so a use case offered
through the UI is built in the API layer too and tested there. The UI and Persistence layers have no layer skill:
`/use-case-implement` builds them from its own knowledge, following the existing code (the `Jdbc*` adapters, the
feature folders of the frontend). The project has no browser test layer.

## External systems

| System            | Contract | Location |
|-------------------|----------|----------|
| Language Model    | –        | `ai.helper2.platform.llm` |
| Source Provider   | –        | `ai.helper2.sources` (one package per source: `github`, `huggingface`, `arxiv`, `npm`, `pypi`, `hackernews`, `tavily`, `feeds`, ...) |
| Embedding Service | –        | `ai.helper2.catalog.internal` (`NvidiaEmbedder`) |
| Search Engine     | –        | `ai.helper2.catalog` (IndexNow: `catalog.schedule`, `catalog.config`) |

## Module layout

Gradle multi-project in `backend/`: shared code in `libs/` (`kernel`, `platform`, `schema`, and `core`, where each
business module is a package: `search`, `guide`, `setup`, `catalog`, `identity`, `site`, `scout`, `sources`,
`research`, `examples`, `admin`), two applications in `apps/public` and `apps/backoffice`, the architecture tests in
`architecture/`. Root package `ai.helper2`. Each module is hexagonal: `usecase/` (one class per business action),
`internal/` (adapters), `web/` (controllers, in the application that serves them), `config/`, `schedule/`. Tests sit
next to the code in `src/test`. The frontend is one Vite project in `frontend/` with two entry points (public site
and back office).

## API contract

REST/JSON under the base path `/api/v1`. Contract-first: the OpenAPI 3.1 files live in `api/` (`public.yaml`,
`admin.yaml`, `common.yaml`); the backend generates its interfaces with openapi-generator, the frontend its types
with openapi-typescript. A use case's `**API:**` line links its operation there. Errors are RFC 9457 Problem
Details; the controller maps a use case's `Outcome` to the status and the sentence.

## Commands

| Purpose            | Command |
|--------------------|---------|
| Build              | `cd backend && ./gradlew build` and `cd frontend && npm run build` |
| Unit tests         | `cd backend && ./gradlew test` and `cd frontend && npm test -- --run` |
| Integration tests  | `cd backend && ./gradlew test` (Postgres and Redis in containers) |
| End-to-end tests   | – |
| Container runtime  | Testcontainers needs one. With Podman: `export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')` and `export TESTCONTAINERS_RYUK_DISABLED=true` |
| Live smoke tests   | `cd backend && ./gradlew liveTest` (real external APIs, `@Tag("live")`; not part of `test`) |
| Evaluation         | `cd backend && ./gradlew evalRecord` (one live search per evaluation query, saved to `backend/eval-data`; `--tests '*GuideSimilarRecorder'` records the similar-question embeddings) and `./gradlew evalTest` (offline replay and metrics; skips without snapshots). Not part of `test`. The sets and the steps: [eval/README.md](../../eval/README.md) |
| Category evaluation | `cd backend && ./gradlew evalTest --tests ai.helper2.eval.CategoryEvalTest` (labels in `apps/public/src/test/resources/eval/categories-labelled.csv`; skips without rows) |
| Guide evaluation   | `node scripts/eval-guide.mjs <baseUrl>` (`--core`, `--intents`, `--adversarial`, `--repeat <n>`, `--rescore`) and `node scripts/eval-setup.mjs <baseUrl>`; every night against staging in `.github/workflows/guide-eval.yml` |
| Latency tests      | `cd backend && ./gradlew perfTest` (seeded catalog, `@Tag("perf")`, p50/p95/p99 in `build/perf/`) |
| Load test          | `cd backend && ./gradlew :loadtest:gatlingRun --simulation ai.helper2.load.SearchSimulation -Dh2.load.baseUrl=https://<host>` (by hand only; `test` only compiles it) |
| Alert rules        | `promtool check rules deploy/monitoring/helper2-rules.yaml` and `promtool test rules deploy/monitoring/tests/*.yaml` (CI runs them in the `monitoring` job) |
| Frontend checks    | `cd frontend && npm test -- --run && npm run lint && npm run typecheck` (Node 26, `frontend/.nvmrc`) |

## Test layers

What each layer covers, and where a new test goes. The tests of each area are listed in [test-map.md](test-map.md).

| Layer | Covers | Where a new test goes |
|---|---|---|
| Unit (domain, TDD) | The rules in the Kotlin domain and the use cases: merging, ranking, filtering, the evidence gate, budgets, the vitality and admission rules. No Spring context, no container; ports are fakes. Written first, with the rule. | Next to the class, `*Test`. |
| BDD (user-visible behaviour) | What a person or an API client sees, said in their words: the user journeys and their states. On the frontend, Vitest and Testing Library tests next to each page and component; on the backend, the API tests below. No Gherkin tool is installed: the test names state the behaviour. | Next to the page or component (`*.test.tsx`), or the API test of the endpoint. |
| Integration (DB, Redis, LLM, HTTP) | Every adapter against the real thing or a recording of it: PostgreSQL 18 and Redis 8 in Testcontainers, a scripted `ChatModel` for the LLM adapters, recorded real responses for the source connectors, real HTTP for the edge. | Next to the adapter, `*IT`: in `backend/libs/core` for an adapter of a shared context (it runs on `CoreTestApplication`, with `@AsBackOffice` when the back office runs that code), in the app when the test needs the app (its controllers, security, schedules, the sources it registers, its own settings) or tests `libs/platform` on a Spring context ([ADR 0002](decisions/ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §10). |
| Contract | The API against `api/public.yaml`, `api/admin.yaml` and `api/common.yaml`: every response of the API tests is validated against the OpenAPI schema; `SchemaContractTest` holds the database schema to the domain enums; `ModularityTest` holds the module rules. | The API test of the endpoint; a new enum value in `SchemaContractTest`; a new table in `TableOwnersTest`. |
| Live | One smoke test per source adapter against the real API (`@Tag("live")`). A test whose key is missing is skipped, and the run prints it as `SKIPPED`. The keys are read from the environment (`H2_GITHUB_TOKEN`, `H2_HF_TOKEN`, `H2_LIVE_WEB_API_KEY`, `H2_NEBIUS_API_KEY`), never from the repository's `.env`. Not part of `test`: `./gradlew liveTest`, and the weekly `live` workflow. Every `liveTest` asked for runs its tests: the task is never up to date and never stored in or taken from the build cache. | Next to the adapter, `*LiveTest`. |
| Evaluation, performance, load | Relevance of search and of the guide on fixed sets ([eval/README.md](../../eval/README.md)), latency on a seeded catalog (`perfTest`), Gatling load tests by hand. Not part of `test`. | `backend/apps/public/src/test` under `eval`, `backend/loadtest`. |
| Browser end to end | **Not built.** No browser journey is automated and no tool is installed; frontend tests run in jsdom. Backlog: [S10.12.1](https://github.com/silvioincalza/helper2.ai/issues/357). | — |

Not covered by automated tests:

- Browser end-to-end tests: not built, and no tool is installed until the journeys are written
  ([S10.12.1](https://github.com/silvioincalza/helper2.ai/issues/357)). The guide journey is covered by `GuideApiIT` (API) and
  `HomeGuide.test.tsx` (UI).
- Live source checks from production: `liveTest` runs from a GitHub runner, not from the cluster's egress.

## Markers

Each layer skill keeps its own rows here: on every run it checks that the rows for its layer match its marker
reference and corrects them. Do not edit the rows by hand; rerun the layer skill instead.

| Layer | Skill | Kind | Marker to grep for |
|-------|-------|------|--------------------|
| All   | –     | Code | `// UC-XXX BR-YYY:` directly above the method, query condition, or validator that enforces the rule |
