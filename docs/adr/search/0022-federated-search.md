# 0022 — Federated search

**Depends on:** [0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0004](../foundation/0004-persistence-and-the-schema.md), [0005](../foundation/0005-redis-as-a-disposable-cache.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0021](../scout/0021-resource-identity-one-resource-per-repository.md).

## Context

Helper2.ai answers a search from several sources: public application programming interfaces (APIs) (GitHub, Hugging Face, npm, arXiv, Tavily, and when switched on Hacker News, [0030](0030-hacker-news-as-a-live-source.md)) and its own catalog.
Each API has its own speed, rate limit and failure modes. One slow or failing source must not block the others, and
a source that is down must not cost every search its full deadline.
Users must see which sources answered, which did not, and why. Helper2.ai does not search the whole web,
so the list of sources must be disclosed, with how each one is doing: how fast it answers, whether it is paused,
and how many web credits the day has used. Two sources can describe the same resource and can disagree on a fact.

## Decision

1. **One port.** Every source implements `SearchSource` with a `SourceDescriptor`: id, name, short name, external, mode
   (`LIVE`, `INDEXED`, `CURATED`, `DEMO`), timeout, cache time to live (TTL), `enabled`, `priority`, `usesWebQuery`,
   `usesTranslation`, `metered`. Search sources and their priority (lower wins on display text): `curated` 10, `github` 20,
   `hf-models` 30, `hf-spaces` 35, `npm` 40, `arxiv` 45, `hacker-news` 47, `live-web` 48, `helper2-index` 50, `helper2-semantic` 55
   (only with an embedding key, [0024](0024-vector-search.md)). `GET /api/v1/sources` lists them.
   The short name (`shortName`, 1 to 12 characters, e.g. `HF models`, `Web scout`) is the name under the source's dot
   on the home radar ([0040](../frontend/0040-design-system.md)); the descriptor refuses a missing or
   longer one, so a new source cannot start without it. `external` is false for Helper2's own sources
   (`helper2-index`, `helper2-semantic`, `curated`) and true for every source run by someone else. The radar and its
   source count show only external sources, and read this flag, not a list of ids.
2. **Parallel fan-out.** `SearchResources.search` starts each source on the `searchExecutor`
   (one virtual thread per task). A disabled source is reported as `SKIPPED` with `SkipReason.DISABLED`
   ("Disabled in configuration"); it leaves no outcome on the Sources page.
   Each source builds its own query from the same `SourceQuery`. GitHub (`GitHubSource.searchText`) reads `a b OR c`
   as `a AND (b OR c)`, so it gets the first required-word variant (the English words of a translated query) alone,
   and only a one-word query gets up to 4 expansions as `word OR "two words" OR …`. The words are cut at a word to
   200 characters, inside GitHub's 256-character and 5-operator limits. Only letters, digits, spaces and inner
   hyphens are sent, so a qualifier (`user:`, `repo:`), a quote or a leading `-` in the text is a plain word.
3. **Own deadline per source.** The deadline and the reported latency count from the source's own start.
   Timeouts: `github`, `hf-models`, `hf-spaces`, `npm`, `hacker-news` 2500 ms; `arxiv` 3 s; `live-web` `H2_SOURCE_LIVE_WEB_TIMEOUT` (6 s);
   `helper2-index` `H2_SOURCE_INDEX_TIMEOUT` (3 s); `helper2-semantic` `H2_SOURCE_SEMANTIC_TIMEOUT` (3 s); `curated` 1000 ms.
   On timeout the task is canceled and the outcome is `TIMED_OUT`.
4. **Cache for LIVE sources.** The fresh and stale windows of [0005](../foundation/0005-redis-as-a-disposable-cache.md) apply: a fresh entry
   is served as `CACHE` with no live call, and after a failure an entry inside the stale window is served as `STALE_CACHE`.
   A metered source (Tavily, `descriptor.metered`) reads its stored result set instead, fresh for 24 h or 48 h by the
   question's intent, and calls live only inside the asker's daily web searches; over the limit it is `SKIPPED` with
   `SkipReason.DAILY_LIMIT` (or `QUOTA_UNAVAILABLE` when the count cannot be read), with its stored set up to 7 days
   old ([0027](0027-live-web-store-first-daily-quota.md)).
