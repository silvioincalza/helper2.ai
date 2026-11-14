# 0036 — Identity and the admin host

**Depends on:** [0003](../foundation/0003-contract-first-rest.md), [0004](../foundation/0004-persistence-and-the-schema.md), [0005](../foundation/0005-redis-as-a-disposable-cache.md), [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0010](../foundation/0010-data-retention.md), [0037](0037-http-edge.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md).

## Context

Search, the guide, resource pages and reports must work without an account: an account costs the user time and
costs us personal data to protect. Sign-in serves two groups: the owner, who runs the back office (review queue,
Scout runs, audit trail), and the users who want their saved resources on every device.
The back office must not be reachable from the internet: one leaked session or one bug in a rule would open it to
everyone. End users must still sign in on the public site.
The admin check must not depend on a value a user can change, such as a GitHub login name. What an administrator
decides must be traceable to that administrator, and the trail must stay even if the back office is broken into.
A signed-in user's account holds personal data that its owner must be able to delete.
The platform runs ingress-nginx, cert-manager with DNS-01 and a private network (a VPN). A client on that network
reaches the cluster with an address from the platform's private ranges.

## Decision

1. **Anonymous by default** (`PublicSecurityConfiguration`). `GET` search, recent, upcoming events, resources,
   sources, categories, home examples, policy and `/me`, and `POST` reports and guide, are open. Health is open; see
   [0037](0037-http-edge.md) for metrics. `/api/v1/me/bookmarks`, `DELETE /api/v1/me` and
   `PATCH /api/v1/me/preferences` need a signed-in user. Any other `/api/**` path is denied. API calls without a
   session get 401, not a redirect; a refused one gets 403. Both answer the problem the contract promises
   (`sign-in-required`, `forbidden`; [0003](../foundation/0003-contract-first-rest.md)), never the container's
   error page. A session whose account is gone gets 401 `sign-in-again` (§6).
2. **Two apps, two hosts.** The public app (`backend/apps/public`) serves the public host. The back office app
   (`backend/apps/backoffice`, [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)) serves the admin host, on its own pods.
   Each listens on 8080.
   - The public app has no back office: no admin code, no admin routes. `/admin`, `/admin/**`, `/api/v1/admin` and
     `/api/v1/admin/**` answer 404 there (`NoBackOfficeHere`, before the session and security filters). The check
     reads the decoded, normalised path the container maps, so `%61dmin` or `x/../admin` cannot pass as another
     path. The 404 is a plain problem body with the same security headers as any other response; it does not echo
     the path.
   - The back office answers only its own paths: the admin web app (`/admin/**`), `/` (to `/admin`), `/resource/**`
     (to the public site), `/api/v1/admin/**` (ADMIN), `GET /api/v1/me`, the reads the admin pages make
     (`GET /api/v1/search|sources|categories`, ADMIN only), the sign-in and `POST /logout`. Anything else is a plain
     404 (`BackOfficeEdge`).
3. **Sign-in on both hosts, each with its own callback.** OAuth2 login exists only when a client registration
   exists: Spring profile `oauth-github` with `H2_GITHUB_OAUTH_CLIENT_ID` and `H2_GITHUB_OAUTH_CLIENT_SECRET`, scope
   `read:user`. The public app's redirect URI is `{baseUrl}/login/oauth2/code/github`. The back office pins its own:
   `H2_ADMIN_BASE_URL/login/oauth2/code/github`, never built from a forwarded Host. The GitHub app lists both. After
   sign-in the admin host returns to the relative `/admin`, the public host to `<H2_FRONTEND_URL>/`; a failed sign-in
   returns to the same place with `?signin=failed`. On the public site, "Sign in with GitHub" sits in the header and
   on the Saved page, only when the server offers a provider; the page the visitor was on waits in sessionStorage
   (`h2.signInReturn`, a path on this site only) and is opened again after the return.
4. **No GitHub token is kept.** Sign-in uses GitHub's access token once, to read who the user is. The apps never
   call GitHub for the user after that. Spring's default would keep the token per signed-in user in the replica's
   memory (`InMemoryOAuth2AuthorizedClientService`) for as long as the replica runs. Both apps' `oauth2Login` use
   `NoProviderTokens` instead, an `OAuth2AuthorizedClientRepository` that keeps nothing (`SignInConfiguration` makes
   it the one bean, so Spring Boot's default store is not created). The session holds the user and the roles, never
   the token. Of GitHub's user reply it keeps only the id and a name to show (`name`, or the login when there is none;
   `WebSecurity.keptAttributes`): a public e-mail, bio, location or company in the reply is dropped at sign-in.
