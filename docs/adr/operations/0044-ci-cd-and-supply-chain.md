# 0044 — CI/CD and supply chain

**Depends on:** [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0009](../foundation/0009-observability.md), [0043](0043-runtime-topology-helm-chart-three-deployables.md), [0045](0045-releases-and-versions.md), [0047](0047-code-format-and-dead-code-audit.md).

## Context

The repository is public. The cluster's application programming interface (API) and the image registry (Harbor) are
reachable only on a private network. Every change on `main` should reach staging without manual steps. Prod should run
only released versions, and only after a person approves: `main` may carry unfinished work that staging shows and
users must not see yet. Code from a fork must never run where deploy credentials exist, and GitHub must hold no
long-lived secret.

Three images ship together: web, public app and back office ([0043](0043-runtime-topology-helm-chart-three-deployables.md)). A change to one page
of the admin app or to a doc should not run every check and build all three. The images are built from public base
images and ship hundreds of Java and JavaScript libraries: we must know what an image holds and when one of its parts
has a known vulnerability. A base-image tag can move to new content without a change in our repository. A scanner run
as a third-party GitHub action is itself a risk: in March 2026 the tags of a widely used scanner action were moved to
code that stole CI secrets (CVE-2026-33634). And the cluster must run only images CD built: anyone with push rights on
Harbor could put an image under a tag first.

## Decision

### The pipeline: CI on pull requests, staging on main, prod on a release
1. **Continuous integration (CI) on every pull request (PR)** — `ci.yml`, GitHub-hosted runners:
   - `changes`: the self-tests of `.github/scripts/changes.sh` and `.github/scripts/ci-ok.sh`, the ADR check
     (`scripts/adr-check.py`, its self-test first: every ADR reference in the repository names a record or a diagram
     that exists, and no file under `docs/adr` holds one of the words that tell a decision's history,
     `HISTORY_WORDS` in the script), every script test in the tree (`scripts/*.test.mjs`: the evaluation script's
     stop rule, `eval-lib.test.mjs`, and the test that the industry events seed is what its script writes from the
     research file, `industry-events-seed.test.mjs`, [0012](../scout/0012-scout-collectors-and-events.md)), the
     check that no released migration changed (`.github/scripts/released-migrations.sh`, its self-test first,
     [0004](../foundation/0004-persistence-and-the-schema.md) §5), then the map of what the change touches (§8);
   - frontend, jobs side by side, each with `npm ci` and the generated API types: `frontend-build` (build,
     `check:bundle`), `frontend-check` (`format:check` with Prettier, lint, `knip`, typecheck, `npm audit`,
     [0047](0047-code-format-and-dead-code-audit.md)) and `frontend-test`, Vitest in three jobs named
     after the folders they test: `search, guide`; `home, resource, pages, admin`; `lib, styles, shell, the rest`. The
     last one runs every test the first two do not, so a new folder is tested there without a change to `ci.yml`.
     A group whose folders hold no test passes (`--passWithNoTests`).
     What the images pack waits only for the build; the image still waits for every check, because CD runs the image
     after the whole of CI;
   - backend, two jobs side by side: `backend-build` compiles every source set (`classes testClasses`, and
     `gatlingClasses` when `backend/loadtest` is in the tree), `backend-check` runs `./gradlew spotlessCheck test`
     ([0047](0047-code-format-and-dead-code-audit.md)), tests with Testcontainers (PostgreSQL and Redis).
     What the images pack waits only for `backend-build`. `backend/gradle.properties` turns on parallel project
     execution and the build cache: the modules' test tasks run side by side, and a module whose inputs did not
     change takes its results from the cache that pushes to `main` fill;
   - chart: `make -C deploy/k8s chart-check` (`helm lint --strict`, `helm template | kubeconform -strict` for
     staging, prod, and prod with the off-site backup on; the self-test of `check-release.sh`, the check after a
     deploy, §3);
   - monitoring: `promtool check rules` and `promtool test rules` on `deploy/monitoring`. promtool comes from the
     official Prometheus image, pinned by its index digest
     (`docker.io/prom/prometheus:vX.Y.Z@sha256:<digest>`, `PROMETHEUS_IMAGE` in `ci.yml`), run with no network and
     the workspace read-only. `go install` cannot build promtool (the Prometheus module has replace directives), and
     the GitHub release tarball answered 504 for hours on 8 Nov 2026. The version and the digest move together, by
     hand: Dependabot does not see this pin;
   - PRs only: a check that `postgres.image.tag` changed when `deploy/postgres` changed (git only, no build);
   - the D2 diagrams re-rendered with d2 v0.9.0 and compared with the committed SVG files. It runs on pushes too, so
     the d2 binary is cached on `main`, where every pull request can restore it (§12).
   - No image build: PRs stay light. Images are built, pushed and scanned only on `main` and on release branches
     (§2), and a release adds its version tag to the images of its commit (§3).
