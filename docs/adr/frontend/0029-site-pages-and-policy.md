# 0029 — Site: pages for crawlers and the policy endpoint

**Status.** Accepted. Merges old 0050, 0052 (texts merged in PR 10 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0050"></a>

## Part: was ADR 0050 — Pages for crawlers: Spring writes the head and a short body, real 404s, sitemap and robots

**Status.** Accepted. **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0025](../edge/0025-http-edge-protection.md), [0027](0027-frontend-one-box.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [0028](0028-design-system.md)

### Context
Search engines and answer engines find almost nothing on helper2.ai today. Every address returns the same 552-byte
shell: the title "Helper2.ai" and an empty `<div id="root">`. React draws the rest.
- Google runs JavaScript, but later and only for pages it already chose to crawl.
- Most AI crawlers (GPTBot, ClaudeBot, PerplexityBot and similar) do not run JavaScript. For them every page is empty.
- Link previews (Slack, LinkedIn, X, WhatsApp, Teams) never run JavaScript. A shared resource link shows only the brand.
- An unknown address returns 200 with "Page not found" drawn by React: a soft 404.
- There is no sitemap and no `robots.txt` of our own.

The pages that can rank are the resource pages: one per tool, model, MCP server or paper. The home page and the search
pages cannot. Nothing seems indexed yet, so this is the cheapest moment to decide.

### Decision
1. **Spring fills the shell; React stays as it is.** A small `pages` module answers every GET and HEAD of an address the
   client router owns (`ClientRoutes`: no file extension, not a server path) with a router function. It reads the
   built `index.html` once at start-up (`helper2.pages.template`; in the cluster `file:/shell/index.html`, copied by an
   init container from the web image of the same tag, [0032](../operations/0032-runtime-topology.md)) and, per
   request, writes into it:
   the title, meta description, canonical link, robots meta, Open Graph and Twitter tags, JSON-LD, and for a
   resource a short plain HTML body inside `#root`. `createRoot` replaces that body when the app starts, so people see
   no change. Bots and people get the same HTML, so it is not cloaking. The start-up fails when the markers it needs
   (`<title>`, `</head>`, `<div id="root"></div>`) are missing, and a test checks the app's own shell. Without a built
   shell (development: Vite serves the UI) the module claims nothing and such an address is a 404.
2. **What each address says, and its status.**
   | Address | Status | Robots | Head |
   |---|---|---|---|
   | `/resource/{id}/{slug}`, found, slug right | 200 | index, or noindex when not index-worthy (5) | from the resource (3) |
   | `/resource/{id}` without a slug, a wrong or old slug, or an id merged into another resource | 301 to `/resource/{owner}/{slug}` | — | — |
   | `/resource/{id}`, unknown or not a UUID | 404 | noindex | "Resource not found" |
   | `/`, `/sources`, `/mission`, `/privacy`, `/terms` | 200 | index | fixed title and description per page |
   | `/search`, `/?q=…`, `/saved`, `/account` | 200 | noindex, follow | fixed title |
   | `/admin/**` | 404 here: the back office app serves it on the admin host ([0032](../operations/0032-runtime-topology.md)), always `noindex` | — | — |
   | any other address without a file extension | 404 | noindex | "Page not found" |

   The 404 pages still carry the app, so a person sees the same "not found" page as today. The pages the server knows
   are one list (`PageKind`, `Routes.kt`); a test reads `routes.tsx` and fails when a path of the app is not in it, so a
   new page cannot ship as a 404.

   **Readable addresses.** The id is the key; the slug only helps people and search engines read the link. The slug
   is the title in plain lowercase words: accents dropped, every run of other characters one hyphen, at most 60
   characters cut at a hyphen (`Slug`, `ResourcePaths.kt`). It is computed from the title on each request, not
   stored, so a new title gives a new slug and the old one moves with a 301. A title with no Latin letter or digit has
   no slug, and its address is `/resource/{id}`. The frontend computes the same slug (`frontend/src/lib/slug.ts`);
   both sides test the same list of titles. Every link the app writes, the canonical, the JSON-LD, the related links
   and the sitemap use the readable address. The app has one route, `resource/:id/:slug?`: when the address it
   opened is not the readable one, it replaces it once the resource is loaded, without moving the focus.
3. **The resource head.**
   - Title, about 60 characters: `{title}: {type in words} · Helper2.ai`.
   - Meta description: the source's own description, cut at a word near 155 characters. An AI-written description
     (`AI_SUMMARY`, `AI_EXTRACTED`) is never used here, because a meta tag cannot carry the AI label (ADR 0018). Without
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

### Consequences
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

### Security (STRIDE)
| Threat | Answer |
|---|---|
| Spoofing | None: public pages, no identity involved. |
| Tampering (HTML or script injection through source text) | One escaper for HTML, Jackson with HTML-safe escapes for JSON-LD, hostile-text tests (4). |
| Repudiation | Not relevant: read-only. |
| Information disclosure | Only what the public API already shows; AI-written descriptions stay out of meta tags; admin paths noindex and only on the admin host. |
| Denial of service | One indexed read per page; the sitemap is built at most once an hour; the edge rate limits of ADR 0025 apply. |
| Elevation of privilege | None: no write path. |

### AI architecture check
No AI layer changes. Guardrails: AI-written text never reaches a meta tag or JSON-LD, and the body shows it only with
its label and `data-ai-generated` (ADR 0018). Observability: the 404 and 301 counts per route show up in the existing
HTTP metrics. Deployment: one new setting, `H2_PUBLIC_BASE_URL` (Helm `app.publicBaseUrl`, empty = the default);
`H2_NOINDEX` is reused. The other layers are not
touched.

### Alternatives considered
- **Server-side rendering with a Node server** (React Router framework mode) — every page fully rendered, but a second
  runtime, image and cache to run, hydration bugs, and weeks of work on an SPA that works. Too much for one person.
- **Prerender at build time** — cheap for the five static pages, useless for thousands of resources that change every
  day, and no real 404s.
- **Dynamic rendering for bots** (Prerender.io, Rendertron) — a headless browser for crawlers only. Google calls it a
  workaround; bots and people get different paths, and it costs money or a service.
- **Client-side tags only** (React 19 `<meta>`) — the cheapest, and it fixes soft 404s for Google, but AI crawlers and
  link previews still see an empty page.

### Code
- `backend/apps/public/src/main/kotlin/ai/helper2/pages/` — `Routes.kt` (`PageKind`, `PageAddress`), `PageHead.kt` (`PageHead`,
  `ResourceView`, `IndexPolicy`), `Pages.kt` (the head of each page, `JsonLd`).
- `backend/apps/public/src/main/java/ai/helper2/pages/` — `usecase/RenderPage`, `usecase/ListSitemap`, `internal/PageTemplate`,
  `internal/PageHtml` (the escaping), `web/PageRoutes`, `web/CrawlerFiles`, `config/PagesProperties`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ClientRoutes.java`; `catalog/usecase/ListIndexableResources.java`,
  `catalog/internal/ResourceStore.indexable`.
- `frontend/src/lib/head.ts` (`useDropServerHead`), `frontend/src/styles/app.css` (`.h2-prerender`).
- `backend/apps/public/src/main/kotlin/ai/helper2/pages/ResourcePaths.kt` (`Slug`, `resourcePath`); `frontend/src/lib/slug.ts`,
  `frontend/src/lib/searchState.ts` (`resourcePath`), `frontend/src/pages/ResourcePage.tsx` (the address replaced).
- Tests: `pages/PagesTest.kt` (slug list), `pages/PageHtmlTest.java`, `api/PagesIT.java`, `frontend/src/lib/head.test.tsx`, `frontend/src/lib/slug.test.ts` (same slug list).

### Diagrams
[flow](../../diagrams/flow-0029-pages-for-crawlers.svg) · [sequence](../../diagrams/sequence-0029-pages-for-crawlers.svg)

### Roadmap
- 410 for resources removed on purpose, once the catalog has a removed state.
- A sitemap index when index-worthy pages pass 50,000; an Open Graph image (brand first, then per resource).
- Hub pages per type or use case (`/mcp-servers`), `llms.txt`, IndexNow pings, one Open Graph image per resource.

<a id="part-0052"></a>

## Part: was ADR 0052 — Policy endpoint: the limits and retention periods the text pages state, read from the configuration

**Status.** Accepted. **Builds on:** [0003](../foundation/0003-contract-first-rest-api.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md), [0025](../edge/0025-http-edge-protection.md), [0035](../operations/0035-data-retention.md), [0014](../search/0014-tavily-store-and-daily-quota.md)

### Context
The Mission, Privacy and Terms pages state numbers: how many candidates the guide reads, how many picks it gives, the
daily limits, and how long each store keeps data. The pages type them in. The code reads the same numbers from
configuration, and every one can change with an `H2_` variable, without a release. So a page can say "30 questions a
day" while the server allows 50, and nothing fails. For the Privacy page that is a legal problem, not only a bug.

### Decision
1. **One public read: `GET /api/v1/policy`.** It returns the numbers the text pages state, as the running server
   applies them. No sign-in, no cookie, no personal data: the same answer for everyone.
2. **What it holds.** Only values the code reads from configuration, or a rule fixed in code that a page states:
   - `guide`: `maxCandidates` (`H2_GUIDE_MAX_CANDIDATES`) and `maxPicks` (`GuideValidator.MAX_PICKS`, fixed in code).
   - `dailyLimits`: `guideQuestions` (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`), `freeAnswers` (answers with no pick and
     failed questions that spend no question, `H2_GUIDE_CLIENT_DAILY_FREE_ANSWERS`, [0017](../guide/0017-guide-cost-guard-and-answer-cache.md))
     and `webSearches` per feature for guests and for signed-in users. `registered` is null while the registered tier
     is off, because everyone then gets the guest limits.
   - `retention`, in seconds: the web search counters (`RedisQuotaCounter.KEEP`, fixed in code) and the guide question
     counters (`RedisCallCounter.KEEP`, fixed in code); articles and news from web search (`H2_RETENTION_WEB_NEWS`),
     pages waiting for review after the decision (`H2_RETENTION_RESOLVED_REVIEW_ITEMS`), the index of awesome lists
     (`H2_RETENTION_LIST_INDEX`), a product page's cleaned text (`H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL`) and its address
     with a fingerprint (`H2_SCOUT_EVIDENCE_PAGE_READ_TTL`, [0023](../scout/0023-evidence-gated-enrichment.md)); database backups
     (`H2_RETENTION_BACKUPS`: the chart passes its `backup.retentionDays`, the app only states it,
     [0032](../operations/0032-runtime-topology.md)); cached searches (`H2_SEARCH_STALE_FOR`), cached guide answers
     (`H2_GUIDE_CACHE_TTL`), translations (`H2_SEARCH_TRANSLATION_TTL`), query vectors (`H2_SEARCH_QUERY_VECTOR_TTL`),
     stored web results (`H2_RETENTION_LIVE_WEB_RESULTS`), inactive accounts with their saved items
     (`H2_RETENTION_INACTIVE_ACCOUNTS`), handled reports (`H2_RETENTION_RESOLVED_ISSUE_REPORTS`) and administrators'
     records in the audit trail (`H2_RETENTION_ADMIN_AUDIT`, [0026](../edge/0026-back-office-and-review.md)), and runtime
     setting changes (`H2_RETENTION_RUNTIME_SETTINGS`, [0007](../foundation/0007-configuration-secrets-and-settings.md); each setting's newest change
     stays while it applies).
   - `sources`: what the Scout reads, as the Privacy page states it: how many categories the discovery searches cover
     and how many searches that is (`helper2.categories`), the longest quote (`EvidenceValidator.MAX_EXCERPT_CHARS`),
     the excerpt kept of an article and of a product page, how many pages of a product's own website one read takes
     (`OfficialPages.MAX_LINKED_PAGES` + 1), and how much of a provider's error message the log keeps
     (`NvidiaEmbedder.MAX_REASON`). All fixed in code.

   Seconds, not ISO-8601: `Duration.toString()` writes 730 days as `PT17520H`, and a page would need a parser. The
   page rounds seconds to hours, days or months.
