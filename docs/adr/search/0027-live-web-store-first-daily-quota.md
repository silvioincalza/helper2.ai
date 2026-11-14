# 0027 — Live web: store first, daily quota

**Depends on:** [0005](../foundation/0005-redis-as-a-disposable-cache.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0010](../foundation/0010-data-retention.md), [0022](0022-federated-search.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0036](../identity/0036-identity-and-the-admin-host.md).

## Context

Tavily is the only source that costs money per call: one credit per basic search, 1,000 credits a month on the free
plan, and the Scout uses about 85 of them. Every search and every guide question calls it. One visitor at the
search rate limit can spend the month in under an hour, and many visitors asking the same news question pay for it
again and again. Tavily allows in writing to show its results publicly, to store them and to reuse them across
users. So the same question should cost one credit, and each visitor should get a fair, small share of fresh web
searches per day, for search and for the guide apart. The rest of the search must keep working when the share is
used up. The counts must hold across 3 replicas, store no address and no query text, and survive a restart.

## Decision

**Names.** The product calls this source "Live web" (`live-web`; the Scout's queries `live-web-scout`, the stored result sets `live_web_query_result`, settings `H2_LIVE_WEB_*`). Tavily is the adapter behind it (`sources/tavily/`) and can be replaced; its name stays on the Privacy page, which must name the recipient of the search text, and in this ADR, which records the choice.

1. **Store first.** `live_web_query_result(query_key, fetched_at, result_ids[])` in PostgreSQL. The key
   is HMAC-SHA256(`H2_QUOTA_KEY_SECRET`, source id and the normalised query: words, expansions, limit, web query and
   date window, the same material as the source cache key of [0005](../foundation/0005-redis-as-a-disposable-cache.md)); exact match
   only. The key is what changes the Tavily request, so the intent class is not part of it: the timeline view
   (newest first, time-bound) of a search just made reads the same set and spends no second credit. A query has one
   stored set; a live answer upserts it by key, and an older fetch that ends later never overwrites a newer set. `result_ids` are the resource ids of the hits' own URLs, in Tavily's order; the hits come back from the
   catalog's source records (`ResultObserver.recordedHits`), which the live search writes as it writes every live hit
   ([0004](../foundation/0004-persistence-and-the-schema.md)). A set with a hit the catalog does not hold is a miss. The search
   module owns the table (`JdbcWebResultStore`); a metered source never uses the Redis source cache.
2. **Freshness by intent.** A stored set answers without a live call and without quota while it is fresh: 24 h for a
   time-bound question (news, courses, hackathons, conferences, events; `H2_LIVE_WEB_FRESH_TIMEBOUND`), 48 h otherwise
   (`H2_LIVE_WEB_FRESH_GENERAL`). The guide states the class from its intent (`NEWEST`, `WHEN`, `LEARN` are
   time-bound); a search reads it from its words (punctuation removed, so "AI news?" is time-bound; a hyphenated word stays
   one word, so "event-driven" is general), type filter,
   date window or newest-first order (`IntentClass`). The class of the asking search decides whether a stored set is
   fresh, whatever class fetched it.
   Served as `CACHE`. A live answer overwrites the set (and its rows in the catalog get a new `last_seen_at`).
3. **Daily web searches per feature, the same for every visitor.** Before a live call `WebSearchQuota` takes one
   web search for the asker: 3 a day for search and 3 for the guide. Every visitor, signed in or not, is counted under
   `h2:v1:quota:guest:<HMAC-SHA256(day key, client)>` (a Redis hash, field = feature), day key = HMAC-SHA256(secret,
   UTC date). An account never changes the limits or the counter. The client is `ClientKey` (IPv6 by /64,
   [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)); no address is stored and the keys of two days cannot be linked without the secret. A key expires 24 h after its first count. One user action is one web
   search: a guide question that searches several times takes it at its first live call. A web search that gave the
   visitor no live answer is given back (`WebSearchQuota.giveBack`): the call failed, timed out or was refused by the
   `source-live-web` policy, and no other live call of the same action answered. An action that searches several
   times at once (a comparison) is settled when its last live call comes back, so a call that fails first does not
   give back the web search a slower call then answers with. The same question can then be asked
   again; a guide question takes its web search at the first live call that answers. An empty answer is an answer and
   counts. When Redis does not take the give-back, the web search stays taken and the action keeps it: its next live
   call takes no second one and, if that call gives no answer either, gives it back. Over the limit the source
   is `SKIPPED` (`SkipReason.DAILY_LIMIT`, "Daily limit of web searches reached; back at 00:00 UTC"), with its stored
   set when it is not older than 7 days (`STALE_CACHE`); the other sources answer as usual.
   The limits are `H2_LIVE_WEB_GUEST_SEARCH` and `H2_LIVE_WEB_GUEST_GUIDE` (default 3). The chart sets them, with
   the guide's questions per client and the global cap, only where `app.dailyLimits` sets them: staging raises the
   per-visitor limits to 1000, so a test is never cut off by earlier tests, and keeps a global cap of 200 live calls
   a day, because staging is public. Prod keeps the defaults.
4. **Credits of the day, optional global cap.** Every live call (also the later calls of one guide question and the
   calls of internal callers) counts on `h2:v1:quota:global:<UTC date>`, field `credits`: the credits of the day
   for the whole cluster. The counter is capped by `H2_LIVE_WEB_DAILY_CREDITS`; unset, the cap is the code
   maximum of the runtime setting, 2,000 a day ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)). The own and the global counter are checked and counted in **one Lua script** (`RedisQuotaCounter`), so
   concurrent requests on any replica never pass a limit. The script counts before the `source-live-web` policy is
   asked, so a live call that policy refuses (circuit open, bulkhead full) gives its credit back
   (`WebSearchQuota.refund`): the call never left. A call that reached Tavily keeps its credit also when it fails
   or times out, because Tavily may have charged it. A source paused after a rate limit is skipped before the count
   and takes nothing. Scout runs do not count here.
