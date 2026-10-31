# 0023 — Identity: anonymous-first; GitHub sign-in; ADMIN by numeric id; bookmarks and account deletion for signed-in users; admin audit trail

**Status.** Accepted (2026-10-27). **Builds on:** [0003](0003-contract-first-rest-api.md), [0004](0004-persistence-and-provenance.md), [0005](0005-redis-disposable-cache.md), [0007](0007-configuration-and-secrets.md), [0034](0034-data-retention.md)

## Context
Search, the guide, resource pages and reports must work without an account: an account costs the user time and
costs us personal data to protect. Sign-in serves the back office (review queue, Scout runs) and the users who want
their saved resources on every device.
The admin check must not depend on a value a user can change, such as a GitHub login name.
Visitors still want to keep a list of resources; a signed-in user keeps it on the server, so an account holds
personal data that its owner must be able to delete. What an administrator decides (review items, Scout runs) must be
traceable to that administrator.

## Decision
1. **Anonymous by default** (`SecurityConfiguration`). `GET` search, recent, upcoming events,
   resources, sources and `/me`, and `POST` reports and guide, are open. `/api/v1/admin/**` and `/actuator/**` need
   `ADMIN` (health and info are open; see [0024](0024-http-edge-protection.md) for metrics). `/api/v1/me/bookmarks`
   and `DELETE /api/v1/me` need a signed-in user. Any other `/api/**` path is denied. Application programming interface (API) calls without a session get 401, not a redirect.
2. **Sign-in only on /admin.** OAuth2 login exists only when a client registration exists: Spring profile
   `oauth-github` with `H2_GITHUB_OAUTH_CLIENT_ID` and `H2_GITHUB_OAUTH_CLIENT_SECRET`, scope `read:user`.
   Success returns to `<H2_FRONTEND_URL>/admin`, failure to `/admin?signin=failed`. The `SignIn` component is shown on
   the admin page only.
3. **ADMIN by immutable id.** `H2_ADMINS` is a comma-separated list of `registrationId:subject`. For GitHub the subject
   is the numeric user id, never the login. An OpenID Connect (OIDC) provider may also use `registrationId:email` when
   `email_verified` is true. Roles are set at sign-in; a change to `H2_ADMINS` needs a new sign-in.
4. **User row on sign-in.** `CurrentUser.signedIn` (on `AuthenticationSuccessEvent`) calls `Users.signedIn`, an upsert
   of `app_user`: provider, subject (GitHub: the numeric id), display name and `last_signed_in_at`, nothing else (no
   login, no e-mail). Later calls only read the row; a session without a row (for example after a database rebuild)
   gets one on first use.
