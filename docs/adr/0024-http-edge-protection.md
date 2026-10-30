# 0024 — HTTP edge: rate limits (IPv6 /64), CSRF, CSP, forwarded headers, management port, SPA fallback and caching

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0005](0005-redis-disposable-cache.md), [0007](0007-configuration-and-secrets.md), [0023](0023-identity-and-admin.md)

## Context
The app is public and anonymous. Search calls paid or rate-limited APIs, and each guide question costs model calls,
so one client must not use up shared budgets. The single-page app (SPA) and the application programming interface (API) share one origin, which must be
safe against cross-site requests and injected scripts. Metrics must not be public. Client routes such as `/sources`
must load the app on a direct visit, and a new release must reach browsers at once.

## Decision
1. **Rate limits per client** (`RateLimitInterceptor` on `/api/**`). The key is the handler method the request was
   routed to (the contract operationId), not the raw path, so path tricks (`;param`, encoding, double slashes) do not
   bypass it. Limits: `search` 60/min (`H2_RATE_LIMIT_SEARCH_PER_MINUTE`), `reportResource` 20/hour
   (`H2_RATE_LIMIT_REPORT_PER_HOUR`), `askGuide` and `askGuideStream` share 10/min (`H2_RATE_LIMIT_GUIDE_PER_MINUTE`),
   `listRecent` 60/min (`H2_RATE_LIMIT_RECENT_PER_MINUTE`). Over the limit: 429, `Retry-After`, problem+json. The second (async)
   dispatch of the streamed guide is not charged again.
2. **Limiter in Redis, shared by all replicas** (port `RateLimiter`, adapter `RedisRateLimiter`). Fixed window: one
   key per limiter and client, `h2:v1:rate:<limiter>:<client>`. One Lua script runs `INCR` and, on the first request,
   `PEXPIRE` with the window, so a key never lives without an expiry. The key's remaining time is the `Retry-After`.
   The client is `platform.ClientKey.of(remote address)`: Internet Protocol version 4 (IPv4) as is, Internet Protocol
   version 6 (IPv6) by its /64 prefix, a zone id (`%eth0`) dropped, IPv4-mapped addresses as IPv4, a missing address
   as `unknown`. Both limiters and the guide's daily quota ([0017](0017-guide-cost-guard.md)) use this one key.
3. **In-memory fallback.** On a Redis error the platform `CircuitBreaker` opens for 30 s, and each replica counts in
   its own `InMemoryRateLimiter` with the same limits (windows in start order, at most 50,000 clients, oldest
   evicted). Every such decision increments `helper2.rate-limit.fallback{limiter}`. Without Redis configured, only the
   in-memory limiter is used.
4. **Client address.** `H2_FORWARD_HEADERS_STRATEGY` defaults to `none`, so `X-Forwarded-For` is ignored. The cluster
   sets `native`: only `cloudflared` can reach the app's port 8080 (NetworkPolicy `ingress-app`), and Cloudflare sets
   the header.
5. **CSRF** (cross-site request forgery). `CookieCsrfTokenRepository.withHttpOnlyFalse()` with the SPA setup:
   cookie `XSRF-TOKEN`; `api/client.ts` sends it back as `X-XSRF-TOKEN` on every non-GET call. `CsrfCookieFilter` makes
   sure the cookie exists before the first POST.
6. **Headers.** Content Security Policy (CSP): `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
   connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.
   `Referrer-Policy: strict-origin-when-cross-origin`. The theme script is a file (`public/theme.js`), not inline.
7. **Session cookie**: `SameSite=Lax`, `HttpOnly`, `Secure` by default (`H2_COOKIE_SECURE`, true; the `dev` profile sets false).
   The session itself is in Redis ([0023](0023-identity-and-admin.md)).
8. **Management port.** `H2_MANAGEMENT_PORT` (empty = app port). The chart sets 8081; the Service and the tunnel route
   only 8080. The NetworkPolicy `ingress-app` opens 8081 only to the `observability` namespace, so `H2_METRICS_PUBLIC=true` exposes
   `/actuator/prometheus` only there; the other actuator endpoints, except health and info, stay ADMIN only. Health shows no details; Redis is left out of health.
9. **SPA fallback and caching** (`SinglePageApp`, rules in [0001](0001-stack-and-runtime.md)): hashed assets are cached
   for one year, `index.html` and other files are `no-cache`, so a new release reaches browsers at once. Search and guide
   answers are `no-store`.

## Consequences
- Good: one client cannot drain the search or guide budget by itself; rotating IPv6 addresses inside a /64 does not help.
- Good: no third-party script or frame can run; cross-site POSTs fail without the token.
- Good: a limit holds across the 3 prod replicas, whichever pod gets the request.
- Bad: while Redis is down each replica counts alone, so a client can get up to 3 times the limit on prod.
- Bad: one Redis round trip per limited request. Rate-limit keys share the 128 MB cache (`allkeys-lru`); an
  evicted key gives that client a fresh window early.
- Bad: clients behind one shared address (an office, carrier network address translation (NAT)) share one limit.
- Trade-off: the fallback serves `index.html` for unknown client paths; the SPA shows its own "not found" page.

## Alternatives considered
- Limits keyed by raw path — bypassable with path variants that reach the same controller.
- In-memory limiter only — with 3 replicas a client gets up to 3 times the limit.
- Fail closed (refuse while Redis is down) — a cache outage would take search and the guide down.
- A token bucket or sliding window — smoother, but more state per key; a fixed window is enough here.
- Trusting `X-Forwarded-For` by default — any client could choose its own address and skip the limit.
- Spring Boot's default static resource handling — no control over cache headers per path.

## Code
- `backend/src/main/java/ai/helper2/platform/RateLimitInterceptor.java`, `RateLimiter.java` — limits, 429.
- `backend/src/main/java/ai/helper2/platform/ClientKey.java` — the client key (IPv6 by /64).
- `backend/src/main/java/ai/helper2/platform/RedisRateLimiter.java`, `InMemoryRateLimiter.java` — Redis count, fallback.
- `backend/src/main/java/ai/helper2/identity/SecurityConfiguration.java` — CSRF, CSP, referrer policy, access rules.
- `backend/src/main/java/ai/helper2/platform/SinglePageApp.java` — SPA fallback and cache headers.
- `backend/src/main/resources/application.yaml` — forward headers, cookie, management port, rate limits.
- `deploy/helm/helper2/templates/app.yaml`, `networkpolicy.yaml` — port 8081, forwarded headers, the app's ingress policy.
- `frontend/src/api/client.ts`, `frontend/public/theme.js` — CSRF header, CSP-safe theme script.

## Diagrams
[flow](../diagrams/flow-0024-http-edge-protection.svg) · [sequence](../diagrams/sequence-0024-http-edge-protection.svg)

## Roadmap
- [Redis memory shared by rate limits, sessions and cache; alert on evictions](../roadmap.md#4-more-than-one-replica).
- [Load test of search at the expected peak; check the rate limits](../roadmap.md#quality-gates).
- [Serve the back office on admin.helper2.ai, reachable only through WireGuard via Traefik](../roadmap.md#domain-and-identity) ([0023](0023-identity-and-admin.md)).
