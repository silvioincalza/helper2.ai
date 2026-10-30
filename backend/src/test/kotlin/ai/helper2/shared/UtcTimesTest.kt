package ai.helper2.shared

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtcTimesTest {

    @Test
    fun `an instant is shown at UTC`() {
        assertEquals(
            OffsetDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneOffset.UTC),
            UtcTimes.odt(Instant.parse("2026-10-30T10:00:00Z")),
        )
    }

    @Test
    fun `no instant stays absent`() {
        assertNull(UtcTimes.odt(null))
    }
}
