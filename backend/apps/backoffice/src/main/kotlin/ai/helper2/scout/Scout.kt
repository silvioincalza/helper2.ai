package ai.helper2.scout

import ai.helper2.catalog.enrichment.EnrichmentCandidates
import ai.helper2.search.MergedResult
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

enum class RunStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
}

data class RunSummary(
  val id: UUID,
  val sourceId: String,
  val status: RunStatus,
  val startedAt: Instant,
  val finishedAt: Instant?,
  val itemsSeen: Int,
  val itemsChanged: Int,
  /**
   * Changed resources of this source the model ran on in this run (whatever it answered). Not
   * counted: resources whose source text did not change, skipped calls (provider down, budget
   * spent) and errors. The name is the contract's.
   */
  val enrichmentsQueued: Int,
  val message: String?,
)

/**
 * Collection position for one source. [checkpoint] = start of the last COMPLETE pass (next
 * `updated_since`). While a pass is unfinished, [resumeCursor] continues it and [passStartedAt]
 * becomes the checkpoint once it completes, so items updated mid-pass are not missed. A pass that
 * failed or was deferred keeps [passStartedAt] with no [resumeCursor]: it is still open.
 */
data class ScoutCursor(
  val checkpoint: String?,
  val resumeCursor: String?,
  val passStartedAt: String?,
)

/** Outbound port: collection cursors and run history. */
interface ScoutStore {
  fun cursor(sourceId: String): ScoutCursor?

  fun saveCursor(sourceId: String, cursor: ScoutCursor)

  fun save(run: RunSummary)

  fun recent(limit: Int): List<RunSummary>

  /** Closes every RUNNING record as FAILED at [at]; returns how many. */
  fun markInterrupted(at: Instant): Int
}

/**
 * Outbound port: when the last scheduled run ended, seen by every replica. Admin runs are not
 * recorded.
 */
interface ScheduledRunStore {
  fun lastScheduledRunEnd(): Instant?

  fun scheduledRunEnded(at: Instant)
}

/**
 * The schedule's calendar: when the next scheduled run is due after one that ended at [lastEnd]. A
 * fixed time of day (cron) in production: a long run or a late tick does not move the next start.
 */
fun interface RunCalendar {
  fun nextRunAfter(lastEnd: Instant): Instant
}

/** What one tick of the schedule did. */
enum class ScheduledRun {
  RAN,

  /** The next run after the last scheduled one is not due yet. */
  NOT_DUE,

  /** Another run (another replica's, or an admin's) holds the lock: the next tick tries again. */
  BUSY,
}

/** Outbound port: the catalog's indexing API. Returns resource ids whose source records changed. */
fun interface CatalogIndex {
  fun index(results: List<MergedResult>, sourceId: String): List<UUID>
}

/**
 * Outbound port: the catalog's search index of curated lists (ADR 0010). Replaces, for each list,
 * what an earlier pass indexed; entries and lists not in the catalog are left out.
 */
fun interface ListIndex {
  fun replace(lists: List<ListText>)
}

/**
 * Outbound port: at most one Scout run at a time across every replica, whether the schedule or the
 * admin button started it (two runs would collect and enrich the same items twice).
 */
interface RunLock {
  /**
   * Runs [task] and returns its result, or returns null without running it while another run holds
   * the lock.
   */
  fun <T : Any> runExclusively(task: Supplier<T>): T?
}

/** Outbound port: which resources are worth sending to the LLM, and which still need it. */
interface EnrichmentBacklog {
  /** Resources that still need enrichment (never run, LLM was down, or a retryable failure). */
  fun pending(limit: Int): List<UUID>

  /** How many resources [pending] would return without a limit; for the back office overview. */
  fun pendingCount(): Long

  /**
   * One page of [view] after [after] (null: from the start); for the back office queue. The WAITING
   * view is the backlog in the order [pending] takes it.
   */
  fun queue(
    view: EnrichmentCandidates.View,
    after: EnrichmentCandidates.BacklogCursor?,
    size: Int,
  ): List<EnrichmentCandidates.BacklogEntry>

  /** Where one resource stands in the queue; null for an unknown resource. */
  fun place(resourceId: UUID): QueuePlace?

  /** How many resources an admin moved to the top still wait for their next run. */
  fun prioritisedCount(): Int

  /**
   * The subset of [resourceIds] with enough source text for the model to cite and not skipped by an
   * admin, in the given order.
   */
  fun eligible(resourceIds: List<UUID>): List<UUID>

  /**
   * Puts [resourceId] back among the resources that still need enrichment, for a later run: a
   * changed resource the run had no slot left for. Its unchanged fingerprint does not skip it (ADR
   * 0023).
   */
  fun requestRun(resourceId: UUID)
}

/**
 * Outbound port: one database transaction, so an admin's change and its audit entry commit together
 * or not at all.
 */
interface UnitOfWork {
  fun <T : Any> run(work: Supplier<T>): T
}

/**
 * Outbound port: an admin's decisions on the enrichment queue (ADR 0026 (part was 0045) §7), kept
 * by the Scout. Each one changes the order only; it never calls the model by itself.
 */
interface EnrichmentQueueControl {
  /**
   * Waits until no other move to the top is in progress, and holds that until the transaction ends,
   * so the count of resources at the top cannot change between the check and the move.
   */
  fun lockTop()

  /** Moves the resource to the top until its next run starts. */
  fun prioritise(resourceId: UUID, at: Instant)

  /** Takes the resource out of the backlog until it is requeued; drops a move to the top. */
  fun skip(resourceId: UUID, reason: String?, at: Instant)

  /**
   * Undoes a skip; with [resetFailures], only FAILED runs after [at] count against the retry cap.
   */
  fun requeue(resourceId: UUID, resetFailures: Boolean, at: Instant)
}

/** Loads evidence material and persists validated enrichment. Implemented over the catalog. */
interface EnrichmentStore {
  /** null when the resource has no retrievable material. */
  fun load(resourceId: UUID): EnrichmentMaterial?

  /**
   * Input fingerprint of the resource's latest finished run that judged the model's answer (any
   * outcome but LLM_UNAVAILABLE and FAILED); null when there is none.
   */
  fun lastFingerprint(resourceId: UUID): String?

  /**
   * Resources other than [resourceId] titled exactly one of [titles] (case-insensitive), by
   * lower-case title.
   */
  fun resourcesTitled(titles: Collection<String>, resourceId: UUID): Map<String, UUID>

  /**
   * [failure] is the short technical reason when no model output was validated (null otherwise).
   * [extraEvidence] names the text the gap round read when the saved result used it
   * ("github-readme:owner/name"), else null.
   */
  fun save(
    resourceId: UUID,
    model: EnrichmentModel,
    inputFingerprint: String,
    startedAt: Instant,
    result: ValidationResult?,
    outcome: EnrichmentOutcome,
    failure: String?,
    extraEvidence: String?,
  )
}
