# 0052 — Hub pages per resource type

**Status:** Accepted

**Date:** 2026-11-19

**Depends on:** [0003](ADR-0003-contract-first-rest.md), [0023](ADR-0023-catalog-full-text-index.md), [0039](ADR-0039-frontend-one-box-state-in-the-url.md), [0040](ADR-0040-design-system.md), [0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md), [0050](ADR-0050-frontend-feature-boundaries.md).

**Requirements:** C-014

## Context

People search Google for a kind of thing: "MCP servers", "open source AI agents", "AI datasets". The pages of
searchavo.ai that can rank are one page per resource ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md)). There is no
page for a kind of resource, so such a search has nothing of ours to land on, and the home page links to a few new
resources only.

A page per type and per category (`/mcp-servers/databases`) would give hundreds of pages, many of them nearly empty.
Google treats many thin pages made in bulk as spam. One page per type is a small set, each with real content.

## Decision

1. **One hub page per indexed type.** Ten pages, one per type that the site pages index:

   | Address | Type | Name |
   |---|---|---|
   | `/ai-apps` | PRODUCT | AI apps |
   | `/ai-models` | MODEL | AI models |
   | `/ai-agents` | AGENT | AI agents |
   | `/mcp-servers` | MCP_SERVER | MCP servers |
   | `/libraries` | LIBRARY | AI libraries |
   | `/agent-skills` | SKILL | Agent skills |
   | `/datasets` | DATASET | AI datasets |
   | `/papers` | PAPER | AI papers |
   | `/prompts` | PROMPT | AI prompts |
   | `/courses` | COURSE | AI courses |

   EVENT has its own page (`/events`). NEWS, ARTICLE, AWESOME_LIST and OTHER are not indexed (`IndexPolicy`), so
   they have no hub. The list is one enum on each side: `Hub` (`Hub.kt`) and `HUBS` (`frontend/src/lib/hubs.ts`); each
   address is a `PageKind` and a route in `routes.tsx`, so the route test of [0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md) covers them.
2. **What a hub lists.** The 50 resources of its type that the site pages mark `index`, newest change first. The
   catalog query is the sitemap's (`Resources.indexable`), with the type as its only type, so a hub never lists a
   page that says `noindex`. A PROMPT is listed only when PROMPT is one of the asked types, also in the sitemap
   (`IndexPolicy.LISTED_TYPES`). `ListNewestIndexable` turns the ids into result cards.
3. **Read once every 10 minutes.** `CatalogSiteLists` keeps each hub's list 10 minutes in memory. The server page
   and the API read the same list. A failed read gives the last list it had, or an empty one.
4. **The server page.** Spring answers each hub address like the other site pages
   ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md) §2): `index`, the title `{Name}: newest in the catalog · searchavo.ai`, a
   fixed description, a canonical, and a body with an `<h1>`, the description, the list ("Newest change first", up to
   50 links) and the links to the other site pages and hubs. Its JSON-LD is a `CollectionPage` whose `mainEntity` is
   an `ItemList` with one `ListItem` per link, in the same order. `/llms.txt` lists the hubs.
5. **A hub with no resource.** It says `noindex, follow`, has no JSON-LD and is not in `/sitemaps/pages.xml`: a page
   with a heading and no list is a thin page. Its address still answers 200, with the links to the other pages, and
   the footer still links it. It says `index` and is back in the sitemap once its list has a resource.
6. **The API.** `GET /api/v1/hubs/{type}` returns `HubResponse` (`type`, `items`, `generatedAt`). A type without a hub
   is a 404, an unknown type a 400. `Cache-Control: public, max-age=300`, and the `recent` rate limit, like the home
   lists. Public in the security chain. It lives in the `site` module (`site.pages.web.HubController`), next to the
   pages it serves, and reads the catalog through its use case.
7. **The app page.** `features/hubs/HubPage.tsx`, one page for every hub address; the address says the type. It
   shows the name, one line on what the list is, and the list: each row is the title, linked to the resource page,
   and a byline with the host and the license. The states are the shared `ListState` (loading skeleton, error with
   **Try again**, "Nothing here yet."). No new style: the row is the news page's small story row
   ([0040](ADR-0040-design-system.md)).
8. **Entry point: the footer.** A second footer row, "Browse by type", links the ten hubs on every page
   ([0040](ADR-0040-design-system.md) §5). The header stays as it is.

## Alternatives considered

- **Type and category hubs** (`/mcp-servers/databases`). More pages that can rank, but many thin ones; a spam risk
  and a lot to maintain. Measured after the type hubs have Search Console numbers.
- **The search page with a type filter as the hub.** No new page, but a search address is `noindex`
  ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md) §2) and needs a query; the hub needs a stable page with its own head.
- **Order by popularity.** Closer to "the best", but popularity is not stated for every type in the same way, and the
  ranking constants wait for the evaluation labels. Newest change first is a fact every resource has, and it brings
  crawlers back to pages that changed.
- **A "Browse" entry in the header.** More visible, but the header has no room on a phone and the hubs are mainly
  for people who arrive from a search engine.

## Consequences

- Up to ten more pages that can rank, each with up to 50 internal links to resource pages.
- A hub whose read fails before it ever had a list says `noindex` until the next read works, 10 minutes at most.
  The sitemap is built once an hour, so a hub can be missing from it for up to an hour after its first resource.
- One catalog query per hub every 10 minutes at most, whatever the traffic.
- The hubs show the 50 newest changes, not the whole type: browsing the rest is the search page.
- A hub's name and its line live twice, in `Hub.kt` (server) and in `en.json` (app), like the other site pages.
- No new data flow, cookie or storage key.

### Security (STRIDE)

| Threat | Answer |
|---|---|
| Spoofing | None: public pages, no identity involved. |
| Tampering (HTML or script injection through titles) | Titles go through the escaper of [0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md); the app renders them as text. |
| Repudiation | Not relevant: read-only. |
| Information disclosure | Only resources that are already public and indexed. |
| Denial of service | Served from memory; the catalog is read at most once per hub every 10 minutes; the `recent` rate limit. |
| Elevation of privilege | None: no write path. |

### AI architecture check

No AI layer of its own. Guardrails: no AI-written text in the list (title, host and license only). Cost &
Reliability: one cached read per hub, a failed read keeps the last list. Deployment: no new setting. The other
layers are not touched.

## Diagrams

[flow](../diagrams/flow-0052-hub-pages.svg) · [sequence](../diagrams/sequence-0052-hub-pages.svg)

## Backlog

- Features: [F7.3](https://github.com/silvioincalza/searchavo.ai/issues/89) Pages for crawlers, SEO, policy endpoint;
  story [S7.3.6](https://github.com/silvioincalza/searchavo.ai/issues/816).
- Open issues that change this decision: none.
