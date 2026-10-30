# CI/CD (GitHub Actions)

| Workflow | Trigger | Runner | Does |
|---|---|---|---|
| [`ci.yml`](../../.github/workflows/ci.yml) | pull request, called by `cd` | GitHub-hosted | frontend lint, typecheck, vitest, build; backend `gradlew test` (Testcontainers); `make chart-check` (helm lint, `helm template \| kubeconform`). PRs only: app image build (no push), Postgres image build + tag-bump check when `deploy/postgres` changes, `terraform fmt -check` + `validate` (no backend), D2 diagrams re-rendered with d2 v0.9.0 and diffed |
| [`cd.yml`](../../.github/workflows/cd.yml) | push to `main`, manual | self-hosted | CI → `make postgres-image` + `make image` (each only if its tag is not in the registry yet) → staging → **approval** → prod, same tag |
| [`deploy.yml`](../../.github/workflows/deploy.yml) | called by `cd` only | self-hosted | `make diff`, `make deploy`, `make status` for one env |
| [`release.yml`](../../.github/workflows/release.yml) | push of a `v*` tag | GitHub-hosted | refuses a moved or deleted tag; checks annotated, on `main`, same version in the four files (ADR 0029); publishes the GitHub release |

Third-party actions are pinned to commit SHAs with the version as a comment;
[Dependabot](../../.github/dependabot.yml) bumps them weekly, along with Gradle, npm and the Dockerfiles' base images.

CD runs the steps of [README.md](README.md) for the two hosted environments, wrapped in a
[`Makefile`](Makefile): build and push both images, then `helm upgrade --install` with
`values-<env>.yaml`. Nothing about the deploy is duplicated in YAML. Tag = first 12 chars of the
commit, image built from `git archive` of it.

```
push main ─► ci ─► image ─► staging ─► (prod reviewer approves) ─► prod
manual:      environment=staging|prod, tag=<12-char commit> ─► deploy
             prod with empty tag = what staging runs now (promote)
```

Rollback: run `cd` manually with the previous tag (`helm -n helper2-<env> history helper2`), or
`helm rollback`. Terraform ([`terraform/`](terraform)) stays manual: it owns the namespaces, the
Cloudflare tunnels, DNS and the `deployer` ServiceAccounts of the hosted environments.

## Why a self-hosted runner

The cluster API and the registry are reachable only from a private network. The repo is public, so the
runner must never run pull-request code: `cd.yml` has no `pull_request` trigger and `deploy.yml` is
`workflow_call` only. CI for PRs runs on GitHub-hosted runners.

## Security model

Nothing long-lived is stored in GitHub.

**GitHub** (repo settings)
- Environments `staging` and `prod`, deployment branch `main` only; `prod` has a required reviewer.
  A missing environment is auto-created *without* protection rules, so they must exist first.
- Actions → General → Fork pull request workflows: require approval for all outside collaborators.
- Variable `BAO_ADDR` (the secrets store address); optional `IMAGE_ENGINE` (default `podman`).

**Runner** — one just-in-time (JIT) registration per job: wipe the runner user's home and workspace, mint
a JIT config for one job, run it, repeat. Every job starts clean and the registration dies with the job.
The token that mints JIT configs (fine-grained, this repo only) is readable only by root; the job runs as
an unprivileged user with rootless podman. No ambient credentials: no kubeconfig, no secrets-store token.

**Secrets store** — OpenBao with GitHub OIDC login (audience = the OpenBao address). Roles:

| Role | Bound `sub` | Can read |
|---|---|---|
| `deploy` | `repo:silvioincalza/helper2.ai:ref:refs/heads/main` | `ci/harbor` (registry push credentials); denied `ci/staging`, `ci/prod` |
| `deploy-staging` | `…:environment:staging` | `ci/staging` |
| `deploy-prod` | `…:environment:prod` (after the approval) | `ci/prod` |

`ci/<env>` holds `kubeconfig` (the `deployer` ServiceAccount of that namespace) and `app_env`.
`make ENV=<env> credentials` writes it.

**Kubernetes** — `deployer` in each namespace may manage what the chart creates there, nothing
cluster-wide ([`terraform/rbac.tf`](terraform/rbac.tf)). The prod deployer may also `get` the `helper2`
Deployment in staging (to promote what staging runs), but not staging's Secrets. After an `app.env`
change, rerun `make ENV=<env> credentials`; the next deploy restarts the app with it.

**Releases** — tag the release commit on `main`: `git tag -a v1.2.0 -m "<notes>" <commit>; git push origin v1.2.0`.
A release tag is never moved: publish a new version instead. Repo settings → Rules → a tag ruleset on
`refs/tags/v*` restricting updates and deletions blocks the push itself; `release.yml` fails on one that
got through and publishes nothing.
