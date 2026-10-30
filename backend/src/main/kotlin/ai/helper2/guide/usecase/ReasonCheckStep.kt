package ai.helper2.guide.usecase

import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import org.slf4j.LoggerFactory

/** The reason check of every answer that has model-written claims (ADR 0016). */
internal class ReasonCheckStep(private val steps: GuideSteps) {
    private val log = LoggerFactory.getLogger(ReasonCheckStep::class.java)

    /** The reason check; any failure leaves every claim NOT_CHECKED, never removes one. */
    fun verdicts(q: Question, model: GuideModel, claims: List<ReasonClaim>, progress: GuideProgress): Map<String, ReasonCheck> {
        val raw = try {
            steps.step(q, "verify") { model.verifyReasons(q.fullText, claims) }
        } catch (e: RuntimeException) {
            log.warn("guide reason check failed: ${failure(e)}")
            emptyMap()
        }
        val verdicts = claims.mapNotNull { c -> raw[c.key]?.let { c.key to if (it) ReasonCheck.SUPPORTED else ReasonCheck.REMOVED } }.toMap()
        progress.reasonsChecked(
            supported = verdicts.values.count { it == ReasonCheck.SUPPORTED },
            removed = verdicts.values.count { it == ReasonCheck.REMOVED },
            notChecked = claims.size - verdicts.size,
        )
        return verdicts
    }
}
