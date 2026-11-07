# 0013 — Filters, facets, categories and works-with flags

**Status.** Accepted. Merges old 0010, 0013, 0053 (texts merged in PR 3b of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0010"></a>

## Part: was ADR 0010 — Filters, facets with Unknown, sort, stated dates, list and timeline view

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0009](0009-federated-search.md)

### Context
Sources state different facts, and many facts are missing. A filter must not hide a resource only because no
source stated the value, and it must not pretend a value is known. Dates are the most misleading field:
"when was this made" and "when did Helper2.ai first see it" are different facts. Users also want a view of
results by time, not only by relevance.

### Decision
1. **Filters** (`SearchFilters`, query parameters of `GET /api/v1/search`): type, source, license, openSource,
   pricing, platform, useCase, techLevel, audience, updatedAfter, and category
   ([the part that was ADR 0053](#part-0053)). They apply after merge and ranking (`Filtering.apply`).
2. **Unknown is a value.** When no source states a value, the facet value is `unknown`, and it can be selected like
   any other. The license facet matches every license any source asserted (case-insensitive), so a resource whose
   sources disagree still matches. An audience of `BOTH` counts for both paths. `updatedAfter` drops results whose
   updated date is unknown.
3. **Disjunctive facets** (`Facets.compute`). Each facet's counts respect every selected filter except its own.
   Known values come first by count, `unknown` last.
4. **Sort** (`Sorting.sort`): `RELEVANCE` (ranking order, [0012](0012-ranking.md)), `NEWEST` (published date),
   `RECENTLY_UPDATED` (updated date). Unknown dates always sort last; ties keep relevance order.
   Paging: `page` 0..50, `size` 1..50, default 20.
5. **Stated dates** (`DateFact`, rules in [0004](../foundation/0004-persistence-schema-and-app-roles.md)): value, precision (`INSTANT`, `DAY`,
   `UNKNOWN`), source id, alternatives, status. When the sources of one merged result differ ([0009](0009-federated-search.md)):
   - published and event start keep the earliest value (`DateFact.earliest`), event end the latest (`DateFact.latest`).
     The status is `CONFLICTING` when two different sources state different days in Coordinated Universal Time (UTC);
     a different time on the same day, or one source with two values, is not a conflict;
   - the last update keeps the most recent value (`DateFact.lastChange`) and is never `CONFLICTING`: each source
     reports when its own part last changed;
   - the other values stay as alternatives. `DateFactView` shows a conflicting date as a "conflicting" badge with
     the kept value and the alternatives, each with its source. Sort and the timeline still use the kept value.
   Dates read from the catalog (detail page, next events) have one value each and are never `CONFLICTING`.
   `discovered` is Helper2.ai's own first-seen time (source id `helper2`). Event days (`eventStart`, `eventEnd`) are
   sent only for `EVENT` results.
6. **Timeline view** (frontend only). `view=timeline` stays in the browser address (URL). `toApiParams` sends `sort=NEWEST` and
   `size=50`. `ResultsTimeline` groups cards by the Coordinated Universal Time (UTC) month of the published date. Results without a stated date
   form one "unknown" group at the end. The discovery date is never used to place a result in time.

### Consequences
- A missing fact is visible and filterable; nothing is guessed.
- Facet counts tell the user how many results each choice would give.
- A date that sources disagree on is marked as such, and the user sees every stated value.

### Alternatives considered
- Hide results with an unknown value when a filter is set — the user would lose results without knowing why.
- Use the discovery date when the published date is missing — it states when Helper2.ai looked, not when the item was made.
- Filter in each source's application programming interface (API) — most sources have no such filters, and results would differ by source.
- Timeline from the server — a second endpoint for the same data; the sort `NEWEST` already gives the order.

### Code
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Filtering.kt` — filters, facets, sort, `UNKNOWN`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/DateFact.kt` — stated dates, plausibility, `earliest`, `latest`,
  `lastChange`, conflict rule.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — which rule each date of a merged result uses.
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/SearchController.java` — query parameters to `SearchFilters`.
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/CardMapper.java` — dates and event days on the card.
- `frontend/src/lib/searchState.ts` — URL state, `toApiParams`, `TIMELINE_PAGE_SIZE`.
- `frontend/src/lib/timeline.ts`, `frontend/src/components/ResultsTimeline.tsx` — grouping by creation month.
- `frontend/src/components/FilterPanel.tsx`, `DateFactView.tsx` — facets and date display.

### Diagrams
[flow](../../diagrams/flow-0013-filters-facets-dates-timeline.svg) · [sequence](../../diagrams/sequence-0013-filters-facets-dates-timeline.svg)

### Roadmap
Nothing open.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0013"></a>

## Part: was ADR 0013 — "Works with" flags derived from current source tags

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0009](0009-federated-search.md)

### Context
Users want to know if a resource works with the tool they already use (OpenAI, Ollama, a Model Context Protocol (MCP) client, VS Code…).
Sources rarely state this as a field, but they often tag it ("ollama", "langchain", "mcp-server").
A model could guess compatibility, but a guess shown as a fact is wrong for this product. A tag that a source removes
must also remove the flag.

### Decision
1. **Rules, no model** (`Compatibility.of(MergedResult)`). A fixed list of 18 tools (`Compatibility.TOOLS`):
   model providers (OpenAI, Anthropic, Google, xAI, Mistral, DeepSeek, NVIDIA), local runtimes (Ollama, llama.cpp,
   Apple MLX, Transformers, vLLM), frameworks (LangChain, LlamaIndex), clients and editors (any MCP client, Claude Code,
   Cursor, VS Code). Each tool has a set of tags and, for some, file formats. A tag that names something else too
   is left out: NVIDIA NIM matches `nvidia-nim` and `nvidia-ai`, not `nim`, which is also the Nim language.
   `cursor` stays: it is the editor's only tag, and in search results it comes with AI tags (`mcp`, `claude`,
   `codex`) far more often than with terminal or pagination tags.
