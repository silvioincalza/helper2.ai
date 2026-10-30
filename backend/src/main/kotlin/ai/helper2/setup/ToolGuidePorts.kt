package ai.helper2.setup

/** Outbound port: the model proposes steps from the documents only. */
interface ToolGuideModel {
    val model: String

    /** Changes when the prompt or settings change, so cached answers from another prompt are not reused. */
    val configFingerprint: String

    fun write(ref: ToolRef, materials: List<Material>, question: String?): ProposedGuide
}

/** Outbound port: answers kept across requests and replicas, stored without the question that produced them. */
interface ToolGuideCache {
    fun get(key: String): ToolGuideAnswer?
    fun put(key: String, answer: ToolGuideAnswer)

    companion object {
        val NONE = object : ToolGuideCache {
            override fun get(key: String): ToolGuideAnswer? = null
            override fun put(key: String, answer: ToolGuideAnswer) {}
        }
    }
}
