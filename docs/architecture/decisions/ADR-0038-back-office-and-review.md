# 0038 — Back office and review

**Depends on:** [0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0004](ADR-0004-persistence-and-the-schema.md), [0007](ADR-0007-configuration-secrets-runtime-settings.md), [0011](ADR-0011-scout-runner.md), [0012](ADR-0012-scout-collectors-and-events.md), [0013](ADR-0013-discovery.md), [0014](ADR-0014-evidence-gated-enrichment.md), [0027](ADR-0027-live-web-store-first-daily-quota.md), [0028](ADR-0028-home-examples.md), [0032](ADR-0032-guide-cost-guard-and-answer-cache.md), [0033](ADR-0033-marking-ai-written-text-research-trail.md), [0036](ADR-0036-identity-and-the-admin-host.md), [0043](ADR-0043-runtime-topology-helm-chart-three-deployables.md).

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
  ARTICLE ([0012](ADR-0012-scout-collectors-and-events.md) §14), so a news item it found could never be accepted as NEWS.
- An enrichment check shows what the model said about a resource and the quotes behind it. Sometimes the model is
  wrong and the quote does not help: the price is the subject's, the licence is an old one, the type is a guess. The
  wrong value stayed until a new run, and a new run could say the same thing again. A value a source states could
  not be fixed at all.

## Decision

### The back office
1. **A thin `admin` module.** `ai.helper2.admin` holds the back office's web adapters (`ReviewController`,
   `OverviewController`, `ScoutRunsController`, ...) and use cases that only compose other modules (`GetOverview`). It owns
   no table and no module depends on it. It calls the other modules only through their use cases and ports
   (`catalog.usecase`, `scout.usecase`, `examples.usecase`, `identity`, `platform.settings`), never a class of their
   `internal` package: one rule of the back office's `ModularityTest` checks every admin package, web and config
   included. Its use cases are plain classes, wired in `admin.config`, and the `DOMAIN` rule of `ModularityTest`
   checks them with the catalog, examples, identity, search and Scout use cases they call
   ([0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md)). It reads no other module's storage format: a
   drafted example is read and accepted through the examples use cases `ReadExampleDraft` and `AcceptExampleDraft`
   ([0028](ADR-0028-home-examples.md) §6). Its controllers answer with views the use cases own, never with a
   record of another module's port: `ReviewItemView` (with its own `Status`) and its `ReviewDetails`,
   `GetReviewEvidence.View`, `ListEnrichmentQueue.Entry` and `ListEnrichmentQueue.Section`. The use case copies the
   port record into its view (`ReviewItemViews`), and the controller maps the view to the contract. One rule of the
   back office's `ModularityTest` (`controllersAnswerWithViewsNotPortRecords`) fails when a class of a `web` package
   uses `ReviewQueue.Status`, `ReviewQueue.Item`, `EnrichmentCandidates.View`, `EnrichmentCandidates.BacklogEntry` or
   `ReviewEvidence.Evidence`. Its own classes live in its own packages, never in a library's: the
   place it serves the admin web app from is `admin.config.SpaProperties` (`helper2.spa`).
2. **One page per part.** `/admin` (overview, the landing page), `/admin/review`, `/admin/runs`, `/admin/audit`, each with
   its state in the address, so a number on the overview links to the view that acts on it
   (`/admin/review?kind=USER_REPORT`).
