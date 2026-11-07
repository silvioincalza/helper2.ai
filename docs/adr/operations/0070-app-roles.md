# 0070 — Each app its own database role and Redis user; the migrations in their own Job

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0005](../foundation/0005-redis-disposable-cache.md), [0007](../foundation/0007-configuration-and-secrets.md), [0023](../edge/0023-identity-and-admin.md), [0027](0027-runtime-topology.md), [0069](0069-three-deployables.md)

## Context
Both apps connected to Postgres as `helper2`, the user that owns every table and, in the container, a superuser. One
SQL injection in either app could drop the schema or run a program on the database pod (`COPY ... FROM PROGRAM`). Both
apps shared one Redis password, so a public pod could read or write the back office's sessions. Sessions were Java
serialised: whoever could write a session key could make an app run code while reading it. And both workloads loaded
the whole env Secret, so each pod held every password, the owner's included.

## Decision
1. **A database role per app, with grants per table.** V23 creates `h2_public` and `h2_backoffice` without a login,
   when they are missing (roles are cluster-wide), and always resets them to no superuser, no CREATEDB, CREATEROLE,
   REPLICATION or BYPASSRLS, and NOINHERIT. Neither may create in the schema or make temporary tables (`CREATE` and
   `TEMPORARY` revoked from PUBLIC), and neither gets `TRUNCATE`, `REFERENCES` or `TRIGGER`. Both only read
   `flyway_schema_history`. The owner stays the user Flyway runs as (`helper2` in the chart).
   - `h2_public` reads every table but the Scout's and the enrichment's bookkeeping (`scout_run`, `scout_schedule`,
     `source_cursor`, `discovery_host`, `enrichment_queue_control`, `maintenance_task`). It writes only what a visit, a
     sign-in, a report or its own jobs write: `resource`, `resource_label`, `source_record` (the live results it
     observes), `source` (its sources at startup and their outcome), `issue_report` and `review_item` (a user's report),
     `live_web_query_result`, `app_user`, `bookmark`, `admin_audit` (account deletion, identity retention; append-only),
     `guide_similar_question`, `ai_cache` and `shedlock`, with `DELETE` only where its code deletes. No sequence.
   - `h2_backoffice` reads and writes rows on every table, and uses the settings' identity sequence.
   - New tables (default privileges of the owner): both apps read them, only the back office writes them. A new table
     the public app writes needs its own `GRANT ... TO h2_public` in the migration that creates it.
   - `AppRolesGrantsIT` holds one row per table and checks every privilege of both roles against the database: a new
     table fails it until it is classified. The app tests connect as these roles, so they show the rows are enough for
     the code; the tests of library code only the back office runs connect as `h2_backoffice` (`@AsBackOffice`).
2. **The migrations run in their own Job.** `java -jar app.jar migrate` on the public image (`MigrateStep`) starts only
   a data source and Flyway: no web server, no Redis, no scheduler. It retries the connection (Postgres may restart in
   the same release), migrates as the owner, then gives both roles
   their login and password (`AppLogins`, hashed by the driver before they are sent) and exits. The chart runs it as the
   Job `helper2-migrate-<release revision>`, the only workload with the owner's password besides Postgres and
   pg-backup. Each revision is a new Job (the template of a Job cannot change); a rollback runs the older image's step,
   which finds nothing to do.
3. **The apps only check the schema, and wait.** Neither app migrates in a deployment. At startup each one validates as
   its own role (`SchemaCheck`). While migrations are only missing (a new versioned one, a new or changed seed) or its
   login is refused (the Job opens it), it waits, checking every 5 s for up to 4 minutes (`H2_SCHEMA_WAIT`), inside the
   startup probe's 5 minutes. Anything else fails at once, e.g. a changed migration. The old pods keep serving
   meanwhile (`maxUnavailable: 0`). `make deploy` passes `--wait-for-jobs`: the release succeeds only once the Job has,
   and a failed Job rolls it back.
4. **Local development migrates itself.** `H2_SCHEMA_MIGRATE=true` (the dev profile, and the compose `app` service)
   migrates at startup as before, connected as the owner. The tests do what a deployment does: the shared containers
   migrate once as the owner and open the logins, and each app's tests connect as its role and its Redis user
   (`helper2.test.client`), so a missing grant or Redis permission fails a test.
5. **A Redis ACL user per app.** `deploy/helm/helper2/files/redis-users.acl` lists each app's keys and the exact
   commands its code sends, scripts included (Redis checks the commands and keys inside `EVAL`). Both reach the shared
   rate limits, budgets, quotas, outbound limits and source caches; only the public app its setup fetches and its
   sessions, only the back office the Scout's page texts and its sessions. The default user stays the admin (probes,
   `redis-cli`). The chart and the backend tests read the same file.
