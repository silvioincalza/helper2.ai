# 0034 — Data retention: a period and a mechanism for every store; daily purges per module

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0005](../foundation/0005-redis-disposable-cache.md), [0023](../edge/0023-identity-and-admin.md), [0026](0026-observability.md)

## Context
Helper2 keeps three kinds of data: what sources said (the catalog), what the Scout and the model did (run history,
enrichment), and what people gave it (accounts, bookmarks, reports, review decisions, admin actions). Data
minimisation (nFADP Art. 6(4), GDPR Art. 5(1)(e)) asks that each be kept only as long as it serves its purpose, and
the privacy page must state those periods. Some data ages fast: a news item from Tavily is stale after weeks, a past
event after a year. Some is personal: an account nobody uses is personal data with no purpose.
The periods are a product decision the owner may change; changing one must not need a release.

## Decision
1. **One table of record.** Every data flow and store, its retention, and what enforces it:

   | Store | Data | Retention | Mechanism |
   |---|---|---|---|
   | Postgres `resource` (`OBSERVED`) | resources only searches saw | 180 days after `last_seen_at` | catalog purge |
   | Postgres `resource` (`INDEXED`, `CURATED`, `DEMO`) | Scout and seed resources | while a source lists them; curated and demo rows as long as their seed | Scout upserts, seeds ([0004](../foundation/0004-persistence-and-provenance.md)) |
   | Postgres `source_record` from `tavily` and `tavily-scout`, type `NEWS` or `ARTICLE` | web news | 180 days after `retrieved_at`; a resource left without records goes too | catalog purge |
   | Postgres `resource` of type `EVENT` (`OBSERVED`, `INDEXED`) | events | 1 year after `event_ends_at` (or `event_starts_at` without an end) | catalog purge |
   | Postgres `source_record`, `resource_label`, `resource_embedding`, `evidence` and its join tables, `field_assertion`, `relationship`, `summary_claim` | facts of a resource | as long as their resource | `ON DELETE CASCADE` |
   | Postgres `enrichment_run` | model runs | 90 days after `started_at`; the latest run of each resource, and any run a current assertion, relationship, summary claim or review item cites, stay | catalog purge |
   | Postgres `review_item` | review decisions | 180 days after `resolved_at` (decided or superseded); open items stay | catalog purge |
   | Postgres `issue_report` | user reports | 12 months after their review was resolved, with that review item; open ones stay | catalog purge |
   | Postgres `scout_run` | Scout run history | 90 days after `started_at`; a `RUNNING` record stays | Scout purge |
   | Postgres `tavily_query_result` | Tavily result sets, keyed by an HMAC of the query ([0037](../search/0037-tavily-store-and-daily-quota.md)) | 7 days after `fetched_at` | search purge |
   | Postgres `source_cursor`, `scout_schedule`, `source`, `shedlock` | one row per source, schedule or lock | overwritten in place | — |
   | Postgres `app_user` | GitHub numeric id, display name, last sign-in | until the user deletes it; 24 months after `last_signed_in_at` | `DeleteMyAccount`, identity purge |
   | Postgres `bookmark` | resource id, title, link, type, saved at | until the user removes it or the account goes | `RemoveBookmark`, `ON DELETE CASCADE` |
   | Postgres `issue_report.reporter_user_id` | link to the reporter | until the account goes | `ON DELETE SET NULL` |
   | Postgres `admin_audit` | admin id, action, target, time, outcome | 24 months after `at` | identity purge |
   | Redis `h2:v1:src:*` | source responses, keyed by a hash of the query | 24 h (`H2_SEARCH_STALE_FOR`) | Redis TTL |
   | Redis `h2:v2:guide-answer:*` | guide answers, keyed by a hash of the question | 24 h (`H2_GUIDE_CACHE_TTL`), 6 h for a dated question | Redis TTL |
   | Redis `h2:v2:tool-guide:*` | setup guide answers, no question text | 7 days (`helper2.setup.guide.cache-ttl`) | Redis TTL |
   | Redis `h2:v1:budget:*` | daily model-call counts, per-client counts under a hash of the address | 2 days | Redis TTL |
   | Redis `h2:v1:rate:*` | rate-limit windows, keyed by the client address | 1 minute or 1 hour (the window) | Redis TTL |
   | Redis `h2:v1:quota:*` | daily web searches per feature: guests under an HMAC of the address and the day, signed-in users under their id; the global count ([0037](../search/0037-tavily-store-and-daily-quota.md)) | 24 h after the first count | Redis TTL, `DeleteMyAccount` |
   | Redis `helper2:session:*` | sign-in sessions | 30 minutes idle | Spring Session expiry |
   | In-memory caches (guide answers, query translations, setup materials and registry specs) | copies of the above | 24 h, 24 h, 1 h, 1 h; bounded entries; lost on restart | TTL and size bound |
   | Browser `localStorage` | language, theme, chosen AI client, bookmarks while signed out | until the user clears it; bookmarks move to the account at sign-in | the user's browser |
   | Logs (Loki), traces (Tempo), metrics (Prometheus) | technical logs with addresses, traces, meters | 90 days | [0026](0026-observability.md) |
   | Database backups (`pg-backup`) | full dumps | 7 days; the off-site copy by its bucket's lifecycle rule | [0026](0026-observability.md), [0027](0027-runtime-topology.md) |

   A resource is never purged while an issue report or a review item still points at it, nor (for an observed one)
   while a resource of a stronger origin links to it; reports and review items have their own periods, so such a
   resource goes in a later purge.
