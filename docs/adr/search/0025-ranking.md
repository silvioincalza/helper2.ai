# 0025 — Ranking

**Depends on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0022](0022-federated-search.md).

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
2. **Index query in two stages** (`JdbcCatalogSearch.searchSql`). A row is retrieved when any word matches
   (`websearch_to_tsquery` with `english`, `simple` and the query language's configuration, generalized inverted index (GIN)) or when the title is
   similar: `lower(title) % lower(:raw)` (trigram GIN index) and `similarity > 0.35`. Stage 1 orders all matches by
   "contains every word of a variant", then `popularity + similarity`, and keeps 300 rows (`PRESELECT`). Stage 2 scores
   only those: `2 (all words) + popularity + 0.5 × min((ts_rank_cd × 3 + similarity) / 10, 1)`, then title.
   The query runs in about 0.2 s on the production catalog.
3. **Stored popularity.** `resource.popularity` (0..1) is kept by the trigger `source_record_popularity`:
   the strongest metric of the resource's records, `ln(1 + value) / ln(1 + reference)`, capped at 1.
   References: GitHub stars 50,000; Hugging Face likes 5,000; Hugging Face downloads 5,000,000; npm weekly downloads 1,000,000.
4. **Facts in batch.** `ResourceRows.withFacts` loads labels for all returned rows in one query and popularity metrics
   in one query (newest value per kind and source). So `CatalogSearchSource` and `SemanticSearchSource` hits carry the
   same metrics a live hit carries.
5. **Fusion across sources** (`Ranking.rank`, `k = 60`, `Ranking.FUSION_K`). Score = Σ over sources of `1 / (k + rank + 1)`, one vote
   per source id. `helper2-index`, `helper2-semantic` and `curated` vote apart: on the eval set one vote for the
   three (best rank of the three) loses nDCG@10 (§7).
   Title boost: +0.02 when the title equals the query (`exactTitle`) or holds every word of the first variant
   (`title`; type words and small words like "to" and "for" removed). An exact title gets no more than that (§7). A word counts only as a whole title word; a word of 4
   letters or more also counts when one starts with the other and is at least three quarters of its length ("agent"
   and "agents", not "data" and "datadog"). So "rag" does not boost
   "storage", and "ai" does not boost "email".
   `rank` is the hit's place among the hits the source kept: an item the source returned and the mapper dropped
   (outside AI, malformed) does not push the others down.
   +0.01 audience match and +0.005 type match for the chosen path; +0.005 when the type is a type hint.
   The weights are one record, `RankingWeights`; the evaluation replays the variants of §7 with it.
   The values stay in the code: the evaluation's baseline and its variants are the code's values, so a weight in a
   settings file would be a second source that the evaluation does not measure. The Settings page shows them
   read-only, in a group "Ranking" (`RankingSettingsConfiguration`): k, each weight as a whole number of thousandths
   (0.02 is 20), the lane window and the popularity references (`Ranking.POPULARITY_REFERENCE`). The group reads the
   `Ranking` bean the search uses, in both apps. A weight changes with a pull request and the evaluation of §7.
6. **Bounded popularity.** + `0.008 × popularity` (same references, strongest metric only, never summed).
   0.008 is about half the fusion score of a first place in one source (1/61). Ties: title, then id.
   One vote is worth 1/61 ≈ 0.0164 at the first place and 1/90 ≈ 0.0111 at the 30th. So the order is:
   - a title with every word (+0.02) beats one more source that found the result;
   - the path and type boosts (+0.01 audience, +0.005 path type, +0.005 type hint) are worth less than one more
     top-ranked source; the audience boost beats a vote only from the 40th place down (1/101), deeper than a source
     returns;
   - popularity (at most 0.008) is worth less than one more source at any place;
   - the place inside one source is the smallest signal: one place is worth 0.0001 to 0.0003.
   `RankingTest` pins this order.
   An unmaintained MCP server, library, skill or agent loses 0.008 (`RankingWeights.unmaintained`,
   [0020](../scout/0020-resource-vitality.md) §4): it stays above a result one source fewer found.
7. **Weights measured on the eval set.** `./gradlew evalTest` replays each variant of `RankingVariants` (131 queries,
   50 judged) and writes, in the section "Ranking variants" of `backend/eval-data/search-report.md`, its ΔnDCG@10
   against the weights of the code with the 95 % paired bootstrap interval of [0046](../operations/0046-evaluation.md),
   its unjudged share, its home examples and every group that moved. The results each variant brings into the top 10
   that have no grade go to `backend/eval-data/search-pool-variant-<variant>.tsv`, and into the judging pool.
   - Exact title +0.02 instead of +0.05: neutral overall (interval touches 0), navigational +0.009, MCP servers
     +0.028, no group lower, unjudged@10 unchanged (0.644). Kept for simplicity, not as a measured gain. The variant
     `exact title +0.05` shows the other side: −0.002 (−0.006 … 0.000).
   - One vote for index, semantic and curated (best rank of the three; each source keeps its own hits, vitality rule
     and limit, and only the votes are joined): −0.068 (−0.102 … −0.038), lower in 8 of the 10 judged groups. Not
     done: the index and the vectors agreeing is evidence.
   - Titles at +0.005 exact / +0.002 every word with popularity 0.002: +0.031 (0.002 … 0.061) once the 22 results it
     brings into the top 10 are judged, home examples 7 → 9, typo and synonym +0.183, home examples +0.072, news
     +0.058. But MCP servers −0.067 (−0.115 … −0.018) and navigational −0.117 (−0.313 … 0.000). Not done: one set of
     weights cannot serve both kinds of query.
   - Exact title kept at +0.02, every word +0.002, popularity 0.002: +0.029 (−0.000 … 0.060), MCP servers −0.088
     (−0.178 … −0.018), navigational −0.117. Exact title kept, every word +0.002 alone: +0.006 (−0.030 … 0.046), papers
     −0.168 (−0.268 … −0.069). So the loss on names comes from the every-word boost, not from the exact one. Not done.
   - Audience, path type and type hint halved: −0.023 (−0.040 … −0.008). Not done.
   - Popularity 0.002: +0.008 (−0.005 … 0.022), navigational −0.047. Not changed.
   The replay applies the vitality rule of [0020](../scout/0020-resource-vitality.md) to each source's hits, as the search
   does, and a snapshot holds what the rule reads: the update date and the facts of each hit.
   - No vote for a catalog hit of a result a live source found (variant `echo-no-vote`; the hit stays in the merge
     with its facts and loses only its vote; the snapshots do not say which catalog rows a live search wrote, so
     every such hit counts as an echo): −0.023 (−0.055 … 0.005), home examples −0.110 (−0.224 … −0.013), MCP
     servers −0.061; papers +0.070, models +0.066, news +0.063. Not done: a catalog row the Scout indexed is
     evidence, and this replay cannot tell it from an echo. A snapshot recorded from now on holds the data origin
     of each catalog hit, and the variant then takes the vote of the `OBSERVED` ones only.
   - News after the other results unless the question asks for news (variant `news-last`): −0.022
     (−0.046 … −0.002), news −0.084, libraries −0.035, everyday −0.034. Not done, for two reasons the replay shows:
     26 of the 333 good results outside the news group have the type `NEWS` (a product page a Hacker News story or
     the live web links, such as a PDF translator), and 5 of the 8 news questions have none of the words that mark
     a question as time-bound ("openai new model launch", "model context protocol announcement").
   Second reading, on the set recorded on 12 Nov 2026 against a copy of the staging catalog (135 queries, Spaces
   found by meaning, the data origin of each catalog hit). The grades are the ones of the first set, so 85 % of the
   first ten results have no grade and count as wrong. The replay now keeps the first 30 admitted hits of each
   source, as the search does.
   - `echo-no-vote`, now only for the `OBSERVED` rows: −0.020 (−0.048 … 0.008), MCP servers −0.073
     (−0.122 … −0.008), home examples −0.066. Of the 6,815 hits of the two catalog sources, 4,381 are `OBSERVED`.
     Still not done: the interval holds 0 and the names lose.
   - `one-catalog-vote`: −0.020 (−0.040 … 0.001), home examples −0.091 (−0.144 … −0.042), MCP servers −0.043.
     Still not done.
   - `news-last`: +0.031 (0.013 … 0.052), navigational +0.081, home examples +0.043, 8 → 9 home examples kept. The
     sign is the opposite of the first set. Not done yet: two sets disagree, and a rule that moves ungraded results
     down gains by construction when most results have no grade. Read it again after the new results are graded.
   - Without `hf-spaces` the replay gains +0.011 (0.004 … 0.019). No Space of the new set has a grade, so this says
     nothing about the Spaces yet.
   A query that names a thing (navigational, MCP server) needs the strong title boosts; a query that describes a need
   (everyday, home examples, typos) does better with weak ones. Weights chosen by the kind of query are open
   ([#251](https://github.com/silvioincalza/helper2.ai/issues/251)), measured on a new recording so they are not tuned on the queries they are tested on.
8. **Both kinds of user find a result on top** (`Ranking.withLanesOnTop`). A result is in a lane: *use it now*
   (`USE_AI`) or *build with it* (`BUILD_WITH_AI`). The lane is the result's audience when it states one of the two;
   otherwise the type decides: a `PRODUCT` is to use, a model, MCP server, library, skill, agent, prompt or dataset
   is to build with. News, papers, courses and events are in no lane. After the scores:
   - with no path chosen, when the first result is in a lane, the best result of the other lane inside the first 10
     (`RankingWeights.laneWindow`) moves to the second place;
   - with a path chosen, the best result of that lane inside the first 10 moves to the first place.
   One result moves, the others keep their order, and no score changes. Nothing moves when the query names a
   result (a title equals the query) or a type (a type hint). With no path nothing moves either when the first
   title holds every word of the query: the query names that result, and the second place is its neighbour.
   Measured on the eval set against the same code with `laneWindow = 0` (variant `scores-only`): +0.016 nDCG@10
   (−0.001 … 0.038), home examples +0.035, everyday +0.024, navigational −0.018 (−0.054 … 0.000), home examples
   that keep their promise 7 → 9 of 11. The interval touches 0 and the rule for names was chosen on these queries,
   so the gain is a first reading, not a proof.

### Code

- `backend/libs/search/src/main/kotlin/ai/helper2/search/QueryAnalyzer.kt` — type hints, synonyms, required-word variants, lexicon.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Ranking.kt` — fusion, boosts, bounded popularity, lanes on top, `RankingWeights`.
- `backend/libs/search/src/main/java/ai/helper2/search/internal/RankingSettingsConfiguration.java` — the read-only "Ranking" group of the Settings page; `search/config/SearchConfiguration.java` — the `Ranking` bean.
- `backend/apps/public/src/test/kotlin/ai/helper2/eval/RankingVariants.kt` — the weight variants the evaluation replays (§7).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcCatalogSearch.java` — `searchSql`, `search`; `ResourceRows.java` — `withFacts`, `popularity`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/CatalogSearchSource.java` — index rows as ranked hits.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `search_vector`, trigram index, popularity trigger.

## Alternatives considered

- Full-text score alone — it measures word density, not relevance (see Context).
- Popularity as a main signal — popular but unrelated results would win.
- Re-ranking with a model — cost and latency per search, and not repeatable.
- A learned ranking model — there is no labeled data to train or check it.
- A larger boost for the lane instead of a move — a boost large enough to reach the top would reorder the whole
  list, and §7 shows that one set of weights cannot serve names and needs.
- Lanes that alternate over the first six places — more results leave their place by score, with no grades yet to
  check them.
- Two lists side by side — a new page layout; one list with both lanes on top needs no change in the UI.

## Consequences

- The same query gives the same order. Every boost is a fixed, visible rule.
- The first two places are not always the two highest scores: with no path one of them is the best result of the
  other lane, and with a path the first is the best result of that lane, even below a result more sources found.
  The score rules of §6 hold for every other place.
- A popular project that matches poorly stays below a good match; popularity reorders results found by the same
  number of sources, never above one more source.
- A weight changes only with its numbers from the eval set; the snapshots are frozen, so an effect over time (a live
  hit written back to the catalog votes again in later searches) does not show there.
- The index query scores only 300 rows, so it stays fast on a large catalog.

### Known limits

- The popularity formula exists twice, in SQL (`h2_resource_popularity`) and Kotlin (`Ranking.popularity`); change them together. Index stage 1 can cut partial matches when more than 300 rows match.
- A live hit is written back to the catalog (observed), so in later searches it can be found again by `helper2-index` and
  `helper2-semantic`, each a vote of its own: a result seen early gains votes. The frozen eval snapshots cannot show
  this effect over time; one catalog vote costs nDCG@10 on them today ([ADR 0025](0025-ranking.md) §7).

## Diagrams

[flow](../../diagrams/flow-0025-ranking.svg) · [sequence](../../diagrams/sequence-0025-ranking.svg)

## Backlog

- Features: [F3.4](https://github.com/silvioincalza/helper2.ai/issues/50) Ranking: RRF, bounded popularity, two lanes ("use now" / "build with it").
- Open issues that change this decision:
  - [#251](https://github.com/silvioincalza/helper2.ai/issues/251) S3.12.1 Ranking weights chosen by the kind of query
  - [#252](https://github.com/silvioincalza/helper2.ai/issues/252) S3.12.2 A recency term for time-bound searches
  - [#253](https://github.com/silvioincalza/helper2.ai/issues/253) S3.12.3 The same order for the same query a few minutes later
  - [#254](https://github.com/silvioincalza/helper2.ai/issues/254) S3.12.4 Two ranking rules decided on graded data
