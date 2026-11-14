# 0039 — Frontend: one box, state in the URL

**Depends on:** [0001](../foundation/0001-stack-and-runtime.md), [0003](../foundation/0003-contract-first-rest.md), [0005](../foundation/0005-redis-as-a-disposable-cache.md), [0022](../search/0022-federated-search.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0031](../guide/0031-guide-pipeline.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0040](0040-design-system.md).

The pages, their layout and the design system are in [0040](0040-design-system.md).

## Context

Users come with a need, not with a choice between "search" and "ask an artificial intelligence (AI)". Two boxes or a
mode switch make them choose first. A guide answer takes several seconds (docs/evaluation.md: p50 10870 ms), so the
stream must stay alive while the server works. Search answers must not flash or steal focus while filters change.
The site must work with a keyboard and a screen reader, and in light and dark mode.

Back and forward are the most common moves on the site: from the results to a resource and back, or between two
resources. Each move remounts the page. Without a cache the page asks the server again, the user sees skeletons for
an answer the browser had a second ago, and the page jumps to the top. Search runs every source again; a resource
guide may call the model again. Both cost time and quota for nothing.

## Decision

1. **One box** (`OneBox`, `HomeOrResultsPage`). The box is the hero on `/` and sits at the top of the results on
   `/search`. On the results page, typing searches live: from 3 characters, 700 ms after the last key. While a question
   is asked (`ask` in the URL), typing does not search live: the guide's answer keeps running, and Enter searches the
   new words as a new page in the history, so Back returns to the answer. Enter keeps the question only when the box
   still holds its words. Two buttons,
   for any length of text: **Search** (primary, also Enter) and **Ask the guide** (outline, also Shift+Enter). So a
   question that costs model calls is always a choice. Each button shows its key, ↵ and ⇧↵, where there is a keyboard
   and room. Ask the guide shows its icon and its words; up to 720 px wide it says "Ask" (its accessible name stays
   "Ask the guide"), and up to 520 px the box takes the full row with the two buttons on the row under it. The phone keyboard's Enter key says
   Search (`enterKeyHint`). A live search never overwrites a key typed after it was sent. Neither is ever
   disabled: with nothing to send it puts the cursor in the box; Search then also says "Type what you need first." and
   searches nothing. Enter in an empty box goes back to the empty home page.
   The box is a one-row text area that grows with the text, up to six lines, then scrolls; the height animates
   (160 ms, none under reduced motion). Enter never adds a line, and a pasted line break becomes a space.
   × (or Esc) empties the box and keeps the results; "/" from anywhere on the page puts the cursor in the box (not
   while typing in another field), with a "/" hint on keyboard devices; from 50 characters left the box says how many
   remain, inside the box ("20 left"; the full sentence for screen readers). No scrollbar while it grows; past six
   lines a thin bar in the theme, on hover or focus only.
2. **All state is in the page address (URL).** `q` (at most 200 characters), `ask` (at most 500), the refinement of
   the question (`clar`, the reply to the guide's question back, at most 300; `drop`, the parts of the reading the
   user removed, at most 8), filters, sort, page, view. A search change keeps the refinement; a new question starts
   without one. The list pages keep their filters there too: `?site=` on News, `?kind=` and `?format=` on Events. A
   link, a bookmark or Back shows the same page.
3. **Search results** (`Results`, `useApi`). A new query aborts the old request. While it loads, the previous results
   stay on screen, dimmed, with `aria-busy`. Only the first load shows skeletons and one "querying" line per enabled
   source. A change of filter, sort or page keeps the scroll position. A failed search shows its error above the
   previous results, still dimmed, so an open filter drawer stays. The page reads only the filters the contract
   accepts from the URL (its values, lengths and counts); when the server still refuses them (400), the error offers
   "Remove all filters" instead of a retry that would fail again.
