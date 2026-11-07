# 0004 — Persistence and the schema: PostgreSQL as system of record, provenance, migrations, a database role per app

**Status.** Accepted. Merges old 0004 and 0070 (first release reset, PR 1b). **Builds on:** [0001](0001-stack-and-runtime.md), [0002](0002-modular-monolith-libraries-and-apps.md), [0005](0005-redis-disposable-cache.md), [0007](0007-configuration-secrets-and-settings.md), [0024](../edge/0024-identity-and-admin-host.md), [0032](../operations/0032-runtime-topology.md)

## Context
The same resource reaches Helper2 from many places: a GitHub repository, its npm package, a Hugging Face
page, a news article. Helper2 must store it once, keep what each source said, and show only dates a source
stated or Helper2 observed itself. Searches see thousands of results a day, and writing them must not slow
the search down.

Two apps share the database: the public app and the back office. One SQL injection in either must not be able to
drop the schema, run a program on the database pod (`COPY ... FROM PROGRAM`) or write the other app's tables. The same
holds for Redis: a public pod must not read or write the back office's sessions, and a session must never make an app
run code while it reads it.

v3.0.0 is the first release of the new history: every database (local, staging, prod) starts empty. Its schema can be
one file again, and from its tag on that file must never change.

## Decision
1. **PostgreSQL is the system of record.** PostgreSQL 18.6 with pgvector 0.8.6 (own image,
   `deploy/postgres/Dockerfile`). Connection: `H2_DATABASE_URL`, `H2_DATABASE_USER`, `H2_DATABASE_PASSWORD`;
   pool size `H2_DATABASE_POOL_SIZE` (default 10), connection timeout 3000 ms.
2. **Ids from canonical web addresses (URLs).** `CanonicalUrl` forces `https`, lower-cases the host, drops `www.`, the
   default port, the fragment, `utm_*` and other tracking parameters and the trailing slash, and sorts the
   query. GitHub URLs become `https://github.com/{owner}/{repo}` in lower case; a sub-directory
   (`/tree/{ref}/{path}`) is kept, so each package of a monorepo has its own URL. `ResourceId` is a universally
   unique identifier (UUID) version 5 (RFC 9562) of that URL in a fixed namespace; Structured Query Language (SQL) computes the same id with `uuid_generate_v5`.
   A resource is named by the canonical URL of one of its own hits (a GitHub URL first), never by a repository link
   alone: two npm packages of one repository are two resources with two ids, and an npm package becomes part of its
   repository's resource only when the merge joins them ([0009](../search/0009-federated-search.md)).
3. **Source records.** `source_record` keeps one row per (`source_id`, `external_id`): exactly what the
   source said, `retrieved_at` and a content `fingerprint`. `resource` is the merged view. A write never
   overwrites the fields of a resource from a stronger origin (`DEMO` > `CURATED` > `INDEXED` > `OBSERVED`). Labels and
   event dates are rebuilt from the current records on every write.
   A record moves to another resource when its source points to another URL. When all the records of the old
   resource have moved to one resource, its user reports (`issue_report`) and review items (`review_item`) move with
   them (`ResourceStore.moveReportsOfEmptiedResources`). Then `deleteOrphans` deletes an `OBSERVED` resource left
   without records, unless a report or a review item still points at it. That happens when its records went to
   several resources: the new owner is not clear, so the resource and its reports stay for the reviewer.
4. **Dates are never invented.** `DateFact` is `KNOWN`, `UNKNOWN` or `CONFLICTING`. A source date before
   1995-01-01 or more than one day after retrieval becomes unknown. `CONFLICTING` means two sources state different
   days for the same date; the merge rules are in [0013](../search/0013-filters-facets-and-categories.md). Date columns are nullable;
   `published_precision = 'UNKNOWN'` exactly when `published_at` is null (a table check).
   `first_discovered_at` is Helper2's own observation, labeled as such.
