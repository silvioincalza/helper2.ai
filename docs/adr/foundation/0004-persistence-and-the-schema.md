# 0004 — Persistence and the schema

**Depends on:** [0001](0001-stack-and-runtime.md), [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0005](0005-redis-as-a-disposable-cache.md), [0007](0007-configuration-secrets-runtime-settings.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)

## Context
The same resource reaches Helper2 from many places: a GitHub repository, its npm package, a Hugging Face
page, a news article. Helper2 must store it once, keep what each source said, and show only dates a source
stated or Helper2 observed itself. Searches see thousands of results a day, and writing them must not slow
the search down.

Two apps share the database: the public app and the back office. One SQL injection in either must not be able to
drop the schema, run a program on the database pod (`COPY ... FROM PROGRAM`) or write the other app's tables. The same
holds for Redis: a public pod must not read or write the back office's sessions, and a session must never make an app
run code while it reads it.

The schema is one file, and from the first release tag on that file must never change.

## Decision
1. **PostgreSQL is the system of record.** PostgreSQL 18.6 with pgvector 0.8.6 (own image,
   `deploy/postgres/Dockerfile`). Connection: `H2_DATABASE_URL`, `H2_DATABASE_USER`, `H2_DATABASE_PASSWORD`;
   pool size `H2_DATABASE_POOL_SIZE` (default 16), connection timeout 3000 ms.
2. **Ids from canonical web addresses (URLs).** `ResourceId` is a universally unique identifier (UUID) version 5
   (RFC 9562) of a resource's canonical URL in a fixed namespace; Structured Query Language (SQL) computes the same
   id with `uuid_generate_v5`. Which address names a resource (the canonical URL, the git ref, a package and its
   repository) is decided in [0021](../scout/0021-resource-identity-one-resource-per-repository.md).
3. **Source records.** `source_record` keeps one row per (`source_id`, `external_id`): what the
   source said, `retrieved_at` and a content `fingerprint`. The licence is stored in its short spelling
   (`ResultMerger.normalizeLicense`: "MIT License" is `MIT`, "Apache License 2.0" is `Apache-2.0`), and a licence the
   source does not assert (`NOASSERTION`, `other`, `UNLICENSED`, "SEE LICENSE IN ...") is none. The fingerprint is of
   what the source said. What the source states about the life of the resource (archived, fork, deprecated,
   running) is kept in `extra.vitality` with the time it was stated, beside the Scout's own check of the
   repository; between the two the newest wins ([0020](../scout/0020-resource-vitality.md) §7). `resource` is the
   merged view. A write never
   overwrites the fields of a resource from a stronger origin (`DEMO` > `CURATED` > `INDEXED` > `OBSERVED`). Labels,
   the licence, the pricing, the open-source flag, the type and event dates are rebuilt from the current records on
   every write. The licence is the one every record that states one agrees on (letter case aside); none when they
   disagree or no record states one. Two spellings of one licence agree, because the records hold the short spelling.
   The pricing and the open-source flag follow the same rule, the one `ResultMerger` applies to the three: one value
   when every record that states one agrees, none otherwise. The
   type is the most specific one a record states, by the same order the merge uses (`ResourceType.specificity`), so
   the order in which collectors run does not change it. All four keep a curated field, and only a write of the
   resource's own origin or a stronger one rebuilds them (an `OBSERVED` search never changes an `INDEXED` resource),
   and only from records of that origin or a stronger one: each record keeps the strongest origin that wrote it
   (`extra.dataOrigin`), so a record only a search saw never changes an `INDEXED` resource at its next indexed write;
   `CURATED` and `DEMO` rows keep what their own write set. A source that states no pricing or open-source flag
   never clears the value another source states; a value no record states any more is cleared.
   The resource page follows the same origin rule: on the card of an `INDEXED` or `OBSERVED` resource, the type, the
   licence, the pricing and the open-source flag come only from the records of the resource's own origin or a
   stronger one (`ResourceCards`, `RecordRow.isOfOriginOrStronger`). A record only a search saw is still a source of
   the card: it is listed with what it states, and every other field of the card (title, description, tags,
   popularity, dates) still counts it. The card of a `CURATED` or `DEMO` resource merges every record, as its row is
   not rebuilt from them.
   The row and the card share one rule: the reconciled facts of the row are a projection of its records. After the
   records of a write are stored, the writer locks the touched resource rows, reads all their records in one
   statement, builds the card of each resource with `ResourceCards.card`, and copies the card's facts into the row in
   one update (`ProjectedReconciliation`). It copies the type, the licence, the pricing and the open-source flag when
   the origin rule above lets the write rebuild them, and the first and last day of an event always, as the card
   shows them: the days the sources state, else the days a model read. So the index hit and the page say the same,
   letter case of a licence included. The vitality is not a column of the row: it stays in the records
   (`extra.vitality`), and every reader merges it. The title, the description, the link and the dates keep the
   upsert rule above.
   The projection runs in the transaction of the write, so a failure rolls the whole write back. It locks the rows
   first (`SELECT ... FOR UPDATE`, in id order, so two writes take their locks in the same order). A write that
   forgets a record waits for a search that is writing the same resource, then reads the records that search left:
   the row is never rebuilt from a view that is already stale. Per write, the facts cost three statements (the
   locked rows, the records, the update; two when nothing changed).
   The older rule in SQL stays behind a temporary switch, `helper2.catalog.reconcile-by-projection`
   (`H2_CATALOG_RECONCILE_BY_PROJECTION`, default `true`). With `false` the writer runs `SqlReconciliation`: two
   statements, `reconcileFacts` and `refreshEventDates`, with the same origin rule. It keeps the letter case of the
   stored licence and takes no lock. The switch exists to test the SQL rule on staging against the projection; then
   `SqlReconciliation` is removed, and the switch with it .
   A record moves to another resource when its source points to another URL. When all the records of the old
   resource have moved to one resource, its user reports (`issue_report`) and review items (`review_item`) move with
   them (`JdbcCatalogWriter.moveReportsOfEmptiedResources`). Then `deleteOrphans` deletes an `OBSERVED` resource left
   without records, unless a report or a review item still points at it, or an admin set one of its fields
   (`curated_fields`). That happens when its records went to
   several resources: the new owner is not clear, so the resource and its reports stay for the reviewer.
