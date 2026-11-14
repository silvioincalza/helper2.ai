# 0023 — Catalog full-text index

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0008](../foundation/0008-llm-integration.md), [0010](../foundation/0010-data-retention.md), [0011](../scout/0011-scout-runner.md), [0012](../scout/0012-scout-collectors-and-events.md), [0022](0022-federated-search.md), [0025](0025-ranking.md), [0040](../frontend/0040-design-system.md).

## Context

The Helper2 index is PostgreSQL full text over the catalog ([0022](0022-federated-search.md)). Three things decide
whether a query finds a resource there:

- **The language.** Users type in many languages; most sources and most catalog text are in English. An Italian query
  such as "app per tradurre" finds little in English text unless it is translated. A wrong language guess is also
  harmful: short technical queries such as "mcp postgres" or "vector database" look like Norwegian or Romanian to a
  plain detector. A translation costs a model call and time, and it must not slow down sources that do not need it.
- **The name.** Half of the catalog's titles are `owner/name` (GitHub repositories) or `@scope/name` (npm). The
  parser reads such a title as one word, so "ollama" did not find ollama/ollama by its words at all (290th on the
  staging copy of 7 Nov 2026).
- **The words around a resource.** The awesome-list collector ([0012](../scout/0012-scout-collectors-and-events.md)) reads the
  README of the top 10 AI awesome lists. A list puts a small vector store under "Tools > Vector databases" and writes
  "embedded, runs in the browser" next to it, while the repository's own description says only "Fast storage". I want
  a search to find a resource by those words too, and the lists by the topics they cover, without keeping the README
  text: it is the list author's work, and we only need it to be searchable.

## Decision

### Names as words
1. **A name is words.** A title like `ollama/ollama` is one token for PostgreSQL's parser (a file path), and
   `ggml-org/llama.cpp` keeps `/llama.cpp` as one, so "ollama" found the first only through the title similarity and
   "llama.cpp" found the second 19th on the staging copy of 7 Nov 2026. `search_vector` therefore holds the title
   twice, both at weight A and in `simple` (no stemming, any language):
   - with `/` as a space: `ollama ollama`, `ggml-org llama.cpp` (a name stays whole as the user types it);
   - as name words, `h2_name_words(title)`: the parts of a name at `/`, `.`, `_`, `@` and `:` (`ggml-org llama cpp`).
   The English part and the row's own language take the title with `/` as a space, then the description. Only the
   title: a `/` in a description is a URL or a path. `h2_name_words` is an explicit character list, not a character
   class: a class depends on the database's character type and drops `+` and `#` ("C++", "C#"), which the parser
   handles. It is one SQL function, so the resource index and the awesome-list index (point 12) cannot drift. A change
   of its body does not recompute the stored column by itself: `ALTER TABLE resource ALTER COLUMN search_vector SET
   EXPRESSION AS (...)` rewrites it (one table rewrite).
   A resource with packages also adds `package_terms` at weight B, in `simple` and `english`: the words of its
   package names, registries and languages, and a provider's other name (`anthropic` → `claude`), built from its
   records ([0018](../scout/0018-ai-libraries-and-sdks-per-language.md) §9).
2. **The query side.** `JdbcCatalogSearch.plainWords` turns `/` into a space before the words are OR-ed for the
   any-word arm, so `@scope/name` is two words there and never an AND. A `.` stays: the index keeps `llama.cpp` whole
   too, and `QueryAnalyzer` already gives the required variant `llama cpp`. The title-similarity arm keeps the raw
   text. So four rules make a query's words: the parser and `h2_name_words` (index), `QueryAnalyzer` (required
   variants: letters, digits, `-`), `plainWords` (any-word arm), `Ranking.normalize` (letters and digits,
   [0025](0025-ranking.md)).

