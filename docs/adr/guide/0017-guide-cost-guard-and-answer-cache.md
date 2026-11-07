# 0017 — Guide cost guard and answer cache

**Status.** Accepted. Merges old 0017, 0041 (texts merged in PR 6 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0017"></a>

## Part: was ADR 0017 — Guide cost guard: per-minute limit, per-client daily quota, global daily cap, answer cache with config hash, single-flight, concurrency limit and deadline

**Status.** Accepted. **Builds on:** [0005](../foundation/0005-redis-disposable-cache.md), [0008](../foundation/0008-llm-integration.md), [0016](0016-guide-pipeline.md)

### Context
A fresh guide question costs 3 model calls and several seconds. The demo is public and free, paid by provider
credits. Without guards one client can spend the whole day's budget, many slow questions can pile up on the server,
and visitors who try the same example question pay for it again and again. The guards must degrade the answer, not
break the page.

### Decision
**When the guide runs: the user chooses.** Enter always searches, whatever the length of the text. The guide runs only
when the user asks for it: the "Ask the guide" button next to Search, for any length, or Shift+Enter; "Ask the guide
instead" on an empty result asks too. No word count starts
the guide, so a name search ("pgvector", or a long product name) spends no model call. The choice lives in the one box
([0027](../frontend/0027-frontend-one-box.md), its flow diagram); the server answers every question it gets, behind
the guards below. Where the guide answers, the page shows what is left today ("2 of 3 AI answers left today",
[0028](../frontend/0028-design-system.md)).

The guards run in this order. Values are defaults; each has an `H2_` variable.
1. **Per-minute rate limit** (`RateLimitInterceptor`, [0025](../edge/0025-http-edge-protection.md)): 10 questions per minute per
   client (`helper2.rate-limit.guide-per-minute`), one limiter shared by `askGuideStream` and the tool guide `askResourceGuide`. The client
   is `platform.ClientKey`: the remote address, Internet Protocol version 4 (IPv4) as is, Internet Protocol version 6
   (IPv6) by its /64 (zone id dropped, IPv4-mapped becomes IPv4). The rate limiters and the daily quota use the same
   key. Counted in Redis, so the limit holds across all replicas. Over the limit: 429 with `Retry-After`.
2. **Concurrency limit and deadline** (`GuideLimits`, used by the guide stream): a question runs only with a slot in the
   `guide` bulkhead ([0006](../foundation/0006-outbound-calls-and-resilience.md), `Resilience.trySlot`), at most `H2_GUIDE_MAX_CONCURRENT` (8)
   questions at once per replica (24 on prod with 3 replicas), on virtual threads. One more question gets 503 "Guide busy" at once (an `error` event on the stream).
   After `H2_GUIDE_QUESTION_DEADLINE` (90 s) the client gets 504 "Guide timed out" and the worker is interrupted; the
   slot is free from the deadline on. The question metric records outcome `timeout`. On the stream, a question that
   got a slot receives a `:keep-alive` comment every `H2_GUIDE_HEARTBEAT` (15 s) until its last event
   ([0016](0016-guide-pipeline.md)).
3. **Answer cache** (`GuideAnswerCache`, [the part that was ADR 0041](#part-0041)): key = SHA-256 of the normalised
   question | audience path | config hash | clarification | removed parts. The config hash is the first 16 hex
   characters of a Secure Hash Algorithm (SHA-256) hash over provider, both model ids, the rendered prompts, thinking
   flags, token limits, candidate text size, the validator's limits, the comparison and explanation limits
   (`AnswerLimits`) and `maxCandidates`. A new prompt or limit therefore never serves old answers. Answers live in
   the shared cache `guide-answer` in PostgreSQL (`ai_cache`, `SharedGuideAnswerStore`, versioned JSON), shared by
   every replica, per build
   (`H2_BUILD_VERSION`, the image tag the chart passes): a restart keeps the answers, a deploy starts empty, so a
   change in how the guide reads or answers is seen at once and never hidden for 24 h by answers of the old code.
   Time to live (TTL) `H2_GUIDE_CACHE_TTL` (24 h), counted from the answer's `generatedAt`, which the page shows; an
   answer built on web results lives only as long as they are fresh, and one built on stale ones is not cached
   ([0014](../search/0014-tavily-store-and-daily-quota.md)). Only complete artificial intelligence (AI) answers are
   cached: model used, at least one AI pick, and no AI pick with reason `NOT_CHECKED`. With
   `H2_GUIDE_SIMILAR_THRESHOLD` set, a question that misses the key is planned and then answered with the cached
   answer of a near-identical question read the same way ([the part that was ADR 0041](#part-0041) §3).
4. **Single-flight**: questions in progress are tracked by cache key. A second asker of the same question waits for the
   first answer (outcome `shared`) and shares its failure. On the stream it gets no step events, only the
   keep-alive comments, so its connection does not look dead.
5. **Per-client daily quota** (`ClientQuota`): `H2_GUIDE_CLIENT_DAILY_QUESTIONS` (30) fresh questions per client per day
   (Coordinated Universal Time, UTC), per `ClientKey`. Counted with a `DailyBudget` named
   `guide-client:<QuotaKeys.client(client)>`: the client's keyed pseudonym of the UTC day, as for the rate limits
   ([0025](../edge/0025-http-edge-protection.md)), so no address is stored and the name cannot be turned back into an
   address without `H2_QUOTA_KEY_SECRET`. The pseudonym changes at midnight UTC, when the quota starts afresh anyway. Past the quota the client gets a search-only
   answer with the notice `GUIDE_DAILY_QUOTA`, not an error. Cached and shared answers do not count; a question answered with a similar question's answer
   counts, because its plan ran ([the part that was ADR 0041](#part-0041)).
   **An answer with no pick, or a question that fails, does not count.** `ClientQuota.reserve` takes the question
   before the model runs, so abuse stays bounded. `AskGuide.ask` settles the reservation in one `finally`, so every
   exit is covered exactly once, and gives the question back in three cases:
   - **no pick:** the answer has nothing from the AI (`ClientQuota.spendsAQuestion`: no AI pick, no comparison row,
     no explanation sentence; a comparison whose every cell the quote gate refused is as empty as none). That covers "Nothing clearly fits your need", no candidates, subject not found, a model
     call that throws or a spent budget (both give a degraded answer), and a question back (`NEEDS_CLARIFICATION`);
   - **failed:** no answer at all: an error escapes `ask` (the stream ends with an `error` event) or the deadline
     interrupts the question (504). The interrupt flag is cleared for the give-back and set again after it;
   - **shared:** an identical question started a moment earlier and its answer is shared, as from the cache.

   A closed connection is not a failure: the question runs on and its answer is cached, so it settles like any
   answer (a pick counts, no pick is given back), and asking again is a free cache hit. A disconnect after the model
   answered is therefore never given back.

   A reservation has a random id. One Lua script in Redis (`RedisCallCounter`, `GIVE_BACK`) gives it back
   atomically: only when the id is not yet in `h2:v1:budget:guide-client:<pseudonym>:<day>:given-back`, the set holds
   fewer ids than `H2_GUIDE_CLIENT_DAILY_FREE_ANSWERS` (default: the daily questions, 30) and the count is above 0;
   then it runs `DECR` on the count and `SADD` of the id, and the set expires after 2 days like the count. So a
   retry, a second completion or a failure after a no-pick give-back gives nothing back twice, on any replica; the
   count never goes below 0; and no-pick answers, failures and shared answers share one cap, so a client cannot loop
   them for free: past the cap they count like any other. A refused question adds nothing to the count: the `INCR`
   script checks the limit first, in the same step, so a search-only question past the quota never takes the
   question a give-back frees. Without Redis, `DailyBudget` applies the same rules in memory. Metric `helper2.guide.quota.give_back` with `reason` (`no_pick`, `failed`, `shared`) and `outcome`
   (`given_back`, `already_given_back`, `cap_reached`, `nothing_counted`). The model calls of these questions stay
   counted in the
   global daily cap (point 6). In memory,
   `ClientQuota` keeps only today's clients, at most 10,000 (`MAX_CLIENTS`; the least recently seen leaves first),
   and drops them all when the day changes. `ClientQuota.left` reads what a client has left without counting (the
   Redis count, else this replica's count in memory), so a give-back on any replica shows at once; `GET /api/v1/me` shows it ([0024](../edge/0024-identity-and-admin-host.md)).
6. **Global daily cap** (`H2_GUIDE_DAILY_MODEL_CALLS`, 600): one unit before each model attempt
   ([0008](../foundation/0008-llm-integration.md)), taken inside the `llm-chat` circuit, so a call the open circuit refuses costs no
   unit. When spent, plan or choose fails with `GuideBudgetExhausted` and the answer is the degraded one; the reason
   check leaves reasons `NOT_CHECKED`. Gauge `helper2.guide.budget.remaining`. The cap is a runtime setting
   ([0007](../foundation/0007-configuration-secrets-and-settings.md)): `H2_GUIDE_DAILY_MODEL_CALLS` is its default, an administrator
   can lower it (0 = search-only answers) or raise it up to 3000 from the back office, and every replica applies
   it within about 20 seconds.
7. **Daily web searches** ([0014](../search/0014-tavily-store-and-daily-quota.md)): the question's searches take one web search
   from the asker's guide quota (3 a day for a guest), at the first live Tavily call; a cached, shared or reused answer
   takes none. Every answer states what the asker has left (`webSearches`).
8. **Metrics**: `helper2.guide.cache` (`exact`, `similar`, `miss`) and `helper2.guide.questions` with outcome `cached`, `shared`,
   `client_quota`, `answered`, `degraded`, `budget_exhausted`, `model_unavailable`, `no_candidates`, `timeout`, `error`.

### Consequences
- The worst case per day is bounded: 600 model calls, whatever the traffic.
- One client can use at most 30 fresh questions with an AI answer a day, plus at most 30 with no pick or that failed
  (60 questions, about 180 calls), so a single client cannot spend the global cap alone.
- A visitor pays only for an answer that helps: a no-pick answer, a failed question and a cached answer leave the
  daily count as it was.
- A question that fails or times out does not count either; its model calls still count in the global cap.
- Repeated example questions are free and instant; a cached answer is honest about its age.
- The rate limit and the daily counts (quota, global cap) are in Redis and hold across replicas
  ([0032](../operations/0032-runtime-topology.md): 3 on prod).
- An answer stored in PostgreSQL serves every replica.
- The quota's memory is bounded (10,000 clients a day), and no client address is stored.

### Alternatives considered
- Global cap only — one client could spend the day in about twenty minutes at 10 per minute.
- Cache in memory only — a restart throws away answers that cost 3 calls each, and each replica pays for its own.
- Cache without a config hash — a changed prompt or model would serve answers of the old one for 24 hours.
- Error (429) past the quota — the search-only answer is still useful and explains itself.
- Count only after the answer, when it has a pick — the model would run before the quota check, so a client past
  its quota could still spend model calls. Taking first and giving back keeps the check in front of the model.
- Give back without a cap — a client could loop questions that find nothing and spend the global cap for free.
- Queue extra questions instead of 503 — a queue hides overload and makes every user wait longer.

### Code
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `ClientQuota`, `GuideAnswerCache`, `GuideAnswerStore`;
  `SimilarQuestions.kt` ([the part that was ADR 0041](#part-0041)).
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `AskGuide.ask` (order of the guards, config hash, single-flight).
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideLimits.java` — slot in the `guide` bulkhead and deadline.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/AnswerLimits.kt` — comparison and explanation limits in the config hash.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — keep-alive comments.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ClientKey.java` — the client key for the rate limits and the quota.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/DailyBudget.java`, `RedisCallCounter.java` — the count and its give-back.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SharedGuideAnswerStore.java`, `GuideConfiguration.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/RateLimitInterceptor.java`, `RateLimiter.java`.

### Diagrams
[flow](../../diagrams/flow-0017-guide-cost-guard.svg) · [sequence](../../diagrams/sequence-0017-guide-cost-guard.svg)

### Roadmap
- [Single-flight and concurrency limit stay per replica, on purpose](../../roadmap.md#3-more-than-one-replica)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0041"></a>

## Part: was ADR 0041 — Guide answer cache: normalised keys, answers in the shared cache, reuse for a near-identical question read the same way

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0011](../search/0011-vector-search-pgvector.md), [the part that was ADR 0017](#part-0017), [0036](../quality/0036-evaluation.md), [0016](0016-guide-pipeline.md), [0035](../operations/0035-data-retention.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0018](0018-ai-content-marking-and-research-trail.md)

### Context
A fresh guide answer costs three model calls, several searches and up to one web search of the asker. People ask the
same thing in many ways: "What is MCP?", "what is mcp", "what does MCP mean". A cache keyed by the exact text pays for
each spelling again.

Two kinds of difference need different answers:
- Spelling only: case, full-width letters, quote marks, extra spaces, the final "?". Nothing in the answer can depend
  on them. These can share one key.
- Wording: "free tool for chatting with pdf files" and "a free tool to chat with PDFs" are the same need. "a paid
  tool to chat with PDFs" and "rust bindings for python" are not, although their embeddings are close to their
  twins. Sharing an answer here needs more than a text rule, and a wrong share is a wrong answer shown as AI-written.

The answers must survive a restart and be the same on every replica. Redis keeps only rate limits, counters, sessions
and the search cache; AI answers are kept on disk, in PostgreSQL.

### Decision

#### 1. Normalised key
`GuideAnswerCache.normalized` changes only what cannot change the answer:
- Unicode compatibility form (NFKC): full-width letters, ligatures, no-break spaces;
- lower case;
- quote marks removed (`" “ ” « » \``, and `'` at the edge of a word); an apostrophe inside a word stays ("what's");
- invisible characters (zero-width spaces) removed, every run of spaces one space, trimmed;
- the punctuation that ends the sentence removed (`. ? ! … , ; :`).

Word order stays: "python bindings for rust" is not "rust bindings for python". "Ollama vs LM Studio" and "LM Studio
vs Ollama" could share an answer, but the comparison's columns follow the order asked, and only the plan knows it is a
comparison; section 3 handles it. Inner punctuation stays ("c++", "node.js", "gpt-4", ".net"), and hyphens stay
("open-source"), because search reads them.

The key is the SHA-256 of normalised question | audience path | config hash ([the part that was ADR 0017](#part-0017)) |
normalised clarification | removed parts of the reading. A clarification and removed parts stay in the key: they are
part of the question ([0016 (part was 0032)](0016-guide-pipeline.md) §3, §9). No store sees the question text.

#### 2. Store: the shared cache in PostgreSQL
`SharedGuideAnswerStore` keeps each answer in the shared cache `guide-answer` (`SharedCaches`, table `ai_cache`,
[0005](../foundation/0005-redis-disposable-cache.md)), like the other model answers: one primary-key lookup, one upsert,
the `db-cache` policy, the shared cache purge. The stored key is the SHA-256 of the running release
(`H2_BUILD_VERSION`) and the guide's key: a release reads only its own answers, so a deploy that changes how the guide
reads or answers is seen at once. The answer is JSON in a versioned envelope; an entry that does not read is a miss.
It is kept for the answer's time to live counted from its `generatedAt`: 24 h (`H2_GUIDE_CACHE_TTL`), 6 h for a
dated question, never past its web results ([0014](../search/0014-tavily-store-and-daily-quota.md)). A database error
is a miss, never a failed question. Single-flight stays in memory: it coordinates the work of one replica, it does
not cache.

#### 3. Reuse for a near-identical question
Off unless `H2_GUIDE_SIMILAR_THRESHOLD` is set. On an exact-key miss, after single-flight and the client quota, the
plan runs as for any fresh question. Then `AskGuide` looks for a stored question read the same way:
- Only a question with no clarification and no removed parts, whose plan succeeded. No model call is added to decide:
  the reading is the plan the fresh answer would use anyway.
- **Reading key** (`ReadingKey`): intent, subjects, constraints, resolved window, reading types wanted, event question,
  and whether the subject is checked for namesakes. Subjects keep the order the user wrote them in, whatever order the
  planner gives, because the order carries the direction; a comparison's subjects are a set. Constraints are a set.
  Names compare without case, hyphens or underscores. The planned search query is left out: two wordings of one need
  are planned with different words.
- **Group** = SHA-256(embedding model | config hash | audience path | reading key). Only questions of the same group
  are compared.
- **Embedding**: the normalised question, embedded as a query by the vector-search model
  ([0011](../search/0011-vector-search-pgvector.md), `nvidia/nemotron-3.5-embed-1b`, 1024 dimensions). An embedding
  failure is a miss.
- **Match**: the closest question of the group whose answer is still stored, if its cosine similarity is at least the
  threshold. Its answer is returned as it is: same `generatedAt`, same AI marking ([0018](0018-ai-content-marking-and-research-trail.md)).
- The reused answer is stored under the new question's own key until the original expires, so the next identical
  question is an exact hit. It is not indexed: a chain of near twins would drift from the question that was answered.
- A fresh answer that is cached is indexed in `guide_similar_question (answer_key, group_key, embedding vector(1024),
  expires_at)` (`JdbcSimilarQuestionIndex`), with its answer's expiry. A read skips an expired row; the guide purge
  deletes it the next night ([0035](../operations/0035-data-retention.md)). It is its own table, not the shared cache,
  because the lookup is a distance search, not a key.

The nearest-neighbour search is an exact scan of the group's live rows by cosine distance (`<=>`). A group holds a few
rows at our size, so no approximate index is needed, and the result is exact.

Cost of a reused answer: the plan call (one fast-model call, one unit of the client's daily questions) and one
embedding call. No search, no choose or reason check, and **no web search** of the asker
([0014](../search/0014-tavily-store-and-daily-quota.md)): the answer states the asker's own web searches left, like an exact hit.

#### 4. Evaluation, and the threshold
`docs/eval/guide-similar.json`: 55 fixed pairs. 25 should share an answer (paraphrases, word order, spelling, one in
Italian); 30 must not (another subject 8, constraint 6, intent 5, negation 4, direction 4, window 2, kind named 1).
Each side carries the reading a correct plan gives it. Their query embeddings are recorded once
(`./gradlew evalRecord --tests '*GuideSimilarRecorder'`) into `docs/eval/guide-similar-vectors.json`, and
`GuideSimilarEvaluationTest` computes the sweep in every build, so the table below cannot drift from the code.

The vectors are recorded through the NVIDIA API with the production model (`nvidia/nemotron-3.5-embed-1b`), through
the same recorder and `NvidiaEmbedder` the index uses.

| Threshold | Reused (of 25) | False reuses (of 30) | Precision | Embedding alone: reused / false |
|---|---|---|---|---|
| 0.80 | 23 | 2 | 0.92 | 24 / 16 |
| 0.90 | 18 | 2 | 0.90 | 19 / 5 |
| 0.93 | 15 | 2 | 0.88 | 16 / 4 |
| 0.95 | 14 | 1 | 0.93 | 15 / 3 |
| **0.96** | **9** | **0** | **1.00** | 10 / 2 |
| 0.97 | 8 | 0 | 1.00 | 9 / 1 |
| 0.98 | 4 | 0 | 1.00 | 4 / 1 |

- With the reading, no must-not pair is reused from 0.96 up. Below it, "ai tools released this year" and "… last
  year" (0.955) share an answer: the guide reads both as the last 365 days ([0016 (part was 0032)](0016-guide-pipeline.md) §4), so the
  reading cannot tell them apart. "a model to translate english to italian" and its reverse (0.931) have no subject
  to keep the direction.
- The embedding alone is not enough: "python bindings for rust" is 0.986 from its reverse, closer than most
  paraphrases. The subject order of the reading key is what keeps them apart.
- Missed paraphrases: "postgres" and "postgresql" are different subjects; "when is neurips" and "when does neurips
  take place" are 0.77 apart.

The threshold that meets the rule (no false reuse on the set) is **0.96**, with 36 % of the paraphrases reused. The
default stays off.

Checked live on staging (1 Nov 2026, the planner of the running release):
- Every negation and direction pair gets two different readings, except "a model to translate english to italian"
  and its reverse (no subject, no constraint): only their cosine (0.931) keeps them apart.
- With `H2_GUIDE_SIMILAR_THRESHOLD=0.96`, 1 of 6 paraphrase pairs that reuse on the set was reused live
  (`helper2.guide.cache{result="similar"}`): the live planner reads two wordings of one need differently more often
  than the set assumes (another intent, a subject named or not). That lowers reuse, not precision.

Prod sets the threshold to 0.96 (`app.guideSimilarThreshold` in `values-prod.yaml`). Staging runs the nightly guide
evaluation with answers kept one second ([0036](../quality/0036-evaluation.md)), so reuse is measured on prod,
through the same metric.

#### 5. Metrics
`helper2.guide.cache{result}`: `exact` (key hit), `similar` (reused), `miss`, once per question when the cache is on.
A reused answer counts as outcome `cached` in `helper2.guide.questions`.

### Consequences
- Spellings of one question share one answer; repeated example questions cost nothing after the first.
- Every replica sees the same answers, and a restart keeps them; a deploy starts a new set.
- With a threshold set, a paraphrase costs one fast-model call and one embedding instead of three model calls and its
  searches.
- A guide question is embedded and its embedding stored next to its answer, for at most the answer's life (24 h) plus
  the night until the purge. Privacy page: "Guide question fingerprints" and the NVIDIA API catalog as a recipient.
  A lawyer must confirm: whether a question embedding is personal data under the nFADP and the GDPR, the legal basis
  for keeping it, and the transfer of the question text to the NVIDIA API catalog in the United States.
- Every read and write of the cache is a database call on the guide's path (a primary-key lookup; the group scan
  reads a few rows), under the `db-cache` policy for the answers.

### Alternatives considered
- **Redis for the answers and a Redis sorted set or Redis 8 vector set for the index** — per-entry time to live for
  free, but model answers are kept on disk and shared from PostgreSQL; a vector set has no time to live per element,
  and its default 8-bit quantisation shifts scores near the threshold.
- **A table of its own for the answers** — a second cache table and a second purge for what the shared cache does.
- **The question embeddings in the shared cache** — a key-value table cannot find the nearest vector.
- **pgvector with an HNSW index on `guide_similar_question`** — approximate, and the filter on group and expiry would
  need an iterative scan ([0011](../search/0011-vector-search-pgvector.md)); an exact scan of a few rows per group is simpler and exact.
- **Reuse before the plan, on the embedding alone** — saves the plan call, but the evaluation shows the embedding
  cannot tell a direction or a negation from a paraphrase.
- **A model call to judge whether two questions are the same** — one more call per miss, and the judge has the
  blind spots of the planner.
- **Reorder the words of "X vs Y" in the key** — the key is built before the plan, so it cannot know it is a
  comparison; "python for rust" would be reordered too.
- **Stemming, stop words or synonyms in the key** — each can change the answer ("free" vs "not free").

### Code
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `GuideAnswerCache` (`key`, `normalized`, `put`),
  `GuideAnswerStore`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/SimilarQuestions.kt` — `ReadingKey`, `SimilarQuestions`, `QuestionEmbedder`,
  `SimilarQuestionIndex`, `SimilarQuestionRetention`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `ask`, `similarAnswer`; `PurgeExpiredData.kt`.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SharedGuideAnswerStore.java`, `JdbcSimilarQuestionIndex.java`,
  `GuideConfiguration.java` (`similarQuestions`), `GuideRetentionSchedule.java`; `platform/PgVectors.java`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `guide_similar_question`.
- `docs/eval/guide-similar.json`, `guide-similar-vectors.json`; `backend/apps/public/src/test/kotlin/ai/helper2/eval/GuideSimilarEvaluation.kt`,
  `GuideSimilarEvaluationTest.kt`, `GuideSimilarRecorder.kt`.
- Tests: `GuideAnswerKeyTest`, `SimilarQuestionsTest`, `AskGuideSimilarTest`, `JdbcSimilarQuestionIndexIT`,
  `SharedGuideAnswerStoreTest`, `GuideSimilarConfigurationTest`, `GuideApiIT`.

### Diagrams
[flow](../../diagrams/flow-0017-guide-answer-cache.svg) · [sequence](../../diagrams/sequence-0017-guide-answer-cache.svg)

### Roadmap
- [Watch similar-question reuse on prod; change the threshold only with a new sweep](../../roadmap.md#4-ai-quality-and-cost).
