# 0018 — AI Resource Scout runner: schedule, intervals, cursors, backlog, failure isolation, cluster-wide run lock

**Status.** Accepted (2026-10-27). **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0006](../foundation/0006-outbound-http.md), [0014](../guide/0014-llm-integration.md)

## Context
Live search only shows what sources return for one query. To answer from its own index (keyword and vector search,
"Today", "Next events") Helper2 must collect resources in the background. Sources differ: some must be
polled hourly, some cost credits per call, some are large and must be read in pages over several runs. A failing
source must not stop the others, a crash must not lose or skip items, and two runs must never work on the same items
at the same time. Prod runs 3 replicas ([0027](../operations/0027-runtime-topology.md)), each with its own timer.

## Decision
1. **Schedule.** `ScoutSchedule` exists only when `H2_SCOUT_ENABLED=true` (default false; the `demo` profile forces
   false). Each replica checks the schedule (a tick): first `H2_SCOUT_INITIAL_DELAY` (20 s) after startup, then every `H2_SCOUT_TICK`
   (1 h; fixed delay). A tick calls `RunScout.tryRunScheduled`. The Scout runs when the last scheduled run ended at
   least `H2_SCOUT_EVERY` (15 min; Helm chart `P1D`) ago, or when no scheduled run ever ended. The end time is one row
   in the PostgreSQL table `scout_schedule` (port `ScheduledRunStore`, adapter `JdbcScoutStore`), so every replica
   sees it. An admin can start a run with `POST /api/v1/admin/scout-runs` (optionally one `sourceId`, use case
   `RunScout.tryStart`). The run starts on its own virtual thread; as soon as it holds the run lock and has saved one
   `scout_run` row per selected collector (`RUNNING`, or `SKIPPED` when disabled), the call answers 202 with those
   rows. The run then collects in the background; the admin page follows it by polling `GET /api/v1/admin/scout-runs`
   every 3 s while a row is `RUNNING`. Admin runs are not recorded in `scout_schedule`.
2. **One run at a time, across all replicas.** `RunScout` runs through the port `RunLock`; the adapter
   `ScoutConfiguration.ClusterRunLock` uses `platform.ClusterLock` named `scout-run`. `ClusterLock` wraps ShedLock 7.10.1
   (`JdbcTemplateLockProvider`, one row per lock in the PostgreSQL table `shedlock`, expiry computed with database
   time, `locked_by` = pod hostname). A task runs only while its replica holds the lock; otherwise it is skipped, never
   queued. If a run is in progress on any replica, a tick returns `BUSY` and the admin call gets 409 Conflict.
   - The due check, the run and the new end time all happen under `scout-run`. So replicas that tick in the same
     period run the Scout once (`RAN`); the others see `NOT_DUE` or `BUSY`. A `BUSY` tick changes nothing, and the
     next tick tries again.
   - `scout-run` is a lease of 2 minutes that ShedLock's `KeepAliveLockProvider` (`platform.ClusterLocks.keptAlive`)
     extends by another lease every minute while the run lives, however long the run takes. It is held at least 0,
     so the admin button works right after a run. A pod that dies mid-run stops extending it, and the lock frees
     within one lease.
   - **Interrupted runs.** A `scout_run` row still `RUNNING` while no run holds `scout-run` belongs to a replica that
     stopped mid-run. At startup (`ApplicationReadyEvent`) and at the start of every run, under `scout-run`,
     `ScoutStore.markInterrupted` closes such rows as `FAILED` "Interrupted: the replica running it stopped" and sets
     their source's last outcome to `FAILED`. A replica that starts while another one runs the Scout leaves the rows
     alone; the next run closes them.
