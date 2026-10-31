# 0035 — Admin host: a second port for the back office, ADMIN only there, sign-in on both hosts

**Status.** Accepted. **Builds on:** [0023](0023-identity-and-admin.md), [0024](0024-http-edge-protection.md), [0027](../operations/0027-runtime-topology.md)

## Context
The back office (review queue, Scout runs, audit trail) and the end users' features (bookmarks, the registered tier of
web searches) both need GitHub sign-in. Until now sign-in lived only on `/admin`, on the public host, and was off in
every hosted environment, so nobody could review anything.
The back office must not be reachable from the internet: one leaked session or one bug in a rule would open it to
everyone. End users must still sign in on the public site.
The platform already runs ingress-nginx, cert-manager with DNS-01 and a WireGuard network. A WireGuard client reaches
the cluster with an address from the vSwitch range (the Proxmox node masquerades it).

## Decision
1. **Two ports in one app.** `H2_ADMIN_PORT` (chart `app.adminPort`, 8082) opens a second Tomcat connector
   (`AdminPortConfiguration`). The public port stays 8080. Not set (0): one port for everything, as before.
2. **The public port has no back office.** `/admin`, `/admin/**`, `/api/v1/admin` and `/api/v1/admin/**` answer 404 on
   the public port (`BackOfficeOffPublicPort`, before the session and security filters). The check reads the decoded,
   normalised path the container maps, so `%61dmin` or `x/../admin` cannot pass as another path.
3. **ADMIN only for a sign-in on the admin port.** The allow-list (`H2_ADMINS`, [0023](0023-identity-and-admin.md))
   still decides who is an admin, but the role is given only when the sign-in itself runs on the admin port. An admin
   who signs in on the public site is a plain user there. A stolen public session never carries ADMIN.
4. **Sign-in on both hosts, each with its own callback.** The redirect URI is `{baseUrl}/login/oauth2/code/github`,
   so the public host and the admin host each get their own callback. The GitHub app must list both. After sign-in
   the admin host returns to `/admin`, the public host to `/`. A failed sign-in returns to the same place with
   `?signin=failed`.
5. **Host-only cookies.** The session and CSRF cookies set no `Domain`, so the browser never sends the public session
   to the admin host or the other way round.
6. **The edge decides who reaches the admin host.** The chart ships the Service `helper2-admin` (port 80 → admin port).
   The platform (incalza-me-infra `terraform/k8s/helper2.tf`) routes the admin host to it with the Ingress
   `helper2-admin` on ingress-nginx: an allow-list of the vSwitch and mesh ranges, a DNS-only record to the ingress
   IP and a DNS-01 certificate. The public names go through Cloudflare to the Ingress `helper2-public`, which routes
   only to the Service `helper2`. No public name reaches the admin port.
7. **Network policy.** Only the ingress controller's pods (`networkPolicy.ingressController`) may reach the app:
   the public port and the admin port.

| Host | Port | Back office | Sign-in | ADMIN |
|---|---|---|---|---|
| helper2.ai, helper2.incalza.me (public) | 8080 | 404 | yes, returns to `/` | never |
| admin.helper2.ai, helper2-admin.incalza.me (LAN and WireGuard) | 8082 | yes | yes, returns to `/admin` | allow-listed ids |

## Consequences
- The back office is closed at three layers: the network edge, the port filter and the role. Each one alone keeps
  it closed.
- End users can sign in on the public site, so bookmarks on the server and the registered tier can go live.
- The GitHub app needs two callback URLs per environment. A GitHub OAuth App allows only one: use a GitHub App, or
  one OAuth App per host. Manual step, outside the repo.
- Local development keeps one port (`H2_ADMIN_PORT` unset).

## Alternatives considered
- A path prefix on the public host behind an IP allow-list in the app — one rule away from the internet, and the app
  would have to trust forwarded addresses.
- A separate back-office deployment — a second image and a second release for a few pages; the same code on a second
  port gives the same isolation.
- Sign-in only on the admin host — no end-user accounts, so no server bookmarks and no registered tier.
- ADMIN for any allow-listed sign-in, on any port — a public session of the owner would be an admin session.

## Code
- `backend/src/main/java/ai/helper2/identity/AdminPort.java`, `AdminPortConfiguration.java` — second connector, port filter.
- `backend/src/main/java/ai/helper2/identity/SecurityConfiguration.java` — ADMIN per port, return after sign-in.
- `backend/src/main/java/ai/helper2/identity/SessionConfiguration.java` — failed sign-in per port.
- `backend/src/test/java/ai/helper2/api/HttpEdgeIT.java` (admin port, in the same test context), `identity/AdminAllowListTest.java`.
- `deploy/helm/helper2/templates/app.yaml` (Service `helper2-admin`), `networkpolicy.yaml`, `values*.yaml`;
  `deploy/k8s/Makefile` (`chart-check`).

## Diagrams
[flow](../../diagrams/flow-0035-admin-host.svg) · [sequence](../../diagrams/sequence-0035-admin-host.svg)
