# 0032 — Guide cost guard and answer cache

**Depends on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0005](../foundation/0005-redis-disposable-cache.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0008](../foundation/0008-llm-integration.md), [0010](../foundation/0010-data-retention.md), [0024](../search/0024-vector-search-pgvector.md), [0026](../search/0026-filters-facets-and-categories.md), [0027](../search/0027-tavily-store-and-daily-quota.md), [0031](0031-guide-pipeline.md), [0033](0033-ai-content-marking-and-research-trail.md), [0037](../identity/0037-http-edge-protection.md), [0046](../operations/0046-evaluation.md).

## Context

A fresh guide question costs three model calls, several searches, up to one web search of the asker, and several
seconds. The site is public and free, paid by provider credits. Without guards one client can spend the whole day's
budget, many slow questions pile up on the server, and visitors who try the same example question pay for it again
and again. The guards must degrade the answer, not break the page.

People also ask the same thing in many ways: "What is MCP?", "what is mcp", "what does MCP mean". A cache keyed by
the exact text pays for each spelling again. Two kinds of difference need different answers:
- Spelling only: case, full-width letters, quote marks, extra spaces, the final "?". Nothing in the answer can depend
  on them. These can share one key.
- Wording: "free tool for chatting with pdf files" and "a free tool to chat with PDFs" are the same need. "a paid
  tool to chat with PDFs" and "rust bindings for python" are not, although their embeddings are close to their twins.
  Sharing an answer here needs more than a text rule, and a wrong share is a wrong answer shown as AI-written.

The answers must survive a restart and be the same on every replica. Redis keeps only rate limits, counters, sessions
and the search cache; AI answers are kept on disk, in PostgreSQL.

## Decision

### When the guide runs: the user chooses
Enter always searches, whatever the length of the text. The guide runs only when the user asks for it: the "Ask the
guide" button next to Search, or Shift+Enter; "Ask the guide instead" on an empty result asks too. No word count
starts the guide, so a name search ("pgvector", a long product name) spends no model call. The choice lives in the one
box ([0039](../frontend/0039-frontend-one-box.md)); the server answers every question it gets, behind the guards below.
Where the guide answers, the page shows what is left today ("2 of 3 AI answers left today",
[0040](../frontend/0040-design-system.md)).

### The guards, in code order
Values are defaults; each has an `H2_` variable.
1. **Per-minute rate limit** (`RateLimitInterceptor`, [0037](../identity/0037-http-edge-protection.md)): 10 questions a
   minute per client (`H2_RATE_LIMIT_GUIDE_PER_MINUTE`), one limiter shared by `askGuideStream` and the tool guide
   `askResourceGuide`. The client is `platform.ClientKey`: the remote address, IPv4 as is, IPv6 by its /64 (zone id
   dropped, IPv4-mapped becomes IPv4). The rate limiters and the daily quota use the same key. Counted in Redis, so the
   limit holds across replicas. Over the limit: 429 with `Retry-After`.
2. **Concurrency limit and deadline** (`GuideLimits`): one client has at most `H2_GUIDE_CLIENT_CONCURRENT_QUESTIONS` (2)
   questions running at once per replica (by `ClientKey`, in memory); one more gets 503 "Guide busy" at once. A question
   does its work only with a slot in the `guide` bulkhead
   ([0006](../foundation/0006-outbound-calls-and-resilience.md), `Resilience.trySlot`), at most `H2_GUIDE_MAX_CONCURRENT`
   (8) questions at once per replica (24 on prod with 3 replicas), on virtual threads. The slot is taken lazily
   (`WorkGate`): `AskGuide` enters it only after the answer cache and single-flight missed, so a cached answer and a
   question that waits for an identical one hold no slot; a question that finds an identical one in flight after it
   entered gives its slot back while it waits. With no slot left, the question gets 503 "Guide busy" (`GuideBusy`, an
   `error` event on the stream, outcome `busy`). A question that got a slot receives a `:keep-alive` comment every
   `H2_GUIDE_HEARTBEAT` (15 s) until its last event ([0031](0031-guide-pipeline.md)).
   **A model call never runs past the deadline.** Each model attempt (the first, the retry of a malformed answer, the
   repeat without thinking of a cut answer) gets its own time: the smaller of the model timeout (`H2_LLM_TIMEOUT`,
   40 s) and the time the question has left minus a margin (`helper2.guide.deadline-margin`, 2 s). `CallTimeLimit`
   computes it from `QuestionDeadline`, a scoped value `GuideLimits` sets for the question's worker, and
   `SpringAiGuideModel` sends it as the timeout of that one request (`OpenAiChatOptions.timeout`), so the bound does
   not depend on the timeout the client was built with. A call read as a stream (`H2_GUIDE_MODEL_STREAM=ON`,
   [0035](0035-guide-model-stream.md)) is also disposed at that time, and a streamed call that is still thinking when
   only the margin and the shortest call are left is stopped and asked again without thinking. The timeout bounds one HTTP try: with `H2_LLM_MAX_RETRIES`
   above 0 the SDK tries a timed-out call again inside the same attempt, each try with that timeout, and only the
   deadline stops it (keep 0, [0008](../foundation/0008-llm-integration.md)). With less than `helper2.guide.model-call-minimum` (10 s) left
   before the margin the call does not start: it fails at once with `GuideUnavailable` category `deadline`, before
   the budget (a repeat without thinking that cannot start fails with `cut`,
   [0031](0031-guide-pipeline.md)). A call that its shortened time stops fails with the same category, is not asked again and is not
   counted against the provider in the `llm-chat-guide` circuit (`DeadlineTimeout`); a call that used its whole model
   timeout is a provider failure as before. The step degrades as when the model is down: no plan means the question's
   words, no choice means the top results with the notice `GUIDE_OUT_OF_TIME`, no reason check means `NOT_CHECKED`
   (not cached). So a slow provider gives a degraded answer at least the margin before the deadline. The app does not
   start when `H2_GUIDE_QUESTION_DEADLINE` is not longer than two model timeouts (plan and choose must both fit), or
   when one model timeout is shorter than the shortest call plus the margin.
   **At the deadline** (`H2_GUIDE_QUESTION_DEADLINE`, 90 s) `GuideLimits` interrupts the worker and frees its slot.
   The question still answers with what it has (`AskGuide`):
   - picks chosen and validated, reason check not finished: the normal answer with those picks, each reason
     `NOT_CHECKED`, the status that path gives (`OK` or `PARTIAL`). It spends a question like every answer with a pick;
   - search done, no pick yet: the top search results with status `LLM_UNAVAILABLE` and the notice
     `GUIDE_OUT_OF_TIME`, free like every answer without a pick (§ "Free answers");
   - search not done: nothing to answer with, the last event is `error` 504 "Guide timed out" and the question metric
     records outcome `timeout`.
   A question is cancelled when its worker is interrupted or its deadline is over (`GuideSteps`): a step the answer
   needs (plan, search) stops the question, a model step (choose, compare, explain, reason check) fails with category
   `cancelled` and degrades, validation still runs. The answer of a cancelled question is never cached, and the
   cancelled call is not a provider failure for the circuit. A worker that has not ended `GuideLimits.ANSWER_GRACE`
   (3 s) after the deadline gets the 504 instead. The stream's own timeout is the deadline plus that grace plus 10 s.
