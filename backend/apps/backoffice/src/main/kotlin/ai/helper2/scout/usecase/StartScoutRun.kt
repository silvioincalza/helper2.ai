package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.RunSummary

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
