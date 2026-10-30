# 0039 — Frontend recent-answers cache: back and forward without a new request

**Status.** Accepted (2026-10-30). **Builds on:** [0025](0025-frontend-one-box.md)

## Context
Back and forward are the most common moves on the site: from the results to a resource and back, or between two
resources. Each move remounted the page, and `useApi` asked the server again. The user saw skeletons for an answer
the browser had a second ago, and the page jumped to the top. Search runs every source again; a resource guide may
call the model again. Both cost time and quota for nothing.

## Decision
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

## Consequences
- Good: back and forward show the page at once, at the place the user left it, with no request.
- Good: fewer search fan-outs and guide calls for the same question within a few minutes.
- Bad: an answer can be up to 5 minutes old. The server's own caches ([0005](0005-redis-disposable-cache.md),
  [0017](0017-guide-cost-guard.md)) already allow that much and more.
- Trade-off: 50 entries and 5 minutes are a fixed choice. A visit of a few searches and resources fits many times over.
- A new page that shows data the user changes must pass `cache: false`, or it shows its old state after back.

## Alternatives considered
- **A data library (TanStack Query, SWR)** — cache, stale-while-revalidate and retries out of the box, but a new
  dependency and a second way to fetch next to `useApi`. Too much for one map and a time limit.
- **Browser HTTP cache (`Cache-Control` on API answers)** — no code in the page, but the API answers depend on
  cookies and the CSRF token, and a shared cache must never keep one user's answer for another.
- **Keep pages mounted** (hidden instead of unmounted) — no request, but every visited page stays in memory and
  keeps its timers running.
- **Stale-while-revalidate** (show the cached answer, ask again in the background) — fresher, but every back still
  costs a search or a model call.

## Code
- `frontend/src/hooks/useApi.ts` — `CACHE_MAX`, `CACHE_TTL_MS`, the cache, `cache: false`, `clearApiCache` for tests.
- `frontend/src/pages/AdminPage.tsx` — the opt-out.
- `frontend/src/components/Layout.tsx` — `ScrollRestoration`, `RouteFocus`.

## Diagrams
[flow](../diagrams/flow-0039-frontend-recent-answers-cache.svg) · [sequence](../diagrams/sequence-0039-frontend-recent-answers-cache.svg)

## Roadmap
Nothing open.
