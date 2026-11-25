package ai.searchavo.guide

@JvmSuppressWildcards
fun interface GuideJudge {
  fun judge(need: Need, claims: List<ReasonClaim>, checks: Map<String, ReasonCheck>)

  companion object {
    @JvmField val NONE = GuideJudge { _, _, _ -> }
  }
}

enum class ClaimKind {
  PICK,
  CELL,
  SENTENCE;

  val label: String = name.lowercase()

  companion object {
    @JvmStatic
    fun of(key: String): ClaimKind =
      when {
        COMPARISON_CELL.matches(key) -> CELL
        EXPLANATION_SENTENCE.matches(key) -> SENTENCE
        else -> PICK
      }

    private val COMPARISON_CELL = Regex("r\\d+-.+")
    private val EXPLANATION_SENTENCE = Regex("e\\d+")
  }
}