2. **Continuous delivery (CD) on every push to `main`** (and to a `release/*` branch, image only,
   [0045](0045-releases-and-versions.md)) — `cd.yml`: all of CI runs again (`full: true`), then the `image` job on the
   self-hosted runner runs `make postgres-image` and `make ci-images`: the images of the components that changed are
   built and pushed, the others get the new tag on their previous digest (§10), and `make sign` signs every new digest
   (§17). The image tag is the first 12 characters of the commit, built from `git archive`. A `scan` job on a
   GitHub-hosted runner then scans the SBOMs of the images, side by side with the staging deploy (§15).
   The images pack what CI built and tested: in a CD run the `frontend-build` job uploads `dist` and `dist-admin`, and a
   `package` job (after `frontend-build` and `backend-build`) puts `dist-admin` into the back office resources, runs both `bootJar`
   tasks and uploads the artifact `prebuilt` (the two jars and the web bundle, kept one day). The `image` job downloads
   it and runs `make ci-images PREBUILT=<dir>`: each Dockerfile takes `--build-arg BUILD=prebuilt` and copies the jar
   or the bundle in, with no Gradle or npm build on the self-hosted runner. The web image still checks what the
   bundle holds, and `nginx -t` still runs. Without `PREBUILT` (`make image` on a workstation) the Dockerfiles build
   from source, as before.
3. **Staging on every push, prod on a release.** `deploy.yml` (reusable, `workflow_call` only) checks the signature
   of every image (`make verify-images`, §19), then runs `make diff`, `make deploy` (images by digest, §20) and
   `make status` for one GitHub environment. **A release that is not healthy is rolled back, and the job fails.**
   The check is `deploy/k8s/check-release.sh`: the Helm release is `deployed`, every Deployment and StatefulSet is
   rolled out, the migrate Job of this revision completed, and the public page (`/`) and one API read
   (`/api/v1/sources`) answer 200 through the edge, within 5 tries.
   - `make deploy` reads the revision Helm marks `deployed` before the upgrade, upgrades, then runs the check. When
     it fails, `helm rollback` goes to that recorded revision (never "the one before", which can be a failed one),
     the check runs once more on the rolled-back release, and the job fails in every case. It says which case:
     `ROLLED BACK` (the old revision is healthy), `ROLLED BACK, STILL NOT HEALTHY` (the fault may be outside the
     release: the edge, the database), `ROLLBACK FAILED`, or `NOT ROLLED BACK` (a first install has nothing to go
     back to). One rollback, one check after it, nothing more: no loop. The check reads the migrate Job the release's
     manifest names: after a rollback that is the Job of the revision gone back to (`helper2-migrate-<that revision>`),
     not one named after the new revision number, and the rollback waits for it (`--wait-for-jobs`).
   - The same rule on staging and on prod. On staging the failed job fails the CD run, so that commit cannot reach
     prod.
   - The schema is not rolled back. The migrate Job of the failed revision has run; the rollback's own Job runs the
     older image's step, which finds nothing to do ([0004](../foundation/0004-persistence-and-the-schema.md)
     §10), and the older apps accept a newer versioned migration (Flyway ignores it) and run their own seeds again. So
     the rollback is safe only when the older code works on the newer schema: the same condition every rolling
     upgrade already needs, because the old pods serve until the new ones are ready. A migration that drops or
     renames what the older code uses is not safe to roll back, automatically or by hand
     (see Known limits).
   - **One switch, for one deploy.** `make deploy ROLLBACK_UNHEALTHY=false` keeps the check and the failure but
     skips the rollback (`NOT ROLLED BACK: rollback switched off for this deploy`). It exists for a prod window in
     which the release before cannot run on the new database, so a rollback would start it on a schema it does not
     know (`deploy/k8s/RUNBOOK-reset-cutover.md`, step 7.4). Only the make command line
     sets it: the Makefile ignores the environment (`$(origin)`), and no chart value or `app.env` key exists for it.
     One workflow path passes it: the manual run of `cd.yml` has the input `keep-unhealthy` (a boolean, default
     false), `deploy.yml` takes it and runs `make deploy ROLLBACK_UNHEALTHY=false`, with a warning and a line in the
     run summary. A push to `main` and a release never set it: `release.yml` has no such input, so a release
     always rolls back. A run with the switch therefore never runs beside a release: `deploy.yml` allows one deploy
     per environment at a time (`deploy-<env>`, §7), and a release run that waits for the `prod` approval holds that
     slot, so a manual run would queue behind it. Nothing stored switches it (no repository or environment variable): the choice
     is made in the form of one run and ends with it. chart-check fails on any other workflow line that names the
     variable or the input, and on a default other than false. Helm's own rollback of a failed upgrade is not
     switched off.
   - `make status` runs the same check with no rollback: it is safe on any day, for the operator and as the job's
     last step.

   `cd.yml` calls `deploy.yml` for `staging` after each push to `main`, and never for prod. `release.yml`, run by hand
   from `main`, calls it for `prod` with the head of a release branch `release/YYYY.N` and its version `YYYY.N.P`, once its
   checks passed ([0045](0045-releases-and-versions.md)): the commit the branch was cut from ran on staging, and the
   CD run of the branch passed. After the approval the deploy job runs a canary of the commit and its smoke test,
   adds the tag `YYYY.N.P` to its images in Harbor and deploys that version; the Git tag and the GitHub release come
   only after. The `prod` environment has a required reviewer and allows branch `main` only.
