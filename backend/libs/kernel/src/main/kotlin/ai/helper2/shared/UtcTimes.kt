package ai.helper2.shared

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

object UtcTimes {

  @JvmStatic fun odt(instant: Instant?): OffsetDateTime? = instant?.atOffset(ZoneOffset.UTC)
}