4. **Dates are never invented.** `DateFact` is `KNOWN`, `UNKNOWN` or `CONFLICTING`. A source date before
   1995-01-01 or more than one day after retrieval becomes unknown. `CONFLICTING` means two sources state different
   days for the same date; the merge rules are in [0026](../search/0026-filters-facets-categories.md). Date columns are nullable;
   `published_precision = 'UNKNOWN'` exactly when `published_at` is null (a table check).
   `first_discovered_at` is Helper2's own observation, labeled as such.
5. **One schema migration, forward-only from the first release; repeatable seeds.** `V1__schema.sql` is the whole schema: the
   tables, checks, indexes, triggers, functions and app roles the code needs, and nothing it does not use.
   - **One section per context**, in an order that creates a table before the tables that point at it: foundation
     (the locale check, extensions, shared functions), platform, identity, catalog, search, Scout, guide, examples,
     runtime settings, and the app roles last. The setup guide has no table: its answers are in `ai_cache`.
   - **Final state only.** Every table, column, check list and index is written as it is, under its final name
     (`live_web_query_result`). The rows every database needs are inserted in V1: the discovery hosts
     ([0013](../scout/0013-discovery.md)) and the built-in home examples ([0028](../search/0028-home-examples.md)).
   - Every foreign key states its delete rule (`CASCADE`, `SET NULL`, `RESTRICT` or `NO ACTION`,
     [0010](0010-data-retention.md)).
   - **The plpgsql functions set their search_path** (`public, pg_catalog`): `pg_dump` and `pg_restore` run with an
     empty one, and the popularity trigger would stop a data-only restore (the trigger exists before the data) at the
     first `source_record`. The SQL functions get
     no `SET`: it would stop their inlining into the generated `search_vector` and every search. They name their
     schema, and their SQL-standard bodies are bound when they are created.
   - **The locale is pinned.** The text search parser and `pg_trgm` split words by the database's character type,
     and `lower()` and the ordering of text follow it. V1 stops unless the database is UTF8 with `LC_CTYPE` and
     `LC_COLLATE` `en_US.utf8`, the locale search was measured on. The chart, compose and the test containers create
     the database so (`POSTGRES_INITDB_ARGS=--encoding=UTF8 --locale=en_US.utf8`).
   - **Forward-only from the first release tag.** While the history has no release tag `vYYYY.N.P`, V1 is edited in
     place. From the tag on, an applied migration is never edited: an edit changes its checksum, and Flyway refuses
     to start. A change is a new versioned migration (`V2__…`), forward-only.
   - **The dump of the v2.0.0 database.** That database is kept as one encrypted dump for 30 days
     ([0010](0010-data-retention.md)); nothing is restored from it. `deploy/restore/restore-v2-dump.sh` stays until
     the dump is deleted: a dry run on a local scratch database only (accounts merged on the GitHub id, settings,
     discovery hosts; nothing selected by default). It refuses any server not started as the scratch one, and loads
     the dump as a role with no superuser rights, under psql's `\restrict`: a dump is SQL, so it is trusted input,
     made and checksummed by the owner. A database is created empty by
     [the cutover runbook](../../../deploy/k8s/RUNBOOK-reset-cutover.md): with its locale, checked before the
     deploy, Redis cleared by key family, never `FLUSHALL`.
   - **CI holds both halves of the rule** (`.github/scripts/released-migrations.sh`, job `changes`, every pull
     request and every push to `main`). While the history has no release tag `vYYYY.N.P`, a versioned migration other
     than `V1__schema.sql` fails. Once it has one, every versioned migration the latest release holds must be the same
     file, byte for byte: an edit or a removal fails, a new `V2__…` passes. The seeds (`R__…`) may change. The check
     compares with the tag, not with the base of the pull request, so staging's catch-up cannot hide an edit: there
     `repair` takes the new checksum, and prod would refuse it.
   - **Until the tag, every schema change edits V1. There is no V2.** Prod gets V1 on an empty database at the tag.
     Staging keeps its data, so its migrate Job catches up first (`migrate.catchUp: true` in `values-staging.yaml`,
     which sets `H2_SCHEMA_CATCH_UP` on the Job only): on a database that already has a schema it runs
     `db/catch-up/V1__catch_up.sql`, then Flyway `repair` (V1's checksum becomes the file's; a migration whose file is
     gone is marked deleted), then `migrate`. A change to V1 adds its statements to the catch-up file in the same pull
     request, written so that a second run changes nothing (`IF NOT EXISTS`). An empty database skips the catch-up.
     Prod never sets the flag (`make chart-check` fails if it renders). A V1 change without its catch-up statement
     deploys, and staging lacks the object: the review of the pull request is the guard. At the tag the flag, the
     file and `CatchUpThenMigrate` are removed.
   - After the versioned migrations, Flyway runs the seeds `R__curated_seed.sql`, `R__industry_events.sql` and
     `R__courses.sql`. The seeds upsert, so Flyway re-runs one whenever it changes.
     The events seed also removes the curated events that are not in its research file ([0012](../scout/0012-scout-collectors-and-events.md)).
     A seed whose file is gone is refused by Flyway on a database that ran it ("applied migration not resolved
     locally"), until `repair` marks it deleted: staging's catch-up does that, and prod starts empty. After the tag,
     taking a seed out needs a `repair` in the migrate step. The rows a removed seed wrote stay.
6. **Catalog writes off the request path.** `SearchResources` reads first-discovery dates in one batched
   query (`ResultObserver.firstDiscovered`), then hands only the results fetched live in this search to
   `ResultObserver.record`. `CatalogResultObserver.record` queues the write in `ObservationQueue`: one thread
   (`catalog-observations`), a bounded queue (`helper2.catalog.observation-queue`, 256). A full queue drops
   the new write. Meters: `helper2.catalog.observations{outcome=written|dropped|failed}` and
   `helper2.catalog.observations.queued`. The Scout writes synchronously (`IndexResources.index`).
7. **The catalog owns its tables.** Other modules write through catalog ports: search through `ResultObserver`,
   the Scout through `IndexResources.index`, `ReviewQueue` and `EnrichmentResults` ([0014](../scout/0014-evidence-gated-enrichment.md)).
   The catalog's use cases reach the database through ports in `ai.helper2.catalog` ([0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)),
   each with one JDBC adapter in `catalog/internal`:
   - `CatalogWriter` (`JdbcCatalogWriter`): the sources, the upsert of resources, labels, categories, event dates and
     records, and a user's report with its review item. It is the public app's whole write path, so its grants stay
     small (point 9).
   - `Resources` (`JdbcResources`), `CategoryLabels`, `JobMarkers`, `EmbeddingStore`, `AwesomeListIndex`,
     `CuratedFields`, `RelationshipTargets`, `ReviewQueue`, `ReviewDecisions` and `CatalogRetention`: the reads and
     the back office's writes. The search sources read the index through `JdbcCatalogSearch`.
   - `source` keeps only what the code does not hold: its id, its mode and its last outcome. A source's name, homepage,
     terms and attribution are its descriptor in the code and the configuration.
   - `review_item` keeps what an item is about by its kind: a JSON object in `details` (`jsonb`) for a resource
     candidate and a drafted example, read by the module that wrote it; plain text in `note` for every other kind.
     CHECKs tie each column to its kinds.
   - `job_marker` keeps what one-off jobs left: the category backfill's fingerprint, and the maintenance tasks claimed
     once across replicas.
8. **Model answers shared by the replicas in `ai_cache`.** One table, owned by the open `platform` module
   (`platform.cache`, [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)), holds every cache of model answers: query translations
   (cache `translation`, [0023](../search/0023-catalog-full-text-index.md)), query vectors (`query-vector`,
   [0024](../search/0024-vector-search.md)), setup guide answers (`tool-guide`, [0034](../guide/0034-setup-guide.md))
   and guide answers (`guide-answer`, [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)).
   Columns: `cache_name`, `key_hash` (SHA-256 of the entry's parts, never the query text), `value` (text), `expires_at`;
   primary key (`cache_name`, `key_hash`). Port `SharedCache`, adapter `JdbcSharedCache`, created by
   `SharedCaches.named(name)`. A read is one primary-key lookup with `expires_at > now()`; a write is an upsert
   (`ON CONFLICT … DO UPDATE`) with `expires_at = now() + ttl`, so the database clock decides expiry for every replica.
   Each statement runs under the `db-cache` policy ([0006](0006-outbound-calls-and-resilience.md)) with a 1 s statement timeout:
   an error, a timeout, the open circuit or a full bulkhead is a miss and a write that does nothing, and the caller asks
   the model. The daily shared cache purge deletes expired rows ([0010](0010-data-retention.md)).
   Meter `helper2.shared.cache{cache, result}` counts hits and misses ([0009](0009-observability.md)).
9. **A database role per app, with grants per table.** The last section of V1, after every `CREATE TABLE` (a grant
   `ON ALL TABLES` reaches only the tables that exist), creates `h2_public` and `h2_backoffice` without a login,
   when they are missing (roles are cluster-wide), and always resets them to no superuser, no CREATEDB, CREATEROLE,
   REPLICATION or BYPASSRLS, and NOINHERIT. Neither may create in the schema or make temporary tables (`CREATE` and
   `TEMPORARY` revoked from PUBLIC), and neither gets `TRUNCATE`, `REFERENCES` or `TRIGGER`. Both only read
   `flyway_schema_history` (`${flyway:table}` in the migration). The owner stays the user Flyway runs as (`helper2`
   in the chart).
   - `h2_public` reads every table but the Scout's and the enrichment's bookkeeping (`scout_run`, `scout_schedule`,
     `source_cursor`, `discovery_host`, `enrichment_queue_control`, `candidate_decision`) and the admin audit trail
     (`admin_audit`: no right at all, [0036](../identity/0036-identity-and-the-admin-host.md) §9). It writes only what a visit, a
     sign-in, a report or its own jobs write: `resource`, `resource_label`, `source_record` (the live results it
     observes), `source` (its sources at startup and their outcome), `issue_report` and `review_item` (a user's report),
     `live_web_query_result`, `app_user`, `bookmark` (sign-in, account deletion, the account purge),
     `guide_similar_question`, `ai_cache`, `shedlock` and `public_app_setting` (its settings for the back office's
     page, at startup, [0038](../backoffice/0038-back-office-and-review.md) §10), with `DELETE` only where its code deletes.
     No sequence.
   - `h2_backoffice` reads and writes rows on every table, and uses the settings' identity sequence.
   - New tables (default privileges of the owner): both apps read them, only the back office writes them. A new table
     the public app writes needs its own `GRANT ... TO h2_public` in the migration that creates it, and its row in
     `AppRolesGrantsIT`.
   - `AppRolesGrantsIT` holds one row per table and checks every privilege of both roles against the database: a new
     table fails it until it is classified. The app tests connect as these roles, so they show the rows are enough for
     the code; the tests of library code only the back office runs connect as `h2_backoffice` (`@AsBackOffice`).
10. **The migrations run in their own Job.** `java -jar app.jar migrate` on the public image (`MigrateStep`) starts only
    a data source and Flyway: no web server, no Redis, no scheduler. It retries the connection (Postgres may restart in
    the same release), migrates as the owner, then gives both roles their login and password (`AppLogins`, hashed by
    the driver before they are sent) and their statement timeout, and exits. The timeout is a setting of the role
    (`ALTER ROLE ... SET statement_timeout`, from `helper2.schema.statement-timeouts`): 5 s for `h2_public`, 60 s for
    `h2_backoffice`. Postgres cancels a longer statement, so one slow query cannot hold a pooled connection while
    visitors wait. The owner has none: a migration may build an index for minutes. A role taken out of the setting
    gets the timeout reset. A new value reaches a connection only when it is opened. When the schema is unchanged the
    pods of a release do not wait for the Job (§11), so a connection they open before the Job ends keeps the old value
    until the pool renews it, within Hikari's `max-lifetime` (30 min). The chart runs it as the Job `helper2-migrate-<release revision>`, the
    only workload with the owner's password besides Postgres and pg-backup. Each revision is a new Job (the template of
    a Job cannot change); a rollback runs the older image's step, which finds nothing to do.
11. **The apps only check the schema, and wait.** Neither app migrates in a deployment. At startup each one validates
    as its own role (`SchemaCheck`). While migrations are only missing (a new versioned one, a new or changed seed) or
    its login is refused (the Job opens it), it waits, checking every 5 s for up to 4 minutes (`H2_SCHEMA_WAIT`), inside
    the startup probe's 5 minutes. Anything else fails at once, e.g. a changed migration. The old pods keep serving
    meanwhile (`maxUnavailable: 0`). `make deploy` passes `--wait-for-jobs`: the release succeeds only once the Job
    has, and a failed Job rolls it back.
12. **Local development migrates itself.** `H2_SCHEMA_MIGRATE=true` (the dev profile, and the compose `app` service)
    migrates at startup, connected as the owner. The tests do what a deployment does: the shared containers migrate
    once as the owner and open the logins, and each app's tests connect as its role and its Redis user
    (`helper2.test.client`), so a missing grant or Redis permission fails a test.
13. **A Redis ACL user per app.** `deploy/helm/helper2/files/redis-users.acl` lists each app's keys and the exact
    commands its code sends, scripts included (Redis checks the commands and keys inside `EVAL`). Both reach the shared
    rate limits, budgets, quotas, outbound limits and source caches; only the public app its setup fetches and its
    sessions, only the back office the Scout's page texts and its sessions. The default user stays the admin (probes,
    `redis-cli`). The chart and the backend tests read the same file.
14. **Sessions are JSON.** `GenericJacksonJsonRedisSerializer` with Spring Security's Jackson modules; besides their
    types, only the exact plain values a provider's user attributes hold (strings, numbers, booleans, `ArrayList`,
    `LinkedHashMap`, `HashMap`), never a subclass. A session that cannot be read is logged and served as anonymous.
    Namespaces `h2:v1:session:public` and `h2:v1:session:backoffice`.
