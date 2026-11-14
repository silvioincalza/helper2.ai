# 0003 — Contract-first REST

**Depends on:** [0001](0001-stack-and-runtime.md), [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)

## Context
The backend (Java and Kotlin) and the frontend (TypeScript) change in the same pull request. Hand-written
types on both sides drift apart quietly. The application programming interface (API) also carries honesty rules (every date can be unknown,
every source has a status), and these rules must be visible in one place that both sides read.

## Decision
1. **One contract, three files.** The contract (OpenAPI 3.1.0, `servers: /api/v1`) is the source of truth for every
   Representational State Transfer (REST) endpoint and schema. Code follows the files, not the other way around.
   `api/public.yaml` holds the public API, `api/admin.yaml` the back office API (`/admin/**`), and `api/common.yaml`
   the types both use and the read-only paths the back office web app calls too (`/search`, `/sources`,
   `/categories`), referenced from both files. `admin.yaml` has its own `GET /me`: the public `/me` also deletes
   the account. A schema or a path has one meaning: `BundleContract` stops the build when two files define the same
   name differently.
2. **Server side generated, the shared part once.** The Gradle task `openApiGenerate` (generator `spring`,
   `interfaceOnly`, `useSpringBoot4`, `useJackson3`, `useTags`, the same options everywhere) writes controller
   interfaces and Data Transfer Objects (DTOs, suffix `Dto`). It runs before every Java and Kotlin compile.
   Controllers implement these interfaces (for example `MeController implements MeApi`). A tag is one generated
   interface, and one controller implements it. A tag holds the endpoints of one feature, so two features never
   share an interface: `/me/bookmarks` has the tag `bookmarks` and `/me/preferences` the tag `preferences`, not
   `me`; the home lists, the resource detail and the issue reports of the catalog have a tag each (`home-lists`,
   `resource-detail`, `issue-reports`).
   - The library `libs/web-contract` generates `common.yaml` alone, once: interfaces in `ai.helper2.api.common`
     (`SearchApi`, `SourcesApi`, `CategoriesApi`), DTOs in `ai.helper2.api.common.model`. It also holds the
     controllers of those three paths, so both apps serve them with the same classes.
   - Each app generates only its own paths, into `ai.helper2.api.generated` and `ai.helper2.api.generated.model`.
     `bundleContract` joins the files the app serves into one (`build/openapi/contract.yaml`, the components of
     `common.yaml` copied in under their own names), and writes a second file for the generator
     (`build/openapi/generation.yaml`): the same, without the paths of `common.yaml`.
   - The app maps every schema of `common.yaml` to the library's class of the same name (`schemaMappings` and
     `importMappings`). The list is not kept by hand: the build runs the generator on `common.yaml` and takes the
     names it makes, the objects nested in a shared schema included (`Me_loginProviders_inner` is
     `MeLoginProvidersInnerDto`). So no class is generated twice, and a DTO keeps its name; only its package says
     where it comes from.
3. **Client side generated.** `npm run gen:api` runs `openapi-typescript --enum-values` on `public.yaml` and
   `admin.yaml` and writes `frontend/src/api/schema.ts` and `admin-schema.ts` (not committed): the types, plus one array of values per enum, so client code that
   needs an enum's values at run time reads them from the contract too. Lint, typecheck, test and build regenerate
   it first.
4. **Responses checked against the contract.** API integration tests validate real response bodies against
   the component schemas of the whole joined contract (`contract.yaml`, shared paths included) with a JavaScript Object Notation (JSON) Schema 2020-12 validator
   (`ContractValidator`).
5. **Errors as problem+json, with Spring's own `ProblemDetail`.** `platform.ApiExceptionHandler` answers with Request for
   Comments (RFC) 9457 problem details (`application/problem+json`, `spring.mvc.problemdetails.enabled`). Every problem
   has a type from `platform.ProblemType`: the `type` URI `https://helper2.ai/problems/<code>` and the same stable
   `code` as an extension member, so a client reacts to the code, never to the title text. A use case throws
   `ApiProblem` with its type (`NotFoundException` and `BadRequestException` are two of them); the errors Spring answers
   on its own get a type by their status. Client mistakes are 400 with a fixed text and an unknown resource is 404;
   anything else is 500 with a fixed text, and the cause is logged, never echoed. The few places that answer before
   Spring MVC (servlet filters, the rate-limit interceptor, the guide stream's error event, the security filters'
   401 `sign-in-required` and 403 `forbidden` on `/api/**`, which says when the CSRF token is the reason, and the 401
   `sign-in-again` of a session without an account, [0036](../identity/0036-identity-and-the-admin-host.md)) write the
   same problem through `platform.Problems`. The codes are listed in the `Problem` schema of the contract.
