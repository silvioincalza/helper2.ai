# 0011 — Language-aware search: detected stemmer, query translation in parallel with sources

**Status.** Accepted. **Builds on:** [0008](0008-federated-search.md), [0009](0009-ranking.md)

## Context
Users type in many languages. Most sources and most catalog text are in English. An Italian query such as
"app per tradurre" finds little in English text unless it is translated. A wrong language guess is also harmful:
short technical queries such as "mcp postgres" or "vector database" look like Norwegian or Romanian to a plain detector.
A translation costs a model call and time, and it must not slow down sources that do not need it.

## Decision
1. **Detect, with evidence** (`LanguageDetector`, Lingua, 27 languages that have a PostgreSQL stemmer).
   No language for fewer than 6 letters. English means no translation. A short Latin-script text (under 8 words) must
   also contain a function word of that language ("per", "para", "zum"…) or a letter with a diacritic. Longer or
   non-Latin text needs a confidence gap of at least 0.05 over the second language.
2. **Stemmer in the index.** `SourceQuery.language` carries the International Organization for Standardization (ISO) 639-1 language code. The catalog query adds
   `websearch_to_tsquery(h2_ts_config(:lang), …)` next to `english` and `simple`. Stored text uses the stemmer of its
   own detected language in `search_vector`.
3. **Translation in parallel** (`SearchResources`). For a non-English query the model call starts first. Sources with
   `usesTranslation = false` start at the same time (`helper2-semantic`, which embeds the text as typed).
   The other sources start when the translation is in, or after `H2_SEARCH_TRANSLATE_TIMEOUT_MS` (2500 ms).
   Each source's deadline and latency count from its own start, so the wait never eats a source's budget.
4. **Translator** (`SpringAiQueryTranslator` in `search.internal`, the search module's adapter of its port
   `QueryTranslator`; model calls as in [0014](../guide/0014-llm-integration.md)): the fast model (`H2_NEBIUS_FAST_MODEL`), prompt
   `prompts/search/translate-query.txt`, at most 12 words (`QueryTranslator.MAX_WORDS`), 400 output tokens, thinking off.
   Its own daily cap `H2_SEARCH_DAILY_TRANSLATIONS` (300 per day; days are in Coordinated Universal Time, UTC), separate from the guide's cap; gauge `helper2.search.translation.budget.remaining`. Answers are shared by
   every replica in Redis ([0005](../foundation/0005-redis-disposable-cache.md)): key `h2:v1:translation:{sha256}` over the model,
   the prompt version, the language and the normalised query (trimmed, spaces collapsed, lower case), value the English
   words, TTL `H2_SEARCH_TRANSLATION_TTL` (24 h). A failed, empty or refused translation is not cached, and a Redis error is a
   miss, so a query any replica translated costs no model call on the others. Off when no model is configured or `helper2.search.translate-queries=false`.
   The call runs under the `llm-chat` circuit ([0033](../foundation/0033-resilience-policies.md)); the budget is taken inside it, so
   a call the open circuit refuses costs no translation.
5. **Untrusted answer.** The answer is dropped if it looks like a link. Only letters, digits, spaces and hyphens stay;
   at most 12 words and 120 characters. It is dropped if it equals the normalized query. Any failure, timeout or open
   circuit means the search runs untranslated.
6. **Disclosed.** The English words go first into the expansions and the required-word variants, so live sources
   receive them as extra terms and the user sees "Also searched for: …" (`QUERY_EXPANDED`).

## Consequences
- Queries in other languages find English resources, and the user sees the words that were added.
- A model outage or a spent budget only removes the translation; search still answers.
- English queries cost no model call and no wait.
- A translated query is in Redis for 24 h, under a hash; the privacy page states it.

## Alternatives considered
- Always translate — a model call for every English query too, and wrong guesses on short queries.
- Translate before starting any source — the semantic source would wait for nothing.
- Translate the catalog into many languages — storage and model cost for every resource.
- Rely only on multilingual embeddings — keyword sources and live application programming interfaces (APIs) would still miss the query.
- A cache in each replica's memory — each of the 3 prod replicas pays its own model call for the same query.
- An in-memory cache in front of Redis — it saves a Redis read of about 1 ms next to a wait of up to 2.5 s, for a second
  TTL and size bound to reason about.

## Code
- `backend/src/main/kotlin/ai/helper2/shared/LanguageDetector.kt` — detection rules.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — `startTranslation`, `awaitTranslation`, `withTranslation`, early and late sources.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt` — `QueryTranslator`.
- `backend/src/main/java/ai/helper2/search/internal/SpringAiQueryTranslator.java`, `TranslationConfiguration.java` — model
  call, budget, cache key.
- `backend/src/main/java/ai/helper2/platform/cache/SharedCache.java`, `RedisSharedCache.java` — the shared cache.
- `backend/src/main/resources/prompts/search/translate-query.txt` — the prompt.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — language stemmer in the index query.
- `backend/src/main/resources/db/migration/V1__schema.sql` — `h2_ts_config`, `search_vector`.

## Diagrams
[flow](../../diagrams/flow-0011-language-aware-search.svg) · [sequence](../../diagrams/sequence-0011-language-aware-search.svg)

## Roadmap
- [More user interface (UI) languages; the UI is English only](../../roadmap.md#7-frontend).
- [Cross-language retrieval in vector search](../../roadmap.md#5-ai-quality-and-cost).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
