package ai.helper2.shared

import java.net.IDN
import java.text.Normalizer

object LinkText {

  private val SCHEME_OR_WWW =
    Regex("(?i)\\b[a-z][a-z0-9+.-]*://|(?<![\\w/])//[\\w-]|\\bwww\\.|\\bhxxps?\\b")

  private const val LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{M}\\p{N}-]{0,61}[\\p{L}\\p{M}\\p{N}])?"

  private val HOST =
    Regex(
      "(?i)(?<![\\p{L}\\p{M}\\p{N}_-])(?:$LABEL\\.)+(\\p{L}[\\p{L}\\p{M}]{1,62}|xn--[a-z0-9-]{2,59})" +
        "(?![\\p{L}\\p{M}\\p{N}_-])(?!\\.[\\p{L}\\p{N}])"
    )

  private val AFTER_HOST = Regex("/|:\\d|\\?[\\w=]|#\\w")

  private const val OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"

  private val IPV4 = Regex("(?<![\\d.])$OCTET(?:\\.$OCTET){3}(?!\\d|\\.\\d)")

  private const val HEX = "[0-9a-f]{1,4}"

  private val IPV6 =
    Regex(
      "(?i)(?<![\\p{L}\\p{N}:])(?:(?:$HEX:){7}$HEX|$HEX(?::$HEX){0,6}::(?:$HEX(?::$HEX){0,6})?|::$HEX(?::$HEX){0,6})(?![\\p{L}\\p{N}:])"
    )

  private val SCHEME_WITHOUT_SLASHES =
    Regex("(?i)\\b(?:(?:java|vb|live)script|mailto|magnet):|\\bdata:[a-z]+/[a-z0-9.+-]+")

  private val FILE_NAME_TLDS = setOf("properties", "tf", "py", "rs", "md", "java")

  private val KNOWN_NAMES = setOf("asp.net", "vb.net", "ado.net")

  private val TLDS: Set<String> =
    LinkText::class.java.getResourceAsStream("iana-tlds.txt")!!.bufferedReader().useLines { lines ->
      lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    }

  private val CODE_SPAN = Regex("`[^`\\n]*`")

  private val PERCENT_ESCAPE = Regex("%([0-9a-fA-F]{2})")
  private val HTML_ENTITY =
    Regex("&(?:#(\\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|(colon|sol|period|tab|newline));?")
  private val NAMED_ENTITIES =
    mapOf("colon" to ":", "sol" to "/", "period" to ".", "tab" to "\t", "newline" to "\n")
  private val URL_IGNORED_WHITESPACE = Regex("[\\t\\r\\n]")
  private val INVISIBLE =
    Regex(
      "[\\p{Cf}\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\uFE00-\\uFE0F]|\\uDB40[\\uDC00-\\uDDEF]"
    )
  private val NON_SPACING_MARKS = Regex("\\p{Mn}")
  private val WIDE_DOTS = Regex("[\\u3002\\uFF0E\\uFF61]")
  private val BRACKETED_DOT = Regex("(?i)\\s*(?:\\[\\.]|\\(\\.\\)|\\[dot]|\\(dot\\))\\s*")
  private val SPOKEN_DOT = Regex("(?i)(?<=[\\p{L}\\p{N}])\\s+dot\\s+(?=[\\p{L}\\p{N}])")

  @JvmStatic fun containsLink(text: String): Boolean = containsLink(text, emptyList())

  @JvmStatic
  fun containsLink(text: String, ownNames: Collection<String>): Boolean {
    val names = ownNames.flatMap { it.lowercase().split(Regex("[\\s/]+")) + it.lowercase() }.toSet()
    val shown = decoded(text)
    return looksLikeLink(shown, names) || looksLikeLink(NON_SPACING_MARKS.replace(shown, ""), names)
  }

  @JvmStatic
  fun containsLinkOutsideCode(text: String): Boolean = containsLink(CODE_SPAN.replace(text, " "))

  private fun looksLikeLink(s: String, names: Set<String>): Boolean =
    SCHEME_OR_WWW.containsMatchIn(s) ||
      IPV4.containsMatchIn(s) ||
      IPV6.containsMatchIn(s) ||
      HOST.findAll(s).any { isHost(it, s, names) } ||
      SCHEME_WITHOUT_SLASHES.containsMatchIn(URL_IGNORED_WHITESPACE.replace(s, ""))

  private fun isHost(match: MatchResult, text: String, names: Set<String>): Boolean {
    val tld = match.groupValues[1].lowercase()
    if (!isTopLevelDomain(tld)) return false
    if (AFTER_HOST.matchesAt(text, match.range.last + 1)) return true
    val name = match.value.lowercase()
    return name !in names && name !in KNOWN_NAMES && tld !in FILE_NAME_TLDS
  }

  private fun isTopLevelDomain(label: String): Boolean {
    if (label in TLDS) return true
    return try {
      IDN.toASCII(label).lowercase() in TLDS
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  private fun decoded(text: String): String {
    var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
    repeat(2) {
      s = PERCENT_ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
      s = HTML_ENTITY.replace(s, ::entity)
    }
    s = INVISIBLE.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), "")
    s = WIDE_DOTS.replace(s, ".")
    s = BRACKETED_DOT.replace(s, ".")
    return SPOKEN_DOT.replace(s, ".")
  }

  private fun entity(m: MatchResult): String {
    val (dec, hex, name) = m.destructured
    val cp =
      when {
        dec.isNotEmpty() -> dec.toInt()
        hex.isNotEmpty() -> hex.toInt(16)
        else -> return NAMED_ENTITIES.getValue(name.lowercase())
      }
    return if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
  }
}