### Languages
3. **Detect, with evidence** (`LanguageDetector`, Lingua, 27 languages that have a PostgreSQL stemmer).
   No language for fewer than 6 letters. English means no translation. A short Latin-script text (under 8 words) must
   also contain a function word of that language ("per", "para", "zum"…) or a letter with a diacritic. Longer or
   non-Latin text needs a confidence gap of at least 0.05 over the second language.
4. **Stemmer in the index.** `SourceQuery.language` carries the International Organization for Standardization (ISO) 639-1 language code. The catalog query adds
   `websearch_to_tsquery(h2_ts_config(:lang), …)` next to `english` and `simple`. Stored text uses the stemmer of its
   own detected language in `search_vector`.
5. **Translation in parallel** (`SearchResources`). For a non-English query the model call starts first. Sources with
   `usesTranslation = false` start at the same time (`helper2-semantic`, which embeds the text as typed).
   The other sources start when the translation is in, or after `H2_SEARCH_TRANSLATE_TIMEOUT_MS` (2500 ms).
   Each source's deadline and latency count from its own start, so the wait never eats a source's budget.
6. **Translator** (`SpringAiQueryTranslator` in `search.internal`, the search module's adapter of its port
   `QueryTranslator`; model calls as in [0008](../foundation/0008-llm-integration.md)): the fast model (`H2_NEBIUS_FAST_MODEL`), prompt
   `prompts/search/translate-query.txt` (label `73c073ff`, the hash of the rendered prompt, pinned by a test), at most 12
   words (`QueryTranslator.MAX_WORDS`), 400 output tokens, thinking off. The query is data: it goes between `<query>` tags
   through `UntrustedText` (cut to 500 characters, the guide's limit for a need), so it cannot close the tag. The language
   is a code from the detector's closed list; any other value is not sent.
   Its own daily cap `H2_SEARCH_DAILY_TRANSLATIONS` (300 per day; days are in Coordinated Universal Time, UTC), separate from the guide's cap; gauge `helper2.search.translation.budget.remaining`. Answers are shared by
   every replica in the shared cache `translation` ([0004](../foundation/0004-persistence-and-the-schema.md), table `ai_cache`):
   key a SHA-256 of the model, a version of the prompt and of the user message's shape (`cacheVersion`, so a new message format never serves old answers; the meters keep the prompt's label), the language and the normalised query (trimmed, spaces collapsed,
   lower case), value the English words cut by code to 12 words and 120 characters (`QueryTranslator.MAX_CHARS`,
   counted in code points), whatever the model wrote, kept `H2_SEARCH_TRANSLATION_TTL` (24 h). A failed, empty or refused translation
   is not cached, and a database error or a lookup over its time limit is a miss, so a query any replica translated costs no model call on the others. On whenever a model is configured, off without one.
   The call runs under the `llm-chat` circuit ([0006](../foundation/0006-outbound-calls-and-resilience.md)); the budget is taken inside it, so
   a call the open circuit refuses costs no translation.
7. **Untrusted answer.** The answer is dropped if it looks like a link. Only letters, digits, spaces and hyphens stay;
   at most 12 words and 120 characters again. It is dropped if it equals the normalized query. Any failure, timeout or open
   circuit means the search runs untranslated.
8. **Disclosed.** The English words go first into the expansions and the required-word variants, so live sources
   receive them as extra terms. The notice "Also searched for: …" (`QUERY_EXPANDED`) lists the word-list terms only, never
   the translation, so the notice stays in our own words. In the index each
   required-word variant stands alone: the English words are a variant of their own, and the expansions (the
   translation and the word-list terms) only widen the any-word arm. So a translated query keeps the every-word
   bonus when its English words are all in a row. GitHub gets the English words alone ([0022](0022-federated-search.md)). The API gives them apart
   from the word-list terms, in `interpretation.translatedQuery`, and the page labels them "Translated by AI"
   ([0033](../guide/0033-marking-ai-written-text-research-trail.md)).