5. **ADMIN by immutable id, only on the back office.** `H2_ADMINS` is a comma-separated list of
   `registrationId:subject`. For GitHub the subject is the numeric user id, never the login. An OpenID Connect
   provider may also use `registrationId:email` when `email_verified` is true. Only the back office gives the role:
   an admin who signs in on the public site is a plain user there, and a stolen public session never carries ADMIN.
   Roles are set at sign-in; a change to `H2_ADMINS` needs a new sign-in.
6. **User row on sign-in.** `SignedInUser.signedIn` (on `InteractiveAuthenticationSuccessEvent`, the event the
   sign-in filter publishes with the session's token) calls `Users.signedIn`, an upsert of `app_user`: provider,
   subject, display name, `last_signed_in_at` and one display setting, `radar_motion` (default true), nothing else
   (no login, no e-mail). `GET /api/v1/me` returns the setting as `preferences.radarMotion` (null for a guest);
   `PATCH /api/v1/me/preferences` (`ChangePreferences`, CSRF token required) changes it. The sign-in is the only
   write of the row: `CurrentUser.id()` (`SignedInUser`) reads it and never writes.
   **A session without a row must sign in again.** A signed-in session can outlive its row (the database was
   rebuilt and the browser still holds the cookie). `SignedInUser.id()` then throws
   `SignInAgain`. `KnownAccountOnly`, a filter of both chains that runs before the access rules, asks for the id
   first: on `SignInAgain` it ends the session (the session is deleted in Redis, the session cookie and the CSRF
   cookie are cleared) and answers 401 with the problem `sign-in-again`. Nothing else runs: no use case, no write,
   no audit entry. The next request of that browser is a visitor's. The public app checks `/api/v1/me/**`, the only
   paths that use the account, so search and the guide answer such a session as they answer a visitor. The back
   office checks `/api/**` and `/actuator/**`: there every call is made in an administrator's name. The frontend
   reacts to the code (`api/client.ts`, `MeContext`): it reads `/me` again as a visitor and shows "Please sign in
   again" with the sign-in, on the public site and in the back office.
7. **Bookmarks are a signed-in user's feature, one use case per action** (`identity.usecase`). Anonymous: browser
   storage (`h2.bookmarks.v1`). Signed in: `ListBookmarks` (`GET /api/v1/me/bookmarks`), `AddBookmark`
   (`PUT /api/v1/me/bookmarks/{resourceId}`), `RemoveBookmark` (`DELETE …/{resourceId}`, idempotent), and
   `MergeLocalBookmarks` (`POST /api/v1/me/bookmarks`), which joins the browser list to the account once, right after
   sign-in; the browser list is then cleared. Rules (`BookmarkRules`): a bookmark links to an http or https page of at
   most 2000 characters, and an account holds at most 500; adding or merging is idempotent (the first `savedAt`
   stays), and a merge is all or nothing (400). A bookmark keeps the resource id, title, link, type and `savedAt`, so
   it renders even when the resource left the catalog. `bookmark.resource_id` has no foreign key to `resource`, on
   purpose: a saved item must always render. A live result can be saved before its observed row is written, or when
   that write was dropped (the queue of [0004](../foundation/0004-persistence-and-the-schema.md) §6); a merge brings
   ids the browser kept for months; and retention and the orphan delete remove resources that people may have saved
   ([0010](../foundation/0010-data-retention.md)). A foreign key would refuse those saves or, with a cascade, silently
   delete saved items.
8. **Delete my account.** The signed-in user's name in the header opens `/account`, where "Delete my account" asks
   for confirmation, then calls `DELETE /api/v1/me` (CSRF token required). The controller ends the session first: a
   session that outlived the row would be told to sign in again on its next request (§6), and if ending it fails
   nothing is deleted. Then `DeleteMyAccount` deletes the `app_user` row; its bookmarks go by `ON DELETE CASCADE`. The daily
   counters are kept under a hash of the client address, never the account
   ([0027](../search/0027-live-web-store-first-daily-quota.md)), so there is nothing of the user's to delete in Redis. An issue report is never linked to an account, signed in
   or not (`issue_report` has no reporter column), so deleting the account leaves the reports as they are. Accounts
   with no sign-in for 24 months are deleted the same way by the public app's daily account purge
   (`PurgeInactiveAccounts`, [0010](../foundation/0010-data-retention.md)).
9. **Admin audit trail.** `admin_audit` holds one row per admin write: the administrator's provider subject (never
   an `app_user` reference, so the trail outlives the account), the action, the target, the time, the outcome and,
   for a setting, the value before and after and the reason. Every admin use case writes it through the `AuditTrail`
   port (in `platform`, so the catalog needs no identity) in the same transaction as the change, so an entry exists exactly when the change was committed.
   `GET /api/v1/admin/audit` (ADMIN, paged, newest first, at most 100 per page) reads it for `/admin/audit`. A
   decision on an item that is no longer open changes nothing: the entry says `NOT_OPEN` and the API answers 409.
   The database keeps the trail, whatever an app does. A plain `UPDATE`, `DELETE` or `TRUNCATE` fails for every role,
   the owner too. The triggers are `ENABLE ALWAYS`, so `SET session_replication_role = replica` does not silence them.
   The app roles cannot get past them: they own nothing, may not `SET` that mode and have no `TRIGGER` grant. The owner
   still can, with DDL (disable or drop a trigger), because it is the database's superuser; a non-superuser owner is an open point of
   [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md):
   - a trigger refuses `UPDATE`, and a statement trigger refuses `TRUNCATE` (the app roles have no `TRUNCATE` or
     `TRIGGER` grant anyway);
   - a trigger (`h2_audit_kept`) refuses to delete a row younger than 729 days: the shortest retention the apps accept
     is 730 days (`AuditLog.SHORTEST_RETENTION`), less one day for an app clock ahead of the database's. A row-level
     refusal would fail the whole `DELETE`, so the purge itself never asks for such a row: `JdbcAuditLog` also bounds
     its delete by the database's own clock (`at < now() - 729 days`);
   - only the back office deletes rows: its daily purge (`PurgeExpiredAuditEntries`, `AuditRetentionSchedule`)
     deletes the rows older than `H2_RETENTION_ADMIN_AUDIT` (default 730 days). Both apps refuse to start with a
     shorter period, so the purge never meets the trigger;
   - the public app has no right on `admin_audit` at all (`h2_public`: no `SELECT`, `INSERT`, `UPDATE` or `DELETE`).
     No public use case writes or reads the trail; account deletion writes no audit row. The administrators'
     GitHub ids are out of reach of the more exposed app.
