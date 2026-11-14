# 0037 — HTTP edge

**Depends on:** [0001](../foundation/0001-stack-and-runtime.md), [0005](../foundation/0005-redis-disposable-cache.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0036](0036-identity-and-admin-host.md).

## Context

The app is public and anonymous. Search calls paid or rate-limited APIs, and each guide question costs model calls,
so one client must not use up shared budgets. The single-page app (SPA) and the application programming interface (API) share one origin, which must be
safe against cross-site requests and injected scripts. Metrics must not be public. Client routes such as `/sources`
must load the app on a direct visit, and a new release must reach browsers at once.

## Decision

1. **Rate limits per client** (`RateLimitInterceptor`). The key is the handler method the request was
   routed to (the contract operationId), not the raw path, so path tricks (`;param`, encoding, double slashes) do not
   bypass it. Limits: `search` 120/min (`H2_RATE_LIMIT_SEARCH_PER_MINUTE`), `reportResource` 20/hour
   (`H2_RATE_LIMIT_REPORT_PER_HOUR`), `askGuideStream` and `askResourceGuide` share 10/min (`H2_RATE_LIMIT_GUIDE_PER_MINUTE`),
   `listRecent` 60/min (`H2_RATE_LIMIT_RECENT_PER_MINUTE`). Everything else shares one default limit of 120/min
   (`H2_RATE_LIMIT_DEFAULT_PER_MINUTE`): every other operation under `/api/` (told by the route pattern Spring
   matched, not by the address), the server-rendered pages and `sitemap.xml`. So a new operation is limited from
   its first day, with no line to add. Static files, `robots.txt`, `ads.txt`, the error page and the management
   port are not counted. `RateLimitedOperationsTest` fails when an operation with its own limit is no longer in
   the contract. Over the limit: 429, `Retry-After`, problem+json. Only the first dispatch of a request is
   charged: the second (async) dispatch of the streamed guide is not, and neither is an error dispatch. On the admin host the client is the signed-in administrator,
   not an address ([0036](0036-identity-and-admin-host.md)).
2. **Limiter in Redis, shared by all replicas** (port `RateLimiter`, adapter `RedisRateLimiter`). Fixed window: one
   key per limiter and client, `h2:v1:rate:<limiter>:<client pseudonym>`. One Lua script runs `INCR` and, on the first request,
   `PEXPIRE` with the window, so a key never lives without an expiry. The key's remaining time is the `Retry-After`.
   The client is `platform.ClientKey.of(remote address)`: Internet Protocol version 4 (IPv4) as is, Internet Protocol
   version 6 (IPv6) by its /64 prefix, a zone id (`%eth0`) dropped, IPv4-mapped addresses as IPv4, a missing address
   as `unknown`. Both limiters and the guide's daily quota ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) use this one key.
   Redis never holds the address: the key holds `QuotaKeys.client(client)`, HMAC-SHA256(day key, client), where the
   day key is HMAC-SHA256(`H2_QUOTA_KEY_SECRET`, UTC date), the same pseudonym as the daily web searches
   ([0027](../search/0027-tavily-store-and-daily-quota.md)). Without the secret, an IPv4 address cannot be found again by
   hashing all 2^32 of them, and the keys of two days cannot be linked. At midnight UTC the pseudonym changes, so a
   window open at midnight is followed by a fresh one: at most one more window's worth of requests (two windows' worth
   in one window), the same as at any fixed-window edge. Without `H2_QUOTA_KEY_SECRET` the app starts, logs a warning
   and uses a random key of the process: still no address in Redis, but the counts restart with the process and each
   replica counts on its own. The in-memory fallback holds the `ClientKey` only in the replica's memory.
3. **In-memory fallback.** The script runs under the `redis` policy ([0006](../foundation/0006-outbound-calls-and-resilience.md)). On a Redis
   error, or while that circuit is open (30 s after repeated errors), each replica counts in its own
   `InMemoryRateLimiter` with the same limits (windows in start order, at most 50,000 clients, oldest
   evicted). Every such decision increments `helper2.rate-limit.fallback{limiter}`. Without Redis configured, only the
   in-memory limiter is used.