15. **The passwords in their own Secret.** `make env-secret` splits app.env: the six passwords (the owner's, each
    role's, Redis' default user's and each app user's) go to `helper2-credentials`, every other key to
    `helper2-app-env`. Both apps still load `helper2-app-env` whole; no workload loads `helper2-credentials` whole. Each
    names only its own keys. `check-app-env.sh` requires all six and refuses two that are the same. For the first
    release only, the owner's and Redis' default password also stay in `helper2-app-env`, so a rollback to the old
    chart still starts Postgres and Redis; both apps set those two names under `env:`, which wins over `envFrom`, so no
    app process sees them.

### Code
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/CanonicalUrl.kt`, `ResourceId.kt`, `DateFact.kt` — identity and dates.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/CatalogWriter.java`, `Resources.java`, `ReviewQueue.java`,
  `JobMarkers.java` and the other ports of `ai.helper2.catalog`; `catalog/config/CatalogUseCaseConfiguration.java` (both apps), `CatalogBackOfficeConfiguration.java` and
  `catalog/internal/CatalogBackOfficeAdapters.java` (the back office only).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/CatalogResultObserver.java` — `ResultObserver`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/IndexResources.java` — `index`, the Scout's write.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ObservationQueue.java` — background writer.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcCatalogWriter.java` — upserts, origin rank, labels, moved
  reports, orphan deletion.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/Reconciliation.java`, `ProjectedReconciliation.java`,
  `SqlReconciliation.java`, `CatalogConfiguration.java` (`reconciliation`) — the reconciled facts of the row, and the
  temporary switch between the two paths (`CatalogProperties.reconcileByProjection`).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java`, `JdbcCatalogWriter.java` (`report`),
  `JdbcReviewQueue.java`, `JdbcReviewDecisions.java`, `JdbcJobMarkers.java` — enrichment results, user reports, the review
  queue and its decisions, job markers.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — read dates, then `record`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql`, `R__*.sql` — schema, app roles and seeds.
- `backend/libs/schema/src/main/java/ai/helper2/platform/schema/MigrateStep.java`, `AppLogins.java`, `SchemaCheck.java` —
  the migrate step, the logins, the apps' schema check.
- `.github/scripts/released-migrations.sh` — CI: V1 only before the first release, no released migration changes after.
- `backend/apps/backoffice/src/test/java/ai/helper2/SchemaContractTest.java` — the schema contract.
- `backend/apps/public/src/test/java/ai/helper2/AppRolesGrantsIT.java`, `MigrateStepIT.java` — the grants per table, the
  migrate step.
- `backend/apps/public/src/test/java/ai/helper2/catalog/internal/IndexHitAndCardParityIT.java`,
  `IndexHitAndCardParityWithSqlReconciliationIT.java` — the row and the page say the same about a resource, except
  for the listed gaps.
- `backend/apps/public/src/test/java/ai/helper2/catalog/internal/JdbcCatalogWriterIT.java`,
  `JdbcCatalogWriterWithSqlReconciliationIT.java`, `ProjectedReconciliationIT.java`;
  `backend/apps/backoffice/src/test/java/ai/helper2/catalog/internal/ForgettingWhileWritingIT.java` — the writer on
  both paths, concurrent writes, the rollback.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ResourceCards.java`, `RecordRow.java` — the card of the
  resource page; the records that count for its type, licence, pricing and open-source flag
  (`backend/libs/catalog/src/test/java/ai/helper2/catalog/ResourceCardsTest.java`).
