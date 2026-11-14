# 0043 — Runtime topology: Helm chart, three deployables

**Depends on:** [0001](../foundation/0001-stack-and-runtime.md), [0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0003](../foundation/0003-contract-first-rest.md), [0004](../foundation/0004-persistence-and-the-schema.md), [0005](../foundation/0005-redis-as-a-disposable-cache.md), [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0009](../foundation/0009-observability.md), [0011](../scout/0011-scout-runner.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0037](../identity/0037-http-edge.md), [0038](../backoffice/0038-back-office-and-review.md), [0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md), [0042](../money/0042-monetisation.md), [0044](0044-ci-cd-and-supply-chain.md).

## Context

The hosted Helper2 runs on one Kubernetes cluster, with a staging environment and prod (`helper2.ai`). The chart must
also run on any other cluster. A deploy must not interrupt the site, and one node going down must not take the app
with it. The app talks to many public application programming interfaces (APIs), so a compromised pod must not reach
the private network. User data (bookmarks, reports, reviews) cannot be rebuilt by the Scout, so prod needs backups.

One image for everything has a cost. The backend jar would carry the built web app, and every pod would serve the
public site, the back office on a second port, the Scout and every nightly job. The back office code and its routes
would ship in every public pod, closed only by a port filter and the role. The Scout and the enrichment would share a
pod with the search and the guide, so a long crawl would compete with a visitor's question for CPU, memory and
database connections. A change to the web app would rebuild and redeploy the backend.

## Decision

### Ownership and the three deployables
1. **Split of ownership.** The platform repository (incalza-me-infra) owns identity and edge: the namespace
   (`helper2-<env>`, Pod Security `restricted` enforced), the Domain Name System (DNS) records, the Cloudflare
   tunnel, ingress-nginx and the Ingresses, and the `deployer` ServiceAccount and Role. The Helm chart
   `deploy/helm/helper2` owns the workloads: one release `helper2` per namespace, values `values-staging.yaml` /
   `values-prod.yaml`. The chart creates no Ingress and no Secret.
2. **Three deployables, one repo.** `web` (the built public React app, static files only), `public` (search, guide,
   setup, the catalog pages, sign-in for users, saved items, news, events, the policy) and `backoffice` (the admin API
   and the admin web app, the Scout, its collectors and enrichment, the home-example check, the catalog's jobs). Each
   has its own image: `deploy/web.Dockerfile`, `deploy/Dockerfile`, `deploy/backoffice.Dockerfile`, built from one
   commit and deployed with the same tag. The back office tag defaults to `image.tag`; the web image has no tag of
   its own, it is always `image.tag`.
3. **Shared code is a library in this repo** ([0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)). Apps
   hold the driving adapters (controllers, schedules) and the wiring. The public app has no admin and no Scout code;
   the back office has no guide, setup or SEO pages. Libraries and apps keep their package names.
   - `libs/sources` holds the live search sources (GitHub, Hugging Face, npm, arXiv, the live web), their HTTP mapping
     and settings, the demo sources, and the descriptors of the Scout's collectors. Both apps search, and both list
     every source on `GET /api/v1/sources`. The collectors themselves stay in the back office.
   - `ScoutPolicy` is in `libs/catalog` with the limits the Scout reads, so the public policy endpoint
     ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md)) states the back office's numbers without its code.
   - The web adapters both apps serve the same way (search, sources, categories) are in `libs/web-contract`, with
     the types of `api/common.yaml` they use, generated once ([0003](../foundation/0003-contract-first-rest.md)).
4. **One contract per app.** The public app generates its controllers from `api/public.yaml`, the back office from
   `api/admin.yaml`. Each serves the parts of `api/common.yaml` it names, but generates none of them: those come from
   `libs/web-contract`, under the same names in both apps.
