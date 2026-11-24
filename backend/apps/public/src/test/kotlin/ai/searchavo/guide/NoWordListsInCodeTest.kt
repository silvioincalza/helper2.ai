package ai.searchavo.guide

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class NoWordListsInCodeTest {

  private val readers =
    listOf(
      "apps/public/src/main/kotlin/ai/searchavo/guide",
      "libs/core/src/main/kotlin/ai/searchavo/search",
    )

  @Test
  fun `the guide and the search read every natural-language word from i18n files`() {
    val found =
      readers
        .flatMap { dir -> source(dir).walkTopDown().filter { it.extension == "kt" }.toList() }
        .filter { it.name !in GUARDRAILS }
        .flatMap { file -> wordLists(file.readText()).map { "${file.name}: $it" } }
        .filterNot { list -> NAMES.any { list.startsWith(it) } }
    assertEquals(emptyList(), found)
  }

  private fun source(dir: String): File =
    listOf(File(dir), File("../../$dir")).first { it.isDirectory }

  private fun wordLists(code: String): List<String> =
    STRING.findAll(code).map { it.groupValues[1] }.flatMap(::alternations).toList() +
      COLLECTION.findAll(code)
        .map { it.value }
        .filter { block -> STRING.findAll(block).count { it.groupValues[1].matches(WORD) } >= 4 }
        .map { it.lineSequence().first().trim() }

  private fun alternations(literal: String): Sequence<String> =
    GROUP.findAll(literal)
      .map { it.groupValues[1] }
      .filter { group ->
        val parts = group.split('|')
        parts.size >= 3 && parts.all { it.matches(PATTERN_WORD) }
      }

  private companion object {
    val GUARDRAILS = setOf("HostileText.kt")
    val NAMES =
      listOf(
        "Guide.kt: listOf(\"mit\"",
        "ResultMerger.kt: mapOf(",
        "LanguageDetector.kt: mapOf(",
        "Compatibility.kt: setOf(",
        "ShadowLibraries.kt: setOf(",
      )
    val STRING = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    val WORD = Regex("\\p{Ll}[\\p{Ll}' -]*")
    val PATTERN_WORD = Regex("\\p{L}[\\p{L}\\[\\]?' -]*")
    val GROUP = Regex("\\((?:\\?:)?([^()]*\\|[^()]*)\\)")
    val COLLECTION = Regex("(?:setOf|listOf|mapOf|linkedSetOf)\\((?:[^()]|\\([^()]*\\))*\\)")
  }
}
