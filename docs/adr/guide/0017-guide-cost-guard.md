# 0017 — Guide cost guard: per-minute limit, per-client daily quota, global daily cap, answer cache with config hash, single-flight, concurrency limit and deadline

**Status.** Accepted (2026-10-27). **Builds on:** [0005](../foundation/0005-redis-disposable-cache.md), [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md), [0016](0016-guide-reason-check.md)

## Context
A fresh guide question costs 3 model calls and several seconds. The demo is public and free, paid by provider
credits. Without guards one client can spend the whole day's budget, many slow questions can pile up on the server,
and visitors who try the same example question pay for it again and again. The guards must degrade the answer, not
break the page.

## Decision
The guards run in this order. Values are defaults; each has an `H2_` variable.
1. **Per-minute rate limit** (`RateLimitInterceptor`, [0024](../edge/0024-http-edge-protection.md)): 10 questions per minute per
   client (`helper2.rate-limit.guide-per-minute`), one limiter shared by `askGuide` and `askGuideStream`. The client
   is `platform.ClientKey`: the remote address, Internet Protocol version 4 (IPv4) as is, Internet Protocol version 6
   (IPv6) by its /64 (zone id dropped, IPv4-mapped becomes IPv4). The rate limiters and the daily quota use the same
   key. Counted in Redis, so the limit holds across all replicas. Over the limit: 429 with `Retry-After`.
2. **Concurrency limit and deadline** (`GuideLimits`, used by both endpoints): a question runs only with a slot in the
   `guide` bulkhead ([0033](../foundation/0033-resilience-policies.md), `Resilience.trySlot`), at most `H2_GUIDE_MAX_CONCURRENT` (8)
   questions at once per replica (24 on prod with 3 replicas), on virtual threads. One more question gets 503 "Guide busy" at once (an `error` event on the stream).
   After `H2_GUIDE_QUESTION_DEADLINE` (90 s) the client gets 504 "Guide timed out" and the worker is interrupted; the
   slot is free from the deadline on. The question metric records outcome `timeout`. On the stream, a question that
   got a slot receives a `:keep-alive` comment every `H2_GUIDE_HEARTBEAT` (15 s) until its last event
   ([0015](0015-ai-guide-evidence-gate.md)).
3. **Answer cache** (`GuideAnswerCache`): key = request trimmed, lower-cased, spaces collapsed | audience path |
   config hash. The config hash is the first 16 hex characters of a Secure Hash Algorithm (SHA-256) hash over provider, both model ids, the rendered
   prompts, thinking flags, token limits, candidate text size, the validator's limits, the comparison and explanation
   limits (`AnswerLimits`) and `maxCandidates`. A new prompt
   or limit therefore never serves old answers. Memory first (500 entries, least recently used out), then Redis
   (`RedisGuideAnswerStore`, key `h2:v1:guide-answer:<sha256 of the key>`, versioned JSON). Time to live (TTL) `H2_GUIDE_CACHE_TTL`
   (24 h), counted from the answer's `generatedAt`, which the page shows; an answer built on web results lives only
   as long as they are fresh, and one built on stale ones is not cached ([0037](../search/0037-tavily-store-and-daily-quota.md)). Only complete artificial intelligence (AI) answers are cached: model
   used, at least one AI pick, and no AI pick with reason `NOT_CHECKED`. Redis calls run under the `redis` policy ([0033](../foundation/0033-resilience-policies.md)).
4. **Single-flight**: questions in progress are tracked by cache key. A second asker of the same question waits for the
   first answer (outcome `shared`) and shares its failure. On the stream it gets no step events, only the
   keep-alive comments, so its connection does not look dead.