5. **Workloads.** The Deployments `helper2-web`, `helper2` and `helper2-backoffice`; the `postgres` StatefulSet
   (5 Gi volume) and the `redis` StatefulSet (1 Gi volume for its append-only file), both on `local-path` in the
   hosted environments; the migrate Job `helper2-migrate-<revision>`, once per release (§10); and on prod the
   `pg-backup` CronJob. Both stateful workloads run one replica on a ReadWriteOnce volume from
   `volumeClaimTemplates`, with `fsGroup` so the non-root user can write it. Every container runs as non-root with a
   read-only root file system, no capabilities and the `RuntimeDefault` seccomp profile. Every pod runs as the
   ServiceAccount `helper2`, which has no RoleBinding, with `automountServiceAccountToken: false`: no workload calls
   the Kubernetes API, so no pod holds a token. A private registry's pull secret goes on that ServiceAccount
   (`imagePullSecrets` in the values, empty by default), so every pod gets it; the hosted environments set none.

### Replicas, rollout and shutdown
6. **Replicas and a rolling update without a gap.** The web image (`web.replicas`: 2 on prod, 1 on staging), the
   public app (`app.replicas`: 3 on prod, 1 on staging) and the back office (`backoffice.replicas`, 1 by default).
   Each has its own image repository and resources. `RollingUpdate` with `maxUnavailable: 0` and `maxSurge: 1`: one
   new pod at a time, and an old pod goes only when the new one is ready. Topology spread over
   `kubernetes.io/hostname` (`ScheduleAnyway`). Above 1 replica a PodDisruptionBudget keeps `replicas - 1` available
   during node drains.
   - A pod that leaves first sleeps 10 s in `preStop` (the native `sleep` action, Kubernetes 1.30 and later) and keeps
     serving while the Service endpoints and ingress-nginx drop it.
   - Then SIGTERM. Both apps shut down gracefully (`server.shutdown: graceful`), take no new connections and give
     requests in flight 30 s (`H2_SHUTDOWN_TIMEOUT`, `spring.lifecycle.timeout-per-shutdown-phase`).
     `terminationGracePeriodSeconds` is 10 + 30 + 5 = 45 s. All three numbers come from one place, `app.shutdown`
     (`preStopSeconds`, `drainSeconds`) in `values.yaml`, and the back office uses the same; chart-check pins them.
   - nginx gets SIGQUIT after its own 10 s `preStop` (`web.preStopSeconds`) and ends its connections gracefully;
     its grace period is 15 s.

   `make deploy` and `make rollback` wait up to 12 min (`helm --wait`, and `--wait-for-jobs` for the migrate Job).
   `make deploy` then checks the release and goes back to the revision deployed before when it is not healthy
   ([0044](0044-ci-cd-and-supply-chain.md) §3).
   Replicas share state only through Postgres and Redis: the Scout and indexer locks and the end time of the last
   scheduled Scout run ([0011](../scout/0011-scout-runner.md)), rate limits
   ([0037](../identity/0037-http-edge.md)) and sign-in sessions ([0036](../identity/0036-identity-and-the-admin-host.md)).
   The `deployer` Role (incalza-me-infra) must allow PodDisruptionBudgets and Jobs.

### Edge, ports and probes
7. **Edge: Cloudflare, ingress-nginx, one origin split by path.** The public names (`helper2.ai`, `www.helper2.ai`,
   `helper2.incalza.me`) are proxied by Cloudflare, which terminates Transport Layer Security (TLS) and sends the
   traffic through the platform's tunnel to ingress-nginx (namespace `ingress-nginx`). The Ingress `helper2-public`
   routes `/assets/` (prefix), `/theme.js` and `/favicon.svg` (exact) to the Service `helper2-web` and everything else
   to the Service `helper2`, port 80; on prod it also redirects `www.helper2.ai` to the apex (301, path and query
   kept). The admin names (`admin.helper2.ai`, `helper2-admin.incalza.me`) are DNS-only records to ingress-nginx; the
   Ingress `helper2-admin` routes them, from the in-network ranges only, to the Service `helper2-admin`
   ([0036](../identity/0036-identity-and-the-admin-host.md)). From ingress-nginx to the pods the traffic is plain Hypertext
   Transfer Protocol (HTTP). On any other cluster, your own Ingress or route targets the same three Services.
   Platform settings (registry, storage classes, Ingress controller, tracing, scraper) are values, set for the hosted
   environments in `values-<env>.yaml` ([deploy/k8s/README.md](../../../deploy/k8s/README.md)).
