# 0010 — Data retention

**Depends on:** [0004](0004-persistence-and-the-schema.md), [0005](0005-redis-as-a-disposable-cache.md), [0009](0009-observability.md), [0036](../identity/0036-identity-and-the-admin-host.md)

## Context
Helper2 keeps three kinds of data: what sources said (the catalog), what the Scout and the model did (run history,
enrichment), and what people gave it (accounts, bookmarks, reports, review decisions, admin actions). Data
minimisation (nFADP Art. 6(4), GDPR Art. 5(1)(e)) asks that each be kept only as long as it serves its purpose, and
the privacy page must state those periods. Some data ages fast: a news item from Tavily is stale after weeks. A past
event is kept ten years, as a record of what took place. Some is personal: an account nobody uses is personal data with no purpose.
The periods are a product decision the owner may change; changing one must not need a release.

## Decision
1. **One table of record.** Every data flow and store, its retention, and what enforces it:

   | Store | Data | Retention | Mechanism |
   |---|---|---|---|
   | Postgres `resource` (`OBSERVED`) | resources only searches saw | 180 days after `last_seen_at`; one with a field an admin set (`curated_fields`) stays | catalog purge |
   | Postgres `resource` (`INDEXED`, `CURATED`, `DEMO`) | Scout and seed resources | until the source withdraws them (a repository deleted, archived or renamed on GitHub, an MCP server deleted from the registry); a resource left with no record goes with them; curated and demo rows as long as their seed, a curated event at most until the events rule below | Scout, after each run ([0011](../scout/0011-scout-runner.md) §4), seeds ([0004](0004-persistence-and-the-schema.md)) |
   | Postgres `source_record` from a web source (`WebSources`: `live-web`, `live-web-scout`, `course-scout`, every `discovery-*`, [0013](../scout/0013-discovery.md), and `linkedin-scout`, [0012](../scout/0012-scout-collectors-and-events.md)) or a news feed (every `feed-*`, `NewsFeeds`, [0016](../scout/0016-news-feeds-of-ai-companies.md)), type `NEWS` or `ARTICLE`, with the search facts in its `extra` | web news and list pages | 180 days after `retrieved_at`; a resource left without records goes too | catalog purge |
   | Postgres `resource` of type `EVENT` (`OBSERVED`, `INDEXED`, `CURATED`) | events | 10 years after `event_ends_at` (or `event_starts_at` without an end), whatever their origin; the quote of a curated event's days (`evidence`) goes in the same statement, because nothing else points at it | catalog purge |
   | Postgres `source_record`, `resource_label`, `resource_embedding`, `evidence` and its join tables, `field_assertion`, `relationship`, `summary_claim` | facts of a resource | as long as their resource; when a source withdraws a record, its evidence goes, and every assertion (not a curated one), relationship and summary claim left without evidence | `ON DELETE CASCADE`; Scout withdrawal ([0011](../scout/0011-scout-runner.md) §4) |
   | Postgres `enrichment_run` | model runs | 90 days after `started_at`; the latest run of each resource, and any run a current assertion, relationship, summary claim or review item cites, stay | catalog purge |
   | Postgres `review_item` | review decisions | 180 days after `resolved_at` (decided by a person or closed by the system); open items stay | catalog purge |
   | Postgres `candidate_decision` | the decision on a proposed page: kind, a one-way key of its address (the UUIDv5 a resource id is made with, [0004](0004-persistence-and-the-schema.md)), status, time. Not the address itself: a LinkedIn post's address names a person. The key is pseudonymous, not anonymous: whoever has the address can compute it | as long as the catalog runs: it keeps a rejected page from coming back and an accepted product first in the enrichment backlog after its review item goes | none (written with the decision, [0013](../scout/0013-discovery.md)) |
   | Postgres `issue_report` | user reports | 12 months after their review was resolved, with that review item; open ones stay | catalog purge |
   | Postgres `scout_run` | Scout run history | 90 days after `started_at`; a `RUNNING` record stays | Scout purge |
   | Postgres `live_web_query_result` | Tavily result sets, keyed by an HMAC of the query ([0027](../search/0027-live-web-store-first-daily-quota.md)) | 7 days after `fetched_at` | search purge |
   | Postgres `ai_cache`, cache `translation` | English translations of non-English searches, keyed by a hash of the query | 24 h (`H2_SEARCH_TRANSLATION_TTL`) | read ignores it after `expires_at`; shared cache purge |
   | Postgres `ai_cache`, cache `query-vector` | query vectors (embeddings) of searches, keyed by a hash of the query | 7 days (`H2_SEARCH_QUERY_VECTOR_TTL`) | read ignores it after `expires_at`; shared cache purge |
   | Postgres `ai_cache`, cache `tool-guide` | setup guide answers (checked steps, document links), no user text | 7 days (`H2_SETUP_GUIDE_CACHE_TTL`), 10 minutes for an unreadable model answer (`H2_SETUP_UNREADABLE_TTL`) | read ignores it after `expires_at`; shared cache purge |
   | Postgres `ai_cache`, cache `guide-answer` | guide answers, keyed by a hash of the question and the build ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) | 24 h after the answer (`H2_GUIDE_CACHE_TTL`), 6 h for a dated question, never past its web results | read ignores it after `expires_at`; shared cache purge |
   | Postgres `guide_similar_question` | the query embedding of a cached answer's question, and its reading group (a hash), only with `H2_GUIDE_SIMILAR_THRESHOLD` set ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) | as long as its answer (`expires_at`) | read ignores it after `expires_at`; guide purge |
   | Postgres `awesome_list_entry`, `awesome_list` | tsvectors of awesome-list entry lines and headings, heading paths (≤ 200 characters); no list text ([0023](../search/0023-catalog-full-text-index.md)) | until the list or the resource goes; 30 days after a pass last refreshed them (`H2_RETENTION_LIST_INDEX`) | `ON DELETE CASCADE`, catalog purge |
   | Postgres `source_cursor`, `scout_schedule`, `source`, `shedlock` | one row per source, schedule or lock | overwritten in place | — |
   | Postgres `app_user` | GitHub numeric id, display name, last sign-in, the radar motion setting | until the user deletes it; 24 months after `last_signed_in_at` | `DeleteMyAccount`, account purge (public app) |
   | Postgres `bookmark` | resource id, title, link, type, saved at | until the user removes it or the account goes | `RemoveBookmark`, `ON DELETE CASCADE` |
   | Postgres `admin_audit` | admin id, action, target, time, outcome; for a setting change the value before and after and the reason | 24 months after `at`, never less (V1 refuses a younger delete, [0036](../identity/0036-identity-and-the-admin-host.md) §9) | audit purge (back office) |
   | Postgres `runtime_setting` | setting key, value, admin id, time, reason ([0007](0007-configuration-secrets-runtime-settings.md)) | 24 months after `changed_at` (`H2_RETENTION_RUNTIME_SETTINGS`); each setting's newest change stays while it is in force | platform purge |
   | Redis `h2:v1:src:*` | source responses, keyed by a hash of the query | 24 h (`H2_SEARCH_STALE_FOR`) | Redis TTL |
   | Redis `h2:v1:budget:*` | daily model-call counts, per-client counts under a keyed hash of the address and the day ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)), and the random ids of the questions given back to them | 2 days | Redis TTL |
   | Redis `h2:v1:rate:*` | rate-limit windows, keyed by a keyed hash of the client address and the day ([0037](../identity/0037-http-edge.md)) | 1 minute or 1 hour (the window) | Redis TTL |
   | Redis `h2:v1:quota:*` | daily web searches per feature: every visitor under an HMAC of the address and the day, signed in or not; the global count ([0027](../search/0027-live-web-store-first-daily-quota.md)) | 24 h after the first count | Redis TTL |
   | Redis `h2:v1:outbound:*` | one key per outside host while its next call must wait, no user data ([0006](0006-outbound-calls-and-resilience.md)) | the host's interval | Redis TTL |
   | Redis `h2:v1:setup:fetches:*` | the setup guide's README fetches of the hour, a count, no user data | 2 h | Redis TTL |
   | Redis `h2:v1:scout:page-text:*` | cleaned text of a product's own pages, keyed by the page address ([0014](../scout/0014-evidence-gated-enrichment.md)) | at most 1 day (`H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL`) | Redis TTL (back office) |
   | Redis `h2:v1:session:public:*`, `h2:v1:session:backoffice:*` | sign-in sessions (JSON, [0004](0004-persistence-and-the-schema.md)): the user id, the name shown and the roles; cookie `SESSION` on the public site, `__Host-SESSION` on the back office | 30 minutes idle, or sign-out (`server.servlet.session.timeout`, set in each app) | Spring Session expiry |
   | In-memory caches (setup materials and registry specs) | copies of the above | 1 h; bounded entries; lost on restart | TTL and size bound |
   | Browser `localStorage` | `h2.theme`, `h2.setup.client` (chosen AI client), `h2.bookmarks.v1` (bookmarks while signed out), `h2.consent` (the analytics answer) | until the user clears it; bookmarks move to the account at sign-in | the user's browser |
   | Browser `sessionStorage` | `h2.signInReturn`, `h2.chunkReload`, scroll positions | until the tab closes | the user's browser |
   | Logs (Loki), traces (Tempo), metrics (Prometheus) | technical logs with addresses, traces, meters | 90 days | [0009](0009-observability.md) |
   | Database backups (`pg-backup`) | full dumps | 7 days; the encrypted off-site copy 7 days, by its bucket's lifecycle rule | [0009](0009-observability.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md) |

   A resource is never purged while an issue report or a review item still points at it, nor (for an observed one)
   while a resource of a stronger origin links to it or an admin set one of its fields; reports and review items have their own periods, so such a
   resource goes in a later purge.
