package ai.helper2.shared

object WordBounds {

  private const val WORD_PART = "\\p{L}\\p{Nd}_\\p{M}"

  private const val SPACELESS =
    "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}\\p{IsTibetan}"

  const val START = "(?:(?<![$WORD_PART])|(?<=[$SPACELESS])|(?=[$SPACELESS]))"

  const val END = "(?:(?![$WORD_PART])|(?<=[$SPACELESS])|(?=[$SPACELESS]))"

  const val WORD_ENDING = "[$WORD_PART]*"

  const val GAP = "(?:(?<=[$SPACELESS])\\s*|\\s*(?=[$SPACELESS])|\\s+)"

  const val HYPHENATED_START = "(?:(?<![$WORD_PART-])|(?<=[$SPACELESS])|(?=[$SPACELESS]))"

  const val HYPHENATED_END = "(?:(?![$WORD_PART-])|(?<=[$SPACELESS])|(?=[$SPACELESS]))"

  private val WITHOUT_SPACES =
    setOf(
      Character.UnicodeScript.HAN,
      Character.UnicodeScript.HIRAGANA,
      Character.UnicodeScript.KATAKANA,
      Character.UnicodeScript.THAI,
      Character.UnicodeScript.LAO,
      Character.UnicodeScript.KHMER,
      Character.UnicodeScript.MYANMAR,
      Character.UnicodeScript.TIBETAN,
    )

  private val MARKS =
    setOf(
      Character.NON_SPACING_MARK.toInt(),
      Character.COMBINING_SPACING_MARK.toInt(),
      Character.ENCLOSING_MARK.toInt(),
    )

  @JvmStatic
  fun containsOnWords(text: String, part: String): Boolean {
    if (part.isEmpty()) return false
    var at = text.indexOf(part)
    while (at >= 0) {
      if (isBoundary(text, at) && isBoundary(text, at + part.length)) return true
      at = text.indexOf(part, at + 1)
    }
    return false
  }

  @JvmStatic
  fun isBoundary(text: String, at: Int): Boolean {
    if (at <= 0 || at >= text.length) return true
    val before = text.codePointBefore(at)
    val after = text.codePointAt(at)
    if (!isWordPart(before) || !isWordPart(after)) return true
    return writtenWithoutSpaces(before) || writtenWithoutSpaces(after)
  }

  private fun isWordPart(cp: Int) =
    Character.isLetterOrDigit(cp) || cp == '_'.code || Character.getType(cp) in MARKS

  @JvmStatic fun isSpaceless(cp: Int): Boolean = Character.UnicodeScript.of(cp) in WITHOUT_SPACES

  private fun writtenWithoutSpaces(cp: Int) = isSpaceless(cp)
}
