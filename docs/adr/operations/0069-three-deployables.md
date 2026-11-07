# 0069 — Three deployables: web, public app, back office

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-hexagonal.md), [0003](../foundation/0003-contract-first-rest-api.md), [0027](0027-runtime-topology.md), [0035](../edge/0035-admin-host.md), [0045](../edge/0045-back-office.md), [0050](../frontend/0050-pages-for-crawlers.md), [0059](../frontend/0059-adsense.md), [0068](../foundation/0068-backend-libraries-and-apps.md)

## Context
One image did everything. The backend jar carried the built web app, and every pod served the public site, the back
office on a second port, the Scout and every nightly job. The back office code and its routes shipped in every public
pod; only a port filter and the role kept them closed. The Scout and the enrichment shared a pod with the search and
the guide, so a long crawl competed with a visitor's question for CPU, memory and database connections. A change to
the web app rebuilt and redeployed the backend.

## Decision
1. **Three deployables, one repo.** `web` (the built React app, static files only), `public` (search, guide, setup,
   the catalog pages, sign-in for users, saved items, news, events, the policy) and `backoffice` (the admin API and
   the admin web app, the Scout, its collectors and enrichment, the home-example check, the catalog's jobs). Each has
   its own image and image tag: `deploy/web.Dockerfile`, `deploy/Dockerfile`, `deploy/backoffice.Dockerfile`, built
   from one commit and deployed with the same tag.
2. **Shared code is a library in this repo** ([0068](../foundation/0068-backend-libraries-and-apps.md)). Apps hold the
   driving adapters (controllers, schedules) and the wiring. The public app has no admin and no Scout code; the back
   office has no guide, setup or SEO pages. Both libraries and apps keep their package names.
   - `libs/sources` holds the live search sources (GitHub, Hugging Face, npm, arXiv, the live web), their HTTP mapping
     and settings, the demo sources, and the descriptors of the Scout's collectors. Both apps search, and both list
     every source on `GET /api/v1/sources`. The collectors themselves stay in the back office.
   - `ScoutPolicy` is in `libs/catalog` with the limits the Scout reads, so the public policy endpoint
     ([0052](0052-policy-endpoint.md)) states the back office's numbers without its code.
   - The web adapters both apps serve the same way (search, sources, categories) are one source folder,
     `backend/apps/common-web`, compiled into each app against its own generated contract types.
3. **One contract per app.** The public app generates its controllers from `api/public.yaml`, the back office from
   `api/admin.yaml`, each with the parts of `api/common.yaml` it names; the shared types keep their names in both.
4. **One database, one Flyway owner.** Both apps use the same Postgres and Redis. The public app runs the migrations.
   The back office only validates at startup: on a release with a new migration it waits, restarted by Kubernetes,
   until the public app has migrated. Rollout order: public, then back office. Daily budgets and quotas stay in the
   shared Redis, so they hold across both apps. Two DB roles (`h2_public`, `h2_backoffice`) come later (roadmap).
5. **The back office only on the admin host** ([0035](../edge/0035-admin-host.md)). The Deployment `helper2-backoffice`
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
   the nonce ([0059](../frontend/0059-adsense.md)).
7. **Every job runs in one app.** The public app: guide, identity, search and shared-cache retention. The back office:
   the Scout's daily check, Scout, catalog and runtime-settings retention, the home-example check, the embedding
   indexer and the category backfill at startup. ShedLock still allows one run at a time across replicas. Each
   replica of either app refreshes its own runtime-settings snapshot; that reads, it does no shared work. A test per
   app (`SchedulesIT`) lists its jobs.
8. **What the owner sees stays whole.** The back office declares the guide's daily cap with the same `H2_` default,
   so its Settings page, its runtime settings ([0060](0060-runtime-settings.md)) and its overview's budgets still show
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
    into it ([0050](../frontend/0050-pages-for-crawlers.md)). The public image holds no web files.
11. **Same origin, split by path.** The Ingress of the public host sends `/assets/` (prefix), `/theme.js` and
    `/favicon.svg` (exact) to the Service `helper2-web`, everything else to the Service `helper2`. The web pods take
    traffic from the Ingress controller on 8080 only, and reach nothing.

Decided, built later: the two DB roles, and CI/CD per component (path filters, signing).

## Consequences
- A Scout change ships without touching the public pods, and a long crawl no longer competes with a visitor's question.
- The back office is closed by a fourth layer: its code is not in the public image.
- Two apps share one schema. A migration must keep the running back office working until it is replaced.
- More to run: three images, three workloads. Still one chart, one database, one Redis.
- A web app change still deploys all three with one tag; per-component CD comes later (roadmap).
- Helm rolls the three Deployments at once; the order web, public, back office is not enforced. In practice the web
  pods are ready in seconds and a public pod needs its Flyway and Lingua start, so new web pods come first.
- Known limit (roadmap): during a rollout, a page from an old public pod can name an asset hash that the new web pods
  no longer have (a 404 for that script until the visitor reloads), and a page from a new public pod can, for a few
  seconds, name a hash that only the new web pods have. Fix: keep the previous release's assets in the web image.
- Known limits (roadmap): both apps share one Redis password, so a public pod could write the back office's
  sessions; and both workloads read the whole env Secret.
- The Settings page shows the back office's groups and the guide's cap; the public app's other guide and setup
  settings are no longer there (roadmap).
- Dashboards and alerts that filter on `application="helper2"` see the public app only; the back office reports as
  `helper2-backoffice` (roadmap).

## Considered
- **Separate databases per service** — the back office writes what the public app reads; keeping them in sync needs
  an API or a queue, without a named need for either.
- **One jar, two deployments by profile** — the admin code would still ship to the public pods.
- **A Node server for the HTML** — rewrites the pages module for no gain; or **a fully static frontend**, which loses
  the SEO pages and the nonce CSP.
- **A published library** — versions and a registry to keep in step for code that changes in the same pull request.
- **A copy of the search controllers in the back office** — two mappers of the same contract types to keep equal.

## AI architecture check (delta)
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

## Code
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

## Diagrams
[flow](../../diagrams/flow-0069-three-deployables.svg) · [sequence](../../diagrams/sequence-0069-three-deployables.svg) · [architecture context](../../diagrams/architecture-context.svg) · [infrastructure](../../diagrams/infrastructure.svg)

## Roadmap
- [roadmap § 2](../../roadmap.md#2-operations) (the previous release's assets in the web image, DB roles, CI/CD per
  component, Grafana) and
  [§ 9](../../roadmap.md#9-known-limits).
