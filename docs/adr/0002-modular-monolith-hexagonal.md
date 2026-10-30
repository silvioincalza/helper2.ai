# 0002 — Modular monolith with Spring Modulith; hexagonal where business rules live; Kotlin domain, Java adapters

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md)

## Context
The app has several features: federated search, a catalog, an artificial intelligence (AI) guide, a background Scout and sign-in.
One process and one database are enough for the load. But the rules (ranking, merging, the evidence gate)
must stay testable without Spring, a database or a model, and features must not reach into each other's
internals. Microservices would add network calls and deploys without a scaling need.

## Decision
1. **One Spring Boot app, ten top-level packages.** Each is a Spring Modulith application module
   (`@ApplicationModule` in `package-info.java`):
   `search`, `catalog`, `guide`, `scout`, `setup`, `identity`, `sources`, and three open modules that every module
   may use: `shared` (value types), `platform` (errors, rate limits, resilience policies, the Large Language Model (LLM) layer, prompts) and
   `api` (generated contract types, see [0003](0003-contract-first-rest-api.md)).
2. **Hexagonal where the business rules are.** The top-level packages `search`, `scout`, `guide`, `setup` and
   `shared` hold the rules in Kotlin. They talk to the outside only through ports (Kotlin interfaces), for
   example `SearchSource`, `SearchCache`, `ResultObserver`, `QueryTranslator` (search), `Collector`,
   `EnrichmentModel`, `ScoutStore`, `CatalogIndex` (scout), `GuideModel`, `GuideAnswerStore` (guide).
3. **Adapters in Java, in sub-packages.** `web` (controllers), `cache`, `config` and `internal`
   (Spring AI, Redis, Java Database Connectivity (JDBC)). The `sources` module implements `SearchSource` and `Collector` for each
   public application programming interface (API). The `catalog` module implements `ResultObserver`, `RecordedOutcomes` and two
   `SearchSource`s over PostgreSQL.
4. **One class per use case.** What a caller can ask a module to do lives in its `usecase` sub-package, one
   class per entry point: `SearchResources` (search), `AskGuide` (guide), `RunScout`, `StartScoutRun`,
   `EnrichResource` and `PurgeExpiredData` (scout), `GetResourceDetail`, `ListRecentResources`,
   `ListUpcomingEvents`, `ReportIssue`, `ListReviewItems`, `ResolveReviewItem` and `PurgeExpiredData` (catalog),
   `AddBookmark`, `RemoveBookmark`, `ListBookmarks`, `MergeLocalBookmarks`, `DeleteMyAccount`, `ListAuditEntries`
   and `PurgeExpiredData` (identity). Controllers and schedulers call use cases for what a module does;
   a read-only lookup of a store or port (run history, source health) may stay direct. Models, ports and rules (for
   example `GuideValidator`, `PlannedQueries`, `EvidenceValidator`) stay at the module root, so adapters never
   depend on a use case. A use case another module calls is exposed with `@NamedInterface("usecase")`
   (`search.usecase` for the guide, `catalog.usecase` for the setup guide and the admin page). Kotlin use
   cases are plain classes wired by `@Bean` methods in `config`/`internal`; catalog use cases are Spring
   services over SQL, because the catalog is not hexagonal.
5. **Rules as tests.** `ModularityTest` runs on every build:
   - `ApplicationModules.verify()`: no cycles, only public types of other modules are used;
   - the domain packages do not depend on `org.springframework..`, `tools.jackson..` or `java.sql..`;
   - the domain packages do not depend on any `web`, `cache`, `config` or `internal` package;
   - both rules also hold for the Kotlin `usecase` packages (`search.usecase`, `guide.usecase`, `scout.usecase`).

## Consequences
- Domain tests are plain unit tests with fakes (for example `SearchResourcesTest`, `GuideValidatorTest`).
- A use case is found by its name, not inside a large service file; its tests sit next to it
  (`search/usecase/SearchResourcesTest`, `guide/usecase/AskGuideTest`, `scout/usecase/RunScoutTest`).
- `setup` has no `usecase` package yet: its split waits for the tool-guide work in that module.
- A boundary break fails the build, not a review.
- The `guide` domain uses `platform.llm.CallCounter` and `DailyBudget`. This is allowed because
  `platform` is open, but it means the LLM layer is visible to every module.
- Modulith checks Java types only. The `scout` module writes enrichment tables that `catalog` reads;
  this coupling through the schema is not checked.
- Two languages in one build. Kotlin is limited to the rule packages, so most framework code is Java.

## Alternatives considered
- Microservices — more deploys, network failure between features, and no part needs its own scaling.
- Layered packages (controller/service/repository) — boundaries by technical role, not by feature;
  nothing stops one feature from using another's repository.
- Hexagonal everywhere — `catalog`, `identity` and `sources` are mostly mapping and Structured Query Language (SQL); ports there
  would only add indirection.
- Only Kotlin or only Java — Kotlin data classes and null safety fit the rules; Spring and generated
  code read well in Java.

## Code
- `backend/src/main/java/ai/helper2/*/package-info.java` — module declarations.
- `backend/src/test/java/ai/helper2/architecture/ModularityTest.java` — module and hexagon rules.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt`, `.../scout/Ports.kt`, `.../scout/Scout.kt`,
  `.../guide/Guide.kt`, `.../guide/GuideCost.kt` — ports.
- `backend/src/main/kotlin/ai/helper2/{search,guide,scout}/usecase/`, `backend/src/main/java/ai/helper2/catalog/usecase/`
  — use cases; `.../{search,catalog}/usecase/package-info.java` — named interfaces.
- `backend/src/main/java/ai/helper2/search/{web,cache,config}`, `.../guide/{web,internal}`,
  `.../scout/{web,internal}` — adapters.

## Diagrams
[flow](../diagrams/flow-0002-modular-monolith-hexagonal.svg) · [sequence](../diagrams/sequence-0002-modular-monolith-hexagonal.svg)
Modules, ports and adapters: [software architecture](../diagrams/software-architecture.svg).

## Roadmap
- One owner per table, so the schema has no hidden coupling: [roadmap](../roadmap.md#8-architecture).
- A closed `llm` module instead of the LLM layer in open `platform`: [roadmap](../roadmap.md#8-architecture).
- Move the source and catalog adapters into `internal` packages: [roadmap](../roadmap.md#8-architecture).