6. **Enums by wire value.** `ContractEnumConverters` (in `libs/web-contract`) binds query parameters to the
   generated enums of both packages, shared and the app's own, by their value (for example `unknown`), using the
   generated `fromValue`. The generator's own `EnumConverterConfiguration` (package `org.openapitools.configuration`)
   is outside the scanned packages and does nothing; the library deletes its copy, so the class is not in two jars.
7. **One exception: the event stream.** `POST /guide/stream` is in the contract (tag `guide-stream`,
   `text/event-stream`, payload schemas, and the event names as the enum `GuideStreamEvent`), but its handler is
   hand-written and returns a Reactor `Flux<ServerSentEvent>`, because the generator produces JSON-only interfaces. The handler names each event
   with the generated `GuideStreamEventDto`; the client takes its step events from `guideStreamEventValues`.

### Code
- `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` — the contract.
- `backend/build-logic/src/main/kotlin/helper2.spring-app.gradle.kts` (tasks `bundleContract`, `openApiGenerate`) — an app's generation and its mappings.
- `backend/build-logic/src/main/kotlin/ai/helper2/buildlogic/SpringContract.kt` — the generator options, and the shared names (`sharedModelClasses`).
- `backend/build-logic/src/main/kotlin/ai/helper2/buildlogic/BundleContract.kt` — joins the files, one meaning per name; the generation file without the shared paths.
- `backend/libs/web-contract/build.gradle.kts` — generates `common.yaml` once.
- `backend/libs/web-contract/src/main/java/ai/helper2/api/package-info.java` — open module for generated types.
- `backend/libs/web-contract/src/main/java/ai/helper2/search/web/SearchController.java`, `CategoriesController.java`, `CardMapper.java` — the shared paths.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ApiExceptionHandler.java`, `ProblemType.java`, `ApiProblem.java`, `Problems.java` — problem details.
- `backend/libs/web-contract/src/main/java/ai/helper2/api/ContractEnumConverters.java` — enum binding.
- `backend/apps/public/src/test/java/ai/helper2/api/ContractValidator.java` — response validation in tests.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — the stream, events named by `GuideStreamEventDto`.
- `frontend/package.json` (script `gen:api`), `frontend/src/api/client.ts` — generated types, `ApiError`, step events from the contract.

## Alternatives considered
- Code-first (springdoc generates the spec) — the spec then follows the Java types, and the frontend
  depends on a running backend to get its types.
- GraphQL — one more runtime and query language for a small, fixed set of screens.
- Zalando Problem (`problem-spring-web`) for the errors — Spring has RFC 9457 support built in (`ProblemDetail`), and
  the library's Jackson module is for Jackson 2, while helper2 runs Jackson 3. One more dependency for what the
  framework does.
- Hand-written TypeScript types — they drift; nothing fails when they do.
- The shared web adapters as one source folder compiled into each app, against each app's own copy of the shared
  types — the same classes compiled twice, a folder with no build file of its
  own, and formatted by one of the apps.
- A hand-kept list of the shared schemas for the mappings — a new schema in `common.yaml` would be generated twice
  until someone adds it.

## Consequences
- A field rename breaks the compile on both sides in the same pull request.
- A change to `common.yaml` rebuilds the library and both apps; a change to `public.yaml` or `admin.yaml` regenerates
  only that app's own types.
- The contract documents the honesty rules (`DateFact`, source status, field origin) next to the schemas.
- The Server-Sent Events (SSE) stream follows the contract too: its event names come from the generated enum,
  and `GuideApiIT` validates each event's data against its schema.

### Known limits
- Generated API code is not committed: after a fresh checkout, run Gradle or npm once before the editor resolves the types.
- The guide SSE handler is hand-written, so the compiler checks only its event names ([0031](../guide/0031-guide-pipeline.md)).

## Diagrams
[flow](../../diagrams/flow-0003-contract-first-rest-api.svg) · [sequence](../../diagrams/sequence-0003-contract-first-rest-api.svg)

## Backlog
- Decides: [F1.2](https://github.com/silvioincalza/helper2.ai/issues/21).
- Open issues that change it: none.
