# 0007 — Configuration, secrets, runtime settings

**Depends on:** [0001](0001-stack-and-runtime.md), [0004](0004-persistence-and-the-schema.md), [0005](0005-redis-as-a-disposable-cache.md), [0010](0010-data-retention.md), [0014](../scout/0014-evidence-gated-enrichment.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0038](../backoffice/0038-back-office-and-review.md)

## Context
The apps need database and Redis passwords, model and source application programming interface (API) keys, and
many tuning values. The same names must work on a laptop, in the demo compose file and in Kubernetes. Unprefixed
names (`REDIS_PORT`, `DATABASE_URL`) clash with variables that Kubernetes or other tools set. Secrets must never live
in Git or in GitHub settings, and the deploy must not need a person to copy them.

Every limit is an `H2_` variable. To lower the guide's daily model calls during an abuse spike, or to give the Scout
more enrichment calls for one day after a fresh database, I would change the env Secret and deploy: a few minutes, a
restart of every replica, and the change is in no audit trail. The Settings page
([0038](../backoffice/0038-back-office-and-review.md)) shows the values, but cannot change them. Most settings must stay
that way: a model id, a prompt or a secret changed at runtime is a release without a review. A few spending limits
are different: they only say how much we spend, and the right number changes with the day.

## Decision
### Variables, profiles and secrets
1. **One prefix.** Every variable the apps read from outside starts with `H2_`, profiles included:
   `spring.profiles.active: ${H2_PROFILES:}`. The yaml files (§19) map each one to a property with a safe
   default (for example `H2_COOKIE_SECURE=true`, `H2_FORWARD_HEADERS_STRATEGY=none`, `H2_METRICS_PUBLIC=false`,
   `H2_SCOUT_ENABLED=false`). Search and rate-limit settings follow the same rule (`H2_SEARCH_STALE_FOR`,
   `H2_SEARCH_PER_SOURCE_LIMIT`, `H2_SEARCH_TRANSLATE_TIMEOUT_MS`, `H2_RATE_LIMIT_*`).
   A token whose quota two features would share has one variable per feature: `H2_GITHUB_TOKEN` for the sources
   and the Scout, `H2_SETUP_GITHUB_TOKEN` for the setup guide's README fetches
   ([0034](../guide/0034-setup-guide.md)). An empty token means anonymous calls, never another feature's token.
   Every pod sets `enableServiceLinks: false`, so Kubernetes adds no service variables of its own.
2. **Profiles.** `llm-nebius` turns on the model provider (needs `H2_NEBIUS_API_KEY`); `oauth-github` turns
   on sign-in (needs `H2_GITHUB_OAUTH_CLIENT_ID` and `_SECRET`); `dev` starts compose and allows
   `http://localhost`; `demo` uses fixture sources and no Scout. Staging and prod run `llm-nebius,oauth-github`
   (`app.springProfiles`, shared by both apps).
3. **Local runs.** The apps import the repository-root `.env` (Git-ignored, template `.env.example`); real
   environment variables win. Tests replace the import, so a developer's keys never reach a test run.
