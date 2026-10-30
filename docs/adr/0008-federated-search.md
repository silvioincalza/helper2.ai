# 0008 — Federated search over disclosed sources: modes, parallel fan-out, deadlines, cooldown, circuit breakers, partial results, source status, merge and conflicts

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md), [0005](0005-redis-disposable-cache.md), [0006](0006-outbound-http.md), [0033](0033-resilience-policies.md)

## Context
Helper2.ai answers a search from several sources: public application programming interfaces (APIs) (GitHub, Hugging Face, npm, arXiv, Tavily) and its own catalog.
Each API has its own speed, rate limit and failure modes. One slow or failing source must not block the others, and
a source that is down must not cost every search its full deadline.
Users must see which sources answered, which did not, and why. Helper2.ai does not search the whole web,
so the list of sources must be disclosed. Two sources can describe the same resource and can disagree on a fact.

## Decision
1. **One port.** Every source implements `SearchSource` with a `SourceDescriptor`: id, name, mode
   (`LIVE`, `INDEXED`, `CURATED`, `DEMO`), timeout, cache time to live (TTL), `enabled`, `priority`, `usesWebQuery`,
   `usesTranslation`. Search sources and their priority (lower wins on display text): `curated` 10, `github` 20,
   `hf-models` 30, `hf-spaces` 35, `npm` 40, `arxiv` 45, `tavily` 48, `helper2-index` 50, `helper2-semantic` 55
   (only with an embedding key, [0012](0012-vector-search-pgvector.md)). `GET /api/v1/sources` lists them.
2. **Parallel fan-out.** `SearchResources.search` starts each source on the `searchExecutor`
   (one virtual thread per task). A disabled source is reported as `SKIPPED` with `SkipReason.DISABLED`
   ("Disabled in configuration"); it leaves no outcome on the Sources page.
3. **Own deadline per source.** The deadline and the reported latency count from the source's own start.
   Timeouts: `github`, `hf-models`, `hf-spaces`, `npm` 2500 ms; `arxiv` 3 s; `tavily` `H2_SOURCE_TAVILY_TIMEOUT` (6 s);
   `helper2-index` `H2_SOURCE_INDEX_TIMEOUT` (3 s); `helper2-semantic` `H2_SOURCE_SEMANTIC_TIMEOUT` (3 s); `curated` 1000 ms.
   On timeout the task is canceled and the outcome is `TIMED_OUT`.
4. **Cache for LIVE sources.** The fresh and stale windows of [0005](0005-redis-disposable-cache.md) apply: a fresh entry
   is served as `CACHE` with no live call, and after a failure an entry inside the stale window is served as `STALE_CACHE`.
5. **Cooldown.** `SourceFailure.RateLimited` pauses the source until `Retry-After` (or 1 min). During the pause the
   source is `SKIPPED` with `SkipReason.RATE_LIMIT_PAUSE` ("Paused after a rate limit"), with a stale entry if one exists.
   A source that cannot browse is `SKIPPED` with `SkipReason.NOT_BROWSABLE` on a browse request.
