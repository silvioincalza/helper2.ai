# 0038 — Back office and review

**Depends on:** [0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0004](../foundation/0004-persistence-and-the-schema.md), [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0011](../scout/0011-scout-runner.md), [0012](../scout/0012-scout-collectors-and-events.md), [0013](../scout/0013-discovery.md), [0014](../scout/0014-evidence-gated-enrichment.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0028](../search/0028-home-examples.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0033](../guide/0033-marking-ai-written-text-research-trail.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md).

## Context

The back office was one page: the review queue and the Scout runs. To see if something was wrong I had to scroll and
read, and the numbers that matter (open items per kind, the enrichment backlog, today's model budgets, web credits,
failed runs) were in Grafana or nowhere. The admin API lived in the Scout's web adapter, but it served the catalog and
the Scout alike. The budget gauges were per replica: a pod that made no call showed the full budget. The back office
has many parts (overview, review queue, enrichment queue, evidence view, bulk decisions, settings), so the structure
must hold them without each module growing an admin corner.

On a review item, accept and reject were not enough:
- A relationship candidate names its target as the model wrote it: "Gemini 4 Carbon", "LangChain". When the catalog
  has no resource with exactly that title, the link has no target. Often the right resource is in the catalog under
  another title ("LangChain framework"); sometimes it is not in the catalog at all, a new model the article is about.
  Accepted, the link stayed a name with no page behind it.
- A resource candidate is what its collector guessed from a URL and a title: "Pricing | Open WebUI", PRODUCT, with the
  category tags of the search that found it. A blog post comes in as a product, or a page title still carries the
  site's name. The type was also locked to the finders: a discovery category declares only its product type and
  ARTICLE ([0012](../scout/0012-scout-collectors-and-events.md) §14), so a news item it found could never be accepted as NEWS.
- An enrichment check shows what the model said about a resource and the quotes behind it. Sometimes the model is
  wrong and the quote does not help: the price is the subject's, the licence is an old one, the type is a guess. The
  wrong value stayed until a new run, and a new run could say the same thing again. A value a source states could
  not be fixed at all.

## Decision

### The back office
1. **A thin `admin` module.** `ai.helper2.admin` holds the back office's web adapters (`AdminController`,
   `OverviewController`, `ReviewController`, ...) and use cases that only compose other modules (`GetOverview`). It owns
   no table and no module depends on it. It calls the other modules only through their use cases and ports
   (`catalog.usecase`, `scout.usecase`, `examples.usecase`, `identity`, `platform.settings`), never a class of their
   `internal` package: one rule of the back office's `ModularityTest` checks every admin package, web and config
   included. Its use cases are plain classes, wired in `admin.config`, and the `DOMAIN` rule of `ModularityTest`
   checks them with the catalog, examples, identity, search and Scout use cases they call
   ([0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)). It reads no other module's storage format: a
   drafted example is read and accepted through the examples use cases `ReadExampleDraft` and `AcceptExampleDraft`
   ([0028](../search/0028-home-examples.md) §6). Its own classes live in its own packages, never in a library's: the
   place it serves the admin web app from is `admin.config.SpaProperties` (`helper2.spa`).
2. **One page per part.** `/admin` (overview, the landing page), `/admin/review`, `/admin/runs`, `/admin/audit`, each with
   its state in the address, so a number on the overview links to the view that acts on it
   (`/admin/review?kind=USER_REPORT`).
3. **The overview: what needs a person now.** `GET /api/v1/admin/overview` returns counts only: open review items per
   kind (every kind, 0 included), the enrichment backlog (the same rule the Scout takes it by), every model budget with
   today's count and limit, web credits today, the last 10 Scout runs and the failed ones of the last 24 hours, the
   home examples shown and hidden ([0028](../search/0028-home-examples.md)), and a link to Grafana (`H2_ADMIN_GRAFANA_URL`) for trends. It is computed at most once a minute, one computation at a time:
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
   (model unavailable, or a FAILED run with retries left), each part the most popular first, then the newest discovery
   ([0011](../scout/0011-scout-runner.md) §7). The ones an admin moved to the top keep their own order, newest
   discovery first. Each row says why it waits,
   how many runs FAILED, and the last run. The page is a keyset (part, popularity, first discovered, id) behind an
   opaque cursor, not an OFFSET: a deep page costs what the first one does. The overview's backlog count links here.
   The Scout's next batch, the queue and the count share one rule, read in two parts from two partial indexes on the
   same rows (`resource_enrichment_value_idx` for the order by popularity, `resource_enrichment_backlog_idx` for the
   oldest ones and the count): the second part runs only when the first has too few rows. The Scout's batch
   reads one more part first, the products a person accepted, from their decisions (`candidate_decision`,
   [0013](../scout/0013-discovery.md)) ([0014](../scout/0014-evidence-gated-enrichment.md)). Then a quarter of the
   batch (`JdbcEnrichmentCandidates.oldestShare`, at least one) goes to the never-run resources that waited longest,
   oldest first, so a steady flow of popular finds does not starve the others; inside that quarter and in the rest,
   the most popular come first. The gauge
   `helper2.scout.enrichment.oldest_pending.age` (seconds) says how long the oldest never-run resource has waited,
   as of the last batch read. Measured on synthetic
   data, 250 000 resources: the next 20 from 1.4 s to under 1 ms, the count from 1.3 s to 0.65 s. Every backlog read
   gives up after 5 seconds. The queue is admin-only and shows catalog data, no personal data; a cursor
   that is not one of ours (wrong shape, a time outside what the database holds) is a 400.
