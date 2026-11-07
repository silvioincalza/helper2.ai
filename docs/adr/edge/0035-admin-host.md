# 0035 — Admin host: a second port for the back office, ADMIN only there, sign-in on both hosts

**Status.** Accepted. **Builds on:** [0023](0023-identity-and-admin.md), [0024](0024-http-edge-protection.md), [0027](../operations/0027-runtime-topology.md)

## Context
The back office (review queue, Scout runs, audit trail) and the end users' features (bookmarks, the registered tier of
web searches) both need GitHub sign-in, in every hosted environment.
The back office must not be reachable from the internet: one leaked session or one bug in a rule would open it to
everyone. End users must still sign in on the public site.
The platform runs ingress-nginx, cert-manager with DNS-01 and a private network (a VPN). A client on that network
reaches the cluster with an address from the platform's private ranges.

## Decision
1. **Two ports in one app.** `H2_ADMIN_PORT` (chart `app.adminPort`, 8082) opens a second Tomcat connector
   (`AdminPortConfiguration`). The public port stays 8080. Not set (0): one port for everything, only on a
   developer machine. With a sign-in provider configured (any OAuth2 client registration: the `oauth-github` profile,
   environment variables, a later provider) and no admin port, the app refuses to start unless the `dev` profile is
   on: a missing value must not put the back office back on the public host. A registration that cannot be read
   counts as configured.
2. **The public port has no back office.** `/admin`, `/admin/**`, `/api/v1/admin` and `/api/v1/admin/**` answer 404 on
   the public port (`BackOfficeOffPublicPort`, before the session and security filters). The check reads the decoded,
   normalised path the container maps, so `%61dmin` or `x/../admin` cannot pass as another path.
   The 404 is a plain problem body with the same security headers as any other response (CSP, `nosniff`, `DENY`,
   `Permissions-Policy`, `no-store`); it does not echo the path.
3. **ADMIN only for a sign-in on the admin port.** The allow-list (`H2_ADMINS`, [0023](0023-identity-and-admin.md))
   still decides who is an admin, but the role is given only when the sign-in itself runs on the admin port. An admin
   who signs in on the public site is a plain user there. A stolen public session never carries ADMIN.
4. **Sign-in on both hosts, each with its own callback.** The redirect URI is `{baseUrl}/login/oauth2/code/github`,
   so the public host and the admin host each get their own callback. The GitHub app must list both. After sign-in
   the admin host returns to `/admin`, the public host to `/`. A failed sign-in returns to the same place with
   `?signin=failed`. On the public site, "Sign in with GitHub" sits in the header and on the Saved page, only when
   the server offers a provider; the page the visitor was on waits in sessionStorage (`h2.signInReturn`, a path on
   this site only) and is opened again after the return to `/`.
5. **Host-only cookies.** The session and CSRF cookies set no `Domain`, so the browser never sends the public session
   to the admin host or the other way round.
6. **The edge decides who reaches the admin host.** The chart ships the Service `helper2-admin` (port 80 → admin port).
   The platform repo (outside this repo) routes the admin host to it with the Ingress
   `helper2-admin` on ingress-nginx: an allow-list of the private ranges, a DNS-only record to the ingress
   IP and a DNS-01 certificate. The public names go through Cloudflare to the Ingress `helper2-public`, which routes
   only to the Service `helper2`. No public name reaches the admin port.
7. **Network policy.** Only the ingress controller's pods (`networkPolicy.ingressController`) may reach the app:
   the public port and the admin port.
8. **A write on the admin host comes from the admin host.** The public site and the admin host are the same site for
   the browser (`helper2.ai`, `admin.helper2.ai`), so a `SameSite` cookie does not keep them apart, and a page of the
   public site could plant a CSRF cookie for the whole domain. Two checks close that:
   - On the admin port, a POST, PUT, PATCH or DELETE must carry an `Origin` that names the host the request went to
     (`SameOriginWrites`, before the CSRF check). No Origin, `null` or another host: 403 problem.
   - The CSRF token counts only in the `X-XSRF-TOKEN` header, on both ports, never as a `_csrf` form field. A form
     from another page cannot set a header, and a script on another origin cannot send one without CORS, which the
     app does not allow.

| Host                                                  | Port | Back office | Sign-in                  | ADMIN            |
|-------------------------------------------------------|------|-------------|--------------------------|------------------|
| helper2.ai, `<staging host>` (public)                 | 8080 | 404         | yes, returns to `/`      | never            |
| admin.helper2.ai, `<staging admin host>` (VPN only)   | 8082 | yes         | yes, returns to `/admin` | allow-listed ids |

## Consequences
- The back office is closed at three layers: the network edge, the port filter and the role. Each one alone keeps
  it closed. Its writes need the admin host's own Origin and the token in a header, so a page elsewhere on the
  site cannot make a signed-in admin's browser write.
- No rate limit keyed on an address on the admin host. There is no Cloudflare in front of it, so a forwarded address
  there could be the client's own choice; a counter keyed on it would be the client's to steer. The limits still
  hold there: the key is the signed-in administrator's GitHub login, and one shared key for whoever is not signed
  in yet (`RateLimitInterceptor` asks `AdminHost`, a platform port identity answers). The network closes the host;
  the limit caps what one admin session can spend on the guide and the search through it.
- End users can sign in on the public site, so bookmarks are kept on the server and the registered tier is possible.
- The GitHub app needs two callback URLs per environment.
- Local development keeps one port (`H2_ADMIN_PORT` unset).

## Alternatives considered
- A path prefix on the public host behind an IP allow-list in the app — one rule away from the internet, and the app
  would have to trust forwarded addresses.
- A separate back-office deployment — a second image and a second release for a few pages; the same code on a second
  port gives the same isolation.
- Sign-in only on the admin host — no end-user accounts, so no server bookmarks and no registered tier.
- ADMIN for any allow-listed sign-in, on any port — a public session of the owner would be an admin session.

## Code
- `backend/src/main/java/ai/helper2/identity/config/AdminPort.java`, `AdminPortConfiguration.java` — second connector, port filter.
- `backend/src/main/java/ai/helper2/identity/config/SecurityConfiguration.java` — ADMIN per port, return after sign-in.
- `backend/src/main/java/ai/helper2/identity/config/SessionConfiguration.java` — failed sign-in per port.
- `backend/src/test/java/ai/helper2/api/HttpEdgeIT.java` (admin port, in the same test context), `identity/AdminAllowListTest.java`,
  `identity/AdminPortFailClosedTest.java` (refuses to start without an admin port).
- `deploy/helm/helper2/templates/app.yaml` (Service `helper2-admin`), `networkpolicy.yaml`, `values*.yaml`;
  `deploy/k8s/Makefile` (`chart-check`).

## Diagrams
[flow](../../diagrams/flow-0035-admin-host.svg) · [sequence](../../diagrams/sequence-0035-admin-host.svg)

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
