# 0007 — Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles

**Status.** Accepted. **Builds on:** [0001](0001-stack-and-runtime.md)

## Context
The app needs database and Redis passwords, model and source application programming interface (API)
keys, and many tuning values. The same
names must work on a laptop, in the demo compose file and in Kubernetes. Unprefixed names (`REDIS_PORT`,
`DATABASE_URL`) clash with variables that Kubernetes or other tools set. Secrets must never live in Git
or in GitHub settings, and the deploy must not need a person to copy them.

## Decision
1. **One prefix.** Every variable the app reads from outside starts with `H2_`, profiles included:
   `spring.profiles.active: ${H2_PROFILES:}`. `application.yaml` maps each one to a property with a safe
   default (for example `H2_COOKIE_SECURE=true`, `H2_FORWARD_HEADERS_STRATEGY=none`,
   `H2_METRICS_PUBLIC=false`, `H2_SCOUT_ENABLED=false`). Search and rate-limit settings follow the same rule
   (`H2_SEARCH_STALE_FOR`, `H2_SEARCH_PER_SOURCE_LIMIT`, `H2_SEARCH_TRANSLATE_TIMEOUT_MS`, `H2_RATE_LIMIT_*`).
   A token whose quota two features would share has one variable per feature: `H2_GITHUB_TOKEN` for the sources
   and the Scout, `H2_SETUP_GITHUB_TOKEN` for the setup guide's README fetches
   ([0031](../guide/0031-setup-guide.md)). An empty token means anonymous calls, never another feature's token.
   The pod sets `enableServiceLinks: false`, so Kubernetes adds no service variables of its own.
2. **Profiles.** `llm-nebius` turns on the model provider (needs `H2_NEBIUS_API_KEY`); `oauth-github` turns
   on sign-in (needs `H2_GITHUB_OAUTH_CLIENT_ID` and `_SECRET`); `dev` starts compose and allows
   `http://localhost`; `demo` uses fixture sources and no Scout. Staging and prod run `llm-nebius` only.
3. **Local runs.** The app imports the repository-root `.env` (Git-ignored, template `.env.example`); real
   environment variables win. Tests replace the import, so a developer's keys never reach a test run.
