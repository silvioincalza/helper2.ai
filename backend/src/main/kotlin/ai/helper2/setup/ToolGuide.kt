package ai.helper2.setup

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat

/** Sections of a tool guide, in the order they are shown. ANSWER holds the answer to a follow-up question. */
enum class GuideSectionKind { ANSWER, REQUIREMENTS, INSTALL, CONFIGURE, USE, CONNECT }

/** The model's proposal: everything in it is checked by [ToolGuideValidator] before anyone sees it. */
data class ProposedStep(val text: String, val materialKey: String, val quote: String, val code: String? = null, val language: String? = null)

data class ProposedSection(val kind: String, val steps: List<ProposedStep>)

data class ProposedGuide(val sections: List<ProposedSection>)

data class Code(val language: String?, val text: String)

/**
 * One step the user can follow. [quote] is a passage of the material at [materialKey]; [code], when present, is
 * whole lines of one fenced block of that material (or one inline code span), as written there. [caution] marks
 * a command that pipes a download into a shell, uses sudo or deletes recursively: copied, not vouched for.
 */
data class GuideStep(val text: String, val materialKey: String, val quote: String, val code: Code?, val caution: Boolean)

data class GuideSection(val kind: GuideSectionKind, val steps: List<GuideStep>)

enum class ToolGuideStatus {
    /** At least one step passed the checks. */
    OK,
    /** The documents were read, but no proposed step passed the checks. */
    NO_STEPS,
    /** The resource has no document we can read. */
    NO_MATERIAL,
    /** No model configured, it failed, or today's model calls are used up. */
    LLM_UNAVAILABLE,
}

data class ToolGuideAnswer(
    val status: ToolGuideStatus,
    val question: String?,
    val sections: List<GuideSection>,
    /** The documents the steps were read from; steps refer to them by key. */
    val materials: List<Material>,
    val model: String?,
    val rejected: Int,
    val generatedAt: Instant,
    /** Why steps were removed, by reason; for logs and the eval. */
    val rejectedBy: Map<String, Int> = emptyMap(),
    /** Why the model was not used, when status is LLM_UNAVAILABLE: "budget" or "error". */
    val unavailable: String? = null,
)

/** The model could not answer now: no model, a provider error, or the daily cap. */
class ToolGuideUnavailable(val reason: String) : RuntimeException(reason)

/** Outbound port: the model proposes steps from the documents only. */
interface ToolGuideModel {
    val model: String

    /** Changes when the prompt or settings change, so cached answers from another prompt are not reused. */
    val configFingerprint: String

    fun write(ref: ToolRef, materials: List<Material>, question: String?): ProposedGuide
}

/** Outbound port: answers kept across requests and replicas. */
interface ToolGuideCache {
    fun get(key: String): ToolGuideAnswer?
    fun put(key: String, answer: ToolGuideAnswer)

    companion object {
        val NONE = object : ToolGuideCache {
            override fun get(key: String): ToolGuideAnswer? = null
            override fun put(key: String, answer: ToolGuideAnswer) {}
        }
    }
}

/**
 * Deterministic gate between the model and the user (same principle as the guide's GuideValidator, ADR 0015):
 * a step survives only when its quote is in the cited document and its command is copied from it exactly.
 * Commands are compared case-sensitively (`-y` is not `-Y`), whitespace collapsed, a leading `$ ` or `> ` prompt
 * ignored; all lines of a command must come from one fenced block, so a model cannot shorten or splice commands.
 */
class ToolGuideValidator {

    data class Result(val sections: List<GuideSection>, val rejected: Int, val rejectedBy: Map<String, Int> = emptyMap())

    /** Why a step was removed; counted for logs and the eval. */
    enum class Rejection { SECTION, SOURCE, TEXT, QUOTE_LENGTH, QUOTE_NOT_FOUND, CODE_NOT_FOUND, DUPLICATE }