- `backend/libs/platform/src/testFixtures/java/ai/helper2/TestcontainersConfiguration.java` — the shared containers,
  the locale, the logins.
- `backend/libs/platform/src/main/java/ai/helper2/platform/cache/SharedCache.java`, `SharedCaches.java`, `JdbcSharedCache.java`,
  `SharedCacheRetention.java`, `SharedCacheConfiguration.java` — the shared caches and their purge.
- `deploy/helm/helper2/templates/postgres.yaml` (`POSTGRES_INITDB_ARGS`), `migrate.yaml`, `files/redis-users.acl`;
  `deploy/k8s/check-app-env.sh`; `compose.yaml`.
- `backend/libs/schema/src/main/java/ai/helper2/platform/schema/CatchUpThenMigrate.java`,
  `backend/libs/schema/src/main/resources/db/catch-up/V1__catch_up.sql`, `deploy/helm/helper2/templates/migrate.yaml`
  (`migrate.catchUp`) — staging follows V1 until the first release tag (§5).
- `deploy/k8s/RUNBOOK-reset-cutover.md` — a database created empty with its locale, the dump of the v2.0.0 database.
- `deploy/restore/restore-v2-dump.sh`, `backend/libs/schema/src/test/java/ai/helper2/platform/schema/RestoreV2DumpIT.java` —
  the restore dry run from the v2.0.0 dump, and its test.
