# 0026 — Back office and the review actions

**Status.** Accepted. Merges old 0045, 0064, 0065, 0066 (texts merged in PR 9 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0045"></a>

## Part: was ADR 0045 — Back office: a thin admin module, one page per part, an overview of what needs a person

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-libraries-and-apps.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md), [0020](../scout/0020-scout-runner.md), [0024](0024-identity-and-admin-host.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md) **Amended by:** [0032](../operations/0032-runtime-topology.md) (the back office is its own app and web app), [the part that was ADR 0064](#part-0064) (the target of a relationship candidate), [the part that was ADR 0065](#part-0065) (editing a resource candidate), [the part that was ADR 0066](#part-0066) (setting or dropping a resource's field).

### Context
The back office was one page: the review queue and the Scout runs. To see if something was wrong I had to scroll and
read, and the numbers that matter (open items per kind, the enrichment backlog, today's model budgets, web credits,
failed runs) were in Grafana or nowhere. The admin API lived in the Scout's web adapter, but it served the catalog and
the Scout alike. The budget gauges were per replica: a pod that made no call showed the full budget.
More admin parts are planned (enrichment queue, evidence view, bulk decisions, settings), so the structure must hold
them without each module growing an admin corner.

### Decision
1. **A thin `admin` module.** `ai.helper2.admin` holds the back office's web adapters (`AdminController`,
   `OverviewController`) and use cases that only compose other modules (`GetOverview`). It owns no table and no module
   depends on it. The catalog and Scout use cases it calls are named interfaces (`catalog.usecase`, `scout.usecase`).
   Its use cases are plain classes, wired in `admin.config`, and the `DOMAIN` rule of `ModularityTest` checks them
   ([0002](../foundation/0002-modular-monolith-libraries-and-apps.md)). It has one adapter of its own: `JsonExampleDrafts`
   reads a drafted example from a review item, behind the `ExampleDrafts` port.
2. **One page per part.** `/admin` (overview, the landing page), `/admin/review`, `/admin/runs`, `/admin/audit`, each with
   its state in the address, so a number on the overview links to the view that acts on it
   (`/admin/review?kind=USER_REPORT`).
3. **The overview: what needs a person now.** `GET /api/v1/admin/overview` returns counts only: open review items per
   kind (every kind, 0 included), the enrichment backlog (the same rule the Scout takes it by), every model budget with
   today's count and limit, web credits today, the last 10 Scout runs and the failed ones of the last 24 hours, the
   home examples shown and hidden ([0015](../search/0015-home-examples.md)), and a link to Grafana (`H2_ADMIN_GRAFANA_URL`) for trends. It is computed at most once a minute, one computation at a time:
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
   reads one more part first, the products a person accepted, from their few review items ([0023](../scout/0023-evidence-gated-enrichment.md)). Measured on synthetic
   data, 250 000 resources: the next 20 from 1.4 s to under 1 ms, the count from 1.3 s to 0.65 s. Every backlog read
   gives up after 5 seconds. The queue is admin-only and shows catalog data, no personal data; a cursor
   that is not one of ours (wrong shape, a time outside what the database holds) is a 400.
7. **The evidence view reads the run through the item's link.** `GET /api/v1/admin/review-items/{id}/evidence`
   (ADMIN, back office) answers what an item is about. A review item already links to its enrichment run and, for a
   relationship candidate, to its relationship (`enrichment_run_id`, `relationship_id`). The view follows that link;
   it never parses `details`, which stays text. An item from a run: the run (model, prompt version, outcome, when it
   finished, the README or model card of its gap round), the run's validation errors, and the fields the run stored,
   CONFLICTING first, then UNVERIFIED, VERIFIED and REJECTED. A relationship candidate: the run and the relationship.
   Any other item (a user report, a course or resource candidate, a drafted example): the item only. Each field and the
   relationship carry at most 5 quotes, the newest first; a run shows at most 50 fields and 50 errors. The catalog reads
   it (`ReviewEvidence`, a query port, `JdbcReviewEvidence`) in at most 3 statements, whatever the number of fields;
   the admin module only composes it (`GetReviewEvidence`). A new run deletes the old run's AI fields, so an open item's
   run is the current one; a superseded item can show its run with no fields left, and the page says so.
   The values are model output (EU AI Act Art. 50, [0018](../guide/0018-ai-content-marking-and-research-trail.md)): the response has an
   `aiContent` marker with the run's model and the AI-written paths, and the page labels each value with its origin in
   words and marks it with `data-ai-generated` and `data-ai-model`. The validation errors quote what the model wrote,
   so the list says "Includes model output" and is marked the same way. Quotes come from the sources: each one says
   "Source quote" and is not marked.
   Every text from a source or a model is plain text on the page; a source address is a link only when it is http(s).
   The review page is a list and the selected item, with keys for a fast review (j/k, a, r, n, ?; UJ-9). The keys
   work only while focus is inside the queue and never in a field (WCAG 2.1.4), and each control names its key
   (`aria-keyshortcuts`). A held key does not repeat: one press is one decision. Decisions stay final: there is no
   undo.