4. **OpenBao login with OpenID Connect (OIDC).** `.github/actions/bao-login` asks GitHub for the job's OIDC token
   (audience = repo variable `BAO_AUDIENCE`, equal to the role's `bound_audiences`) and logs in at
   `auth/helper2/jwt` with a role bound to the repository and to the branch or environment: `deploy` (reads
   `ci/harbor`, signs with the Transit key `helper2-images`), `deploy-staging` (reads `ci/staging` and
   `ci/harbor-pull`), `deploy-prod` (reads `ci/prod`, `ci/harbor-pull`, and `ci/harbor` to add a release's version
   tag, [0045](0045-releases-and-versions.md)). The prod role can log in only after the approval.
5. **Tag checks in the deploy job.** The tag must be 12 hex characters with no branch or tag of that name, or a
   version `YYYY.N.P` whose tag `vYYYY.N.P` exists. It must resolve to a commit on `main`, or, as a release version
   only, on a `release/*` branch; the chart is checked out from that same commit. A tag older than what the environment
   runs needs `rollback: true`. Manual runs take an environment, a tag (redeploy, rollback) and `keep-unhealthy` (§3). The environment defaults
   to staging, so a run started without a thought lands where it does no harm; prod with an empty tag promotes what
   staging runs now. On prod the deploy job also reads the CD run of the commit from the GitHub API, for a release and
   for a manual run: the run must have finished and passed. A commit whose run failed, was cancelled, still runs or
   does not exist is refused. `rollback: true` skips this check with a warning: a rollback must not wait for a run.
6. **One runner per job.** The self-hosted runner registers a just-in-time (JIT) runner for one job and wipes its
   workspace after it. It holds no kubeconfig and no OpenBao token of its own. `cd.yml` has no `pull_request` trigger,
   and `ci.yml` never uses `pull_request_target`. Third-party actions are pinned to commit hashes (SHA).
7. **Deploys are not canceled.** `concurrency: deploy-<env>` with `cancel-in-progress: false`, because an interrupted
   `helm upgrade --wait` leaves the release blocked. A release found `pending-*` is recovered first
   (`make release-ready`, and on staging `make rollback`): a rollback to the newest revision Helm marked deployed,
   never to the one just before the current, which after an interrupted rollback is the failed upgrade. The job's
   budget (45 min) covers that recovery, the upgrade and one rollback, 12 min each: Helm's own when the upgrade
   fails, or the one after a failed check (§3), never both.
   A newer push to the same branch cancels the build of the older one: the `ci`, `image` and `scan` jobs of `cd.yml`
   share `concurrency: cd-build-<ref>` with `cancel-in-progress: true`. Only the newest commit reaches staging, and the
   runner builds it sooner. The `staging` job is not in that group, so a deploy already running finishes. The
   cancelled commit has no staging deploy and so no release; its images may be missing or unsigned in Harbor, and
   the next run builds what it cannot carry (§10).
   The Postgres image is the exception: its tag is shared by every commit and `make postgres-image` skips a tag that
   is in Harbor, so a cancel before `make sign` would leave it unsigned and stop every later run at `verify-images`.
   A step with `if: always()` right after the push runs `make sign-pushed` (the digests of `PUSHED`, nothing else), so
   the image is signed even when the run is cancelled.
   A re-run of an older run joins the same group and cancels the newer build: re-run the newest run, not an older one.

### Per component: a change runs its own checks and builds its own images
8. **One map of paths to components.** `.github/scripts/changes.sh` maps the changed paths, first match wins:
   - `docs/eval/` → backend jobs (its tests read the evaluation sets) and diagrams;
   - `frontend/` → frontend jobs, web image and back office image (the back office image builds the admin web app from
     the same package, lock file and tsconfig, so the folder is not split);
   - `deploy/web.Dockerfile`, `deploy/web/` → web image;
   - `backend/apps/public/`, `deploy/Dockerfile` → backend jobs, public image;
   - `backend/apps/backoffice/`, `deploy/backoffice.Dockerfile` → backend jobs, back office image;
   - `docs/design/system/tokens.json` → frontend jobs (the token test reads it) and diagrams;
   - `backend/loadtest/` → backend jobs (`backend-build` compiles the Gatling simulations), no image;
   - any other `*.md`, `docs/`, `LICENSE` → diagrams job only; `.claude/`, `.idea/` → nothing;
   - `deploy/postgres/` → the Postgres tag check and chart; `deploy/monitoring/` → monitoring and chart;
     `deploy/restore/` → backend jobs (the restore script, tested by `libs/schema`); `deploy/helm/`,
     `deploy/k8s/`, `deploy/grafana/` → chart only. No app image for these;
   - anything else → everything: `api/`, `backend/libs/` (`web-contract` too: both apps serve it),
     `backend/build-logic/`, the Gradle files, settings, version catalog and wrapper, `.github/`, `scripts/`, a new
     folder. An unknown path runs all, never nothing.

   A part is selected only when it is in the tree. Each part names the files it needs (`needs` in `changes.sh`):
   `frontend/package.json`, `backend/gradlew`, `deploy/k8s/Makefile` for the chart,
   `deploy/monitoring/helper2-rules.yaml`, `deploy/postgres/Dockerfile` with `deploy/helm/helper2/values.yaml` for
   the Postgres tag check, `docs/diagrams/render.sh`, and the Dockerfile of each image. A change to `.github/` in a
   tree with no `frontend/` runs no frontend job, and the pull request that adds `frontend/package.json` runs them,
   with no change to `ci.yml`.

   The script has a self-test of fixed cases; the CI job `changes` runs it first.
9. **CI per component on pull requests.** No `paths:` filter on the workflow: it always starts. The job `changes`
   diffs the pull request against its base (plain `git diff`, no third-party action) and each job runs only when its
   part changed. A base that cannot be diffed gives everything. One job, `ci-ok`, runs always and compares each
   job with the map (`.github/scripts/ci-ok.sh`, with a self-test): a job whose part is selected must succeed, and a
   job whose part is not selected must be skipped. So a selected job that failed, was cancelled or was skipped fails
   the check, and so does a job the script has no rule for. On a push (`full: true`) every part in the tree is
   selected; `postgres-tag` is selected on pull requests only. `ci-ok` is the one check to require on `main` and `release/*`. On `main` it is required
   by the ruleset "main: pull requests only", and a pull request must be up to date with `main` before it merges:
   two pull requests that are green alone can break `main` together.
10. **CD per component.** The `image` job maps the push against the commit pushed before (`github.event.before`).
    `make ci-images` gives all three images the new commit tag, always, so they keep one tag (the public pod copies
    its shell from the web image of `image.tag`). A changed component is built and pushed. An unchanged one gets the
    new tag on the image of the commit before, through the Harbor API (the same tag call as `make retag`): same
    digest, no build, and so the same signature. It is carried only when that digest verifies with `cosign.pub`. It is
    built instead when that image is not in Harbor (its run failed), is not signed by our key, or there is no commit
    before (a new branch). A chart-only or docs-only push builds nothing, tags all three and deploys. A tag already in
    Harbor (a rerun) must verify; an unsigned one fails the run.
11. **The map counts a move on both sides.** `changes.sh` diffs with `--no-renames -z`: a file moved out of
    `backend/apps/public` still builds the public image, and a file name with a space or a newline cannot split.

### Supply chain: digests, an SBOM per image, a scan that stops a Critical with a fix
12. **Digests.** Base images and the images the chart runs are pinned as `image:tag@sha256:<digest>`; Dependabot
    (`docker` ecosystem) moves tag and digest together. Actions are pinned by commit SHA. The tools CI installs itself
    are pinned too: promtool's image by digest (§1), Syft and cosign by SHA-256 (§13, §17), Grype, helm, kubeconform
    and d2 by exact version through the Go checksum database. Those four are built once per version
    (`.github/actions/go-tool`): `go install` on a cache miss, then the binary is cached under a key of tool, version,
    Go version, OS and architecture, so a new version or toolchain builds it again through the checksum database.
    The binary is saved right after the build, not in a post step: a post step nested in another composite action
    (`install-scanners`) can hang the job.
13. **Scanner: Syft and Grype (Anchore, Apache-2.0).** Syft writes a CycloneDX SBOM of an image; Grype scans that SBOM.
    Both are installed in the job, never as a third-party action (`.github/actions/install-scanners`): Syft from its
    GitHub release tarball checked against a pinned SHA-256, Grype with `go install` of an exact version, checked by
    the Go checksum database and cached per version (§12). Grype's database is cached per database build
    (`.github/actions/grype-db`): the cache key is the SHA-256 of the latest database in Grype's listing, so a hit is
    always the current database and a new build is downloaded once, then saved right after the download, not in a post
    step. The listing and the download are each retried once; the scan itself never downloads the database again.
14. **Where.**
    - Pull requests build no image and scan nothing: they stay light (§1).
    - Every push to `main` or `release/*` (`cd.yml`): on the self-hosted runner, `make sbom`, `backoffice-sbom`,
      `web-sbom` and `postgres-sbom` write the SBOMs of the three images pushed to Harbor and of the Postgres image of
      `postgres.image.tag`, kept 90 days as artifacts of the run (`sbom`, `sbom-backoffice`, `sbom-web`,
      `sbom-postgres`). Then the `scan` job, on a GitHub-hosted runner, downloads them and runs Grype: findings with a
      fix (`--only-fixed`) in the job log and in GitHub code scanning (SARIF, categories `image/app`,
      `image/backoffice`, `image/web` and `image/postgres`). It reads artifacts only, never Harbor.
    - A release (`release.yml`): the SBOMs of the CD run of its release branch are attached to the GitHub release as
      `helper2-vYYYY.N.P.cdx.json`, `helper2-backoffice-vYYYY.N.P.cdx.json`, `helper2-web-vYYYY.N.P.cdx.json` and
      `helper2-postgres-vYYYY.N.P.cdx.json` (the Postgres image of `postgres.image.tag`). They are
      public: they list packages and versions, no data.
    - Weekly (`image-scan.yml`, Monday): Grype with a fresh database against the SBOMs of the latest release, so a CVE
      published after a release is found without a new build. It covers all four images. Categories `release/app`,
      `release/backoffice`, `release/web` and `release/postgres`, on the release tag.
15. **A Critical finding with a fix stops the release, not staging.** The `scan` job scans every image and uploads
    its findings first, then fails when any image has a Critical finding that has a fix (`--only-fixed --fail-on
    critical`; Grype exits 2 for it, and any other exit is an error of the scan itself). The staging deploy does not
    wait for it: both jobs need only the `image` job and run side by side. They never compete for a runner: the scan
    is on a GitHub-hosted one, the deploy on the self-hosted one. So such an image can reach staging, and never prod:
    a failed `scan` job fails the CD run, and prod needs the CD run of its commit to have succeeded. A release checks
    it (`staged`), and the deploy job checks it again on every prod deploy, a manual one too (§5). The weekly
    scan stays report-only: it reads what already runs, and has nothing to stop.
16. **Exceptions** live in `.grype.yaml`, empty today: each one names the vulnerability, the package if it can, the
    reason and a review date.

### Signed images, deployed by digest
17. **Signing: only what the run pushed.** cosign v3.1.4, installed in the job from its GitHub release binary checked
    against a pinned SHA-256 (`.github/actions/install-cosign`), never `sigstore/cosign-installer`. The key is an
    ECDSA P-256 key in OpenBao Transit (`helper2/transit`, key `helper2-images`), reached as
    `hashivault://helper2-images`. The key never leaves OpenBao; the role `deploy` may only read its public half and
    ask Transit to sign. Every image CD pushes is signed, the Postgres image too.
    - `make image` and `make postgres-image` write the digest of every image they push to a file (`PUSHED`), taken
      from the push itself (`podman push --digestfile`) and checked against Harbor right after. `make sign` signs
      those digests and nothing else, so an image someone else put in Harbor never gets our signature. Then every
      image of the tag and the Postgres image must verify: the run fails on any other.
    - cosign gets the token as `VAULT_ADDR` and `VAULT_TOKEN`, set from the `BAO_ADDR` and `BAO_TOKEN` of
      `bao-login`; every other `VAULT_` variable is dropped first, so the runner's own settings or a token helper
      never win.
    - The job installs its tools first, signs as the last step that needs OpenBao, then revokes the token
      (`bao token revoke -self`, also when a step failed) and blanks it; the SBOM uploads come after.
    - Signing runs only in `cd.yml`, on the self-hosted runner, for pushes to `main` and `release/*`; never on a pull
      request.
    - Images pushed without a signature are signed only by hand, on the
      workstation: `make sign-existing TAG=<tag>` shows the digests that have no signature and signs them after a
      confirmation (or `CONFIRM=yes`). It refuses to run in CI, and chart-check fails when a workflow names it.
18. **No transparency log.** `deploy/k8s/cosign-signing-config.json` names no service, so nothing goes to the public
    Rekor log, and verify skips it (`--insecure-ignore-tlog`). The trust is the key: whoever can sign with it is in
    the OpenBao audit log. The public half is committed as `deploy/k8s/cosign.pub` (`make cosign-pub` writes it).
19. **Verification before every deploy.** `deploy.yml`, with its own commit's Makefile and `cosign.pub`, before it
    checks out the target commit: `make verify-images` reads the digest of each image of the tag and of the Postgres
    image from Harbor (a pull-only robot, `ci/harbor-pull`) and runs `cosign verify --key cosign.pub` on each digest.
    One image without a valid signature stops the deploy. After the checkout, the target commit's `make diff`,
    `env-secret` and `deploy` check again themselves (`make verified`, into `<env>/digests.env`, bound to the tag)
    and take the digests only from that check, so a digest set by hand cannot skip it. A commit whose chart or
    Makefile has no digests deploys by tag, and only with `rollback: true`.
20. **Deployed by digest.** The chart takes `image.digest`, `backoffice.image.digest`, `web.image.digest` and
    `postgres.image.digest`, and then names each image `repository:tag@sha256:<digest>` (`helper2.atDigest`). The tag
    stays in the name, so `deploy.yml` still reads the running tag (it cuts the digest first). Without a digest the
    chart renders by tag: a local or another cluster's install needs no signature.

### GitHub settings as files
21. **The repository's settings are in `.github/settings`.** JSON bodies for the GitHub API (the repository, its
    topics, the three rulesets, the environments, the workflow permissions, fork pull request approval, the OIDC
    subject) and `variables.env`. [docs/recreate.md](../../recreate.md) applies them in order, on a new repository or
    after a change. A setting changes by pull request, like code. Squash merges only; every pull request from an
    outside collaborator waits for an approval before its workflows run; secret scanning with push protection;
    Dependabot alerts and security-update pull requests beside the weekly version updates and the Grype scan.