- `deploy/postgres/Dockerfile` — PostgreSQL with pgvector, pinned by commit and checksum.

## Alternatives considered
- Write in the request — every search waits for a multi-row upsert, and parallel searches lock the same rows.
- An event bus or outbox for the writes — durable, but a second moving part for data that the next
  search records again anyway.
- Random ids — the same URL from two sources would become two resources, and links would not be stable
  ([0021](../scout/0021-resource-identity-one-resource-per-repository.md)).
- A document store — the data is relational (records, evidence, labels, bookmarks), and PostgreSQL
  also gives full-text search and vectors.
- A chain of versioned migrations before the first release — every new database replays fixes to rows it never has,
  and a reader sees a chain of changes, not the schema.
- The locale only in the chart — a database made by hand, or by a new image default, would index differently without
  a word; the check in V1 refuses it.
- Model answers in Redis — memory shared with sessions and rate limits under `allkeys-lru`, and not in the backups.
- One table per cache — the same four columns three times, and three purges.
- `UNLOGGED` table for `ai_cache` — faster writes, but emptied after a crash and left out of the backups.
- **A Helm pre-install/pre-upgrade hook** for the migrations — on a fresh install it runs before Postgres exists, so the
  chart could not be installed; and hooks sit outside the release.
- **An init container in each app pod that migrates** — the app pods would hold the owner's password.
- **An init container that waits for the Job** — needs the Kubernetes API (a token and RBAC in every app pod) or a
  second JVM, for what the app's own schema check already does.
