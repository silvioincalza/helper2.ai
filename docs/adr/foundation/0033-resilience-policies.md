# 0033 — Resilience policies (Resilience4j): circuit breakers, bulkheads and retries by name

**Status.** Accepted. **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0005](0005-redis-disposable-cache.md),
[0006](0006-outbound-http.md), [0008](../search/0008-federated-search.md), [0014](../guide/0014-llm-integration.md), [0017](../guide/0017-guide-cost-guard.md), [0026](../operations/0026-observability.md)

## Context
Every search calls up to six public application programming interfaces (APIs) plus the catalog, the Scout calls seven
more in the background, Redis sits on the path of every request (cache, budgets, rate limits, guide answers), the
shared caches of model answers read PostgreSQL before a model call, the
guide, the query translation, the setup guide, the Scout's enrichment and the awesome-list classifier call the model
provider, and search queries and the indexer call the embedding API.
Each of them fails in its own way: a provider is down for an hour, answers slowly, or sends a 503 for a few seconds;
Redis restarts and loads its append-only file. Without a guard, a provider that is down costs every search its full
deadline (2.5–6 s), a Redis outage adds a 250 ms timeout to every call, and a model provider that is down costs every
guide step its 40 s timeout before the answer degrades. The guards must be the same everywhere,
configurable per dependency without a release, measured (state, refused calls), and testable without a running app.
The domain (Kotlin) must not depend on the library that implements them ([0002](0002-modular-monolith-hexagonal.md)).

## Decision
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
   | `source-<id>` (github, hf-models, hf-spaces, npm, arxiv, tavily, helper2-index, helper2-semantic, curated) | count window 20, at least 10 calls, opens at 50% failed or slow (over 2 s; Tavily 5 s), open 30 s, 2 trial calls | 20 calls per replica, no wait; catalog sources 6 (below the database pool of 10) | none: the search deadline is the budget |
   | `collector-<id>` (the seven Scout collectors) | count window 10, at least 5 calls, 50%, open 1 min, 1 trial call | none: the Scout runs one collector at a time | 3 attempts, 1 s then ×2 with ±50% jitter, only on a transient failure |
   | `redis` (search cache, guide answers, budget counters, rate limits, outbound throttle, session reads) | count window 10, at least 3 calls, 50%, open 30 s, 2 trial calls | none | none |
   | `db-cache` (shared caches of model answers in `ai_cache`: translations, query vectors, setup guide answers, [0004](0004-persistence-and-provenance.md)) | count window 10, at least 3 calls, 50% failed or slow (over 250 ms), open 30 s, 2 trial calls; each statement also stops after 1 s | 2 calls per replica, no wait: with the catalog sources' 6, below the database pool of 10 | none: a miss costs one model call |
   | `llm-chat` (every chat-model call: guide, query translation, setup guide, Scout enrichment, awesome-list classifier) | count window 10, at least 5 calls, 50%, open 30 s, 2 trial calls; only provider errors count (`ModelErrors.NotFromProvider` ignores a malformed answer and a spent budget) | none | none: every attempt is charged to a daily budget ([0014](../guide/0014-llm-integration.md)) |
   | `llm-embed` (every embedding call: search queries and the indexer) | as `llm-chat`, every failure counts | none | none |
   | `guide` (guide questions) | none | 8 questions per replica, no wait, held per question with `trySlot` by `GuideLimits` from start to end or deadline ([0017](../guide/0017-guide-cost-guard.md)) | none |

   The guide's bulkhead is its own policy, not part of `llm-chat`: `call` applies every instance of a name, so a
   bulkhead on `llm-chat` held by a question would refuse that question's own model calls.

   A rate limit (`SourceFailure.RateLimited`) is not a failure for a circuit: the source pauses until `Retry-After`
   instead ([0008](../search/0008-federated-search.md)). A transient failure (`TransientSourceFailure`) is a 503, a network error
   or timeout (`SourceFailure.Unavailable` with `transient`, set by the HTTP mapping of [0006](0006-outbound-http.md)), or
   a 429 that asks for at most 10 s; a 404, a malformed body or a long pause is not retried.
4. **Owner overrides without a release**, all optional: `H2_RESILIENCE_SOURCE_FAILURE_RATE` (50),
   `H2_RESILIENCE_SOURCE_OPEN_FOR` (30s), `H2_RESILIENCE_SOURCE_MAX_CONCURRENT` (20),
   `H2_RESILIENCE_CATALOG_MAX_CONCURRENT` (6), `H2_RESILIENCE_COLLECTOR_OPEN_FOR` (1m),
   `H2_RESILIENCE_COLLECTOR_MAX_ATTEMPTS` (3), `H2_RESILIENCE_REDIS_OPEN_FOR` (30s), `H2_RESILIENCE_DB_CACHE_OPEN_FOR` (30s),
   `H2_RESILIENCE_DB_CACHE_MAX_CONCURRENT` (2), `H2_RESILIENCE_LLM_OPEN_FOR` (30s),
   `H2_GUIDE_MAX_CONCURRENT` (8).