5. **One schema migration, forward-only from v3.0.0; repeatable seeds.** `V1__schema.sql` is the whole schema: the
   tables, checks, indexes, triggers, functions and app roles the code needs, and nothing it does not use.
   - **One section per context**, in an order that creates a table before the tables that point at it: foundation
     (the locale check, extensions, shared functions), platform, identity, catalog, search, Scout, guide, examples,
     runtime settings, and the app roles last. The setup guide has no table: its answers are in `ai_cache`.
   - **Final state only.** Every table, column, check list and index is written as it is, under its final name
     (`live_web_query_result`). The rows every database needs are inserted in V1: the discovery hosts
     ([0022](../scout/0022-discovery.md)) and the built-in home examples ([0015](../search/0015-home-examples.md)).
   - Every foreign key states its delete rule (`CASCADE`, `SET NULL`, `RESTRICT` or `NO ACTION`,
     [0035](../operations/0035-data-retention.md)).
   - **The plpgsql functions set their search_path** (`public, pg_catalog`): `pg_dump` and `pg_restore` run with an
     empty one, and the popularity trigger would stop a restore at the first `source_record`. The SQL functions get
     no `SET`: it would stop their inlining into the generated `search_vector` and every search. They name their
     schema, and their SQL-standard bodies are bound when they are created.
   - **The locale is pinned.** The text search parser and `pg_trgm` split words by the database's character type,
     and `lower()` and the ordering of text follow it. V1 stops unless the database is UTF8 with `LC_CTYPE` and
     `LC_COLLATE` `en_US.utf8`, the locale search was measured on. The chart, compose and the test containers create
     the database so (`POSTGRES_INITDB_ARGS=--encoding=UTF8 --locale=en_US.utf8`).
   - **Forward-only from the v3.0.0 tag.** V1 replaces the chain V1 to V23 of v2. Until the tag, V1 is edited in place
     while every environment is recreated empty ([deploy/k8s/RESET-DATABASE.md](../../../deploy/k8s/RESET-DATABASE.md)).
     From the tag on, an applied migration is never edited: an edit changes its checksum, and Flyway refuses to start.
     A change is a new versioned migration (`V2__…`), forward-only.
   - After the versioned migrations, Flyway runs the seeds `R__curated_seed.sql`, `R__industry_events.sql`,
     `R__community_events.sql` and `R__courses.sql`. The seeds upsert, so Flyway re-runs one whenever it changes.
     The events seed also removes the curated events that are not in its research file ([0021](../scout/0021-collectors-and-events.md)).
6. **Catalog writes off the request path.** `SearchResources` reads first-discovery dates in one batched
   query (`ResultObserver.firstDiscovered`), then hands only the results fetched live in this search to
   `ResultObserver.record`. `CatalogResultObserver.record` queues the write in `ObservationQueue`: one thread
   (`catalog-observations`), a bounded queue (`helper2.catalog.observation-queue`, 256). A full queue drops
   the new write. Meters: `helper2.catalog.observations{outcome=written|dropped|failed}` and
   `helper2.catalog.observations.queued`. The Scout writes synchronously (`IndexResources.index`).
7. **The catalog owns its tables.** Other modules write through catalog ports: search through `ResultObserver`,
   the Scout through `IndexResources.index`, `ReviewQueue` and `EnrichmentResults` ([0023](../scout/0023-evidence-gated-enrichment.md)).
   The SQL is in `catalog/internal` (`ResourceStore`, `JdbcEnrichmentResults`, `IssueReports`, `ReviewDecisions`);
   catalog use cases call those classes and hold no SQL of their own.
