# 0046 — Supply chain: base images pinned by digest, an SBOM per image, a vulnerability scan, report-only first

**Status.** Accepted. **Builds on:** [0027](0027-runtime-topology.md), [0028](0028-ci-cd.md), [0029](0029-release-versioning.md)

## Context
The app image and the Postgres image are built from public base images, and the app ships hundreds of Java and
JavaScript libraries. Nothing told us when one of them had a known vulnerability, and nothing listed what an image
holds. A base-image tag can move to new content without a change in our repository. A scanner run as a third-party
GitHub action is itself a risk: in March 2026 the tags of a widely used scanner action were moved to code that stole
CI secrets (CVE-2026-33634).

## Decision
1. **Digests.** Base images and the images the chart runs are pinned as `image:tag@sha256:<digest>`; Dependabot
   moves tag and digest together. Actions are pinned by commit SHA, as before.
2. **Scanner: Syft and Grype (Anchore, Apache-2.0).** Syft writes a CycloneDX SBOM of an image; Grype scans that
   SBOM. Both are installed in the job, never as a third-party action (`.github/actions/install-scanners`): Syft from
   its release tarball checked against a pinned SHA-256, Grype with `go install` of an exact version, checked by the
   Go checksum database like helm and d2. Grype's database is cached for the day and its download retried once
   (`.github/actions/grype-db`).
3. **Where.**
   - Pull requests build no image and scan nothing: they stay light ([0028](0028-ci-cd.md)).
   - Every push to main (`cd.yml`): on the self-hosted runner, `make sbom` and `make postgres-sbom` write the SBOMs
     of the app image pushed to Harbor and of the Postgres image of `postgres.image.tag`, kept 90 days as artifacts
     of the run (`sbom`, `sbom-postgres`). Then the `scan` job, on a GitHub-hosted runner, downloads both and runs
     Grype: findings with a fix (`--only-fixed`) in the job log and in GitHub code scanning (SARIF, categories
     `image/app` and `image/postgres`, on `main`). It reads artifacts only, never Harbor.
   - A release (`release.yml`): the SBOM of that commit's CD run is attached to the GitHub release as
     `helper2-vX.Y.Z.cdx.json`. It is public: it lists packages and versions, no data.
   - Weekly (`image-scan.yml`, Monday): Grype with a fresh database against the SBOM of the latest release, so a CVE
     published after a release is found without a new build. Category `release/app`, on the release tag.
4. **Report-only first.** Until 18 Nov 2026 nothing fails a build: two weeks to see the findings and the noise.
   Then the `scan` job in `cd.yml` fails on a Critical finding that has a fix (`--fail-on critical --only-fixed`)
   and becomes a need of the staging deploy, so such an image never reaches staging, and so never prod.
5. **Exceptions** live in `.grype.yaml`, empty today: each one names the vulnerability, the reason and a review date.
6. **Signatures.** Every image CD pushes, and the Postgres image, is signed by digest with a key in OpenBao Transit,
   and a deploy runs only images whose signature it checked, pinned by digest
   ([0071](0071-ci-cd-per-component-and-image-signing.md)). An image CD did not change keeps its digest, its
   signature and its SBOM.

No runtime path: this decision runs in CI only, so it has no sequence diagram.

## Consequences
- Every image has a list of what it holds, and the release that runs on prod has it published.
- A new fixable vulnerability shows up in code scanning on `main` as soon as the commit that brings it is merged,
  or within a week for what already runs. The pull request itself does not show it: the price of light PRs.
- A moved base-image tag cannot change a build: a new digest arrives as a Dependabot pull request.
- The `scan` job on `main` takes a few minutes: Grype is built from source (about 2 minutes) and its database is
  downloaded once a day. It runs beside the staging deploy, not before it, while the scan is report-only.
- A broken `deploy/Dockerfile` is found on `main` (the CD `image` job fails, staging keeps its version), not on the
  pull request.
- The scanner versions are pinned by hand: Dependabot does not see them.

## Alternatives considered
- Trivy — also good, but its official action's tags were the ones hijacked; running the binary is possible, and
  Grype plus a separate SBOM tool keeps the SBOM usable by any scanner.
- Docker Scout — needs a Docker account and sends the image metadata to Docker.
- Fail on Critical from day one — with no baseline, the first week of pull requests would be blocked by old findings.
- Scan in the cluster (an operator) — more to run for a one-person team; the weekly job covers what runs.

## Code
- `.github/actions/install-scanners/action.yml`, `.github/actions/grype-db/action.yml`,
  `.github/actions/upload-sarif/action.yml` — install, database, code scanning upload.
- `.github/workflows/cd.yml` (`image`, `scan`), `release.yml` (`sbom`),
  `image-scan.yml` — where the scans run.
- `deploy/k8s/Makefile` (`sbom`, `postgres-sbom`) — SBOM of a pushed image, read from Harbor.
- `.grype.yaml` — exceptions.

## Diagrams
[flow](../../diagrams/flow-0046-supply-chain.svg)

## Roadmap
- [Fail the `scan` job in `cd.yml` on a Critical finding with a fix, from 18 Nov 2026](../../roadmap.md#2-operations).
- Signature check in the cluster as well (admission controller, infra side): [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