2. **Every period is configuration.** `helper2.retention.*` in `helper2-shared.yaml`, each with an `H2_RETENTION_*`
   variable and an ISO-8601 default: `OBSERVED_UNSEEN` P180D, `ENRICHMENT_RUNS` P90D, `RESOLVED_REVIEW_ITEMS`
   P180D, `RESOLVED_ISSUE_REPORTS` P365D, `WEB_NEWS` P180D, `SCOUT_RUNS` P90D, `PAST_EVENTS` P3650D,
   `INACTIVE_ACCOUNTS` P730D, `ADMIN_AUDIT` P730D, `TAVILY_RESULTS` P7D, `LIST_INDEX` P30D
   ([0023](../search/0023-catalog-full-text-index.md)). Cached guide answers keep the expiry
   their own time to live gives them (`H2_GUIDE_CACHE_TTL`). `H2_RETENTION_CRON` (default `0 17 3 * * *`, UTC) sets when the
   purges run.
3. **One purge per owning module.** The module that owns a table deletes from it:
   `catalog.usecase.PurgeExpiredData` (review items, issue reports, enrichment runs, web news, past events,
   observed resources, in that order, so a resource that only a purged report kept goes in the same run; SQL in
   `catalog.internal.CatalogRetention`), `scout.usecase.PurgeExpiredData` (run history, through the
   `RunHistoryRetention` port), `identity.usecase.PurgeInactiveAccounts` (inactive accounts, in the public app) and
   `identity.usecase.PurgeExpiredAuditEntries` (audit entries, only in the back office),
   `search.usecase.PurgeExpiredData` (stored Tavily result sets, through the `WebResultRetention` port),
   `guide.usecase.PurgeExpiredData` (expired rows of the similar-question index, through the
   `SimilarQuestionRetention` port) and, in the open `platform` module that has no use cases,
   `platform.cache.SharedCacheRetention` (expired `ai_cache` rows). The period of an `ai_cache` row and of an indexed
   question is the entry's own TTL, set when it is written, so it has no `H2_RETENTION_*` variable. Each module's
   `*RetentionSchedule` gives one `DailyJob(name, task)` bean; `platform.DailyJobs` (a `SchedulingConfigurer`) runs
   every one daily on the cron of the `RetentionSchedule` record (`helper2.retention.cron`, UTC) under its own
   cluster lock `retention-<name>` (`retention-catalog`, `retention-scout`, `retention-accounts`, `retention-audit`,
   `retention-search`, `retention-guide`, `retention-shared-cache`, `retention-runtime-settings`,
   `ClusterLocks.keptAlive`), so one replica runs it. A failed purge is logged and the next day's run catches up;
   each logs what it deleted.
