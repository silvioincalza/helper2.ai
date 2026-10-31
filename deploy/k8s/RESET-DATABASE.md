# Reset the database to the single V1 schema

The schema is one migration, `V1__schema.sql` ([ADR 0004](../../docs/adr/foundation/0004-persistence-and-provenance.md)).
A database that ran the older `V1`, `V2` and `V3` cannot take it: Flyway sees a different `V1` checksum and two
applied migrations it no longer has, and refuses to start. So each environment gets an empty database once,
right before it first runs this schema. After that, migrations are forward-only.

What is lost: everything in Postgres. The catalog comes back from the seeds at startup and from the Scout over the
next hours. Accounts, bookmarks, issue reports, review decisions, admin audit entries, Scout run history and
stored Tavily results do not come back. Signed-in people sign in again.

**Prod data stays untouched until the prod maintenance window (part 2).** Prod is reset only as the step right
before this release is deployed to prod, never earlier, and never while the old release still runs on it.

All commands run from the workstation, with the mesh up and `KUBECONFIG` set as in the [Makefile](Makefile).

## Safety net if prod is deployed without the reset

Do not count on it, but know it is there. If the release reaches prod without the reset, the new pods stop at
startup on Flyway's validation error and never become ready. The rollout replaces one pod at a time and only after
the new one is ready (`maxUnavailable: 0`), so the old pods keep serving. `make deploy` waits, then rolls back
(`--rollback-on-failure`). Prod stays on the old release with its data, and the release run ends red. Then do part 2
and deploy again with `make ENV=prod TAG=<commit> deploy`.

The reviewer of the `prod` environment approves the prod deploy of a release
([ADR 0029](../../docs/adr/operations/0029-release-versioning.md)). For this release, approve only inside the
maintenance window of part 2, after step 2.4.

## 1. Staging

Staging runs every commit of `main` ([CI.md](CI.md)). Reset it right before the pull request is merged, so the CD
run of the merge commit deploys onto the empty database.

1. Set the namespace.

   ```bash
   NS=helper2-staging
   ```

2. Back up anyway (staging has no backup CronJob). Check that the file is a valid gzip.

   ```bash
   kubectl -n $NS exec postgres-0 -- pg_dump -U helper2 -d helper2 --no-owner \
     | gzip > helper2-staging-before-reset-$(date +%F).sql.gz
   gunzip -t helper2-staging-before-reset-$(date +%F).sql.gz && ls -lh helper2-staging-before-reset-*.sql.gz
   ```

3. Scale the app to 0 and wait until no app pod is left. Nothing may write while the database is replaced.

   ```bash
   kubectl -n $NS scale deployment/helper2 --replicas=0
   kubectl -n $NS wait --for=delete pod -l app=helper2 --timeout=180s
   ```

4. Drop and create the database (`postgres-0`, connected to the `postgres` maintenance database).
   `WITH (FORCE)` closes any session left, for example a `psql` someone forgot.

   ```bash
   kubectl -n $NS exec postgres-0 -- psql -U helper2 -d postgres -v ON_ERROR_STOP=1 \
     -c 'DROP DATABASE helper2 WITH (FORCE)' -c 'CREATE DATABASE helper2 OWNER helper2'
   ```

5. Empty Redis. Its sessions point at accounts that no longer exist, and its cached answers at deleted resources.
   The container's `REDISCLI_AUTH` gives `redis-cli` the password.

   ```bash
   kubectl -n $NS exec redis-0 -- redis-cli FLUSHALL
   ```

6. Deploy: merge the pull request. The CD run deploys the merge commit to staging and sets the replicas back.
   To deploy by hand instead: `make ENV=staging TAG=<12-char commit> deploy`.

