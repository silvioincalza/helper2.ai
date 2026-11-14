package ai.helper2.guide.usecase

import ai.helper2.guide.SimilarQuestionRetention
import java.time.Clock
import org.slf4j.LoggerFactory

class PurgeExpiredData(private val retention: SimilarQuestionRetention, private val clock: Clock) {
  private val log = LoggerFactory.getLogger(PurgeExpiredData::class.java)

  fun purge(): Int =
    retention.deleteExpiredBefore(clock.instant()).also {
      log.info("guide retention: $it expired indexed question(s) deleted")
    }
}