4. **Foreign keys carry the deletes.** Every foreign key states its delete rule. A purge deletes the parent row;
   children go by `ON DELETE CASCADE`, links that must outlive their target are `ON DELETE SET NULL`
   (`review_item.relationship_id`, `relationship.to_resource_id`). A source is never
   deleted while catalog data cites it (`ON DELETE RESTRICT` on `resource` and `source_record`); its cursor and run
   history go with it. An enrichment run that a current assertion, relationship, summary claim or review item cites
   is `ON DELETE NO ACTION`: it never goes on its own, and a resource delete that cascades to both the run and the
   rows citing it passes, because the check runs at the end of the statement. Each purge has an index on the column
   it filters by (`resource_observed_seen_idx`, `review_item_resolved_idx`, `app_user_last_signed_in_idx`,
   `admin_audit_at_idx`, `live_web_query_result_fetched_idx`, `ai_cache_expires_idx`, and the run and report indexes on the citing tables),
   and every foreign key a delete follows leads an index, so a cascade never scans a child table.

### Code
- `backend/libs/platform/src/main/java/ai/helper2/platform/DailyJob.java`, `DailyJobs.java`, `ScheduledJob.java` — the
  daily run, its lock and its name in the scheduler.
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/PurgeExpiredData.java`, `catalog/internal/JdbcCatalogRetention.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/catalog/schedule/CatalogRetentionSchedule.java` — catalog rules.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/PurgeExpiredData.kt`, `scout/Retention.kt`,
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ScoutRetentionSchedule.java`, `JdbcScoutStore.java` — Scout rule.
- `backend/libs/core/src/main/java/ai/helper2/identity/usecase/PurgeInactiveAccounts.java`, `PurgeExpiredAuditEntries.java`,
  `identity/Users.java`, `AuditLog.java`, `identity/internal/JdbcUsers.java`, `JdbcAuditLog.java`,
  `backend/apps/public/src/main/java/ai/helper2/identity/schedule/AccountRetentionSchedule.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/identity/schedule/AuditRetentionSchedule.java` — identity rules.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/usecase/PurgeExpiredData.kt`,
  `backend/apps/public/src/main/java/ai/helper2/search/config/SearchRetentionSchedule.java`,
  `backend/libs/core/src/main/java/ai/helper2/search/internal/JdbcWebResultStore.java`
  — search rule.
