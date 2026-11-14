# 0045 — Releases and versions

**Depends on:** [0001](../foundation/0001-stack-and-runtime.md), [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0010](../foundation/0010-data-retention.md), [0044](0044-ci-cd-and-supply-chain.md).

## Context

Deploys use commit tags (12 characters, [0044](0044-ci-cd-and-supply-chain.md)), which say nothing about what
changed. Users and the owner need one readable version for the whole product: the backend, the frontend, the
API contracts and the Helm chart. A published version must always mean the same code, so it can be cited and rolled
back to. Harbor and the cluster should say which release runs on prod, not only which commit.

A semantic major version moves for every migration that drops or rewrites data. So it says one thing only, that the
database changed, and the release notes say that better. API clients do not need it: the API carries its own version
in the path (`/api/v1`). What a reader of the footer, Harbor or the cluster wants to know is how old the running
release is, and whether it is a release or a fix of one.

A release that is tagged first and deployed after can fail on prod: the tag and the GitHub release then name a
version that never ran, and the number is used up. A release should be tried on prod, with prod's data, network and
edge, before anyone can see it, and get its name only once it works.

A fix for what runs on prod should not have to ship with everything that reached `main` since the last release, and it should
read as a fix of that release. What a release branch gained must reach `main` again.

## Decision

### Versions
1. **Versions: `YYYY.N.P`.** The year of the release in UTC, the release's number in that year from 1, then the patch
   from 0: v2026.12.0 is a release, v2026.12.1 and v2026.12.2 are its patches, v2026.13.0 the next release,
   v2027.1.0 the first of 2027. It is a valid semantic version, so the chart, npm, Gradle and the three API files carry
   it as it is.
   - **The first release fits.** v2026.11.3, the first release of this repository, reads as release 11 of 2026,
     patch 3: the next release is v2026.12.0.
   - **No gap, no repeat.** A release `.0` follows the highest release of its year (or is `.1.0`); a patch `.P`
     follows `.P-1` of the same release.
   - **The year is the year the release is prepared.** The tag comes later, after the canary, maybe on 1 January:
     the release check takes any year from the commit the release is cut from to the time of the check
     (`release-version.sh check <tag> <from> <to>`).
   - **A breaking change is said in the release notes.** A migration that drops or rewrites data, or an API change
     that breaks a client, goes on the first line of the notes (the `headline` input of `release`). The API path
     stays `/api/v1` for now, also after a break: no client outside this repository is kept working.
   - **One script holds the rule.** `.github/scripts/release-version.sh` checks a tag (`check`), prints the next
     release (`next`) and the next patch of a release (`next-patch YYYY.N`), and tests itself on fixed cases
     (`--self-test`, run by the CI job `changes` on every pull request).
2. **One version, seven files.** `backend/build.gradle.kts` (`version`), `frontend/package.json` and the two
   `version` lines of `frontend/package-lock.json`, `deploy/helm/helper2/Chart.yaml` (`version`) and
   `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` (`info.version`) change together, in the version commit
   on the release branch (`.github/scripts/bump-version.sh bump`). `main` gets the version with the release's pull
   request back (§10), and keeps the higher one when a patch of an older release comes back. The chart has no
   `appVersion`: the image tag is passed per deploy, and on prod it is the version (§8).
3. **Version in the site footer.** Vite injects `package.json`'s version as `__APP_VERSION__`; `Layout.tsx` shows it
   in the footer (for example `v2026.12.0`), linked to the GitHub release of that tag. The canary smoke test reads
   the same string from the page's scripts to know it reached the canary (§6).

