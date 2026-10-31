# 0040 — Frontend design: home page, results with preview, resource page, guide picks bar, designed states and the pages around them

**Status.** Accepted (2026-10-30). **Builds on:** [0025](0025-frontend-one-box.md),
[0015](0015-ai-guide-evidence-gate.md), [0031](0031-setup-guide.md), [0038](0038-ai-content-marking.md),
[0039](0039-frontend-recent-answers-cache.md)

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
1. **Palette and fonts: the design system.** Paper, indigo ink, one saffron accent. Space Grotesk, Inter,
   JetBrains Mono. `docs/design/system/tokens.json` names semantic tokens on top of the base palette (type tags,
   status pills, proof quote, sources tile, selected row) and the breakpoints 560, 640, 860 and 1000 px.
2. **Home is only the home page.** Radar hero, the one box and example searches. Below: **News** (last 7 days) and
   **Events** (next 3 months), two boards that scroll slowly on their own. Each row: date, type tag, source or place,
   status pill (Updated, Published, First seen, in 13 days, On now), name. The name links to the resource page.
   A board moves up one row every 4 seconds and back to the top at the end. It pauses on hover, on keyboard focus
   and on tap (tap again to play), and a visible Pause/Play button does the same for everyone (WCAG 2.2.2). Under `prefers-reduced-motion` it does not move and
   scrolls by hand. Each row is in the list once and the list is not a live region, so a screen reader reads a normal
   list. Loading shows skeleton rows; empty and error say so, and an error offers to try again.
3. **Results on `/search`.** The box and the examples open `/search?q=…`. `/?q=…` shows the same page and declares
   `/search` as the canonical URL, so search engines see one page. `/search` with nothing to search is the home page.
   On the home page typing waits for Enter; on the results page it searches live ([0025](0025-frontend-one-box.md)).
4. **List and preview.** From 861 px: the result list on the left, a preview of the selected result on the right
   (proof quote with the checked sentence highlighted, Use and Build facts, Listed by, Timeline, actions). Up to
   860 px: the preview opens inside the list, under the selected row: why it matches, three facts, and links to the
   source and to `/resource/:id`. Both come from the search answer, with no request of their own. A row is a button
   named by its title; one row is in the tab order, and the arrow keys, Home and End move between rows and select.
   Filters fold away above the list, so the list and the preview share the row.
   **The resource page `/resource/:id` is the preview as a page**: one component tree draws both, with the title as
   the page's h1. The page adds what the type means, the tags, the AI summary with its citations, what each source
   says (value, origin, verification, evidence), relationships (verified apart from unverified candidates), the source
   records, Guide me ([0031](0031-setup-guide.md)) and the report form. AI-written text is labelled and marked
   ([0038](0038-ai-content-marking.md)). Followed from the results ("Full details"), "Back to results" steps back like
   the browser's Back, to the same search, selection and scroll position.
5. **The selected result is in the URL.** `?q=…&id=…`, replaced, not pushed (`history.replaceState`): a shared link
   opens the same result, and Back leaves the search instead of stepping through rows. An `id` that is not in the results
   selects the first row. The `id` is untrusted input: it only selects among results already on the page.
6. **Guide picks bar, nothing moves.** A box above the list is there from the first render: "Guide is reading N
   candidates… picks will appear here". Its height is reserved. When the answer arrives over SSE
   (`POST /api/v1/guide/stream`, [0015](0015-ai-guide-evidence-gate.md)), the same box shows the picks as chips
   (#1, #2, #3), labelled AI-written ([0038](0038-ai-content-marking.md)). The list never re-orders: each pick gets a
   "Pick #n" badge and its reason in its own row. No pick: the box shrinks to one line, "No pick: no result had a
   sentence that proves it", or the reason when it is another (no model, the daily limit, a comparison or an
   explanation instead of picks). Tapping a chip selects that row and moves focus to it; a pick the guide found
   outside the results on the page is a link to its resource page. The preview of a pick shows the checked quote in
   the source's text, the user's requirements checked against it, and the model's explanation, labelled.
   The bar also holds the question, a close button, the guide's question back and how it read the question
   ([0032](0032-guide-intents.md)). The rest of the answer (search terms, evidence check, models, a comparison, an
   explanation, a prompt) is folded under the bar, opened by the user, so nothing moves by itself.