5. **Fail closed.** When Redis cannot answer or the `redis` policy refuses the call ([0006](../foundation/0006-outbound-calls-and-resilience.md)),
   no live Tavily call goes out (`SkipReason.QUOTA_UNAVAILABLE`, "Web search paused: the daily limit cannot be
   checked"); stored sets still answer. Credits are the only cost here that grows with traffic, and during a Redis
   outage the per-client rate limits count per replica ([0037](../identity/0037-http-edge.md)), so failing open would
   leave the monthly credits unguarded exactly when abuse is easiest. Everything else keeps answering. The arXiv pace
   fails closed for the same reason ([0006](../foundation/0006-outbound-calls-and-resilience.md)).
6. **What is left, in every answer.** `SearchResponse.webSearches` and `GuideResponse.webSearches`
   (`{limit, remaining}`, null without an enabled metered source or a readable count) show the visitor's web searches
   left today for that feature. A cached guide answer, or one reused for a similar question
   ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)), takes no web search and gets the current asker's count, never the first asker's.
   The page says the count from the first web search used. A limit of 100 or more is a test ceiling (staging), so the
   page says only when none is left.
   `GET /api/v1/sources` shows the credits of the day and the cap on the metered source
   (`webCredits {usedToday, dailyCap}`, `WebSearchQuota.creditsToday`, [0022](0022-federated-search.md)): one read, no count.
7. **Guide answers live no longer than their web results.** Guide answers are kept in PostgreSQL
   (the shared cache `ai_cache`, [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)). Answer TTL = min(24 h, freshness left of the web
   results it used); an answer built on stale web results is not cached ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)).
8. **Credits are measured.** `helper2.live-web.credits{feature}` counts every live call (`none` for internal
   callers). Alert `Helper2LiveWebCreditsHigh` when more than 200 credits were spent in 24 h: 80% of prod's
   daily cap (250, `app.dailyLimits.webSearchCredits`). The plan has 14,000 credits a month for prod, staging (cap
   180 a day) and the Scout (~506 a month); a 31-day month at both caps stays about 80 under it. The Scout's
   discovery and LinkedIn searches count on the global daily counter and in the alert too (features `scout-discovery`
   and `scout-linkedin`, at most 40 a day together, [0013](../scout/0013-discovery.md),
   [0012](../scout/0012-scout-collectors-and-events.md)).
9. **Retention** ([0010](../foundation/0010-data-retention.md)): stored sets are deleted 7 days after `fetched_at`
   (`H2_RETENTION_LIVE_WEB_RESULTS`) by the search module's daily purge; counters by their 24 h TTL. No counter is
   kept under an account, so deleting one touches none.
10. **No secret, no start.** An enabled metered source without `H2_QUOTA_KEY_SECRET` (OpenBao) stops startup.

### Code

