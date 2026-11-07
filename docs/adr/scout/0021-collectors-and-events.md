# 0021 — Scout collectors and events, LinkedIn and the live web events scout included

**Status.** Accepted. Merges old 0020, 0022, 0061, 0067 (texts merged in PR 5 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0020"></a>

## Part: was ADR 0020 — Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, courses, awesome lists with classifier, ai-deadlines

**Status.** Accepted. **Builds on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0008](../foundation/0008-llm-integration.md), [0020](0020-scout-runner.md)

### Context
The Scout runner ([0020](0020-scout-runner.md)) needs collectors that find new artificial intelligence (AI) resources without a user query.
Each source has its own application programming interface (API), terms, rate limits and cost. Some return clean structured data (registries, the Hugging
Face Hub). Others return lists that mix AI resources with unrelated software (GitHub awesome lists). Each collector must
only report sources it actually queried, and it must state where its data comes from.

### Decision
Seven collectors implement the `Collector` port in `ai.helper2.sources`, one package per provider. All use `RunInterval`, the shared
Hypertext Transfer Protocol (HTTP) clients ([0006](../foundation/0006-outbound-calls-and-resilience.md)) and the item limit of the run (500). Each collector's
settings bind from `helper2.scout.<collector>` to a validated record (`CollectorProperties`): an invalid value stops startup.
A collector returns hits, which the runner indexes, and candidates, which the runner sends to the review list
([0020](0020-scout-runner.md)); no collector calls another module itself.
1. **Official Model Context Protocol (MCP) Registry** (`mcp-registry`, every `H2_SCOUT_MCP_INTERVAL`, 1 h, as the registry asks of
   aggregators). `GET /v0.1/servers?limit=100&updated_since=<checkpoint>&cursor=…`, only entries marked latest, up to
   100 pages. Link: repository, else website, else the registry entry. Platform `remote` when the server lists remotes,
   else `local`. Data is CC0 (Creative Commons public domain). Incremental; resumes with the registry's cursor.
2. **Hugging Face trending** (`hf-trending`, 1 day). Top 100 models by `trendingScore` with at least 20 likes. Not
   incremental: each pass reads the current list; unchanged models are a no-op in the catalog.
3. **Hugging Face provider organizations** (`hf-provider-orgs`, 1 day). The 20 newest models of each official org:
   `meta-llama`, `nvidia`, `mistralai`, `deepseek-ai`, `Qwen`, `google`, `openai`, `xai-org`. The org is added as a tag.
