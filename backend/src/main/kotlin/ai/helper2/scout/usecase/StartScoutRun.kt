package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.RunSummary

/**
 * An administrator starts a Scout run of every collector (or only [sourceId]). The audit entry follows the run's
 * records: STARTED once the run holds the lock and has saved them, BUSY when another run held the lock.
 */
class StartScoutRun(private val runner: RunScout, private val audit: AuditTrail) {

    /** The started run's records, or null while another run holds the lock. */
    fun start(adminId: String, sourceId: String?): List<RunSummary>? {
        val runs = runner.tryStart(sourceId)
        audit.record(adminId, AuditTrail.Action.START_SCOUT_RUN, sourceId, if (runs == null) "BUSY" else "STARTED")
        return runs
    }
}