3. **The overview: what needs a person now.** `GET /api/v1/admin/overview` returns counts only: open review items per
   kind (every kind, 0 included), the enrichment backlog (the same rule the Scout takes it by), every model budget with
   today's count and limit, web credits today, the last 10 Scout runs and the failed ones of the last 24 hours, the
   home examples shown and hidden ([0028](ADR-0028-home-examples.md)), the 10 most frequent enrichment validation
   issues of the runs started in the last 7 days (subject and rule, quotes and numbers taken out, counted per run; a
   partial run opens no review item, so this is where the validator is tuned), and a link to Grafana (`H2_ADMIN_GRAFANA_URL`) for trends. It is computed at most once a minute, one computation at a time:
   the backlog count is a full pass, and Refresh must not repeat it. That count gives up after 5 seconds. The Grafana
   link must be an absolute http(s) URL with a host and a path only, or the app does not start: the page puts it in an
   href and every administrator's browser receives it, so it carries no user info, no query and no fragment. The
   refusal names the variable and never repeats its value.
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
   ([0011](ADR-0011-scout-runner.md) §7). The ones an admin moved to the top keep their own order, newest
   discovery first. Each row says why it waits,
   how many runs FAILED, and the last run. The page is a keyset (part, popularity, first discovered, id) behind an
   opaque cursor, not an OFFSET: a deep page costs what the first one does. The overview's backlog count links here.
   The Scout's next batch, the queue and the count share one rule, read in two parts from two partial indexes on the
   same rows (`resource_enrichment_value_idx` for the order by popularity, `resource_enrichment_backlog_idx` for the
   oldest ones and the count): the second part runs only when the first has too few rows. The Scout's batch
   reads one more part first, the products a person accepted, from their decisions (`candidate_decision`,
   [0013](ADR-0013-discovery.md)) ([0014](ADR-0014-evidence-gated-enrichment.md)). Then a quarter of the
   batch (`JdbcEnrichmentCandidates.oldestShare`, at least one) goes to the never-run resources that waited longest,
   oldest first, so a steady flow of popular finds does not starve the others; inside that quarter and in the rest,
   the most popular come first. The gauge
   `helper2.scout.enrichment.oldest_pending.age` (seconds) says how long the oldest never-run resource has waited,
   as of the last batch read. Measured on synthetic
   data, 250 000 resources: the next 20 from 1.4 s to under 1 ms, the count from 1.3 s to 0.65 s. Every backlog read
   gives up after 5 seconds. The queue is admin-only and shows catalog data, no personal data; a cursor
   that is not one of ours (wrong shape, a time outside what the database holds) is a 400.

   Below the queue, **Not about AI by the word rule** (`GET /api/v1/admin/ai-check`, `CheckAiWords`) reads the
   indexed and observed resources by popularity (then id), 500 a page behind an opaque cursor, and lists the ones
   whose title, description and tags fail `Classification.isAiRelated` (through the `AiTopicCheck` port, since the
   back office reaches the source connectors only through ports). Only resources whose every record comes from a
   general source (`AiCheckReport.GENERAL_SOURCES`: GitHub, npm, PyPI, Hacker News, live web, confs.tech) are
   checked: a source that only holds AI (Hugging Face, arXiv, the MCP Registry, the AI news feeds, the discovery and
   SDK collectors) says a resource is about AI, whatever its words. Without this rule staging listed 5,067 of 65,521,
   among them 484 models, 178 papers and 484 news items. The check itself changes nothing; the admin acts row by
   row. Skip: a waiting one leaves the enrichment queue with the reason "Not about AI (word rule)", and a requeue
   from Skipped undoes it. There is no bulk skip: a library's catalog text often has no AI word (the official SDK of
   a vector database), so each row needs a look. Waiting is the backlog's own rule (`EnrichmentBacklog.waiting`: due
   and not skipped, as `SkipEnrichment` checks it), so the panel offers Skip only where Skip succeeds.

   Remove (`POST /api/v1/admin/ai-check/{id}/remove`, `RemoveResource`), on any listed row, after a dialog that asks
   for a reason of 1 to 500 characters: in one transaction the audit keeps `REMOVE_RESOURCE` with the address
   (cut to the 1000 characters an audit text holds) and the reason, `candidate_decision` keeps a `REJECTED` decision for the address's key, as a resource candidate and as a course
   candidate,
   `removed_resource` keeps the key (the same UUID a resource id is made from its address, never the address), and
   the resource goes with what cascades from it: source records, labels, embedding, enrichment runs, review items,
   links from it; a link to it loses its target. Saved items keep their own title and address. From then on the
   catalog writer drops a result whose key is in `removed_resource`, whatever the source and in both apps (the
   public app's role reads the table, never `candidate_decision`), and the review queue proposes no candidate for
   the address. A removal and a catalog write never
   overlap: the removal takes the transaction lock `removed_resource` alone, every write takes it shared
   (`ResourceRemovalLock`), so a write that began first ends before the removal reads the resource, and a write that
   begins later reads the key. An admin who adds the address by hand (`CURATED`) clears the key and the resource comes back.
   Flow and sequence: [flow](../diagrams/flow-0038-remove-resource.svg) ·
   [sequence](../diagrams/sequence-0038-remove-resource.svg).
7. **The evidence view reads the run through the item's link.** `GET /api/v1/admin/review-items/{id}/evidence`
   (ADMIN, back office) answers what an item is about. A review item already links to its enrichment run and, for a
   relationship candidate, to its relationship (`enrichment_run_id`, `relationship_id`). The view follows that link;
   it never reads the evidence from the item's `details`. An item from a run: the run (model, prompt version, outcome, when it
   finished, the README or model card of its gap round), the run's validation errors, and the fields the run stored,
   CONFLICTING first, then UNVERIFIED, VERIFIED and REJECTED. A relationship candidate: the run and the relationship.
   Any other item (a user report, a course or resource candidate, a drafted example): the item only, a drafted
   example with its `draft` (§11). Each field and the
   relationship carry at most 5 quotes, the newest first; a run shows at most 50 fields and 50 errors. The catalog reads
   it (`ReviewEvidence`, a query port, `JdbcReviewEvidence`) in at most 3 statements, whatever the number of fields;
   the admin module only composes it (`GetReviewEvidence`). A new run deletes the old run's AI fields, so an open item's
   run is the current one; an item a later run has closed can show its run with no fields left, and the page says so.
   The values are model output (EU AI Act Art. 50, [0033](ADR-0033-marking-ai-written-text-research-trail.md)): the response has an
   `aiContent` marker with the run's model and the AI-written paths (and a drafted example's, §11), and the page labels each value with its origin in
   words and marks it with `data-ai-generated` and `data-ai-model`. The validation errors quote what the model wrote,
   so the list says "Includes model output" and is marked the same way. A quote that is not in the material it cites
   is kept in its error, with the kind of that material (record, readme or vendor page) and, when another material
   of the run holds it, the kind of that one. Quotes come from the sources: each one says
   "Source quote" and is not marked.
   Every text from a source or a model is plain text on the page; a source address is a link only when it is http(s).
   The review page is a list and the selected item, with keys for a fast review (j/k, a, r, n, ?; [the back office screens](../../mockups/back-office.md)). The keys
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
     (`TableOwnersTest`): the catalog reads a Scout table, read only. I chose it because the order must stay one rule
     for the Scout's next batch, the page and the count. Two copies of the rule would drift apart.
   - Measured on synthetic data (250 000 resources, 37 500 REJECTED, 50 at the top), with `EXPLAIN ANALYZE`: the next 20
     in 2 ms (0.9 ms before; 5 ms in the worst case, 500 skips at the head of the never-run part), the count 1.7 to
     1.9 s (1.4 to 1.65 s before, same data; still cached a minute, with the 5-second limit), a waiting page 1 to 3 ms,
     the skipped page 0.3 ms, the out-of-retries page 1.1 to 1.6 s (it walks every REJECTED resource), one resource's
     place 0.1 ms.
