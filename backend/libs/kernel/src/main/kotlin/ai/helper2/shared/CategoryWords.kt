package ai.helper2.shared

import java.util.Locale
import java.util.regex.Pattern

/**
 * A category's words (ADR 0022, 0013), each matched at the start of a word in lower case, with
 * hyphens and underscores read as spaces ("fine tuning platform" fits "Fine-Tuning platform",
 * "lora" does not fit "flora"). The patterns are compiled once. The discovery collectors tag their
 * results with them, and [CategoryMatcher] assigns categories with the same patterns.
 */
class CategoryWords private constructor(private val list: List<String>) {

  private val patterns: List<Pattern> = list.map {
    Pattern.compile("\\b" + Pattern.quote(normal(it)))
  }

  /** The words, lower case, in the configured order. */
  fun words(): List<String> = list

  /** The words found in [text], in the configured order. */
  fun matchedIn(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    val normal = normal(text)
    return list.indices.filter { patterns[it].matcher(normal).find() }.map { list[it] }
  }

  /** True when one of the words is in [text]. */
  fun matches(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    val normal = normal(text)
    return patterns.any { it.matcher(normal).find() }
  }

  override fun equals(other: Any?): Boolean = other is CategoryWords && other.list == list

  override fun hashCode(): Int = list.hashCode()

  override fun toString(): String = list.toString()

  companion object {
    @JvmField val NONE = CategoryWords(emptyList())

    /** Trimmed, lower case, without blanks and repeats. */
    @JvmStatic
    fun of(words: List<String?>?): CategoryWords =
      CategoryWords(
        (words ?: emptyList())
          .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) }
          .distinct()
      )

    /** Lower case, hyphens and underscores as spaces: "Text-to-Speech" reads "text to speech". */
    @JvmStatic
    fun normal(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[-_]+"), " ")
  }
}
