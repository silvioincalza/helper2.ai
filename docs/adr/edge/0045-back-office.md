# 0045 — Back office: a thin admin module, one page per part, an overview of what needs a person

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-hexagonal.md), [0017](../guide/0017-guide-cost-guard.md), [0018](../scout/0018-scout-runner.md), [0023](0023-identity-and-admin.md), [0035](0035-admin-host.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0038](../guide/0038-ai-content-marking.md)

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
   `/admin/enrichment` list the resources that wait, never-run ones first, then the ones to try again
   (model unavailable, or a FAILED run with retries left), each part newest discovery first. Each row says why it waits,
   how many runs FAILED, and the last run. The page is a keyset (part, first discovered, id) behind an opaque cursor, not
   an OFFSET: a deep page costs what the first one does. The overview's backlog count links here.
   The Scout's next batch, the queue and the count share one rule, read in two parts from one partial index
   (`resource_enrichment_backlog_idx`): the second part runs only when the first has too few rows. The Scout's batch
   reads one more part first, the products a person accepted, from their few review items ([0056](../scout/0056-evidence-ladder.md)). Measured on synthetic
   data, 250 000 resources: the next 20 from 1.4 s to under 1 ms, the count from 1.3 s to 0.65 s. Every backlog read
   gives up after 5 seconds. The queue is admin-only and shows catalog data, no personal data; a cursor
   that is not one of ours (wrong shape, a time outside what the database holds) is a 400.
     place 0.1 ms.
7. **The evidence view reads the run through the item's link.** `GET /api/v1/admin/review-items/{id}/evidence`
   (ADMIN, admin port) answers what an item is about. A review item already links to its enrichment run and, for a
   relationship candidate, to its relationship (`enrichment_run_id`, `relationship_id`). The view follows that link;
   it never parses `details`, which stays text. An item from a run: the run (model, prompt version, outcome, when it
   finished, the README or model card of its gap round), the run's validation errors, and the fields the run stored,
   CONFLICTING first, then UNVERIFIED, VERIFIED and REJECTED. A relationship candidate: the run and the relationship.
   Any other item (a user report, a course or resource candidate, a drafted example): the item only. Each field and the
   relationship carry at most 5 quotes, the newest first; a run shows at most 50 fields and 50 errors. The catalog reads
   it (`ReviewEvidence`, a query port, `JdbcReviewEvidence`) in at most 3 statements, whatever the number of fields;
   the admin module only composes it (`GetReviewEvidence`). A new run deletes the old run's AI fields, so an open item's
   run is the current one; a superseded item can show its run with no fields left, and the page says so.
   The values are model output (EU AI Act Art. 50, [0038](../guide/0038-ai-content-marking.md)): the response has an
   `aiContent` marker with the run's model and the AI-written paths, and the page labels each value with its origin in
   words and marks it with `data-ai-generated` and `data-ai-model`. The validation errors quote what the model wrote,
   so the list says "Includes model output" and is marked the same way. Quotes come from the sources: each one says
   "Source quote" and is not marked.
   Every text from a source or a model is plain text on the page; a source address is a link only when it is http(s).
   The review page is a list and the selected item, with keys for a fast review (j/k, a, r, n, ?; UJ-9). The keys
   work only while focus is inside the queue and never in a field (WCAG 2.1.4), and each control names its key
   (`aria-keyshortcuts`). A held key does not repeat: one press is one decision. Decisions stay final: there is no
   undo.
