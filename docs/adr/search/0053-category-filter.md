# 0053 — Categories as a search filter, assigned by code

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0008](0008-federated-search.md), [0010](0010-filters-facets-dates-timeline.md), [0015](../guide/0015-ai-guide-evidence-gate.md), [0036](../quality/0036-search-evaluation-harness.md), [0038](../guide/0038-ai-content-marking.md), [0041](../guide/0041-guide-answer-cache.md), [0042](../frontend/0042-redesign-design-system-v4.md), [0051](../scout/0051-tavily-discovery.md)

## Context
[0051](../scout/0051-tavily-discovery.md) split the AI world in 34 categories in 7 families. Each category is a
discovery collector, and its id goes in the tags of what it finds. That helps the Scout, not the person who searches:
there is no way to say "only voice and audio tools". The tags cannot do it either. They are capped at 20 per resource
(the first 20, alphabetical), a GitHub repository often has more, and a category tag can fall off the list. Only the
discovery collectors put category ids in the tags, so a model from Hugging Face or a package from npm has none.

We want categories that every resource can have, whatever source found it, and that search can filter by. We want
them assigned by code first, and measured on labelled items before a model is ever considered.

## Decision
1. **A label kind of its own.** A resource's categories are `resource_label` rows of kind `CATEGORY` (migration
   `V11`), apart from `TAG`. They do not count in the 20 tags. A resource can have several.
2. **The categories come from one list.** `helper2.categories` in `application.yaml` stays the one list
   ([0051](../scout/0051-tavily-discovery.md)). Search and the catalog read it through `CategoriesProperties`
   (platform) into a `CategoryCatalog` (shared kernel): id, name, short name, family, words, require-keyword and the
   discovery type, in the configured order. The words are `CategoryWords` in the shared kernel. It is the class the
   discovery collectors already used (`DiscoveryCategory.Keywords` moved there), so the words and their precompiled
   patterns exist once. Startup fails on an id that is not lower-case words with hyphens, on two categories with one
   id, and on the id `unknown`.
3. **Assigned by code.** `CategoryMatcher` (shared kernel, pure, no model) gives a resource a category when:
   - a collector of that category found it (`discovery-<id>`, as a record's source or in an accepted candidate's
     `foundBy`);
   - one of the category's words is in its title or in what its sources wrote;
   - one of its tags is the category's id or has one of its words (each tag alone, so two tags never join into one
     phrase);
   - its type is `PROMPT` or `SKILL` and exactly one category has that type (prompt libraries, skills and GPTs).

   Health and legal require a word: they come only from their finder or a word in the title or description, never
   from a tag or a type alone. An AI-written description (`AI_EXTRACTED`, `AI_SUMMARY`) is never read: categories
   come from public source data only. So they are code-made values, not AI-made ones, and carry no AI label
   ([0038](../guide/0038-ai-content-marking.md)). The page says so in one line: "Categories are set by fixed rules
   from what the sources say, not by AI."
4. **When.** On every catalog upsert, for every source: after the labels are rebuilt, `ResourceStore` reads each
   touched resource's title, its records' descriptions, every tag of every record, its type and its finders, runs the
   matcher and replaces its `CATEGORY` labels. One extra query per upsert batch, not per resource.
5. **Backfill.** `BackfillCategories` (catalog use case) assigns the categories of the whole catalog again, 500
   resources a batch, each batch its own transaction, on one replica (cluster lock `category-backfill`). It is
   idempotent. It writes the fingerprint of the category words and rules (`CategoryCatalog.fingerprint`) and the
   number of the last migration to `catalog_marker`. At startup it runs in the background when either changed
   (`H2_CATEGORIES_BACKFILL_AT_STARTUP`, default true): the seeds (`R__`) write curated rows without the code, so
   a seed that ran again needs its rows matched. The seeds replace only their own labels, never `CATEGORY`. An admin can start it from the Scout runs page
   (`POST /api/v1/admin/category-backfill`, 202, audited as `BACKFILL_CATEGORIES`). The metric
   `helper2.categories.backfill.resources{result=categorized|uncategorized}` counts what it found.
6. **Live results too.** `ResultMerger` gets the matcher. A merged result's categories are what the catalog stored for
   its hits plus what the matcher reads in the merged result, in the configured order. So a Tavily or GitHub result
   seen for the first time can be filtered by category in the same search.
7. **The filter.** `GET /search?category=` takes category ids, repeatable: a result in any of them is kept (OR), and
   the category filter is AND with the other filters. `unknown` keeps the results with no category yet. Every value
   is checked against the configured ids: an unknown one is a 400 with problem details.