9. **Bulk decisions: each item on its own, accept only where it is safe.** `POST /api/v1/admin/review-items/resolutions`
   takes up to 100 ids, a decision and an optional note (CSRF applies). `ResolveReviewItems` sends each item through the
   one-item decision (`ResolveReview`) in its own unit of work ([0051](ADR-0051-use-cases-return-outcomes.md)
   §5), so each item writes its own audit entry, the same as a single decision, with the batch at the end:
   `ACCEPTED bulk:<batch>`. One item that fails does not undo the others. An accept that cannot add its candidate (it
   cannot be read, or the catalog refuses a confirmed value) stops the batch with that problem, a 400: the items
   before it stay decided, the ones after it are not tried.
   The answer gives one outcome per item, in the order sent: RESOLVED, NOT_OPEN, NOT_FOUND or NOT_ALLOWED. Rejecting is
   allowed for every kind: nothing is added or shown. Accepting is allowed for an enrichment check whose run has no
   CONFLICTING licence, open source or pricing (`BulkDecisionRule`, a pure class; the conflicts come from their own
   query, `ReviewEvidence.conflictingFields`, not from what the evidence view shows), and for a resource or course
   candidate whose newest research ([0048](ADR-0048-review-research-assistant.md)) is DONE with verdict KEEP and no CONFLICT
   value, and that research still describes it: the URL, title, type and tags it saw are the candidate's now (an admin
   edit since then refuses the item). The research read the item, so the admin is not adding something nobody read. Its CONFIRMED changed values go
   into the candidate through `EditCandidate` first, as one Accept with them confirmed would; values only one source or
   no source states stay as they were (`ResearchedAcceptance`). When the candidate edit refuses a value, the item is not
   accepted. Every other kind verifies a relationship, publishes an example or closes a user's report, and a new kind is
   refused until the rule names it. A refused item stays open and is recorded as `NOT_ALLOWED bulk:<batch>`. On the page each open row has a
   box (x picks the selected item); a bar under the list says "4 selected · Accept · Reject · Clear"; Accept and Reject
   ask first in a dialog that repeats the count by kind, with focus on Cancel. The line after says "11 done, 1 failed
   (already decided)", and the items that failed stay picked.
10. **The Settings page: what the app runs with now, read-only.** `GET /api/v1/admin/config` and `/admin/settings` show
    the settings by module: model ids per use, prompt versions, the guide's configuration hash, the daily budgets and
    caps, the Tavily credits and per-feature quotas, the Scout's schedule, and the sources and collectors that are on.
    Each module publishes one `SettingsGroup` bean (`platform.settings`) built from its own typed records, like the
    policy values of [0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md). The back office reads no other module's record and
    never the environment ([0007](ADR-0007-configuration-secrets-runtime-settings.md)).
    It is an allow-list: each setting is listed by hand, through one factory per kind of value (count, cap, flag,
    duration, model, version, schedule, names). There is no factory for a secret. A value with a quote, an `@` or more
    than 500 characters is refused, so a URL with a user or a password cannot pass even by mistake. Each row names the
    `H2_` variable that sets it. The values are bound at startup, so the page is fixed until the next restart.
    Six spending limits are the exception: they change on the same page, at runtime, and their rows show the value
    now ([0007](ADR-0007-configuration-secrets-runtime-settings.md)). The overview says how many of them are changed, with a link
    to the page.
    The guide and the setup guide run only in the public app, so their groups are not beans of the back office. The
    public app writes all its groups to the table `public_app_setting` when it is ready (`PublishSettingsAtStartup`):
    one transaction under an advisory lock deletes the rows and inserts the new ones, with the app's version and the
    time. A write that fails is logged and the app starts all the same. The back office shows its own groups, read now,
    and adds the published groups whose id it does not have; a group both apps have (models, search, http) is the back
    office's own. Each group says which app it is from (`ConfigGroup.app`), and a public app group shows the version
    and the time of that start. Before the public app's first start on a database the page says so, and shows the back
    office's groups only. No call goes between the two apps ([0043](ADR-0043-runtime-topology-helm-chart-three-deployables.md)). A stored
    row was built by a factory in the public app; the platform reads it back through `Setting.published`, which only
    its own package can call, and it passes the same check as a bound value, so a value the page cannot show reads "(hidden)", and
    rows that make no group (a key outside its group) or more than 500 rows read as nothing published. The table holds no personal data and
    has no retention period: each start writes it again.