5. **Cooldown.** `SourceFailure.RateLimited` pauses the source until `Retry-After` (or 1 min). During the pause the
   source is `SKIPPED` with `SkipReason.RATE_LIMIT_PAUSE` ("Paused after a rate limit"), with a stale entry if one exists.
   A source that cannot browse is `SKIPPED` with `SkipReason.NOT_BROWSABLE` on a browse request.
6. **Circuit breaker and bulkhead per source.** Each call runs under the source's policy `source-<id>`
   ([0006](../foundation/0006-outbound-calls-and-resilience.md)), never with a retry: the deadline is the budget. The circuit opens when half of
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
8. **Merge without a model.** `ResultMerger` groups hits that share a canonical web address (URL) (union-find). Which hits are
   one resource (a package and its repository, every package of one repository, a link to a sub-directory, the git
   ref) is decided by the resource-identity rules of [0021](../scout/0021-resource-identity-one-resource-per-repository.md): the merger applies
   them, and this record does not repeat them. The npm search API returns the repository URL only, not
   `repository.directory`; the sub-directory is known when the package states it in the URL.
   The result's id comes from the repository its hits state, else from their own URLs, a GitHub URL first ([0004](../foundation/0004-persistence-and-the-schema.md)).
   Display text comes from the preferred source. License, pricing and open source: if sources disagree, the value is
   null and every claim is kept in `conflicts`. Licence ids that differ only in letter case (`cc-by-4.0`, `CC-BY-4.0`)
   agree. Popularity keeps one value per metric and source: the newest (`asOf`), so a stored star count never hides
   the live one. Use cases: the ones a source states; with none, a use case is derived from the hits' title,
   description and tags only when two different words of its word-list entry appear ("translate" and "translation"),
   so one common word in a title ("data", "code", "image") is not a facet value. The user sees every claim; nothing goes to the review queue. Dates: published and event start = earliest, event end = latest,
   last update = most recent; the others stay as alternatives. Published and event dates are `CONFLICTING` when two
   sources state different days ([0026](0026-filters-facets-categories.md)).
9. **Catalog link** ([0004](../foundation/0004-persistence-and-the-schema.md)). One read for first-discovered dates; only hits fetched
   live now are handed to `ResultObserver.record`, which queues the write (`helper2.catalog.observation-queue`, 256).
10. Each source returns at most `H2_SEARCH_PER_SOURCE_LIMIT` (30) hits. The response has `Cache-Control: no-store`.
11. **Sources page numbers.** `GET /api/v1/sources` (`ListSources`) lists every disclosed source by priority, with:
    - `lastOutcome`, `lastCheckedAt`: the newer of this replica's last search (`SourceHealth`) and the Scout's last
      run (`RecordedOutcomes`); when the database cannot answer, this replica's only.
    - `lastCheckedSecondsAgo`: how long ago that was, by the server's clock when it answers. The home radar says
      "2 minutes ago" from it, so a browser with a wrong clock does not say the wrong time. The Sources table shows
      `lastCheckedAt` in UTC.
    - `responseTimeMs`: the median of this replica's last 20 calls to the source, failed and timed-out ones included.
      A cache hit or a skipped call is not a call. A median of a short window is steadier than the last call and
      needs no shared store.
    - `circuit` (`CLOSED`, `OPEN`, `HALF_OPEN`) and `circuitOpenedAt` (only while `OPEN`): this replica's
      `source-<id>` circuit (`Resilience.circuit`); null for a disabled source and for Scout collectors.
    - `metered`: true for a source whose calls spend a paid quota, enabled or not (`SourceDescriptor.metered`; today
      only Tavily). The page reads this flag, not a source id.
    - `webCredits {usedToday, dailyCap}` on an enabled metered source: the credits of the day for the whole cluster
      and the global cap, from Redis ([0027](0027-live-web-store-first-daily-quota.md)); null on every other source and
      when Redis cannot answer. A visitor's own web searches left stay in the search and guide responses, and in
      `GET /api/v1/me` ([0036](../identity/0036-identity-and-the-admin-host.md)).
    - `newThisWeek`: the catalog resources this source brought that Helper2 first discovered in the last 7 days (a
      rolling window, `ListSources.NEW_WINDOW`), for the home radar
      ([0040](../frontend/0040-design-system.md)). For a source with records: the resources with a record
      from it. `helper2-index` is the whole index, so it counts every indexed and observed resource.
      `helper2-semantic` is the same index searched by meaning: null, no count of its own. Read through the
      catalog's `DiscoveredResources` port, on the index `resource_first_discovered_idx` (60,000 resources: 34 ms
      without it, 19 ms with it, median). Null when the database cannot answer.

    Response time and circuit are per replica, like the circuits themselves; the field descriptions say so.