4. **The guide stream as the browser sees it** (`useGuideStream`, `api.askGuideStream`). `POST /api/v1/guide/stream`
   with the cross-site request forgery (CSRF) header. The hook reads the question and its refinement from the URL and
   sends one request per distinct question, refinement and attempt, with the audience path and the category filter
   as they are when the question is asked; a filter changed later does not ask again. After "Ask again" the hook
   hands out the answer before (`previous`) until the new run sends its first event, so the picks bar shows the old
   picks dimmed and `aria-busy` instead of the reading line. The step events are the contract's
   `GuideStreamEvent` values other than `answer` and `error`.
   Server-Sent Events (SSE) are read with `fetch` and `SseParser`, not `EventSource`, so there is no automatic
   reconnect (each question costs model calls). An idle timer of 45 s (`GUIDE_IDLE_TIMEOUT_MS`) is re-armed on every
   chunk. The server sends a `:keep-alive` comment every 15 s while a question runs ([0031](../guide/0031-guide-pipeline.md)),
   so any bytes, a comment included, keep the stream alive. `SseParser` ignores comments.
   Failures: `IDLE_TIMEOUT`, `MALFORMED`, `ENDED_EARLY`, `DROPPED` (the connection broke while reading), or the
   server's `error` event (its problem detail is shown).
   The user retries with a button, which asks the same question with the same refinement; a reload does too.
   Closing the panel, asking again or refining aborts the running stream.
   While the guide works, the status line in the picks bar (`GuidePicks`, `PicksBar`, `features/guide/reading.ts`)
   adds how the question was read (`understood`: intent, subjects, period), then how many results the guide reads
   (`retrieved`). It is a `role="status"` line, so a screen reader hears each change once. The parts are plain labels
   until the answer: ✕ to remove one comes with the answer, so a second question never runs next to the first.
   Search and the guide each show the web searches left today (`WebSearchesLeft`,
   [0027](../search/0027-live-web-store-first-daily-quota.md)), from the first one used, because the limit is small; at 0
   the line says that stored results still answer and when the limit resets. Nothing is shown when the count is
   unknown.
5. **The recent-answers cache** (`useApi`). One shared cache: the last 50 successful answers (`CACHE_MAX`), by
   request key, for 5 minutes (`CACHE_TTL_MS`). A page that mounts with a key in the cache shows the answer at once
   and sends no request. Least recently used goes first when the cache is full.
   - Only successful answers. Errors are never kept, so the next visit asks again.
   - `reload` always asks the server. It skips the cache and stores the fresh answer.
   - A shown answer stays shown. A cache hit becomes the hook's own answer; when the shared entry expires or is
     evicted later, the page does not go back to loading.
   - Data that changes under the user opts out: `useApi(key, fetcher, { cache: false })`. Every back office page
     does: review items and scout runs change while an admin works.
   - Memory only. The cache lives in the page's JavaScript memory. Nothing goes to `localStorage` or to disk; a full
     page load starts empty.
   - The scroll position follows. `ScrollRestoration` puts the page back where it was on back and forward, and at the
     top for a new page. `RouteFocus` focuses without scrolling, so it does not undo that.
   - Finished guide answers are kept in the same cache, by their request (`useGuideStream`), so Back to an answered
     question shows it with no new model call. Only answers that a new ask would repeat are kept: `OK`,
     `NO_CANDIDATES`, `NEEDS_CLARIFICATION`, and `LLM_UNAVAILABLE` when the day's budget is used up
     (`GUIDE_DAILY_QUOTA`). A `PARTIAL` answer, a provider failure or an answer refused by the guide's own model budget
     (`GUIDE_BUDGET_SPENT`, which can open again the same day) is never kept. The hit is decided once per
     request, when the run starts. A retry always asks the server.
   Only the pages that show the sources ask for `GET /api/v1/sources` (Home for its radar and count, Sources); the
   cache serves the second one.
6. **Accessibility.** Skip link; `RouteFocus` moves focus to the new page's `h1` (or `#main`, without scrolling) and
   names the page in a live region; paging moves focus to the results heading. The focus ring is a 3px indigo outline
   2px off the control (`--focus`): at least 10.4:1 on every light surface and 8.8:1 (light indigo) on every dark one,
   above the 3:1 of WCAG 1.4.11. The home lists hold on hover, focus and a tap, have no Pause button and never move
   under reduced motion ([0040](0040-design-system.md), principle 7). Vitest runs axe-core smoke tests
   (`a11y.test.tsx`, colour contrast left out because jsdom cannot compute it); the contrast of the tokens has its own
   tests.
7. **Theme: light and dark, light by default.** One button switches light ↔ dark; the choice is saved as `h2.theme`.
   `public/theme.js` sets `data-theme` before the first paint: dark only when it was chosen, else light. The device
   setting is not followed: the site looks the same for everyone until a visitor picks dark.