### The words of awesome lists
9. **What is read.** `AwesomeListParser` reads the README once and gives, per linked repository, its entry line as
   plain words and its heading path (headings from the second level down, "Tools > Vector databases"; the title when
   the entry sits under the title only), and the list's headings. Headings and links inside code fences are skipped.
   `MarkdownText` cleans every line: link text stays; images and badges, HTML tags and entities, URLs, Markdown
   markers, control characters and bidirectional or zero-width characters go. An entry line is cut at 500 characters,
   a heading and a heading path at 200 (code points).
10. **Caps.** Per list at most `max-entries-per-pass` entries (1000), at most 256 KiB of entry text and heading path
   together, and 16 KiB of headings. Past a cap the index stops; collection is not affected.
11. **The lists are resources.** Each list read in a pass is a hit of `github-awesome`: type `ARTICLE` (reading about AI
   tools), tag `awesome-list`, with the facts of the GitHub search answer (description, stars, license, dates). So a
   search can show a list, and the index has a resource to point to.
12. **Only a tsvector is stored.** The batch carries the text as `ListText` to the Scout; after the hits are indexed,
   `RunScout` hands it to the `ListIndex` port. `JdbcAwesomeListIndex` writes each list again in one transaction: delete its
   entry rows, insert one row per entry whose page is in the catalog (`awesome_list_entry`: list, resource, heading
   path, and a tsvector built as the resource index builds its words, point 1: the line with `/` as a space in
   `english` and its name words in `simple` at A, the heading path with `/` as a space at B), and upsert the list's
   row (`awesome_list`: a tsvector of its headings, `/` as a space, so "CI/CD" is two words). The entries travel as one bind parameter
   (`jsonb_to_recordset`), never spliced into SQL. The text is never written to a column, a cache or a log. The heading
   path is the only list text kept as text (at most 200 characters), for the explanation in point 14. A list not in the
   catalog writes nothing; an entry not yet in the catalog waits for the next pass. A failing index is logged and the
   pass goes on; the next pass rebuilds it.
13. **Search.** The index query ([0025](0025-ranking.md)) also retrieves rows whose own text does not match but an entry
   line or a list's headings do, each on its own GIN index (`awesome_list_entry_search_idx`,
   `awesome_list_search_idx`). A list-only row counts half: half the every-word bonus (+1) and half its popularity, no
   text score. So a direct every-word match (≥ 2) always outranks it, and a direct match beats a list-only match of the
   same strength. In stage 1 the every-word bonus is 3, not a strict "every word first" order, so direct rows keep
   their order and list-only rows sort below direct every-word rows. The list words help recall; they never push a
   direct match down.