### The release, step by step
4. **Prepare: the branch and the version.** `release-prepare.yml`, run by hand from `main`, does one of two things:
   - **a release:** takes a commit of `main` (default: the newest one whose CD run deployed staging), picks the next
     release with `release-version.sh next` (it counts prepared branches too), makes the version commit, and only then
     creates `release/YYYY.N` on that commit. A branch `release/YYYY.N` with no tag and no version yet was cut by a
     run that stopped half-way: the next run finishes it instead of picking `YYYY.N+1`;
   - **a patch** (`patch-of: YYYY.N`): takes the head of `release/YYYY.N`, where the fixes arrived by pull
     request, and picks `release-version.sh next-patch`. It refuses a branch whose last version is neither released nor used up (`used/vYYYY.N.P`, §8),
     and a branch with nothing since that version.
   Then it pushes the version commit (§2) to `release-bump/YYYY.N.P` and opens the pull request into
   `release/YYYY.N`. Run again, it keeps a pushed `release-bump/YYYY.N.P` and an open pull request. The owner merges it. That push runs `cd.yml` on the release branch: CI, then the images of the
   commit, built, signed and scanned like on `main` ([0044](0044-ci-cd-and-supply-chain.md)).
5. **Release: checks, then one approval.** `release.yml`, run by hand from `main` with the version:
   - the branch `release/YYYY.N` exists and no tag `vYYYY.N.P` does; a `used/vYYYY.N.P` names its head, or there is none;
   - the version follows §1, with the year window from the commit the branch was cut from to now;
   - the seven files at the head of the branch carry the version;
   - a release `.0` must be the commit of `main` plus the version files only (`bump-version.sh only-version-files`):
     then that commit's CD run on `main` must have deployed staging. A patch reaches prod without a staging run, with
     a warning;
   - the head of the branch has the canary (`canary:` in `deploy/k8s/Makefile`);
   - it waits for the CD run of the head of the branch, which must pass.
   Then the `prod` job calls `deploy.yml` with the commit, the version and the commit the checks passed. It waits
   for the approval on the `prod` environment; the canary, the rollout and the version tag in Harbor all come after
   that one approval.
6. **Canary and smoke test.** After the approval `deploy.yml` installs the Helm release `helper2-canary` in the prod
   namespace: the same chart with `canary.only=true`, the images of the commit, by digest.
   - It holds its own migrate Job (`helper2-canary-migrate-N`), one public app pod and one web pod
     (`helper2-canary`, `helper2-web-canary`, their Services) and the NetworkPolicies they need, under names of their
     own. Postgres, Redis, the back office, the backup and the PrometheusRule belong to the release `helper2` and are
     not in it.
   - An Ingress with the ingress-nginx canary annotations routes the public host to it, only for requests with the
     header `X-Helper2-Canary` set to a token made for this run (32 hex characters, masked in the log). No weight: no
     user reaches the canary. Only `/` and the hashed `/assets/` go to the canary: `/theme.js` and `/favicon.svg`
     keep their names and are cached by the edge for hours, so they always come from the release that runs.
   - The smoke test goes through the public host, the edge included (`check-release.sh smoke`). With the header: the
     page answers, one of its scripts names the new version, `/api/v1/sources` and a search answer 200. Each
     ingress-nginx controller loads the canary's backend on its own, and until it has, it sends the header to the
     release that runs: the version check reads the page again for up to a minute. Without the header: the same reads
     against the release that runs, on the schema the canary just migrated.
   - When the install or the smoke test fails, the canary is removed and the job fails: prod runs the release
     before, no version tag exists in Harbor or Git, and the version can be released again once the branch is fixed.
7. **Then the version tag in Harbor.** `make retag` adds the tag `YYYY.N.P` to the web, public and back office images
   of the commit (`POST …/artifacts/<commit>/tags`). One digest, two tags, nothing rebuilt. Run again, it does
   nothing; `YYYY.N.P` already on another digest fails, and Harbor's tag-immutability rule refuses to move it anyway.
   It runs in the approved prod job only, with the commit `release.yml` checked: the head of the branch must still be
   that commit. No job reaches Harbor's write credentials before a reviewer said yes.
