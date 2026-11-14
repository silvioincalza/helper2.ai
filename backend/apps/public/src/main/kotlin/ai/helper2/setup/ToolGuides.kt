package ai.helper2.setup

import ai.helper2.platform.quota.ClientDayQuota
import ai.helper2.platform.resilience.Resilience
import ai.helper2.shared.SingleFlight
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat

class ToolGuides
@JvmOverloads
constructor(
  private val materials: ToolMaterials,
  private val model: ToolGuideModel?,
  private val cache: ToolGuideCache,
  private val clock: Clock,
  private val ttls: ToolGuideTtls,
  private val validator: ToolGuideValidator = ToolGuideValidator(),
  private val visitors: ClientDayQuota? = null,
  private val resilience: Resilience = Resilience.NONE,
  private val stepText: StepTextMode = StepTextMode.VERIFIED,
) {
  private val writing = SingleFlight<ToolGuideAnswer>()

  init {
    resilience.require(WRITER_SLOTS)
  }

  @JvmOverloads
  fun answer(resourceId: String, ref: ToolRef, client: String? = null): ToolGuideAnswer {
    val docs = if (ref.hasDocs) materials.load(ref) else emptyList()
    if (docs.isEmpty()) return empty(ToolGuideStatus.NO_MATERIAL, docs)
    val writer = model ?: return empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, "error")
    val key = keyOf(resourceId, writer, docs)
    cache.get(key)?.let {
      return it
    }
    val turn = ClientTurn(client)
    if (!turn.taken) return empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, "quota", nextUtcDay())
    var written = false
    try {
      val outcome = writing.run(key, beforeFollowing = {}) { inASlot(key, writer, ref, docs) }
      written = outcome is SingleFlight.Led && outcome.value.model != null
      return outcome.value
    } finally {
      if (!written) turn.giveBack()
    }
  }

  fun guidesLeft(client: String): SetupGuidesLeft? {
    if (model == null || visitors == null) return null
    return SetupGuidesLeft(visitors.limit(), visitors.left(client))
  }

  private fun inASlot(
    key: String,
    writer: ToolGuideModel,
    ref: ToolRef,
    docs: List<Material>,
  ): ToolGuideAnswer {
    val slot =
      resilience.trySlot(WRITER_SLOTS).orElse(null)
        ?: return empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, "busy")
    try {
      return write(key, writer, ref, docs)
    } finally {
      slot.release()
    }
  }

  private fun write(
    key: String,
    writer: ToolGuideModel,
    ref: ToolRef,
    docs: List<Material>,
  ): ToolGuideAnswer {
    val proposal =
      try {
        writer.write(ref, docs)
      } catch (e: ToolGuideUnavailable) {
        val unavailable =
          empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, e.reason.shown, openAgainAt(e.reason))
        if (e.reason == ToolGuideUnavailable.Reason.UNREADABLE)
          cache.put(key, unavailable, ttls.unreadable)
        return unavailable
      }
    val result = validator.validate(proposal, docs)
    val steps = withStepTexts(writer, ref, result.sections)
    val answer =
      ToolGuideAnswer(
        if (steps.sections.isEmpty()) ToolGuideStatus.NO_STEPS else ToolGuideStatus.OK,
        steps.sections,
        docs.map { it.ref },
        writer.model,
        result.rejected,
        clock.instant(),
        result.rejectedBy,
      )
    if (steps.settled) cache.put(key, answer, ttls.answer)
    return answer
  }

  private fun withStepTexts(
    writer: ToolGuideModel,
    ref: ToolRef,
    sections: List<GuideSection>,
  ): StepTextsShown {
    if (sections.isEmpty()) return StepTextsShown(sections, settled = true)
    if (stepText == StepTextMode.SOURCE_ONLY)
      return StepTextsShown(StepTexts.sourceOnly(sections), settled = true)
    return try {
      val confirmed = writer.confirm(ref, StepTexts.claims(sections))
      StepTextsShown(StepTexts.keepConfirmed(sections, confirmed), settled = true)
    } catch (_: ToolGuideUnavailable) {
      StepTextsShown(StepTexts.sourceOnly(sections), settled = false)
    }
  }

  private class StepTextsShown(val sections: List<GuideSection>, val settled: Boolean)

  private inner class ClientTurn(client: String?) {
    private val turn = client?.let { visitors?.take(it) }
    val taken = client == null || visitors == null || turn != null

    fun giveBack() {
      turn?.giveBack()
    }
  }

  private fun keyOf(resourceId: String, writer: ToolGuideModel, docs: List<Material>) =
    "v1:" +
      resourceId +
      ":" +
      sha256(
        writer.configFingerprint +
          "\u0000stepText=" +
          stepText.name +
          "\u0000" +
          docs.joinToString("\u0000") { it.url.toString() + "\u0000" + it.text }
      )

  private fun empty(
    status: ToolGuideStatus,
    docs: List<Material>,
    why: String? = null,
    retryAt: Instant? = null,
  ) =
    ToolGuideAnswer(
      status,
      emptyList(),
      docs.map { it.ref },
      null,
      0,
      clock.instant(),
      unavailable = why,
      retryAt = retryAt,
    )

  private fun openAgainAt(reason: ToolGuideUnavailable.Reason): Instant? =
    if (reason == ToolGuideUnavailable.Reason.BUDGET) nextUtcDay() else null

  private fun nextUtcDay(): Instant =
    clock.instant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant()

  private fun sha256(s: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

  companion object {
    const val WRITER_SLOTS = "setup-guide"
  }
}
