# 0032 — Runtime topology: the Helm chart and three deployables

**Status.** Accepted. Merges old 0027, 0069 (texts merged in PR 11 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0027"></a>

## Part: was ADR 0027 — Runtime topology: Helm chart, three replicas on prod, Cloudflare and ingress-nginx in front, per-workload network policies, Redis auth, backups

**Status.** Accepted. **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0005](../foundation/0005-redis-disposable-cache.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0020](../scout/0020-scout-runner.md), [0024](../edge/0024-identity-and-admin-host.md), [0025](../edge/0025-http-edge-protection.md)

### Context
The hosted Helper2 runs on one Kubernetes cluster, with a staging environment and
prod (`helper2.ai`). The chart must also run on any other cluster. A deploy must not interrupt the site, and one node
going down must not take the app with it. The app talks to many public application programming interfaces (APIs), so a compromised pod must not reach the private
network. User data (bookmarks, reports, reviews) cannot be rebuilt by the Scout, so prod needs backups.

### Decision
1. **Split of ownership.** The platform repository (incalza-me-infra) owns identity and edge: the namespace
   (`helper2-<env>`, Pod Security `restricted` enforced), Domain Name System (DNS) records, the Cloudflare tunnel, ingress-nginx and the Ingresses, and the
   `deployer` ServiceAccount and Role. The Helm chart `deploy/helm/helper2` owns the workloads: one release
   `helper2` per namespace, values `values-staging.yaml` / `values-prod.yaml`. The chart creates no Ingress.
2. **Workloads.** `helper2-web`, `helper2` and `helper2-backoffice` Deployments, `postgres` StatefulSet
   (5 Gi `local-path` volume), `redis` StatefulSet (1 Gi `local-path` volume for its append-only file),
   and in prod the `pg-backup` CronJob. Both stateful workloads run one replica on a ReadWriteOnce volume from
   `volumeClaimTemplates`, with `fsGroup` so the non-root user can write it. Every container runs as non-root with
   a read-only root file system, no capabilities and the `RuntimeDefault` seccomp profile. Every pod runs as the ServiceAccount `helper2`, which has no RoleBinding,
   with `automountServiceAccountToken: false`: no workload calls the Kubernetes API, so no pod holds a token.
