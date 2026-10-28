package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.SearchOutcome
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import java.text.Normalizer
import java.time.Instant

/** A real search result the model may choose. Keys (c1, c2…) are short so the model cannot invent ids. */
data class Candidate(val key: String, val result: MergedResult) {
    /** The only text a quote may come from: the resource's own title and description. */
    val text: String get() = listOfNotNull(result.title, result.description).joinToString("\n")
}

/**
 * [constraints] are the user's hard requirements in a few words each (e.g. "free", "self-hosted",
 * "MIT or Apache licence"). The model extracts them; the validator checks each one against every pick.
 */
data class GuideSearchPlan @JvmOverloads constructor(
    val searchQuery: String,
    val constraints: List<String> = emptyList(),
    /** True when the user asks for something to read (papers, articles, news, events), not a tool. */
    val wantsReading: Boolean = false,
    /** Natural-language query for web search sources; [searchQuery] is shaped for the catalog and reads badly there. */
    val webQuery: String = "",
    /** What kind of answer the question asks for; null = not stated (the backup rules or [GuideIntent.SUGGEST] apply). */
    val intent: GuideIntent? = null,
    /** The named things the question is about, as written ("Cursor"; "Ollama", "LM Studio"). */
    val subjects: List<String> = emptyList(),
    /** The period the question names ("last month"); code resolves it to dates. */
    val window: PlannedWindow? = null,
    /** Set when the question is too vague to search well; the guide asks it back instead of searching. */
    val clarify: Clarification? = null,
)

/** Types that are knowledge about AI, not tools. Recommended only when the user asks to read. */
val READING_TYPES = setOf(ResourceType.ARTICLE, ResourceType.PAPER, ResourceType.NEWS, ResourceType.EVENT)

/** Courses and certifications: retrieved only for [GuideIntent.LEARN], so they never fill a tool question's slots. */
val LEARN_TYPES: Set<ResourceType> = setOf(ResourceType.COURSE)

/** What a tool question may retrieve. */
val TOOL_TYPES: Set<ResourceType> = ResourceType.entries.toSet() - READING_TYPES - LEARN_TYPES

/**
 * Deterministic backup for [GuideSearchPlan.wantsReading]: the fast model may miss it, so explicit words
 * in the user's own request also count (English and Italian).
 */
private val READING_WORDS = Regex(
    "(?i)\\b(papers?|articles?|news|research|stud(y|ies)|blogs?|posts?|events?|conferences?|reading|read about|" +
        "articol[oi]|notizi[ae]|ricerc[ah]e?|studi[oi]?|eventi?)\\b",
)

/**
 * Generic qualifiers that are checked as constraints, not searched for: in keyword search they match any
 * project that says "open-source" or "free" (evaluation 2026-10-26: "open-source MCP server Postgres"
 * returned an ERP and a Solana tool; "MCP server Postgres" returned 12 Postgres MCP servers). Fixed list on
 * purpose: stripping every model-given constraint could remove the subject ("Postgres") itself.
 */
private val QUALIFIERS = Regex(
    "(?i)(?<![\\w-])(open[- ]?source|oss|free( of charge)?|for free|gratis|gratuit[oa]|self[- ]?host(ed|able)?|" +
        "local(ly)?|offline|on[- ]prem(ise)?|mit|apache( 2(\\.0)?)?|bsd|gpl|licen[cs]ed?|licen[cs]e|permissive|cheap|low[- ]cost)(?![\\w-])",
)

/** The planner's query without generic qualifiers; falls back to the original when nothing is left. */
fun withoutQualifiers(query: String): String =
    QUALIFIERS.replace(query, " ").replace(Regex("\\s+"), " ").trim().ifBlank { query.trim() }

fun wantsReading(request: String, plan: GuideSearchPlan?): Boolean = plan?.wantsReading == true || READING_WORDS.containsMatchIn(request)

/** How the planner's queries are cut before search; the plan prompt states the same limits. */
object PlannedQueries {
    /** Search ranks rows with every query word first, so each extra word narrows that group. */
    const val MAX_QUERY_WORDS = 6

    /** Web engines handle a full phrase better than six keywords. */
    const val MAX_WEB_QUERY_WORDS = 12

