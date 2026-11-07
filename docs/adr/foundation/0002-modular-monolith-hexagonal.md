# 0002 — Modular monolith with Spring Modulith; hexagonal where business rules live; Kotlin domain, Java adapters

**Status.** Accepted. **Builds on:** [0001](0001-stack-and-runtime.md)

## Context
The app has several features: federated search, a catalog, an artificial intelligence (AI) guide, a background Scout and sign-in.
One process and one database are enough for the load. But the rules (ranking, merging, the evidence gate)
must stay testable without Spring, a database or a model, and features must not reach into each other's
internals. Microservices would add network calls and deploys without a scaling need.

## Decision
1. **One Spring Boot app, ten top-level packages.** Each is a Spring Modulith application module
   (`@ApplicationModule` in `package-info.java`):
   `search`, `catalog`, `guide`, `scout`, `setup`, `identity`, `sources`, and three open modules that every module
   may use: `shared` (value types), `platform` (errors, rate limits, resilience policies, the Large Language Model (LLM) layer, prompts, the shared caches of model answers and their table `ai_cache`) and
   `api` (generated contract types, see [0003](0003-contract-first-rest-api.md)). The open modules depend on no feature module: an adapter
   of a feature's port lives in that feature (the query translator, an LLM adapter of `search.QueryTranslator`, is
   in `search.internal`).
2. **Hexagonal where the business rules are.** The top-level packages `search`, `scout`, `guide`, `setup` and
   `shared` hold the rules in Kotlin. They talk to the outside only through ports (Kotlin interfaces), for
   example `SearchSource`, `SearchCache`, `ResultObserver`, `QueryTranslator` (search), `Collector`,
   `EnrichmentModel`, `ScoutStore`, `CatalogIndex` (scout), `GuideModel`, `GuideAnswerStore`, `SimilarQuestionIndex`, `SimilarQuestionRetention`, `QuestionEmbedder` (guide).
   The Java modules `identity` and `admin` have the same shape: their use cases are plain classes over ports in the
   base package (`Users`, `Bookmarks`, `AuditLog`, `RetentionPeriods` in identity, `ExampleDrafts` in admin), and the
   adapters implement them in `internal` (the JDBC stores, the JSON reader of drafted examples).
   A `@Bean` method in `config` wires each use case. A use case that must be all or nothing gets its transaction
   there, on a subclass with `@Transactional`, so the use case itself has no Spring type.
3. **Adapters in Java, in sub-packages.** `web` (controllers), `cache`, `config` and `internal`
   (Spring AI, Redis, Java Database Connectivity (JDBC)). The `sources` module implements `SearchSource` and `Collector` for each
   public application programming interface (API). The `catalog` module implements `ResultObserver`, `RecordedOutcomes` and two
   `SearchSource`s over PostgreSQL.
   The base package of a Java module holds only what other modules use. `catalog` keeps `ReviewQueue`,
   `Embedder`, `EnrichmentFacts` and `ResourceDetail` there, and its use cases in `catalog.usecase`; its adapters and
   its configuration (`CatalogConfiguration`, `CatalogResultObserver`, `CatalogSearchSource`, `SemanticSearchSource`,
   `ResourceCards`) are in `catalog.internal`.
   `sources` has no API: its base package is empty, and its own configuration wires its adapters into the ports of
   `search` and `scout`.
