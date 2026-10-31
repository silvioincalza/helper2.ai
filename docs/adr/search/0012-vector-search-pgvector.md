# 0012 — Vector search: pgvector + NVIDIA embeddings, indexer, fused by rank

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0008](0008-federated-search.md), [0009](0009-ranking.md)

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
   `passage`, `truncate=END`, 1024 dimensions. Settings: `H2_NVIDIA_API_KEY`, `H2_NVIDIA_EMBEDDING_BASE_URL`
   (`https://integrate.api.nvidia.com/v1`), `H2_NVIDIA_EMBEDDING_MODEL` (`nvidia/nemotron-3.5-embed-1b`).
   Hypertext Transfer Protocol (HTTP) status 429 is reported as rate-limited. The response must hold one vector of the right size per input.
   The client is the observed client from [0006](../foundation/0006-outbound-http.md) (span, `http.client.requests`), with redirects off
   and a buffered body that carries a `Content-Length`.
3. **Off without a key.** All beans live in `CatalogConfiguration.VectorSearch`, active only when
   `helper2.embedding.api-key` is set. Without it, search is keyword-only.
4. **Indexer** (`EmbeddingIndexer.tick`) on its own thread `embedding-indexer`, fixed delay: first after 30 s, then
   every 15 s (`helper2.embedding.every`). Each tick embeds up to 50 resources (`helper2.embedding.batch`) that have no
   vector for the model or whose `md5(title + "\n" + description)` changed, most popular first; demo rows are skipped.
   It uses its own client with a 30 s timeout (`helper2.embedding.index-timeout`). After a rate limit it pauses for 2 min.
   Every replica runs the thread, but a tick runs only while it holds the cluster lock `embedding-indexer`
   (`platform.ClusterLock`, ShedLock row in PostgreSQL, see [0018](../scout/0018-scout-runner.md)): held at least 90 % of
   the period (13.5 s) and at most 3 × the timeout + 1 min (2 min 30 s). So the cluster embeds one batch per
   period, as with one replica; a replica whose tick finds the lock taken skips that period.
   Meters: `helper2.embedding.backlog`, `helper2.embedding.failures{reason}`, `helper2.embedding.last.success`.
5. **One more source** (`SemanticSearchSource`, id `helper2-semantic`, mode `INDEXED`, priority 55). It embeds the
   query as typed (`usesTranslation = false`, [0011](0011-language-aware-search.md)) and returns the nearest rows of the index, curated list and observed resources with cosine distance at most
   `H2_SOURCE_SEMANTIC_MAX_DISTANCE` (0.65), within `H2_SOURCE_SEMANTIC_TIMEOUT` (3 s).
   Rows carry labels and stored popularity like keyword rows ([0009](0009-ranking.md)).
   Query vectors are shared by every replica in the shared cache `query-vector` (`QueryVectorCache`, table `ai_cache`,
   [0004](../foundation/0004-persistence-and-provenance.md)): key a SHA-256 of the embedding model and the normalised query,
   value the floats in Base64, kept `H2_SEARCH_QUERY_VECTOR_TTL` (7 days). A vector of another size is a miss, and so is
   a database error or a lookup over its time limit: the query is embedded again. So the guide's example questions and pagination spend the embedding rate limit once for the cluster.
   The model, origin and distance filters apply to the HNSW candidates, so the query runs in its own transaction with
   `SET LOCAL hnsw.iterative_scan = relaxed_order` (pgvector 0.8): the scan reads on until the limit is met instead of
   stopping after one candidate list. The rows are sorted by distance again, since relaxed order may return them
   slightly out of order.
6. **Fused by rank.** `SearchResources` treats it like any source, so `Ranking` adds `1 / (k + rank + 1)` for it.
   Distances and keyword scores are never compared directly. Embedding 429 → `SourceFailure.RateLimited` (1 min cooldown).

## Consequences
- Needs described in other words find resources; a resource found both ways ranks higher.
- No new stateful service; vectors are derived data and safe to truncate.
- A query vector is kept for 7 days, under a hash of the query; the privacy page states it. The daily shared cache
  purge deletes it afterwards ([0034](../operations/0034-data-retention.md)).

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

## Code
- `backend/src/main/java/ai/helper2/catalog/SemanticSearchSource.java`, `QueryVectorCache.java` — the source and its shared
  query vectors; `platform/cache/JdbcSharedCache.java` — the shared cache.
- `backend/src/main/java/ai/helper2/catalog/internal/EmbeddingIndexer.java` — batches, pause, meters.
- `backend/src/main/java/ai/helper2/catalog/internal/NvidiaEmbedder.java`, `catalog/Embedder.java` — API client and port.
- `backend/src/main/java/ai/helper2/catalog/CatalogConfiguration.java` — conditional beans, `IndexerSchedule`, indexer lock.
- `backend/src/main/java/ai/helper2/platform/ClusterLock.java` — lock shared by every replica.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `pendingEmbeddings`, `saveEmbeddings` (drops other models' vectors), `semanticSearch` (iterative scan).
- `backend/src/main/resources/db/migration/V1__schema.sql` — `resource_embedding`, HNSW index.

## Diagrams
[flow](../../diagrams/flow-0012-vector-search-pgvector.svg) · [sequence](../../diagrams/sequence-0012-vector-search-pgvector.svg)

## Roadmap
- [Full vector index before recording the video](../../roadmap.md#1-before-the-hackathon-deadline-30-oct-2026-1800-zurich).
- [The embedding backlog gauge does not count rows whose text changed](../../roadmap.md#6-search-and-catalog).
- [Cross-language retrieval in vector search](../../roadmap.md#5-ai-quality-and-cost).
- [Rate-limit pause stays per replica, on purpose](../../roadmap.md#4-more-than-one-replica).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