4. **Secrets live in OpenBao.** Mount `helper2/kv`, path `ci/<env>`, fields `app_env` (the whole env file)
   and `kubeconfig` (the namespace's deployer). The owner writes them with `make ENV=<env> credentials`
   after a login with role `helper2-operator`; the target first checks that the deployer may do what a deploy
   needs. Other entries: `ci/harbor` (image push robot), `ci/harbor-pull` (pull only, to verify images) and
   `backup/r2` (the off-site backup's access key, endpoint, bucket and encryption password, written to the
   cluster from the workstation with `make backup-credentials`; continuous integration (CI) never reads it).
5. **The path to the pods.** The deploy job logs in to OpenBao with the job's GitHub OpenID Connect (OIDC)
   token ([0044](../operations/0044-ci-cd-and-supply-chain.md)), writes `app_env` to `<env>/app.env` (`umask 077`),
   and runs `make deploy`. Its first step, `make env-secret`, checks the file with `check-app-env.sh`: `KEY=value`
   lines and `#` comments only, every key matches `^H2_[A-Z0-9_]+$` and is set once, no line starts or ends in a space
   or tab (kubectl keeps a trailing space in the value), the database owner's and both app roles' passwords have 32+
   characters of base64 or base64url and differ, and the three Redis passwords (default user, `h2_public`,
   `h2_backoffice`) have 32+ letters and digits and differ. Then kubectl (server-side apply) writes two Secrets:
   `helper2-credentials` with the passwords ([0004](0004-persistence-and-the-schema.md)), and
   `helper2-app-env` with every other key. Helm never sees a value: the chart renders no Secret, so the release
   history (3 revisions) holds none. Both apps read `helper2-app-env` with `envFrom` and only their own passwords
   from `helper2-credentials` by key; Postgres, Redis, the backup job and the migrate Job read single keys too. No
   workload loads `helper2-credentials` whole. The job deletes the file and the kubeconfig at the end, even on
   failure.
6. **Restart on change.** The chart cannot see a Secret change, so `make deploy` passes hashes:
   `envSecret.revision` (of the file) becomes `checksum/env-revision` on both app pods, and
   `envSecret.redisPasswordRevision` (of the Redis password lines) becomes `checksum/auth` on Redis. A changed
   value restarts them on the next deploy. With `redis.auth.existingSecret`, the app pods and Redis carry
   `checksum/redis-auth-revision` with the number `redis.auth.revision` instead, and a rotation is "update the
   Secret, raise the revision, deploy".
7. **Values that are not secret** (database address, Redis host, profiles, Scout settings, tracing endpoint,
   management port 8081) are set in the chart's Deployments, per environment through `values-<env>.yaml`.
8. **Typed settings.** The code reads configuration only through immutable `@ConfigurationProperties`
   records: one per module, or per clear concern, in that module's `config` package, under
   `helper2.<module>`. The open `platform` module uses concern prefixes such as `helper2.llm`.
   `helper2.retention` is shared, with one record per module for its own periods. No `@Value`, no
   `Environment.getProperty`: an architecture test in each app (`ConfigurationAccessTest`) fails the build on
   either. One exception: `DiscoveryCollectorRegistrar` registers one bean per entry of `helper2.categories`
   before any bean exists, so it binds that list through Spring Boot's `Binder` and checks it in code
   ([0013](../scout/0013-discovery.md)).
   Records are `@Validated`, use `Duration`, `DataSize`, `URI` and lists, and mask secrets in `toString`
   (`Secrets.mask`). Spring binds and validates them before the context is ready, so a bad value stops
   startup with its name. Defaults live in one place, the yaml files (§19), next to the `H2_` name that
   overrides them. Records carry none. A schedule takes its timing from its record too: it is a
   `SchedulingConfigurer` that registers its task with the record's cron or durations (`DailyJobs` with
   `RetentionSchedule`, `HomeExampleCheckSchedule` with `ExamplesProperties`, `ScoutSchedule` with
   `ScoutProperties`), so no `@Scheduled` reads a placeholder (`ConfigurationAccessTest` fails on one). Only
   `@ConditionalOnProperty` and `@ConditionalOnExpression` still use placeholders, and only for keys a record owns. Env names never follow a record path: the yaml mapping is the
   contract, so renaming a field never renames an `H2_` variable. The database password has no default
   in the base document; only the `dev` profile has the local one from `compose.yaml`.
9. **Six limits change at runtime.** For the spending limits in `RuntimeSetting` (§10), the `H2_` value is the
   default, not a ceiling: an administrator can change it from the back office, between 0 and a maximum in code.
   The record still binds the value at startup; the module reads the limit through a supplier (§14). Every other
   setting changes only with a deploy.

### Runtime settings
10. **An allow-list in code.** `platform.settings.RuntimeSetting` is the list: Scout enrichments per run
    (`H2_SCOUT_MAX_ENRICHMENTS`, at most 3,000), enrichment calls a day (`H2_SCOUT_DAILY_ENRICHMENT_CALLS`, 3,000),
    gap rounds a day (`H2_SCOUT_GAP_DAILY_CALLS`, 2,000), the guide's model calls a day
    (`H2_GUIDE_DAILY_MODEL_CALLS`, 3,000), translations a day (`H2_SEARCH_DAILY_TRANSLATIONS`, 2,000) and Tavily
    credits a day for all of Helper2 (`H2_LIVE_WEB_DAILY_CREDITS`, 2,000). Each has a key, a unit, a line of help and
    that absolute maximum. Nothing else can change at runtime: no secret, no `H2_ADMINS`, no model id, prompt, token
    limit, ranking weight, rate limit, nor the Tavily quotas per visitor.