- `backend/libs/core/src/main/kotlin/ai/helper2/search/WebSearch.kt` — `Asker`, `IntentClass`, `WebFreshness`, `WebSearchQuota`, `WebCredits`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/usecase/SourceFanOut.kt` — store first, quota (`Run.quotaAllows`, `Run.settleWebSearches`); `SearchResources.kt` — `webSearchesLeft`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/Ports.kt` — `WebResultStore`, `WebResultRetention`, `ResultObserver.recordedHits`.
- `backend/libs/core/src/main/java/ai/helper2/search/internal/JdbcWebResultStore.java`, `catalog/internal/CatalogResultObserver.java` (`recordedHits`).
- `backend/libs/core/src/main/kotlin/ai/helper2/search/usecase/PurgeExpiredData.kt`, `search/config/SearchRetentionSchedule.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/quota/` — `QuotaCounter`, `RedisQuotaCounter` (Lua), `QuotaKeys` (HMAC).
- `backend/libs/core/src/main/java/ai/helper2/sources/tavily/TavilySource.java` — the metered descriptor.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` (`find`, `forAsker`), `guide/GuideCost.kt` (`ttlOf`).
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` (`helper2.search.web.*`), `db/migration/V1__schema.sql`, `api/common.yaml` (`WebSearches`).
- `deploy/monitoring/helper2-rules.yaml` — `Helper2LiveWebCreditsHigh`.

## Alternatives considered

- Keep Tavily in the Redis source cache only — 30 minutes fresh, lost under memory pressure, and no quota: every
  visitor pays again for the same question.
- Store the hits themselves in the table — a second copy of what the catalog's source records already hold.
- A plain SHA-256 of the query as key — short queries can be found again by hashing guesses; the HMAC needs the secret.
- Count in memory per replica while Redis is down, as the model budgets do — three times the limit with 3 replicas,
  and a map of client keys in memory, for an outage that lasts seconds.
- A global cap only — one visitor could spend the whole day's credits; per-visitor quotas with an optional cap share them fairly.
- Near-duplicate reuse by embeddings — more hits, but wrong answers when two questions only look alike.

## Consequences

- A question asked by many visitors costs one credit per day or two; stored answers are free and instant for
  everyone.
- One visitor spends at most 3 + 3 credits a day on answered web searches. A call that fails or times out costs a
  credit and no web search, so while Tavily fails a visitor can spend more: the `source-live-web` circuit, the search
  rate limit and the global cap bound it. The alert and the switchable cap bound the rest without a release.
- No address and no query text are stored for this; the counters are gone after a day.
- Every live call costs one Redis script; a guide question that calls the web three times runs it three times.
- Security (STRIDE): the counter key comes from the client address only, never from the request body or the session.
  The credits of the day and the cap are public on the Sources page: they tell an abuser how much is left, which a visitor over the cap learns
  anyway, and the cap still holds. The HMAC secret lives in
  OpenBao, so day keys and stored query keys cannot be tested against guesses; rotating it only resets the day's
  counters and the stored sets. A Redis outage stops fresh web results, not the search.
- AI architecture check: covered are Tool & API (metered source behind a quota), Data (one table, retention), Cost &
  Reliability (quota, cap, alert, fail closed), Observability (credits meter), Security & Identity (HMAC keys;
  sign-in changes nothing). Evaluation of whether near-duplicate queries could share a set is deferred (exact
  match until data shows it pays). The other layers are not touched.
- `WebSearchQuotaTest`, `IntentClassTest`, `MeteredSourceTest`, `AskGuideWebSearchTest`, `QuotaKeysTest`, `ListSourcesTest`,
  `RedisQuotaCounterIT`, `JdbcWebResultStoreIT`, `WebSearchQuotaApiIT` and the promtool tests cover the rules.

### Known limits

- Accepted risk (owner): rotating addresses can drain the monthly Tavily credits while no cap is set; searches then answer from stored sets and other sources. Failing closed loses fresh web results for a Redis outage.
- A live web call that times out is cancelled and its late answer is not stored: the credit stays counted, the visitor's web search is given back, and the next asker of the same question pays a credit again. A call cancelled before it reached the provider still keeps its credit. A stored hit is read back with two short queries; a set not yet stored is a miss.

### Open points

- Scout news and events are not written to the same store, and have no share of the credits of their own.

## Diagrams

[flow](../../diagrams/flow-0027-tavily-store-and-quota.svg) · [sequence](../../diagrams/sequence-0027-tavily-store-and-quota.svg)

## Backlog

- Features: [F3.6](https://github.com/silvioincalza/helper2.ai/issues/52) Live web: store first, daily quota per feature.
- Open issues that change this decision:
  - [#293](https://github.com/silvioincalza/helper2.ai/issues/293) S5.5.1 10 web searches and 10 AI answers a day instead of 3
