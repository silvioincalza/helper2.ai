# 0006 — Outbound calls: the HTTP client and the resilience policies

**Status.** Accepted. Merges old 0006, 0033 (texts merged in PR 13 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0006"></a>

## Part: was ADR 0006 — Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; safe redirects; bounded bodies; error mapping

**Status.** Accepted. **Builds on:** [0002](0002-modular-monolith-libraries-and-apps.md), [0005](0005-redis-disposable-cache.md), [the part that was ADR 0033](#part-0033)

### Context
Every search calls several public Application Programming Interfaces (APIs) in parallel, and the Scout
calls more in the background. Each one can be slow, rate-limited or broken. One slow source must not hold up
a search, and a rate limit must lead to a pause, not to more calls. Remote hosts and their data are untrusted:
a redirect must not carry a token elsewhere, a body must not fill the heap, and one bad item must not cost the
whole answer. Failures must reach the search as a small set of known outcomes, so the source status panel can
state what happened.

### Decision
1. **One observed client for every remote API.** `platform.http.ObservedHttp` builds the Spring `RestClient` on
   Apache HttpClient 5 (5.6.5) with a connection pool (50 connections, 20 per host). Sources and Scout collectors use
   `ObservedHttp.restClientBuilder(timeout, observations)`. It lives in `platform`, so the catalog module (embeddings)
   can use it too without depending on `sources`. `sources.support.HttpClients` adds only the base URL, the token and
   the error mapping. The client sends each request once (its automatic retries are off): retries belong to the
   caller's resilience policy ([the part that was ADR 0033](#part-0033)), where they are counted and bounded.
2. **Timeouts and a total deadline.** The source's timeout is used once as the connect timeout and once as the
   response timeout. Waiting for a free pooled connection is limited to 250 ms (`POOL_WAIT`). Search source timeouts are
   2.5 s (GitHub, Hugging Face, npm), 3 s (arXiv) and 6 s (Tavily, `H2_SOURCE_LIVE_WEB_TIMEOUT`); Scout
   collectors use 10 s or 15 s.
   The response timeout applies to each read, so a body that keeps trickling in never trips it. So every call also
   has a total deadline (`BoundedResponse`, `CappedStream`). Its clock starts once, before the request goes out: the
   pool wait, the connect and the wait for the headers count inside it, never on top. Every read of the body checks
   it, before and after the read. Past it, the read throws `DeadlineExceeded`, the connection is dropped (not drained:
   Spring and Apache would read the rest of the body first), and `HttpClients` maps it to
   `Unavailable("deadline exceeded")`, transient like a timeout. The deadline is:
   - for search sources and the setup guide's fetches, the timeout (`restClientBuilder(timeout, …)`), so a search
     waits as before;
   - for Scout collectors, `H2_SOURCE_<ID>_DEADLINE` (`hf-trending` `HF_TRENDING`, `live-web-scout` `TAVILY_SCOUT`,
     `course-scout` `COURSE_SCOUT`, `github-awesome` `GITHUB_AWESOME`, `hf-provider-orgs` `HF_ORGS`, `ai-deadlines`
     `AI_DEADLINES`, `mcp-registry` `MCP`), by default twice the timeout (`SourceSettings.deadline`);
   - for one call, its own with `ObservedHttp.deadline(d)`: an awesome-list README may take 60 s (5 MB);
   - for embeddings, the embedder's timeout (11).
3. **Redirects.** `NoDowngradeRedirects` follows redirects, except one from `https` to `http`. A request that
   carries `Authorization` is not redirected to another scheme, host or port (HttpClient's `isRedirectAllowed`,
   kept by `NoDowngradeRedirects`): a token only ever reaches the configured host, and the unfollowed 3xx ends the call
   as `Unavailable`. A redirected `POST` (Tavily) is re-sent as a `GET` without the source's headers.
4. **Identity and tokens.** Every call sends `User-Agent: Helper2.ai-metasearch/<version> (+https://helper2.ai/sources)`.
   The version is the build version from the boot jar's manifest (`1.0.0`, [0034](../operations/0034-releases-and-versions.md));
   `dev` when the app runs from classes.
   A token, when set (`H2_GITHUB_TOKEN`, `H2_SETUP_GITHUB_TOKEN`, `H2_HF_TOKEN`, `H2_LIVE_WEB_API_KEY`), goes in
   `Authorization: Bearer`.
5. **Bounded bodies.** Source clients (`restClientBuilder`) read at most 2 MB of a response body
   (`MAX_RESPONSE_BYTES`); a call may set its own cap with `ObservedHttp.maxResponseBytes`, as the awesome-list
   README fetch does (5 MB). A longer body stops the read and ends the call as `Unavailable("response too large")`.
6. **Error mapping.** `HttpClients.getText` and `getJson` turn every failure into a `SourceFailure`:
   - 429, 403 with `x-ratelimit-remaining: 0`, or 503 with `Retry-After` → `RateLimited`, with the pause
     from `Retry-After` or `x-ratelimit-reset`;
   - any other status → `Unavailable("HTTP <status>")`; network error or timeout → `Unavailable`;
   - a body that is not a JavaScript Object Notation (JSON) object or array → `Unavailable`.
   `Unavailable` is `transient` for a 503 and for a network error or timeout: the same call may succeed in a moment,
   so a Scout collector's policy retries it ([the part that was ADR 0033](#part-0033)); any other status or body is not.
7. **One bad item is not a failed source.** `HttpClients.hits` maps each item on its own: an item whose mapping
   throws is dropped and counted in `helper2.source.items.dropped{source, reason}` (reason = exception type, never
   source text), and the other items are returned. Mappers build links from source data with `CanonicalUrl` and
   `HttpClients.link`, never with a bare `URI.create`.
8. **Outcomes in the search.** A `SourceFailure` becomes the source's outcome: `RateLimited` → `RATE_LIMITED`
   with a pause, `Unavailable` → `FAILED`. Deadlines, cancellation, cooldowns and the source's circuit breaker are
   decided in [0009](../search/0009-federated-search.md); a stale cache entry can still answer ([0005](0005-redis-disposable-cache.md)).
9. **arXiv pace across replicas.** arXiv allows one request every 3 seconds from all of Helper2. Before each call
   `ArxivSource` takes the shared slot of `platform.resilience.OutboundThrottle`: `SET h2:v1:outbound:arxiv 1 NX PX 3000`
   in Redis (`RedisOutboundThrottle`, on the `redis` policy), so the key's expiry frees the slot on Redis' clock.
   Another replica holding the slot, or Redis down, means `RATE_LIMITED` (pause 3 s) without a call: the pace fails
   closed, because breaking arXiv's terms costs more than a search without papers.
10. **Metrics and traces without user text.** Calls are observed (`http.client.requests`, one client span,
   trace context sent on). `BoundedUriConvention` tags the path with numeric and id-like segments replaced
   by `{id}`, and drops the query string from the tag and from the span's `http.url`.
11. **Embeddings.** `NvidiaEmbedder` uses the same observed client with its own settings
   (`ObservedHttp.requestFactory(timeout, timeout, false)`): its timeout also bounds the pool wait, redirects are
   not followed (the API key never goes to another host), and the body is buffered so the request carries a
   `Content-Length` (on a chunked body of 50 texts the API hangs until the read timeout). Its timeout is also the
   call's total deadline (`ObservedHttp.deadlineOnly`, placed under the buffering so it stops the buffered read).
   Timeouts: 3 s for queries, 30 s for indexing batches ([0011](../search/0011-vector-search-pgvector.md)).
12. **Model calls** go through Spring AI's own client, with `H2_LLM_TIMEOUT` (40 s) and `H2_LLM_MAX_RETRIES` (0, so the SDK does not retry);
   see [0008](0008-llm-integration.md). They are observed by `ModelCallMetrics`, not by `ObservedHttp`.
13. **Live check, weekly.** Every source and collector adapter has one `@Tag("live")` test against the real API
   (`*LiveTest` next to its adapter). Only `./gradlew liveTest` runs them, never `test`; a test whose key is not set
   is skipped. `.github/workflows/live.yml` runs `liveTest` every Monday on a GitHub-hosted runner with the repository
   secrets `H2_GITHUB_TOKEN`, `H2_HF_TOKEN`, `H2_LIVE_WEB_API_KEY` and `H2_NEBIUS_API_KEY`. It is not part of CI or CD.

### Consequences
- A failing source costs at most its timeout, and the search still answers with the other sources.
- A call ends at its deadline even when the body trickles in byte by byte: a collector pass cannot hang on one slow
  host for longer than its deadline per attempt, plus one read that was already waiting (see the known limits).
- Rate limits are respected: during the pause the source is not called (status `SKIPPED`).
- arXiv gets at most one call every 3 seconds whatever the number of replicas.
- One request per attempt: a provider that answers 503 sees exactly the calls the policies allow, never a hidden
  second one from the client.
- Embedding calls have the same span, `http.client.requests` timer and bounded `uri` tag as source calls.
- A host that redirects a token-carrying call elsewhere, streams an oversized body or sends a malformed item
  costs that call or that item, never a token leak, the heap or the whole source.
- An API that changes its response shape, its access or its limits shows up within a week as a failed `live` run,
  not as a source that quietly returns nothing.
- `HttpClientsMappingTest`, `TokenRedirectTest`, `ArxivSourceTest`, `RedisOutboundThrottleIT`, `ObservedHttpRedirectTest`,
  `ObservedHttpRetryTest`, `ObservedHttpObservationTest`, `ObservedHttpUserAgentTest`, `ObservedHttpDeadlineTest`,
  `CollectorRetryIT` and `NvidiaEmbedderTest` cover the rules.

### Alternatives considered
- The Java Development Kit (JDK) `HttpClient` — no connection pool limits or redirect strategy hooks of this kind.
- Spring WebClient (reactive) — a second programming model; virtual threads already make blocking calls cheap.
- Retries in the HTTP client — invisible to the circuit breaker and multiplied by any retry of the caller.
- A total deadline by a timer that aborts the request from another thread — one more thread or scheduler per call;
  checking the clock on each read gives the same bound with one read of slack.
- Retries on the search path — they would spend more of the search deadline; the pause after a rate limit and the
  stale cache cover the main cases ([the part that was ADR 0033](#part-0033)).
- arXiv pace per pod, or with Resilience4j's rate limiter — both count in one JVM, so 3 replicas send up to 3 calls
  in 3 seconds.
- arXiv pace failing open while Redis is down — each replica would call arXiv on its own pace, against its terms.

### Code
- `backend/libs/platform/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — pooled client, timeouts, redirects,
  body cap, User-Agent, observation and `uri` tag.
- `backend/libs/sources/src/main/java/ai/helper2/sources/support/HttpClients.java` — base URL, token, error mapping, per-item guard.
- `backend/libs/sources/src/main/java/ai/helper2/sources/config/LiveSourcesConfiguration.java` and `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/CollectorsConfiguration.java` — one builder per source and collector;
  collectors get their deadline (`collectorClient`). `sources/support/SourceSettings.java` — `deadline`.
- `backend/libs/sources/src/main/java/ai/helper2/sources/arxiv/ArxivSource.java`, `platform/resilience/RedisOutboundThrottle.java` —
  one arXiv call every 3 seconds across replicas.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` (`Run.collect`) — deadlines and outcomes.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Model.kt` (`SourceFailure`) — failure types.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/NvidiaEmbedder.java` — embedding client on `ObservedHttp`.
- `backend/libs/sources/src/test/java/ai/helper2/sources/*/*LiveTest.java, backend/apps/backoffice/src/test/java/ai/helper2/sources/*/*LiveTest.java`, `.github/workflows/live.yml` — the weekly live check.

### Diagrams
[flow](../../diagrams/flow-0006-outbound-http.svg) · [sequence](../../diagrams/sequence-0006-outbound-http.svg)

The weekly live check (13) is a scheduled test run, not a runtime path, so the diagrams leave it out.

### Roadmap
- Run `./gradlew liveTest` against production egress: [roadmap](../../roadmap.md#quality-gates).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0033"></a>

## Part: was ADR 0033 — Resilience policies (Resilience4j): circuit breakers, bulkheads and retries by name

**Status.** Accepted. **Builds on:** [0002](0002-modular-monolith-libraries-and-apps.md), [0005](0005-redis-disposable-cache.md),
[the part that was ADR 0006](#part-0006), [0009](../search/0009-federated-search.md), [0008](0008-llm-integration.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md), [0031](../operations/0031-observability.md)

### Context
Every search calls up to six public application programming interfaces (APIs) plus the catalog, the Scout calls seven
more in the background, Redis sits on the path of every request (cache, budgets, rate limits), the
shared caches of model answers read PostgreSQL before a model call, the
guide, the query translation, the setup guide, the Scout's enrichment and the awesome-list classifier call the model
provider, and search queries and the indexer call the embedding API.
Each of them fails in its own way: a provider is down for an hour, answers slowly, or sends a 503 for a few seconds;
Redis restarts and loads its append-only file. Without a guard, a provider that is down costs every search its full
deadline (2.5–6 s), a Redis outage adds a 250 ms timeout to every call, and a model provider that is down costs every
guide step its 40 s timeout before the answer degrades. The guards must be the same everywhere,
configurable per dependency without a release, measured (state, refused calls), and testable without a running app.
The domain (Kotlin) must not depend on the library that implements them ([0002](0002-modular-monolith-libraries-and-apps.md)).

### Decision
1. **Resilience4j 2.4.0** (`resilience4j-spring-boot4`, `resilience4j-micrometer`; circuit breaker, bulkhead, retry).
   `build.gradle.kts` pins the version of the Spring Boot 4 module explicitly, next to the bill of materials (BOM) import.
2. **One port, applied in code.** `platform.resilience.Resilience`: `call(policy, supplier)` and `run(policy, runnable)`.
   `Resilience4jPolicies` builds each call from the registries of the Spring Boot module with programmatic decorators:
   no annotations, no aspect-oriented proxies, so Kotlin use cases and Java adapters use it the same way and a test
   passes `Resilience.NONE` or its own registries. A policy is the circuit breaker, bulkhead and retry instances that
   share its name; order, outermost first: retry, bulkhead, circuit breaker. A call the policy does not let through
   throws `CallRefused` (reason `CIRCUIT_OPEN` or `BUSY`) without running; any other exception is the work's own.
   `require(policy)` fails when no instance has that name. `trySlot(policy)` takes one place in the policy's bulkhead
   for work that outlives one call and gives it back once, on `release()`; it is empty at once when the bulkhead is
   full.
3. **Named policies** in `application.yaml` (`resilience4j.*`), every instance created at startup:

   | Policy | Circuit breaker | Bulkhead (semaphore) | Retry |
   |---|---|---|---|
   | `source-<id>` (github, hf-models, hf-spaces, npm, arxiv, live-web, helper2-index, helper2-semantic, curated) | count window 20, at least 10 calls, opens at 50% failed or slow (over 2 s; Tavily 5 s), open 30 s, 2 trial calls | 20 calls per replica, no wait; catalog sources 6 (below the database pool of 10) | none: the search deadline is the budget |
   | `collector-<id>` (the seven Scout collectors) | count window 10, at least 5 calls, 50%, open 1 min, 1 trial call | none: the Scout runs one collector at a time | 3 attempts, 1 s then ×2 with ±50% jitter, only on a transient failure |
   | `redis` (search cache, budget counters, rate limits, outbound throttle, session reads) | count window 10, at least 3 calls, 50%, open 30 s, 2 trial calls | none | none |
   | `db-cache` (shared caches of model answers in `ai_cache`: translations, query vectors, setup guide answers, guide answers, [0004](0004-persistence-schema-and-app-roles.md)) | count window 10, at least 3 calls, 50% failed or slow (over 250 ms), open 30 s, 2 trial calls; each statement also stops after 1 s | 2 calls per replica, no wait: with the catalog sources' 6, below the database pool of 10 | none: a miss costs one model call |
   | `llm-chat` (every chat-model call: guide, query translation, setup guide, Scout enrichment, awesome-list classifier) | count window 10, at least 5 calls, 50%, open 30 s, 2 trial calls; only provider errors count (`ModelErrors.NotFromProvider` ignores a malformed answer and a spent budget) | none | none: every attempt is charged to a daily budget ([0008](0008-llm-integration.md)) |
   | `llm-embed` (embedding calls of search queries and guide questions) | as `llm-chat`, every failure counts | none | none |
   | `llm-embed-index` (the embedding indexer's calls) | as `llm-embed`; its own circuit, so the indexer's failed batches never refuse a visitor's query ([0011](../search/0011-vector-search-pgvector.md)) | none | none: the indexer sends a rejected batch one text at a time instead |
   | `guide` (guide questions) | none | 8 questions per replica, no wait, held per question with `trySlot` by `GuideLimits` from start to end or deadline ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)) | none |

   The guide's bulkhead is its own policy, not part of `llm-chat`: `call` applies every instance of a name, so a
   bulkhead on `llm-chat` held by a question would refuse that question's own model calls.

   A rate limit (`SourceFailure.RateLimited`) is not a failure for a circuit: the source pauses until `Retry-After`
   instead ([0009](../search/0009-federated-search.md)). A transient failure (`TransientSourceFailure`) is a 503, a network error
   or timeout (`SourceFailure.Unavailable` with `transient`, set by the HTTP mapping of [the part that was ADR 0006](#part-0006)), or
   a 429 that asks for at most 10 s; a 404, a malformed body or a long pause is not retried.
4. **Owner overrides without a release**, all optional: `H2_RESILIENCE_SOURCE_FAILURE_RATE` (50),
   `H2_RESILIENCE_SOURCE_OPEN_FOR` (30s), `H2_RESILIENCE_SOURCE_MAX_CONCURRENT` (20),
   `H2_RESILIENCE_CATALOG_MAX_CONCURRENT` (6), `H2_RESILIENCE_COLLECTOR_OPEN_FOR` (1m),
   `H2_RESILIENCE_COLLECTOR_MAX_ATTEMPTS` (3), `H2_RESILIENCE_REDIS_OPEN_FOR` (30s), `H2_RESILIENCE_DB_CACHE_OPEN_FOR` (30s),
   `H2_RESILIENCE_DB_CACHE_MAX_CONCURRENT` (2), `H2_RESILIENCE_LLM_OPEN_FOR` (30s),
   `H2_GUIDE_MAX_CONCURRENT` (8).
5. **No policy, no start.** `SearchResources` requires `source-<id>` for every enabled source and each collector
   requires `collector-<id>` when it is enabled, the model adapters require `llm-chat`, `llm-embed` or `llm-embed-index`, `GuideLimits`
   requires `guide` and `SharedCaches` requires `db-cache`, so a call site added without its policy stops startup instead of running unguarded. The `demo` profile adds the policies of its fixture sources.
6. **Each outbound request is sent once.** The Apache client's own retries are off (`ObservedHttp`), so every attempt
   is one the circuit counts and the retry bounds.
7. **Global limits stay in Redis.** Resilience4j's rate limiter counts in one Java virtual machine (JVM), so it cannot
   keep a limit across 3 replicas. Limits that must hold for all of Helper2 are Redis keys: the per-client inbound
   limits ([0025](../edge/0025-http-edge-protection.md)), the daily model budgets ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)) and the
   arXiv pace (`OutboundThrottle`, [the part that was ADR 0006](#part-0006)).
8. **No time limiter.** Search deadlines stay in `SearchResources` (one virtual thread per source, `Future.get` with
   the source's own timeout, [0009](../search/0009-federated-search.md)); a `TimeLimiter` would need futures of its own and
   duplicate them. Each HTTP call also has a total deadline of its own, in the client
   ([the part that was ADR 0006](#part-0006) §2): a retry starts a new one, so a collector call costs at most its deadline per
   attempt plus the backoff.
9. **Metrics** by `resilience4j-micrometer`, with the common tags `application` and `environment` ([0031](../operations/0031-observability.md)):
   `resilience4j.circuitbreaker.state{name,state}` (1 for the current state), `.calls{name,kind}`,
   `.not.permitted.calls{name}`, `.failure.rate`, `.slow.call.rate`; `resilience4j.bulkhead.available.concurrent.calls{name}`;
   `resilience4j.retry.calls{name,kind}`. The circuit breaker health indicator stays off (the module's default), so an
   open circuit never takes a pod out of readiness.

### Consequences
- a provider that is down costs a few searches their deadline, then is `SKIPPED` ("Paused after repeated
  failures", `SkipReason.CIRCUIT_OPEN`) without a call until a trial call succeeds; its stale cache entry still answers.
- a Redis outage costs at most a few 250 ms timeouts per 30 s for the whole replica, not per adapter.
- a slow or failing database costs the shared caches a few lookups of at most 1 s per 30 s, then every lookup is a
  miss at once; they never hold more than 2 connections of a replica's pool.
- one source cannot hold every virtual thread or every database connection; one search too many for a source
  is `SKIPPED` ("Too many searches at once", `SkipReason.BUSY`) at once.
- a Scout collector rides out a short 503 without losing its run; a permanent error fails at once.
- while the model provider is down, the guide answers with top search results at once, search runs
  untranslated, the setup guide shows its examples only, enrichment stays in the backlog and the classifier uses its
  keyword rules, without spending a daily budget on calls that cannot succeed; while the embedding API is down,
  search is keyword-only without waiting for the embedding timeout.
- the guide's concurrency limit is one more named, metered bulkhead (`resilience4j.bulkhead.available.concurrent.calls{name="guide"}`).
- the state of every circuit and every refused call is a meter and an alert (`Helper2CircuitOpen`,
  `Helper2CallsNotPermitted`, [0031](../operations/0031-observability.md)).
- the Sources page shows each enabled source's circuit and when it opened, as the answering replica sees it
  (`Resilience.circuit`, [0009](../search/0009-federated-search.md)). Resilience4j keeps no opening time, so
  `Resilience4jPolicies` watches each circuit's state transitions on the circuit's own clock.
- `Resilience4jPoliciesTest`, `ResiliencePoliciesConfigIT`, `SearchResourcesTest`, `SourceCircuitIT`, `GuideLimitsTest`,
  `SpringAiGuideModelTest`, `SpringAiQueryTranslatorTest`, `SpringAiToolGuideModelTest`, `SpringAiEnrichmentModelTest`,
  `SpringAiRepoClassifierTest`, `NvidiaEmbedderTest`,
  `CollectorRetryIT`, `TransientSourceFailureTest`, `ObservedHttpRetryTest`, `JdbcSharedCacheIT` and the Redis adapter tests cover the rules.

### Alternatives considered
- Spring Framework 7 `@Retryable` and `@ConcurrencyLimit` — no circuit breaker and only basic metrics; annotations need
  proxies, which Kotlin use cases built in configuration classes do not get. Using it next to Resilience4j would mean
  two configuration models for one concern.
- Hand-written guards (a 30 s bypass after any error, a consecutive-failure cooldown) — no half-open trial call, no
  failure rate, no bulkhead, no meters, and more code to test with every new rule.
- Resilience4j annotations (`@CircuitBreaker`) — need aspect-oriented proxies, hide the policy name from the call
  site and cannot be used from the Kotlin domain without a framework import.
- Resilience4j rate limiter for the arXiv pace or inbound limits — counts per JVM, so 3 replicas allow 3 times the
  limit.
- Retries on the search path — each attempt spends the deadline the user is waiting on; a stale cache entry and the
  other sources answer instead.

### Code
- `backend/libs/platform/src/main/java/ai/helper2/platform/resilience/Resilience.java`, `Resilience4jPolicies.java`, `CallRefused.java`,
  `ResilienceConfiguration.java` — the port and its Resilience4j adapter.
- `backend/libs/platform/src/main/java/ai/helper2/platform/resilience/OutboundThrottle.java`, `RedisOutboundThrottle.java` — the
  shared pace of one host.
- `backend/apps/public/src/main/resources/application.yaml` (`resilience4j.*`) — the policies.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — `source-<id>`, `CIRCUIT_OPEN` and `BUSY`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/*/*Collector.java`, `support/TransientSourceFailure.java` — `collector-<id>` and what
  it retries; `support/HttpClients.java` — transient failures.
- `backend/libs/search/src/main/java/ai/helper2/search/cache/RedisSearchCache.java`,
  `platform/llm/RedisCallCounter.java`, `platform/RedisRateLimiter.java` — `redis`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/cache/SharedCaches.java`, `JdbcSharedCache.java` — `db-cache`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — one request per attempt.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java`, `search/internal/SpringAiQueryTranslator.java`,
  `setup/internal/SpringAiToolGuideModel.java`, `scout/internal/SpringAiEnrichmentModel.java`,
  `sources/classifier/SpringAiRepoClassifier.java` — `llm-chat`; `platform/llm/ModelErrors.java` — what counts for it; `catalog/internal/NvidiaEmbedder.java` — `llm-embed` or `llm-embed-index`, given by `CatalogConfiguration`.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideLimits.java` — `guide`, held with `trySlot`.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2CircuitOpen`, `Helper2CallsNotPermitted`.

### Diagrams
[flow](../../diagrams/flow-0006-resilience-policies.svg) · [sequence](../../diagrams/sequence-0006-resilience-policies.svg)

[flow 0009](../../diagrams/flow-0009-federated-search.svg) (source policy in the fan-out) ·
[sequence 0005](../../diagrams/sequence-0005-redis-disposable-cache.svg) (the `redis` circuit)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
