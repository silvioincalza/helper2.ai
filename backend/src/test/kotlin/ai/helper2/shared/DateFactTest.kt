package ai.helper2.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateFactTest {

    private val retrievedAt = Instant.parse("2026-10-22T10:00:00Z")

    @Test
    fun `missing value is unknown not guessed`() {
        val fact = DateFact.fromSource(null, "github", retrievedAt)
        assertEquals(DateStatus.UNKNOWN, fact.status)
        assertEquals(DatePrecision.UNKNOWN, fact.precision)
        assertNull(fact.value)
    }

    @Test
    fun `source instant is known and attributed`() {
        val fact = DateFact.fromSource(Instant.parse("2025-01-02T03:04:05Z"), "github", retrievedAt)
        assertEquals(DateStatus.KNOWN, fact.status)
        assertEquals(DatePrecision.INSTANT, fact.precision)
        assertEquals("github", fact.sourceId)
    }

    @Test
    fun `date in the future relative to retrieval is rejected as unknown`() {
        val fact = DateFact.fromSource(Instant.parse("2027-01-01T00:00:00Z"), "npm", retrievedAt)
        assertEquals(DateStatus.UNKNOWN, fact.status)
    }

    @Test
    fun `implausibly old dates are rejected`() {
        assertEquals(DateStatus.UNKNOWN, DateFact.fromSource(Instant.EPOCH, "npm", retrievedAt).status)
    }

    @Test
    fun `earliest picks the minimum known value and keeps others as alternatives`() {
        val a = DateFact.fromSource(Instant.parse("2024-01-01T00:00:00Z"), "npm", retrievedAt)
        val b = DateFact.fromSource(Instant.parse("2023-06-01T00:00:00Z"), "github", retrievedAt)
        val merged = DateFact.earliest(listOf(a, DateFact.unknown(), b))
        assertEquals(Instant.parse("2023-06-01T00:00:00Z"), merged.value)
        assertEquals("github", merged.sourceId)
        assertEquals(listOf(SourcedValue("2024-01-01T00:00:00Z", "npm")), merged.alternatives)
    }

    @Test
    fun `two sources stating different days conflict, the chosen value stays`() {
        val a = DateFact.fromSource(Instant.parse("2024-01-01T00:00:00Z"), "npm", retrievedAt)
        val b = DateFact.fromSource(Instant.parse("2023-06-01T00:00:00Z"), "github", retrievedAt)
        val earliest = DateFact.earliest(listOf(a, b))
        val latest = DateFact.latest(listOf(a, b))
        assertEquals(DateStatus.CONFLICTING, earliest.status)
        assertEquals(Instant.parse("2023-06-01T00:00:00Z"), earliest.value)
        assertEquals(DateStatus.CONFLICTING, latest.status)
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), latest.value)
    }

    @Test
    fun `the same day, one source, or a last change is no conflict`() {
        val morning = DateFact.fromSource(Instant.parse("2024-01-01T08:00:00Z"), "npm", retrievedAt)
        val evening = DateFact.fromSource(Instant.parse("2024-01-01T20:00:00Z"), "github", retrievedAt)
        val later = DateFact.fromSource(Instant.parse("2024-03-01T00:00:00Z"), "npm", retrievedAt)
        val other = DateFact.fromSource(Instant.parse("2025-03-01T00:00:00Z"), "github", retrievedAt)
        assertEquals(DateStatus.KNOWN, DateFact.earliest(listOf(morning, evening)).status)
        assertEquals(DateStatus.KNOWN, DateFact.earliest(listOf(morning, later)).status)
        assertEquals(DateStatus.KNOWN, DateFact.lastChange(listOf(later, other)).status)
        assertEquals(Instant.parse("2025-03-01T00:00:00Z"), DateFact.lastChange(listOf(later, other)).value)
    }

    @Test
    fun `latest of only unknowns is unknown`() {
        assertEquals(DateStatus.UNKNOWN, DateFact.latest(listOf(DateFact.unknown(), DateFact.unknown())).status)
    }
}