- `backend/libs/platform/src/main/java/ai/helper2/platform/cache/SharedCacheRetention.java`, `SharedCacheConfiguration.java` — shared
  cache rule.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/PurgeExpiredData.kt`,
  `backend/apps/public/src/main/java/ai/helper2/guide/schedule/GuideRetentionSchedule.java`, `guide/internal/JdbcSimilarQuestionIndex.java`
  — guide rule.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — foreign keys and retention indexes.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` — `helper2.retention.*`.
- `frontend/src/i18n/locales/en.legal.json` — the privacy page's periods.

## Alternatives considered
- One purge job for every table — it would reach into every module's tables and break module ownership
  ([0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)).
- PostgreSQL partitioning by time and dropping old partitions — fast for very large append-only tables; these tables
  are small, and most rules depend on references, not only on age.
- `pg_cron` in the database — a second scheduler outside the app, with SQL that the app's tests do not cover.
- Periods fixed in code — every change would need a release.

## Consequences
- Every store has a stated period, and the privacy page states the ones that hold personal data.
- The owner changes a period with one variable and a restart. The admin audit period only goes up that way: V1 keeps
  every entry for 729 days, and the apps refuse less than 730 ([0036](../identity/0036-identity-and-the-admin-host.md) §9).
- A purge never leaves a dangling row and never deletes a resource a reviewer still needs.
- A past event has one period, whoever listed it. A curated event is one edition with fixed days, so it ages like
  an event the Scout found: "Next events" drops it the day after its last day, search and the catalog ten years later.
  The ten years are `H2_RETENTION_PAST_EVENTS`; a shorter one applies to every origin.
- A seed runs again only when its file changes ([0004](0004-persistence-and-the-schema.md) §5). A
  research file that still lists an event ended more than ten years ago puts it back on that run, and the next purge
  deletes it again.
- Each rule has an integration test with a fixed clock on the real schema: `CatalogPurgeExpiredDataIT`,
  `ScoutPurgeExpiredDataIT`, `AccountRetentionIT`, `AuditRetentionIT`, `JdbcWebResultStoreIT`, and `JdbcSharedCacheIT` on the database clock. `SchemaContractTest` checks
  every foreign key's delete rule and its index.

### Known limits
- A deleted row stays in the backups for up to 7 days.
- Purges run daily, so a row can outlive its period by a day.
- A purged observed resource seen again comes back with a new `first_discovered_at`.
- A curated event leaves the catalog ten years after its last day, like every event; until then search still finds it. A research file that still lists an event ended more than ten years ago puts it back when the seed changes, until the next daily purge ([0012](../scout/0012-scout-collectors-and-events.md)).

### Points for a lawyer
- Keeping a hashed key of rejected candidate URLs with no time limit (`candidate_decision`): a pseudonymous key, whose
  address can name a person (a LinkedIn post).

## Diagrams
[flow](../../diagrams/flow-0010-data-retention.svg) · [sequence](../../diagrams/sequence-0010-data-retention.svg)

## Backlog
- Decides: [F1.9](https://github.com/silvioincalza/helper2.ai/issues/28).
- Open issues that change it: none.