6. **Sessions are JSON.** `GenericJacksonJsonRedisSerializer` with Spring Security's Jackson modules; besides their
   types, only the exact plain values a provider's user attributes hold (strings, numbers, booleans, `ArrayList`,
   `LinkedHashMap`, `HashMap`), never a subclass. A session that cannot be read is logged and served as anonymous. New
   namespaces, `h2:v1:session:public` and `h2:v1:session:backoffice`: a session kept in the old format is never read,
   so everyone signs in again once.
7. **The passwords in their own Secret.** `make env-secret` splits app.env: the six passwords (the owner's, each role's,
   Redis' default user's and each app user's) go to `helper2-credentials`, every other key to `helper2-app-env`. Both
   apps still load `helper2-app-env` whole; no workload loads `helper2-credentials` whole. Each names only its own
   keys. `check-app-env.sh` requires all six and refuses two that are the same. For the first release only, the
   owner's and Redis' default password also stay in `helper2-app-env`, so a rollback to the old chart still starts
   Postgres and Redis; both apps set those two names under `env:`, which wins over `envFrom`, so no app process sees
   them.

## Consequences
- An injection in the public app reaches only the rows it writes (the catalog it observes, users, reports, caches),
  never the Scout's tables, the review decisions, the settings, the schema or the database host. One in the back office
  reaches rows, never the schema or the host.
- A compromised public pod can no longer read, forge or delete an admin's session, and a planted session runs no code.
- A deploy runs one more pod, the Job, for a few seconds. The first deploy with this change restarts Postgres once (its
  password now comes from the credentials Secret) and signs every user and the admin out once.
- Before that deploy, app.env in OpenBao needs the four new passwords (`openssl rand -hex 32`), and the deployer may
  `create`/`delete` Jobs and `patch` the Secret `helper2-credentials` (the platform repo owns that RBAC; `make
  credentials` checks it).
- A new password for a role takes effect when the Job runs, a moment before the app pods restart: an old pod opening a
  new connection in between is refused until it is replaced.
- Accepted: `+eval` lets an app run a Lua script that blocks Redis until the default user kills it (`SCRIPT KILL`);
  and both apps share the rate-limit, budget, quota and source-cache keys, so a compromised public pod could reset a
  spend cap or poison the source cache. Both need code running in a pod.
- Known limits (roadmap): the owner is a superuser (the container's `POSTGRES_USER`), so the migrate Job could reach
  `COPY ... TO PROGRAM`; the rest of app.env (API keys, OAuth secrets) still reaches both apps; and a narrower split
  of the shared Redis key families.

## Considered
- **A Helm pre-install/pre-upgrade hook** — on a fresh install it runs before Postgres exists, so the chart could not be
  installed; on the first upgrade with it, its NetworkPolicy is not there yet; and hooks sit outside the release.
- **An init container in each app pod that migrates** — the app pods would hold the owner's password.
- **An init container that waits for the Job** — needs the Kubernetes API (a token and RBAC in every app pod) or a
  second JVM, for what the app's own schema check already does.
- **Row access on every table for both roles** — simpler, but an injection in the public app could rewrite the review
  queue, the settings or the Scout's tables.
- **One Redis database per app** (`SELECT n`) — no isolation: any user may select any database.

## AI architecture check (delta)
| # | Layer | Status |
|---|---|---|
| 5 | Memory | covered: sessions as JSON in each app's own namespace; the old ones are dropped once |
| 7 | Data | covered: migrations in their own step, as the owner; grants per table, every table classified in a test |
| 12 | Deployment | covered: the migrate Job per revision, `--wait-for-jobs`, rollback runs the older step |
| 13 | Security & Identity | covered: a role with grants per table and a Redis user per app, the passwords in their own Secret; a non-superuser owner deferred (roadmap) |
| 15 | Cost & Reliability | covered: the apps wait up to 4 minutes for the Job, the old pods keep serving |
| other | — | not affected |

## Diagrams
[flow](../../diagrams/flow-0070-app-roles.svg) · [sequence](../../diagrams/sequence-0070-app-roles.svg) · [infrastructure](../../diagrams/infrastructure.svg)

## Roadmap
- [roadmap § 2](../../roadmap.md#2-operations) (a non-superuser owner, an env Secret per app, the Redis key split) and
  [§ 9](../../roadmap.md#9-known-limits).