3. **Per collector, isolated.** For each `Collector` (port in `scout/Ports.kt`):
   - disabled → `SKIPPED` "Disabled in configuration";
   - the `RUNNING` row saved when the run started is followed by `collect(checkpoint, resumeCursor, max-items-per-run 500)`;
   - not due (its `RunInterval` has not passed and no pass is unfinished) → `SKIPPED` "Not due yet (run interval)";
   - otherwise the hits are merged and written with `CatalogIndex.index`, which returns the resources whose source
     records changed;
   - the batch's candidates (pages a person must accept first, such as `CourseCollector`'s course pages) go to the
     port `CandidateReview.propose(sourceId, candidates)`; the adapter `ReviewQueueCandidateReview` opens one
     `COURSE_CANDIDATE` review item per page not proposed before. Collectors never call the review list themselves;
   - any exception, including a failing review list → `FAILED` "Collection failed: <class>", the cursor is not moved,
     and the next collector runs. The next pass proposes the same pages again; the review list opens none twice.
4. **Cursor and checkpoint** (`ScoutCursor`, table `source_cursor`). The checkpoint is the start time of the last
   complete pass; collectors use it as `updated_since` or `start_date`. When a pass stops at the item limit, the
   collector returns `nextCursor`: it is saved with the pass start, and the checkpoint stays. When the pass completes,
   the checkpoint becomes the pass start, so items updated during a long pass are read again next time. Re-running a
   pass upserts the same records with the same fingerprints, so it is idempotent.
5. **Intervals per collector** (`RunInterval`): Model Context Protocol (MCP) Registry `H2_SCOUT_MCP_INTERVAL` (1 h), Hugging Face trending
   `H2_SCOUT_HF_INTERVAL` (1 day), Hugging Face provider orgs `H2_SCOUT_HF_ORGS_INTERVAL` (1 day), awesome lists
   `H2_SCOUT_AWESOME_INTERVAL` (1 day), ai-deadlines `H2_SCOUT_AI_DEADLINES_INTERVAL` (1 day), Tavily
   `H2_SCOUT_TAVILY_INTERVAL` (5 days), courses `H2_SCOUT_COURSES_INTERVAL` (7 days). Details in
   [0020](0020-scout-collectors.md).
6. **Enrichment budget per run.** One `EnrichmentBudget` of `H2_SCOUT_MAX_ENRICHMENTS` (20) is shared by all
   collectors and the backlog. Changed resources with a source description of at least 40 characters are enriched
   while the budget lasts ([0019](0019-evidence-gated-enrichment.md)). The budget counts model calls: an `UNCHANGED`
   outcome gives its slot back, and the resource is not tried again in the same run. An `LLM_UNAVAILABLE` outcome
   (provider down or daily cap spent) sets the budget to 0 for the rest of the run. An enrichment error is counted in the run message;
   it never turns a collected run into `FAILED`.
7. **Backlog.** After the collectors, the rest of the budget goes to `EnrichmentBacklog.pending`: resources never
   enriched, enriched while no model was available, or whose last attempt failed (fewer than 3 failures). New
   discoveries first. They are enriched one at a time, so a slot given back by an `UNCHANGED` resource goes to the
   next one.
8. **Records and metrics.** Each run is a `scout_run` row (`GET /api/v1/admin/scout-runs` lists the last 50); a store
   failure is logged and does not stop collection. Meters: `helper2.scout.cycle`, `helper2.scout.run` (source, status),
   `helper2.scout.items` (seen, changed, queued), gauge `helper2.scout.last.success`, counter `helper2.scout.enrichment`.

## Consequences
- One broken source shows as `FAILED` in its own row; the others keep the index fresh.
- A crash mid-pass loses nothing: the next run resumes from the saved cursor or repeats the pass.
- Credit-priced sources (Tavily) run rarely. The 1-hour MCP Registry interval follows the registry's polling advice,
  but the Helm chart starts a run only once a day (`H2_SCOUT_EVERY=P1D`), so in the cluster it runs daily.
- Enrichment cost per run is bounded (20 calls by default, 200 in the Helm chart, and 300 per day), and the backlog catches up after an outage or a day with a spent budget.
- The admin call answers in milliseconds; the run's progress is the run listing, so no request is held for the
  length of a run and no extra status endpoint exists.
- 3 replicas, no double work: one run at a time, one scheduled run per period. The lock and the end time are a few
  PostgreSQL statements per run, not a new service.
- A restart or a deploy does not add a run: a new pod runs the Scout only if a run is due. A first start with an
  empty `scout_schedule` runs at once.
