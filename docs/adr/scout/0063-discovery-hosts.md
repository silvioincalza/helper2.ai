# 0063 — Discovery hosts: the writing sites and directories are kept in the back office

**Status.** Accepted. **Builds on:** [0023](../edge/0023-identity-and-admin.md), [0045](../edge/0045-back-office.md), [0051](0051-tavily-discovery.md), [0058](0058-awesome-deep-search.md), [0060](../operations/0060-runtime-settings.md), [0062](0062-writing-about-a-resource.md)

## Context
Discovery knows some sites by name ([0062](0062-writing-about-a-resource.md)). A writing site (Medium, TechCrunch …)
publishes articles about many products: a post there is an ARTICLE candidate. A directory (Product Hunt, G2 …) lists
products: a page there is never kept, and never taken for a product's own site. Both lists were constants in
`DiscoveryPages`. A new news site or a new directory needed a pull request and a deploy, and the review list filled
with pages of a site the code did not know yet.

The runtime settings ([0060](../operations/0060-runtime-settings.md)) are numbers with a maximum in code, and their
audit keeps 100 characters a value. A list of hosts does not fit there.

## Decision
1. **A table of hosts.** `discovery_host` (V17): one row per host, its kind (WRITING or DIRECTORY), who added it,
   when and why. The host is the key, so a host is in one list only. V17 seeds the lists that were in code. The Scout
   owns the table (`scout.DiscoveryHostStore`, `JdbcDiscoveryHostStore`).
2. **Read once per run.** Tavily discovery and the awesome-list collector read both lists at the start of each run
   and build their `DiscoveryPages` from them. A change applies at the next run; a run already going keeps the lists
   it started with. No snapshot, no refresh thread: the collectors are batch jobs, and nothing on a search path reads
   the lists.
3. **Fail closed.** A run whose lists cannot be read fails before any search, and its checkpoint stays. Discovery
   never runs on empty lists: an empty directory list would send every Product Hunt page to review as a product.
4. **Only a host name.** `HostNames.check`: lower case, no trailing dot, no "www.". Refused (400): a scheme, path,
   port or space; a name alone or a top-level domain ("com"); a registry's second level ("co.uk"); an IP address;
   github.com (discovery reads repositories by their own rule). An entry also matches every subdomain, so "com" would
   match nearly every site. At most 500 hosts.
5. **The page.** On `/admin/settings`, below the spending limits: the two lists, each host with who added it, when
   and why. Add: host, list, optional reason. Remove: a review that says what changes, then Confirm, with an
   optional reason. To move a host to the other list, remove it, then add it. API: `GET /api/v1/admin/discovery-hosts`,
   `POST /api/v1/admin/discovery-hosts`, `POST /api/v1/admin/discovery-hosts/{host}/remove`; ADMIN only, admin port,
   CSRF on writes.
6. **Audited.** Each add and each remove is an `admin_audit` row in the same transaction: `ADD_DISCOVERY_HOST` or
   `REMOVE_DISCOVERY_HOST`, the host as target, the outcome (ADDED, ALREADY_LISTED, FULL, REMOVED, NOT_FOUND), the
   list it went to or came from, and the reason. A refused host name changes nothing and is not recorded.
7. **Retention.** A host stays until an administrator removes it. Its audit rows follow the audit trail's retention
   ([0034](../operations/0034-data-retention.md)).

## Consequences
- A new news site or directory is one form in the back office, without a deploy. The next discovery run uses it.
- Not within seconds, like a spending limit: a category runs every few days, so the change shows at its next run.
- A wrong entry has a cost: a directory entry drops that site's product pages, a writing entry turns its pages into
  article candidates. The audit row says who added it and why; removing it undoes it at the next run.
- The places of a site (`/blog`, `/docs`, `/careers` …) and GitHub's own pages stay in code: they are rules about
  addresses, not about sites, and change with the code that reads them.
- Privacy: the table holds the GitHub user ID of the administrator who added a host and the reason they wrote. The
  Privacy page lists it with its retention.

## Security and privacy (STRIDE)
- **Spoofing**: only a signed-in ADMIN on the admin host; GitHub is the identity provider
  ([0023](../edge/0023-identity-and-admin.md)).
- **Tampering**: an admin could add a host that matches too much ("com", "co.uk"), or move a product's site into a
  directory. Mitigation: the host checks above, the 500 cap, one list per host, and an audit row for each change.
- **Repudiation**: every change has an audit row with the admin, the time, the list and the reason.
- **Information disclosure**: the lists are site names, public anyway. The admin ID is shown on the admin page only.
- **Denial of service**: at most 500 hosts; each run reads them with one query.
- **Elevation**: unchanged; the endpoints are under `/api/v1/admin`, ADMIN only, with CSRF on writes.

## Alternatives considered
- **A runtime setting of type list** ([0060](../operations/0060-runtime-settings.md)) — reuses its history and page,
  but its value is a number and its audit keeps 100 characters; a changed list would show as one long line.
- **`H2_` variables** with today's lists as defaults — simple, but a change still needs a deploy.
- **A snapshot per replica, refreshed every 20 s** — like the runtime settings; not needed while only batch runs read
  the lists, and one more thread to run.

## Code
- `backend/apps/helper2/src/main/kotlin/ai/helper2/scout/DiscoveryHosts.kt` — `HostKind`, `DiscoveryHost`, `HostLists`,
  `DiscoveryHostStore`, `HostNames`.
- `backend/apps/helper2/src/main/kotlin/ai/helper2/scout/usecase/ManageDiscoveryHosts.kt`;
  `backend/apps/helper2/src/main/java/ai/helper2/scout/internal/JdbcDiscoveryHostStore.java`, `ScoutConfiguration.java`.
- `backend/apps/helper2/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java` (built from `HostLists`),
  `TavilyDiscoveryCollector.java`, `sources/github/GitHubAwesomeCollector.java`, `ProductLinks.java`.
- `backend/apps/helper2/src/main/java/ai/helper2/admin/web/DiscoveryHostsController.java`; `api/admin.yaml`
  (`DiscoveryHosts`, `DiscoveryHostEntry`, `DiscoveryHostAdd`, `DiscoveryHostRemove`).
- `backend/libs/schema/src/main/resources/db/migration/V17__discovery_host.sql`; `identity/AuditTrail.java` (the two actions).
- `frontend/src/pages/admin/DiscoveryHostsPanel.tsx`, `SettingsPage.tsx`.
- Tests: `ManageDiscoveryHostsTest`, `JdbcDiscoveryHostStoreIT` (the seed), `DiscoveryHostsApiIT`, `DiscoveryPagesTest`,
  `TavilyDiscoveryCollectorTest`, `DiscoveryHostsPanel.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0063-discovery-hosts.svg) · [sequence](../../diagrams/sequence-0063-discovery-hosts.svg).
