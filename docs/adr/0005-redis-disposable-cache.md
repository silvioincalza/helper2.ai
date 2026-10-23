# 0005 — Redis as a disposable cache: source responses, fresh/stale windows, circuit breaker, counters, guide answers

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md)

## Context
Live sources are slow (seconds) and rate-limited. A guide answer costs three model calls. The daily
model budgets must survive a restart. All of this is short-lived data: losing it costs time or money,
never correctness. PostgreSQL ([0004](0004-persistence-and-provenance.md)) stays the only store of truth,
so the cache must be optional: when Redis is down, the app still works, only slower.

## Decision
1. **Redis 8.10.2 as a cache only.** No persistence (`--save ""`, `--appendonly no`), bounded memory
   (`maxmemory 128mb`, `allkeys-lru` in the Helm chart). Connection: `H2_REDIS_HOST`, `H2_REDIS_PORT`
   (6379), `H2_REDIS_PASSWORD`; command timeout 250 ms, connect timeout 500 ms. The Redis health
   indicator is off, so a Redis outage never takes the app out of the readiness rotation.
2. **Source responses.** `RedisSearchCache` implements the `SearchCache` port for sources in `LIVE` mode.
   Key `h2:v1:src:{sourceId}:{hash}`; the hash covers the normalized query, the expansions, the limit and,
   for sources that read it, the guide's web query. No raw query text is stored in a key. Values use the
   versioned `SourceHitCodec`; an unknown version is a miss.
3. **Fresh and stale windows.** An entry younger than the source's `cacheTtl` (30 minutes by default,
   6 hours for arXiv) answers without a live call. Every entry lives for `H2_SEARCH_STALE_FOR` (24 hours).
   When the live call fails, times out, is rate-limited or is in cooldown, an entry inside that window is
   served as `STALE_CACHE`, with the failure stated in the source status.
4. **Circuit breaker.** `platform.CircuitBreaker` opens for 30 seconds after any Redis error. Each Redis adapter
   (search cache, guide answer store, budget counter) has its own instance. While open, callers skip Redis. There is no half-open state: the first call after 30 seconds tries again.
   A search cache error adds the notice `CACHE_UNAVAILABLE`, and the sources are called live.
5. **Daily counters.** `RedisCallCounter` counts model calls per budget and day in Coordinated Universal Time (UTC):
   `h2:v1:budget:{name}:{day}`, kept 2 days. One Lua script runs `INCR` and, on the first count, `PEXPIRE`, so a
   counter key is never left without an expiry. Names: `guide`, `translation`, `enrichment`, `classifier`,
   and `guide-client:{hash}` for the per-client guide quota. On an error its `CircuitBreaker` opens for 30 seconds
   and `DailyBudget` counts in memory.
6. **Guide answers.** `GuideAnswerCache` keeps up to 500 answers in memory and reads through to
   `RedisGuideAnswerStore`: key `h2:v1:guide-answer:{sha256}`, Redis time to live (TTL) = `H2_GUIDE_CACHE_TTL` (24 hours),
   JavaScript Object Notation (JSON) in a versioned envelope. Only complete artificial intelligence (AI) answers are stored.
7. **Shared state for the replicas.** Prod runs 3 replicas ([0027](0027-runtime-topology.md)). The same Redis also
   holds the rate-limit windows (`h2:v1:rate:{limiter}:{client}`, [0024](0024-http-edge-protection.md)) and the
   sign-in sessions (namespace `helper2:session`, [0023](0023-identity-and-admin.md)), so every replica sees them.
   Both have their own fallback while Redis is down.

## Consequences
- A Redis outage costs at most one failed call per 30 seconds per adapter, not a timeout on every request.
- During an outage, budgets count in memory, in each replica on its own. They can only allow more calls, never
  block the model by mistake: with 3 replicas, up to three times what was left of the day. A restart during an
  outage resets that replica's count.
- Answers and counters survive app restarts and deploys, but not a Redis restart; that is accepted.
- Source cooldowns, the guide's in-memory answer cache and its single-flight map stay in each replica's memory, on
  purpose: a replica that misses them costs one more call, not a wrong answer ([roadmap](../roadmap.md#4-more-than-one-replica)).
- Redis holds sessions and rate-limit windows next to cache entries, in the same 128 MB with `allkeys-lru`.
  Under memory pressure an admin can be signed out, or a client can get a fresh window early.
- `CircuitBreakerTest`, `RedisSearchCacheTest` and `RedisGuideAnswerStoreTest` cover the open window and the
  fallbacks.

## Alternatives considered
- Caffeine (in-process cache) only — lost on every deploy, and budgets would reset with each restart.
- Cache in PostgreSQL — writes on the hot path to the system of record, and eviction by hand.
- Resilience4j circuit breaker — a full library for one rule (open for 30 seconds, then retry).
- Redis with persistence — the data is disposable; persistence adds disk and recovery work for nothing.

## Code
- `backend/src/main/java/ai/helper2/search/cache/RedisSearchCache.java`, `SourceHitCodec.java` — source cache.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — fresh/stale rules, `cacheKey`.
- `backend/src/main/java/ai/helper2/platform/CircuitBreaker.java` — fail-fast guard.
- `backend/src/main/java/ai/helper2/platform/llm/RedisCallCounter.java`, `DailyBudget.java` — counters.
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` (`GuideAnswerCache`, `ClientQuota`) and
  `backend/src/main/java/ai/helper2/guide/internal/RedisGuideAnswerStore.java` — guide answers.
- `deploy/helm/helper2/templates/redis.yaml`, `compose.yaml` — Redis settings.

## Diagrams
[flow](../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../diagrams/sequence-0005-redis-disposable-cache.svg)

## Roadmap
- A Redis exporter and alerts on cache errors: [roadmap](../roadmap.md#3-operations).
- Redis memory shared by cache, sessions and rate limits; alert on evictions: [roadmap](../roadmap.md#4-more-than-one-replica).
