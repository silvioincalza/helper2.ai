# CI/CD (GitHub Actions)

| Workflow | Trigger | Runner | Does |
|---|---|---|---|
| [`ci.yml`](../../.github/workflows/ci.yml) | pull request, called by `cd` (`full: true`), manual (the pull request of a release back into `main`) | GitHub-hosted | job `changes` maps the changed paths ([`changes.sh`](../../.github/scripts/changes.sh), [ADR 0044](../../docs/adr/operations/0044-ci-cd-and-supply-chain.md)) and fails when a released migration changed ([`released-migrations.sh`](../../.github/scripts/released-migrations.sh): before the first `vYYYY.N.P` tag only `V1__schema.sql`, after it no edit of a released `V…` file); each job runs when its part changed: frontend lint, typecheck, vitest, build; backend `gradlew test` (Testcontainers); `make chart-check` (helm lint, `helm template \| kubeconform`); promtool. PRs only: Postgres tag-bump check when `deploy/postgres` changes, D2 diagrams re-rendered with d2 v0.9.0 and diffed. `ci-ok`: one check, red when a job failed or was cancelled |
| [`cd.yml`](../../.github/workflows/cd.yml) | push to `main`, manual | self-hosted | all of CI, plus job `package` (both jars and the web bundle as the artifact `prebuilt`) → `make postgres-image`, then `make sign-pushed` in a step that runs also when the run is cancelled (the Postgres tag is shared by every commit: left unsigned, it would stop every later run) + `make ci-images PREBUILT=…` (a changed image packs what CI built, no rebuild on the runner, an unchanged one gets the new tag on the signed image of the commit before; the digests pushed are recorded) → `make sbom` + `make backoffice-sbom` + `make web-sbom` + `make postgres-sbom` → `make sign` (exactly the digests this run pushed, OpenBao Transit; then every image must verify) → token revoked → artifacts `sbom`, `sbom-backoffice`, `sbom-web`, `sbom-postgres` (90 days) → staging, and side by side Grype on each (a Critical finding with a fix fails the run, so the commit cannot be released; staging does not wait for it). A newer push to the same branch cancels the older run's `ci`, `image` and `scan`; a running deploy finishes. Never prod on its own |
| [`deploy.yml`](../../.github/workflows/deploy.yml) | called by `cd` and `release` only | self-hosted | `make verify-images` (each image's signature, by digest; before the target checkout), on prod the commit's CD run must have passed (GitHub API; skipped with `rollback`), `make diff` and `make deploy` (they check again and deploy by digest; a commit from before digests only with `rollback`), `make status` for one env. `make deploy` ends with the check of [`check-release.sh`](check-release.sh) (release deployed, workloads rolled out, migrate Job complete, `/` and `/api/v1/sources` answer 200): when it fails, the release goes back to the revision deployed before the upgrade, is checked once more, and the job fails. `make status` is the same check with no rollback. The input `keep-unhealthy` (from `cd`'s manual run only, default false) skips that rollback for one deploy: `make deploy ROLLBACK_UNHEALTHY=false`, said in the run summary (see below) |
| [`release-prepare.yml`](../../.github/workflows/release-prepare.yml) | manual, from `main` | GitHub-hosted | a release: the commit of `main` (default: the newest one staging ran), the next release `YYYY.N.0` (`release-version.sh next`), `release/YYYY.N` created on it; or a patch (`patch-of: YYYY.N`): the head of that branch and its next patch. Then the pull request with the version in the seven files (`bump-version.sh bump`, ADR 0045) |
| [`release.yml`](../../.github/workflows/release.yml) | manual, from `main`, with the version | GitHub-hosted, then self-hosted | checks the branch, the version rule, the seven files, that staging ran the commit a `.0` is cut from (a patch: a warning), the canary in its Makefile; waits for the branch's CD run → **approval** → canary of the commit (`make canary`), smoke test (`make canary-smoke`), version tag in Harbor (`make retag`), prod by version, canary removed → annotated tag `vYYYY.N.P`, GitHub release, SBOMs (public app, back office, web, Postgres) → pull request `release-merge/vYYYY.N.P` into `main`, with CI started on it |
| [`image-scan.yml`](../../.github/workflows/image-scan.yml) | Monday 05:17 UTC, manual | GitHub-hosted | Grype with a fresh database against the SBOMs of the latest release (public app, back office, web and Postgres image); code scanning and job summary, report-only ([ADR 0044](../../docs/adr/operations/0044-ci-cd-and-supply-chain.md)) |

Third-party actions are pinned to commit SHAs with the version as a comment;
[Dependabot](../../.github/dependabot.yml) bumps them weekly, along with Gradle, npm and the Dockerfiles' base images.

CD runs the steps of [README.md](README.md) for the two hosted environments, wrapped in a
[`Makefile`](Makefile): build and push the three images (web, public app, back office), then `helm upgrade --install` with
`values-<env>.yaml`. Nothing about the deploy is duplicated in YAML. Tag = first 12 chars of the
commit, image built from `git archive` of it.

```
push main            ─► ci ─► image ─► staging
release-prepare      ─► release/YYYY.N (or its next patch) + version pull request ─► (merge) ─► ci ─► image
release YYYY.N.P     ─► checks ─► CD run green? ─► (prod reviewer approves) ─► canary ─► smoke test
                        ─► Harbor tag YYYY.N.P ─► prod ─► tag vYYYY.N.P ─► GitHub release
                        ─► pull request release-merge/vYYYY.N.P into main ─► (merge)
manual:                 environment=staging|prod, tag=<12-char commit | YYYY.N.P> ─► deploy
                        prod with empty tag = what staging runs now (promote)
```

Prod runs only released versions. Staging runs every commit of `main`, so unfinished work (for example
a new design built page by page) can sit on `main` and on staging while prod stays on the last release.

Rollback: run `cd` manually with the previous tag (`helm -n helper2-<env> history helper2`; an image from before
signing needs `make sign-existing` first, see [Image signing](#image-signing)) and
`rollback` ticked (a deploy refuses a commit older than the one running without it), or
`helm rollback`. The platform repo (incalza-me-infra) owns the namespaces, DNS, the Ingresses and the
`deployer` ServiceAccounts of the hosted environments; CD never changes them.

A deploy that is not healthy after the upgrade goes back by itself to the revision deployed before, and its job
fails. To keep such a release for one deploy, run `cd` manually with `keep-unhealthy` ticked (default: not ticked).
`deploy.yml` then runs `make deploy ROLLBACK_UNHEALTHY=false`: the check still runs, a failed check still fails the
job, with `NOT ROLLED BACK: rollback switched off for this deploy`, and the run summary says the rollback is off.
Only that manual run can set it. A push to `main` and a release cannot: `release.yml` has no such input. No
repository or environment variable is read, so nothing stays set after the run. From the workstation
the same switch is `make ENV=<env> TAG=<tag> deploy ROLLBACK_UNHEALTHY=false` (the make command line only; a value
in the environment is ignored).

## Why a self-hosted runner

The cluster API and the registry are reachable only from a private network. The repo is public, so the
runner must never run pull-request code: `cd.yml` has no `pull_request` trigger and `deploy.yml` is
`workflow_call` only. CI for PRs runs on GitHub-hosted runners.

## Security model

Nothing long-lived is stored in GitHub.

**GitHub** (repo settings, as files in [`.github/settings`](../../.github/settings); applied in order by
[docs/recreate.md](../../docs/recreate.md))
- Environments `staging` (deployment branch `main` only) and `prod` (branch `main` and tags `v*`, for manual runs and
  releases); `prod` has a required reviewer.
  A missing environment is auto-created *without* protection rules, so they must exist first.
- Actions → General → Workflow permissions: read only by default; allow GitHub Actions to create pull requests
  (`release-prepare` opens the version pull request).
- Actions → General → Fork pull request workflows: require approval for all outside collaborators.
- Squash merges only. Rulesets: `main` and `release/**` take pull requests only, with every review thread resolved
  (`main` also needs `ci-ok`); `v*` tags never move.
- Secret scanning with push protection, Dependabot alerts and security-update pull requests.
- OIDC subject: the immutable one, `repo:<owner>@<owner id>/<repo>@<repo id>`.
- Variables `BAO_ADDR` (the secrets store address), `BAO_AUDIENCE` (the OIDC audience, equal to the
  OpenBao role's `bound_audiences`) and `H2_STAGING_URL` (staging, for `guide-eval`); optional `IMAGE_ENGINE`
  (default `podman`). Repository secrets: only the optional keys of the weekly `live` workflow.

**Runner** — one just-in-time (JIT) registration per job: wipe the runner user's home and workspace, mint
a JIT config for one job, run it, repeat. Every job starts clean and the registration dies with the job.
The token that mints JIT configs (fine-grained, this repo only) is readable only by root; the job runs as
an unprivileged user with rootless podman. No ambient credentials: no kubeconfig, no secrets-store token.

**Secrets store** — OpenBao with GitHub OIDC login (audience = `BAO_AUDIENCE`). Roles:

| Role | Bound `sub` | Can read |
|---|---|---|
| `deploy` | `repo:silvioincalza@644958/helper2.ai@1457242485:ref:refs/heads/main` or `…:ref:refs/heads/release/*`, `ref_protected` true | `ci/harbor` (registry push credentials), sign with the Transit key `helper2-images`; denied `ci/staging`, `ci/prod` |
| `deploy-staging` | `…:environment:staging` | `ci/staging`, `ci/harbor-pull` |
| `deploy-prod` | `…:environment:prod` (after the approval) | `ci/prod`, `ci/harbor-pull`, and `ci/harbor` to add the release's version tag to its image |

`ci/<env>` holds `kubeconfig` (the `deployer` ServiceAccount of that namespace) and `app_env`.
`make ENV=<env> credentials` writes it.

**Kubernetes** — `deployer` in each namespace may manage what the chart creates there, nothing
cluster-wide (RBAC in incalza-me-infra). The prod deployer may also `get` the `helper2`
Deployment in staging (to promote what staging runs), but not staging's Secrets. After an `app.env`
change, rerun `make ENV=<env> credentials`; the next deploy restarts the app with it.

**Releases** ([ADR 0045](../../docs/adr/operations/0045-releases-and-versions.md)) — a version is `YYYY.N.P`: the year
(UTC), the release in the year, the patch. `2026.12.0` is a release, `2026.12.1` its first patch. A release is
tried on prod before it gets its tag:

1. Run **release-prepare** from `main` (Actions → release-prepare). Leave `commit` empty for the newest commit of
   `main` that staging ran. It creates `release/2026.12` on that commit and opens the pull request with the version
   `2026.12.0` in the seven files. When it stops half-way, run it again: it finishes `release/2026.12`.
2. Merge that pull request (squash). The push builds, signs and scans the images of the release branch.
3. Run **release** from `main` with `version` 2026.12.0. Put a migration that drops or rewrites data, or an API
   break, in `headline`: it becomes the first line of the notes. The run checks, waits for the CD run of the branch,
   then for the approval on `prod`.
4. After the approval: the canary (one public app and one web pod, reached only with a header and a token made for
   the run), its smoke test through the public host, the canary removed, the Harbor tag `2026.12.0` on the same
   digests, prod by version. Then the tag `v2026.12.0`, the GitHub release, and the pull request
   `release-merge/v2026.12.0` into `main` (CI runs on it). When the canary fails, prod stays on the release before
   and nothing is tagged: fix the branch by pull request and run **release** again. When prod fails after the
   Harbor tag, `2026.12.0` is used up and the run tags the commit `used/v2026.12.0`. Run **release** again for the same
   commit, or merge the fix and run **release-prepare** with `patch-of` 2026.12: the fix is the patch `2026.12.1`.
5. Merge the pull request into `main`.

A migration the release before cannot run on (not expand then contract) needs a maintenance window: run
**release** with `canary` unticked. Older releases (`2.0.0` and before) still deploy by their version for a rollback.

**Patches** (ADR 0045) — every new deploy to prod from a release branch is its next patch. Merge the fix into
`release/2026.12` by pull request, run **release-prepare** with `patch-of` `2026.12` (it opens the pull request
with `2026.12.1`), merge it, then run **release** with `2026.12.1`. It warns that staging never ran the fix: the
canary is its first run. Its pull request back into `main` brings the fix to `main`; when `main` is on a newer
release, `main` keeps its own version. A patch of a release made before the canary (`release/2026.11`, from
`v2026.11.1`) must also carry `deploy/` and `.github/` of `main`.

A release branch and a release tag are never deleted or moved. Setup, once: a branch ruleset on `release/*`
(pull requests only, no force push, no deletion), the OpenBao role `deploy` bound to `refs/heads/main` and
`refs/heads/release/*`, the role `deploy-prod` allowed to read `ci/harbor`, a Harbor robot that may create tags, and
a tag ruleset on `refs/tags/v*` restricting updates and deletions.

## Image signing

CD signs every image it pushes with a key in OpenBao Transit, and a deploy checks each signature before Helm runs
([ADR 0044](../../docs/adr/operations/0044-ci-cd-and-supply-chain.md)). The private key never leaves
OpenBao; [`cosign.pub`](cosign.pub) is its public half.

The platform repo (incalza-me-infra) sets it up, from `tenants.yml` (`helper2.ci.signing`,
`helper2.harbor.pull_robot`, `helper2.ci.environment_reads`), with `make bao-tenants` (playbook 33):

- the Transit engine `helper2/transit` with the key `helper2-images` (ECDSA P-256, not exportable, no plaintext
  backup);
- read on that key and sign with it, nothing else, in the policies `helper2-deploy` (role `deploy`, the CD image
  job) and `helper2-write` (role `helper2-operator`, the workstation);
- the pull-only Harbor robot `robot$helper2+github-pull` (repository pull, artifact read) in `helper2/kv`
  `ci/harbor-pull`, read by the roles `deploy-staging` and `deploy-prod`.

Then, once, the public key into this repo (OIDC login as `helper2-operator`), and commit it:

```bash
make -C deploy/k8s cosign-pub
```

CD signs only the digests its own run pushed (`make sign` reads them from the push). It never signs an image that
was already in Harbor: the first run after this change rebuilds the three app images (the old ones are unsigned) and
then fails on the Postgres image, which was pushed before signing. That one, and an old tag before a rollback to it,
are signed by hand on the workstation, never from a workflow:

```bash
# Needs sign rights on helper2-images: helper2-operator has them (policy helper2-write). Without BAO_TOKEN the
# target logs in with OIDC as helper2-operator.
make -C deploy/k8s sign-existing TAG=<12-char commit or YYYY.N.P>  # also the Postgres image of postgres.image.tag
```

It lists the images of that tag and the Postgres image that have no signature, with their digests, and signs them
after you confirm (`CONFIRM=yes` without a terminal). Before you confirm, check each digest against the commit's CD
run log ("pushed ..."): sign only what CD built. An image signed by another key is never signed again: it fails.

**Rollback to a commit from before signing.** Sign its images first (`make sign-existing TAG=<commit>`), then run
`cd` manually with that tag and `rollback` ticked. Its Makefile has no digests, so Helm deploys it by tag: `deploy.yml`
checked the signatures before, and refuses such a commit without `rollback`.

cosign gets the token as `VAULT_ADDR` and `VAULT_TOKEN`, set from `BAO_ADDR` and `BAO_TOKEN`; the Makefile drops every
other `VAULT_` variable first and sets `TRANSIT_SECRET_ENGINE_PATH=helper2/transit`. In CD the token is revoked right
after the signing step.

Harbor keeps a signature as a referrer of the image's digest or, without the referrers API, under a tag
`sha256-<digest>`. Check which one after the first signed run:
`curl -u <robot> https://harbor-i.infra.incalza.me/v2/helper2/apps/helper2/referrers/<digest>`. With the tag, the
tag-immutability rule must allow creating it; CD never updates it (an image is signed once).
