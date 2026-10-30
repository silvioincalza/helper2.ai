# 0040 — Frontend design v2: home page, results with preview, guide picks bar, design system v3

**Status.** Accepted (2026-10-30). **Builds on:** [0025](0025-frontend-one-box.md),
[0015](0015-ai-guide-evidence-gate.md), [0038](0038-ai-content-marking.md), [0039](0039-frontend-recent-answers-cache.md)

## Context
The one box ([0025](0025-frontend-one-box.md)) searches and asks the guide. Three things decide how the pages around it
work for a user:
- The guide answer arrives after about 15 seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- To check a result, the user should not have to open the resource page and come back. Comparing three results
  would mean six page loads.
- On a phone, what is new and the results cannot share one narrow column.

We drew the design as clickable prototypes (Home, Results, Resource, States, Saved, Sources, Mission, Privacy)
and chose between options for each part.

## Decision
1. **Same palette and fonts, design system v3.** Paper, indigo ink, one saffron accent. Space Grotesk, Inter,
   JetBrains Mono. `docs/design/system/tokens.json` v3 adds semantic tokens only (type tags, status pills, proof
   quote, sources tile, selected row) and the breakpoints 560, 640, 860 and 1000 px. No colour value changes.
2. **Home is only the home page.** Radar hero, the one box and example searches. Below: **News** (last 7 days) and
   **Events** (next 3 months), two boards that scroll slowly on their own. Each row: date, type tag, source or place,
   status pill (Updated, Published, First seen, In 13 days…), name. A board moves up one row every few seconds and
   back to the top at the end. It pauses on hover, on keyboard focus and on tap (tap again to play), and a visible
   Pause/Play button does the same for everyone (WCAG 2.2.2). Under `prefers-reduced-motion` it does not move and
   scrolls by hand. Each row is in the list once and the list is not a live region, so a screen reader reads a normal
   list. Loading shows skeleton rows; empty and error say so, and an error offers to try again.
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
9. **The footer makes one claim: "No ads."** Next to it, in one row, the links: Mission, Sources, Privacy, Terms,
   the code on GitHub and the release notes. Tracking cookies are planned later. The footer makes no claim about
   tracking, so it does not need to change when they come. Mission and Privacy keep their text until then.
10. **Built page by page**, one pull request each: shared components and tokens, Home, Results, Resource, States,
    Saved and Sources, Mission and Privacy. Each step builds its page in place, so the code holds one design.

## Consequences
- Good: the user reads results at once and is never moved. The guide adds to the list, it does not reorder it.
- Good: checking a result is one click, not a page load and a Back.
- Good: a shared link opens the same search and the same result.
- Good: search ranking and the guide's opinion stay visibly separate: the list is ranking, the badges are the guide.
- Bad: the picks bar takes about 110 px above the list, also when the guide has nothing to say (then one line).
- Bad: two layouts for results (desktop preview, inline preview on a phone) to build and test.
- Bad: the boards move. Hover, focus, tap and the Pause button stop them. A screen reader user gets a normal list.
- Trade-off: until the last step, some pages have the new look and some do not. Small pull requests per page keep
  that short.

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
- **Big-bang branch** — one review instead of seven, but a long-lived branch and one large merge.
- **A flag per page** — keeps two designs in the code while it lives; no user knows another design, so it protects
  no one.
- **Boards as a seamless loop** (the rows repeated, the copy hidden from assistive tech) — smooth, but twice the
  rows in the page and a copy to keep out of the tab order; one row at a time reads as well.

## Code
Built:
- `frontend/src/styles/tokens.css` — the semantic tokens of `tokens.json` v3, each an alias of its base token, and
  `board-height`.
- `frontend/src/components/ui/` — `TypeTag`, `StatusPill`, `ProofQuote`, `SourceTiles`, `SelectableRow` (the
  selected-row outline), `PicksBar` (reserved height, waiting line, no-pick line, AI-written picks slot) and
  `NewsBoard` (heading, states, the moving list and its Pause/Play button), with `ui.css` imported by the components.
- `frontend/src/pages/HomePage.tsx`, `components/HomeBoards.tsx` — the radar hero, the box, the examples, and the News
  (`GET /api/v1/recent`, 7 days) and Events (`GET /api/v1/events/upcoming`, [0022](0022-next-events.md), 90 days)
  boards.
- `frontend/src/components/Layout.tsx` — the one-row footer.
- `frontend/src/hooks/useGuideStream.ts` — the guide stream for the question and refinement in the URL.

Planned:
- `frontend/src/routes.tsx` — `/search`; `/` home only, `/?q=` shows results with a canonical link. Until then the box
  and the examples show the results on `/` under the hero.
- `frontend/src/components/SearchResults.tsx`, `ResultCardView.tsx` — list, preview, inline preview, `id` in the URL.
- `frontend/src/components/GuideAnswer.tsx` — the picks in `PicksBar` and the badges.
- `frontend/src/components/SourceStatusPanel.tsx`, `Layout.tsx` — sources at the bottom, header.
- `tokens.css` — `content-wide`, `list-max` and `radar-sweep` of `tokens.json` v3, with the pages that use them.

## Diagrams
[flow](../diagrams/flow-0040-frontend-design-v2.svg) · [sequence](../diagrams/sequence-0040-frontend-design-v2.svg)

## Roadmap
[Frontend](../roadmap.md#7-frontend): the boards' pause on touch and with a screen reader checked against WCAG 2.2.2
on real devices; consent banner and Privacy update before any tracking cookie.
