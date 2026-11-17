package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.shared.LinkText
import ai.helper2.shared.ResourceType
import ai.helper2.shared.WordBounds.END
import ai.helper2.shared.WordBounds.GAP
import ai.helper2.shared.WordBounds.START
import ai.helper2.shared.WordLists
import java.text.Normalizer
import java.time.LocalDate

internal val IntentWords: WordLists = WordLists.load("intent-words")

enum class GuideIntent {
  SUGGEST,
  NEWEST,
  ALTERNATIVES,
  COMPARE,
  HOW_TO,
  WHAT_IS,
  WHEN,
  LEARN,
}

enum class WindowUnit(val days: Long) {
  DAY(1),
  WEEK(7),
  MONTH(30),
  YEAR(365),
}

enum class WindowDirection {
  PAST,
  NEXT,
}

data class PlannedWindow
@JvmOverloads
constructor(
  val amount: Int,
  val unit: WindowUnit,
  val direction: WindowDirection = WindowDirection.PAST,
) {
  fun resolve(today: LocalDate): DateWindow {
    val days = amount.coerceIn(1, MAX_AMOUNT).toLong() * unit.days
    return when (direction) {
      WindowDirection.PAST -> DateWindow(today.minusDays(days), today)
      WindowDirection.NEXT -> DateWindow(today, today.plusDays(days))
    }
  }

  companion object {
    const val MAX_AMOUNT = 120
  }
}

data class DateWindow(val from: LocalDate, val to: LocalDate)

data class Clarification
@JvmOverloads
constructor(val question: String, val options: List<String>, val aiWritten: Boolean = true) {
  companion object {
    const val MAX_OPTIONS = 4
    const val MAX_OPTION_CHARS = 40
    const val MAX_QUESTION_CHARS = 120

    @JvmStatic
    @JvmOverloads
    fun of(question: String?, options: List<String?>?, aiWritten: Boolean = true): Clarification? {
      val q =
        question?.trim()?.replace(Regex("\\s+"), " ")?.takeIf {
          it.isNotEmpty() && it.length <= MAX_QUESTION_CHARS
        } ?: return null
      val opts =
        options
          .orEmpty()
          .asSequence()
          .mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ") }
          .filter {
            it.isNotEmpty() && it.length <= MAX_OPTION_CHARS && !LinkText.containsLink(it)
          }
          .distinctBy { it.lowercase() }
          .take(MAX_OPTIONS)
          .toList()
      if (LinkText.containsLink(q) || opts.size < 2) return null
      return Clarification(q, opts, aiWritten)
    }
  }
}

enum class SubjectKind(val label: String) {
  MODEL("model"),
  DATASET("dataset"),
  TOOL("tool");

  companion object {
    @JvmStatic
    fun of(type: ResourceType): SubjectKind? =
      when (type) {
        ResourceType.MODEL -> MODEL
        ResourceType.DATASET -> DATASET
        ResourceType.PRODUCT,
        ResourceType.AGENT,
        ResourceType.PROMPT,
        ResourceType.SKILL,
        ResourceType.MCP_SERVER,
        ResourceType.LIBRARY -> TOOL
        else -> null
      }
  }
}

object AmbiguousSubject {
  @JvmStatic
  fun clarification(
    subject: String,
    results: List<MergedResult>,
    planned: Clarification?,
  ): Clarification? {
    val firstOfEachKind =
      results
        .filter { isSubject(it, subject) }
        .mapNotNull { r -> SubjectKind.of(r.type)?.let { it to r } }
        .distinctBy { it.first }
    if (firstOfEachKind.size < 2) return null
    return planned
      ?: Clarification.of(
        "Which $subject do you mean?",
        firstOfEachKind.map { (kind, r) -> option(subject, kind, r) },
        aiWritten = false,
      )
  }

  private fun option(subject: String, kind: SubjectKind, r: MergedResult): String {
    val name = r.title.substringAfterLast('/').replace(Regex("[-_\\s]+"), " ").trim()
    val option = "$name (${kind.label})"
    return if (name.isEmpty() || option.length > Clarification.MAX_OPTION_CHARS)
      "$subject (${kind.label})"
    else option
  }
}

