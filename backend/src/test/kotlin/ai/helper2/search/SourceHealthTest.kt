package ai.helper2.search

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
}
