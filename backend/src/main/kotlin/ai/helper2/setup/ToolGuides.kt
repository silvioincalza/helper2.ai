package ai.helper2.setup

import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/**
 * The tool guide: the resource's own documents, a model that may only pick and order passages from them, and the
 * validator. One answer per resource and documents, cached and the same for every visitor. When the model's answers
 * could not be read, that "unavailable" answer is cached too, for [ToolGuideTtls.unreadable]; a provider error or a
 * spent budget is not, since both recover by themselves.
 */
class ToolGuides @JvmOverloads constructor(
    private val materials: ToolMaterials,
    private val model: ToolGuideModel?,
    private val cache: ToolGuideCache,
    private val clock: Clock,
    private val ttls: ToolGuideTtls,
    private val validator: ToolGuideValidator = ToolGuideValidator(),
) {

    fun answer(resourceId: String, ref: ToolRef): ToolGuideAnswer {
        val docs = if (ref.hasDocs) materials.load(ref) else emptyList()
        if (docs.isEmpty()) return empty(ToolGuideStatus.NO_MATERIAL, docs)
        if (model == null) return empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, "error")
        val key = "v1:" + resourceId + ":" + sha256(model.configFingerprint + "\u0000" +
            docs.joinToString("\u0000") { it.url.toString() + "\u0000" + it.text })
        cache.get(key)?.let { return it }
        val proposal = try {
            model.write(ref, docs)
        } catch (e: ToolGuideUnavailable) {
            val unavailable = empty(ToolGuideStatus.LLM_UNAVAILABLE, docs, e.reason.shown)
            if (e.reason == ToolGuideUnavailable.Reason.UNREADABLE) cache.put(key, unavailable, ttls.unreadable)
            return unavailable
        }
        val result = validator.validate(proposal, docs)
        val answer = ToolGuideAnswer(if (result.sections.isEmpty()) ToolGuideStatus.NO_STEPS else ToolGuideStatus.OK,
            result.sections, docs.map { it.ref }, model.model, result.rejected, clock.instant(), result.rejectedBy)
        cache.put(key, answer, ttls.answer)
        return answer
    }

    private fun empty(status: ToolGuideStatus, docs: List<Material>, why: String? = null) =
        ToolGuideAnswer(status, emptyList(), docs.map { it.ref }, null, 0, clock.instant(), unavailable = why)

    private fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
