# 0023 — Catalog full-text index

**Depends on:** [0004](ADR-0004-persistence-and-the-schema.md), [0008](ADR-0008-llm-integration.md), [0010](ADR-0010-data-retention.md), [0011](ADR-0011-scout-runner.md), [0012](ADR-0012-scout-collectors-and-events.md), [0022](ADR-0022-federated-search.md), [0025](ADR-0025-ranking.md), [0040](ADR-0040-design-system.md).

## Context

The Helper2 index is PostgreSQL full text over the catalog ([0022](ADR-0022-federated-search.md)). Three things decide
whether a query finds a resource there:

- **The language.** Users type in many languages; most sources and most catalog text are in English. An Italian query
  such as "app per tradurre" finds little in English text unless it is translated. A wrong language guess is also
  harmful: short technical queries such as "mcp postgres" or "vector database" look like Norwegian or Romanian to a
  plain detector. A translation costs a model call and time, and it must not slow down sources that do not need it.
- **The name.** Half of the catalog's titles are `owner/name` (GitHub repositories) or `@scope/name` (npm). The
  parser reads such a title as one word, so "ollama" did not find ollama/ollama by its words at all (290th on the
  staging copy of 7 Nov 2026).
- **The words around a resource.** The awesome-list collector ([0012](ADR-0012-scout-collectors-and-events.md)) reads the
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
   records ([0018](ADR-0018-ai-libraries-and-sdks-per-language.md) §9).
2. **The query side.** `JdbcCatalogSearch.plainWords` turns `/` into a space before the words are OR-ed for the
   any-word arm, so `@scope/name` is two words there and never an AND. A `.` stays: the index keeps `llama.cpp` whole
   too, and `QueryAnalyzer` already gives the required variant `llama cpp`. The title-similarity arm keeps the raw
   text. So four rules make a query's words: the parser and `h2_name_words` (index), `QueryAnalyzer` (required
   variants: letters, digits, `-`), `plainWords` (any-word arm), `Ranking.normalize` (letters and digits,
   [0025](ADR-0025-ranking.md)).

### Languages
0. **The word files** (`i18n/query-words_{en,it,de,fr,es}.properties` and `i18n/language-function-words.properties`,
   read by `WordLists`). Comma-separated, matched case-insensitively as whole words; every language file is loaded and
   the languages are joined. A word that is also a common English word is left out of the other languages (German
   `was`, `will`; French `on`, `son`; Spanish `sin`): it would change how an English query is read. The keys of
   `query-words`: `usecases` and `usecase.*` (use cases, in the order a result lists them) with `usecase.*.query`
   (the English term a query in another language is expanded with, English only); `type.*` (a word that names the
   kind of resource asked for); `server` (names an MCP server only next to "mcp"); `repository.generic`, `function`
   and `request` (words that carry no subject of their own); `synonyms` (names of one thing: a query with one also
   searches the others; groups comma-separated, names space-separated); `time-bound` (a query with one asks for news,
   courses or events, `IntentClass.TIME_BOUND`); `ranking.stop` (words ranking leaves out when it counts the query's
   words in a title; English and Italian only, since words of another language would change how those queries rank).
   `language-function-words`: short words that tell a short Latin-script query's language when `LanguageDetector` is
   not sure, one line per language code, words space-separated.
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
   `QueryTranslator`; model calls as in [0008](ADR-0008-llm-integration.md)): the fast model (`H2_NEBIUS_FAST_MODEL`), prompt
   `prompts/search/translate-query.txt` (label `ecc182c3`, the hash of the rendered prompt, pinned by a test), at most 12
   words (`QueryTranslator.MAX_WORDS`), 400 output tokens, thinking off. The query is data: it goes between `<query>` tags
   through `UntrustedText` (cut to 500 characters, the guide's limit for a need), so it cannot close the tag. The language
   is a code from the detector's closed list; any other value is not sent.
   Its own daily cap `H2_SEARCH_DAILY_TRANSLATIONS` (300 per day; days are in Coordinated Universal Time, UTC), separate from the guide's cap; gauge `helper2.search.translation.budget.remaining`. Answers are shared by
   every replica in the shared cache `translation` ([0004](ADR-0004-persistence-and-the-schema.md), table `ai_cache`):
   key a SHA-256 of the model, a version of the prompt and of the user message's shape (`cacheVersion`, so a new message format never serves old answers; the meters keep the prompt's label), the language and the normalised query (trimmed, spaces collapsed,
   lower case), value the English words cut by code to 12 words and 120 characters (`QueryTranslator.MAX_CHARS`,
   counted in code points), whatever the model wrote, kept `H2_SEARCH_TRANSLATION_TTL` (24 h). A failed, empty or refused translation
   is not cached, and a database error or a lookup over its time limit is a miss, so a query any replica translated costs no model call on the others. On whenever a model is configured, off without one.
   The call runs under the `llm-chat` circuit ([0006](ADR-0006-outbound-calls-and-resilience.md)); the budget is taken inside it, so
   a call the open circuit refuses costs no translation.
