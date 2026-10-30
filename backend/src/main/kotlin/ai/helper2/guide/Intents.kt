package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.shared.ResourceType
import java.text.Normalizer
import java.time.LocalDate

/**
 * What kind of answer the question asks for (ADR 0032). One per question; [SUGGEST] is the fallback and behaves
 * exactly as the guide did before intents.
 */
enum class GuideIntent {
    /** "a free tool to chat with PDFs": picks with a quote. */
    SUGGEST,

    /** "AI tools born last month", "latest MCP servers": created inside a window, newest first. */
    NEWEST,

    /** "alternatives to Cursor": the subject's category, the subject itself excluded. */
    ALTERNATIVES,

    /** "Ollama vs LM Studio": one column per subject, each cell quoted. */
    COMPARE,

    /** "how do I run Llama locally": tools that do the task, each with its setup guide. */
    HOW_TO,

    /** "what is MCP?": a short quoted explanation and the subject's card. */
    WHAT_IS,

    /** "when is NeurIPS?", "when was Llama 4 released?": a date with its source. */
    WHEN,

    /** "AI certifications for beginners", "a course on RAG": courses and certifications. */
    LEARN,
}

enum class WindowUnit(val days: Long) { DAY(1), WEEK(7), MONTH(30), YEAR(365) }

enum class WindowDirection { PAST, NEXT }

/**
 * A relative period the planner read in the question ("last month" = 1 MONTH PAST). Never a date: the model does
 * not know today's date, so code resolves it with the clock ([resolve]).
 */
data class PlannedWindow @JvmOverloads constructor(
    val amount: Int,
    val unit: WindowUnit,
    val direction: WindowDirection = WindowDirection.PAST,
) {
    /** Rolling from [today] (UTC): a month is 30 days, so "last month" never empties at the start of a month. */
    fun resolve(today: LocalDate): DateWindow {
        val days = amount.coerceIn(1, MAX_AMOUNT).toLong() * unit.days
        return when (direction) {
            WindowDirection.PAST -> DateWindow(today.minusDays(days), today)
            WindowDirection.NEXT -> DateWindow(today, today.plusDays(days))
        }
    }

    companion object {
        /** Ten years of days, months or weeks is more than any question means; larger numbers are model noise. */
        const val MAX_AMOUNT = 120
    }
}

/** Inclusive days, UTC. */
data class DateWindow(val from: LocalDate, val to: LocalDate)

/** A question back to the user when the question is too vague to search well, or names an ambiguous subject (ADR 0032 §3). */
data class Clarification(val question: String, val options: List<String>) {
    companion object {
        const val MAX_OPTIONS = 4
        const val MAX_OPTION_CHARS = 40
        const val MAX_QUESTION_CHARS = 120

        /** The model's clarification, bounded; null when fewer than 2 usable options remain or there is no question. */
        @JvmStatic
        fun of(question: String?, options: List<String?>?): Clarification? {
            val q = question?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() && it.length <= MAX_QUESTION_CHARS }
                ?: return null
            val opts = options.orEmpty().asSequence()
                .mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() && it.length <= MAX_OPTION_CHARS && !GuideValidator.containsLink(it) }
                .distinctBy { it.lowercase() }
                .take(MAX_OPTIONS)
                .toList()
            if (GuideValidator.containsLink(q) || opts.size < 2) return null
            return Clarification(q, opts)
        }
    }
}

/**
 * What kind of thing an entry is, to tell namesakes apart (ADR 0032 §3). A model and a command-line tool both called
 * "Gemini" are different things; an npm package and a GitHub repository of the same tool are not.
 */
enum class SubjectKind(val label: String) {
    MODEL("model"),
    DATASET("dataset"),
    TOOL("tool");

    companion object {
        /** Null for reading types, events, courses and OTHER: they are about a thing or unknown, never a namesake. */
        @JvmStatic
        fun of(type: ResourceType): SubjectKind? = when (type) {
            ResourceType.MODEL -> MODEL
            ResourceType.DATASET -> DATASET
            ResourceType.PRODUCT, ResourceType.AGENT, ResourceType.PROMPT, ResourceType.SKILL, ResourceType.MCP_SERVER,
            ResourceType.LIBRARY -> TOOL
            else -> null
        }
    }
}