3. **Answer cache**: an exact hit is answered at once (below, "The answer cache").
4. **Single-flight** (`shared.SingleFlight`): questions in progress are tracked by cache key, in memory, per replica. A
   second asker of the same question waits for the first answer (outcome `shared`) and shares its failure. On the stream it gets no step
   events, only the keep-alive comments, so its connection does not look dead. Where the guide reads its model
   calls as a stream and a step thinks, a question stops when nobody waits for its answer any more; elsewhere it runs
   on (below, "A closed connection").
5. **Per-client daily quota** (below).
6. **Global daily cap** (below).
7. **Daily web searches** ([0027](../search/0027-tavily-store-and-daily-quota.md)): the question's searches take one web
   search from the asker's guide quota, at the first live Tavily call; a cached, shared or reused answer takes none.
   Every answer states what the asker has left (`webSearches`).

### The per-client daily quota
`ClientQuota` allows `H2_GUIDE_CLIENT_DAILY_QUESTIONS` (30) fresh questions per client per UTC day, per `ClientKey`,
and `H2_GUIDE_NETWORK_DAILY_QUESTIONS` (300) per network (`ClientKey.network`: an IPv4 /24, an IPv6 /56, the first
14 hex digits of the address), so a client that rotates its addresses inside one /56 (256 /64 networks, the block a
home or small office line usually gets) shares one network count. A /56 and not a /48: a /48 is often a whole campus
or company, and one count for all of it is too tight. A question needs both. The
network is checked first: when the client's own count is full, the network unit is taken off again at once
(`DailyBudget.undo`). An undo is not a free answer: it has no cap and is not counted with the answers given back, so
refusals use up neither the client's free answers nor the network's. A free answer gives back both, the network's unit under a
cap of its own: the free answers of as many clients as the network's questions stand for (300 / 30 = 10 clients,
so 50 a day; `ClientQuota.freeAnswersOfANetwork`). Without it, clients rotating inside one /56 each brought 5 fresh
free answers and the network never filled: no-pick questions cost model calls and no quota. With it one network
runs at most 350 questions a day. A client refused because its
network is full, though it has questions left, gets the notice `GUIDE_NETWORK_QUOTA` ("Your network has used today's
AI answers") instead of `GUIDE_DAILY_QUOTA`, outcome `network_quota`. 0 refuses every AI answer; there is no "off"
value (staging sets 1000 for the nightly eval). It counts with a
`DailyBudget` named `guide-client:<QuotaKeys.client(client)>` (and `guide-network:<QuotaKeys.client(network)>`), held
by `platform.quota.ClientBudgets`: the client's keyed pseudonym of the UTC
day, as for the rate limits ([0037](../identity/0037-http-edge-protection.md)). So no address is stored, and the name cannot
be turned back into an address without `H2_QUOTA_KEY_SECRET`. The pseudonym changes at midnight UTC, when the quota
starts afresh anyway. Past the quota the client gets a search-only answer with the notice `GUIDE_DAILY_QUOTA`, not an
error. Cached and shared answers do not count; a question answered with a similar question's answer counts, because its
plan ran.

**An answer with no pick, or a question that fails, does not count.** `ClientQuota.reserve` takes the question before
the model runs, so abuse stays bounded. `AskGuide` settles the reservation in one `finally` (`QuotaTurn.settle`), so every
exit is covered exactly once, and gives the question back in three cases:
- **no pick:** the answer has nothing from the AI (`ClientQuota.spendsAQuestion`: no AI pick, no comparison row, no
  explanation sentence; a comparison whose every cell the quote gate refused is as empty as none). That covers "Nothing
  clearly fits your need", no candidates, subject not found, a model call that throws or a spent budget (both give a
  degraded answer), and a question back (`NEEDS_CLARIFICATION`);
- **failed:** no answer at all: an error escapes `ask` (the stream ends with an `error` event) or the deadline
  interrupts the question (504). The interrupt flag is cleared for the give-back and set again after it;