3. **Each module publishes its own numbers.** The module that owns a setting builds one small value from its
   `@ConfigurationProperties` records at startup, in its base package: `search.SearchPolicy`, `guide.GuidePolicy`,
   `identity.AccountPolicy`, `catalog.CatalogPolicy`, `catalog.ScoutPolicy`. The config records stay internal to their
   module. The Scout runs in the back office and this endpoint in the public app, so the Scout's numbers live in the
   catalog library, with the limits the Scout's code reads, and the public app binds the two retention periods
   (`helper2.scout.evidence`) from the same `H2_` variables ([0032](../operations/0032-runtime-topology.md)). The platform's own numbers (the counter lifetimes, the backup period, the categories) the controller reads
   from the platform directly: they belong to no module.
4. **A thin `policy` module serves them.** Like the back office, it composes other modules and owns no table; no module
   depends on it. `policy.web.PolicyController` maps the five values to the contract. There is no use case: the
   endpoint makes no decision.
5. **Cached and rate-limited.** `Cache-Control: public, max-age=300`, like the home examples. The values change only
   with a restart, so 5 minutes of a stale number after a deploy is acceptable. No ETag: the body is a few hundred
   bytes, and no endpoint of the API has one yet. The `policy` limiter allows `H2_RATE_LIMIT_RECENT_PER_MINUTE`
   requests per client address and minute, like the other public reads.
