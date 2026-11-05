package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentQueueControl
import ai.helper2.scout.QueueOutcome
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.util.UUID

/**
 * An administrator takes a waiting resource out of the enrichment queue until it is requeued (ADR
 * 0045 §7), with an optional reason of at most [MAX_REASON] characters. A move to the top is
 * dropped. The audit entry's outcome is SKIPPED, or why the action did not apply; it commits with
 * the change.
 */
class SkipEnrichment(
  private val backlog: EnrichmentBacklog,
  private val control: EnrichmentQueueControl,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  companion object {
    const val MAX_REASON = 200
  }

  /** A blank reason counts as none; one longer than [MAX_REASON] is refused before anything. */
  fun skip(adminId: String, resourceId: UUID, reason: String?): QueueOutcome {
    val note = reason?.trim()?.ifEmpty { null }
    require(note == null || note.length <= MAX_REASON) {
      "reason must be at most $MAX_REASON characters"
    }
    return unitOfWork.run {
      val outcome = backlog.place(resourceId)?.skip() ?: QueueOutcome.NOT_FOUND
      if (outcome == QueueOutcome.DONE) {
        control.skip(resourceId, note, clock.instant())
      }
      audit.record(
        adminId,
        AuditTrail.Action.SKIP_ENRICHMENT,
        resourceId.toString(),
        recorded(outcome, "SKIPPED"),
      )
      outcome
    }
  }
}
