# 0040 — Frontend design v2: home page, results with preview, guide picks bar, design system v3

**Status.** Accepted (2026-10-30). First decision for this design: it has never been deployed. Until it ships, the
code still follows [0025](0025-frontend-one-box.md). **Builds on:** [0025](0025-frontend-one-box.md),
[0015](0015-ai-guide-evidence-gate.md), [0038](0038-ai-content-marking.md), [0039](0039-frontend-recent-answers-cache.md)

## Context
The first frontend ([0025](0025-frontend-one-box.md)) puts the box, the guide answer and the results on one long page.
It works, but three things are hard for a user:
- The guide answer arrives after about 15 seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- To check a result, the user must open the resource page and come back. Comparing three results means six page loads.
- On a phone, the home feeds and the results compete for the same narrow column.

We drew the new design as clickable prototypes (Home, Results, Resource, States, Saved, Sources, Mission, Privacy)
and chose between options for each part. The design was never deployed, so this record is a first decision,
not a migration of something users already know.

## Decision
1. **Same palette and fonts, design system v3.** Paper, indigo ink, one saffron accent. Space Grotesk, Inter,
   JetBrains Mono. `docs/design/system/tokens.json` v3 adds semantic tokens only (type tags, status pills, proof
   quote, sources tile, selected row) and the breakpoints 560, 640, 860 and 1000 px. No colour value changes.
2. **Home is only the home page.** Radar hero, the one box and example searches. Below: **News** (last 7 days) and
   **Events** (next 3 months), two boards that scroll slowly on their own. Each row: date, type tag, source or place,
   status pill (Updated, Published, First seen, In 13 days…), name. A board pauses on hover, on keyboard focus and
   on tap (tap again to play). Under `prefers-reduced-motion` it does not move and scrolls by hand.
3. **Results on `/search`.** The box and the examples open `/search?q=…`. `/?q=…` shows the same page and declares
   `/search` as the canonical URL, so search engines see one page.
4. **List and preview.** From 861 px: the result list on the left, a preview of the selected result on the right
   (proof quote with the checked sentence highlighted, Use and Build facts, Listed by, Timeline, actions). Up to
   860 px: the preview opens inside the list, under the selected row, with a link to `/resource/:id`.
5. **The selected result is in the URL.** `?q=…&id=…`, changed with `history.replaceState`: a shared link opens the
   same result, and Back leaves the search instead of stepping through rows. An `id` that is not in the results
   selects the first row. The `id` is untrusted input: it only selects among results already on the page.
