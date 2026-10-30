# 0023 — Identity: anonymous-first; GitHub sign-in only for /admin; ADMIN by numeric id; bookmarks

**Status.** Accepted (2026-10-27). **Builds on:** [0003](0003-contract-first-rest-api.md), [0004](0004-persistence-and-provenance.md), [0005](0005-redis-disposable-cache.md), [0007](0007-configuration-and-secrets.md)

## Context
Search, the guide, resource pages and reports must work without an account: an account costs the user time and
costs us personal data to protect. Only the owner needs to sign in, for the back office (review queue, Scout runs).
The admin check must not depend on a value a user can change, such as a GitHub login name.
Visitors still want to keep a list of resources.

## Decision
1. **Anonymous by default** (`SecurityConfiguration`). `GET` search, recent, upcoming events,
   resources, sources and `/me`, and `POST` reports and guide, are open. `/api/v1/admin/**` and `/actuator/**` need
   `ADMIN` (health and info are open; see [0024](0024-http-edge-protection.md) for metrics). `/api/v1/me/bookmarks`
   needs a signed-in user. Any other `/api/**` path is denied. Application programming interface (API) calls without a session get 401, not a redirect.
2. **Sign-in only on /admin.** OAuth2 login exists only when a client registration exists: Spring profile
   `oauth-github` with `H2_GITHUB_OAUTH_CLIENT_ID` and `H2_GITHUB_OAUTH_CLIENT_SECRET`, scope `read:user`.
   Success returns to `<H2_FRONTEND_URL>/admin`, failure to `/admin?signin=failed`. The `SignIn` component is shown on
   the admin page only.
3. **ADMIN by immutable id.** `H2_ADMINS` is a comma-separated list of `registrationId:subject`. For GitHub the subject
   is the numeric user id, never the login. An OpenID Connect (OIDC) provider may also use `registrationId:email` when
   `email_verified` is true. Roles are set at sign-in; a change to `H2_ADMINS` needs a new sign-in.
4. **User row on sign-in.** `CurrentUser.signedIn` (on `AuthenticationSuccessEvent`) calls `Users.ensure`, an upsert of
   `app_user` (provider, subject, display name). No e-mail is stored. Later calls only read the row; a session without
   a row (for example after a database rebuild) gets one on first use.
5. **Bookmarks.** Anonymous: browser storage (`h2.bookmarks.v1`). Signed in: `GET/POST/DELETE /api/v1/me/bookmarks`.
   On sign-in the browser list is merged once into the account and then cleared. `Bookmarks.add` is idempotent and
   refuses the whole request (400) when the account would hold more than 500 (`MAX_PER_USER`). Bookmark web addresses (URLs) must be http or
   https and at most 2000 characters.
6. **Admin host (intended).** The back office is meant to live on `admin.helper2.ai`, reachable only through the
   WireGuard network via Traefik. The app adds no network filter of its own. This is not implemented: `deploy/` routes
   only `helper2.ai`, `www.helper2.ai` and the staging host through the Cloudflare tunnel, and neither staging nor
   prod enables `oauth-github` today, so nobody can sign in there.
7. **Sessions in Redis** (Spring Session Data Redis, namespace `helper2:session`), so every replica knows a signed-in
   admin. Timeout and cookie stay under `server.servlet.session`. Only sign-in creates a session: the saved-request
   cache is off (`NullRequestCache`), so an anonymous request never writes one. While Redis is down
   (`SessionConfiguration`): a request with a session cookie is served as anonymous (session reads go through a
   30 s circuit breaker), and sign-in redirects to `/admin?signin=failed`; any other request that fails on the session store gets 503.

## Consequences
- Good: the public site stores no account data; the privacy surface is small.
- Good: renaming a GitHub account cannot grant or remove ADMIN.
- Bad: the public pages offer no sign-in, so visitors keep bookmarks only in their own browser.
- Bad: today `/admin` and `/api/v1/admin/**` are on the public host, protected only by the role check.
- Trade-off: roles live in the session; removing an admin takes effect at their next sign-in.
- Trade-off: a Redis outage signs the admin out until it ends; the public site is not affected. Sessions share
  the 128 MB Redis cache (`allkeys-lru`), so under memory pressure an admin can be signed out.

## Alternatives considered
- Accounts for every visitor — more personal data and more work, for little gain.
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
- `backend/src/main/java/ai/helper2/identity/MeController.java` — `/api/v1/me`, bookmarks endpoints.
- `backend/src/main/java/ai/helper2/identity/internal/Users.java`, `Bookmarks.java` — `app_user`, `bookmark`, the 500 cap.
- `frontend/src/context/MeContext.tsx`, `BookmarksContext.tsx`, `frontend/src/components/SignIn.tsx`, `frontend/src/pages/AdminPage.tsx`.
- `backend/src/main/resources/application.yaml` — profile `oauth-github`, `helper2.security.admins`.

## Diagrams
[flow](../diagrams/flow-0023-identity-and-admin.svg) · [sequence](../diagrams/sequence-0023-identity-and-admin.svg)

## Roadmap
- [Serve the back office on admin.helper2.ai, reachable only through WireGuard via Traefik](../roadmap.md#domain-and-identity).
- [Register a production GitHub OAuth App, enable `oauth-github`, set `H2_ADMINS`](../roadmap.md#domain-and-identity).
- [Redis memory shared by sessions, rate limits and cache; alert on evictions](../roadmap.md#4-more-than-one-replica).