4. **One class per use case.** What a caller can ask a module to do lives in its `usecase` sub-package, one
   class per entry point: `SearchResources` (search), `AskGuide` (guide), `RunScout`, `StartScoutRun`,
   `EnrichResource` and `PurgeExpiredData` (scout), `GetResourceDetail`, `ListRecentResources`,
   `ListUpcomingEvents`, `ReportIssue`, `ListReviewItems`, `ResolveReviewItem` and `PurgeExpiredData` (catalog),
   `AddBookmark`, `RemoveBookmark`, `ListBookmarks`, `MergeLocalBookmarks`, `DeleteMyAccount`, `ListAuditEntries`
   and `PurgeExpiredData` (identity). A use case may delegate its steps to internal classes of its own package:
   `AskGuide` keeps the guards and the dispatch by intent, and `QuestionReader`, `PickAnswer`, `CompareAnswer`,
   `ExplainAnswer`, `DateAnswer` and `ReasonCheckStep` do the work; no other module sees them. Controllers and schedulers call use cases for what a module does;
   a read-only lookup of a store or port (run history, source health) may stay direct. Models, ports and rules (for
   example `GuideValidator`, `PlannedQueries`, `EvidenceValidator`) stay at the module root, so adapters never
   depend on a use case. A use case another module calls is exposed with `@NamedInterface("usecase")`
   (`search.usecase` for the guide, `catalog.usecase` for the setup guide and the admin page). The Scout reads and
   writes catalog tables only through `catalog.enrichment` (`@NamedInterface("enrichment")`): `EnrichmentCandidates`
   (the backlog and a resource's source text), `EnrichmentResults` (what a run leaves) and `SourceOutcomes` (how a
   source's last run ended, for the Sources page). The Scout keeps its own
   rules (the 40-character text bar, three FAILED attempts) and passes them in; the SQL stays in the catalog. Kotlin use
   cases are plain classes wired by `@Bean` methods in `config`/`internal`; catalog use cases are Spring
   services over SQL, because the catalog is not hexagonal.
5. **Rules as tests.** `ModularityTest` runs on every build:
   - `ApplicationModules.verify()`: no cycles, only public types of other modules are used;
   - the domain packages do not depend on `org.springframework..`, `tools.jackson..` or `java.sql..`;
   - the domain packages do not depend on any `web`, `cache`, `config` or `internal` package;
   - both rules also hold for the Kotlin `usecase` packages (`search.usecase`, `guide.usecase`, `scout.usecase`), and for
     `identity`, `identity.usecase`, `admin` and `admin.usecase`;
   - no class outside `sources` depends on a class of `sources`;
   - `tablesHaveOneOwner`: every table the migrations (`V*__*.sql`) create has one owning module, and only that module's code names
     it in SQL (`FROM`, `JOIN`, `INTO`, `UPDATE`). There are no exceptions.

## Consequences
- Domain tests are plain unit tests with fakes (for example `SearchResourcesTest`, `GuideValidatorTest`).
- A use case is found by its name, not inside a large service file; its tests sit next to it
  (`search/usecase/SearchResourcesTest`, `guide/usecase/AskGuideTest`, `scout/usecase/RunScoutTest`).
- A boundary break fails the build, not a review.
- Kotlin stays in the rule packages, so most framework code is Java.

## Alternatives considered
- Microservices — more deploys, network failure between features, and no part needs its own scaling.
- Layered packages (controller/service/repository) — boundaries by technical role, not by feature;
  nothing stops one feature from using another's repository.
- Hexagonal everywhere — `catalog` and `sources` are mostly mapping and Structured Query Language (SQL); ports there
  would only add indirection. `identity` has ports since its use cases are checked like the Kotlin ones.
- A closed `llm` module with a named interface — the LLM layer stays in open `platform` (owner decision,
  4 Nov 2026): every feature module calls it, so a closed module would only add a named interface for all of it.
- Only Kotlin or only Java — Kotlin data classes and null safety fit the rules; Spring and generated
  code read well in Java.

## Code
- `backend/{libs,apps}/*/src/main/java/ai/helper2/*/package-info.java` — module declarations.
- `backend/apps/public/src/test/java/ai/helper2/architecture/ModularityTest.java` — module and hexagon rules.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Ports.kt`, `.../scout/Ports.kt`, `.../scout/Scout.kt`,
  `.../guide/Guide.kt`, `.../guide/GuideCost.kt` — ports.
- `backend/libs/identity/src/main/java/ai/helper2/identity/{Users,Bookmarks,AuditLog,RetentionPeriods}.java`, `.../admin/ExampleDrafts.java`
  — ports of the Java use cases; `.../identity/config/IdentityUseCaseConfiguration.java`,
  `.../admin/config/AdminUseCaseConfiguration.java` — the use cases and their transactions.
- `backend/{libs,apps}/*/src/main/kotlin/ai/helper2/{search,guide,scout}/usecase/`, `backend/{libs,apps}/*/src/main/java/ai/helper2/{catalog,identity,admin}/usecase/`
  — use cases; `.../{search,catalog}/usecase/package-info.java` — named interfaces.
- `backend/{libs,apps}/*/src/main/java/ai/helper2/search/{web,cache,config}`, `.../guide/{web,internal}`,
  `.../scout/{web,internal}`, `.../catalog/internal`, `.../sources/*` — adapters.

## Diagrams
[flow](../../diagrams/flow-0002-modular-monolith-hexagonal.svg) · [sequence](../../diagrams/sequence-0002-modular-monolith-hexagonal.svg)
Modules, ports and adapters: one view per module group, [context](../../diagrams/architecture-context.svg) · [search](../../diagrams/architecture-search.svg) · [guide](../../diagrams/architecture-guide.svg) · [index](../../diagrams/architecture-index.svg) · [setup](../../diagrams/architecture-setup.svg) · [platform](../../diagrams/architecture-platform.svg).

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
