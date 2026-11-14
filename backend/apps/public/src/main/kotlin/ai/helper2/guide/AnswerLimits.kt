package ai.helper2.guide

data class AnswerLimits(
  val maxRows: Int = 8,
  val maxAspectChars: Int = 40,
  val maxCellChars: Int = 200,
  val maxSentences: Int = 4,
  val maxSentenceChars: Int = 300,
) {
  val fingerprint: String
    get() = toString()

  companion object {
    @JvmField val DEFAULT = AnswerLimits()
  }
}
