package ai.helper2.scout

import ai.helper2.shared.WebSources
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

enum class GapKind(val label: String) {
  GITHUB_README("github-readme"),
  HF_MODEL_CARD("hf-model-card"),
  OFFICIAL_PAGE("official-page"),
}

data class GapTarget
@JvmOverloads
constructor(
  val kind: GapKind,
  val owner: String,
  val name: String,
  val anchorRecordId: UUID,
  val claimedPackage: String? = null,
) {
  val label: String
    get() = "${kind.label}:$owner/$name"
}

data class ExtraText(
  val sourceUrl: URI,
  val retrievedAt: Instant,
  val raw: String,
  val license: String?,
  val archived: Boolean? = null,
)

fun interface ExtraEvidence {
  fun fetch(target: GapTarget): ExtraText?
}

fun interface GapBudget {
  fun tryAcquire(): Boolean

  fun hasRoom(): Boolean = true
}

data class GapSourceRecord(
  val id: UUID,
  val sourceId: String,
  val externalId: String,
  val sourceUrl: String,
  val repositoryUrl: String?,
)

object GapTargets {
  private val GITHUB_SOURCES = setOf("github", "github-awesome")

  private fun namesRepository(sourceId: String): Boolean =
    sourceId in GITHUB_SOURCES || WebSources.isDiscovery(sourceId)

  private val HF_SOURCES = setOf("hf-models", "hf-trending", "hf-provider-orgs")

  private const val MCP = "mcp-registry"

  private val PACKAGE_SOURCES = setOf("npm", "pypi")

  private const val INSTALLERS =
    "npm i|npm install|yarn add|pnpm add|bun add|npx|pip install|pip3 install|uv add|uv pip install|poetry add|pipx install"

  @JvmStatic
  fun namesPackage(readme: String, packageName: String): Boolean =
    Regex(
        "(?:$INSTALLERS)\\s+(?:-{1,2}[\\w-]+\\s+)*[\"']?" +
          Regex.escape(packageName) +
          "(?![\\w./-])",
        RegexOption.IGNORE_CASE,
      )
      .containsMatchIn(readme)

  private val GITHUB =
    Regex("^https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})$")
  private val HUB =
    Regex(
      "^https://huggingface\\.co/([A-Za-z0-9][A-Za-z0-9._-]{0,95})/([A-Za-z0-9][A-Za-z0-9._-]{0,95})$"
    )

  private val MCP_GITHUB_NAMESPACE = Regex("^io\\.github\\.([A-Za-z0-9-]{1,39})/.+$")

  @JvmStatic
  fun select(records: List<GapSourceRecord>): GapTarget? {
    records
      .firstNotNullOfOrNull { r ->
        if (namesRepository(r.sourceId)) github(r.sourceUrl, r.id) else null
      }
      ?.let {
        return it
      }
    records
      .firstNotNullOfOrNull { r -> if (r.sourceId in HF_SOURCES) hub(r.sourceUrl, r.id) else null }
      ?.let {
        return it
      }
    records
      .firstNotNullOfOrNull { r ->
        if (r.sourceId != MCP) return@firstNotNullOfOrNull null
        val namespaceOwner =
          MCP_GITHUB_NAMESPACE.find(r.externalId)?.groupValues?.get(1)
            ?: return@firstNotNullOfOrNull null
        val target =
          github(r.repositoryUrl ?: r.sourceUrl, r.id) ?: return@firstNotNullOfOrNull null
        target.takeIf { it.owner.equals(namespaceOwner, ignoreCase = true) }
      }
      ?.let {
        return it
      }
    return records.firstNotNullOfOrNull { r ->
      if (r.sourceId !in PACKAGE_SOURCES) return@firstNotNullOfOrNull null
      github(r.repositoryUrl ?: return@firstNotNullOfOrNull null, r.id)
        ?.copy(claimedPackage = r.externalId)
    }
  }

  private fun github(url: String, recordId: UUID): GapTarget? {
    val m = GITHUB.matchEntire(trimmed(url).removeSuffix(".git")) ?: return null
    val (owner, name) = m.destructured
    return if (name == "." || name == "..") null
    else GapTarget(GapKind.GITHUB_README, owner, name, recordId)
  }

  private fun hub(url: String, recordId: UUID): GapTarget? {
    val m = HUB.matchEntire(trimmed(url)) ?: return null
    val (owner, name) = m.destructured
    return if (".." in owner || ".." in name) null
    else GapTarget(GapKind.HF_MODEL_CARD, owner, name, recordId)
  }

  private fun trimmed(url: String) = url.trim().removeSuffix("/")
}

object UntrustedPageText {
  const val MAX_CHARS = 4000

  const val MAX_PAGE_CHARS = 6000

  const val MAX_RAW_CHARS = 64 * 1024