8. **Model answers shared by the replicas in `ai_cache`.** One table, owned by the open `platform` module
   (`platform.cache`, [0002](0002-modular-monolith-libraries-and-apps.md)), holds every cache of model answers: query translations
   (cache `translation`, [0010](../search/0010-catalog-full-text-index.md)), query vectors (`query-vector`,
   [0011](../search/0011-vector-search-pgvector.md)), setup guide answers (`tool-guide`, [0019](../guide/0019-setup-guide.md))
   and guide answers (`guide-answer`, [0017](../guide/0017-guide-cost-guard-and-answer-cache.md)).
   Columns: `cache_name`, `key_hash` (SHA-256 of the entry's parts, never the query text), `value` (text), `expires_at`;
   primary key (`cache_name`, `key_hash`). Port `SharedCache`, adapter `JdbcSharedCache`, created by
   `SharedCaches.named(name)`. A read is one primary-key lookup with `expires_at > now()`; a write is an upsert
   (`ON CONFLICT … DO UPDATE`) with `expires_at = now() + ttl`, so the database clock decides expiry for every replica.
   Each statement runs under the `db-cache` policy ([0006](0006-outbound-calls-and-resilience.md)) with a 1 s statement timeout:
   an error, a timeout, the open circuit or a full bulkhead is a miss and a write that does nothing, and the caller asks
   the model. The daily shared cache purge deletes expired rows ([0035](../operations/0035-data-retention.md)).
   Meter `helper2.shared.cache{cache, result}` counts hits and misses ([0031](../operations/0031-observability.md)).
9. **A database role per app, with grants per table.** The last section of V1, after every `CREATE TABLE` (a grant
   `ON ALL TABLES` reaches only the tables that exist), creates `h2_public` and `h2_backoffice` without a login,
   when they are missing (roles are cluster-wide), and always resets them to no superuser, no CREATEDB, CREATEROLE,
   REPLICATION or BYPASSRLS, and NOINHERIT. Neither may create in the schema or make temporary tables (`CREATE` and
   `TEMPORARY` revoked from PUBLIC), and neither gets `TRUNCATE`, `REFERENCES` or `TRIGGER`. Both only read
   `flyway_schema_history` (`${flyway:table}` in the migration). The owner stays the user Flyway runs as (`helper2`
   in the chart).
   - `h2_public` reads every table but the Scout's and the enrichment's bookkeeping (`scout_run`, `scout_schedule`,
     `source_cursor`, `discovery_host`, `enrichment_queue_control`, `maintenance_task`). It writes only what a visit, a
     sign-in, a report or its own jobs write: `resource`, `resource_label`, `source_record` (the live results it
     observes), `source` (its sources at startup and their outcome), `issue_report` and `review_item` (a user's report),
     `live_web_query_result`, `app_user`, `bookmark`, `admin_audit` (account deletion, identity retention; append-only),
     `guide_similar_question`, `ai_cache` and `shedlock`, with `DELETE` only where its code deletes. No sequence.
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
    the driver before they are sent) and exits. The chart runs it as the Job `helper2-migrate-<release revision>`, the
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

## Consequences
- A search never waits for a catalog write.
- One writer thread and rows written in canonical-URL order keep concurrent upserts from deadlocking.
- If the first-discovery read fails, the search still answers, with an `INDEX_UNAVAILABLE` notice and
  unknown discovery dates.
- On shutdown the queue gets 5 seconds to finish.
- The index can be rebuilt by the Scout and the embedding indexer. Bookmarks and reports are user data, kept by
  the backup ([0032](../operations/0032-runtime-topology.md)).
- How long each table keeps its rows, and the daily purges that enforce it, are in
  [0035](../operations/0035-data-retention.md); the foreign keys and indexes those purges use are part of `V1__schema.sql`.
- One file holds the whole schema, so a reader sees every table as it is, not a chain of changes.
- The squash is proven, not assumed. `SquashEquivalenceIT` (`./gradlew :libs:schema:squashCheck`) reads the old chain
  V1 to V23 from git, migrates it and the new V1 with today's seeds in two fresh containers, and compares
  `pg_dump --schema-only` object by object, the privileges on the database, the schema, every table and sequence, the
  default privileges, the roles, the functions' settings and the rows of every table. The only differences are the
  ones it lists: the constraint names of `live_web_query_result` (the old chain kept `tavily_query_result_*` after
  the rename) and the `search_path` of `h2_refresh_popularity` and `h2_append_only`. It needs the git history, so it
  is not part of `test`. The old chain's backfills (V3, V9, V18 and V20 moved or rewrote rows) are not in V1: a v3
  database has no old rows.
- A database built by v2 (V1 to V23 applied) cannot take v3's V1: Flyway sees a different checksum for V1 and 22
  applied migrations it cannot find, and refuses to start. Staging, prod and every local database are recreated
  empty for v3.0.0; locally: `podman compose down -v`.
- A database made with another encoding or locale stops at V1's first statement instead of indexing differently.
  The test image (Debian, glibc) and the deployed image (Alpine, musl) both name the locale `en_US.utf8`, but musl
  sorts text by code point: the check pins the name, not the C library.
- `SchemaContractTest` checks the migrated schema with plain JDBC: every foreign key's delete rule, an index on every
  foreign key a delete follows, every check list equal to the domain vocabulary it stores (the `admin_audit.action`
  list equal to `AuditTrail.Action`), and no table or column the code does not use.
- A merge of two resources never loses a user report. A resource whose records were split over several resources
  stays, without records, until a reviewer handles its reports. `ResourceStoreIT` covers both cases.
- A query is translated and embedded once for the cluster, and a setup guide is written once, not once per replica.
  These answers are on disk and in the backups, and a slow database costs a lookup at most 1 s.
  `JdbcSharedCacheIT` covers sharing, expiry, the purge, a locked table and a database that is down.
- `ObservationQueueTest` covers the queue (background write, a full queue drops the new write, shutdown). `SearchApiIT` checks the result: unknown
  discovery date on the first search, known on the next.
- A deploy whose schema does not match the database's migration history stops at startup: the new pods never
  become ready, the deploy rolls back and the old pods keep serving ([0033](../operations/0033-ci-cd-and-supply-chain.md)).
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
- Known limits (roadmap): the owner is a superuser (the container's `POSTGRES_USER`), so the migrate Job could reach
  `COPY ... TO PROGRAM`; the rest of app.env (API keys, OAuth secrets) still reaches both apps; and a narrower split
  of the shared Redis key families.

## Alternatives considered
- Write in the request — every search waits for a multi-row upsert, and parallel searches lock the same rows.
- An event bus or outbox for the writes — durable, but a second moving part for data that the next
  search records again anyway.
- Random ids — the same URL from two sources would become two resources, and links would not be stable.
- A document store — the data is relational (records, evidence, labels, bookmarks), and PostgreSQL
  also gives full-text search and vectors.
- Keep the chain V1 to V23 and add to it — every new database replays fixes to rows it never has (the moved
  discovery records, the renamed sources), and the audit check list is rewritten ten times before it is read.
- A Flyway baseline on the old history — the files would no longer describe how the database was built, and
  every environment would still carry the old constraint names.
- The equivalence gate in `test` — it needs the old chain from git, and a CI checkout has one commit; a gate run once
  for the squash, kept in the code, is enough.
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

## AI architecture check (delta)
| # | Layer | Status |
|---|---|---|
| 5 | Memory | covered: sessions as JSON in each app's own namespace |
| 7 | Data | covered: one V1, final state only, proven equal to the old chain (`SquashEquivalenceIT`); forward-only from v3.0.0; locale pinned; migrations in their own step, as the owner; grants per table, every table classified in a test |
| 12 | Deployment | covered: the migrate Job per revision, `--wait-for-jobs`, rollback runs the older step; every database recreated for v3.0.0 |
| 13 | Security & Identity | covered: a role with grants per table and a Redis user per app, the passwords in their own Secret, `search_path` set on the plpgsql functions; a non-superuser owner deferred (roadmap) |
| 15 | Cost & Reliability | covered: the apps wait up to 4 minutes for the Job, the old pods keep serving |
| other | — | not affected |

## Code
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/CanonicalUrl.kt`, `ResourceId.kt`, `DateFact.kt` — identity and dates.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/CatalogResultObserver.java` — `ResultObserver`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/IndexResources.java` — `index`, the Scout's write.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ObservationQueue.java` — background writer.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — upserts, origin rank, labels, moved
  reports, orphan deletion.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java`, `IssueReports.java`,
  `ReviewDecisions.java` — enrichment results, user reports and review decisions.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — read dates, then `record`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql`, `R__*.sql` — schema, app roles and seeds.
- `backend/libs/schema/src/main/java/ai/helper2/platform/schema/MigrateStep.java`, `AppLogins.java`, `SchemaCheck.java` —
  the migrate step, the logins, the apps' schema check.
- `backend/libs/schema/src/test/java/ai/helper2/platform/schema/SquashEquivalenceIT.java`, `backend/libs/schema/build.gradle.kts`
  (`squashCheck`) — the squash gate.
- `backend/apps/backoffice/src/test/java/ai/helper2/SchemaContractTest.java` — the schema contract.
- `backend/apps/public/src/test/java/ai/helper2/AppRolesGrantsIT.java`, `MigrateStepIT.java` — the grants per table, the
  migrate step.
- `backend/libs/platform/src/testFixtures/java/ai/helper2/TestcontainersConfiguration.java` — the shared containers,
  the locale, the logins.
- `backend/libs/platform/src/main/java/ai/helper2/platform/cache/SharedCache.java`, `SharedCaches.java`, `JdbcSharedCache.java`,
  `SharedCacheRetention.java`, `SharedCacheConfiguration.java` — the shared caches and their purge.
- `deploy/helm/helper2/templates/postgres.yaml` (`POSTGRES_INITDB_ARGS`), `migrate.yaml`, `files/redis-users.acl`;
  `deploy/k8s/check-app-env.sh`; `compose.yaml`.
- `deploy/k8s/RESET-DATABASE.md` — an empty database for an in-place edit of `V1__schema.sql` before the v3.0.0 tag.
- `deploy/postgres/Dockerfile` — PostgreSQL with pgvector, pinned by commit and checksum.

## Diagrams
[flow](../../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../../diagrams/sequence-0004-persistence-and-provenance.svg)
· app roles: [flow](../../diagrams/flow-0004-app-roles.svg) · [sequence](../../diagrams/sequence-0004-app-roles.svg) ·
[data model](../../diagrams/data-model.svg) · [infrastructure](../../diagrams/infrastructure.svg)

Shared cache lookups: [flow 0010](../../diagrams/flow-0010-language-aware-search.svg) ·
[sequence 0010](../../diagrams/sequence-0010-language-aware-search.svg) ·
[sequence 0011](../../diagrams/sequence-0011-vector-search-pgvector.svg); purge: [flow 0035](../../diagrams/flow-0035-data-retention.svg).

## Roadmap
- Discovery date unknown on a resource's first search: [roadmap](../../roadmap.md#5-search-and-catalog).
- Delete indexed resources left without source records: [roadmap](../../roadmap.md#5-search-and-catalog).
- The runbook that recreates every database for v3.0.0, in place of `RESET-DATABASE.md`: [roadmap § 2](../../roadmap.md#2-operations).
- [roadmap § 2](../../roadmap.md#2-operations) (a non-superuser owner, an env Secret per app, the Redis key split) and
  [§ 9](../../roadmap.md#9-known-limits).