5. **No policy, no start.** `SearchResources` requires `source-<id>` for every enabled source and each collector
   requires `collector-<id>` when it is enabled, the model adapters require `llm-chat` or `llm-embed`, `GuideLimits`
   requires `guide` and `SharedCaches` requires `db-cache`, so a call site added without its policy stops startup instead of running unguarded. The `demo` profile adds the policies of its fixture sources.
6. **Each outbound request is sent once.** The Apache client's own retries are off (`ObservedHttp`), so every attempt
   is one the circuit counts and the retry bounds.
7. **Global limits stay in Redis.** Resilience4j's rate limiter counts in one Java virtual machine (JVM), so it cannot
   keep a limit across 3 replicas. Limits that must hold for all of Helper2 are Redis keys: the per-client inbound
   limits ([0024](../edge/0024-http-edge-protection.md)), the daily model budgets ([0017](../guide/0017-guide-cost-guard.md)) and the
   arXiv pace (`OutboundThrottle`, [0006](0006-outbound-http.md)).
8. **No time limiter.** Search deadlines stay in `SearchResources` (one virtual thread per source, `Future.get` with
   the source's own timeout, [0008](../search/0008-federated-search.md)); a `TimeLimiter` would need futures of its own and
   duplicate them.
9. **Metrics** by `resilience4j-micrometer`, with the common tags `application` and `environment` ([0026](../operations/0026-observability.md)):
   `resilience4j.circuitbreaker.state{name,state}` (1 for the current state), `.calls{name,kind}`,
   `.not.permitted.calls{name}`, `.failure.rate`, `.slow.call.rate`; `resilience4j.bulkhead.available.concurrent.calls{name}`;
   `resilience4j.retry.calls{name,kind}`. The circuit breaker health indicator stays off (the module's default), so an
   open circuit never takes a pod out of readiness.

## Consequences
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
  `Helper2CallsNotPermitted`, [0026](../operations/0026-observability.md)).
- the Sources page shows each enabled source's circuit and when it opened, as the answering replica sees it
  (`Resilience.circuit`, [0008](../search/0008-federated-search.md)). Resilience4j keeps no opening time, so
  `Resilience4jPolicies` watches each circuit's state transitions on the circuit's own clock.
- `Resilience4jPoliciesTest`, `ResiliencePoliciesConfigIT`, `SearchResourcesTest`, `SourceCircuitIT`, `GuideLimitsTest`,
  `SpringAiGuideModelTest`, `SpringAiQueryTranslatorTest`, `SpringAiToolGuideModelTest`, `SpringAiEnrichmentModelTest`,
  `SpringAiRepoClassifierTest`, `NvidiaEmbedderTest`,
  `CollectorRetryIT`, `TransientSourceFailureTest`, `ObservedHttpRetryTest`, `JdbcSharedCacheIT` and the Redis adapter tests cover the rules.

## Alternatives considered
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

## Code
- `backend/src/main/java/ai/helper2/platform/resilience/Resilience.java`, `Resilience4jPolicies.java`, `CallRefused.java`,
  `ResilienceConfiguration.java` — the port and its Resilience4j adapter.
- `backend/src/main/java/ai/helper2/platform/resilience/OutboundThrottle.java`, `RedisOutboundThrottle.java` — the
  shared pace of one host.
- `backend/src/main/resources/application.yaml` (`resilience4j.*`) — the policies.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — `source-<id>`, `CIRCUIT_OPEN` and `BUSY`.
- `backend/src/main/java/ai/helper2/sources/*/*Collector.java`, `support/TransientSourceFailure.java` — `collector-<id>` and what
  it retries; `support/HttpClients.java` — transient failures.
- `backend/src/main/java/ai/helper2/search/cache/RedisSearchCache.java`, `guide/internal/RedisGuideAnswerStore.java`,
  `platform/llm/RedisCallCounter.java`, `platform/RedisRateLimiter.java` — `redis`.
- `backend/src/main/java/ai/helper2/platform/cache/SharedCaches.java`, `JdbcSharedCache.java` — `db-cache`.
- `backend/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — one request per attempt.
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java`, `search/internal/SpringAiQueryTranslator.java`,
  `setup/internal/SpringAiToolGuideModel.java`, `scout/internal/SpringAiEnrichmentModel.java`,
  `sources/classifier/SpringAiRepoClassifier.java` — `llm-chat`; `platform/llm/ModelErrors.java` — what counts for it; `catalog/internal/NvidiaEmbedder.java` — `llm-embed`.
- `backend/src/main/java/ai/helper2/guide/web/GuideLimits.java` — `guide`, held with `trySlot`.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2CircuitOpen`, `Helper2CallsNotPermitted`.

## Diagrams
[flow](../../diagrams/flow-0033-resilience-policies.svg) · [sequence](../../diagrams/sequence-0033-resilience-policies.svg)

[flow 0008](../../diagrams/flow-0008-federated-search.svg) (source policy in the fan-out) ·
[sequence 0005](../../diagrams/sequence-0005-redis-disposable-cache.svg) (the `redis` circuit)

## Roadmap
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
