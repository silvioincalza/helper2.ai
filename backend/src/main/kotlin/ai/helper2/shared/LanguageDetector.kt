package ai.helper2.shared

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import java.text.Normalizer

/**
 * Detects the language of a query or of catalog text, among the languages Postgres can stem.
 * Returns an ISO 639-1 code, or null when the guess is not backed by evidence: search then uses
 * English + language-neutral matching only, never a guessed stemmer (and never translates).
 * The code maps to a text search config in SQL with `h2_ts_config(code)` (V1__schema.sql).
 *
 * Lingua alone is not enough for short Latin-script queries: measured 2026-10-26, "mcp postgres" came
 * out Norwegian and "vector database" Romanian, as confident as "app per tradurre" came out Italian.
 * So a short Latin-script text must also contain a function word of the detected language ("per",
 * "para", "zum"...) or a letter with a diacritic. Long texts and other scripts trust Lingua.
 */
class LanguageDetector {

    // High-accuracy models for 27 languages ran a test JVM out of memory; low accuracy (trigrams) fits.
    private val detector = LanguageDetectorBuilder.fromLanguages(*SUPPORTED.keys.toTypedArray())
        .withLowAccuracyMode()
        .withPreloadedLanguageModels()
        .build()

    fun detect(text: String?): String? {
        if (text == null || text.count(Char::isLetter) < MIN_LETTERS) return null
        val ranked = detector.computeLanguageConfidenceValues(text).entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val code = SUPPORTED[best.key] ?: return null
        if (code == "en") return "en"
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        val latin = text.filter(Char::isLetter).all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
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

        /** Lingua language -> ISO code, only those with a Postgres snowball stemmer (same list as h2_ts_config). */
        val SUPPORTED: Map<Language, String> = mapOf(
            Language.ARABIC to "ar", Language.ARMENIAN to "hy", Language.BASQUE to "eu", Language.CATALAN to "ca",
            Language.DANISH to "da", Language.DUTCH to "nl", Language.ENGLISH to "en", Language.FINNISH to "fi",
            Language.FRENCH to "fr", Language.GERMAN to "de", Language.GREEK to "el", Language.HINDI to "hi",
            Language.HUNGARIAN to "hu", Language.INDONESIAN to "id", Language.IRISH to "ga", Language.ITALIAN to "it",
            Language.LITHUANIAN to "lt", Language.BOKMAL to "no", Language.NYNORSK to "no", Language.PORTUGUESE to "pt",
            Language.ROMANIAN to "ro", Language.RUSSIAN to "ru", Language.SERBIAN to "sr", Language.SPANISH to "es",
            Language.SWEDISH to "sv", Language.TAMIL to "ta", Language.TURKISH to "tr",
        )

        val CODES: Set<String> = SUPPORTED.values.toSet()

        /** Short, common function words, none of them common in English text: evidence that a short Latin-script query is really in that language. */
        private val FUNCTION_WORDS: Map<String, Set<String>> = mapOf(
            "it" to "per di da con che il lo la gli le un una del della dei delle come cosa quale su".words(),
            "es" to "para de del con que el la los las un una unos como qué cuál en por sin".words(),
            "pt" to "para de do da dos das com que o os as um uma como em por sem".words(),
            "fr" to "pour de du des avec que le la les un une comment quel quelle en sans sur d' l'".words(),
            "de" to "für zum zur von mit und der die das ein eine einen wie was welche im ohne auf".words(),
            "nl" to "voor van met en de het een hoe wat welke zonder op naar".words(),
            "ca" to "per de amb que el la els les un una com en sense".words(),
            "ro" to "pentru de cu și că un o cum ce care în fără la".words(),
            "da" to "til af med og en et hvordan hvad som uden på".words(),
            "sv" to "för av med och en ett hur vad som utan på till".words(),
            "no" to "av med og et hvordan hva som uten på til".words(),
            "fi" to "ja tai kanssa miten mikä joka ilman".words(),
            "hu" to "és az egy hogyan mi ami nélkül".words(),
            "tr" to "için ile ve bir nasıl ne hangi olmadan".words(),
            "id" to "untuk dari dengan dan yang cara apa tanpa".words(),
            "lt" to "ir su be kaip kas".words(),
            "ga" to "do le agus an na conas cad gan".words(),
            "eu" to "eta bat nola zer gabe".words(),
        )

        private fun String.words(): Set<String> =
            split(' ').map { Normalizer.normalize(it, Normalizer.Form.NFC) }.toSet()
    }
}