### Code

- `.github/workflows/ci.yml` (`changes`, the `if:` of each job, `monitoring` with `PROMETHEUS_IMAGE`, `ci-ok`),
  `cd.yml` (`image`, `scan`, `staging`, `manual`), `deploy.yml` (Resolve tag, verify, canary, deploy), `release.yml`
  (the prod deploy of a release, `publish` with the SBOMs), `image-scan.yml` (the weekly scan).
- `.github/scripts/changes.sh` — the map of paths to components, the files each part needs, and its self-test.
- `.github/scripts/ci-ok.sh` — the verdict of `ci-ok`: each job against the map, and its self-test.
- `scripts/adr-check.py` — the ADR references and the history words, and its self-test.
- `.github/scripts/released-migrations.sh` — no released migration changes; before the first release, V1 only.
- `.github/actions/bao-login/action.yml` — OIDC login to OpenBao.
- `.github/actions/install-scanners/action.yml`, `grype-db/action.yml`, `upload-sarif/action.yml`,
  `install-cosign/action.yml` — Syft and Grype, the database cached per build, the code scanning upload, cosign.
- `.github/actions/go-tool/action.yml` — helm, kubeconform, d2 and Grype: `go install` once per version, then cached.
- `.github/dependabot.yml` — base images and actions by digest and SHA.
- `.github/settings/` — the repository's GitHub settings; `docs/recreate.md` applies them.
- `.grype.yaml` — exceptions.
- `deploy/k8s/Makefile` — `image`, `ci-images`, `postgres-image` (`PUSHED`), `sbom`, `backoffice-sbom`, `web-sbom`,
  `postgres-sbom`, `sign`, `sign-existing`, `verify-images`, `verified`, `cosign-pub`, `chart-check`,
  `chart-check-digests`, `chart-check-status`, `release-ready`, `diff`, `deploy`, `status`, `rollback`.