- **shared:** an identical question started a moment earlier and its answer is shared, as from the cache;
- **abandoned:** every asker of the question closed the connection before the answer, where such a question stops
  (next paragraph).

**A closed connection.** What a closed connection does depends on how the guide runs
(`GuideProperties.stopsAQuestionNobodyWaitsFor`, the one place of the rule):
- **the model stream is ON and a step thinks** (`H2_GUIDE_MODEL_STREAM=ON` and at least one of
  `H2_GUIDE_THINK_PLAN_TOKENS`, `H2_GUIDE_THINK_CHOOSE_TOKENS`, `H2_GUIDE_THINK_VERIFY_TOKENS` above 0): a question
  whose askers have all left stops, as described below;
- **every other case** (the defaults): a closed connection changes nothing. The question runs on, its answer is
  cached and it settles like any answer (a pick counts, no pick is given back), so asking again is a free cache hit.
  No asker is counted, nobody stops waiting, there is no outcome `abandoned` and no give-back with that reason.

Why: the server sees a closed connection 18 to 33 s after the browser left (measured, below). With thinking off a
question takes about 6.5 s, so stopping it stops nothing and only loses the cache hit. With a stream and thinking,
the calls are long (a plan call that thinks runs for about 30 s, [0035](0035-guide-model-stream.md)) and a stream can
be closed at once, so the stop saves model time. The rule is read from the settings once, at the start: both
settings need a restart, and neither can be changed from the back office. Settings, AI guide shows the result.
`H2_GUIDE_THINK_BROWSE_TOKENS` does not count: it makes only the choose call of a browse by date think.

Where a question stops: nobody reads its answer, and each model call
left would be paid for nothing. Each stream has a `shared.Presence`. When the stream is cancelled (the browser closed
the tab, left the page or lost the network; the server sees it when a write fails, and the first write after the
close still succeeds, so at the second `:keep-alive` when no step event comes sooner), `GuideLimits` waits 3 s
(`LEAVE_GRACE`) and then the asker leaves. Measured on a real socket closed against Tomcat
(`GuideLeaveOnTheWireTest`, 8 closes each): the worker is interrupted 1.2 to 1.9 s after the close with a keep-alive
of 1 s and 3.5 to 5.9 s with 3 s, so one to two keep-alives; with the default 15 s that is 15 to 30 s, plus the
3 s. The wait covers a page reload: the
new request asks the same question, finds it in progress and waits for it. `SingleFlight` counts the askers of the
question in progress, the first one and those who wait for it:
- an asker who waits and leaves stops waiting (`SingleFlight.Abandoned`), and the question goes on for the others;
- the first asker leaves and others wait: the question goes on to its end, for them;
- the last asker leaves: the question is taken off the questions in progress, so the same question asked now starts
  afresh, and its worker is interrupted. The running model call is cancelled as at the deadline, and whatever the
  steps had is thrown away: `ask` ends with `SingleFlight.Abandoned`, nothing is cached, the outcome is `abandoned`
  and the question is given back (reason `abandoned`), under the same cap as the other free answers. The slot and the
  client's running question are free as soon as the worker ends.

A question asked without a stream has no `Presence` and is never abandoned. Where questions do not stop, `AskGuide`
drops the `Presence` before single-flight, so every asker counts as one who cannot leave. The model calls made before the stop stay
counted in the global cap. An answer that was complete when the last asker left is cached and still given back: the
asker never saw it, and asking again is a free cache hit.

A reservation has a random id. One Lua script in Redis (`RedisCallCounter`, `GIVE_BACK`) gives it back atomically:
only when the id is not yet in `h2:v1:budget:guide-client:<pseudonym>:<day>:given-back`, the set holds fewer ids than
`H2_GUIDE_CLIENT_DAILY_FREE_ANSWERS` (default 5) and the count is above 0. Then it runs `DECR` on
the count and `SADD` of the id; the set expires after 2 days like the count. So a retry, a second completion or a
failure after a no-pick give-back gives nothing back twice, on any replica; the count never goes below 0; and no-pick
answers, failures and shared answers share one cap, so a client cannot loop them for free. A refused question adds
nothing to the count: the `INCR` script checks the limit first, in the same step, so a search-only question past the
quota never takes the question a give-back frees. Without Redis, `DailyBudget` applies the same rules in memory. The
model calls of these questions stay counted in the global cap.