2. **Three bases.** `TYPE`: an `MCP_SERVER` works with any MCP client. `TAG`: a source tag is in the tool's tag set.
   `FORMAT`: the tag `gguf` gives Ollama and llama.cpp. Tags are compared in a normalized form (trimmed, lower case,
   `_` and spaces become `-`).
3. **Evidence on every flag.** `CompatibilityFlag` holds the tool, label, basis, the tag or type seen, and the id of
   the source that stated it (null for `TYPE`). The first source in merge order wins. Flags follow the order of `TOOLS`.
4. **Current tags only.** For catalog hits, tags come from `resource_label`. `ResourceStore.replaceLabels` rebuilds
   these rows from the current `source_record.extra` on every upsert, so a dropped tag drops the flag. At most 20 tags
   per resource are kept (alphabetical), each cut to 60 characters.
5. **On every card and every guide pick.** `CardMapper.compatibility` (named interface `cards` of the search module)
   maps the flags once. `CardMapper.card` adds them as `compatibleWith`, so search results, resource pages, the "new"
   list and upcoming events show the same flags. `GuideDtoMapper` adds the same list to every guide recommendation
   (`GuideRecommendation.compatibleWith`), the AI picks and the plain top results shown when no AI pick survived.
   The server always sends the list, `[]` when no rule matched. The flags are not AI-written: they are outside
   `aiContent.fields` ([0018](../guide/0018-ai-content-marking-and-research-trail.md)). The contract states they are never
   model-generated. In the web app a pick is shown as its result card (row and preview) or links to its resource
   page, so the flags show there, outside the AI-labelled reason.

### Consequences
- Every flag can be traced to a tag, a type or a format; the rules are public.
- No model cost, no latency, the same input always gives the same flags.

### Alternatives considered
- Ask a model per resource — guesses shown as facts, plus cost.
- Store flags at index time — they would go stale when tags change; computing them per card is cheap.
- Read README files for tool names — a mention is not a statement of support.

