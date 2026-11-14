# 0005 — Redis as a disposable cache

**Depends on:** [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0004](0004-persistence-and-the-schema.md), [0006](0006-outbound-calls-and-resilience.md)

## Context
Live sources are slow (seconds) and rate-limited. The daily model budgets must survive a restart. All of this
is short-lived data: losing it costs time or money,
never correctness. PostgreSQL ([0004](0004-persistence-and-the-schema.md)) stays the only system of record,
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
   metered source (Tavily), whose result sets live in PostgreSQL ([0027](../search/0027-live-web-store-first-daily-quota.md)).
   Key `h2:v1:src:{sourceId}:{hash}`; the hash covers the normalized query, the expansions, the limit and,
   for sources that read it, the guide's web query and the date window. No raw query text is stored in a key. Values use the
   versioned `SourceHitCodec`; an unknown version is a miss.
3. **Fresh and stale windows.** An entry younger than the source's `cacheTtl` (30 minutes by default,
   6 hours for arXiv) answers without a live call. Every entry lives for `H2_SEARCH_STALE_FOR` (24 hours).
   When the live call fails, times out, is rate-limited, is in cooldown or is refused by the source's open circuit,
   an entry inside that window is served as `STALE_CACHE`, with the failure stated in the source status.
4. **Circuit breaker.** Every Redis call of the search cache, the budget counter, the web-search quota, the rate
   limiter, the outbound throttle and the session reads runs under the one `redis` policy ([0006](0006-outbound-calls-and-resilience.md)):
   once half of the last calls (at least 3) failed, the circuit opens for 30 seconds (`H2_RESILIENCE_REDIS_OPEN_FOR`)
   and callers get their fallback at once, without a Redis call. Then 2 trial calls decide whether it closes again.
   A search cache error or refusal adds the notice `CACHE_UNAVAILABLE`, and the sources are called live. A session
   read that fails or is refused finds no session ([0036](../identity/0036-identity-and-the-admin-host.md)).
5. **Daily counters.** `RedisCallCounter` counts model calls per budget and day in Coordinated Universal Time (UTC):
   `h2:v1:budget:{name}:{day}`, kept 2 days. One Lua script runs `INCR` only below the budget's limit and, on the first count,
   `PEXPIRE`, so a counter key is never left without an expiry and a refused call adds nothing. Names: `guide`, `translation`, `enrichment`, `classifier`,
   and `guide-client:{pseudonym}` for the per-client guide quota (a keyed hash of the client and the day, [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)).
   A count given back for an answer with no pick also writes `h2:v1:budget:{name}:{day}:given-back`, a set of random
   question ids that expires with the count, in the same script. On an error, or while the `redis` circuit is open,
   `DailyBudget` counts in memory.
6. **Model answers are not in Redis.** Query translations, query vectors, setup guide answers and guide answers
   ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) are kept on disk in the PostgreSQL table `ai_cache`
   ([0004](0004-persistence-and-the-schema.md)): they are backed up, survive a Redis restart and cost no Redis memory.
7. **Shared state for the replicas.** Prod runs 3 replicas ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)). The same Redis also
   holds the rate-limit windows (`h2:v1:rate:{limiter}:{client}`, [0037](../identity/0037-http-edge.md)), the sign-in
   sessions (namespace `h2:v1:session:public`, [0036](../identity/0036-identity-and-the-admin-host.md)) and the daily web-search counters
   (`h2:v1:quota:*`, [0027](../search/0027-live-web-store-first-daily-quota.md)), so every replica sees them. Rate limits and
   sessions have their own fallback while Redis is down; the web-search quota allows no live call then.

### Code
- `backend/libs/search/src/main/java/ai/helper2/search/cache/RedisSearchCache.java`, `SourceHitCodec.java` — source cache.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — fresh/stale rules, `cacheKey`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/resilience/` (`Resilience`, policy `redis` in `application.yaml`) —
  fail-fast guard ([0006](0006-outbound-calls-and-resilience.md)).
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/RedisCallCounter.java`, `DailyBudget.java` — counters.
- `backend/libs/platform/src/main/java/ai/helper2/platform/quota/RedisQuotaCounter.java` — daily web-search counters.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/GuideCost.kt` (`ClientQuota`) — the per-client guide quota.
- `deploy/helm/helper2/templates/redis.yaml` (StatefulSet, AOF, PVC), `compose.yaml` — Redis settings;
  `deploy/k8s/Makefile` (`chart-check`) fails when the rendered Redis has no AOF or no PVC.

## Alternatives considered
- Caffeine (in-process cache) only — lost on every deploy, and budgets would reset with each restart.
- Source responses in PostgreSQL — a write on every live call of every search, and eviction by hand, for entries
  that are stale after 24 hours.
- One circuit per Redis adapter — each adapter would find the same outage on its own and pay its own timeouts.
- A bypass after any single error, without a failure rate or a trial call — one slow command opens it, and the
  first call after the window meets a Redis that may still be down at full timeout.
- Redis without persistence — every Redis restart drops all budgets, sessions and rate-limit windows.
- RDB snapshots instead of AOF — a restart loses everything since the last snapshot, minutes of counts.

## Consequences
- A Redis outage costs a few failed calls per 30 seconds for the whole replica (the circuit is shared by every
  adapter), not a timeout on every request.
- During an outage, budgets count in memory and never block the model by mistake.
- Source responses, counters, sessions and rate-limit windows survive app restarts, deploys and Redis restarts.
- While Redis loads the AOF at start, the app treats it like a short outage (the `redis` circuit).
- Redis memory holds source responses, counters, rate-limit windows and sessions only.
- `RedisSearchCacheTest`, `RedisCallCounterTest` and `RedisRateLimiterIT` cover the open window, the trial call and
  the fallbacks; `RedisDownIT` covers search with no Redis at all.

### Known limits
- During a Redis outage, budgets count in memory per replica (up to three times what was left of the day on prod).
- Redis is not ready while it loads the append-only file; with `everysec` a crash loses at most the last second.

## Diagrams
[flow](../../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../../diagrams/sequence-0005-redis-disposable-cache.svg)

## Backlog
- Decides: [F1.4](https://github.com/silvioincalza/helper2.ai/issues/23).
- Open issues that change it:
  - [S1.8.3](https://github.com/silvioincalza/helper2.ai/issues/174): a Redis exporter and an alert on evicted keys.
  - [S1.10.3](https://github.com/silvioincalza/helper2.ai/issues/179): sessions and counters kept when the cache fills.
