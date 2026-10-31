# 0025 — Frontend: one box, state in the URL, guide stream, accessibility, theme (system/light/dark)

**Status.** Accepted. The pages, their layout and the design system are in [0040](0040-frontend-design.md). **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0003](../foundation/0003-contract-first-rest-api.md), [0008](../search/0008-federated-search.md), [0015](../guide/0015-ai-guide-evidence-gate.md)

## Context
Users come with a need, not with a choice between "search" and "ask an artificial intelligence (AI)". Two boxes or a mode switch make them
choose first. A guide answer takes several seconds (docs/evaluation.md: p50 10870 ms), so the stream must stay
alive while the server works. Search answers must not flash or steal focus while filters change. The site must work with a keyboard and a
screen reader, and in light and dark mode.

## Decision
1. **One box** (`OneBox`, `HomePage`). The box is the hero on `/` and sits at the top of the results on `/search`.
   On the results page, typing searches live: from 3 characters, 700 ms after the last key. One button follows the text: fewer than 4 words
   search, 4 or more ask the guide; Shift+Enter always asks; Enter in an empty box goes back to the empty home page.
   The box is a one-row text area that grows with the text, up to six lines, then scrolls; the height animates
   (160 ms, none under reduced motion). Enter never adds a line, and a pasted line break becomes a space.
   All state is in the page address (URL): `q` (at most 200 characters), `ask` (at most 500), the refinement of the
   question (`clar`, the reply to the guide's question back, at most 300; `drop`, the parts of the reading the user
   removed, at most 8), filters, sort, page, view. A search change keeps the refinement; a new question starts without one.
2. **Search results** (`Results`, `useApi`). A new query aborts the old request. While it loads, the previous
   results stay on screen, dimmed, with `aria-busy`. Only the first load shows skeletons and one "querying" line per
   enabled source. A change of filter, sort or page keeps the scroll position.
   Back and forward show a recent answer at once, where the user left it: [0039](0039-frontend-recent-answers-cache.md).
3. **Guide stream as the browser sees it** (`useGuideStream`, `api.askGuideStream`). `POST /api/v1/guide/stream` with the cross-site request forgery (CSRF) header.
   The hook reads the question and its refinement from the URL and sends one request per distinct question, refinement
   and attempt; the step events are the contract's `GuideStreamEvent` values other than `answer` and `error`.
   Server-Sent Events (SSE) are read with `fetch` and `SseParser`, not `EventSource`, so there is no automatic reconnect
   (each question costs model calls). An idle timer of 45 s (`GUIDE_IDLE_TIMEOUT_MS`) is re-armed on every chunk.
   The server sends a `:keep-alive` comment every 15 s while a question runs ([0015](../guide/0015-ai-guide-evidence-gate.md)),
   so any bytes, a comment included, keep the stream alive. `SseParser` ignores comments.
   Failures: `IDLE_TIMEOUT`, `MALFORMED`, `ENDED_EARLY`, or the server's `error` event (its problem detail is shown).
   The user retries with a button, which asks the same question with the same refinement; a reload does too.
   Closing the panel, asking again or refining aborts the running stream. A language change does not ask again.
   While the guide works, the status line in the picks bar (`GuidePicks`, `PicksBar`, `components/guide/reading.ts`)
   adds how the question was read (`understood`: intent, subjects, period), then how many results the guide reads
   (`retrieved`). It is a `role="status"` line, so a screen reader hears each change once. The parts are plain labels
   until the answer: ✕ to remove one comes with the answer, so a second question never runs next to the first.
   Search and the guide each show the web searches left today (`WebSearchesLeft`, [0037](../search/0037-tavily-store-and-daily-quota.md)),
   from the first one used, because the limit is small; at 0 the line says that stored results still answer and when
   the limit resets. Nothing is shown when the count is unknown.
4. **Radar design system.** The Helper2.ai design system in `docs/design/system`, named after its radar mark:
   warm paper, indigo ink, one saffron accent; `tokens.css` mirrors `tokens.json` ([0040](0040-frontend-design.md)).
   Unknown values read "Unknown".