6. **Public in the security chain.** `/api/v1/policy` is in the `permitAll` list of the public app's `PublicSecurityConfiguration`. It is
   not a page, so the pages for crawlers do not use it.

### Consequences
- A text page can show the number the server applies. A changed `H2_` variable changes the page with the next cache
  refresh, with no release of the frontend.
- A new setting that a page states needs one field in the contract, in the owning module's policy value and in the
  controller.
- A number fixed in code that a page states is in the response too (the counter lifetimes, the quote and excerpt
  lengths): a changed constant changes the page with it. What stays typed is what the code does not know: the
  retention of logs, traces and usage totals, which live outside the app, and the lifetimes of Google's cookies. The
  rate-limit windows stay as words ("the length of the limit"): each limiter has its own.
- Every text page item with a number has a second wording without it, shown when the policy read fails.

### Alternatives considered
- Keep the numbers typed in the pages — no code, but nothing tells when a page and the server disagree.
- The policy module reads the config records of the other modules — fewer types, but the records are internal to their
  modules (Spring Modulith), and the controller would depend on how each module binds its settings.
- Numbers in each existing response (`limits` on GET /me, and so on) — no new endpoint, but the text pages would call
  endpoints made for other screens, and retention periods belong to no screen.
- A build-time step that writes the numbers into the frontend bundle — no runtime call, but it bakes the defaults, not
  the values of the running cluster.