    fun validate(proposal: ProposedGuide, materials: List<Material>): Result {
        val byKey = materials.associateBy { it.key }
        val parsed = materials.associate { it.key to Parsed(it.text) }
        val rejectedBy = sortedMapOf<String, Int>()
        fun reject(r: Rejection) = rejectedBy.merge(r.name, 1, Int::plus)
        val kept = linkedMapOf<GuideSectionKind, MutableList<GuideStep>>()
        for (section in proposal.sections) {
            val kind = GuideSectionKind.entries.firstOrNull { it.name.equals(section.kind.trim(), ignoreCase = true) }
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
                // A step already given in an earlier section (same command or same text) is not repeated.
                val seen = kept.values.flatten().any { it.text.equals(out.text, ignoreCase = true) || (out.code != null && it.code?.text == out.code.text) }
                if (list.size >= MAX_STEPS_PER_SECTION || seen) {
                    reject(Rejection.DUPLICATE)
                } else {
                    list += out
                }
            }
        }
        return Result(GuideSectionKind.entries.mapNotNull { k -> kept[k]?.takeIf { it.isNotEmpty() }?.let { GuideSection(k, it) } }, rejectedBy.values.sum(), rejectedBy)
    }

    /**
     * The kept step, or why it was removed. A command copied exactly from the document is its own evidence; a
     * command that is not is dropped, and the step then stands only on its quote. Commands are never relaxed.
     */
    private fun check(step: ProposedStep, material: Material?, parsed: Parsed?): Any {
        if (material == null || parsed == null) return Rejection.SOURCE
        val text = step.text.trim()
        // The model saw third-party text with < > shown as ‹ › (UntrustedText): map them back before matching.
        val quote = unmask(step.quote).trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS || containsLink(text)) return Rejection.TEXT
        val quoteFound = quote.length in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS && parsed.prose.contains(loose(quote))
        val code = step.code?.let { unmask(it).trim().ifEmpty { null } }?.let(parsed::code)
        if (code == null && !quoteFound) {
            return when {
                step.code != null -> Rejection.CODE_NOT_FOUND
                quote.length !in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS -> Rejection.QUOTE_LENGTH
                else -> Rejection.QUOTE_NOT_FOUND
            }
        }
        val language = step.language?.trim()?.lowercase()?.takeIf { LANGUAGE.matches(it) }
        return GuideStep(text, material.key, if (quoteFound) quote else code!!,
            code?.let { Code(language ?: parsed.languageOf(it), it) }, code != null && cautious(code))
    }

    /** One command line of a block: its comparison key and the source lines it came from, as written. */
    private class Line(val key: String, val source: List<String>)

    /** A document prepared once for all its steps. */
    private class Parsed(text: String) {
        val prose = loose(text)

        // Each block as logical lines: a command continued with a trailing backslash is one line, never its parts.
        private val blocks = Markdown.blocks(text).map { it to logicalLines(it.lines) }
        private val spans = Markdown.inlineSpans(text).associateBy(::tight)

        /**
         * The code as the document writes it, or null when it is not copied from one place. The proposed lines must
         * be an ordered, contiguous run of one block's lines (compared with whitespace collapsed, a `$ ` prompt
         * ignored); what is returned is the document's own text for that run, indentation and spacing kept.
         */
        fun code(code: String): String? {
            val wanted = logicalLines(code.lines()).map { it.key }
            if (wanted.isEmpty() || wanted.size > MAX_CODE_LINES) return null
            if (wanted.size == 1) spans[wanted[0]]?.let { return it.trim() }
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
            return blocks.firstOrNull { (_, lines) -> lines.any { l -> l.source.any { tight(it).removePrefix("$ ") == first } } }
                ?.first?.language?.lowercase()?.takeIf { LANGUAGE.matches(it) }
        }

        /** The source lines without a leading `$ ` prompt and without the indentation they share. */
        private fun output(source: List<String>): String {
            val lines = source.map { it.trimEnd() }.map { l ->
                val i = l.indexOfFirst { !it.isWhitespace() }
                if (i >= 0 && (l.startsWith("$ ", i) || l.startsWith("> ", i))) l.substring(0, i) + l.substring(i + 2) else l
            }
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } } ?: 0
            return lines.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it.trim() }
        }
    }

    companion object {
        const val MAX_STEPS_PER_SECTION = 8
        const val MAX_TEXT_CHARS = 280
        const val MIN_QUOTE_CHARS = 6
        const val MAX_QUOTE_CHARS = 500
        const val MAX_CODE_LINES = 40

        private val LANGUAGE = Regex("[a-z0-9+#-]{1,16}")
        private val LINK = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
        private val CAUTION = Regex("\\|\\s*(sudo\\s+)?(ba|z)?sh\\b|\\bsudo\\b|\\brm\\s+-[a-z]*r[a-z]*f|\\brm\\s+-[a-z]*f[a-z]*r")

        fun containsLink(text: String) = LINK.containsMatchIn(text)

        /** Checked on the command as a shell reads it: backslash-continued lines joined, whitespace collapsed. */
        fun cautious(code: String) = CAUTION.containsMatchIn(tight(code.replace(CONTINUATION, " ")))

        private val CONTINUATION = Regex("\\\\[ \\t]*\\r?\\n")

        private fun unmask(s: String) = s.replace('‹', '<').replace('›', '>')

        /**
         * Logical lines of code: blank lines dropped, a line ending with a backslash joined to the next (as a shell
         * reads it). Each keeps its source lines for output; its key is whitespace-collapsed, without a `$ ` prompt.
         */
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

        /** Case-insensitive, whitespace collapsed, markdown emphasis ignored: for prose quotes. */
        fun loose(s: String): String = s
            .replace(FENCE, " ")
            .replace(MD_LINK, "$1")
            .replace(HTML_TAG, " ")
            .replace(Regex("[*_`>#|]+"), "")
            .replace(Regex("\\s+"), " ").trim().lowercase()

        private val MD_LINK = Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)")
        private val HTML_TAG = Regex("<[^>]{1,200}>")
        private val FENCE = Regex("(`{3,}|~{3,})[A-Za-z0-9_+#.-]*")

        /** Case-sensitive, whitespace collapsed: for commands. */
        fun tight(s: String): String = s.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * The tool guide: the resource's own documents, a model that may only pick and order passages from them, and the
 * validator. One answer per resource, documents and question, cached; the plain guide (no question) is the same
 * for every visitor.
 */