14. **Explainability.** A list-only row carries the list and heading of its best matching entry (every word first, then
   the list's popularity). The card has `foundIn {listTitle, headingPath}` only when every source found the result
   only in a list; one direct match from any source hides it. The results row shows it as a small muted line, "Found in
   owner/awesome-llm under Tools > Vector databases" (or "Found in owner/awesome-llm" when there is no heading). It is
   the list's own words, not AI-written, so no AI label and no `data-ai-*` mark.
15. **Retention.** Rows go with the list or the resource (`ON DELETE CASCADE`, [0010](../foundation/0010-data-retention.md)).
   A list that leaves the top lists is no longer read, so its rows are not refreshed: the catalog purge deletes rows not
   refreshed for `H2_RETENTION_LIST_INDEX` (30 days).

### Code

- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `h2_unaccent`, `h2_ts_config`, `h2_name_words`,
  `search_vector`, `awesome_list`, `awesome_list_entry`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcCatalogSearch.java` — `plainWords`, `searchSql`,
  `searchFound`, the language stemmer in the index query; `JdbcAwesomeListIndex.java` — the list index;
  `CatalogSearchSource.java`; `JdbcCatalogRetention.java` (`deleteListIndexUpdatedBefore`).
- `backend/libs/core/src/main/java/ai/helper2/catalog/AwesomeListText.java`, `AwesomeListIndex.java`,
  `usecase/IndexAwesomeLists.java`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/LanguageDetector.kt` — detection rules.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/usecase/QueryTranslation.kt` — `start`, `await`,
  `withTranslation`; `SearchResources.kt` — early and late sources (`startSources`); `Ports.kt` — `QueryTranslator`; `QueryAnalyzer.kt`.
- `backend/libs/core/src/main/java/ai/helper2/search/internal/SpringAiQueryTranslator.java`,
  `TranslationConfiguration.java` — model call, budget, cache key; `src/main/resources/prompts/search/translate-query.txt`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/cache/SharedCache.java`, `JdbcSharedCache.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/AwesomeListParser.java`, `MarkdownText.java`,
  `GitHubAwesomeCollector.java`; `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Ports.kt` (`ListText`),
  `Scout.kt` (`ListIndex`), `usecase/RunScout.kt` (`indexListsQuietly`).
- `backend/libs/core/src/main/kotlin/ai/helper2/search/Model.kt` (`ListMatch`, `foundIn`),
  `backend/libs/core/src/main/java/ai/helper2/search/web/CardMapper.java`; `api/common.yaml` (`ResultCard.foundIn`).
- `frontend/src/features/search/ResultRow.tsx`; `frontend/src/i18n/locales/en.json` (`results.foundIn`), `en.legal.json` (the Privacy page).
- Tests: `catalog/CatalogSearchRankingIT.kt` (owner/name cases), `catalog/SchemaMigrationTest.java` (the words of a
  title), `catalog/AwesomeListSearchIT.kt`, `catalog/internal/PlainWordsTest.java`,
  `catalog/internal/AwesomeListSearchPerfTest.java` (perfTest only), `sources/github/scout/AwesomeListParserTest.java`,
  `scout/usecase/RunScoutTest.kt`, `search/web/CardMapperTest.kt`.

## Alternatives considered

- **Only `/` as a space** (the first fix proposed) — fixes "ollama" but makes "llama.cpp" worse (19 → 43): the dotted
  name stays one token, so the every-word bonus goes to `node-llama-cpp`. Rejected.
- **Split names with a character class** (`regexp_replace(title, '[^[:alnum:]]+', ' ')`) — depends on the database's
  character type and drops `+` and `#`. Rejected for an explicit list.
- **Split names on the query side too** — the index already holds both forms; a split query would lose the whole
  dotted name.
- Always translate — a model call for every English query too, and wrong guesses on short queries. Translate before
  starting any source — the semantic source would wait for nothing. Translate the catalog — storage and model cost for
  every resource. Rely only on multilingual embeddings — keyword sources and live APIs would still miss the query.
- A translation cache per replica, in Redis or in memory in front of the table — each replica pays its own call, or the
  memory is shared with sessions and not in the backups, for a lookup of about 1 ms next to a model call of up to 2.5 s.
- **Store the README text** and search it — keeps the author's work and more personal data than we need.
- **Embeddings of entry lines** — an embedding call per entry and pass and a second vector per resource; measure the
  full-text version first.
- **An external search engine** (OpenSearch, Meilisearch) — a new service to run, secure and back up for one person.
- **Add the list words to the resource's own `search_vector`** — a list's words would then rank like the resource's own
  and could not be explained or retired per list.

## Consequences

- "ollama" finds ollama/ollama, "llama.cpp" finds ggml-org/llama.cpp, and scoped npm names match whole or by their
  parts. A name counts twice at weight A; that sits inside the text score's cap and only orders rows within a group.
- Queries in other languages find English resources, and the user sees the words that were added. A model outage or a
  spent budget only removes the translation; English queries cost no model call and no wait. A translated query is
  kept for 24 h under a hash; the Privacy page states it; the daily shared cache purge deletes it
  ([0010](../foundation/0010-data-retention.md)).
- A tool is found by how the lists describe it, and a list is a result of its own, found by its title, description and
  headings. A list-only result says where it was found, in the list's words.
- About 10 more resources (the lists) and about 10,000 rows of tsvectors with short heading paths. No new service, no
  new dependency.
- The list index is English-stemmed: a list in another language matches only its exact word forms. A repository
  renamed since the list was written is matched by its old URL, so its entry is left out (open point).

### Measurement

**Names as words.** Store-level ranks in `CatalogSearchRankingIT` (the case's rows only), before and after:
"ollama" puts ollama/ollama 4th of 4, then 1st; "llama.cpp" ggml-org/llama.cpp 3rd of 4, then 1st; "llama cpp"
3rd, then 2nd; "modelcontextprotocol inspector" the inspector 2nd of 3, then 1st; the scoped npm names stay 1st. On
the staging copy of 7 Nov 2026 (15,331 resources, the index alone) metasearch measured: "ollama" 290 → 1, "llama.cpp"
19 → 1, "langchain" 3 → 1; replacing only `/` made "llama.cpp" worse (19 → 43), which is why names are split too. The
fused order of a whole search also depends on the exact-title boost, which waits for graded queries
([0046](../operations/0046-evaluation-guide-and-search.md)). Cost: about 7 % more lexemes per row and 10 % more vector bytes; search SQL p50
51 ms and p95 79–80 ms on that copy, before and after (the awesome-list numbers below are single runs). No
re-embedding: the embedded text (title and description) does not change.

**Awesome lists.** `./gradlew perfTest --tests '*AwesomeListSearchPerfTest'`: 25,000 resources, 50 lists × 300 entries
(15,000 entry rows), 200 calls per query after 100 warm-up calls, PostgreSQL 18 in a container. "Empty" is the same
query with the list tables empty (the list arms find nothing). One run each, before and after the names as words
(8 Nov 2026, same machine):

| Query | Empty, before | Empty, after | With lists, before | With lists, after |
|---|---|---|---|---|
| vector database | 49 / 51 / 52 ms | 48 / 51 / 52 ms | 55 / 59 / 64 ms | 57 / 61 / 67 ms |
| agent framework python | 59 / 63 / 64 ms | 57 / 62 / 63 ms | 17 / 69 / 71 ms | 26 / 74 / 76 ms |
| mcp server postgres | 58 / 62 / 63 ms | 56 / 61 / 64 ms | 26 / 28 / 29 ms | 29 / 33 / 38 ms |
| embeddings | 40 / 42 / 43 ms | 40 / 42 / 44 ms | 7 / 8 / 8 ms | 8 / 10 / 11 ms |

(p50 / p95 / p99.) These are single runs, and the "with lists" runs are faster than the empty ones, so the noise
between runs is not known: the names cost a few milliseconds with lists at most, and that is not proven neutral. The seeded
entries match much more often than real ones, so this is a heavy case. EXPLAIN ANALYZE for "vector database": `Bitmap Index Scan on awesome_list_entry_search_idx`,
`Index Scan using awesome_list_entry_resource_idx` for the explanation of the preselected rows. The budget is the
index source's 3 s timeout ([0022](0022-federated-search.md)); the worst p95 here is under 0.1 s. One change came from
the first measurement: stage 1 joined `resource` again to read popularity, and with list rows the planner made that a
hash join over the whole table (p95 105–117 ms); carrying popularity from the two arms removed it.

### STRIDE

| Threat | Risk | Control |
|---|---|---|
| Spoofing | A list pretends to describe a tool it does not | Only the top 10 lists by stars (≥ 1000) are read; list words count half and never outrank a direct match; the card names the list. |
| Tampering | Markdown or SQL injection through README text or a query | README text is cleaned and goes in as a bind parameter; query text is only ever a parameter, its search syntax removed (`plainWords`); React escapes the heading path. |
| Repudiation | Not knowing why a result showed | `foundIn` names the list and heading; the list is a resource with its source record. |
| Information disclosure | README text or personal data kept | Only tsvectors and heading paths (≤ 200 characters) are stored; a test checks the columns and that an entry line is in no text column. A translated query is kept under a hash only. |
| Denial of service | A huge README, a query with many words, a translation flood | README read up to 5 MB within 60 s ([0006](../foundation/0006-outbound-calls-and-resilience.md)); per-list caps; GIN indexes; at most 4 required variants; the translator has its own daily cap and the `llm-chat` circuit. |
| Elevation of privilege | — | No new endpoint, no new role; the list index is written by the Scout in the back office. |

OWASP LLM Top 10: the translator's answer is untrusted (point 7); no model reads or writes the list text or the names.

### AI architecture check

| Layer | Status |
|---|---|
| Model | Covered: the fast model translates, NVIDIA only ([0008](../foundation/0008-llm-integration.md)); the index and the names need no model. |
| Agent framework, Memory, Browser & Computer Use | Deferred, safe: none involved. |
| Orchestration | Covered: translation in parallel with early sources; the list index is part of the Scout pass, a failure is logged and the next pass rebuilds. |
| Tool & API | Covered: the same GitHub calls as before for the lists. |
| Knowledge & RAG | Covered: full-text recall by language, by name words and by list words, ranked below direct matches. Embeddings of list text deferred. |
| Data | Covered: `h2_name_words` and `search_vector` in V1, two derived list tables with FK cascade and a retention rule; no text column. |
| Guardrails | Covered: untrusted translation, cleaning, caps, bind parameters, half weight. |
| Observability | Covered: translation budget gauge, a log line per list pass. |
| Evaluation | Gap, deferred: the search set has no grades yet ([0046](../operations/0046-evaluation-guide-and-search.md)); the owner/name cases are store-level ITs, not graded metrics. |
| Deployment | Covered: V1, one optional setting (`H2_RETENTION_LIST_INDEX`), no chart change. |
| Security & Identity | Covered: no new endpoint or role. |
| Human-in-the-loop | Deferred, safe: lists are public, read-only and capped. |
| Cost & Reliability | Covered: the translator's daily cap and cache; no new calls for names or lists; p95 measured above. |

### Points for a lawyer

1. Is a search index of a list's words (lexemes with positions, not the text) a reproduction of the list under Swiss URG
   and EU copyright, or a database extraction under the EU sui generis database right?
2. Is keeping section headings (at most 200 characters) and showing them as "Found in … under …" covered by quotation
   or by the facts exception? Many awesome lists are CC0 or CC BY; some have no licence.
3. Do GitHub's terms allow reading README files through the API to build such an index?

### Known limits

- A search waits for the slowest answering source up to its deadline: Tavily (6 s) sets the worst case, and a non-English query can start most sources up to 2.5 s later for translation.
- The English/Italian lexicon still runs next to translation, so Italian gets two kinds of help and other languages rely on the model.
- Awesome-list words are English-stemmed: a list in another language matches only exact word forms. An entry whose repository was renamed since the list was written links the old URL and is left out of the index.

### Open points

- The exact-title boost for owner/name titles and the half weight of list words are decided on the graded search set.
- A renamed repository is not matched by its record URL.
- The list index is not language-aware.

## Diagrams

Languages: [flow](../../diagrams/flow-0023-language-aware-search.svg) · [sequence](../../diagrams/sequence-0023-language-aware-search.svg) ·
names and awesome lists: [flow](../../diagrams/flow-0023-search-inside-awesome-lists.svg) · [sequence](../../diagrams/sequence-0023-search-inside-awesome-lists.svg)

## Backlog

- Features: [F3.2](https://github.com/silvioincalza/helper2.ai/issues/48) Catalog full-text index, awesome-list words.
- Open issues that change this decision:
  - [#254](https://github.com/silvioincalza/helper2.ai/issues/254) S3.12.4 Two ranking rules decided on graded data
  - [#256](https://github.com/silvioincalza/helper2.ai/issues/256) S3.14.1 Search by meaning to match English resources
  - [#326](https://github.com/silvioincalza/helper2.ai/issues/326) S7.7.1 The UI in my language