5. **Per-client daily quota** (`ClientQuota`): `H2_GUIDE_CLIENT_DAILY_QUESTIONS` (30) fresh questions per client per day
   (Coordinated Universal Time, UTC), per `ClientKey`. Counted with a `DailyBudget` named
   `guide-client:<16 hex of SHA-256(client)>`, so no address is stored. Past the quota the client gets a search-only
   answer with the notice `GUIDE_DAILY_QUOTA`, not an error. Cached and shared answers do not count. In memory,
   `ClientQuota` keeps only today's clients, at most 10,000 (`MAX_CLIENTS`; the least recently seen leaves first),
   and drops them all when the day changes.
6. **Global daily cap** (`H2_GUIDE_DAILY_MODEL_CALLS`, 600): one unit before each model attempt
   ([0014](0014-llm-integration.md)), taken inside the `llm-chat` circuit, so a call the open circuit refuses costs no
   unit. When spent, plan or choose fails with `GuideBudgetExhausted` and the answer is the degraded one; the reason
   check leaves reasons `NOT_CHECKED`. Gauge `helper2.guide.budget.remaining`.
7. **Daily web searches** ([0037](../search/0037-tavily-store-and-daily-quota.md)): the question's searches take one web search
   from the asker's guide quota (3 a day for a guest), at the first live Tavily call; a cached or shared answer takes
   none. Every answer states what the asker has left (`webSearches`).
8. **Metrics**: `helper2.guide.cache` (hit, miss) and `helper2.guide.questions` with outcome `cached`, `shared`,
   `client_quota`, `answered`, `degraded`, `budget_exhausted`, `model_unavailable`, `no_candidates`, `timeout`, `error`.

## Consequences
- The worst case per day is bounded: 600 model calls, whatever the traffic.
- One client can use at most 30 fresh questions a day (90 calls), so a single client cannot spend the global cap alone.
- Repeated example questions are free and instant; a cached answer is honest about its age.
- Clients behind one shared address (an office, a mobile carrier) share one quota.
- The rate limit and the daily counts (quota, global cap) are in Redis and hold across replicas
  ([0027](../operations/0027-runtime-topology.md): 3 on prod). While Redis is down, each replica counts on its own.
- The in-memory answer cache, the single-flight map and the `guide` bulkhead are per replica. Two replicas may
  answer the same new question once each; the answer in Redis then serves every replica.
- The quota is taken before the question runs, so a question that later fails still counts.
- The quota's memory is bounded (10,000 clients a day). A client that leaves memory keeps its count in Redis; only
  the in-memory fallback count is lost, so while Redis is down such a client can start again from zero.

## Alternatives considered
- Global cap only — one client could spend the day in about twenty minutes at 10 per minute.
- Cache in memory only — a deploy or restart throws away answers that cost 3 calls each.
- Cache without a config hash — a changed prompt or model would serve answers of the old one for 24 hours.
- Error (429) past the quota — the search-only answer is still useful and explains itself.
- Queue extra questions instead of 503 — a queue hides overload and makes every user wait longer.

## Code
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `ClientQuota`, `GuideAnswerCache`, `GuideAnswerStore`.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `AskGuide.ask` (order of the guards, config hash, single-flight).
- `backend/src/main/java/ai/helper2/guide/web/GuideLimits.java` — slot in the `guide` bulkhead and deadline.
- `backend/src/main/kotlin/ai/helper2/guide/AnswerLimits.kt` — comparison and explanation limits in the config hash.
- `backend/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — keep-alive comments.
- `backend/src/main/java/ai/helper2/platform/ClientKey.java` — the client key for the rate limits and the quota.
- `backend/src/main/java/ai/helper2/guide/internal/RedisGuideAnswerStore.java`, `GuideConfiguration.java`.
- `backend/src/main/java/ai/helper2/platform/RateLimitInterceptor.java`, `RateLimiter.java`.

## Diagrams
[flow](../../diagrams/flow-0017-guide-cost-guard.svg) · [sequence](../../diagrams/sequence-0017-guide-cost-guard.svg)

## Roadmap
- [In-memory answer cache, single-flight and concurrency limit stay per replica, on purpose](../../roadmap.md#4-more-than-one-replica)