8. **Prod runs the version.** `deploy.yml` then deploys `YYYY.N.P` by digest with `make deploy`: the check after it,
   and the rollback to the revision before when it fails ([0044](0044-ci-cd-and-supply-chain.md)). The canary is
   removed as soon as its smoke test passes: its pods carry the release's labels, and the check after the rollout
   would count them. The job removes it again at the end, whatever happened. A rollout that fails after the version
   tag in Harbor uses the version up: the log says so, and the `used-up` job of `release.yml` (write access, on a
   GitHub runner, not the prod runner) tags the commit `used/vYYYY.N.P`. `release` runs again for that commit
   unchanged, when the failure was not the code. Once the branch moved, it refuses the version; `check`, `next-patch`
   and `release-prepare` count `used/vYYYY.N.P` as taken, so the fix is the next patch. A release is ordered against what prod runs by its version: a higher
   version deploys, whatever the Git history; a lower one (a patch of an older release while prod runs a newer one)
   needs `rollback: true`.
9. **Then the tag and the GitHub release.** Only after prod is healthy the `publish` job tags the head of the branch
   `vYYYY.N.P` (annotated; the notes are the `headline`, then GitHub's notes from the pull requests since the release
   below), pushes the tag, publishes the GitHub release with the commit and the three image names, and attaches the
   SBOMs of the CD run of the branch. Run again, it finishes what is missing and leaves what exists.
10. **Last, the pull request back into `main`.** The `publish` job makes `release-merge/vYYYY.N.P` from the tag,
    merges `main` into it, and opens its pull request into `main`: the version and, for a patch, its fixes. When
    `main` carries a higher version (a patch of an older release), a commit first puts `main`'s version back, so the
    merge has no conflict on the version lines and `main` keeps its version. A pull request opened with the workflow
    token starts no workflow, so the job starts `ci.yml` on that branch (`workflow_dispatch`), and its `ci-ok` stands
    for the pull request. When the merge conflicts, the branch stays at the tag and the pull request says so: the
    owner resolves it, and that push runs CI. The owner merges it.
11. **No canary for a migration the release before cannot run on.** A migration is written expand then contract:
    the release before must keep working on the new schema, because the canary migrates prod's database while the
    release before still serves it. A release that cannot follow that rule is released with `canary` unticked, in a
    maintenance window: no canary, the version tag, prod by version, then the tag.

### Tags, branches and what may deploy
12. **Tags are immutable.** A release tag is never moved or deleted; a fix is a new patch. A GitHub tag ruleset on
    `refs/tags/v*` blocks moving and deleting release tags, with no bypass. Images are never rebuilt for a version:
    the version tag goes on the images of the release commit. `make image` skips a tag that Harbor already has, and a
    Harbor tag-immutability rule on the `helper2` project refuses to overwrite or delete any tag. A `used/vYYYY.N.P`
    tag is not a release: it records a version that Harbor keeps on a commit prod could not run (§8).
13. **Image tags.** CD tags every image with its commit (12 characters), on `main` and on a release branch. The
    version commit builds new images, since the version is built into the jar and the web bundle: the canary is the
    first run of those digests on a cluster, and staging ran the commit a release is cut from (§5).
14. **Release branches: `release/YYYY.N`, one per release.** It is cut from `main` for the release `YYYY.N.0`, and
    every later deploy to prod from it is the next patch: fixes by pull request into the branch, then
    `release-prepare` with `patch-of`, then `release`. A push runs `cd.yml`: CI, then the `image` and `scan` jobs,
    with the commit tag. No staging deploy: staging follows `main`. A patch of a release made before the canary must
    carry `deploy/` and `.github/` of `main`. A release branch is never deleted (ruleset), so a version that was
    prepared is released.
15. **What may deploy.** `deploy.yml` hands deploy secrets to a commit on `main`, to a release version that has its
    tag (a rollback), and to the head of `release/YYYY.N` only when `release.yml` passes its version and that
    commit, after the approval on `prod`. A commit tag of a release branch alone is refused. `release` and
    `release-prepare` run from `main` only, and the `prod` environment accepts the ref `main`.

### Migrations after a release
16. **After the first tag, forward only.** A schema change is a new `V2__…`, never an edit of `V1__schema.sql`; a fix to
    what runs is a new patch (§12). CI holds the rule on every pull request
    (`.github/scripts/released-migrations.sh`, [0004](../foundation/0004-persistence-schema-and-app-roles.md) §5). A
    release tag sits on its release branch, beside `main`: the check reads every release whose commit, or the commit
    before it, is in the history it checks.

### Code