11. **The `H2_` value is the default, not a ceiling.** The minimum is 0, which turns the use off. The maximum is in
    code; going above it needs a pull request. A value outside 0 to the maximum is refused by the server (400) and
    blocked in the form. A default above the maximum stops startup with the variable's name. Every runtime setting is
    a finite number: an empty `H2_LIVE_WEB_DAILY_CREDITS` (no cap) applies as the code maximum, 2,000 credits a
    day. So "Reset to default" never removes the global Tavily cap. The other choice, refusing a reset when the
    default is empty, still leaves no cap on a fresh install and makes "reset" mean two things; the code maximum is
    the safer of the two.
12. **Postgres, append-only.** Table `runtime_setting` (`V1__schema.sql`): one row per change, with the value, who,
    when and an optional reason. The current value is the newest row; "Reset to default" appends a row with no value.
    A trigger refuses every UPDATE. Not Redis: Redis is a disposable cache ([0005](0005-redis-as-a-disposable-cache.md)),
    and a lost override would raise a limit without anyone knowing.
13. **One snapshot per replica.** `RuntimeSettings` keeps an immutable snapshot in an `AtomicReference`, in both
    apps. A thread of its own checks the newest version every `H2_RUNTIME_SETTINGS_REFRESH` (20 s, 5 to 60 s) and
    reads the settings again only when it changed. Hot paths read the snapshot only, never the database. The replica
    that made the change reads it right after the commit. A store that cannot answer leaves the last snapshot. When
    that read after the commit fails, the change stays stored and the admin's request succeeds: the replica logs a
    warning with the class of the failure, no value, and the next refresh applies the change.
14. **Limits read through a supplier.** The module that uses a setting declares its default and gets a supplier:
    `DailyBudget` takes an `IntSupplier` and reads it on every call, `BudgetLedger` shows the limit as it is now,
    `WebSearchQuota` reads the global cap on every call. The Scout reads its enrichments per run once, at the start
    of each run. A limit lowered below today's count refuses the next calls.
15. **Optimistic version.** Each setting's version is its newest row. A change sends the version it was made on; a
    stale one is a 409, and the page shows the current value. A transaction-scoped advisory lock serialises the check
    and the insert across replicas.
16. **Audited.** Each change, reset, refused value and conflict is a `CHANGE_SETTING` row in `admin_audit`, in the
    same transaction, with the value before and after and the reason. The reason is one plain line: a control or
    format character (line break, zero-width or bidirectional mark) is a 400, and nothing is stored. `admin_audit`
    has `before_value`, `after_value` and `reason`, and its action check lists `CHANGE_SETTING` with every other
    admin action (`V1__schema.sql`).
17. **The page.** `/admin/settings` lists the limits first: the value now, the default, the allowed range with the
    unit and one line of help. Change → "Review changes" shows old → new → Confirm, with an optional reason. History
    lists the last 10 changes; Restore goes through the same review. The overview says how many limits are changed.
    API: `GET /api/v1/admin/runtime-settings`, `PUT /api/v1/admin/runtime-settings/{key}`,
    `POST /api/v1/admin/runtime-settings/{key}/reset`, ADMIN only, on the back office, CSRF on writes.
18. **Retention.** A change is kept 24 months (`H2_RETENTION_RUNTIME_SETTINGS`, `P730D`); each setting's newest
    change stays, because it is the value in force. The back office app purges the older ones daily
    (`helper2.retention.cron`, UTC), on one replica at a time (a cluster lock).

### Settings files
19. **One shared file, one small file per app.** `helper2-shared.yaml`, in the `platform` library, holds every
    setting both apps read with the same value (about 930 keys), with the profile documents. Each app's
    `application.yaml` holds only what is its own: its name, its session and cookie values, the settings of the
    modules only it runs (guide, setup, pages and monetisation in the public app; admin, the Scout's run and the page
    reader in the back office) and its own values under a profile. A setting both apps read is written once.