- **Row access on every table for both roles** — simpler, but an injection in the public app could rewrite the review
  queue, the settings or the Scout's tables.
- **One Redis database per app** (`SELECT n`) — no isolation: any user may select any database.

## Consequences
- A search never waits for a catalog write.
- One writer thread and rows written in canonical-URL order keep concurrent upserts from deadlocking.
- If the first-discovery read fails, the search still answers, with an `INDEX_UNAVAILABLE` notice and
  unknown discovery dates.
- On shutdown the queue gets 5 seconds to finish.
- The index can be rebuilt by the Scout and the embedding indexer. Bookmarks and reports are user data, kept by
  the backup ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
- `bookmark.resource_id` points at a resource without a foreign key: a bookmark keeps its own title, link and type
  and always renders ([0036](../identity/0036-identity-and-the-admin-host.md) §7). A saved live result may never get a row
  (point 6 drops a write when the queue is full), a merge brings old browser ids, and retention and the orphan delete
  remove resources people saved; none of that may refuse a save or delete a saved item.
- How long each table keeps its rows, and the daily purges that enforce it, are in
  [0010](0010-data-retention.md); the foreign keys and indexes those purges use are part of `V1__schema.sql`.
- One file holds the whole schema, so a reader sees every table as it is, not a chain of changes.
- `AppRolesGrantsIT` (every privilege of both roles on every table), `SchemaContractTest` (foreign-key rules, check
  lists, indexes) and `MigrateStepIT` (the migrate step, the logins, the statement timeout per role, the history)
  guard V1.
