# 0050 — Pages for crawlers: Spring writes the head and a short body, real 404s, sitemap and robots

**Status.** Accepted. **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0024](../edge/0024-http-edge-protection.md), [0025](0025-frontend-one-box.md), [0038](../guide/0038-ai-content-marking.md), [0040](0040-frontend-design.md)

## Context
Search engines and answer engines find almost nothing on helper2.ai today. Every address returns the same 552-byte
shell: the title "Helper2.ai" and an empty `<div id="root">`. React draws the rest.
- Google runs JavaScript, but later and only for pages it already chose to crawl.
- Most AI crawlers (GPTBot, ClaudeBot, PerplexityBot and similar) do not run JavaScript. For them every page is empty.
- Link previews (Slack, LinkedIn, X, WhatsApp, Teams) never run JavaScript. A shared resource link shows only the brand.
- An unknown address returns 200 with "Page not found" drawn by React: a soft 404.
- There is no sitemap and no `robots.txt` of our own.

The pages that can rank are the resource pages: one per tool, model, MCP server or paper. The home page and the search
pages cannot. Nothing seems indexed yet, so this is the cheapest moment to decide.

## Decision
1. **Spring fills the shell; React stays as it is.** A small `pages` module answers every GET and HEAD of an address the
   client router owns (`ClientRoutes`: no file extension, not a server path) with a router function, which Spring asks
   before the static files. It reads the built `index.html` once at start-up and, per request, writes into it:
   the title, meta description, canonical link, robots meta, Open Graph and Twitter tags, JSON-LD, and for a
   resource a short plain HTML body inside `#root`. `createRoot` replaces that body when the app starts, so people see
   no change. Bots and people get the same HTML, so it is not cloaking. The start-up fails when the markers it needs
   (`<title>`, `</head>`, `<div id="root"></div>`) are missing, and a test checks the app's own shell. Without a built
   app (development: Vite serves the UI) the module claims nothing and `SinglePageApp` answers as before.
2. **What each address says, and its status.**
   | Address | Status | Robots | Head |
   |---|---|---|---|
   | `/resource/{id}`, found | 200 | index, or noindex when not index-worthy (5) | from the resource (3) |
   | `/resource/{id}`, id merged into another resource | 301 to `/resource/{owner}` | — | — |
   | `/resource/{id}`, unknown or not a UUID | 404 | noindex | "Resource not found" |
   | `/`, `/sources`, `/mission`, `/privacy`, `/terms` | 200 | index | fixed title and description per page |
   | `/search`, `/?q=…`, `/saved`, `/account` | 200 | noindex, follow | fixed title |
   | `/admin/**` (admin host only) | as today | noindex | fixed title |
   | any other address without a file extension | 404 | noindex | "Page not found" |

   The 404 pages still carry the app, so a person sees the same "not found" page as today. The pages the server knows
   are one list (`PageKind`, `Routes.kt`); a test reads `routes.tsx` and fails when a path of the app is not in it, so a
   new page cannot ship as a 404.
3. **The resource head.**
   - Title, about 60 characters: `{title}: {type in words} · Helper2.ai`.
   - Meta description: the source's own description, cut at a word near 155 characters. An AI-written description
     (`AI_SUMMARY`, `AI_EXTRACTED`) is never used here, because a meta tag cannot carry the AI label (ADR 0038). Without
     a source description the text is built from facts: type, source, licence, pricing.
   - Canonical: always absolute and always the public host (`H2_PUBLIC_BASE_URL`, `https://helper2.ai` in prod),
     also when served from staging.
   - Open Graph and Twitter: title, description, URL, `og:type=website`, `og:site_name`, `twitter:card=summary`. No
     image yet (roadmap).
   - JSON-LD (schema.org) by type: `SoftwareApplication` for PRODUCT, AGENT and MODEL; `SoftwareSourceCode` with
     `codeRepository` and `license` for LIBRARY, MCP_SERVER and SKILL; `ScholarlyArticle` for PAPER; `Event` for EVENT;
     `Course` for COURSE; `Dataset` for DATASET; nothing for the rest. Every resource page also gets a
     `BreadcrumbList`; the home page gets `WebSite` and `Organization`. Only facts the page itself shows go in.
   - Body (`main.h2-prerender`): an `<h1>`, the source's description, up to 6 facts, up to 5 source links (web
     addresses only), and up to 8 links to related resources (relationships a person did not reject).
4. **Escaping is the security rule.** Every title and description comes from a third party. Text goes through one
   HTML escaper; JSON-LD goes through Jackson with `<`, `>`, `&` and U+2028/U+2029 written as `\uXXXX`, so
   `</script>` in a description cannot close the tag. The id in the address is parsed as a UUID before any lookup. A
   test feeds hostile titles, descriptions, licences and source names through every slot.
5. **Index-worthy is one rule, in one place.** `IndexPolicy.indexWorthy(resource)`, used by the robots meta and by
   the sitemap: PRODUCT, AGENT, MODEL, MCP_SERVER, LIBRARY, SKILL, DATASET, PAPER, EVENT and COURSE with a source
   description and at least one fact (licence, pricing, a date) or relationship; a PROMPT only when its source
   description, the prompt text, has at least 200 characters. NEWS and ARTICLE are copies of someone else's text: the original ranks, not
   us. They, OTHER and thin pages get `noindex, follow`, so crawlers still follow their links. I tune the rule from
   Search Console numbers, not by adding cases elsewhere.