8. **Ports and probes.** Each workload serves on 8080: the web files behind the Service `helper2-web`, the public app
   behind the Service `helper2`, the back office behind the Service `helper2-admin`. Actuator is on 8081
   (`H2_MANAGEMENT_PORT`) in both apps; the web pods answer `/healthz` on 8081. Probes and the Prometheus scrape use
   8081. No Service routes 8081; only the admin names reach the back office.
   - Both apps: a startup probe every 5 s, up to `app.startupSeconds` (300 s: the schema check, which waits up to
     4 minutes for the migrate Job, and the Lingua models); then readiness every 5 s and liveness every 10 s.
   - Postgres and Redis have a liveness probe too (`pg_isready`; `redis-cli ping`, where LOADING counts as alive):
     two minutes without an answer (6 × 20 s) restarts a hung server. Postgres gets a startup probe first, up to
     10 min (120 × 5 s): `pg_isready` fails while the server replays its log after a crash, and a restart then would
     start the recovery over. Redis gets 30 s before its first check.

### Network policy
9. **One policy per workload and direction,** each naming only the peers that workload needs.
   Ingress is denied by default, then allowed per workload:
   - public app (`ingress-app`) and back office (`ingress-backoffice`): the Ingress controller's pods
     (`networkPolicy.ingressController`: namespace `ingress-nginx`, label `app.kubernetes.io/name=ingress-nginx`) on
     8080; the scraper's namespace (`networkPolicy.metricsNamespace`, `observability`) on 8081;
   - web pods (`ingress-web`): the Ingress controller's pods on 8080, nothing else;
   - Postgres (`ingress-postgres`, `ingress-postgres-migrate`): both apps, `pg-backup` and the migrate Job on 5432;
   - Redis (`ingress-redis`): both apps on 6379;
   - `pg-backup`: nothing.

   Egress is denied by default, then allowed per workload:
   - both apps (`egress-app`): DNS, Postgres 5432, Redis 6379, the OpenTelemetry collector (`observability`, 4318),
     and the internet on 443 only, with the private ranges excluded;
   - the migrate Job (`egress-migrate`): DNS and Postgres;
   - `pg-backup` (`egress-backup`): DNS, Postgres, and 443 only when `backup.offsite` is on;
   - the web pods, Postgres and Redis: nothing.

### Data: Postgres, Redis and the backups
10. **One database, one Flyway owner.** Both apps use the same Postgres and Redis. The migrate Job runs the public
    image's migrate step (`MigrateStep`, `args: [migrate]`) as the database owner: Flyway migrates, the apps' roles get
    their login, and the Job ends. Each app connects as its own role (`h2_public`, `h2_backoffice`) and only checks
    the schema (`SchemaCheck`), waiting while the Job runs ([0004](../foundation/0004-persistence-and-the-schema.md)).
    The passwords are in their own Secret, `helper2-credentials`; every other `H2_` key is in `helper2-app-env`, which
    both apps load whole ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)). Each workload names only
    its own password keys; only Postgres, `pg-backup` and the migrate Job get the owner's. Daily budgets and quotas
    stay in the shared Redis, so they hold across both apps.
11. **Redis auth.** `redis.auth.enabled: true`. The default user is the admin (probes, `redis-cli`), with
    `requirepass` from `H2_REDIS_PASSWORD`. Each app connects as its own ACL user (`h2_public`, `h2_backoffice`,
    `files/redis-users.acl`) with its own password: its keys and commands only. The passwords come from the
    credentials Secret (or `redis.auth.existingSecret`) and reach `redis-server` as config on standard input, never
    in the command line. Redis is a single-replica StatefulSet with an append-only file on its own
    PersistentVolumeClaim ([0005](../foundation/0005-redis-as-a-disposable-cache.md)): a Redis restart keeps sessions,
    rate-limit windows and cached answers. The volume holds only cache data and is not backed up.
