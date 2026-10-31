# 0037 — Tavily: store first, per-feature daily quota

**Status.** Accepted. **Builds on:** [0005](../foundation/0005-redis-disposable-cache.md), [0008](0008-federated-search.md),
[0017](../guide/0017-guide-cost-guard.md), [0023](../edge/0023-identity-and-admin.md), [0033](../foundation/0033-resilience-policies.md),
[0034](../operations/0034-data-retention.md)

## Context
Tavily is the only source that costs money per call: one credit per basic search, 1,000 credits a month on the free
plan, and the Scout uses about 85 of them. Every search and every guide question calls it. One visitor at the
search rate limit can spend the month in under an hour, and many visitors asking the same news question pay for it
again and again. Tavily allows in writing to show its results publicly, to store them and to reuse them across
users. So the same question should cost one credit, and each visitor should get a fair, small share of fresh web
searches per day, for search and for the guide apart. The rest of the search must keep working when the share is
used up. The counts must hold across 3 replicas, store no address and no query text, and survive a restart.

## Decision
1. **Store first.** `tavily_query_result(query_key, intent_class, fetched_at, result_ids[])` in PostgreSQL. The key
   is HMAC-SHA256(`H2_QUOTA_KEY_SECRET`, source id and the normalised query: words, expansions, limit, web query and
   date window, the same material as the source cache key of [0005](../foundation/0005-redis-disposable-cache.md)); exact match
   only. `result_ids` are the resource ids of the hits' own URLs, in Tavily's order; the hits come back from the
   catalog's source records (`ResultObserver.recordedHits`), which the live search writes as it writes every live hit
   ([0004](../foundation/0004-persistence-and-provenance.md)). A set with a hit the catalog does not hold is a miss. The search
   module owns the table (`JdbcWebResultStore`); a metered source never uses the Redis source cache.
2. **Freshness by intent.** A stored set answers without a live call and without quota while it is fresh: 24 h for a
   time-bound question (news, courses, hackathons, conferences, events; `H2_TAVILY_FRESH_TIMEBOUND`), 48 h otherwise
   (`H2_TAVILY_FRESH_GENERAL`). The guide states the class from its intent (`NEWEST`, `WHEN`, `LEARN` are
   time-bound); a search reads it from its words, type filter, date window or newest-first order (`IntentClass`).
   Served as `CACHE`. A live answer replaces the set (and its rows in the catalog get a new `last_seen_at`).
3. **Daily web searches per feature.** Before a live call `WebSearchQuota` takes one web search for the asker:

   | Tier | Search / day | Guide / day | Counter (Redis hash, field = feature) |
   |---|---|---|---|
   | guest | 3 | 3 | `h2:v1:quota:guest:<HMAC-SHA256(day key, client)>`, day key = HMAC-SHA256(secret, UTC date) |
   | registered | 10 | 10 | `h2:v1:quota:user:<user id>:<UTC date>`, only with `H2_TAVILY_REGISTERED_TIER_ENABLED` |

   The client is `ClientKey` (IPv6 by /64, [0017](../guide/0017-guide-cost-guard.md)); no address is stored and the keys of two
   days cannot be linked without the secret. A key expires 24 h after its first count. One user action is one web
   search: a guide question that searches several times takes it at its first live call. Over the limit the source
   is `SKIPPED` (`SkipReason.DAILY_LIMIT`, "Daily limit of web searches reached; back at 00:00 UTC"), with its stored
   set when it is not older than 7 days (`STALE_CACHE`); the other sources answer as usual.
   The guest limits are `H2_TAVILY_GUEST_SEARCH` and `H2_TAVILY_GUEST_GUIDE` (default 3). The chart sets them, with
   the guide's questions per client and the global cap, only where `app.dailyLimits` sets them: staging raises the
   per-visitor limits to 1000, so a test is never cut off by earlier tests, and keeps a global cap of 200 live calls
   a day, because staging is public. Prod keeps the defaults.
4. **Credits of the day, optional global cap.** Every live call (also the later calls of one guide question and the
   calls of internal callers) counts on `h2:v1:quota:global:<UTC date>`, field `credits`: the credits of the day
   for the whole cluster. With `H2_TAVILY_GLOBAL_DAILY_CREDITS` set, the counter is capped; unset (the default) means
   no cap. The own and the global counter are checked and counted in **one Lua script** (`RedisQuotaCounter`), so
   concurrent requests on any replica never pass a limit. The script counts before the `source-tavily` policy is
   asked, so a live call that policy refuses (circuit open, bulkhead full) gives its credit back
   (`WebSearchQuota.refund`); the asker's own web search stays taken. Scout runs do not count here.
