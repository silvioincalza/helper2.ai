package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

enum class ExampleStatus {
  ACTIVE,
  PAUSED,
}

enum class ExampleOrigin {
  BUILT_IN,
  CURATED,
  AI_DRAFTED,
}

enum class CheckOutcome {
  PENDING,
  PASS,
  WEAK,
  FAIL,
}

data class CheckResult(
  val outcome: CheckOutcome,
  val bestRank: Int? = null,
  val resultCount: Int? = null,
  val checkedAt: Instant? = null,
) {
  companion object {
    val PENDING = CheckResult(CheckOutcome.PENDING)
  }
}

data class HomeExample(
  val id: UUID,
  val label: String,
  val query: String,
  val path: AudiencePath,
  val locale: String,
  val status: ExampleStatus,
  val expectedResourceIds: List<UUID>,
  val origin: ExampleOrigin,
  val model: String?,
  val check: CheckResult,
  val version: Int,
  val updatedAt: Instant,
)

data class ExampleInput
private constructor(
  val label: String,
  val query: String,
  val path: AudiencePath,
  val expectedResourceIds: List<UUID>,
) {
  companion object {
    const val LABEL_MIN = 2
    const val LABEL_MAX = 32
    const val QUERY_MIN = 3
    const val QUERY_MAX = 200
    const val EXPECTED_MAX = 3

    fun of(
      label: String,
      query: String,
      path: AudiencePath,
      expectedResourceIds: List<UUID>,
    ): Checked {
      val l = ExampleText.normalise(label)
      val q = ExampleText.normalise(query)
      val expected = expectedResourceIds.distinct()
      val problems =
        ExampleText.problems("label", l, LABEL_MIN, LABEL_MAX) +
          ExampleText.problems("query", q, QUERY_MIN, QUERY_MAX) +
          expectedProblems(expected)
      return if (problems.isEmpty()) Checked.Valid(ExampleInput(l, q, path, expected))
      else Checked.Invalid(problems)
    }

    private fun expectedProblems(expected: List<UUID>): List<String> =
      if (expected.size in 1..EXPECTED_MAX) emptyList()
      else listOf("give 1 to $EXPECTED_MAX expected results")
  }

  sealed interface Checked {
    data class Valid(val input: ExampleInput) : Checked

    data class Invalid(val problems: List<String>) : Checked
  }
}

object ExampleText {
  private val SPACES = Regex("\\s+")
  private val CONTROL = Regex("\\p{Cntrl}")
  private val URL_LIKE = Regex("(?i)https?:|www\\.|://")

  fun normalise(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC).replace(SPACES, " ").trim()

  fun problems(field: String, text: String, min: Int, max: Int): List<String> =
    listOfNotNull(
      "$field: $min to $max characters".takeUnless { text.length in min..max },
      "$field: no control characters".takeIf { CONTROL.containsMatchIn(text) },
      "$field: no < or >".takeIf { '<' in text || '>' in text },
      "$field: no web addresses".takeIf { URL_LIKE.containsMatchIn(text) },
    )
}