12. **Backups (prod).** `pg-backup` runs at 02:30 Europe/Zurich: `pg_dump --no-owner | gzip` to the volume
    `pg-backup` (NFS on prod, kept on `helm uninstall`), 7 days kept. A failed `pg_dump` fails the Job (`pipefail`);
    the new dump takes the place of the old one only once `gzip -t` reads it back whole and it is bigger than an empty dump
    (1 KB), and the old dumps are deleted after that, never before. A run that cannot start within an hour is missed
    (`startingDeadlineSeconds`). Two alerts: `Helper2BackupFailed` for a failed Job, `Helper2BackupStale` when no run
    has succeeded for 36 hours (a suspended CronJob, a Pod stuck Pending, a missed start). With
    `backup.offsite.enabled` (on for prod), rclone then encrypts tonight's dump (`rclone crypt`) and copies it to a
    Cloudflare R2 bucket in the EU jurisdiction. The access key, the endpoint, the bucket name and the encryption
    password come from the Secret `pg-backup-offsite`, so the public chart names no account and Cloudflare never
    holds the password. rclone never deletes there: the bucket's lifecycle rule deletes after 7 days, the same
    period as the volume. Only a dump younger than 24 hours is copied (`backup.offsite.maxAge`), so a dump the rule
    deleted is not uploaded again. Restore steps: [deploy/k8s/README.md](../../../deploy/k8s/README.md), "Backups".

### The web image and the public app's shell
13. **The `web` image serves the public web app's files; the public app writes the pages.** nginx
    (`nginx-unprivileged`, alpine-slim, user 101, read-only root) serves GET and HEAD only:
    - `/assets/<file>`: Vite's content-hashed files (scripts, styles, fonts), `public, max-age=31536000, immutable`;
    - `/theme.js` and `/favicon.svg`, the root files of `frontend/public`: `public, max-age=300, must-revalidate`;
    - anything else: a plain 404 with `no-store`, never `index.html` and never a directory listing; another method:
      405.

    Every answer carries its own CSP (`default-src 'none'; style-src 'unsafe-inline'; ... sandbox`), `nosniff`, CORP
    `same-origin` and `Referrer-Policy: no-referrer`. nginx keeps no access log. The build stops when `frontend/dist`
    holds a file nginx does not name, or a source map. The admin web app is not in this image: the back office
    image builds and serves it.
14. **The shell of the same version.** The built `index.html` sits outside the served folder, at
    `/usr/share/helper2/shell/index.html`. An init container of each public pod runs the web image of the same tag
    (one Helm helper, `helper2.webImage`, names it for both) and copies it into an `emptyDir` that the app mounts
    read-only at `/shell`. The public app reads it once at start (`H2_PAGES_TEMPLATE`, default
    `file:/shell/index.html`) and writes every page into it ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md)). The
    public image holds no web files.

### The back office
15. **The back office only on the admin host** ([0036](../identity/0036-identity-and-the-admin-host.md)). The Deployment
    `helper2-backoffice` sits behind the Service `helper2-admin`; the public Deployment opens no admin port. On every
    response, errors included, the back office sends:
    - a fixed strict CSP, without a nonce: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'
      data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self';
      frame-ancestors 'none'; frame-src 'none'; worker-src 'none'`;
    - `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, the same `Permissions-Policy`, COOP and CORP
      `same-origin`, `X-Robots-Tag: noindex, nofollow` and HSTS;
    - `Cache-Control`: `no-store` on pages, the API, redirects and errors; `private, max-age=31536000, immutable` on
      `/admin/assets/**`; `no-cache` on the app's other files.

    Only its own paths answer (the admin API, the admin app, sign-in, a few shared reads such as `/api/v1/search`);
    anything else is a 404 (problem JSON). `/` and `/admin/admin.html` go to `/admin`, a resource link
    (`/resource/**`) to the public site (`H2_PUBLIC_BASE_URL`).
16. **Cookies and writes on the back office.** `__Host-SESSION` (Lax: the sign-in callback from GitHub is a
    cross-site navigation) in its own Redis namespace (`h2:v1:session:backoffice`), and `__Host-XSRF-TOKEN` (Strict).
    Every write needs the admin host's own Origin and the token in the `X-XSRF-TOKEN` header. Only the back office
    grants ADMIN; its sign-in callback is pinned (`H2_ADMIN_BASE_URL`) and returns to the relative `/admin`. The
    public app keeps its cookies and its CSP with the nonce ([0042](../money/0042-monetisation.md)).