- A database that ran another V1 cannot take this one: Flyway sees a different checksum and refuses to start. Such
  a database is recreated empty; locally: `podman compose down -v`.
- A database made with another encoding or locale stops at V1's first statement instead of indexing differently.
  The test image (Debian, glibc) and the deployed image (Alpine, musl) both name the locale `en_US.utf8`, but musl
  sorts text by code point: the check pins the name, not the C library.
- `SchemaContractTest` checks the migrated schema with plain JDBC: every foreign key's delete rule, an index on every
  foreign key a delete follows, every check list equal to the domain vocabulary it stores (the `admin_audit.action`
  list equal to `AuditTrail.Action`), and no table or column the code does not use.
- A merge of two resources never loses a user report. A resource whose records were split over several resources
  stays, without records, until a reviewer handles its reports. `JdbcCatalogWriterIT` covers both cases.
- A resource is read in two ways. The index search reads the `resource` row. The resource page merges the records
  again with `ResultMerger`. The row's type, licence, pricing, open-source flag and event days are copied from that
  same card, so the two agree on them. `IndexHitAndCardParityIT` writes one resource from two sources, in both orders,
  and compares the two on the title, the type, the licence, the pricing, the open-source flag and the event days. The
  cases where they differ are listed in the test, so a new difference fails it and a closed one must leave the list
  (Known limits, below). Only the title is left: the row keeps the title of the last write.
  `IndexHitAndCardParityWithSqlReconciliationIT` keeps the list of the SQL rule, which also differs on the letter
  case of a licence.
- While both paths exist, `JdbcCatalogWriterIT` runs on both (`JdbcCatalogWriterWithSqlReconciliationIT`).
  `ProjectedReconciliationIT` covers two searches that write one resource at once and a projection that fails;
  `ForgettingWhileWritingIT` (back office) covers a record forgotten while a search writes the same resource.
- A query is translated and embedded once for the cluster, and a setup guide is written once, not once per replica.
  These answers are on disk and in the backups, and a slow database costs a lookup at most 1 s.
  `JdbcSharedCacheIT` covers sharing, expiry, the purge, a locked table and a database that is down.
- `ObservationQueueTest` covers the queue (background write, a full queue drops the new write, shutdown). `SearchApiIT` checks the result: unknown
  discovery date on the first search, known on the next.
- A deploy whose schema does not match the database's migration history stops at startup: the new pods never
  become ready, the deploy rolls back and the old pods keep serving ([0044](../operations/0044-ci-cd-and-supply-chain.md)).
- An injection in the public app reaches only the rows it writes (the catalog it observes, users, reports, caches),
  never the Scout's tables, the review decisions, the settings, the schema or the database host. One in the back office
  reaches rows, never the schema or the host.
- A compromised public pod cannot read, forge or delete an admin's session, and a planted session runs no code.
- A deploy runs one more pod, the Job, for a few seconds. A new password for a role takes effect when the Job runs, a
  moment before the app pods restart: an old pod opening a new connection in between is refused until it is replaced.