/**
 * A single named subject is ambiguous when its search finds the subject's own entries ([isSubject]) of two or more
 * [SubjectKind]s. Decided by code on results the question searches anyway: no model call, no extra search.
 */
object AmbiguousSubject {
    /**
     * The question back for [subject], or null when [results] hold its own entries of fewer than two kinds. The
     * planner's [planned] question and options win (in the user's language); otherwise one option per kind, from the
     * first own entry of that kind in search order.
     */
    @JvmStatic
    fun clarification(subject: String, results: List<MergedResult>, planned: Clarification?): Clarification? {
        val firstOfEachKind = results.filter { isSubject(it, subject) }
            .mapNotNull { r -> SubjectKind.of(r.type)?.let { it to r } }
            .distinctBy { it.first }
        if (firstOfEachKind.size < 2) return null
        return planned ?: Clarification.of("Which $subject do you mean?", firstOfEachKind.map { (kind, r) -> option(subject, kind, r) })
    }

    /** "gemini cli (tool)": the entry's name without owner, so the reply grounds as a subject; too long, "Gemini (tool)". */
    private fun option(subject: String, kind: SubjectKind, r: MergedResult): String {
        val name = r.title.substringAfterLast('/').replace(Regex("[-_\\s]+"), " ").trim()
        val option = "$name (${kind.label})"
        return if (name.isEmpty() || option.length > Clarification.MAX_OPTION_CHARS) "$subject (${kind.label})" else option
    }
}

/**
 * Parts of the reading the user removed with ✕ on the "Understood as" chips. Structured, applied by code after the
 * plan; the model never sees them, so they cannot be confused with instructions in the user's text.
 */
