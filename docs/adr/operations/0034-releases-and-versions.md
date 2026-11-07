# 0034 — Releases and versions: tags and release branches

**Status.** Accepted. Merges old 0029, 0048 (texts merged in PR 13 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0029"></a>

## Part: was ADR 0029 — Release and versioning: semantic versions, immutable tags, release workflow

**Status.** Accepted. **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0033](0033-ci-cd-and-supply-chain.md)

### Context
Deploys use commit tags (12 characters, [0033](0033-ci-cd-and-supply-chain.md)), which say nothing about what changed.
Users, judges and the owner need one readable version for the whole product: the backend, the frontend
and the Helm chart. A published version must always mean the same code, so it can be cited and rolled back to.

### Decision
1. **Versions: `MAJOR.MINOR.PATCH`, released by patch.** Every release, features and fixes alike, raises only the
   patch number (v1.0.0, v1.0.1, v1.0.2, …). A change that breaks an application programming interface (API) client,
   or a migration that drops or rewrites data, is the only reason for a new major version; the minor number is not
   used. v1.0.0 is the baseline; nothing older is kept or referenced.
2. **One version, six files.** `backend/build.gradle.kts` (`version`), `frontend/package.json` (`version`),
   `deploy/helm/helper2/Chart.yaml` (`version`) and `api/public.yaml`, `api/admin.yaml`, `api/common.yaml`
   (`info.version`) change together, in the
   release commit. The chart has
   no `appVersion`: on prod the running image tag is the version (`X.Y.Z`, [the part that was ADR 0048](#part-0048)).
3. **Version in the site footer.** Vite injects `package.json`'s version as `__APP_VERSION__`; `Layout.tsx`
   shows it in the footer (for example `v1.0.1`), linked to the GitHub release of that tag.
4. **Annotated tag on `main`.** A release is `git tag -a vX.Y.Z -m "<notes>" <commit>` on a commit of `main`,
   pushed to GitHub. A fix for what runs on prod may instead be tagged on a `release/*` branch, with the next patch
   only ([the part that was ADR 0048](#part-0048)).
5. **Release workflow.** `release.yml` runs on every pushed `v*` tag, on a GitHub-hosted runner:
   - it fails when the tag was deleted or moved (the push was forced, or the tag had a previous value);
   - it checks the name is `vX.Y.Z`, the tag is annotated, and its commit is on `main`, or on a `release/*`
     branch with the next patch after the release just below it;
   - it checks the four files at that commit carry the same version as the tag;
   - it publishes the GitHub release with the tag message as notes plus the image name
     `<registry>/helper2/apps/helper2:X.Y.Z` and the commit. An existing release is left as is;
   - then it waits until the commit's CD run has passed (from `main`: staging too), adds the tag `X.Y.Z` to the
     commit's image in Harbor, and deploys `X.Y.Z` to prod, after the approval on the `prod` environment
     ([0033](0033-ci-cd-and-supply-chain.md), [the part that was ADR 0048](#part-0048)).
     A release is the only automatic way to prod.
6. **Tags are immutable.** A release tag is never moved or deleted; a fix is a new version. A GitHub tag ruleset
   on `refs/tags/v*` blocks moving and deleting release tags, with no bypass. Images are never rebuilt; a release
   only adds its version tag to the image of its commit: `make image` skips a tag that Harbor already has, and a Harbor tag-immutability rule on the `helper2`
   project refuses to overwrite or delete any tag.
7. **v1.0.0 is the first release**, on a fresh database with clean images and Helm releases. Every later change
   ships as a new version under point 6.

### Consequences
- A version names one commit and one image, so a rollback to a release is `make ENV=prod TAG=X.Y.Z deploy`
  (or the commit).
- The ruleset blocks a moved or deleted tag at push time; the workflow check is a second guard.
- A release deploys prod; a push to `main` deploys staging only ([0033](0033-ci-cd-and-supply-chain.md)). So prod always runs a
  version the footer can name.

### Alternatives considered
- Calendar versions — they do not say whether an upgrade breaks a client.
- A version derived from Git at build time — the four files would not show it, and the frontend build
  would need Git history inside the image build.
- Release tools that bump and tag automatically — more tooling than one manual tag per release needs.

### Code
- `.github/workflows/release.yml` — tag checks, GitHub release, prod deploy (calls `deploy.yml`).
- `backend/build.gradle.kts`, `frontend/package.json`, `deploy/helm/helper2/Chart.yaml`, `api/public.yaml`, `api/admin.yaml`, `api/common.yaml` (`info.version`) — the version.
- `frontend/vite.config.ts`, `frontend/src/components/Layout.tsx` — version in the footer.
- `deploy/k8s/Makefile` (`image`, `retag`) — commit tags never rebuilt; the version tag on a release's image.
- `deploy/k8s/CI.md` (Releases) — how to tag a release.

### Diagrams
[flow](../../diagrams/flow-0034-release-versioning.svg) · [sequence](../../diagrams/sequence-0034-release-versioning.svg)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0048"></a>

## Part: was ADR 0048 — Release branches and version tags on images

**Status.** Accepted. **Builds on:** [0033](0033-ci-cd-and-supply-chain.md), [the part that was ADR 0029](#part-0029), [0033](0033-ci-cd-and-supply-chain.md)

### Context
Images are tagged with their commit ([0033](0033-ci-cd-and-supply-chain.md)), so Harbor and the cluster say nothing about which
release runs. And a release could only come from `main`: a fix for what runs on prod had to ship with everything
merged since the last release. Images are built only on `main` (pull requests stay light), and a release never
rebuilds one.

### Decision
1. **Image tags.** CD tags every image with its commit (12 characters), on `main` and on a release branch. A
   release adds its version: the prod deploy job runs `make retag`, and Harbor adds the tag `X.Y.Z` to the image of
   the commit (`POST …/artifacts/<commit>/tags`). One digest, two tags, nothing rebuilt. Run again, it does nothing;
   `X.Y.Z` already on another digest fails, and Harbor's tag-immutability rule refuses to move it anyway.
   The retag runs in `deploy.yml`, after the prod approval and before the release commit is checked out, and only
   when `release.yml` calls it with the commit its checks passed (`release-commit`): the version tag must still
   name that commit, and is never force-fetched. No job reaches Harbor's credentials from a tag push before a
   reviewer said yes, and a manual run cannot add a version tag that no release checked.
2. **Prod runs the version.** The `prod` job of `release.yml` deploys `X.Y.Z`. `deploy.yml` takes a 12-character
   commit or a version; a version resolves to the commit of the tag `vX.Y.Z`, and the chart is checked out from that
   commit, as before. The release notes name both tags. The "older than what runs" check compares commits, so the same commit under a commit tag and
   under a version tag is not a rollback. Commits of `main` and of a release branch can diverge, so neither is the
   older one: then the higher release version wins, and a lower version, or a commit tag on either side, needs
   `rollback: true`. The `staged` job reads the CD run of the commit on its own source branch; from `main`, that
   run's staging deploy must have passed, so a release-branch run of the same commit never stands in for it.
3. **Release branches `release/*`.** A push runs `cd.yml`: CI, then the `image` and `scan` jobs, with the commit
   tag. No staging deploy: staging follows `main`. A pull request to a release branch runs `ci.yml` like any other.
4. **Patch only on a release branch.** A tag on a commit that is not on `main` must be on a `release/*` branch and
   must be the next patch after the release just below it in version order (`v1.0.5` → `v1.0.6`). Any other version
   fails the release check. From `main`, the rules of [the part that was ADR 0029](#part-0029) stay as they are.
5. **What may deploy.** `deploy.yml` hands deploy secrets to a commit on `main`, and to a commit of a `release/*`
   branch only under its release version (checked by `release.yml`, approved on `prod`); a commit tag of a release
   branch is refused.
6. **Outside this repository, required before this change runs.** The OpenBao role `deploy` (CD's `image` job)
   also accepts `refs/heads/release/*`; the role `deploy-prod` also reads `ci/harbor`; the Harbor robot in
   `ci/harbor` may create tags; the `release/*` branches have a ruleset like `main` (pull requests only, no force
   push, no deletion). The `prod` environment needs no change: the prod deploy runs on the tag ref `v*`.

### Consequences
- Harbor, the cluster and the release notes name the version that runs on prod; the commit tag stays for staging
  and for any image built before a release.
- A fix reaches prod without what `main` gained since the last release.
- A release from a release branch reaches prod without running on staging: the one exception to "prod never gets
  an untested image" ([0033](0033-ci-cd-and-supply-chain.md)). The `staged` job then logs a warning, not "staging passed". The
  image went through CI, the Grype scan and the prod approval.
- A manual run (staging or prod, a rollback) deploys a version only once its release has tagged it in Harbor.
- The guide-answer cache is kept per build (`H2_BUILD_VERSION`, the image tag): the first prod deploy by version
  of a commit starts with an empty cache, once.
- The prod deployer can read the Harbor robot: it can push images, after the approval only.

### Alternatives considered
- Rebuild the image on the release tag — prod would run a fresh build, not the image CI and staging had.
- Tag images with the version only — staging and the commits between two releases would have no name.
- Release branches that deploy staging too — staging would jump between `main` and an old line, and every push to
  `main` would overwrite the fix under test.
- Allow minor or major versions on a release branch — the part that was ADR 0029 raises only the patch; a new line starts on `main`.

### Code
- `deploy/k8s/Makefile` (`retag`) — the version tag on a pushed image, through Harbor's API.
- `.github/workflows/release.yml` (`release` check step, `staged`, `prod`) — release branches, patch only, prod by
  version.
- `.github/workflows/deploy.yml` (Resolve tag) — commit or version, main or release branch, the rollback check,
  `make retag` on prod.
- `.github/workflows/cd.yml` — `release/**` pushes, staging on `main` only.
- OpenBao roles `deploy` and `deploy-prod`, the Harbor robot, the GitHub ruleset for `release/*` — outside this repo
  (the platform repo, Harbor, repo settings).

### Diagrams
[flow](../../diagrams/flow-0034-release-branches-and-version-tags.svg) · [sequence](../../diagrams/sequence-0034-release-branches-and-version-tags.svg)

### Roadmap
- A release branch that also deploys staging before prod, if hotfixes become frequent: [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
