package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.shared.LinkText
import ai.helper2.shared.ResourceType
import java.text.Normalizer
import java.time.LocalDate

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

  private val PAST =
    Regex("(?i)\\b(?:${alternation("period.past")})\\s+(?:(\\d{1,3})\\s+)?($UNIT)\\b")

  private val THIS = Regex("(?i)\\b(?:${alternation("period.this")})\\s+($UNIT)\\b")

  private val NEXT =
    Regex("(?i)\\b(?:${alternation("period.next")})\\s+(?:(\\d{1,3})\\s+)?($UNIT)\\b")

  private val TODAY = anywhere("period.today")

  private val NEWEST = anywhere("intent.newest")

  private val NEW_KIND =
    Regex(
      "(?i)\\b(?:${alternation("intent.new-word")})\\s+" +
        "(?:(?!(?:${alternation("intent.new-skip")})\\b)[\\p{L}\\d.'-]+\\s+)?(?:${alternation("intent.new-kind")})\\b"
    )
  private val ALTERNATIVES = anywhere("intent.alternatives")
  private val COMPARE = anywhere("intent.compare")
  private val WHAT_IS = atStart("intent.what-is")
  private val WHEN = atStart("intent.when")
  private val HOW_TO = atStart("intent.how-to")
  private val LEARN = anywhere("intent.learn")

  private val GENERIC: Set<String> =
    IntentWords.phrases("query.generic").map { it.lowercase() }.toSet()

  private fun anywhere(key: String) = Regex("(?i)\\b(?:${alternation(key)})\\b")

  private fun atStart(key: String) = Regex("(?i)^\\s*(?:${alternation(key)})\\b")

  private fun alternation(key: String) = alternation(IntentWords.phrases(key))

  private fun alternation(phrases: Collection<String>): String =
    phrases
      .sortedByDescending { it.length }
      .joinToString("|") { p -> p.split(' ').joinToString("\\s+") { Regex.escape(it) } }

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
      NEW_KIND.containsMatchIn(request) -> GuideIntent.NEWEST
      else -> null
    }

  private val TIME_WORDS =
    Regex(
      "(?i)\\b(days?|weeks?|months?|years?|today|yesterday|tonight|weekend|since|20\\d\\d|" +
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
        "giorn[oi]|settiman[ae]|mes[ei]|ann[oi]|oggi|ieri|da quando)\\b"
    )

  private val NEW_WORDS =
    Regex(
      "(?i)\\b(new|newest|latest|recent(ly)?|upcoming|launched|released|born|nuov[oaie]|recent[ei])\\b"
    )

  @JvmStatic fun mentionsTime(request: String): Boolean = TIME_WORDS.containsMatchIn(request)

  @JvmStatic
  fun mentionsNewness(request: String): Boolean =
    mentionsTime(request) || NEW_WORDS.containsMatchIn(request)

  private val REQUIREMENT_WORDS =
    listOf(
      Regex("(?i)\\b(free|gratis|gratuit\\w*|no cost|without paying|kostenlos)\\b"),
      Regex("(?i)\\b(self[- ]?host\\w*|on[- ]?prem\\w*|local(ly)?|my own server|in locale)\\b"),
      Regex("(?i)\\b(offline|without internet|no internet|air[- ]?gapped|local(ly)?)\\b"),
      Regex("(?i)\\b(open[- ]?source|oss|mit|apache|bsd|gpl|licen[cs]e\\w*|permissive)\\b"),
    )

  @JvmStatic
  fun constraintStated(constraint: String, request: String): Boolean {
    val words =
      constraint.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter {
        it.length >= 3 && it !in STOP
      }
    if (words.any { Regex("(?i)\\b${Regex.escape(it)}").containsMatchIn(request) }) return true
    return REQUIREMENT_WORDS.any { it.containsMatchIn(constraint) && it.containsMatchIn(request) }
  }

  private val STOP =
    setOf(
      "works",
      "runs",
      "the",
      "and",
      "with",
      "must",
      "has",
      "have",
      "licence",
      "license",
      "support",
      "supports",
    )

  @JvmStatic
  fun windowOf(request: String): PlannedWindow? {
    PAST.find(request)?.let { m ->
      return window(m.groupValues[1], m.groupValues[2], WindowDirection.PAST)
    }
    NEXT.find(request)?.let { m ->
      return window(m.groupValues[1], m.groupValues[2], WindowDirection.NEXT)
    }
    THIS.find(request)?.let { m ->
      return window("", m.groupValues[1], WindowDirection.PAST)
    }
    if (TODAY.containsMatchIn(request)) return PlannedWindow(1, WindowUnit.DAY)
    return null
  }

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

private val TYPE_SUFFIX: List<String> =
  IntentWords.phrases("subject.type-suffix").map { it.lowercase() }

private fun normalizedName(s: String): String =
  Normalizer.normalize(s, Normalizer.Form.NFKC)
    .lowercase()
    .substringAfterLast('/')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

internal object IntentWords {
  private val LANGUAGES = listOf("en", "it")

  private val bundles: List<java.util.Properties> = LANGUAGES.map { lang ->
    val path = "i18n/intent-words_$lang.properties"
    val stream =
      IntentWords::class.java.classLoader.getResourceAsStream(path) ?: error("missing $path")
    java.util.Properties().apply { stream.reader(Charsets.UTF_8).use { load(it) } }
  }

  fun phrases(key: String): List<String> {
    require(bundles.any { it.containsKey(key) }) { "no language defines $key" }
    return bundles
      .flatMap { b ->
        b.getProperty(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
      }
      .distinct()
  }
}
