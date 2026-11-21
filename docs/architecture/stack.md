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

## Markers

Each layer skill keeps its own rows here: on every run it checks that the rows for its layer match its marker
reference and corrects them. Do not edit the rows by hand; rerun the layer skill instead.

| Layer | Skill | Kind | Marker to grep for |
|-------|-------|------|--------------------|
| All   | –     | Code | `// UC-XXX BR-YYY:` directly above the method, query condition, or validator that enforces the rule |
