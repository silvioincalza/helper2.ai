package ai.helper2.guide.usecase

import ai.helper2.guide.GuideAnswerRetention
import java.time.Clock
import org.slf4j.LoggerFactory

/** The guide module's retention rule (ADR 0034): cached answers and indexed questions are deleted once expired. */
class PurgeExpiredData(private val retention: GuideAnswerRetention, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(PurgeExpiredData::class.java)

    /** Returns how many answers were deleted. */
    fun purge(): Int = retention.deleteExpiredBefore(clock.instant()).also {
        log.info("guide retention: $it expired answer(s) deleted")
    }
}
