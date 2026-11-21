# Sources

The contract of the external system Source Provider ([stack](stack.md)): what helper2.ai queries, within which limits
and under which terms ([C-008](../requirements.md)). Live view: `GET /api/v1/sources` and the Sources page.
Evidence for every term and limit: [Terms evidence](#terms-evidence).
What each source's terms allow a site with ads: [Terms for commercial use](#terms-for-commercial-use). Who receives data and on what
transfer basis: [data-processing.md](data-processing.md).
GitHub, Hugging Face, npm, Hacker News and arXiv get the query's content words, not the whole sentence; Live web gets the sentence
([ADR 0029](decisions/ADR-0029-sources-search-content-words.md)).

| id | Name | Mode | Endpoint | Auth | Limits | Terms / attribution | Dates provided |
|---|---|---|---|---|---|---|---|
| `github` | GitHub | LIVE | `GET /search/repositories` | optional `H2_GITHUB_TOKEN` | 10/min unauth, 30/min token | GitHub ToS; no token pooling | created_at → published; pushed_at → updated |
| `hf-models` | Hugging Face models | LIVE | `GET /api/models?search=` with the query's content words; a task query (speech to text, image segmentation, reranker…) adds `pipeline_tag=` and `sort=likes`, and a second call fills a thin page (ADR 0029) | optional `H2_HF_TOKEN` | 500/5 min anon | HF ToS (silent on metadata reuse → UNKNOWN, link back to model) | createdAt, lastModified |
| `hf-spaces` | Hugging Face Spaces | LIVE | `GET /api/spaces/semantic-search?q=` (by meaning, running Spaces); `GET /api/spaces?search=&expand[]=runtime` (3 × the limit asked) when it finds nothing, for a date window and for browsing; the vitality rule leaves out Spaces that do not run | optional `H2_HF_TOKEN` | same | same | createdAt, lastModified (expanded) |
| `npm` | npm registry | LIVE | `GET /-/v1/search?text=` | none | unpublished; be reasonable | npm Open-Source Terms: API OK, no website crawling, no reuse of security data | `date` (latest publish) → updated; created unknown, so npm results never answer "born in a period" |
| `hacker-news` | Hacker News search (Algolia) | LIVE ([ADR 0030](decisions/ADR-0030-hacker-news-as-a-live-source.md)), on by default (`H2_SOURCE_HACKER_NEWS_ENABLED=true`) | `GET /api/v1/search?query=&tags=story&hitsPerPage=` on hn.algolia.com, content words, 3 × the limit asked, `numericFilters=created_at_i>=` for a date window; a hit is the linked page, never the discussion; stories without a link or not about AI are left out | none | not documented on the API page (about 10,000/hour per IP reported by third parties) | No API terms page; HN/YC terms ([Terms for commercial use](#terms-for-commercial-use)); only title, link and date are kept | `created_at` → published |
| `arxiv` | arXiv | LIVE (+6 h cache) | `GET /api/query` (Atom), AI categories only (cs.AI, cs.CL, cs.LG, cs.CV, cs.MA, cs.IR, stat.ML) | none | 1 request / 3 s (adapter self-throttles → RATE_LIMITED) | Metadata CC0; must not imply endorsement; acknowledgement shown | published, updated |
| `live-web` | Live web search | LIVE, store first ([ADR 0027](decisions/ADR-0027-live-web-store-first-daily-quota.md)), **only with `H2_LIVE_WEB_API_KEY`**: a stored result set answers for 24 h (time-bound questions: news, courses, events) or 48 h (other questions) without a call | `POST /search` topic `news` | Bearer key | 1 credit / basic search, only when no fresh set is stored; free tier 1,000 credits/month. Per visitor and UTC day: 3 web searches for search and 3 for the guide, signed in or not; optional global cap `H2_LIVE_WEB_DAILY_CREDITS`. Over the limit: `SKIPPED`, stored set up to 7 days old | The provider's terms (the provider is named in [ADR 0027](decisions/ADR-0027-live-web-store-first-daily-quota.md) and on the Privacy page), read 2026-10-25: no building a "competitive product or service" (§3.2(v)); no attribution clause. The provider gave its written OK to show its results publicly, to store them and to reuse them across users. Stored sets are deleted after 7 days | `published_date` (the provider's estimate, null → unknown); event dates never inferred |
| `course-scout` | Courses and certifications, through Live web | REVIEW ONLY (Scout, every 7 days), **only with `H2_LIVE_WEB_API_KEY`** | `POST /search` topic `general`, one query per platform with `include_domains` (coursera.org, oreilly.com, edx.org, udemy.com, deeplearning.ai, huggingface.co/learn, cloud.google.com/learn, aws.amazon.com/certification, learn.microsoft.com/en-us/credentials, nvidia.com), `start_date` = previous pass. Nothing is indexed: each new page becomes a `COURSE_CANDIDATE` review item; an accepted page is added to the curated list by hand ([ADR 0031](decisions/ADR-0031-guide-pipeline.md)) | Bearer key | 1 credit / platform and pass → ~43 credits/month | same as `live-web`. Coursera, O'Reilly, edX and Udemy offer no catalog API without a partner or enterprise agreement, so no live source exists for them | none stored |
| `hf-trending` | Hugging Face trending | INDEXED (Scout, every 1 day) | `GET /api/models?sort=trendingScore&direction=-1&limit=100`, indexed only with ≥ 20 likes (the admission rule of the Scout, [ADR 0020](decisions/ADR-0020-resource-vitality.md) §11) | optional `H2_HF_TOKEN` | 1 request / pass | same as `hf-models` | createdAt |
| `live-web-scout` | AI news, through Live web | INDEXED (Scout, every 5 days), **only with `H2_LIVE_WEB_API_KEY`** | `POST /search` topic `news`, 7 fixed queries (4 general, 3 on new models by provider: OpenAI/Anthropic/Google, xAI/Meta/Mistral, NVIDIA/DeepSeek/Qwen), `start_date` = previous pass | Bearer key | 1 credit / query and pass → ~42 credits/month (with `course-scout`, the discovery categories and `linkedin-scout`, the Scout uses ~506 of the plan's 14,000 credits a month) | same as `live-web` | `published_date` (the provider's estimate) |
| `discovery-<category>` | Live web discovery, one source per category (34, below) | INDEXED for list pages, REVIEW for products (Scout, every 7 days per category, 4 or 5 categories a day), **only with `H2_LIVE_WEB_API_KEY`** ([ADR 0013](decisions/ADR-0013-discovery.md)) | `POST /search` topic `general`, `search_depth advanced`, 10 results, one or two queries per category (`helper2.categories`), `include_published_date`, `include_favicon`, `include_usage`; no raw content, no images, no answer. Social and video sites, AI directories and review sites excluded, plus each category's own list; `start_date` = previous pass. List pages ("12 best…", "X vs Y") are indexed as ARTICLE (not for health and legal); a product's own site or GitHub repository becomes a `RESOURCE_CANDIDATE` review item, indexed only when an admin accepts it. Every result gets the tag of each category it belongs to | Bearer key (shared settings `helper2.sources.discovery`) | 2 credits / search and pass: 39 searches → 78 credits a week, ~335 a month; capped at the Scout's daily limit (`H2_SCOUT_DAILY_CREDITS`, a runtime setting, 3,000 by default), counted on the global credit counter | Live web terms as for `live-web`; written OK for this use: **to confirm**. Stored per page: title, link, the provider's excerpt (≤ 400 characters for a list page; ≤ 2,400 for a product candidate, as enrichment evidence; `basic` and `advanced` return extracts, not summaries) and what the search said (`source_record.extra.discovery`: query, rank, score, depth, shape, published date, request id, matched words, the favicon URL as text, never loaded). ARTICLE records 180 days after retrieval; accepted products while listed. AI directories and review sites are excluded from the searches (their terms forbid copying listings); they are linked from the curated list instead. Removed on request | `published_date` kept as a search fact only |
| `linkedin-scout` | LinkedIn pages, through Live web | REVIEW ONLY (Scout, every 7 days), **only with `H2_LIVE_WEB_API_KEY`** ([ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md)) | `POST /search` topic `general`, `search_depth basic`, 10 results, `include_domains` linkedin.com, 20 fixed queries (`helper2.scout.linkedin.queries`: model, tool and MCP launches, company announcements, events), `include_usage`; no raw content, no images, no answer; `start_date` = previous pass. We never call LinkedIn: the pages come from the Live web index. The path decides the type: `/pulse/` ARTICLE, `/posts/` and `/feed/update/` NEWS, `/company/` and `/showcase/` PRODUCT, `/events/` EVENT; profiles, jobs and learning pages are dropped. Every kept page becomes a `RESOURCE_CANDIDATE` review item (tag `linkedin`), indexed only when an admin accepts it | Bearer key | 1 credit / query and pass: 20 credits a week, ~86 a month; on the Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, a runtime setting, 3,000 by default) with the discovery categories, counted on the global credit counter | LinkedIn's User Agreement forbids scraping and its API has no search, so pages come only from the Live web index; Live web terms as for `live-web`, written OK covering LinkedIn pages: **to confirm**. Stored per page: link, a title without the author's name, an excerpt ≤ 300 characters; no author field. Articles and posts 180 days after retrieval; accepted company pages and events while listed. Removed on request | none; an accepted event has no stated days, so it is not in Next events ([ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md)) |
| `live-web-events-scout` | Upcoming AI events, through Live web search and page extract; an AI model reads the days ([ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md)) | INDEXED (Scout, every 7 days), on by default (`H2_SOURCE_LIVE_WEB_EVENTS_SCOUT_ENABLED=true`), needs `H2_LIVE_WEB_API_KEY` and an AI model | `POST /search` topic `general`, `search_depth basic`, 10 results, 4 fixed queries (`helper2.scout.live-web-events.queries`: upcoming AI hackathons, meetups and talks, conferences and summits, workshops), `include_usage`; then `POST /extract` (basic, markdown, no images) of at most 20 search pages a pass, 4 a call, plus the pages of undated catalog EVENTs first found since the last pass (at most 20 a call, oldest first; a full batch keeps the pass open). Hidden text, email addresses and phone numbers removed before the model reads a page | Bearer key | ~4 search + ~4 Extract credits a week, ~35 a month, on the Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`); one model call per page, at most `H2_SCOUT_EVENT_READER_DAILY_CALLS` (40) a day | Live web terms as for `live-web`; written OK for scheduled searches whose pages we list: **to confirm** (page extract: received). Stored per event: title, link, the provider's excerpt, the quote that states the days, the model and prompt version (`source_record.extra.eventEvidence`). Deleted ten years (3650 days) after the event ends. Removed on request | event days read by an AI model from a quote of the page and checked by code (AI_EXTRACTED, labelled); never over days another source states |
| `github-awesome` | GitHub Awesome lists | INDEXED (Scout, pass every 1 day, ≤ 1,000 entries per run and per pass), **only with `H2_GITHUB_TOKEN`** (GraphQL rejects anonymous calls) | `GET /search/repositories` by topic pair (6 queries, ≥ 1,000 stars, name must contain "awesome", top 10 lists) → `GET /repos/{list}/readme` (raw) → `POST /graphql` 50 repos per call. Every link of an entry goes to the rule of its kind ([ADR 0013](decisions/ADR-0013-discovery.md) §17): a Hugging Face model is read from the Hub (`GET /api/models/{owner}/{name}`, ≤ 100 a pass; indexed with ≥ 10 likes, else to review), an arXiv paper is read by id from the arXiv API (`GET /api/query?id_list=`, 50 ids a call, ≤ 200 papers a pass, through the shared 3 s slot; AI categories only; no author, no PDF), an npm package by name from the registry (`GET /{name}/latest`, ≤ 100 a pass; the npm AI check; a deprecated one is refused), a page on a course platform and a product site go to review. A list an administrator added is read too: a GitHub repository first at every pass, a web page through the Live web page extract (Markdown) at most once in `H2_SCOUT_AWESOME_WEB_PAGES_EVERY` (7 days), never on a host of `H2_SCOUT_AWESOME_WEB_PAGES_NEVER_READ` ([ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md) point 6). Entries kept with ≥ 50 stars and judged AI by the fast model; each states archived and fork, and the vitality rule refuses an archived one and keeps an active fork ([ADR 0020](decisions/ADR-0020-resource-vitality.md)). The model is `helper2.llm.fast-model` (Nemotron 3 Nano; keyword rules if no model or no answer) | token: a fine-grained PAT with public read-only access is enough | per pass ~6 search + 10 README + 20 GraphQL calls; Nano ~0.5 s/entry, 25 per call | GitHub ToS. No list text is stored: each list is a resource (ARTICLE, tag `awesome-list`) with facts from the search answer, and its entry lines and headings are kept only as a search index (tsvectors) plus each entry's heading path, at most 200 characters ([ADR 0023](decisions/ADR-0023-catalog-full-text-index.md)); descriptions, license, stars and dates come from the GitHub API. **The type is the model's choice** (no per-field origin on `type` yet) | createdAt → published; pushedAt → updated |
| `curated-web-lists` | Curated web lists | INDEXED (Scout, written by the `github-awesome` collector in the same pass, on when it is on; [ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md) point 6) | the web pages an administrator added in "Lists to read", read through the Live web page extract (`POST /extract`, Markdown) at most once in `H2_SCOUT_AWESOME_WEB_PAGES_EVERY` (7 days); a failed read tried again after `H2_SCOUT_AWESOME_WEB_PAGES_RETRY_AFTER` (1 hour), 3 times in a row at most; never on a host of `H2_SCOUT_AWESOME_WEB_PAGES_NEVER_READ` (default coursera.org) | `H2_LIVE_WEB_API_KEY` (the page reader) | at most 50 lists together; one Live web credit per read, on the Scout's daily cap (`scout-list-pages`) | the terms of each page's site, read by the administrator who adds it; each list resource links back to its page. No page text is stored: the record is the list resource (title, address, tag `awesome-list`), and the last read keeps headings, entries and links only ([ADR 0010](decisions/ADR-0010-data-retention.md)) | none |
| `hf-provider-orgs` | Hugging Face model makers | INDEXED (Scout, every 1 day) | `GET /api/models?author={org}&sort=createdAt&direction=-1&limit=20` for 36 official orgs (the labs, Chinese labs, open research, embedding and image, video and speech makers listed in ADR 0012); the org is added as a tag | optional `H2_HF_TOKEN` | 36 requests / pass | same as `hf-models` | createdAt |
| `ai-deadlines` | AI conference deadlines (huggingface/ai-deadlines) | INDEXED (Scout, every 1 day) | `GET /repos/huggingface/ai-deadlines/contents/src/data/conferences` → each raw YAML file; one record per conference, rolled to its next edition that has not ended | optional `H2_GITHUB_TOKEN` (contents API: 60/h anon) | 1 API call + ~70 raw files / pass | MIT License; link back to the repository and each conference's own site | `start`, `end` → event days (DAY precision); no publication date |
| `confs-tech` | Tech conferences (confs.tech, tech-conferences/conference-data) | INDEXED (Scout, every 1 day, `H2_SCOUT_CONFS_TECH_INTERVAL`), on by default (`H2_SOURCE_CONFS_TECH_ENABLED=true`, [ADR 0012](decisions/ADR-0012-scout-collectors-and-events.md)) | `GET /repos/tech-conferences/conference-data/contents/conferences/{year}` for this year and the next → each raw JSON topic file; kept: a name that passes the AI check, not ended, one record per canonical URL | optional `H2_GITHUB_TOKEN` (contents API: 60/h anon) | 2 API calls + ~60 raw files / pass | MIT License; link back to confs.tech and each conference's own site | `startDate`, `endDate` → event days (DAY precision); no publication date |
| `mcp-registry` | Official MCP Registry | INDEXED (Scout, interval 1 h, `H2_SCOUT_MCP_INTERVAL`; up to 5,000 servers a run, `H2_SCOUT_MCP_ITEMS_PER_RUN`, [ADR 0015](decisions/ADR-0015-reference-projects.md)) | `GET /v0.1/servers?version=latest&updated_since&cursor` | none | unpublished; the registry asks for hourly polling, but the Scout runs once a day in the cluster (`scoutCron`, 03:07 UTC), so it is read daily | CC0; "This data comes from the Official MCP Registry"; preview API, data may reset | publishedAt, updatedAt |
| `feed-<id>` | News feeds of AI companies, one source per feed (8: `feed-openai`, `feed-google-deepmind`, `feed-google-research`, `feed-google-ai`, `feed-hugging-face`, `feed-mistral`, `feed-nvidia`, `feed-aws-ml`; YAML `helper2.feeds`) | INDEXED (Scout, every 1 day, `H2_SCOUT_NEWS_FEEDS_INTERVAL`), on by default (`H2_SOURCE_NEWS_FEEDS_ENABLED=true`, [ADR 0016](decisions/ADR-0016-news-feeds-of-ai-companies.md)) | `GET` the feed URL (RSS 2.0 or Atom), first 100 posts; the NVIDIA feed keeps only posts with an AI word | none | 1 request a feed a day; response ≤ 2 MB | each company's site terms ([Terms for commercial use](#terms-for-commercial-use)); title, link, date and the feed's summary (≤ 400 characters), linked to the original post | RSS `pubDate`, Atom `published` (else `updated`) → published |
| `pypi` | PyPI (Python packages), public metadata through Google BigQuery (`bigquery-public-data.pypi.distribution_metadata`) | INDEXED and REVIEW (Scout, every 1 day, `H2_SCOUT_PYPI_INTERVAL`), **off by default** (`H2_SOURCE_PYPI_ENABLED=false`); needs `H2_PYPI_BIGQUERY_PROJECT` and `H2_PYPI_BIGQUERY_KEY` ([ADR 0017](decisions/ADR-0017-pypi-through-bigquery.md)) | BigQuery `jobs.query`: packages with the classifier `Topic :: Scientific/Engineering :: Artificial Intelligence`, newest upload per package, from the last pass − 2 days (first pass: the whole table), at most 5,000 a pass; with a summary and a GitHub repo → Python library, else review (≤ 20 a day) | service account key (Google, BigQuery Job User only) | 1 query a day; `maximumBytesBilled` 20 GB; free tier 1 TiB a month | PyPI terms and Google Cloud terms; the BigQuery tables are CC BY 4.0 ([Terms for commercial use](#terms-for-commercial-use)); never the author, maintainer or e-mail columns | newest upload → updated |
| `github-topic-leaders` | GitHub topic leaders | INDEXED and REVIEW (Scout, pass every 7 days, `H2_SCOUT_TOPIC_LEADERS_INTERVAL`), **only with `H2_GITHUB_TOKEN`** | To index: `GET /search/repositories?q=topic:{t} stars:>=1000 pushed:>={today − 90 days} fork:false archived:false&sort=stars&order=desc&per_page=100&page={n}`, up to 10 pages per topic. For review: `q=topic:{t} stars:>=3000 fork:false archived:false`, `per_page=50`, one call per topic (14 topics, `H2_SCOUT_TOPIC_LEADERS_TOPICS`) | token: the same read-only PAT | at most 154 search calls a pass, 2 s apart (`H2_SCOUT_TOPIC_LEADERS_SEARCH_PAUSE`) | GitHub ToS. A repository with 1,000 stars or more (`H2_SCOUT_TOPIC_LEADERS_INDEX_MIN_STARS`) and a push in the last 90 days (`H2_SCOUT_TOPIC_LEADERS_INDEX_PUSHED_WITHIN`) is indexed as it is found; `H2_SCOUT_TOPIC_LEADERS_INDEX_PER_TOPIC=0` turns this off. A quieter leader not yet in the catalog becomes a resource candidate in the admin review list (at most 25 new a day, `H2_SCOUT_TOPIC_LEADERS_PER_DAY`); full name, description, stars, topics, licence and dates come from the search answer ([ADR 0015](decisions/ADR-0015-reference-projects.md)) | none |
| `github-sdks` | AI libraries and SDKs | REVIEW (Scout, pass every 7 days, `H2_SCOUT_SDKS_INTERVAL`; 20 queries a run, `H2_SCOUT_SDKS_QUERIES_PER_RUN`), **only with `H2_GITHUB_TOKEN`** | GitHub: `GET /search/repositories?q=topic:{t} language:"{l}" stars:>={floor} fork:false archived:false&sort=stars&order=desc&per_page=100&page={p}` for 16 topics × 9 languages (floors 5,000 for Python, TypeScript, JavaScript; 1,000 for the others, `H2_SCOUT_SDKS_LANGUAGES`), then `q=org:{o} stars:>=300 …` for 18 AI provider orgs (`H2_SCOUT_SDKS_ORGS`); up to 3 pages a query (`H2_SCOUT_SDKS_PAGES_PER_QUERY`), 2 s apart (`H2_SCOUT_SDKS_SEARCH_PAUSE`). deps.dev: `GET /v3/projects/github.com%2F{owner}%2F{repo}:packageversions`, then `GET /v3/systems/{S}/packages/{name}` and `…/versions/{default}` for at most 3 packages a repo (`H2_SCOUT_SDKS_PACKAGES_PER_REPO`), only for repos not yet listed or proposed and within the day's cap | GitHub: the same read-only PAT; deps.dev: none (the token is never sent there) | up to 486 searches a pass; the run stops when `X-RateLimit-Remaining` falls to 10 (`H2_SCOUT_SDKS_RATE_LIMIT_RESERVE`); deps.dev no published limit, 404 = no package | GitHub ToS; deps.dev under the Google APIs Terms, its generated data CC-BY 4.0, caching allowed. Nothing is indexed: each AI repo not yet in the catalog becomes a resource candidate (at most 500 new a day, `H2_SCOUT_SDKS_PER_DAY`, official repos with a confirmed package first) with its languages, an official flag for the provider orgs, its packages (registry, name, latest version, release date, licences) and their search words; a package counts only when its latest version points back to the repo ([ADR 0018](decisions/ADR-0018-ai-libraries-and-sdks-per-language.md)) | the main package's release → updated (on accept) |
| `gap-evidence` | README and model card of an indexed resource (enrichment gap round, [ADR 0014](decisions/ADR-0014-evidence-gated-enrichment.md)) | EVIDENCE ONLY (Scout, only after an enrichment run leaves fields unproven, at most `H2_SCOUT_GAP_DAILY_CALLS` (400) a day) | GitHub `GET /repos/{owner}/{repo}/readme` (raw) and `GET /repos/{owner}/{repo}`; Hugging Face `GET /{owner}/{model}/raw/main/README.md` | `H2_GITHUB_TOKEN`, optional `H2_HF_TOKEN` | 2 GitHub calls or 1 Hub call per gap round; ≤ 512 KB | GitHub ToS, HF ToS. The README and card text is not stored; excerpts of 20–300 characters are kept as evidence, with the repository or model named and a link back. Only for the resource's own repository or model (an MCP Registry entry only in its own `io.github.<owner>` namespace) | none (read time only) |
| `page-reader` | A product's own web pages (evidence ladder, [ADR 0014](decisions/ADR-0014-evidence-gated-enrichment.md)) | EVIDENCE ONLY (Scout, only after an enrichment run leaves a target field unproven, for `discovery-<category>` and `curated` product pages; at most the page-read limit a day (`H2_SCOUT_PAGE_READER_DAILY_CREDITS`, a runtime setting, 3,000 credits by default), also on the global Live web cap) | `POST /extract`, `extract_depth` basic, `format` markdown, no images: the accepted page, then up to 3 pages it links on the same site (pricing, plans, download, features, docs, integrations, about). Code picks every URL; https, no IP, no port; same registrable domain (exact host on shared hosts); every returned URL checked again | `H2_LIVE_WEB_API_KEY` | 1 credit per call of up to 5 pages; ≤ 2 calls per product; a page is not read again for 30 days | Live web terms as for `live-web`; written OK for page extract: received. The raw page is never stored; the cleaned text is kept in Redis for at most 24 hours, only to retry; hidden text removed, emails and phone numbers masked; excerpts of 20–300 characters kept as evidence with the page link; after a saved run, the page URL and a hash of its text kept 30 days (`ai_cache`). Facts only these pages state are shown "Self-declared by the vendor" | none (read time only) |
| `helper2-index` | helper2.ai catalog | INDEXED | PostgreSQL FTS | — | — | own data | relays original attribution |
| `helper2-semantic` | helper2.ai catalog, by meaning | INDEXED ([ADR 0024](decisions/ADR-0024-vector-search.md)) | PostgreSQL pgvector, cosine distance ≤ 0.65 (`H2_SOURCE_SEMANTIC_MAX_DISTANCE`), timeout 3 s; the query as typed, not translated | none on the hosted helper2: the embedding model (`nvidia/nemotron-3.5-embed-1b`) runs in the cluster ([C-002](../requirements.md)); an NVIDIA key only where the chart's embedder is off and the model is called on `integrate.api.nvidia.com` | one embedding call per new query; query vectors cached 7 days | own data. Without the chart's embedder, the embedding service is NVIDIA's hosted endpoint: [NVIDIA API Trial Terms of Service](https://assets.ngc.nvidia.com/products/api-catalog/legal/NVIDIA%20API%20Trial%20Terms%20of%20Service.pdf) (v. 19 Sep 2025, read 2026-11-06) allow trial use only, not production (§1.2, §1.4), and let NVIDIA use the text to improve its models (§3.3(iv)); production needs a subscription whose own terms govern (§1.4). The hosted helper2 sends no text there; the Privacy page names no provider for search by meaning | relays original attribution |
| `curated` | Hand-picked list | CURATED | PostgreSQL | — | — | editorial; no publication dates or prices asserted. Industry events carry the days stated on each event's own site, with that excerpt as evidence, and the format (online, in person) only when the page states it | event days only (industry events) |
| `demo-*` | Demo sources | DEMO | in-process fixtures | — | — | **profile `demo` only** | fixture |

## Terms for commercial use

Each source's terms are read for a site that shows ads and takes gifts, one source at a time. The reading and
its legal points are tracked privately (S9.6.1, S9.6.3). The credits a source's terms ask for are shown on the Sources
page ([S9.6.2](https://github.com/silvioincalza/helper2.ai/issues/434)).

**Discovery categories** ([ADR 0013](decisions/ADR-0013-discovery.md)): one `discovery-<id>` source each, grouped by
family on the Sources page. Health and legal keep only results with their own words (`require-keyword`), with a Live web
score of 0.5 or more, and index no list pages.

| Family | Categories (id) | Searches |
|---|---|---|
| Prompts and skills | `prompt-libraries`, `skills-and-gpts` | 2 |
| Everyday apps | `ai-assistants`, `writing`, `translation`, `meetings-and-transcription`, `documents-and-pdf`, `research-and-papers`, `ai-search`, `browser-extensions`, `education-and-tutors` (2: students, teachers), `health` (2: professionals, patients) | 12 |
| Creative and media | `image-generation`, `video-generation`, `voice-and-audio` (2: speech, music), `design-and-presentations` (2: slides, websites) | 6 |
| Business | `customer-support`, `voice-agents`, `data-and-analytics`, `no-code`, `marketing-and-seo`, `legal` | 6 |
| Developers | `coding`, `agent-platforms`, `llm-frameworks`, `rag-and-vector-databases` (2: vector databases, document parsing), `llmops-and-evaluation`, `ai-security`, `fine-tuning` | 8 |
| Hosting and infrastructure | `model-hosting`, `ai-cloud-and-paas`, `ai-gateways` | 3 |
| Run it yourself | `self-hosted`, `local-ai` | 2 |
| **Total** | **34** | **39** |

**Every source call** ([ADR 0006](decisions/ADR-0006-outbound-calls-and-resilience.md)): a token goes only to the configured host (no redirect
to another host while `Authorization` is set); a body is read up to 2 MB (awesome-list READMEs 5 MB), past that the
source is `FAILED` ("response too large"); a malformed item is dropped and counted in
`helper2.source.items.dropped{source,reason}` while the other items are kept (ai-deadlines counts a skipped
conference file there too). A hit the vitality rule refuses ([ADR 0020](decisions/ADR-0020-resource-vitality.md))
is counted there too, with the reason `archived`, `deprecated`, `not-running`, `too-few-likes` or `idle-fork`.

**Web page titles** (`live-web`, `live-web-scout`, `course-scout` and every `discovery-*`, one predicate: `WebSources.isWeb`): the title is the
page's HTML title, cleaned by `WebPageTitle` when it is stored and again when it is read. On read, a source record from
one of these sources, and a resource whose records all come from them, show the clean title; a curated resource keeps its
editorial title. Cleaning a clean title changes nothing, so stored rows are never rewritten for it, and a row stored
with the page's raw title (a stored Live web set) reads back clean too. Whitespace is trimmed and collapsed. A final ` | Site`, ` - Site`, ` · Site`,
` — Site` or ` – Site` goes when Site names the result's own host: the host itself (`scored.tools`), its registrable
domain (`Kaily.ai` on kaily.ai) or its name without spaces and punctuation (`Medium` on medium.com, `Hugging Face` on
huggingface.co). One segment at most, so `Chat with PDF | Ask your PDF | allpdf.com` keeps its first two parts. A leading
name stays (`Ollama - Get up and running`), and so does a segment that is not the site (`LangChain | Docs`). On github.com,
`GitHub - owner/repo: description · GitHub` becomes `owner/repo`; its description is used only when Live web gives no
content. A title is never empty: when nothing is left, the title stays as it was. The relevance gate and the event check
read the title as Live web sent it. Identity is the page URL, so cleaning changes no result id or merge.

**Not connected (and why):** LinkedIn directly (User Agreement forbids scraping; the API has no search — public pages come through Live web, `linkedin-scout`), PyPI directly (no search API: packages come through BigQuery, `pypi`), Product Hunt (API forbids commercial use),
There's An AI For That (terms not obtainable), paid web-search APIs other than the one behind Live web.

**Adding a source:** verify docs/terms/limits/costs → add a row here and its evidence under [Terms evidence](#terms-evidence) →
implement `SearchSource` and/or `Collector` in its own package under `ai.helper2.sources` → boundary tests with recorded
fixtures → a `@Tag("live")` test next to the adapter, which `.github/workflows/live.yml` runs every week
([ADR 0006](decisions/ADR-0006-outbound-calls-and-resilience.md)).

## Terms evidence

Each fact has its source URL and a short quote from the official page, or from the API's own response where noted.
Read on 2026-10-22 unless a section names another day. "UNKNOWN" means not found in the official docs. Read the
page again before relying on a quote.

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

### 6. Product catalogs that are not connected

| Source | Finding | Source / quote |
|---|---|---|
| Product Hunt API v2 | **Commercial use prohibited** without permission | https://api.producthunt.com/v2/docs — "The Product Hunt API must not be used for commercial purposes." |
| Product Hunt — business route | must ask | same — "please contact us at hello@producthunt.com" |
| Product Hunt — auth | token required | same — "only accessible with a provided access_token" |
| Product Hunt — rate limits | UNKNOWN numeric | same — "We reserve the right to rate-limit any application" |
| There's An AI For That | UNKNOWN — not found in official docs (theresanaiforthat.com/api/ and /terms/ returned HTTP 403 / bot challenge; no public API terms could be read) | — |
### 7. arXiv API

| Item | Fact | Source / quote |
|---|---|---|
| Rate limit | 1 request / 3 s, one connection | https://info.arxiv.org/help/api/tou.html — "no more than one request every three seconds" |
| Metadata license | CC0 | same — "Creative Commons Universal (CC0 1.0) Public Domain Declaration" |
| Content | don't host PDFs/source | same — must not "Store and serve arXiv e-prints" without permission |
| Branding | no implied endorsement | same — "Represent your project as endorsed or supported by arXiv.org" (prohibited) |
| Commercial use | UNKNOWN — not stated explicitly | — |
| Dates in response | UNKNOWN — not verified (live `export.arxiv.org` call returned HTTP 429) | — |

### 8. The Live web provider: terms

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

The provider gave its written OK to show its results publicly, to store them, to reuse them across users and to read
product pages with page extract. The written OK for the scheduled discovery, LinkedIn and event searches is still to
confirm (the rows `discovery-<category>`, `linkedin-scout` and `live-web-events-scout` above). Without the key
(`H2_LIVE_WEB_API_KEY` unset) the source is skipped and search keeps working.

### 9. deps.dev (Open Source Insights) API v3

Read 2026-11-09 at https://docs.deps.dev/api/v3/ (no last-updated date on the page). Used by the `github-sdks`
collector (ADR 0018) from the server, with public repo and package names only.

| Item | Fact | Quote |
|---|---|---|
| Terms | Google APIs Terms of Service | "Use of the deps.dev API is subject to the Google API Terms of Service." (https://developers.google.com/terms) |
| Rate limit | none published | — (nothing on the page; no limit hit in about 120 calls on 2026-11-09) |
| Caching | allowed | "Clients are expressly permitted to cache data served by the API." |
| Licence of generated data | CC-BY 4.0 | "This generated data is available under a CC-BY 4.0 license." (resolved dependencies, advisories, associations between entities) |
| Third-party data | each source's own terms | "consult their documentation" |
| Project → packages cap | 1,500 versions, attestation mappings first | "At most 1500 package versions are returned." |
| Auth | none | — |
