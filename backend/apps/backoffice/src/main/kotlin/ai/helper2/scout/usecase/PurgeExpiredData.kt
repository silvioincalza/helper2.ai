package ai.helper2.scout.usecase

import ai.helper2.scout.RunHistoryRetention
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

class PurgeExpiredData(
  private val history: RunHistoryRetention,
  private val clock: Clock,
  private val scoutRuns: Duration,
) {
  private val log = LoggerFactory.getLogger(PurgeExpiredData::class.java)

  fun purge(): Int =
    history.deleteRunsStartedBefore(clock.instant().minus(scoutRuns)).also {
      log.info("scout retention: $it run record(s) deleted")
    }
}