    /** The planner's query, qualifiers removed, cut to [MAX_QUERY_WORDS] words and 200 chars. */
    @JvmStatic
    fun searchQueryOf(planned: String): String =
        withoutQualifiers(planned).split(' ').filter { it.isNotBlank() }.take(MAX_QUERY_WORDS).joinToString(" ").take(200)

    /** The planner's web query cut to [MAX_WEB_QUERY_WORDS] words and 200 chars; null when blank, so sources keep their default. */
    @JvmStatic
    fun webQueryOf(planned: String): String? =
        planned.split(Regex("\\s+")).filter { it.isNotBlank() }.take(MAX_WEB_QUERY_WORDS).joinToString(" ").take(200)
            .ifBlank { null }
}

/** The model's claim that [quote] (verbatim from the candidate) shows the pick meets [constraint]. */
data class ConstraintQuote(val constraint: String, val quote: String)

data class ProposedPick @JvmOverloads constructor(
    val candidateKey: String,
    val reason: String,
    val quote: String,
    val evidence: List<ConstraintQuote> = emptyList(),
)

data class GuideProposal(val picks: List<ProposedPick>, val prompt: String?, val promptBasedOn: List<String>)

/**
 * The model could not answer. [category] says why, in a fixed vocabulary for logs and metrics (timeout,
 * rate_limited, unavailable, parse_error, budget, error); never a prompt or an answer.
 */
open class GuideUnavailable @JvmOverloads constructor(message: String, val category: String = "error") : RuntimeException(message)

/** The daily model-call cap is spent; told apart from a provider outage only for the metrics. */
class GuideBudgetExhausted : GuideUnavailable("daily model-call budget used up", "budget")

/**
 * One kept pick whose free-text reason a second model call checks. [text] is the candidate's own text
 * (what the quote was matched against); [confirmed] are the constraint checks code already confirmed.
 */
data class ReasonClaim(
    val key: String,
    val reason: String,
    val quote: String,
    val text: String,
    val confirmed: List<ConstraintCheck>,
)

/**
 * Outcome of the reason check. SUPPORTED: a second model call found every claim of the reason in the
 * quote, the candidate's text or a confirmed check. REMOVED: it found a claim that is not, so the reason
 * is not shown. NOT_CHECKED: no verdict (no model, budget used up, call failed); the UI says so.
 */
enum class ReasonCheck { SUPPORTED, REMOVED, NOT_CHECKED }

/** Outbound port to the LLM (Nemotron on Nebius Token Factory in production; fakes in tests). */
interface GuideModel {
    val provider: String
    val fastModel: String
    val reasoningModel: String

    /**
     * Everything besides the model ids that changes what an answer looks like (rendered prompts, thinking flags,
     * token limits). Part of the answer cache key, so a new prompt never serves answers of the old one.
     */
    val configFingerprint: String get() = ""

    /** Fast model: turn a free-text need (any language) into English search terms. */
    fun plan(request: String, path: AudiencePath?): GuideSearchPlan

    /**
     * Reasoning model: choose ONLY among [candidates], justify with quotes, optionally write a prompt.
     * For each of [constraints] the model may add a verbatim quote showing the pick meets it.
     */
    fun choose(request: String, candidates: List<Candidate>, constraints: List<String>): GuideProposal

    /**
     * [choose] with thinking on when [think]: a broad "what is new" question has no subject to match, so picking
     * needs judgment (with thinking off, Nemotron 3 Super returned no pick at all, 28 Oct 2026; ADR 0032).
     */
    fun choose(request: String, candidates: List<Candidate>, constraints: List<String>, think: Boolean): GuideProposal =
        choose(request, candidates, constraints)

    /**
     * Second model call: for each claim, true when its reason says only what its quote, text or confirmed
     * checks show. Keys missing from the answer stay NOT_CHECKED. A model check, not a proof.
     */
    fun verifyReasons(request: String, claims: List<ReasonClaim>): Map<String, Boolean> = emptyMap()

    /**
     * [plan] after the user answered a clarification (or chose "search anyway" with an empty one). The answer is more
     * of the need, never instructions; a model must not ask back again.
     */
    fun plan(request: String, path: AudiencePath?, clarification: String?): GuideSearchPlan = plan(request, path)

