# Release runbook

How a release reaches prod, step by step: what must be true before, who does each step, what to check after it, and
how to go back. [CI.md](CI.md) says what each workflow does and
[ADR 0045](../../docs/architecture/decisions/ADR-0045-releases-and-versions.md) says why. This page is the order to follow on the
day.

**(Silvio)** marks a step only the owner can do: a workflow run, a merge, the approval on `prod`, a setting of the
repository or the platform. **(workflow)** marks a step a workflow does by itself. Commands run from the repository
root, with `gh` signed in as the owner; the `make` and `kubectl` ones need the mesh up and the cluster's kubeconfig.

```bash
R=silvioincalza/helper2.ai
```

## What a release does to prod's data

A release keeps prod's database. Nothing is dumped, dropped or loaded.

- **Postgres.** The migrate Job of the release runs Flyway as the owner. It runs the versioned migrations prod does
  not have yet, and a seed (`R__…`) only when its file changed. A release with no new migration changes no table.
- **A migration is written expand then contract**, so the release before keeps working on the new schema: the canary
  migrates prod's database while the release before still serves it (ADR 0045 §11). A rollback brings the code back,
  never the schema.
- **A migration file prod already ran never changes.** Flyway compares each file with the checksum it stored. When
  they differ, the migrate Job fails, the canary fails with it, and prod is as it was: no table changed. No step
  brings such a database to the new file: it would have to be created again, empty
  ([ADR 0004](../../docs/architecture/decisions/ADR-0004-persistence-and-the-schema.md) §5).
- **Redis** is kept. Sessions, rate-limit windows and the day's counters stay. The guide-answer cache is kept per
  build, so the new release starts with an empty one, once.
