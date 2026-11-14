package ai.helper2.scout

import java.time.Instant

fun interface RunHistoryRetention {
  fun deleteRunsStartedBefore(cutoff: Instant): Int
}
