package ai.helper2.shared

import java.util.Locale
import java.util.regex.Pattern

class CategoryWords private constructor(private val list: List<String>) {

  private val patterns: List<Pattern> = list.map {
    Pattern.compile("\\b" + Pattern.quote(normal(it)))
  }

  fun words(): List<String> = list

  fun matchedIn(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    val normal = normal(text)
    return list.indices.filter { patterns[it].matcher(normal).find() }.map { list[it] }
  }

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

    @JvmStatic
    fun of(words: List<String?>?): CategoryWords =
      CategoryWords(
        (words ?: emptyList())
          .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) }
          .distinct()
      )

    @JvmStatic
    fun normal(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[-_]+"), " ")
  }
}
