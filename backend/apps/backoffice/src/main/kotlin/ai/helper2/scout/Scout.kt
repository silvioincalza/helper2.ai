package ai.helper2.scout

import ai.helper2.catalog.KnownTarget
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
  val enrichmentsQueued: Int,
  val message: String?,
)

data class ScoutCursor(
  val checkpoint: String?,
  val resumeCursor: String?,
  val passStartedAt: String?,
) {
  val candidatesHeldBack: Boolean
    get() = resumeCursor == HELD_BACK

  val ownResume: String?
    get() = resumeCursor.takeUnless { candidatesHeldBack }

  companion object {
    private const val HELD_BACK = "held-back"

    @JvmStatic
    fun candidatesHeldBack(checkpoint: String?, passStartedAt: String?): ScoutCursor =
      ScoutCursor(checkpoint, HELD_BACK, passStartedAt)
  }
}

interface ScoutStore {
  fun cursor(sourceId: String): ScoutCursor?

  fun saveCursor(sourceId: String, cursor: ScoutCursor)

  fun save(run: RunSummary)

  fun recent(limit: Int): List<RunSummary>

  fun markInterrupted(at: Instant): Int
}

interface ScheduledRunStore {
  fun lastScheduledRunEnd(): Instant?

  fun scheduledRunEnded(at: Instant)
}

fun interface RunCalendar {
  fun nextRunAfter(lastEnd: Instant): Instant
}

enum class ScheduledRun {
  RAN,
  NOT_DUE,
  BUSY,
}

fun interface CatalogIndex {
  fun index(results: List<MergedResult>, sourceId: String): List<UUID>
}

fun interface WithdrawnRecords {
  fun forget(sourceId: String, externalIds: List<String>): Withdrawal
}

data class Withdrawal(val records: Int, val resources: Int, val keptResources: List<UUID>) {
  companion object {
    val NONE = Withdrawal(0, 0, emptyList())
  }
}

fun interface KnownTargets {
  fun linkOpenCandidates(): Int
}

fun interface KnownTargetProposals {
  fun propose(): Proposal
}

fun interface ListIndex {
  fun replace(lists: List<ListText>)
}

interface RunLock {
  fun <T : Any> runExclusively(task: Supplier<T>): T?
}

interface EnrichmentBacklog {
  fun pending(limit: Int): List<UUID>

  fun pendingCount(): Long

  fun queue(
    view: EnrichmentCandidates.View,
    after: EnrichmentCandidates.BacklogCursor?,
    size: Int,
  ): List<EnrichmentCandidates.BacklogEntry>

  fun place(resourceId: UUID): QueuePlace?

  fun prioritisedCount(): Int

  fun eligible(resourceIds: List<UUID>): List<UUID>

  fun requestRun(resourceId: UUID)
}

interface UnitOfWork {
  fun <T : Any> run(work: Supplier<T>): T
}

interface EnrichmentQueueControl {
  fun lockTop()

  fun prioritise(resourceId: UUID, at: Instant)

  fun skip(resourceId: UUID, reason: String?, at: Instant)

  fun requeue(resourceId: UUID, resetFailures: Boolean, at: Instant)
}

interface EnrichmentStore {
  fun load(resourceId: UUID): EnrichmentMaterial?

  fun lastFingerprint(resourceId: UUID): String?

  fun resourcesTitled(titles: Collection<String>, resourceId: UUID): Map<String, UUID>

  fun knownTargets(names: Collection<String>): Map<String, KnownTarget> = emptyMap()

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

  fun recordArchived(recordId: UUID, archived: Boolean, checkedAt: Instant) {}
}
