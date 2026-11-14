# Set up from zero

These steps set up helper2.ai on a new GitHub repository, from zero: the repository and its settings, the CI
variables, the OpenBao roles, the self-hosted runner, the first deploy. The platform (cluster, OpenBao, Harbor,
the runner VM) still runs: only what is bound to the repository changes.

The GitHub settings are files in [`.github/settings`](../.github/settings). This page says in which order to apply
them. The deploy itself is in [deploy/k8s/README.md](../deploy/k8s/README.md), CI/CD and its security model in
[deploy/k8s/CI.md](../deploy/k8s/CI.md), the decisions in [ADR 0044](adr/operations/0044-ci-cd-and-supply-chain.md).

A new repository has a new id. Three things are bound to that id, so they are made again: the OIDC subject the
OpenBao roles accept, the runner's token, and the rulesets.

## 1. What git does not hold

Keep a copy of these before you start. Git cannot give them back.

| What | Where | Also in |
|---|---|---|
| Notes, plans, reviews, memory, `launch.json` | `.private/` (`.remember` and `.claude/launch.json` point into it) | nowhere |
| Each environment's settings and passwords | `deploy/k8s/staging/app.env`, `deploy/k8s/prod/app.env` | OpenBao `helper2/kv`, `ci/<env>` (`app_env`) |
| Terraform backend and variables | `deploy/k8s/terraform/backend.hcl`, `terraform.tfvars` | nowhere |
| Local settings | `.env`, `.claude/settings.local.json`, `CLAUDE.local.md` | nowhere |
| The database | the Postgres PVC and the nightly dump | the off-site bucket ([Backups](../deploy/k8s/README.md#backups)) |
| The signing key `helper2-images` | OpenBao Transit `helper2/transit` (not exportable) | its public half is `deploy/k8s/cosign.pub` |

Repository secrets: optional, read only by the weekly `live` workflow: `H2_GITHUB_TOKEN`, `H2_HF_TOKEN`,
`H2_LIVE_WEB_API_KEY`, `H2_NEBIUS_API_KEY`. A live test whose key is missing is skipped. Nothing that deploys
reads a GitHub secret.

## 2. Tools on the workstation

The versions are the ones CI and the runner pin.

| Tool | Version | Pinned in |
|---|---|---|
| Java (Temurin) | 25 | `.github/workflows/ci.yml` |
| Gradle | wrapper | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Node | 24 or later | `frontend/package.json` (`engines`) |
| Helm | 4.3.1 | `ci.yml`, the runner role |
| kubectl | 1.36.0 | the runner role |
| OpenBao CLI (`bao`) | 2.7.1 | the runner role |
| cosign | 3.1.4 | `.github/actions/install-cosign` |
| Syft, Grype | 1.56.0, 0.121.1 | `.github/actions/install-scanners` |
| kubeconform, d2 | 0.8.0, 0.9.0 | `ci.yml` |

Also `gh` (signed in as the owner), `git`, `jq`, `podman` and `rclone` (restores only).

## 3. Create the repository, with Actions off

Actions stay off until step 10. Otherwise the first push starts `cd.yml`, and GitHub creates the environments
`staging` and `prod` by itself, without their protection rules.

```bash
R=<owner>/helper2.ai
gh repo create $R --public
gh api -X PUT repos/$R/actions/permissions -F enabled=false
```

## 4. Push the history

Push before the rulesets exist: the ruleset of `main` takes pull requests only, and no one may bypass it.

```bash
git remote set-url origin https://github.com/$R.git
git push origin main
git push origin 'refs/heads/release/*:refs/heads/release/*'
git push origin 'refs/tags/v*:refs/tags/v*'
```

## 5. Repository settings and rulesets

```bash
S=.github/settings
gh api -X PATCH repos/$R --input $S/repo.json
gh api -X PUT repos/$R/topics --input $S/topics.json
gh api -X PUT repos/$R/vulnerability-alerts
gh api -X PUT repos/$R/automated-security-fixes
for f in main release tags; do gh api -X POST repos/$R/rulesets --input $S/ruleset-$f.json; done
```

What they hold:

- Squash merges only, and the branch deleted after the merge. No auto-merge.
- Secret scanning with push protection. Dependabot alerts and security-update pull requests; `.github/dependabot.yml`
  bumps the versions every week.
- `main`: pull requests only, every review thread resolved, the check `ci-ok` green (GitHub Actions, integration
  15368) on an up-to-date branch, no force push.
- `release/**`: pull requests only, every review thread resolved, a Copilot review on each push, no force push,
  no deletion, the check `ci-ok` green on an up-to-date branch, as on `main`.
- `v*` tags: never moved or deleted.

The OpenBao roles accept a token only when its `ref_protected` claim is true, so CD cannot log in before these
rulesets exist.

## 6. Actions settings

```bash
gh api -X PUT repos/$R/actions/permissions/workflow --input $S/workflow-permissions.json
gh api -X PUT repos/$R/actions/permissions/fork-pr-contributor-approval --input $S/fork-pr-approval.json
gh api -X PUT repos/$R/actions/oidc/customization/sub --input $S/oidc-sub.json
gh api repos/$R/actions/oidc/customization/sub --jq .sub_claim_prefix
```

- The `GITHUB_TOKEN` may only read by default. GitHub Actions may open pull requests (`release-prepare` opens the
  version pull request).
- Every pull request from an outside collaborator waits for an approval before its workflows run.
- The OIDC subject is the immutable one: `repo:<owner>@<owner id>/<repo>@<repo id>`. A renamed owner or repository
  cannot take over the OpenBao roles. Keep the printed prefix for step 8.

## 7. Environments and variables

```bash
gh api -X PUT repos/$R/environments/staging --input $S/environment-staging.json
gh api -X PUT repos/$R/environments/prod --input $S/environment-prod.json
gh api -X POST repos/$R/environments/staging/deployment-branch-policies -f name=main -f type=branch
gh api -X POST repos/$R/environments/prod/deployment-branch-policies -f name=main -f type=branch
gh api -X POST repos/$R/environments/prod/deployment-branch-policies -f name='v*' -f type=tag
gh variable set -R $R -f $S/variables.env
```

- `staging` deploys from `main` only. `prod` from `main` (a manual run of `cd`, the `release` workflow) and from
  `v*` tags, after the required reviewer approves. `environment-prod.json` names that reviewer by GitHub user id:
  `gh api users/<login> --jq .id`.
- The variables are in [`variables.env`](../.github/settings/variables.env), each with what it is. `IMAGE_ENGINE` is
  optional (default `podman`).
- An environment `copilot` appears by itself when the Copilot coding agent first runs. Nothing deploys from it.

## 8. The hosted platform (incalza-me-infra)

The platform repository owns OpenBao, Harbor, the runner VM and the cluster's namespaces, Ingresses and `deployer`
ServiceAccounts. For a new repository, three things change there:

1. In `tenants.yml`, `owners.helper2.ci`: `repo` and `sub_prefix` (the prefix printed in step 6). Then
   `make bao-tenants` (playbook 33): the roles `deploy`, `deploy-staging` and `deploy-prod` of the auth mount
   `helper2/jwt` now accept the new repository.
2. A new fine-grained token, this repository only, permission Administration read and write (the API that makes a
   just-in-time runner needs it). It goes into the platform's sealed `.secrets.env` as `GITHUB_RUNNER_PAT`. Then
   `make github-runner` (playbook 3a) on `gh-runner-01`.
3. Check that the runner is there, with the labels the workflows ask for (`self-hosted`, `incalzame`, `linux`,
   `x64`):

   ```bash
   gh api repos/$R/actions/runners --jq '.runners[] | {name, status, labels: [.labels[].name]}'
   ```

   It registers again for every job, so its name changes each time.

The Harbor projects, the Transit key `helper2-images` and the namespaces stay as they are. When the Transit key is
new too, write its public half and commit it: `make -C deploy/k8s cosign-pub`. Images signed with the old key then
fail their check: sign them again before a rollback to them (`make -C deploy/k8s sign-existing TAG=<tag>`).

## 9. What OpenBao gives CI

Log in as `helper2-operator` (OIDC, in the browser), then write each environment's `app.env` and the `deployer`
kubeconfig into `ci/<env>`, and the off-site backup Secret of prod:

```bash
make -C deploy/k8s ENV=staging credentials
make -C deploy/k8s ENV=prod credentials
make -C deploy/k8s ENV=prod backup-credentials
```

When OpenBao already holds them, nothing changes: the paths do not name the repository.

## 10. Actions on, the first run

```bash
gh api -X PUT repos/$R/actions/permissions -F enabled=true -f allowed_actions=all
```

Only a push builds images: `cd.yml` runs CI and the image job on `push` events, and a manual run only deploys
images Harbor already holds. The history pushed in step 4 started nothing, and the ruleset takes no direct push to
`main`. So the first build comes from a pull request: open one with a small change (for example a line in
`CHANGELOG.md`), wait for `ci-ok`, merge it. The push to `main` starts `cd.yml`: it builds, signs and scans the
images, then deploys staging. A release also needs a green push run of its commit, so the first release is cut
after this run, with `release-prepare`. Check:

- the run is green and staging answers: `curl -s -o /dev/null -w '%{http_code}\n' https://helper2.incalza.me/`;
- the pull request showed the check `ci-ok`, and the merge button offered squash only;
- the next nightly `guide-eval` run reaches staging.

Prod gets the next release: [CI.md, Releases](../deploy/k8s/CI.md).

## 11. The workstation

```bash
git clone https://github.com/$R.git helper2.ai && cd helper2.ai
cp -R <backup>/.private .private
cp <backup>/.env .env
cp <backup>/staging.app.env deploy/k8s/staging/app.env
cp <backup>/prod.app.env deploy/k8s/prod/app.env
scripts/env-align.sh deploy/k8s/app.env.example deploy/k8s/staging/app.env --dry-run
cd backend && ./gradlew test && cd ../frontend && npm ci && npm test -- --run
```

`.remember` and `.claude/launch.json` are symlinks into `.private/`; git restores the links. Each session then
works in its own worktree under `.claude/worktrees/` ([AGENTS.md](../AGENTS.md)).

## 12. The backlog and the Project

The backlog is the issues of the repository and one GitHub Project of the owner. Both are made from a backlog file
by [`scripts/backlog.py`](../scripts/backlog.py). The file is kept in `.private/plans/` and not in git: it can hold
the text of items that stay private.

```bash
gh auth refresh -s project
python3 scripts/backlog.py import .private/plans/backlog.json
python3 scripts/backlog.py import .private/plans/backlog.json --write
```

The first command writes nothing: it prints each change it would make. Read it, then run the second.

What the import does:

- One issue per epic, feature, story and task, with the label of its kind. A feature is a sub-issue of its epic, a
  story or a task of its feature.
- An issue is found again by the line `<!-- backlog:ID -->` at the end of its body. So a second run changes nothing,
  and an entry whose title or text changed updates its issue and never makes a second one.
- Every issue is an item of the Project. The import sets the fields Context, Priority, Release and Replay PR. Status
  is the Project's own field: the import checks that it is there and never sets it, because the Project's workflow
  closes an issue whose Status becomes Done.
- A built story stays open. The pull request that builds it closes it with a `Closes #n` line.
- An entry with `"private": "security"` or `"private": "legal"` becomes an issue that says only "Tracked privately",
  with its ID. Its title, text and criteria are never sent to GitHub. A security finding or a point for a lawyer is
  written this way, or stays out of the file.

The backlog file is one JSON object:

| Key | Holds |
|---|---|
| `repo`, `project` | `owner/name` of the repository; the title of the Project |
| `slices` | number of a replay pull request → its name |
| `epics` | `id`, `kind` (`epic`), `title`, `context` |
| `features` | `id`, `kind` (`feature`), `parent`, `title`, `source`, `context` |
| `stories`, `tasks` | `id`, `kind`, `parent`, `title`, `story`, `criteria`, `needs`, `context`, `priority`, `release`, `replay_pr`, and `private` for an item that stays private |

`priority` is one of Replay, Now, Next, Later, Parked, Accepted. Replay means built: the story waits for its pull
request.

Two commands read GitHub and write nothing there:

```bash
python3 scripts/backlog.py roadmap
python3 scripts/backlog.py pr-stories <pull request number>
```

`roadmap` writes [docs/roadmap.md](roadmap.md) from the open issues; its output goes into a pull request.
`pr-stories` is run before a replay pull request is merged: it passes only when the pull request closes every story
whose Replay PR field is the number in its branch name (`replay/NN-name`), and no other issue.

## Change a setting

Edit its file in `.github/settings` in a pull request. After the merge, apply it the same way as above. A ruleset is
updated by its id:

```bash
id=$(gh api repos/$R/rulesets --jq '.[] | select(.name == "main: pull requests only") | .id')
gh api -X PUT repos/$R/rulesets/$id --input .github/settings/ruleset-main.json
```

To see what runs today, read it back: `gh api repos/$R/rulesets/$id`, `gh api repos/$R/environments/prod`,
`gh api repos/$R/actions/permissions/fork-pr-contributor-approval`.