In memory, `ClientQuota` keeps only today's clients, at most 10,000 (`MAX_CLIENTS`; the least recently seen leaves
first), and drops them all when the day changes. `ClientQuota.left` reads what a client has left without counting (the
Redis count, else this replica's count in memory), so a give-back on any replica shows at once; `GET /api/v1/me` shows it
([0036](../identity/0036-identity-and-admin-host.md)).

### The global daily cap
`H2_GUIDE_DAILY_MODEL_CALLS` (600): one unit before each model attempt ([0008](../foundation/0008-llm-integration.md)),
taken inside the `llm-chat-guide` circuit, so a call the open circuit refuses costs no unit. The cap opens through the
day (`DailyBudget.paced`, `H2_GUIDE_BUDGET_PACE_SLOTS`, 12): by the end of each 2-hour part of the UTC day, at most
that share of the cap is used (50, 100, … 600), and what an earlier part left unused carries over. So an early burst,
or many rotating addresses, cannot take the whole day; 1 turns pacing off. Gauge
`helper2.guide.budget.paced_remaining` shows what is left of the share open so far, next to
`helper2.guide.budget.remaining` for the whole day. When spent, plan, choose, compare or explain fails
with `GuideBudgetExhausted` and the answer is the degraded one; the reason check leaves reasons `NOT_CHECKED`. Gauge
`helper2.guide.budget.remaining`.

**A spent budget is said, with the time it opens again.** The degraded answer carries the notice
`GUIDE_BUDGET_SPENT` (`AskGuide`, `budgetSpentNotice`), so a visitor, an API client and the evaluation script can tell
it from a provider that is down (status `LLM_UNAVAILABLE` with no notice). The notice has a machine-readable
`retryAt`, a UTC instant from `DailyBudget.opensAgainAt`:
- the day's cap still has room (`DailyBudget.opensMoreToday`), so only the part of the day open so far is spent: the
  start of the next part. A part is 24 hours / `H2_GUIDE_BUDGET_PACE_SLOTS` of the UTC day and starts on the hour
  (12 parts: 00:00, 02:00, … 22:00);
- the day's cap is spent, the last part of the day is running, or pacing is off (1 part): the next 00:00 UTC.

One code, two messages, no number of calls: "The AI guide has answered as many questions as it can today; showing
search results instead. AI answers are back after 00:00 UTC.", or "… as it can for now; showing search results
instead. AI answers are back at 14:00 UTC." The page shows the time as the visitor's browser shows times
(`backAgain` in `frontend/src/lib/format.ts`, [0039](../frontend/0039-frontend-one-box.md) point 10): "… AI answers
are back at 15:00.", or
"… back tomorrow at 01:00." when the instant is on the visitor's next day. Without `retryAt`, or with one that is
past, it falls back to "… Try again later." The page does not keep the answer, so a later visit asks again. The
time is when the next share opens, not a promise: other visitors can use that share first.

The answer is not cached, and it is free for the asker like every answer without a pick (§ "Free answers"). Picks
already validated when only the reason check found the budget spent stay as they are, `NOT_CHECKED`, with no notice.
The question metric counts the answer as `budget_exhausted`.

The notices `GUIDE_DAILY_QUOTA` and `GUIDE_NETWORK_QUOTA` carry `retryAt` too: the next 00:00 UTC, when both counts
start afresh. The page shows it the same way: "AI answers are back at
7:00 PM." for a visitor in New York in the evening, "… back tomorrow at 01:00." in Zurich. Without `retryAt`, or with
one that is past, it says "after midnight UTC".

The cap is a runtime setting ([0007](../foundation/0007-configuration-secrets-and-settings.md)):
the variable is its default, an administrator can lower it (0 = search-only answers) or raise it up to 3000 from the
back office, and every replica applies it within about 20 seconds.

### The answer cache and its key
`GuideAnswerCache.normalized` changes only what cannot change the answer:
- Unicode compatibility form (NFKC): full-width letters, ligatures, no-break spaces;
- lower case;
- quote marks removed (`" “ ” « » \``, and `'` at the edge of a word); an apostrophe inside a word stays ("what's");
- invisible characters (zero-width spaces) removed, every run of spaces one space, trimmed;
- the punctuation that ends the sentence removed (`. ? ! … , ; :`).

Word order stays: "python bindings for rust" is not "rust bindings for python". "Ollama vs LM Studio" and "LM Studio vs
Ollama" could share an answer, but the comparison's columns follow the order asked, and only the plan knows it is a
comparison; the similar-question reuse handles it. Inner punctuation stays ("c++", "node.js", "gpt-4", ".net"), and
hyphens stay ("open-source"), because search reads them.

The key is the SHA-256 of normalised question | audience path | config | normalised clarification | removed parts of
the reading. A clarification and removed parts are part of the question ([0031](0031-guide-pipeline.md)). The config is
the config hash, plus the active category filter when there is one ([0026](../search/0026-filters-facets-and-categories.md)):
it changes the candidates. No store sees the question text or the reply to a question back. Before the answer is
cached, `AskGuide.withoutTheQuestion` keeps a search text only when the planner wrote it (`Question.plannerWrote`: its
query and the subject words of that query). It empties the search request (`text`, `webQuery`); it empties
`searchQuery` and the search's reading (`interpretation`: original, normalised, content and expanded terms) when they
are the question's own words: the planner wrote no query, or a dated (`WHEN`) or "what is" answer searched the name
read from the question; and it drops `QUERY_EXPANDED` notices. The asker of a fresh answer still sees their own
words; the stored copy does not hold them. `StoredAnswerPrivacyTest` checks the encoded entry for a planned answer,
a planner with no query and a dated answer. The stored answer still holds the search terms the planner wrote, the
trail's queries, and the names read from the question (`subjects`, the compared names): words of the question, never
all of it.

The **config hash** (`AskGuide.configHash`) is the first 16 hex characters of a SHA-256 over the provider, both model
ids, the model's config fingerprint (`SpringAiGuideModel.getConfigFingerprint`: the five rendered prompts, the thinking
flags, the native-schema flag, the token limits, the candidate text size), the validator's limits, the comparison and
explanation limits (`AnswerLimits`) and `maxCandidates`. How a model call is read (`H2_GUIDE_MODEL_STREAM`) is not
part of it: the request and the checks are the same, so it cannot change an answer
([0035](0035-guide-model-stream.md); a test pins it). The back office's Settings page shows it. A new prompt wording
or limit therefore never serves answers of the old one: it starts a fresh cache by itself. Each prompt's label is the
first 8 hex characters of the SHA-256 of its rendered text (`ModelCallMetrics.promptVersion`), the label the meters and
the eval runs name; a test pins each one (`SpringAiGuideModelTest`): `guide/plan.txt` `5684c305`, `guide/choose.txt`
`2411356c`, `guide/compare.txt` `14806e21`, `guide/explain.txt` `180b36ae`, `guide/verify-reasons.txt` `c457a114`.