- A tick that meets an admin run is not lost: the next tick runs the Scout if it is still due. Admin runs do not
  move the schedule.
- A scheduled run can start up to one `H2_SCOUT_TICK` (1 h) after it is due. With `P1D` this is small; with a short
  `H2_SCOUT_EVERY` (local: 15 min), set a shorter tick (`.env.example` and the demo use 5 min).
- A pod killed mid-run holds `scout-run` for at most one lease (2 min). Its rows show `FAILED` once a replica starts
  or the next run begins; its end time is not recorded, so the next tick runs the Scout again.
- One renewal thread per replica extends the lease. A pause of that thread longer than one lease (a long GC, an
  unreachable database) lets another replica start a second run; a renewal every half lease leaves a wide margin.

## Alternatives considered
- Spring Batch or a job framework — far more setup than a loop over a few collectors.
- Separate worker service — a second deployable and image for a small load.
- Cron per collector — more schedules to run; the per-collector interval gives the same effect in one loop.
- Fixed delay of `H2_SCOUT_EVERY` per replica, with a second lock held for most of a period — each replica has its
  own phase, a tick that met an admin run lost its period, and the lock length had to be guessed. The end time in
  the database gives one schedule for all replicas.
- Checkpoint = time of the last item seen — misses items updated during a long pass; the pass start does not.
- Lock in Redis (`SET NX PX`) — Redis is a disposable cache with `allkeys-lru`, so a lock could be evicted.
- PostgreSQL advisory lock — tied to one open connection for the whole run (hours); no expiry if the pod hangs.
- A fixed lock sized for the longest run (collectors plus every enrichment timing out) — a pod that dies mid-run
  blocks the Scout for hours. A short lease kept alive frees the lock within minutes.
- Admin run on a job queue or broker (Spring Modulith outbox, a message broker) — the run already has durable state
  in `scout_run` and one lock; a queue adds a component and gives nothing the lock does not.
- A separate run-status endpoint — `GET /api/v1/admin/scout-runs` already returns each row's status.
- Kubernetes Lease or leader election — needs API access for the app, and does not work outside the cluster.

## Code
- `backend/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt` — `RunScout` (`tryStart`, `tryRunScheduled`,
  `closeInterruptedRuns`), `EnrichmentBudget`,
  `ScoutCursor`, `ScheduledRun`, ports (`ScheduledRunStore`).
- `backend/src/main/kotlin/ai/helper2/scout/Ports.kt` — `Collector`, `CollectedBatch` (hits and candidates), `CandidateReview`.
- `backend/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java` — candidates as review items.
- `backend/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java` — `ScoutSchedule`, `scoutRunLock`, `ClusterRunLock`,
  `RUN_THREADS`, `closeInterruptedScoutRuns`.
- `backend/src/main/java/ai/helper2/platform/ClusterLock.java`, `ClusterLocks.java`, `ClusterLockConfiguration.java`; tables `shedlock` and
  `scout_schedule` in `V1__schema.sql`.
- `backend/src/test/kotlin/ai/helper2/scout/internal/ScoutRunLockIT.kt` — two replicas, one run; one scheduled run per
  period; a tick that meets an admin run runs at the next tick; the lease is renewed while a run lives; runs left
  `RUNNING` are closed at startup.
- `backend/src/test/java/ai/helper2/scout/ScoutPipelineIT.java` — 202 with the `RUNNING` row, polled to its end; 409
  while another replica holds the lock.
- `frontend/src/pages/AdminPage.tsx` — "Run now", polling of the run listing.
- `backend/src/main/java/ai/helper2/scout/internal/JdbcScoutStore.java` — `source_cursor`, `scout_run`, `scout_schedule`.
- `backend/src/main/java/ai/helper2/scout/web/AdminController.java` — `startScoutRun`, `listScoutRuns`.
- `backend/src/main/java/ai/helper2/sources/support/RunInterval.java`.

## Diagrams
[flow](../../diagrams/flow-0018-scout-runner.svg) · [sequence](../../diagrams/sequence-0018-scout-runner.svg)