4. **Client address.** `H2_FORWARD_HEADERS_STRATEGY` defaults to `none`, so `X-Forwarded-For` is ignored. The cluster
   sets `native`: only the Ingress controller can reach the app's port 8080 (NetworkPolicy `ingress-app`). The chain
   is Cloudflare (appends the visitor's address to `X-Forwarded-For`), the platform's tunnel, then ingress-nginx
   (`compute-full-forwarded-for`: appends the tunnel pod's address). Tomcat's remote-IP handling reads the header from
   the right and skips private and loopback addresses (its default `internalProxies`), so the first public address is the one
   Cloudflare appended. A client can prepend addresses but cannot replace that one. The admin host has no Cloudflare in front, so
   no limit is keyed on an address there: the key is the signed-in administrator, or one shared key for whoever is
   not signed in yet ([0036](0036-identity-and-admin-host.md)).
5. **CSRF** (cross-site request forgery). `CookieCsrfTokenRepository.withHttpOnlyFalse()` with the SPA setup:
   cookie `XSRF-TOKEN`; `api/client.ts` sends it back as `X-XSRF-TOKEN` on every non-GET call. `CsrfCookieFilter` makes
   sure the cookie exists before the first POST: it writes it on `GET /api/v1/me`, which the SPA calls when it starts,
   and nowhere else. A `Set-Cookie` on a static file or a public cacheable GET would stop Cloudflare from caching it.
   A POST that comes without the cookie gets 403 and a fresh cookie. The token counts only in the header, never as a
   `_csrf` form field. On the back office ([0043](../operations/0043-runtime-topology.md)) the cookie is
   `__Host-XSRF-TOKEN` (Secure, `Path=/`, `SameSite=Strict`, no `Domain`), `client.ts` reads either name, and every
   write also needs that host's own `Origin` ([0036](0036-identity-and-admin-host.md) §13).
6. **Headers.** Content Security Policy (CSP): `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
   connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.
   `Referrer-Policy: strict-origin-when-cross-origin`. `Permissions-Policy: camera=(), microphone=(), geolocation=(),
   payment=(), usb=()`: the site uses none of them. The theme script is a file (`public/theme.js`), not inline.
   This is the public app's policy (`site.csp`); it adds the origins of the monetisation parts that are on, or the
   nonce policy ([0042](../money/0042-monetisation.md) §3, §11). The back office sends its own fixed
   policy on every response, never with a nonce or a third-party origin (`BackOfficeEdge`, ADR
   [0043](../operations/0043-runtime-topology.md)): the one above with `base-uri 'none'`, `frame-src 'none'` and
   `worker-src 'none'`, plus `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP and CORP `same-origin`,
   `X-Robots-Tag: noindex, nofollow` and HSTS. The web image sends a third policy of its own on the files it serves
   (9). So three owners send a CSP, each its own; none reads another's.
7. **Session cookie**: `SESSION` on the public site (Spring Session's name), `SameSite=Lax`, `HttpOnly`, `Secure` by default (`H2_COOKIE_SECURE`, true; the `dev` profile sets false).
   The session itself is in Redis ([0036](0036-identity-and-admin-host.md)). The back office's is `__Host-SESSION`, always
   Secure, in its own Redis namespace.
8. **Management port.** `H2_MANAGEMENT_PORT` (empty = app port). The chart sets 8081; the Services and the Ingresses never route
   it. The NetworkPolicy `ingress-app` opens 8081 only to the `observability` namespace, so `H2_METRICS_PUBLIC=true` exposes
   `/actuator/prometheus` only there; the actuator serves only health and prometheus, and health is the one open to all. Health shows no details; Redis is left out of health.
9. **SPA fallback and caching** (rules in [0001](../foundation/0001-stack-and-runtime.md)): the web image
   ([0043](../operations/0043-runtime-topology.md), `deploy/web/nginx.conf`) caches hashed assets for one year and
   `theme.js` and `favicon.svg` for 5 minutes, then revalidated; its other answers are plain 404s with `no-store`. On
   every answer it sends its own CSP (`default-src 'none'`, `sandbox`), `nosniff`, CORP `same-origin` and
   `Referrer-Policy: no-referrer`, and it takes GET and HEAD only. The shell (`index.html`, from the public app) is
   `no-cache`, so a new release reaches browsers at once. Search and guide answers are `no-store`. On the back office (`AdminWebApp`, `BackOfficeEdge`): `/admin/assets/**` is
   `private, max-age=31536000, immutable`, the app's other files `no-cache`, and pages, the API, redirects and errors
   `no-store`, whatever a controller sets.

### Code

- `backend/libs/platform/src/main/java/ai/helper2/platform/RateLimitInterceptor.java`, `RateLimiter.java` — limits, 429.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ClientKey.java` — the client key (IPv6 by /64).
- `backend/libs/platform/src/main/java/ai/helper2/platform/RedisRateLimiter.java`, `InMemoryRateLimiter.java` — Redis count, fallback.
- `backend/apps/public/src/main/java/ai/helper2/identity/security/PublicSecurityConfiguration.java` — CSRF, referrer policy, access rules;
  the CSP from `site/csp/ContentSecurityPolicy.java` ([0042](../money/0042-monetisation.md)).
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/security/BackOfficeSecurityConfiguration.java`, `BackOfficeEdge.java`,
  `admin/web/AdminWebApp.java` — the back office's CSRF cookie, headers, cache rules and routes.
- `deploy/web/nginx.conf`, `deploy/web/headers.conf` — the web image's routes, cache and security headers.
- `backend/apps/public/src/main/java/ai/helper2/site/pages/web/PageRoutes.java` — the shell on every client route.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` — forward headers, cookie, management port, rate limits; `backend/apps/public/src/main/resources/application.yaml` — the cookie's `secure` flag.
- `deploy/helm/helper2/templates/app.yaml`, `web.yaml`, `networkpolicy.yaml` — port 8081, forwarded headers, the ingress
  policies of the app and the web pods.
- `frontend/src/api/client.ts`, `frontend/public/theme.js` — CSRF header, CSP-safe theme script.

## Alternatives considered

- Limits keyed by raw path — bypassable with path variants that reach the same controller.
- In-memory limiter only — with 3 replicas a client gets up to 3 times the limit.
- Fail closed (refuse while Redis is down) — a cache outage would take search and the guide down.
- A token bucket or sliding window — smoother, but more state per key; a fixed window is enough here.
- Resilience4j's rate limiter ([0006](../foundation/0006-outbound-calls-and-resilience.md)) — it counts in one Java virtual machine (JVM) and for
  the whole app, not per client: 3 replicas would allow 3 times the limit, and one client per limiter would mean an
  unbounded registry keyed by address. Resilience4j guards the calls to Redis here; the count stays in Redis.
- Bucket4j or another distributed limiter library — the same Redis round trip and key as the short Lua script, with
  one more dependency.
- Trusting `X-Forwarded-For` by default — any client could choose its own address and skip the limit.
- Spring Boot's default static resource handling — no control over cache headers per path.

## Consequences

- One client cannot drain the search or guide budget by itself; rotating IPv6 addresses inside a /64 does not help.
- No third-party script or frame can run; cross-site POSTs fail without the token.
- A limit holds across the 3 prod replicas, whichever pod gets the request.
- A Redis outage does not take search or the guide down: each replica keeps counting in memory.
- Every client route loads the app on a direct visit; the SPA shows its own "not found" page for unknown paths.

### Known limits

- While Redis is down each replica counts alone (up to 3× the limit on prod). Clients behind one shared address share one limit.
- No flood rule at the edge. The app's rate limits are the only ones: a flood reaches the tunnel, the ingress and the pods before it gets its 429, and the web image's files (served by nginx, never by the app) have no limit at all. To do: one coarse Cloudflare rule per address on `/api/*`, `/resource/*` and `/sitemap.xml`, far above the app's limits (about 600 a minute), so a normal visitor always meets the app's own 429 first. The rule lives in Cloudflare and the platform repo, not here: the deploy stays generic. The admin host has no Cloudflare in front, so the rule never covers it. When the rule exists, ADR 0037 §1 says so.

## Diagrams

[flow](../../diagrams/flow-0037-http-edge-protection.svg) · [sequence](../../diagrams/sequence-0037-http-edge-protection.svg)

## Backlog

- Features: [F5.4](https://github.com/silvioincalza/helper2.ai/issues/76) HTTP edge: rate limits, CSRF, CSP, caching.
- Open issues that change this decision:
  - [#174](https://github.com/silvioincalza/helper2.ai/issues/174) S1.8.3 Postgres and Redis exporters in the Helm chart and an alert on Redis evicted keys
  - [#179](https://github.com/silvioincalza/helper2.ai/issues/179) S1.10.3 My session not to be evicted when the cache fills
  - [#356](https://github.com/silvioincalza/helper2.ai/issues/356) S10.11.1 Search and the guide's search-only path load-tested at 5 requests a second
