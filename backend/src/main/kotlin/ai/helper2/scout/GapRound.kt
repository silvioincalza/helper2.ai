package ai.helper2.scout

import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/**
 * The gap round (ADR 0043): when an enrichment leaves fields unproven, one more pass with more text from the
 * resource's own source (its GitHub README or its Hugging Face model card). Code decides where that text comes
 * from; the model never picks a URL.
 */
enum class GapKind(val label: String) {
    GITHUB_README("github-readme"),
    HF_MODEL_CARD("hf-model-card"),
}

/** Where to read more text: [owner]/[name] on GitHub or on the Hugging Face Hub. [anchorRecordId] is the source record it belongs to. */
data class GapTarget(val kind: GapKind, val owner: String, val name: String, val anchorRecordId: UUID) {
    /** Stored with the run, for audit: "github-readme:owner/name". */
    val label: String get() = "${kind.label}:$owner/$name"
}

/** What a source adapter read for a [GapTarget]: the raw text, where a person can see it, and the licence the source API states. */
data class ExtraText(val sourceUrl: URI, val retrievedAt: Instant, val raw: String, val license: String?)

/** Outbound port: reads the README or model card of a [GapTarget]. null when there is none; throws when the source fails. */
fun interface ExtraEvidence {
    fun fetch(target: GapTarget): ExtraText?
}

/** Outbound port: the gap round's own daily cap, inside the enrichment cap. */
fun interface GapBudget {
    fun tryAcquire(): Boolean
}

/** The parts of a source record the gap round looks at. */
data class GapSourceRecord(val id: UUID, val sourceId: String, val externalId: String, val sourceUrl: String, val repositoryUrl: String?)

object GapTargets {

    /** Sources whose record URL is the repository itself, as the GitHub API returned it. */
    private val GITHUB_SOURCES = setOf("github", "github-awesome")

    /** Hub model listings; the record URL is the model page. Spaces are apps, not models. */
    private val HF_SOURCES = setOf("hf-models", "hf-trending", "hf-provider-orgs")

    private const val MCP = "mcp-registry"

    private val GITHUB = Regex("^https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})$")
    private val HUB = Regex("^https://huggingface\\.co/([A-Za-z0-9][A-Za-z0-9._-]{0,95})/([A-Za-z0-9][A-Za-z0-9._-]{0,95})$")

    /** A registry name in the GitHub namespace: io.github.<owner>/<server>. The registry checks that owner on publish. */
    private val MCP_GITHUB_NAMESPACE = Regex("^io\\.github\\.([A-Za-z0-9-]{1,39})/.+$")

    /**
     * The first record that names its own repository or model, or null. An MCP Registry entry is written by
     * whoever publishes it and can link any repository, so it counts only when its namespace is that repository's
     * owner: otherwise a famous README would "prove" facts about someone else's server.
     */
    @JvmStatic
    fun select(records: List<GapSourceRecord>): GapTarget? {
        records.firstNotNullOfOrNull { r -> if (r.sourceId in GITHUB_SOURCES) github(r.sourceUrl, r.id) else null }?.let { return it }
        records.firstNotNullOfOrNull { r -> if (r.sourceId in HF_SOURCES) hub(r.sourceUrl, r.id) else null }?.let { return it }
        return records.firstNotNullOfOrNull { r ->
            if (r.sourceId != MCP) return@firstNotNullOfOrNull null
            val namespaceOwner = MCP_GITHUB_NAMESPACE.find(r.externalId)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            val target = github(r.repositoryUrl ?: r.sourceUrl, r.id) ?: return@firstNotNullOfOrNull null
            target.takeIf { it.owner.equals(namespaceOwner, ignoreCase = true) }
        }
    }

    private fun github(url: String, recordId: UUID): GapTarget? {
        val m = GITHUB.matchEntire(trimmed(url).removeSuffix(".git")) ?: return null
        val (owner, name) = m.destructured
        return if (name == "." || name == "..") null else GapTarget(GapKind.GITHUB_README, owner, name, recordId)
    }