- `.github/workflows/release-prepare.yml` — a release or a patch: the commit, the version, the release branch, the
  version pull request.
- `.github/workflows/release.yml` (`check`, `built`, `prod`, `publish`) — branch, version rule, seven files, staging
  of the commit a release is cut from, the CD run of the branch, prod through `deploy.yml`, then the tag, the GitHub
  release, the SBOMs and the pull request back into `main`.
- `.github/workflows/deploy.yml` (Resolve tag, Canary and smoke test, Version tag in Harbor, Remove the canary) — the
  release commit, the order by version, the canary, `make retag`, prod by version.
- `.github/workflows/ci.yml` (`workflow_dispatch`) — CI on the pull request back into `main`.
- `.github/scripts/release-version.sh` — the version rule: `check` (with the year window), `next`, `next-patch`,
  `--self-test`.
- `.github/scripts/bump-version.sh` — the version commit (`bump`) and the check that a branch is its commit of `main`
  plus the version (`only-version-files`).
- `.github/scripts/released-migrations.sh` — the forward-only rule over every release of the history: `check`,
  `--self-test`.
- `deploy/helm/helper2` (`canary.*` values, `templates/canary.yaml`, the canary names in `_helpers.tpl`) — the
  canary-only release.
- `deploy/k8s/Makefile` (`canary`, `canary-smoke`, `uncanary`, `retag`, the canary part of `chart-check`) and
  `deploy/k8s/check-release.sh` (`smoke`) — the canary and its smoke test.
- `.github/workflows/cd.yml` — `release/**` pushes, staging on `main` only.
- `backend/build.gradle.kts`, `frontend/package.json`, `frontend/package-lock.json`, `deploy/helm/helper2/Chart.yaml`,
  `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` (`info.version`) — the version.
- `frontend/vite.config.ts`, `frontend/src/shell/Layout.tsx` — version in the footer.
- `deploy/k8s/CI.md` — how to release, and how to patch a release.
- OpenBao roles `deploy` and `deploy-prod`, the Harbor robot, the GitHub rulesets for `v*` tags and `release/*`, the
  repository setting that lets workflows open pull requests — outside this repo (the platform repo, Harbor, repo
  settings).

## Alternatives considered

- `YYYY.M.N` (the month and the release in the month) — a fix of a release took the next number of the month, so
  nothing in the number said it was a fix, nor of which release.
- One release branch per month, every deploy the next number — fixes and new work share one line, so a fix of the
  release on prod waits for, or ships with, what `main` brought into the branch.
- Tag first, then deploy (the release workflow on a pushed `v*` tag) — the tag and the GitHub release came before
  prod ran anything, so a failed deploy left a published version that never ran.
- A weighted canary (a share of real traffic) — with three replicas and little traffic it proves little, and it
  shows users a release no check has passed yet.
- Argo Rollouts or Flagger — a controller and CRDs more to run and upgrade, for one canary per release that a
  second Helm release and one Ingress already give.
- A canary in its own namespace with its own database — it would not test prod's data, network or migration.
- The version commit on `main`, by pull request, before the tag — `main` would name a version before it is released.
- A bot that pushes the version straight onto the release branch — the branch ruleset allows pull requests only,
  and a bypass needs a GitHub App and its key.
- The release branch itself as the head of the pull request into `main` — `main` needs the head up to date, and
  updating a release branch is a push the ruleset refuses; it would also bring `main`'s new work into the release.
- Semantic versions (major, minor, patch) — the major moves with every data migration, so it carries no news, and
  the number says nothing of the release's age.
- A version for every build on `main` — CD would need a counter store, and most builds never ship; the commit tag
  already names them.
- A version derived from Git at build time — the seven files would not show it, and the frontend build would need Git
  history inside the image build.
- Tag images with the version only — staging and the commits between two releases would have no name.
- Release branches that deploy staging too — staging would jump between `main` and an old line, and every push to
  `main` would overwrite the release under test.

## Consequences

- A version names one commit and one set of images, so a rollback to a release is
  `make ENV=prod TAG=YYYY.N.P deploy` (or the manual `cd` run with the version and `rollback`).
