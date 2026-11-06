# 0020 — Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, courses, awesome lists with classifier, ai-deadlines

**Status.** Accepted. **Builds on:** [0006](../foundation/0006-outbound-http.md), [0014](../guide/0014-llm-integration.md), [0018](0018-scout-runner.md)

## Context
The Scout runner ([0018](0018-scout-runner.md)) needs collectors that find new artificial intelligence (AI) resources without a user query.
Each source has its own application programming interface (API), terms, rate limits and cost. Some return clean structured data (registries, the Hugging
Face Hub). Others return lists that mix AI resources with unrelated software (GitHub awesome lists). Each collector must
only report sources it actually queried, and it must state where its data comes from.

## Decision
Seven collectors implement the `Collector` port in `ai.helper2.sources`, one package per provider. All use `RunInterval`, the shared
Hypertext Transfer Protocol (HTTP) clients ([0006](../foundation/0006-outbound-http.md)) and the item limit of the run (500). Each collector's
settings bind from `helper2.scout.<collector>` to a validated record (`CollectorProperties`): an invalid value stops startup.
A collector returns hits, which the runner indexes, and candidates, which the runner sends to the review list
([0018](0018-scout-runner.md)); no collector calls another module itself.
1. **Official Model Context Protocol (MCP) Registry** (`mcp-registry`, every `H2_SCOUT_MCP_INTERVAL`, 1 h, as the registry asks of
   aggregators). `GET /v0.1/servers?limit=100&updated_since=<checkpoint>&cursor=…`, only entries marked latest, up to
   100 pages. Link: repository, else website, else the registry entry. Platform `remote` when the server lists remotes,
   else `local`. Data is CC0 (Creative Commons public domain). Incremental; resumes with the registry's cursor.
2. **Hugging Face trending** (`hf-trending`, 1 day). Top 100 models by `trendingScore` with at least 20 likes. Not
   incremental: each pass reads the current list; unchanged models are a no-op in the catalog.
3. **Hugging Face provider organizations** (`hf-provider-orgs`, 1 day). The 20 newest models of each official org:
   `meta-llama`, `nvidia`, `mistralai`, `deepseek-ai`, `Qwen`, `google`, `openai`, `xai-org`. The org is added as a tag.
