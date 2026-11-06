# 0060 — Runtime settings: a few spending limits change from the back office, without a deploy

**Status.** Accepted. **Builds on:** [0005](../foundation/0005-redis-disposable-cache.md), [0007](../foundation/0007-configuration-and-secrets.md), [0017](../guide/0017-guide-cost-guard.md), [0023](../edge/0023-identity-and-admin.md), [0034](0034-data-retention.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0043](../scout/0043-enrichment-gap-round.md), [0045](../edge/0045-back-office.md)

## Context
Every limit is an `H2_` variable. To lower the guide's daily model calls during an abuse spike, or to give the Scout
more enrichment calls for one day after a fresh database, I change the env Secret and deploy: a few minutes, a
restart of every replica, and the change is in no audit trail. The Settings page ([0045](../edge/0045-back-office.md))
shows the values, but cannot change them. Most settings must stay that way: a model id, a prompt or a secret
changed at runtime is a release without a review. A few spending limits are different: they only say how much we
spend, and the right number changes with the day.

## Decision
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
3. **Postgres, append-only.** Table `runtime_setting` (V15): one row per change, with the value, who, when and an
   optional reason. The current value is the newest row; "Reset to default" appends a row with no value. A trigger
   refuses every UPDATE. Not Redis: Redis is a disposable cache ([0005](../foundation/0005-redis-disposable-cache.md)),
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
   character (line break, zero-width or bidirectional mark) is a 400, and nothing is stored. V15 adds `before_value`,
   `after_value` and `reason`, and re-creates the action check with every action of V12 (`BACKFILL_CATEGORIES`
   included), the enrichment queue actions of V14 (`REQUEUE_ENRICHMENT`, `SKIP_ENRICHMENT`, `PRIORITISE_ENRICHMENT`)
   and `CHANGE_SETTING`. V14 runs first: Flyway refuses a lower version after a higher one.
8. **The page.** `/admin/settings` lists the limits first: the value now, the default, the allowed range with the unit
   and one line of help. Change → "Review changes" shows old → new → Confirm, with an optional reason. History lists
   the last 10 changes; Restore goes through the same review. The overview says how many limits are changed.
   API: `GET /api/v1/admin/runtime-settings`, `PUT /api/v1/admin/runtime-settings/{key}`,
   `POST /api/v1/admin/runtime-settings/{key}/reset`, ADMIN only, admin port, CSRF on writes.
9. **Retention.** A change is kept 24 months (`H2_RETENTION_RUNTIME_SETTINGS`); each setting's newest change stays,
   because it is the value in force. Purged daily, on one replica.

## Consequences
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

## Alternatives considered
- Change the env Secret and deploy — no new code, but minutes and a restart, and no audit row.
- Redis for the overrides — fast and shared, but Redis is disposable: a flush would silently restore every default.
- A config server (Spring Cloud Config, Consul) — a new service to run for six numbers.
- Read the table on every call — always fresh, but a database read on every guide or search request.
- Spring `@RefreshScope` — rebuilds beans on refresh, and still needs a store and an audit.
- A row per setting, updated in place — simpler reads, but the history and the undo need a second table.

## Code
- `backend/src/main/java/ai/helper2/platform/settings/` — `RuntimeSetting.java` (the allow-list), `RuntimeSettings.java`
  (the snapshot), `RuntimeSettingStore.java`, `JdbcRuntimeSettingStore.java`, `RuntimeSettingsConfiguration.java`
  (refresh thread, purge).
- `backend/src/main/java/ai/helper2/platform/config/RuntimeSettingsProperties.java`, `PlatformRetentionProperties.java`.
- `backend/src/main/resources/db/migration/V15__runtime_setting.sql`.
- `backend/src/main/java/ai/helper2/admin/usecase/GetRuntimeSettings.java`, `ChangeRuntimeSetting.java`,
  `ResetRuntimeSetting.java`; `admin/web/RuntimeSettingsController.java`.
- `backend/src/main/java/ai/helper2/platform/llm/DailyBudget.java`, `BudgetLedger.java`, `CallCounter.java` (limits by
  supplier); `kotlin/ai/helper2/search/WebSearch.kt` (`WebSearchQuota`), `kotlin/ai/helper2/scout/usecase/RunScout.kt`.
- Declarations: `guide/internal/GuideConfiguration.java`, `search/internal/TranslationConfiguration.java`,
  `search/config/SearchConfiguration.java`, `scout/internal/ScoutConfiguration.java`.
- `backend/src/main/java/ai/helper2/identity/AuditTrail.java`, `identity/internal/AuditLog.java` (before, after, reason).
- `api/openapi.yaml` — `RuntimeSettings`, `RuntimeSettingState`, `RuntimeSettingChange`, `RuntimeSettingReset`;
  `AuditEntry` and `AdminOverview` fields.
- `frontend/src/pages/admin/RuntimeSettingsPanel.tsx`, `SettingsPage.tsx`, `AdminOverview.tsx`, `AuditPage.tsx`.
- Tests: `platform/settings/RuntimeSettingTest.java`, `RuntimeSettingsTest.java`, `JdbcRuntimeSettingStoreIT.java`,
  `admin/usecase/RuntimeSettingReasonTest.java`,
  `platform/config/RuntimeSettingsPropertiesBindingTest.java`, `platform/llm/BudgetLedgerTest.java`,
  `api/AdminRuntimeSettingsApiIT.java`, `frontend/src/pages/admin/SettingsPage.test.tsx`, `Admin.test.tsx`.

## Diagrams
[flow](../../diagrams/flow-0060-runtime-settings.svg) · [sequence](../../diagrams/sequence-0060-runtime-settings.svg)

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