20. **The app's file wins.** The first document of an app's `application.yaml` has one key,
    `spring.config.import: classpath:helper2-shared.yaml`. Spring puts an imported file above the document that
    imports it and below the documents after it. So, weakest first: the shared file's documents in their order, then
    the app's own documents in their order. A profile changes which documents count, not their order: an app's
    document with no profile is still above a shared `dev` document, so an app's file never sets, outside a profile,
    a key that a shared profile document sets. An `H2_` variable fills its placeholder wherever the key is written.
    The `.env` import (§3) is in the shared file's first document: `.env` holds `H2_` names only (§1), so every app
    reads it the same way.
21. **Tests keep it so.** `SharedSettingsTest` fails when an app's first document has more than the import, when both
    apps' files write the same key with the same value (it belongs in the shared file), when an app's file hides a
    shared profile value, and when the two apps resolve different values for a shared setting neither overrides, for
    no profile, each profile and all of them. Each app also has a recorded list of every key of its yaml, as written
    and as resolved (`src/test/resources/configuration/`: `no-profile.txt` has every key, a profile's file the lines
    that profile changes). `PublicConfigurationSnapshotTest` and `BackOfficeConfigurationSnapshotTest` compare the
    running configuration with it, so a change of a setting shows in the pull request as the line it changes in each
    app. The test loads the files without the machine's environment, so no secret can reach a recorded file. To
    record again: delete the file and run the test once; it writes the file and fails, the second run passes.

