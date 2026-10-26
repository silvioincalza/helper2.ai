# Helper2.ai — Federated sources & infrastructure costs (research)

Researched: 2026-10-22. Every fact has a source URL and a short quote from an official page (or from the official API's own response/price list where noted). Decision-driving quotes (Product Hunt, Hetzner, Fly.io, arXiv ToU, npm terms, MCP registry, LLM prices, AWS) were verified verbatim against raw page text; others came via page summaries. "UNKNOWN" = not found in official docs. Prices are list prices on the research date and change often; re-verify before committing.

---

## Part A — Candidate federated sources

### Summary matrix

| # | Source | Search/list endpoint | Auth | Rate limit (unauth / auth) | Dates in response | Cost | Fit for our use |
|---|---|---|---|---|---|---|---|
| 1 | GitHub REST Search | `GET /search/repositories` | Optional (token) | 10 req/min / 30 req/min (search bucket) | `created_at`, `updated_at`, `pushed_at` | Free (paid tier only for high throughput) | OK — use a token; cache |
| 2 | Hugging Face Hub | `GET /api/models`, `GET /api/spaces` | Optional (H2_HF_TOKEN) | 500 / 1,000 API req per 5 min (free user) | `createdAt`, `lastModified` (via `expand`) | Free | OK — always send token |
| 3 | Official MCP Registry | `GET /v0.1/servers?search=` (also `/v0/servers`) | None for reads | UNKNOWN (no numbers published) | `publishedAt`, `updatedAt`, `statusChangedAt` | No charge (unauthenticated API); no pricing statement found | OK, but **ingest + store**, don't live-query per user search |
| 4 | npm registry | `GET registry.npmjs.org/-/v1/search?text=` | None | UNKNOWN numeric; hard ceiling 5M req/month | `package.date`, `updated` | No charge (no auth); no pricing statement found | OK via Public API; cache |
| 5 | PyPI | **No search API** (XML-RPC `search` disabled) | — | No edge rate limit (JSON API) | per-project JSON only | Free | **No federated search possible**; only per-package lookup |
| 6a | Product Hunt API v2 | GraphQL `api.producthunt.com/v2/api/graphql` | Token required | UNKNOWN numeric ("fair-use") | — | Free | **Forbidden for commercial use** without permission |
| 6b | There's An AI For That | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN — site returned 403/Cloudflare challenge |
| 6c | Wikidata (alternative) | SPARQL `query.wikidata.org` | None | 60 s processing per 60 s per client | depends on query | Free, CC0 | Permitted free option, but sparse/incomplete for AI products |
| 7 | arXiv API | `export.arxiv.org/api/query` | None | 1 req / 3 s, single connection | UNKNOWN — not verified (live call returned HTTP 429) | No charge (no auth); no pricing statement found | OK for metadata (CC0); don't host PDFs |


### 1. GitHub REST Search API

| Item | Fact | Source / quote |
|---|---|---|
| Endpoint | `GET /search/repositories` (up to 100/page) | https://docs.github.com/en/rest/search/search — "Find repositories via various criteria." |
| Result cap | max 1,000 results per query | same — "up to 1,000 results for each search" |
| Search rate, authenticated | 30 req/min | same — "up to 30 requests per minute for all search endpoints" |
| Search rate, unauthenticated | 10 req/min (per IP) | same — "the rate limit allows you to make up to 10 requests per minute" |
| General REST (non-search) | 60/h unauth, 5,000/h auth | https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api — "60 requests per hour" / "personal rate limit of 5,000 requests per hour" |
| Dates | `created_at`, `updated_at`, `pushed_at` (date-time) | search docs — "`pushed_at`: required, string, format: date-time" |
| Terms: abuse | excessive use → suspension | https://docs.github.com/en/site-policy/github-terms/github-terms-of-service (Sec. H) — "excessively frequent requests to GitHub via the API may result in" suspension |
| Terms: token sharing | can't pool tokens to beat limits | Sec. H — "You may not share API tokens to exceed GitHub's rate limitations." |
| Terms: prohibited uses | no spam, no selling personal info | Sec. H — "You may not use the API to download data or Content from GitHub for spamming purposes" |
| Terms: public content | third-party lawful use of public repos not restricted | Sec. D.8 — "do not restrict lawful access to or use of the contents of public repositories" |
| AUP: API ≠ scraping | API collection isn't "scraping" | https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies — "Scraping does not refer to the collection of information through our API." |
| Attribution | UNKNOWN — not found in official docs (no explicit attribution clause for API data) | — |
| Cost | Free; paid only for high throughput | ToS Sec. H — "may offer subscription-based access to our API" for "high-throughput access" |

**Implication:** 30 search req/min per token is the binding constraint → server-side cache keyed by query; don't fan out per keystroke. Repo descriptions/READMEs are user content under their own licenses — store short metadata, link out.

### 2. Hugging Face Hub API

| Item | Fact | Source / quote |
|---|---|---|
| Endpoints | `GET https://huggingface.co/api/models`, `/api/spaces` with `search`, `author`, `filter`, `sort`, `limit`, `expand[]` | https://huggingface.co/docs/huggingface_hub/package_reference/hf_api — "List models hosted on the Huggingface Hub, given some filters." (Note: these list endpoints were **not** found in the published OpenAPI spec at https://huggingface.co/.well-known/openapi.json) |
| Fields | `createdAt`, `lastModified`, `likes`, `downloads`, `trendingScore` returned with `expand[]` | Live response of `GET /api/models?search=llama&limit=1&expand[]=createdAt&expand[]=lastModified&expand[]=likes&expand[]=downloads` returned `"lastModified":"2025-06-18T23:49:51.000Z","likes":5486,…"createdAt":…` |
| Spaces fields | `createdAt`, `likes`, `sdk`, `tags` (default response; no `lastModified` by default) | Live response of `GET /api/spaces?search=chat&limit=1` |
| Rate limits (5-min window, "API" bucket) | Anonymous 500 / Free user 1,000 / PRO 2,500 | https://huggingface.co/docs/hub/rate-limits — table "Anonymous user (per IP address) 500"; "Free user 1,000" |
| Limits may change | anon/free limits not guaranteed | same — "Anonymous and Free users are subject to change over time" |
| Headers | `RateLimit`, `RateLimit-Policy` (IETF draft) | same; confirmed live: `ratelimit-policy: "fixed window";"api";q=500;w=300` |
| Advice | always send a token | same — "make sure you always pass a `H2_HF_TOKEN`" |
| Terms: content | user content stays under its own license | https://huggingface.co/terms-of-service — "Neither party is permitted to remove reference to any such license." |
| Terms: scraping/API reuse | UNKNOWN — no explicit API-reuse, scraping or attribution clause found in ToS | — |
| Cost | Free (paid plans raise limits) | rate-limits page — "upgrade your account to PRO, Team, or Enterprise" |

### 3. Official MCP Registry (registry.modelcontextprotocol.io)

| Item | Fact | Source / quote |
|---|---|---|
| Endpoints | `GET /v0.1/servers` (frozen) and `GET /v0/servers`; params `cursor`, `limit`, `updated_since`, `search`, `version`, `include_deleted` | https://registry.modelcontextprotocol.io/openapi.json — "Get a paginated list of MCP servers from the registry" |
| Search semantics | name substring only | https://github.com/modelcontextprotocol/registry/blob/main/docs/reference/api/official-registry-api.md — "Case-insensitive substring search on server names" |
| Detail / history | `/v0.1/servers/{serverName}/versions[/{version}]` | same doc |
| Response fields | `server.{name,description,title,version,repository,remotes,packages}` + `_meta["io.modelcontextprotocol.registry/official"].{status,publishedAt,updatedAt,statusChangedAt,isLatest}`; `metadata.nextCursor` | Live response of `GET /v0/servers?search=github&limit=1` |
| Auth | none for reads | https://modelcontextprotocol.io/registry/registry-aggregators — "an unauthenticated read-only REST API" |
| **Intended consumption** | aggregators should poll ~hourly and keep their own copy | same — "expected to scrape data on a regular but infrequent basis (e.g., once per hour)" |
| No SLA | no uptime/durability guarantee | same — "does not provide uptime or data durability guarantees" |
| Status | preview; v0.1 API frozen | https://modelcontextprotocol.io/registry/faq — "currently in preview. Breaking changes or data resets may occur"; https://github.com/modelcontextprotocol/registry — "The Registry API has entered an API freeze (v0.1)." (per 2025-10-24 README update; freeze promised "the next month or more" — may be stale) |
| Deleted servers | should honor `status` | aggregators page — "Aggregators may prefer to remove these servers from their index." |
| Rate limits | UNKNOWN — not found in official docs | — |
| Data license | Registry Data is CC0 | https://modelcontextprotocol.io/registry/terms-of-service — "dedicated to the public domain under CC0 1.0 Universal" |
| Branding rule | may say data comes from it; may not imply partnership | ToS §6 — "This data comes from the Official MCP Registry" (permitted example) |
| Cost | No charge (unauthenticated API); no pricing statement found | — |

**Implication:** best-licensed source we have. Build a sync job (`updated_since` incremental, hourly) into our DB rather than calling it per query. Use `/v0.1` (frozen), not `/v0`.

### 4. npm registry search

| Item | Fact | Source / quote |
|---|---|---|
| Endpoint | `GET https://registry.npmjs.org/-/v1/search?text=&size=&from=` | https://github.com/npm/registry/blob/main/docs/REGISTRY-API.md — `size`: "default 20, max 250" |
| Fields | `package.{name,version,description,date,links,keywords,publisher}`, `score`, `searchScore`, `downloads`, `updated` | Live response: `"date":"2026-09-28T23:19:13.609Z"`, `"updated":"2026-10-19T12:41:14.296Z"` |
| Rate limits | UNKNOWN numeric; hard ceiling stated | https://docs.npmjs.com/policies/open-source-terms — "five million requests to npm Services in a single month-long period" is not "remotely reasonable" |
| Load rule | no unreasonable volume | same — "You will not strain infrastructure of npm Services with an unreasonable volume of requests" |
| Replication allowed via API | yes | same — "You may replicate data from the Public Registry using the Public APIs" |
| Website crawling | forbidden (use API, not npmjs.com HTML) | same — "You will not automate access to, use, or monitor the Website" |
| Security data | can't reuse npm security data in other products | same, Conditions #4 (vulnerability reports for internal use only) |
| Attribution | UNKNOWN — not found in official docs | — |
| Cost | No charge (no auth); no pricing statement found | — |

### 5. PyPI

| Item | Fact | Source / quote |
|---|---|---|
| Search API | **None.** XML-RPC `search` returns a fault | Live response from `POST https://pypi.org/pypi` (method `search`): "PyPI no longer supports 'pip search' (or XML-RPC search)" |
| XML-RPC overall | deprecated for new integrations | https://docs.pypi.org/api/ — "No new integrations should use the XML-RPC APIs" |
| Rate limiting | none at edge (heavy caching) | same — "there is currently no rate limiting of PyPI APIs at the edge" |
| Etiquette | avoid bursts | same — "Try not to make a lot of requests (thousands) in a short amount of time" |
| Usable alternative | per-project JSON API (`/pypi/<name>/json`) — lookup only, not search | same — "Query PyPI's Index API or JSON API" |
| Scraping pypi.org/search HTML | UNKNOWN whether permitted — not found in official docs; fault message directs to "via a browser" | — |

**Implication:** PyPI cannot be a live federated search source. Options: enrich packages found elsewhere (e.g., MCP registry `packages[]` with `registryType: pypi`) via the JSON API.

### 6. Consumer AI product catalogs

| Source | Finding | Source / quote |
|---|---|---|
| Product Hunt API v2 | **Commercial use prohibited** without permission | https://api.producthunt.com/v2/docs — "The Product Hunt API must not be used for commercial purposes." |
| Product Hunt — business route | must ask | same — "please contact us at hello@producthunt.com" |
| Product Hunt — auth | token required | same — "only accessible with a provided access_token" |
| Product Hunt — rate limits | UNKNOWN numeric | same — "We reserve the right to rate-limit any application" |
| There's An AI For That | UNKNOWN — not found in official docs (theresanaiforthat.com/api/ and /terms/ returned HTTP 403 / bot challenge; no public API terms could be read) | — |
| Wikidata (fallback) | free, CC0, permitted; coverage of AI products is patchy | https://www.wikidata.org/wiki/Wikidata:Licensing — "released into the public domain under Creative Commons Zero"; limits: https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual — "allowed 60 seconds of processing time each 60 seconds" |

**Honest answer:** no free, *commercially permitted*, AI-product-specific catalog API was confirmed. Product Hunt explicitly forbids commercial use; TAAFT is unverifiable. Wikidata is permitted but incomplete. Plan for: own curated table + user submissions, or negotiate a Product Hunt license.

### 7. arXiv API

| Item | Fact | Source / quote |
|---|---|---|
| Rate limit | 1 request / 3 s, one connection | https://info.arxiv.org/help/api/tou.html — "no more than one request every three seconds" |
| Metadata license | CC0 | same — "Creative Commons Universal (CC0 1.0) Public Domain Declaration" |
| Content | don't host PDFs/source | same — must not "Store and serve arXiv e-prints" without permission |
| Branding | no implied endorsement | same — "Represent your project as endorsed or supported by arXiv.org" (prohibited) |
| Commercial use | UNKNOWN — not stated explicitly | — |
| Dates in response | UNKNOWN — not verified (live `export.arxiv.org` call returned HTTP 429) | — |

---

### 8. Tavily (web search API) — terms

Read 2026-10-25 at https://tavily.com/terms ("Last updated: May 4, 2026"). Quotes via page fetch; re-read the raw page before relying on them.

| Item | Fact | Quote |
|---|---|---|
| Permitted use | internal business purposes only | §2 / §3.2 — "solely for Customer's internal business purposes" |
| Competing products | forbidden | §3.2(v) — "access the Services in order to build a competitive product or service"; §3.2(vi) — "compete with Tavily or its business" |
| Output vs Services | results are not "Services" | §1.7 — "the term “Services” does not include Output." |
| Caching / storing / redisplay | no clause found | — (the copy restriction in §3.2(i) is on the Services, which exclude Output) |
| Attribution | no clause found | — |
| Free tier | can end any time | §5 — "we reserve the right to cease the provision of Free-Trials, at any time and at our sole discretion" |
| Model training on Output | forbidden for competing models | §6.6(i) — "use Output generated by the AI Functionality to develop models that compete with the Company" |

**Implication (judgment, not legal advice):** storing and caching results looks fine (Output is outside the copy restriction). Two open risks: (1) showing results to the public may not count as "internal business purposes"; (2) a search engine could be read as "competitive". For the hackathon this is low risk: Tavily sponsors a "Best Use of Tavily" prize, which assumes public apps built on it. **Before launch on helper2.ai: email Tavily, describe the use (AI-news results shown with a link to the original, cached up to 24 h, indexed every 5 days) and get a written OK.** Without it, switch Tavily off (`H2_TAVILY_API_KEY` unset); search keeps working.

## Part B — Infrastructure & API prices (list prices, 2026-10-22)

### Managed PostgreSQL

| Option | Price | Source / quote |
|---|---|---|
| Neon Free | 100 CU-h/project, 1 GB/project | https://neon.com/pricing — "100 CU-hours/project"; "1 GB/project, 20 GB account total" |
| Neon Launch | $0.106/CU-h + $0.35/GB-month, no minimum | same — "$0.106/CU-hour"; "$0.35/GB-month" |
| Supabase Free | 500 MB DB; pauses after 1 week idle | https://supabase.com/pricing — "500 MB database size per project included"; "paused after 1 week of inactivity" |
| Supabase Pro | from $25/mo incl. $10 compute credits, 8 GB disk | same — "from $25/month"; "$10/month in compute credits" |
| AWS RDS PostgreSQL db.t4g.micro (us-east-1, Single-AZ, on-demand) | $0.016/h (≈$11.68/mo at 730 h) + gp3 $0.115/GB-mo | AWS Price List API `AmazonRDS/current/us-east-1/index.csv` — "$ 0.016 per RDS db.t4g.micro Single-AZ instance hour"; "$0.115 per GB-month of provisioned GP3 storage" |
| AWS RDS Free Tier | 750 h + 20 GB for 12 months (new accounts) | https://aws.amazon.com/rds/postgresql/pricing/ — "750 hours on a selection of Single-AZ instance databases" |
| Render Postgres | Free (limited); paid from $6/mo (256 MB RAM) | https://render.com/pricing — "$6/month 256 MB RAM 100 connections" |

### Managed Valkey / Redis

| Option | Price | Source / quote |
|---|---|---|
| Upstash Free | 500K commands/mo, 250 MB data, 10 GB bandwidth | https://upstash.com/pricing/redis — "Free tier includes 250MB data size and 500K commands per month." |
| Upstash Pay-as-you-go | $0.20 / 100K commands | same — "Pay as You Go $0.2 per 100K commands" |
| Upstash Fixed | from $10/mo (Fixed 250MB), +$5 per read region | same — "Fixed 250MB … $10 / month" |
| AWS ElastiCache Serverless Valkey (us-east-1) | $0.084/GB-h storage, $0.0023/M ECPU, min 100 MB billed (≈$6.13/mo floor) | AWS Price List `AmazonElastiCache` — "$0.084 per GB-hour for Valkey data storage"; "$0.0023 per million Valkey ECPUs"; https://aws.amazon.com/elasticache/pricing/ — "100 MB per cache for ElastiCache Serverless for Valkey" |
| Render Key Value | Free 25 MB; paid from $10/mo (256 MB) | https://render.com/pricing — "$10/month 256 MB RAM 256 MB storage" |


### App hosting

| Option | Price | Source / quote |
|---|---|---|
| Fly.io shared-cpu-1x | 256 MB $2.19/mo; 512 MB $3.69/mo; 1 GB $6.70/mo; no free tier | https://docs.fly.io/about/pricing — "New organizations don't have a free tier or a monthly free usage allowance." |
| Render web service | Free (limited, 512 MB); Starter $7/mo 512 MB / 0.5 CPU; $25/mo 2 GB / 1 CPU | https://render.com/pricing — "$7/month 512 MB RAM 0.5c-512mb" |
| Hetzner CX23 (2 vCPU, 4 GB, 40 GB NVMe, 20 TB EU) | €5.99/mo excl. VAT — **currently shown as unavailable** | https://www.hetzner.com/cloud/cost-optimized/ — "This product is currently unavailable. Please check back later." |
| Hetzner CAX11 (Arm, 2 vCPU, 4 GB) | €6.49/mo excl. VAT (also shown unavailable) | same |
| Hetzner CX22 | UNKNOWN — not listed on current Hetzner Cloud pages (CX23 appears to be its successor; unconfirmed) | — |

### Domain (.ai)

| Item | Fact | Source / quote |
|---|---|---|
| Porkbun .ai | $82.70 registration / $82.70 renewal / $165.09 transfer (page doesn't clearly say per-year; treat renewal as ≈$165 per 2-year cycle if per-year) | https://porkbun.com/tld/ai — "everyday low price" |
| 2-year minimum | Yes (registrar statement) | same — ".AI Domains require a minimum term of 2 years for registration and renewals." |
| Cloudflare Registrar | supports .ai at cost; exact price UNKNOWN (needs logged-in search) | https://www.cloudflare.com/products/registrar/ — "always at or below what registries and ICANN charge us" |
| Registry (Identity Digital) policy page | UNKNOWN — not found in official registry docs | — |

### LLM API (per 1M tokens, standard tier)

| Model | Input | Output | Source / quote |
|---|---|---|---|
| **Used:** NVIDIA Nemotron-3-Super-120b-a12b on Nebius Token Factory | $0.30 | $0.90 | https://tokenfactory.nebius.com/model-catalog.md (official catalog, read 2026-10-26): input 0.3, cached input 0.06, output 0.9; region us-central1 |
| NVIDIA Nemotron-3-Nano-30B-A3B on Nebius Token Factory | $0.06 | $0.24 | https://tokenfactory.nebius.com/model-catalog.md (read 2026-10-26); region eu-north1 |

Helper2.ai uses NVIDIA models only. The rows below are a market reference from the 2026-10-22 research, not options.

| Model | Input | Output | Source / quote |
|---|---|---|---|
| Claude Haiku 5.5 (≤100K-token prompts) | $0.10 | $0.50 | https://platform.claude.com/docs/en/about-claude/pricing — "Claude Haiku 5.5 (for prompts up to 100,000 tokens) \| $0.10 / MTok" |
| Claude Haiku 5.5 (>100K-token prompts) | $0.50 | $2.50 | same |
| Claude Haiku 4.5 (reference) | $1 | $5 | same |
| OpenAI gpt-5-nano (cheapest listed) | $0.05 | $0.40 | https://developers.openai.com/api/docs/pricing — row `gpt-5-nano` 0.05 / 0.4 |
| OpenAI gpt-6-luna (smallest current flagship) | $0.10 | $0.50 | same — "gpt-6-luna $0.10 $0.50" |
| OpenAI gpt-4.1-nano | $0.10 | $0.40 | same |

Batch API is 50% off on both (Haiku 5.5 batch $0.05/$0.25; gpt-6-luna batch $0.05/$0.25) — same pages.

### GitHub API cost

| Item | Fact | Source / quote |
|---|---|---|
| Price | Free with a Free account; no per-call price published | https://github.com/pricing — "$ 0 USD" "per month forever"; ToS Sec. H — paid access only "for those Users who require high-throughput access" |

### Auth

| Option | Free tier | Paid entry | Source / quote |
|---|---|---|---|
| Keycloak (self-hosted OSS) | Free software, Apache-2.0 (hosting cost is ours) | — | https://github.com/keycloak/keycloak/blob/main/LICENSE.txt — "Apache License Version 2.0" |
| Auth0 | up to 25,000 MAU, 1 custom domain | Essentials from $35/mo (B2C) | https://auth0.com/pricing — "Up to 25,000 monthly active users" |
| Clerk | 50,000 MRU per app | Pro $25/mo ($20 annual), +$0.02/MRU | https://clerk.com/pricing — "50,000 MRU limit per app" |

---

## Open items / UNKNOWNs to resolve
- MCP Registry numeric rate limits; npm search numeric rate limits; Product Hunt numeric limits.
- TAAFT API existence/terms (site blocks automated fetch — check manually in a browser).
- HF ToS has no explicit clause on reuse of API metadata in third-party search — consider emailing HF if storing at scale.
- Porkbun .ai price: per-year vs per-2-year-term not stated on page.
- arXiv Atom `published`/`updated` fields not verified (rate-limited, HTTP 429).