5. **Fail closed.** When Redis cannot answer or the `redis` policy refuses the call ([0033](../foundation/0033-resilience-policies.md)),
   no live Tavily call goes out (`SkipReason.QUOTA_UNAVAILABLE`, "Web search paused: the daily limit cannot be
   checked"); stored sets still answer. Credits are the only cost here that grows with traffic, and during a Redis
   outage the per-client rate limits count per replica ([0024](../edge/0024-http-edge-protection.md)), so failing open would
   leave the monthly credits unguarded exactly when abuse is easiest. Everything else keeps answering. The arXiv pace
   fails closed for the same reason ([0006](../foundation/0006-outbound-http.md)).
6. **What is left, in every answer.** `SearchResponse.webSearches` and `GuideResponse.webSearches`
   (`{limit, remaining}`, null without an enabled metered source or a readable count) show the visitor's web searches
   left today for that feature. A cached guide answer gets the current asker's count, never the first asker's.
   `GET /api/v1/sources` shows the credits of the day and the cap on the metered source
   (`webCredits {usedToday, dailyCap}`, `WebSearchQuota.creditsToday`, [0008](0008-federated-search.md)): one read, no count.
7. **Guide answers live no longer than their web results.** Answer TTL = min(24 h, freshness left of the web results
   it used); an answer built on stale web results is not cached ([0017](../guide/0017-guide-cost-guard.md)).
8. **Credits are measured.** `helper2.tavily.credits{feature,tier}` counts every live call (`none` for internal
   callers). Alert `Helper2TavilyCreditsHigh` when more than 30 credits were spent in 24 h, the share the free plan
   leaves after the Scout.
9. **Retention** ([0034](../operations/0034-data-retention.md)): stored sets are deleted 7 days after `fetched_at`
   (`H2_RETENTION_TAVILY_RESULTS`) by the search module's daily purge; counters by their 24 h TTL. `DeleteMyAccount`
   deletes the user's counters with the account.
10. **No secret, no start.** An enabled metered source without `H2_QUOTA_KEY_SECRET` (OpenBao) stops startup.

## Consequences
- A question asked by many visitors costs one credit per day or two; stored answers are free and instant for
  everyone.
- One visitor spends at most 3 + 3 credits a day; the alert and the switchable cap bound the rest without a release.
- No address and no query text are stored for this; the counters are gone after a day.
- Every live call costs one Redis script; a guide question that calls the web three times runs it three times.
- Security (STRIDE): the user id comes from the session only, never from the request. The credits of the day and
  the cap are public on the Sources page: they tell an abuser how much is left, which a visitor over the cap learns
  anyway, and the cap still holds. The HMAC secret lives in
  OpenBao, so day keys and stored query keys cannot be tested against guesses; rotating it only resets the day's
  counters and the stored sets. A Redis outage stops fresh web results, not the search.
- AI architecture check: covered are Tool & API (metered source behind a quota), Data (one table, retention), Cost &
  Reliability (quota, cap, alert, fail closed), Observability (credits meter), Security & Identity (HMAC keys,
  registered tier behind sign-in). Evaluation of whether near-duplicate queries could share a set is deferred (exact
  match until data shows it pays). The other layers are not touched.
- `WebSearchQuotaTest`, `IntentClassTest`, `MeteredSourceTest`, `AskGuideWebSearchTest`, `QuotaKeysTest`, `ListSourcesTest`,
  `RedisQuotaCounterIT`, `JdbcWebResultStoreIT`, `WebSearchQuotaApiIT`, `AccountApiIT` and the promtool tests cover the rules.

## Alternatives considered
- Keep Tavily in the Redis source cache only — 30 minutes fresh, lost under memory pressure, and no quota: every
  visitor pays again for the same question.
- Store the hits themselves in the table — a second copy of what the catalog's source records already hold.
- A plain SHA-256 of the query as key — short queries can be found again by hashing guesses; the HMAC needs the secret.
- Count in memory per replica while Redis is down, as the model budgets do — three times the limit with 3 replicas,
  and a map of client keys in memory, for an outage that lasts seconds.
- A global cap only — one visitor could spend the whole day's credits; per-visitor quotas with an optional cap share them fairly.
- Near-duplicate reuse by embeddings — more hits, but wrong answers when two questions only look alike.

## Code
- `backend/src/main/kotlin/ai/helper2/search/WebSearch.kt` — `Asker`, `IntentClass`, `WebFreshness`, `WebSearchQuota`, `WebCredits`.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — store first, quota, `webSearchesLeft`.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt` — `WebResultStore`, `WebResultRetention`, `ResultObserver.recordedHits`.
- `backend/src/main/java/ai/helper2/search/internal/JdbcWebResultStore.java`, `catalog/ResourceCatalog.java` (`recordedHits`).
- `backend/src/main/kotlin/ai/helper2/search/usecase/PurgeExpiredData.kt`, `search/config/SearchRetentionSchedule.java`.
- `backend/src/main/java/ai/helper2/platform/quota/` — `QuotaCounter`, `RedisQuotaCounter` (Lua), `QuotaKeys` (HMAC).
- `backend/src/main/java/ai/helper2/sources/tavily/TavilySource.java` — the metered descriptor.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` (`find`, `forAsker`), `guide/GuideCost.kt` (`ttlOf`).
- `backend/src/main/java/ai/helper2/identity/usecase/DeleteMyAccount.java` — counters deleted with the account.
- `backend/src/main/resources/application.yaml` (`helper2.tavily.*`), `db/migration/V1__schema.sql`, `api/openapi.yaml` (`WebSearches`).
- `deploy/monitoring/helper2-rules.yaml` — `Helper2TavilyCreditsHigh`.

## Diagrams
[flow](../../diagrams/flow-0037-tavily-store-and-quota.svg) · [sequence](../../diagrams/sequence-0037-tavily-store-and-quota.svg)

## Roadmap
- The page shows the web searches left and the daily limit state (FE-2): [roadmap](../../roadmap.md#5-ai-quality-and-cost).
- Turn on the registered tier once end-user sign-in is live: [roadmap](../../roadmap.md#domain-and-identity).
- Scout news and events written to the same store, with their own share of credits: [roadmap](../../roadmap.md#5-ai-quality-and-cost).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
