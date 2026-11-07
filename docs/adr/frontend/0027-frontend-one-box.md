# 0027 — Frontend: one box, state in the URL, recent-answers cache

**Status.** Accepted. Merges old 0025, 0039 (texts merged in PR 12 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0025"></a>

## Part: was ADR 0025 — Frontend: one box, state in the URL, guide stream, accessibility, theme (system/light/dark)

**Status.** Accepted. The pages, their layout and the design system are in [0028](0028-design-system.md). **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0003](../foundation/0003-contract-first-rest-api.md), [0009](../search/0009-federated-search.md), [0016](../guide/0016-guide-pipeline.md)

### Context
Users come with a need, not with a choice between "search" and "ask an artificial intelligence (AI)". Two boxes or a mode switch make them
choose first. A guide answer takes several seconds (docs/evaluation.md: p50 10870 ms), so the stream must stay
alive while the server works. Search answers must not flash or steal focus while filters change. The site must work with a keyboard and a
screen reader, and in light and dark mode.

### Decision
1. **One box** (`OneBox`, `HomePage`). The box is the hero on `/` and sits at the top of the results on `/search`.
   On the results page, typing searches live: from 3 characters, 700 ms after the last key. Two buttons, for any length of
   text: **Search** (primary, also Enter) and **Ask the guide** (outline, also Shift+Enter). So a question that costs
   model calls is always a choice. Each button shows its key, ↵ and ⇧↵, where there is a keyboard and room. On a
   narrow box Ask the guide is a 44 px icon button with its name and a tooltip. Neither is ever disabled: with nothing
   to send it puts the cursor in the box; Search then also says "Type what you need first." and searches nothing. Enter in an empty box goes back to the empty home page.
   The box is a one-row text area that grows with the text, up to six lines, then scrolls; the height animates
   (160 ms, none under reduced motion). Enter never adds a line, and a pasted line break becomes a space.
   × (or Esc) empties the box and keeps the results; "/" from anywhere on the page puts the cursor in the box (not
   while typing in another field), with a "/" hint on keyboard devices; from 50 characters left the box says how
   many remain, inside the box ("20 left"; the full sentence for screen readers). No scrollbar while it grows; past six lines a thin bar in the theme, on hover or focus only.
   All state is in the page address (URL): `q` (at most 200 characters), `ask` (at most 500), the refinement of the
   question (`clar`, the reply to the guide's question back, at most 300; `drop`, the parts of the reading the user
   removed, at most 8), filters, sort, page, view. A search change keeps the refinement; a new question starts without one.
