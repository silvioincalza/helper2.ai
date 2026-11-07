# 0068 — The backend as libraries and apps: one Gradle project per library, the app on top

**Status.** Accepted. **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0003](0003-contract-first-rest-api.md), [0047](../quality/0047-code-format.md)

## Context
The backend was one Gradle project. Every package could see every other one at compile time; only `ModularityTest`
kept the modules apart, after the build. The next step splits the backend into two apps, a public one and the back
office, with a static web image beside them. Both apps need the same catalog, search, identity and platform code.
That code must exist once, and an app must not be able to reach into the other's code.

## Decision
1. **Libraries and apps.** `backend/` is a Gradle multi-project. A library (`backend/libs/<name>`) holds a bounded
   context's domain, use cases and driven adapters (database, Redis, model calls). An app (`backend/apps/<name>`)
   holds the driving adapters (HTTP controllers, schedules), the generated contract and the wiring, and builds the
   boot jar. Today there is one app, `apps/helper2`.
2. **The libraries.** `kernel` (shared value types, was `shared`), `platform` (LLM client, resilience, cache, settings,
   quotas), `monetisation`, `identity`, `search`, `catalog`, `examples`, and `schema` (the Flyway migrations, resources
   only). A module goes into a library only when more than one app needs it; the rest stays in its app.
3. **Dependencies point one way.** A library never depends on an app. Between libraries: kernel ← platform ← search
   ← catalog; identity ← platform, monetisation; catalog and examples use identity's `AuditTrail`. Gradle refuses a
   cycle at configuration time, so a wrong direction fails before any test runs. `ModularityTest` still checks the
   Spring Modulith modules inside the app, as before.
4. **Packages stay.** A class keeps its package when it moves into a library, so component scanning, the Modulith
   modules and the ArchUnit rules see the same code. The jar of each library lands in the boot jar.
5. **One place for build settings.** `backend/build-logic/` holds the convention plugins: `helper2.format`
   (Spotless, [0047](../quality/0047-code-format.md)), `helper2.kotlin-library` (Java 25, Kotlin, the BOMs, test
   settings) and `helper2.spring-app` (Spring Boot, the contract generation, [0003](0003-contract-first-rest-api.md)).
   Every project uses one of them; versions live there and in `build-logic/build.gradle.kts`.
6. **Tests live with their code.** A library's tests run without the app. Helpers several projects need (fixtures,
   Testcontainers setup) are test fixtures of the lowest library that owns them. Tests that start the whole app stay
   in the app.
7. **No code outside a project.** A file left in the old `backend/src/` stops the build (`checkNoRootSources`): it
   would compile nowhere.

No runtime path: the boot jar holds the same classes and resources as before, so there is no sequence diagram.

## Consequences
- The two apps of the next step take only the libraries they need, and neither can import the other's code.
- A change in a library rebuilds and retests every app that uses it.
- A schedule that sits in a library today (catalog, identity and search retention, the catalog indexer, the shared
  cache and runtime settings refresh) moves to its app when there are two.
- More build files to read: one `build.gradle.kts` per project, three convention plugins.

## Considered
- **Keep one project, split by packages only.** No compile-time boundary; the second app would ship every class.
- **Published libraries** (a Maven registry) — versions to keep in step for code that changes in the same pull
  request, and a registry to run.
- **One project per Modulith module** (13 projects) — more build files for modules that only one app uses.

## Code
- `backend/settings.gradle.kts` — the projects.
- `backend/build-logic/build.gradle.kts`, `backend/build-logic/src/main/kotlin/helper2.*.gradle.kts` — the conventions.
- `backend/build.gradle.kts` — the version (ADR 0029) and `checkNoRootSources`.
- `backend/libs/*/build.gradle.kts`, `backend/apps/helper2/build.gradle.kts` — each project's dependencies.
- `deploy/Dockerfile` — builds `:apps:helper2:bootJar`.

Diagram: [flow](../../diagrams/flow-0068-backend-libraries-and-apps.svg).