- **The nightly dump** (`pg-backup`, 02:30, 7 days, with the off-site copy) is the only way back for data. A restore
  replaces the whole database and loses what was written after the dump ([README.md, Backups](README.md#backups)).

## 1. Once, before the first release of a repository

Each line is checked once. A release run does not check them.

| What | Check | Set up by |
|---|---|---|
| The environment `prod`, with its required reviewer and its two deployment rules (`main`, tags `v*`) | `gh api repos/$R/environments/prod --jq '.protection_rules[].type'` prints `required_reviewers` | **(Silvio)** [RUNBOOK-new-repository.md](RUNBOOK-new-repository.md) §7 |
| The three rulesets: `main`, `release/**`, tags `v*` | `gh api repos/$R/rulesets --jq '.[].name'` | **(Silvio)** RUNBOOK-new-repository.md §5 |
| The variables `BAO_ADDR`, `BAO_AUDIENCE`, `H2_STAGING_URL` | `gh variable list -R $R` | **(Silvio)** RUNBOOK-new-repository.md §7 |
| The OpenBao roles `deploy`, `deploy-staging` and `deploy-prod` accept this repository; `deploy-prod` reads `ci/prod`, `ci/harbor-pull` and `ci/harbor` | a green push run of `cd` proves `deploy` and `deploy-staging`; `deploy-prod` is proved by the first prod job | **(Silvio)** RUNBOOK-new-repository.md §8, [CI.md, Security model](CI.md#security-model) |
| `ci/prod` holds prod's `app.env` and the `deployer` kubeconfig; the off-site backup Secret is on prod | `make -C deploy/k8s ENV=prod credentials`, `make -C deploy/k8s ENV=prod backup-credentials` | **(Silvio)** RUNBOOK-new-repository.md §9 |
| Staging's and prod's `app.env` in OpenBao hold `H2_DATABASE_MONITOR_PASSWORD` and `H2_REDIS_MONITOR_PASSWORD` (two new values of `openssl rand -hex 32`), staging's before the merge that adds them; the cluster's Alloy keeps the `pg_` and `redis_` metric names of `helper2-*` pods | `scripts/env-align.sh --dry-run deploy/k8s/app.env.example deploy/k8s/<env>/app.env` lists neither; `make -C deploy/k8s ENV=<env> credentials`; the exporters' series in Prometheus | **(Silvio)** [ADR 0009](../../docs/architecture/decisions/ADR-0009-observability.md) §7; the allow-list in the platform repo |
| Prod can be recovered and is watched: a tested restore, the off-site copy, the exporters and the eviction alert, the DNS records, `H2_SETUP_GITHUB_TOKEN` | [S10.8.1](https://github.com/silvioincalza/helper2.ai/issues/353), [S1.8.3](https://github.com/silvioincalza/helper2.ai/issues/174) | **(Silvio)** per step |
| The ad networks' rules are read before a prod ad setting is filled | [S8.4.1](https://github.com/silvioincalza/helper2.ai/issues/332) | **(Silvio)** |
| The Privacy and Terms pages: the points for a lawyer are answered, or the owner releases with them open | [F9.3](https://github.com/silvioincalza/helper2.ai/issues/102) | **(Silvio)** |

The environment `prod` comes first. GitHub creates a missing environment by itself when a job names it, with no
reviewer: the prod job would then start with no approval.

## 2. The release check

Before every release. Nothing here changes prod.

1. **Staging runs the commit to release, and its CD run is green**, the image scan included. A release is cut from
   the newest commit of `main` that staging ran, unless `release-prepare` gets another one.

   ```bash
   gh run list -R $R --workflow cd.yml --branch main --event push --limit 5
   make -C deploy/k8s ENV=staging status
   ```

2. **The last nightly guide evaluation passed** on staging
   ([eval/README.md](../../eval/README.md)): `gh run list -R $R --workflow guide-eval.yml --limit 3`.
3. **Prod is healthy now**, and you know the revision a rollback goes back to: the one marked `deployed`.

   ```bash
   make -C deploy/k8s ENV=prod status
   helm -n helper2-prod history helper2 --max 5
   ```

4. **Last night's dump is there.** For a release with a migration, take one more right before the release. Wait
   until its Job is complete: that covers the dump, which is verified before it replaces the old one, and the
   off-site upload after it. Then read both logs.

   ```bash
   kubectl -n helper2-prod get cronjob pg-backup
   kubectl -n helper2-prod create job --from=cronjob/pg-backup pg-backup-now
   kubectl -n helper2-prod wait --for=condition=complete job/pg-backup-now --timeout=30m
   kubectl -n helper2-prod logs job/pg-backup-now -c dump
   kubectl -n helper2-prod logs job/pg-backup-now -c upload
   ```

   When the wait fails or times out, stop: the release has no fresh dump to go back to. Read the logs, fix the
   cause, and run the Job again under another name. The Job stays until the release is done; section 6 deletes it.

5. **Read the migrations of the release.** List what changed under
   `backend/libs/schema/src/main/resources/db/migration` since the release prod runs
   (`git diff v<running>..<commit> -- <that folder>`).
   - No versioned file changed or added: the release changes no table.
   - A new `V…` file the release before can run on: a normal release, with the canary.
   - A new `V…` file the release before cannot run on: a maintenance window, `canary` unticked in step 3 below, and
     the change on the first line of the notes (`headline`).
   - A change of a file prod already ran: stop. The release cannot migrate prod.
6. **Prod's `app.env` has every key the release reads.** The dry run lists the keys the example gained; fill them,
   then write `ci/prod` again.

   ```bash
   scripts/env-align.sh --dry-run deploy/k8s/app.env.example deploy/k8s/prod/app.env
   make -C deploy/k8s ENV=prod credentials
   ```

7. **[CHANGELOG.md](../../CHANGELOG.md) has the entry of the version**, on the commit to release.
8. **For the first release only: the load-test baseline is recorded**
   ([S10.11.1](https://github.com/silvioincalza/helper2.ai/issues/356)), run against staging, never prod, with the two
   simulations of `backend/loadtest` ([the stack](../../docs/architecture/stack.md)).

## 3. The release

No user reaches a release before it passed its smoke test, and prod is never down: the canary runs beside the
release, and the rollout replaces the pods one at a time. Only a release with `canary` unticked needs a window.

| # | Who | Step | Check after | When it fails |
|---|---|---|---|---|
| 1 | **(Silvio)** | Run `release-prepare` from `main`. A release: no input, or `commit`. A patch: `patch-of` with the line `YYYY.N`, after its fixes are merged into `release/YYYY.N` | The run summary names the version and the branch; the version pull request `release-bump/YYYY.N.P` is open | Nothing reached prod. Run it again: it finishes a branch a run left half-way |
| 2 | **(Silvio)** | Merge the version pull request (squash). The ruleset of `release/**` wants `ci-ok` on it: when the pull request shows no check, start CI on its branch with `gh workflow run ci.yml -R $R --ref release-bump/YYYY.N.P` | **(workflow)** the push runs `cd` on the release branch: CI, the images built, signed and scanned. `gh run list -R $R --workflow cd.yml --branch release/YYYY.N --limit 1` is green | Fix the branch by pull request into `release/YYYY.N`; each push builds again |
| 3 | **(Silvio)** | Run `release` from `main` with `version`. `headline` for a breaking change. `canary` unticked only for the maintenance window of check 5 | **(workflow)** jobs `check` and `built` are green: the branch, the version rule, the seven files, staging ran the commit a `.0` is cut from, the CD run of the branch passed | Nothing reached prod and no tag exists. Fix what the error names, run `release` again |
| 4 | **(Silvio)** | Read the summary of `check` (version, commit, the commit it is cut from), then approve the job `prod`: [the release runs](https://github.com/silvioincalza/helper2.ai/actions/workflows/release.yml) → the run → Review deployments | The job `prod / deploy` starts on the self-hosted runner | Reject: nothing reached prod |
| 5 | **(workflow)** | The canary: its migrate Job on prod's database, one public app pod and one web pod, reached only with the run's header. Then the smoke test through `https://helper2.ai`, with the header and without | The run summary says the canary passed and is removed | The canary is removed and the job fails. Prod runs the release before. No tag anywhere, the version is still free. A migration the canary ran stays in the schema |
| 6 | **(workflow)** | The version tag `YYYY.N.P` in Harbor, on the three images of the commit | The step log says `tagged …` for each image | From here a failure uses the version up: see section 5 |
| 7 | **(workflow)** | `make diff`, then `make deploy`: prod by version, by digest, and the check after it (release deployed, workloads rolled out, migrate Job complete, `/` and `/api/v1/sources` answer 200) | The step ends with `release helper2 in helper2-prod is healthy`; then `make status` passes | The release goes back by itself to the revision deployed before, is checked once more, and the job fails |
| 8 | **(workflow)** | The job `publish`: the tag `vYYYY.N.P`, the GitHub release with its notes, the four SBOMs, the pull request `release-merge/vYYYY.N.P` into `main` with CI started on it | `gh release view vYYYY.N.P -R $R`; the pull request is open | Prod already runs the version. Run the failed job again: `gh run rerun <run id> -R $R --failed`. It finishes what is missing |
| 9 | **(Silvio)** | Merge the pull request back into `main` once `ci-ok` is green | Staging's footer shows the version after its CD run | A conflict: resolve it on `release-merge/vYYYY.N.P`; that push runs CI |

The same from the terminal:

```bash
gh workflow run release-prepare.yml -R $R --ref main
gh workflow run release-prepare.yml -R $R --ref main -f patch-of=YYYY.N
gh workflow run release.yml -R $R --ref main -f version=YYYY.N.P
gh workflow run release.yml -R $R --ref main -f version=YYYY.N.P -f headline='<the breaking change>' -f canary=false
```

## 4. Checks after the release

1. The release is healthy, and prod runs the version:

   ```bash
   make -C deploy/k8s ENV=prod status
   kubectl -n helper2-prod get deployment helper2 helper2-backoffice helper2-web \
     -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
   helm -n helper2-prod list
   ```

   `helm list` shows the release `helper2` only: no `helper2-canary` is left.
2. Open `https://helper2.ai`: the footer shows the version, a search answers, the Sources page lists the sources,
   sign-in works. On the admin host: the overview loads, and Settings shows the public app's build as the version.
3. The schema is the one the release holds: every row successful, the newest version the newest `V…` file.

   ```bash
   kubectl -n helper2-prod exec postgres-0 -- psql -U helper2 -d helper2 -At \
     -c 'SELECT version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank'
   ```

4. The tag and the release exist: `git ls-remote --tags https://github.com/$R.git vYYYY.N.P`,
   `gh release view vYYYY.N.P -R $R`.
5. Grafana, dashboards `helper2` and `helper2-backoffice`, for the first hour: requests by status, source outcomes,
   the guide's outcomes. No alert of [`helper2-rules.yaml`](../monitoring/helper2-rules.yaml) fires.
6. The next morning: the nightly dump ran (`kubectl -n helper2-prod get pods -l app=pg-backup`) and the Scout's run
   shows in Scout runs.

## 5. Going back

What prod runs after each failure, and what to do. The schema never goes back: every way below brings the code back
onto the schema as the release left it.

| When it fails | Prod runs | Tags | Do |
|---|---|---|---|
| `check` or `built` | the release before | none | Fix what the error names, run `release` again |
| The canary or its smoke test | the release before; the canary is removed | none, the version is free | Read the canary step's log. Fix by pull request into `release/YYYY.N`, then `release` again with the same version |
| The rollout, after the Harbor tag | the revision deployed before: `make deploy` went back by itself | Harbor keeps `YYYY.N.P`; the run tags the commit `used/vYYYY.N.P` | When the code was not the cause: `release` again, same version, same commit. Else merge the fix, `release-prepare` with `patch-of`, and release the next patch |
| The rollback itself (`ROLLBACK FAILED`, or `ROLLED BACK, STILL NOT HEALTHY`) | not healthy | as above | Nothing more is tried by the run. Read the pods and `helm -n helper2-prod history helper2`, then `make -C deploy/k8s ENV=prod rollback` |
| `publish` | the new version, healthy | missing in Git | `gh run rerun <run id> -R $R --failed` |
| Later: the release is healthy for the checks and wrong for users | the new version | all there | One of the three ways below |

To go back by hand, fastest first:

1. **Helm's last deployed revision**, from the workstation. It needs no image check and no approval. Helm keeps three
   revisions.

   ```bash
   make -C deploy/k8s ENV=prod rollback
   make -C deploy/k8s ENV=prod status
   ```

2. **A named release, through the workflow**: `cd` by hand with the version and `rollback`, then the approval on
   `prod`. The version needs its tag `vYYYY.N.P` in this repository.

   ```bash
   gh workflow run cd.yml -R $R --ref main -f environment=prod -f tag=YYYY.N.P -f rollback=true
   ```

3. **A named release, from the workstation** (cosign installed, a token that reads `ci/harbor`), from a checkout of
   that release: `make -C deploy/k8s ENV=prod TAG=YYYY.N.P deploy`.

After a rollback the version that failed is still released: its tag never moves. The fix is the next patch.

**When the release before cannot run on the migrated schema** (a release made with `canary` unticked), the code
rollback is not enough. The way back is the dump taken in check 4: the restore of
[README.md, Backups](README.md#backups), then way 2 or 3. It loses everything written after the dump. Decide it
before the window, not in it. One command for it, a dump of its own per release and a rehearsal on staging are
open: [S10.8.2](https://github.com/silvioincalza/helper2.ai/issues/427).

**An interrupted Helm run** leaves the release `pending-…`. `make deploy` recovers it first, by a rollback to the
last deployed revision; by hand it is `make -C deploy/k8s ENV=prod rollback`. Never `helm uninstall`.

## 6. After the release

1. The pull request back into `main` is merged (step 9), and its branch is gone.
2. `CHANGELOG.md` on `main`: the entry of the version no longer says unreleased. By pull request.
3. The release's story is closed by its pull request, and the Project follows.
4. The job made for check 4 is deleted (`pg-backup-now`), and no canary is left (`helm -n helper2-prod list`).
5. The first release with the restore check runs it once by hand, then the CronJob takes over every Sunday:

   ```bash
   kubectl -n helper2-prod create job --from=cronjob/pg-restore-check pg-restore-check-now
   kubectl -n helper2-prod wait --for=condition=complete job/pg-restore-check-now --timeout=60m
   kubectl -n helper2-prod logs job/pg-restore-check-now -c check
   kubectl -n helper2-prod delete job pg-restore-check-now
   ```
6. Nothing to delete in Git or Harbor: a release branch, a release tag and an image tag stay for good.
7. From the first tag `vYYYY.N.P` on, CI refuses an edit of a released migration
   (`.github/scripts/released-migrations.sh`): a schema change is a new `V<version>.<n>__…` (ADR 0004 §5).

## The first use: v2026.11.3

`v2026.11.3` is the first release of this repository
([S10.7.1](https://github.com/silvioincalza/helper2.ai/issues/352)). What is special about it:

- **Prod keeps its data, after one step by hand.** Prod runs `2026.11.2`, and its database ran an earlier
  `V1__schema.sql`: the one of this repository has two indexes fewer (`resource_updated_idx`,
  `resource_label_value_idx`). Flyway refuses the new file on prod's database (check 5), so before step 3 the
  database is brought to the new file by hand: the two indexes are dropped, and the checksum Flyway stored for
  version 1 is set to the checksum of the file. Nothing else differs between the two files, and no code reads the two
  indexes. The catalog and its embeddings, accounts, saved items, reports, review decisions, the audit trail and the
  runtime settings stay as they are.

  **(Silvio)** From the commit of the release, print the checksum of the file (Flyway's own: CRC32 of its lines):

  ```bash
  python3 -c 'import sys,zlib
  c=0
  for l in open(sys.argv[1],encoding="utf-8-sig",newline="").read().splitlines(): c=zlib.crc32(l.encode(),c)
  print(c-2**32 if c>=2**31 else c)' backend/libs/schema/src/main/resources/db/migration/V1__schema.sql
  ```

  Then, with that number as `CHECKSUM`, right before the release (check 4's dump is taken first):

  ```bash
  NS=helper2-prod; CHECKSUM=<the number>
  kubectl -n $NS exec postgres-0 -- psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 -1 \
    -c 'DROP INDEX IF EXISTS resource_updated_idx, resource_label_value_idx' \
    -c "UPDATE flyway_schema_history SET checksum = $CHECKSUM WHERE version = '1'"
  kubectl -n $NS exec postgres-0 -- psql -U helper2 -d helper2 -At \
    -c "SELECT version, checksum FROM flyway_schema_history WHERE version = '1'"
  ```

  Between this step and the release, a pod of `2026.11.2` that restarts does not start: it checks the schema against
  its own file. The pods that run are not touched. So the step and the release are done in one sitting.
  This holds only while the two indexes are the whole difference: `git diff v<running>..<commit>` of the file in
  check 5 must show those two lines and nothing else. Any other change of V1 needs its own step, or an empty database.
- **Staging gets the same step**, with `NS=helper2-staging`, before the first `cd` deploy that carries the new file.
  Without it that deploy fails at the migrate Job and Helm goes back to the release before.
- **A normal release, with the canary.** No migration runs, so no window. `make diff` in the run shows what changes
  beside the three images and the chart version: the image of the off-site upload in the backup CronJob.
- **No new `app.env` key** is expected; check 6 proves it.
- **The way back is Helm's revision of `2026.11.2`** (section 5, way 1). This repository has no tag `v2026.11.2`,
  so ways 2 and 3 cannot name that release.
- **Two steps fewer.** `main` carries `2026.11.3` already, so step 1 (`release-prepare`, no input) creates
  `release/2026.11` on the commit of `main` and opens no version pull request: its summary says so. Skip step 2.
  Step 3 uses that commit's CD run on `main`, the one check 1 read. Step 8 opens no pull request back into `main`,
  because the release is a commit of `main`: skip step 9, and section 6 item 1.
- **Check the number before step 1.** `release-version.sh first` prints the version the first release takes:
  `2026.11.3`. Anything else, stop: the files on `main` do not carry the version this section is for.

  ```bash
  .github/scripts/release-version.sh first
  ```
