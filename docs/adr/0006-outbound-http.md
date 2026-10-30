# 0006 — Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; safe redirects; bounded bodies; error mapping

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0005](0005-redis-disposable-cache.md), [0033](0033-resilience-policies.md)

## Context
Every search calls several public Application Programming Interfaces (APIs) in parallel, and the Scout
calls more in the background. Each one can be slow, rate-limited or broken. One slow source must not hold up
a search, and a rate limit must lead to a pause, not to more calls. Remote hosts and their data are untrusted:
a redirect must not carry a token elsewhere, a body must not fill the heap, and one bad item must not cost the
whole answer. Failures must reach the search as a small set of known outcomes, so the source status panel can
state what happened.

## Decision
1. **One observed client for every remote API.** `platform.http.ObservedHttp` builds the Spring `RestClient` on
   Apache HttpClient 5 (5.6.5) with a connection pool (50 connections, 20 per host). Sources and Scout collectors use
   `ObservedHttp.restClientBuilder(timeout, observations)`. It lives in `platform`, so the catalog module (embeddings)
   can use it too without depending on `sources`. `sources.HttpClients` adds only the base URL, the token and the
   error mapping. The client sends each request once (its automatic retries are off): retries belong to the
   caller's resilience policy ([0033](0033-resilience-policies.md)), where they are counted and bounded.
2. **Timeouts.** The source's timeout is used once as the connect timeout and once as the response timeout.
   Waiting for a free pooled connection is limited to 250 ms (`POOL_WAIT`). Search source timeouts are
   2.5 s (GitHub, Hugging Face, npm), 3 s (arXiv) and 6 s (Tavily, `H2_SOURCE_TAVILY_TIMEOUT`); Scout
   collectors use 10 s or 15 s.
3. **Redirects.** `NoDowngradeRedirects` follows redirects, except one from `https` to `http`. A request that
   carries `Authorization` is not redirected to another scheme, host or port (HttpClient's `isRedirectAllowed`,
   kept by `NoDowngradeRedirects`): a token only ever reaches the configured host, and the unfollowed 3xx ends the call
   as `Unavailable`. A redirected `POST` (Tavily) is re-sent as a `GET` without the source's headers.
4. **Identity and tokens.** Every call sends `User-Agent: Helper2.ai-metasearch/<version> (+https://helper2.ai/sources)`.
   The version is the build version from the boot jar's manifest (`1.0.0`, [0029](0029-release-versioning.md));
   `dev` when the app runs from classes.
   A token, when set (`H2_GITHUB_TOKEN`, `H2_HF_TOKEN`, `H2_TAVILY_API_KEY`), goes in `Authorization: Bearer`.
5. **Bounded bodies.** Source clients (`restClientBuilder`) read at most 2 MB of a response body
   (`MAX_RESPONSE_BYTES`); a call may set its own cap with `ObservedHttp.maxResponseBytes`, as the awesome-list
   README fetch does (5 MB). A longer body stops the read and ends the call as `Unavailable("response too large")`.
6. **Error mapping.** `HttpClients.getText` and `getJson` turn every failure into a `SourceFailure`:
   - 429, 403 with `x-ratelimit-remaining: 0`, or 503 with `Retry-After` → `RateLimited`, with the pause
     from `Retry-After` or `x-ratelimit-reset`;
   - any other status → `Unavailable("HTTP <status>")`; network error or timeout → `Unavailable`;
   - a body that is not a JavaScript Object Notation (JSON) object or array → `Unavailable`.
   `Unavailable` is `transient` for a 503 and for a network error or timeout: the same call may succeed in a moment,
   so a Scout collector's policy retries it ([0033](0033-resilience-policies.md)); any other status or body is not.
7. **One bad item is not a failed source.** `HttpClients.hits` maps each item on its own: an item whose mapping
   throws is dropped and counted in `helper2.source.items.dropped{source, reason}` (reason = exception type, never
   source text), and the other items are returned. Mappers build links from source data with `CanonicalUrl` and
   `HttpClients.link`, never with a bare `URI.create`.
