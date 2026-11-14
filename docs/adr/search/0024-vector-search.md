# 0024 — Vector search

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0022](0022-federated-search.md), [0025](0025-ranking.md).

## Context

Keyword search misses a resource when the user describes a need in different words than the resource uses.
Search by meaning needs vectors for every catalog resource, kept current when the text changes, and a query vector
per search. The embedding application programming interface (API) has a rate limit and can fail. A failure must only remove the results by meaning,
never break keyword search.

## Decision

1. **Store vectors in PostgreSQL** (pgvector). Table `resource_embedding` (resource id, model, `text_hash`,
   `vector(1024)`), primary key (resource, model), Hierarchical Navigable Small World (HNSW) index with cosine distance. The vector belongs to one model,
   so a model change re-embeds everything instead of mixing spaces: storing a resource's vector under the configured
   model deletes its vectors of every other model, so the table and the index hold one vector space.
2. **NVIDIA embeddings** (`NvidiaEmbedder`, port `Embedder`). `POST {base}/embeddings` with `input_type` `query` or
   `passage`, `truncate=END`, 1024 dimensions. The model also embeds images: NVIDIA reads a base64 data URI
   (`data:<mime>;base64,…`) anywhere in a text as an image and rejects the whole call with 422 when the payload is no
   image, so every base64 data URI in a text or a query becomes a space before the call. The stored `text_hash`
   stays on the text as written. Settings: `H2_NVIDIA_API_KEY`, `H2_NVIDIA_EMBEDDING_BASE_URL`
   (`https://integrate.api.nvidia.com/v1`), `H2_NVIDIA_EMBEDDING_MODEL` (`nvidia/nemotron-3.5-embed-1b`).
   Hypertext Transfer Protocol (HTTP) status 429 is reported as rate-limited. The response must hold one vector of the right size per input.
   The client is the observed client from [0006](../foundation/0006-outbound-calls-and-resilience.md) (span, `http.client.requests`), with redirects off
   and a buffered body that carries a `Content-Length`.
3. **Off without a key.** All beans live in `CatalogConfiguration.VectorSearch`, active only when
   `helper2.embedding.api-key` is set. Without it, search is keyword-only.
4. **Indexer** (`IndexEmbeddings.tick`) on its own thread `embedding-indexer`, fixed delay: first after 30 s, then
   every 5 s (`helper2.catalog.indexer.every`, `H2_EMBEDDING_EVERY`). Each tick embeds up to 1 resource
   (`helper2.catalog.indexer.batch`, `H2_EMBEDDING_BATCH`) that has no
   vector for the model or whose `md5(title + "\n" + description)` changed, most popular first; demo rows are skipped.
   It uses its own client with a 30 s timeout (`helper2.catalog.indexer.timeout`) and its own circuit, `llm-embed-index`
   ([0006](../foundation/0006-outbound-calls-and-resilience.md)), so its failures never refuse a search query's embedding. After a rate limit it pauses for 2 min.
   When the API rejects a batch (an error answer: 5xx or another status that is not 429), the tick sends up to 5 of
   its texts one per call (`helper2.catalog.indexer.singles-after-failure`); a text that fails on its own waits for
   a later tick, and a rate limit, a refused call or a timeout ends the round. NVIDIA failed most calls of two or
   more texts with 500 "service inference failed" while every single text went through (staging, 5 Nov 2026):
   without the fallback the same batch failed every tick and the backlog never moved. The log line of a rejected
   batch carries the provider's reason from the body, cut to 200 characters on one line; a query's failure carries
   the status only, since a body can echo the visitor's text.
   NVIDIA fails every call of three or more texts and most calls of two (staging, measured on 11 Nov 2026: 1
   text 5 of 6, 2 texts 2 of 6, 3 to 50 texts 0), and each failed batch counts against the circuit, which then
   refuses the single texts too. So one text goes per call: 12 calls a minute for the cluster, under the trial
   limit of 40 a minute, about 17,000 resources a day. `H2_EMBEDDING_BATCH` raises it again when NVIDIA batches.
   Every replica runs the thread, but a tick runs only while it holds the cluster lock `embedding-indexer`
   (`platform.ClusterLock`, ShedLock row in PostgreSQL, see [0011](../scout/0011-scout-runner.md)): held at least 90 % of
   the period (4.5 s) and at most 3 × the timeout × (1 + the single texts after a failure) + 1 min (10 min with
   30 s and 5 singles; the lock is not renewed). So the cluster embeds one batch per
   period, as with one replica; a replica whose tick finds the lock taken skips that period.
   Meters: `helper2.embedding.backlog`, `helper2.embedding.failures{reason}`, `helper2.embedding.last.success`.
   The backlog counts what the indexer still has to do: rows with no vector for the model and rows whose text changed
   since their vector was made. The query that picks the batch counts it too (`count(*) OVER ()` before the limit),
   so the two always agree and there is no second scan; after a stored batch the gauge drops by the batch size.
   A process that has not run a tick yet has not counted: its gauge reports `NaN`, not 0. After a start that lasts
   until the lock of the replica before it runs out, up to 10 min.
