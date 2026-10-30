# 0003 — Contract-first REST with OpenAPI 3.1; generated server interfaces and TypeScript types; problem+json

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0002](0002-modular-monolith-hexagonal.md)

## Context
The backend (Java and Kotlin) and the frontend (TypeScript) change in the same pull request. Hand-written
types on both sides drift apart quietly. The application programming interface (API) also carries honesty rules (every date can be unknown,
every source has a status), and these rules must be visible in one place that both sides read.

## Decision
1. **One contract.** `api/openapi.yaml` (OpenAPI 3.1.0, `servers: /api/v1`) is the source of truth for every
   Representational State Transfer (REST) endpoint and schema. Code follows the file, not the other way around.
2. **Server side generated.** The Gradle task `openApiGenerate` (generator `spring`, `interfaceOnly`,
   `useSpringBoot4`, `useJackson3`, `useTags`) writes controller interfaces to `ai.helper2.api.generated` and
   Data Transfer Objects (DTOs, suffix `Dto`) to `ai.helper2.api.generated.model`. It runs before every
   Java and Kotlin compile. Controllers implement these interfaces (for example `MeController implements MeApi`).
3. **Client side generated.** `npm run gen:api` runs `openapi-typescript` and writes `frontend/src/api/schema.d.ts`
   (not committed). Lint, typecheck, test and build regenerate it first.
4. **Responses checked against the contract.** API integration tests validate real response bodies against
   the component schemas with a JavaScript Object Notation (JSON) Schema 2020-12 validator (`ContractValidator`).
5. **Errors as problem+json.** `platform.ApiExceptionHandler` answers with Request for Comments (RFC) 9457 problem details
   (`application/problem+json`). Only client mistakes are 400; anything else is 500 with a fixed text,
   and the cause is logged, never echoed.
6. **Enums by wire value.** `ContractEnumConverters` binds query parameters to generated enums by their
   value (for example `unknown`), using the generated `fromValue`.
7. **One exception: the event stream.** `POST /guide/stream` is in the contract (tag `guide-stream`,
   `text/event-stream`, event names and payload schemas), but its handler is hand-written with
   `SseEmitter`, because the generator produces JSON-only interfaces.

## Consequences
- A field rename breaks the compile on both sides in the same pull request.
- The contract documents the honesty rules (`DateFact`, source status, field origin) next to the schemas.
- Generated code is not committed. After a fresh checkout, run Gradle or npm once before the editor can
  resolve the generated types.
- The Server-Sent Events (SSE) handler is not generated, so the compiler does not tie it to the contract.
  `GuideApiIT` validates each event's data against its schema instead.

## Alternatives considered
- Code-first (springdoc generates the spec) — the spec then follows the Java types, and the frontend
  depends on a running backend to get its types.
- GraphQL — one more runtime and query language for a small, fixed set of screens.
- Hand-written TypeScript types — they drift; nothing fails when they do.

## Code
- `api/openapi.yaml` — the contract.
- `backend/build.gradle.kts` (task `openApiGenerate`) — server generation settings.
- `backend/src/main/java/ai/helper2/api/package-info.java` — open module for generated types.
- `backend/src/main/java/ai/helper2/platform/ApiExceptionHandler.java` — problem details.
- `backend/src/main/java/ai/helper2/platform/ContractEnumConverters.java` — enum binding.
- `backend/src/test/java/ai/helper2/api/ContractValidator.java` — response validation in tests.
- `frontend/package.json` (script `gen:api`), `frontend/src/api/client.ts` — generated types and `ApiError`.

## Diagrams
[flow](../diagrams/flow-0003-contract-first-rest-api.svg) · [sequence](../diagrams/sequence-0003-contract-first-rest-api.svg)

## Roadmap
Nothing open.