11. **A drafted example comes typed.** The review item of a drafted example (`EXAMPLE_CANDIDATE`) has `draft`: its
    label, query, path and model, as the examples module reads them from the item (`ReadExampleDraft`, over the
    `DraftQueue` port; [0028](ADR-0028-home-examples.md) §6). Every other kind, and an item whose details hold no
    readable draft, has `draft: null`. One view (`ReviewItemViews`) and one mapping (`ReviewItemMapper`) give the
    item the same way in the list, after a decision or an edit, and in the evidence view, so the browser never parses
    a draft. The label and the query are AI-written (EU AI Act Art. 50,
    [0033](ADR-0033-marking-ai-written-text-research-trail.md)). In the API, an item with a draft has its own
    `aiContent` marker: `fields` names `draft.label` and `draft.query`, `models` the draft's model, `provider` the
    configured model provider; every other item has `aiContent: null`. The evidence view's `aiContent` adds
    `item.draft.label` and `item.draft.query`, also for an item with no run (§7). On the page they are labelled
    "Drafted with AI (model)" and marked `data-ai-generated` and `data-ai-model`.
    - **The details of an item come typed too.** `ReviewItem.details` is a `ReviewDetails` or null: one of three
      shapes, named by `shape` (a `oneOf` with a discriminator in `api/admin.yaml`). The browser reads the generated
      type and parses nothing.
    - `CANDIDATE`: a `RESOURCE_CANDIDATE` or a `COURSE_CANDIDATE` (url, title, type, tags, snippet, `foundBy` as
      source ids, `edited`, `revision`). Scout reads it (`ShownCandidates`, in `ResourceCandidateJson`), also a
      candidate written before `foundBy` existed (its one `sourceId`) and one with a type the catalog no longer has.
    - `KNOWN_TARGET_PROPOSAL`: a `KNOWN_TARGET_CANDIDATE` (name, kind, rule, count, the titles of its examples,
      other names, `edited`). The catalog reads it (`KnownTargetCandidates.shown`, in `KnownTargetProposalJson`).
    - `TEXT`: every other kind (validation errors, why a relationship is not verified, the note of a report). A
      stored value that does not read as its shape is also `TEXT`, as it is stored: a candidate with no https
      address, a proposal with an unknown kind or rule, a draft with a missing value, a course candidate still held
      as text. One such row never fails the list.
    - Null: an item that holds nothing, and a drafted example (its values are in `draft`).
    - What the database holds does not change: `review_item.details` stays the JSON its module writes, and `note`
      the text. Only the answer of the API has a type.

### The target of a relationship candidate: link, add or rename
12. **One call changes the target** of an open relationship candidate:
    `PUT /admin/review-items/{id}/relationship-target`, with exactly one of:
    - `resourceId`: a resource in the catalog; the relationship takes its title;
    - `newResource`: name, official https address and type (a tool or a model: PRODUCT, MODEL, AGENT, PROMPT, SKILL,
      MCP_SERVER, LIBRARY, DATASET); see 13;
    - `title`: another name, with no resource.

    It answers the item's evidence with the new target. The item stays open: accepting it is what makes the link
    VERIFIED ([0014](ADR-0014-evidence-gated-enrichment.md)), so a change can be checked before it counts.
13. **Adding a resource** (`AddCuratedResource`). An address the catalog already has, as a resource or as one of its
    records, gives that resource: never a second one. Otherwise it becomes a record of the `curated` source, with the
    admin's name and type, no description, data origin CURATED and enrichment NOT_RUN: the next Scout run enriches it
    from its own pages ([0014](ADR-0014-evidence-gated-enrichment.md)). The curated source now lists these types too.
14. **The target is the admin's, the kind stays the model's.** The relationship keeps its origin; its own
    `target_origin` becomes CURATED (`targetOrigin` in the API). The review evidence marks `relationship.kind` as
    AI-written, not `relationship.target` ([0033](ADR-0033-marking-ai-written-text-research-trail.md)). The resource page keeps the
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

