package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.READING_TYPES
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.TOOL_TYPES
import ai.helper2.guide.isSubject
import ai.helper2.search.Filtering
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchRequest
import ai.helper2.search.SortOrder
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

/** Tools only, unless the user asked for something to read (seen in evaluation run 4: papers filled the slots). */
internal fun readingFilter(r: Reading) =
    if (r.allowReading) SearchFilters(types = ResourceType.entries.toSet() - LEARN_TYPES) else SearchFilters(types = TOOL_TYPES)

/** One search for the pick pipeline: text, filters, order, and what must not be picked. */
internal data class Retrieval(
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

/**
 * The pick pipeline of SUGGEST, HOW_TO, LEARN, NEWEST and ALTERNATIVES: one search, the chooser among real candidates,
 * the validator, the reason check.
 */
internal class PickAnswer(
    private val steps: GuideSteps,
    private val reasonCheck: ReasonCheckStep,
    private val validator: GuideValidator,
    private val maxCandidates: Int,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PickAnswer::class.java)

    /** "AI tools born last month": created inside the window, chosen among the most relevant and used, shown newest first. */
    fun newest(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
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
    fun alternatives(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val name = r.subjects.firstOrNull()
            ?: return picks(q, r, progress, asked, model, Retrieval(r.query, readingFilter(r)))
        val own = steps.step(q, "search") {
            steps.find(q, r, SearchRequest(text = name, path = q.path, filters = SearchFilters(types = TOOL_TYPES), size = SUBJECT_SEARCH_SIZE))
        }.results.firstOrNull { isSubject(it, name) }
        val category = own?.let { listOfNotNull(it.useCases.joinToString(" ").ifBlank { null }, it.type.name.lowercase().replace('_', ' ')).joinToString(" ") }
        val notices = if (own == null) listOf(Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "$name was not found in our sources; showing tools for: ${r.query}")) else emptyList()
        val text = category?.takeIf { IntentRules.subjectWords(it).isNotBlank() } ?: r.query
        return picks(q, r, progress, asked, model, Retrieval(text, readingFilter(r), exclude = { isSubject(it, name) }, notices = notices))
    }

    /**
     * Retrieval first, AI second, validator last: the guide as it was before intents, parameterised by [retrieval].
     * Works (degraded) without a model.
     */
    fun picks(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?, retrieval: Retrieval): GuideAnswer {
        val text = q.fullText
        val outcome = steps.step(q, "search") {
            steps.find(
                q, r,
                SearchRequest(
                    text = retrieval.text, path = q.path, filters = retrieval.filters, sort = retrieval.sort, size = maxCandidates,
                    webQuery = r.webQuery, browse = retrieval.browse,
                ),
            )
        }
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
            return degraded(clock, GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false).withNotices(retrieval.notices)
        }
        val proposal = try {
            if (choosable.isEmpty()) {
                return degraded(clock, GuideStatus.PARTIAL, query, candidates, outcome, 0, modelUsed = true, constraints).withNotices(retrieval.notices)
            }
            steps.step(q, "choose") {
                if (retrieval.think) model.choose(text, choosable, constraints, true) else model.choose(text, choosable, constraints)
            }
        } catch (e: RuntimeException) {
            log.warn("guide choose failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return degraded(clock, GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false, constraints).withNotices(retrieval.notices)
        }
        progress.chosen(proposal.picks.size)
        val checked = steps.step(q, "validate") { validator.validate(proposal, choosable, constraints, allowReading).insideWindow(retrieval.filters) }
        progress.validated(checked.picks.size, checked.rejected)
        // Search rank of each kept pick. A pick far down the list is the first sign of text written to be chosen
        // (content poisoning): its quote is genuine, so the gate cannot catch it.
        val keptRanks = checked.picks.map { candidates.indexOf(it.candidate) + 1 }
        keptRanks.forEach { meters.counter("helper2.guide.picks", "rank", AskGuide.rankBand(it)).increment() }
        val verdicts = if (checked.picks.isEmpty()) emptyMap() else reasonCheck.verdicts(q, model, claimsOf(checked), progress)
        log.info(
            "guide picks: candidates=${candidates.size} kept=${checked.picks.size} keptRanks=$keptRanks rejected=${checked.rejected} " +
                "rejectedBy=${checked.rejectedBy} reasons=${verdicts.values.groupingBy { it }.eachCount()}",
        )
        if (checked.picks.isEmpty()) {
            return degraded(clock, GuideStatus.PARTIAL, query, candidates, outcome, checked.rejected, modelUsed = true, constraints).withNotices(retrieval.notices)
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
}
