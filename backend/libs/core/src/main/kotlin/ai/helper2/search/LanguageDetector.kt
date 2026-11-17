package ai.helper2.search

import ai.helper2.shared.WordLists
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

class LanguageDetector {
  private val detector =
    LanguageDetectorBuilder.fromLanguages(*SUPPORTED.keys.toTypedArray())
      .withLowAccuracyMode()
      .withPreloadedLanguageModels()
      .build()

  fun detect(text: String?): String? {
    if (text == null || text.count(Char::isLetter) < MIN_LETTERS) return null
    val ranked =
      detector.computeLanguageConfidenceValues(text).entries.sortedByDescending { it.value }
    val best = ranked.firstOrNull() ?: return null
    val code = SUPPORTED[best.key] ?: return null
    if (code == "en") return "en"
    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
    val latin =
      text.filter(Char::isLetter).all {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
      }
    if (!latin || words.size >= LONG_TEXT_WORDS) {
      val second = ranked.getOrNull(1)?.value ?: 0.0
      return code.takeIf { best.value - second >= LONG_TEXT_MIN_GAP }
    }
    val functionWord = words.any { it in FUNCTION_WORDS[code].orEmpty() }
    val diacritic = text.any { it.isLetter() && it.code > 0x7F }
    return code.takeIf { functionWord || diacritic }
  }

  companion object {
    const val MIN_LETTERS = 6
    private const val LONG_TEXT_WORDS = 8
    private const val LONG_TEXT_MIN_GAP = 0.05
    private val WORD = Regex("[\\p{L}']+")

    val SUPPORTED: Map<Language, String> =
      mapOf(
        Language.ARABIC to "ar",
        Language.ARMENIAN to "hy",
        Language.BASQUE to "eu",
        Language.CATALAN to "ca",
        Language.DANISH to "da",
        Language.DUTCH to "nl",
        Language.ENGLISH to "en",
        Language.FINNISH to "fi",
        Language.FRENCH to "fr",
        Language.GERMAN to "de",
        Language.GREEK to "el",
        Language.HINDI to "hi",
        Language.HUNGARIAN to "hu",
        Language.INDONESIAN to "id",
        Language.IRISH to "ga",
        Language.ITALIAN to "it",
        Language.LITHUANIAN to "lt",
        Language.BOKMAL to "no",
        Language.NYNORSK to "no",
        Language.PORTUGUESE to "pt",
        Language.ROMANIAN to "ro",
        Language.RUSSIAN to "ru",
        Language.SERBIAN to "sr",
        Language.SPANISH to "es",
        Language.SWEDISH to "sv",
        Language.TAMIL to "ta",
        Language.TURKISH to "tr",
      )

    private val FUNCTION_WORDS: Map<String, Set<String>> =
      WordLists.table("i18n/language-function-words.properties")
  }
}