8. **Outcomes in the search.** A `SourceFailure` becomes the source's outcome: `RateLimited` → `RATE_LIMITED`
   with a pause, `Unavailable` → `FAILED`. Deadlines, cancellation, cooldowns and the source's circuit breaker are
   decided in [0008](0008-federated-search.md); a stale cache entry can still answer ([0005](0005-redis-disposable-cache.md)).
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
   `Content-Length` (a chunked body of 50 texts made the API hang until the read timeout). Timeouts: 3 s for
   queries, 30 s for indexing batches ([0012](0012-vector-search-pgvector.md)).
12. **Model calls** go through Spring AI's own client, with `H2_LLM_TIMEOUT` (40 s) and `H2_LLM_MAX_RETRIES` (0, so the SDK does not retry);
   see [0014](0014-llm-integration.md). They are observed by `ModelCallMetrics`, not by `ObservedHttp`.

## Consequences
- A failing source costs at most its timeout, and the search still answers with the other sources.
- Rate limits are respected: during the pause the source is not called (status `SKIPPED`).
- arXiv gets at most one call every 3 seconds whatever the number of replicas; two searches for papers inside the
  same 3 seconds get arXiv once, the second from the cache or not at all. While Redis is down, arXiv is not called.
- One request per attempt: a provider that answers 503 sees exactly the calls the policies allow, never a hidden
  second one from the client.
- The response timeout applies to each read, not to the whole call. A source that sends data slowly can
  keep a pooled connection longer, but the search has already given up at the deadline, and its slow call counts
  against the source's circuit ([0033](0033-resilience-policies.md)). A Scout collector has no deadline of its own: a
  slow read costs its run time, at most once per attempt of its policy.
- Embedding calls have the same span, `http.client.requests` timer and bounded `uri` tag as source calls.
- A host that redirects a token-carrying call elsewhere, streams an oversized body or sends a malformed item
  costs that call or that item, never a token leak, the heap or the whole source.
- `HttpClientsMappingTest`, `TokenRedirectTest`, `ArxivSourceTest`, `RedisOutboundThrottleIT`, `ObservedHttpRedirectTest`,
  `ObservedHttpRetryTest`, `ObservedHttpObservationTest` and `ObservedHttpUserAgentTest` cover the rules.

## Alternatives considered
- The Java Development Kit (JDK) `HttpClient` — no connection pool limits or redirect strategy hooks of this kind.
- Spring WebClient (reactive) — a second programming model; virtual threads already make blocking calls cheap.
- Retries in the HTTP client — invisible to the circuit breaker and multiplied by any retry of the caller.
- Retries on the search path — they would spend more of the search deadline; the pause after a rate limit and the
  stale cache cover the main cases ([0033](0033-resilience-policies.md)).
- arXiv pace per pod, or with Resilience4j's rate limiter — both count in one JVM, so 3 replicas send up to 3 calls
  in 3 seconds.
- arXiv pace failing open while Redis is down — each replica would call arXiv on its own pace, against its terms.

## Code
- `backend/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — pooled client, timeouts, redirects,
  body cap, User-Agent, observation and `uri` tag.
- `backend/src/main/java/ai/helper2/sources/HttpClients.java` — base URL, token, error mapping, per-item guard.
- `backend/src/main/java/ai/helper2/sources/SourcesConfiguration.java` — one builder per source and collector.
- `backend/src/main/java/ai/helper2/sources/ArxivSource.java`, `platform/resilience/RedisOutboundThrottle.java` —
  one arXiv call every 3 seconds across replicas.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` (`Run.collect`) — deadlines and outcomes.
- `backend/src/main/kotlin/ai/helper2/search/Model.kt` (`SourceFailure`) — failure types.
- `backend/src/main/java/ai/helper2/catalog/internal/NvidiaEmbedder.java` — embedding client on `ObservedHttp`.

## Diagrams
[flow](../diagrams/flow-0006-outbound-http.svg) · [sequence](../diagrams/sequence-0006-outbound-http.svg)

## Roadmap
- Total deadline per call instead of a per-read timeout: [roadmap](../roadmap.md#6-search-and-catalog).
- Run `./gradlew liveTest` against production egress: [roadmap](../roadmap.md#quality-gates).
