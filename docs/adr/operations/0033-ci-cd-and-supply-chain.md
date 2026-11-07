# 0033 — CI/CD and supply chain: per component, images signed and deployed by digest

**Status.** Accepted. Merges old 0028, 0046, 0071 (texts merged in PR 13 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0028"></a>

## Part: was ADR 0028 — CI/CD: GitHub Actions, PR checks, OpenBao OIDC, staging on main, approved prod on a release

**Status.** Accepted. **Builds on:** [0007](../foundation/0007-configuration-secrets-and-settings.md), [0032](0032-runtime-topology.md)

### Context
The repository is public. The cluster's application programming interface (API) and the image registry (Harbor) are reachable
only on a private network. Every change on `main`
should reach staging without manual steps. Prod should run only released versions, and only after a person
approves: `main` may carry unfinished work that staging shows and users must not see yet. Code from a fork must
never run where deploy credentials exist, and GitHub must hold no long-lived secret.

### Decision
1. **Continuous integration (CI) on every pull request (PR)** — `ci.yml`, GitHub-hosted runners:
   - frontend: `npm ci`, generate API types, `format:check` (Prettier), lint, `knip`, typecheck, Vitest, build, `check:bundle`, `npm audit` ([0037](../quality/0037-code-format-and-dead-code-audit.md));
   - backend: `./gradlew spotlessCheck` ([0037](../quality/0037-code-format-and-dead-code-audit.md)), `./gradlew test` (Testcontainers with
     PostgreSQL and Redis);
   - chart: `make -C deploy/k8s chart-check` (`helm lint --strict`, `helm template | kubeconform -strict`
     for staging, prod, and prod with the off-site backup on);
   - monitoring: `promtool check rules` and `promtool test rules` on `deploy/monitoring`;
   - PRs only: a check that `postgres.image.tag` changed when `deploy/postgres` changed (git only, no build);
     D2 diagrams re-rendered with d2 v0.9.0 and compared with the committed SVG files.
   - No image build: PRs stay light. Images are built, pushed and scanned only on `main` (point 2), and a release
     tag reuses them (point 3).
   - Per component ([the part that was ADR 0071](#part-0071)): a job runs only when its part changed;
     the job `ci-ok` sums them up.
2. **Continuous delivery (CD) on every push to `main`** (and to a `release/*` branch, image only,
   [0034](0034-releases-and-versions.md)) — `cd.yml`: all of CI runs again, then the `image` job on
   the self-hosted runner runs `make postgres-image` and `make ci-images`: the images of the components that changed
   are built and pushed, the others get the new tag on their previous digest, and `make sign` signs every new digest
   ([the part that was ADR 0071](#part-0071)). The image tag is the first 12 characters of the commit,
   built from `git archive`. A `scan` job on a GitHub-hosted runner then scans the SBOMs of the images
   ([the part that was ADR 0046](#part-0046)).
3. **Staging on every push, prod on a release.** `deploy.yml` (reusable, `workflow_call` only) checks the
   signature of every image (`make verify-images`, [the part that was ADR 0071](#part-0071)), then runs
   `make diff`, `make deploy` (images by digest), `make status` for one GitHub environment. `cd.yml` calls it for `staging` after each push to
   `main`, and never for prod. `release.yml` calls it for `prod` when a release tag `vX.Y.Z` passes its checks
   ([0034](0034-releases-and-versions.md)), with that tag's version: the image built when the commit reached `main`
   and already run on staging, given the tag `X.Y.Z` in Harbor ([0034](0034-releases-and-versions.md)). A `staged` job first waits for that commit's CD run and goes on only when it
   passed: a commit whose staging deploy failed, or never ran, cannot reach prod. The `prod` environment has a required reviewer and allows branch `main` and
   tags `v*` only.
4. **OpenBao login with OpenID Connect (OIDC).** `.github/actions/bao-login` asks GitHub for the job's OIDC
   token (audience = repo variable `BAO_AUDIENCE`, equal to the role's `bound_audiences`) and logs in at `auth/helper2/jwt` with a role bound
   to the repository and to the branch or environment: `deploy` (reads `ci/harbor`, signs with the Transit key
   `helper2-images`), `deploy-staging` (reads `ci/staging` and `ci/harbor-pull`), `deploy-prod` (reads `ci/prod`,
   `ci/harbor-pull`, and `ci/harbor` to add a release's version tag,
   [0034](0034-releases-and-versions.md); [the part that was ADR 0071](#part-0071)). The prod role can log in only after the approval.
5. **Tag checks in the deploy job.** The tag must be 12 hex characters with no branch or tag of that name, or a
   version `X.Y.Z` whose tag `vX.Y.Z` exists; it must resolve to a commit on `main` or on a `release/*` branch, and the chart is checked out from that same commit. Manual runs
   take an environment and a tag (redeploy, rollback); the environment defaults to staging, so a run started without
   a thought lands where it does no harm; prod with an empty tag promotes what staging runs now.
6. **One runner per job.** The self-hosted runner registers a just-in-time (JIT) runner
   for one job and wipes its workspace after it. It holds no kubeconfig and no OpenBao token of its own.
   `cd.yml` has no `pull_request` trigger; third-party actions are pinned to commit hashes (SHA).
7. **Deploys are not canceled.** `concurrency: deploy-<env>` with `cancel-in-progress: false`, because an
   interrupted `helm upgrade --wait` leaves the release blocked. A release found `pending-*` is recovered first
   (`make release-ready`, `make rollback`): a rollback to the newest revision Helm marked deployed, never to the
   one just before the current, which after an interrupted rollback is the failed upgrade. The job's budget
   (45 min) covers that recovery, the upgrade and a rollback on failure, 12 min each.

### Consequences
- A green PR has run the same checks that guard `main`; CD repeats CI before it builds anything.
- Pipeline and workstation run the same `Makefile` targets, so the deploy is not described twice.
- Prod cannot get an untested image: a release tag names a commit of `main`, which staging got first, and images
  are never rebuilt. The exception is a release from a `release/*` branch, which skips staging
  ([0034](0034-releases-and-versions.md)).
- Prod changes only on a release (or a manual run). Work in progress can live on `main` and on staging, with no
  feature flag to keep it from users.
- A push to `main` never waits for a prod approval.

### Alternatives considered
- GitHub-hosted runners for deploys — they cannot reach the private network without opening the API or Harbor.
- GitHub Actions secrets for the kubeconfig and app env — long-lived values in GitHub; OIDC avoids them.
- Argo CD or Flux (GitOps) — a controller in the cluster and a second repository of state for one app.
- Build on every deploy — prod would run a fresh build, not the image staging tested.

### Code
- `.github/workflows/ci.yml`, `cd.yml`, `deploy.yml` — the pipeline; `release.yml` — the prod deploy of a release.
- `.github/actions/bao-login/action.yml` — OIDC login to OpenBao.
- `deploy/k8s/Makefile` — `image`, `ci-images`, `sign`, `verify-images`, `postgres-image`, `chart-check`, `diff`,
  `deploy`, `status`, `rollback`.
- `.github/scripts/changes.sh` — which jobs and images a change touches
  ([the part that was ADR 0071](#part-0071)).
- The platform repo (outside this repo) — `deployer` Role, and the prod deployer's read of staging's Deployment.
- `deploy/k8s/CI.md` — one-time setup of GitHub, the runner and OpenBao.

### Diagrams
[flow](../../diagrams/flow-0033-ci-cd.svg) · [sequence](../../diagrams/sequence-0033-ci-cd.svg)

### Roadmap
- Short-lived deployer token from the OpenBao Kubernetes secrets engine: [roadmap](../../roadmap.md#2-operations).
- The open supply-chain steps ([the part that was ADR 0046](#part-0046)): [roadmap](../../roadmap.md#2-operations).
- Browser end-to-end tests in CI: [roadmap](../../roadmap.md#quality-gates).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0046"></a>

## Part: was ADR 0046 — Supply chain: base images pinned by digest, an SBOM per image, a vulnerability scan, report-only first

**Status.** Accepted. **Builds on:** [0032](0032-runtime-topology.md), [the part that was ADR 0028](#part-0028), [0034](0034-releases-and-versions.md)

### Context
The app image and the Postgres image are built from public base images, and the app ships hundreds of Java and
JavaScript libraries. Nothing told us when one of them had a known vulnerability, and nothing listed what an image
holds. A base-image tag can move to new content without a change in our repository. A scanner run as a third-party
GitHub action is itself a risk: in March 2026 the tags of a widely used scanner action were moved to code that stole
CI secrets (CVE-2026-33634).

### Decision
1. **Digests.** Base images and the images the chart runs are pinned as `image:tag@sha256:<digest>`; Dependabot
   moves tag and digest together. Actions are pinned by commit SHA, as before.
2. **Scanner: Syft and Grype (Anchore, Apache-2.0).** Syft writes a CycloneDX SBOM of an image; Grype scans that
   SBOM. Both are installed in the job, never as a third-party action (`.github/actions/install-scanners`): Syft from
   its release tarball checked against a pinned SHA-256, Grype with `go install` of an exact version, checked by the
   Go checksum database like helm and d2. Grype's database is cached for the day and its download retried once
   (`.github/actions/grype-db`).
3. **Where.**
   - Pull requests build no image and scan nothing: they stay light ([the part that was ADR 0028](#part-0028)).
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
   ([the part that was ADR 0071](#part-0071)). An image CD did not change keeps its digest, its
   signature and its SBOM.

No runtime path: this decision runs in CI only, so it has no sequence diagram.

### Consequences
- Every image has a list of what it holds, and the release that runs on prod has it published.
- A new fixable vulnerability shows up in code scanning on `main` as soon as the commit that brings it is merged,
  or within a week for what already runs. The pull request itself does not show it: the price of light PRs.
- A moved base-image tag cannot change a build: a new digest arrives as a Dependabot pull request.
- The `scan` job on `main` takes a few minutes: Grype is built from source (about 2 minutes) and its database is
  downloaded once a day. It runs beside the staging deploy, not before it, while the scan is report-only.
- A broken `deploy/Dockerfile` is found on `main` (the CD `image` job fails, staging keeps its version), not on the
  pull request.
- The scanner versions are pinned by hand: Dependabot does not see them.

### Alternatives considered
- Trivy — also good, but its official action's tags were the ones hijacked; running the binary is possible, and
  Grype plus a separate SBOM tool keeps the SBOM usable by any scanner.
- Docker Scout — needs a Docker account and sends the image metadata to Docker.
- Fail on Critical from day one — with no baseline, the first week of pull requests would be blocked by old findings.
- Scan in the cluster (an operator) — more to run for a one-person team; the weekly job covers what runs.

### Code
- `.github/actions/install-scanners/action.yml`, `.github/actions/grype-db/action.yml`,
  `.github/actions/upload-sarif/action.yml` — install, database, code scanning upload.
- `.github/workflows/cd.yml` (`image`, `scan`), `release.yml` (`sbom`),
  `image-scan.yml` — where the scans run.
- `deploy/k8s/Makefile` (`sbom`, `postgres-sbom`) — SBOM of a pushed image, read from Harbor.
- `.grype.yaml` — exceptions.

### Diagrams
[flow](../../diagrams/flow-0033-supply-chain.svg)

### Roadmap
- [Fail the `scan` job in `cd.yml` on a Critical finding with a fix, from 18 Nov 2026](../../roadmap.md#2-operations).
- Signature check in the cluster as well (admission controller, infra side): [roadmap](../../roadmap.md#2-operations).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0071"></a>

## Part: was ADR 0071 — CI and CD per component, images signed with an OpenBao key, deployed by digest

**Status.** Accepted. **Builds on:** [the part that was ADR 0028](#part-0028), [the part that was ADR 0046](#part-0046), [0034](0034-releases-and-versions.md), [0032](0032-runtime-topology.md)

### Context
Three images now ship together: web, public app and back office ([0032](0032-runtime-topology.md)). Every pull
request still ran every check, and every push to `main` built all three images, also for a change to one page of the
admin app or to a doc. Nothing proved that an image the cluster runs is one that CD built: anyone with push rights on
Harbor could put an image under a tag before CD did, and the deploy named images by tag only. Image signing was an
open supply-chain step ([the part that was ADR 0046](#part-0046)).

### Decision
1. **One map of paths to components.** `.github/scripts/changes.sh` maps the changed paths, first match wins:
   - `frontend/` → frontend job, web image and back office image (the back office image builds the admin web app from
     the same package, lock file and tsconfig, so the folder is not split);
   - `deploy/web.Dockerfile`, `deploy/web/` → web image;
   - `backend/apps/public/`, `deploy/Dockerfile` → backend job, public image;
   - `backend/apps/backoffice/`, `deploy/backoffice.Dockerfile` → backend job, back office image;
   - `deploy/helm/`, `deploy/k8s/`, `deploy/grafana/` → chart job; `deploy/monitoring/` → monitoring and chart;
     `deploy/postgres/` → the Postgres tag check and chart. No app image for these;
   - `docs/eval/` → backend job (its tests read the evaluation sets); other `docs/`, any `*.md`, `LICENSE` → diagrams
     job only; `.claude/`, `.idea/` → nothing;
   - anything else → everything: `api/`, `backend/libs/`, `backend/apps/common-web/`, `backend/build-logic/`, the
     Gradle files, settings, version catalog and wrapper, `.github/`, a new folder. An unknown path runs all, never
     nothing.
   The script has a self-test of fixed cases; the CI job `changes` runs it first.
2. **CI per component on pull requests.** No `paths:` filter on the workflow: it always starts. The job `changes`
   diffs the pull request against its base (plain `git diff`, no third-party action) and each job runs only when its
   part changed. A base that cannot be diffed gives everything. One job, `ci-ok`, runs always and fails when a job
   failed or was cancelled; a skipped job is fine. It is the one check to require on `main` later. `cd.yml` calls
   `ci.yml` with `full: true`: a push to `main` runs every job.
3. **CD per component.** The `image` job maps the push against the commit pushed before (`github.event.before`).
   `make ci-images` gives all three images the new commit tag, always, so they keep one tag (the public pod copies its
   shell from the web image of `image.tag`). A changed component is built and pushed. An unchanged one gets the new
   tag on the image of the commit before, through the Harbor API (the same tag call as `make retag`): same digest, no
   build, and so the same signature. It is carried only when that digest verifies with `cosign.pub`. It is built
   instead when that image is not in Harbor (its run failed), is not signed by our key (the first run after this
   change), or there is no commit before (a new branch). A chart-only or docs-only push builds nothing, tags all three
   and deploys. A tag already in Harbor (a rerun) must verify; an unsigned one fails the run.
4. **Signing: only what the run pushed.** cosign v3.1.4, installed in the job from its release binary checked against
   a pinned SHA-256 (`.github/actions/install-cosign`), never `sigstore/cosign-installer`. The key is an ECDSA P-256
   key in OpenBao Transit (`helper2/transit`, key `helper2-images`), reached as `hashivault://helper2-images`. The key
   never leaves OpenBao; the role `deploy` may only read its public half and ask Transit to sign.
   - `make image` and `make postgres-image` write the digest of every image they push to a file (`PUSHED`), taken
     from the push itself (`podman push --digestfile`) and checked against Harbor right after. `make sign` signs
     those digests and nothing else, so an image someone else put in Harbor never gets our signature. Then every image
     of the tag and the Postgres image must verify: the run fails on any other.
   - cosign gets the token as `VAULT_ADDR` and `VAULT_TOKEN`, set from the `BAO_ADDR` and `BAO_TOKEN` of `bao-login`;
     every other `VAULT_` variable is dropped first, so the runner's own settings or a token helper never win.
   - The job installs its tools first, signs as the last step that needs OpenBao, then revokes the token
     (`bao token revoke -self`) and blanks it; the SBOM uploads come after.
   - Signing runs only in `cd.yml`, on the self-hosted runner, for pushes to `main` and `release/*`; never on a pull
     request.
   - Images pushed before signing (the current Postgres image, older tags) are signed only by hand, on the
     workstation: `make sign-existing TAG=<tag>` shows the digests that have no signature and signs them after a
     confirmation (or `CONFIRM=yes`). It refuses to run in CI, and chart-check fails when a workflow names it.
5. **No transparency log.** `deploy/k8s/cosign-signing-config.json` names no service, so nothing goes to the public
   Rekor log, and verify skips it (`--insecure-ignore-tlog`). The trust is the key: whoever can sign with it is in the
   OpenBao audit log. The public half is committed as `deploy/k8s/cosign.pub` (`make cosign-pub` writes it).
6. **Verification before every deploy.** `deploy.yml`, with its own commit's Makefile and `cosign.pub`, before it
   checks out the target commit: `make verify-images` reads the digest of each image of the tag and of the Postgres
   image from Harbor (a pull-only robot, `ci/harbor-pull`) and runs `cosign verify --key cosign.pub` on each digest.
   One image without a valid signature stops the deploy. After the checkout, the target commit's `make diff`,
   `env-secret` and `deploy` check again themselves (`make verified`, into `<env>/digests.env`, bound to the tag) and
   take the digests only from that check, so a digest set by hand cannot skip it. A commit from before this change
   (its chart or Makefile has no digests) deploys by tag, and only with `rollback: true`.
7. **Deployed by digest.** The chart takes `image.digest`, `backoffice.image.digest`, `web.image.digest` (and the
   existing `postgres.image.digest`) and then names each image `repository:tag@sha256:<digest>`. The tag stays in the
   name, so `deploy.yml` still reads the running tag (it cuts the digest first). Without a digest the chart renders
   by tag, as before: a local or another cluster's install needs no signature.
8. **The map counts a move on both sides.** `changes.sh` diffs with `--no-renames -z`: a file moved out of
   `backend/apps/public` still builds the public image, and a file name with a space or a newline cannot split.

### Consequences
- A pull request that changes one part runs that part's checks; a docs change runs the diagrams only. A path the map
  does not know runs all of CI: slower, never wrong. On a push every job must pass: `ci-ok` takes no skip there.
- A web change builds the web image and the back office image; the public image is tagged, not rebuilt. A push to
  `main` still runs all of CI.
- The cluster runs only images signed by our key, pinned by digest: a tag pushed to Harbor by someone else, or moved,
  is neither signed by CD nor deployed.
- The first CD run after this change builds all three images (the images before are unsigned) and fails on the
  unsigned Postgres image until it is signed once: `make sign-existing TAG=<that run's commit>` (CI.md).
- A rerun of a CD run that pushed images but failed before signing fails too: sign them by hand after checking their
  digests against that run's log, or push a new commit.
- An image keeps the digest it was built with across commits, so the same digest carries many tags. Its SBOM is the
  same; each run still writes one per image, as `release.yml` expects.
- The first deploy with digests restarts Postgres once (its image string changes, not its content).
- Rollback to a commit from before this change: `deploy.yml` checks its images first, and they are unsigned. Sign them
  once from the workstation (`make sign-existing TAG=<commit>`), then deploy with `rollback: true`: that commit's
  Makefile deploys by tag.
- Threats (STRIDE): spoofing and tampering of an image (another push, a moved tag) are blocked: CD signs only the
  digests it pushed, and the deploy checks the signature and pins the digest. A stolen `deploy` token can sign, but
  only on the private network and only until the job revokes it, and OpenBao logs every signature. The deploy roles
  cannot sign. The public key in the repo is no secret.
- Harbor must accept cosign's signature: a referrer manifest of the image, or, without the referrers API, a tag
  `sha256-<digest>`. That tag is created once per digest and never updated (point 4), so the tag-immutability rule
  does not block it.

### Alternatives considered
- `dorny/paths-filter` or a workflow-level `paths:` — a third-party action in every run, or a skipped workflow that
  leaves a required check waiting forever.
- Build all three on every push — simple, but a docs change costs three builds and an admin change rebuilds the
  public image.
- Keyless signing (Fulcio and Rekor, the GitHub OIDC identity) — no key to keep, but every signature and our image
  names go to a public log, and the deploy needs the public Sigstore services to verify.
- A key file in OpenBao KV — the private key would sit on the runner during the job; Transit never hands it out.
- Verify in the cluster with an admission controller (Kyverno, the Sigstore policy controller) — stronger, but a
  controller to run and a dependency on one cluster; the deploy stays generic (roadmap, infra side).

### AI architecture check (delta)
| # | Layer | Status |
|---|---|---|
| 12 | Deployment | covered: per-component build, one tag for the three images, verify then deploy by digest; Helm rollback unchanged |
| 13 | Security & Identity | covered: signing key in OpenBao Transit, sign rights for the role `deploy` only and revoked after the signing step, CD signs only what it pushed, pull-only robot for verification |
| 15 | Cost & Reliability | covered: fewer builds; a failed run's images are rebuilt by the next run, never assumed |

Layers 1 to 11 and 14 do not change: no model, prompt, data or user-facing path.

### Code
- `.github/scripts/changes.sh` — the map and its self-test.
- `.github/workflows/ci.yml` (`changes`, the `if:` of each job, `ci-ok`), `cd.yml` (`image`), `deploy.yml` (Resolve
  tag).
- `.github/actions/install-cosign/action.yml` — cosign, pinned by SHA-256.
- `deploy/k8s/Makefile` — `ci-images`, `sign`, `sign-existing`, `verify-images`, `verified`, `cosign-pub`,
  `chart-check-digests`, `PUSHED` in `image` and `postgres-image`, the digests in `HELM_ARGS`.
- `deploy/k8s/cosign.pub`, `deploy/k8s/cosign-signing-config.json`.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.atDigest`), `values.yaml` (the `digest` values).
- `deploy/k8s/CI.md` — the OpenBao Transit key, the policies and the pull-only robot.

### Diagrams
[flow](../../diagrams/flow-0033-ci-cd-per-component-and-image-signing.svg) · [sequence](../../diagrams/sequence-0033-ci-cd-per-component-and-image-signing.svg)

### Roadmap
- Make `ci-ok` a required status check on `main`: [roadmap](../../roadmap.md#2-operations).
- Verify signatures in the cluster too (admission controller, infra side): [roadmap](../../roadmap.md#2-operations).
