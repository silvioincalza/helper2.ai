package ai.helper2.eval

import ai.helper2.shared.WordLists
import java.io.File

object RankingCode {
  private const val SEARCH = "backend/libs/core/src/main/kotlin/ai/helper2/search"
  private const val KERNEL = "backend/libs/kernel/src/main/kotlin/ai/helper2/shared"
  private const val WORDS = "backend/libs/core/src/main/resources/i18n"

  val files =
    listOf("Ranking.kt", "ResultMerger.kt", "QueryAnalyzer.kt", "Vitality.kt", "RepositoryJoin.kt")
      .map {
        "$SEARCH/$it"
      } +
      listOf("WordLists.kt", "WordBounds.kt").map { "$KERNEL/$it" } +
      WordLists.LANGUAGES.map { "$WORDS/query-words_$it.properties" } +
      "backend/libs/core/src/main/resources/catalog/repository-joins.properties"

  private val WHITESPACE = Regex("\\s+")

  fun fingerprint(): String =
    EvalFiles.sha256(
      files.joinToString("\n") { path ->
        path + ":" + File(EvalFiles.root, path).readText().replace(WHITESPACE, "")
      }
    )
}
