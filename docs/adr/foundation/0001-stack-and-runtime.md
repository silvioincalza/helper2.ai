# 0001 — Stack and runtime: Java 25 + Kotlin, Spring Boot 4.1, Spring AI 2.0, React SPA served by Spring, JVM image

**Status.** Accepted.

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
4. **One image per deployable** ([0069](../operations/0069-three-deployables.md)). `deploy/web.Dockerfile`: Node builds
   the public SPA, nginx (`nginx-unprivileged`, alpine-slim) serves its files as user 101. `deploy/Dockerfile`: the JDK
   stage runs `:apps:public:bootJar`, the runtime stage is `eclipse-temurin:25-jre` as user 10001; the jar holds no
   web files. `deploy/backoffice.Dockerfile` builds the back office with the admin web app (`dist-admin`) in
   `backend/apps/backoffice/src/main/resources/static/admin`. The SPA and `/api/v1` come from the same origin (the
   Ingress splits the paths), so there is no Cross-Origin Resource Sharing (CORS) setup.
5. **SPA serving.** The web image serves `/assets/<file>` (365 days, immutable) and the root files `/theme.js` and
   `/favicon.svg` (5 minutes, then revalidated); any other address there is a plain 404, never `index.html`. The public
   app has no static handler (`spring.web.resources.add-mappings: false`): every path the client router owns (no file
   extension, outside `/api`, `/oauth2`, `/login`, `/logout` and `/actuator`) is the shell, `index.html`, written by
   the pages module with `no-cache` ([0050](../frontend/0050-pages-for-crawlers.md)). The shell comes from the web
   image of the same tag.
6. **Java Virtual Machine (JVM) flags.** `-XX:MaxRAMPercentage=60` (heap is 60% of the container limit) and
   `-XX:+ExitOnOutOfMemoryError` (the pod restarts instead of running broken).
7. **Local development.** Vite on port 5173 proxies `/api`, `/oauth2`, `/login` and `/logout` to the
   backend on 8080. `compose.yaml` starts Postgres and Redis; the `dev` profile starts them for the app.

## Consequences
- One image holds the API and the user interface (UI). A deploy changes both at once, and they can never be out of step.
- The site footer shows the release version from `package.json` (see [0029](../operations/0029-release-versioning.md)).
- Request code stays plain and blocking; virtual threads give the concurrency.

## Alternatives considered
- GraalVM native image — not used. Ahead-of-time processing makes every build slower, and the
  structured-output records would need reflection hints. A few JVM replicas (3 on prod, [0027](../operations/0027-runtime-topology.md)) are enough for this load.
- Separate frontend container, or the SPA on a content delivery network (CDN) — a second deploy and CORS
  for no gain at this size.
- Server-side rendering (Next.js) — a Node runtime in production only for search engine optimization
  (SEO); not worth it at this size.
- Spring WebFlux — reactive code is harder to read; virtual threads give the same concurrency here.

## Code
- `backend/settings.gradle.kts`, `backend/build-logic/src/main/kotlin/helper2.kotlin-library.gradle.kts`, `backend/*/*/build.gradle.kts` — projects, versions, toolchain, plugins.
- `frontend/package.json`, `frontend/vite.config.ts` — frontend versions, dev proxy, version constant.
- `deploy/Dockerfile` — two-stage build, Java Runtime Environment (JRE), JVM flags.
- `deploy/web.Dockerfile`, `deploy/web/nginx.conf` — the SPA's files and their caching.
- `backend/apps/public/src/main/java/ai/helper2/pages/web/PageRoutes.java` — client routes get the shell.
- `backend/apps/public/src/main/resources/application.yaml` — virtual threads, static mappings off.
- `compose.yaml` — local Postgres and Redis.

## Diagrams
[flow](../../diagrams/flow-0001-stack-and-runtime.svg) · [sequence](../../diagrams/sequence-0001-stack-and-runtime.svg)
The modules are in the [context view](../../diagrams/architecture-context.svg); each module group has its own view in [architecture](../../architecture.md).

## Roadmap
- Search engine support (robots.txt, sitemap, metadata for detail pages): [roadmap](../../roadmap.md#product-and-legal).
- The open supply-chain steps ([0046](../operations/0046-supply-chain.md)): [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
