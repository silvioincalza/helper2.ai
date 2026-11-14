package ai.helper2.guide.steps

import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import org.slf4j.LoggerFactory

internal class ReasonVerdicts(val checks: Map<String, ReasonCheck>, val offNeed: Set<String>) {
  operator fun get(key: String): ReasonCheck? = checks[key]

  fun fitsNeed(key: String) = key !in offNeed

  companion object {
    val NONE = ReasonVerdicts(emptyMap(), emptySet())
  }
}

internal class ReasonCheckStep(
  private val steps: GuideSteps,
  private val validator: GuideValidator,
) {
  private val log = LoggerFactory.getLogger(ReasonCheckStep::class.java)

  fun verdicts(
    q: Question,
    model: GuideModel,
    claims: List<ReasonClaim>,
    progress: GuideProgress,
  ): ReasonVerdicts {
    val raw = modelVerdicts(q, model, claims)
    val checks = claims.mapNotNull { c -> checkOf(c, raw[c.key])?.let { c.key to it } }.toMap()
    val offNeed = claims.map { it.key }.filter { raw[it]?.fitsNeed == false }.toSet()
    progress.reasonsChecked(
      supported = checks.values.count { it == ReasonCheck.SUPPORTED },
      removed = checks.values.count { it == ReasonCheck.REMOVED },
      notChecked = claims.size - checks.size,
    )
    return ReasonVerdicts(checks, offNeed)
  }

  private fun modelVerdicts(
    q: Question,
    model: GuideModel,
    claims: List<ReasonClaim>,
  ): Map<String, ReasonVerdict> =
    try {
      steps.modelStep(q, "verify") { model.verifyReasons(q.need, claims) }
    } catch (e: RuntimeException) {
      log.warn("guide reason check failed: ${failure(e)}")
      emptyMap()
    }

  private fun checkOf(claim: ReasonClaim, verdict: ReasonVerdict?): ReasonCheck? =
    when {
      validator.namesUnstatedSecret(claim) -> ReasonCheck.REMOVED
      verdict == null -> null
      verdict.claimsSupported -> ReasonCheck.SUPPORTED
      else -> ReasonCheck.REMOVED
    }
}