8. **Acting on the queue: requeue, skip, move to the top.** On `/admin/enrichment` each row has its actions, sent as
   `POST /api/v1/admin/enrichment-queue/{id}/requeue|skip|prioritise` (back office, ADMIN, CSRF). An action changes the
   order only. It never calls the model, and the daily enrichment limit stays the same.
   - **Move to top** puts a waiting resource before every other part, the accepted products included. It holds until
     the resource's next run starts. At most 50 wait at the top: with no limit, the order would be whatever I clicked
     last.
   - **Skip** takes a waiting resource out of the backlog until I requeue it, with an optional reason (200 characters,
     control characters dropped; the form says "No personal data"). It drops a move to the top. A skip holds
     everywhere the Scout enriches: a collector that sees new text for a skipped resource does not send it to the
     model either (`EnrichmentCandidates.withEnoughText`).
   - **Requeue** undoes a skip. For a resource out of retries (its last run FAILED, and 3 FAILED runs), only the FAILED
     runs from now on count against the cap, so it gets 3 more tries.
   - The page has three views, in the address: waiting (the Scout's order), skipped (the latest skip first), out of
     retries. A resource out of retries is still in the backlog index (status REJECTED), so its view walks the index.
   - An unknown resource is a 404. An action that does not apply (requeue a waiting resource, skip a skipped one, move
     one more to a full top) is a 409 `not-applicable` with the reason in words. Every call writes one audit row, in
     the same transaction as the change: `REQUEUE_ENRICHMENT`, `SKIP_ENRICHMENT`, `PRIORITISE_ENRICHMENT`, with the
     outcome (`REQUEUED`, `REQUEUED_RESET` when the retries start again, `SKIPPED`, `PRIORITISED`, or the reason it
     did not apply, `NOT_FOUND` included).
   - A move to the top first takes a Postgres advisory lock for the rest of its transaction
     (`pg_advisory_xact_lock`), then counts. Two moves at the same second wait for each other, so the limit of 50 is
     strict.
   - **Who owns what.** The decisions are the Scout's: the rules are `QueuePlace` (Kotlin, no framework) and one use
     case per action. They are stored in a Scout table, `enrichment_queue_control`, one row per resource I touched,
     gone with the resource. The Scout is its only writer. The backlog SQL stays in the catalog (`BacklogRule`), and
     it reads the control rows through a join. This is a known exception in the table-owner test
     (`ModularityTest`): the catalog reads a Scout table, read only. I chose it because the order must stay one rule
     for the Scout's next batch, the page and the count. Two copies of the rule would drift apart.
   - Measured on synthetic data (250 000 resources, 37 500 REJECTED, 50 at the top), with `EXPLAIN ANALYZE`: the next 20
     in 2 ms (0.9 ms before; 5 ms in the worst case, 500 skips at the head of the never-run part), the count 1.7 to
     1.9 s (1.4 to 1.65 s before, same data; still cached a minute, with the 5-second limit), a waiting page 1 to 3 ms,
     the skipped page 0.3 ms, the out-of-retries page 1.1 to 1.6 s (it walks every REJECTED resource), one resource's
     place 0.1 ms.
9. **Bulk decisions: each item on its own, accept only where it is safe.** `POST /api/v1/admin/review-items/resolutions`
   takes up to 100 ids, a decision and an optional note (CSRF applies). `ResolveReviewItems` sends each item through the
   one-item decision (`ResolveReview`) in its own transaction, so each item writes its own audit entry, the same as a
   single decision, with the batch at the end: `ACCEPTED bulk:<batch>`. One item that fails does not undo the others.
   The answer gives one outcome per item, in the order sent: RESOLVED, NOT_OPEN, NOT_FOUND or NOT_ALLOWED. Rejecting is
   allowed for every kind: nothing is added or shown. Accepting is allowed only for an enrichment check whose run has no
   CONFLICTING licence, open source or pricing (`BulkDecisionRule`, a pure class; the conflicts come from their own
   query, `ReviewEvidence.conflictingFields`, not from what the evidence view shows): every other kind adds a resource,
   verifies a relationship, publishes an example or closes a user's report, and a new kind is refused until the rule
   names it. A refused item stays open and is recorded as `NOT_ALLOWED bulk:<batch>`. On the page each open row has a
   box (x picks the selected item); a bar under the list says "4 selected · Accept · Reject · Clear"; Accept and Reject
   ask first in a dialog that repeats the count by kind, with focus on Cancel. The line after says "11 done, 1 failed
   (already decided)", and the items that failed stay picked.
10. **The Settings page: what the app runs with now, read-only.** `GET /api/v1/admin/config` and `/admin/settings` show
    the settings by module: model ids per use, prompt versions, the guide's configuration hash, the daily budgets and
    caps, the Tavily credits and per-feature quotas, the Scout's schedule, and the sources and collectors that are on.
    Each module publishes one `SettingsGroup` bean (`platform.settings`) built from its own typed records, like the
    policy values of [0029](../frontend/0029-site-pages-and-policy.md). The back office reads no other module's record and
    never the environment ([0007](../foundation/0007-configuration-secrets-and-settings.md)).
    It is an allow-list: each setting is listed by hand, through one factory per kind of value (count, cap, flag,
    duration, model, version, schedule, names). There is no factory for a secret. A value with a quote, an `@` or more
    than 500 characters is refused, so a URL with a user or a password cannot pass even by mistake. Each row names the
    `H2_` variable that sets it. The values are bound at startup, so the page is fixed until the next restart.
    Six spending limits are the exception: they change on the same page, at runtime, and their rows show the value
    now ([0007](../foundation/0007-configuration-secrets-and-settings.md)). The overview says how many of them are changed, with a link
    to the page.

### Consequences
- One place for every admin endpoint; a new part is a route, a controller method and a use case here.
- The public JavaScript does not carry the back office: its pages, its API calls (`api/admin.ts`) and its copy
  (`en.admin.json`) are only in the admin web app, and the public app has no admin route at all.
  `api/adminBundle.test.ts` keeps the shared client and the public translations free of them. The back office refuses
  every admin call without ADMIN anyway.
- The overview shows only what to act on now. Trends stay in Grafana; the overview does not rebuild them.
- The numbers can be up to a minute old; the page says when they were counted.
- A budget that is never built (no model configured), or one that counts only in memory, is not listed.
- The admin sees the quotes behind a value before deciding, without opening the resource page. The evidence is read
  only for the selected item, one request per item.
- A batch is not atomic: a server error halfway leaves the first items decided. The page reloads and shows them as
  they are; the audit trail names the batch.
- A repeated id makes the whole batch a 400. The contract does not declare the ids unique: the generated set would
  drop the repeat before the use case could refuse it.
- The bulk rule is checked before the item's transaction, not inside it. Between the check and the decision the item
  can be decided by someone else (then it is NOT_OPEN), but its kind and its run cannot change, so the rule's answer
  stays right. Low risk, and one transaction per item stays simple.
- Bulk accept takes an enrichment check whose fields are UNVERIFIED or AI_ESTIMATED, as long as licence, open source
  and pricing are not CONFLICTING. This is a product choice: those values are already shown with their origin and
  verification, and accepting the check publishes nothing new. I may revisit it.
- The queue has no total of its own: the overview's count is the total, up to a minute old. A resource that changes
  part between two pages (a run finishes) can be skipped or shown twice; the next visit shows it in its place.
- A skip or a move to the top changes which resources get the daily model calls, never how many.
- Moves to the top run one at a time (the advisory lock). They are rare admin clicks, so the wait is short.
- The catalog's SQL names a Scout table. The table-owner test lists it as an exception, so it stays visible.
- The Settings page shows what the running pod was started with. A new setting is a line in its module's group;
  a setting that is not listed is not shown.

### Alternatives considered
- Each module keeps its own admin controller — the overview would still need one place to compose them, and the
  admin API would be spread over every module's web adapter.
- A stored read model for the overview — a table and a job to keep it current, for numbers one person reads a few
  times a day. Not needed until a measurement says so.
- Per-replica budget gauges on the page — wrong by design: a replica that made no call shows the full budget.
- Rebuild the Grafana graphs in the back office — two places for the same trend, and more code to keep right.
- Store the evidence in the item's `details` as JSON — a second copy of what the run tables hold, which goes stale
  when a re-check rejects a field, and a format change for every writer of `details`.
- Send the evidence with every row of the list — 20 items times their fields and quotes on every page, for the one
  item the admin looks at.
- Queue decisions as columns on `resource` — the catalog's table would carry the Scout's rules, and every resource a
  row it rarely needs.
- The Scout passes its decisions to the catalog as parameters (ids to put first, ids to leave out) — fine for the 50
  at the top, but the skipped and requeued ids grow without a bound, and the count would need them all.
- A catalog-owned control table, written through a catalog port (like the re-run request of ADR 0023) — no exception in
  the table-owner test, but the rules and their data would live in two modules. Possible later, if the exception
  starts to hurt.
- One transaction for a whole batch — one bad item would undo the others, and a long transaction holds locks on up to
  100 rows. Each item on its own matches what one decision does.
- Bulk accept for every kind — one click could add 100 resources to the catalog or verify 100 relationships nobody read.
- An undo for bulk decisions — a decision can publish or verify at once; an undo would have to reverse that. Decisions
  stay final, and the confirm dialog says so.
- Settings from Spring's `Environment` or `/actuator/env` — every key with no work, but a deny-list: a new secret
  shows until someone masks it, and the actuator lives on the management port, not on the admin host.
- The back office reads every module's config record — fewer types, but the records are internal to their module
  (Spring Modulith), like for the policy endpoint.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/` — `package-info.java`, `web/AdminController.java`, `web/OverviewController.java`,
  `web/EnrichmentQueueController.java`, `web/SettingsController.java`, `usecase/GetOverview.java`,
  `usecase/ListEnrichmentQueue.java`, `usecase/GetEffectiveSettings.java`,
  `usecase/ResolveReview.java`, `ExampleDrafts.java` and `internal/JsonExampleDrafts.java` (the drafted example in a
  review item), `config/AdminUseCaseConfiguration.java` (wiring, the transaction of `ResolveReview`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/settings/` — `Setting.java`, `SettingsGroup.java`; each module's group in
  `platform/llm/LlmConfiguration.java`, `guide/internal/GuideConfiguration.java`,
  `search/internal/SearchSettingsConfiguration.java`, `scout/internal/ScoutConfiguration.java`,
  `sources/config/SourcesConfiguration.java`, `setup/internal/SetupConfiguration.java`,
  `examples/internal/ExamplesConfiguration.java`.
- `backend/libs/schema/src/main/resources/db/migration/V10__resource_enrichment_backlog_idx.sql`; `catalog/enrichment/EnrichmentCandidates.java`
  and `catalog/internal/JdbcEnrichmentCandidates.java` (`pending`, `pendingCount`, `backlog`, `standing`, `prioritisedCount`),
  `catalog/internal/BacklogRule.java`.
- Queue actions: `V14__enrichment_queue_control.sql`; `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EnrichmentQueue.kt`
  (`QueuePlace`), `scout/Scout.kt` (`EnrichmentQueueControl`, `UnitOfWork`), `scout/usecase/RequeueEnrichment.kt`,
  `SkipEnrichment.kt`, `PrioritiseEnrichment.kt`; `scout/internal/JdbcEnrichmentQueueControl.java`,
  `ScoutConfiguration.java`; `admin/web/EnrichmentQueueController.java`; `platform/ProblemType.java` (`NOT_APPLICABLE`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/BudgetLedger.java`, `CallCounter.java`, `DailyBudget.java`,
  `RedisCallCounter.java` (`current`), `LlmConfiguration.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`openCountsByKind`),
  `scout/internal/CatalogEnrichmentStore.java` and `catalog/internal/JdbcEnrichmentCandidates.java` (`pendingCount`), `search/config/MicrometerSearchMetrics.java`.
- `backend/libs/examples/src/main/java/ai/helper2/examples/usecase/CountHomeExamples.java`, `backend/libs/examples/src/main/kotlin/ai/helper2/examples/PoolCounts.kt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/GetReviewEvidence.java`, `admin/web/ReviewController.java`,
  `catalog/ReviewEvidence.java`, `catalog/internal/JdbcReviewEvidence.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/BulkDecisionRule.java`, `admin/usecase/ResolveReviewItems.java`,
  `admin/usecase/ResolveReview.java` and `catalog/usecase/ResolveReviewItem.java` (the batch in the audit outcome).
- `frontend/src/pages/admin/` — `AdminLayout.tsx`, `AdminOverview.tsx`, `ReviewQueuePage.tsx`, `ReviewEvidence.tsx`,
  `ReviewItemDetails.tsx`, `ReviewKeysHelp.tsx`, `ReviewBulk.tsx`, `AdminDialog.tsx`, `ScoutRunsPage.tsx`,
  `EnrichmentQueuePage.tsx`, `AuditPage.tsx`, `SettingsPage.tsx`; `adminText.ts` adds the back office's copy
  (`i18n/locales/en.admin.json`) when an admin page loads; `frontend/src/api/admin.ts` holds the admin calls.
- Tests: `api/AdminOverviewApiIT.java`, `admin/usecase/GetOverviewTest.java`, `platform/llm/BudgetLedgerTest.java`,
  `RedisCallCounterIT.java`, `scout/internal/CatalogEnrichmentStoreIT.java`, `search/config/MicrometerSearchMetricsTest.java`,
  `api/EnrichmentQueueApiIT.java`, `admin/web/EnrichmentQueueCursorTest.java`, `catalog/internal/JdbcEnrichmentCandidatesIT.java`,
  `api/ReviewEvidenceApiIT.java`, `catalog/internal/JdbcReviewEvidenceIT.java`,
  `api/ReviewBulkApiIT.java`, `admin/BulkDecisionRuleTest.java`,
  `api/EnrichmentQueueActionsApiIT.java`, `scout/QueuePlaceTest.kt`, `scout/usecase/EnrichmentQueueActionsTest.kt`,
  `api/AdminConfigApiIT.java` (fake secrets bound, none in the answer), `platform/settings/SettingTest.java`,
  `admin/usecase/GetEffectiveSettingsTest.java`,
  `architecture/ModularityTest.java` (the table-owner exception),
  `frontend/src/pages/admin/Admin.test.tsx`, `EnrichmentQueuePage.test.tsx`, `ReviewEvidence.test.tsx`, `ReviewBulk.test.tsx`,
  `SettingsPage.test.tsx`, `src/a11y.test.tsx`.

### Diagrams
[flow](../../diagrams/flow-0026-back-office.svg) · [sequence](../../diagrams/sequence-0026-back-office.svg)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0064"></a>

## Part: was ADR 0064 — Relationship target in the review list: link, add or rename

**Status.** Accepted. **Builds on:** [0023](../scout/0023-evidence-gated-enrichment.md), [0024](0024-identity-and-admin-host.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [the part that was ADR 0045](#part-0045), [0023](../scout/0023-evidence-gated-enrichment.md)

### Context
A relationship candidate names its target as the model wrote it: "Gemini 4 Carbon", "LangChain". When the catalog
has no resource with exactly that title, the link has no target. The admin could only accept or reject it: accepted,
it stayed a name with no page behind it.

Often the right resource is in the catalog under another title ("LangChain framework"). Sometimes it is not in the
catalog at all: a new model the article is about.

### Decision
1. **One call changes the target** of an open relationship candidate:
   `PUT /admin/review-items/{id}/relationship-target`, with exactly one of:
   - `resourceId`: a resource in the catalog; the relationship takes its title;
   - `newResource`: name, official https address and type (a tool or a model: PRODUCT, MODEL, AGENT, PROMPT, SKILL,
     MCP_SERVER, LIBRARY, DATASET); see 2;
   - `title`: another name, with no resource.

   It answers the item's evidence with the new target. The item stays open: accepting it is what makes the link
   VERIFIED ([0023](../scout/0023-evidence-gated-enrichment.md)), so a change can be checked before it counts.
2. **Adding a resource** (`AddCuratedResource`). An address the catalog already has, as a resource or as one of its
   records, gives that resource: never a second one. Otherwise it becomes a record of the `curated` source, with the
   admin's name and type, no description, data origin CURATED and enrichment NOT_RUN: the next Scout run enriches it
   from its own pages ([0023](../scout/0023-evidence-gated-enrichment.md)). The curated source now lists these types too.
3. **The target is the admin's, the kind stays the model's.** The relationship keeps its origin; its own
   `target_origin` becomes CURATED (`targetOrigin` in the API). The review evidence marks `relationship.kind` as
   AI-written, not `relationship.target` ([0018](../guide/0018-ai-content-marking-and-research-trail.md)). The resource page keeps the
   AI label and `data-ai-generated` on the relationship: its kind is still model output.
4. **A later run keeps the admin's choice.** A Scout run on the resource before the item is decided does not
   supersede that item, nor delete its relationship: the change survives and the item stays decidable.
5. **Refusals change nothing.** One transaction, with the item row locked: 404 for no item; 409 `already-decided`
   for an item no longer open; 409 `not-applicable` for an item that is not a relationship candidate; 409
   `duplicate` when the resource already has a relationship of that kind to that target; 400 for two or none of the
   three, an unknown resource, the resource itself, a non-https address, or a type that is not a tool or a model. A
   resource added for a refused change is rolled back with it.
6. **Audit.** `CHANGE_RELATIONSHIP_TARGET` with the item id and `LINKED <resource id>` or `RENAMED`, and
   `ADD_RESOURCE` with the new resource's id and `ADDED`. Refused changes are not recorded: nothing changed.
7. **Back office.** The evidence of an open relationship candidate has "Change the target": find the resource with
   the visitor search (as the home examples do), add it, or give another name. Errors say why; the form stays open.

### Consequences
- An ABOUT link ([0023](../scout/0023-evidence-gated-enrichment.md)) can always end on a real page, even for a model
  launched yesterday.
- A resource an admin adds starts with only a name and an address. Until the Scout enriches it, its card is thin.
- Two writes for one decision: change the target, then accept. The UI keeps them next to each other.
- A retargeted relationship stays open until a person decides it, whatever later runs say. A run that proposes the
  same kind and name adds nothing; one that proposes the model's first name again opens a new item next to it.

### Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, on the admin host behind the VPN, with CSRF
  ([0024](0024-identity-and-admin-host.md)); the same rules as every back-office write.
- **Tampering**: the address must be https and canonical; the name and the type are checked; a link to itself is
  refused. The resource is added through the same catalog write as every record.
- **Repudiation**: every change and every added resource is in the append-only audit trail.
- **Information disclosure**: none new. A resource holds a public name and address; the audit holds the
  administrator's GitHub id, as for every action. The Privacy page names these records, and that a relationship
  keeps whether a person chose its target (kept as long as the relationship).
- **Denial of service**: one admin, one row per change; no outbound call (the page is read later by the Scout,
  inside its caps).

### Alternatives considered
- **Pick the target when accepting** — one call, but accept then does two things, and a wrong target is VERIFIED at
  once with no second look.
- **Add a resource from its own admin page** — a second place to keep, and the admin leaves the item to do it.
- **Let a name-only target create a resource later** — no address, so no page to enrich and no way to avoid a
  duplicate. Every new resource needs its address.

### Code
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/ChangeRelationshipTarget.java`, `AddCuratedResource.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/RelationshipTargets.java`, `CuratedTypes.java`,
  `ResourceStore.java` (`atUrl`), `CatalogConfiguration.java` (the curated source's types).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml`
  (`RelationshipTargetChange`, `NewResource`, audit actions).
- `backend/libs/schema/src/main/resources/db/migration/V19__relationship_target.sql`.
- `frontend/src/pages/admin/RelationshipTarget.tsx`, `ReviewEvidence.tsx`.
- Tests: `RelationshipTargetApiIT`, `RelationshipTarget.test.tsx`, `ReviewEvidence.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0026-relationship-target-in-review.svg) · [sequence](../../diagrams/sequence-0026-relationship-target-in-review.svg).

<a id="part-0065"></a>

## Part: was ADR 0065 — Candidate edit in the review list: title, type and tags before accepting

**Status.** Accepted. **Builds on:** [the part that was ADR 0045](#part-0045), [0022](../scout/0022-discovery.md), [0021](../scout/0021-collectors-and-events.md), [0023](../scout/0023-evidence-gated-enrichment.md)

### Context
A resource candidate is what its collector guessed from a URL and a title: "Pricing | Open WebUI", PRODUCT, with the
category tags of the search that found it. Often the guess is close but not right. A blog post comes in as a product,
or a page title still carries the site's name. The admin could only accept it as it was, or reject it and lose the
page.

The type was also locked to the finders: a discovery category declares only its product type and ARTICLE
([0021 (part was 0061)](../scout/0021-collectors-and-events.md) §5), so a news item it found could never be accepted as NEWS.

### Decision
1. **One call edits an open resource candidate:** `PUT /admin/review-items/{id}/candidate` with at least one of
   `title` (1 to 300 characters, one line), `type` (any candidate type: products and tools, MODEL, DATASET,
   ARTICLE, NEWS, EVENT; never COURSE or OTHER) and `tags` (at most 12, tag-shaped). What is left out stays as the
   collector wrote it. It answers the item, with its new reason line. The item stays open.
2. **The edit is marked in the details** (`edited`: the fields the admin changed).
   - When another collector finds the page later ([0022 (part was 0051)](../scout/0022-discovery.md) §8,
     [0022](../scout/0022-discovery.md)), it joins the candidate's finders. It does not change an edited
     type or edited tags, and never the title.
   - Accepting indexes what the admin wrote. A type the admin set need not be one a finder declares; it must still be
     a candidate type.
   - The accepted record keeps the list of edited fields in its extra (never shown). Who edited is in the audit
     trail, not in the catalog.
3. **No lost update.**
   - The form sends back the details it showed (`expectedDetails`). The edit applies only to those: when the
     candidate changed since the form opened (a collector, another admin), it is 409 `version-conflict`: reload,
     edit again.
   - The edit and a collector's merge both save only when the item still holds the details they read
     (`updateCandidate` compares them). A merge that lost the race is skipped and logged; the next run of that
     collector joins it again.
   - The new details and the `EDIT_CANDIDATE` entry are saved in one transaction: no edit without its audit entry.
   - Accepting locks the item's row first (`ReviewQueue.lock`), so what is indexed is what is decided. An edit or a
     merge at the same moment waits, then finds the item no longer open, and saves nothing.
4. **Refusals change nothing:** 400 for no field or an unusable value, 404 for no item, 409 `already-decided` for an
   item no longer open, 409 `not-applicable` for another kind.
5. **Audit:** `EDIT_CANDIDATE` with the item id and `EDITED <fields>`.
6. **Back office:** "Edit the candidate" in the details of an open resource candidate: title, type and tags (comma
   separated). Only changed fields are sent. The details then say "Changed by an admin: title, type."

### Consequences
- A post a category found can be accepted as the article or news item it is, under a clean title; its enrichment
  then names what it is about ([0023](../scout/0023-evidence-gated-enrichment.md)).
- An edited type holds for the candidate, not for the resource. Once in the catalog, the resource's type comes from
  all its records, the most specific first: a later record from another source can still change it. Fixing a
  resource's own fields is the next step (roadmap).

### Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0024](0024-identity-and-admin-host.md)).
- **Tampering**: the values are checked here and again on accept; the details are rewritten as JSON by code, never
  taken as text from the client.
- **Repudiation**: every edit is in the append-only audit trail.
- **Information disclosure**: none new. The record's extra lists field names only, no administrator id. The Privacy
  page says the record keeps which fields a person changed, for as long as the resource is listed.
- **Denial of service**: one admin, one row per edit.

### Alternatives considered
- **Edit while accepting** — one call, but a mistake is in the catalog at once, and a collector's merge in between
  would be lost silently.
- **Edit the resource after accepting** — the right tool for a resource's own facts (next step), but it lets a wrong
  type reach the catalog and the enrichment first.
- **Lock the item while an admin edits** — long locks for a form left open; the compare-and-save keeps both writers
  safe without one. The only lock is the short one of an accept, for the length of its transaction.

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EditCandidate.kt`; `IndexAcceptedCandidate.kt`
  (`CandidateRules`, `typeChosenByAdmin`).
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` (`edit`, `merge`, `read`, `reason`),
  `ReviewQueueCandidateItems.java`, `ReviewQueueCandidateReview.java`, `ScoutConfiguration.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`updateCandidate` compares the details, `lock`);
  `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` (locks an accepted item first).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml` (`CandidateEdit`,
  `CandidateType`, audit action).
- `backend/libs/schema/src/main/resources/db/migration/V21__candidate_edit.sql`.
- `frontend/src/pages/admin/CandidateEditForm.tsx`, `ReviewItemDetails.tsx`, `ReviewQueuePage.tsx`.
- Tests: `EditCandidateTest`, `ResourceCandidateJsonTest`, `IndexAcceptedCandidateTest`, `CandidateEditApiIT`,
  `CandidateEditForm.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0026-candidate-edit-in-review.svg) · [sequence](../../diagrams/sequence-0026-candidate-edit-in-review.svg).

<a id="part-0066"></a>

## Part: was ADR 0066 — Curated field: an admin sets a resource's own value, over every source and every run

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0023](../scout/0023-evidence-gated-enrichment.md), [0021](../scout/0021-collectors-and-events.md), [the part that was ADR 0045](#part-0045), [0023](../scout/0023-evidence-gated-enrichment.md)

### Context
An enrichment check shows what the model said about a resource and the quotes behind it. Sometimes the model is wrong
and the quote does not help: the price is the subject's, the licence is an old one, the type is a guess. The admin
could accept or reject the check, and nothing else. The wrong value stayed until a new run, and a new run could say
the same thing again. A value a source states could not be fixed at all.

### Decision
1. **Set a value:** `PUT /admin/resources/{id}/fields/{field}` with `value`, for the fields that have a column of
   their own: type, pricing, license, openSource, techLevel and audience. The value must be one the field takes
   (an SPDX id for a licence; no OTHER type, no UNKNOWN audience).
   - The value is written to the resource's column, and the field is listed in `resource.curated_fields`.
   - A CURATED, VERIFIED field assertion says where it came from; the page shows "Curated by Helper2".
   - The AI's values of the field are rejected.
2. **It wins everywhere, and stays.**
   - No record changes it: the catalog upsert keeps a listed column, whatever the origin of the record
     (`ResourceStore`).
   - No run changes it: the value goes into the run's stated facts, so the evidence ladder does not read more for
     it, and the validator drops a model value for it without an error, so no review item opens for it.
   - The resource page shows it before a source conflict, the source's value and the enrichment (`FieldStates`).
     Filters and ranking read the column. A card is merged from the source records, then takes the curated
     columns over them and drops the sources' conflict on those fields (`ResourceCards`), so the page card shows it.
     In a search, the catalog's hit carries the curated fields, and the merge takes its values over the other
     sources' with no conflict (`ResultMerger`), so the search cards show it too.
   - The event seeds replace only the fields they assert (eventDates, eventFormat), so they leave an admin's value
     on the same event alone (`scripts/industry-events-seed.mjs`).
3. **Drop the AI's value:** `POST /admin/resources/{id}/fields/{field}/drop`, for any field the AI writes, use case
   and platforms too. It rejects the AI's values, as the code re-check does
   ([0023](../scout/0023-evidence-gated-enrichment.md)). A value a source states or an admin set stays. The page then shows the
   source's value, or "Not stated": a rejected estimate is not shown (`FieldStates`).
4. **Audit:** `SET_FIELD` (`SET <field>`) and `DROP_FIELD` (`DROPPED <field> <count>`), with the resource id.
5. **Back office:** next to each field of an enrichment check, "Set the value" (a list of the field's values, or the
   licence id) and "Drop the AI value". Both read the evidence again.

### Consequences
- The admin fixes a wrong fact once, and it stays fixed: the Engadget item can be set to NEWS, its pricing dropped.
- A curated value is not AI-written: no AI label, no `data-ai-generated` mark.
- Known limits (roadmap):
  - platforms and use cases are labels rebuilt from the records: they can be dropped, not set;
  - there is no "back to the sources" yet: an admin sets another value instead;
  - the editorial seeds (`R__curated_seed`, `R__courses`, the event seeds) write their own columns when they run
    again, over an admin's value on the same entry. They are editorial too; a seed change is a deliberate edit.

### Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0024](0024-identity-and-admin-host.md)).
- **Tampering**: the field is one of a fixed list, the column is the code's constant, the value is checked against
  the field's values; SQL takes the value only as a parameter.
- **Repudiation**: every change is in the append-only audit trail.
- **Information disclosure**: none new; the values are public facts of a public resource. The catalog holds no
  administrator id.
- **Denial of service**: one admin, one row per change.

### Alternatives considered
- **Only a CURATED assertion, read when shown** — the page could show it, but search cards, filters and ranking read
  the columns: the wrong value would still be found and filtered on.
- **A new data origin per field** — the row-level origin rank cannot say "this one field"; a list of curated fields
  can, and costs one array column.
- **Reject the whole check and wait for a better run** — the next run reads the same pages and can say the same
  thing.

### Code
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/CurateField.java`, `catalog/internal/CuratedFields.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` (the upsert keeps a curated column),
  `ResourceCards.java` and `backend/libs/search/src/main/kotlin/ai/helper2/search/Model.kt` (`withCurated`: the card takes it;
  `SourceHit.curatedFields`), `search/ResultMerger.kt` (a search merge takes it), `CatalogSearchSource.java`,
  `JdbcEnrichmentCandidates.java`, `catalog/enrichment/EnrichmentCandidates.java` (the curated values).
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java`,
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` (`curatedFields`), `EvidenceValidator.kt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml` (`ResourceField`,
  `ResourceFieldValue`, audit actions).
- `backend/libs/schema/src/main/resources/db/migration/V22__curated_fields.sql`; `scripts/industry-events-seed.mjs` and the two
  event seeds it writes.
- `frontend/src/components/results/FieldStates.tsx`, `frontend/src/pages/admin/FieldCuration.tsx`,
  `ReviewEvidence.tsx`.
- Tests: `CuratedFieldApiIT`, `CatalogEnrichmentStoreTest`, `EvidenceValidatorWritingTest`, `ResourcePage.test.tsx`,
  `FieldCuration.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0026-curated-field.svg) · [sequence](../../diagrams/sequence-0026-curated-field.svg).