2. **Search results** (`Results`, `useApi`). A new query aborts the old request. While it loads, the previous
   results stay on screen, dimmed, with `aria-busy`. Only the first load shows skeletons and one "querying" line per
   enabled source. A change of filter, sort or page keeps the scroll position.
   Back and forward show a recent answer at once, where the user left it: [the part that was ADR 0039](#part-0039).
3. **Guide stream as the browser sees it** (`useGuideStream`, `api.askGuideStream`). `POST /api/v1/guide/stream` with the cross-site request forgery (CSRF) header.
   The hook reads the question and its refinement from the URL and sends one request per distinct question, refinement
   and attempt; the step events are the contract's `GuideStreamEvent` values other than `answer` and `error`.
   Server-Sent Events (SSE) are read with `fetch` and `SseParser`, not `EventSource`, so there is no automatic reconnect
   (each question costs model calls). An idle timer of 45 s (`GUIDE_IDLE_TIMEOUT_MS`) is re-armed on every chunk.
   The server sends a `:keep-alive` comment every 15 s while a question runs ([0016](../guide/0016-guide-pipeline.md)),
   so any bytes, a comment included, keep the stream alive. `SseParser` ignores comments.
   Failures: `IDLE_TIMEOUT`, `MALFORMED`, `ENDED_EARLY`, or the server's `error` event (its problem detail is shown).
   The user retries with a button, which asks the same question with the same refinement; a reload does too.
   Closing the panel, asking again or refining aborts the running stream. A language change does not ask again.
   While the guide works, the status line in the picks bar (`GuidePicks`, `PicksBar`, `components/guide/reading.ts`)
   adds how the question was read (`understood`: intent, subjects, period), then how many results the guide reads
   (`retrieved`). It is a `role="status"` line, so a screen reader hears each change once. The parts are plain labels
   until the answer: ✕ to remove one comes with the answer, so a second question never runs next to the first.
   Search and the guide each show the web searches left today (`WebSearchesLeft`, [0014](../search/0014-tavily-store-and-daily-quota.md)),
   from the first one used, because the limit is small; at 0 the line says that stored results still answer and when
   the limit resets. Nothing is shown when the count is unknown.
4. **Radar design system.** The Helper2.ai design system in `docs/design/system`, named after its radar mark:
   warm paper, indigo ink, one saffron accent; `tokens.css` mirrors `tokens.json` ([0028](0028-design-system.md)).
   Unknown values read "Unknown".
5. **Accessibility.** Skip link; `RouteFocus` moves focus to the new page's `h1` (or `#main`, without scrolling) and
   names the page in a live region; paging moves focus to the results heading. The focus ring is a 3px indigo outline 2px off the control
   (`--focus`): at least 10.4:1 on every light surface and 8.8:1 (light indigo) on every dark one, above the 3:1 of
   WCAG 1.4.11. The home boards pause on hover, focus and tap and never move with reduced motion
   ([0028](0028-design-system.md)). Vitest runs axe-core smoke tests (`a11y.test.tsx`,
   color contrast excluded because jsdom cannot compute it).
6. **Theme: light and dark, light by default.** One button switches light ↔ dark; the choice is saved as `h2.theme`.
   `public/theme.js` sets `data-theme` before the first paint: dark only when it was chosen, else light. The device
   setting is not followed: the site looks the same for everyone until a visitor picks dark.
7. **Shared data.** Only the pages that show the sources ask for `GET /api/v1/sources` (Home for its count, Sources);
   the recent-answers cache ([the part that was ADR 0039](#part-0039)) serves the second one. `PrivacyPage` names
   the contact `helper2ai@incalza.me`.
8. **Bundle.** `routes.tsx` imports every page eagerly: the first visit loads one entry chunk, with no blank first
   paint on a deep link and no extra request per page. Lazy pages are larger without a bundler-specific chunk group
   (see Alternatives). `npm run check:bundle` (`scripts/check-bundle.mjs`) runs in CI
   after the build and fails it when the first visit's JavaScript (the entry and the modules it preloads) passes
   250 KiB gzipped, or any other chunk passes 30 KiB (165 KiB until 5 Nov 2026, when the first visit was at 164.5:
   each new feature tripped it). It runs in CI only, so a few bytes never block an image build or a deploy.

### Consequences
- One place to type; the guide and search share the same URL and can be bookmarked or shared.
- Growth of the first visit's JavaScript fails CI, with room for a few more pages.
- A slow model step does not look like a dead connection, thanks to the keep-alive comments. 45 s without any
  byte means the stream is stuck, and the user retries with one button.
- Enter always searches, whatever the length of the text. A keyboard user asks the guide with Shift+Enter or Tab to
  the button.

### Alternatives considered
- Separate search and guide pages — the user must choose a mode before typing.
- `EventSource` for the stream — it only supports GET and reconnects by itself, which would ask the question again.
- Following the device setting ("system") — the first visit then depends on the device, and a third state on the
  button is one more thing to read; the owner chose light for everyone by default.
- Lazy pages (react-router `lazy`) — about 1.9 kB gzipped less on the home page, but only with a rolldown-specific
  chunk group in `vite.config.ts`; without it the first visit is larger than with eager pages. It also needs a
  hydrate fallback (a blank page until the chunk arrives) and one more request on a page's first visit.

### Code
- `frontend/src/components/OneBox.tsx`, `frontend/src/pages/HomePage.tsx` — box, live search, guide section.
- `frontend/src/hooks/useGuideStream.ts`, `frontend/src/lib/searchState.ts` — the guide stream for the URL's question and refinement.
- `frontend/src/components/guide/` — `GuidePicks` (picks bar and progress line), `reading.ts`, `GuideAnswer` and its parts, `WebSearchesLeft`.
- `frontend/src/components/results/Results.tsx`, `frontend/src/hooks/useApi.ts` — previous results while loading.
- `frontend/src/api/client.ts`, `frontend/src/lib/sse.ts` — guide stream, idle timeout, error codes.
- `frontend/src/components/Layout.tsx`, `frontend/src/lib/theme.ts`, `frontend/public/theme.js` — `RouteFocus`, theme.
- `frontend/src/pages/Home.tsx`, `frontend/src/components/sources/Sources.tsx`, `frontend/src/pages/PrivacyPage.tsx`.
- `frontend/src/routes.tsx`, `frontend/scripts/check-bundle.mjs` — eager pages, bundle budget.
- `docs/design/system/` — design tokens and components.

### Diagrams
[flow](../../diagrams/flow-0027-frontend-one-box.svg) · [sequence](../../diagrams/sequence-0027-frontend-one-box.svg)

### Roadmap
- [Browser end-to-end tests (skipped for now)](../../roadmap.md#quality-gates).
- [Manual accessibility pass on real browsers](../../roadmap.md#quality-gates).
- [Search engine optimization (SEO): detail pages are not crawlable](../../roadmap.md#product-and-legal).
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0039"></a>

## Part: was ADR 0039 — Frontend recent-answers cache: back and forward without a new request

**Status.** Accepted. **Builds on:** [the part that was ADR 0025](#part-0025)

### Context
Back and forward are the most common moves on the site: from the results to a resource and back, or between two
resources. Each move remounts the page. Without a cache, `useApi` asks the server again, the user sees skeletons
for an answer the browser had a second ago, and the page jumps to the top. Search runs every source again; a resource
guide may call the model again. Both cost time and quota for nothing.

### Decision
1. **One shared cache in `useApi`.** The last 50 successful answers (`CACHE_MAX`), by request key, for 5 minutes
   (`CACHE_TTL_MS`). A page that mounts with a key in the cache shows the answer at once and sends no request.
   Least recently used goes first when the cache is full.
2. **Only successful answers.** Errors are never kept, so the next visit asks again.
3. **`reload` always asks the server.** It skips the cache and stores the fresh answer.
4. **A shown answer stays shown.** A cache hit becomes the hook's own answer. When the shared entry expires or is
   evicted later, the page does not go back to loading.
5. **Opt out for data that changes under the user.** `useApi(key, fetcher, { cache: false })`. The admin page uses it:
   review items and scout runs change while an admin works.
6. **Memory only.** The cache lives in the page's JavaScript memory. Nothing goes to `localStorage` or to disk; a full
   page load starts empty.
7. **Scroll position follows.** `ScrollRestoration` puts the page back where it was on back and forward, and at the
   top for a new page. `RouteFocus` focuses without scrolling, so it does not undo that.

### Consequences
- Back and forward show the page at once, at the place the user left it, with no request.
- Fewer search fan-outs and guide calls for the same question within a few minutes.
- 50 entries and 5 minutes hold a visit of a few searches and resources many times over. The server's own caches
  ([0005](../foundation/0005-redis-disposable-cache.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md)) keep answers at least as long.
- A page that shows data the user changes passes `cache: false` and always shows fresh data.

### Alternatives considered
- **A data library (TanStack Query, SWR)** — cache, stale-while-revalidate and retries out of the box, but a new
  dependency and a second way to fetch next to `useApi`. Too much for one map and a time limit.
- **Browser HTTP cache (`Cache-Control` on API answers)** — no code in the page, but the API answers depend on
  cookies and the CSRF token, and a shared cache must never keep one user's answer for another.
- **Keep pages mounted** (hidden instead of unmounted) — no request, but every visited page stays in memory and
  keeps its timers running.
- **Stale-while-revalidate** (show the cached answer, ask again in the background) — fresher, but every back still
  costs a search or a model call.

### Code
- `frontend/src/hooks/useApi.ts` — `CACHE_MAX`, `CACHE_TTL_MS`, the cache, `cache: false`, `clearApiCache` for tests.
- `frontend/src/pages/admin/` — the opt-out (every admin page).
- `frontend/src/components/Layout.tsx` — `ScrollRestoration`, `RouteFocus`.

### Diagrams
[flow](../../diagrams/flow-0027-frontend-recent-answers-cache.svg) · [sequence](../../diagrams/sequence-0027-frontend-recent-answers-cache.svg)

### Roadmap
Nothing open.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