7. **Sources at the bottom.** The fan-out summary ("fan-out · N sources · N hits → N after merge") and one tile per
   source (status, response time or cache age) sit under the results, not in the header. Under them one quiet line
   says how many web searches are left today for search and the guide, or that the daily limit is reached and stored
   web results still answer ([0037](0037-tavily-store-and-daily-quota.md)). It offers nothing to buy.
8. **States are designed, not left to chance.** Loading (skeleton rows and the sources being asked), guide
   answering (the waiting line), no results (remove the filters, fewer words, ask the guide), some sources failed
   (a banner with the way to the sources and Try again), all sources failed (an alert with Try again), search or the
   guide unavailable (Try again), a board that cannot load (Try again), a resource not in the index (the way home), a
   resource that cannot be loaded (Try again, the way home), Guide me's steps that cannot load (Try again), and 404
   (the way home and the box in the header). Each has a next step. One component draws the error, empty and
   not-found states (the picks bar keeps its error in the bar): a heading that says what happened, the reason, the
   next step; an error is an alert. Try again keeps the reader's place: focus goes to the heading that stays while
   the answer loads, then to the heading of what loaded, or of the state when it comes back.
9. **The footer makes one claim: "No ads."** Next to it, in one row, the links: Mission, Sources, Privacy, Terms,
   the code on GitHub, its licence and the release notes. Tracking cookies are planned later. The footer makes no claim about
   tracking, so it does not need to change when they come. Mission and Privacy keep their text until then.
10. **Saved, Sources and the account page.** Saved (`/saved`, in the header) lists the saved resources as rows like
    the result rows: type tag, site, the date saved, the title linking to the resource page, Open on source and
    Remove. A guest's list is in this browser, a signed-in user's on the account
    ([0023](0023-identity-and-admin.md)). Remove moves focus to the Remove button of the row that takes its place, or
    to the empty state's heading, and a status line says what went. Sources (`/sources`, in the footer) shows each
    configured source's last outcome as the tiles under the results (the outcome and how long ago, not asked yet, or
    turned off; [0008](0008-federated-search.md)), what the modes mean, and a table of what each source covers, its
    terms and attribution. `GET /api/v1/sources` gives no response time, circuit state or web searches left, so the
    page shows none; the results page shows them for a search. The account page, opened from the signed-in name,
    says who is signed in and deletes the account after a confirmation; it offers no sign-in. Loading, empty and
    error are designed states (8).
11. **Text pages and admin.** Mission, Privacy and Terms are one reading column: the title, the date of the last
    change in mono (Privacy and Terms), a lede, then one part per section with a `display` heading and a rule on top.
    Their text lives in the locale file. Mission says where the AI-written labels sit on screen
    ([0038](0038-ai-content-marking.md)). Admin (`/admin`, [0023](0023-identity-and-admin.md)) shows the review queue
    as rows like the saved rows, each status as a StatusPill, and the Scout runs in the table of the Sources page.
    Saved, Sources, the account page, admin and the text pages share one page layout: the `h1`, a lede, then parts
    with a rule on top. These pages have no runtime path of their own beyond [0023](0023-identity-and-admin.md).

## Consequences
- Good: the user reads results at once and is never moved. The guide adds to the list, it does not reorder it.
- Good: checking a result is one click, not a page load and a Back.
- Good: a shared link opens the same search and the same result.
- Good: the preview and the resource page cannot drift apart: one component tree draws what they share.
- Good: every state names a next step, and Try again never drops focus to the page body.
- Good: search ranking and the guide's opinion stay visibly separate: the list is ranking, the badges are the guide.
- Bad: the picks bar takes about 175 px above the list (head, picks, how the guide read the question) while the guide
  works and when it picks; with no pick it is shorter.
- Bad: two layouts for results (desktop preview, inline preview on a phone) to build and test.
- Bad: the guide searches with its own words, so a pick can be missing from the list; its chip links to the resource
  page instead of selecting a row.
- Bad: a comparison or an explanation sits folded under the bar; the bar's line says it is there, and one click
  opens it.
