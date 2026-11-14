package ai.helper2.eval

import java.io.File

object RankingCode {
  private const val SEARCH = "backend/libs/core/src/main/kotlin/ai/helper2/search"
  val files = listOf("Ranking.kt", "ResultMerger.kt", "QueryAnalyzer.kt", "Vitality.kt")

  private val WHITESPACE = Regex("\\s+")

  fun fingerprint(): String =
    EvalFiles.sha256(
      files.joinToString("\n") { name ->
        name + ":" + File(EvalFiles.root, "$SEARCH/$name").readText().replace(WHITESPACE, "")
      }
    )
}