**Store.** `SharedGuideAnswerStore` keeps each answer in the shared cache `guide-answer` (`SharedCaches`, table
`ai_cache`, [0005](../foundation/0005-redis-disposable-cache.md)), like the other model answers: one primary-key lookup,
one upsert, the `db-cache` policy, the shared cache purge. The stored key is the SHA-256 of the running release
(`H2_BUILD_VERSION`, the image tag the chart passes) and the guide's key: a release reads only its own answers. A
restart keeps them; a deploy starts empty, so a change in how the guide reads or answers is seen at once. The answer is
JSON in a versioned envelope; an entry that does not read is a miss, and a database error is a miss, never a failed
question.

**Time to live.** Counted from the answer's `generatedAt`, which the page shows: `H2_GUIDE_CACHE_TTL` (24 h), 6 h for a
question with a period or a `WHEN` question (a `WHEN` answer also ends at the next 00:00 UTC, when "this week" moves
on), never past its web results; an answer built on stale web results is not
cached ([0027](../search/0027-tavily-store-and-daily-quota.md)). Only complete answers are cached: model used, at least
one AI pick, and no AI pick with reason `NOT_CHECKED`. A `WHEN` answer is cached too when it found its date or events
(status `OK`): code reads them from the records, so it has no AI pick, but its plan cost a model call. An answer built
when a web source was skipped for its daily limit (`DAILY_LIMIT`, `QUOTA_UNAVAILABLE`) is not cached: it is what that
asker could get, not what the question gets.

A complete answer with no pick (no AI pick, no comparison row, no explanation sentence) is cached too, for 10 minutes
only (`GuideAnswerCache.NO_PICK_TTL`), under the same key: the same question asked again costs no model call. The
model must have run and answered: not `LLM_UNAVAILABLE`, not a question back, not out of time or budget. A comparison
with a cell the reason check could not confirm is not cached either, also when the cells that are left are checked: the
unconfirmed cell is not shown, so the answer is not what the question gets. It is never
indexed for similar questions (`AskGuide.answerAndStore`), so a near twin still gets its own answer. Ten minutes stop
a repeated question; a catalog that gains the missing resource answers it soon after.

### Reuse for a near-identical question
Off unless `H2_GUIDE_SIMILAR_THRESHOLD` is set; prod sets 0.96 (`app.guideSimilarThreshold` in `values-prod.yaml`). On
an exact-key miss, after single-flight and the client quota, the plan runs as for any fresh question. Then `AskGuide`
(`similarAnswer`) looks for a stored question read the same way:
- Only a question with no clarification and no removed parts, whose plan succeeded. No model call is added to decide:
  the reading is the plan the fresh answer would use anyway.
- **Reading key** (`ReadingKey`): intent, subjects, constraints, resolved window, reading types wanted, event question,
  and whether the subject is checked for namesakes. Subjects keep the order the user wrote them in, whatever order the
  planner gives, because the order carries the direction; a comparison's subjects are a set. Constraints are a set.
  Names compare without case, hyphens or underscores. The planned search query is left out: two wordings of one need
  are planned with different words.
- **Group** = SHA-256(embedding model | release (`H2_BUILD_VERSION`) | config hash | audience path | reading key). The
  release is in the group because the answer store's key has it: after a deploy the old rows point at answers the new
  release cannot read, so they must not be the nearest row of a new question. Only questions of one group are
  compared.
- **Embedding**: the normalised question, embedded as a query by the vector-search model
  ([0024](../search/0024-vector-search-pgvector.md), `nvidia/nemotron-3.5-embed-1b`, 1024 dimensions). An embedding
  failure is a miss.
- **Match**: the closest question of the group whose answer is still stored, if its cosine similarity is at least the
  threshold. Its answer is returned as it is: same `generatedAt`, same AI marking
  ([0033](0033-ai-content-marking-and-research-trail.md)), but without the planner's words of the other asker: no
  search query, no trail queries, no not-found query, and no `QUERY_EXPANDED` notice in its search.
- The reused answer is stored under the new question's own key until the original expires, so the next identical
  question is an exact hit. It is not indexed: a chain of near twins would drift from the question that was answered.
- A fresh answer that is cached is indexed in `guide_similar_question (answer_key, group_key, embedding vector(1024),
  expires_at)` (`JdbcSimilarQuestionIndex`), with its answer's expiry. It is its own table, not the shared cache,
  because the lookup is a distance search, not a key. The search is an exact scan of the group's live rows by cosine
  distance (`<=>`): a group holds a few rows at our size, so no approximate index is needed.