### Jobs and the environments
17. **Every job runs in one app.** The public app: guide, account, search and shared-cache retention. The back office:
    the Scout's tick, Scout, catalog, audit and runtime-settings retention, the home-example check (pending and
    nightly), the embedding indexer (only with an embedding key) and the category backfill at startup. ShedLock
    still allows one run at a time across replicas. Each replica of either app refreshes its own runtime-settings
    snapshot; that only reads, it does no shared work. A test per app (`SchedulesIT`) lists its jobs.
18. **What the owner sees stays whole.** The back office gets the two site-wide caps (guide model calls, live web
    credits) with the same values as the public app, so its Settings page, its runtime settings
    ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)) and its overview's budgets show them, read from
    the shared Redis count. Both apps register the same `source` rows at startup, from the same settings and the same
    descriptors.
19. **Per environment.** The back office runs the Scout once a day at 03:07 UTC (`backoffice.scoutCron`; each replica
    checks every 10 minutes, `H2_SCOUT_TICK`, and a restart does not add a run; each collector runs at most once a
    day) with at most 200 enrichments per run (`backoffice.scoutMaxEnrichments`); the daily enrichment cap (500) still
    applies. Staging has no backup. Its Scout is on only to fill an empty database before a first release; after
    the tag it goes off again (`deploy/k8s/RUNBOOK-reset-cutover.md`, "After the tag").

### Code