### Code
- `api/public.yaml` — `GET /policy`, schemas `Policy` and `WebSearchLimits`.
- `backend/apps/public/src/main/java/ai/helper2/policy/` — the module and `web/PolicyController.java`.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/SearchPolicy.kt`, `guide/GuidePolicy.kt`,
  `backend/libs/identity/src/main/java/ai/helper2/identity/AccountPolicy.java`, `catalog/CatalogPolicy.java`,
  `catalog/ScoutPolicy.java`, `catalog/config/ScoutEvidenceProperties.java` — the values; built in
  `search/config/SearchConfiguration.java`, `guide/internal/GuideConfiguration.java`,
  `identity/config/IdentityPolicyConfiguration.java`, `catalog/internal/CatalogConfiguration.java` (also `ScoutPolicy`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/config/PlatformRetentionProperties.java` (`backups` from
  `H2_RETENTION_BACKUPS`, set by `deploy/helm/helper2/templates/app.yaml` from `backup.retentionDays`),
  `platform/config/CategoriesProperties.java` (`discoverySearches`), `platform/quota/RedisQuotaCounter.java` and
  `platform/llm/RedisCallCounter.java` (`KEEP`) — the platform's numbers.
- `backend/libs/platform/src/main/java/ai/helper2/platform/RateLimitInterceptor.java` — the `policy` limiter.
- `backend/apps/public/src/main/java/ai/helper2/identity/config/PublicSecurityConfiguration.java` — the public path.
- `backend/apps/public/src/test/java/ai/helper2/policy/web/PolicyControllerTest.java` — every number comes from its module.
- `backend/apps/public/src/test/java/ai/helper2/api/PolicyApiIT.java` — contract, cache header, values from configuration, rate limit.

### Diagrams
[flow](../../diagrams/flow-0029-policy-endpoint.svg) · [sequence](../../diagrams/sequence-0029-policy-endpoint.svg)

### Frontend
The Mission, Privacy and Terms pages read the numbers from `GET /api/v1/policy`
([0028 (part was 0042)](0028-design-system.md), decision 13): `frontend/src/hooks/usePolicy.ts`,
`lib/duration.ts`, `components/TextSections.tsx`, `routes.tsx`.
