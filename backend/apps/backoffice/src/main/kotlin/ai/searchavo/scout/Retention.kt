package ai.searchavo.scout

import java.time.Instant

fun interface RunHistoryRetention {
  fun deleteRunsStartedBefore(cutoff: Instant): Int
}