10. **Daily limits on `/me`.** `GET /api/v1/me` has `limits`: the guide's fresh questions
    ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)), the web searches of search and of the guide
    ([0027](../search/0027-live-web-store-first-daily-quota.md)), each as `limit` and `remaining`, and `resetsAt` (the next
    00:00 UTC). The guide questions are null when the guide has no model; a web search count is null without an
    enabled web source or when Redis cannot answer. Identity reads them through the `VisitorLimits` port, which the
    guide implements (`GuideVisitorLimits`): the same counters that enforce the limits, by `ClientKey`, signed in
    or not. Reading never counts. The response carries only numbers, never the address or its hash.
11. **Sessions in Redis, cookies per host** (Spring Session Data Redis, as JSON). Every replica knows a signed-in
    user. Only sign-in creates a session: the saved-request cache is off (`NullRequestCache`), so an anonymous
    request never writes one. The public cookies are host-only (`SESSION`, `XSRF-TOKEN`, no `Domain`), sessions under
    `h2:v1:session:public`. The back office uses `__Host-SESSION` and `__Host-XSRF-TOKEN` (Secure, `Path=/`, no
    `Domain`, so no sibling host can set them), sessions under their own namespace `h2:v1:session:backoffice`, so a
    public session id is never one there. A session ends 30 minutes after its last request, or at sign-out, in
    both apps: `server.servlet.session.timeout: 30m` in `helper2-shared.yaml`, set, not Spring's default, because
    the Privacy page states it (`SessionTimeoutBindingTest`). While Redis is down (`SessionConfiguration`): a request with a session
    cookie is served as anonymous (session reads run under the `redis` policy,
    [0006](../foundation/0006-outbound-calls-and-resilience.md)), sign-in returns to its page with `?signin=failed`,
    and any other request that fails on the session store gets 503.
