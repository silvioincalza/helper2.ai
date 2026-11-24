package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.RunSummary

class StartScoutRun(private val runner: RunScout, private val audit: AuditTrail) {
  fun start(adminId: String, sourceId: String?): List<RunSummary>? {
    val runs = runner.tryStart(sourceId)
    audit.record(
      adminId,
      AuditTrail.Action.START_SCOUT_RUN,
      sourceId,
      if (runs == null) "BUSY" else "STARTED",
    )
    return runs
  }
}