6. **`/sitemap.xml` and `/robots.txt` come from Spring.** The sitemap lists the indexed static pages and every
   index-worthy resource with `<lastmod>` from its last update, newest change first, at most 50,000 (one file, Google's
   limit). The catalog query applies the same rule as `IndexPolicy`; an integration test opens every listed page and
   checks it says `index`. It is built at most once an hour and kept in memory. `robots.txt` blocks `/oauth2/`, `/login`, `/logout` and
   `/admin`, never `/api/` (Google needs the API to render a page), and names the sitemap. When
   `H2_NOINDEX` is on (staging), the `X-Robots-Tag: noindex, nofollow` header of #151 stays, every robots meta says
   `noindex` too, `robots.txt` allows all (a blocked page never shows its noindex) and there is no sitemap.
7. **Client navigation drops the server's tags.** The tags Spring writes carry `data-h2-head`. At the first move to
   another page the app removes them (`useDropServerHead`); from then on it keeps the title itself, as before. Crawlers
   and link previews read the first load.
8. **Cache.** The HTML stays `no-cache`, as today, so a release is picked up at once. The resource lookup is the one
   the API makes (`GetResourceDetail`); the page asks for it again when it starts. That is one extra indexed read per
   full page load, not per click.

## Consequences
- Every resource page is readable without JavaScript: by Google at once, by AI crawlers, and in link previews.
- Unknown addresses are real 404s; merged resources move with a 301, so old links keep working.
- Staging stays out of the index; prod has a sitemap to submit to Google Search Console and Bing Webmaster Tools.
- "What a page says" now lives twice: in React and in the `pages` module. The server body stays short (title,
  description, facts, links) to limit drift, and the head rules have their own tests.
- A full load of a resource page reads the resource twice (HTML, then API). Budget: the HTML answer adds at most
  20 ms at p95 over today's static file; measured with a load test before and after, on staging.
- Until the app starts, a person sees the server's plain text of a resource page for a moment. Its nodes are
  replaced, not moved, so it adds no layout shift.
- No new runtime, no new data flow, no new cookie or storage: the Privacy page does not change.

## Security (STRIDE)
| Threat | Answer |
|---|---|
| Spoofing | None: public pages, no identity involved. |
| Tampering (HTML or script injection through source text) | One escaper for HTML, Jackson with HTML-safe escapes for JSON-LD, hostile-text tests (4). |
| Repudiation | Not relevant: read-only. |
| Information disclosure | Only what the public API already shows; AI-written descriptions stay out of meta tags; admin paths noindex and only on the admin host. |
| Denial of service | One indexed read per page; the sitemap is built at most once an hour; the edge rate limits of ADR 0024 apply. |
| Elevation of privilege | None: no write path. |

## AI architecture check
No AI layer changes. Guardrails: AI-written text never reaches a meta tag or JSON-LD, and the body shows it only with
its label and `data-ai-generated` (ADR 0038). Observability: the 404 and 301 counts per route show up in the existing
HTTP metrics. Deployment: one new setting, `H2_PUBLIC_BASE_URL` (Helm `app.publicBaseUrl`, empty = the default);
`H2_NOINDEX` is reused. The other layers are not
touched.

## Alternatives considered
- **Server-side rendering with a Node server** (React Router framework mode) — every page fully rendered, but a second
  runtime, image and cache to run, hydration bugs, and weeks of work on an SPA that works. Too much for one person.
- **Prerender at build time** — cheap for the five static pages, useless for thousands of resources that change every
  day, and no real 404s.
- **Dynamic rendering for bots** (Prerender.io, Rendertron) — a headless browser for crawlers only. Google calls it a
  workaround; bots and people get different paths, and it costs money or a service.
- **Client-side tags only** (React 19 `<meta>`) — the cheapest, and it fixes soft 404s for Google, but AI crawlers and
  link previews still see an empty page.

## Code
- `backend/src/main/kotlin/ai/helper2/pages/` — `Routes.kt` (`PageKind`, `PageAddress`), `PageHead.kt` (`PageHead`,
  `ResourceView`, `IndexPolicy`), `Pages.kt` (the head of each page, `JsonLd`).
- `backend/src/main/java/ai/helper2/pages/` — `usecase/RenderPage`, `usecase/ListSitemap`, `internal/PageTemplate`,
  `internal/PageHtml` (the escaping), `web/PageRoutes`, `web/CrawlerFiles`, `config/PagesProperties`.
- `backend/src/main/java/ai/helper2/platform/ClientRoutes.java`; `catalog/usecase/ListIndexableResources.java`,
  `catalog/internal/ResourceStore.indexable`.
- `frontend/src/lib/head.ts` (`useDropServerHead`), `frontend/src/styles/app.css` (`.h2-prerender`).
- Tests: `pages/PagesTest.kt`, `pages/PageHtmlTest.java`, `api/PagesIT.java`, `frontend/src/lib/head.test.tsx`.

## Diagrams
[flow](../../diagrams/flow-0050-pages-for-crawlers.svg) · [sequence](../../diagrams/sequence-0050-pages-for-crawlers.svg)

## Roadmap
- 410 for resources removed on purpose, once the catalog has a removed state.
- A sitemap index when index-worthy pages pass 50,000; an Open Graph image (brand first, then per resource).
- Readable slugs (`/resource/{id}/{slug}`): decide before Google indexes many pages.
- Hub pages per type or use case (`/mcp-servers`), `llms.txt`, IndexNow pings, one Open Graph image per resource.
