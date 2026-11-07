package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentQueueControl
import ai.helper2.scout.QueueOutcome
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.util.UUID

/**
 * An administrator puts a resource back into the enrichment queue (ADR 0026 (part was 0045) §7): a
 * skip is undone, and for a resource out of retries only the FAILED runs from now on count against
 * the cap. It takes its own place in the backlog again, within the daily enrichment cap. The audit
 * entry's outcome is REQUEUED_RESET when the retries start again, REQUEUED otherwise, or why the
 * action did not apply; it commits with the change.
 */
class RequeueEnrichment(
  private val backlog: EnrichmentBacklog,
  private val control: EnrichmentQueueControl,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  fun requeue(adminId: String, resourceId: UUID): QueueOutcome = unitOfWork.run {
    val place = backlog.place(resourceId)
    val outcome = place?.requeue() ?: QueueOutcome.NOT_FOUND
    if (place != null && outcome == QueueOutcome.DONE) {
      control.requeue(resourceId, resetFailures = place.outOfRetries, at = clock.instant())
    }
    audit.record(
      adminId,
      AuditTrail.Action.REQUEUE_ENRICHMENT,
      resourceId.toString(),
      recorded(outcome, if (place?.outOfRetries == true) "REQUEUED_RESET" else "REQUEUED"),
    )
    outcome
  }
}