- `deploy/k8s/check-release.sh` — the check after a deploy, the rollback to the revision deployed before
  (`make deploy`), the check alone (`make status`), and its self-test.
- `deploy/k8s/cosign.pub`, `deploy/k8s/cosign-signing-config.json`.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.atDigest`), `values.yaml` (the `digest` values).
- `deploy/k8s/CI.md` — one-time setup of GitHub, the runner and OpenBao: the Transit key, the policies and the
  pull-only robot.
- The platform repo (outside this repo) — the `deployer` Role, the prod deployer's read of staging's Deployment, the
  OpenBao roles and the Transit key.

## Alternatives considered

- GitHub-hosted runners for deploys — they cannot reach the private network without opening the API or Harbor.
- GitHub Actions secrets for the kubeconfig and app env — long-lived values in GitHub; OIDC avoids them.
- Argo CD or Flux (GitOps) — a controller in the cluster and a second repository of state for one app.
- Build on every deploy — prod would run a fresh build, not the image staging tested.
- `dorny/paths-filter` or a workflow-level `paths:` — a third-party action in every run, or a skipped workflow that
  leaves a required check waiting forever.
- Build all three images on every push — simple, but a docs change costs three builds and an admin change rebuilds the
  public image.
- Trivy — also good, but its official action's tags were the ones hijacked; running the binary is possible, and Grype
  plus a separate SBOM tool keeps the SBOM usable by any scanner.
- Docker Scout — needs a Docker account and sends the image metadata to Docker.
- Fail on Critical with no baseline — old findings would block the first week of pull requests. The gate is on
  because a baseline of reports exists.
- Fail on High as well — Debian `apt` and `libpcre2` findings would block every deploy for packages the app does not
  call; High findings stay in code scanning.
- Scan in the cluster (an operator) — more to run for a one-person team; the weekly job covers what runs.
- promtool from `go install` or the release tarball — the first cannot build it, the second failed for hours; the
  official image, pinned by digest, has the same binary.
- helm, kubeconform, d2 and Grype from release tarballs checked against a pinned SHA-256 — as fast as the cache, but
  four more digests to move by hand, and GitHub release downloads have failed for hours; the cached `go install`
  keeps the checksum database as the pin.
- Grype's database cached per day — a day's cache can hold the database from before that day's build, and then every
  run restores it and downloads the new one anyway.
- A scanner image in Harbor holding Grype and its database — the `scan` job runs on a GitHub-hosted runner, which
  cannot reach Harbor without opening it to the internet, and the self-hosted runner must stay free for the deploy;
  the database in the image would also need a daily rebuild, push and signature, and the pull is as large as the
  cache.
- `setup-go` with its module and build cache — caches every module the tools need, hundreds of MB, to save the build
  of four binaries.
- Keyless signing (Fulcio and Rekor, the GitHub OIDC identity) — no key to keep, but every signature and our image
  names go to a public log, and the deploy needs the public Sigstore services to verify.
- A key file in OpenBao KV — the private key would sit on the runner during the job; Transit never hands it out.
- Verify in the cluster with an admission controller (Kyverno, the Sigstore policy controller) — stronger, but a
  controller to run and a dependency on one cluster; the deploy stays generic (open point, infra side).

## Consequences

- A green PR has run the same checks that guard `main`; CD repeats every CI job before it builds anything.
- A pull request that changes one part runs that part's checks; a docs change runs the diagrams only. A path the map
  does not know runs all of CI: slower, never wrong.
- A web change builds the web image and the back office image; the public image is tagged, not rebuilt.
- Pipeline and workstation run the same `Makefile` targets, so the deploy is not described twice.
- Prod cannot get an untested image: a release is a commit of `main` that staging ran, plus its version; its images
  run first as a canary on prod, behind a header, and the version tag goes on those images, never on a rebuild. The
  exception is a fix release, whose fix skips staging ([0045](0045-releases-and-versions.md)).
- Prod changes only on a release (or a manual run). Work in progress can live on `main` and on staging, with no feature
  flag to keep it from users. A push to `main` never waits for a prod approval.
- The cluster runs only images signed by our key, pinned by digest: a tag pushed to Harbor by someone else, or moved,
  is neither signed by CD nor deployed.
- A rerun of a CD run that pushed images but failed before signing fails too: sign them by hand after checking their
  digests against that run's log, or push a new commit.
- An image keeps the digest it was built with across commits, so the same digest carries many tags. Its SBOM is the
  same; each run still writes one per image, as `release.yml` expects.
- The first deploy by digest of an environment restarts Postgres once (its image string changes, not its content).
- A rollback to a release whose images carry no signature needs them signed once from the workstation
  (`make sign-existing TAG=<version>`) and `rollback: true`: it deploys by tag.
- Every image has a list of what it holds, and the release that runs on prod has it published.
- A new fixable vulnerability shows up in code scanning as soon as the commit that brings it reaches `main`, or within
  a week for what already runs. The pull request itself does not show it: the price of light PRs.
- A new Critical with a fix in a base image or a library fails the CD run: staging gets the commit, and no release of
  it is possible until it is fixed, or until `.grype.yaml` holds an exception with its reason and a review date.
- A manual prod deploy (`cd.yml`, `workflow_dispatch`) reads the CD run of the commit too. Promoting what staging runs
  while its scan still runs, or after it failed, is refused. Only `rollback: true` deploys such a commit.
- A commit whose CD run was cancelled cannot go to prod until the run is started again and passes.
- A moved base-image tag cannot change a build: a new digest arrives as a Dependabot pull request.
- A broken Dockerfile is found on `main` (the CD `image` job fails, staging keeps its version), not on the pull
  request.
- The `scan` job takes about a minute: Grype is built from source (about 2.5 minutes) once per version, and its
  database (about 500 MB unpacked) is downloaded once per database build, about a minute each time. Staging does not
  wait for it.
- The versions of Syft, Grype, cosign and the Prometheus image are pinned by hand: Dependabot does not see them.
- Harbor must accept cosign's signature: a referrer manifest of the image, or, without the referrers API, a tag
  `sha256-<digest>`. That tag is created once per digest and never updated (§17), so the tag-immutability rule does
  not block it.

### Security and privacy (STRIDE)

- **Spoofing, tampering** of an image (another push, a moved tag): blocked. CD signs only the digests it pushed, and
  the deploy checks the signature and pins the digest (§17, §19, §20). Base images and tools are pinned by digest or
  SHA-256 (§12).
- **Elevation of privilege** from fork code: no self-hosted job runs on a pull request (§6); GitHub holds no
  long-lived secret, and the OpenBao roles are bound to the repository and to the branch or environment (§4).
- **Information disclosure**: a stolen `deploy` token can sign, but only on the private network and only until the job
  revokes it, and OpenBao logs every signature. The deploy roles cannot sign. The public key and the published SBOMs
  are no secret.
- **Repudiation**: every signature is in the OpenBao audit log; every prod deploy has a named reviewer.
- **Denial of service** of the pipeline (Harbor or a release download down): the run fails and nothing is deployed;
  what runs keeps running.

### AI architecture check

| # | Layer | Status |
|---|---|---|
| 12 | Deployment | covered: per-component build, one tag for the three images, verify then deploy by digest; Helm rollback unchanged |
| 13 | Security & Identity | covered: signing key in OpenBao Transit, sign rights for the role `deploy` only and revoked after the signing step, CD signs only what it pushed, pull-only robot for verification, OIDC instead of stored secrets |
| 15 | Cost & Reliability | covered: fewer builds; a failed run's images are rebuilt by the next run, never assumed; a Critical with a fix never reaches prod, by a release or by a manual run (a rollback aside) |

Layers 1 to 11 and 14 do not change: no model, prompt, data or user-facing path.

### Known limits

- No browser end-to-end tests: frontend tests run in jsdom only, also in CI.
- A deploy that is not healthy after the upgrade is rolled back by itself, but the schema is not: the new release's migrations stay. The older code must work on the newer schema, as in every rolling upgrade; no test checks it (the previous release's schema contract against the new migrations). A migration that drops or renames what the older code uses needs two releases (add, then remove), or the rollback ends in `ROLLBACK FAILED` or in an older app with errors. Until the v2026.11.1 tag, a rollback across an edit of `V1__schema.sql` passes on staging only through the catch-up. What the new release wrote between the upgrade and the rollback stays. The check sees the public host only, not the admin host.
- The scanner versions (Syft, Grype in `.github/actions/install-scanners`), cosign and the Prometheus image of the `monitoring` job are pinned by hand: Dependabot does not see them. Grype is built from source in each scan job (about 2 minutes); the weekly scan reads only the latest release, not an older one still on prod after a rollback.
- The web and Postgres images take Alpine's fixed zlib with one `apk upgrade` line (CVE-2026-4738); drop it once the base image's own zlib is 1.4 or later (`deploy/web.Dockerfile`, `deploy/postgres/Dockerfile`; the second needs a new Postgres tag).

### Open points

- `ci-ok` is not a required status check on `release/*`.
- Signatures are not verified in the cluster (admission controller, infra side).

## Diagrams

The pipeline: [flow](../../diagrams/flow-0044-ci-cd.svg) · [sequence](../../diagrams/sequence-0044-ci-cd.svg) ·
per component and signing: [flow](../../diagrams/flow-0044-ci-cd-per-component-and-image-signing.svg) · [sequence](../../diagrams/sequence-0044-ci-cd-per-component-and-image-signing.svg) ·
the supply-chain checks: [flow](../../diagrams/flow-0044-supply-chain.svg)

The supply-chain checks run in CI only, with no runtime path, so they have no sequence diagram.

## Backlog

- Features: [F10.2](https://github.com/silvioincalza/helper2.ai/issues/108) CI/CD and supply chain: signed images by digest, SBOM, scans.
- Open issues that change this decision:
  - [#355](https://github.com/silvioincalza/helper2.ai/issues/355) S10.10.1 The deployer token minted per deploy
  - [#357](https://github.com/silvioincalza/helper2.ai/issues/357) S10.12.1 The main journeys automated in a browser