A reused answer costs the plan call (one fast-model call, one of the client's daily questions) and one embedding call.
No search, no choose or reason check, and no web search of the asker: the answer states the asker's own web searches
left, like an exact hit.

**Evaluation and the threshold.** `docs/eval/guide-similar.json` holds 55 fixed pairs: 25 should share an answer
(paraphrases, word order, spelling, one in Italian); 30 must not (another subject 8, constraint 6, intent 5, negation 4,
direction 4, window 2, kind named 1). Each side carries the reading a correct plan gives it. Their query embeddings are
recorded once through the NVIDIA API with the production model (`./gradlew evalRecord --tests '*GuideSimilarRecorder'`,
the same `NvidiaEmbedder` the index uses) into `docs/eval/guide-similar-vectors.json`, and `GuideSimilarEvaluationTest`
computes the sweep in every build, so the table cannot drift from the code.

| Threshold | Reused (of 25) | False reuses (of 30) | Precision | Embedding alone: reused / false |
|---|---|---|---|---|
| 0.80 | 23 | 2 | 0.92 | 24 / 16 |
| 0.90 | 18 | 2 | 0.90 | 19 / 5 |
| 0.93 | 15 | 2 | 0.88 | 16 / 4 |
| 0.95 | 14 | 1 | 0.93 | 15 / 3 |
| **0.96** | **9** | **0** | **1.00** | 10 / 2 |
| 0.97 | 8 | 0 | 1.00 | 9 / 1 |
| 0.98 | 4 | 0 | 1.00 | 4 / 1 |

- With the reading, no must-not pair is reused from 0.96 up. Below it, "ai tools released this year" and "… last year"
  (0.955) share an answer: the guide reads both as the last 365 days ([0031](0031-guide-pipeline.md)), so the reading
  cannot tell them apart. "a model to translate english to italian" and its reverse (0.931) have no subject to keep the
  direction.
- The embedding alone is not enough: "python bindings for rust" is 0.986 from its reverse, closer than most
  paraphrases. The subject order of the reading key keeps them apart.
- Missed paraphrases: "postgres" and "postgresql" are different subjects; "when is neurips" and "when does neurips take
  place" are 0.77 apart.

0.96 is the threshold that meets the rule (no false reuse on the set), with 36 % of the paraphrases reused. The default
stays off. Checked live on staging with the planner of the running release (1 Nov 2026): every negation and direction
pair gets two readings, except the translation pair above (only its cosine keeps it apart); 1 of the 6 paraphrase pairs
that reuse on the set was reused live, because the live planner reads two wordings of one need differently more often
than the set assumes. That lowers reuse, not precision. Staging runs the nightly guide evaluation with answers kept one
second ([0046](../operations/0046-evaluation.md)), so reuse is measured on prod, through `helper2.guide.cache`.

### Retention
A row of `guide_similar_question` lives as long as its answer. A read skips an expired row; the guide's purge
(`PurgeExpiredData`, run once a day by `guide.schedule.GuideRetentionSchedule` on `helper2.retention.cron`, UTC, under a
cluster lock) deletes it the next night ([0010](../foundation/0010-data-retention.md)). The answers themselves go with the
shared cache purge.

### Metrics
- `helper2.guide.cache{result}`: `exact` (key hit), `similar` (reused), `miss`, once per question when the cache is on.
- `helper2.guide.questions{outcome}`: `cached` (a reused answer too), `shared`, `client_quota`, `network_quota`,
  `answered`, `degraded`, `budget_exhausted`, `model_unavailable`, `no_candidates`, `timeout`, `abandoned`, `error`.
  `budget_exhausted` counts the answers that carry the notice `GUIDE_BUDGET_SPENT`: plan, choose, compare or explain
  found the guide's budget spent.
  `network_quota` counts every question refused because the asker's network is full. It carries no address and no
  network label, so it says how often the network cap (`H2_GUIDE_NETWORK_DAILY_QUESTIONS`) refuses, not who: the
  number to judge the default from. The dashboard shows it next to `client_quota`.
- `helper2.guide.quota.give_back{reason, outcome}`: reason `no_pick`, `failed`, `shared`, `abandoned`; outcome `given_back`,
  `already_given_back`, `cap_reached`, `nothing_counted`.
- `helper2.guide.budget.remaining`.

### Code

- `backend/apps/public/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `ClientQuota`, `GuideAnswerCache` (`key`,
  `normalized`, `put`), `GuideAnswerStore`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/SimilarQuestions.kt` — `ReadingKey`, `SimilarQuestions`,
  `QuestionEmbedder`, `SimilarQuestionIndex`, `SimilarQuestionRetention`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `ask` and `answerOnce` (order of the
  guards, config hash, single-flight, the lazy slot, `QuotaTurn.settle`), `similarAnswer`; `steps/Question.kt`
  (`Asked.modelFailed`, `budgetSpentNotice`); `frontend/src/features/guide/GuideAnswer.tsx` (`budgetSpentReason`); `PurgeExpiredData.kt`;
  `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/SingleFlight.kt`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/AnswerLimits.kt` — comparison and explanation limits in the
  config hash.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` — `getConfigFingerprint`, the
  rendered prompts.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideLimits.java` — questions per client, the lazy slot in
  the `guide` bulkhead (`LazySlot`), deadline;
  `GuideStreamController.java` — keep-alive comments.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SharedGuideAnswerStore.java`,
  `JdbcSimilarQuestionIndex.java`, `GuideConfiguration.java` (`similarQuestions`);
  `backend/apps/public/src/main/java/ai/helper2/guide/schedule/GuideRetentionSchedule.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ClientKey.java` (`of`, `network`), `quota/ClientBudgets.java`,
  `RateLimitInterceptor.java`,
  `RateLimiter.java`, `PgVectors.java`; `platform/llm/DailyBudget.java`, `RedisCallCounter.java` (the count and its
  give-back); `platform/llm/ModelCallMetrics.java` (`promptVersion`).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `guide_similar_question`.
- `docs/eval/guide-similar.json`, `guide-similar-vectors.json`;
  `backend/apps/public/src/test/kotlin/ai/helper2/eval/GuideSimilarEvaluation.kt`, `GuideSimilarEvaluationTest.kt`,
  `GuideSimilarRecorder.kt`.
- Tests: `ClientQuotaTest`, `GuideAnswerKeyTest`, `GuideAnswerCacheStoreTest`, `SimilarQuestionsTest`,
  `AskGuideSimilarTest`, `JdbcSimilarQuestionIndexIT`, `SharedGuideAnswerStoreTest`, `GuideSimilarConfigurationTest`,
  `GuideLimitsTest`, `SpringAiGuideModelTest` (prompt labels), `GuideApiIT`.
- A closed connection: `backend/apps/public/src/main/java/ai/helper2/guide/config/GuideProperties.java`
  (`stopsAQuestionNobodyWaitsFor`, `Thinking.inAGuideStep`), `AskGuide.kt` (`countedIfQuestionsStop`); tests
  `GuidePropertiesBindingTest` (the rule), `SingleFlightTest`, `AskGuideAbandonTest`, `GuideStreamLeaveTest`,
  `GuideLeaveOnTheWireTest` (a real socket closed on Tomcat, both sides of the rule), `AbandonOnTheWireTest` (the
  provider request of a stopped streamed call).

## Alternatives considered

- **Global cap only** — one client could spend the day in about twenty minutes at 10 a minute.
- **Error (429) past the quota** — the search-only answer is still useful and explains itself.
- **Count only after the answer, when it has a pick** — the model would run before the quota check, so a client past
  its quota could still spend model calls. Taking first and giving back keeps the check in front of the model.
- **Give back without a cap** — a client could loop questions that find nothing and spend the global cap for free.
- **Queue extra questions instead of 503** — a queue hides overload and makes every user wait longer.
- **Cache in memory only** — a restart throws away answers that cost three calls each, and each replica pays for its own.
- **Cache without a config hash** — a changed prompt or model would serve answers of the old one for 24 hours.
- **Redis for the answers and a Redis sorted set or Redis 8 vector set for the index** — per-entry time to live for
  free, but model answers are kept on disk and shared from PostgreSQL; a vector set has no time to live per element,
  and its default 8-bit quantisation shifts scores near the threshold.
- **A table of its own for the answers** — a second cache table and a second purge for what the shared cache does.
- **The question embeddings in the shared cache** — a key-value table cannot find the nearest vector.
- **pgvector with an HNSW index on `guide_similar_question`** — approximate, and the filter on group and expiry would
  need an iterative scan ([0024](../search/0024-vector-search-pgvector.md)); an exact scan of a few rows is simpler and
  exact.
- **Reuse before the plan, on the embedding alone** — saves the plan call, but the evaluation shows the embedding cannot
  tell a direction or a negation from a paraphrase.
- **A model call to judge whether two questions are the same** — one more call per miss, and the judge has the blind
  spots of the planner.
- **Reorder the words of "X vs Y" in the key** — the key is built before the plan, so it cannot know it is a comparison;
  "python for rust" would be reordered too.
- **Stemming, stop words or synonyms in the key** — each can change the answer ("free" vs "not free").

## Consequences

- The worst case per day is bounded: 600 model calls, whatever the traffic.
- One client can use at most 30 fresh questions with an AI answer a day, plus at most 30 with no pick or that failed
  (60 questions, about 180 calls), so a single client cannot spend the global cap alone.
- A visitor pays only for an answer that helps: a no-pick answer, a failed question and a cached answer leave the daily
  count as it was. Their model calls still count in the global cap.
- The same no-pick question asked again within 10 minutes is a cache hit: no model call.
- Spellings of one question share one answer; repeated example questions are free and instant, and a cached answer is
  honest about its age.
- With a threshold set, a paraphrase costs one fast-model call and one embedding instead of three model calls and its
  searches.
- The rate limit and the daily counts are in Redis and hold across replicas ([0043](../operations/0043-runtime-topology.md):
  3 on prod). Every replica reads the same answers from PostgreSQL; a restart keeps them, a deploy starts a new set.
  Single-flight and the concurrency limit stay per replica, on purpose.
- A new prompt wording, model or limit starts a fresh cache by itself, through the config hash.
- The quota's memory is bounded (10,000 clients a day), and no client address is stored.
- A guide question is embedded and its embedding kept next to its answer, for at most the answer's life (24 h) plus the
  night until the purge. Privacy page: "Guide question fingerprints" and the NVIDIA API catalog as a recipient.
- Every cache read and write is a database call on the guide's path (a primary-key lookup; the group scan reads a few
  rows), under the `db-cache` policy for the answers.