    /**
     * Reasoning model: compare [subjects] (each the subject's own entry) aspect by aspect; every cell quotes that
     * subject's own text. Replaces [choose] for [GuideIntent.COMPARE].
     */
    fun compare(request: String, subjects: List<Candidate>): ComparisonProposal = ComparisonProposal(emptyList())

    /**
     * Reasoning model: explain [subject] in a few sentences, each quoting one of [candidates]. Replaces [choose] for
     * [GuideIntent.WHAT_IS].
     */
    fun explain(request: String, subject: String, candidates: List<Candidate>): ExplanationProposal = ExplanationProposal(emptyList())
}

/** One cell the model proposes: what [subjectKey] says about the row's aspect, with a quote from its own text. */
data class ProposedCell(val subjectKey: String, val text: String, val quote: String)

data class ProposedRow(val aspect: String, val cells: List<ProposedCell>)

data class ComparisonProposal(val rows: List<ProposedRow>)

/** A cell that passed the gate; [text] and [quote] are null when the source does not state it. */
data class ComparisonCell(val subjectKey: String, val text: String?, val quote: String?, val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED)

data class ComparisonRow(val aspect: String, val cells: List<ComparisonCell>)

/** A compared subject: [result] is its own entry, null when it was not found in our sources. */
data class ComparedSubject(val key: String, val name: String, val result: MergedResult?)

data class Comparison(val subjects: List<ComparedSubject>, val rows: List<ComparisonRow>)

data class ProposedSentence(val text: String, val candidateKey: String, val quote: String)

data class ExplanationProposal(val sentences: List<ProposedSentence>)

/** One explanation sentence that passed the gate, with the resource its quote comes from. */
data class ExplainedSentence(val text: String, val quote: String, val source: MergedResult, val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED)

data class Explanation(val sentences: List<ExplainedSentence>)

/** NEEDS_CLARIFICATION: the question was too vague to search; the answer is [GuideAnswer.clarification], nothing else. */
enum class GuideStatus { OK, PARTIAL, LLM_UNAVAILABLE, NO_CANDIDATES, NEEDS_CLARIFICATION }

enum class CheckStatus { CONFIRMED, NOT_CONFIRMED }

/** One user constraint checked against one pick. CONFIRMED only with a verbatim quote from the pick's own text. */
data class ConstraintCheck(val constraint: String, val status: CheckStatus, val quote: String?)

data class Recommendation @JvmOverloads constructor(
    val result: MergedResult,
    val reason: String,
    val quote: String,
    val aiReasoned: Boolean,
    val checks: List<ConstraintCheck> = emptyList(),
    val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED,
)

data class GuidePromptOut(val text: String, val basedOn: List<MergedResult>)

data class GuideAnswer(
    val status: GuideStatus,
    val searchQuery: String,
    val recommendations: List<Recommendation>,
    val prompt: GuidePromptOut?,
    val modelUsed: Boolean,
    val candidatesConsidered: Int,
    val rejected: Int,
    val search: SearchOutcome,
    val generatedAt: Instant,
    val constraints: List<String> = emptyList(),
    /** The guide's own notices; the search's are in [search]. */
    val notices: List<Notice> = emptyList(),
    /** How the question was read; shown as "Understood as" so a misreading can be corrected. */
    val intent: GuideIntent = GuideIntent.SUGGEST,
    val subjects: List<String> = emptyList(),
    /** The resolved period of the question, when it named one. */
    val window: DateWindow? = null,
    /** With [GuideStatus.NEEDS_CLARIFICATION]: the question back to the user. */
    val clarification: Clarification? = null,
    /** [GuideIntent.COMPARE] only. */
    val comparison: Comparison? = null,
    /** [GuideIntent.WHAT_IS] only. */
    val explanation: Explanation? = null,
)

/**
 * Deterministic gate for the guide. A pick survives only if it names a retrieved candidate and its
 * quote appears in that candidate's own text. The quote must be long enough to carry meaning: at least
 * [minQuoteChars] characters and [minQuoteWords] words, or the whole text when the text is shorter than
 * that (so short npm-style descriptions can still be quoted, but only in full). The prompt survives only if it is
 * based on at least one candidate, every "based on" entry is a candidate of type PROMPT, and it contains no links
 * at all (the model is told not to add any; a link in a copy-paste prompt is the easiest way to smuggle content to
 * the user). A prompt based on nothing is free text: anyone could put words on our page through a shared question.
 */