8. **Bundle.** `routes.tsx` imports the pages most visits start on (Home and the results at `/` and `/search`, the
   resource page) into the entry chunk, so a deep link to them paints at once. Every other page (Saved, News, Events,
   Sources, Account, Sign in, the text pages, Support, Not found) and the results view load on their first visit; a
   chunk of an older release that is gone reloads the tab once (`lib/staleChunk.ts`). The copy of the Privacy and
   Terms pages, about half of the public translations, is its own file (`i18n/locales/en.legal.json`, `i18n/legal.ts`)
   that only those two pages import, so it comes with their chunk, not the first download. `npm run check:bundle`
   (`scripts/check-bundle.mjs`) runs in CI after the build and fails it when the first visit's JavaScript (the entry
   and the modules it preloads) passes 250 KiB gzipped, or any other chunk passes 30 KiB. It runs in CI only, so a few
   bytes never block an image build or a deploy. The back office is its own entry and bundle
   ([0038](../backoffice/0038-back-office-and-review.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)); a test keeps its
   calls and its copy out of the public bundle (`api/adminBundle.test.ts`).
9. **One folder per feature.** `src/features/<feature>` holds a feature's pages, parts, styles and tests, named in the
   words of the backend's contexts (search, guide, home, resource, saved, sources, news, events, setup, account,
   pages, monetisation); `src/shell` the frame of every page, `src/ui` the shared kit, `src/admin` the back office;
   `api`, `i18n`, `hooks`, `lib`, `context`, `styles` and `test` stay shared.