The chart and the cluster:
- `deploy/helm/helper2/values.yaml`, `values-staging.yaml`, `values-prod.yaml` — settings.
- `deploy/helm/helper2/templates/web.yaml`, `app.yaml` (the shell's init container), `backoffice.yaml`,
  `migrate.yaml`, `postgres.yaml`, `redis.yaml`, `backup.yaml`, `networkpolicy.yaml`, `serviceaccount.yaml`,
  `_helpers.tpl` (`helper2.webImage`, `helper2.credentialsSecret`, `helper2.redisPasswordFrom`) — workloads and
  policies; `deploy/helm/helper2/files/redis-users.acl` — the apps' Redis users.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2BackupFailed`, `Helper2BackupStale`.
- `deploy/k8s/Makefile` (`deploy`, `rollback`, `env-secret`, `chart-check`), `deploy/k8s/check-app-env.sh`,
  `deploy/k8s/README.md` — deploy, rollback, backup and restore steps; `deploy/k8s/RUNBOOK-reset-cutover.md` — the cutover of a first release.
- incalza-me-infra (outside this repo) — namespace, DNS, tunnel, ingress-nginx and the Ingresses, deployer.

The images:
- `deploy/web.Dockerfile`, `deploy/web/nginx.conf`, `deploy/web/headers.conf`, `deploy/Dockerfile`,
  `deploy/backoffice.Dockerfile`; `.github/workflows/cd.yml`, `release.yml`, `image-scan.yml`.

The apps:
- `backend/settings.gradle.kts`, `backend/build-logic/src/main/kotlin/helper2.spring-app.gradle.kts` — the projects;
  the contract per app.
- `backend/apps/public`, `backend/apps/backoffice`, `backend/libs/web-contract`, `backend/libs/sources`,
  `backend/libs/catalog/src/main/java/ai/helper2/catalog/ScoutPolicy.java`.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` — `server.shutdown`, `spring.lifecycle.timeout-per-shutdown-phase`,
  `H2_PAGES_TEMPLATE`, `H2_SCOUT_TICK`.
- `backend/libs/schema/src/main/java/ai/helper2/platform/schema/MigrateStep.java`, `SchemaCheck.java` — the migrate
  step and the apps' schema check.
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/security/BackOfficeSecurityConfiguration.java`,
  `BackOfficeEdge.java`, `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/AdminWebApp.java`.
- `backend/apps/public/src/main/java/ai/helper2/identity/security/PublicSecurityConfiguration.java`.
- `backend/apps/public/src/main/java/ai/helper2/site/pages/internal/PageTemplate.java`, `site/pages/web/PageRoutes.java`.
- `frontend/vite.admin.config.ts`, `frontend/src/api/client.ts` (either CSRF cookie name).
- Tests: `backend/apps/*/src/test/java/ai/helper2/api/SchedulesIT.java`, `BackOfficeEdgeIT.java`, `HttpEdgeIT.java`;
  `make chart-check`.

## Alternatives considered

- An Ingress controller with a public Internet Protocol (IP) address — opens a port on the cluster and needs
  certificate management.
- A Cloudflare tunnel per app (a `cloudflared` Deployment in each namespace) — one more workload, egress rule and
  token per environment, for the path the platform's tunnel already serves.
- An Ingress template in the chart — it ties the chart to one controller's annotations, and the platform keeps
  every Ingress of the cluster in one place.
- One replica with `Recreate` — a short outage on every deploy and no failover.
- Redis as a Deployment with `Recreate` and a separate PersistentVolumeClaim — the same single pod, but the claim is
  one more template to keep in step, and a StatefulSet already guarantees that two pods never share the volume.
- A Horizontal Pod Autoscaler — the load is small and steady; a fixed count keeps the connection budget known.
- Managed PostgreSQL — a monthly cost and data outside the owner's cluster for a small database.
- Kustomize — no release history, no `--rollback-on-failure` and no value checks; Helm has all three.
- Separate databases per service — the back office writes what the public app reads; keeping them in sync needs an
  API or a queue, without a named need for either.
- One jar, two deployments by profile — the admin code would still ship to the public pods.
- A Node server for the HTML — rewrites `site.pages` for no gain; or a fully static frontend, which loses the SEO
  pages and the nonce CSP.
- A published library — versions and a registry to keep in step for code that changes in the same pull request.
- A copy of the search controllers in the back office — two mappers of the same contract types to keep equal.

## Consequences

- Visitors reach the apps only through Cloudflare and ingress-nginx; the back office only from the in-network ranges.
  No other pod reaches either app.
- A compromised app pod can reach only the internet on 443, its own database role and Redis user, and the trace
  collector, and holds no Kubernetes API credential.
- The back office is closed by a fourth layer: its code is not in the public image.
- A deploy or a node drain keeps the site up on prod, and one pod failing leaves two serving. On staging too, a deploy
  starts the new pod before the old one stops.
- Postgres connections: (3 public replicas + 1 during a deploy) × `H2_DATABASE_POOL_SIZE` (16) = 64, plus the back
  office (2 × 16 during its deploy) and at worst one pod still terminating: 112, under the chart's `max_connections`
  of 150 (`postgres.maxConnections`; the backup and `psql` need a few more). A change of it restarts Postgres.
- The Postgres and Redis volumes are not owned by the release, so the data outlives the release.
- A Scout change ships without touching the public pods, and a long crawl does not compete with a visitor's
  question.
- Two apps share one schema. A migration must keep the running back office working until it is replaced.
  The other way round, a record the newer app wrote must still open in the older one: `ResourceRows` reads a value
  it does not know as unknown (a popularity kind is skipped, a pricing, level, audience or event kind is empty, a
  type is `OTHER`), and a description whose origin it does not know is not shown, so no text is shown without
  knowing whether AI wrote it (`StoredValuesOfANewerVersionApiIT`). A resource row's `data_origin` has no safe
  reading and still fails.
- More to run: three images, three workloads and a Job per release. Still one chart, one database, one Redis.
- A web app change deploys all three with one tag; only the changed images are built, the others get the new tag on
  their previous digest ([0044](0044-ci-cd-and-supply-chain.md)).
- Helm rolls the three Deployments at once; the order web, public, back office is not enforced. In practice the web
  pods are ready in seconds and a public pod needs its schema check and Lingua start, so new web pods come first.
  During a rollout a page from an old public pod can still name an asset hash the new web pods no longer have (a
  404 for that script until the visitor reloads), and a page from a new public pod can, for a few seconds, name a
  hash only the new web pods have (open point).
- Both workloads still read the whole env Secret, so the public pod holds the back office's keys (the Scout's sources)
  and the other way round (open point).
- The Settings page shows the back office's groups and the site-wide caps; the public app's other guide and setup
  settings are not there (open, see Backlog).
- Each app reports under its own name: metrics `application="helper2"` and `application="helper2-backoffice"`, traces
  `service.name` the same. Each has its own Grafana dashboard and its own alert rules
  ([0009](../foundation/0009-observability.md)).

### AI architecture check

