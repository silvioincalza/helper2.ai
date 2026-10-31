package ai.helper2.search

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceHealthTest {

    private fun searched(id: String, outcome: SourceOutcome, at: Instant) =
        SourceStatus(descriptor(id), outcome, ServedFrom.LIVE, 0, null, at)

    private val earlier = Instant.parse("2026-10-26T10:00:00Z")
    private val later = Instant.parse("2026-10-26T11:00:00Z")

    @Test
    fun `a Scout-only collector shows its recorded outcome`() {
        val recorded = SourceHealth.Entry(SourceOutcome.SUCCEEDED, earlier)

        assertEquals(recorded, SourceHealth().lastFor("mcp-registry", recorded))
    }

    @Test
    fun `a source never searched nor run has no outcome`() {
        assertNull(SourceHealth().lastFor("mcp-registry", null))
    }

    @Test
    fun `a newer recorded outcome wins over an older search`() {
        val health = SourceHealth()
        health.sourceCompleted(searched("github", SourceOutcome.SUCCEEDED, earlier))
        val recorded = SourceHealth.Entry(SourceOutcome.FAILED, later)

        assertEquals(recorded, health.lastFor("github", recorded))
    }

    @Test
    fun `a newer search wins over an older recorded outcome`() {
        val health = SourceHealth()
        health.sourceCompleted(searched("github", SourceOutcome.TIMED_OUT, later))

        assertEquals(SourceHealth.Entry(SourceOutcome.TIMED_OUT, later),
            health.lastFor("github", SourceHealth.Entry(SourceOutcome.SUCCEEDED, earlier)))
    }

    @Test
    fun `a source disabled in configuration leaves no outcome, whatever its message says`() {
        val health = SourceHealth()
        health.sourceCompleted(SourceStatus(descriptor("tavily"), SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, later,
            message = "Turned off", skipReason = SkipReason.DISABLED))

        assertNull(health.lastFor("tavily", null))
    }

    @Test
    fun `a source paused after a rate limit keeps its skipped outcome`() {
        val health = SourceHealth()
        health.sourceCompleted(SourceStatus(descriptor("github"), SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, later,
            message = "Paused", skipReason = SkipReason.RATE_LIMIT_PAUSE))

        assertEquals(SourceHealth.Entry(SourceOutcome.SKIPPED, later), health.lastFor("github", null))
    }

    private fun called(id: String, millis: Long, outcome: SourceOutcome = SourceOutcome.SUCCEEDED,
                       servedFrom: ServedFrom = ServedFrom.LIVE) =
        SourceStatus(descriptor(id), outcome, servedFrom, 0, Duration.ofMillis(millis), later)

    @Test
    fun `the response time is the median of the last calls`() {
        val health = SourceHealth()
        listOf(900L, 100L, 300L).forEach { health.sourceCompleted(called("github", it)) }

        assertEquals(Duration.ofMillis(300), health.responseTime("github"))
    }

    @Test
    fun `failed and timed-out calls count, cache hits and skipped calls do not`() {
        val health = SourceHealth()
        health.sourceCompleted(called("github", 200))
        health.sourceCompleted(called("github", 2500, SourceOutcome.TIMED_OUT, ServedFrom.STALE_CACHE))
        health.sourceCompleted(called("github", 2400, SourceOutcome.FAILED, ServedFrom.NONE))
        health.sourceCompleted(called("github", 0, servedFrom = ServedFrom.CACHE))
        health.sourceCompleted(SourceStatus(descriptor("github"), SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, later,
            skipReason = SkipReason.CIRCUIT_OPEN))

        assertEquals(Duration.ofMillis(2400), health.responseTime("github"))
    }

    @Test
    fun `only the last twenty calls count`() {
        val health = SourceHealth()
        repeat(30) { health.sourceCompleted(called("github", 5000)) }
        repeat(20) { health.sourceCompleted(called("github", 100)) }

        assertEquals(Duration.ofMillis(100), health.responseTime("github"))
    }

    @Test
    fun `a source not called on this replica has no response time`() {
        val health = SourceHealth()
        health.sourceCompleted(called("github", 0, servedFrom = ServedFrom.CACHE))

        assertNull(health.responseTime("github"))
        assertNull(health.responseTime("npm"))
    }
}