8. **The facet.** `facets.category` counts each category, and `unknown`, over the merged result set in memory, with
   every filter but the category's own (disjunctive, like the other facets of
   [0010](0010-filters-facets-dates-timeline.md)). No extra database query per category.
9. **The list.** `GET /api/v1/categories` gives id, name, short name and family in the configured order. The same
   answer for everyone: `Cache-Control: public, max-age=3600`, an ETag of the list (a 304 when it matches), and the
   same rate limit as the other public reads.
10. **The page** (design v4, [0042](../frontend/0042-redesign-design-system-v4.md)).
    - The Filters panel has a Category facet: one group per family (a fieldset with its legend), each category with
      its count, then "No category yet". Only categories with results, or already chosen, are listed, so no choice
      leads to an empty page. It says when the names are loading, says when they could not be loaded with Try again,
      and says when no result has a category yet.
    - A chosen category is a chip ("Category: Coding") with one-click removal, and it is in the URL
      (`?category=coding`), so a shared link and Back give the same page.
    - The resource page lists its categories, each a link to a search for its name filtered by it.
11. **The guide honours the filter.** `GuideRequest.categories` carries the page's category filter. It is checked like
    the search's. The pick searches of the guide add it to their filters (`PickAnswer.searchFor`), so the guide picks
    only in those categories. The subject searches of a comparison or an explanation do not: a named thing must be
    found wherever it is. The filter is part of the answer cache key, and a question with a filter never reuses a
    similar question's answer ([0041](../guide/0041-guide-answer-cache.md)).
12. **Measured first.** `backend/src/test/resources/eval/categories-labelled.csv` holds the labels a person gives (150
    items, to do). `./gradlew evalTest --tests ai.helper2.eval.CategoryEvalTest` prints precision and recall per
    category and writes `build/eval/categories.md`. It is skipped while the file has no rows.

## Consequences
- Every resource can be found by category, from any source, without a model call and without a cost per search.
- Tags stay what the sources say. Categories do not take their room.
- A change of the category words reaches the whole catalog at the next startup, with no migration.
- Recall depends on the words. A tool whose page says "voice cloning" but not one of the voice words has no category.
  The eval tells us where, per category. "No category yet" makes the gap visible on the page.
- A category assigned by a tag can be wrong when a source tags loosely. Health and legal are protected from that.
- Facet counts see only the hits fetched for this query, like every facet ([0010](0010-filters-facets-dates-timeline.md)).
- A changed category filter on a page where the guide was asked asks the guide again (one more AI answer of the day,
  unless the same question with the same filter is in the cache).
- Use case is a separate facet for now. Folding it into category is the next step, after the eval.

## Security (STRIDE)
- **Spoofing:** none new. The list and the filter are public reads; the backfill action is admin only (back office,
  VPN, [0035](../edge/0035-admin-host.md)) and audited.
- **Tampering:** a page can put words in its title to get a category. The worst it gets is a wrong filter match on a
  public page, the same as a wrong tag today. Health and legal still need a word in the page's own text or their own
  collector.
- **Repudiation:** the admin backfill is in the audit trail (`BACKFILL_CATEGORIES`).
- **Information disclosure:** no personal data. Categories come from public source data; the filter values are
  category ids from a fixed list. The guide's category filter is part of the cache key as ids only, and the cached
  answer keeps it for as long as the answer (`H2_GUIDE_CACHE_TTL`, 24 h), not linked to a person. The Privacy page
  says so, next to what the guide receives and caches.
- **Denial of service:** `category` has at most 40 values, each tag-shaped and at most 30 characters, checked against
  the list before the search runs. The facet is computed in memory over at most 30 hits per source. The backfill is
  batched, holds a cluster lock and runs in the background. `GET /categories` is cached and rate-limited.
- **Elevation:** `POST /admin/category-backfill` is under `/api/v1/admin/**` (ADMIN role, CSRF).
- **OWASP LLM:** no model reads or writes categories, so no prompt injection reaches them. The guide only filters its
  candidates by them.

## AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: no model. Categories are code; a model is considered only after the eval. |
| 2 | Agent Framework | Covered: the guide's pick pipeline gets one more filter, nothing else changes. |
| 3 | Orchestration | Covered: assigned in the upsert transaction; backfill batched, one replica, idempotent, marker. |
| 4 | Tool & API | Covered: `GET /categories`, `?category=`, `GuideRequest.categories`, admin backfill, in the contract. |
| 5 | Memory | Covered: the filter is in the URL; the guide's cache key includes it. |
| 6 | Knowledge & RAG | Covered: retrieval filters by category before the guide chooses; no re-ranking change. |
| 7 | Data | Covered: `CATEGORY` labels (V11), `catalog_marker`; provenance is the records the matcher read. |
| 8 | Browser & Computer Use | Deferred: not used. |
| 9 | Guardrails | Covered: ids checked against the list (400); require-keyword categories; no AI text read. |
| 10 | Observability | Covered: backfill metric and log line; no user text logged. |
| 11 | Evaluation | Gap until labelled: the harness exists, the 150 labels are Silvio's to write. |
| 12 | Deployment | Covered: one migration, one `H2_` variable with a safe default; rollback leaves unused labels. |
| 13 | Security & Identity | Covered: admin action under the admin role and audited. |
| 14 | Human-in-the-Loop | Covered: labels by a person decide whether code is enough; the admin starts a backfill. |
| 15 | Cost & Reliability | Covered: no model or credit cost; one cached list; backfill off the request path. |

## Alternatives considered
- **A model assigns the categories** (the fast model, on enrichment) — better recall on odd pages, but a model call per
  resource, values that need an AI label and a check, and a model that reads untrusted pages. Rejected for now: code
  first, measured on 150 labelled items; a model only where the numbers say code is not enough.
- **Reuse the TAG label** (the discovery collectors already tag their category) — no migration, but the 20-tag cap
  drops categories, a source's own tag "coding" would count as the category, and only discovery results have them.
- **Facet counts in the database** (one `GROUP BY` over `resource_label`) — counts over the whole catalog, but an extra
  query per search, and it would not count live web results or agree with the other facets, which count the merged
  set ([0010](0010-filters-facets-dates-timeline.md)).
- **Categories as a hard-coded enum** — type-safe, but a new category would need a code change and a release; the
  YAML list is already checked at startup.

## Code
- `backend/src/main/kotlin/ai/helper2/shared/Categories.kt` — `Category`, `CategoryCatalog`, `CategoryInput`,
  `CategoryMatcher`; `CategoryWords.kt` — the words and their patterns (moved from `DiscoveryCategory.Keywords`).
- `backend/src/main/java/ai/helper2/platform/config/CategoriesProperties.java`, `platform/CategoryConfiguration.java`.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` (`assignCategories`, `refreshCategories`,
  `resourceIdsAfter`, `marker`, `mark`), `CatalogSearchSource.java`, `ResourceCards.java`,
  `CategoryBackfillAtStartup.java`; `catalog/usecase/BackfillCategories.java`; `catalog/config/CatalogProperties.java`
  (`categories.backfill-at-startup`).
- `backend/src/main/kotlin/ai/helper2/search/ResultMerger.kt` (`categoriesOf`), `Filtering.kt` (the `category`
  facet), `Model.kt` (`SourceHit.categories`, `MergedResult.categories`).
- `backend/src/main/java/ai/helper2/search/web/SearchController.java` (`categoryFilter`), `CategoriesController.java`,
  `CardMapper.java`; `admin/web/AdminController.java`; `guide/web/GuideStreamController.java`;
  `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt`, `guide/steps/PickAnswer.kt`, `guide/steps/Question.kt`.
- `backend/src/main/resources/db/migration/V11__resource_categories.sql`; the `R__` seeds keep `CATEGORY` labels.
- `frontend/src/components/CategoryFacet.tsx`, `FilterPanel.tsx`, `ActiveFilters.tsx`,
  `components/results/CategoryLinks.tsx`, `Resource.tsx`, `Results.tsx`, `hooks/useCategories.ts`,
  `hooks/useGuideStream.ts`, `lib/searchState.ts`, `pages/admin/CategoryBackfill.tsx`.
- Tests: `CategoryMatcherTest`, `CategoryFilterTest`, `AskGuideCategoryTest`, `CategoryEvaluationTest`,
  `CategoriesPropertiesBindingTest`, `ResourceStoreIT`, `BackfillCategoriesIT`, `CategoriesApiIT`,
  `CategoryFilter.test.tsx`, `CategoryBackfill.test.tsx`, `searchState.test.ts`; eval: `CategoryEvalTest`.
- Diagrams: [flow](../../diagrams/flow-0053-category-filter.svg) · [sequence](../../diagrams/sequence-0053-category-filter.svg).