12. **A result that must work now is confirmed now.** A source can name the URLs only it can confirm
   (`descriptor.confirmsUrlPrefix`). A merged result under such a URL that only the catalog sources (`INDEXED`)
   returned in this search is left out (`SearchResources.confirmedNow`). A hit of the source itself, of another live
   source or of `curated` keeps it. `hf-spaces` names `https://huggingface.co/spaces/`: a Space is an app to use, and
   the catalog does not know if it still runs. Which Space is a result (it runs now, it has the minimum of likes)
   is the vitality rule of [0020](../scout/0020-resource-vitality.md), applied to every source's answer before the cache and
   the limit.
13. **Spaces are found by meaning first.** The Hub's listing (`GET /api/spaces?search=`) matches the words against
    the names of the Spaces: on 11 Nov 2026 it returned a Space with 3 likes or more for 4 of the 22 English "use
    it" queries of the eval set, and no Space at all for 14. `hf-spaces` asks the Hub's search by meaning instead,
    `GET /api/spaces/semantic-search?q=` (in the Hub's OpenAPI specification,
    `https://huggingface.co/.well-known/openapi.json`, which the Hub's API page names as its reference): the same
    22 queries give a Space with 3 likes or more for 17.
    - `q` is the question as asked (its English translation when there is one), up to the 250 characters the
      specification allows. The answer holds running Spaces only, each with its stage and likes.
    - `category` is added when the words name one of four needs (`SpaceCategories`, a fixed word list):
      `background-removal`, `image-generation`, `music-generation`, `speech-synthesis`. For "remove image
      background" the question alone returned 7 Spaces, the best with 17 likes; with the category 44, the first
      with 2,987. The other categories of the specification measured no better than the question alone, so they
      are not mapped.
    - The listing, most liked first, stays for a browse request, for a date window (by trend), when the search by
      meaning returns no Space the vitality rule of [0020](../scout/0020-resource-vitality.md) admits (none, or only
      Spaces with too few likes) and when it fails (counted `search-by-meaning-unavailable`).
    - The answer also holds a short description a model wrote (`ai_short_description`). It is not read: a Space's
      card still has no description.

### Code

- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — the use case, top-down: analyse, fan out, merge, confirm,
  rank, filter, page, coverage, notices.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchNotices.kt` — coverage and the notices of one search;
  `QueryTranslation.kt` — the bounded wait for the English words of a query ([0023](0023-catalog-full-text-index.md)).
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SourceFanOut.kt` — one search over the sources: skip rules, fresh cache,
  quota, live call under the source policy, deadlines, cooldown, stale fallback (`Run`, `SourceAttempt`, `FanOutResult`).
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SourceCacheKeys.kt` — the cache key of one source query and the text it is
  made of.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — grouping, conflicts, freshness.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Ports.kt`, `Model.kt` — ports, descriptors, statuses.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/SourceHealth.kt` — last outcome and recent response time per source.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/ListSources.kt` — the Sources page list with its live numbers.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/CatalogResultObserver.java`, `JdbcResources.java` —
  `DiscoveredResources`, `discoveredSince`; `resource_first_discovered_idx` in `V1__schema.sql`.
- `backend/libs/search/src/main/java/ai/helper2/search/config/SearchConfiguration.java` — executor, ordering, properties.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` (`resilience4j.*`) — the `source-<id>` policies.
- `backend/libs/search/src/main/java/ai/helper2/search/cache/RedisSearchCache.java` — Redis adapter on the `redis` policy.
- `backend/libs/search/src/main/java/ai/helper2/search/internal/JdbcWebResultStore.java`, `search/WebSearch.kt` — the metered source's
  store and quota ([0027](0027-live-web-store-first-daily-quota.md)).
- `backend/libs/web-contract/src/main/java/ai/helper2/search/web/SearchController.java` — `GET /api/v1/search`; `SourcesController.java` — `GET /api/v1/sources`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/CatalogResultObserver.java` — `ResultObserver` implementation.

## Alternatives considered

- One global deadline for the whole search — a slow step (translation, a slow source) would eat the budget of the others.
- A pause after N consecutive failures — one success in between resets it, and it has no trial call or slow-call rule.
- Retrying a failed source within the search — each attempt spends the deadline the user is waiting on.
- Calling sources one after the other — latency becomes the sum of all sources.
- Scraping or crawling sites — not allowed by their terms; sources would not be disclosed.
- Merging with a model — not repeatable and hard to explain; conflicts could be hidden.
- Keeping the packages of one monorepo apart, each with a link to the repository — the rule depended on which hits arrived together (a package alone joined, the same package with a sibling did not), so one package was one resource in one run and two in the next; and many near-twin rows of one project filled the first page.
- Reading `repository.directory` from each package document — one more registry call per hit on the request path.
- Keeping the stage of each Space in the catalog, refreshed by a job — a schema change and a job to run; the stage
  would still be as old as the last run.
- Asking the Hub for the stage of each catalog Space during the search — one call per Space on the request path.

## Consequences

- A slow or failed source costs only its own results. The user sees the outcome of every source.
- A source that is down costs at most 10 searches its deadline per replica, then none until it recovers.
- The merge is deterministic. Conflicts are shown, not resolved.
- The Hub's search by meaning does not return the same list for the same question every time (the count moved
  between 6 and 7, and between 85 and 88, in calls a second apart). Inside the 30 minutes of the cache the answer
  is the same; after it, the Spaces of a question can change. The search by meaning can also return a popular Space
  that fits the question loosely; its place in the source's answer is then low.
- A Space is shown only when Hugging Face answers in this search (live, or from the fresh or stale cache). With
  `hf-spaces` off, paused or failed and no cache entry, the search shows no Space from the catalog. A Space the
  catalog finds by meaning and the Hub does not return for the same words is not shown either.
- A package is found through its repository: its own name and description are in the resource's records, but the result shows the repository's title and one link per source, so of 40 packages of one source one link is shown. A repository with packages for different uses (an SDK and a command line tool) is still one result.
- Two loads of the Sources page can show different response times and circuit states when two replicas answer.
- `SourceHealthTest`, `ListSourcesTest`, `Resilience4jPoliciesTest`, `SearchApiIT` and `WebSearchQuotaApiIT` cover the
  Sources page numbers.
- Security: the endpoint stays public and rate-limited like every API path ([0037](../identity/0037-http-edge.md)).
  Circuit state, response time and the credit count are operational, not secret: a search already shows a paused
  source as `SKIPPED` ("Paused after repeated failures"), and a visitor over the cap already sees the refusal. The
  response holds no host name, key or exception text, only numbers, enums and times.

### Known limits

- All packages of one repository are one resource ([0021](../scout/0021-resource-identity-one-resource-per-repository.md)), so the result shows one link per source: with several packages of one source, one of them (the first by external id). Download counts of several packages are kept per record and one is shown. Until the repository's own record arrives, the resource carries the first package's title and description.
- A search waits for the slowest answering source up to its deadline: Tavily (6 s) sets the worst case, and a non-English query can start most sources up to 2.5 s later for translation.
- Facets and filters see only the hits fetched for this query (at most 30 per source), not the whole catalog.
- A package found without its repository is a result of its own until a search finds both. Once joined, its record stays with the repository; a later batch where a second package of the same source links to the repository splits it off again, though the stored join was not ambiguous.
- The row the index search reads takes its type, licence, pricing, open-source flag and event days from the card of the resource page, but not its title: the row keeps the title of the last write, the page shows the title of the first source by priority. `IndexHitAndCardParityIT` lists that gap. The test covers resources of two indexed sources: the page of a curated resource merges every record, while its row keeps what the curated write set, and no test compares the two.
- Page 2 runs the fan-out again, so results recorded while page 1 was built can move results between pages. A short-lived
  snapshot of the ranked ids per search would fix it.

## Diagrams

[flow](../../diagrams/flow-0022-federated-search.svg) · [sequence](../../diagrams/sequence-0022-federated-search.svg)

## Backlog

- Features: [F3.1](https://github.com/silvioincalza/helper2.ai/issues/47) Federated search: parallel fan-out, deadlines, partial results, source status, [F3.10](https://github.com/silvioincalza/helper2.ai/issues/56) Hugging Face Spaces found by meaning.
- Open issues that change this decision:
  - [#178](https://github.com/silvioincalza/helper2.ai/issues/178) S1.10.2 To know when per-replica limits hurt
  - [#249](https://github.com/silvioincalza/helper2.ai/issues/249) S3.10.2 A strong Space on the first page
  - [#250](https://github.com/silvioincalza/helper2.ai/issues/250) S3.11.1 A search to answer from the catalog and call a live source only when the catalog is too old or too thin