6. **Guide picks bar, nothing moves.** A box above the list is there from the first render: "Guide is reading N
   candidates… picks will appear here". Its height is reserved. When the answer arrives over SSE
   (`POST /api/v1/guide/stream`, [0015](0015-ai-guide-evidence-gate.md)), the same box shows the picks as chips
   (#1, #2, #3), labelled AI-written ([0038](0038-ai-content-marking.md)). The list never re-orders: each pick gets a
   "Pick #n" badge and its reason in its own row. No pick: the box shrinks to one line, "No pick: no result had a
   sentence that proves it". Tapping a chip selects that row.
7. **Sources at the bottom.** The fan-out summary and one tile per source (status, response time or cache age) sit
   under the results, not in the header.
8. **States are designed, not left to chance.** Loading (skeleton rows), guide answering, no results, some sources
   failed, all sources failed, 404. Each has a next step.
9. **Footer says only "No ads."** Tracking cookies are planned later. The footer makes no claim about tracking, so it
   does not need to change when they come. Mission and Privacy keep their text until then.
10. **Built behind `NEW_DESIGN_ENABLED`** in `frontend/src/lib/features.ts`, page by page: shared components and
    tokens, Home, Results, Resource, States, Saved and Sources, Mission and Privacy. Nobody uses the old design yet,
    so the flag does not protect users. It keeps `main` releasable while the pages are half done. When the last page
    is done, the flag and the old components go in the same pull request.

## Consequences
- Good: the user reads results at once and is never moved. The guide adds to the list, it does not reorder it.
- Good: checking a result is one click, not a page load and a Back.
- Good: a shared link opens the same search and the same result.
- Good: search ranking and the guide's opinion stay visibly separate: the list is ranking, the badges are the guide.
- Bad: the picks bar takes about 110 px above the list, also when the guide has nothing to say (then one line).
- Bad: two layouts for results (desktop preview, inline preview on a phone) to build and test.
- Bad: the boards move. Hover, focus and tap pause them. A screen reader user gets a normal list.
- Trade-off: while the flag lives, two designs sit in the code. Small pull requests per page keep that short.

## Alternatives considered
- **Look: minimal search page, editorial news front page, or developer console** — each drawn on its own. Minimal
  was fast but plain, editorial needs fresh content every day, the console was too dense for beginners. Home and
  Results take parts of all three.
- **Results: answer first** (the guide's answer as a block above the list) — good when the guide is sure, but the
  page waits 15 seconds for its most important part.
- **Results: grouped by type** (MCP servers, libraries, models, papers) — good for broad searches, empty groups for
  narrow ones.
- **Guide answer: move the picks to the top** — the best answer comes first, but the list jumps while the user reads.
- **Guide answer: badges only** — nothing moves, but the best answer can sit at row 5, under the fold.
- **Results only on `/`** — keeps one route, but home and results share a URL and a page component.
- **Big-bang branch** without a flag — one review instead of seven, but a long-lived branch and one large merge.

## Code
Built (step 1, shared parts; no page uses them while the flag is off):
- `frontend/src/lib/features.ts` — `NEW_DESIGN_ENABLED`, off.
- `frontend/src/styles/tokens.css` — the semantic tokens of `tokens.json` v3, each an alias of its base token.
- `frontend/src/components/ui/` — `TypeTag`, `StatusPill`, `ProofQuote`, `SourceTiles`, `SelectableRow` (the
  selected-row outline) and `PicksBar` (reserved height, waiting line, no-pick line, AI-written picks slot), with
  `ui.css` imported by the components, so a page without them loads none of it.
- `frontend/src/hooks/useGuideStream.ts` — the guide stream for the question and refinement in the URL, shared by
  both designs.

Built (step 2, Home; only while the flag is on):
- `frontend/src/pages/HomeV2.tsx` — the home page: radar hero, the one box, the examples, the two boards.
- `frontend/src/components/home/` — `HomeBoard` (the loop, the copy hidden from assistive tech and from Tab, pause
  on hover, focus and tap, still under reduced motion), `NewsBoard` (`GET /recent`) and `EventsBoard`
  (`GET /events/upcoming`), with `home-v2.css`.
- `frontend/src/lib/countdown.ts` — Today, Tomorrow, In N days, weeks or months.
- `frontend/src/lib/searchState.ts` — `RESULTS_PATH`: `/search` with the flag on, `/` with it off. `OneBox` sends
  results there and searches live only on that page.
- `frontend/src/pages/HomePage.tsx`, `routes.tsx` — `/` empty is the home page; `/search` shows results (the current
  results page until step 3) and goes home with no query; `/?q=` still shows results with a canonical link to
  `/search`.
- `frontend/src/components/Layout.tsx` — Sources and Mission in the menu, "No ads." in the footer.
- `tokens.css` — `board-height` and `board-loop`.

Built (step 3, Results; only while the flag is on):
- `frontend/src/components/results/ResultsV2.tsx` — the results page: the box, the guide picks bar (only when the
  guide is asked, as before), type tabs, the list of `SelectableRow`s, the preview beside the list from 861 px and
  inside it below, the sources at the bottom; `results-v2.css`. The old `SearchResults` and `ResultCardView` stay
  for the flag-off page.
- `frontend/src/lib/selection.ts` — the selected result (`id` in the URL, else the first) and the guide picks by
  resource id: badges never change the order of the list.
- `frontend/src/hooks/useMediaQuery.ts` — beside the list or inside it.
- Filters and the timeline view keep working: the filters fold under a "Filters" line above the list for now.

Built (step 4, Resource; only while the flag is on):
- `frontend/src/components/results/ResourceV2.tsx` — the resource page: back link, title, actions, "Guide me", the
  source's own text, the detail sections of the old page, and a side column with the facts, who lists it and its
  dates; one column up to 860 px; `resource-v2.css`. Opened from a result, the page links back to that search with
  that result selected, and shows the sentence the guide checked when the result was a pick. The back link only
  takes a path inside the site.
- `frontend/src/components/results/ResultFacts.tsx` — the Use and Build facts and "Listed by", shared by the preview
  and the resource page.
- `frontend/src/pages/ResourcePage.tsx` — loads the resource once; its detail sections (summary, recorded facts,
  relationships, source records, report) are the same in both designs.
- `frontend/src/components/SetupGuide.tsx` — the setup steps written by AI are now also marked machine-readable
  (`data-ai-generated`, [0038](0038-ai-content-marking.md)), in both designs.

Built (step 5, States; only while the flag is on):
- `frontend/src/components/results/ResultsV2.tsx` — every state says what happened and offers a next step:
  loading shows skeleton rows shaped like the list; while the guide answers, the list is already there and the picks
  bar reads; no results offers "Ask the guide instead" and the sources page; some sources failed keeps the results,
  links to the failed tiles and retries; no source answered is an alert with retry and the sources page; search
  unavailable is an alert with retry. Status lines use `role=status`, failures `role=alert`.
- `frontend/src/pages/NotFoundPage.tsx` — the 404 page points to the search box in the header and the home page.

Built (step 6, Saved, Sources and your account; only while the flag is on):
- `frontend/src/components/saved/SavedV2.tsx` — the saved page, still behind `SAVED_ENABLED`: type tabs when there
  is more than one type, one row per item (type, host, saved date, title, the original link, Remove). A saved item
  keeps no quote, so the row shows none. `saved-v2.css` also holds the page head shared with the sources page.
- `frontend/src/components/sources/SourcesV2.tsx` — the sources page: how a search works, the source modes in use,
  a summary of the last checks, mode chips, and one card per source with its last check in words, what it covers,
  its terms and attribution. The sources list has no response time, so the cards show none. `sources-v2.css`.
- `frontend/src/pages/AccountPage.tsx` — the same behaviour and words in the text-page look (`text-v2.css`).
- `frontend/src/components/ui/ui.css` — the type tabs, now shared by results and saved.

Planned:
- `frontend/src/components/SourceStatusPanel.tsx`, `Layout.tsx` — header and footer on the other pages.
- `tokens.css` — the sizes (`content-wide`, `list-max`) and motion (`radar-sweep`) of `tokens.json` v3, with the
  pages that use them.

## Diagrams
[flow](../diagrams/flow-0040-frontend-design-v2.svg) · [sequence](../diagrams/sequence-0040-frontend-design-v2.svg)

## Roadmap
[Frontend](../roadmap.md#7-frontend): build steps behind `NEW_DESIGN_ENABLED`; pause for touch users on the boards to
review against WCAG 2.2.2; consent banner and Privacy update before any tracking cookie.