7. **The evidence view reads the run through the item's link.** `GET /api/v1/admin/review-items/{id}/evidence`
   (ADMIN, back office) answers what an item is about. A review item already links to its enrichment run and, for a
   relationship candidate, to its relationship (`enrichment_run_id`, `relationship_id`). The view follows that link;
   it never parses `details`, which stays text. An item from a run: the run (model, prompt version, outcome, when it
   finished, the README or model card of its gap round), the run's validation errors, and the fields the run stored,
   CONFLICTING first, then UNVERIFIED, VERIFIED and REJECTED. A relationship candidate: the run and the relationship.
   Any other item (a user report, a course or resource candidate, a drafted example): the item only, a drafted
   example with its `draft` (§11). Each field and the
   relationship carry at most 5 quotes, the newest first; a run shows at most 50 fields and 50 errors. The catalog reads
   it (`ReviewEvidence`, a query port, `JdbcReviewEvidence`) in at most 3 statements, whatever the number of fields;
   the admin module only composes it (`GetReviewEvidence`). A new run deletes the old run's AI fields, so an open item's
   run is the current one; an item a later run has closed can show its run with no fields left, and the page says so.
   The values are model output (EU AI Act Art. 50, [0033](../guide/0033-marking-ai-written-text-research-trail.md)): the response has an
   `aiContent` marker with the run's model and the AI-written paths (and a drafted example's, §11), and the page labels each value with its origin in
   words and marks it with `data-ai-generated` and `data-ai-model`. The validation errors quote what the model wrote,
   so the list says "Includes model output" and is marked the same way. A quote that is not in the material it cites
   is kept in its error, with the kind of that material (record, readme or vendor page) and, when another material
   of the run holds it, the kind of that one. Quotes come from the sources: each one says
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
    policy values of [0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md). The back office reads no other module's record and
    never the environment ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)).
    It is an allow-list: each setting is listed by hand, through one factory per kind of value (count, cap, flag,
    duration, model, version, schedule, names). There is no factory for a secret. A value with a quote, an `@` or more
    than 500 characters is refused, so a URL with a user or a password cannot pass even by mistake. Each row names the
    `H2_` variable that sets it. The values are bound at startup, so the page is fixed until the next restart.
    Six spending limits are the exception: they change on the same page, at runtime, and their rows show the value
    now ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)). The overview says how many of them are changed, with a link
    to the page.
    The guide and the setup guide run only in the public app, so their groups are not beans of the back office. The
    public app writes all its groups to the table `public_app_setting` when it is ready (`PublishSettingsAtStartup`):
    one transaction under an advisory lock deletes the rows and inserts the new ones, with the app's version and the
    time. A write that fails is logged and the app starts all the same. The back office shows its own groups, read now,
    and adds the published groups whose id it does not have; a group both apps have (models, search, http) is the back
    office's own. Each group says which app it is from (`ConfigGroup.app`), and a public app group shows the version
    and the time of that start. Before the public app's first start on a database the page says so, and shows the back
    office's groups only. No call goes between the two apps ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)). A stored
    row was built by a factory in the public app; the platform reads it back through `Setting.published`, which only
    its own package can call, and it passes the same check as a bound value, so a value the page cannot show reads "(hidden)", and
    rows that make no group (a key outside its group) or more than 500 rows read as nothing published. The table holds no personal data and
    has no retention period: each start writes it again.