### One decision per target name
A relationship candidate is open because its target is not a known resource; its quotes already passed
([0014](ADR-0014-evidence-gated-enrichment.md)). Most names come back for many resources (on prod, Cursor for more
than a hundred), so the question is the same for each of them: what is this name?

- **Groups.** `GET /admin/relationship-targets` lists the open relationship candidates grouped by the known-target key
  of their target (`KnownTarget.KEY_OF_TITLE_SQL`: lower case, spaces folded, a trailing 's dropped), the largest
  group first, with the name most of them write, the count, the relationship kinds and up to three resources that
  name it.
- **One decision.** `POST /admin/relationship-targets/decision` with the key and LINK (a catalog resource), VENDOR,
  SERVICE, TOOL or REJECT. LINK runs §12 on each item and accepts it: the quote already names the target, the admin answers
  which resource the name is, so the check §12 waits for is the one the admin just made. VENDOR, SERVICE or TOOL adds the
  name as a known target ([0019](ADR-0019-known-targets-vendors-and-services.md)) and closes the items of this
  batch (SUPERSEDED, relationship `VERIFIED` with the target's kind); other names the list already matches wait for
  their own decision or the Scout's run. REJECT rejects each item and its relationship.
- **Each item on its own**, as in §9: one unit of work per item (`DecideRelationshipItem`,
  [0051](ADR-0051-use-cases-return-outcomes.md) §5) holds the item's lock from the
  target change to the decision, so a LINK changes the target and accepts the item together or not at all: an item
  decided between the two is the outcome `LinkUndone`, and its target change is rolled back. The group has no
  transaction of its own. Each item
  has its own audit entry, `<decision> bulk:<batch>` (`SUPERSEDED bulk:<batch>` for VENDOR, SERVICE and TOOL). An item
  decided meanwhile, one whose resource already has this target, or the target resource itself, is skipped and stays
  open. At most 1000 items per call, the oldest first; the answer's `left` counts the items of the name still open
  after the call.
- **Back office.** The review page opens the kind "Relationship candidate" by target name; "Each item" shows them one
  by one as before. A group spans every category, so the category filter is not shown there. When a decision empties
  the last page, the list moves back to the last page that has groups.

### A resource or course candidate edited before it is accepted
19. **One call edits an open resource or course candidate:** `PUT /admin/review-items/{id}/candidate` with at least
    one of `title` (1 to 300 characters, one line), `type` (any candidate type: products and tools, MODEL, DATASET,
    ARTICLE, NEWS, EVENT, COURSE; never OTHER) and `tags` (at most 12, tag-shaped), or a value the admin confirms:
    `canonicalUrl` (https), `pricing`, `openSource`, `license` (SPDX), `audience`, `techLevel`, `description` (1 to
    4000 characters, with `descriptionWrittenByAi`), `language` and `publishedAt` (a date, not in the future). The
    values follow the catalog's rules (`EvidenceValidator.allows`). What is left out stays as the collector wrote it.
    It answers the item, with its new reason line. The item stays open.
    - A course candidate holds the same structured candidate as a resource candidate, with type COURSE and its
      platform (`ReviewQueueCandidateReview`). Accepting it indexes the course like a resource candidate, with every
      confirmed value. `V2026.11.6.5__course_candidate_details.sql` turns the open course candidates held as text
      into structured ones; a decided one keeps its text.
20. **The edit is marked in the details** (`edited`: the fields the admin changed).
    - When another collector finds the page later ([0013](ADR-0013-discovery.md) §8 and
      §17), it joins the candidate's finders. It does not change an edited
      type or edited tags, and never the title.
    - Accepting indexes what the admin wrote. A type the admin set need not be one a finder declares; it must still be
      a candidate type. A confirmed canonical URL is the page it is indexed at, and the listed check uses it. Every
      other confirmed value becomes a curated value of the new resource (§25), with its own `SET_FIELD` entry: a
      description keeps `AI_SUMMARY` when the model wrote it, else `CURATED`; a published date is a day. When the
      catalog refuses a confirmed value, the answer is a 400 that names the field and the reason, nothing is added and
      the item stays open; the page shows the sentence. When the page was listed meanwhile (as a resource or as a source record of one), accepting
      adds nothing: the item is closed as ACCEPTED with the note "Already listed as <title>.", and the review page says
      so and links to the listed resource. The edits are not applied; the admin makes them on the listed page.
    - The accepted record keeps the signals its collector stated (stars, last push, archived, fork:
      [0013](ADR-0013-discovery.md) §9). An edit does not change them.
    - The accepted record keeps the list of edited fields in its extra (never shown). Who edited is in the audit
      trail, not in the catalog.
21. **No lost update.**
    - The form sends back the revision of the details it showed (`expectedRevision`). The revision is the SHA-256
      of the stored details (`CandidateRevision`), so it changes with any change to them. The contract takes 64
      lower-case hex characters and nothing else (400). The page sends no edit for a candidate it has no revision
      for: it says the candidate cannot be read, and the item stays open. The edit applies only to
      that revision: when the candidate changed since the form opened (a collector, another admin), it is 409
      `version-conflict`: reload, edit again.
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
    their own: type, pricing, license, openSource, techLevel, audience, language, publishedAt and description. The
    value must be one the field takes (an SPDX id for a licence; no OTHER type, no UNKNOWN audience; a catalog
    language; a date `YYYY-MM-DD`, not in the future; a description of 1 to 4000 characters, stored as `CURATED`).
    A value the field cannot take is a 400 whose `detail` names the field and the reason ("This value is not allowed
    for pricing.", "The published date must be YYYY-MM-DD."), never the value; the page shows it under the field.
    - The value is written to the resource's column, and the field is listed in `resource.curated_fields`.
    - Accepting a candidate also curates its description, language and published date
      (`V2026.11.6.4__curated_description_language_published.sql`); no source or run changes them after.
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
    and platforms too. Language, published date and description are no field the AI writes: a 400 that names the field. It rejects the AI's values, as the code re-check does
    ([0014](ADR-0014-evidence-gated-enrichment.md)). A value a source states or an admin set stays. The page then shows the
    source's value, or "Not stated": a rejected estimate is not shown (`FieldStates`).
28. **Audit:** `SET_FIELD` (`SET <field>`) and `DROP_FIELD` (`DROPPED <field> <count>`), with the resource id.
    Every `SET_FIELD` holds the value before (with "(curated)" when a person had set it, "(none)" when it was empty)
    and the value after, each cut at the 1000 characters an audit text holds; every path that curates a field goes through `CurateField`.
29. **Back office:** next to each field of an enrichment check, "Set the value" (a list of the field's values, the
    licence id, a date, or a text box for a description) and "Drop the AI value". Both read the evidence again.

### Code

- `backend/apps/backoffice/src/main/java/ai/helper2/admin/` — `package-info.java`, `web/ReviewController.java`, `web/OverviewController.java`,
  `web/EnrichmentQueueController.java`, `web/SettingsController.java`, `web/AdminWebApp.java`, `usecase/GetOverview.java`, `catalog/internal/JdbcValidationIssues.java`,
  `usecase/ListEnrichmentQueue.java`, `usecase/GetEffectiveSettings.java` (its own groups and the public app's),
  `usecase/ResolveReview.java` (accepts a drafted example through `examples/usecase/AcceptExampleDraft.java`),
  `usecase/ListReviewQueue.java`, `usecase/ShowReviewItem.java`, `usecase/DecideReviewItem.java` (the review
  actions that answer with a view),
  `web/ReviewProblems.java` (the problem of an accept that adds nothing),
  `config/AdminUseCaseConfiguration.java` (wiring), `config/SpaProperties.java`.
- The views and the typed details: `admin/usecase/ReviewItemView.java`, `ReviewDetails.java`, `ReviewItemViews.java`;
  `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/ShownCandidate.kt` (`ShownCandidates`,
  `CandidateRevision`), `scout/internal/ResourceCandidateJson.java` (`shown`);
  `backend/libs/core/src/main/java/ai/helper2/catalog/KnownTargetCandidates.java` (`shown`),
  `catalog/internal/KnownTargetProposalJson.java`; `api/admin.yaml` (`ReviewDetails` and its three shapes);
  `backend/apps/backoffice/src/test/java/ai/helper2/architecture/ModularityTest.java`
  (`controllersAnswerWithViewsNotPortRecords`), `api/ReviewItemDetailsApiIT.java` (a stored row of each kind).
- The typed draft: `admin/web/ReviewItemMapper.java`; `backend/libs/core/src/main/java/ai/helper2/examples/usecase/ReadExampleDraft.java`,
  `examples/internal/ReviewQueueDrafts.java` (the only reader of the draft's JSON),
  `backend/apps/backoffice/src/main/java/ai/helper2/examples/config/ExamplesBackOfficeConfiguration.java`;
  `api/admin.yaml` (`ReviewItem.draft`, `ReviewItem.aiContent`, `DraftedExample`); `admin/web/ReviewController.java` (the
  evidence view's marker); `frontend/src/admin/ReviewItemDetails.tsx`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/settings/` — `Setting.java`, `SettingsGroup.java`; each module's group in
  `platform/llm/internal/LlmConfiguration.java`, `guide/internal/GuideConfiguration.java`,
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
  (`QueuePlace`), `scout/Scout.kt` (`EnrichmentQueueControl`),
  `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/UnitOfWork.kt`, `scout/usecase/RequeueEnrichment.kt`,
  `SkipEnrichment.kt`, `PrioritiseEnrichment.kt`; `scout/internal/JdbcEnrichmentQueueControl.java`,
  `ScoutConfiguration.java`; `admin/web/EnrichmentQueueController.java`; `platform/ProblemType.java` (`NOT_APPLICABLE`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/BudgetLedger.java`, `CallCounter.java`, `DailyBudget.java`,
  `internal/RedisCallCounter.java` (`current`), `internal/LlmConfiguration.java`.
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
  `architecture/ModularityTest.java` (in the back office, admin names no internal class),
  `backend/architecture/src/test/java/ai/helper2/architecture/TableOwnersTest.java` (the table-owner exception),
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
- One decision per target name: `admin/usecase/DecideRelationshipTargetGroup.java`, `DecideRelationshipItem.java`,
  `admin/web/RelationshipTargetsController.java`, `frontend/src/admin/RelationshipGroups.tsx`; tests
  `RelationshipTargetGroupsApiIT`, `RelationshipGroups.test.tsx`.

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
- The review kind as the discriminator of `details` — two kinds share the candidate shape, three kinds are text, and a
  stored value that does not read must still be shown under any kind. The kind cannot say which shape came; `shape`
  can, with one value per schema.
- A typed column in the database for the details — a migration and a new writer for every module that writes them,
  for a page one person reads. The API types the answer; the stored JSON keeps its one writer per kind.
- The stored details as the version of an edit — the browser would still carry the JSON it must not read. A hash of
  them is as exact and says nothing.
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
  Candidates are accepted in bulk only when the research read them and kept them.
- Bulk accept of researched candidates as the Scout found them — fast, but it drops the values the research confirmed
  and the admin would confirm one by one anyway.
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
  `adminBundle.test.ts` keeps the shared client and the public translations free of them. The back office refuses
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
- The details of a candidate and of a proposal have one reader each, in the module that writes them. A new value in
  the stored JSON reaches the page only when the contract names it; the page cannot guess a field.
- A port record can change without a change to a controller: the use case's view takes the difference. The cost is
  one copy of each record. The parts of the evidence (`ReviewEvidence.Run`, `Field`, `Relationship`, `Quote`) and the
  enums `ReviewQueue.Kind`, `EnrichmentCandidates.State` and `Waiting` are not copied: the views hold them as they
  are.
- The API no longer gives the key of a proposal or what each finder said about a candidate (`foundBy` is the source
  ids): the page showed neither.

### The review actions
- An ABOUT link ([0014](ADR-0014-evidence-gated-enrichment.md)) can always end on a real page, even for a model
  launched yesterday.
- A resource an admin adds starts with only a name and an address. Until the Scout enriches it, its card is thin.
- Two writes for one decision: change the target, then accept. The UI keeps them next to each other.
- A retargeted relationship stays open until a person decides it, whatever later runs say. A run that proposes the
  same kind and name adds nothing; one that proposes the model's first name again opens a new item next to it.
- A post a category found can be accepted as the article or news item it is, under a clean title; its enrichment
  then names what it is about ([0014](ADR-0014-evidence-gated-enrichment.md)).
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
([0036](ADR-0036-identity-and-the-admin-host.md)); every write is in the append-only audit trail; the review page shows every
text from a source or a model as plain text. The typed draft (§11) adds no data: the same four values the item held,
read by code, with no new store; its marker names only the provider and the model.

The target of a relationship candidate (§12 to §18):
- **Spoofing / elevation**: ADMIN only, on the admin host behind the VPN, with CSRF
  ([0036](ADR-0036-identity-and-the-admin-host.md)); the same rules as every back-office write.
- **Tampering**: the address must be https and canonical; the name and the type are checked; a link to itself is
  refused. The resource is added through the same catalog write as every record.
- **Repudiation**: every change and every added resource is in the append-only audit trail.
- **Information disclosure**: none new. A resource holds a public name and address; the audit holds the
  administrator's GitHub id, as for every action. The Privacy page names these records, and that a relationship
  keeps whether a person chose its target (kept as long as the relationship).
- **Denial of service**: one admin, one row per change; no outbound call (the page is read later by the Scout,
  inside its caps).

A candidate edit (§19 to §24):
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0036](ADR-0036-identity-and-the-admin-host.md)).
- **Tampering**: the values are checked here and again on accept; the details are rewritten as JSON by code, never
  taken as text from the client.
- **Repudiation**: every edit is in the append-only audit trail.
- **Information disclosure**: none new. The record's extra lists field names only, no administrator id. The Privacy
  page says the record keeps which fields a person changed, for as long as the resource is listed.
- **Denial of service**: one admin, one row per edit.

A curated field (§25 to §29):
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0036](ADR-0036-identity-and-the-admin-host.md)).
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
| Model | Covered: no model call here. The drafts ([0028](ADR-0028-home-examples.md)), the enrichment ([0014](ADR-0014-evidence-gated-enrichment.md)) and the classifier run in their own modules, on NVIDIA models. |
| Tool & API | Covered: the admin API is contract first (`api/admin.yaml`); the admin calls other modules only through their use cases and ports, checked by `ModularityTest`. |
| Knowledge & RAG | Covered: the evidence view reads the quotes through the item's link, never from its `details` (§7). |
| Data | Covered: the admin owns no table; a drafted example's JSON has one reader (§11). |
| Guardrails | Covered: a person decides every item; bulk accept only where nothing new is published (§9); a curated value is checked against the field's values (§25). |
| Observability | Covered: every decision, edit and curated value is in the audit trail; model ids are shown with each AI value and each draft. Deferred: Grafana panels for the back office (open point). |
| Human-in-the-Loop | Covered: this record is the human loop. Decisions are final; there is no undo (§9). |
| Agent Framework, Orchestration, Memory, Browser & Computer Use, Evaluation | Not used here. |
| Deployment, Security & Identity, Cost & Reliability | Covered by [0036](ADR-0036-identity-and-the-admin-host.md) and [0043](ADR-0043-runtime-topology-helm-chart-three-deployables.md); the overview is computed at most once a minute (§3). |

### Known limits

- Strict enrichment rules reject some correct values; the errors of a `PARTIAL` run open no review item, only its `CONFLICTING` fields and `UNVERIFIED` relationships do ([0014](ADR-0014-evidence-gated-enrichment.md)). A relationship target two catalog resources share by title waits for a person, and its review item says only that the target is not a known resource. The oldest-waiting gauge is set when the Scout reads a batch, not at each scrape.
- The overview's enrichment backlog count reads every resource that may wait (0.65 s at 250 000 synthetic resources, cached a minute; 1.7 to 1.9 s with 37 500 REJECTED resources and the queue decisions). A queue page where no REJECTED resource is due for a retry walks every REJECTED resource (0.4 s at 250 000), and so does the out-of-retries view (1.1 to 1.6 s with 37 500 REJECTED); a stored "last run failed" flag would let the index skip them. A budget that is never built (no model configured) is not listed.
- The catalog's backlog query reads the Scout's `enrichment_queue_control` (a listed exception in the table-owner test). If it starts to hurt, the catalog can own the table behind a port, as for the re-run request of ADR 0014. ([ADR 0038](ADR-0038-back-office-and-review.md) §8)
- Privacy check (privacy-legal): the skip reason in the enrichment queue is free text, like the review note. The form says "No personal data" and the code drops control characters, but nothing stops a name. Confirm whether the Privacy page must list the skip reason, and whether it needs a retention rule (today it stays until a requeue, a new skip or the resource's delete). ([ADR 0038](ADR-0038-back-office-and-review.md) §8)
- Runtime settings: a change reaches the other replicas within about 20 s, so for those seconds two replicas can cut off at two limits. The Settings page's read-only groups show the startup value for every other setting. The public app's groups on that page are the ones its last started replica wrote ([ADR 0038](ADR-0038-back-office-and-review.md) §10): during a rolling deploy they can be the old or the new version's, and two replicas with different env values show only the last one's. A change has no Grafana annotation yet, so a dashboard does not show when a limit moved. A limit raised for one day stays until someone resets it: an expiry ("until midnight UTC") would need one more column. The six maximums are first guesses (2.5 to 5 times today's prod values); revisit them after a month of use. A new `H2_` default with an override in place changes nothing until the override is reset.

### Open points

- The table-owner exception of the enrichment queue.
- The skip reason is not on the Privacy page.
- The back office has no Grafana panels.

## Diagrams

The back office: [flow](../diagrams/flow-0038-back-office.svg) · [sequence](../diagrams/sequence-0038-back-office.svg) ·
the target of a relationship candidate: [flow](../diagrams/flow-0038-relationship-target-in-review.svg) · [sequence](../diagrams/sequence-0038-relationship-target-in-review.svg) ·
one decision per target name: [flow](../diagrams/flow-0038-relationship-groups.svg) · [sequence](../diagrams/sequence-0038-relationship-groups.svg) ·
a candidate edit: [flow](../diagrams/flow-0038-candidate-edit-in-review.svg) · [sequence](../diagrams/sequence-0038-candidate-edit-in-review.svg) ·
a curated field: [flow](../diagrams/flow-0038-curated-field.svg) · [sequence](../diagrams/sequence-0038-curated-field.svg) ·
a removed resource: [flow](../diagrams/flow-0038-remove-resource.svg) · [sequence](../diagrams/sequence-0038-remove-resource.svg) ·
the settings of both apps: [flow](../diagrams/flow-0038-settings-of-both-apps.svg) · [sequence](../diagrams/sequence-0038-settings-of-both-apps.svg)

## Backlog

- Features: [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82) Review queue and actions (bulk, evidence, fix before accept), [F6.3](https://github.com/silvioincalza/helper2.ai/issues/83) Enrichment queue: requeue, skip, move to top.
- Open issues that change this decision:
  - [#308](https://github.com/silvioincalza/helper2.ai/issues/308) S6.5.1 Every setting on `/admin/settings`
  - [#309](https://github.com/silvioincalza/helper2.ai/issues/309) S6.6.1 To edit the curated list in the back office