- app.env in OpenBao holds the six passwords (`openssl rand -hex 32`), and the deployer may `create`/`delete` Jobs and
  `patch` the Secret `helper2-credentials` (the platform repo owns that RBAC; `make credentials` checks it).
- Accepted: `+eval` lets an app run a Lua script that blocks Redis until the default user kills it (`SCRIPT KILL`);
  and both apps share the rate-limit, budget, quota and source-cache keys, so a compromised public pod could reset a
  spend cap or poison the source cache. Both need code running in a pod.
- Not covered: the owner is a superuser (the container's `POSTGRES_USER`), so the migrate Job could reach
  `COPY ... TO PROGRAM`; the rest of app.env (API keys, OAuth secrets) reaches both apps; and the shared
  Redis key families are not split further.

### Known limits
- Each app role has a statement timeout (5 s public, 60 s back office), not a socket timeout: a connection whose network path dies without a reset waits for the operating system. The two values are not measured: read the staging logs for "canceling statement due to statement timeout" before prod.
- The pool (16 a replica), the catalog bulkheads (3 a source) and `max_connections` (150) are not measured. The load test sizes the three together from the pool wait, the calls the catalog bulkheads refuse and the search latency. A catalog bulkhead does not wait, so a fourth concurrent search on a replica gets no results from that catalog source ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
- A resource seen for the first time has no discovery date in that first response (the write is still queued); a dropped write delays it until the resource is seen again. On shutdown the queue gets 5 s; later writes are lost.
- Bookmarks and reports are user data; the backup is the only copy ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
- Two sources that disagree on the licence leave it empty; the review does not show that dispute.
- A record stays until its source withdraws it: an item a source only stops listing (it left the top awesome lists, trending, a Hugging Face organisation's newest models) stays, and so does a Hugging Face model that was deleted.
- The row the index search reads takes its type, licence, pricing, open-source flag and event days from the card of the resource page, but not its title: the row keeps the title of the last write, the page shows the title of the first source by priority. `IndexHitAndCardParityIT` lists that gap. The test covers resources of two indexed sources: the page of a curated resource merges every record, while its row keeps what the curated write set, and no test compares the two ([0022](../search/0022-federated-search.md)).
- The limits of resource identity (a package without its repository, a single file, a sub-folder under two branches) are in [0021](../scout/0021-resource-identity-one-resource-per-repository.md).

### AI architecture check
| # | Layer | Status |
|---|---|---|
| 5 | Memory | covered: sessions as JSON in each app's own namespace |
| 7 | Data | covered: one V1, final state only, guarded by `AppRolesGrantsIT`, `SchemaContractTest` and `MigrateStepIT`; forward-only from the first release tag; locale pinned; migrations in their own step, as the owner; grants per table, every table classified in a test |
| 12 | Deployment | covered: the migrate Job per revision, `--wait-for-jobs`, rollback runs the older step |
| 13 | Security & Identity | covered: a role with grants per table and a Redis user per app, the passwords in their own Secret, `search_path` set on the plpgsql functions; a non-superuser owner is not built |
| 15 | Cost & Reliability | covered: the apps wait up to 4 minutes for the Job, the old pods keep serving |
| other | — | not affected |

## Diagrams
[flow](../../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../../diagrams/sequence-0004-persistence-and-provenance.svg)
· app roles: [flow](../../diagrams/flow-0004-app-roles.svg) · [sequence](../../diagrams/sequence-0004-app-roles.svg) ·
[data model](../../diagrams/data-model.svg) · [infrastructure](../../diagrams/infrastructure.svg)

Shared cache lookups: [flow 0023](../../diagrams/flow-0023-language-aware-search.svg) ·
[sequence 0023](../../diagrams/sequence-0023-language-aware-search.svg) ·
[sequence 0024](../../diagrams/sequence-0024-vector-search-pgvector.svg); purge: [flow 0010](../../diagrams/flow-0010-data-retention.svg).

## Backlog
- Decides: [F1.3](https://github.com/silvioincalza/helper2.ai/issues/22).
- Open issues that change it:
  - [S1.3.6](https://github.com/silvioincalza/helper2.ai/issues/156): the four indexes no query uses are removed from `V1__schema.sql`.
  - [S10.11.1](https://github.com/silvioincalza/helper2.ai/issues/356): the load test that sizes the pool, the catalog bulkheads and `max_connections`.