class ToolGuides @JvmOverloads constructor(
    private val materials: ToolMaterials,
    private val model: ToolGuideModel?,
    private val cache: ToolGuideCache,
    private val clock: Clock,
    private val validator: ToolGuideValidator = ToolGuideValidator(),
) {

    fun answer(resourceId: String, ref: ToolRef, question: String?): ToolGuideAnswer {
        val q = question?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }
        val docs = if (ref.hasDocs) materials.load(ref) else emptyList()
        if (docs.isEmpty()) return empty(ToolGuideStatus.NO_MATERIAL, q, docs)
        if (model == null) return empty(ToolGuideStatus.LLM_UNAVAILABLE, q, docs, "error")
        val key = "v1:" + resourceId + ":" + sha256(model.configFingerprint + "\u0000" + (q?.lowercase() ?: "") + "\u0000" +
            docs.joinToString("\u0000") { it.url.toString() + "\u0000" + it.text })
        cache.get(key)?.let { return it }
        val proposal = try {
            model.write(ref, docs, q)
        } catch (e: ToolGuideUnavailable) {
            return empty(ToolGuideStatus.LLM_UNAVAILABLE, q, docs, e.reason)
        }
        val result = validator.validate(proposal, docs)
        val answer = ToolGuideAnswer(if (result.sections.isEmpty()) ToolGuideStatus.NO_STEPS else ToolGuideStatus.OK, q,
            result.sections, docs, model.model, result.rejected, clock.instant(), result.rejectedBy)
        cache.put(key, answer)
        return answer
    }

    private fun empty(status: ToolGuideStatus, q: String?, docs: List<Material>, why: String? = null) =
        ToolGuideAnswer(status, q, emptyList(), docs, null, 0, clock.instant(), unavailable = why)

    private fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
