# 0007 — Configuration: H2_ variables, secrets in OpenBao, runtime settings

**Status.** Accepted. Merges old 0007, 0060 (texts merged in PR 13 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0007"></a>

## Part: was ADR 0007 — Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles

**Status.** Accepted. **Builds on:** [0001](0001-stack-and-runtime.md)

### Context
The app needs database and Redis passwords, model and source application programming interface (API)
keys, and many tuning values. The same
names must work on a laptop, in the demo compose file and in Kubernetes. Unprefixed names (`REDIS_PORT`,
`DATABASE_URL`) clash with variables that Kubernetes or other tools set. Secrets must never live in Git
or in GitHub settings, and the deploy must not need a person to copy them.

### Decision
1. **One prefix.** Every variable the app reads from outside starts with `H2_`, profiles included:
   `spring.profiles.active: ${H2_PROFILES:}`. `application.yaml` maps each one to a property with a safe
   default (for example `H2_COOKIE_SECURE=true`, `H2_FORWARD_HEADERS_STRATEGY=none`,
   `H2_METRICS_PUBLIC=false`, `H2_SCOUT_ENABLED=false`). Search and rate-limit settings follow the same rule
   (`H2_SEARCH_STALE_FOR`, `H2_SEARCH_PER_SOURCE_LIMIT`, `H2_SEARCH_TRANSLATE_TIMEOUT_MS`, `H2_RATE_LIMIT_*`).
   A token whose quota two features would share has one variable per feature: `H2_GITHUB_TOKEN` for the sources
   and the Scout, `H2_SETUP_GITHUB_TOKEN` for the setup guide's README fetches
   ([0019](../guide/0019-setup-guide.md)). An empty token means anonymous calls, never another feature's token.
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
   token ([0033](../operations/0033-ci-cd-and-supply-chain.md)), writes `app_env` to `<env>/app.env` (`umask 077`), and runs `make deploy`.
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
   ([0022](../scout/0022-discovery.md)).
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
   and a maximum in code ([the part that was ADR 0060](#part-0060)). The record still binds the value at
   startup; the module reads the limit through a supplier. Every other setting changes only with a deploy.

### Consequences
- One search for `H2_` finds every setting. A missing or misspelled prefix is easy to spot in review.
- No secret is stored in GitHub: the deploy reads OpenBao after a login that is bound to the
  repository and the job's environment.
- A changed value restarts the pods that use it on the next deploy.
- A Helm rollback restores the chart and the image, not app.env: a bad value is fixed in OpenBao and
  deployed again.
- The Redis hash in the pod annotations is unsalted, so the password must be long and random (32+
  characters, `openssl rand -hex 32`).

### Alternatives considered
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

### Code
- `backend/apps/public/src/main/resources/application.yaml` — every `H2_` variable, defaults, profiles.
- `backend/{libs,apps}/*/src/main/java/ai/helper2/<module>/config/*Properties.java` — the typed settings;
  `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Secrets.kt` — masks secrets in `toString`.
- `backend/apps/public/src/test/java/ai/helper2/architecture/ConfigurationAccessTest.java` — the no-`@Value` rule;
  `backend/libs/platform/src/testFixtures/java/ai/helper2/testing/ConfigBinding.java` — binding tests on the real yaml.
- `.env.example`, `deploy/k8s/app.env.example` — templates.
- `deploy/k8s/Makefile` (`credentials`, `backup-credentials`, `env-secret`, `deploy`), `deploy/k8s/check-app-env.sh`
  — writes, checks and uses the secrets.
- `.github/actions/bao-login/action.yml`, `.github/workflows/deploy.yml` — OIDC login, `app_env` to file.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.envSecret`, `helper2.redisPasswordFrom`),
  `templates/app.yaml`, `templates/redis.yaml` — pod environment, restart annotations.

### Diagrams
[flow](../../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../../diagrams/sequence-0007-configuration-and-secrets.svg)

### Roadmap
- Short-lived deployer token from the OpenBao Kubernetes secrets engine: [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0060"></a>

## Part: was ADR 0060 — Runtime settings: a few spending limits change from the back office, without a deploy

**Status.** Accepted. **Builds on:** [0005](0005-redis-disposable-cache.md), [the part that was ADR 0007](#part-0007), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md), [0024](../edge/0024-identity-and-admin-host.md), [0035](../operations/0035-data-retention.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0023](../scout/0023-evidence-gated-enrichment.md), [0026](../edge/0026-back-office-and-review.md)

### Context
Every limit is an `H2_` variable. To lower the guide's daily model calls during an abuse spike, or to give the Scout
more enrichment calls for one day after a fresh database, I change the env Secret and deploy: a few minutes, a
restart of every replica, and the change is in no audit trail. The Settings page ([0026](../edge/0026-back-office-and-review.md))
shows the values, but cannot change them. Most settings must stay that way: a model id, a prompt or a secret
changed at runtime is a release without a review. A few spending limits are different: they only say how much we
spend, and the right number changes with the day.

### Decision
1. **An allow-list in code.** `platform.settings.RuntimeSetting` is the list: Scout enrichments per run, enrichment
   calls a day, gap rounds a day, the guide's model calls a day, translations a day and Tavily credits a day for all
   of Helper2. Each has a key, a unit, a line of help and an absolute maximum. Nothing else can change at runtime:
   no secret, no `H2_ADMINS`, no model id, prompt, token limit, ranking weight, rate limit, nor the Tavily quotas
   per visitor.
2. **The `H2_` value is the default, not a ceiling.** The minimum is 0, which turns the use off. The maximum is in
   code; going above it needs a pull request. A value outside 0 to the maximum is refused by the server (400) and
   blocked in the form. A default above the maximum stops startup with the variable's name. Every runtime setting is
   a finite number: an empty `H2_LIVE_WEB_DAILY_CREDITS` (no cap) applies as the code maximum, 2,000 credits a
   day. So "Reset to default" never removes the global Tavily cap. The other choice, refusing a reset when the
   default is empty, still leaves no cap on a fresh install and makes "reset" mean two things; the code maximum is the
   safer of the two.
3. **Postgres, append-only.** Table `runtime_setting` (`V1__schema.sql`): one row per change, with the value, who, when and an
   optional reason. The current value is the newest row; "Reset to default" appends a row with no value. A trigger
   refuses every UPDATE. Not Redis: Redis is a disposable cache ([0005](0005-redis-disposable-cache.md)),
   and a lost override would raise a limit without anyone knowing.
4. **One snapshot per replica.** `RuntimeSettings` keeps an immutable snapshot in an `AtomicReference`. A thread of
   its own checks the newest version every `H2_RUNTIME_SETTINGS_REFRESH` (20 s, 5 to 60 s) and reads the settings
   again only when it changed. Hot paths read the snapshot only, never the database. The replica that made the
   change reads it right after the commit. A store that cannot answer leaves the last snapshot.
5. **Limits read through a supplier.** The module that uses a setting declares its default and gets a supplier:
   `DailyBudget` takes an `IntSupplier` and reads it on every call, `BudgetLedger` shows the limit as it is now,
   `WebSearchQuota` reads the global cap on every call. The Scout reads its enrichments per run once, at the start of
   each run. A limit lowered below today's count refuses the next calls.
6. **Optimistic version.** Each setting's version is its newest row. A change sends the version it was made on; a
   stale one is a 409, and the page shows the current value. A transaction-scoped advisory lock serialises the check
   and the insert across replicas.
7. **Audited.** Each change, reset, refused value and conflict is a `CHANGE_SETTING` row in `admin_audit`, in the same
   transaction, with the value before and after and the reason. The reason is one plain line: a control or format
   character (line break, zero-width or bidirectional mark) is a 400, and nothing is stored. `admin_audit` has
   `before_value`, `after_value` and `reason`, and its action check lists `CHANGE_SETTING` with every other admin
   action (`V1__schema.sql`).
8. **The page.** `/admin/settings` lists the limits first: the value now, the default, the allowed range with the unit
   and one line of help. Change → "Review changes" shows old → new → Confirm, with an optional reason. History lists
   the last 10 changes; Restore goes through the same review. The overview says how many limits are changed.
   API: `GET /api/v1/admin/runtime-settings`, `PUT /api/v1/admin/runtime-settings/{key}`,
   `POST /api/v1/admin/runtime-settings/{key}/reset`, ADMIN only, on the back office, CSRF on writes.
9. **Retention.** A change is kept 24 months (`H2_RETENTION_RUNTIME_SETTINGS`); each setting's newest change stays,
   because it is the value in force. Purged daily, on one replica.

### Consequences
- A spending limit changes in seconds, without a restart, and the audit trail says who changed it, from what, to what
  and why.
- Every replica applies a change within about 20 seconds. Until then two replicas can apply two limits: the shared
  counters keep the total right, only the cut-off moves by those seconds.
- The Settings page's read-only groups show the value now for these six settings, and the `H2_` value as the default.
- Raising a maximum is a code change, reviewed like any other.
- Emergency stop: a 0 reaches every replica within about 20 seconds (the replica that made the change at once). A pod
  whose first read of the store fails (database down) runs on the `H2_` defaults until the store answers. To stop a use for sure through an outage, also set the `H2_` value to 0 and deploy.
- Reloads run one at a time on a replica (the refresh thread and the replica's own reload after a change), and a read
  never replaces a snapshot that another read applied while it ran. So an older snapshot never replaces a newer one,
  and a store whose version went back (restored from a backup) wins: what it holds now applies.
- The env value is still read at startup. A new `H2_` value with an override in place changes the default only.

### Alternatives considered
- Change the env Secret and deploy — no new code, but minutes and a restart, and no audit row.
- Redis for the overrides — fast and shared, but Redis is disposable: a flush would silently restore every default.
- A config server (Spring Cloud Config, Consul) — a new service to run for six numbers.
- Read the table on every call — always fresh, but a database read on every guide or search request.
- Spring `@RefreshScope` — rebuilds beans on refresh, and still needs a store and an audit.
- A row per setting, updated in place — simpler reads, but the history and the undo need a second table.

### Code
- `backend/libs/platform/src/main/java/ai/helper2/platform/settings/` — `RuntimeSetting.java` (the allow-list), `RuntimeSettings.java`
  (the snapshot), `RuntimeSettingStore.java`, `JdbcRuntimeSettingStore.java`, `RuntimeSettingsConfiguration.java`
  (refresh thread, purge).
- `backend/libs/platform/src/main/java/ai/helper2/platform/config/RuntimeSettingsProperties.java`, `PlatformRetentionProperties.java`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`runtime_setting`).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/GetRuntimeSettings.java`, `ChangeRuntimeSetting.java`,
  `ResetRuntimeSetting.java`; `admin/web/RuntimeSettingsController.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/DailyBudget.java`, `BudgetLedger.java`, `CallCounter.java` (limits by
  supplier); `kotlin/ai/helper2/search/WebSearch.kt` (`WebSearchQuota`), `kotlin/ai/helper2/scout/usecase/RunScout.kt`.
- Declarations: `guide/internal/GuideConfiguration.java`, `search/internal/TranslationConfiguration.java`,
  `search/config/SearchConfiguration.java`, `scout/internal/ScoutConfiguration.java`.
- `backend/libs/identity/src/main/java/ai/helper2/identity/AuditTrail.java`, `identity/internal/AuditLog.java` (before, after, reason).
- `api/admin.yaml` — `RuntimeSettings`, `RuntimeSettingState`, `RuntimeSettingChange`, `RuntimeSettingReset`;
  `AuditEntry` and `AdminOverview` fields.
- `frontend/src/pages/admin/RuntimeSettingsPanel.tsx`, `SettingsPage.tsx`, `AdminOverview.tsx`, `AuditPage.tsx`.
- Tests: `platform/settings/RuntimeSettingTest.java`, `RuntimeSettingsTest.java`, `JdbcRuntimeSettingStoreIT.java`,
  `admin/usecase/RuntimeSettingReasonTest.java`,
  `platform/config/RuntimeSettingsPropertiesBindingTest.java`, `platform/llm/BudgetLedgerTest.java`,
  `api/AdminRuntimeSettingsApiIT.java`, `frontend/src/pages/admin/SettingsPage.test.tsx`, `Admin.test.tsx`.

### Diagrams
[flow](../../diagrams/flow-0007-runtime-settings.svg) · [sequence](../../diagrams/sequence-0007-runtime-settings.svg)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
