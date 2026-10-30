package ai.helper2.guide

/**
 * How much of a comparison and an explanation an answer keeps (ADR 0032). The compare and explain prompts ask for
 * these limits and AskGuide enforces them; they are part of the answer cache key, so new limits never serve answers
 * kept under the old ones.
 */
data class AnswerLimits(
    val maxRows: Int = 8,
    val maxAspectChars: Int = 40,
    val maxCellChars: Int = 200,
    val maxSentences: Int = 4,
    val maxSentenceChars: Int = 300,
) {
    val fingerprint: String get() = toString()

    companion object {
        @JvmField
        val DEFAULT = AnswerLimits()
    }
}