    private fun hub(url: String, recordId: UUID): GapTarget? {
        val m = HUB.matchEntire(trimmed(url)) ?: return null
        val (owner, name) = m.destructured
        return if (".." in owner || ".." in name) null else GapTarget(GapKind.HF_MODEL_CARD, owner, name, recordId)
    }

    private fun trimmed(url: String) = url.trim().removeSuffix("/")
}

/**
 * README and model-card text before a model or a validator sees it. A README is written by anyone: what a reader
 * on GitHub does not see (HTML comments, zero-width or direction characters, Unicode tags) must not reach the
 * model or a stored quote either.
 */
object ReadmeText {

    /** Characters of text we keep from one README or model card; the same bound as one source record. */
    const val MAX_CHARS = 4000

    /** Characters read before cleaning: the window keeps 4000, and every pattern below stays cheap on this much. */
    const val MAX_RAW_CHARS = 64 * 1024

    private val COMMENT = Regex("<!--[\\s\\S]*?(-->|$)")
    private val IMAGE = Regex("!\\[[^\\]]{0,300}]\\([^)]{0,2000}\\)")
    private val LINK = Regex("\\[([^\\]]{0,300})]\\([^)]{0,2000}\\)")
    private val SCRIPT_OR_STYLE = Regex("<(script|style)\\b[\\s\\S]{0,20000}?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("</?[A-Za-z][^>]{0,2000}>")
    private val REFERENCE_LINK = Regex("(?m)^ {0,3}\\[[^\\]\\n]{1,300}]:[^\\n]{0,2000}$")

    /** Bounded on both sides of "@": an unbounded local part makes the scan quadratic on a long run of letters. */
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}")
    private val FRONT_MATTER = Regex("\\A---\\n[\\s\\S]*?\\n---\\n")
    private val BLANK_LINES = Regex("\\n{3,}")
    private val TRAILING_SPACE = Regex("[ \\t]+\\n")

    /** Headings of the sections that usually state licence, platforms and how to install. */
    private val USEFUL_HEADING = Regex("licen[cs]e|install|getting started|quick ?start|usage|platform|support|compatib|requirement", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        // Hidden characters go first: "alice\u200B@x.com" must not slip past the email mask and join up afterwards.
        var s = visible(Normalizer.normalize(raw.take(MAX_RAW_CHARS).replace("\r\n", "\n"), Normalizer.Form.NFKC))
        s = s.replace(FRONT_MATTER, "")
        s = s.replace(COMMENT, "")
        s = s.replace(SCRIPT_OR_STYLE, "")
        s = s.replace(IMAGE, "")
        s = s.replace(LINK, "$1")
        s = s.replace(REFERENCE_LINK, "")
        s = s.replace(TAG, "")
        s = s.replace(EMAIL, "[email]")
        return s.replace(TRAILING_SPACE, "\n").replace(BLANK_LINES, "\n\n").trim()
    }

    /** Drops control and format characters (zero-width, bidi, Unicode tags), keeping new lines and tabs. */
    private fun visible(s: String): String {
        val out = StringBuilder(s.length)
        s.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val hidden = (type == Character.CONTROL.toInt() && cp != '\n'.code && cp != '\t'.code) || type == Character.FORMAT.toInt()
            if (!hidden) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /**
     * At most [maxChars] of [text]: the top (what the project is), then the sections whose heading suggests licence,
     * install or platforms, then the rest in order; a section that does not fit is cut. Kept in the README's order.
     */
    fun window(text: String, maxChars: Int = MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val sections = sections(text)
        val order = listOf(0) + sections.indices.drop(1).filter { USEFUL_HEADING.containsMatchIn(sections[it].lineSequence().first()) } +
            sections.indices.drop(1).filterNot { USEFUL_HEADING.containsMatchIn(sections[it].lineSequence().first()) }
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
