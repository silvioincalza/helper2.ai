# 0005 — Redis as a disposable cache: source responses, fresh/stale windows, circuit breaker, counters, guide answers

**Status.** Accepted. **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md), [0033](0033-resilience-policies.md)

## Context
Live sources are slow (seconds) and rate-limited. A guide answer costs three model calls. The daily
model budgets must survive a restart. Guide answers are the most expensive entries, and a cold cache after
every Redis restart would pay for them again. All of this is short-lived data: losing it costs time or money,
never correctness. PostgreSQL ([0004](0004-persistence-and-provenance.md)) stays the only system of record,
so the cache must be optional: when Redis is down, the app still works, only slower.

## Decision
1. **Redis 8.10.2 as a cache with AOF persistence.** Bounded memory (`maxmemory 128mb`, `allkeys-lru` in the
   Helm chart): entries are evicted under pressure, as in any cache. The append-only file keeps the cache across
   restarts: `--appendonly yes`, `--appendfsync everysec`, no RDB snapshots (`--save ""`). In the chart Redis is a
   single-replica StatefulSet with `/data` on a ReadWriteOnce PersistentVolumeClaim (`redis.storage`, 1 Gi;
   `redis.storageClassName`, empty = the cluster default). Redis is never a system of record: losing the volume
   only costs cache misses. Connection: `H2_REDIS_HOST`, `H2_REDIS_PORT`
   (6379), `H2_REDIS_PASSWORD`; command timeout 250 ms, connect timeout 500 ms. The Redis health
   indicator is off, so a Redis outage never takes the app out of the readiness rotation.
2. **Source responses.** `RedisSearchCache` implements the `SearchCache` port for sources in `LIVE` mode, except a
   metered source (Tavily), whose result sets live in PostgreSQL ([0037](../search/0037-tavily-store-and-daily-quota.md)).
   Key `h2:v1:src:{sourceId}:{hash}`; the hash covers the normalized query, the expansions, the limit and,
   for sources that read it, the guide's web query and the date window. No raw query text is stored in a key. Values use the
   versioned `SourceHitCodec`; an unknown version is a miss.
3. **Fresh and stale windows.** An entry younger than the source's `cacheTtl` (30 minutes by default,
   6 hours for arXiv) answers without a live call. Every entry lives for `H2_SEARCH_STALE_FOR` (24 hours).
   When the live call fails, times out, is rate-limited, is in cooldown or is refused by the source's open circuit,
   an entry inside that window is served as `STALE_CACHE`, with the failure stated in the source status.
4. **Circuit breaker.** Every Redis call of the search cache, the guide and tool guide answer stores, the budget
   counter, the web-search quota, the rate limiter, the outbound throttle and the session reads runs under the one
   `redis` policy ([0033](0033-resilience-policies.md)):
   once half of the last calls (at least 3) failed, the circuit opens for 30 seconds (`H2_RESILIENCE_REDIS_OPEN_FOR`)
   and callers get their fallback at once, without a Redis call. Then 2 trial calls decide whether it closes again.
   A search cache error or refusal adds the notice `CACHE_UNAVAILABLE`, and the sources are called live. A session
   read that fails or is refused finds no session ([0023](../edge/0023-identity-and-admin.md)).
5. **Daily counters.** `RedisCallCounter` counts model calls per budget and day in Coordinated Universal Time (UTC):
   `h2:v1:budget:{name}:{day}`, kept 2 days. One Lua script runs `INCR` and, on the first count, `PEXPIRE`, so a
   counter key is never left without an expiry. Names: `guide`, `translation`, `enrichment`, `classifier`,
   and `guide-client:{hash}` for the per-client guide quota. On an error, or while the `redis` circuit is open,
   `DailyBudget` counts in memory.
