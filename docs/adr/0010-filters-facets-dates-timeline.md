# 0010 — Filters, facets with Unknown, sort, stated dates, list and timeline view

**Status.** Accepted (2026-10-27). **Builds on:** [0004](0004-persistence-and-provenance.md), [0008](0008-federated-search.md)

## Context
Sources state different facts, and many facts are missing. A filter must not hide a resource only because no
source stated the value, and it must not pretend a value is known. Dates are the most misleading field:
"when was this made" and "when did Helper2.ai first see it" are different facts. Users also asked for a view of
results by time, not only by relevance.

## Decision
1. **Filters** (`SearchFilters`, query parameters of `GET /api/v1/search`): type, source, license, openSource,
   pricing, platform, useCase, techLevel, audience, updatedAfter. They apply after merge and ranking (`Filtering.apply`).
2. **Unknown is a value.** When no source states a value, the facet value is `unknown`, and it can be selected like
   any other. The license facet matches every license any source asserted (case-insensitive), so a resource whose
   sources disagree still matches. An audience of `BOTH` counts for both paths. `updatedAfter` drops results whose
   updated date is unknown.
3. **Disjunctive facets** (`Facets.compute`). Each facet's counts respect every selected filter except its own.
   Known values come first by count, `unknown` last.
4. **Sort** (`Sorting.sort`): `RELEVANCE` (ranking order, [0009](0009-ranking.md)), `NEWEST` (published date),
   `RECENTLY_UPDATED` (updated date). Unknown dates always sort last; ties keep relevance order.
   Paging: `page` 0..50, `size` 1..50, default 20.
5. **Stated dates** (`DateFact`, rules in [0004](0004-persistence-and-provenance.md)): value, precision (`INSTANT`, `DAY`,
   `UNKNOWN`), source id, alternatives, status. When the sources of one merged result differ ([0008](0008-federated-search.md)):
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

## Consequences
- Good: a missing fact is visible and filterable; nothing is guessed.
- Good: facet counts tell the user how many results each choice would give.
- Bad: filters and facets work on the hits fetched for this query (at most 30 per source), not on the whole catalog.
- Bad: the timeline pages through results 50 at a time, so one month can be split across two pages.
- Good: a date that sources disagree on is marked as such, and the user sees every stated value.
- Trade-off: the earliest (or latest) value is still used to sort and place the result; a wrong early date from one
  source moves the result, but the badge shows that the date is disputed.

## Alternatives considered
- Hide results with an unknown value when a filter is set — the user would lose results without knowing why.
- Use the discovery date when the published date is missing — it states when Helper2.ai looked, not when the item was made.
- Filter in each source's application programming interface (API) — most sources have no such filters, and results would differ by source.
- Timeline from the server — a second endpoint for the same data; the sort `NEWEST` already gives the order.

## Code
- `backend/src/main/kotlin/ai/helper2/search/Filtering.kt` — filters, facets, sort, `UNKNOWN`.
- `backend/src/main/kotlin/ai/helper2/shared/DateFact.kt` — stated dates, plausibility, `earliest`, `latest`,
  `lastChange`, conflict rule.
- `backend/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — which rule each date of a merged result uses.
- `backend/src/main/java/ai/helper2/search/web/SearchController.java` — query parameters to `SearchFilters`.
- `backend/src/main/java/ai/helper2/search/web/CardMapper.java` — dates and event days on the card.
- `frontend/src/lib/searchState.ts` — URL state, `toApiParams`, `TIMELINE_PAGE_SIZE`.
- `frontend/src/lib/timeline.ts`, `frontend/src/components/ResultsTimeline.tsx` — grouping by creation month.
- `frontend/src/components/FilterPanel.tsx`, `DateFactView.tsx` — facets and date display.

## Diagrams
[flow](../diagrams/flow-0010-filters-facets-dates-timeline.svg) · [sequence](../diagrams/sequence-0010-filters-facets-dates-timeline.svg)

## Roadmap
Nothing open.