class GuideValidator(
    private val maxPicks: Int = MAX_PICKS,
    private val minQuoteChars: Int = MIN_QUOTE_CHARS,
    private val minQuoteWords: Int = MIN_QUOTE_WORDS,
    private val maxQuote: Int = MAX_QUOTE_CHARS,
) {

    data class Result(
        val picks: List<KeptPick>,
        val prompt: GuidePromptOut?,
        val rejected: Int,
        val checks: Map<String, List<ConstraintCheck>> = emptyMap(),
        /** Why each rejected suggestion was rejected; sums to [rejected]. For logs and evaluation only. */
        val rejectedBy: Map<Rejection, Int> = emptyMap(),
    )

    /** A pick that passed the gate, with the candidate it names. */
    data class KeptPick(val candidate: Candidate, val pick: ProposedPick)

    /** The gate's limits; part of the answer cache key, so a changed limit never serves answers of the old one. */
    val fingerprint: String get() = "$maxPicks/$minQuoteChars/$minQuoteWords/$maxQuote/$MAX_REASON_CHARS/$MIN_CHECK_QUOTE"

    enum class Rejection {
        UNKNOWN_CANDIDATE, DUPLICATE, READING_TYPE, NO_REASON, REASON_TOO_LONG,
        /** The reason contains a link: the UI shows it as text, but a link is the easiest way to smuggle content. */
        REASON_INVALID,
        QUOTE_TOO_LONG,
        QUOTE_NOT_IN_TEXT, QUOTE_TOO_SHORT,
        /** Born outside the question's period, by the date its source states (checked by AskGuide, not the gate). */
        OUTSIDE_WINDOW,
        /** A valid pick beyond [maxPicks]: nothing wrong with it, only no room. */
        OVER_LIMIT,
        PROMPT_INVALID,
    }

    fun validate(
        proposal: GuideProposal,
        candidates: List<Candidate>,
        constraints: List<String> = emptyList(),
        allowReading: Boolean = true,
    ): Result {
        val byKey = candidates.associateBy { it.key }
        val rejectedBy = mutableMapOf<Rejection, Int>()
        fun reject(r: Rejection) { rejectedBy.merge(r, 1, Int::plus) }
        val picks = mutableListOf<KeptPick>()
        for (p in proposal.picks) {
            val c = byKey[p.candidateKey.trim()]
            val quote = p.quote.trim()
            val problem = when {
                c == null -> Rejection.UNKNOWN_CANDIDATE
                picks.any { it.candidate.key == c.key } -> Rejection.DUPLICATE
                !allowReading && c.result.type in READING_TYPES -> Rejection.READING_TYPE
                p.reason.isBlank() -> Rejection.NO_REASON
                p.reason.length > MAX_REASON_CHARS -> Rejection.REASON_TOO_LONG
                containsLink(p.reason) -> Rejection.REASON_INVALID
                quote.length > maxQuote -> Rejection.QUOTE_TOO_LONG
                else -> quoteProblem(normalize(quote), normalize(c.text))
            }
            when {
                problem != null -> reject(problem)
                picks.size >= maxPicks -> reject(Rejection.OVER_LIMIT)
                else -> picks += KeptPick(c!!, p.copy(reason = p.reason.trim(), quote = quote))
            }
        }
        val prompt = proposal.prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val basedOn = proposal.promptBasedOn.map { byKey[it.trim()] }
            if (text.length > 2000 || containsLink(text) || basedOn.isEmpty() ||
                basedOn.any { it == null || it.result.type != ResourceType.PROMPT }
            ) {
                reject(Rejection.PROMPT_INVALID)
                null
            } else {
                GuidePromptOut(text, basedOn.map { it!!.result })
            }
        }
        val checks = picks.associate { (c, p) -> c.key to checkConstraints(constraints, c, p.evidence) }
        return Result(picks, prompt, rejectedBy.values.sum(), checks, rejectedBy)
    }

    /**
     * Every constraint gets a status. CONFIRMED needs a model-supplied quote for that same constraint that
     * appears verbatim in the candidate's own text; anything else (no quote, invented quote, quote for a
     * different constraint) is NOT_CONFIRMED. The model can only lose a check, never create one.
     */
    fun checkConstraints(constraints: List<String>, candidate: Candidate, evidence: List<ConstraintQuote>): List<ConstraintCheck> {
        val text = normalize(candidate.text)
        return normalizedConstraints(constraints).map { constraint ->
            licenceCheck(constraint, candidate.result)?.let { return@map it }
            val quote = evidence.asSequence()
                .filter { normalize(it.constraint) == normalize(constraint) }
                .map { it.quote.trim() }
                .firstOrNull { q -> q.length in MIN_CHECK_QUOTE..maxQuote && text.contains(normalize(q)) }
            if (quote != null) ConstraintCheck(constraint, CheckStatus.CONFIRMED, quote)
            else ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
        }
    }

    /** The adapter shows the model "‹ ›" instead of "< >" (see SpringAiGuideModel.untrusted); map them back. */
    private fun normalize(s: String) = s.replace('‹', '<').replace('›', '>').lowercase().replace(Regex("\\s+"), " ").trim()

    /** True when [quote] is a meaningful verbatim excerpt of [text], by the same rule as a pick's quote. */
    fun quoteHolds(quote: String, text: String): Boolean =
        quote.trim().length <= maxQuote && quoteProblem(normalize(quote.trim()), normalize(text)) == null

    /** True when [quote] is a short verbatim excerpt of [text], by the rule of a constraint check ("MIT License"). */
    fun shortQuoteHolds(quote: String, text: String): Boolean =
        quote.trim().length in MIN_CHECK_QUOTE..maxQuote && normalize(text).contains(normalize(quote))

    /** null when the quote is a meaningful verbatim excerpt of the candidate's text. */
    private fun quoteProblem(quote: String, text: String): Rejection? {
        if (quote.isEmpty() || !text.contains(quote)) return Rejection.QUOTE_NOT_IN_TEXT
        val requiredChars = minOf(minQuoteChars, text.length)
        val requiredWords = minOf(minQuoteWords, wordCount(text))
        return if (quote.length >= requiredChars && wordCount(quote) >= requiredWords) null else Rejection.QUOTE_TOO_SHORT
    }

    private fun wordCount(s: String) = s.split(' ').count { it.isNotBlank() }

    /**
     * Licence constraints are decided by the licence the source itself states (GitHub, npm, Hugging Face), not
     * by the description: most repositories never write "open source" in their description. "Open source" needs
     * [MergedResult.openSource] = true, which the merger sets only for OSI licences. A named licence ("MIT or
     * Apache") needs one of the names as a word of the stated licence ("mit" does not match "Limited").
     * A stated licence that does not match is NOT_CONFIRMED: a quote cannot overrule the source.
     * Returns null when the constraint is not about licences, or when the source states nothing about it,
     * so the text check applies.
     */
    private fun licenceCheck(constraint: String, result: MergedResult): ConstraintCheck? {
        val c = constraint.lowercase()
        val named = LICENCE_NAMES.filter { word(it).containsMatchIn(c) }
        val asksOpenSource = OPEN_SOURCE.containsMatchIn(c)
        if (named.isEmpty() && !asksOpenSource) return null
        val licence = result.license
        if (licence == null) {
            return when {
                named.isEmpty() && result.openSource == false -> ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
                else -> null
            }
        }
        val ok = if (named.isNotEmpty()) named.any { word(it).containsMatchIn(licence.lowercase()) } else result.openSource == true
        if (!ok) return ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
        val stated = "License: $licence (stated by ${result.sources.joinToString { s -> s.sourceName }})"
        return ConstraintCheck(constraint, CheckStatus.CONFIRMED, stated)
    }

    /** [name] as a whole word: not inside a longer word ("mit" in "limited", "gpl" in "lgpl"). */
    private fun word(name: String) = Regex("(?<![a-z0-9])${Regex.escape(name)}(?![a-z0-9])")

    companion object {
        /** Defaults of the gate. The choose prompt states the same numbers, so the model aims at what survives. */
        const val MAX_PICKS = 3
        const val MIN_QUOTE_CHARS = 40
        const val MIN_QUOTE_WORDS = 6
        const val MAX_QUOTE_CHARS = 300
        const val MAX_REASON_CHARS = 400

        private val OPEN_SOURCE = Regex("open[- ]?source|\\boss\\b|\\bosi\\b")
        private val LICENCE_NAMES = listOf("mit", "apache", "bsd", "gpl", "lgpl", "agpl", "mpl", "isc", "cc0", "unlicense")

        /** A constraint quote can be short ("MIT License", "free plan") but not a single token. */
        const val MIN_CHECK_QUOTE = 5
        const val MAX_CONSTRAINTS = 4
        private const val MAX_CONSTRAINT_CHARS = 60

        /** Trimmed, de-duplicated, bounded: the model's list is untrusted input too. */
        fun normalizedConstraints(raw: List<String>): List<String> = raw.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && it.length <= MAX_CONSTRAINT_CHARS }
            .distinctBy { it.lowercase() }
            .take(MAX_CONSTRAINTS)
            .toList()

        /** Any scheme URL, protocol-relative "//", "www.", or bare "name.tld" / "name.tld/path" — case-insensitive. */
        private val LINK_LIKE = Regex(
            "(?i)\\b[a-z][a-z0-9+.-]*://|(?<![\\w/])//[\\w-]|\\bwww\\.|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|io|ai|dev|app|co|me|xyz|info|ly|gg|to|sh|site|online|link|click|top|ru|cn)\\b",
        )

        /** Schemes that run or send something without any "//": javascript:, vbscript:, mailto:, data:<mime>. */
        private val SCHEME_WITHOUT_SLASHES = Regex("(?i)\\b(?:(?:java|vb|live)script|mailto):|\\bdata:[a-z]+/[a-z0-9.+-]+")
        private val PERCENT_ESCAPE = Regex("%([0-9a-fA-F]{2})")
        private val HTML_ENTITY = Regex("&(?:#(\\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|(colon|sol|period|tab|newline));?")
        private val NAMED_ENTITIES = mapOf("colon" to ":", "sol" to "/", "period" to ".", "tab" to "\t", "newline" to "\n")
        /** Characters a browser or terminal drops or never shows: controls (not tab/newline), soft hyphen, zero-width, BOM. */
        private val URL_IGNORED_WHITESPACE = Regex("[\\t\\r\\n]")
        private val INVISIBLE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]")

        /**
         * A link counts however it is spelled: fullwidth forms are folded (NFKC), invisible characters
         * removed, and %XX / HTML-entity escapes decoded (twice, for double encoding) before matching.
         * Browsers also ignore tabs and newlines inside a URL, so the scheme check runs without them.
         */
        internal fun containsLink(text: String): Boolean {
            var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
            repeat(2) {
                s = PERCENT_ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
                s = HTML_ENTITY.replace(s) { m ->
                    val (dec, hex, name) = m.destructured
                    val cp = when {
                        dec.isNotEmpty() -> dec.toInt()
                        hex.isNotEmpty() -> hex.toInt(16)
                        else -> return@replace NAMED_ENTITIES.getValue(name.lowercase())
                    }
                    if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
                }
            }
            s = INVISIBLE.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), "")
            return LINK_LIKE.containsMatchIn(s) || SCHEME_WITHOUT_SLASHES.containsMatchIn(URL_IGNORED_WHITESPACE.replace(s, ""))
        }
    }
}

/**
 * Observer of the guide's steps, so the streamed answer can show real progress. Each method is called
 * at most once, in this order, and only for a step that actually happened (no plan without a model, no
 * choice when the model failed). Called on the asking thread; an implementation must not throw.
 */
interface GuideProgress {
    fun planned(searchQuery: String, constraints: List<String>) {}

    /** How the question was read; called right after [planned] (or alone, without a model, from the backup rules). */
    fun understood(intent: GuideIntent, subjects: List<String>, window: DateWindow?) {}

    /** The question is too vague: the answer is [clarification]; no other step follows. */
    fun clarify(clarification: Clarification) {}
    fun retrieved(candidates: Int, search: SearchOutcome) {}
    fun chosen(proposed: Int) {}
    fun validated(kept: Int, rejected: Int) {}

    /** The reason check ran on every kept pick; [notChecked] got no verdict (call failed, budget, key missing). */
    fun reasonsChecked(supported: Int, removed: Int, notChecked: Int) {}

    companion object {
        @JvmField
        val NONE: GuideProgress = object : GuideProgress {}
    }
}