4. **Tavily news** (`tavily-scout`, every `H2_SCOUT_TAVILY_INTERVAL`, 5 days). 7 fixed news queries ("new AI tool
   launched", "new MCP server released"…), `search_depth basic`, up to 20 results each, published since the day of the
   last complete pass (first run: the last week). Each query costs one credit: 7 queries in 6 passes, about 42
   credits a month. Stored as NEWS, ARTICLE or EVENT with Tavily's snippet as description. Off without `H2_TAVILY_API_KEY`.
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
     index ([0055](../search/0055-search-inside-awesome-lists.md)).
6. **Courses** (`course-scout`, every `H2_SCOUT_COURSES_INTERVAL`, 7 days; [0032](../guide/0032-guide-intents.md) §8). One Tavily
   search per learning platform (`helper2.scout.courses.platforms`, 10 platforms), restricted to its domain with
   `include_domains`, `search_depth basic`, published since the last complete pass (first run: the last month). One
   credit per platform and pass, about 43 credits a month. A result is kept only when it is on the platform's host and
   path, about AI and reads like a course or certification page. Kept pages are the batch's **candidates**, not hits:
   each becomes a `COURSE_CANDIDATE` review item, and nothing reaches the catalog until an admin accepts it. Off
   without `H2_TAVILY_API_KEY`.
7. **ai-deadlines** (`ai-deadlines`, 1 day). One GitHub contents call lists the conference YAML files of
   `huggingface/ai-deadlines` (MIT). Each file is read only from `raw.githubusercontent.com` and without the GitHub
   token. One EVENT per conference: its next edition that has not ended ([0022](0022-next-events.md)). A file that
   cannot be read or parsed, or whose download URL is on another host, is logged, skipped and counted in
   `helper2.source.items.dropped{source=ai-deadlines, reason}` (the exception type, or `ForeignDownloadUrl`). A
   conference with no upcoming edition is not counted. When the circuit breaker refuses a file, the pass ends there:
   the run fails and the next run retries it.

8. **Tavily discovery** (`discovery-<category>`, every `H2_SCOUT_DISCOVERY_INTERVAL`, 7 days, staggered so 4 or 5
   categories search a day; [0051](0051-tavily-discovery.md)). One collector per category of the AI world: 34
   categories in 7 families (prompt libraries, writing, health, legal, coding, model hosting, self-hosted, ...), 39
   searches a week, registered from `helper2.categories` by a `BeanRegistrar`. List pages are indexed as ARTICLE; a
   product's own site or repository becomes a `RESOURCE_CANDIDATE` review item, merged when another category finds
   it, and accepting it indexes it. Each result gets the tag of every category it belongs to. About 335 credits a
   month, capped at 40 a day. Off without `H2_TAVILY_API_KEY`.

9. **LinkedIn via Tavily** (`linkedin-scout`, every `H2_SCOUT_LINKEDIN_INTERVAL`, 7 days;
   [0061](0061-linkedin-via-tavily.md)). 20 Tavily searches restricted to `linkedin.com`; we never call LinkedIn.
   The URL path makes a page an article, a post, a company page or an event; every one becomes a `RESOURCE_CANDIDATE`
   review item, and accepting it indexes it. About 86 credits a month, on the Scout's daily cap of 40 with discovery's. Off without `H2_TAVILY_API_KEY`.
## Consequences
- The index grows from registries, the Hub, news and curated lists, with each record's source disclosed.
- Intervals keep cost and load low: Tavily credits (about 506 a month: news ~42, courses ~43, discovery ~335, LinkedIn ~86), GitHub limits and
  Hub calls are bounded per pass.
- The classifier keeps non-AI repositories of awesome lists out; the heuristic keeps the collector working without a model.
- A course page reaches the catalog only after an admin accepts it.

## Alternatives considered
- Full crawl of GitHub or the Hub — millions of items; bounded windows (trending, newest, curated lists) are enough.
- Topic search alone for awesome lists — returns prompt sites and apps; the "awesome" name filter removes them.
- Heuristic only for awesome lists — keyword rules let generic developer tools through; the model is the first choice.
- Scrape conference websites — ai-deadlines already keeps the dates, under MIT, in one format.

## Code
- `backend/src/main/java/ai/helper2/sources/` — one package per provider: `mcp/McpRegistryCollector.java`,
  `huggingface/HuggingFaceTrendingCollector.java`, `huggingface/HuggingFaceProviderOrgsCollector.java`,
  `tavily/TavilyCollector.java`, `tavily/CourseCollector.java`, `tavily/TavilyDiscoveryCollector.java` (one per category,
  `config/DiscoveryCollectorRegistrar.java`), `github/GitHubAwesomeCollector.java`,
  `github/AwesomeListParser.java`, `aideadlines/AiDeadlinesCollector.java`.
- `classifier/RepoClassifier.java`, `classifier/SpringAiRepoClassifier.java` — the awesome-list classifier.
- `support/RunInterval.java`, `support/HttpClients.java` — shared by every collector.
- `config/SourcesConfiguration.java` — beans; `config/CollectorProperties.java` — each collector's validated settings
  (`helper2.scout.<collector>`): intervals, queries, limits. An invalid value stops startup.
- `backend/src/main/resources/application.yaml` — `helper2.scout.*`, `helper2.sources.*`.

## Diagrams
[flow](../../diagrams/flow-0020-scout-collectors.svg) · [sequence](../../diagrams/sequence-0020-scout-collectors.svg)

## Amended by
- [0043](0043-enrichment-gap-round.md): a run that leaves fields unproven reads the resource's README or model card once.
- [0055](../search/0055-search-inside-awesome-lists.md): each list is a resource, and its entry lines and headings are indexed for search.

## Roadmap
- [More registries, if their terms allow it](../../roadmap.md#5-search-and-catalog)
- [Review each source's terms for commercial use](../../roadmap.md#product-and-legal)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
