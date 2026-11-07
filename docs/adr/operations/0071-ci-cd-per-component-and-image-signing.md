# 0071 — CI and CD per component, images signed with an OpenBao key, deployed by digest

**Status.** Accepted. **Builds on:** [0028](0028-ci-cd.md), [0046](0046-supply-chain.md), [0048](0048-release-branches-and-version-tags.md), [0069](0069-three-deployables.md)

## Context
Three images now ship together: web, public app and back office ([0069](0069-three-deployables.md)). Every pull
request still ran every check, and every push to `main` built all three images, also for a change to one page of the
admin app or to a doc. Nothing proved that an image the cluster runs is one that CD built: anyone with push rights on
Harbor could put an image under a tag before CD did, and the deploy named images by tag only. Image signing was an
open supply-chain step ([0046](0046-supply-chain.md)).

## Decision
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

## Consequences
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

## Alternatives considered
- `dorny/paths-filter` or a workflow-level `paths:` — a third-party action in every run, or a skipped workflow that
  leaves a required check waiting forever.
- Build all three on every push — simple, but a docs change costs three builds and an admin change rebuilds the
  public image.
- Keyless signing (Fulcio and Rekor, the GitHub OIDC identity) — no key to keep, but every signature and our image
  names go to a public log, and the deploy needs the public Sigstore services to verify.
- A key file in OpenBao KV — the private key would sit on the runner during the job; Transit never hands it out.
- Verify in the cluster with an admission controller (Kyverno, the Sigstore policy controller) — stronger, but a
  controller to run and a dependency on one cluster; the deploy stays generic (roadmap, infra side).

## AI architecture check (delta)
| # | Layer | Status |
|---|---|---|
| 12 | Deployment | covered: per-component build, one tag for the three images, verify then deploy by digest; Helm rollback unchanged |
| 13 | Security & Identity | covered: signing key in OpenBao Transit, sign rights for the role `deploy` only and revoked after the signing step, CD signs only what it pushed, pull-only robot for verification |
| 15 | Cost & Reliability | covered: fewer builds; a failed run's images are rebuilt by the next run, never assumed |

Layers 1 to 11 and 14 do not change: no model, prompt, data or user-facing path.

## Code
- `.github/scripts/changes.sh` — the map and its self-test.
- `.github/workflows/ci.yml` (`changes`, the `if:` of each job, `ci-ok`), `cd.yml` (`image`), `deploy.yml` (Resolve
  tag).
- `.github/actions/install-cosign/action.yml` — cosign, pinned by SHA-256.
- `deploy/k8s/Makefile` — `ci-images`, `sign`, `sign-existing`, `verify-images`, `verified`, `cosign-pub`,
  `chart-check-digests`, `PUSHED` in `image` and `postgres-image`, the digests in `HELM_ARGS`.
- `deploy/k8s/cosign.pub`, `deploy/k8s/cosign-signing-config.json`.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.atDigest`), `values.yaml` (the `digest` values).
- `deploy/k8s/CI.md` — the OpenBao Transit key, the policies and the pull-only robot.

## Diagrams
[flow](../../diagrams/flow-0071-ci-cd-per-component-and-image-signing.svg) · [sequence](../../diagrams/sequence-0071-ci-cd-per-component-and-image-signing.svg)

## Roadmap
- Make `ci-ok` a required status check on `main`: [roadmap](../../roadmap.md#2-operations).
- Verify signatures in the cluster too (admission controller, infra side): [roadmap](../../roadmap.md#2-operations).