7. **Untrusted answer.** The prompt asks the model to give back a query that is already English, or has nothing to
   translate, unchanged. The answer is dropped if it looks like a link, or if it talks about the query instead of being
   one. Some phrases count anywhere in the answer ("already in English", "nothing to translate", "no translation
   required"); others only when the answer opens with them ("The query is", "This cannot be translated", "No
   translation", "Sorry", "I cannot", "Unable to"), because "words that cannot be translated" is a real translation.
   The model can also refuse in the user's language ("Nessuna traduzione necessaria", "Keine Übersetzung nötig", "Lo
   siento, no puedo traducir", "Aucune traduction nécessaire"), so each of the five languages has both lists, in
   `i18n/translation-words_<language>.properties`, and every list counts for every answer. Apologies and "I cannot"
   ("mi dispiace", "lo siento", "désolé", "leider") count only at the start, because "How to say lo siento in English"
   is a real translation. The lists are matched on the answer reduced to plain words, so they are written that way:
   lower case, accents kept (NFC, on both sides), an apostrophe as a space ("c est en anglais"), and a few phrases
   also without their accents ("gia in inglese"). Only letters, digits,
   spaces and hyphens stay; at most 12 words and 120 characters again. It is dropped if it equals the query reduced the same way, so an
   echo that differs only in case or punctuation adds no variant. Any failure, timeout or open circuit means the search
   runs untranslated.
8. **Disclosed.** The English words go first into the expansions and the required-word variants, so live sources
   receive them as extra terms. The notice "Also searched for: …" (`QUERY_EXPANDED`) lists the word-list terms only, never
   the translation, so the notice stays in our own words. In the index each
   required-word variant stands alone: the English words are a variant of their own, and the expansions (the
   translation and the word-list terms) only widen the any-word arm. So a translated query keeps the every-word
   bonus when its English words are all in a row. GitHub gets the English words alone ([0022](ADR-0022-federated-search.md)). The API gives them apart
   from the word-list terms, in `interpretation.translatedQuery`, and the page labels them "Translated by AI"
   ([0033](ADR-0033-marking-ai-written-text-research-trail.md)).

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
   catalog writes nothing; an entry not yet in the catalog waits for the next pass. An entry is a repository, a
   model, a paper or a package of the list, or a product, a course or an article one of its links points to
   ([0055](ADR-0055-directory-lists.md) point 15). A failing index is logged and the
   pass goes on; the next pass rebuilds it.
13. **Search.** The index query ([0025](ADR-0025-ranking.md)) also retrieves rows whose own text does not match but an entry
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
15. **On the resource page.** The resource detail of the public API has `foundIn {lists, more}`: every list that names
   the resource, as `{listId, listTitle, headingPath}`, the most popular list first (`resource.popularity` of the
   list's own row), then by title; at most 10 (`ResourceDetail.FoundIn.MAX_LISTS`), and `more` counts the rest. One
   indexed read on `awesome_list_entry_resource_idx` (`JdbcResourceDetailReads.naming`, the port `AwesomeListEntries`),
   as the public app's role, which may only read the table. The page shows them in "Found in these lists", just above
   "Where this information comes from": each list is a link to the list's own page in the catalog, never straight to
   GitHub, followed by "under <heading path>" when the entry has one, and "and N more lists" when there are more than
   10. A resource no list names has no section. The server's page for crawlers lists the same links
   ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md)). The list's own words again: no AI label and no
   `data-ai-*` mark.