12. **The edge decides who reaches the admin host.** The chart ships the Service `helper2-admin` (port 80 → the back
    office pods). The platform repo (outside this repo) routes the admin host to it with the Ingress `helper2-admin`
    on ingress-nginx: an allow-list of the private ranges, a DNS-only record to the ingress IP and a DNS-01
    certificate. The public names go through Cloudflare to the Ingress `helper2-public`, which routes only to the
    Service `helper2`. No public name reaches the back office. Only the ingress controller's pods
    (`networkPolicy.ingressController`) may reach either app.
13. **A write on the admin host comes from the admin host.** The public site and the admin host are the same site for
    the browser (`helper2.ai`, `admin.helper2.ai`), so a `SameSite` cookie does not keep them apart, and a page of the
    public site could plant a CSRF cookie for the whole domain. Two checks close that:
    - On the back office, a POST, PUT, PATCH or DELETE must carry an `Origin` that names the host the request went to
      (`SameOriginWrites`, before the CSRF check). No Origin, `null` or another host: 403 problem.
    - The CSRF token counts only in the `X-XSRF-TOKEN` header, in both apps, never as a `_csrf` form field, and no
      generated login or logout page writes it into HTML. A form from another page cannot set a header, and a script
      on another origin cannot send one without CORS, which neither app allows.

**Threats to a session without an account (STRIDE).**

| Threat | Answer |
|---|---|
| Spoofing | Only the holder of a valid session cookie gets `sign-in-again`. The session is deleted on the server and the cookie cleared, so the cookie is worth nothing afterwards. A new account needs a real sign-in at the provider. |
| Tampering | The request stops in the filter, before the access rules and every use case: nothing is written, no `app_user` row is created outside the sign-in. |
| Repudiation | An administrator session without an account writes nothing, so the audit trail gets no entry in the name of a user the database does not know. |
| Information disclosure | The answer is the same fixed problem for every such session and names no account. A request without a session gets `sign-in-required` as before, so the code tells nothing about which accounts exist. Nothing is logged. |
| Denial of service | One indexed read of `app_user` per signed-in request on the checked paths. Without a session there is no read. The rate limits of [0037](0037-http-edge.md) apply as before. |
| Elevation of privilege | ADMIN comes only from a sign-in on the back office (§5). Ending the session removes the roles with it; the allow-list is asked again at the new sign-in. |

| Host                                                  | App         | Back office | Sign-in                  | ADMIN            |
|-------------------------------------------------------|-------------|-------------|--------------------------|------------------|
| helper2.ai, `<staging host>` (public)                 | public      | 404         | yes, returns to `/`      | never            |
| admin.helper2.ai, `<staging admin host>` (VPN only)   | back office | yes         | yes, returns to `/admin` | allow-listed ids |

### Code

- `backend/libs/core/` — the library both apps use. Core: `Users.java`, `CurrentUser.java`, `Bookmarks.java`, `AuditLog.java`
  (`SHORTEST_RETENTION`), `VisitorLimits.java`, `AccountPolicy.java`. Use cases:
  `identity/usecase/` (`AddBookmark`, `RemoveBookmark`, `ListBookmarks`, `MergeLocalBookmarks`, `BookmarkRules`,
  `ShowPreferences`, `ChangePreferences`, `DeleteMyAccount`, `ListAuditEntries`, `PurgeInactiveAccounts`,
  `PurgeExpiredAuditEntries`). Adapters: `identity/internal/JdbcUsers.java`, `JdbcBookmarks.java`, `JdbcAuditLog.java` (implements
  `platform/AuditTrail.java`, the port every admin use case writes),
  `SignedInUser.java` (the `CurrentUser` port that controllers take: the signed-in user's id and display name, read
  only), `identity/SignInAgain.java` (the problem of a session without an account).
  Shared setup: `identity/config/WebSecurity.java`, `SameOriginWrites.java`, `KnownAccountOnly.java`, `SignInPages.java`,
  `SessionConfiguration.java`, `NoProviderTokens.java`, `SignInConfiguration.java`, `IdentityUseCaseConfiguration.java`,
  `IdentityRetentionProperties.java` (the 730-day floor).
