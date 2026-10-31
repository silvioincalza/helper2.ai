# 0040 — Frontend design: home page, results with preview, guide picks bar, design system

**Status.** Accepted. **Builds on:** [0025](0025-frontend-one-box.md),
[0015](../guide/0015-ai-guide-evidence-gate.md), [0038](../guide/0038-ai-content-marking.md), [0039](0039-frontend-recent-answers-cache.md)

## Context
The box, the URL state and the guide stream are in [0025](0025-frontend-one-box.md). This record decides the pages
around them. Three things matter for a user:
- The guide answer arrives after about 15 seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- Checking a result must not cost a page load and a Back. Comparing three results must stay on one page.
- On a phone, the home feeds and the results must not compete for the same narrow column.

We drew the design as clickable prototypes (Home, Results, Resource, States, Saved, Sources, Mission, Privacy)
and chose between options for each part.

## Decision
1. **Same palette and fonts, one design system.** Paper, indigo ink, one saffron accent. Space Grotesk, Inter,
   JetBrains Mono. `docs/design/system/tokens.json` holds the base tokens, the semantic tokens (type tags, status
   pills, proof quote, sources tile, selected row) and the breakpoints 560, 640, 860 and 1000 px.
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
6. **Guide picks bar, nothing moves.** The guide runs only for a question: four words or more, or Shift+Enter. A name
   search ("pgvector") gets no picks bar and no model call ([0017](../guide/0017-guide-cost-guard.md)). For a question, a box
   above the list is there from the first render: "Guide is reading N
   candidates… picks will appear here". Its height is reserved, and so are the lines that come with the answer ("web searches left" and "How the guide decided"); the chips stay on one line, cut a long title with "…", and scroll sideways when they do not fit. When the answer arrives over SSE
   (`POST /api/v1/guide/stream`, [0015](../guide/0015-ai-guide-evidence-gate.md)), the same box shows the picks as chips
   (#1, #2, #3), labelled AI-written ([0038](../guide/0038-ai-content-marking.md)). The list never re-orders: each pick gets a
   "Pick #n" badge and its reason in its own row. No pick: the box shrinks to one line, "No pick: no result had a
   sentence that proves it", and the guide's full answer opens under it, so its notice and how it read the question
   stay in reach. Tapping a chip selects that row; a pick that is not on the page links to its own page.
7. **Sources at the bottom.** The fan-out summary and one tile per source (status, response time or cache age) sit
   under the results, not in the header.
8. **States are designed, not left to chance.** Loading (skeleton rows), guide answering, no results, some sources
   failed, all sources failed, 404. Each has a next step. One component draws them all (`StatePanel`): a heading
   that says what happened, why under it, the next step last. An error is an alert. Try again keeps focus on a
   heading that stays on the page, never on the page body.
9. **Footer says only "No ads."** The footer makes no claim about tracking, so it stays true whatever the cookie
   policy becomes.
10. **One design, released by tag.** The code holds one design and no design flag. Prod deploys only a release tag
    ([0028](../operations/0028-ci-cd.md)), so `main` and staging run a change before a release takes it to users.

## Consequences
- The user reads results at once and is never moved. The guide adds to the list, it does not reorder it.
- Checking a result is one click, not a page load and a Back.
- A shared link opens the same search and the same result.
- Search ranking and the guide's opinion stay visibly separate: the list is ranking, the badges are the guide.
- A screen reader user gets the boards as a normal list.

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
- **A design flag per environment** (on in one image, off in another) — a build constant needs one image per
  environment, and staging would not test the image prod gets. Release-only prod deploys make it unneeded.

## Code
- `frontend/src/styles/tokens.css` — the semantic tokens of `tokens.json`, each an alias of its base token, with
  `board-height` and `board-loop`.
- `frontend/src/components/ui/` — `TypeTag`, `StatusPill`, `ProofQuote`, `SourceTiles`, `SelectableRow` (the
  selected-row outline), `PicksBar` (reserved height, waiting line, no-pick line, AI-written picks slot),
  `StatePanel` and `RetryButton` (every error, empty and 404 state), `CopyButton` and the type tabs; `ui.css`.
- `frontend/src/hooks/useRetry.ts` — Try again moves focus to a heading, so it never falls to the page body.
  `frontend/src/lib/motion.ts` — one `prefersReducedMotion` for the boards and the radar.
- `frontend/src/hooks/useGuideStream.ts` — the guide stream for the question and refinement in the URL.
- `frontend/src/pages/Home.tsx` — the home page: radar hero, the one box, the examples, the two boards.
- `frontend/src/components/home/` — `HomeBoard` (the loop, the copy hidden from assistive tech and from Tab, pause
  on hover, focus and tap, still under reduced motion), `NewsBoard` (`GET /recent`) and `EventsBoard`
  (`GET /events/upcoming`); `home.css`. `frontend/src/lib/countdown.ts` — Today, Tomorrow, In N days, weeks or
  months.
- `frontend/src/lib/searchState.ts` — `RESULTS_PATH` is `/search`; `resourcePath(id)` builds every link to a
  resource page with the id encoded. `OneBox` sends results there and searches live only
  on that page.
- `frontend/src/pages/HomePage.tsx`, `routes.tsx` — `/` empty is the home page; `/search` shows results and goes home
  with no query; `/?q=` shows results with a canonical link to `/search`; there is no `/guide` route (the site was never public, so no old links exist).
- `frontend/src/components/results/Results.tsx` — the results page: the box, the picks bar (questions only), type
  tabs, a "Filters" fold with sort, list or timeline and the filter panel, search notices and use-case hints, the list
  of `SelectableRow`s, the preview beside the list from 861 px and inside it below, the pager, and the sources at the
  bottom. Every state says what happened and offers a next step; after a page change, focus goes to the result count.
  `results.css`. `frontend/src/lib/selection.ts` — the selected result (`id` in the URL, else the first) and the
  picks by resource id. `frontend/src/hooks/useMediaQuery.ts` — beside the list or inside it.
- `frontend/src/components/results/Resource.tsx`, `ResultFacts.tsx` — the resource page and the facts, "Listed by"
  (with mode and stale-cache marks) shared with the preview; `frontend/src/pages/ResourcePage.tsx` loads it once.
- `frontend/src/pages/NotFoundPage.tsx` — the 404 page.
- `frontend/src/components/saved/Saved.tsx` (behind `SAVED_ENABLED`), `components/sources/Sources.tsx`,
  `pages/AccountPage.tsx` — Saved, Sources and your account.
- `frontend/src/components/TextPage.tsx`, `pages/MissionPage.tsx`, `PrivacyPage.tsx`, `TermsPage.tsx` — the text
  pages with "On this page".
- `frontend/src/pages/AdminPage.tsx`, `pages/admin.css` — the admin back office ([0023](../edge/0023-identity-and-admin.md))
  in the design: the review queue as rows like the saved rows, each status as a `StatusPill`, the Scout runs as a
  table.
- `frontend/src/components/Layout.tsx` — Sources and Mission in the menu, "No ads." in the footer.

## Diagrams
[flow](../../diagrams/flow-0040-frontend-design.svg) · [sequence](../../diagrams/sequence-0040-frontend-design.svg)

## Roadmap
[Frontend](../../roadmap.md#7-frontend): pause for touch users on the boards to review against WCAG 2.2.2; consent banner
and Privacy update before any tracking cookie; a release to take the design to prod.
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