### Code
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Compatibility.kt` — tools, rules, flags.
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/CardMapper.java` — `compatibility`, and `compatibleWith` on each card.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideDtoMapper.java` — `compatibleWith` on each guide recommendation.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `replaceLabels`, `MAX_TAGS`.
- `api/common.yaml, api/public.yaml` — `CompatibilityFlag`, `ResultCard.compatibleWith`, `GuideRecommendation.compatibleWith`.
- `frontend/src/components/results/ResultFacts.tsx` — "Works with" in the Build facts of the preview and the resource page; the reason as tooltip and screen-reader text.

### Diagrams
[flow](../../diagrams/flow-0013-works-with-flags.svg) · [sequence](../../diagrams/sequence-0013-works-with-flags.svg)

### Roadmap
Nothing open.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0053"></a>

## Part: was ADR 0053 — Categories as a search filter, assigned by code

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0009](0009-federated-search.md), [the part that was ADR 0010](#part-0010), [0016](../guide/0016-guide-pipeline.md), [0036](../quality/0036-evaluation.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md), [0028](../frontend/0028-design-system.md), [0022](../scout/0022-discovery.md)

### Context
[0022](../scout/0022-discovery.md) split the AI world in 34 categories in 7 families. Each category is a
discovery collector, and its id goes in the tags of what it finds. That helps the Scout, not the person who searches:
there is no way to say "only voice and audio tools". The tags cannot do it either. They are capped at 20 per resource
(the first 20, alphabetical), a GitHub repository often has more, and a category tag can fall off the list. Only the
discovery collectors put category ids in the tags, so a model from Hugging Face or a package from npm has none.

We want categories that every resource can have, whatever source found it, and that search can filter by. We want
them assigned by code first, and measured on labelled items before a model is ever considered.

### Decision
1. **A label kind of its own.** A resource's categories are `resource_label` rows of kind `CATEGORY` (migration
   `V11`), apart from `TAG`. They do not count in the 20 tags. A resource can have several.
2. **The categories come from one list.** `helper2.categories` in `application.yaml` stays the one list
   ([0022](../scout/0022-discovery.md)). Search and the catalog read it through `CategoriesProperties`
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
   ([0018](../guide/0018-ai-content-marking-and-research-trail.md)). The page says so in one line: "Categories are set by fixed rules
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
   [the part that was ADR 0010](#part-0010)). No extra database query per category.
9. **The list.** `GET /api/v1/categories` gives id, name, short name and family in the configured order. The same
   answer for everyone: `Cache-Control: public, max-age=3600`, an ETag of the list (a 304 when it matches), and the
   same rate limit as the other public reads.
10. **The page** (design v4, [0028](../frontend/0028-design-system.md)).
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
    similar question's answer ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)).
12. **Measured first.** `backend/apps/public/src/test/resources/eval/categories-labelled.csv` holds the labels a person gives (150
    items, to do). `./gradlew evalTest --tests ai.helper2.eval.CategoryEvalTest` prints precision and recall per
    category and writes `build/eval/categories.md`. It is skipped while the file has no rows.

### Consequences
- Every resource can be found by category, from any source, without a model call and without a cost per search.
- Tags stay what the sources say. Categories do not take their room.
- A change of the category words reaches the whole catalog at the next startup, with no migration.
- Recall depends on the words. A tool whose page says "voice cloning" but not one of the voice words has no category.
  The eval tells us where, per category. "No category yet" makes the gap visible on the page.
- A category assigned by a tag can be wrong when a source tags loosely. Health and legal are protected from that.
- Facet counts see only the hits fetched for this query, like every facet ([the part that was ADR 0010](#part-0010)).
- A changed category filter on a page where the guide was asked asks the guide again (one more AI answer of the day,
  unless the same question with the same filter is in the cache).
- Use case is a separate facet for now. Folding it into category is the next step, after the eval.

### Security (STRIDE)
- **Spoofing:** none new. The list and the filter are public reads; the backfill action is admin only (back office,
  VPN, [0024](../edge/0024-identity-and-admin-host.md)) and audited.
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

### AI architecture check
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

### Alternatives considered
- **A model assigns the categories** (the fast model, on enrichment) — better recall on odd pages, but a model call per
  resource, values that need an AI label and a check, and a model that reads untrusted pages. Rejected for now: code
  first, measured on 150 labelled items; a model only where the numbers say code is not enough.
- **Reuse the TAG label** (the discovery collectors already tag their category) — no migration, but the 20-tag cap
  drops categories, a source's own tag "coding" would count as the category, and only discovery results have them.
- **Facet counts in the database** (one `GROUP BY` over `resource_label`) — counts over the whole catalog, but an extra
  query per search, and it would not count live web results or agree with the other facets, which count the merged
  set ([the part that was ADR 0010](#part-0010)).
- **Categories as a hard-coded enum** — type-safe, but a new category would need a code change and a release; the
  YAML list is already checked at startup.

### Code
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Categories.kt` — `Category`, `CategoryCatalog`, `CategoryInput`,
  `CategoryMatcher`; `CategoryWords.kt` — the words and their patterns (moved from `DiscoveryCategory.Keywords`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/config/CategoriesProperties.java`, `platform/CategoryConfiguration.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` (`assignCategories`, `refreshCategories`,
  `resourceIdsAfter`, `marker`, `mark`), `CatalogSearchSource.java`, `ResourceCards.java`,
  `CategoryBackfillAtStartup.java`; `catalog/usecase/BackfillCategories.java`; `catalog/config/CatalogProperties.java`
  (`categories.backfill-at-startup`).
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` (`categoriesOf`), `Filtering.kt` (the `category`
  facet), `Model.kt` (`SourceHit.categories`, `MergedResult.categories`).
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/SearchController.java` (`categoryFilter`), `CategoriesController.java`,
  `CardMapper.java`; `admin/web/AdminController.java`; `guide/web/GuideStreamController.java`;
  `backend/apps/public/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt`, `guide/steps/PickAnswer.kt`, `guide/steps/Question.kt`.
- `backend/libs/schema/src/main/resources/db/migration/V12__resource_categories.sql`; the `R__` seeds keep `CATEGORY` labels.
- `frontend/src/components/CategoryFacet.tsx`, `FilterPanel.tsx`, `ActiveFilters.tsx`,
  `components/results/CategoryLinks.tsx`, `Resource.tsx`, `Results.tsx`, `hooks/useCategories.ts`,
  `hooks/useGuideStream.ts`, `lib/searchState.ts`, `pages/admin/CategoryBackfill.tsx`.
- Tests: `CategoryMatcherTest`, `CategoryFilterTest`, `AskGuideCategoryTest`, `CategoryEvaluationTest`,
  `CategoriesPropertiesBindingTest`, `ResourceStoreIT`, `BackfillCategoriesIT`, `CategoriesApiIT`,
  `CategoryFilter.test.tsx`, `CategoryBackfill.test.tsx`, `searchState.test.ts`; eval: `CategoryEvalTest`.
- Diagrams: [flow](../../diagrams/flow-0013-category-filter.svg) · [sequence](../../diagrams/sequence-0013-category-filter.svg).
