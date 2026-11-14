package ai.helper2.guide

import ai.helper2.guide.GuideValidator.Rejection
import ai.helper2.shared.LinkText.containsLink
import ai.helper2.shared.WordBounds

class EvidenceGate(
  private val minQuoteChars: Int = GuideValidator.MIN_QUOTE_CHARS,
  private val minQuoteWords: Int = GuideValidator.MIN_QUOTE_WORDS,
  private val maxQuote: Int = GuideValidator.MAX_QUOTE_CHARS,
) {

  fun claimProblem(text: String, quote: String, candidate: Candidate, maxChars: Int): Rejection? {
    val quoted = quote.trim()
    return when {
      text.isBlank() -> Rejection.NO_REASON
      text.length > maxChars -> Rejection.REASON_TOO_LONG
      containsLink(text, listOf(candidate.result.title)) -> Rejection.REASON_INVALID
      quoted.length > maxQuote -> Rejection.QUOTE_TOO_LONG
      else -> quoteProblem(normalize(quoted), normalize(candidate.text))
    }
  }

  fun quoteProblem(quote: String, text: String): Rejection? {
    if (quote.isEmpty() || !containsWords(text, quote)) return Rejection.QUOTE_NOT_IN_TEXT
    val requiredChars = minOf(minQuoteChars, text.length)
    val requiredWords = minOf(minQuoteWords, wordCount(text))
    return if (quote.length >= requiredChars && wordCount(quote) >= requiredWords) null
    else Rejection.QUOTE_TOO_SHORT
  }

  fun normalize(s: String) =
    s.replace('‹', '<').replace('›', '>').lowercase().replace(Regex("\\s+"), " ").trim()

  fun containsWords(text: String, quote: String) = WordBounds.containsOnWords(text, quote)

  private fun wordCount(s: String) = s.split(' ').count { it.isNotBlank() }
}
