package ai.helper2.shared

import java.text.Normalizer
import java.util.Properties

class WordLists
private constructor(private val name: String, private val byLanguage: Map<String, Properties>) {

  fun phrases(key: String): List<String> {
    require(byLanguage.values.any { it.containsKey(key) }) { "no language of $name defines $key" }
    return LANGUAGES.flatMap { phrasesIn(it, key) }.distinct()
  }

  fun phrasesIn(language: String, key: String): List<String> =
    byLanguage[language]
      ?.getProperty(key)
      .orEmpty()
      .split(',')
      .map { Normalizer.normalize(it.trim(), Normalizer.Form.NFC) }
      .filter { it.isNotEmpty() }

  fun words(key: String): Set<String> = phrases(key).mapTo(linkedSetOf()) { it.lowercase() }

  fun wordsIn(language: String, key: String): Set<String> =
    phrasesIn(language, key).mapTo(linkedSetOf()) { it.lowercase() }

  fun keys(prefix: String): List<String> =
    byLanguage.values
      .flatMap { it.stringPropertyNames() }
      .filter { it.startsWith(prefix) }
      .distinct()
      .sorted()

  fun allPhrases(): List<String> =
    byLanguage.keys
      .flatMap { lang ->
        byLanguage.getValue(lang).stringPropertyNames().flatMap { phrasesIn(lang, it) }
      }
      .distinct()

  fun anywhere(key: String): Regex = anywhere(phrases(key))

  fun atStart(key: String): Regex =
    Regex("(?iu)^\\s*(?:${alternation(phrases(key))})${WordBounds.END}")

  companion object {
    @JvmField val LANGUAGES = listOf("en", "it", "de", "fr", "es", "zh")

    @JvmStatic
    fun load(name: String): WordLists =
      WordLists(name, LANGUAGES.associateWith { read("i18n/${name}_$it.properties") })

    @JvmStatic
    fun anywhere(phrases: Collection<String>): Regex =
      Regex("(?iu)${WordBounds.START}(?:${alternation(phrases)})${WordBounds.END}")

    @JvmStatic
    fun alternation(phrases: Collection<String>): String =
      phrases.sortedByDescending { it.length }.joinToString("|", transform = ::pattern)

    @JvmStatic
    fun table(path: String): Map<String, Set<String>> {
      val rows = read(path)
      return rows.stringPropertyNames().associateWith { code ->
        rows
          .getProperty(code)
          .split(' ')
          .filter { it.isNotEmpty() }
          .mapTo(linkedSetOf()) { Normalizer.normalize(it, Normalizer.Form.NFC) }
      }
    }

    @JvmStatic
    fun separator(words: Collection<String>): (String) -> String {
      val spaceless = words.filter { w -> w.any { WordBounds.isSpaceless(it.code) } }
      if (spaceless.isEmpty()) return { it }
      val any =
        Regex(
          spaceless.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape)
        )
      return { text -> any.replace(text) { " ${it.value} " } }
    }

    private fun pattern(phrase: String): String =
      phrase
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString("\\s+") { word ->
          if (word.endsWith('*')) Regex.escape(word.dropLast(1)) + WordBounds.WORD_ENDING
          else Regex.escape(word)
        }

    private fun read(path: String): Properties {
      val stream =
        WordLists::class.java.classLoader.getResourceAsStream(path) ?: error("missing $path")
      return Properties().apply { stream.reader(Charsets.UTF_8).use { load(it) } }
    }
  }
}
