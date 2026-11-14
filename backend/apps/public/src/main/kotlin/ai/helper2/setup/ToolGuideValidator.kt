package ai.helper2.setup

import ai.helper2.shared.LinkText
import ai.helper2.shared.WordBounds

class ToolGuideValidator {

  data class Result(
    val sections: List<GuideSection>,
    val rejected: Int,
    val rejectedBy: Map<String, Int> = emptyMap(),
  )

  enum class Rejection {
    SECTION,
    SOURCE,
    TEXT,
    QUOTE_LENGTH,
    QUOTE_NOT_FOUND,
    DUPLICATE,
  }

  fun validate(proposal: ProposedGuide, materials: List<Material>): Result {
    val byKey = materials.associateBy { it.key }
    val parsed = materials.associate { it.key to Parsed(it.text) }
    val rejectedBy = sortedMapOf<String, Int>()
    fun reject(r: Rejection) = rejectedBy.merge(r.name, 1, Int::plus)
    val kept = linkedMapOf<GuideSectionKind, MutableList<GuideStep>>()
    for (section in proposal.sections) {
      val kind =
        GuideSectionKind.entries.firstOrNull {
          it.name.equals(section.kind.trim(), ignoreCase = true)
        }
      for (step in section.steps) {
        if (kind == null) {
          reject(Rejection.SECTION)
          continue
        }
        val out = check(step, byKey[step.materialKey.trim()], parsed[step.materialKey.trim()])
        if (out is Rejection) {
          reject(out)
          continue
        }
        out as GuideStep
        val list = kept.getOrPut(kind) { mutableListOf() }
        val seen =
          kept.values.flatten().any {
            it.text.equals(out.text, ignoreCase = true) ||
              (out.code != null && it.code?.text == out.code.text)
          }
        if (list.size >= MAX_STEPS_PER_SECTION || seen) {
          reject(Rejection.DUPLICATE)
        } else {
          list += out
        }
      }
    }
    return Result(
      GuideSectionKind.entries.mapNotNull { k ->
        kept[k]?.takeIf { it.isNotEmpty() }?.let { GuideSection(k, it) }
      },
      rejectedBy.values.sum(),
      rejectedBy,
    )
  }

  private fun check(step: ProposedStep, material: Material?, parsed: Parsed?): Any {
    if (material == null || parsed == null) return Rejection.SOURCE
    val text = step.text.trim()
    val quote = unmask(step.quote).trim()
    if (text.isEmpty() || text.length > MAX_TEXT_CHARS || LinkText.containsLinkOutsideCode(text))
      return Rejection.TEXT
    if (!parsed.isQuotable(quote)) return Rejection.QUOTE_LENGTH
    if (!parsed.quotes(quote)) return Rejection.QUOTE_NOT_FOUND
    val copied = step.code?.let { unmask(it).trim().ifEmpty { null } }?.let(parsed::code)
    val language = step.language?.trim()?.lowercase()?.takeIf { LANGUAGE.matches(it) }
    return GuideStep(
      text,
      material.key,
      quote,
      copied?.let { Code(language ?: parsed.languageOf(it), it) },
      copied != null && cautious(copied),
    )
  }

  private class Line(val key: String, val source: List<String>)

  private class Parsed(text: String) {
    private val prose = loose(text)
    private val lines = text.lines().map(::loose).filter { it.isNotEmpty() }.toSet()

    fun isQuotable(quote: String): Boolean {
      val q = loose(quote)
      if (quote.length > MAX_QUOTE_CHARS) return false
      val long = q.length >= MIN_QUOTE_CHARS && words(q) >= MIN_QUOTE_WORDS
      return long || (words(q) >= MIN_LINE_QUOTE_WORDS && q in lines)
    }

    fun quotes(quote: String): Boolean = WordBounds.containsOnWords(prose, loose(quote))

    private fun words(s: String) = s.split(' ').count { it.isNotBlank() }

    private val blocks = Markdown.blocks(text).map { it to logicalLines(it.lines) }
    private val spans = Markdown.inlineSpans(text).associateBy(::tight)