- `backend/apps/public/src/main/java/ai/helper2/identity/security/PublicSecurityConfiguration.java` — the public
  chain, never ADMIN, `NoBackOfficeHere`; `identity/web/MeController.java` — `/api/v1/me`, bookmarks, account
  deletion; `identity/schedule/AccountRetentionSchedule.java` — the daily account purge.
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/security/BackOfficeSecurityConfiguration.java`,
  `BackOfficeEdge.java` — the back office chain, ADMIN for the allow-list, `__Host-` cookies, route allow-list,
  headers; `identity/web/AuditController.java` — `/api/v1/admin/audit`;
  `identity/schedule/AuditRetentionSchedule.java` — the daily audit purge.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `admin_audit` and its three triggers
  (`h2_append_only`, `h2_audit_kept`), `issue_report` without a reporter, the `h2_public` grants.
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/ResolveReviewItem.java`,
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/StartScoutRun.kt` — admin use cases that write the
  trail.
- Tests: `backend/libs/core/src/test/java/ai/helper2/identity/` (`architecture/IdentityArchitectureTest.java`,
  `usecase/PurgeInactiveAccountsTest.java`, `PurgeExpiredAuditEntriesTest.java`, `config/AdminAllowListTest.java`,
  `internal/SignedInUserTest.java`, `config/KnownAccountOnlyTest.java`);
  `backend/apps/*/src/test/java/ai/helper2/identity/security/SessionWithoutAccountIT.java` (a real sign-in, the row
  deleted: 401 `sign-in-again`, the session gone, the next call a visitor's, nothing audited);
  `backend/apps/*/src/test/java/ai/helper2/identity/security/SignInKeepsNoTokenIT.java` (a real sign-in against
  `FakeSignInProvider` leaves no token in the session or in memory); `backend/apps/public/src/test/java/ai/helper2/AdminAuditKeptIT.java`
  (the triggers), `AppRolesGrantsIT.java`, `identity/schedule/AccountRetentionIT.java`, `api/AccountApiIT.java`,
  `api/HttpEdgeIT.java`, `api/RedisDownIT.java`, `identity/security/SignInEventIT.java`;
  `backend/apps/backoffice/src/test/java/ai/helper2/identity/schedule/AuditRetentionIT.java`, `api/AdminAuditApiIT.java`,
  `api/BackOfficeEdgeIT.java`.
- `frontend/src/context/MeContext.tsx`, `BookmarksContext.tsx`, `frontend/src/features/account/SignIn.tsx`,
  `frontend/src/features/account/AccountPage.tsx`, `frontend/src/admin/`.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml`, `backend/apps/*/src/main/resources/application.yaml` — profile `oauth-github`, `helper2.identity.admins`,
  `helper2.retention.admin-audit`; `deploy/helm/helper2/templates/backoffice.yaml` (Deployment `helper2-backoffice`,
  Service `helper2-admin`), `networkpolicy.yaml`, `values*.yaml`.

## Alternatives considered

- Accounts for every visitor — more personal data and more work, for little gain.
- A foreign key from `bookmark` to `resource` — refuses a save of a result the catalog has not stored, and a cascade
  deletes saved items with their resource; with RESTRICT, retention and the orphan delete would have to keep every
  saved resource.
- Creating the missing row on first use — a read that writes: an account appears without a sign-in,
  `last_signed_in_at` says a sign-in that did not happen, and the 24-month purge counts from it.
- Serving a session without a row as a visitor, silently — the page would show a signed-out state with no reason,
  and the session would stay in Redis until it times out.
- Checking the row on every public path — one more database read on each signed-in search, for paths that never use
  the account.
- Deleting an account by e-mail request only — slower for the user, and work for the owner each time.
- Keeping the reporter on an issue report (a nullable `reporter_user_id`) — personal data the reviewer never needs:
  a report is judged by its text.
- Keeping the GitHub token, in memory or in the session — nothing reads it; it would only be one more secret to
  lose.
- Audit entries in the application log — logs are kept 90 days and can be filtered or lost; the audit trail must be
  queryable and kept 24 months.
- An audit table that references `app_user` — deleting the administrator's account would delete or orphan the trail.
- A plain `DELETE` trigger on `admin_audit` — no purge could run; the trail would grow without end, against the
  retention on the Privacy page.
