# 0009 — Ranking: every query word first, synonyms, two-stage index query, reciprocal-rank fusion, bounded popularity

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0008](0008-federated-search.md)

## Context
Each source ranks its own hits. Helper2.ai must put them in one order that is repeatable and easy to explain.
The catalog index must also rank its own rows. Text density alone is not relevance: for "MCP server Postgres"
(MCP: Model Context Protocol) a full-text score puts short npm names ahead of `crystaldba/postgres-mcp` (3.4k stars).
The index query must also stay well inside its 3 s timeout on the production catalog.

## Decision
1. **Query analysis without a model** (`QueryAnalyzer`). Type words become type hints and are removed from the required words:
   "library", "libraries", "package", "libreria" → `LIBRARY`; "model", "models", "modello" → `MODEL`; "dataset(s)" →
   `DATASET`; "server(s)" next to "mcp" → `MCP_SERVER`. Fixed synonym groups (postgres/postgresql, js/javascript,
   ts/typescript, k8s/kubernetes) give up to 4 required-word variants. A fixed English/Italian lexicon adds use cases
   and English expansions, shown to the user.
2. **Index query in two stages** (`ResourceStore.searchSql`). A row is retrieved when any word matches
   (`websearch_to_tsquery` with `english`, `simple` and the query language's configuration, generalized inverted index (GIN)) or when the title is
   similar: `lower(title) % lower(:raw)` (trigram GIN index) and `similarity > 0.35`. Stage 1 orders all matches by
   "contains every word of a variant", then `popularity + similarity`, and keeps 300 rows (`PRESELECT`). Stage 2 scores
   only those: `2 (all words) + popularity + 0.5 × min((ts_rank_cd × 3 + similarity) / 10, 1)`, then title.
   The query runs in about 0.2 s on the production catalog.
3. **Stored popularity.** `resource.popularity` (0..1) is kept by the trigger `source_record_popularity`:
   the strongest metric of the resource's records, `ln(1 + value) / ln(1 + reference)`, capped at 1.
   References: GitHub stars 50,000; Hugging Face likes 5,000; Hugging Face downloads 5,000,000; npm weekly downloads 1,000,000.
4. **Facts in batch.** `ResourceStore.withFacts` loads labels for all returned rows in one query and popularity metrics
   in one query (newest value per kind and source). So `CatalogSearchSource` and `SemanticSearchSource` hits carry the
   same metrics a live hit carries.
5. **Fusion across sources** (`Ranking.rank`, `k = 60`). Score = Σ over sources of `1 / (k + rank + 1)`.
   Boosts: +0.05 when the title equals the query, else +0.02 when the title holds every word of the first variant
   (type words removed);
   +0.01 audience match and +0.005 type match for the chosen path; +0.005 when the type is a type hint.
6. **Bounded popularity.** + `0.008 × popularity` (same references, strongest metric only, never summed).
   0.008 is about half the fusion score of a first place in one source (1/61). Ties: title, then id.

## Consequences
- The same query gives the same order. Every boost is a fixed, visible rule.
- A popular project that matches poorly stays below a good match; popularity only breaks near-ties.
- The index query scores only 300 rows, so it stays fast on a large catalog.

## Alternatives considered
- Full-text score alone — it measures word density, not relevance (see Context).
- Popularity as a main signal — popular but unrelated results would win.
- Re-ranking with a model — cost and latency per search, and not repeatable.
- A learned ranking model — there is no labeled data to train or check it.

## Code
- `backend/src/main/kotlin/ai/helper2/search/QueryAnalyzer.kt` — type hints, synonyms, required-word variants, lexicon.
- `backend/src/main/kotlin/ai/helper2/search/Ranking.kt` — fusion, boosts, bounded popularity.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `searchSql`, `search`, `withFacts`, `popularity`.
- `backend/src/main/java/ai/helper2/catalog/CatalogSearchSource.java` — index rows as ranked hits.
- `backend/src/main/resources/db/migration/V1__schema.sql` — `search_vector`, trigram index, popularity trigger.

## Diagrams
[flow](../../diagrams/flow-0009-ranking.svg) · [sequence](../../diagrams/sequence-0009-ranking.svg)

## Roadmap
- [Ranking measured on a labeled query set](../../roadmap.md#quality-gates).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
