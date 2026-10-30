package ai.helper2.scout

import java.time.Instant

/** Outbound port: run history past its retention (ADR 0034). */
fun interface RunHistoryRetention {
    /** Deletes the finished run records started before [cutoff]; returns how many. A RUNNING record stays. */
    fun deleteRunsStartedBefore(cutoff: Instant): Int
}
