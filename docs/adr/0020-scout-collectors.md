# 0020 — Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, awesome lists with classifier, ai-deadlines

**Status.** Accepted (2026-10-27). **Builds on:** [0006](0006-outbound-http.md), [0014](0014-llm-integration.md), [0018](0018-scout-runner.md)

## Context
The Scout runner ([0018](0018-scout-runner.md)) needs collectors that find new artificial intelligence (AI) resources without a user query.
Each source has its own application programming interface (API), terms, rate limits and cost. Some return clean structured data (registries, the Hugging
Face Hub). Others return lists that mix AI resources with unrelated software (GitHub awesome lists). Each collector must
only report sources it actually queried, and it must state where its data comes from.

## Decision
Six collectors implement the `Collector` port in `ai.helper2.sources`. All use `RunInterval`, the shared Hypertext Transfer Protocol (HTTP) clients
([0006](0006-outbound-http.md)) and the item limit of the run (500).
1. **Official Model Context Protocol (MCP) Registry** (`mcp-registry`, every `H2_SCOUT_MCP_INTERVAL`, 1 h, as the registry asks of
   aggregators). `GET /v0.1/servers?limit=100&updated_since=<checkpoint>&cursor=…`, only entries marked latest, up to
   100 pages. Link: repository, else website, else the registry entry. Platform `remote` when the server lists remotes,
   else `local`. Data is CC0 (Creative Commons public domain). Incremental; resumes with the registry's cursor.
2. **Hugging Face trending** (`hf-trending`, 1 day). Top 100 models by `trendingScore` with at least 20 likes. Not
   incremental: each pass reads the current list; unchanged models are a no-op in the catalog. The list API returns
   no description, so these models do not reach enrichment.
3. **Hugging Face provider organizations** (`hf-provider-orgs`, 1 day). The 20 newest models of each official org:
   `meta-llama`, `nvidia`, `mistralai`, `deepseek-ai`, `Qwen`, `google`, `openai`, `xai-org`. The org is added as a tag,
   which the model-family page uses ([0021](0021-model-families.md)).
4. **Tavily discovery** (`tavily-scout`, every `H2_SCOUT_TAVILY_INTERVAL`, 5 days). 7 fixed news queries ("new AI tool
   launched", "new MCP server released"…), `search_depth basic`, up to 20 results each, published since the day of the
   last complete pass (first run: the last week). Each query costs one credit; the config comment estimates about 42
   credits a month. Stored as NEWS, ARTICLE or EVENT with Tavily's snippet as description. Off without `H2_TAVILY_API_KEY`.
5. **GitHub awesome lists** (`github-awesome`, 1 day). Off without `H2_GITHUB_TOKEN` (GraphQL rejects anonymous calls).
   - Topic search with 6 topic pairs; only repositories named "awesome…" with at least 1000 stars; the top 10 lists.
   - Each README is parsed into linked repositories (`AwesomeListParser`): at most 1000 per pass and
     `H2_SCOUT_AWESOME_PER_RUN` (200) per run; an unfinished pass resumes from an entry offset.
   - Facts via GraphQL, 50 repositories per call; archived, forked and under-50-star repositories are dropped.
   - **Classifier.** The fast model (`SpringAiRepoClassifier`, `prompts/sources/classify-repos.txt`, thinking off)
     answers `ai` true/false and one allowed type, in batches of 25. The schema is described in the prompt, not sent as
     `response_format`. Its own daily cap `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300), gauge
     `helper2.scout.classifier.budget.remaining`; calls are timed and their tokens counted with step `classify`. Ids the model did not answer
     (error, spent budget, missing id) and runs without a model use keyword rules (`RepoClassifier.Heuristic`).
   - A failing list search or README is skipped; a GraphQL rate limit stops the pass (run `FAILED`, cursor kept).
6. **ai-deadlines** (`ai-deadlines`, 1 day). One GitHub contents call lists the conference YAML files of
   `huggingface/ai-deadlines` (MIT). Each file is read only from `raw.githubusercontent.com` and without the GitHub
   token. One EVENT per conference: its next edition that has not ended ([0022](0022-next-events.md)). A file that
   cannot be read or parsed is logged and skipped.

## Consequences
- The index grows from registries, the Hub, news and curated lists, with each record's source disclosed.
- Intervals keep cost and load low: Tavily credits, GitHub limits and Hub calls are bounded per pass.
- The classifier keeps non-AI repositories of awesome lists out; the heuristic keeps the collector working without a model.
- Coverage of consumer AI tools is thin: the sources lean toward developer resources.
- Tavily results are news about tools, not the tools themselves.

## Alternatives considered
- Full crawl of GitHub or the Hub — millions of items; bounded windows (trending, newest, curated lists) are enough.
- Topic search alone for awesome lists — returns prompt sites and apps; the "awesome" name filter removes them.
- Heuristic only for awesome lists — keyword rules let generic developer tools through; the model is the first choice.
- Scrape conference websites — ai-deadlines already keeps the dates, under MIT, in one format.

## Code
- `backend/src/main/java/ai/helper2/sources/McpRegistryCollector.java`, `HuggingFaceTrendingCollector.java`,
  `HuggingFaceProviderOrgsCollector.java`, `TavilyCollector.java`, `GitHubAwesomeCollector.java`, `AiDeadlinesCollector.java`.
- `backend/src/main/java/ai/helper2/sources/AwesomeListParser.java`, `RepoClassifier.java`, `SpringAiRepoClassifier.java`.
- `backend/src/main/java/ai/helper2/sources/SourcesConfiguration.java` — beans, intervals, limits.
- `backend/src/main/resources/application.yaml` — `helper2.scout.*`, `helper2.sources.*`.

## Diagrams
[flow](../diagrams/flow-0020-scout-collectors.svg) · [sequence](../diagrams/sequence-0020-scout-collectors.svg)

## Roadmap
- [More registries, if their terms allow it](../roadmap.md#6-search-and-catalog)
- [Count skipped ai-deadlines files in a metric](../roadmap.md#6-search-and-catalog)
- [Written OK from Tavily for public display, or unset the key](../roadmap.md#product-and-legal)
- [Review each source's terms for commercial use](../roadmap.md#product-and-legal)