- The audit purge in the public app, or any right of the public app on the trail — the public
  app, the more exposed of the two, would hold or reach the administrators' ids and could change the trail.
- ADMIN by GitHub login — logins can be renamed and taken by someone else.
- An IP address allow-list inside the app, or a path prefix on the public host — one rule away from the internet,
  and the app would have to trust forwarded addresses. The network edge decides who reaches the admin host.
- A second port in one app — the back office code and routes shipped in every public pod; only a port filter and the
  role kept them closed.
- Sign-in only on the admin host — no end-user accounts, so no server bookmarks.
- A shared admin password or API key — no per-person identity, and one more secret to rotate.
- Sessions in each pod's memory with sticky routing — the edge and the Service do not pin a client to one pod, and a
  deploy would sign the admin out. Sessions in PostgreSQL — a durable store and cleanup job for short-lived data.

## Consequences

- An anonymous visitor leaves no account data. A signed-in user's account holds an id, a name, the last sign-in, one
  display setting and the bookmarks, and its owner deletes it in two clicks. The daily counters are never kept under the account. A report
  says nothing about who sent it.
- No GitHub token sits in Redis, in its AOF file or in a replica's memory: a leaked session or a heap dump gives no
  access to anyone's GitHub account. The apps cannot act on GitHub for a user; nothing needs that.
- Every admin decision names the administrator. No statement an app can send edits, empties or cuts the trail
  short: someone who takes over the back office can add rows but not remove the last 729 days, because its role does
  not own the table and cannot touch its triggers. The owner role still could, with DDL, while it is a superuser
  (an open point of [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
  A shorter audit retention than 730 days needs a migration, not only a setting.
- A user row exists only for someone who signed in. After a database rebuild, the open sessions end at their next
  account call and each user signs in once more; the back office does not act for a user the database does not know.
- Renaming a GitHub account cannot grant or remove ADMIN.
- The back office is closed at four layers: the network edge, its code not being in the public image, the route
  allow-list and the role. Its writes need the admin host's own Origin and the token in a header, so a page elsewhere
  on the site cannot make a signed-in admin's browser write.
- No rate limit keyed on an address on the admin host: there is no Cloudflare in front of it, so a forwarded address
  there could be the client's own choice. The key is the signed-in administrator, and one shared key for whoever is
  not signed in yet (`RateLimitInterceptor` asks the app's `RateLimitKey`: the back office gives `SIGNED_IN_ADMIN`, the public
  app `CLIENT_ADDRESS`).
- A Redis outage touches only signed-in sessions; the public site keeps working.
- The GitHub app needs two callback URLs per environment. Local development runs both apps: the public one on 8080,
  the back office on 8090 (its `dev` profile).

### Known limits

- Roles live in the session: removing an admin takes effect at the next sign-in; a Redis outage signs the admin out.
- A public session whose account is gone is told to sign in again on `/api/v1/me/**` only; search and the guide answer it as a visitor until the page reads `/me`. `GET /api/v1/me` reads the account row twice (the check, then the preferences).
- No GitHub token is kept after sign-in, so the apps cannot ask GitHub anything about a user later (a renamed account, an org membership). A feature that needs that must ask the user to sign in again for it.
- A GitHub OAuth App allows only one callback URL, and each environment needs two (public host and admin host): use a GitHub App, or one OAuth App per host. A manual step, outside the repo.

## Diagrams

[flow](../../diagrams/flow-0036-identity-and-admin-host.svg) · [sequence](../../diagrams/sequence-0036-identity-and-admin-host.svg)

## Backlog

- Features: [F5.1](https://github.com/silvioincalza/helper2.ai/issues/73) Anonymous-first, GitHub sign-in, minimal profile, [F6.1](https://github.com/silvioincalza/helper2.ai/issues/81) Back office as its own app, admin host over WireGuard.
- Open issues that change this decision:
  - [#179](https://github.com/silvioincalza/helper2.ai/issues/179) S1.10.3 My session not to be evicted when the cache fills
  - [#294](https://github.com/silvioincalza/helper2.ai/issues/294) S5.6.1 To sign in without GitHub
  - [#295](https://github.com/silvioincalza/helper2.ai/issues/295) S5.7.1 To choose between Keycloak and plain Spring Security sign-in
