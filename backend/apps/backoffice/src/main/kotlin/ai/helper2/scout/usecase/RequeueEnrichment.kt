package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentQueueControl
import ai.helper2.scout.QueueOutcome
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.util.UUID

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