### STRIDE

| Threat | Risk | Control |
|---|---|---|
| Spoofing | A client spreads its questions over many addresses | IPv6 counted by its /64, and a network count per /56 (IPv4 per /24); the global cap opens in 2-hour parts and bounds the day whatever the client count. |
| Tampering | A wrong share: one asker's answer shown for another need | Reuse only for the same reading key and group, at a threshold with no false reuse on the eval set; the planner's words of the other asker are dropped from the reused answer. |
| Repudiation | Not knowing which prompts wrote a cached answer | The config hash covers the rendered prompts and models; each prompt's label is pinned by a test and tagged on every model call. |
| Information disclosure | Question text or client address kept | Keys are hashes, no store sees the whole question text (the search request is emptied before the answer is stored); the quota keeps a keyed pseudonym of the day, never an address; the question embedding lives only as long as its answer. |
| Denial of service / cost | A flood of questions, slow questions piling up | Per-minute limit, per-client and per-network quota with a capped give-back, a global daily cap paced through the day, 2 questions at once per client, a bulkhead of 8 per replica taken only for real work, a 90 s deadline, no model call without time left for it, no repeat of a call whose answer was cut at the token limit, a circuit that opens on slow model calls (a cancelled question does not count), single-flight. |
| Elevation of privilege | Raising the cap | Only an administrator changes the runtime cap, from the back office, up to 3000. |

