package ai.helper2.setup

import java.net.URI
import java.time.Instant

enum class MaterialKind {
  README,
  PACKAGE_README,
  MODEL_CARD,
}

data class Material(
  val key: String,
  val kind: MaterialKind,
  val url: URI,
  val retrievedAt: Instant,
  val text: String,
) {
  val ref: MaterialRef
    get() = MaterialRef(key, kind, url, retrievedAt)
}

data class MaterialRef(
  val key: String,
  val kind: MaterialKind,
  val url: URI,
  val retrievedAt: Instant,
)

data class ToolRef(
  val title: String,
  val type: String,
  val githubRepo: String?,
  val npmPackage: String?,
  val hfModel: String?,
  val hfSpace: String?,
) {
  val hasDocs: Boolean
    get() = githubRepo != null || npmPackage != null || hfModel != null || hfSpace != null

  companion object {
    private val GITHUB =
      Regex(
        "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$"
      )
    private val HF_SPACE =
      Regex("^https?://huggingface\\.co/spaces/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:[/?#].*)?$")
    private val HF_MODEL =
      Regex("^https?://huggingface\\.co/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:[/?#].*)?$")
    private val NPM_NAME = Regex("^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
    private val NOT_REPOS =
      setOf("topics", "orgs", "sponsors", "features", "marketplace", "collections", "apps")

    fun of(
      title: String,
      type: String,
      urls: Collection<String>,
      npmNames: Collection<String>,
    ): ToolRef {
      val repo = urls.firstNotNullOfOrNull { u ->
        GITHUB.matchEntire(u.trim())
          ?.takeIf { it.groupValues[1].lowercase() !in NOT_REPOS }
          ?.takeIf { GITHUB_OWNER.matches(it.groupValues[1]) && githubRepoName(it.groupValues[2]) }
          ?.let { it.groupValues[1] + "/" + it.groupValues[2] }
      }
      val space = urls.firstNotNullOfOrNull {
        HF_SPACE.matchEntire(it.trim())?.groupValues?.get(1)?.takeIf(::hfId)
      }
      val model = urls.firstNotNullOfOrNull { u ->
        HF_MODEL.matchEntire(u.trim())?.groupValues?.get(1)?.takeIf {
          !it.startsWith("spaces/") && !it.startsWith("datasets/") && hfId(it)
        }
      }
      val npm = npmNames.firstOrNull { NPM_NAME.matches(it) }
      return ToolRef(title, type, repo, npm, if (space == null) model else null, space)
    }

    private val GITHUB_OWNER = Regex("^[A-Za-z0-9](?:-?[A-Za-z0-9]){0,38}$")
    private val GITHUB_REPO = Regex("^[A-Za-z0-9_.-]{1,100}$")

    private fun githubRepoName(name: String) =
      GITHUB_REPO.matches(name) && name != "." && name != ".."

    private val HF_PART = Regex("^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,94}[A-Za-z0-9_])?$")

    private fun hfId(id: String) =
      id.split('/').let { parts ->
        parts.size == 2 && parts.all { HF_PART.matches(it) && "--" !in it && ".." !in it }
      }
  }
}

fun interface ToolMaterials {
  fun load(ref: ToolRef): List<Material>
}

object Markdown {
  data class Block(val language: String?, val lines: List<String>)

  private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9_+#.-]*)")
  private val INLINE = Regex("(?<!`)`([^`\\n]{2,300})`(?!`)")
  private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")

  fun blocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    var fence: String? = null
    var language: String? = null
    var lines = mutableListOf<String>()
    for (line in text.lines()) {
      val m = FENCE.find(line)
      if (fence == null) {
        if (m != null) {
          fence = m.groupValues[1]
          language = m.groupValues[2].ifBlank { null }
          lines = mutableListOf()
        }
      } else if (m != null && line.trim().startsWith(fence) && m.groupValues[2].isEmpty()) {
        out += Block(language, lines)
        fence = null
      } else {
        lines += line
      }
    }
    return out
  }

  fun inlineSpans(text: String): List<String> =
    INLINE.findAll(text).map { it.groupValues[1] }.toList()

  fun relevant(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val starts = mutableListOf<Pair<Int, String>>()
    var offset = 0
    for (line in text.split('\n')) {
      HEADING.find(line)?.let { starts += offset to it.groupValues[1] }
      offset += line.length + 1
    }
    val out = StringBuilder(text.take(OPENING_CHARS))
    for ((i, s) in starts.withIndex()) {
      val (from, heading) = s
      val to = starts.getOrNull(i + 1)?.first ?: text.length
      if (to <= OPENING_CHARS || !USEFUL.containsMatchIn(heading)) continue
      val body = text.substring(maxOf(from, OPENING_CHARS), to)
      if (out.length + body.length > maxChars) {
        out.append(body.take(maxChars - out.length))
        break
      }
      out.append('\n').append(body)
    }
    return out.toString()
  }

  private const val OPENING_CHARS = 1500
  private val USEFUL =
    Regex(
      "install|setup|set up|getting started|quick ?start|usage|use|using|config|environment|requirement|prerequisite|" +
        "dependenc|run|running|connect|integrat|claude|cursor|vs ?code|windsurf|codex|mcp|client|example|api key|auth",
      RegexOption.IGNORE_CASE,
    )
}
