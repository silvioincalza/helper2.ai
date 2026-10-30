# 0008 — Federated search over disclosed sources: modes, parallel fan-out, deadlines, cooldown, partial results, source status, merge and conflicts

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md), [0005](0005-redis-disposable-cache.md), [0006](0006-outbound-http.md)

## Context
Helper2.ai answers a search from several sources: public application programming interfaces (APIs) (GitHub, Hugging Face, npm, arXiv, Tavily) and its own catalog.
Each API has its own speed, rate limit and failure modes. One slow or failing source must not block the others.
Users must see which sources answered, which did not, and why. Helper2.ai does not search the whole web,
so the list of sources must be disclosed. Two sources can describe the same resource and can disagree on a fact.

## Decision
1. **One port.** Every source implements `SearchSource` with a `SourceDescriptor`: id, name, mode
   (`LIVE`, `INDEXED`, `CURATED`, `DEMO`), timeout, cache time to live (TTL), `enabled`, `priority`, `usesWebQuery`,
   `usesTranslation`. Search sources and their priority (lower wins on display text): `curated` 10, `github` 20,
   `hf-models` 30, `hf-spaces` 35, `npm` 40, `arxiv` 45, `tavily` 48, `helper2-index` 50, `helper2-semantic` 55
   (only with an embedding key, [0012](0012-vector-search-pgvector.md)). `GET /api/v1/sources` lists them.
2. **Parallel fan-out.** `SearchResources.search` starts each source on the `searchExecutor`
   (one virtual thread per task). A disabled source is reported as `SKIPPED` ("Disabled in configuration").
3. **Own deadline per source.** The deadline and the reported latency count from the source's own start.
   Timeouts: `github`, `hf-models`, `hf-spaces`, `npm` 2500 ms; `arxiv` 3 s; `tavily` `H2_SOURCE_TAVILY_TIMEOUT` (6 s);
   `helper2-index` `H2_SOURCE_INDEX_TIMEOUT` (3 s); `helper2-semantic` `H2_SOURCE_SEMANTIC_TIMEOUT` (3 s); `curated` 1000 ms.
   On timeout the task is canceled and the outcome is `TIMED_OUT`.
4. **Cache for LIVE sources.** The fresh and stale windows of [0005](0005-redis-disposable-cache.md) apply: a fresh entry
   is served as `CACHE` with no live call, and after a failure an entry inside the stale window is served as `STALE_CACHE`.
5. **Cooldown.** `SourceFailure.RateLimited` pauses the source until `Retry-After` (or 1 min). During the pause the
   source is `SKIPPED` ("Paused after a rate limit"), with a stale entry if one exists.
6. **Status and coverage.** Each source gets a `SourceStatus`: outcome (`SUCCEEDED`, `FAILED`, `TIMED_OUT`,
   `RATE_LIMITED`, `SKIPPED`), served from (`LIVE`, `CACHE`, `STALE_CACHE`, `NONE`), count, latency, message.
   `coverage.partial` is true when any enabled source did not succeed. Notices: `CACHE_UNAVAILABLE`,
   `INDEX_UNAVAILABLE`, `QUERY_EXPANDED`, `ALL_SOURCES_FAILED`. The search never throws for a source, cache or index failure.
7. **Merge without a model.** `ResultMerger` groups hits that share a canonical web address (URL) or a repository URL (union-find).
   Display text comes from the preferred source. License, pricing and open source: if sources disagree, the value is
   null and every claim is kept in `conflicts`. Dates: published and event start = earliest, event end = latest,
   last update = most recent; the others stay as alternatives. Published and event dates are `CONFLICTING` when two
   sources state different days ([0010](0010-filters-facets-dates-timeline.md)).
8. **Catalog link** ([0004](0004-persistence-and-provenance.md)). One read for first-discovered dates; only hits fetched
   live now are handed to `ResultObserver.record`, which queues the write (`helper2.catalog.observation-queue`, 256).
9. Each source returns at most `H2_SEARCH_PER_SOURCE_LIMIT` (30) hits. The response has `Cache-Control: no-store`.

## Consequences
- Good: a slow or failed source costs only its own results. The user sees the outcome of every source.
- Good: the merge is deterministic. Conflicts are shown, not resolved.
- Bad: the response waits for the slowest source up to its deadline. Tavily (6 s) sets the worst case;
  a non-English query can add the translation wait before most sources start ([0011](0011-language-aware-search.md)).
- Bad: cooldowns and the per-source health on the Sources page live in each replica's memory. Each replica finds
  a source's rate limit on its own (one more refused call), and the Sources page shows what the replica that answered has seen.
- Trade-off: facets and filters only see the hits fetched for this query (at most 30 per source), not the whole catalog.

## Alternatives considered
- One global deadline for the whole search — a slow step (translation, a slow source) would eat the budget of the others.
- Calling sources one after the other — latency becomes the sum of all sources.
- Scraping or crawling sites — not allowed by their terms; sources would not be disclosed.
- Merging with a model — not repeatable and hard to explain; conflicts could be hidden.

## Code
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — fan-out, deadlines, cache, cooldown, coverage, notices.
- `backend/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — grouping, conflicts, freshness.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt`, `Model.kt` — ports, descriptors, statuses.
- `backend/src/main/kotlin/ai/helper2/search/SourceHealth.kt` — last outcome per source for the Sources page.
- `backend/src/main/java/ai/helper2/search/config/SearchConfiguration.java` — executor, ordering, properties.
- `backend/src/main/java/ai/helper2/search/cache/RedisSearchCache.java` — Redis adapter with circuit breaker.
- `backend/src/main/java/ai/helper2/search/web/SearchController.java` — `GET /api/v1/search`, `GET /api/v1/sources`.
- `backend/src/main/java/ai/helper2/catalog/ResourceCatalog.java` — `ResultObserver` implementation.

## Diagrams
[flow](../diagrams/flow-0008-federated-search.svg) · [sequence](../diagrams/sequence-0008-federated-search.svg)

## Roadmap
- [Source cooldowns and source health stay per replica, on purpose](../roadmap.md#4-more-than-one-replica).
- [Resilience4j for outbound calls (rate limit, retry with backoff)](../roadmap.md#5-ai-quality-and-cost).