4. **Tavily news** (`live-web-scout`, every `H2_SCOUT_LIVE_WEB_INTERVAL`, 5 days). 7 fixed news queries ("new AI tool
   launched", "new MCP server released"…), `search_depth basic`, up to 20 results each, published since the day of the
   last complete pass (first run: the last week). Each query costs one credit: 7 queries in 6 passes, about 42
   credits a month. Stored as NEWS, ARTICLE or EVENT with Tavily's snippet as description. Off without `H2_LIVE_WEB_API_KEY`.
5. **GitHub awesome lists** (`github-awesome`, 1 day). Off without `H2_GITHUB_TOKEN` (GraphQL rejects anonymous calls).
   - Topic search with 6 topic pairs; only repositories named "awesome…" with at least 1000 stars; the top 10 lists.
   - Each README (read up to 5 MB) is parsed into linked repositories (`AwesomeListParser`): at most 1000 per pass and
     `H2_SCOUT_AWESOME_PER_RUN` (1000) per run, so a pass ends in one daily run; a pass cut short by
     `max-items-per-run` resumes from an entry offset.
   - Facts via GraphQL, 50 repositories per call; archived, forked and under-50-star repositories are dropped.
   - **Classifier.** The fast model (`SpringAiRepoClassifier`, `prompts/sources/classify-repos.txt`, thinking off)
     answers `ai` true/false and one allowed type, in batches of 25. The schema is described in the prompt, not sent as
     `response_format`. Its own daily cap `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300), gauge
     `helper2.scout.classifier.budget.remaining`; calls are timed and their tokens counted with step `classify`. Ids the model did not answer
     (error, spent budget, missing id) and runs without a model use keyword rules (`RepoClassifier.Heuristic`).
   - A failing list search or README is skipped; a GraphQL rate limit stops the pass (run `FAILED`, cursor kept).
   - Each list read is also a resource (ARTICLE, tag `awesome-list`), and its entry lines and headings become a search
     index ([0010](../search/0010-catalog-full-text-index.md)).
6. **Courses** (`course-scout`, every `H2_SCOUT_COURSES_INTERVAL`, 7 days; [0016 (part was 0032)](../guide/0016-guide-pipeline.md) §8). One Tavily
   search per learning platform (`helper2.scout.courses.platforms`, 10 platforms), restricted to its domain with
   `include_domains`, `search_depth basic`, published since the last complete pass (first run: the last month). One
   credit per platform and pass, about 43 credits a month. A result is kept only when it is on the platform's host and
   path, about AI and reads like a course or certification page. Kept pages are the batch's **candidates**, not hits:
   each becomes a `COURSE_CANDIDATE` review item, and nothing reaches the catalog until an admin accepts it. Off
   without `H2_LIVE_WEB_API_KEY`.
7. **ai-deadlines** (`ai-deadlines`, 1 day). One GitHub contents call lists the conference YAML files of
   `huggingface/ai-deadlines` (MIT). Each file is read only from `raw.githubusercontent.com` and without the GitHub
   token. One EVENT per conference: its next edition that has not ended ([the part that was ADR 0022](#part-0022)). A file that
   cannot be read or parsed, or whose download URL is on another host, is logged, skipped and counted in
   `helper2.source.items.dropped{source=ai-deadlines, reason}` (the exception type, or `ForeignDownloadUrl`). A
   conference with no upcoming edition is not counted. When the circuit breaker refuses a file, the pass ends there:
   the run fails and the next run retries it.

8. **Tavily discovery** (`discovery-<category>`, every `H2_SCOUT_DISCOVERY_INTERVAL`, 7 days, staggered so 4 or 5
   categories search a day; [0022](0022-discovery.md)). One collector per category of the AI world: 34
   categories in 7 families (prompt libraries, writing, health, legal, coding, model hosting, self-hosted, ...), 39
   searches a week, registered from `helper2.categories` by a `BeanRegistrar`. List pages are indexed as ARTICLE; a
   product's own site or repository becomes a `RESOURCE_CANDIDATE` review item, merged when another category finds
   it, and accepting it indexes it. Each result gets the tag of every category it belongs to. About 335 credits a
   month, capped at 40 a day. Off without `H2_LIVE_WEB_API_KEY`.

9. **LinkedIn via Tavily** (`linkedin-scout`, every `H2_SCOUT_LINKEDIN_INTERVAL`, 7 days;
   [the part that was ADR 0061](#part-0061)). 20 Tavily searches restricted to `linkedin.com`; we never call LinkedIn.
   The URL path makes a page an article, a post, a company page or an event; every one becomes a `RESOURCE_CANDIDATE`
   review item, and accepting it indexes it. About 86 credits a month, on the Scout's daily cap of 40 with discovery's. Off without `H2_LIVE_WEB_API_KEY`.
### Consequences
- The index grows from registries, the Hub, news and curated lists, with each record's source disclosed.
- Intervals keep cost and load low: Tavily credits (about 506 a month: news ~42, courses ~43, discovery ~335, LinkedIn ~86), GitHub limits and
  Hub calls are bounded per pass.
- The classifier keeps non-AI repositories of awesome lists out; the heuristic keeps the collector working without a model.
- A course page reaches the catalog only after an admin accepts it.

### Alternatives considered
- Full crawl of GitHub or the Hub — millions of items; bounded windows (trending, newest, curated lists) are enough.
- Topic search alone for awesome lists — returns prompt sites and apps; the "awesome" name filter removes them.
- Heuristic only for awesome lists — keyword rules let generic developer tools through; the model is the first choice.
- Scrape conference websites — ai-deadlines already keeps the dates, under MIT, in one format.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/` — one package per provider: `mcp/McpRegistryCollector.java`,
  `huggingface/HuggingFaceTrendingCollector.java`, `huggingface/HuggingFaceProviderOrgsCollector.java`,
  `tavily/TavilyCollector.java`, `tavily/CourseCollector.java`, `tavily/TavilyDiscoveryCollector.java` (one per category,
  `config/DiscoveryCollectorRegistrar.java`), `github/GitHubAwesomeCollector.java`,
  `github/AwesomeListParser.java`, `aideadlines/AiDeadlinesCollector.java`.
- `classifier/RepoClassifier.java`, `classifier/SpringAiRepoClassifier.java` — the awesome-list classifier.
- `support/RunInterval.java`, `support/HttpClients.java` — shared by every collector.
- `config/SourcesConfiguration.java` — beans; `config/CollectorProperties.java` — each collector's validated settings
  (`helper2.scout.<collector>`): intervals, queries, limits. An invalid value stops startup.
- `backend/apps/backoffice/src/main/resources/application.yaml` — `helper2.scout.*`, `helper2.sources.*`.

### Diagrams
[flow](../../diagrams/flow-0021-scout-collectors.svg) · [sequence](../../diagrams/sequence-0021-scout-collectors.svg)

### Amended by
- [0023](0023-evidence-gated-enrichment.md): a run that leaves fields unproven reads the resource's README or model card once.
- [0010](../search/0010-catalog-full-text-index.md): each list is a resource, and its entry lines and headings are indexed for search.

### Roadmap
- [More registries, if their terms allow it](../../roadmap.md#5-search-and-catalog)
- [Review each source's terms for commercial use](../../roadmap.md#product-and-legal)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0022"></a>

## Part: was ADR 0022 — Next events: stated event days, ai-deadlines and curated industry and community events

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0013](../search/0013-filters-facets-and-categories.md), [0020](0020-scout-runner.md), [the part that was ADR 0020](#part-0020) **Amended by:** [the part that was ADR 0067](#part-0067) (§6: events whose days a model read on their page and code checked), [0026](../edge/0026-back-office-and-review.md) (the event seeds replace only the fields they assert).

### Context
The home page lists the next artificial intelligence (AI) events: research conferences, industry events and community
events (hackathons, meetups, talks and workshops, online or in person). A wrong date is worse than no
date: a visitor may plan travel around it. News results often mention events but rarely state their days in a form
code can read. Event dates also differ from other dates in the catalog: they lie in the future, and they are days,
not instants. Helper2 never invents a date ([0004](../foundation/0004-persistence-schema-and-app-roles.md)).

### Decision
1. **Stated days only.** An event has a start (and maybe an end) only when a source states it. `DateFact.scheduledDay`
   stores the day at 00:00 Coordinated Universal Time (UTC) with DAY precision and the source id. Unlike publication dates it may be in the future;
   a day before 1995 is treated as unknown. Source records keep `source_event_starts_at` / `source_event_ends_at`;
   `ResourceStore.refreshEventDates` sets the resource's `event_starts_at` to the earliest stated start and
   `event_ends_at` to the latest stated end, on every upsert.
2. **Conferences from ai-deadlines** ([the part that was ADR 0020](#part-0020)). For each conference file the collector keeps
   the edition with the soonest start that has not ended (end, or start when there is no end, on or after today, UTC).
   An edition without a parseable start ("TBA") is skipped, and so is one without an official link or a title. City and
   country come from the file.
3. **Curated industry events.** `docs/research/industry-events-2026-10-27.json` holds each event's official site, the
   page that states the dates and the exact excerpt, checked against the live page.
   `scripts/industry-events-seed.mjs` generates the repeatable migration `R__industry_events.sql` (21 events,
   from 2026-11-09 to 2027-09-20) and fails when a start date, web address (URL) or excerpt is missing. Each event gets an `eventDates`
   assertion (`CURATED`, `VERIFIED`) with the excerpt as evidence. The place is only what the page states; a country is
   kept only when the excerpt names it. Editions that share a site get distinct ids (`?event=<edition slug>`).
4. **Curated community events.** `docs/research/community-events-2026-10-27.json` lists hackathons, meetups, talks and
   workshops in the same shape, plus `kind` and, when the page states it, `format` with the excerpt that says so
   (`formatExcerpt`). The same script, run with the set `community`, generates `R__community_events.sql`
   (fingerprint `community-events-v1`, tag `community-event`), so each seed removes only its own dropped events.
   A stated format also gets an `eventFormat` assertion (`CURATED`, `VERIFIED`) with that excerpt as evidence.
5. **Kind and format.** `ResultCard.eventKind` (CONFERENCE, HACKATHON, MEETUP, TALK, WORKSHOP) and
   `ResultCard.eventFormat` (IN_PERSON, ONLINE, HYBRID) are set only when a source states them, never inferred; they
   travel in the source record's `extra`, like `location`. ai-deadlines is a list of conferences, so its events are
   `CONFERENCE`, with no format. Industry events are `CONFERENCE`, hybrid where the page says so. The home page shows
   them as one tag, for example "Hackathon · Online".
6. **Other events have no dates.** Tavily stores some news items as EVENT, but never with stated days, so they are never listed. Amended by [the part that was ADR 0067](#part-0067): the live web events scout lists an event when a model reads its days on its page and code finds them in a quote of that page; they are labelled as AI and never win over days a source states.
7. **Application programming interface (API).** `GET /api/v1/events/upcoming?days=&size=` (`size` 1–60, default 6): resources of type EVENT with a stated start whose
   end (or start) is on or after today 00:00 UTC, soonest first, then by title. With `days` (1–366), only events that
   start before today 00:00 UTC plus `days`; the response says that end in `until` (null without `days`). An event
   already running stays listed until it ends. `total` counts every event in the window, so "Show all N" is a real
   number ([0028](../frontend/0028-design-system.md)). `Cache-Control: public, max-age=300`.
   The home page component `EventsBoard` asks for 60 (about 45 curated events, plus conferences) and lists those that start within 90 days of the response's
   `generatedAt`.

### Consequences
- Every listed date can be traced to a source: the ai-deadlines file or an excerpt of the event's own page.
- An event stays listed until its last day is over (UTC).
- The events seed removes curated events that are not in the research file: their curated assertions, evidence and
  source record, and the resource itself when no other source record points to it.

### Alternatives considered
- Extract dates from news text with the model — a model can misread or invent a day; nothing to verify it against.
- Show events without dates as "soon" — invents a timing claim.
- Store event days as instants with the event's time zone — the sources state days, not times; DAY precision says so.
- Call ai-deadlines live per page view — slower and dependent on GitHub; the Scout reads it once a day.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/aideadlines/AiDeadlinesCollector.java` — `nextEdition`, `toHit`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/DateFact.kt` — `scheduledDay`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `refreshEventDates`, `upcomingEvents`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/ListUpcomingEvents.java`, `catalog/web/ResourcesController.java` — `listUpcomingEvents`.
- `scripts/industry-events-seed.mjs`, `docs/research/industry-events-2026-10-27.json`,
  `docs/research/community-events-2026-10-27.json`, `backend/libs/schema/src/main/resources/db/migration/R__industry_events.sql`,
  `R__community_events.sql`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `EventKind`, `EventFormat`.
- `frontend/src/components/home/EventsBoard.tsx`.

### Diagrams
[flow](../../diagrams/flow-0021-next-events.svg) · [sequence](../../diagrams/sequence-0021-next-events.svg)

### Roadmap
- [Live collectors for hackathons, meetups, talks and workshops](../../roadmap.md#5-search-and-catalog)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0061"></a>

## Part: was ADR 0061 — LinkedIn via Tavily: public articles, posts, company pages and events, all reviewed

**Status.** Accepted. **Builds on:** [0020](0020-scout-runner.md), [the part that was ADR 0020](#part-0020), [the part that was ADR 0022](#part-0022), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0035](../operations/0035-data-retention.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0022](0022-discovery.md) **Amended by:** [0026](../edge/0026-back-office-and-review.md) (a type an admin sets need not be declared by a finder).

### Context
Much of the AI world is announced on LinkedIn first: a company launches a product, a lab posts a model, a meetup
opens its sign-up. The Scout does not see any of it.

LinkedIn gives us no allowed way in. Its User Agreement forbids scraping, crawlers and bots. Its API has no search:
it manages your own profile and company page, and most of it needs partner approval. So we never call LinkedIn.

Tavily already indexes public LinkedIn pages. A Tavily search restricted to `linkedin.com` finds them, under the same
terms as our other Tavily searches.

LinkedIn pages are about people more than any other source: a post has an author, and the author's name is in the
page title and in the URL.

### Decision
**Names (6 Nov 2026).** The collector is "LinkedIn" in the product; the live web search behind it is the adapter of [0014](../search/0014-tavily-store-and-daily-quota.md).

1. **Collector `linkedin-scout`** (`LinkedInCollector`). One Tavily search per fixed query, every 7 days
   (`H2_SCOUT_LINKEDIN_INTERVAL`), with `include_domains: ["linkedin.com"]`, `topic: general`,
   `search_depth: basic` (1 credit), `max_results: 10`, `include_usage`, no raw content, no images, no answer, and
   only new pages: `start_date` = the previous complete pass, or `time_range: month` on the first. It is built as the
   discovery collectors are ([0022](0022-discovery.md)): a skipped pass says why ("Not due yet: next pass on
   …", or the credit cap). The queries are config (`helper2.scout.linkedin.queries`): 20 of them, for
   launches of models, tools and MCP servers, company announcements, and events.
2. **The URL path decides what a page is** (code, no model). The query string is tracking and is dropped.

   | Path | Becomes | Review label |
   |---|---|---|
   | `/pulse/{slug}` | ARTICLE | LinkedIn article |
   | `/posts/{slug}`, `/feed/update/{id}`, `/company/{name}/posts/{slug}` | NEWS | LinkedIn post |
   | `/company/{name}`, `/company/{name}/about`, `/showcase/{name}` | PRODUCT | LinkedIn company page |
   | `/events/{slug}` | EVENT | LinkedIn event |

   Anything else is dropped: personal profiles (`/in/`), jobs, learning, schools, topic pages, other hosts. There is no
   score or AI check: the queries are about AI, and a doubtful page is for the admin to decide.
3. **Every kept page waits for a person.** It is a `RESOURCE_CANDIDATE` review item, as in
   [0022 (part was 0051)](0022-discovery.md) §8, with the tag `linkedin`. The batch has no hits: nothing from LinkedIn reaches
   the catalog without an admin accepting it.
4. **No author in the title.**
   - "Jane Doe on LinkedIn: text" keeps the text;
   - the site and what follows it go (" | LinkedIn", " - LinkedIn"), and so do the segments after the first " | "
     (an article's author, its comment count);
   - a post whose title names only its author ("Jane Doe's Post") takes the start of its text, cut at a word at 120
     characters;
   - an article whose title names only its author is dropped.

   The excerpt is cut at a word at 300 characters, shorter than other web excerpts (400). There is no author field.
   A post's URL and text can still name people: that is the page.
5. **Each collector proposes only its own types.** Accepting a candidate checks that its type is one a collector that
   found it declares (`SourceDescriptor.resourceTypes`, the first finder or any in `foundBy`), inside the candidate
   types (products, plus ARTICLE, NEWS and EVENT). `linkedin-scout` declares ARTICLE, NEWS, PRODUCT and EVENT; a
   discovery category its product type and ARTICLE. A candidate two categories merged keeps the most specific type
   ([0022 (part was 0051)](0022-discovery.md) §8), which only the second may declare: the check takes every finder.
6. **Event days.** A LinkedIn event page is not the event's own site, so its days are not stated days
   ([the part that was ADR 0022](#part-0022) §1). An accepted LinkedIn event has no days: it is found by search and never listed
   in Next events. To list it, an admin adds the event to the curated list with the days from its own site.
7. **`linkedin.com` stays excluded from Tavily discovery** ([0022 (part was 0051)](0022-discovery.md) §4), so this collector
   is the only way LinkedIn pages come in, and no LinkedIn candidate is merged with a discovery one.
8. **Failed searches.** As in discovery: a search that fails alone is logged and skipped, the others, already paid
   for, are kept. When half or more fail, the pass fails and its checkpoint stays, so the next daily run repeats it.
9. **Credits are counted and capped with discovery's.** Every search adds its credits (Tavily's `usage.credits`, or 1)
   to the day's global credit counter and to the Scout's own counter (`ScoutTavilyCredits`,
   [0022 (part was 0051)](0022-discovery.md) §10), with the metric `helper2.live-web.credits{feature="scout-linkedin"}`. Past the
   Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, 40, shared with the discovery categories) the pass is
   SKIPPED ("Daily Tavily credit cap of the Scout reached") and tried again the next day.
10. **Retention and titles.** `linkedin-scout` is a web source (`WebSources`, [0022 (part was 0051)](0022-discovery.md)
    §12): its titles are cleaned as page titles, and its NEWS and ARTICLE records go with the web retention.
11. **Alert.** `Helper2ScoutCollectorStale` fires when `linkedin-scout` has had no success for more than 8 days (its
    7-day interval plus a day), as for `course-scout`.

### Consequences
- The review list gets LinkedIn launches and events every week. No LinkedIn page enters the catalog without a person.
- Credits: 20 searches × 1 credit = 20 a week, ≈ 86 a month. With the other Scout collectors, about 506 of the plan's
  14,000 credits a month. All 20 fall on one day: with that day's discovery searches (about 12 credits) it stays
  under the cap of 40. If discovery grows, LinkedIn's day is the first to be skipped for the cap.
- Retention ([0035](../operations/0035-data-retention.md)): accepted articles and posts are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`), like other web news; an accepted company page or event stays as long as
  it is listed; a review item stays until it is decided, then 180 days.
- The Privacy page names LinkedIn pages as a source of public data, says that titles and excerpts can name people,
  and how to object.
- Search can filter LinkedIn pages by the `linkedin` tag.

### Security and privacy (STRIDE)
- **Spoofing / tampering**: anyone can post on LinkedIn. Mitigation: every page waits for a person; on accept the URL
  is checked again, the type must be one the collector declares, the tag must be tag-shaped.
- **Information disclosure**: the queries are fixed config, with no user data. The pages are public, but they name
  people: no author field, names removed from titles, short excerpts, 180-day retention for posts and articles,
  removal on request.
- **Denial of service / cost**: 20 calls a week, a circuit breaker and retry (`collector-linkedin-scout`),
  `max_results` 10, the Scout's daily credit cap.
- **Elevation**: only an admin can accept (back office, VPN, [0024](../edge/0024-identity-and-admin-host.md)); the admin page
  renders the candidate as text and links only `https://` URLs.
- **Prompt injection**: an accepted post's text reaches a model in enrichment and in the guide, as untrusted text, as
  in [0022](0022-discovery.md).
- **To confirm with a lawyer before release**: the legal basis for post authors' names (legitimate interest, GDPR
  Art. 6(1)(f) and Swiss nFADP Art. 31), the Art. 14 notice through the Privacy page, and that Tavily's written OK
  covers pages Tavily indexed from LinkedIn.

### Alternatives considered
- **LinkedIn API** — no search; the APIs manage your own pages and need partner approval.
- **Scrape LinkedIn** — forbidden by the User Agreement; LinkedIn enforces it in court.
- **A live search source** — a credit on every search that misses the stored set, and posts shown to users before
  anyone checked them.
- **Add LinkedIn to Tavily discovery** — discovery sorts by product-site rules (home page, one level below it);
  LinkedIn pages never match them, and discovery indexes list pages without review.
- **A model sorts the pages** — the path already says what a page is; a model call per result adds cost and reads
  untrusted text before anything is checked.
- **Drop events and company pages** — simpler, but events are a main reason people use LinkedIn for AI, and a
  company page is often the first trace of a new product.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/LinkedInCollector.java` — the searches, the path rules, titles and
  excerpts.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`LinkedIn`), `SourcesConfiguration.java`;
  the queries in `application.yaml` (`helper2.scout.linkedin`).
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/ScoutTavilyCredits.java` — the credits, with the feature
  `scout-linkedin`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — the type check per finder;
  `ResourceCandidateJson` reads the finders.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt` — `linkedin-scout` in `FIXED` (titles, retention).
- `deploy/monitoring/helper2-rules.yaml` — `Helper2ScoutCollectorStale`, `Helper2LiveWebCreditsHigh`.
- Tests: `LinkedInCollectorTest`, `IndexAcceptedCandidateTest`, `ResourceCandidateJsonTest`,
  `SourcesPropertiesBindingTest`, `CatalogPurgeExpiredDataIT`, the rule tests.
- Diagrams: [flow](../../diagrams/flow-0021-linkedin-via-tavily.svg) · [sequence](../../diagrams/sequence-0021-linkedin-via-tavily.svg).

<a id="part-0067"></a>

## Part: was ADR 0067 — Live web events scout: a model reads an event page's days, code checks them against the page

**Status.** Accepted. **Builds on:** [0008](../foundation/0008-llm-integration.md), [0020](0020-scout-runner.md), [the part that was ADR 0020](#part-0020), [the part that was ADR 0022](#part-0022), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [0022](0022-discovery.md), [0023](0023-evidence-gated-enrichment.md). **Amends:** [the part that was ADR 0022](#part-0022) (§6 and its first rejected alternative).

### Context
Coming up lists the next AI events. Its events come from ai-deadlines (research conferences) and from our curated
lists, which need a research pass by hand to stay current. Hackathons, meetups, talks and workshops are announced
every week, and the list misses most of them.

Tavily finds their pages. It does not give their days: a search result has a title, a link and an excerpt. the part that was ADR 0022
lists an event only when a source states its days, because a wrong date can make a visitor plan a trip for nothing.
It rejected reading dates from text with a model: a model can misread or invent a day, and there was nothing to check
it against.

### Decision
1. **A new Scout source, `live-web-events-scout` ("Upcoming AI events").** Weekly (`H2_SCOUT_LIVE_WEB_EVENTS_INTERVAL`,
   P7D). One Tavily search per query (`general`, `basic`, 10 results): upcoming AI hackathons, meetups and talks,
   conferences and summits, workshops. A result is kept when it is about AI and its link is plain https
   (`PageScope.plainUrlProblem`). At most 20 pages a pass (`H2_SCOUT_LIVE_WEB_EVENTS_MAX_PAGES`).
2. **Off by default.** `H2_SOURCE_LIVE_WEB_EVENTS_SCOUT_ENABLED` is false: Tavily's written OK for scheduled searches
   whose pages we list is still to confirm (as for discovery and LinkedIn). It also needs `H2_LIVE_WEB_API_KEY` and
   an AI model; without a model it is off.
3. **The page is read with Tavily Extract** (basic, markdown, 4 pages a call). Search and Extract credits count on the
   Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, ADR 0022). The cap is checked before the pass and before each Extract
   call; past it the pass keeps what it read.
4. **A model reads the page.** The reasoning model, thinking off (ADR 0008), prompt `prompts/sources/read-event-v1.txt`:
   how many events the page is about, the first and last day, and the words of the page that state them; kind,
   format and place, each with its own words. The page is untrusted data: it is cut to 12,000 characters, it cannot
   close its block, and the prompt says to ignore instructions in it. One call per page, at most
   `H2_SCOUT_EVENT_READER_DAILY_CALLS` (40) a UTC day, inside the `llm-chat` circuit. When no model can be called
   (cap, circuit), the pass keeps what it read and stays open, so the next Scout run tries again.
5. **Code decides, not the model** (`EventQuoteGate`). An event is kept only when:
   - the page is about one event;
   - the date quote is 20 to 300 characters and is on the page (case, spaces, dashes and quotation marks folded);
   - code reads one range of days from the quote, with its year (`QuotedDays`: "12–14 March 2027", "March 12–14,
     2027", "30 March to 2 April 2027", ISO dates; not 12/03/2027, whose order is unclear);
   - those days are the model's days;
   - it starts between today and six months from today, and lasts at most 31 days.
   Kind, format and place are kept only when their own quote is on the page and names them (kind and format by
   their words, place as written). Otherwise they stay null, as in the part that was ADR 0022 §5.
6. **The evidence is kept with the record.** `source_record.extra.eventEvidence`: the date quote, the model id and the
   prompt version; `extra.query`: the search that found the page.
7. **AI-read days never win.** When another source states an event's days (ai-deadlines, the curated lists), the
   model's are ignored: in the search merge (`ResultMerger`) and in the stored days (`ResourceStore.refreshEventDates`).
   The same for kind, format and place.
8. **They are labelled as AI.** EU AI Act Art. 50 and ADR 0018: a checked quote does not make the days the source's
   own words; they are the model's reading. A result card says `eventOrigin: AI_EXTRACTED` when its start comes from
   this source, and the UI (Coming up, the Events page, the resource page) shows the days, kind and place with the AI label and `data-ai-generated`. Server pages leave AI-read days and place out: their facts and schema.org data cannot carry the label.

### Consequences
- Coming up can list hackathons, meetups, talks and workshops without a research pass, each with a quote of its own
  page that states its days.
- A page that states a wrong date passes the gate: the gate proves the days are written on the page, not that the
  page is right. The same holds for every source.
- Credits: about 4 search and 4 Extract credits a week, ~35 a month, inside the Scout's daily cap.
- Retention: events are deleted 365 days after they end (ADR 0035, `past-events`), with their quote.

### Alternatives considered
- **Admin confirms the days** (review queue, as LinkedIn): no AI dates, but an admin has to read every page each week.
- **Suggested days, admin confirms**: less typing, the same admin time.
- **Dates from structured data (schema.org Event)**: exact when present, but Extract returns markdown, without it,
  and many event pages have none. Kept as a later step before the model.
- **The model's days without a quote check**: the part that was ADR 0022's rejected alternative; a model can invent a day.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/LiveWebEventsCollector.java` — `collect`, `pagesToRead`, `readAll`, `readPage`, `eventHit`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/events/EventQuoteGate.java`, `QuotedDays.java`, `PageText.java`, `EventReading.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/events/EventReader.java`, `SpringAiEventReader.java`, `backend/apps/backoffice/src/main/resources/prompts/sources/read-event-v1.txt`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt` — `EVENTS_SCOUT`, `eventDaysReadByAi`.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — `eventSources`, `statedFirst`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `refreshEventDates`.
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/CardMapper.java` — `eventOrigin`.
- `backend/apps/public/src/main/java/ai/helper2/pages/usecase/RenderPage.java` — `view`: AI-read days off server pages.
- `frontend/src/components/home/TermList.tsx`, `listParts.tsx`, `frontend/src/pages/EventsPage.tsx`,
  `frontend/src/components/Timeline.tsx`, `DateFactView.tsx` — the AI label.

### Diagrams
[flow](../../diagrams/flow-0021-live-web-events-scout.svg) · [sequence](../../diagrams/sequence-0021-live-web-events-scout.svg)

### Roadmap
- Read schema.org Event data before calling the model.
- Measure in the first weeks how many pages a pass reads and how many events the gate keeps; drop a query that
  brings only noise.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