- The number says whether a release is new (`.0`) or a fix of one (`.P`), and which release it fixes.
- A tag and a GitHub release always name a version that ran on prod and passed its check. A failed canary leaves no
  trace but its run: no tag, no release, no version tag in Harbor, the number still free.
- A failed full rollout after the version tag in Harbor uses up the number: Harbor keeps `YYYY.N.P` on that digest,
  and a fixed branch builds another one. Git records it as `used/vYYYY.N.P`, and the fix is the next patch.
- The footer, Harbor and the release list say which release runs; the release date says when.
- The version does not say whether an upgrade breaks something: the release notes and the API path do.
- A release needs no choice of number: `release-prepare` picks it.
- A release is two manual runs and two merges: `release-prepare`, its version pull request, `release`, and the pull
  request back into `main`. The version pull request is opened with the workflow token, so no CI runs on it; the push
  after the merge runs all of CI.
- The canary shares prod's Postgres and Redis. Its migrate Job changes prod's schema before the rollout, so a
  migration must keep the release before working (§11); the smoke test without the header checks that it does.
- The canary pod runs the public app's start-up work against prod: it publishes its build to the settings the back
  office shows, until the next pods of the release start. It runs no scheduled job (those are in the back office).
- A patch reaches prod without a staging run, after its canary: the one exception to "prod never gets an untested
  image" ([0044](0044-ci-cd-and-supply-chain.md)).
- A manual run (staging or prod, a rollback) deploys a version only once its release has tagged it.
- The guide-answer cache is kept per build (`H2_BUILD_VERSION`, the image tag): the canary and the release by
  version each start with an empty cache, once.
- The prod deployer can read the Harbor robot: it can push images, after the approval only.
- The canary deepens the use of ingress-nginx (its canary annotations), which upstream has retired. A move to another
  controller has to bring header routing too.

### Known limits

- Deleting an old image needs the Harbor immutability rule disabled first, and enabled again after.
- A release that has no version tag in Harbor (one made before release tags) is deployed by its commit, not by its version.
- The year of a release is checked against a window, from the commit it is cut from to the time of the check: a
  release prepared on 31 December may carry the old year. The version does not say whether an upgrade breaks
  something; the release notes say it.
- A prepared release cannot be abandoned: its branch is never deleted and the numbers allow no gap, so the version
  is released, or the next year starts again at `.1.0`. A rollout that fails after the version tag in Harbor uses the
  number up (Harbor keeps it on that digest): the fix is the next patch. A run that fails after prod and before the tag leaves prod on a version
  with no tag and no GitHub release: run the failed `publish` job again.
- The canary shares prod's Postgres and Redis: its migrate Job changes prod's schema before the rollout, and a failed
  canary leaves the schema migrated. Its start-up publishes its build to the settings the back office shows, until the
  next pods of the release start. Its smoke test reads the public host only, and spends one search (live web credits
  included). Only the public app and the web image run as a canary: the back office gets the new version at the
  rollout, untried.
- `main` gets a release's version only when its pull request back is accepted: until then staging's footer shows the
  release before. The pull request is opened with the workflow token, so CI on it is started by the release run
  (`workflow_dispatch`); if GitHub stops counting that run's `ci-ok` for the pull request, push a commit to
  `release-merge/vYYYY.N.P`.

### Open points

- No CI check proves that the release before starts on the new schema; the smoke test of §6 is the only proof.

## Diagrams

The release workflow: [flow](../../diagrams/flow-0045-release-versioning.svg) · [sequence](../../diagrams/sequence-0045-release-versioning.svg) ·
release branches, the canary and version tags: [flow](../../diagrams/flow-0045-release-branches-and-version-tags.svg) · [sequence](../../diagrams/sequence-0045-release-branches-and-version-tags.svg)

## Backlog

- Features: [F10.3](https://github.com/silvioincalza/helper2.ai/issues/109) Release branches, versions, canary.
- Open issues that change this decision:
  - [#352](https://github.com/silvioincalza/helper2.ai/issues/352) S10.7.1 V2026.11.3, the first release of the new repository, on prod
