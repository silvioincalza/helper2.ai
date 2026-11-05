# 0055 — Search inside awesome lists: a tsvector per entry and per list, never the README text

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0009](0009-ranking.md), [0011](0011-language-aware-search.md), [0018](../scout/0018-scout-runner.md), [0020](../scout/0020-scout-collectors.md), [0034](../operations/0034-data-retention.md), [0042](../frontend/0042-redesign-design-system-v4.md)

## Context
The awesome-list collector ([0020](../scout/0020-scout-collectors.md) §5) reads the README of the top 10 AI awesome
lists and keeps the GitHub repositories they link. Until now it threw the list's words away. But those words are often
the best description of a tool: a list puts a small vector store under "Tools > Vector databases" and writes "embedded,
runs in the browser" next to it, while the repository's own description says only "Fast storage". A search for
"vector database" did not find it.
I want a search to also find a resource by the words a list uses around it (its entry line and its section heading),
and to find the lists themselves by the topics they cover. Without keeping the README text: it is the list author's
work, and we only need it to be searchable.

## Decision
1. **What is read.** `AwesomeListParser` reads the README once and gives, per linked repository, its entry line as
   plain words and its heading path (headings from the second level down, "Tools > Vector databases"; the title when
   the entry sits under the title only), and the list's headings. Headings and links inside code fences are skipped.
   `MarkdownText` cleans every line: link text stays; images and badges, HTML tags and entities, URLs, Markdown
   markers, control characters and bidirectional or zero-width characters go. An entry line is cut at 500 characters,
   a heading and a heading path at 200 (code points).
2. **Caps.** Per list at most `max-entries-per-pass` entries (1000), at most 256 KiB of entry text and heading path
   together, and 16 KiB of headings. Past a cap the index stops; collection is not affected.
3. **The lists are resources.** Each list read in a pass is a hit of `github-awesome`: type `ARTICLE` (reading about AI
   tools), tag `awesome-list`, with the facts of the GitHub search answer (description, stars, license, dates). So a
   search can show a list, and the index has a resource to point to.
4. **Only a tsvector is stored.** The batch carries the text as `ListText` to the Scout; after the hits are indexed,
   `RunScout` hands it to the `ListIndex` port. `AwesomeListIndex` replaces each list in one transaction: delete its
   entry rows, insert one row per entry whose page is in the catalog (`awesome_list_entry`: list, resource, heading
   path, `setweight(to_tsvector('english', line), 'A') || setweight(to_tsvector('english', heading path), 'B')`), and
   upsert the list's row (`awesome_list`: a tsvector of its headings). The entries travel as one bind parameter
   (`jsonb_to_recordset`), never spliced into SQL. The text is never written to a column, a cache or a log. The heading
   path is the only list text kept as text (at most 200 characters), for the explanation in point 6. A list not in the
   catalog writes nothing; an entry not yet in the catalog waits for the next pass. A failing index is logged and the
   pass goes on; the next pass rebuilds it.
5. **Search.** The index query ([0009](0009-ranking.md)) also retrieves rows whose own text does not match but an entry
   line or a list's headings do, each on its own GIN index (`awesome_list_entry_search_idx`,
   `awesome_list_search_idx`). A list-only row counts half: half the every-word bonus (+1) and half its popularity, no
   text score. So a direct every-word match (≥ 2) always outranks it, and a direct match beats a list-only match of the
   same strength. In stage 1 the every-word bonus is 3 instead of the old order "every word first", so direct rows keep
   their order and list-only rows sort below direct every-word rows. The list words help recall; they never push a
   direct match down.