5. **Bookmarks are a signed-in user's feature, one use case per action** (`identity.usecase`). Anonymous: browser
   storage (`h2.bookmarks.v1`). Signed in: `ListBookmarks` (`GET /api/v1/me/bookmarks`), `AddBookmark`
   (`PUT /api/v1/me/bookmarks/{resourceId}`), `RemoveBookmark` (`DELETE …/{resourceId}`, idempotent), and
   `MergeLocalBookmarks` (`POST /api/v1/me/bookmarks`), which joins the browser list to the account once, right after
   sign-in; the browser list is then cleared. Rules (`BookmarkRules`): a bookmark links to an http or https page of at
   most 2000 characters, and an account holds at most 500; adding or merging is idempotent (the first `savedAt`
   stays), and a merge is all or nothing (400). A bookmark keeps the resource id, title, link, type and `savedAt`, so it
   renders even when the resource left the catalog. `MeController` only maps requests; `Bookmarks` and `Users` only
   run SQL. The Saved page (`/saved`, in the header) shows the browser list to a guest ("Saved in this browser
   only.") and the account list to a signed-in user ("Saved on your account."), with a loading state and Try again
   when the account list cannot load; a removal the account refuses puts the item back and says so.
6. **Delete my account.** The signed-in user's name in the header opens `/account`, where "Delete my account" asks
   for confirmation, then calls `DELETE /api/v1/me` (CSRF token required). `DeleteMyAccount` deletes the `app_user`
   row; its bookmarks go by `ON DELETE CASCADE`, and the user's issue reports stay with `reporter_user_id` set to null
   (`ON DELETE SET NULL`). It then deletes the user's daily web-search counters in Redis
   ([0037](0037-tavily-store-and-daily-quota.md)); while Redis is down they stay until their 24 h expiry. The
   controller ends the session, so the next request does not recreate the account; the page
   reloads signed out. Accounts with no sign-in for 24 months are deleted the same way by the identity purge
   ([0034](0034-data-retention.md)).
7. **Admin audit trail.** `admin_audit` holds one row per admin write: the administrator's provider subject, the
   action (`RESOLVE_REVIEW_ITEM`, `START_SCOUT_RUN`), the target (review item id, source id, or none for every
   source), the time and the outcome (`ACCEPTED`, `REJECTED`, `NOT_OPEN`, `NOT_FOUND`; `STARTED`, `BUSY`). Every
   admin use case writes it through the `AuditTrail` port: `ResolveReviewItem` in the same transaction as the decision,
   so an entry exists exactly when the decision was committed; `StartScoutRun` once the run holds its lock and has
   saved its run records (or was refused), since the run itself goes on in the background. The table is append-only: a
   trigger refuses `UPDATE`, and only the 24-month retention deletes rows. `GET /api/v1/admin/audit` (ADMIN, paged
   like the review items, newest first, at most 100 per page) reads it for the back office.
8. **Admin host (intended).** The back office is meant to live on `admin.helper2.ai`, reachable only through the
   WireGuard network via Traefik. The app adds no network filter of its own. This is not implemented: `deploy/` routes
   only `helper2.ai`, `www.helper2.ai` and the staging host through the Cloudflare tunnel, and neither staging nor
   prod enables `oauth-github` today, so nobody can sign in there.
9. **Sessions in Redis** (Spring Session Data Redis, namespace `helper2:session`), so every replica knows a signed-in
   admin. Timeout and cookie stay under `server.servlet.session`. Only sign-in creates a session: the saved-request
   cache is off (`NullRequestCache`), so an anonymous request never writes one. While Redis is down
   (`SessionConfiguration`): a request with a session cookie is served as anonymous (session reads run under the
   `redis` policy, [0033](0033-resilience-policies.md)), and sign-in redirects to `/admin?signin=failed`; any other request that fails on the session store gets 503.

## Consequences
- Good: an anonymous visitor leaves no account data; a signed-in user's account holds two fields and the bookmarks,
  and its owner deletes it, with its web-search counters, in two clicks.
- Good: every admin decision names the administrator, and the trail cannot be edited.
- Good: renaming a GitHub account cannot grant or remove ADMIN.
- Bad: the public pages offer no sign-in, so visitors keep bookmarks only in their own browser.
- Bad: today `/admin` and `/api/v1/admin/**` are on the public host, protected only by the role check.
- Trade-off: roles live in the session; removing an admin takes effect at their next sign-in.
- Trade-off: a Redis outage signs the admin out until it ends; the public site is not affected. Sessions share
  the 128 MB Redis cache (`allkeys-lru`), so under memory pressure an admin can be signed out.

## Alternatives considered
- Accounts for every visitor — more personal data and more work, for little gain.
- Deleting an account by e-mail request only — slower for the user, and work for the owner each time.
- Audit entries in the application log — logs are kept 90 days and can be filtered or lost; the audit trail must be
  queryable and kept 24 months.
- An audit table that references `app_user` — deleting the administrator's account would delete or orphan the trail.
- ADMIN by GitHub login — logins can be renamed and taken by someone else.
- An IP (Internet Protocol) address allow-list inside the app — the owner chose the network edge (WireGuard and Traefik) for this.
- A shared admin password or API key — no per-person identity, and one more secret to rotate.
- Sessions in each pod's memory with sticky routing — the Cloudflare tunnel and the Service do not pin a client
  to one pod, and a deploy would sign the admin out.
- Sessions in PostgreSQL (Spring Session JDBC) — a durable store and cleanup job for short-lived data.

## Code
- `backend/src/main/java/ai/helper2/identity/SecurityConfiguration.java` — access rules, OAuth2 login, roles.
- `backend/src/main/java/ai/helper2/identity/SessionConfiguration.java` — session reads and writes while Redis is down.
- `backend/src/test/java/ai/helper2/api/RedisDownIT.java` — anonymous use and sign-in with Redis unreachable.
- `backend/src/main/java/ai/helper2/identity/CurrentUser.java` — user row on sign-in, current user id.
- `backend/src/main/java/ai/helper2/identity/MeController.java` — `/api/v1/me`, bookmarks endpoints, account deletion.
- `backend/src/main/java/ai/helper2/identity/usecase/` — `AddBookmark`, `RemoveBookmark`, `ListBookmarks`,
  `MergeLocalBookmarks`, `BookmarkRules` (http(s), 2000 characters, the 500 cap), `DeleteMyAccount`, `ListAuditEntries`.
- `backend/src/main/java/ai/helper2/identity/internal/Users.java`, `Bookmarks.java`, `AuditLog.java` — `app_user`,
  `bookmark`, `admin_audit`.
- `backend/src/main/java/ai/helper2/identity/AuditTrail.java`, `AuditController.java` — the port, `/api/v1/admin/audit`.
- `backend/src/main/java/ai/helper2/catalog/usecase/ResolveReviewItem.java`,
  `backend/src/main/kotlin/ai/helper2/scout/usecase/StartScoutRun.kt` — admin use cases that write the trail.
- `backend/src/test/java/ai/helper2/api/AccountApiIT.java`, `AdminAuditApiIT.java` — bookmarks, account deletion, audit.
- `frontend/src/context/MeContext.tsx`, `BookmarksContext.tsx`, `frontend/src/lib/bookmarks.ts`,
  `frontend/src/components/SignIn.tsx`, `BookmarkButton.tsx`, `frontend/src/pages/SavedPage.tsx`, `AccountPage.tsx`,
  `AdminPage.tsx`.
- `backend/src/main/resources/application.yaml` — profile `oauth-github`, `helper2.security.admins`.

## Diagrams
[flow](../diagrams/flow-0023-identity-and-admin.svg) · [sequence](../diagrams/sequence-0023-identity-and-admin.svg)

## Roadmap
- [Serve the back office on admin.helper2.ai, reachable only through WireGuard via Traefik](../roadmap.md#domain-and-identity).
- [Register a production GitHub OAuth App, enable `oauth-github`, set `H2_ADMINS`](../roadmap.md#domain-and-identity).
- [Sign-in for everyone: a public sign-in entry point for bookmarks and account deletion](../roadmap.md#domain-and-identity).
- [Redis memory shared by sessions, rate limits and cache; alert on evictions](../roadmap.md#4-more-than-one-replica).
