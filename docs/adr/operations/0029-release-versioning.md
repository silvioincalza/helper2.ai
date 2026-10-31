# 0029 — Release and versioning: semantic versions, immutable tags, release workflow

**Status.** Accepted (2026-10-27). **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0028](0028-ci-cd.md)

## Context
Deploys use commit tags (12 characters, [0028](0028-ci-cd.md)), which say nothing about what changed.
Users, judges and the owner need one readable version for the whole product: the backend, the frontend
and the Helm chart. A published version must always mean the same code, so it can be cited and rolled back to.

## Decision
1. **Versions: `MAJOR.MINOR.PATCH`, released by patch.** Every release, features and fixes alike, raises only the
   patch number (v1.0.0, v1.0.1, v1.0.2, …). A change that breaks an application programming interface (API) client,
   or a migration that drops or rewrites data, is the only reason for a new major version; the minor number is not
   used. v1.0.0 is the baseline; nothing older is kept or referenced.
2. **One version, four files.** `backend/build.gradle.kts` (`version`), `frontend/package.json` (`version`),
   `deploy/helm/helper2/Chart.yaml` (`version`) and `api/openapi.yaml` (`info.version`) change together, in the
   release commit. The chart has
   no `appVersion`: the running image is the commit tag.
3. **Version in the site footer.** Vite injects `package.json`'s version as `__APP_VERSION__`; `Layout.tsx`
   shows it in the footer (for example `v1.0.1`), linked to the GitHub release of that tag.
4. **Annotated tag on `main`.** A release is `git tag -a vX.Y.Z -m "<notes>" <commit>` on a commit of `main`,
   pushed to GitHub.
5. **Release workflow.** `release.yml` runs on every pushed `v*` tag, on a GitHub-hosted runner:
   - it fails when the tag was deleted or moved (the push was forced, or the tag had a previous value);
   - it checks the name is `vX.Y.Z`, the tag is annotated, and its commit is on `main`;
   - it checks the four files at that commit carry the same version as the tag;
   - it publishes the GitHub release with the tag message as notes plus the image name
     `<registry>/helper2/apps/helper2:<commit[0:12]>`. An existing release is left as is;
   - then it waits until the commit's CD run has passed staging, and deploys that commit to prod, after the approval on the `prod` environment ([0028](0028-ci-cd.md)).
     A release is the only automatic way to prod.
6. **Tags are immutable.** A release tag is never moved or deleted; a fix is a new version. A GitHub tag ruleset
   on `refs/tags/v*` blocks moving and deleting release tags, with no bypass. Image tags are commits and are never
   rebuilt: `make image` skips a tag that Harbor already has, and a Harbor tag-immutability rule on the `helper2`
   project refuses to overwrite or delete any tag.
7. **v1.0.0 is the first release** (27 Oct 2026), deployed with a clean start: a fresh database, and clean
   images and Helm releases. Every later change ships as a new version under point 6.
8. **v1.1.0 withdrawn** (28 Oct 2026). It raised the minor number, against point 1. Its GitHub release, tag, images
   and Helm revisions were removed with the protections of point 6 lifted for that one change, and the next
   release is v1.0.2. v1.1.1 was never tagged.

## Consequences
- A version names one commit and one image, so a rollback to a release is `make ENV=prod TAG=<commit> deploy`.
- The ruleset blocks a moved or deleted tag at push time; the workflow check stays as a second guard.
- Deleting an old image needs the Harbor immutability rule disabled first, and enabled again after.
- A release deploys prod; a push to `main` deploys staging only ([0028](0028-ci-cd.md)). So prod always runs a
  version the footer can name.

## Alternatives considered
- Calendar versions — they do not say whether an upgrade breaks a client.
- A version derived from Git at build time — the three files would not show it, and the frontend build
  would need Git history inside the image build.
- Release tools that bump and tag automatically — more tooling than one manual tag per release needs.

## Code
- `.github/workflows/release.yml` — tag checks, GitHub release, prod deploy (calls `deploy.yml`).
- `backend/build.gradle.kts`, `frontend/package.json`, `deploy/helm/helper2/Chart.yaml`, `api/openapi.yaml` (`info.version`) — the version.
- `frontend/vite.config.ts`, `frontend/src/components/Layout.tsx` — version in the footer.
- `deploy/k8s/Makefile` (`image`) — commit tags never rebuilt.
- `deploy/k8s/CI.md` (Releases) — how to tag a release.

## Diagrams
[flow](../../diagrams/flow-0029-release-versioning.svg) · [sequence](../../diagrams/sequence-0029-release-versioning.svg)

## Roadmap
- GitHub tag ruleset on `refs/tags/v*` and a Harbor tag-immutability rule: [roadmap](../../roadmap.md#1-release-v100-before-the-hackathon-deadline-30-oct-2026-1800-zurich).
