# 0002 — Modules: modular monolith, hexagonal where the rules live

**Depends on:** [0001](0001-stack-and-runtime.md), [0003](0003-contract-first-rest.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md), [0047](../operations/0047-code-format-and-dead-code-audit.md)

## Context
Helper2 has several features: federated search, a catalog, an artificial intelligence (AI) guide, a background Scout
and sign-in. One database is enough for the load. But the rules (ranking, merging, the evidence gate) must stay
testable without Spring, a database or a model, and features must not reach into each other's internals.
Microservices would add network calls and deploys without a scaling need.

The backend runs as two apps, a public one and the back office, with a static web image beside them
([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)). Both apps need the same catalog, search, identity and platform code,
and both serve the same search, sources and categories paths. That code must exist once, and an app must not be able
to reach into the other's code.

## Decision
1. **Libraries and apps.** `backend/` is a Gradle multi-project. A library (`backend/libs/<name>`) holds a bounded
   context's domain, use cases and driven adapters (database, Redis, model calls). An app (`backend/apps/<name>`)
   holds the driving adapters (HTTP controllers, schedules), its own generated contract and the wiring, and builds
   the boot jar. There are two apps, `apps/public` and `apps/backoffice`.
2. **The libraries.** `kernel` (shared value types, package `shared`, no third-party library), `platform` (LLM client, resilience, cache,
   settings, quotas), `identity`, `search`, `catalog`, `examples`, `schema` (the Flyway migrations,
   resources only), `sources` (the live search sources and the collectors' descriptors) and `web-contract`. A module
   goes into a library only when more than one app needs it; the rest stays in its app.
   - `web-contract` is the web part both apps serve the same way: the types of `api/common.yaml`, generated once
     into `ai.helper2.api.common` ([0003](0003-contract-first-rest.md)), and the controllers of its paths
     (`SearchController`, `SourcesController`, `CategoriesController`), `CardMapper` and
     `ContractEnumConverters`. It is a library like the others: each class is compiled once, and its jar lands in
     both boot jars.
3. **Dependencies point one way.** A library never depends on an app. Between libraries: kernel ← platform ← search
   ← catalog; search ← web-contract; identity ← platform. The admin use cases of catalog, examples
   and scout write the audit through `platform.AuditTrail`; identity's `JdbcAuditLog` implements it, so the catalog
   does not pull identity or its security starters. Gradle refuses a cycle at configuration time, so a wrong direction fails before any test runs. The
   apps depend on the libraries with `implementation`: nothing builds on an app, so nothing needs its dependencies
   passed on.
4. **Spring Modulith modules, the same in every jar.** Each top-level package is an application module
   (`@ApplicationModule` in `package-info.java`): `search`, `catalog`, `examples`, `identity` and
   `sources` from the libraries; `guide`, `setup` and `site` (pages, policy, monetisation, the public CSP,
   [0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md) §15) in the public app; `admin` and `scout` in the
   back office. Three open modules every module may use: `shared` (value types), `platform` (errors, rate limits,
   resilience policies, the Large Language Model (LLM) layer, prompts, the shared caches of model answers and their
   table `ai_cache`) and `api` (the generated contract types, the shared ones in `api.common` and each app's own in
   `api.generated`, and `ContractEnumConverters`). The open modules depend on no feature module: an adapter of a
   feature's port lives in that feature (the query translator, an LLM adapter of `search.QueryTranslator`, is in
   `search.internal`).
   A class keeps its package when it moves into a library, so component scanning, the modules and the ArchUnit
   rules see the same code. A library puts no class into another library's package: `ContractEnumConverters` lives
   in `api`, not in `platform`, which is the platform library's package.
