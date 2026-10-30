package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.Clarification
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.ComparedSubject
import ai.helper2.guide.Comparison
import ai.helper2.guide.ComparisonCell
import ai.helper2.guide.ComparisonRow
import ai.helper2.guide.ConstraintCheck
import ai.helper2.guide.DateWindow
import ai.helper2.guide.Drops
import ai.helper2.guide.ExplainedSentence
import ai.helper2.guide.Explanation
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.PlannedQueries
import ai.helper2.guide.READING_TYPES
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.TOOL_TYPES
import ai.helper2.guide.isSubject
import ai.helper2.guide.sha256Hex
import ai.helper2.guide.wantsReading
import ai.helper2.search.Asker
import ai.helper2.search.Feature
import ai.helper2.search.Filtering
import ai.helper2.search.IntentClass
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SortOrder
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import org.slf4j.LoggerFactory

/**
 * Retrieval first, AI second, validator last. Works (degraded) without a model.
 *
 * Fresh questions cost model calls, so three things stand before them: the answer [cache]; one shared
 * computation for identical questions asked at the same time; and the per-client [quota], past which a
 * client gets the degraded answer (top search results) instead of an error.
 */
class AskGuide(
    private val search: SearchResources,
    private val model: GuideModel?,
    private val clock: Clock,
    private val maxCandidates: Int = 24,
    private val validator: GuideValidator = GuideValidator(),
    private val cache: GuideAnswerCache? = null,
    private val observations: ObservationRegistry = ObservationRegistry.NOOP,
    private val meters: MeterRegistry = SimpleMeterRegistry(),
    private val quota: ClientQuota? = null,
) {
    private val log = LoggerFactory.getLogger(AskGuide::class.java)

    /** Everything that changes an answer besides the question: a change gives new cache keys. */
    private val configHash: String = sha256Hex(
        listOf(model?.provider, model?.fastModel, model?.reasoningModel, model?.configFingerprint, validator.fingerprint, maxCandidates)
            .joinToString("\u0000"),
    ).take(16)

    /** Questions being answered right now, by cache key; a second asker waits for the first answer. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<GuideAnswer>>()

    /** Set by [answer] for the question metric: the status alone cannot tell a spent budget from an outage. */
    private class Asked(var budgetExhausted: Boolean = false)

    companion object {
        /** Added to the search-only answer a client gets past its [ClientQuota]; the page localizes it by code. */
        private val DAILY_QUOTA_NOTICE = Notice(
            NoticeCode.GUIDE_DAILY_QUOTA,
            "You have used today's AI answers; showing the top search results. AI answers are back after 00:00 UTC.",
        )

        /** Search rank of a kept pick as a low-cardinality tag: `1-3`, `4-8` or `9+`. */
        @JvmStatic
        fun rankBand(rank: Int): String = when {
            rank <= 3 -> "1-3"
            rank <= 8 -> "4-8"
            else -> "9+"
        }

        /** Web pages that can announce a new resource. */
        private val WEB_ANNOUNCEMENTS = setOf(ResourceType.NEWS, ResourceType.ARTICLE)

        private const val MAX_SUBJECTS = 3
        private const val MAX_SUBJECT_CHARS = 60

        /** A subject's own entry is among the first hits for its name, or not found. */
        private const val SUBJECT_SEARCH_SIZE = 5
        private const val MAX_ROWS = 8
        private const val MAX_ASPECT_CHARS = 40
        private const val MAX_CELL_CHARS = 200
        private const val MAX_SENTENCES = 4
        private const val MAX_SENTENCE_CHARS = 300
        private const val EXPLAIN_CANDIDATES = 12
        private const val RELATED_CARDS = 4
        private const val MAX_EVENTS = 5
        /** Not "the subject's own page": a concept ("retrieval augmented generation") has none, only namesakes. */
        private const val SUBJECT_REASON = "Matches the name you asked about."
        private const val RELATED_REASON = "Related search result."
    }

    /**
     * A cached or shared answer is returned without any [progress] call: no step ran for this asker.
     * [client] identifies the asker for the per-client quota and, with [userId] when signed in, for the daily web
     * searches; null (internal callers, tests) is never limited. Every answer carries this asker's web searches left.
     * [clarification] is the user's answer to a [GuideStatus.NEEDS_CLARIFICATION] answer ("" = "search anyway"):
     * a question with one is never clarified again. [drops] are parts of the reading the user removed.
     */
    @JvmOverloads
    fun ask(
        request: String,
        path: AudiencePath?,
        progress: GuideProgress = GuideProgress.NONE,
        client: String? = null,
        clarification: String? = null,
        drops: Drops = Drops.NONE,
        userId: UUID? = null,
    ): GuideAnswer {
        val observation = Observation.createNotStarted("helper2.guide.ask", observations).start()
        var outcome = "error"
        val asker = client?.let { Asker(Feature.GUIDE, it, userId) }
        val question = Question(request.trim(), path, clarification?.trim(), drops, asker)
        try {
            observation.openScope().use {
                val key = GuideAnswerCache.key(request, path, configHash, question.clarification, drops)
                if (cache != null) {
                    val hit = cache.get(key)
                    meters.counter("helper2.guide.cache", "result", if (hit != null) "hit" else "miss").increment()
                    if (hit != null) {
                        outcome = "cached"
                        return hit.forAsker(asker)
                    }
                }
                inFlight[key]?.let {
                    outcome = "shared"
                    return await(it).forAsker(asker)
                }
                val asked = Asked()
                if (client != null && quota?.tryAcquire(client) == false) {
                    log.info("guide client quota reached: answering from search only")
                    outcome = "client_quota"
                    val searchOnly = answer(question, progress, asked, model = null)
                    return searchOnly.copy(notices = searchOnly.notices + DAILY_QUOTA_NOTICE).forAsker(asker)
                }
                val mine = CompletableFuture<GuideAnswer>()
                inFlight.putIfAbsent(key, mine)?.let {
                    outcome = "shared"
                    return await(it).forAsker(asker)
                }
                try {
                    val answer = answer(question, progress, asked, model)
                    cache?.put(key, answer)
                    mine.complete(answer)
                    outcome = outcomeOf(answer, asked)
                    return answer.forAsker(asker)
                } catch (e: Throwable) {
                    mine.completeExceptionally(e)
                    throw e
                } finally {
                    inFlight.remove(key, mine)
                }
            }
        } catch (e: Throwable) {
            observation.error(e)
            // Only the question deadline interrupts an asking thread (GuideLimits in guide.web).
            if (e is InterruptedException) outcome = "timeout"
            throw e
        } finally {
            if (Thread.currentThread().isInterrupted) outcome = "timeout"
            observation.lowCardinalityKeyValue("outcome", outcome)
            observation.stop()
            meters.counter("helper2.guide.questions", "outcome", outcome).increment()
            // Its own meter: adding a tag to helper2.guide.questions would split the series the dashboard and alerts read.
            meters.counter("helper2.guide.intents", "intent", question.intentTag).increment()
        }
    }

    /**
     * What the user asked: the text, the answer to a clarification and the parts of the reading they removed; and who
     * asks, for the daily web searches (one per question, however many searches it runs).
     */
    private class Question(val text: String, val path: AudiencePath?, val clarification: String?, val drops: Drops, val asker: Asker?) {
        /** The text the search and the no-model backup see: the question plus the user's answer to our question. */
        val fullText: String get() = listOfNotNull(text, clarification?.takeIf { it.isNotBlank() }).joinToString(" ")

        /** Set once the question is read; the metric tag of the intent. */
        var intentTag: String = "unknown"

        /** When the earliest web results this question's searches used stop being fresh. */
        var webFreshUntil: Instant? = null
    }

    /** The answer with [asker]'s web searches left; read for each asker, so a cached answer never shows another's. */
    private fun GuideAnswer.forAsker(asker: Asker?) = copy(webSearches = asker?.let(this@AskGuide.search::webSearchesLeft))

    /**
     * Every search of a question: under its asker's web-search quota, with the freshness its intent needs (dates,
     * newest and learning questions are time-bound), and noting how long the web results it used stay fresh.
     */
    private fun find(q: Question, r: Reading, request: SearchRequest): SearchOutcome {
        val intentClass = when (r.intent) {
            GuideIntent.NEWEST, GuideIntent.WHEN, GuideIntent.LEARN -> IntentClass.TIME_BOUND
            else -> IntentClass.GENERAL
        }
        val outcome = search.search(request.copy(asker = q.asker, intentClass = intentClass))
        outcome.webFreshUntil?.let { until -> q.webFreshUntil = q.webFreshUntil?.let { minOf(it, until) } ?: until }
        return outcome
    }

    /** The answer of the identical question already running; its failure is this asker's failure. */
    private fun await(shared: CompletableFuture<GuideAnswer>): GuideAnswer = try {
        shared.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun outcomeOf(answer: GuideAnswer, asked: Asked) = when {
        asked.budgetExhausted -> "budget_exhausted"
        answer.status == GuideStatus.NEEDS_CLARIFICATION -> "clarify"
        answer.status == GuideStatus.LLM_UNAVAILABLE -> "model_unavailable"
        answer.status == GuideStatus.NO_CANDIDATES -> "no_candidates"
        answer.recommendations.any { it.aiReasoned } || answer.comparison != null || answer.explanation != null -> "answered"
        else -> "degraded"
    }

    /** Why a model call failed, for the log: the adapter's category, or the exception's class. */
    private fun failure(e: RuntimeException) = (e as? GuideUnavailable)?.category ?: e.javaClass.simpleName

    /** How the question was read: the plan (or the backup rules alone), with the user's removals applied. */
    private class Reading(
        val plan: GuideSearchPlan?,
        val modelFailed: Boolean,
        val intent: GuideIntent,
        val subjects: List<String>,
        val window: DateWindow?,
        /** "latest" without a period: order by date, filter nothing. */
        val newestFirst: Boolean,
        val constraints: List<String>,
        val query: String,
        val webQuery: String?,
        val allowReading: Boolean,
    )

    private fun read(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): Reading {
        var modelFailed = model == null
        val plan = if (model == null) null else try {
            // The two-argument form unless the user answered a clarification: a model that only implements it keeps working.
            if (q.clarification == null) model.plan(q.text, q.path) else model.plan(q.text, q.path, q.clarification)
        } catch (e: RuntimeException) {
            log.warn("guide plan failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            modelFailed = true
            null
        }
        val text = q.fullText
        // Only what the user's own words support: the fast model copies values from its examples.
        val subjects = plan?.subjects.orEmpty().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && it.length <= MAX_SUBJECT_CHARS && !GuideValidator.containsLink(it) }
            .filter { text.contains(it, ignoreCase = true) }
            .distinctBy { it.lowercase() }.take(MAX_SUBJECTS)
        val modelIntent = plan?.intent?.takeIf { grounded(it, text, subjects) }
        val intent = when {
            q.drops.intent -> GuideIntent.SUGGEST
            else -> modelIntent ?: IntentRules.intentOf(text) ?: GuideIntent.SUGGEST
        }
        val modelWindow = plan?.window?.takeIf { IntentRules.mentionsTime(text) }
        val planned = if (q.drops.window) null else modelWindow ?: IntentRules.windowOf(text)
        val window = planned?.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        // A period with no other intent is a question about what is new ("AI tools from last month").
        val effective = if (window != null && intent == GuideIntent.SUGGEST) GuideIntent.NEWEST else intent
        val constraints = GuideValidator.normalizedConstraints(plan?.constraints.orEmpty())
            .filter { it.lowercase() !in q.drops.constraints && IntentRules.constraintStated(it, text) }
        val query = plan?.let { PlannedQueries.searchQueryOf(it.searchQuery) }?.ifBlank { null } ?: text
        if (!modelFailed) progress.planned(query, constraints)
        progress.understood(effective, subjects, window)
        q.intentTag = effective.name.lowercase()
        return Reading(
            plan = plan,
            modelFailed = modelFailed,
            intent = effective,
            subjects = subjects,
            window = window,
            newestFirst = effective == GuideIntent.NEWEST && window == null,
            constraints = constraints,
            query = query,
            webQuery = plan?.let { PlannedQueries.webQueryOf(it.webQuery) },
            allowReading = wantsReading(text, plan),
        )
    }

    /** A model intent the user's words support: newness for NEWEST, the named subjects for the subject intents. */
    private fun grounded(intent: GuideIntent, text: String, subjects: List<String>): Boolean = when (intent) {
        GuideIntent.NEWEST -> IntentRules.mentionsNewness(text)
        GuideIntent.COMPARE -> subjects.size >= 2
        GuideIntent.ALTERNATIVES, GuideIntent.WHAT_IS -> subjects.isNotEmpty()
        else -> true
    }

    /**
     * The planner's question back, kept only when the question really is too vague to search (ADR 0032 §3): never
     * after the user answered one, and never when a subject, words that name one, or a period can drive the search.
     */
    private fun clarificationOf(q: Question, r: Reading): Clarification? {
        val asked = r.plan?.clarify ?: return null
        if (q.clarification != null) return null
        val compareMissingSubject = r.intent == GuideIntent.COMPARE && r.subjects.size < 2
        val nothingToSearch = r.subjects.isEmpty() && r.window == null && IntentRules.subjectWords(r.query).isBlank()
        return if (compareMissingSubject || nothingToSearch) asked else null
    }

    private fun answer(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val r = read(q, progress, asked, model)
        clarificationOf(q, r)?.let { c ->
            progress.clarify(c)
            return GuideAnswer(
                GuideStatus.NEEDS_CLARIFICATION, r.query, emptyList(), null, true, 0, 0, SearchOutcome.empty(clock.instant()),
                clock.instant(), r.constraints, intent = r.intent, subjects = r.subjects, window = r.window, clarification = c,
            )
        }
        val usable = if (r.modelFailed) null else model
        val answer = when (r.intent) {
            GuideIntent.COMPARE -> compare(q, r, progress, asked, usable)
            GuideIntent.WHAT_IS -> explain(q, r, progress, asked, usable)
            GuideIntent.WHEN -> dates(q, r, progress)
            GuideIntent.ALTERNATIVES -> alternatives(q, r, progress, asked, usable)
            GuideIntent.NEWEST -> newest(q, r, progress, asked, usable)
            GuideIntent.LEARN -> picks(
                q, r, progress, asked, usable,
                // A stated period ("courses from last month") filters like it does for newest: undated courses drop out.
                Retrieval(r.query, SearchFilters(types = LEARN_TYPES, publishedAfter = r.window?.from, publishedBefore = r.window?.to)),
            )
            GuideIntent.HOW_TO, GuideIntent.SUGGEST -> picks(q, r, progress, asked, usable, Retrieval(r.query, readingFilter(r)))
        }
        return answer.copy(intent = r.intent, subjects = r.subjects, window = r.window, webFreshUntil = q.webFreshUntil)
    }

    /** Tools only, unless the user asked for something to read (seen in evaluation run 4: papers filled the slots). */
    private fun readingFilter(r: Reading) =
        if (r.allowReading) SearchFilters(types = ResourceType.entries.toSet() - LEARN_TYPES) else SearchFilters(types = TOOL_TYPES)

    /** One search for the pick pipeline: text, filters, order, and what must not be picked. */
    private data class Retrieval(
        val text: String,
        val filters: SearchFilters,
        val sort: SortOrder = SortOrder.RELEVANCE,
        val browse: Boolean = false,
        val exclude: (MergedResult) -> Boolean = { false },
        val notices: List<Notice> = emptyList(),
        /** Reading types may be chosen even though the question asks for no reading (web announcements for NEWEST). */
        val allowReading: Boolean = false,
        /** The chooser thinks before answering: only for a broad browse, where it has no subject to match. */
        val think: Boolean = false,
    )

    /** "AI tools born last month": created inside the window, chosen among the most relevant and used, shown newest first. */
    private fun newest(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val subject = IntentRules.subjectWords(r.query)
        // Web announcements count: "new AI tools last month" is answered as much by an article launching one as by a
        // catalog entry. The web query is AI-scoped and the web source drops non-AI pages.
        val types = TOOL_TYPES + if (r.allowReading) READING_TYPES else WEB_ANNOUNCEMENTS
        val filters = SearchFilters(types = types, publishedAfter = r.window?.from, publishedBefore = r.window?.to)
        val browse = r.window != null && subject.isBlank()
        val retrieval = Retrieval(
            // Browsing ranks by popularity inside the window: words like "AI tools" would favour projects named "tool".
            text = if (browse) "" else subject.ifBlank { r.query },
            filters = filters,
            // No period: "latest" orders by date. With a period, relevance inside it; the picks are dated below.
            sort = if (r.window == null) SortOrder.NEWEST else SortOrder.RELEVANCE,
            browse = browse,
            // A new entry without a description cannot be quoted, so it can never be chosen; it would only take a slot.
            exclude = { it.description.isNullOrBlank() },
            allowReading = true,
            think = browse,
        )
        val answer = picks(q, r, progress, asked, model, retrieval)
        return answer.copy(recommendations = answer.recommendations.sortedWith(compareByDescending(nullsLast()) { it.result.published.value }))
    }

    /** "alternatives to Cursor": the subject's own use cases as the search, the subject itself never a pick. */
    private fun alternatives(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val name = r.subjects.firstOrNull()
            ?: return picks(q, r, progress, asked, model, Retrieval(r.query, readingFilter(r)))
        val own = find(q, r, SearchRequest(text = name, path = q.path, filters = SearchFilters(types = TOOL_TYPES), size = SUBJECT_SEARCH_SIZE))
            .results.firstOrNull { isSubject(it, name) }
        val category = own?.let { listOfNotNull(it.useCases.joinToString(" ").ifBlank { null }, it.type.name.lowercase().replace('_', ' ')).joinToString(" ") }
        val notices = if (own == null) listOf(Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "$name was not found in our sources; showing tools for: ${r.query}")) else emptyList()
        val text = category?.takeIf { IntentRules.subjectWords(it).isNotBlank() } ?: r.query
        return picks(q, r, progress, asked, model, Retrieval(text, readingFilter(r), exclude = { isSubject(it, name) }, notices = notices))
    }

    /**
     * Retrieval first, AI second, validator last: the guide as it was before intents, parameterised by [retrieval].
     * Works (degraded) without a model.
     */
    private fun picks(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?, retrieval: Retrieval): GuideAnswer {
        val text = q.fullText
        val planStart = System.nanoTime()
        val searchStart = System.nanoTime()
        val outcome = find(
            q, r,
            SearchRequest(
                text = retrieval.text, path = q.path, filters = retrieval.filters, sort = retrieval.sort, size = maxCandidates,
                webQuery = r.webQuery, browse = retrieval.browse,
            ),
        )
        val searchEnd = System.nanoTime()
        val candidates = outcome.results.filterNot(retrieval.exclude).take(maxCandidates).mapIndexed { i, res -> Candidate("c${i + 1}", res) }
        // Defence in depth: the search filter already removed reading types; keep the model and the
        // validator on the same rule in case a source mislabels a type after filtering.
        val allowReading = r.allowReading || retrieval.allowReading
        val choosable = if (allowReading) candidates else candidates.filter { it.result.type !in READING_TYPES }
        progress.retrieved(candidates.size, outcome)
        if (log.isDebugEnabled) log.debug("guide candidates: {}", candidates.map { "${it.key} ${it.result.type} ${it.result.title.take(60)} ${it.result.published.value} desc=${it.result.description?.length ?: 0}" })
        val query = retrieval.text
        val constraints = r.constraints
        if (candidates.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, query, emptyList(), null, !r.modelFailed, 0, 0, outcome, clock.instant(), constraints,
                notices = retrieval.notices)
        }
        if (model == null) {
            return degraded(GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false).withNotices(retrieval.notices)
        }
        val proposal = try {
            if (choosable.isEmpty()) {
                return degraded(GuideStatus.PARTIAL, query, candidates, outcome, 0, modelUsed = true, constraints).withNotices(retrieval.notices)
            }
            if (retrieval.think) model.choose(text, choosable, constraints, true) else model.choose(text, choosable, constraints)
        } catch (e: RuntimeException) {
            log.warn("guide choose failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return degraded(GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false, constraints).withNotices(retrieval.notices)
        }
        val chooseEnd = System.nanoTime()
        progress.chosen(proposal.picks.size)
        val checked = validator.validate(proposal, choosable, constraints, allowReading).insideWindow(retrieval.filters)
        progress.validated(checked.picks.size, checked.rejected)
        // Search rank of each kept pick. A pick far down the list is the first sign of text written to be chosen
        // (content poisoning): its quote is genuine, so the gate cannot catch it.
        val keptRanks = checked.picks.map { candidates.indexOf(it.candidate) + 1 }
        keptRanks.forEach { meters.counter("helper2.guide.picks", "rank", rankBand(it)).increment() }
        val verifyStart = System.nanoTime()
        val verdicts = if (checked.picks.isEmpty()) emptyMap() else verdicts(model, text, claimsOf(checked), progress)
        val verifyEnd = System.nanoTime()
        // Timings feed the evaluation and the provider feedback; no user text is logged.
        log.info(
            "guide timings: intent=${r.intent} plan(${model.fastModel})=${ms(planStart, searchStart)}ms " +
                "search=${ms(searchStart, searchEnd)}ms choose(${model.reasoningModel})=${ms(searchEnd, chooseEnd)}ms " +
                "verify=${ms(verifyStart, verifyEnd)}ms " +
                "candidates=${candidates.size} kept=${checked.picks.size} keptRanks=$keptRanks rejected=${checked.rejected} " +
                "rejectedBy=${checked.rejectedBy} reasons=${verdicts.values.groupingBy { it }.eachCount()}",
        )
        if (checked.picks.isEmpty()) {
            return degraded(GuideStatus.PARTIAL, query, candidates, outcome, checked.rejected, modelUsed = true, constraints).withNotices(retrieval.notices)
        }
        return GuideAnswer(
            status = if (checked.rejected == 0) GuideStatus.OK else GuideStatus.PARTIAL,
            searchQuery = query,
            recommendations = checked.picks.map { (c, p) ->
                val verdict = verdicts[c.key] ?: ReasonCheck.NOT_CHECKED
                Recommendation(
                    c.result, if (verdict == ReasonCheck.REMOVED) "" else p.reason, p.quote, aiReasoned = true,
                    checks = checked.checks[c.key].orEmpty(), reasonCheck = verdict,
                )
            },
            prompt = checked.prompt,
            modelUsed = true,
            candidatesConsidered = candidates.size,
            rejected = checked.rejected,
            search = outcome,
            generatedAt = clock.instant(),
            constraints = constraints,
            notices = retrieval.notices,
        )
    }

    /**
     * A pick born outside the window is rejected by code, not left to the model: the search filter already keeps
     * only dated candidates inside it, so this only catches a source that misreports a date after filtering.
     */
    private fun GuideValidator.Result.insideWindow(filters: SearchFilters): GuideValidator.Result {
        if (filters.publishedAfter == null && filters.publishedBefore == null) return this
        val inside = picks.filter { Filtering.apply(listOf(it.candidate.result), SearchFilters(publishedAfter = filters.publishedAfter, publishedBefore = filters.publishedBefore)).isNotEmpty() }
        val out = picks.size - inside.size
        if (out == 0) return this
        return copy(picks = inside, rejected = rejected + out, rejectedBy = rejectedBy + (GuideValidator.Rejection.OUTSIDE_WINDOW to out))
    }

    /**
     * The evidence includes the creation date its source states, when known: a reason that says "new this month" is
     * then checked against that date instead of being removed for a claim the text cannot show.
     */
    private fun claimsOf(checked: GuideValidator.Result) = checked.picks.map { (c, p) ->
        val created = c.result.published.value?.let { "\nCreated: ${DateTimeFormatter.ISO_LOCAL_DATE.format(it.atOffset(ZoneOffset.UTC))} (as stated by ${c.result.published.sourceId ?: "its source"})" } ?: ""
        ReasonClaim(c.key, p.reason, p.quote, c.text + created, checked.checks[c.key].orEmpty().filter { it.status == CheckStatus.CONFIRMED })
    }

    /**
     * "Ollama vs LM Studio": each subject's own entry, then one model call that fills aspect rows; a cell survives only
     * with a quote from that subject's own text. Fewer than two subjects found: the picks of the one found.
     */
    private fun compare(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val found = r.subjects.map { name ->
            name to find(q, r, SearchRequest(text = name, path = q.path, size = SUBJECT_SEARCH_SIZE)).results.firstOrNull { isSubject(it, name) }
        }
        val subjects = found.mapIndexed { i, (name, res) -> ComparedSubject("s${i + 1}", name, res) }
        val present = subjects.mapNotNull { s -> s.result?.let { Candidate(s.key, it) } }
        progress.retrieved(present.size, SearchOutcome.empty(clock.instant()))
        if (present.size < 2) {
            val fallback = present.firstOrNull()?.result?.title ?: r.query
            val notices = subjects.filter { it.result == null }.map { Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "${it.name} was not found in our sources.") }
            return picks(q, r, progress, asked, model, Retrieval(fallback, readingFilter(r), notices = notices))
        }
        val empty = Comparison(subjects, emptyList())
        if (model == null) return subjectsOnly(GuideStatus.LLM_UNAVAILABLE, r, present, empty, modelUsed = false)
        val proposal = try {
            model.compare(q.fullText, present)
        } catch (e: RuntimeException) {
            log.warn("guide compare failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return subjectsOnly(GuideStatus.LLM_UNAVAILABLE, r, present, empty, modelUsed = false)
        }
        progress.chosen(proposal.rows.size)
        val byKey = present.associateBy { it.key }
        var rejected = 0
        val rows = proposal.rows.asSequence()
            .map { it.aspect.trim() }.zip(proposal.rows.asSequence())
            .filter { (aspect, _) -> aspect.isNotEmpty() && aspect.length <= MAX_ASPECT_CHARS && !GuideValidator.containsLink(aspect) }
            .distinctBy { (aspect, _) -> aspect.lowercase() }
            .take(MAX_ROWS)
            .map { (aspect, row) ->
                ComparisonRow(aspect, subjects.map { s ->
                    val cell = row.cells.firstOrNull { it.subjectKey.trim() == s.key }
                    val c = byKey[s.key]
                    val ok = cell != null && c != null && cell.text.isNotBlank() && cell.text.length <= MAX_CELL_CHARS &&
                        !GuideValidator.containsLink(cell.text) && validator.shortQuoteHolds(cell.quote, c.text)
                    if (cell != null && !ok) rejected++
                    if (ok) ComparisonCell(s.key, cell!!.text.trim(), cell.quote.trim()) else ComparisonCell(s.key, null, null)
                })
            }
            .filter { row -> row.cells.any { it.text != null } }
            .toList()
        progress.validated(rows.sumOf { row -> row.cells.count { it.text != null } }, rejected)
        log.info("guide compare: subjects=${present.size} rows=${proposal.rows.size} keptRows=${rows.size} rejectedCells=$rejected")
        val claims = rows.flatMapIndexed { i, row ->
            row.cells.filter { it.text != null }.map { cell -> ReasonClaim("r${i + 1}-${cell.subjectKey}", cell.text!!, cell.quote!!, byKey.getValue(cell.subjectKey).text, emptyList()) }
        }
        val verdicts = if (claims.isEmpty()) emptyMap() else verdicts(model, q.fullText, claims, progress)
        val checkedRows = rows.mapIndexed { i, row ->
            row.copy(cells = row.cells.map { cell ->
                when (val v = verdicts["r${i + 1}-${cell.subjectKey}"]) {
                    null -> cell
                    ReasonCheck.REMOVED -> ComparisonCell(cell.subjectKey, null, null, ReasonCheck.REMOVED)
                    else -> cell.copy(reasonCheck = v)
                }
            })
        }
        val status = if (checkedRows.isEmpty()) GuideStatus.PARTIAL else if (rejected == 0) GuideStatus.OK else GuideStatus.PARTIAL
        return subjectsOnly(status, r, present, Comparison(subjects, checkedRows), modelUsed = true, rejected = rejected)
    }

    private fun subjectsOnly(status: GuideStatus, r: Reading, present: List<Candidate>, comparison: Comparison, modelUsed: Boolean, rejected: Int = 0) =
        GuideAnswer(
            status, r.query,
            present.map { Recommendation(it.result, "", (it.result.description ?: it.result.title).take(160), aiReasoned = false) },
            null, modelUsed, present.size, rejected, SearchOutcome.empty(clock.instant()), clock.instant(), r.constraints,
            comparison = comparison,
        )

    /**
     * "what is MCP?": the subject's own entry first, then what our sources say about it; one model call writes a few
     * sentences, each kept only with a quote from one of them. No sentence left: the subject's card alone.
     */
    private fun explain(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val subject = r.subjects.firstOrNull() ?: r.query
        val outcome = find(q, r, SearchRequest(text = subject, path = q.path, size = maxCandidates, webQuery = r.webQuery))
        // Only what can be quoted: an entry without a description cannot support a sentence. The subject's own entry
        // first, then web explainers (articles, news), which say what a thing is better than projects named after it.
        val ordered = outcome.results.filterNot { it.description.isNullOrBlank() }
            .sortedWith(compareByDescending<MergedResult> { isSubject(it, subject) }.thenByDescending { it.type in WEB_ANNOUNCEMENTS })
            .take(EXPLAIN_CANDIDATES)
        val candidates = ordered.mapIndexed { i, res -> Candidate("c${i + 1}", res) }
        progress.retrieved(candidates.size, outcome)
        if (log.isDebugEnabled) log.debug("guide explain candidates: {}", candidates.map { "${it.key} ${it.result.type} ${it.result.title.take(60)} subject=${isSubject(it.result, subject)} desc=${it.result.description?.length ?: 0}" })
        if (candidates.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, subject, emptyList(), null, !r.modelFailed, 0, 0, outcome, clock.instant(), r.constraints)
        }
        // The subject's own card, then up to RELATED_CARDS - 1 related ones, in search order.
        val own = candidates.firstOrNull { isSubject(it.result, subject) }
        val cards = listOfNotNull(own) + candidates.filter { it !== own }.take(RELATED_CARDS - listOfNotNull(own).size)
        fun cardsAnswer(status: GuideStatus, modelUsed: Boolean, explanation: Explanation?, rejected: Int) = GuideAnswer(
            status, subject,
            cards.map {
                Recommendation(it.result, if (isSubject(it.result, subject)) SUBJECT_REASON else RELATED_REASON,
                    (it.result.description ?: it.result.title).take(160), aiReasoned = false)
            },
            null, modelUsed, candidates.size, rejected, outcome, clock.instant(), r.constraints, explanation = explanation,
        )
        if (model == null) return cardsAnswer(GuideStatus.LLM_UNAVAILABLE, false, null, 0)
        val proposal = try {
            model.explain(q.fullText, subject, candidates)
        } catch (e: RuntimeException) {
            log.warn("guide explain failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return cardsAnswer(GuideStatus.LLM_UNAVAILABLE, false, null, 0)
        }
        progress.chosen(proposal.sentences.size)
        val byKey = candidates.associateBy { it.key }
        var rejected = 0
        val kept = proposal.sentences.mapNotNull { p ->
            val c = byKey[p.candidateKey.trim()]
            val ok = c != null && p.text.isNotBlank() && p.text.length <= MAX_SENTENCE_CHARS && !GuideValidator.containsLink(p.text) &&
                validator.quoteHolds(p.quote, c.text)
            if (!ok) rejected++
            if (ok) c!!.key to p else null
        }.take(MAX_SENTENCES)
        progress.validated(kept.size, rejected)
        log.info("guide explain: candidates=${candidates.size} proposed=${proposal.sentences.size} kept=${kept.size} rejected=$rejected")
        val claims = kept.mapIndexed { i, (key, p) -> ReasonClaim("e${i + 1}", p.text.trim(), p.quote.trim(), byKey.getValue(key).text, emptyList()) }
        val verdicts = if (claims.isEmpty()) emptyMap() else verdicts(model, q.fullText, claims, progress)
        val sentences = kept.mapIndexedNotNull { i, (key, p) ->
            when (val v = verdicts["e${i + 1}"] ?: ReasonCheck.NOT_CHECKED) {
                ReasonCheck.REMOVED -> null
                else -> ExplainedSentence(p.text.trim(), p.quote.trim(), byKey.getValue(key).result, v)
            }
        }
        val status = when {
            sentences.isEmpty() -> GuideStatus.PARTIAL
            rejected == 0 && sentences.size == kept.size -> GuideStatus.OK
            else -> GuideStatus.PARTIAL
        }
        return cardsAnswer(status, true, Explanation(sentences).takeIf { sentences.isNotEmpty() }, rejected)
    }

    /**
     * "when is NeurIPS?", "AI events next month", "when was Llama 4 released?": dates come from records, never from a
     * model. Events: their stated days, soonest first. Otherwise the subject's creation date and the source stating it.
     */
    private fun dates(q: Question, r: Reading, progress: GuideProgress): GuideAnswer {
        val subject = r.subjects.firstOrNull()
        val text = subject ?: IntentRules.subjectWords(r.query).ifBlank { "AI" }
        val outcome = find(q, r, SearchRequest(text = text, path = q.path, size = maxCandidates))
        progress.retrieved(outcome.results.size, outcome)
        val window = r.window
        val events = outcome.results.filter { it.type == ResourceType.EVENT && it.eventStart.value != null }
            .filter { e -> subject == null || e.title.contains(subject, ignoreCase = true) }
            .filter { e ->
                val start = e.eventStart.value!!
                val end = e.eventEnd.value ?: start
                if (window != null) {
                    !end.isBefore(window.from.atStartOfDay(ZoneOffset.UTC).toInstant()) &&
                        start.isBefore(window.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                } else {
                    !end.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant())
                }
            }
            .sortedBy { it.eventStart.value }
            .take(MAX_EVENTS)
        if (events.isNotEmpty()) {
            return GuideAnswer(
                GuideStatus.OK, text, events.map { Recommendation(it, eventReason(it), (it.description ?: it.title).take(160), aiReasoned = false) },
                null, !r.modelFailed, outcome.results.size, 0, outcome, clock.instant(), r.constraints,
            )
        }
        val own = subject?.let { name -> outcome.results.firstOrNull { isSubject(it, name) && it.type != ResourceType.EVENT } }
        if (own != null) {
            val date = own.published.value
            val reason = if (date == null) "No release date found in our sources."
            else "Created ${DateTimeFormatter.ISO_LOCAL_DATE.format(date.atOffset(ZoneOffset.UTC))}, as stated by ${own.published.sourceId ?: "its source"}."
            return GuideAnswer(
                if (date == null) GuideStatus.PARTIAL else GuideStatus.OK, text,
                listOf(Recommendation(own, reason, (own.description ?: own.title).take(160), aiReasoned = false)),
                null, !r.modelFailed, outcome.results.size, 0, outcome, clock.instant(), r.constraints,
            )
        }
        if (outcome.results.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, text, emptyList(), null, !r.modelFailed, 0, 0, outcome, clock.instant(), r.constraints)
        }
        return degraded(GuideStatus.PARTIAL, text, outcome.results.mapIndexed { i, res -> Candidate("c${i + 1}", res) }, outcome, 0, !r.modelFailed)
            .withNotices(listOf(Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "No date found in our sources; showing the closest search results.")))
    }

    private fun eventReason(e: MergedResult): String {
        val day = DateTimeFormatter.ISO_LOCAL_DATE
        val start = day.format(e.eventStart.value!!.atOffset(ZoneOffset.UTC))
        val end = e.eventEnd.value?.let { day.format(it.atOffset(ZoneOffset.UTC)) }?.takeIf { it != start }
        val where = e.location?.takeIf { it.isNotBlank() }
        return buildString {
            append(if (end == null) "On $start" else "From $start to $end")
            if (where != null) append(", $where")
            append(" (as stated by ${e.eventStart.sourceId ?: "its source"}).")
        }
    }

    private fun GuideAnswer.withNotices(extra: List<Notice>) = if (extra.isEmpty()) this else copy(notices = notices + extra)

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    /** The reason check; any failure leaves every claim NOT_CHECKED, never removes one. */
    private fun verdicts(model: GuideModel, request: String, claims: List<ReasonClaim>, progress: GuideProgress): Map<String, ReasonCheck> {
        val raw = try {
            model.verifyReasons(request, claims)
        } catch (e: RuntimeException) {
            log.warn("guide reason check failed: ${failure(e)}")
            emptyMap()
        }
        val verdicts = claims.mapNotNull { c -> raw[c.key]?.let { c.key to if (it) ReasonCheck.SUPPORTED else ReasonCheck.REMOVED } }.toMap()
        progress.reasonsChecked(
            supported = verdicts.values.count { it == ReasonCheck.SUPPORTED },
            removed = verdicts.values.count { it == ReasonCheck.REMOVED },
            notChecked = claims.size - verdicts.size,
        )
        return verdicts
    }

    /** No trustworthy AI answer: show the top search results, clearly marked as not AI-reasoned. */
    private fun degraded(
        status: GuideStatus,
        query: String,
        candidates: List<Candidate>,
        outcome: SearchOutcome,
        rejected: Int,
        modelUsed: Boolean,
        constraints: List<String> = emptyList(),
    ) =
        GuideAnswer(
            status = status,
            searchQuery = query,
            recommendations = candidates.take(3).map { c ->
                Recommendation(
                    c.result, "Top search result (no AI reasoning available).", (c.result.description ?: c.result.title).take(160),
                    aiReasoned = false,
                    checks = constraints.map { ConstraintCheck(it, CheckStatus.NOT_CONFIRMED, null) },
                )
            },
            prompt = null,
            modelUsed = modelUsed,
            candidatesConsidered = candidates.size,
            rejected = rejected,
            search = outcome,
            generatedAt = clock.instant(),
            constraints = constraints,
        )
}
