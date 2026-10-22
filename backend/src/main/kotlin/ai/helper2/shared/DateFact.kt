package ai.helper2.shared

import java.time.Duration
import java.time.Instant

enum class DatePrecision { INSTANT, DAY, UNKNOWN }

enum class DateStatus { KNOWN, UNKNOWN, CONFLICTING }

/**
 * A date that may be unknown. Helper2 never fills a missing date with a guess.
 * Values a source reports that cannot be true (after retrieval time, or before the web existed)
 * are discarded and become UNKNOWN rather than being shown.
 */
data class DateFact(
    val value: Instant?,
    val precision: DatePrecision,
    val sourceId: String?,
    val alternatives: List<SourcedValue> = emptyList(),
    val conflicting: Boolean = false,
) {
    val status: DateStatus
        get() = when {
            value == null -> DateStatus.UNKNOWN
            conflicting -> DateStatus.CONFLICTING
            else -> DateStatus.KNOWN
        }

    companion object {
        private val EARLIEST_PLAUSIBLE: Instant = Instant.parse("1995-01-01T00:00:00Z")
        private val CLOCK_SKEW: Duration = Duration.ofDays(1)

        @JvmStatic
        fun unknown() = DateFact(null, DatePrecision.UNKNOWN, null)

        @JvmStatic
        fun fromSource(value: Instant?, sourceId: String, retrievedAt: Instant): DateFact =
            if (value == null || !plausible(value, retrievedAt)) unknown()
            else DateFact(value, DatePrecision.INSTANT, sourceId)

        /**
         * A calendar day a source states for when something takes place (an event). Unlike
         * [fromSource] it may lie in the future; stored as that day at 00:00 UTC with DAY precision.
         */
        @JvmStatic
        fun scheduledDay(day: java.time.LocalDate?, sourceId: String): DateFact {
            val value = day?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()
            return if (value == null || value.isBefore(EARLIEST_PLAUSIBLE)) unknown() else DateFact(value, DatePrecision.DAY, sourceId)
        }

        /** Helper2's own observation time (e.g. first discovered). */
        @JvmStatic
        fun observed(value: Instant?): DateFact =
            if (value == null) unknown() else DateFact(value, DatePrecision.INSTANT, "helper2")

        private fun plausible(value: Instant, retrievedAt: Instant) =
            !value.isBefore(EARLIEST_PLAUSIBLE) && !value.isAfter(retrievedAt.plus(CLOCK_SKEW))

        /**
         * Original publication (or an event's start) across sources: the earliest known value, others kept as
         * alternatives. CONFLICTING when two sources state different days for it.
         */
        @JvmStatic
        fun earliest(facts: List<DateFact>): DateFact = pick(facts, flagConflict = true) { it.minByOrNull { f -> f.value!! } }

        /** An event's end across sources: the latest known value, others kept as alternatives. CONFLICTING as for [earliest]. */
        @JvmStatic
        fun latest(facts: List<DateFact>): DateFact = pick(facts, flagConflict = true) { it.maxByOrNull { f -> f.value!! } }

        /**
         * Last update across sources: the most recent known value, others kept as alternatives. Never CONFLICTING:
         * each source reports when its own part last changed, so different values are expected.
         */
        @JvmStatic
        fun lastChange(facts: List<DateFact>): DateFact = pick(facts, flagConflict = false) { it.maxByOrNull { f -> f.value!! } }

        private fun pick(facts: List<DateFact>, flagConflict: Boolean, chooser: (List<DateFact>) -> DateFact?): DateFact {
            val known = facts.filter { it.value != null }
            val chosen = chooser(known) ?: return unknown()
            val others = known.filter { it !== chosen && it.value != chosen.value }
                .map { SourcedValue(it.value.toString(), it.sourceId ?: "unknown") }
                .distinct()
            return chosen.copy(alternatives = others, conflicting = flagConflict && sourcesDisagree(known))
        }

        /** Two different sources state different UTC days; one source repeating itself, or a time of day, is no conflict. */
        private fun sourcesDisagree(known: List<DateFact>): Boolean {
            val daysBySource = known.groupBy({ it.sourceId }, { it.value!!.atZone(java.time.ZoneOffset.UTC).toLocalDate() })
            return daysBySource.size > 1 && daysBySource.values.flatten().distinct().size > 1
        }
    }
}