5. **One more source** (`SemanticSearchSource`, id `helper2-semantic`, mode `INDEXED`, priority 55). It embeds the
   query as typed (`usesTranslation = false`, [0023](0023-catalog-full-text-index.md)) and returns the nearest rows of the index, curated list and observed resources with cosine distance at most
   `H2_SOURCE_SEMANTIC_MAX_DISTANCE` (0.65), within `H2_SOURCE_SEMANTIC_TIMEOUT` (3 s).
   Rows carry labels and stored popularity like keyword rows ([0025](0025-ranking.md)).
   Query vectors are shared by every replica in the shared cache `query-vector` (`QueryVectorCache`, table `ai_cache`,
   [0004](../foundation/0004-persistence-and-the-schema.md)): key a SHA-256 of the embedding model and the normalised query,
   value the floats in Base64, kept `H2_SEARCH_QUERY_VECTOR_TTL` (7 days). A vector of another size is a miss, and so is
   a database error or a lookup over its time limit: the query is embedded again. So the guide's example questions and pagination spend the embedding rate limit once for the cluster.
   The model, origin and distance filters apply to the HNSW candidates, so the query runs in its own transaction with
   `SET LOCAL hnsw.iterative_scan = relaxed_order` (pgvector 0.8): the scan reads on until the limit is met instead of
   stopping after one candidate list. The rows are sorted by distance again, since relaxed order may return them
   slightly out of order.
6. **Fused by rank.** `SearchResources` treats it like any source, so `Ranking` adds `1 / (k + rank + 1)` for it.
   Distances and keyword scores are never compared directly. Embedding 429 → `SourceFailure.RateLimited` (1 min cooldown).

### Code

- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/SemanticSearchSource.java`, `QueryVectorCache.java` — the source and its shared
  query vectors; `platform/cache/JdbcSharedCache.java` — the shared cache.
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/IndexEmbeddings.java` — batches, pause, meters.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/NvidiaEmbedder.java`, `catalog/Embedder.java` — API client and port.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/CatalogConfiguration.java` — conditional beans, `IndexerSchedule`, indexer lock.
- `backend/libs/platform/src/main/java/ai/helper2/platform/ClusterLock.java` — lock shared by every replica.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcEmbeddingStore.java` — `pendingEmbeddings` (batch and backlog), `saveEmbeddings` (drops other models' vectors), `semanticSearch` (iterative scan).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `resource_embedding`, HNSW index.

## Alternatives considered

- Query vectors in Redis — about 5.5 kB each for 7 days in the memory that sessions and rate limits share, and not in
  the backups.
- Query vectors as `vector(N)` in their own table — no vector search runs on them; Base64 text in the one shared cache
  table needs no table per embedding dimension.
- A separate vector database — more operational work for a catalog of this size.
- One Structured Query Language (SQL) query that mixes text and vector scores — the two scales do not compare, and one failure would remove both.
- `hnsw.iterative_scan = strict_order` — exact order is not needed (Ranking uses the rank only) and costs more index reads.
- A partial HNSW index per model — one index per model id, created at deploy; one model at a time is simpler.
- Re-ranking with a model — cost and latency per search.
- Query vectors in each replica's memory — each of the 3 prod replicas pays its own embedding call for the same query.
- An in-memory cache in front of the table — it saves a lookup of about 1 ms next to an embedding call of about 0.3 s, for
  a second TTL and size bound to reason about.

## Consequences

- Needs described in other words find resources; a resource found both ways ranks higher.
- No new stateful service; vectors are derived data and safe to truncate.
- A query vector is kept for 7 days, under a hash of the query; the privacy page states it. The daily shared cache
  purge deletes it afterwards ([0010](../foundation/0010-data-retention.md)).

### Known limits

- A new or changed resource has no current vector until the indexer reaches it. The 0.65 cutoff comes from one set of measurements. A text the API always rejects is never skipped: it stays in the batch at its popularity rank, fails every batch it is in and takes one of the 5 single calls each tick. Decide whether such a row is set aside after a few failures.

## Diagrams

[flow](../../diagrams/flow-0024-vector-search-pgvector.svg) · [sequence](../../diagrams/sequence-0024-vector-search-pgvector.svg)

## Backlog

- Features: [F3.3](https://github.com/silvioincalza/helper2.ai/issues/49) Vector search: pgvector + NVIDIA embeddings.
- Open issues that change this decision:
  - [#178](https://github.com/silvioincalza/helper2.ai/issues/178) S1.10.2 To know when per-replica limits hurt
  - [#231](https://github.com/silvioincalza/helper2.ai/issues/231) S3.3.2 The embedding indexer to send texts in batches again
  - [#256](https://github.com/silvioincalza/helper2.ai/issues/256) S3.14.1 Search by meaning to match English resources