- Bad: the boards move. Hover, focus, tap and the Pause button stop them. A screen reader user gets a normal list.

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
- **Boards as a seamless loop** (the rows repeated, the copy hidden from assistive tech) — smooth, but twice the
  rows in the page and a copy to keep out of the tab order; one row at a time reads as well.

## Code
Built:
- `frontend/src/styles/tokens.css` — the tokens of `tokens.json`; the semantic ones are each an alias of its base token,
  `board-height`, `content-wide` and `list-max`.
- `frontend/src/components/ui/` — `TypeTag`, `StatusPill`, `ProofQuote`, `SourceTiles`, `SelectableRow` (the row
  button: selected outline, one tab stop, named by its title), `PicksBar` (reserved height, head with the question and
  close, waiting line, no-pick line, the AI-written label over the picks and the question back, footer) and
  `NewsBoard` (heading, states, the moving list and its Pause/Play button), `StatePanel` (a state's heading, reason
  and next step; `RetryButton`), `Heading` (a heading at the level that fits where it sits) and `CopyButton` (a snippet
  or the guide's prompt), with `ui.css` imported by the components.
- `frontend/src/hooks/useRetry.ts` — Try again that keeps focus on the page.
- `frontend/src/pages/HomePage.tsx`, `components/HomeBoards.tsx` — the radar hero, the box, the examples, and the News
  (`GET /api/v1/recent`, 7 days) and Events (`GET /api/v1/events/upcoming`, [0022](0022-next-events.md), 90 days)
  boards.
- `frontend/src/routes.tsx` — `/search`; `/` is the home page, `/?q=` shows the results page.
- `frontend/src/pages/ResultsPage.tsx` — the canonical link, the search, the selection (`id`), the states.
- `frontend/src/components/ResultList.tsx` — the rows, the pick badges and reasons, the arrow keys, the timeline view.
- `frontend/src/components/ResourceOverview.tsx` — what the preview and the resource page share: meta, title,
  actions, Guide me, why it matches, facts, listed by, timeline; `DetailsLink` (Full details, with the way back).
- `frontend/src/components/ResultPreview.tsx` — `ResultPreview` and `InlinePreview`; `CardFacts.tsx` — Use and Build.
- `frontend/src/pages/ResourcePage.tsx` — the overview as a page, then the AI summary, what the sources say,
  relationships, source records, the report form; Back to results; its states.
- `frontend/src/components/setup/` — Guide me ([0031](0031-setup-guide.md)).
- `frontend/src/pages/NotFoundPage.tsx` — 404.
- `frontend/src/hooks/useNarrow.ts` — `bp-split`: preview panel or inline preview.
- `frontend/src/components/GuidePicks.tsx` — the guide's answer in `PicksBar`; `hooks/useGuideStream.ts` — the guide
  stream for the question and refinement in the URL.
- `frontend/src/components/SourcesSummary.tsx` — the fan-out line, the tiles, the web searches left today.
- `frontend/src/components/Layout.tsx` — the box in the header on every page but the home page, Saved in the header,
  the one-row footer.
- `frontend/src/pages/SavedPage.tsx` — the saved rows, Remove and where focus goes after it, the states;
  `context/BookmarksContext.tsx` — browser or account list, its loading and error.
- `frontend/src/pages/SourcesPage.tsx` — the last outcome as `SourceTiles`, the modes, what each source covers.
- `frontend/src/pages/AccountPage.tsx` — who is signed in, delete the account after a confirmation.
- `frontend/src/pages/MissionPage.tsx`, `PrivacyPage.tsx`, `TermsPage.tsx` — the text pages;
  `components/TextSections.tsx` (a locale file's sections as parts), `components/HowItWorks.tsx` (how the guide
  answers, the rules).
- `frontend/src/pages/AdminPage.tsx` — the review queue and the Scout runs.
- `frontend/src/styles/app.css` — `.page`, `.page-part`, `.page-table`: the layout the pages of parts share.

Planned:
- `tokens.css` — `radar-sweep` of `tokens.json`, with the page that uses it.

## Diagrams
[flow](../diagrams/flow-0040-frontend-design.svg) · [sequence](../diagrams/sequence-0040-frontend-design.svg)

## Roadmap
[Frontend](../roadmap.md#7-frontend): the boards' pause on touch and with a screen reader checked against WCAG 2.2.2
on real devices; consent banner and Privacy update before any tracking cookie.