data class Drops(val intent: Boolean = false, val window: Boolean = false, val constraints: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = !intent && !window && constraints.isEmpty()

    /** Stable text for the answer cache key. */
    val key: String get() = if (isEmpty) "" else listOfNotNull(
        "intent".takeIf { intent }, "window".takeIf { window }, *constraints.sorted().map { "constraint:$it" }.toTypedArray(),
    ).joinToString(",")

    companion object {
        @JvmField
        val NONE = Drops()

        private const val MAX_DROPS = 8

        /** "intent", "window" and "constraint:<text>"; anything else is ignored. */
        @JvmStatic
        fun of(raw: List<String?>?): Drops {
            val items = raw.orEmpty().mapNotNull { it?.trim() }.filter { it.isNotEmpty() && it.length <= 80 }.take(MAX_DROPS)
            return Drops(
                intent = "intent" in items,
                window = "window" in items,
                constraints = items.filter { it.startsWith("constraint:") }
                    .map { it.removePrefix("constraint:").trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            )
        }
    }
}

/**
 * Deterministic backups for the planner's intent and window, from the user's own words (English and Italian). They
 * only fill what the planner left empty, and run alone when there is no model, so a degraded answer to "tools from
 * last month" is the newest tools. Narrow on purpose: a false hit changes the whole answer.
 */
object IntentRules {
    private val UNITS: Map<String, WindowUnit> = WindowUnit.entries
        .flatMap { u -> IntentWords.phrases("unit.${u.name.lowercase()}").map { it.lowercase() to u } }.toMap()
    private val UNIT = alternation(UNITS.keys)

    /** "last month", "past 2 weeks", "in the last 30 days", "ultimo mese", "ultime 2 settimane". */
    private val PAST = Regex("(?i)\\b(?:${alternation("period.past")})\\s+(?:(\\d{1,3})\\s+)?($UNIT)\\b")

    /** "this week", "this month", "questa settimana", "questo mese". */
    private val THIS = Regex("(?i)\\b(?:${alternation("period.this")})\\s+($UNIT)\\b")

    /** "next month", "next 2 weeks", "prossimo mese". */
    private val NEXT = Regex("(?i)\\b(?:${alternation("period.next")})\\s+(?:(\\d{1,3})\\s+)?($UNIT)\\b")

    /** "today", "oggi": the last day. */
    private val TODAY = anywhere("period.today")

    private val NEWEST = anywhere("intent.newest")
    private val ALTERNATIVES = anywhere("intent.alternatives")
    private val COMPARE = anywhere("intent.compare")
    private val WHAT_IS = atStart("intent.what-is")
    private val WHEN = atStart("intent.when")
    private val HOW_TO = atStart("intent.how-to")
    private val LEARN = anywhere("intent.learn")

    /** Words that name no subject ("AI tools", "new models", "what are the…"); see `query.generic` in the bundle. */
    private val GENERIC: Set<String> = IntentWords.phrases("query.generic").map { it.lowercase() }.toSet()

    private fun anywhere(key: String) = Regex("(?i)\\b(?:${alternation(key)})\\b")
    private fun atStart(key: String) = Regex("(?i)^\\s*(?:${alternation(key)})\\b")
    private fun alternation(key: String) = alternation(IntentWords.phrases(key))

    /** Longest phrase first; each word quoted, spaces between words matching any run of whitespace. */
    private fun alternation(phrases: Collection<String>): String =
        phrases.sortedByDescending { it.length }.joinToString("|") { p -> p.split(' ').joinToString("\\s+") { Regex.escape(it) } }

    /**
     * The intent the user's words state, or null. Order: the more specific shape first; a period or "latest" before
     * the question words, because "what is the latest…" asks for new things, not an explanation. WHEN keeps its
     * period ("when are the AI events next month") and is checked first.
     */
    @JvmStatic
    fun intentOf(request: String): GuideIntent? = when {
        COMPARE.containsMatchIn(request) -> GuideIntent.COMPARE
        ALTERNATIVES.containsMatchIn(request) -> GuideIntent.ALTERNATIVES
        LEARN.containsMatchIn(request) -> GuideIntent.LEARN
        WHEN.containsMatchIn(request) -> GuideIntent.WHEN
        windowOf(request) != null || NEWEST.containsMatchIn(request) -> GuideIntent.NEWEST
        WHAT_IS.containsMatchIn(request) -> GuideIntent.WHAT_IS
        HOW_TO.containsMatchIn(request) -> GuideIntent.HOW_TO
        else -> null
    }

    /** A unit of time, a year or a day word: what any stated period contains. */
    private val TIME_WORDS = Regex(
        "(?i)\\b(days?|weeks?|months?|years?|today|yesterday|tonight|weekend|since|20\\d\\d|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "giorn[oi]|settiman[ae]|mes[ei]|ann[oi]|oggi|ieri|da quando)\\b",
    )

    /** "new", "latest", "recent", "upcoming" and the like: a question about what is new, with or without a period. */
    private val NEW_WORDS = Regex("(?i)\\b(new|newest|latest|recent(ly)?|upcoming|launched|released|born|nuov[oaie]|recent[ei])\\b")

    /**
     * Grounding for the planner (ADR 0032): the fast model copies periods from its examples ("an open source MCP
     * server" came back with "last month"), so a model window is kept only when the user's words name a time.
     */
    @JvmStatic
    fun mentionsTime(request: String): Boolean = TIME_WORDS.containsMatchIn(request)

    /** A model's NEWEST is kept only when the user's words are about time or newness. */
    @JvmStatic
    fun mentionsNewness(request: String): Boolean = mentionsTime(request) || NEW_WORDS.containsMatchIn(request)

    /**
     * Words that state a requirement, per kind of requirement. A model constraint is kept only when the user's words
     * contain one of its kind's words, or one of its own words: the fast model copies "self-hosted" and "works offline"
     * from its examples. Fixed list on purpose, like the qualifiers in Guide.kt.
     */
    private val REQUIREMENT_WORDS = listOf(
        Regex("(?i)\\b(free|gratis|gratuit\\w*|no cost|without paying|kostenlos)\\b"),
        Regex("(?i)\\b(self[- ]?host\\w*|on[- ]?prem\\w*|local(ly)?|my own server|in locale)\\b"),
        Regex("(?i)\\b(offline|without internet|no internet|air[- ]?gapped|local(ly)?)\\b"),
        Regex("(?i)\\b(open[- ]?source|oss|mit|apache|bsd|gpl|licen[cs]e\\w*|permissive)\\b"),
    )

    /** Whether [constraint] restates something in [request]. */
    @JvmStatic
    fun constraintStated(constraint: String, request: String): Boolean {
        val words = constraint.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }
        if (words.any { Regex("(?i)\\b${Regex.escape(it)}").containsMatchIn(request) }) return true
        return REQUIREMENT_WORDS.any { it.containsMatchIn(constraint) && it.containsMatchIn(request) }
    }

    private val STOP = setOf("works", "runs", "the", "and", "with", "must", "has", "have", "licence", "license", "support", "supports")

    /** A window only from an explicit time expression; "latest" alone sorts by date but sets none. */
    @JvmStatic
    fun windowOf(request: String): PlannedWindow? {
        PAST.find(request)?.let { m -> return window(m.groupValues[1], m.groupValues[2], WindowDirection.PAST) }
        NEXT.find(request)?.let { m -> return window(m.groupValues[1], m.groupValues[2], WindowDirection.NEXT) }
        THIS.find(request)?.let { m -> return window("", m.groupValues[1], WindowDirection.PAST) }
        if (TODAY.containsMatchIn(request)) return PlannedWindow(1, WindowUnit.DAY)
        return null
    }

    private fun window(amount: String, unit: String, direction: WindowDirection): PlannedWindow? {
        val u = UNITS[unit.lowercase()] ?: return null
        return PlannedWindow(amount.toIntOrNull()?.takeIf { it > 0 } ?: 1, u, direction)
    }

    private val EVENT_KINDS = anywhere("when.event")
    private val STARTS = anywhere("when.start")

    /**
     * Whether [text] names a kind of event: a conference, a hackathon, a meetup, a deadline, or a course's start. A
     * WHEN question whose words or planned query do so asks for an event's dates, not a release date.
     */
    @JvmStatic
    fun namesEvent(text: String): Boolean =
        EVENT_KINDS.containsMatchIn(text) || (STARTS.containsMatchIn(text) && LEARN.containsMatchIn(text))

    private val KINDS = anywhere("subject.kind")

    /** Whether [text] names the kind of thing its subject is ("the Gemini model", "the Gemini CLI tool"). */
    @JvmStatic
    fun namesKind(text: String): Boolean = KINDS.containsMatchIn(text)

    /** The query without generic words; empty when nothing names a subject. */
    @JvmStatic
    fun subjectWords(query: String): String =
        query.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase().trim('.', ',', '?', '!') !in GENERIC }.joinToString(" ")
}

