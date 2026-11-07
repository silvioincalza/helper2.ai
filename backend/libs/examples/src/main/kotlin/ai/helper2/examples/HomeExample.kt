package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/** Set by an administrator: a paused example is kept but never shown. */
enum class ExampleStatus {
  ACTIVE,
  PAUSED,
}

/** Where an example comes from. AI_DRAFTED keeps its model id for good, even after an edit. */
enum class ExampleOrigin {
  BUILT_IN,
  CURATED,
  AI_DRAFTED,
}

/**
 * What the last quality check found (ADR 0049). PENDING: new or changed, not checked yet. PASS: an
 * expected resource in the top 3 of the example's own search. WEAK: in the top 4 to 10. FAIL: none
 * in the top 10.
 */
enum class CheckOutcome {
  PENDING,
  PASS,
  WEAK,
  FAIL,
}

data class CheckResult(
  val outcome: CheckOutcome,
  /** The best rank (1 = first) of an expected resource; null when none was in the results. */
  val bestRank: Int? = null,
  val resultCount: Int? = null,
  val checkedAt: Instant? = null,
) {
  companion object {
    val PENDING = CheckResult(CheckOutcome.PENDING)
  }
}

/**
 * One example search under the home box: the [label] on the chip, the [query] it searches, and the
 * [path] it sends (which also puts it in a bucket: people who use AI, people who build with it).
 */
data class HomeExample(
  val id: UUID,
  val label: String,
  val query: String,
  val path: AudiencePath,
  val locale: String,
  val status: ExampleStatus,
  /** Catalog resource ids an administrator says are a right answer: 1 to 3. */
  val expectedResourceIds: List<UUID>,
  val origin: ExampleOrigin,
  /** The model that drafted it, for AI_DRAFTED only. */
  val model: String?,
  val check: CheckResult,
  /** Optimistic lock: a change names the version it read. */
  val version: Int,
  val updatedAt: Instant,
)

/**
 * What an administrator (or an accepted AI draft) gives for an example. Built through [of], which
 * normalises the texts and refuses what the rules do not allow, so a stored example is always
 * valid.
 */
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

    /** The normalised input, or the rules it breaks, in words an administrator reads. */
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

/** The text rules of an example: it is shown on the home page and sent as a search. */
object ExampleText {
  private val SPACES = Regex("\\s+")
  private val CONTROL = Regex("\\p{Cntrl}")
  private val URL_LIKE = Regex("(?i)https?:|www\\.|://")

  /** NFC, trimmed, one space between words. */
  fun normalise(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC).replace(SPACES, " ").trim()

  /** What is wrong with [text] as the [field] of an example; empty when nothing is. */
  fun problems(field: String, text: String, min: Int, max: Int): List<String> =
    listOfNotNull(
      "$field: $min to $max characters".takeUnless { text.length in min..max },
      "$field: no control characters".takeIf { CONTROL.containsMatchIn(text) },
      "$field: no < or >".takeIf { '<' in text || '>' in text },
      "$field: no web addresses".takeIf { URL_LIKE.containsMatchIn(text) },
    )
}