5. **Hexagonal where the business rules are.** The packages `search`, `scout`, `guide`, `setup` and `shared` hold
   the rules in Kotlin. They talk to the outside only through ports (Kotlin interfaces), for example `SearchSource`,
   `SearchCache`, `ResultObserver`, `QueryTranslator` (search), `Collector`, `EnrichmentModel`, `ScoutStore`,
   `CatalogIndex` (scout), `GuideModel`, `GuideAnswerStore`, `SimilarQuestionIndex`, `SimilarQuestionRetention`,
   `QuestionEmbedder` (guide).
   The Java use cases of `identity`, `catalog`, `examples` and `admin` have the same shape: plain classes over ports
   in the base package (`Users`, `Bookmarks`, `AuditLog` in identity, `DraftQueue`, `UnitOfWork` in examples), and
   the adapters implement them in `internal` (the JDBC stores, the drafts in the review queue).
   A `@Bean` method in `config` wires each use case; a use case only one app runs (a scheduled purge) is wired next
   to its schedule, in that app. The catalog's back office part is not scanned: `CatalogBackOfficeConfiguration`
   (the curation, review, indexing and purge use cases) and `CatalogBackOfficeAdapters` (their JDBC stores, with no
   stereotype) carry no `@Configuration`, and only `BackOfficeApplication` imports them (the named interface
   `backoffice`). The public app's context
   has none of them (`NoBackOfficeCatalogIT`). The examples library is split the same way: the public app builds
   `ListHomeExamples` over the read port `ServedExamples` (`JdbcServedExamples`) and nothing else;
   `ExamplesBackOfficeConfiguration` (the admin list and counts, add, change, remove, the check, the drafts) and
   `ExamplesBackOfficeAdapters` (the pool's store `JdbcHomeExamples`, the search, the titles, the draft sources, the
   draft queue, the drafter with its budget and settings) are imported only by `BackOfficeApplication`
   (`NoBackOfficeExamplesIT`, `BackOfficeExamplesIT`). A use case that must be all or nothing gets its transaction there, on a subclass
   with `@Transactional`, so the use case itself has no Spring type.
6. **Adapters in Java, in sub-packages.** `web` (controllers), `cache`, `config` and `internal` (Spring AI, Redis,
   Java Database Connectivity (JDBC)). The `sources` module implements `SearchSource` and `Collector` for each public
   application programming interface (API). The `catalog` module implements `ResultObserver`, `RecordedOutcomes` and
   two `SearchSource`s over PostgreSQL.
   The base package of a Java module holds its ports and what other modules use. `catalog` keeps its ports there
   (`CatalogWriter`, the public app's one write path; `Resources`, `ReviewQueue`, `JobMarkers`, `EmbeddingStore` and
   the others), its records (`ResourceRow`, `ResourceDetail`) and `ResourceCards`, and its use cases in
   `catalog.usecase`; its JDBC adapters (`Jdbc*`), `CatalogConfiguration`, `CatalogResultObserver`,
   `CatalogSearchSource` and `SemanticSearchSource` are in `catalog.internal`.
   `sources` has no API: its base package is empty, and its own configuration wires its adapters into the ports of
   `search` and `scout`.
7. **One class per use case.** What a caller can ask a module to do lives in its `usecase` sub-package, one class per
   entry point: `SearchResources` (search), `AskGuide` (guide), `RunScout`, `StartScoutRun`, `EnrichResource` and
   `PurgeExpiredData` (scout), `GetResourceDetail`, `ListRecentResources`, `ListUpcomingEvents`, `ReportIssue`,
   `ListReviewItems`, `ResolveReviewItem` and `PurgeExpiredData` (catalog), `AddBookmark`, `RemoveBookmark`,
   `ListBookmarks`, `MergeLocalBookmarks`, `DeleteMyAccount`, `ListAuditEntries`, `PurgeInactiveAccounts` and
   `PurgeExpiredAuditEntries` (identity).
   A use case may delegate its steps to internal classes of its own package: `AskGuide` keeps the guards and the
   dispatch by intent, and `QuestionReader`, `PickAnswer`, `CompareAnswer`, `ExplainAnswer`, `DateAnswer` and
   `ReasonCheckStep` do the work; no other module sees them. Controllers and schedulers call use cases for what a
   module does, a read-only lookup too (`GetReviewItem`, `ListScoutRuns`); the back office's `ModularityTest`
   fails when a controller calls a port of catalog, scout or examples. Models, ports
   and rules (for example `GuideValidator`, `PlannedQueries`, `EvidenceValidator`) stay at the module root, so
   adapters never depend on a use case. A use case another module calls is exposed with `@NamedInterface("usecase")`
   (`search.usecase` for the guide, `catalog.usecase` for the setup guide and the admin page). The Scout reads and
   writes catalog tables only through `catalog.enrichment` (`@NamedInterface("enrichment")`): `EnrichmentCandidates`
   (the backlog and a resource's source text), `EnrichmentResults` (what a run leaves) and `SourceOutcomes` (how a
   source's last run ended, for the Sources page). The Scout keeps its own rules (the 40-character text bar, three
   FAILED attempts) and passes them in; the SQL stays in the catalog. Kotlin use cases are plain classes wired by
   `@Bean` methods in `config`/`internal`; so are the Java use cases of identity and catalog (catalog's in
   `catalog.config`, with the transactions on subclasses there).