| # | Layer | Status |
|---|---|---|
| 1 | Model | covered: the same NVIDIA models; both apps use the platform library's client |
| 3 | Orchestration | covered: every job in one app (`SchedulesIT` per app), ShedLock across replicas, the migrate Job before the apps start |
| 4 | Tool & API | covered: the admin API in its own contract and app |
| 9 | Guardrails | covered: the evidence gate and the quote checks moved with the Scout, unchanged |
| 10 | Observability | covered: the back office reports as `helper2-backoffice`, with its own dashboard and alerts (Scout runs and collectors, budgets and credits, the indexer, the review queue; [0009](../foundation/0009-observability.md)); the backup alerts; model ids per answer unchanged |
| 12 | Deployment | covered: three images and three workloads with one tag; the public pod's shell from the web image of that tag; `helm --wait` and a rollback on failure. Deferred: the rollout order and the previous release's assets (open point) |
| 13 | Security & Identity | covered: no ServiceAccount token, network policy per workload, a database role and a Redis user per app, ADMIN and the admin session only on the back office, `__Host-` cookies, Origin and header CSRF. Deferred: an env Secret per app (open point) |
| 15 | Cost & Reliability | covered: budgets and quotas in the shared Redis; PodDisruptionBudgets; nightly backups with a verification gate; the public site does not depend on the back office being up |

Layers 2, 5, 6, 7, 8, 11 and 14 do not change with the topology.

### Known limits

- Each app role has a statement timeout (5 s public, 60 s back office), not a socket timeout: a connection whose network path dies without a reset waits for the operating system. The values are not measured yet: read the staging logs for "canceling statement due to statement timeout" before prod. The pool (16 a replica), the catalog bulkheads (3 a source) and `max_connections` (150) are not measured. They stay as they are until the load test, and the load test runs before the next release: read the pool wait, the calls the catalog bulkheads refuse and the search latency from it, then size the three together. A catalog bulkhead does not wait, so a fourth concurrent search on a replica gets no results from that catalog source.
- Startup is slower than a native binary: Flyway and the language models load first; the startup probe allows up to 300 s.
- Bookmarks and reports are user data; the backup is the only copy.
- Both apps upsert the same `source` rows at startup, from the same settings; an env Secret that differs between the two workloads would make them overwrite each other's `enabled` flag.
- A guide stream or any request still running 30 s after SIGTERM is cut during a deploy (`app.shutdown.drainSeconds`); the guide's own deadline is 90 s.
- Staging (1 replica) has no failover; a prod deploy starts pods one after another (up to 300 s each). Plain HTTP inside the cluster; sign-in for everyone changes that. Never run `helm uninstall`: it deletes the Postgres and Redis StatefulSets.

### Open points

- The web image does not keep the assets of the release before; the schema owner is a superuser; the Redis split is not narrower than one user per app; the apps share one env Secret.
- No restore of a backup is tested, and there is no restore-check job.
- The Scout stays on in staging after the tag.

## Diagrams

The chart and its runtime paths: [flow](../../diagrams/flow-0043-runtime-topology.svg) ·
[sequence](../../diagrams/sequence-0043-runtime-topology.svg) ·
the three deployables: [flow](../../diagrams/flow-0043-three-deployables.svg) ·
[sequence](../../diagrams/sequence-0043-three-deployables.svg) ·
[architecture context](../../diagrams/architecture-context.svg) · the topology itself:
[infrastructure](../../diagrams/infrastructure.svg)

## Backlog

- Features: [F6.1](https://github.com/silvioincalza/helper2.ai/issues/81) Back office as its own app, admin host over WireGuard, [F10.1](https://github.com/silvioincalza/helper2.ai/issues/107) Helm chart and three deployables.
- Open issues that change this decision:
  - [#174](https://github.com/silvioincalza/helper2.ai/issues/174) S1.8.3 Postgres and Redis exporters in the Helm chart and an alert on Redis evicted keys
  - [#178](https://github.com/silvioincalza/helper2.ai/issues/178) S1.10.2 To know when per-replica limits hurt
  - [#353](https://github.com/silvioincalza/helper2.ai/issues/353) S10.8.1 Prod recoverable and watched before the release
