# 0024 — Identity and the admin host

**Status.** Accepted. Merges old 0023, 0035 (texts merged in PR 2 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0023"></a>

## Part: was ADR 0023 — Identity: anonymous-first; GitHub sign-in; ADMIN by numeric id; bookmarks and account deletion for signed-in users; admin audit trail

**Status.** Accepted. **Builds on:** [0003](../foundation/0003-contract-first-rest-api.md), [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0005](../foundation/0005-redis-disposable-cache.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0035](../operations/0035-data-retention.md)

### Context
Search, the guide, resource pages and reports must work without an account: an account costs the user time and
costs us personal data to protect. Sign-in serves the back office (review queue, Scout runs) and the users who want
their saved resources on every device.
The admin check must not depend on a value a user can change, such as a GitHub login name.
Visitors want to keep a list of resources; a signed-in user keeps it on the server, so an account holds
personal data that its owner must be able to delete. What an administrator decides (review items, Scout runs) must be
traceable to that administrator.

### Decision
1. **Anonymous by default** (`PublicSecurityConfiguration`). `GET` search, recent, upcoming events,
   resources, sources and `/me`, and `POST` reports and guide, are open. `/api/v1/admin/**` is not in the public app;
   the back office app serves it and needs `ADMIN` ([the part that was ADR 0035](#part-0035), [0032](../operations/0032-runtime-topology.md)).
   Health is open; see [0025](0025-http-edge-protection.md) for metrics. `/api/v1/me/bookmarks`
   and `DELETE /api/v1/me` need a signed-in user. Any other `/api/**` path is denied. Application programming interface (API) calls without a session get 401, not a redirect; a refused one gets 403. Both answer the problem the contract promises
   (`sign-in-required`, `forbidden`; [0003](../foundation/0003-contract-first-rest-api.md)), never the container's error page.
2. **Sign-in.** OAuth2 login exists only when a client registration exists: Spring profile
   `oauth-github` with `H2_GITHUB_OAUTH_CLIENT_ID` and `H2_GITHUB_OAUTH_CLIENT_SECRET`, scope `read:user`.
   It works on both hosts ([the part that was ADR 0035](#part-0035)): the public app returns to `<H2_FRONTEND_URL>/`, the back
   office to `/admin`; failure adds `?signin=failed`.
3. **ADMIN by immutable id.** `H2_ADMINS` is a comma-separated list of `registrationId:subject`; only a sign-in on
   the back office gets the role ([the part that was ADR 0035](#part-0035)). For GitHub the subject
   is the numeric user id, never the login. An OpenID Connect (OIDC) provider may also use `registrationId:email` when
   `email_verified` is true. Roles are set at sign-in; a change to `H2_ADMINS` needs a new sign-in.
4. **User row on sign-in.** `CurrentUser.signedIn` (on `InteractiveAuthenticationSuccessEvent`, the event the sign-in
   filter publishes with the session's token) calls `Users.signedIn`, an upsert
   of `app_user`: provider, subject (GitHub: the numeric id), display name and `last_signed_in_at`, and one display
   setting, `radar_motion` (default true), nothing else (no login, no e-mail). `GET /api/v1/me` returns the setting as
   `preferences.radarMotion` (null for a guest); `PATCH /api/v1/me/preferences` (`ChangePreferences`, CSRF token
   required) changes it. It goes with the row. Later calls only read the row; a session without a row (for example after a database rebuild)
   gets one on first use.
5. **Bookmarks are a signed-in user's feature, one use case per action** (`identity.usecase`). Anonymous: browser
   storage (`h2.bookmarks.v1`). Signed in: `ListBookmarks` (`GET /api/v1/me/bookmarks`), `AddBookmark`
   (`PUT /api/v1/me/bookmarks/{resourceId}`), `RemoveBookmark` (`DELETE …/{resourceId}`, idempotent), and
   `MergeLocalBookmarks` (`POST /api/v1/me/bookmarks`), which joins the browser list to the account once, right after
   sign-in; the browser list is then cleared. Rules (`BookmarkRules`): a bookmark links to an http or https page of at
   most 2000 characters, and an account holds at most 500; adding or merging is idempotent (the first `savedAt`
   stays), and a merge is all or nothing (400). A bookmark keeps the resource id, title, link, type and `savedAt`, so it
   renders even when the resource left the catalog. `MeController` only maps requests; `Bookmarks` and `Users` only
   run SQL.
6. **Delete my account.** The signed-in user's name in the header opens `/account`, where "Delete my account" asks
   for confirmation, then calls `DELETE /api/v1/me` (CSRF token required). The controller ends the session first: a
   session that outlived the row would recreate the account on the next request, and if ending it fails nothing is
   deleted. Then `DeleteMyAccount` deletes the `app_user` row; its bookmarks go by `ON DELETE CASCADE`, and the user's
   issue reports stay with `reporter_user_id` set to null (`ON DELETE SET NULL`). It then deletes the user's daily
   web-search counters in Redis ([0014](../search/0014-tavily-store-and-daily-quota.md)); while Redis is down they
   stay until their 24 h expiry. The page reloads signed out. Accounts with no sign-in for 24 months are deleted the same way by the identity purge
   ([0035](../operations/0035-data-retention.md)).
7. **Admin audit trail.** `admin_audit` holds one row per admin write: the administrator's provider subject, the
   action (`RESOLVE_REVIEW_ITEM`, `START_SCOUT_RUN`), the target (review item id, source id, or none for every
   source), the time and the outcome (`ACCEPTED`, `REJECTED`, `NOT_OPEN`, `NOT_FOUND`; `STARTED`, `BUSY`). Every
   admin use case writes it through the `AuditTrail` port: `ResolveReviewItem` in the same transaction as the decision,
   so an entry exists exactly when the decision was committed; `StartScoutRun` once the run holds its lock and has
   saved its run records (or was refused), since the run itself goes on in the background. The table is append-only: a
   trigger refuses `UPDATE`, and only the 24-month retention deletes rows. `GET /api/v1/admin/audit` (ADMIN, paged
   like the review items, newest first, at most 100 per page) reads it for the back office (`/admin/audit`).
   A decision on an item that is no longer open changes nothing: the entry says `NOT_OPEN` and the API answers 409, so
   the back office knows its decision was not applied. Not an exception inside the transaction, so the entry is kept.
8. **Admin host.** The back office is on its own port and host, reachable only from a private network:
   [the part that was ADR 0035](#part-0035). Each environment turns sign-in on with the `oauth-github` profile.
9. **Daily limits on `/me`.** `GET /api/v1/me` has `limits`: the guide's fresh questions
   ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)), the web searches of search and of the guide
   ([0014](../search/0014-tavily-store-and-daily-quota.md)), each as `limit` and `remaining`, and `resetsAt` (the next
   00:00 UTC). The guide questions are null when the guide has no model: it then answers from search only and spends
   nothing. A web search count is null without an enabled web source or when Redis cannot answer. Identity reads
   them through the `VisitorLimits` port, which the guide implements (`GuideVisitorLimits`): the same counters that
   enforce the limits, by `ClientKey` and, when signed in, the user id. Reading never counts. The response carries
   only numbers, never the address, its hash or the id.
9. **Sessions in Redis** (Spring Session Data Redis, namespace `h2:v1:session:public`), so every replica knows a signed-in
   admin. Timeout and cookie stay under `server.servlet.session`. Only sign-in creates a session: the saved-request
   cache is off (`NullRequestCache`), so an anonymous request never writes one. While Redis is down
   (`SessionConfiguration`): a request with a session cookie is served as anonymous (session reads run under the
   `redis` policy, [0006](../foundation/0006-outbound-calls-and-resilience.md)), and sign-in redirects to `/admin?signin=failed`; any other request that fails on the session store gets 503.

### Consequences
- An anonymous visitor leaves no account data; a signed-in user's account holds two fields and the bookmarks,
  and its owner deletes it, with its web-search counters, in two clicks.
- Every admin decision names the administrator, and the trail cannot be edited.
- Renaming a GitHub account cannot grant or remove ADMIN.
- A Redis outage touches only signed-in sessions; the public site keeps working.

### Alternatives considered
- Accounts for every visitor — more personal data and more work, for little gain.
- Deleting an account by e-mail request only — slower for the user, and work for the owner each time.
- Audit entries in the application log — logs are kept 90 days and can be filtered or lost; the audit trail must be
  queryable and kept 24 months.
- An audit table that references `app_user` — deleting the administrator's account would delete or orphan the trail.
- ADMIN by GitHub login — logins can be renamed and taken by someone else.
- An IP (Internet Protocol) address allow-list inside the app — the network edge decides who reaches the admin host ([the part that was ADR 0035](#part-0035)).
- A shared admin password or API key — no per-person identity, and one more secret to rotate.
- Sessions in each pod's memory with sticky routing — the edge and the Service do not pin a client to one pod,
  and a deploy would sign the admin out.
- Sessions in PostgreSQL (Spring Session JDBC) — a durable store and cleanup job for short-lived data.

### Code
- `backend/apps/public/src/main/java/ai/helper2/identity/config/PublicSecurityConfiguration.java` — access rules, OAuth2 login, roles.
- `backend/libs/identity/src/main/java/ai/helper2/identity/config/SessionConfiguration.java` — session reads and writes while Redis is down.
- `backend/apps/public/src/test/java/ai/helper2/api/RedisDownIT.java` — anonymous use and sign-in with Redis unreachable.
- `backend/libs/identity/src/main/java/ai/helper2/identity/internal/CurrentUser.java` — user row on sign-in, current user id.
- `backend/apps/public/src/main/java/ai/helper2/identity/web/MeController.java` — `/api/v1/me`, bookmarks endpoints, account deletion.
- `backend/libs/identity/src/main/java/ai/helper2/identity/VisitorLimits.java`, `guide/internal/GuideVisitorLimits.java` — the daily
  limits on `/me`.
- `backend/libs/identity/src/main/java/ai/helper2/identity/usecase/` — `AddBookmark`, `RemoveBookmark`, `ListBookmarks`,
  `MergeLocalBookmarks`, `BookmarkRules` (http(s), 2000 characters, the 500 cap), `DeleteMyAccount`, `ListAuditEntries`.
- `backend/libs/identity/src/main/java/ai/helper2/identity/Users.java`, `Bookmarks.java`, `AuditLog.java` — the ports of the use
  cases; `identity/internal/JdbcUsers.java`, `JdbcBookmarks.java`, `JdbcAuditLog.java` — `app_user`, `bookmark`,
  `admin_audit`; `identity/config/IdentityUseCaseConfiguration.java` — wiring and transactions.
- `backend/libs/identity/src/main/java/ai/helper2/identity/AuditTrail.java`, `AuditController.java` — the port, `/api/v1/admin/audit`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/ResolveReviewItem.java`,
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/StartScoutRun.kt` — admin use cases that write the trail.
- `backend/apps/public/src/test/java/ai/helper2/api/AccountApiIT.java`, `AdminAuditApiIT.java` — bookmarks, account deletion, audit;
  `identity/internal/SignInEventIT.java` — the sign-in filter's event writes the row, the provider manager's does not.
- `frontend/src/context/MeContext.tsx`, `BookmarksContext.tsx`, `frontend/src/components/SignIn.tsx`,
  `frontend/src/pages/AccountPage.tsx`, `frontend/src/pages/admin/`.
- `backend/apps/public/src/main/resources/application.yaml` — profile `oauth-github`, `helper2.identity.admins`.

### Diagrams
[flow](../../diagrams/flow-0024-identity-and-admin.svg) · [sequence](../../diagrams/sequence-0024-identity-and-admin.svg)

### Roadmap
- [Redis memory shared by sessions, rate limits and cache; alert on evictions](../../roadmap.md#3-more-than-one-replica).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0035"></a>

## Part: was ADR 0035 — Admin host: the back office app, ADMIN only there, sign-in on both hosts

**Status.** Accepted. **Builds on:** [the part that was ADR 0023](#part-0023), [0025](0025-http-edge-protection.md), [0032](../operations/0032-runtime-topology.md). **Changed by:** [0032](../operations/0032-runtime-topology.md) (the back office is its own app; the admin port is gone)

### Context
The back office (review queue, Scout runs, audit trail) and the end users' features (bookmarks, the registered tier of
web searches) both need GitHub sign-in, in every hosted environment.
The back office must not be reachable from the internet: one leaked session or one bug in a rule would open it to
everyone. End users must still sign in on the public site.
The platform runs ingress-nginx, cert-manager with DNS-01 and a private network (a VPN). A client on that network
reaches the cluster with an address from the platform's private ranges.

### Decision
1. **Two apps, two hosts.** The public app (`backend/apps/public`) serves the public host. The back office app
   (`backend/apps/backoffice`, ADR [0032](../operations/0032-runtime-topology.md)) serves the admin host, on its own
   pods. Each listens on 8080; there is no second port.
2. **The public app has no back office.** It has no admin code and no admin routes. `/admin`, `/admin/**`,
   `/api/v1/admin` and `/api/v1/admin/**` answer 404 there (`NoBackOfficeHere`, before the session and security
   filters). The check reads the decoded, normalised path the container maps, so `%61dmin` or `x/../admin` cannot pass
   as another path. The 404 is a plain problem body with the same security headers as any other response (CSP,
   `nosniff`, `DENY`, `Permissions-Policy`, `no-store`); it does not echo the path.
3. **The back office answers only its own paths.** The admin web app (`/admin/**`), `/` (to `/admin`), `/resource/**`
   (to the public site), `/api/v1/admin/**`, `GET /api/v1/me`, the reads the admin pages make
   (`GET /api/v1/search|sources|categories`, ADMIN only), the sign-in and `POST /logout`. Anything else is a plain
   404 (`BackOfficeEdge`).
4. **ADMIN only on the back office.** The allow-list (`H2_ADMINS`, [the part that was ADR 0023](#part-0023)) decides who is
   an admin, and only the back office gives the role. An admin who signs in on the public site is a plain user there.
   A stolen public session never carries ADMIN.
5. **Sign-in on both hosts, each with its own callback.** The public app's redirect URI is
   `{baseUrl}/login/oauth2/code/github`. The back office pins its own: `H2_ADMIN_BASE_URL/login/oauth2/code/github`,
   never built from a forwarded Host. The GitHub app must list both. After sign-in the admin host returns to the
   relative `/admin`, the public host to `/`. A failed sign-in returns to the same place with `?signin=failed`. On the
   public site, "Sign in with GitHub" sits in the header and on the Saved page, only when the server offers a
   provider; the page the visitor was on waits in sessionStorage (`h2.signInReturn`, a path on this site only) and is
   opened again after the return to `/`.
6. **Cookies of each host.** The public cookies are host-only (`SESSION`, `XSRF-TOKEN`, no `Domain`). The back office
   uses `__Host-SESSION` and `__Host-XSRF-TOKEN`: Secure, `Path=/`, no `Domain`, so no sibling host can set them. Its
   sessions are in their own Redis namespace (`h2:v1:session:backoffice`), so a public session id is never one there.
7. **The edge decides who reaches the admin host.** The chart ships the Service `helper2-admin` (port 80 → the back
   office pods). The platform repo (outside this repo) routes the admin host to it with the Ingress `helper2-admin`
   on ingress-nginx: an allow-list of the private ranges, a DNS-only record to the ingress IP and a DNS-01
   certificate. The public names go through Cloudflare to the Ingress `helper2-public`, which routes only to the
   Service `helper2`. No public name reaches the back office.
8. **Network policy.** Only the ingress controller's pods (`networkPolicy.ingressController`) may reach either app.
9. **A write on the admin host comes from the admin host.** The public site and the admin host are the same site for
   the browser (`helper2.ai`, `admin.helper2.ai`), so a `SameSite` cookie does not keep them apart, and a page of the
   public site could plant a CSRF cookie for the whole domain. Two checks close that:
   - On the back office, a POST, PUT, PATCH or DELETE must carry an `Origin` that names the host the request went to
     (`SameOriginWrites`, before the CSRF check). No Origin, `null` or another host: 403 problem.
   - The CSRF token counts only in the `X-XSRF-TOKEN` header, in both apps, never as a `_csrf` form field. A form
     from another page cannot set a header, and a script on another origin cannot send one without CORS, which
     neither app allows.

| Host                                                  | App         | Back office | Sign-in                  | ADMIN            |
|-------------------------------------------------------|-------------|-------------|--------------------------|------------------|
| helper2.ai, `<staging host>` (public)                 | public      | 404         | yes, returns to `/`      | never            |
| admin.helper2.ai, `<staging admin host>` (VPN only)   | back office | yes         | yes, returns to `/admin` | allow-listed ids |

### Consequences
- The back office is closed at four layers: the network edge, its code not being in the public image, the route
  allow-list and the role. Each one alone keeps it closed. Its writes need the admin host's own Origin and the token
  in a header, so a page elsewhere on the site cannot make a signed-in admin's browser write.
- No rate limit keyed on an address on the admin host. There is no Cloudflare in front of it, so a forwarded address
  there could be the client's own choice; a counter keyed on it would be the client's to steer. The limits still
  hold there: the key is the signed-in administrator, and one shared key for whoever is not signed in yet
  (`RateLimitInterceptor` asks `AdminHost`; the back office answers yes for every request). The network closes the
  host; the limit caps what one admin session can spend on the search through it.
- End users can sign in on the public site, so bookmarks are kept on the server and the registered tier is possible.
- The GitHub app needs two callback URLs per environment.
- Local development runs both apps: the public one on 8080, the back office on 8090 (its `dev` profile).

### Alternatives considered
- A path prefix on the public host behind an IP allow-list in the app — one rule away from the internet, and the app
  would have to trust forwarded addresses.
- A second port in one app (this ADR until [0032](../operations/0032-runtime-topology.md)) — the back office code and
  routes shipped in every public pod; only a port filter and the role kept them closed.
- Sign-in only on the admin host — no end-user accounts, so no server bookmarks and no registered tier.
- ADMIN for any allow-listed sign-in, in either app — a public session of the owner would be an admin session.

### Code
- `backend/apps/public/src/main/java/ai/helper2/identity/config/PublicSecurityConfiguration.java` — the public chain,
  never ADMIN, `NoBackOfficeHere`.
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/config/BackOfficeSecurityConfiguration.java`,
  `BackOfficeEdge.java` — the back office chain, ADMIN for the allow-list, `__Host-` cookies, route allow-list,
  headers.
- `backend/libs/identity/src/main/java/ai/helper2/identity/config/WebSecurity.java`, `SameOriginWrites.java`,
  `SignInPages.java`, `SessionConfiguration.java` — what both chains share; where a sign-in returns.
- `backend/apps/public/src/test/java/ai/helper2/api/HttpEdgeIT.java`, `backend/apps/backoffice/src/test/java/ai/helper2/api/BackOfficeEdgeIT.java`,
  `backend/libs/identity/src/test/java/ai/helper2/identity/config/AdminAllowListTest.java`.
- `deploy/helm/helper2/templates/backoffice.yaml` (Deployment `helper2-backoffice`, Service `helper2-admin`),
  `networkpolicy.yaml`, `values*.yaml`; `deploy/k8s/Makefile` (`chart-check`).

### Diagrams
[flow](../../diagrams/flow-0024-admin-host.svg) · [sequence](../../diagrams/sequence-0024-admin-host.svg)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