8. **Rules as tests.** `ModularityTest` runs in each app on every build, over the app's code and the libraries it
   takes:
   - `ApplicationModules.verify()`: no cycles, only public types of other modules are used;
   - the domain packages do not depend on `org.springframework..`, `tools.jackson..` or `java.sql..`;
   - the domain packages do not depend on any `web`, `cache`, `config` or `internal` package;
   - both rules also hold for the Kotlin `usecase` packages (`search.usecase`, `guide.usecase`, `scout.usecase`), and
     for `identity`, `identity.usecase`, `catalog`, `catalog.usecase`, `catalog.enrichment`, `admin` and
     `admin.usecase`;
   - no class outside `sources` depends on a class of `sources`;
   - `tablesHaveOneOwner`: every table the migrations (`V*__*.sql`) create has one owning module, and only that
     module's code names it in SQL (`FROM`, `JOIN`, `INTO`, `UPDATE`). There are no exceptions.

   A library also checks its own rules on its classes alone, without an app: `IdentityArchitectureTest`,
   `CatalogArchitectureTest`, `ExamplesArchitectureTest` (no app code, no use case into `internal`, the core
   framework-free) and `WebContractArchitectureTest`. The last one fails if the library uses an app's generated types
   (`ai.helper2.api.generated`).
9. **One place for build settings.** `backend/build-logic/` holds the convention plugins: `helper2.format` (Spotless,
   [0047](../operations/0047-code-format-and-dead-code-audit.md)), `helper2.kotlin-library` (Java 25, Kotlin, the BOMs,
   test settings) and `helper2.spring-app` (Spring Boot, the app's contract generation,
   [0003](0003-contract-first-rest.md)). The generator options are in one place, `SpringContract.kt`, used by
   both apps and by `web-contract`. Every project uses one of the plugins; versions live there and in
   `build-logic/build.gradle.kts`.
10. **Tests live with their code.** A library's tests run without the app (`CardMapperTest` in `web-contract`).
    Helpers several projects need (fixtures, Testcontainers setup) are test fixtures of the lowest library that owns
    them. Tests that start the whole app stay in the app, the contract tests of the shared paths included: they
    check each app's answers against that app's whole contract.
11. **No code outside a project.** A file in `backend/src/` stops the build (`checkNoRootSources`): it
    would compile nowhere.

### Code
- `backend/settings.gradle.kts` — the projects.
- `backend/build-logic/build.gradle.kts`, `backend/build-logic/src/main/kotlin/helper2.*.gradle.kts`,
  `backend/build-logic/src/main/kotlin/ai/helper2/buildlogic/SpringContract.kt` — the conventions.
- `backend/build.gradle.kts` — the version ([0045](../operations/0045-releases-and-versions.md)) and `checkNoRootSources`.
- `backend/libs/*/build.gradle.kts`, `backend/apps/public/build.gradle.kts`, `backend/apps/backoffice/build.gradle.kts`
  — each project's dependencies.
- `backend/{libs,apps}/*/src/main/java/ai/helper2/*/package-info.java` — module declarations.
- `backend/apps/{public,backoffice}/src/test/java/ai/helper2/architecture/ModularityTest.java` — module and hexagon
  rules; `backend/libs/identity/src/test/java/ai/helper2/identity/architecture/IdentityArchitectureTest.java`,
  `backend/libs/catalog/src/test/java/ai/helper2/catalog/architecture/CatalogArchitectureTest.java`,
  `backend/libs/examples/src/test/java/ai/helper2/examples/architecture/ExamplesArchitectureTest.java`,
  `backend/libs/web-contract/src/test/java/ai/helper2/api/architecture/WebContractArchitectureTest.java` — a
  library's own rules.