2. **Every period is configuration.** `helper2.retention.*` in `application.yaml`, each with an `H2_RETENTION_*`
   variable and an ISO-8601 default: `OBSERVED_UNSEEN` P180D, `ENRICHMENT_RUNS` P90D, `RESOLVED_REVIEW_ITEMS`
   P180D, `RESOLVED_ISSUE_REPORTS` P365D, `WEB_NEWS` P180D, `SCOUT_RUNS` P90D, `PAST_EVENTS` P365D,
   `INACTIVE_ACCOUNTS` P730D, `ADMIN_AUDIT` P730D, `TAVILY_RESULTS` P7D. `H2_RETENTION_CRON` (default `0 17 3 * * *`, UTC) sets when the
   purges run.
3. **One `PurgeExpiredData` use case per owning module.** The module that owns a table deletes from it:
   `catalog.usecase.PurgeExpiredData` (review items, issue reports, enrichment runs, web news, past events,
   observed resources, in that order, so a resource that only a purged report kept goes in the same run; SQL in
   `catalog.internal.CatalogRetention`), `scout.usecase.PurgeExpiredData` (run history, through the
   `RunHistoryRetention` port), `identity.usecase.PurgeExpiredData` (inactive accounts, audit entries) and
   `search.usecase.PurgeExpiredData` (stored Tavily result sets, through the `WebResultRetention` port). Each runs
   daily on `helper2.retention.cron` under its own cluster lock (`retention-catalog`, `retention-scout`,
   `retention-identity`, `retention-search`, `ClusterLocks.keptAlive`), so one replica runs it. A failed purge is logged and the next
   day's run catches up; each logs what it deleted.
4. **Foreign keys carry the deletes.** A purge deletes the parent row; children go by `ON DELETE CASCADE`, links
   that must outlive their target are `ON DELETE SET NULL` (`issue_report.reporter_user_id`,
   `review_item.relationship_id`, `relationship.to_resource_id`, `enrichment_run.reprocess_of`). Each purge has an
   index on the column it filters by (`resource_observed_seen_idx`, `review_item_resolved_idx`,
   `app_user_last_signed_in_idx`, `admin_audit_at_idx`, `tavily_query_result_fetched_idx`, and the run and report
   indexes on the citing tables).

## Consequences
- Every store has a stated period, and the privacy page states the ones that hold personal data.
- The owner changes a period with one variable and a restart.
- A purge never leaves a dangling row and never deletes a resource a reviewer still needs.
- Each rule has an integration test with a fixed clock on the real schema: `CatalogPurgeExpiredDataIT`,
  `ScoutPurgeExpiredDataIT`, `IdentityPurgeExpiredDataIT`, `JdbcWebResultStoreIT`.

## Alternatives considered
- One purge job for every table — it would reach into every module's tables and break module ownership
  ([0002](../foundation/0002-modular-monolith-hexagonal.md)).
- PostgreSQL partitioning by time and dropping old partitions — fast for very large append-only tables; these tables
  are small, and most rules depend on references, not only on age.
- `pg_cron` in the database — a second scheduler outside the app, with SQL that the app's tests do not cover.
- Periods fixed in code — every change would need a release.

## Code
- `backend/src/main/java/ai/helper2/catalog/usecase/PurgeExpiredData.java`, `catalog/internal/CatalogRetention.java`,
  `catalog/CatalogRetentionSchedule.java` — catalog rules.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/PurgeExpiredData.kt`, `scout/Retention.kt`,
  `backend/src/main/java/ai/helper2/scout/internal/ScoutRetentionSchedule.java`, `JdbcScoutStore.java` — Scout rule.
- `backend/src/main/java/ai/helper2/identity/usecase/PurgeExpiredData.java`, `identity/IdentityRetentionSchedule.java`,
  `identity/internal/Users.java`, `AuditLog.java` — identity rules.
- `backend/src/main/kotlin/ai/helper2/search/usecase/PurgeExpiredData.kt`,
  `backend/src/main/java/ai/helper2/search/config/SearchRetentionSchedule.java`, `search/internal/JdbcWebResultStore.java`
  — search rule.
- `backend/src/main/resources/db/migration/V1__schema.sql` — foreign keys and retention indexes.
- `backend/src/main/resources/application.yaml` — `helper2.retention.*`.
- `frontend/src/i18n/locales/en.json` — the privacy page's periods.

## Diagrams
[flow](../../diagrams/flow-0034-data-retention.svg) · [sequence](../../diagrams/sequence-0034-data-retention.svg)

## Roadmap
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
