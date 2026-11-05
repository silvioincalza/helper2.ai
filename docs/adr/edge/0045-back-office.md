# 0045 — Back office: a thin admin module, one page per part, an overview of what needs a person

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-hexagonal.md), [0017](../guide/0017-guide-cost-guard.md), [0018](../scout/0018-scout-runner.md), [0023](0023-identity-and-admin.md), [0035](0035-admin-host.md), [0037](../search/0037-tavily-store-and-daily-quota.md)

## Context
The back office was one page: the review queue and the Scout runs. To see if something was wrong I had to scroll and
read, and the numbers that matter (open items per kind, the enrichment backlog, today's model budgets, web credits,
failed runs) were in Grafana or nowhere. The admin API lived in the Scout's web adapter, but it served the catalog and
the Scout alike. The budget gauges were per replica: a pod that made no call showed the full budget.
More admin parts are planned (enrichment queue, evidence view, bulk decisions, settings), so the structure must hold
them without each module growing an admin corner.

## Decision
1. **A thin `admin` module.** `ai.helper2.admin` holds the back office's web adapters (`AdminController`,
   `OverviewController`) and use cases that only compose other modules (`GetOverview`). It owns no table and no module
   depends on it. The catalog and Scout use cases it calls are named interfaces (`catalog.usecase`, `scout.usecase`).
2. **One page per part.** `/admin` (overview, the landing page), `/admin/review`, `/admin/runs`, `/admin/audit`, each with
   its state in the address, so a number on the overview links to the view that acts on it
   (`/admin/review?kind=USER_REPORT`).
3. **The overview: what needs a person now.** `GET /api/v1/admin/overview` returns counts only: open review items per
   kind (every kind, 0 included), the enrichment backlog (the same rule the Scout takes it by), every model budget with
   today's count and limit, web credits today, the last 10 Scout runs and the failed ones of the last 24 hours, the
   home examples shown and hidden ([0049](../search/0049-home-examples.md)), and a link to Grafana (`H2_ADMIN_GRAFANA_URL`) for trends. It is computed at most once a minute, one computation at a time:
   the backlog count is a full pass, and Refresh must not repeat it. That count gives up after 5 seconds. The Grafana
   link must be an absolute http(s) URL with a host, or the app does not start: the page puts it in an href.
4. **Budgets read from the shared counter.** Every `DailyBudget` declares its name and limit in the `BudgetLedger`, the
   `CallCounter` it counts on. The overview reads today's count from Redis, the store every replica counts in, never
   from one replica's memory. Without Redis, or while it cannot answer, the count is unknown and shown as unknown.
   A budget per visitor (the guide's `ClientQuota`) counts through `CallCounter.unlisted()` and is never listed: its
   name comes from the visitor's address, and there is one per visitor. The ledger lists at most 64 names.
5. **The zero-result rate is a metric.** `helper2.search.results{empty}` counts searches that found nothing, with no
   query text. It goes to Grafana, not to the overview.
6. **The enrichment queue: the backlog, page by page, in the Scout's order.** `GET /api/v1/admin/enrichment-queue` and
   `/admin/enrichment` (read-only) list the resources that wait, never-run ones first, then the ones to try again
   (model unavailable, or a FAILED run with retries left), each part newest discovery first. Each row says why it waits,
   how many runs FAILED, and the last run. The page is a keyset (part, first discovered, id) behind an opaque cursor, not
   an OFFSET: a deep page costs what the first one does. The overview's backlog count links here.
   The Scout's next batch, the queue and the count share one rule, read in two parts from one partial index
   (`resource_enrichment_backlog_idx`): the second part runs only when the first has too few rows. Measured on synthetic
   data, 250 000 resources: the next 20 from 1.4 s to under 1 ms, the count from 1.3 s to 0.65 s. Every backlog read
   gives up after 5 seconds. The queue is read-only and admin-only and shows catalog data, no personal data; a cursor
   that is not one of ours (wrong shape, a time outside what the database holds) is a 400.

## Consequences
- One place for every admin endpoint; a new part is a route, a controller method and a use case here.
- The overview shows only what to act on now. Trends stay in Grafana; the overview does not rebuild them.
- The numbers can be up to a minute old; the page says when they were counted.
- A budget that is never built (no model configured), or one that counts only in memory, is not listed.
- The queue has no total of its own: the overview's count is the total, up to a minute old. A resource that changes
  part between two pages (a run finishes) can be skipped or shown twice; the next visit shows it in its place.

## Alternatives considered
- Each module keeps its own admin controller — the overview would still need one place to compose them, and the
  admin API would be spread over every module's web adapter.
- A stored read model for the overview — a table and a job to keep it current, for numbers one person reads a few
  times a day. Not needed until a measurement says so.
- Per-replica budget gauges on the page — wrong by design: a replica that made no call shows the full budget.
- Rebuild the Grafana graphs in the back office — two places for the same trend, and more code to keep right.

## Code
- `backend/src/main/java/ai/helper2/admin/` — `package-info.java`, `web/AdminController.java`, `web/OverviewController.java`,
  `web/EnrichmentQueueController.java`, `usecase/GetOverview.java`, `usecase/ListEnrichmentQueue.java`.
- `backend/src/main/resources/db/migration/V10__resource_enrichment_backlog_idx.sql`; `catalog/enrichment/EnrichmentCandidates.java`
  and `catalog/internal/JdbcEnrichmentCandidates.java` (`pending`, `pendingCount`, `backlog`).
- `backend/src/main/java/ai/helper2/platform/llm/BudgetLedger.java`, `CallCounter.java`, `DailyBudget.java`,
  `RedisCallCounter.java` (`current`), `LlmConfiguration.java`.
- `backend/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`openCountsByKind`),
  `scout/internal/CatalogEnrichmentStore.java` and `catalog/internal/JdbcEnrichmentCandidates.java` (`pendingCount`), `search/config/MicrometerSearchMetrics.java`.
- `backend/src/main/java/ai/helper2/examples/usecase/CountHomeExamples.java`, `backend/src/main/kotlin/ai/helper2/examples/PoolCounts.kt`.
- `frontend/src/pages/admin/` — `AdminLayout.tsx`, `AdminOverview.tsx`, `ReviewQueuePage.tsx`, `ScoutRunsPage.tsx`,
  `EnrichmentQueuePage.tsx`, `AuditPage.tsx`.
- Tests: `api/AdminOverviewApiIT.java`, `admin/usecase/GetOverviewTest.java`, `platform/llm/BudgetLedgerTest.java`,
  `RedisCallCounterIT.java`, `scout/internal/CatalogEnrichmentStoreIT.java`, `search/config/MicrometerSearchMetricsTest.java`,
  `api/EnrichmentQueueApiIT.java`, `admin/web/EnrichmentQueueCursorTest.java`, `catalog/internal/JdbcEnrichmentCandidatesIT.java`,
  `frontend/src/pages/admin/Admin.test.tsx`, `EnrichmentQueuePage.test.tsx`.

## Diagrams
[flow](../../diagrams/flow-0045-back-office.svg) · [sequence](../../diagrams/sequence-0045-back-office.svg)

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