- `backend/libs/web-contract/src/main/java/ai/helper2/search/web/` (`SearchController`, `SourcesController`,
  `CategoriesController`, `CardMapper`), `backend/libs/web-contract/src/main/java/ai/helper2/api/ContractEnumConverters.java` — the shared
  web adapters.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Ports.kt`, `.../scout/Ports.kt`, `.../scout/Scout.kt`,
  `.../guide/Guide.kt`, `.../guide/GuideCost.kt` — ports.
- `backend/libs/identity/src/main/java/ai/helper2/identity/{Users,Bookmarks,AuditLog}.java`,
  `backend/libs/examples/src/main/kotlin/ai/helper2/examples/ExampleDrafts.kt` (`DraftQueue`, `UnitOfWork`) — ports
  of the Java use cases; `.../identity/config/IdentityUseCaseConfiguration.java`,
  `.../examples/config/ExamplesUseCaseConfiguration.java` (both apps),
  `.../examples/config/ExamplesBackOfficeConfiguration.java` (the back office only),
  `.../admin/config/AdminUseCaseConfiguration.java` — the use cases and their transactions.
- `backend/{libs,apps}/*/src/main/kotlin/ai/helper2/{search,guide,scout}/usecase/`,
  `backend/{libs,apps}/*/src/main/java/ai/helper2/{catalog,identity,examples,admin}/usecase/` — use cases;
  `.../{search,catalog}/usecase/package-info.java` — named interfaces.
- `backend/{libs,apps}/*/src/main/java/ai/helper2/search/{web,cache,config}`, `.../guide/{web,internal}`,
  `.../scout/{web,internal}`, `.../catalog/internal`, `.../sources/*` — adapters.
- `deploy/Dockerfile`, `deploy/backoffice.Dockerfile` — build `:apps:public:bootJar` and `:apps:backoffice:bootJar`.

## Alternatives considered
- Microservices — more deploys, network failure between features, and no part needs its own scaling.
- One project, split by packages only — no compile-time boundary; the second app would ship every class.
- Layered packages (controller/service/repository) — boundaries by technical role, not by feature; nothing stops one
  feature from using another's repository.
- Hexagonal everywhere — `sources` is mostly mapping; ports there would only add indirection. `identity` and
  `catalog` have ports since their use cases are checked like the Kotlin ones.
- A closed `llm` module with a named interface — the LLM layer stays in open `platform` (owner
  decision): every feature module calls it, so a closed module would only add a named interface for all of it.
- Published libraries (a Maven registry) — versions to keep in step for code that changes in the same pull request,
  and a registry to run.
- One project per Modulith module (13 projects) — more build files for modules that only one app uses.
- The shared web adapters as a source folder compiled into each app — the same classes compiled twice, each time
  against that app's own copy of the shared types, in a folder with no build file of its own.
- Only Kotlin or only Java — Kotlin data classes and null safety fit the rules; Spring and generated code read well
  in Java.

## Consequences
- Domain tests are plain unit tests with fakes (for example `SearchResourcesTest`, `GuideValidatorTest`).
- A use case is found by its name, not inside a large service file; its tests sit next to it
  (`search/usecase/SearchResourcesTest`, `guide/usecase/AskGuideTest`, `scout/usecase/RunScoutTest`).
- A boundary break fails the build, not a review: a wrong Gradle dependency at configuration time, a wrong class
  dependency in `ModularityTest` or in a library's own architecture test.
- Kotlin stays in the rule packages, so most framework code is Java.
- Each app takes only the libraries it needs, and neither can import the other's code. The shared web adapters are
  compiled once; both apps run the same classes.
- A change in a library rebuilds and retests every app that uses it (`changes.sh` builds all three images for any
  change under `backend/libs/`, [0044](../operations/0044-ci-cd-and-supply-chain.md)).
- A library declares no schedule: each app declares the jobs it runs (catalog, identity, search and shared-cache
  retention, the runtime-settings purge, the embedding indexer, the category backfill, the home-example check), so
  every job runs in one app
  ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)). Only the per-replica runtime-settings refresh stays in the
  platform library.
- More build files to read: one `build.gradle.kts` per project, three convention plugins.

### Known limits
- Two backend languages raise the entry cost for a new developer; Kotlin stays in the rule packages ([0001](0001-stack-and-runtime.md)).

## Diagrams
[flow](../../diagrams/flow-0002-modular-monolith-libraries-and-apps.svg) · [sequence](../../diagrams/sequence-0002-modular-monolith-libraries-and-apps.svg)
Modules, ports and adapters: one view per module group, [context](../../diagrams/architecture-context.svg) · [search](../../diagrams/architecture-search.svg) · [guide](../../diagrams/architecture-guide.svg) · [index](../../diagrams/architecture-index.svg) · [setup](../../diagrams/architecture-setup.svg) · [platform](../../diagrams/architecture-platform.svg).

## Backlog
- Decides: [F1.1](https://github.com/silvioincalza/helper2.ai/issues/20).
- Open issues that change it:
  - [S1.11.1](https://github.com/silvioincalza/helper2.ai/issues/180): a closed `llm` module with a small public API.
  - [S1.11.2](https://github.com/silvioincalza/helper2.ai/issues/181): controllers return views owned by the use cases.