data class Drops(
  val intent: Boolean = false,
  val window: Boolean = false,
  val constraints: Set<String> = emptySet(),
) {
  val isEmpty: Boolean
    get() = !intent && !window && constraints.isEmpty()

  val key: String
    get() =
      if (isEmpty) ""
      else
        listOfNotNull(
            "intent".takeIf { intent },
            "window".takeIf { window },
            *constraints.sorted().map { "constraint:$it" }.toTypedArray(),
          )
          .joinToString(",")

  companion object {
    @JvmField val NONE = Drops()

    private const val MAX_DROPS = 8

    @JvmStatic
    fun of(raw: List<String?>?): Drops {
      val items =
        raw
          .orEmpty()
          .mapNotNull { it?.trim() }
          .filter { it.isNotEmpty() && it.length <= 80 }
          .take(MAX_DROPS)
      return Drops(
        intent = "intent" in items,
        window = "window" in items,
        constraints =
          items
            .filter { it.startsWith("constraint:") }
            .map { it.removePrefix("constraint:").trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet(),
      )
    }
  }
}

object IntentRules {
  private val UNITS: Map<String, WindowUnit> =
    WindowUnit.entries
      .flatMap { u ->
        IntentWords.phrases("unit.${u.name.lowercase()}").map { it.lowercase() to u }
      }
      .toMap()
  private val UNIT = alternation(UNITS.keys)

  private val PAST = periods("period.past")

  private val THIS = Regex("(?iu)$START(?:${alternation("period.this")})$GAP($UNIT)$END")

  private val NEXT = periods("period.next")

  private fun periods(key: String): List<Regex> {
    val period = alternation(key)
    val amount = "(\\d{1,3})"
    return listOf(
      Regex("(?iu)$START(?:$period)$GAP(?:$amount$GAP)?($UNIT)$END"),
      Regex("(?iu)$START$amount$GAP(?:$period)$GAP($UNIT)$END"),
      Regex("(?iu)$START(?:$amount$GAP)?($UNIT)$GAP(?:$period)$END"),
    )
  }

  private val TODAY = anywhere("period.today")

  private val NEWEST = anywhere("intent.newest")

  private val NEW_KIND: List<Regex> =
    WordLists.LANGUAGES.mapNotNull { language ->
      val newWords = IntentWords.phrasesIn(language, "intent.new-word")
      if (newWords.isEmpty()) return@mapNotNull null
      val skip = IntentWords.phrasesIn(language, "intent.new-skip")
      val between =
        if (skip.isEmpty()) "[\\p{L}\\d.'-]+?$GAP"
        else "(?!(?:${WordLists.alternation(skip)})$END)[\\p{L}\\d.'-]+?$GAP"
      Regex(
        "(?iu)$START(?:${WordLists.alternation(newWords)})$GAP" +
          "(?:$between)?(?:${alternation("intent.new-kind")})$END"
      )
    }
  private val ALTERNATIVES = anywhere("intent.alternatives")
  private val COMPARE = anywhere("intent.compare")
  private val WHAT_IS = atStart("intent.what-is")
  private val WHEN = atStart("intent.when")
  private val HOW_TO = atStart("intent.how-to")
  private val LEARN = anywhere("intent.learn")

  private val GENERIC: Set<String> = IntentWords.words("query.generic")

  private fun anywhere(key: String) = IntentWords.anywhere(key)

  private fun atStart(key: String) = IntentWords.atStart(key)

  private fun alternation(key: String) = alternation(IntentWords.phrases(key))

  private fun alternation(phrases: Collection<String>): String = WordLists.alternation(phrases)

  @JvmStatic
  fun intentOf(request: String): GuideIntent? =
    when {
      COMPARE.containsMatchIn(request) -> GuideIntent.COMPARE
      ALTERNATIVES.containsMatchIn(request) -> GuideIntent.ALTERNATIVES
      LEARN.containsMatchIn(request) -> GuideIntent.LEARN
      WHEN.containsMatchIn(request) -> GuideIntent.WHEN
      windowOf(request) != null || NEWEST.containsMatchIn(request) -> GuideIntent.NEWEST
      WHAT_IS.containsMatchIn(request) -> GuideIntent.WHAT_IS
      HOW_TO.containsMatchIn(request) -> GuideIntent.HOW_TO
      NEW_KIND.any { it.containsMatchIn(request) } -> GuideIntent.NEWEST
      else -> null
    }

  private val TIME_WORDS = anywhere("time.word")

  private val YEAR = Regex("${START}20\\d\\d$END")

  private val NEW_WORDS = anywhere("new.word")

  @JvmStatic
  fun mentionsTime(request: String): Boolean =
    TIME_WORDS.containsMatchIn(request) || YEAR.containsMatchIn(request)

  @JvmStatic
  fun mentionsNewness(request: String): Boolean =
    mentionsTime(request) || NEW_WORDS.containsMatchIn(request)

  private val REQUIREMENT_WORDS = IntentWords.keys("requirement.").map(::anywhere)

  @JvmStatic
  fun constraintStated(constraint: String, request: String): Boolean {
    val words =
      constraint.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter {
        it.length >= 3 && it !in STOP
      }
    if (words.any { Regex("(?iu)$START${Regex.escape(it)}").containsMatchIn(request) }) return true
    return REQUIREMENT_WORDS.any { it.containsMatchIn(constraint) && it.containsMatchIn(request) }
  }

  private val STOP = IntentWords.words("constraint.stop")

  @JvmStatic
  fun windowOf(request: String): PlannedWindow? {
    earliest(PAST, request)?.let { m ->
      return window(m.groupValues[1], m.groupValues[2], WindowDirection.PAST)
    }
    earliest(NEXT, request)?.let { m ->
      return window(m.groupValues[1], m.groupValues[2], WindowDirection.NEXT)
    }
    THIS.find(request)?.let { m ->
      return window("", m.groupValues[1], WindowDirection.PAST)
    }
    if (TODAY.containsMatchIn(request)) return PlannedWindow(1, WindowUnit.DAY)
    return null
  }

  private fun earliest(rules: List<Regex>, request: String): MatchResult? =
    rules.mapNotNull { it.find(request) }.minByOrNull { it.range.first }

  private fun window(amount: String, unit: String, direction: WindowDirection): PlannedWindow? {
    val u = UNITS[unit.lowercase()] ?: return null
    return PlannedWindow(amount.toIntOrNull()?.takeIf { it > 0 } ?: 1, u, direction)
  }

  private val EVENT_KINDS = anywhere("when.event")
  private val STARTS = anywhere("when.start")

  @JvmStatic
  fun namesEvent(text: String): Boolean =
    EVENT_KINDS.containsMatchIn(text) ||
      (STARTS.containsMatchIn(text) && LEARN.containsMatchIn(text))

  private val KINDS = anywhere("subject.kind")

  @JvmStatic fun namesKind(text: String): Boolean = KINDS.containsMatchIn(text)

  @JvmStatic
  fun subjectWords(query: String): String =
    query
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() && it.lowercase().trim('.', ',', '?', '!') !in GENERIC }
      .joinToString(" ")
}

fun isSubject(result: MergedResult, subject: String): Boolean {
  val want = normalizedName(subject)
  if (want.isEmpty()) return false
  val names = listOf(result.title, result.canonicalUrl.value.trimEnd('/').substringAfterLast('/'))
  return names.any { n ->
    val have = normalizedName(n)
    have == want || TYPE_SUFFIX.any { have == "$want $it" || have == "$want$it" }
  }
}

private val TYPE_SUFFIX: Set<String> = IntentWords.words("subject.type-suffix")

private fun normalizedName(s: String): String =
  Normalizer.normalize(s, Normalizer.Form.NFKC)
    .lowercase()
    .substringAfterLast('/')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
