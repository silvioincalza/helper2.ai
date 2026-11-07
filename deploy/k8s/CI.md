# CI/CD (GitHub Actions)

| Workflow | Trigger | Runner | Does |
|---|---|---|---|
| [`ci.yml`](../../.github/workflows/ci.yml) | pull request, called by `cd` (`full: true`) | GitHub-hosted | job `changes` maps the changed paths ([`changes.sh`](../../.github/scripts/changes.sh), [ADR 0071](../../docs/adr/operations/0071-ci-cd-per-component-and-image-signing.md)); each job runs when its part changed: frontend lint, typecheck, vitest, build; backend `gradlew test` (Testcontainers); `make chart-check` (helm lint, `helm template \| kubeconform`); promtool. PRs only: Postgres tag-bump check when `deploy/postgres` changes, D2 diagrams re-rendered with d2 v0.9.0 and diffed. `ci-ok`: one check, red when a job failed or was cancelled |
| [`cd.yml`](../../.github/workflows/cd.yml) | push to `main`, manual | self-hosted | all of CI → `make postgres-image` + `make ci-images` (a changed image is built, an unchanged one gets the new tag on the signed image of the commit before; the digests pushed are recorded) → `make sbom` + `make backoffice-sbom` + `make web-sbom` + `make postgres-sbom` → `make sign` (exactly the digests this run pushed, OpenBao Transit; then every image must verify) → token revoked → artifacts `sbom`, `sbom-backoffice`, `sbom-web`, `sbom-postgres` (90 days) → Grype on each → staging. Never prod on its own |
| [`deploy.yml`](../../.github/workflows/deploy.yml) | called by `cd` and `release` only | self-hosted | `make verify-images` (each image's signature, by digest; before the target checkout), `make diff` and `make deploy` (they check again and deploy by digest; a commit from before digests only with `rollback`), `make status` for one env |
| [`guide-eval.yml`](../../.github/workflows/guide-eval.yml) | nightly 01:41 UTC, manual | GitHub-hosted | `scripts/eval-guide.mjs` (fails below 10 of 20 passed) and `--adversarial` (fails on any case) against staging, never prod; reports kept 30 days as an artifact. No secret. Not called by CI or CD: a failure never blocks a deploy ([ADR 0030](../../docs/adr/quality/0030-guide-evaluation.md)) |
| [`release.yml`](../../.github/workflows/release.yml) | push of a `v*` tag | GitHub-hosted | refuses a moved or deleted tag; checks annotated, on `main`, same version in the four files (ADR 0029); publishes the GitHub release → waits for the commit's CD run to pass staging → attaches that run's SBOM to the release; **approval** → prod, the tag's commit |
| [`image-scan.yml`](../../.github/workflows/image-scan.yml) | Monday 05:17 UTC, manual | GitHub-hosted | Grype with a fresh database against the SBOMs of the latest release (public app, back office and web image); code scanning and job summary, report-only ([ADR 0046](../../docs/adr/operations/0046-supply-chain.md)) |

Third-party actions are pinned to commit SHAs with the version as a comment;
[Dependabot](../../.github/dependabot.yml) bumps them weekly, along with Gradle, npm and the Dockerfiles' base images.

CD runs the steps of [README.md](README.md) for the two hosted environments, wrapped in a
[`Makefile`](Makefile): build and push the three images (web, public app, back office), then `helm upgrade --install` with
`values-<env>.yaml`. Nothing about the deploy is duplicated in YAML. Tag = first 12 chars of the
commit, image built from `git archive` of it.

```
push main      ─► ci ─► image ─► staging
push tag vX.Y.Z ─► release checks ─► GitHub release ─► staging passed? ─► (prod reviewer approves) ─► prod
manual:         environment=staging|prod, tag=<12-char commit | X.Y.Z> ─► deploy
                prod with empty tag = what staging runs now (promote)
```

Prod runs only released versions. Staging runs every commit of `main`, so unfinished work (for example
a new design built page by page) can sit on `main` and on staging while prod stays on the last release.

Rollback: run `cd` manually with the previous tag (`helm -n helper2-<env> history helper2`; an image from before
signing needs `make sign-existing` first, see [Image signing](#image-signing)) and
`rollback` ticked (a deploy refuses a commit older than the one running without it), or
`helm rollback`. The platform repo (incalza-me-infra) owns the namespaces, DNS, the Ingresses and the
`deployer` ServiceAccounts of the hosted environments; CD never changes them.

## Why a self-hosted runner

The cluster API and the registry are reachable only from a private network. The repo is public, so the
runner must never run pull-request code: `cd.yml` has no `pull_request` trigger and `deploy.yml` is
`workflow_call` only. CI for PRs runs on GitHub-hosted runners.

## Security model

Nothing long-lived is stored in GitHub.

**GitHub** (repo settings)
- Environments `staging` (deployment branch `main` only) and `prod` (branch `main` and tags `v*`, for
  manual runs and releases); `prod` has a required reviewer.
  A missing environment is auto-created *without* protection rules, so they must exist first.
- Actions → General → Fork pull request workflows: require approval for all outside collaborators.
- Variables `BAO_ADDR` (the secrets store address) and `BAO_AUDIENCE` (the OIDC audience, equal to the
  OpenBao role's `bound_audiences`); optional `IMAGE_ENGINE` (default `podman`).

**Runner** — one just-in-time (JIT) registration per job: wipe the runner user's home and workspace, mint
a JIT config for one job, run it, repeat. Every job starts clean and the registration dies with the job.
The token that mints JIT configs (fine-grained, this repo only) is readable only by root; the job runs as
an unprivileged user with rootless podman. No ambient credentials: no kubeconfig, no secrets-store token.

**Secrets store** — OpenBao with GitHub OIDC login (audience = `BAO_AUDIENCE`). Roles:

| Role | Bound `sub` | Can read |
|---|---|---|
| `deploy` | `repo:silvioincalza/helper2.ai:ref:refs/heads/main` | `ci/harbor` (registry push credentials), sign with the Transit key `helper2-images`; denied `ci/staging`, `ci/prod` |
| `deploy-staging` | `…:environment:staging` | `ci/staging`, `ci/harbor-pull` |
| `deploy-prod` | `…:environment:prod` (after the approval) | `ci/prod`, `ci/harbor-pull` |

`ci/<env>` holds `kubeconfig` (the `deployer` ServiceAccount of that namespace) and `app_env`.
`make ENV=<env> credentials` writes it.

**Kubernetes** — `deployer` in each namespace may manage what the chart creates there, nothing
cluster-wide (RBAC in incalza-me-infra). The prod deployer may also `get` the `helper2`
Deployment in staging (to promote what staging runs), but not staging's Secrets. After an `app.env`
change, rerun `make ENV=<env> credentials`; the next deploy restarts the app with it.

**Releases** — tag the release commit on `main`: `git tag -a v1.0.3 -m "<notes>" <commit>; git push origin v1.0.3`.
The push publishes the GitHub release and starts the prod deploy of `1.0.3`, which waits for the approval; after it,
the deploy job adds the tag `1.0.3` to the commit's three images in Harbor (same digests) and deploys them.

**Fix releases from a release branch** (ADR 0048) — branch from the release tag (`git switch -c release/1.0 v1.0.3`,
push it), merge the fix into it by pull request, then tag the next patch on it (`v1.0.4` when `v1.0.3` is the release
just below). CD builds the image of each push to `release/*` and deploys no staging: the release goes to prod without
a staging run, after the approval. Merge the fix into `main` too. Setup, once: a branch ruleset on `release/*` like
`main` (pull requests only, no force push, no deletion), the OpenBao role `deploy` bound to `refs/heads/main` and
`refs/heads/release/*`, the role `deploy-prod` allowed to read `ci/harbor`, and a Harbor robot that may create tags.
A release tag is never moved: publish a new version instead. Repo settings → Rules → a tag ruleset on
`refs/tags/v*` restricting updates and deletions blocks the push itself; `release.yml` fails on one that
got through and publishes nothing.

## Image signing

CD signs every image it pushes with a key in OpenBao Transit, and a deploy checks each signature before Helm runs
([ADR 0071](../../docs/adr/operations/0071-ci-cd-per-component-and-image-signing.md)). The private key never leaves
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
make -C deploy/k8s sign-existing TAG=<12-char commit or X.Y.Z>     # also the Postgres image of postgres.image.tag
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
