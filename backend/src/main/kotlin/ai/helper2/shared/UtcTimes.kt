package ai.helper2.shared

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Instants as the API writes them: an offset date-time at UTC. */
object UtcTimes {

    @JvmStatic
    fun odt(instant: Instant?): OffsetDateTime? = instant?.atOffset(ZoneOffset.UTC)
}
