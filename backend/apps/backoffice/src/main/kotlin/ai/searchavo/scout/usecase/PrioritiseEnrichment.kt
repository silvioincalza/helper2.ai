package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.EnrichmentBacklog
import ai.searchavo.scout.EnrichmentQueueControl
import ai.searchavo.scout.QueueOutcome
import ai.searchavo.shared.UnitOfWork
import java.time.Clock
import java.util.UUID

class PrioritiseEnrichment(
  private val backlog: EnrichmentBacklog,
  private val control: EnrichmentQueueControl,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  fun prioritise(adminId: String, resourceId: UUID): QueueOutcome = unitOfWork.run {
    control.lockTop()
    val outcome =
      backlog.place(resourceId)?.prioritise(backlog.prioritisedCount()) ?: QueueOutcome.NOT_FOUND
    if (outcome == QueueOutcome.DONE) {
      control.prioritise(resourceId, clock.instant())
    }
    audit.record(
      adminId,
      AuditTrail.Action.PRIORITISE_ENRICHMENT,
      resourceId.toString(),
      recorded(outcome, "PRIORITISED"),
    )
    outcome
  }
}

internal fun recorded(outcome: QueueOutcome, done: String): String =
  if (outcome == QueueOutcome.DONE) done else outcome.name
