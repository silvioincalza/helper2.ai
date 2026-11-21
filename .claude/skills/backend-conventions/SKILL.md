---
name: backend-conventions
description: Use before writing, changing or reviewing any file under backend/ or api/ (Java, Kotlin, Gradle, SQL, OpenAPI), and any config or script of the repository. Holds the backend layout, the architecture and code rules (modular monolith, hexagonal, Outcome and UnitOfWork, tests, configuration, format, clean code, no dead code) and the backend commands.
---

# Backend conventions

## Layout

- `backend/` — Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Java 25 + Kotlin, Gradle multi-project:
  shared code in `libs/` (`kernel`, `platform`, `schema`, and `core`, where each shared context is a package:
  ADR 0002), two apps in `apps/public` and `apps/backoffice` (ADR 0043), the tests that read every project in
  `architecture/`, the Gatling load tests in `loadtest/`, plugins and versions in `build-logic/`
- `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` — contract first (OpenAPI 3.1); backend and frontend generate from them
- PostgreSQL 18 (full-text + pgvector), Redis 8 (disposable cache)
- LLMs: NVIDIA models only (Nemotron on Nebius Token Factory)

## Commands

```
cd backend && ./gradlew test        # unit + integration (Postgres, Redis in containers)
cd backend && ./gradlew spotlessApply  # format Java and Kotlin (CI runs spotlessCheck)
cd backend && ./gradlew liveTest    # opt-in, real external APIs
```

Run the relevant tests before calling a change done. `scripts/verify.sh backend` runs what CI runs.

## Architecture and code

- Modular monolith (single repo; the style is used when the user asks or the project is one repo),
  hexagonal: domain and use cases in the core, adapters for DB, LLM, HTTP, queue.
  One explicit use case per business action. Lightweight DDD: ubiquitous language, clear modules.
- A use case whose caller must tell results apart returns a sealed `Outcome` nested in it, one record per case, data
  only (ADR 0051). No use case or domain class names `ApiProblem`: the controller maps the outcome with one `switch`
  that names every case, no `default`, and writes the sentence and the status. A refusal after a write is a `Refused`
  record, and its use case runs in `UnitOfWork.run`. `ModularityRules` checks both (`useCasesDoNotSpeakHttp`,
  `aUseCaseThatCanRefuseAfterAWriteRunsInAUnitOfWork`).
- Prefer the JDK (`String.formatted`) and Apache Commons Lang 3 to a hand-written helper. Commons Lang comes through
  `platform`; the kernel has no third-party library (ADR 0002).
- TDD for domain logic, BDD for user-visible behaviour, integration tests for adapters.
- Configuration only through `@ConfigurationProperties` records (one per module or concern); no `@Value`, no
  `Environment.getProperty` (ADR 0007).
- Code format (ADR 0047): Google style for the backend, google-java-format for Java and ktfmt
  Google style for Kotlin, through Spotless; Prettier for the frontend. Run `./gradlew spotlessApply`
  and `npm run format` before a commit; CI fails on unformatted code.
- Clean code: a use case reads top-down as the business action. Small methods with one level of
  abstraction each, names that say the intent, early returns, no flag arguments. Split a long
  method into named steps instead of adding comments.
- No dead code and no dead docs: a file, a function, a setting, a page or a section that nothing uses or reads is
  deleted in the change that makes it unused. Nothing is kept "for later" or as a backup copy.
- Comments are allowed in code, config and scripts, Javadoc and KDoc too. A comment says why the code is so, or what
  a type or a method is for; it does not repeat what the code says.
- The code that enforces a business rule names it with the marker of `docs/architecture/stack.md`
  (`// UC-XXX BR-YYY:`), directly above the method, the query condition or the validator.
- Javadoc, and KDoc in Kotlin, documents a public type or method: what it is for and the business rule it holds.
  It does not repeat what the code says.
- Env files (`.env.example`, `deploy/k8s/app.env.example` and other dot files) are setup guides for people: their
  comments say what each key is and how to make its value.
- Code, ADRs and docs state the rule in force, never who decided it or when, and never what it replaced. Decision
  history goes to memory in `.private/`. The one exception is the revision history of the specification pages, once
  the migration to the AI Unified Process is complete (`AGENTS.md`, Hard rules).
- No backward compatibility with removed code or migrations unless asked.
