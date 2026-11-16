package ai.helper2.eval

import java.io.File

object RankingCode {
  private const val SEARCH = "backend/libs/core/src/main/kotlin/ai/helper2/search"

  /** Every file whose change can move a result: a change to one of them needs a new baseline. */
  val files =
    listOf(
      "$SEARCH/Ranking.kt",
      "$SEARCH/ResultMerger.kt",
      "$SEARCH/QueryAnalyzer.kt",
      "$SEARCH/Vitality.kt",
      "$SEARCH/RepositoryJoin.kt",
    )

  private val WHITESPACE = Regex("\\s+")

  fun fingerprint(): String =
    EvalFiles.sha256(
      files.joinToString("\n") { name ->
        name + ":" + File(EvalFiles.root, name).readText().replace(WHITESPACE, "")
      }
    )
}