7. Check (see [Checks](#checks)).

If the deploy rolled back, the old release started on the empty database and created the old schema. Repeat
steps 3 to 6.

## 2. Prod, in one maintenance window

Do this when the first release that contains the single V1 is tagged, and only after staging passed part 1.
Push the tag, and let the release run reach the approval of the `prod` environment. Do not approve yet.
Then, in one window:

1. Set the namespace.

   ```bash
   NS=helper2-prod
   ```

2. Back up. First a fresh dump on the backup volume (and off-site, if it is on), then a copy on the workstation.

   ```bash
   kubectl -n $NS create job --from=cronjob/pg-backup pg-backup-before-reset
   kubectl -n $NS wait --for=condition=complete job/pg-backup-before-reset --timeout=15m
   kubectl -n $NS exec postgres-0 -- pg_dump -U helper2 -d helper2 --no-owner \
     | gzip > helper2-prod-before-reset-$(date +%F).sql.gz
   gunzip -t helper2-prod-before-reset-$(date +%F).sql.gz && ls -lh helper2-prod-before-reset-*.sql.gz
   ```

   The dumps hold personal data (accounts, reports). Keep the workstation copy only until the checks pass, then
   delete it; the volume copy goes after 7 days ([ADR 0034](../../docs/adr/operations/0034-data-retention.md)).

3. Scale the app to 0. The site is down from here until step 6.

   ```bash
   kubectl -n $NS scale deployment/helper2 --replicas=0
   kubectl -n $NS wait --for=delete pod -l app=helper2 --timeout=180s
   ```

4. Drop and create the database, then empty Redis.

   ```bash
   kubectl -n $NS exec postgres-0 -- psql -U helper2 -d postgres -v ON_ERROR_STOP=1 \
     -c 'DROP DATABASE helper2 WITH (FORCE)' -c 'CREATE DATABASE helper2 OWNER helper2'
   kubectl -n $NS exec redis-0 -- redis-cli FLUSHALL
   ```

5. Deploy the new release: approve the `prod` deployment in the release run. Flyway creates V1 and loads the seeds.
   If the release run is no longer waiting, deploy the same commit by hand:
   `make ENV=prod TAG=<12-char commit of the tag> deploy`.

6. Scale up. The deploy sets the replicas from `values-prod.yaml` (3); check that all of them are ready.

   ```bash
   kubectl -n $NS rollout status deployment/helper2 --timeout=12m
   kubectl -n $NS get deployment helper2    # READY 3/3
   ```

7. Check (see [Checks](#checks)).

If the deploy rolled back, the old release started on the empty database and created the old schema. Repeat
steps 3 to 6 before anything else writes to it.

## Checks

With `NS` set to the environment:

```bash
PSQL="kubectl -n $NS exec postgres-0 -- psql -U helper2 -d helper2 -At -c"

# One versioned migration and the four seeds, all successful.
$PSQL "SELECT coalesce(version, 'R'), description, success FROM flyway_schema_history ORDER BY installed_rank"
#   1|schema|t, then R|community events|t, R|courses|t, R|curated seed|t, R|industry events|t (any order)

# Every foreign key states its delete rule: CASCADE, SET NULL, RESTRICT or NO ACTION, as in SchemaContractTest.
$PSQL "SELECT delete_rule, count(*) FROM information_schema.referential_constraints
       WHERE constraint_schema = 'public' GROUP BY 1 ORDER BY 1"
#   CASCADE|20, NO ACTION|4, RESTRICT|3, SET NULL|3 (30 foreign keys)

# The seeds are in; the Scout adds INDEXED rows over the next hours.
$PSQL "SELECT data_origin, count(*) FROM resource GROUP BY 1 ORDER BY 1"

# The app is ready.
kubectl -n $NS port-forward deploy/helper2 8081 &
curl -s localhost:8081/actuator/health/readiness    # {"status":"UP"}
kill %1
```

Then open the site: a search answers and the Sources page lists the sources. The Scout starts about 20 seconds
after startup; rerun the `resource` count after an hour to see the catalog grow.

## After the reset

Migrations are forward-only from here: a schema change is a new `V2__…`, never an edit of `V1__schema.sql`
([ADR 0004](../../docs/adr/foundation/0004-persistence-and-provenance.md)).