11. **A drafted example comes typed.** The review item of a drafted example (`EXAMPLE_CANDIDATE`) has `draft`: its
    label, query, path and model, as the examples module reads them from the item (`ReadExampleDraft`, over the
    `DraftQueue` port; [0028](../search/0028-home-examples.md) §6). Every other kind, and an item whose details hold no
    readable draft, has `draft: null`; its `details` are shown as plain text. One mapping (`ReviewItemMapper`) gives the
    item the same way in the list, after a decision or an edit, and in the evidence view, so the browser never parses
    a draft. The label and the query are AI-written (EU AI Act Art. 50,
    [0033](../guide/0033-marking-ai-written-text-research-trail.md)). In the API, an item with a draft has its own
    `aiContent` marker: `fields` names `draft.label` and `draft.query`, `models` the draft's model, `provider` the
    configured model provider; every other item has `aiContent: null`. The evidence view's `aiContent` adds
    `item.draft.label` and `item.draft.query`, also for an item with no run (§7). On the page they are labelled
    "Drafted with AI (model)" and marked `data-ai-generated` and `data-ai-model`. A resource candidate's
    details stay JSON for now (open, see Backlog): the edit form sends them back as its version (§21).

### The target of a relationship candidate: link, add or rename
12. **One call changes the target** of an open relationship candidate:
    `PUT /admin/review-items/{id}/relationship-target`, with exactly one of:
    - `resourceId`: a resource in the catalog; the relationship takes its title;
    - `newResource`: name, official https address and type (a tool or a model: PRODUCT, MODEL, AGENT, PROMPT, SKILL,
      MCP_SERVER, LIBRARY, DATASET); see 13;
    - `title`: another name, with no resource.

    It answers the item's evidence with the new target. The item stays open: accepting it is what makes the link
    VERIFIED ([0014](../scout/0014-evidence-gated-enrichment.md)), so a change can be checked before it counts.
13. **Adding a resource** (`AddCuratedResource`). An address the catalog already has, as a resource or as one of its
    records, gives that resource: never a second one. Otherwise it becomes a record of the `curated` source, with the
    admin's name and type, no description, data origin CURATED and enrichment NOT_RUN: the next Scout run enriches it
    from its own pages ([0014](../scout/0014-evidence-gated-enrichment.md)). The curated source now lists these types too.
14. **The target is the admin's, the kind stays the model's.** The relationship keeps its origin; its own
    `target_origin` becomes CURATED (`targetOrigin` in the API). The review evidence marks `relationship.kind` as
    AI-written, not `relationship.target` ([0033](../guide/0033-marking-ai-written-text-research-trail.md)). The resource page keeps the
    AI label and `data-ai-generated` on the relationship: its kind is still model output. The item's reason line
    and note are written again in the same transaction (`CandidateText`): "Unverified relationship: <kind> <new
    target>", and a note that an admin chose the target (a catalog resource) or named it (no resource). The heading
    and the row in the list take the new line from the reloaded evidence.
15. **A later run keeps the admin's choice.** A Scout run on the resource before the item is decided does not
    supersede that item, nor delete its relationship: the change survives and the item stays decidable.
16. **Refusals change nothing.** One transaction, with the item row locked: 404 for no item; 409 `already-decided`
    for an item no longer open; 409 `not-applicable` for an item that is not a relationship candidate; 409
    `duplicate` when the resource already has a relationship of that kind to that target; 400 for two or none of the
    three, an unknown resource, the resource itself, a non-https address, or a type that is not a tool or a model. A
    resource added for a refused change is rolled back with it.
17. **Audit.** `CHANGE_RELATIONSHIP_TARGET` with the item id and `LINKED <resource id>` or `RENAMED`, and
    `ADD_RESOURCE` with the new resource's id and `ADDED`. Refused changes are not recorded: nothing changed.
18. **Back office.** The evidence of an open relationship candidate has "Change the target": find the resource with
    the visitor search (as the home examples do), add it, or give another name. Errors say why; the form stays open.

### A resource candidate edited before it is accepted: title, type and tags
19. **One call edits an open resource candidate:** `PUT /admin/review-items/{id}/candidate` with at least one of
    `title` (1 to 300 characters, one line), `type` (any candidate type: products and tools, MODEL, DATASET,
    ARTICLE, NEWS, EVENT; never COURSE or OTHER) and `tags` (at most 12, tag-shaped). What is left out stays as the
    collector wrote it. It answers the item, with its new reason line. The item stays open.
