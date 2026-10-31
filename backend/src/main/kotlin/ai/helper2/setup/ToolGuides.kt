package ai.helper2.setup

import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/**
 * The tool guide: the resource's own documents, a model that may only pick and order passages from them, and the
 * validator. One answer per resource, documents and question, cached; the plain guide (no question) is the same
 * for every visitor. When the model's answers could not be read, that "unavailable" answer is cached too, for
 * [ToolGuideTtls.unreadable]; a provider error or a spent budget is not, since both recover by themselves.
 */
class ToolGuides @JvmOverloads constructor(
    private val materials: ToolMaterials,
    private val model: ToolGuideModel?,
    private val cache: ToolGuideCache,
    private val clock: Clock,
    private val ttls: ToolGuideTtls,
    private val validator: ToolGuideValidator = ToolGuideValidator(),
) {

    fun answer(resourceId: String, ref: ToolRef, question: String?): ToolGuideAnswer {
        val q = question?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }
        val docs = if (ref.hasDocs) materials.load(ref) else emptyList()
        if (docs.isEmpty()) return empty(ToolGuideStatus.NO_MATERIAL, q, docs)
        if (model == null) return empty(ToolGuideStatus.LLM_UNAVAILABLE, q, docs, "error")
        val key = "v1:" + resourceId + ":" + sha256(model.configFingerprint + "\u0000" + (q?.lowercase() ?: "") + "\u0000" +
            docs.joinToString("\u0000") { it.url.toString() + "\u0000" + it.text })
        cache.get(key)?.let { return it.copy(question = q) }
        val proposal = try {
            model.write(ref, docs, q)
        } catch (e: ToolGuideUnavailable) {
            val unavailable = empty(ToolGuideStatus.LLM_UNAVAILABLE, null, docs, e.reason.shown)
            if (e.reason == ToolGuideUnavailable.Reason.UNREADABLE) cache.put(key, unavailable, ttls.unreadable)
            return unavailable.copy(question = q)
        }
        val result = validator.validate(proposal, docs)
        val answer = ToolGuideAnswer(if (result.sections.isEmpty()) ToolGuideStatus.NO_STEPS else ToolGuideStatus.OK, null,
            result.sections, docs.map { it.ref }, model.model, result.rejected, clock.instant(), result.rejectedBy)
        cache.put(key, answer, ttls.answer)
        return answer.copy(question = q)
    }

    private fun empty(status: ToolGuideStatus, q: String?, docs: List<Material>, why: String? = null) =
        ToolGuideAnswer(status, q, emptyList(), docs.map { it.ref }, null, 0, clock.instant(), unavailable = why)

    private fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