10. **Dates and times as the visitor's browser shows them.** Every date and time on the public pages and in the back
    office goes through `lib/format.ts`, and every formatter there asks `dateLocale()` for its locale:
    - the words come from the app language (month and weekday names, "2 days ago"), so an English page never gets an
      Italian month;
    - the conventions come from the browser's region (`navigator.language`): 24 h or 12 h clock, day before month or
      month before day. App `en` and browser `it-CH` give `en-CH`: "11 Nov 2026, 14:30". Browser `en-US` gives
      "Nov 11, 2026, 2:30 PM". A browser language with no region, or one that cannot be read, leaves the app
      language alone;
    - an instant (`INSTANT` precision, a clock time, "saved on", "retrieved on", today's date on News) is shown in the
      browser's time zone, with no zone name;
    - a calendar day (`DAY` precision, an event's day, a month of the timeline, the day of a news item) is not an
      instant: it is formatted in UTC, so the same day shows in Los Angeles and in Tokyo;
    - "tomorrow" in a notice is the visitor's next local day (`backAgain`).
    Numbers (`Intl.NumberFormat`: counts, limits, response times) stay in the app language alone, because the text
    around them is in that language. Nothing is sent or stored: the browser's language and time zone are only read to
    format what is on the page. The pages Spring writes for crawlers ([0041](0041-site-pages-for-crawlers-policy-endpoint.md)) have no
    browser to ask and keep their own dates. This rule has no runtime path of its own, so it has no diagram.

### Code

- `frontend/src/features/search/OneBox.tsx`, `HomeOrResultsPage.tsx`, `Results.tsx` — the box, live search, the
  results view on demand, previous results while loading.
- `frontend/src/hooks/useGuideStream.ts`, `frontend/src/lib/searchState.ts` — the guide stream for the URL's question
  and refinement.
- `frontend/src/features/guide/model/guideRun.ts` — the state of one guide run as a pure reducer: its steps, answer,
  error, retries and the answer kept on screen while the question is asked again.
- `frontend/src/features/guide/` — `GuidePicks` (picks bar and progress line), `reading.ts`, `GuideAnswer` and its
  parts, `WebSearchesLeft`.
- `frontend/src/hooks/useApi.ts` — `CACHE_MAX`, `CACHE_TTL_MS`, the cache, `cache: false`, `clearApiCache` for tests.
- `frontend/src/api/client.ts`, `frontend/src/lib/sse.ts` — the guide stream, the idle timeout, the error codes.
- `frontend/src/shell/Layout.tsx`, `frontend/src/lib/theme.ts`, `frontend/public/theme.js` — `RouteFocus`,
  `ScrollRestoration`, the theme.
- `frontend/src/features/news/NewsPage.tsx`, `frontend/src/features/events/EventsPage.tsx` — filters in the URL.
- `frontend/src/admin/` — the cache opt-out (every back office page).
- `frontend/src/routes.tsx`, `frontend/src/lib/staleChunk.ts`, `frontend/scripts/check-bundle.mjs`,
  `frontend/src/api/adminBundle.test.ts` — the pages on demand, the bundle budget, the admin code out of the public
  bundle.
- `frontend/src/a11y.test.tsx` — axe-core smoke tests.
- `frontend/src/lib/format.ts`, `format.test.ts` — `dateLocale` and every date and time formatter; the tests fix the
  browser language and the time zone.

## Alternatives considered

- **Separate search and guide pages.** The user must choose a mode before typing.
- **`EventSource` for the stream.** It only supports GET and reconnects by itself, which would ask the question again.
- **Following the device setting ("system").** The first visit then depends on the device, and a third state on the
  button is one more thing to read; the owner chose light for everyone by default.
- **Every page in the entry chunk.** No request on a page's first visit, but the first download grows with every page
  most visitors never open.
- **A data library (TanStack Query, SWR).** Cache, stale-while-revalidate and retries out of the box, but a new
  dependency and a second way to fetch next to `useApi`. Too much for one map and a time limit.
- **Browser HTTP cache (`Cache-Control` on API answers).** No code in the page, but the API answers depend on cookies
  and the CSRF token, and a shared cache must never keep one user's answer for another.
- **Keep pages mounted** (hidden instead of unmounted). No request, but every visited page stays in memory and keeps
  its timers running.
- **Stale-while-revalidate** (show the cached answer, ask again in the background). Fresher, but every back still
  costs a search or a model call.
- **Dates in the app language's own conventions** (`en` everywhere: "2:30 PM", month first). One output for every
  visitor and simpler tests, but a visitor in Zurich reads a 12 h clock and UTC times they must convert.
- **Dates in the browser's full locale** (`it-CH` on an English page). The visitor's conventions, but Italian month
  names inside English sentences.
- **Folders by technical kind** (`components/`, `pages/`). Familiar, but a feature's files are spread over several
  folders, and names came in confusing pairs (`FilterPanel` and `FiltersPanel`, `Home` and `HomePage`).

## Consequences

- One place to type; the guide and search share the same URL and can be bookmarked or shared.
- Back and forward show the page at once, at the place the user left it, with no request. Fewer search fan-outs and
  guide calls for the same question within a few minutes. 50 entries and 5 minutes hold a visit of a few searches and
  resources many times over; the server's own caches ([0005](../foundation/0005-redis-as-a-disposable-cache.md),
  [0032](../guide/0032-guide-cost-guard-and-answer-cache.md)) keep answers at least as long.
- A page that shows data the user changes passes `cache: false` and always shows fresh data.
- Two visitors read the same instant as two clock times, each right for where they are. A screenshot of a time says
  nothing about the zone: the `<time datetime>` attribute and the API keep the UTC instant.
- A slow model step does not look like a dead connection, thanks to the keep-alive comments. 45 s without any byte
  means the stream is stuck, and the user retries with one button.
- Enter always searches, whatever the length of the text. A keyboard user asks the guide with Shift+Enter or Tab to
  the button.
- Growth of the first visit's JavaScript fails CI, with room for a few more pages.
- A change to one feature stays in one folder; the folder names are the backend's.

### Known limits

- Dates follow the region of the browser's language, not the device's own clock setting: a visitor who set a 24 h clock on a device in `en-US` still reads "2:30 PM". The web-search line still names "00:00 UTC" next to the local time. A news item's day and a result's creation day are read as UTC calendar days, because their rows carry no precision.
- A user who never presses Ask the guide (or Shift+Enter) never sees the guide's picks: Enter only searches.
- No browser end-to-end tests: frontend tests run in jsdom only, also in CI.
- A recent answer can be up to 5 minutes old (50 entries, 5 minutes, fixed). A new page that shows data the user changes must pass `cache: false`.

## Diagrams

The one box: [flow](../../diagrams/flow-0039-frontend-one-box.svg) · [sequence](../../diagrams/sequence-0039-frontend-one-box.svg) ·
the recent-answers cache: [flow](../../diagrams/flow-0039-frontend-recent-answers-cache.svg) · [sequence](../../diagrams/sequence-0039-frontend-recent-answers-cache.svg)

## Backlog

- Features: [F7.1](https://github.com/silvioincalza/helper2.ai/issues/87) One box, state in the URL, recent-answers cache.
- Open issues that change this decision:
  - [#324](https://github.com/silvioincalza/helper2.ai/issues/324) S7.6.2 One manual accessibility pass on real browsers
  - [#357](https://github.com/silvioincalza/helper2.ai/issues/357) S10.12.1 The main journeys automated in a browser