20. **The edit is marked in the details** (`edited`: the fields the admin changed).
    - When another collector finds the page later ([0013](../scout/0013-discovery.md) §8 and
      §17), it joins the candidate's finders. It does not change an edited
      type or edited tags, and never the title.
    - Accepting indexes what the admin wrote. A type the admin set need not be one a finder declares; it must still be
      a candidate type. When the page was listed meanwhile (as a resource or as a source record of one), accepting
      adds nothing: the item is closed as ACCEPTED with the note "Already listed as <title>.", and the review page says
      so and links to the listed resource. The edits are not applied; the admin makes them on the listed page.
    - The accepted record keeps the signals its collector stated (stars, last push, archived, fork:
      [0013](../scout/0013-discovery.md) §9). An edit does not change them.
    - The accepted record keeps the list of edited fields in its extra (never shown). Who edited is in the audit
      trail, not in the catalog.
21. **No lost update.**
    - The form sends back the details it showed (`expectedDetails`). The edit applies only to those: when the
      candidate changed since the form opened (a collector, another admin), it is 409 `version-conflict`: reload,
      edit again.
    - The edit and a collector's merge both save only when the item still holds the details they read
      (`updateCandidate` compares them). A merge that lost the race is skipped and logged; the next run of that
      collector joins it again.
    - The new details and the `EDIT_CANDIDATE` entry are saved in one transaction: no edit without its audit entry.
    - Accepting locks the item's row first (`ReviewQueue.lock`), so what is indexed is what is decided. An edit or a
      merge at the same moment waits, then finds the item no longer open, and saves nothing.
22. **Refusals change nothing:** 400 for no field or an unusable value, 404 for no item, 409 `already-decided` for an
    item no longer open, 409 `not-applicable` for another kind.
23. **Audit:** `EDIT_CANDIDATE` with the item id and `EDITED <fields>`.
24. **Back office:** "Edit the candidate" in the details of an open resource candidate: title, type and tags (comma
    separated). Only changed fields are sent. The details then say "Changed by an admin: title, type."

### A curated field: an admin sets a resource's own value, over every source and every run
25. **Set a value:** `PUT /admin/resources/{id}/fields/{field}` with `value`, for the fields that have a column of
    their own: type, pricing, license, openSource, techLevel and audience. The value must be one the field takes
    (an SPDX id for a licence; no OTHER type, no UNKNOWN audience).
    - The value is written to the resource's column, and the field is listed in `resource.curated_fields`.
    - A CURATED, VERIFIED field assertion says where it came from; the page shows "Curated by Helper2".
    - The AI's values of the field are rejected.
26. **It wins everywhere, and stays.**
    - No record changes it: the catalog upsert keeps a listed column, whatever the origin of the record
      (`JdbcCatalogWriter`).
    - No run changes it: the value goes into the run's stated facts, so the evidence ladder does not read more for
      it, and the validator drops a model value for it without an error, so no review item opens for it.
    - The resource page shows it before a source conflict, the source's value and the enrichment (`FieldStates`).
      Filters and ranking read the column. A card is merged from the source records, then takes the curated
      columns over them and drops the sources' conflict on those fields (`ResourceCards`), so the page card shows it.
      In a search, the catalog's hit carries the curated fields, and the merge takes its values over the other
      sources' with no conflict (`ResultMerger`), so the search cards show it too.
    - The events seed overwrites only the fields it asserts (eventDates, eventFormat), so it leaves an admin's value
      on the same event alone (`scripts/industry-events-seed.mjs`).
27. **Drop the AI's value:** `POST /admin/resources/{id}/fields/{field}/drop`, for any field the AI writes, use case
    and platforms too. It rejects the AI's values, as the code re-check does
    ([0014](../scout/0014-evidence-gated-enrichment.md)). A value a source states or an admin set stays. The page then shows the
    source's value, or "Not stated": a rejected estimate is not shown (`FieldStates`).
28. **Audit:** `SET_FIELD` (`SET <field>`) and `DROP_FIELD` (`DROPPED <field> <count>`), with the resource id.
29. **Back office:** next to each field of an enrichment check, "Set the value" (a list of the field's values, or the
    licence id) and "Drop the AI value". Both read the evidence again.

### Code

