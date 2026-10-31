package ai.helper2.setup

import java.time.Duration

/** Outbound port: the model proposes steps from the documents only. */
interface ToolGuideModel {
    val model: String

    /** Changes when the prompt or settings change, so cached answers from another prompt are not reused. */
    val configFingerprint: String

    fun write(ref: ToolRef, materials: List<Material>): ProposedGuide
}

/** Outbound port: answers kept across requests and replicas, shared by every visitor. */
interface ToolGuideCache {
    fun get(key: String): ToolGuideAnswer?
    fun put(key: String, answer: ToolGuideAnswer, ttl: Duration)

    companion object {
        val NONE = object : ToolGuideCache {
            override fun get(key: String): ToolGuideAnswer? = null
            override fun put(key: String, answer: ToolGuideAnswer, ttl: Duration) {}
        }
    }
}

/**
 * How long [ToolGuideCache] keeps an answer: [answer] for a checked one; [unreadable] for the "unavailable" answer
 * after two model answers that were not the requested JSON, so the same ask does not spend two calls again at once.
 */
data class ToolGuideTtls(val answer: Duration, val unreadable: Duration)