    fun code(code: String): String? {
      val wanted = logicalLines(code.lines()).map { it.key }
      if (wanted.isEmpty() || wanted.size > MAX_CODE_LINES) return null
      if (wanted.size == 1)
        spans[wanted[0]]?.let {
          return it.trim()
        }
      for ((_, lines) in blocks) {
        val keys = lines.map { it.key }
        for (from in 0..keys.size - wanted.size) {
          if (keys.subList(from, from + wanted.size) == wanted) {
            return output(lines.subList(from, from + wanted.size).flatMap { it.source })
          }
        }
      }
      return null
    }

    fun languageOf(code: String): String? {
      val first = tight(code.lines().first { it.isNotBlank() })
      return blocks
        .firstOrNull { (_, lines) ->
          lines.any { l -> l.source.any { tight(it).removePrefix("$ ") == first } }
        }
        ?.first
        ?.language
        ?.lowercase()
        ?.takeIf { LANGUAGE.matches(it) }
    }

    private fun output(source: List<String>): String {
      val lines =
        source
          .map { it.trimEnd() }
          .map { l ->
            val i = l.indexOfFirst { !it.isWhitespace() }
            if (i >= 0 && (l.startsWith("$ ", i) || l.startsWith("> ", i)))
              l.substring(0, i) + l.substring(i + 2)
            else l
          }
      val indent =
        lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } }
          ?: 0
      return lines.joinToString("\n") {
        if (it.length >= indent) it.substring(indent) else it.trim()
      }
    }
  }

  companion object {
    const val MAX_STEPS_PER_SECTION = 8
    const val MAX_TEXT_CHARS = 280
    const val MIN_QUOTE_CHARS = 30
    const val MIN_QUOTE_WORDS = 5
    const val MIN_LINE_QUOTE_WORDS = 3
    const val MAX_QUOTE_CHARS = 500
    const val MAX_CODE_LINES = 40

    private val LANGUAGE = Regex("[a-z0-9+#-]{1,16}")
    private const val RUNNER =
      "(?:(?:ba|z|da|k|fi)?sh|python[0-9.]*|perl|ruby|node|php|pwsh|powershell|iex|invoke-expression)"
    private const val DOWNLOAD = "(?:curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)"
    private val CAUTION =
      Regex(
        listOf(
            "\\|\\s*(?:sudo(?:\\s+-\\S+)*\\s+)?(?:env(?:\\s+\\S+=\\S*)*\\s+)?$RUNNER(?![\\w.-])",
            "[$<]\\(\\s*$DOWNLOAD\\b",
            "\\b(?:invoke-expression|iex)\\s*\\(",
            "\\bsudo\\b",
            "\\bchmod\\s+(?:-\\S+\\s+)*0?777\\b",
            "\\brm\\s+(?:-\\S+\\s+)*-[a-z]*(?:r[a-z]*f|f[a-z]*r)",
            "\\brm\\s+(?:-\\S+\\s+)*--recursive\\b",
          )
          .joinToString("|"),
        RegexOption.IGNORE_CASE,
      )

    fun cautious(code: String) = CAUTION.containsMatchIn(tight(code.replace(CONTINUATION, " ")))

    private val CONTINUATION = Regex("\\\\[ \\t]*\\r?\\n")

    private fun unmask(s: String) = s.replace('‹', '<').replace('›', '>')

    private fun logicalLines(lines: List<String>): List<Line> {
      val out = mutableListOf<Line>()
      var key: String? = null
      var source = mutableListOf<String>()
      for (raw in lines) {
        val t = tight(raw).removePrefix("$ ").removePrefix("> ")
        if (t.isEmpty() && key == null) continue
        source += raw
        val joined = if (key == null) t else "$key $t"
        if (joined.endsWith("\\")) {
          key = joined.dropLast(1).trimEnd()
        } else {
          out += Line(joined, source)
          key = null
          source = mutableListOf()
        }
      }
      key?.let { out += Line(it, source) }
      return out
    }

    fun loose(s: String): String =
      s.replace(FENCE, " ")
        .replace(MD_LINK, "$1")
        .replace(HTML_TAG, " ")
        .replace(Regex("[*_`>#|]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    private val MD_LINK = Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)")
    private val HTML_TAG = Regex("<[^>]{1,200}>")
    private val FENCE = Regex("(`{3,}|~{3,})[A-Za-z0-9_+#.-]*")

    fun tight(s: String): String = s.replace(Regex("\\s+"), " ").trim()
  }
}