5. **Accessibility.** Skip link; `RouteFocus` moves focus to the new page's `h1` (or `#main`, without scrolling) and
   names the page in a live region; paging moves focus to the results heading. The focus ring is a 3px indigo outline 2px off the control
   (`--focus`): at least 10.4:1 on every light surface and 8.8:1 (light indigo) on every dark one, above the 3:1 of
   WCAG 1.4.11. The home boards pause on hover, focus and tap and never move with reduced motion
   ([0040](0040-frontend-design.md)). Vitest runs axe-core smoke tests (`a11y.test.tsx`,
   color contrast excluded because jsdom cannot compute it).
6. **Theme: system, light, dark.** One button cycles system → light → dark. A fixed choice is saved as `h2.theme`;
   "system" removes it. `public/theme.js` sets `data-theme` before the first paint and follows the device while no choice is saved.
7. **Shared data.** `SourcesContext` fetches `GET /api/v1/sources` once per page load for all pages. `PrivacyPage` names
   the contact `helper2ai@incalza.me`.
8. **Bundle.** `routes.tsx` imports every page eagerly: the first visit loads one entry chunk, with no blank first
   paint on a deep link and no extra request per page. Lazy pages are larger without a bundler-specific chunk group
   (see Alternatives). `npm run check:bundle` (`scripts/check-bundle.mjs`) runs in CI
   after the build and fails it when the first visit's JavaScript (the entry and the modules it preloads) passes
   160 KiB gzipped, or any other chunk passes 30 KiB. It runs in CI only, so a few bytes
   never block an image build or a deploy.

## Consequences
- One place to type; the guide and search share the same URL and can be bookmarked or shared.
- Growth of the first visit's JavaScript fails CI, with room for a few more pages.
- A slow model step does not look like a dead connection, thanks to the keep-alive comments. 45 s without any
  byte means the stream is stuck, and the user retries with one button.
- Shift+Enter always asks the guide, whatever the length of the text.

## Alternatives considered
- Separate search and guide pages — the user must choose a mode before typing.
- `EventSource` for the stream — it only supports GET and reconnects by itself, which would ask the question again.
- Only light and dark — users who follow the device setting would lose that.
- Lazy pages (react-router `lazy`) — about 1.9 kB gzipped less on the home page, but only with a rolldown-specific
  chunk group in `vite.config.ts`; without it the first visit is larger than with eager pages. It also needs a
  hydrate fallback (a blank page until the chunk arrives) and one more request on a page's first visit.

## Code
- `frontend/src/components/OneBox.tsx`, `frontend/src/pages/HomePage.tsx` — box, live search, guide section.
- `frontend/src/hooks/useGuideStream.ts`, `frontend/src/lib/searchState.ts` — the guide stream for the URL's question and refinement.
- `frontend/src/components/guide/` — `GuidePicks` (picks bar and progress line), `reading.ts`, `GuideAnswer` and its parts, `WebSearchesLeft`.
- `frontend/src/components/results/Results.tsx`, `frontend/src/hooks/useApi.ts` — previous results while loading.
- `frontend/src/api/client.ts`, `frontend/src/lib/sse.ts` — guide stream, idle timeout, error codes.
- `frontend/src/components/Layout.tsx`, `frontend/src/lib/theme.ts`, `frontend/public/theme.js` — `RouteFocus`, theme.
- `frontend/src/context/SourcesContext.tsx`, `frontend/src/pages/PrivacyPage.tsx`.
- `frontend/src/routes.tsx`, `frontend/scripts/check-bundle.mjs` — eager pages, bundle budget.
- `docs/design/system/` — design tokens and components.

## Diagrams
[flow](../../diagrams/flow-0025-frontend-one-box.svg) · [sequence](../../diagrams/sequence-0025-frontend-one-box.svg)

## Roadmap
- [Browser end-to-end tests (skipped for now)](../../roadmap.md#quality-gates).
- [Manual accessibility pass on real browsers](../../roadmap.md#quality-gates).
- [The shared `/sources` data can be old during a long visit](../../roadmap.md#7-frontend).
- [A fixed theme can be overridden by a device change when storage is blocked](../../roadmap.md#7-frontend).
- [Search engine optimization (SEO): detail pages are not crawlable](../../roadmap.md#product-and-legal).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