16. **Retention.** Rows go with the list or the resource (`ON DELETE CASCADE`, [0010](ADR-0010-data-retention.md)).
   No period deletes them: `JdbcAwesomeListIndex.replace` writes the rows of a list again each time a pass reads the
   list. A list that is no longer read because it left the top lists or an administrator removed it keeps the rows of
   its last read for as long as the list and the resource are in the catalog. A list that is gone at its source
   loses its rows (point 17): a list on GitHub whose repository is gone, and a web page its site says is gone.
17. **A list gone at its source no longer names its resources** (UC-030 BR-012).
   - **How "gone" is known, for a list on GitHub.** The repository sweep of
     [0020](ADR-0020-resource-vitality.md) §7 already asks GitHub about every record that is a repository, so about
     the own record of every curated list on GitHub, whoever found the list: an administrator, the collector's search
     or a link in another list. GitHub answers `NOT_FOUND` for a repository it does not know
     (`GitHubRepositoryStates`); a renamed repository is found under its new name, so a rename is not gone. No call is
     added.
   - **The count.** Each visit that does not find the repository adds one to `extra.vitality.notFoundVisits` of the
     record (`JdbcRepositoryVisits.recordNotFound`). A visit that finds it, or a source that states the repository
     again, removes the count; a visit without an answer leaves it. A rate limit, an answer without data and a batch
     where more than one repository in five is missing store nothing, as before.
   - **The rule.** After each batch the sweep hands the records GitHub did not find to `ForgetGoneLists`
     (`SweepRepositories.sweepOneBatch`). `JdbcGoneLists.forget` takes the ones that are the repository itself
     (`repository_url IS NULL`), are marked `gone` (two visits at least 24 hours apart, 0020 §7) and were not found
     on `H2_SCOUT_REPOSITORY_SWEEP_GONE_LIST_VISITS` (3) visits in a row. It deletes the `awesome_list_entry` rows and
     the `awesome_list` row of each such list, and `ForgetGoneLists` refreshes the categories of the resources the
     list named, in one unit of work: a refresh that fails leaves the rows, and the next visit tries again.
   - **A candidate accepted later.** A list names a resource on accept ([0055](ADR-0055-directory-lists.md) point
     15) only while it has its row in `awesome_list` (`JdbcListNamings.byCuratedList`). A list that is gone has
     none, so a candidate it proposed, accepted after that, is not named by it.
   - **Why 3.** One "not found" is not believed anywhere in the Scout: a wrong answer of GitHub would wipe a list.
     Two is the rule that marks a repository `gone`, and that mark is undone by the next visit that finds it. Deleting
     the rows is undone only by the next read of the list, so it asks for one visit more. The mark `gone` stays a
     condition, so three visits inside one day, in a small catalog the sweep goes round in minutes, delete nothing.
   - **The list's own resource** follows 0020 §7 and nothing else: marked `gone`, it is read as archived and search
     refuses it; it is not deleted, and its page no longer names what it listed. A list another list links, and
     GitHub does not find in the collector's pass, is withdrawn by the collector as any linked repository
     ([0011](ADR-0011-scout-runner.md) §4); its rows go with its resource.
   - **When the repository is back**, the visit that finds it removes `gone` and the count, and the next pass that
     reads the list writes its rows again.
   - **How "gone" is known, for a web page**, a curated one and a list page of a directory host
     ([0055](ADR-0055-directory-lists.md)). The page reader answers a page it could not read in `failed_results`
     with its address and a free text (`error`), and `LiveWebPageReader` reads only `results`: nothing tells a 404
     or a 410 from a time-out or a block. So the site is asked, with the status request of our own
     ([0057](ADR-0057-follow-list-links.md) point 8, `PageStatusReader`): the same adapter, with its host check, its
     robots.txt rule and its caps, and no other client.
   - **When the site is asked.** Only when a read of the page through the page reader failed in this pass
     (`WebPageLists.readNow`, a `SourceFailure`; `GoneWebPages.readFailed`). Never for a page that was read. Never
     for a page that is not read: one that is not due or waits, a list whose reading is turned off
     ([0055](ADR-0055-directory-lists.md) point 11), a page on a host that is never read. A read our own circuit
     refused was not made, so it asks nothing either. A page that is not read is never checked, and keeps what it
     named.
   - **What counts.** Only `Answered` with 404 or 410 is "gone": `ListSeedStore.goneChecked` adds one to
     `list_seed.gone_checks` and sets `gone_since` at the first such check. A 2xx and a 3xx start the count again
     (`notGone`), and so does a read that gives the page (`readSucceeded`). Every other answer (5xx, 401, 403, 429,
     another 4xx) and every `Refused` (a time-out, robots.txt, a cap, the host check, a private address, the open
     circuit) says nothing: nothing is removed and the count stays.
   - **A redirect starts the count again and is not followed.** A 3xx says that the site still answers for the
     address. Leaving the count would let two "gone" answers, a redirect and one more "gone" remove a list; starting
     it again asks for three answers "gone" with nothing between them that says the page is there. A redirect to a
     page that says "not found" with status 200 keeps a dead list for good: the price of removing nothing by
     mistake.
   - **The rule.** A page is gone when `gone_checks` reached `H2_SCOUT_AWESOME_WEB_PAGES_GONE_CHECKS` (3) and the
     first of these checks is at least one day before the last (`GoneWebPages.CONFIRMED_AFTER`), so one bad hour
     of a site removes nothing. With a pass a day, that is the third failed read in a row: the weekly read and the
     tries of the two next days. The two counts mean
     the same on both sides: answers "gone" in a row from the source itself, at least 3 by default, over at least
     24 hours, started again by an answer that finds the list. On GitHub the day comes from the mark `gone`
     (two visits 24 hours apart) and the count from `H2_SCOUT_REPOSITORY_SWEEP_GONE_LIST_VISITS`; for a web page
     both are on the row of the list in `list_seed`. Each side has its own setting because each has its own rhythm:
     the sweep comes back in days, a failed read is tried again at the next pass.
   - **What goes.** `ForgetGoneWebList` (port `GoneWebLists`, `JdbcGoneWebLists.forget`) deletes, by the address
     of the page and in one unit of work: its `directory_list_entry` rows, its `directory_rating` rows
     ([0056](ADR-0056-directory-rating.md) point 12), its `awesome_list_entry` rows and its `awesome_list` row, and
     refreshes the categories of what it named. Both kinds go by the address because a host can become a directory
     host, or stop being one, between two reads. Then the Scout drops `list_seed_last_read` of the page
     (`ListSeedStore.forgotten`), so no later pass records the stored read again. The catalog comes first: when
     the second step fails, the next pass records the stored read again and the next check that says "gone" removes
     it again.
   - **The own resource of a curated web page** follows the one rule for a resource that is gone,
     [0020](ADR-0020-resource-vitality.md) §7: its record gets `extra.vitality.gone` (with `checkedAt`), search
     reads that as archived and refuses it, and the resource is not deleted. A read that gives the page again
     states `archived: false` for it (`GitHubAwesomeCollector.pageHit`, only for a page read in this pass), and a
     source that states a record again removes `gone`, as for a repository. A list page of a directory host has no
     resource ([0055](ADR-0055-directory-lists.md) point 14).
   - **The list to read.** A page an administrator added keeps its row in `list_seed`: a person added it and a
     person removes it. It is tried again by the rule of failed reads, weekly once three tries failed. A list
     discovery added loses its row: it frees its place among the 100 lists discovery adds, and discovery adds it
     again when a search finds it again.
   - **A candidate accepted later.** A curated web page that is gone has no row in `awesome_list`, so it names
     nothing on accept, like a list on GitHub. A list page of a directory host still names a candidate it proposed
     (Known limits).
   - **When the page is back**, the read that gives it starts the count again, writes its last read and its rows,
     and takes the mark `gone` off the record of a curated page.
   - **Metrics.** `helper2.scout.lists.gone.checks{outcome}`: `gone`, `found`, `redirect`, `other-status`, or the
     refusal of the status request (`robots`, `daily-cap`, `host-cap`, `timeout`, `host-check`, …).
     `helper2.scout.lists.forgotten{kind}`: the lists that no longer name their resources, `web-page` or `github`
     (counted by the repository sweep from what `ForgetGoneLists` returns).

