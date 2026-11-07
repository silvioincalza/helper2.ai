# 0035 — Admin host: the back office app, ADMIN only there, sign-in on both hosts

**Status.** Accepted. **Builds on:** [0023](0023-identity-and-admin.md), [0024](0024-http-edge-protection.md), [0027](../operations/0027-runtime-topology.md). **Changed by:** [0069](../operations/0069-three-deployables.md) (the back office is its own app; the admin port is gone)

## Context
The back office (review queue, Scout runs, audit trail) and the end users' features (bookmarks, the registered tier of
web searches) both need GitHub sign-in, in every hosted environment.
The back office must not be reachable from the internet: one leaked session or one bug in a rule would open it to
everyone. End users must still sign in on the public site.
The platform runs ingress-nginx, cert-manager with DNS-01 and a private network (a VPN). A client on that network
reaches the cluster with an address from the platform's private ranges.

## Decision
1. **Two apps, two hosts.** The public app (`backend/apps/public`) serves the public host. The back office app
   (`backend/apps/backoffice`, ADR [0069](../operations/0069-three-deployables.md)) serves the admin host, on its own
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
4. **ADMIN only on the back office.** The allow-list (`H2_ADMINS`, [0023](0023-identity-and-admin.md)) decides who is
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
   sessions are in their own Redis namespace (`helper2:backoffice:session`), so a public session id is never one there.
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

## Consequences
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

## Alternatives considered
- A path prefix on the public host behind an IP allow-list in the app — one rule away from the internet, and the app
  would have to trust forwarded addresses.
- A second port in one app (this ADR until [0069](../operations/0069-three-deployables.md)) — the back office code and
  routes shipped in every public pod; only a port filter and the role kept them closed.
- Sign-in only on the admin host — no end-user accounts, so no server bookmarks and no registered tier.
- ADMIN for any allow-listed sign-in, in either app — a public session of the owner would be an admin session.

## Code
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

## Diagrams
[flow](../../diagrams/flow-0035-admin-host.svg) · [sequence](../../diagrams/sequence-0035-admin-host.svg)

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