- `backend/apps/backoffice/src/main/java/ai/helper2/admin/` — `package-info.java`, `web/AdminController.java`, `web/OverviewController.java`,
  `web/EnrichmentQueueController.java`, `web/SettingsController.java`, `web/AdminWebApp.java`, `usecase/GetOverview.java`,
  `usecase/ListEnrichmentQueue.java`, `usecase/GetEffectiveSettings.java` (its own groups and the public app's),
  `usecase/ResolveReview.java` (accepts a drafted example through `examples/usecase/AcceptExampleDraft.java`),
  `config/AdminUseCaseConfiguration.java` (wiring, the transaction of `ResolveReview`), `config/SpaProperties.java`.
- The typed draft: `admin/web/ReviewItemMapper.java`; `backend/libs/core/src/main/java/ai/helper2/examples/usecase/ReadExampleDraft.java`,
  `examples/internal/ReviewQueueDrafts.java` (the only reader of the draft's JSON), `examples/config/ExamplesBackOfficeConfiguration.java`;
  `api/admin.yaml` (`ReviewItem.draft`, `ReviewItem.aiContent`, `DraftedExample`); `admin/web/ReviewController.java` (the
  evidence view's marker); `frontend/src/admin/ReviewItemDetails.tsx`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/settings/` — `Setting.java`, `SettingsGroup.java`; each module's group in
  `platform/llm/LlmConfiguration.java`, `guide/internal/GuideConfiguration.java`,
  `search/internal/SearchSettingsConfiguration.java`, `scout/internal/ScoutConfiguration.java`,
  `sources/config/scout/CollectorsConfiguration.java` (back office), `setup/internal/SetupConfiguration.java`,
  `examples/internal/ExamplesBackOfficeAdapters.java` (back office). The public app's groups in the database:
  `platform/settings/PublicAppSettings.java`, `JdbcPublicAppSettings.java`, `PublicAppSettingsConfiguration.java`;
  `backend/apps/public/src/main/java/ai/helper2/site/settings/PublishSettingsAtStartup.java`; `V1__schema.sql`
  (`public_app_setting`); `api/admin.yaml` (`ConfigGroup.app`, `PublicAppPublication`).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`resource_enrichment_backlog_idx`, `resource_enrichment_value_idx`); `catalog/enrichment/EnrichmentCandidates.java`
  and `catalog/internal/JdbcEnrichmentCandidates.java` (`pending`, `pendingCount`, `backlog`, `standing`, `prioritisedCount`),
  `catalog/internal/BacklogRule.java`.
- Queue actions: `enrichment_queue_control` in `V1__schema.sql`; `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EnrichmentQueue.kt`
  (`QueuePlace`), `scout/Scout.kt` (`EnrichmentQueueControl`, `UnitOfWork`), `scout/usecase/RequeueEnrichment.kt`,
  `SkipEnrichment.kt`, `PrioritiseEnrichment.kt`; `scout/internal/JdbcEnrichmentQueueControl.java`,
  `ScoutConfiguration.java`; `admin/web/EnrichmentQueueController.java`; `platform/ProblemType.java` (`NOT_APPLICABLE`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/BudgetLedger.java`, `CallCounter.java`, `DailyBudget.java`,
  `RedisCallCounter.java` (`current`), `LlmConfiguration.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`openCountsByKind`),
  `scout/internal/CatalogEnrichmentStore.java` and `catalog/internal/JdbcEnrichmentCandidates.java` (`pendingCount`), `search/config/MicrometerSearchMetrics.java`.
- `backend/libs/core/src/main/java/ai/helper2/examples/usecase/CountHomeExamples.java`, `backend/libs/core/src/main/kotlin/ai/helper2/examples/PoolCounts.kt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/GetReviewEvidence.java`, `admin/web/ReviewController.java`,
  `catalog/ReviewEvidence.java`, `catalog/internal/JdbcReviewEvidence.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/BulkDecisionRule.java`, `admin/usecase/ResolveReviewItems.java`,
  `admin/usecase/ResolveReview.java` and `catalog/usecase/ResolveReviewItem.java` (the batch in the audit outcome).
- `frontend/src/admin/` — `AdminLayout.tsx`, `AdminOverview.tsx`, `ReviewQueuePage.tsx`, `ReviewEvidence.tsx`,
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
  `admin/usecase/GetEffectiveSettingsTest.java`, `platform/settings/JdbcPublicAppSettingsIT.java`,
  `site/settings/PublishSettingsAtStartupTest.java`,
  `architecture/ModularityTest.java` (the table-owner exception; in the back office, admin names no internal class),
  `examples/usecase/ReadExampleDraftTest.java`, `api/HomeExamplesApiIT.java` (the typed draft and its markers, checked against the contract),
  `frontend/src/admin/ExamplesPage.test.tsx`, `admin/config/SpaPropertiesBindingTest.java`,
  `frontend/src/admin/Admin.test.tsx`, `EnrichmentQueuePage.test.tsx`, `ReviewEvidence.test.tsx`, `ReviewBulk.test.tsx`,
  `SettingsPage.test.tsx`, `src/a11y.test.tsx`.

The target of a relationship candidate:
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/ChangeRelationshipTarget.java`, `AddCuratedResource.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcRelationshipTargets.java`, `CuratedTypes.java`,
  `JdbcResources.java` (`atUrl`), `CatalogConfiguration.java` (the curated source's types).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml`
  (`RelationshipTargetChange`, `NewResource`, audit actions).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`relationship.target_origin`, the audit actions).
- `frontend/src/admin/RelationshipTarget.tsx`, `ReviewEvidence.tsx`.
- Tests: `RelationshipTargetApiIT`, `RelationshipTarget.test.tsx`, `ReviewEvidence.test.tsx`.

A candidate edit:
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EditCandidate.kt`; `IndexAcceptedCandidate.kt`
  (`CandidateRules`, `typeChosenByAdmin`).
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` (`edit`, `merge`, `read`, `reason`),
  `ReviewQueueCandidateItems.java`, `ReviewQueueCandidateReview.java`, `ScoutConfiguration.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`updateCandidate` compares the details, `lock`);
  `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` (locks an accepted item first).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml` (`CandidateEdit`,
  `CandidateType`, audit action).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (the `EDIT_CANDIDATE` audit action).
- `frontend/src/admin/CandidateEditForm.tsx`, `ReviewItemDetails.tsx`, `ReviewQueuePage.tsx`.
- Tests: `EditCandidateTest`, `ResourceCandidateJsonTest`, `IndexAcceptedCandidateTest`, `CandidateEditApiIT`,
  `CandidateEditForm.test.tsx`.

A curated field:
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/CurateField.java`, `catalog/internal/JdbcCuratedFields.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcResources.java` (the upsert keeps a curated column),
  `ResourceCards.java` and `backend/libs/core/src/main/kotlin/ai/helper2/search/Model.kt` (`withCurated`: the card takes it;
  `SourceHit.curatedFields`), `search/ResultMerger.kt` (a search merge takes it), `CatalogSearchSource.java`,
  `JdbcEnrichmentCandidates.java`, `catalog/enrichment/EnrichmentCandidates.java` (the curated values).
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java`,
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` (`curatedFields`), `EvidenceValidator.kt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml` (`ResourceField`,
  `ResourceFieldValue`, audit actions).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`resource.curated_fields`); `scripts/industry-events-seed.mjs` and the
  events seed it writes.
- `frontend/src/features/resource/FieldStates.tsx`, `frontend/src/admin/FieldCuration.tsx`,
  `ReviewEvidence.tsx`.
- Tests: `CuratedFieldApiIT`, `CatalogEnrichmentStoreTest`, `EvidenceValidatorWritingTest`, `ResourcePage.test.tsx`,
  `FieldCuration.test.tsx`.

## Alternatives considered

### The back office
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
- A catalog-owned control table, written through a catalog port (like the re-run request of ADR 0014) — no exception in
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

### The review actions
- **Pick the target when accepting** — one call, but accept then does two things, and a wrong target is VERIFIED at
  once with no second look.
- **Add a resource from its own admin page** — a second place to keep, and the admin leaves the item to do it.
- **Let a name-only target create a resource later** — no address, so no page to enrich and no way to avoid a
  duplicate. Every new resource needs its address.
- **Edit while accepting** — one call, but a mistake is in the catalog at once, and a collector's merge in between
  would be lost silently.
- **Edit the resource after accepting** — the right tool for a resource's own facts (§25), but it lets a wrong
  type reach the catalog and the enrichment first.
- **Lock the item while an admin edits** — long locks for a form left open; the compare-and-save keeps both writers
  safe without one. The only lock is the short one of an accept, for the length of its transaction.
- **Only a CURATED assertion, read when shown** — the page could show it, but search cards, filters and ranking read
  the columns: the wrong value would still be found and filtered on.
- **A new data origin per field** — the row-level origin rank cannot say "this one field"; a list of curated fields
  can, and costs one array column.
- **Reject the whole check and wait for a better run** — the next run reads the same pages and can say the same
  thing.

## Consequences

### The back office
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
- The public app's groups are as old as its last start: with several replicas, the last one that started wrote them.
  During a rolling deploy the page can show the old or the new version for a minute; the line under the group names it.
- The public app's database role can write `public_app_setting` and nothing else of the back office. Text it writes
  reaches the admin page only as text, with the lengths and kinds the table checks.
- The admin module cannot reach into another module's internals: the test fails on the first import, in the PR that
  adds it.
- A drafted example's JSON has one reader, the examples module. A change to its format changes no admin code and no
  page.

### The review actions
- An ABOUT link ([0014](../scout/0014-evidence-gated-enrichment.md)) can always end on a real page, even for a model
  launched yesterday.
- A resource an admin adds starts with only a name and an address. Until the Scout enriches it, its card is thin.
- Two writes for one decision: change the target, then accept. The UI keeps them next to each other.
- A retargeted relationship stays open until a person decides it, whatever later runs say. A run that proposes the
  same kind and name adds nothing; one that proposes the model's first name again opens a new item next to it.
- A post a category found can be accepted as the article or news item it is, under a clean title; its enrichment
  then names what it is about ([0014](../scout/0014-evidence-gated-enrichment.md)).
- An edited type holds for the candidate, not for the resource. Once in the catalog, the resource's type comes from
  all its records, the most specific first: a later record from another source can still change it. A resource's
  own value is set with §25.
- The admin fixes a wrong fact once, and it stays fixed: the Engadget item can be set to NEWS, its pricing dropped.
- A curated value is not AI-written: no AI label, no `data-ai-generated` mark.
- Limits of the curated field:
  - platforms and use cases are labels rebuilt from the records: they can be dropped, not set;
  - there is no "back to the sources" yet: an admin sets another value instead;
  - the editorial seeds (`R__curated_seed`, `R__courses`, the event seeds) write their own columns when they run
    again, over an admin's value on the same entry. They are editorial too; a seed change is a deliberate edit.

### Security and privacy (STRIDE)

The back office as a whole: ADMIN only, on the admin host behind the VPN, with `__Host-` cookies and CSRF
([0036](../identity/0036-identity-and-the-admin-host.md)); every write is in the append-only audit trail; the review page shows every
text from a source or a model as plain text. The typed draft (§11) adds no data: the same four values the item held,
read by code, with no new store; its marker names only the provider and the model.

The target of a relationship candidate (§12 to §18):
- **Spoofing / elevation**: ADMIN only, on the admin host behind the VPN, with CSRF
  ([0036](../identity/0036-identity-and-the-admin-host.md)); the same rules as every back-office write.
- **Tampering**: the address must be https and canonical; the name and the type are checked; a link to itself is
  refused. The resource is added through the same catalog write as every record.
- **Repudiation**: every change and every added resource is in the append-only audit trail.
- **Information disclosure**: none new. A resource holds a public name and address; the audit holds the
  administrator's GitHub id, as for every action. The Privacy page names these records, and that a relationship
  keeps whether a person chose its target (kept as long as the relationship).
- **Denial of service**: one admin, one row per change; no outbound call (the page is read later by the Scout,
  inside its caps).

A candidate edit (§19 to §24):
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0036](../identity/0036-identity-and-the-admin-host.md)).
- **Tampering**: the values are checked here and again on accept; the details are rewritten as JSON by code, never
  taken as text from the client.
- **Repudiation**: every edit is in the append-only audit trail.
- **Information disclosure**: none new. The record's extra lists field names only, no administrator id. The Privacy
  page says the record keeps which fields a person changed, for as long as the resource is listed.
- **Denial of service**: one admin, one row per edit.

A curated field (§25 to §29):
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0036](../identity/0036-identity-and-the-admin-host.md)).
- **Tampering**: the field is one of a fixed list, the column is the code's constant, the value is checked against
  the field's values; SQL takes the value only as a parameter.
- **Repudiation**: every change is in the append-only audit trail.
- **Information disclosure**: none new; the values are public facts of a public resource. The catalog holds no
  administrator id.
- **Denial of service**: one admin, one row per change.

### AI architecture check

The back office calls no model itself. It shows model output and lets a person decide on it.

| Layer | State |
|---|---|
| Model | Covered: no model call here. The drafts ([0028](../search/0028-home-examples.md)), the enrichment ([0014](../scout/0014-evidence-gated-enrichment.md)) and the classifier run in their own modules, on NVIDIA models. |
| Tool & API | Covered: the admin API is contract first (`api/admin.yaml`); the admin calls other modules only through their use cases and ports, checked by `ModularityTest`. |
| Knowledge & RAG | Covered: the evidence view reads the quotes through the item's link, never from its `details` (§7). |
| Data | Covered: the admin owns no table; a drafted example's JSON has one reader (§11). |
| Guardrails | Covered: a person decides every item; bulk accept only where nothing new is published (§9); a curated value is checked against the field's values (§25). |
| Observability | Covered: every decision, edit and curated value is in the audit trail; model ids are shown with each AI value and each draft. Deferred: Grafana panels for the back office (open point). |
| Human-in-the-Loop | Covered: this record is the human loop. Decisions are final; there is no undo (§9). |
| Agent Framework, Orchestration, Memory, Browser & Computer Use, Evaluation | Not used here. |
| Deployment, Security & Identity, Cost & Reliability | Covered by [0036](../identity/0036-identity-and-the-admin-host.md) and [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md); the overview is computed at most once a minute (§3). |

### Known limits

- Strict enrichment rules reject some correct values; the review queue grows with every partial run and needs a person. A relationship target two catalog resources share by title waits for a person, and its review item says only that the target is not a known resource. The oldest-waiting gauge is set when the Scout reads a batch, not at each scrape.
- The overview's enrichment backlog count reads every resource that may wait (0.65 s at 250 000 synthetic resources, cached a minute; 1.7 to 1.9 s with 37 500 REJECTED resources and the queue decisions). A queue page where no REJECTED resource is due for a retry walks every REJECTED resource (0.4 s at 250 000), and so does the out-of-retries view (1.1 to 1.6 s with 37 500 REJECTED); a stored "last run failed" flag would let the index skip them. A budget that is never built (no model configured) is not listed.
- The catalog's backlog query reads the Scout's `enrichment_queue_control` (a listed exception in the table-owner test). If it starts to hurt, the catalog can own the table behind a port, as for the re-run request of ADR 0014. ([ADR 0038](0038-back-office-and-review.md) §8)
- Privacy check (privacy-legal): the skip reason in the enrichment queue is free text, like the review note. The form says "No personal data" and the code drops control characters, but nothing stops a name. Confirm whether the Privacy page must list the skip reason, and whether it needs a retention rule (today it stays until a requeue, a new skip or the resource's delete). ([ADR 0038](0038-back-office-and-review.md) §8)
- Runtime settings: a change reaches the other replicas within about 20 s, so for those seconds two replicas can cut off at two limits. The Settings page's read-only groups show the startup value for every other setting. The public app's groups on that page are the ones its last started replica wrote ([ADR 0038](0038-back-office-and-review.md) §10): during a rolling deploy they can be the old or the new version's, and two replicas with different env values show only the last one's. A change has no Grafana annotation yet, so a dashboard does not show when a limit moved. A limit raised for one day stays until someone resets it: an expiry ("until midnight UTC") would need one more column. The six maximums are first guesses (2.5 to 5 times today's prod values); revisit them after a month of use. A new `H2_` default with an override in place changes nothing until the override is reset.

### Open points

- The table-owner exception of the enrichment queue.
- The skip reason is not on the Privacy page.
- The back office has no Grafana panels.

## Diagrams

The back office: [flow](../../diagrams/flow-0038-back-office.svg) · [sequence](../../diagrams/sequence-0038-back-office.svg) ·
the target of a relationship candidate: [flow](../../diagrams/flow-0038-relationship-target-in-review.svg) · [sequence](../../diagrams/sequence-0038-relationship-target-in-review.svg) ·
a candidate edit: [flow](../../diagrams/flow-0038-candidate-edit-in-review.svg) · [sequence](../../diagrams/sequence-0038-candidate-edit-in-review.svg) ·
a curated field: [flow](../../diagrams/flow-0038-curated-field.svg) · [sequence](../../diagrams/sequence-0038-curated-field.svg) ·
the settings of both apps: [flow](../../diagrams/flow-0038-settings-of-both-apps.svg) · [sequence](../../diagrams/sequence-0038-settings-of-both-apps.svg)

## Backlog

- Features: [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82) Review queue and actions (bulk, evidence, fix before accept), [F6.3](https://github.com/silvioincalza/helper2.ai/issues/83) Enrichment queue: requeue, skip, move to top.
- Open issues that change this decision:
  - [#304](https://github.com/silvioincalza/helper2.ai/issues/304) S6.2.6 The review page to read a typed resource candidate
  - [#308](https://github.com/silvioincalza/helper2.ai/issues/308) S6.5.1 Every setting on `/admin/settings`
  - [#309](https://github.com/silvioincalza/helper2.ai/issues/309) S6.6.1 To edit the curated list in the back office