3. **App replicas.** Three workloads ([the part that was ADR 0069](#part-0069)): the web image (`helper2-web`, `web.replicas`:
   2 on prod, 1 on staging; nginx, a 10 s `preStop` sleep and 15 s of grace), the public app (`helper2`, `app.replicas`:
   3 on prod, 1 on staging) and the back office (`helper2-backoffice`, `backoffice.replicas`, 1 by default). Each has
   its own image repository and resources; the web and back office tags default to `image.tag`. `RollingUpdate` with `maxUnavailable: 0` and
   `maxSurge: 1`: one new pod at a time, and an old pod goes only when the new one is ready. Topology spread over
   `kubernetes.io/hostname` (`ScheduleAnyway`). Above 1 replica a PodDisruptionBudget keeps `replicas - 1` available
   during node drains. A pod that leaves first sleeps 10 s in `preStop` (native `sleep` action, Kubernetes 1.30 and
   later) and keeps serving while the Service endpoints and ingress-nginx drop it. Then SIGTERM: the app shuts down
   gracefully (`server.shutdown: graceful`), takes no new connections and gives requests in flight 30 s
   (`H2_SHUTDOWN_TIMEOUT`, `spring.lifecycle.timeout-per-shutdown-phase`). `terminationGracePeriodSeconds` is
   10 + 30 + 5 = 45 s. All three come from one place, `app.shutdown` (`preStopSeconds`, `drainSeconds`) in
   `values.yaml`; chart-check pins them. `make deploy` and `make rollback` wait up to 12 min (`helm --wait`). Replicas share state only
   through Postgres and Redis: the Scout and indexer locks and the end time of the last scheduled Scout run
   ([0020](../scout/0020-scout-runner.md)), rate limits
   ([0025](../edge/0025-http-edge-protection.md)) and sign-in sessions ([0024](../edge/0024-identity-and-admin-host.md)). The `deployer`
   Role (incalza-me-infra) must allow PodDisruptionBudgets.
4. **Edge.** The public names (`helper2.ai`, `www.helper2.ai`, `helper2.incalza.me`) are proxied by Cloudflare,
   which terminates Transport Layer Security (TLS) and sends the traffic through the platform's tunnel to
   ingress-nginx (namespace `ingress-nginx`). The Ingress `helper2-public` routes `/assets/` (prefix), `/theme.js`
   and `/favicon.svg` (exact) to the Service `helper2-web` and everything else to the Service `helper2`, port 80; on prod it also redirects `www.helper2.ai` to the apex (301, path and query kept). The admin names (`admin.helper2.ai`, `helper2-admin.incalza.me`) are DNS-only records to ingress-nginx;
   the Ingress `helper2-admin` routes them, from the in-network ranges only, to the Service `helper2-admin`
   ([0024](../edge/0024-identity-and-admin-host.md)). From ingress-nginx to the app the traffic is plain Hypertext Transfer
   Protocol (HTTP). On any other cluster, your own Ingress or route targets the same three Services.
   Platform settings (registry, storage classes, Ingress controller, tracing, scraper) are values, set for the
   hosted environments in `values-<env>.yaml` ([deploy/k8s/README.md](../../../deploy/k8s/README.md)).
5. **Ports.** Each workload serves on 8080: the web files behind the Service `helper2-web`, the public app behind the
   Service `helper2`, the back office behind the Service `helper2-admin` ([0024](../edge/0024-identity-and-admin-host.md)). Actuator
   is on 8081 (`H2_MANAGEMENT_PORT`) in both apps; the web pods answer `/healthz` on 8081. Probes and the Prometheus
   scrape use 8081. No Service routes 8081; only the admin names reach the back office.
   Startup probe: every 5 s, up to `app.startupSeconds` (300 s); then readiness every 5 s and liveness
   every 10 s. Postgres and Redis have a liveness probe too (`pg_isready`; `redis-cli ping`, where LOADING
   counts as alive): two minutes without an answer (6 × 20 s) restarts a hung server. Postgres gets a startup
   probe first, up to 10 min (120 × 5 s): `pg_isready` fails while the server replays its log after a crash,
   and a restart then would start the recovery over. Redis gets 30 s before its first check.
6. **Network policy.** One policy per workload and direction, each naming only the peers that workload needs.
   Ingress is denied by default, then allowed per workload:
   - public app (`ingress-app`) and back office (`ingress-backoffice`): the Ingress controller's pods
     (`networkPolicy.ingressController`: namespace `ingress-nginx`, label `app.kubernetes.io/name=ingress-nginx`) on
     8080; namespace `observability` on 8081;
   - web pods (`ingress-web`): the Ingress controller's pods on 8080, nothing else;
   - Postgres (`ingress-postgres`): both apps and `pg-backup` on 5432;
   - Redis (`ingress-redis`): both apps on 6379;
   - `pg-backup`: nothing.

   Egress is denied by default, then allowed per workload:
   - both apps (`egress-app`): DNS, Postgres 5432, Redis 6379, the OpenTelemetry collector (`observability`, 4318), and the
     internet on 443 only, with the private ranges excluded;
   - `pg-backup`: DNS, Postgres, and 443 only when `backup.offsite` is on;
   - the web pods, Postgres and Redis: nothing.
7. **Redis auth.** `redis.auth.enabled: true`. The password is `H2_REDIS_PASSWORD` from `helper2-app-env` (or
   `existingSecret`); it reaches `redis-server` as `requirepass` on standard input, never in the command line.
   Redis is a single-replica StatefulSet with an append-only file on its own PersistentVolumeClaim
   ([0005](../foundation/0005-redis-disposable-cache.md)): a Redis restart keeps sessions, rate-limit windows and cached
   answers. The volume holds only cache data and is not backed up.
8. **Backups (prod).** `pg-backup` runs at 02:30 Europe/Zurich: `pg_dump --no-owner | gzip` to the Network File System (NFS)
   volume `pg-backup` (kept on `helm uninstall`), 7 days kept. A failed `pg_dump` fails the Job (`pipefail`); the
   new dump replaces the old one only once `gzip -t` reads it back whole and it is bigger than an empty dump, and
   the old dumps are deleted after that, never before. A run that cannot start within an hour is missed
   (`startingDeadlineSeconds`). Two alerts: `Helper2BackupFailed` for a failed Job, `Helper2BackupStale` when no
   run has succeeded for 36 hours (a suspended CronJob, a Pod stuck Pending, a missed start). With
   `backup.offsite.enabled`, rclone then copies new dumps to Cloudflare R2 (it never deletes there; the bucket's
   lifecycle rule sets retention).
9. **Per environment.** The back office runs the Scout once a day at 03:07 UTC (`backoffice.scoutCron`, so each collector runs at
   most once a day; each replica checks every 10 minutes, `H2_SCOUT_TICK` default, and a restart does not add a run) with at most 200 enrichments per run (`backoffice.scoutMaxEnrichments`); the daily enrichment cap
   (500) still applies. Staging has the Scout off and no backup.

### Consequences
- Visitors reach the app only through Cloudflare and ingress-nginx; the back office only from the in-network
  ranges. No other pod reaches either app.
- A compromised app pod can reach only the internet on 443, its own database and cache, and the trace collector,
  and holds no Kubernetes API credential.
- A deploy or a node drain keeps the site up on prod, and one pod failing leaves two serving. On staging too, a
  deploy starts the new pod before the old one stops.
- Postgres connections: (replicas + 1 during a deploy) × `H2_DATABASE_POOL_SIZE` (10) = 40 on prod, under the default
  `max_connections` of 100.
- The Postgres and Redis volumes are not owned by the release, so the data outlives the release.

### Alternatives considered
- Ingress controller with a public Internet Protocol (IP) address — opens a port on the cluster and needs certificate management.
- A Cloudflare tunnel per app (a `cloudflared` Deployment in each namespace) — one more workload, egress rule and
  token per environment, for the path the platform's tunnel already serves.
- An Ingress template in the chart — it ties the chart to one controller's annotations, and the platform keeps
  every Ingress of the cluster in one place.
- One replica with `Recreate` — a short outage on every deploy and no failover.
- Redis as a Deployment with `Recreate` and a separate PersistentVolumeClaim — same single pod, but the claim is one
  more template to keep in step, and a StatefulSet already guarantees that two pods never share the volume.
- Horizontal Pod Autoscaler — the load is small and steady; a fixed count keeps the connection budget known.
- Managed PostgreSQL — monthly cost and data outside the owner's cluster for a small database.
- Kustomize — no release history, no `--rollback-on-failure` and no value checks; Helm has all three.

### Code
- `deploy/helm/helper2/values.yaml`, `values-staging.yaml`, `values-prod.yaml` — settings.
- `deploy/helm/helper2/templates/app.yaml`, `backoffice.yaml`, `postgres.yaml`, `redis.yaml`, `backup.yaml`,
  `networkpolicy.yaml`, `serviceaccount.yaml`, `secret.yaml`, `_helpers.tpl` — workloads and policies.
- incalza-me-infra (outside this repo) — namespace, DNS, tunnel, ingress-nginx and the Ingresses, deployer.
- `backend/apps/public/src/main/resources/application.yaml` — `server.shutdown`, `spring.lifecycle.timeout-per-shutdown-phase`.
- `deploy/k8s/Makefile`, `deploy/k8s/README.md` — deploy, rollback, backup and restore steps.

### Diagrams
[flow](../../diagrams/flow-0032-runtime-topology.svg) · [sequence](../../diagrams/sequence-0032-runtime-topology.svg)
The topology itself is in the [infrastructure](../../diagrams/infrastructure.svg) diagram.

### Roadmap
- Test a restore, add a restore-check job, turn on the R2 copy, encrypt dumps: [roadmap](../../roadmap.md#2-operations).
- Postgres and Redis exporters: [roadmap](../../roadmap.md#2-operations).
- What stays per replica; Scout lock after a crash; Redis memory:
  [roadmap](../../roadmap.md#3-more-than-one-replica).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0069"></a>

## Part: was ADR 0069 — Three deployables: web, public app, back office

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-libraries-and-apps.md), [0003](../foundation/0003-contract-first-rest-api.md), [the part that was ADR 0027](#part-0027), [0024](../edge/0024-identity-and-admin-host.md), [0026](../edge/0026-back-office-and-review.md), [0029](../frontend/0029-site-pages-and-policy.md), [0030](../frontend/0030-monetisation.md), [0002](../foundation/0002-modular-monolith-libraries-and-apps.md)

### Context
One image did everything. The backend jar carried the built web app, and every pod served the public site, the back
office on a second port, the Scout and every nightly job. The back office code and its routes shipped in every public
pod; only a port filter and the role kept them closed. The Scout and the enrichment shared a pod with the search and
the guide, so a long crawl competed with a visitor's question for CPU, memory and database connections. A change to
the web app rebuilt and redeployed the backend.

### Decision
1. **Three deployables, one repo.** `web` (the built React app, static files only), `public` (search, guide, setup,
   the catalog pages, sign-in for users, saved items, news, events, the policy) and `backoffice` (the admin API and
   the admin web app, the Scout, its collectors and enrichment, the home-example check, the catalog's jobs). Each has
   its own image and image tag: `deploy/web.Dockerfile`, `deploy/Dockerfile`, `deploy/backoffice.Dockerfile`, built
   from one commit and deployed with the same tag.
2. **Shared code is a library in this repo** ([0002](../foundation/0002-modular-monolith-libraries-and-apps.md)). Apps hold the
   driving adapters (controllers, schedules) and the wiring. The public app has no admin and no Scout code; the back
   office has no guide, setup or SEO pages. Both libraries and apps keep their package names.
   - `libs/sources` holds the live search sources (GitHub, Hugging Face, npm, arXiv, the live web), their HTTP mapping
     and settings, the demo sources, and the descriptors of the Scout's collectors. Both apps search, and both list
     every source on `GET /api/v1/sources`. The collectors themselves stay in the back office.
   - `ScoutPolicy` is in `libs/catalog` with the limits the Scout reads, so the public policy endpoint
     ([0029](../frontend/0029-site-pages-and-policy.md)) states the back office's numbers without its code.
   - The web adapters both apps serve the same way (search, sources, categories) are one source folder,
     `backend/apps/common-web`, compiled into each app against its own generated contract types.
3. **One contract per app.** The public app generates its controllers from `api/public.yaml`, the back office from
   `api/admin.yaml`, each with the parts of `api/common.yaml` it names; the shared types keep their names in both.
4. **One database, one Flyway owner.** Both apps use the same Postgres and Redis. The migrate step of the public image
   runs the migrations in its own Job, and each app connects as its own role and only checks the schema
   ([0004](../foundation/0004-persistence-schema-and-app-roles.md)). Daily budgets and quotas stay in the shared Redis, so they hold across both apps.
5. **The back office only on the admin host** ([0024](../edge/0024-identity-and-admin-host.md)). The Deployment `helper2-backoffice`
   (one replica by default) sits behind the Service `helper2-admin`; the public Deployment opens no admin port. On
   every response, errors included, the back office sends:
   - a fixed strict CSP, without a nonce: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'
     data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self';
     frame-ancestors 'none'; frame-src 'none'; worker-src 'none'`;
   - `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, the same `Permissions-Policy`, COOP and CORP
     `same-origin`, `X-Robots-Tag: noindex, nofollow` and HSTS;
   - `Cache-Control`: `no-store` on pages, the API, redirects and errors; `private, max-age=31536000, immutable` on
     `/admin/assets/**`; `no-cache` on the app's other files.

   Only its own paths answer; anything else is a plain 404. `/` and `/admin/admin.html` go to `/admin`, a resource
   link (`/resource/**`) to the public site (`H2_PUBLIC_BASE_URL`).
6. **Cookies and writes on the back office.** `__Host-SESSION` (Lax: the sign-in callback from GitHub is a cross-site
   navigation) in its own Redis namespace, and `__Host-XSRF-TOKEN` (Strict). Every write needs the admin host's own
   Origin and the token in the `X-XSRF-TOKEN` header. Only the back office grants ADMIN; its sign-in callback is
   pinned (`H2_ADMIN_BASE_URL`) and returns to the relative `/admin`. The public app keeps its cookies and its CSP with
   the nonce ([0030](../frontend/0030-monetisation.md)).
7. **Every job runs in one app.** The public app: guide, identity, search and shared-cache retention. The back office:
   the Scout's daily check, Scout, catalog and runtime-settings retention, the home-example check, the embedding
   indexer and the category backfill at startup. ShedLock still allows one run at a time across replicas. Each
   replica of either app refreshes its own runtime-settings snapshot; that reads, it does no shared work. A test per
   app (`SchedulesIT`) lists its jobs.
8. **What the owner sees stays whole.** The back office declares the guide's daily cap with the same `H2_` default,
   so its Settings page, its runtime settings ([0007](../foundation/0007-configuration-secrets-and-settings.md)) and its overview's budgets still show
   it, read from the shared Redis count. Both apps register the same `source` rows at startup, from the same settings
   and the same descriptors.

9. **The `web` image serves the public web app's files, the public app writes the pages.** nginx
   (`nginx-unprivileged`, alpine-slim, user 101, read-only root) serves GET and HEAD only:
   - `/assets/<file>`: Vite's content-hashed files, `public, max-age=31536000, immutable`;
   - `/theme.js` and `/favicon.svg`, the root files of `frontend/public`: `public, max-age=300, must-revalidate`;
   - anything else: a plain 404 with `no-store`, never `index.html` and never a directory listing; another method:
     405.

   Every answer carries its own CSP (`default-src 'none'; style-src 'unsafe-inline'; ... sandbox`), `nosniff`, CORP
   `same-origin` and `Referrer-Policy: no-referrer`. nginx keeps no access log. The build stops when `frontend/dist`
   holds a file nginx does not name, or a source map.
10. **The shell of the same version.** The built `index.html` sits outside the served folder, at
    `/usr/share/helper2/shell/index.html`. An init container of each public pod runs the web image of the same tag
    (one Helm helper names it for both) and copies it into an `emptyDir` that the app mounts read-only at `/shell`.
    The public app reads it once at start (`H2_PAGES_TEMPLATE`, default `file:/shell/index.html`) and writes every page
    into it ([0029](../frontend/0029-site-pages-and-policy.md)). The public image holds no web files.
11. **Same origin, split by path.** The Ingress of the public host sends `/assets/` (prefix), `/theme.js` and
    `/favicon.svg` (exact) to the Service `helper2-web`, everything else to the Service `helper2`. The web pods take
    traffic from the Ingress controller on 8080 only, and reach nothing.

Built later: the two DB roles ([0004](../foundation/0004-persistence-schema-and-app-roles.md)); CI/CD per component and image signing
([0033](0033-ci-cd-and-supply-chain.md)).

### Consequences
- A Scout change ships without touching the public pods, and a long crawl no longer competes with a visitor's question.
- The back office is closed by a fourth layer: its code is not in the public image.
- Two apps share one schema. A migration must keep the running back office working until it is replaced.
- More to run: three images, three workloads. Still one chart, one database, one Redis.
- A web app change deploys all three with one tag; since [0033](0033-ci-cd-and-supply-chain.md)
  only the changed images are built, the others get the new tag on their previous digest.
- Helm rolls the three Deployments at once; the order web, public, back office is not enforced. In practice the web
  pods are ready in seconds and a public pod needs its schema check and Lingua start, so new web pods come first.
- Known limit (roadmap): during a rollout, a page from an old public pod can name an asset hash that the new web pods
  no longer have (a 404 for that script until the visitor reloads), and a page from a new public pod can, for a few
  seconds, name a hash that only the new web pods have. Fix: keep the previous release's assets in the web image.
- Each app has its own database role and Redis user, and the passwords their own Secret ([0004](../foundation/0004-persistence-schema-and-app-roles.md)).
  Known limit (roadmap): both workloads still read the rest of the env Secret.
- The Settings page shows the back office's groups and the guide's cap; the public app's other guide and setup
  settings are no longer there (roadmap).
- Dashboards and alerts that filter on `application="helper2"` see the public app only; the back office reports as
  `helper2-backoffice` (roadmap).

### Considered
- **Separate databases per service** — the back office writes what the public app reads; keeping them in sync needs
  an API or a queue, without a named need for either.
- **One jar, two deployments by profile** — the admin code would still ship to the public pods.
- **A Node server for the HTML** — rewrites the pages module for no gain; or **a fully static frontend**, which loses
  the SEO pages and the nonce CSP.
- **A published library** — versions and a registry to keep in step for code that changes in the same pull request.
- **A copy of the search controllers in the back office** — two mappers of the same contract types to keep equal.

### AI architecture check (delta)
| # | Layer | Status |
|---|---|---|
| 1 | Model | covered: the same NVIDIA models; both apps use the platform library's client |
| 3 | Orchestration | covered: every job in one app (`SchedulesIT` per app), ShedLock unchanged |
| 4 | Tool & API | covered: the admin API in its own contract and app |
| 9 | Guardrails | covered: the same code paths; the evidence gate and the quote checks moved with the Scout |
| 10 | Observability | deferred: `helper2-backoffice` needs its own Grafana panels and alerts (roadmap); model ids per answer unchanged |
| 12 | Deployment | covered: three images and three workloads with one tag; the public pod's shell from the web image of that tag; the rollout order is not enforced and old pods can name assets the new web pods lack (known limit, roadmap) |
| 13 | Security & Identity | covered: ADMIN, the admin session and the sign-in callback only on the back office; `__Host-` cookies; Origin and header CSRF; DB roles deferred (roadmap) |
| 15 | Cost & Reliability | covered: budgets and quotas in the shared Redis; the public site does not depend on the back office being up |

Layers 2, 5, 6, 7, 8, 11 and 14 do not change.

### Code
- `backend/settings.gradle.kts`, `backend/build-logic/src/main/kotlin/helper2.spring-app.gradle.kts` — the projects;
  the contract per app; the shared web source folder.
- `backend/apps/public`, `backend/apps/backoffice`, `backend/apps/common-web`, `backend/libs/sources`.
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/config/BackOfficeSecurityConfiguration.java`,
  `BackOfficeEdge.java`, `admin/web/AdminWebApp.java`, `platform/SchemaCheck.java`.
- `backend/apps/public/src/main/java/ai/helper2/identity/config/PublicSecurityConfiguration.java`.
- `backend/apps/*/src/test/java/ai/helper2/api/SchedulesIT.java`, `BackOfficeEdgeIT.java`, `HttpEdgeIT.java`.
- `deploy/web.Dockerfile`, `deploy/web/nginx.conf`, `deploy/web/headers.conf`, `deploy/Dockerfile`,
  `deploy/backoffice.Dockerfile`.
- `deploy/helm/helper2/templates/web.yaml`, `app.yaml` (the init container), `backoffice.yaml`, `networkpolicy.yaml`,
  `_helpers.tpl` (`helper2.webImage`); `deploy/k8s/Makefile`; `.github/workflows/cd.yml`, `release.yml`,
  `image-scan.yml`.
- `backend/apps/public/src/main/java/ai/helper2/pages/internal/PageTemplate.java`, `pages/web/PageRoutes.java`.
- `frontend/vite.admin.config.ts`, `frontend/src/api/client.ts` (either CSRF cookie name).

### Diagrams
[flow](../../diagrams/flow-0032-three-deployables.svg) · [sequence](../../diagrams/sequence-0032-three-deployables.svg) · [architecture context](../../diagrams/architecture-context.svg) · [infrastructure](../../diagrams/infrastructure.svg)

### Roadmap
- [roadmap § 2](../../roadmap.md#2-operations) (the previous release's assets in the web image, DB roles, Grafana) and
  [§ 9](../../roadmap.md#9-known-limits).