### AI architecture check

| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: NVIDIA only ([0008](../foundation/0008-llm-integration.md)); a provider outage or a spent cap gives the degraded answer, never an error page. |
| 2 | Agent Framework | Covered: no agent loop; the guards wrap a fixed pipeline ([0031](0031-guide-pipeline.md)). |
| 3 | Orchestration | Covered: a slot, a deadline and single-flight per question; a model call starts only with time left for it; no server retry. |
| 4 | Tool & API | Covered: the embedder behind a port; Tavily searches under the asker's quota ([0027](../search/0027-tavily-store-and-daily-quota.md)). |
| 5 | Memory | Covered: answers for at most 24 h (6 h for a period), per release; question embeddings for the answer's life. |
| 6 | Knowledge & RAG | Covered: exact vector scan of a group's few rows; reuse needs the same reading. |
| 7 | Data | Covered: `ai_cache` (`guide-answer`), `guide_similar_question` in V1; the purge of [0010](../foundation/0010-data-retention.md). |
| 8 | Browser & Computer Use | Covered: none. |
| 9 | Guardrails | Covered: only complete, checked AI answers are cached; the reused answer keeps its marking. |
| 10 | Observability | Covered: cache, question, give-back and budget metrics; the config hash on the Settings page; no question text in metrics. |
| 11 | Evaluation | Covered: `GuideSimilarEvaluationTest` on 55 fixed pairs in every build; prompt labels pinned by a test. |
| 12 | Deployment | Covered: config only; reuse off by default, 0.96 on prod; a deploy starts a new cache. |
| 13 | Security & Identity | Covered: `H2_QUOTA_KEY_SECRET` from OpenBao; no new secret. |
| 14 | Human-in-the-Loop | Covered: an administrator sets the daily cap. |
| 15 | Cost & Reliability | Covered: every guard above; a database or embedding error is a miss, never a failed question. |

### Points for a lawyer

1. Is a question embedding personal data under the nFADP and the GDPR, and what is the legal basis for keeping it?
2. The transfer of the question text to the NVIDIA API catalog in the United States.
3. The cached answer keeps the planner's search terms and the names read from the question, shown to anyone who
   asks the same or a similar question. Is the Privacy sentence ("never your whole question") enough, and is the
   retention of those words covered by the answer's retention period?

### Known limits

- A guide question can answer up to 3 s after its 90 s deadline. The margin before the deadline (2 s) and the shortest model call (10 s) are fixed in the yaml; a call started with little time left takes a budget unit even when it runs out of time. The tool guide (`POST /resources/{id}/guide`) has no question deadline: it runs on the request thread, each of its model calls is bounded by `H2_LLM_TIMEOUT` alone, and nothing bounds the whole request; a failed call gives status `LLM_UNAVAILABLE`.
- The time on the notices `GUIDE_BUDGET_SPENT`, `GUIDE_DAILY_QUOTA` and `GUIDE_NETWORK_QUOTA` and on the setup guide (`retryAt`) is when the next share opens, not a promise: other visitors can use it first. For `GUIDE_BUDGET_SPENT` it comes from what one replica knows of the count, so during a Redis outage two replicas can give different times.
- A question nobody waits for stops only with `H2_GUIDE_MODEL_STREAM=ON` and a thinking budget for plan, choose or
  verify; in every other case (the defaults) it runs on and is cached. The rule is per deployment, not per question:
  with it on, a quick question that does not think is stopped too and loses its cache hit, and a browse by date,
  whose choose call thinks by default (`H2_GUIDE_THINK_BROWSE_TOKENS`), does not turn it on. Where it stops, it stops
  only when the server sees the closed connection: at the second write after the
  close, so one to two `:keep-alive` (15 to 30 s; measured 1.2 to 1.9 s with a 1 s keep-alive on a local Tomcat)
  plus the 3 s leave grace after the browser left; not measured behind ingress-nginx; a proxy that keeps the connection open hides it
  longer. The askers are counted per replica, as single-flight is. The model calls made before the stop are paid. With `H2_GUIDE_MODEL_STREAM=ON` a call interrupted before
  its response headers runs to its timeout. The log line of a question cut by a leave says "cancelled at its
  deadline".
- Clients behind one shared address share one guide quota; the quota is taken before the question runs, so a failed question still counts.
- Similar-question reuse shares an answer only between questions read the same way; the reading cannot tell "this
  year" from "last year", and a question with no subject keeps no direction, which is why the threshold is high and
  most paraphrases still miss.

## Diagrams

The cost guard: [flow](../../diagrams/flow-0032-guide-cost-guard.svg) · [sequence](../../diagrams/sequence-0032-guide-cost-guard.svg) ·
the answer cache: [flow](../../diagrams/flow-0032-guide-answer-cache.svg) · [sequence](../../diagrams/sequence-0032-guide-answer-cache.svg) ·
[architecture: guide](../../diagrams/architecture-guide.svg)

## Backlog

- Features: [F4.2](https://github.com/silvioincalza/helper2.ai/issues/65) Cost guard and answer cache, [F4.6](https://github.com/silvioincalza/helper2.ai/issues/69) Similar-question reuse of answers.
- Open issues that change this decision:
  - [#177](https://github.com/silvioincalza/helper2.ai/issues/177) S1.10.1 To know when the per-replica guide single flight costs money
  - [#281](https://github.com/silvioincalza/helper2.ai/issues/281) S4.6.2 The similar-question threshold checked on live traffic
  - [#293](https://github.com/silvioincalza/helper2.ai/issues/293) S5.5.1 10 web searches and 10 AI answers a day instead of 3
