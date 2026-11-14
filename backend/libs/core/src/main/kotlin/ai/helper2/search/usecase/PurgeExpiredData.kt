package ai.helper2.search.usecase

import ai.helper2.search.WebResultRetention
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

class PurgeExpiredData(
  private val retention: WebResultRetention,
  private val clock: Clock,
  private val webResults: Duration,
) {
  private val log = LoggerFactory.getLogger(PurgeExpiredData::class.java)

  fun purge(): Int =
    retention.deleteFetchedBefore(clock.instant().minus(webResults)).also {
      log.info("search retention: $it web result set(s) deleted")
    }
}