/**
 * Whether [result] is the named [subject] itself, not something about it: "Ollama" is `ollama/ollama` or the curated
 * "Ollama", never `ollama-js-helper`. Titles are compared without owner prefixes (`org/`, `@scope/`), punctuation
 * or case; a trailing type word ("Ollama app") still counts.
 */
fun isSubject(result: MergedResult, subject: String): Boolean {
    val want = normalizedName(subject)
    if (want.isEmpty()) return false
    val names = listOf(result.title, result.canonicalUrl.value.trimEnd('/').substringAfterLast('/'))
    return names.any { n ->
        val have = normalizedName(n)
        have == want || TYPE_SUFFIX.any { have == "$want $it" || have == "$want$it" }
    }
}

private val TYPE_SUFFIX: List<String> = IntentWords.phrases("subject.type-suffix").map { it.lowercase() }

private fun normalizedName(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        .substringAfterLast('/')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * The words the backup rules read, from `i18n/intent-words_<lang>.properties` (UTF-8, comma-separated phrases).
 * Every language is merged: the question's language is not known before planning, so the rules match all of them.
 * A missing file or key fails at startup, never as a silent empty rule.
 */
internal object IntentWords {
    private val LANGUAGES = listOf("en", "it")

    private val bundles: List<java.util.Properties> = LANGUAGES.map { lang ->
        val path = "i18n/intent-words_$lang.properties"
        val stream = IntentWords::class.java.classLoader.getResourceAsStream(path) ?: error("missing $path")
        java.util.Properties().apply { stream.reader(Charsets.UTF_8).use { load(it) } }
    }

    fun phrases(key: String): List<String> {
        require(bundles.any { it.containsKey(key) }) { "no language defines $key" }
        return bundles.flatMap { b -> b.getProperty(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } }.distinct()
    }
}
