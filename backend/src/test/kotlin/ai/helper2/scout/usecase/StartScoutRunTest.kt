package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.RunLock
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.ScoutStore
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class StartScoutRunTest {

    private class Recorded(val adminId: String, val action: AuditTrail.Action, val targetId: String?, val outcome: String)

    private val audit = mutableListOf<Recorded>()
    private val trail = AuditTrail { adminId, action, targetId, outcome -> audit += Recorded(adminId, action, targetId, outcome) }

    private object NoStore : ScoutStore {
        override fun cursor(sourceId: String): ScoutCursor? = null
        override fun saveCursor(sourceId: String, cursor: ScoutCursor) {}
        override fun save(run: RunSummary) {}
        override fun recent(limit: Int) = emptyList<RunSummary>()
        override fun markInterrupted(at: Instant) = 0
    }

    private fun runner(lock: RunLock) = RunScout(emptyList(), NoStore, CatalogIndex { _, _ -> emptyList() }, null, Clock.systemUTC(), lock)

    private val free = object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
    }
    private val held = object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
    }

    @Test
    fun `a started run is recorded as STARTED for its source`() {
        assertNotNull(StartScoutRun(runner(free), trail).start("1234567", "mcp-registry"))
        val entry = audit.single()
        assertEquals("1234567", entry.adminId)
        assertEquals(AuditTrail.Action.START_SCOUT_RUN, entry.action)
        assertEquals("mcp-registry", entry.targetId)
        assertEquals("STARTED", entry.outcome)
    }

    @Test
    fun `a run refused by the lock is recorded as BUSY`() {
        assertNull(StartScoutRun(runner(held), trail).start("1234567", null))
        assertEquals("BUSY", audit.single().outcome)
        assertNull(audit.single().targetId)
    }
}
