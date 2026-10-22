# 0001 — Stack and runtime: Java 25 + Kotlin, Spring Boot 4.1, Spring AI 2.0, React SPA served by Spring, JVM image

**Status.** Accepted (2026-10-27).

## Context
Helper2.ai is built and run by one person. It must call public application programming interfaces (APIs) and a Large Language Model (LLM),
keep a catalog in a database, and serve a web page. It runs on the owner's own Kubernetes cluster.
The stack must be well known, typed end to end, and cheap to operate: one image, one deploy, one log.

## Decision
1. **Backend.** Java 25 (Gradle toolchain, Long-Term Support release) and Kotlin 2.4.21 on Spring Boot 4.1.2.
   Spring AI 2.0.2 (OpenAI-compatible starter) for model calls, Spring Modulith 2.1.2 for module rules.
   Gradle 9.8.2; the foojay resolver downloads the Java Development Kit (JDK) 25 when it is missing.
2. **Threads.** Virtual threads are on (`spring.threads.virtual.enabled: true`). Request code and source
   calls stay blocking and simple; there is no reactive stack.
3. **Frontend.** A single-page application (SPA): React 19.3.1, React Router 8.5.0, i18next, built with
   Vite 8.3.5 and TypeScript 6.0.3. Node.js 26 in the build image (`engines: >=24`).
4. **One artifact.** `deploy/Dockerfile` has three stages: Node builds the SPA, the JDK stage copies it into
   `backend/src/main/resources/static` and runs `bootJar`, the runtime stage is `eclipse-temurin:25-jre`
   as user 10001. The SPA and `/api/v1` come from the same origin, so there is no Cross-Origin Resource
   Sharing (CORS) setup.
5. **SPA serving.** `platform.SinglePageApp` is the only static handler (`spring.web.resources.add-mappings: false`):
   `/assets/**` is cached for 365 days as immutable; any other path without a file extension, outside
   `/api`, `/oauth2`, `/login`, `/logout` and `/actuator`, returns `index.html` with `no-cache`.
6. **Java Virtual Machine (JVM) flags.** `-XX:MaxRAMPercentage=60` (heap is 60% of the container limit) and
   `-XX:+ExitOnOutOfMemoryError` (the pod restarts instead of running broken).
7. **Local development.** Vite on port 5173 proxies `/api`, `/oauth2`, `/login` and `/logout` to the
   backend on 8080. `compose.yaml` starts Postgres and Redis; the `dev` profile starts them for the app.

## Consequences
- One image holds the API and the user interface (UI). A deploy changes both at once, and they can never be out of step.
- The site footer shows the release version from `package.json` (see [0029](0029-release-versioning.md)).
- Startup is slower than a native binary: Flyway and the language models load first. The Helm chart
  gives the startup probe up to 300 seconds (see [0027](0027-runtime-topology.md)).
- Two backend languages raise the entry cost for a new developer. [0002](0002-modular-monolith-hexagonal.md)
  limits Kotlin to the business rules.
- The SPA is not crawled well by search engines; detail pages have no HyperText Markup Language (HTML) rendered on the server.

## Alternatives considered
- GraalVM native image — not used. Ahead-of-time processing makes every build slower, and the
  structured-output records would need reflection hints. A few JVM replicas (3 on prod, [0027](0027-runtime-topology.md)) are enough for this load.
- Separate frontend container, or the SPA on a content delivery network (CDN) — a second deploy and CORS
  for no gain at this size.
- Server-side rendering (Next.js) — a Node runtime in production only for search engine optimization
  (SEO); not worth it for v1.
- Spring WebFlux — reactive code is harder to read; virtual threads give the same concurrency here.

## Code
- `backend/build.gradle.kts`, `backend/settings.gradle.kts` — versions, toolchain, plugins.
- `frontend/package.json`, `frontend/vite.config.ts` — frontend versions, dev proxy, version constant.
- `deploy/Dockerfile` — three-stage build, Java Runtime Environment (JRE), JVM flags.
- `backend/src/main/java/ai/helper2/platform/SinglePageApp.java` — SPA routes and caching.
- `backend/src/main/resources/application.yaml` — virtual threads, static mappings off.
- `compose.yaml` — local Postgres and Redis.

## Diagrams
None: no runtime flow. The modules are in the [software architecture](../diagrams/software-architecture.svg) diagram.

## Roadmap
- Search engine support (robots.txt, sitemap, metadata for detail pages): [roadmap](../roadmap.md#product-and-legal).
- Pin base images by digest, image scan and software bill of materials: [roadmap](../roadmap.md#3-operations).