### Code
Variables, profiles and secrets:
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` — the settings both apps share: `H2_` variables,
  defaults, profiles; `backend/apps/public/src/main/resources/application.yaml`,
  `backend/apps/backoffice/src/main/resources/application.yaml` — the import and each app's own settings.
- `backend/libs/platform/src/test/java/ai/helper2/platform/config/SharedSettingsTest.java`,
  `backend/apps/public/src/test/java/ai/helper2/config/PublicConfigurationSnapshotTest.java`,
  `backend/apps/backoffice/src/test/java/ai/helper2/config/BackOfficeConfigurationSnapshotTest.java` with
  `backend/apps/*/src/test/resources/configuration/*.txt`;
  `backend/libs/platform/src/testFixtures/java/ai/helper2/testing/ResolvedConfiguration.java`,
  `ConfigurationSnapshot.java` — the settings files' tests (§21).
- `backend/{libs,apps}/*/src/main/java/ai/helper2/<module>/config/*Properties.java` — the typed settings;
  `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Secrets.kt` — masks secrets in `toString`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/DiscoveryCollectorRegistrar.java` — the
  one `Binder` exception.
- `backend/apps/public/src/test/java/ai/helper2/architecture/ConfigurationAccessTest.java`,
  `backend/apps/backoffice/src/test/java/ai/helper2/architecture/ConfigurationAccessTest.java` — the no-`@Value` rule;
  `backend/libs/platform/src/testFixtures/java/ai/helper2/testing/ConfigBinding.java` — binding tests on the real yaml.
- `.env.example`, `deploy/k8s/app.env.example` — templates.
- `deploy/k8s/Makefile` (`credentials`, `backup-credentials`, `env-secret`, `deploy`), `deploy/k8s/check-app-env.sh`,
  `deploy/k8s/check-env-overrides.sh` — write, check and use the secrets.
- `.github/actions/bao-login/action.yml`, `.github/workflows/deploy.yml` — OIDC login, `app_env` to file.
- `deploy/helm/helper2/values.yaml` (`envSecret`, `credentialsSecret`, `redis.auth`),
  `templates/_helpers.tpl` (`helper2.envSecret`, `helper2.credentialsSecret`, `helper2.redisPasswordFrom`,
  `helper2.redisAuthRevision`), `templates/app.yaml`, `templates/backoffice.yaml`, `templates/redis.yaml` — pod
  environment, restart annotations.

Runtime settings:
- `backend/libs/platform/src/main/java/ai/helper2/platform/settings/` — `RuntimeSetting.java` (the allow-list),
  `RuntimeSettings.java` (the snapshot), `RuntimeSettingStore.java`, `JdbcRuntimeSettingStore.java`,
  `RuntimeSettingsConfiguration.java` (the refresh thread).
- `backend/apps/backoffice/src/main/java/ai/helper2/platform/settings/RuntimeSettingsPurgeSchedule.java` — the purge.
- `backend/libs/platform/src/main/java/ai/helper2/platform/config/RuntimeSettingsProperties.java`,
  `PlatformRetentionProperties.java`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`runtime_setting`, `admin_audit`).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/GetRuntimeSettings.java`, `ChangeRuntimeSetting.java`,
  `ResetRuntimeSetting.java`; `admin/web/RuntimeSettingsController.java`; `admin/config/AdminUseCaseConfiguration.java`,
  `SettingsReloadAfterCommit.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/DailyBudget.java`, `BudgetLedger.java`,
  `CallCounter.java` (limits by supplier); `backend/libs/core/src/main/kotlin/ai/helper2/search/WebSearch.kt`
  (`WebSearchQuota`), `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt`.
- Declarations: `backend/apps/public/src/main/java/ai/helper2/guide/internal/GuideConfiguration.java`,
  `backend/libs/core/src/main/java/ai/helper2/search/internal/TranslationConfiguration.java`,
  `backend/libs/core/src/main/java/ai/helper2/search/config/SearchConfiguration.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/AuditTrail.java`, `identity/AuditLog.java` (before, after,
  reason).
- `api/admin.yaml` — `RuntimeSettings`, `RuntimeSettingState`, `RuntimeSettingChange`, `RuntimeSettingReset`;
  `AuditEntry` and `AdminOverview` fields.
- `frontend/src/admin/RuntimeSettingsPanel.tsx`, `SettingsPage.tsx`, `AdminOverview.tsx`, `AuditPage.tsx`.
- Tests: `platform/settings/RuntimeSettingTest.java`, `RuntimeSettingsTest.java`, `JdbcRuntimeSettingStoreIT.java`,
  `admin/usecase/RuntimeSettingReasonTest.java`, `platform/config/RuntimeSettingsPropertiesBindingTest.java`,
  `platform/llm/BudgetLedgerTest.java`, `api/AdminRuntimeSettingsApiIT.java`, `frontend/src/admin/SettingsPage.test.tsx`,
  `Admin.test.tsx`.

## Alternatives considered
### Variables, profiles and secrets
- GitHub Actions secrets — long-lived values in a second place, readable by any workflow change.
- External Secrets Operator or the OpenBao agent in the cluster — another controller to run for one app. The
  chart only names the Secrets (`envSecret.name`, `credentialsSecret.name`), so either can fill them later without a
  chart change.
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

### Settings files
Judged on: which file wins, profile documents and import order, test contexts, the IDE and `bootRun`, the image, Helm,
and what a person must keep in mind.
- **A shared yaml that each app imports (chosen).** The order is in the app's file, in its first three lines. Tests,
  `bootRun` and the image find the file on the classpath with no extra step, and Helm sets the same variables. The
  one thing to know is that an import in the first document sits below the documents after it; a test holds it.
- A shared `application.yaml` in a library jar, the app's part in `application-<app>.yaml` — Spring Boot reads one
  `application.yaml` per classpath location, so the app's part needs a profile that is always on. Each app, each test
  context and each binding test must then switch it on; one that forgets loads half the settings and says nothing.
  The other way, the app's part in `classpath:/config/application.yaml`, is the place the tests already use for
  their own values.
- Generate both files at build time from a shared part and a part per app — the file that runs is in no folder of
  the repo, a search for a key finds a template, the order is whatever the merge task does, and the build gets a task
  to keep.
- Keep two full files and a test that compares them — safe, but every shared setting is still written twice.

### Runtime settings
- Change the env Secret and deploy — no new code, but minutes and a restart, and no audit row.
- Redis for the overrides — fast and shared, but Redis is disposable: a flush would silently restore every default.
- A config server (Spring Cloud Config, Consul) — a new service to run for six numbers.
- Read the table on every call — always fresh, but a database read on every guide or search request.
- Spring `@RefreshScope` — rebuilds beans on refresh, and still needs a store and an audit.
- A row per setting, updated in place — simpler reads, but the history and the undo need a second table.

## Consequences
### Variables, profiles and secrets
- One search for `H2_` finds every setting. A missing or misspelled prefix is easy to spot in review.
- No secret is stored in GitHub: the deploy reads OpenBao after a login that is bound to the repository and the
  job's environment.
- No app process holds the database owner's or Redis' default password, and a leaked app password opens neither
  the owner nor the other app.
- A changed value restarts the pods that use it on the next deploy.
- A Helm rollback restores the chart and the images, not app.env: a bad value is fixed in OpenBao and deployed again.
- The Redis hash in the pod annotations is unsalted, so the passwords must be long and random (32+ characters,
  `openssl rand -hex 32`).

### Settings files
- A shared setting is one line in one file. Nothing in the image, the chart or the env files changes: both files are
  on the classpath, in the app's jar and in the `platform` jar.
- A search for a key can end in two files: the shared one first, then the app's.
- A setting that changes value changes a recorded file too, in the same commit.
- `.env` sits below an app's own values. It makes a difference only for a Spring key written in `.env` under its own
  name and set in the app's file, which §1 already rules out.

### Runtime settings
- A spending limit changes in seconds, without a restart, and the audit trail says who changed it, from what, to
  what and why.
- Every replica applies a change within about 20 seconds. Until then two replicas can apply two limits: the shared
  counters keep the total right, only the cut-off moves by those seconds.
- The Settings page's read-only groups show the value now for these six settings, and the `H2_` value as the default.
- Raising a maximum is a code change, reviewed like any other.
- Emergency stop: a 0 reaches every replica within about 20 seconds (the replica that made the change at once). A
  pod whose first read of the store fails (database down) runs on the `H2_` defaults until the store answers. To
  stop a use for sure through an outage, also set the `H2_` value to 0 and deploy.
- Reloads run one at a time on a replica (the refresh thread and the replica's own reload after a change), and a
  read never overwrites a snapshot that another read applied while it ran. So an older snapshot never overwrites a newer
  one, and a store whose version went back (restored from a backup) wins: what it holds now applies.
- The env value is still read at startup. A new `H2_` value with an override in place changes the default only.

### Known limits
- The Kubernetes Secret is base64 in the cluster: namespace access means secret access.
- A renamed variable needs its OpenBao entry rewritten by hand (an old name is ignored); an `app_env` change needs `make credentials` and a deploy.
- A runtime setting change reaches the other replicas within about 20 s, so for those seconds two replicas can cut off at two limits.
- The Settings page's read-only groups show the startup value for every other setting. The public app's groups on that page are the ones its last started replica wrote ([0038](../backoffice/0038-back-office-and-review.md)): during a rolling deploy they can be the old or the new version's, and two replicas with different env values show only the last one's.
- A change has no Grafana annotation, so a dashboard does not show when a limit moved.
- A limit raised for one day stays until someone resets it: an expiry ("until midnight UTC") would need one more column.
- The six maximums are first guesses (2.5 to 5 times the prod values); revisit them after a month of use.
- A new `H2_` default with an override in place changes nothing until the override is reset.

## Diagrams
Variables and secrets: [flow](../../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../../diagrams/sequence-0007-configuration-and-secrets.svg) ·
runtime settings: [flow](../../diagrams/flow-0007-runtime-settings.svg) · [sequence](../../diagrams/sequence-0007-runtime-settings.svg) ·
settings files: [flow](../../diagrams/flow-0007-settings-files.svg). The files are read once at startup, with no
call path of their own, so they have no sequence diagram; the startup binding is in the first sequence.

## Backlog
- Decides: [F1.6](https://github.com/silvioincalza/helper2.ai/issues/25), [F6.4](https://github.com/silvioincalza/helper2.ai/issues/84).
- Open issues that change it:
  - [S6.5.1](https://github.com/silvioincalza/helper2.ai/issues/308): every setting on `/admin/settings`.
  - [S10.10.1](https://github.com/silvioincalza/helper2.ai/issues/355): the deployer token minted per deploy.
