package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.EnrichmentBacklog
import ai.searchavo.scout.EnrichmentQueueControl
import ai.searchavo.scout.QueueOutcome
import ai.searchavo.shared.UnitOfWork
import java.time.Clock
import java.util.UUID

class SkipEnrichment(
  private val backlog: EnrichmentBacklog,
  private val control: EnrichmentQueueControl,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  companion object {
    const val MAX_REASON = 200
    private val CONTROL_CHARACTERS = Regex("\\p{Cc}")
  }

  fun skip(adminId: String, resourceId: UUID, reason: String?): QueueOutcome {
    val note = reason?.replace(CONTROL_CHARACTERS, "")?.trim()?.ifEmpty { null }
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