4. **Secrets live in OpenBao.** Mount `helper2/kv`, path `ci/<env>`, fields `app_env` (the whole env file)
   and `kubeconfig` (the namespace's deployer). The owner writes them with `make ENV=<env> credentials`
   after a login with role `helper2-operator`. Other entries: `ci/harbor` (image push robot) and
   `backup/r2` (off-site backup token, written to the cluster from the workstation; continuous integration (CI) never reads it).
5. **The path to the pod.** The deploy job logs in to OpenBao with the job's GitHub OpenID Connect (OIDC)
   token ([0028](../operations/0028-ci-cd.md)), writes `app_env` to `<env>/app.env` (`umask 077`), and runs `make deploy`.
   Its first step, `make env-secret`, checks the file (`check-app-env.sh`: every key matches `^H2_[A-Z0-9_]+$`,
   `H2_DATABASE_PASSWORD` is set, `H2_REDIS_PASSWORD` has 32+ letters and digits, no line starts or ends
   in a space or tab, because kubectl keeps a trailing space in the value) and writes it with kubectl
   (server-side apply) into the Secret `helper2-app-env`. Helm never sees a value: the chart renders no Secret,
   so the release history (3 revisions) holds none. The app reads the Secret with `envFrom`; Postgres, Redis
   and the backup job read single keys. The job deletes the file and the kubeconfig at the end, even on failure.
6. **Restart on change.** The chart cannot see the Secret change, so `make deploy` passes hashes:
   `envSecret.revision` (of the file) becomes `checksum/env-revision` on the app pod, and
   `envSecret.redisPasswordRevision` (of the Redis password line) becomes `checksum/auth` on Redis. A changed
   value restarts them on the next deploy. With `redis.auth.existingSecret`, both pods carry
   `checksum/redis-auth-revision` with the number `redis.auth.revision` instead, and a rotation is "update the
   Secret, raise the revision, deploy".
7. **Values that are not secret** (database address, Redis host, profiles, Scout settings, tracing endpoint,
   management port 8081) are set in the chart's Deployment, per environment through `values-<env>.yaml`.
8. **Typed settings.** The code reads configuration only through immutable `@ConfigurationProperties`
   records: one per module, or per clear concern, in that module's `config` package, under
   `helper2.<module>`. The open `platform` module uses concern prefixes such as `helper2.llm`.
   `helper2.retention` is shared, with one record per module for its own periods. No `@Value`, no
   `Environment.getProperty`: an architecture test (`ConfigurationAccessTest`) fails the build on either.
   One exception: `DiscoveryCollectorRegistrar` registers one bean per entry of `helper2.categories`
   before any bean exists, so it binds that list through Spring Boot's `Binder` and checks it in code
   ([0051](../scout/0051-tavily-discovery.md)).
   Records are `@Validated`, use `Duration`, `DataSize`, `URI` and lists, and mask secrets in `toString`
   (`Secrets.mask`). Spring binds and validates them before the context is ready, so a bad value stops
   startup with its name. Defaults live in one place, `application.yaml`, next to the `H2_` name that
   overrides them. Records carry none. `@Scheduled` and `@ConditionalOnProperty` still use placeholders,
   but only for keys a record owns. Env names never follow a record path: the yaml mapping is the
   contract, so renaming a field never renames an `H2_` variable. The database password has no default
   in the base document; only the `dev` profile has the local one from `compose.yaml`.
9. **Six limits change at runtime.** For the spending limits in `RuntimeSetting` (Scout enrichments per run,
   enrichment calls and gap rounds a day, the guide's model calls a day, translations a day, Tavily credits a day),
   the `H2_` value is the default, not a ceiling: an administrator can change it from the back office, between 0
   and a maximum in code ([0058](../operations/0058-runtime-settings.md)). The record still binds the value at
   startup; the module reads the limit through a supplier. Every other setting changes only with a deploy.

## Consequences
- One search for `H2_` finds every setting. A missing or misspelled prefix is easy to spot in review.
- No secret is stored in GitHub: the deploy reads OpenBao after a login that is bound to the
  repository and the job's environment.
- A changed value restarts the pods that use it on the next deploy.
- A Helm rollback restores the chart and the image, not app.env: a bad value is fixed in OpenBao and
  deployed again.
- The Redis hash in the pod annotations is unsalted, so the password must be long and random (32+
  characters, `openssl rand -hex 32`).

## Alternatives considered
- GitHub Actions secrets — long-lived values in a second place, readable by any workflow change.
- External Secrets Operator or the OpenBao agent in the cluster — another controller to run for one app. The
  chart only names the Secret (`envSecret.name`), so either can fill it later without a chart change.
- The env file as a Helm value (`--set-file envFile`, until Nov 2026) — Helm keeps every revision's values,
  so each old secret stayed in the release history after a rotation.
- Spring Cloud Config — a config server for one service.
- Plain Spring names (`SPRING_DATASOURCE_URL`) — long, tied to Spring's internal property names, and
  mixed with our own settings under no common prefix.
- `@Value` in the code — scattered, not validated, defaults copied next to the yaml ones.
- One global properties class — crosses module boundaries.
- Relaxed binding of `H2_*` straight to records — cannot give one token to several sources, would rename
  about 45 variables in OpenBao, and every field would become settable from outside.
- A checksum of the existing Redis Secret read with Helm's `lookup` — needs read access to Secrets at deploy time
  and renders nothing in `helm template` (CI); a controller that restarts pods on Secret changes (Reloader) — one
  more component. A revision number in the values is explicit and testable.

## Code
- `backend/src/main/resources/application.yaml` — every `H2_` variable, defaults, profiles.
- `backend/src/main/java/ai/helper2/<module>/config/*Properties.java` — the typed settings;
  `backend/src/main/kotlin/ai/helper2/shared/Secrets.kt` — masks secrets in `toString`.
- `backend/src/test/java/ai/helper2/architecture/ConfigurationAccessTest.java` — the no-`@Value` rule;
  `backend/src/test/java/ai/helper2/testing/ConfigBinding.java` — binding tests on the real yaml.
- `.env.example`, `deploy/k8s/app.env.example` — templates.
- `deploy/k8s/Makefile` (`credentials`, `backup-credentials`, `env-secret`, `deploy`), `deploy/k8s/check-app-env.sh`
  — writes, checks and uses the secrets.
- `.github/actions/bao-login/action.yml`, `.github/workflows/deploy.yml` — OIDC login, `app_env` to file.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.envSecret`, `helper2.redisPasswordFrom`),
  `templates/app.yaml`, `templates/redis.yaml` — pod environment, restart annotations.

## Diagrams
[flow](../../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../../diagrams/sequence-0007-configuration-and-secrets.svg)

## Roadmap
- Short-lived deployer token from the OpenBao Kubernetes secrets engine: [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