  private val COMMENT = Regex("<!--[\\s\\S]*?(-->|$)")
  private val IMAGE = Regex("!\\[[^\\]]{0,300}]\\([^)]{0,2000}\\)")
  private val LINK = Regex("\\[([^\\]]{0,300})]\\([^)]{0,2000}\\)")
  private val SCRIPT_OR_STYLE =
    Regex("<(script|style)\\b[\\s\\S]{0,20000}?</\\1\\s*>", RegexOption.IGNORE_CASE)
  private val TAG = Regex("</?[A-Za-z][^>]{0,$MAX_TAG_CHARS}>")
  private val REFERENCE_LINK = Regex("(?m)^ {0,3}\\[[^\\]\\n]{1,300}]:[^\\n]{0,2000}$")

  private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}")

  private val PHONE =
    Regex(
      "\\+\\d{1,3}(?:[ .-]?\\(?\\d{1,4}\\)?){2,5}" +
        "|\\(\\d{2,4}\\)[ .-]?\\d{3,4}[ .-]?\\d{3,4}" +
        "|(?<![\\d.])\\d{3}[ .-]\\d{3}[ .-]\\d{4}(?![\\d.])"
    )

  private val INSTRUCTION_LINE =
    Regex(
      "(?im)^[^\\n]{0,500}(ignore (all |any )?(the )?(previous|prior|above|earlier) " +
        "(instructions|prompts?|rules)|disregard (all |any )?(previous|prior|above)|" +
        "system prompt|you are (an? )?(ai|assistant|language model|llm)|as an ai\\b|" +
        "(ai|llm|language model|assistant)s?[, ]{1,3}(please )?(set|output|say|answer|reply|respond)|" +
        "set (the )?(pricing|price|licen[cs]e|field|value|audience)\\b)[^\\n]{0,500}$"
    )

  private const val MAX_TAG_CHARS = 2000

  private val HIDDEN_ATTRIBUTE =
    Regex(
      "\\bhidden\\b|display\\s*:\\s*none|visibility\\s*:\\s*hidden|aria-hidden\\s*=\\s*[\"']?true",
      RegexOption.IGNORE_CASE,
    )

  private val VOID_ELEMENTS =
    setOf(
      "area",
      "base",
      "br",
      "col",
      "embed",
      "hr",
      "img",
      "input",
      "link",
      "meta",
      "source",
      "track",
      "wbr",
    )
  private val FRONT_MATTER = Regex("\\A---\\n[\\s\\S]*?\\n---\\n")
  private val BLANK_LINES = Regex("\\n{3,}")
  private val TRAILING_SPACE = Regex("[ \\t]+\\n")

  private val USEFUL_HEADING =
    Regex(
      "licen[cs]e|install|getting started|quick ?start|usage|platform|support|compatib|requirement",
      RegexOption.IGNORE_CASE,
    )

  @JvmField
  val PAGE_HEADING =
    Regex(
      "pric|plan|licen[cs]e|open[- ]?source|download|platform|install|integrat|feature|" +
        "self[- ]?host|requirement|support",
      RegexOption.IGNORE_CASE,
    )

  fun clean(raw: String): String {
    var s =
      visible(
        Normalizer.normalize(raw.take(MAX_RAW_CHARS).replace("\r\n", "\n"), Normalizer.Form.NFKC)
      )
    s = s.replace(FRONT_MATTER, "")
    s = s.replace(COMMENT, "")
    s = s.replace(SCRIPT_OR_STYLE, "")
    s = dropHidden(s)
    s = s.replace(IMAGE, "")
    s = s.replace(LINK, "$1")
    s = s.replace(REFERENCE_LINK, "")
    s = s.replace(TAG, "")
    s = s.replace(EMAIL, "[email]")
    s = s.replace(PHONE, "[phone]")
    s = s.replace(INSTRUCTION_LINE, "")
    return s.replace(TRAILING_SPACE, "\n").replace(BLANK_LINES, "\n\n").trim()
  }

  internal fun dropHidden(text: String): String {
    val hiding = HIDDEN_ATTRIBUTE.findAll(text).map { it.range.first }.toList().toIntArray()
    fun hides(tag: Tag) = hiding.any(tag.attributesFrom, tag.attributesTo)
    val out = StringBuilder(text.length)
    var from = 0
    var at = 0
    while (true) {
      val lt = text.indexOf('<', at)
      if (lt < 0) break
      val tag = readTag(text, lt)
      when {
        tag == null -> at = lt + 1
        tag.end < 0 ->
          if (hides(tag)) {
            return out.append(text, from, lt).toString()
          } else {
            at = lt + 1
          }
        tag.closing -> at = tag.end
        !hides(tag) -> at = if (tag.name in RAW_TEXT) rawTextEnd(text, tag) ?: break else tag.end
        else -> {
          out.append(text, from, lt)
          from =
            if (tag.name in VOID_ELEMENTS || closesItself(tag)) tag.end
            else closeOf(text, tag.name, tag.end) ?: return out.toString()
          at = from
        }
      }
    }
    return out.append(text, from, text.length).toString()
  }

  private class Tag(
    val name: String,
    val closing: Boolean,
    val attributesFrom: Int,
    val attributesTo: Int,
    val end: Int,
    val selfClosing: Boolean = false,
  )

  private fun closesItself(tag: Tag): Boolean = tag.selfClosing && tag.name in FOREIGN_ROOTS

  private val FOREIGN_ROOTS = setOf("svg", "math")

  private val RAW_TEXT =
    setOf("textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript")

  private fun rawTextEnd(text: String, tag: Tag): Int? {
    var at = tag.end
    while (true) {
      val lt = text.indexOf("</", at)
      if (lt < 0) return null
      val close = readTag(text, lt)
      if (close != null && close.closing && close.name == tag.name) {
        return if (close.end < 0) null else close.end
      }
      at = lt + 2
    }
  }

  private fun IntArray.any(from: Int, to: Int): Boolean {
    val i = binarySearch(from).let { if (it >= 0) it else -it - 1 }
    return i < size && this[i] < to
  }

  private fun readTag(text: String, lt: Int): Tag? {
    var i = lt + 1
    val closing = i < text.length && text[i] == '/'
    if (closing) i++
    val nameStart = i
    if (i >= text.length || !text[i].isLetter() || text[i].code > 127) return null
    while (i < text.length && i - nameStart <= 20 && text[i].isLetterOrDigit()) i++
    if (i < text.length && !(text[i].isWhitespace() || text[i] == '/' || text[i] == '>')) {
      return null
    }
    val name = text.substring(nameStart, i).lowercase()
    val limit = minOf(text.length, lt + MAX_TAG_CHARS)
    var quote = ' '
    var afterEquals = false
    var unquoted = false
    var j = i
    while (j < limit) {
      val c = text[j]
      when {
        quote != ' ' -> if (c == quote) quote = ' '
        c == '>' ->
          return Tag(name, closing, i, j, j + 1, selfClosing = text[j - 1] == '/' && !unquoted)
        afterEquals && (c == '"' || c == '\'') -> quote = c
        afterEquals && !c.isWhitespace() -> unquoted = true
        unquoted && c.isWhitespace() -> unquoted = false
      }
      if (quote == ' ') afterEquals = !unquoted && (c == '=' || (afterEquals && c.isWhitespace()))
      j++
    }
    return Tag(name, closing, i, limit, -1)
  }

  private fun closeOf(text: String, name: String, from: Int): Int? {
    var depth = 1
    var at = from
    while (true) {
      val lt = text.indexOf('<', at)
      if (lt < 0) return null
      val tag = readTag(text, lt)
      when {
        tag == null -> at = lt + 1
        tag.end < 0 -> return null
        !tag.closing && tag.name in RAW_TEXT -> at = rawTextEnd(text, tag) ?: return null
        tag.name != name -> at = tag.end
        tag.closing -> if (--depth == 0) return tag.end else at = tag.end
        else -> {
          if (!closesItself(tag)) depth++
          at = tag.end
        }
      }
    }
  }

  private fun visible(s: String): String {
    val out = StringBuilder(s.length)
    s.codePoints().forEach { cp ->
      val type = Character.getType(cp)
      val hidden =
        (type == Character.CONTROL.toInt() && cp != '\n'.code && cp != '\t'.code) ||
          type == Character.FORMAT.toInt()
      if (!hidden) out.appendCodePoint(cp)
    }
    return out.toString()
  }

  @JvmOverloads
  fun window(text: String, maxChars: Int = MAX_CHARS, useful: Regex = USEFUL_HEADING): String {
    if (text.length <= maxChars) return text
    val sections = sections(text)
    val order =
      listOf(0) +
        sections.indices.drop(1).filter {
          useful.containsMatchIn(sections[it].lineSequence().first())
        } +
        sections.indices.drop(1).filterNot {
          useful.containsMatchIn(sections[it].lineSequence().first())
        }
    val kept = sortedMapOf<Int, String>()
    var used = 0
    for (i in order) {
      val separator = if (kept.isEmpty()) 0 else SEPARATOR.length
      val room = maxChars - used - separator
      if (room <= 0) break
      val section = sections[i]
      when {
        section.length <= room -> kept[i] = section
        room >= MIN_CUT -> kept[i] = section.take(room)
        else -> continue
      }
      used += separator + kept.getValue(i).length
    }
    return kept.values.joinToString(SEPARATOR)
  }

  private const val SEPARATOR = "\n\n"
  private const val MIN_CUT = 200

  private fun sections(text: String): List<String> {
    val out = mutableListOf<StringBuilder>(StringBuilder())
    for (line in text.lines()) {
      if (line.startsWith("#") && out.last().isNotBlank()) out += StringBuilder()
      out.last().append(line).append('\n')
    }
    return out.map { it.toString().trim() }.filter { it.isNotEmpty() }
  }
}
