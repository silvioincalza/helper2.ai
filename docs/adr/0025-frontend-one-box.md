# 0025 — Frontend: one page, one box, Radar design system, guide progress, accessibility, theme (system/light/dark)

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0003](0003-contract-first-rest-api.md), [0008](0008-federated-search.md), [0015](0015-ai-guide-evidence-gate.md)

## Context
Users come with a need, not with a choice between "search" and "ask an artificial intelligence (AI)". Two boxes or a mode switch make them
choose first. A guide answer takes several seconds (docs/evaluation.md: p50 10870 ms), so the page must show real
progress. Search answers must not flash or steal focus while filters change. The site must work with a keyboard and a
screen reader, and in light and dark mode.

## Decision
1. **One page, one box** (`OneBox`, `HomePage`). The box is the hero on `/` and sits in the header on other pages.
   Typing searches live: from 3 characters, 700 ms after the last key. One button follows the text: fewer than 4 words
   search, 4 or more ask the guide; Shift+Enter always asks; Enter in an empty box goes back to the empty home page.
   All state is in the page address (URL): `q` (at most 200 characters), `ask` (at most 500), filters, sort, page, view.
2. **Search results** (`SearchResults`, `useApi`). A new query aborts the old request. While it loads, the previous
   results stay on screen, dimmed, with `aria-busy`. Only the first load shows skeletons and one "querying" line per
   enabled source. `useApi` keeps the last 20 settled responses of the page load in memory, by request key: a key seen
   less than 60 s ago renders at once with no request; an older one renders at once and is refreshed in the background
   (a failed refresh keeps it); Retry always asks the server. Nothing is written to browser storage.
   `<ScrollRestoration/>` puts a new page at the top and back/forward at the saved position; a change of filter, sort or
   page keeps the position. So back from a result shows the same results, at the same place, without a new federated search.
3. **Guide stream as the browser sees it** (`api.askGuideStream`). `POST /api/v1/guide/stream` with the cross-site request forgery (CSRF) header.
   Server-Sent Events (SSE) are read with `fetch` and `SseParser`, not `EventSource`, so there is no automatic reconnect
   (each question costs model calls). An idle timer of 45 s (`GUIDE_IDLE_TIMEOUT_MS`) is re-armed on every chunk.
   The server sends a `:keep-alive` comment every 15 s while a question runs ([0015](0015-ai-guide-evidence-gate.md)),
   so any bytes, a comment included, keep the stream alive. `SseParser` ignores comments.
   Failures: `IDLE_TIMEOUT`, `MALFORMED`, `ENDED_EARLY`, or the server's `error` event (its problem detail is shown).
   The user retries with a button; closing the panel or asking again aborts the stream.
4. **Guide progress** (`GuideProgress`): five steps from real events — `planned`, `retrieved`, `chosen`, `validated`,
   `checked`. Only the current step's title is in a `role="status"` region; the seconds counter is hidden from screen readers.
5. **Radar design system.** The Helper2.ai design system in `docs/design/system`, named after its radar mark:
   warm paper, indigo ink, one saffron accent; `tokens.css` mirrors `tokens.json`. Unknown values read "Unknown".
6. **Accessibility.** Skip link; `RouteFocus` moves focus to the new page's `h1` (or `#main`) and names the page in a
   live region; paging moves focus to the results heading. The focus ring is a 3px indigo outline 2px off the control
   (`--focus`): at least 10.4:1 on every light surface and 8.8:1 (light indigo) on every dark one, above the 3:1 of
   WCAG 1.4.11. The home feeds' `Carousel` moves by itself only with a visible Pause/Play button (WCAG 2.2.2, also on
   touch screens), holds still while the pointer or focus is inside, and never moves with reduced motion. Vitest runs axe-core smoke tests (`a11y.test.tsx`,
   color contrast excluded because jsdom cannot compute it).
7. **Theme: system, light, dark.** One button cycles system → light → dark. A fixed choice is saved as `h2.theme`;
   "system" removes it. `public/theme.js` sets `data-theme` before the first paint and follows the device while no choice is saved.
8. **Shared data.** `SourcesContext` fetches `GET /api/v1/sources` once per page load for all pages. `PrivacyPage` names
   the contact `helper2ai@incalza.me`.
9. **Bundle.** The home page ships in one entry chunk; every other page (resource, sources, mission, privacy, saved,
   admin, not found) is a react-router `lazy` route in its own chunk, loaded on its first visit. `vite.config.ts` keeps
   all shared modules in the entry, so the first visit loads one script, not a set of small preloaded fragments.
   `scripts/bundle-budget.mjs` runs after every `vite build` (local, CI, image) and fails it when the entry and the
   modules it preloads pass 153.5 kB gzipped (152.7 kB measured; an entry with every page is 154.5 kB).

## Consequences
- Good: one place to type; the guide and search share the same URL and can be bookmarked or shared.
- Good: progress shows what the server did, not a timer that guesses.
- Good: a page added later cannot grow the first visit, and growth of the home page itself fails the build.
- Good: a slow model step does not look like a dead connection, thanks to the keep-alive comments. 45 s without any
  byte means the stream is stuck, and the user must retry.
- Bad: there are no browser end-to-end tests. Tests run in jsdom only.
- Trade-off: the four-word rule sends a short question to search and a long keyword list to the guide;
  Shift+Enter always asks.

## Alternatives considered
- Separate search and guide pages — the user must choose a mode before typing.
- `EventSource` for the stream — it only supports GET and reconnects by itself, which would ask the question again.
- A spinner with a fixed estimate — not true to what the server is doing.
- Only light and dark — users who follow the device setting would lose that.

## Code
- `frontend/src/components/OneBox.tsx`, `frontend/src/pages/HomePage.tsx` — box, live search, guide section.
- `frontend/src/components/SearchResults.tsx`, `frontend/src/hooks/useApi.ts` — previous results while loading.
- `frontend/src/api/client.ts`, `frontend/src/lib/sse.ts` — guide stream, idle timeout, error codes.
- `frontend/src/components/GuideProgress.tsx` — five steps.
- `frontend/src/components/Layout.tsx`, `frontend/src/lib/theme.ts`, `frontend/public/theme.js` — `RouteFocus`, theme.
- `frontend/src/context/SourcesContext.tsx`, `frontend/src/pages/PrivacyPage.tsx`.
- `frontend/src/routes.tsx`, `frontend/vite.config.ts`, `frontend/scripts/bundle-budget.mjs` — lazy pages, entry chunk, budget.
- `docs/design/system/` — design tokens and components.

## Diagrams
[flow](../diagrams/flow-0025-frontend-one-box.svg) · [sequence](../diagrams/sequence-0025-frontend-one-box.svg)

## Roadmap
- [Browser end-to-end tests (skipped for now)](../roadmap.md#quality-gates).
- [Manual accessibility pass on real browsers](../roadmap.md#quality-gates).
- [The shared `/sources` data can be old during a long visit](../roadmap.md#7-frontend).
- [A fixed theme can be overridden by a device change when storage is blocked](../roadmap.md#7-frontend).
- [Search engine optimization (SEO): detail pages are not crawlable](../roadmap.md#product-and-legal).
