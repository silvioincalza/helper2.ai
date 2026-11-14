package ai.helper2.shared

import java.time.Duration
import java.time.Instant

enum class DatePrecision {
  INSTANT,
  DAY,
  UNKNOWN,
}

enum class DateStatus {
  KNOWN,
  UNKNOWN,
  CONFLICTING,
}

data class DateFact(
  val value: Instant?,
  val precision: DatePrecision,
  val sourceId: String?,
  val alternatives: List<SourcedValue> = emptyList(),
  val conflicting: Boolean = false,
) {
  val status: DateStatus
    get() =
      when {
        value == null -> DateStatus.UNKNOWN
        conflicting -> DateStatus.CONFLICTING
        else -> DateStatus.KNOWN
      }

  companion object {
    private val EARLIEST_PLAUSIBLE: Instant = Instant.parse("1995-01-01T00:00:00Z")
    private val CLOCK_SKEW: Duration = Duration.ofDays(1)

    @JvmStatic fun unknown() = DateFact(null, DatePrecision.UNKNOWN, null)

    @JvmStatic
    fun fromSource(value: Instant?, sourceId: String, retrievedAt: Instant): DateFact =
      if (value == null || !plausible(value, retrievedAt)) unknown()
      else DateFact(value, DatePrecision.INSTANT, sourceId)

    @JvmStatic
    fun scheduledDay(day: java.time.LocalDate?, sourceId: String): DateFact {
      val value = day?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()
      return if (value == null || value.isBefore(EARLIEST_PLAUSIBLE)) unknown()
      else DateFact(value, DatePrecision.DAY, sourceId)
    }

    @JvmStatic
    fun observed(value: Instant?): DateFact =
      if (value == null) unknown() else DateFact(value, DatePrecision.INSTANT, "helper2")

    private fun plausible(value: Instant, retrievedAt: Instant) =
      !value.isBefore(EARLIEST_PLAUSIBLE) && !value.isAfter(retrievedAt.plus(CLOCK_SKEW))

    @JvmStatic
    fun earliest(facts: List<DateFact>): DateFact =
      pick(facts, flagConflict = true) { it.minByOrNull { f -> f.value!! } }

    @JvmStatic
    fun latest(facts: List<DateFact>): DateFact =
      pick(facts, flagConflict = true) { it.maxByOrNull { f -> f.value!! } }

    @JvmStatic
    fun lastChange(facts: List<DateFact>): DateFact =
      pick(facts, flagConflict = false) { it.maxByOrNull { f -> f.value!! } }

    private fun pick(
      facts: List<DateFact>,
      flagConflict: Boolean,
      chooser: (List<DateFact>) -> DateFact?,
    ): DateFact {
      val known = facts.filter { it.value != null }
      val chosen = chooser(known) ?: return unknown()
      val others =
        (known.filter { it !== chosen }.map { it.asStated() } + known.flatMap { it.alternatives })
          .filter { it.value != chosen.value.toString() }
          .distinct()
      return chosen.copy(
        alternatives = others,
        conflicting = flagConflict && (sourcesDisagree(known) || known.any { it.conflicting }),
      )
    }

    private fun DateFact.asStated() = SourcedValue(value.toString(), sourceId ?: "unknown")

    private fun sourcesDisagree(known: List<DateFact>): Boolean {
      val daysBySource =
        known.groupBy(
          { it.sourceId },
          { it.value!!.atZone(java.time.ZoneOffset.UTC).toLocalDate() },
        )
      return daysBySource.size > 1 && daysBySource.values.flatten().distinct().size > 1
    }
  }
}