6. **Guide answers.** `GuideAnswerCache` keeps up to 500 answers in memory and reads through to
   `RedisGuideAnswerStore`: key `h2:v2:guide-answer:{build}:{sha256}`, one set per build ([0017](../guide/0017-guide-cost-guard.md)), Redis time to live (TTL) = `H2_GUIDE_CACHE_TTL` (24 hours),
   JavaScript Object Notation (JSON) in a versioned envelope. Only complete artificial intelligence (AI) answers are stored.
7. **Shared state for the replicas.** Prod runs 3 replicas ([0027](../operations/0027-runtime-topology.md)). The same Redis also
   holds the rate-limit windows (`h2:v1:rate:{limiter}:{client}`, [0024](../edge/0024-http-edge-protection.md)), the sign-in
   sessions (namespace `helper2:session`, [0023](../edge/0023-identity-and-admin.md)) and the daily web-search counters
   (`h2:v1:quota:*`, [0037](../search/0037-tavily-store-and-daily-quota.md)), so every replica sees them. Rate limits and
   sessions have their own fallback while Redis is down; the web-search quota allows no live call then.

## Consequences
- A Redis outage costs a few failed calls per 30 seconds for the whole replica (the circuit is shared by every
  adapter), not a timeout on every request.
- During an outage, budgets count in memory and never block the model by mistake.
- Answers, counters, sessions and rate-limit windows survive app restarts, deploys and Redis restarts; guide answers
  survive restarts of the same build, and a deploy starts a new set (the old one expires with its TTL).
- While Redis loads the AOF at start, the app treats it like a short outage (the `redis` circuit).
- `RedisSearchCacheTest`, `RedisGuideAnswerStoreTest`, `RedisCallCounterTest` and `RedisRateLimiterIT` cover the open
  window, the trial call and the fallbacks; `RedisDownIT` covers search with no Redis at all.

## Alternatives considered
- Caffeine (in-process cache) only — lost on every deploy, and budgets would reset with each restart.
- Cache in PostgreSQL — writes on the hot path to the system of record, and eviction by hand.
- One circuit per Redis adapter — each adapter would find the same outage on its own and pay its own timeouts.
- A bypass after any single error, without a failure rate or a trial call — one slow command opens it, and the
  first call after the window meets a Redis that may still be down at full timeout.
- Redis without persistence — every Redis restart drops all guide answers, budgets, sessions and rate-limit
  windows; refilling the answers costs three model calls each.
- RDB snapshots instead of AOF — a restart loses everything since the last snapshot, minutes of answers and counts.
- Guide answers in PostgreSQL — writes on the hot path to the system of record and eviction by hand, for data that
  is a cache.

## Code
- `backend/src/main/java/ai/helper2/search/cache/RedisSearchCache.java`, `SourceHitCodec.java` — source cache.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — fresh/stale rules, `cacheKey`.
- `backend/src/main/java/ai/helper2/platform/resilience/` (`Resilience`, policy `redis` in `application.yaml`) —
  fail-fast guard ([0033](0033-resilience-policies.md)).
- `backend/src/main/java/ai/helper2/platform/llm/RedisCallCounter.java`, `DailyBudget.java` — counters.
- `backend/src/main/java/ai/helper2/platform/quota/RedisQuotaCounter.java` — daily web-search counters.
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` (`GuideAnswerCache`, `ClientQuota`) and
  `backend/src/main/java/ai/helper2/guide/internal/RedisGuideAnswerStore.java` — guide answers.
- `deploy/helm/helper2/templates/redis.yaml` (StatefulSet, AOF, PVC), `compose.yaml` — Redis settings;
  `deploy/k8s/Makefile` (`chart-check`) fails when the rendered Redis has no AOF or no PVC.

## Diagrams
[flow](../../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../../diagrams/sequence-0005-redis-disposable-cache.svg)

## Roadmap
- A Redis exporter and alerts on cache errors: [roadmap](../../roadmap.md#3-operations).
- Redis memory shared by cache, sessions and rate limits; alert on evictions: [roadmap](../../roadmap.md#4-more-than-one-replica).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