6. **Circuit breaker and bulkhead per source.** Each call runs under the source's policy `source-<id>`
   ([0033](0033-resilience-policies.md)), never with a retry: the deadline is the budget. The circuit opens when half of
   the source's last 20 calls (at least 10) failed or took over 2 s (Tavily 5 s); a rate limit
   does not count. While it is open, for 30 s, the source is `SKIPPED` with `SkipReason.CIRCUIT_OPEN` ("Paused after
   repeated failures") without a call, with a stale entry if one exists; then 2 trial calls decide. The bulkhead lets
   20 calls of one source run at once in a replica (6 for the catalog sources, below the database pool); one more is
   `SKIPPED` with `SkipReason.BUSY` ("Too many searches at once") at once. An enabled source without its policy stops
   startup.
7. **Status and coverage.** Each source gets a `SourceStatus`: outcome (`SUCCEEDED`, `FAILED`, `TIMED_OUT`,
   `RATE_LIMITED`, `SKIPPED`), skip reason, served from (`LIVE`, `CACHE`, `STALE_CACHE`, `NONE`), count, latency, message.
   `coverage.partial` is true when any enabled source did not succeed. Notices: `CACHE_UNAVAILABLE`,
   `INDEX_UNAVAILABLE`, `QUERY_EXPANDED`, `ALL_SOURCES_FAILED`. The search never throws for a source, cache or index failure.
8. **Merge without a model.** `ResultMerger` groups hits that share a canonical web address (URL) (union-find). A hit's
   repository link (an npm package's `links.repository`) joins it to the repository's own hit only when no other
   resource of the same source in this result set links to that repository. Packages of one monorepo all link to
   it, so each stays its own result and keeps its repository link. A link to a sub-directory (`/tree/{ref}/{path}`) is
   a different URL from the repository root and never joins it. The npm search API returns the repository URL only,
   not `repository.directory`; the sub-directory is known when the package states it in the URL.
   The result's id comes from its hits' own URLs, a GitHub URL first ([0004](0004-persistence-and-provenance.md)).
   Display text comes from the preferred source. License, pricing and open source: if sources disagree, the value is
   null and every claim is kept in `conflicts`. Dates: published and event start = earliest, event end = latest,
   last update = most recent; the others stay as alternatives. Published and event dates are `CONFLICTING` when two
   sources state different days ([0010](0010-filters-facets-dates-timeline.md)).
9. **Catalog link** ([0004](0004-persistence-and-provenance.md)). One read for first-discovered dates; only hits fetched
   live now are handed to `ResultObserver.record`, which queues the write (`helper2.catalog.observation-queue`, 256).
10. Each source returns at most `H2_SEARCH_PER_SOURCE_LIMIT` (30) hits. The response has `Cache-Control: no-store`.

## Consequences
- Good: a slow or failed source costs only its own results. The user sees the outcome of every source.
- Good: a source that is down costs at most 10 searches its deadline per replica, then none until it recovers.
- Good: the merge is deterministic. Conflicts are shown, not resolved.
- Good: each package of a monorepo is its own result and catalog resource; a package joins its repository only when the link is unambiguous.
- Trade-off: a package found without its repository is a result of its own. When a later search finds both, its catalog
  record moves to the repository's resource and links to the package's id still resolve ([0004](0004-persistence-and-provenance.md)).
- Bad: the response waits for the slowest answering source up to its deadline. Tavily (6 s) sets the worst case;
  a non-English query can add the translation wait before most sources start ([0011](0011-language-aware-search.md)).
- Bad: cooldowns, circuits and the per-source health on the Sources page live in each replica's memory. Each replica
  finds a source's rate limit or outage on its own, and the Sources page shows what the replica that answered has seen.
- Trade-off: facets and filters only see the hits fetched for this query (at most 30 per source), not the whole catalog.

## Alternatives considered
- One global deadline for the whole search — a slow step (translation, a slow source) would eat the budget of the others.
- A pause after N consecutive failures — one success in between resets it, and it has no trial call or slow-call rule.
- Retrying a failed source within the search — each attempt spends the deadline the user is waiting on.
- Calling sources one after the other — latency becomes the sum of all sources.
- Scraping or crawling sites — not allowed by their terms; sources would not be disclosed.
- Merging with a model — not repeatable and hard to explain; conflicts could be hidden.
- Joining every package to its repository link — the packages of one monorepo would collapse into one result and one id.
- Reading `repository.directory` from each package document — one more registry call per hit on the request path.

## Code
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — fan-out, deadlines, cache, cooldown, source
  policies, coverage, notices.
- `backend/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — grouping, conflicts, freshness.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt`, `Model.kt` — ports, descriptors, statuses.
- `backend/src/main/kotlin/ai/helper2/search/SourceHealth.kt` — last outcome per source for the Sources page.
- `backend/src/main/java/ai/helper2/search/config/SearchConfiguration.java` — executor, ordering, properties.
- `backend/src/main/resources/application.yaml` (`resilience4j.*`) — the `source-<id>` policies.
- `backend/src/main/java/ai/helper2/search/cache/RedisSearchCache.java` — Redis adapter on the `redis` policy.
- `backend/src/main/java/ai/helper2/search/web/SearchController.java` — `GET /api/v1/search`, `GET /api/v1/sources`.
- `backend/src/main/java/ai/helper2/catalog/ResourceCatalog.java` — `ResultObserver` implementation.

## Diagrams
[flow](../diagrams/flow-0008-federated-search.svg) · [sequence](../diagrams/sequence-0008-federated-search.svg)

## Roadmap
- [Source cooldowns, circuits and source health stay per replica, on purpose](../roadmap.md#4-more-than-one-replica).