### Code

- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `h2_unaccent`, `h2_ts_config`, `h2_name_words`,
  `search_vector`, `awesome_list`, `awesome_list_entry`.
  `V2026.11.9.2__list_index_without_period.sql` — no index on `updated_at` of the list tables: no purge reads it.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcCatalogSearch.java` — `plainWords`, `searchSql`,
  `searchFound`, the language stemmer in the index query; `JdbcAwesomeListIndex.java` — the list index;
  `CatalogSearchSource.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/AwesomeListText.java`, `AwesomeListIndex.java`,
  `usecase/IndexAwesomeLists.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/GoneLists.java`, `usecase/ForgetGoneLists.java`,
  `internal/JdbcGoneLists.java`, `internal/JdbcRepositoryVisits.java` (`recordNotFound`);
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/SweepRepositories.kt` (`GoneListRule`),
  `scout/config/ScoutProperties.java` (`RepositorySweep.goneListVisits`) — a list on GitHub gone at its source.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/GoneWebPages.java`, `WebPageLists.java`
  (`readNow`), `GitHubAwesomeCollector.java` (`pageHit`); `scout/ListSeeds.kt` (`goneChecked`, `notGone`,
  `forgotten`, `GoneWebPageRule`), `scout/internal/JdbcListSeedStore.java`;
  `backend/libs/core/src/main/java/ai/helper2/catalog/GoneWebLists.java`, `usecase/ForgetGoneWebList.java`,
  `internal/JdbcGoneWebLists.java`; `V2026.11.10.2__list_seed_gone_checks.sql`;
  `sources/config/CollectorProperties.java` (`WebPages.goneChecks`) — a web page gone at its site.
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
- `backend/libs/core/src/main/java/ai/helper2/catalog/ResourceDetail.java` (`FoundIn`, `ListEntry`), `AwesomeListEntries.java`,
  `usecase/GetResourceDetail.java`, `internal/JdbcResourceDetailReads.java` (`naming`),
  `backend/apps/public/src/main/java/ai/helper2/catalog/web/ResourceDetailController.java`; `api/public.yaml`
  (`ResourceDetail.foundIn`, `FoundIn`, `FoundInList`).
- `frontend/src/features/search/ResultRow.tsx`, `frontend/src/features/resource/FoundInLists.tsx`;
  `frontend/src/i18n/locales/en.json` (`results.foundIn`, `resource.foundIn`), `en.legal.json` (the Privacy page).
- Tests: `catalog/CatalogSearchRankingIT.kt` (owner/name cases), `catalog/SchemaMigrationTest.java` (the words of a
  title), `catalog/AwesomeListSearchIT.kt`, `catalog/internal/PlainWordsTest.java`,
  `catalog/internal/AwesomeListSearchPerfTest.java` (perfTest only), `sources/github/scout/AwesomeListParserTest.java`,
  `scout/usecase/RunScoutTest.kt`, `search/web/CardMapperTest.kt`, `api/ResourceFoundInApiIT.java` (the lists of a
  resource, their order and the cap), `ResourcePage.test.tsx`; `catalog/usecase/ForgetGoneListsTest.java`,
  `catalog/internal/JdbcGoneListsIT.java`, `JdbcRepositoryVisitsIT.java` (the count), `SweepRepositoriesTest.kt`
  (a list gone at its source); `sources/github/scout/GoneWebPagesTest.java`, `WebPageListsTest.java`
  (`uc030Br012…`), `catalog/usecase/ForgetGoneWebListTest.java`, `catalog/internal/JdbcGoneWebListsIT.java`,
  `scout/internal/JdbcListSeedStoreIT.java` (a web page gone at its site).

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
- **A list gone after one "not found"** — one wrong answer of GitHub wipes what a list named until its next read.
  **After a time window** (not found for 30 days) — the sweep comes back to a record every few days at most, so a
  window counts visits anyway, with a date to compare and a length to choose on top; and a window that starts at the
  first "not found" does not see a visit that found the repository in between unless that visit clears it, which is
  the count again. **Three visits in a row, with the mark `gone`** — chosen (point 17).
- **Where the count of "not found" lives.** `list_seed.read_failures` — holds only the lists an administrator or
  discovery added, not the ones the collector's search or another list finds, and it counts every failed read of a
  web page, not a "not found". A column on `awesome_list` — a migration, and the collector would have to ask GitHub
  again about every list it no longer reads to feed it: up to one call per list and pass. A table of its own — the
  same call, one more table. **On the list's own source record, `extra.vitality.notFoundVisits`** — chosen: the
  sweep already visits that record and stores `notFoundAt` and `gone` there, so there is no migration, no call and
  one writer.
- **Where the count of a web page lives.** `list_seed.read_failures` — counts every failed read, a time-out as
  much as a 404, and steers the retry rule: reading it as "gone" would remove a list after three time-outs. On the
  own source record, as for GitHub — a list page of a directory host has no record, so two places for one rule.
  **Two columns on `list_seed`, `gone_checks` and `gone_since`** — chosen: one migration, the row every web page
  list has, next to the count of failed reads it is the stricter twin of.
- **What happens to a gone list discovery added.**

  | | The row is removed (chosen) | The row stays |
  |---|---|---|
  | Place among the 100 | Freed | Held until an administrator removes the list |
  | Cost of a dead page | None | 1 read and up to 2 status requests a week, and one of the 10 reads of its day |
  | When the page is back | Added again when a search of discovery finds it | Read at its next weekly try |
  | A candidate accepted later | Still named by the list (Known limits) | The row could say that the list is gone |
  | Failure mode | A page a search keeps finding is added, fails and is removed again: 3 reads and at most 4 status requests over 2 days a round | The 100 fill with dead pages and discovery adds no list, with nothing that says why |
  | Load for one person | None | A list to clean by hand |

  The row that stays is the simpler code and the trap: the cap fills in silence. A page an administrator added is
  the other case: its row is a person's decision and is never removed by the system.
- **A redirect leaves the count** — see point 17: starting it again is the choice that removes less.
- **Following the redirect to see whether the page moved** — a second request to an address the site chose, for a
  rule that only needs "gone or not". The moved page is a new list for a person to add.
- **The collector decides that a list is gone** (a 404 on `/repos/{owner}/{repo}` or on the README in
  `AwesomeCrawl`) — it sees only the lists of this pass: a deleted repository leaves the search results and is never
  asked again. A missing README of a repository that still exists is not "gone" either.

## Consequences

- "ollama" finds ollama/ollama, "llama.cpp" finds ggml-org/llama.cpp, and scoped npm names match whole or by their
  parts. A name counts twice at weight A; that sits inside the text score's cap and only orders rows within a group.
- Queries in other languages find English resources, and the user sees the words that were added. A model outage or a
  spent budget only removes the translation; English queries cost no model call and no wait. A translated query is
  kept for 24 h under a hash; the Privacy page states it; the daily shared cache purge deletes it
  ([0010](ADR-0010-data-retention.md)).
- A tool is found by how the lists describe it, and a list is a result of its own, found by its title, description and
  headings. A list-only result says where it was found, in the list's words.
- A resource page names every list the resource is in and links to each list's page. One more indexed read per
  resource detail. No new personal data: the titles and heading paths of public lists, which search already shows.
- About 10 more resources (the lists) and about 10,000 rows of tsvectors with short heading paths. No new service, no
  new dependency.
- The list index is English-stemmed: a list in another language matches only its exact word forms. A repository
  renamed since the list was written is matched by its old URL, so its entry is left out (open point).
- A list on GitHub that was deleted stops naming its resources at the third visit of the sweep that does not find
  it: with 35,000 records and a round of about 5 days, 10 to 15 days after it went (ESTIMATE, from the round of
  [0020](ADR-0020-resource-vitality.md)). Until then the public page of a resource still names it.
- A GitHub that wrongly answers "not found" for a list on three visits in a row, over more than a day, removes what
  the list named until the collector reads the list again. The sweep's guard (more than one repository in five
  missing is not believed) stands in front of it.
- A web page that was deleted stops naming its resources at the third pass that cannot read it and whose status
  request is answered 404 or 410: its weekly read, then the tries of the two next days, so at most 9 days after it
  went with a pass a day. A site that answers 404 for a page that
  exists, on three checks over a day, removes what the page named until its next good read; a list discovery added
  is then found again only by a search.
- The status requests of this rule count on the caps of [0057](ADR-0057-follow-list-links.md) point 12 (200 a
  day, 30 a host, robots.txt included). One failed read asks at most 2 (the page, and the robots.txt of a host not
  asked that day). At most 10 lists discovery added are read a day and an administrator adds at most 50 lists, so
  a day asks at most 120, and only when every read fails. A check a cap refused does not count and is made again
  at the next failed read.
- Our server calls the site of a list page itself, where only the live web processor did. The site sees the
  address of our server and our User-Agent. The Privacy page says so.

### Measurement

**Names as words.** Store-level ranks in `CatalogSearchRankingIT` (the case's rows only), before and after:
"ollama" puts ollama/ollama 4th of 4, then 1st; "llama.cpp" ggml-org/llama.cpp 3rd of 4, then 1st; "llama cpp"
3rd, then 2nd; "modelcontextprotocol inspector" the inspector 2nd of 3, then 1st; the scoped npm names stay 1st. On
the staging copy of 7 Nov 2026 (15,331 resources, the index alone) metasearch measured: "ollama" 290 → 1, "llama.cpp"
19 → 1, "langchain" 3 → 1; replacing only `/` made "llama.cpp" worse (19 → 43), which is why names are split too. The
fused order of a whole search also depends on the exact-title boost, which waits for graded queries
([0046](ADR-0046-evaluation-guide-and-search.md)). Cost: about 7 % more lexemes per row and 10 % more vector bytes; search SQL p50
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
index source's 3 s timeout ([0022](ADR-0022-federated-search.md)); the worst p95 here is under 0.1 s. One change came from
the first measurement: stage 1 joined `resource` again to read popularity, and with list rows the planner made that a
hash join over the whole table (p95 105–117 ms); carrying popularity from the two arms removed it.

### STRIDE

| Threat | Risk | Control |
|---|---|---|
| Spoofing | A list pretends to describe a tool it does not | Only the top 10 lists by stars (≥ 1000) are read; list words count half and never outrank a direct match; the card names the list. The resource page links a list's page in the catalog by its id, never an address read from a README. |
| Tampering | Markdown or SQL injection through README text or a query | README text is cleaned and goes in as a bind parameter; query text is only ever a parameter, its search syntax removed (`plainWords`); React escapes the heading path. |
| Repudiation | Not knowing why a result showed | `foundIn` names the list and heading; the list is a resource with its source record. The resource page names every list. |
| Information disclosure | README text or personal data kept | Only tsvectors and heading paths (≤ 200 characters) are stored; a test checks the columns and that an entry line is in no text column. A translated query is kept under a hash only. |
| Denial of service | A huge README, a query with many words, a translation flood | README read up to 5 MB within 60 s ([0006](ADR-0006-outbound-calls-and-resilience.md)); per-list caps; GIN indexes; at most 4 required variants; at most 10 lists on a resource detail; the translator has its own daily cap and the `llm-chat` circuit. |
| Elevation of privilege | — | No new endpoint, no new role; the list index is written by the Scout in the back office, and the public app only reads it. |

OWASP LLM Top 10: the translator's answer is untrusted (point 7); no model reads or writes the list text or the names.

### AI architecture check

| Layer | Status |
|---|---|
| Model | Covered: the fast model translates, NVIDIA only ([0008](ADR-0008-llm-integration.md)); the index and the names need no model. |
| Agent framework, Memory, Browser & Computer Use | Deferred, safe: none involved. |
| Orchestration | Covered: translation in parallel with early sources; the list index is part of the Scout pass, a failure is logged and the next pass rebuilds. |
| Tool & API | Covered: the same GitHub calls as before for the lists. |
| Knowledge & RAG | Covered: full-text recall by language, by name words and by list words, ranked below direct matches. Embeddings of list text deferred. |
| Data | Covered: `h2_name_words` and `search_vector` in V1, two derived list tables with FK cascade and no retention period; no text column. The rows of a list go when it is gone at its source (point 17): two columns on `list_seed` count the checks of a web page. |
| Guardrails | Covered: untrusted translation, cleaning, caps, bind parameters, half weight. |
| Observability | Covered: translation budget gauge, a log line per list pass. |
| Evaluation | Gap, deferred: the search set has no grades yet ([0046](ADR-0046-evaluation-guide-and-search.md)); the owner/name cases are store-level ITs, not graded metrics. |
| Deployment | Covered: V1, no setting, no chart change. |
| Security & Identity | Covered: no new endpoint or role. |
| Human-in-the-loop | Deferred, safe: lists are public, read-only and capped. |
| Cost & Reliability | Covered: the translator's daily cap and cache; no new calls for names or lists; p95 measured above. |

### Legal points
Tracked privately (S9.6.3).

### Known limits

- A search waits for the slowest answering source up to its deadline: Tavily (6 s) sets the worst case, and a non-English query can start most sources up to 2.5 s later for translation.
- The English/Italian lexicon still runs next to translation, so Italian gets two kinds of help and other languages rely on the model.
- Awesome-list words are English-stemmed: a list in another language matches only exact word forms. An entry whose repository was renamed since the list was written links the old URL and is left out of the index.
- A list on GitHub whose repository stays but whose README is deleted keeps the rows of its last read: nothing
  says that such a list is gone (point 17).
- A web page is checked only after a failed read. A page that answers 200 with an empty page, a sign-in wall or a
  "not found" text is a failed read with a status that says "there": it keeps what it named. So does a page
  whose site redirects it, a page robots.txt keeps us from asking about, and a page that is not read (switch off,
  host never read, removed from the lists to read).
- A candidate a list page of a directory host proposed, accepted after the page is gone, is still named by it:
  one row in `directory_list_entry` that no later read writes again. The catalog has no row for such a list, and
  the row in `list_seed` of a list discovery added is removed, so nothing says on accept that the list is gone.
- A list that left the top lists of the collector's search and still exists keeps the rows of its last read.
- With the repository sweep off (`H2_SCOUT_REPOSITORY_SWEEP_ENABLED=false`, or no GitHub token) no list is ever gone.

### Open points

- The exact-title boost for owner/name titles and the half weight of list words are decided on the graded search set.
- A renamed repository is not matched by its record URL.
- The list index is not language-aware.
- A list page of a directory host that is gone still names a candidate accepted later (Known limits).

## Diagrams

Languages: [flow](../diagrams/flow-0023-language-aware-search.svg) · [sequence](../diagrams/sequence-0023-language-aware-search.svg) ·
names and awesome lists: [flow](../diagrams/flow-0023-search-inside-awesome-lists.svg) · [sequence](../diagrams/sequence-0023-search-inside-awesome-lists.svg)

## Backlog

- Features: [F3.2](https://github.com/silvioincalza/helper2.ai/issues/48) Catalog full-text index, awesome-list words.
- Open issues that change this decision:
  - [#254](https://github.com/silvioincalza/helper2.ai/issues/254) S3.12.4 Two ranking rules decided on graded data
  - [#256](https://github.com/silvioincalza/helper2.ai/issues/256) S3.14.1 Search by meaning to match English resources
  - [#326](https://github.com/silvioincalza/helper2.ai/issues/326) S7.7.1 The UI in my language
