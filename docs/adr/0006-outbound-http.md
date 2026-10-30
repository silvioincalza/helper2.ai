# 0006 — Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; safe redirects; bounded bodies; error mapping

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0005](0005-redis-disposable-cache.md)

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
   error mapping.
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
7. **One bad item is not a failed source.** `HttpClients.hits` maps each item on its own: an item whose mapping
   throws is dropped and counted in `helper2.source.items.dropped{source, reason}` (reason = exception type, never
   source text), and the other items are returned. Mappers build links from source data with `CanonicalUrl` and
   `HttpClients.link`, never with a bare `URI.create`.
8. **Outcomes in the search.** A `SourceFailure` becomes the source's outcome: `RateLimited` → `RATE_LIMITED`
   with a pause, `Unavailable` → `FAILED`. Deadlines, cancellation and cooldowns are decided in
   [0008](0008-federated-search.md); a stale cache entry can still answer ([0005](0005-redis-disposable-cache.md)).
9. **Metrics and traces without user text.** Calls are observed (`http.client.requests`, one client span,
   trace context sent on). `BoundedUriConvention` tags the path with numeric and id-like segments replaced
   by `{id}`, and drops the query string from the tag and from the span's `http.url`.
10. **Embeddings.** `NvidiaEmbedder` uses the same observed client with its own settings
   (`ObservedHttp.requestFactory(timeout, timeout, false)`): its timeout also bounds the pool wait, redirects are
   not followed (the API key never goes to another host), and the body is buffered so the request carries a
   `Content-Length` (a chunked body of 50 texts made the API hang until the read timeout). Timeouts: 3 s for
   queries, 30 s for indexing batches ([0012](0012-vector-search-pgvector.md)).
11. **Model calls** go through Spring AI's own client, with `H2_LLM_TIMEOUT` (40 s) and `H2_LLM_MAX_RETRIES` (0, so the SDK does not retry);
   see [0014](0014-llm-integration.md). They are observed by `ModelCallMetrics`, not by `ObservedHttp`.

## Consequences
- A failing source costs at most its timeout, and the search still answers with the other sources.
- Rate limits are respected: during the pause the source is not called (status `SKIPPED`).
- arXiv also enforces at least 3 seconds between calls in each pod (replica); a call sooner is `RATE_LIMITED`.
  With 3 replicas on prod, arXiv can get up to 3 calls in 3 seconds.
- The response timeout applies to each read, not to the whole call. A source that sends data slowly can
  keep a pooled connection longer, but the search has already given up at the deadline.
- Embedding calls have the same span, `http.client.requests` timer and bounded `uri` tag as source calls.
- A host that redirects a token-carrying call elsewhere, streams an oversized body or sends a malformed item
  costs that call or that item, never a token leak, the heap or the whole source.
- `HttpClientsMappingTest`, `TokenRedirectTest`, `ArxivSourceTest`, `ObservedHttpRedirectTest`,
  `ObservedHttpObservationTest` and `ObservedHttpUserAgentTest` cover the rules.

## Alternatives considered
- The Java Development Kit (JDK) `HttpClient` — no connection pool limits or redirect strategy hooks of this kind.
- Spring WebClient (reactive) — a second programming model; virtual threads already make blocking calls cheap.
- Resilience4j retry and rate limiter — retries would spend more of the search deadline; the pause after
  a rate limit already covers the main case.

## Code
- `backend/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — pooled client, timeouts, redirects,
  body cap, User-Agent, observation and `uri` tag.
- `backend/src/main/java/ai/helper2/sources/HttpClients.java` — base URL, token, error mapping, per-item guard.
- `backend/src/main/java/ai/helper2/sources/SourcesConfiguration.java` — one builder per source and collector.
- `backend/src/main/java/ai/helper2/sources/ArxivSource.java` — minimum interval between calls.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` (`Run.collect`) — deadlines and outcomes.
- `backend/src/main/kotlin/ai/helper2/search/Model.kt` (`SourceFailure`) — failure types.
- `backend/src/main/java/ai/helper2/catalog/internal/NvidiaEmbedder.java` — embedding client on `ObservedHttp`.

## Diagrams
[flow](../diagrams/flow-0006-outbound-http.svg) · [sequence](../diagrams/sequence-0006-outbound-http.svg)

## Roadmap
- Total deadline per call instead of a per-read timeout: [roadmap](../roadmap.md#6-search-and-catalog).
- Resilience4j for outbound calls (rate limit, retry with backoff): [roadmap](../roadmap.md#5-ai-quality-and-cost).
- Run `./gradlew liveTest` against production egress: [roadmap](../roadmap.md#quality-gates).
