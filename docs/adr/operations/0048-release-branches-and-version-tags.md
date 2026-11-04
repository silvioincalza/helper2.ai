# 0048 — Release branches and version tags on images

**Status.** Accepted. **Builds on:** [0028](0028-ci-cd.md), [0029](0029-release-versioning.md), [0046](0046-supply-chain.md)

## Context
Images are tagged with their commit ([0028](0028-ci-cd.md)), so Harbor and the cluster say nothing about which
release runs. And a release could only come from `main`: a fix for what runs on prod had to ship with everything
merged since the last release. Images are built only on `main` (pull requests stay light), and a release never
rebuilds one.

## Decision
1. **Image tags.** CD tags every image with its commit (12 characters), on `main` and on a release branch. A
   release adds its version: `release.yml` runs `make retag`, and Harbor adds the tag `X.Y.Z` to the image of the
   commit (`POST …/artifacts/<commit>/tags`). One digest, two tags, nothing rebuilt. Run again, it does nothing;
   `X.Y.Z` already on another digest fails, and Harbor's tag-immutability rule refuses to move it anyway.
2. **Prod runs the version.** The `prod` job of `release.yml` deploys `X.Y.Z`. `deploy.yml` takes a 12-character
   commit or a version; a version resolves to the commit of the tag `vX.Y.Z`, and the chart is checked out from that
   commit, as before. The "older than what runs" check compares commits, so the same commit under a commit tag and
   under a version tag is not a rollback.
3. **Release branches `release/*`.** A push runs `cd.yml`: CI, then the `image` and `scan` jobs, with the commit
   tag. No staging deploy: staging follows `main`. A pull request to a release branch runs `ci.yml` like any other.
4. **Patch only on a release branch.** A tag on a commit that is not on `main` must be on a `release/*` branch and
   must be the next patch after the release just below it in version order (`v1.0.5` → `v1.0.6`). Any other version
   fails the release check. From `main`, the rules of [0029](0029-release-versioning.md) stay as they are.
5. **What may deploy.** `deploy.yml` hands deploy secrets only to a commit on `main` or on a `release/*` branch.
6. **Outside this repository.** The OpenBao role `deploy`, the only one that reads `ci/harbor`, accepts
   `refs/heads/main`, `refs/heads/release/*` and `refs/tags/v*`; the `release/*` branches have a ruleset like `main`
   (pull requests only, no force push, no deletion).

## Consequences
- Harbor, the cluster and the release notes name the version that runs on prod; the commit tag stays for staging
  and for any image built before a release.
- A fix reaches prod without what `main` gained since the last release.
- A release from a release branch reaches prod without running on staging: the one exception to "prod never gets
  an untested image" ([0028](0028-ci-cd.md)). The `staged` job then logs a warning, not "staging passed". The
  image went through CI, the Grype scan and the prod approval.
- A release branch made before this decision carries the old `release.yml`, which refuses its tags: cut release
  branches from a commit that has this change.
- A version tag that `retag` never added (a manual prod run with a version before its release ran) fails the
  rollout, and Helm rolls back.

## Alternatives considered
- Rebuild the image on the release tag — prod would run a fresh build, not the image CI and staging had.
- Tag images with the version only — staging and the commits between two releases would have no name.
- Release branches that deploy staging too — staging would jump between `main` and an old line, and every push to
  `main` would overwrite the fix under test.
- Allow minor or major versions on a release branch — 0029 raises only the patch; a new line starts on `main`.

## Code
- `deploy/k8s/Makefile` (`retag`) — the version tag on a pushed image, through Harbor's API.
- `.github/workflows/release.yml` (`release` check step, `staged`, `retag`, `prod`) — release branches, patch only,
  the version tag, prod by version.
- `.github/workflows/deploy.yml` (Resolve tag) — commit or version, main or release branch, the rollback check.
- `.github/workflows/cd.yml` — `release/**` pushes, staging on `main` only.
- OpenBao role `deploy` and the GitHub ruleset for `release/*` — outside this repo (incalza-me-infra, repo settings).

## Diagrams
[flow](../../diagrams/flow-0048-release-branches-and-version-tags.svg) · [sequence](../../diagrams/sequence-0048-release-branches-and-version-tags.svg)

## Roadmap
- A release branch that also deploys staging before prod, if hotfixes become frequent: [roadmap](../../roadmap.md#2-operations).