6. **Explainability.** A list-only row carries the list and heading of its best matching entry (every word first, then
   the list's popularity). The card has `foundIn {listTitle, headingPath}` only when every source found the result
   only in a list; one direct match from any source hides it. The results row shows it as a small muted line, "Found in
   owner/awesome-llm under Tools > Vector databases" (or "Found in owner/awesome-llm" when there is no heading). It is
   the list's own words, not AI-written, so no AI label and no `data-ai-*` mark.
7. **Retention.** Rows go with the list or the resource (`ON DELETE CASCADE`, [0034](../operations/0034-data-retention.md)).
   A list that leaves the top lists is no longer read, so its rows are not refreshed: the catalog purge deletes rows not
   refreshed for `H2_RETENTION_LIST_INDEX` (30 days).

## Measurement
`./gradlew perfTest --tests '*AwesomeListSearchPerfTest'`: 25,000 resources, 50 lists × 300 entries (15,000 entry
rows), 200 calls per query after 100 warm-up calls, PostgreSQL 18 in a container on a 2-core machine. "Empty" is the
same query with the list tables empty (the list arms find nothing): the index query as before.

| Query | Empty: p50 / p95 / p99 | With lists: p50 / p95 / p99 |
|---|---|---|
| vector database | 39 / 56 / 59 ms | 31 / 45 / 50 ms |
| agent framework python | 37 / 69 / 83 ms | 58 / 98 / 156 ms |
| mcp server postgres | 38 / 55 / 62 ms | 55 / 83 / 88 ms |
| embeddings | 9 / 13 / 18 ms | 13 / 21 / 23 ms |

The seeded entries match much more often than real ones (14 % of the entry rows match "vector database"), so this is a
heavy case. EXPLAIN ANALYZE for "vector database": `Bitmap Index Scan on awesome_list_entry_search_idx` (2,147 entry
rows), `Index Scan using awesome_list_entry_resource_idx` for the explanation of the 300 preselected rows, execution
80 ms. `awesome_list` (50 rows) is scanned: with 50 rows that is cheaper than the index. The budget is the index
source's 3 s timeout ([0008](0008-federated-search.md)); prod took 0.2 s for a 5-word query before this change
([0009](0009-ranking.md)). The worst p95 here is under 0.1 s.
One change came from the measurement: the first version joined `resource` again in stage 1 to read popularity, and
with list rows the planner made that a hash join over the whole table (p95 105–117 ms on three queries). Carrying
popularity from the two arms instead removed it (the numbers above).

## Consequences
- A tool is found by how the lists describe it, not only by its own one-line description.
- A list is a result of its own, found by its title, description and headings.
- About 10 more resources (the lists), and about 10,000 rows of tsvectors with short heading paths. No new service,
  no new dependency.
- A list-only result says where it was found, so a surprising result is explained in the list's words.
- The index is English-stemmed. A list written in another language matches only its exact word forms.
- A repository renamed since the list was written is matched by its old URL, so its entry is left out (roadmap).

## STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A list pretends to describe a tool it does not | Only the top 10 lists by stars (≥ 1000) are read; list words count half and never outrank a direct match; the card names the list. |
| Tampering | Markdown or SQL injection through README text | The text is cleaned (no HTML, links, control or bidi characters) and goes in as a bind parameter; React escapes the heading path. |
| Repudiation | Not knowing why a result showed | `foundIn` names the list and heading; the list is a resource with its source record. |
| Information disclosure | README text or personal data kept | Only tsvectors and heading paths (≤ 200 characters) are stored; a test checks the columns and that an entry line is in no text column. A heading can name a person: same rule as quotes, removal on request. |
| Denial of service | A huge or hostile README | README read up to 5 MB within 60 s ([0006](../foundation/0006-outbound-http.md)); per-list caps on entries and bytes; GIN index for search; the list index cannot fail a pass. |
| Elevation of privilege | — | No new endpoint, no new role; the write path is the Scout's. |

OWASP LLM Top 10: no model reads or writes this text. The classifier gets the section name as before, now cleaned the same way.

## AI architecture check
| Layer | Status |
|---|---|
| Model | Deferred, safe: no model call; the classifier is unchanged. |
| Agent framework, Memory, Browser & Computer Use | Deferred, safe: none involved. |
| Orchestration | Covered: part of the Scout pass, after the hits; a failure is logged and the next pass rebuilds. |
| Tool & API | Covered: the same GitHub calls as before (the search answer now also gives the list's facts). |
| Knowledge & RAG | Covered: full-text recall from list words, ranked below direct matches. Embeddings of list text are deferred (see alternatives). |
| Data | Covered: V12, two derived tables, FK cascade, retention rule; no text column. |
| Guardrails | Covered: cleaning, caps, bind parameters, half weight. |
| Observability | Covered: a log line per pass (lists, entries written); a failed list is logged by URL and exception class only. |
| Evaluation | Gap, deferred: the labelled search set ([0036](../quality/0036-search-evaluation-harness.md)) has no grades yet; measure recall and nDCG with and without list words once it has (roadmap). |
| Deployment | Covered: one migration, one optional setting (`H2_RETENTION_LIST_INDEX`), no chart change. |
| Security & Identity | Covered: no new endpoint or role. |
| Human-in-the-loop | Deferred, safe: lists are public, read-only and capped; their words cannot outrank a direct match. |
| Cost & Reliability | Covered: no new calls; per-list caps; p95 measured above. |

## Points for a lawyer
1. Is a search index of a list's words (lexemes with positions, not the text) a reproduction of the list under Swiss URG
   and EU copyright, or a database extraction under the EU sui generis database right?
2. Is keeping section headings (at most 200 characters) and showing them as "Found in … under …" covered by quotation
   or by the facts exception? Many awesome lists are CC0 or CC BY; some have no licence.
3. Do GitHub's terms allow reading README files through the API to build such an index?

## Alternatives considered
- **Store the README text** (a column or a cache) and search it — simpler to rebuild and to show snippets, but it keeps
  the author's work and more personal data than we need. Rejected: the index needs only the word forms.
- **Embeddings of entry lines (pgvector)** — finds meaning, not only words, but costs an embedding call per entry and
  pass and adds a second vector per resource to fuse. Rejected for now: measure the full-text version first.
- **An external search engine** (OpenSearch, Meilisearch) — better text features, but a new service to run, secure and
  back up for one person. PostgreSQL full-text with GIN already serves the catalog.
- **Add the list words to the resource's own `search_vector`** — one index, but a list's words would then rank like the
  resource's own and could not be explained or retired per list.

## Code
- `backend/src/main/java/ai/helper2/sources/github/AwesomeListParser.java` (entries, heading paths, headings, caps),
  `MarkdownText.java` (cleaning), `GitHubAwesomeCollector.java` (`read`, `searchText`, `listHits`).
- `backend/src/main/kotlin/ai/helper2/scout/Ports.kt` (`ListText`, `ListEntryText`, `CollectedBatch.lists`),
  `Scout.kt` (`ListIndex`), `usecase/RunScout.kt` (`indexListsQuietly`),
  `backend/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java` (`listIndex`).
- `backend/src/main/java/ai/helper2/catalog/AwesomeListText.java`, `usecase/IndexAwesomeLists.java`,
  `internal/AwesomeListIndex.java`, `internal/ResourceStore.java` (`searchSql`, `searchFound`),
  `internal/CatalogSearchSource.java`, `internal/CatalogRetention.java` (`deleteListIndexUpdatedBefore`).
- `backend/src/main/kotlin/ai/helper2/search/Model.kt` (`ListMatch`, `SourceHit.foundIn`, `MergedResult.foundIn`),
  `backend/src/main/java/ai/helper2/search/web/CardMapper.java`, `search/cache/SourceHitCodec.java`.
- `backend/src/main/resources/db/migration/V12__awesome_list_search.sql`; `api/openapi.yaml` (`ResultCard.foundIn`,
  `ListMatch`).
- `frontend/src/components/results/ResultRow.tsx`, `results.css`; `frontend/src/i18n/locales/en.json`
  (`results.foundIn`, the Privacy page).
- Tests: `sources/github/AwesomeListParserTest.java`, `MarkdownTextTest.java`, `GitHubAwesomeCollectorTest.java`,
  `scout/usecase/RunScoutTest.kt`, `catalog/AwesomeListSearchIT.kt`, `catalog/internal/AwesomeListSearchPerfTest.java`
  (perfTest only), `search/web/CardMapperTest.kt`, `search/cache/SourceHitCodecTest.java`,
  `frontend/src/pages/Results.test.tsx`.

## Roadmap
See [the roadmap](../../roadmap.md): grade the search set and compare with and without list words; match renamed
repositories by their record URL; a language-aware list index.

## Diagrams
[flow](../../diagrams/flow-0055-search-inside-awesome-lists.svg) · [sequence](../../diagrams/sequence-0055-search-inside-awesome-lists.svg)
