package ai.helper2.guide.usecase

import ai.helper2.guide.SimilarQuestionRetention
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The guide module's retention rule (ADR 0034): indexed questions are deleted once expired. The answers themselves are
 * in the shared cache, which has its own purge.
 */
class PurgeExpiredData(private val retention: SimilarQuestionRetention, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(PurgeExpiredData::class.java)

    /** Returns how many indexed questions were deleted. */
    fun purge(): Int = retention.deleteExpiredBefore(clock.instant()).also {
        log.info("guide retention: $it expired indexed question(s) deleted")
    }
}
