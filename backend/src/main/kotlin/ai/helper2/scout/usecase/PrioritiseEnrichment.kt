package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentQueueControl
import ai.helper2.scout.QueueOutcome
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.util.UUID

/**
 * An administrator moves a waiting resource to the top of the enrichment queue (ADR 0045 §7). It is
 * taken in the Scout's next batch, within the daily enrichment cap. The audit entry's outcome is
 * PRIORITISED, or why the action did not apply; it commits with the change. The top is locked
 * before the count, so two admins at the same second cannot pass the limit together.
 */
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

/** The audit entry's outcome: what was done, or why the action did not apply. */
internal fun recorded(outcome: QueueOutcome, done: String): String =
  if (outcome == QueueOutcome.DONE) done else outcome.name
