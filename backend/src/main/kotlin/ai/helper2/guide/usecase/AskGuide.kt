package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.ConstraintCheck
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.READING_TYPES
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.sha256Hex
import ai.helper2.guide.wantsReading
import ai.helper2.guide.PlannedQueries
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
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
    }

    /**
     * A cached or shared answer is returned without any [progress] call: no step ran for this asker.
     * [client] identifies the asker for the per-client quota; null (internal callers, tests) is never limited.
     */
    @JvmOverloads
    fun ask(request: String, path: AudiencePath?, progress: GuideProgress = GuideProgress.NONE, client: String? = null): GuideAnswer {
        val observation = Observation.createNotStarted("helper2.guide.ask", observations).start()
        var outcome = "error"
        try {
            observation.openScope().use {
                val key = GuideAnswerCache.key(request, path, configHash)
                if (cache != null) {
                    val hit = cache.get(key)
                    meters.counter("helper2.guide.cache", "result", if (hit != null) "hit" else "miss").increment()
                    if (hit != null) {
                        outcome = "cached"
                        return hit
                    }
                }
                inFlight[key]?.let {
                    outcome = "shared"
                    return await(it)
                }
                val asked = Asked()
                if (client != null && quota?.tryAcquire(client) == false) {
                    log.info("guide client quota reached: answering from search only")
                    outcome = "client_quota"
                    val searchOnly = answer(request, path, progress, asked, model = null)
                    return searchOnly.copy(notices = searchOnly.notices + DAILY_QUOTA_NOTICE)
                }
                val mine = CompletableFuture<GuideAnswer>()
                inFlight.putIfAbsent(key, mine)?.let {
                    outcome = "shared"
                    return await(it)
                }
                try {
                    val answer = answer(request, path, progress, asked, model)
                    cache?.put(key, answer)
                    mine.complete(answer)
                    outcome = outcomeOf(answer, asked)
                    return answer
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
        }
    }

    /** The answer of the identical question already running; its failure is this asker's failure. */
    private fun await(shared: CompletableFuture<GuideAnswer>): GuideAnswer = try {
        shared.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun outcomeOf(answer: GuideAnswer, asked: Asked) = when {
        asked.budgetExhausted -> "budget_exhausted"
        answer.status == GuideStatus.LLM_UNAVAILABLE -> "model_unavailable"
        answer.status == GuideStatus.NO_CANDIDATES -> "no_candidates"
        answer.recommendations.any { it.aiReasoned } -> "answered"
        else -> "degraded"
    }

    /** Why a model call failed, for the log: the adapter's category, or the exception's class. */
    private fun failure(e: RuntimeException) = (e as? GuideUnavailable)?.category ?: e.javaClass.simpleName

    private fun answer(request: String, path: AudiencePath?, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val text = request.trim()
        var modelFailed = model == null
        val planStart = System.nanoTime()
        var constraints = emptyList<String>()
        var plan: GuideSearchPlan? = null
        val query = if (model == null) text else try {
            plan = model.plan(text, path)
            constraints = GuideValidator.normalizedConstraints(plan.constraints)
            PlannedQueries.searchQueryOf(plan.searchQuery).ifBlank { text }
        } catch (e: RuntimeException) {
            log.warn("guide plan failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            modelFailed = true
            text
        }
        if (!modelFailed) progress.planned(query, constraints)
        val searchStart = System.nanoTime()
        val allowReading = wantsReading(text, plan)
        // Asked for a tool: retrieve tools only, so papers and news cannot fill the candidate slots
        // (seen in evaluation run 4: "open source MCP server Postgres" retrieved papers, not servers).
        val filters = if (allowReading) SearchFilters() else SearchFilters(types = ResourceType.entries.toSet() - READING_TYPES)
        val webQuery = plan?.let { PlannedQueries.webQueryOf(it.webQuery) }
        val outcome = search.search(SearchRequest(text = query, path = path, filters = filters, size = maxCandidates, webQuery = webQuery))
        val searchEnd = System.nanoTime()
        val candidates = outcome.results.take(maxCandidates).mapIndexed { i, r -> Candidate("c${i + 1}", r) }
        // Defence in depth: the search filter already removed reading types; keep the model and the
        // validator on the same rule in case a source mislabels a type after filtering.
        val choosable = if (allowReading) candidates else candidates.filter { it.result.type !in READING_TYPES }
        progress.retrieved(candidates.size, outcome)
        if (candidates.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, query, emptyList(), null, !modelFailed, 0, 0, outcome, clock.instant(), constraints)
        }
        if (model == null || modelFailed) {
            return degraded(GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false)
        }
        val proposal = try {
            if (choosable.isEmpty()) {
                return degraded(GuideStatus.PARTIAL, query, candidates, outcome, 0, modelUsed = true, constraints)
            }
            model.choose(text, choosable, constraints)
        } catch (e: RuntimeException) {
            log.warn("guide choose failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return degraded(GuideStatus.LLM_UNAVAILABLE, query, candidates, outcome, 0, modelUsed = false, constraints)
        }
        val chooseEnd = System.nanoTime()
        progress.chosen(proposal.picks.size)
        val checked = validator.validate(proposal, choosable, constraints, allowReading)
        progress.validated(checked.picks.size, checked.rejected)
        // Search rank of each kept pick. A pick far down the list is the first sign of text written to be chosen
        // (content poisoning): its quote is genuine, so the gate cannot catch it.
        val keptRanks = checked.picks.map { candidates.indexOf(it.candidate) + 1 }
        keptRanks.forEach { meters.counter("helper2.guide.picks", "rank", rankBand(it)).increment() }
        val verifyStart = System.nanoTime()
        val verdicts = if (checked.picks.isEmpty()) emptyMap() else verdicts(model, text, checked, progress)
        val verifyEnd = System.nanoTime()
        // Timings feed the evaluation and the provider feedback; no user text is logged.
        log.info(
            "guide timings: plan(${model.fastModel})=${ms(planStart, searchStart)}ms " +
                "search=${ms(searchStart, searchEnd)}ms choose(${model.reasoningModel})=${ms(searchEnd, chooseEnd)}ms " +
                "verify=${ms(verifyStart, verifyEnd)}ms " +
                "candidates=${candidates.size} kept=${checked.picks.size} keptRanks=$keptRanks rejected=${checked.rejected} " +
                "rejectedBy=${checked.rejectedBy} reasons=${verdicts.values.groupingBy { it }.eachCount()}",
        )
        if (checked.picks.isEmpty()) {
            return degraded(GuideStatus.PARTIAL, query, candidates, outcome, checked.rejected, modelUsed = true, constraints)
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
        )
    }

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    /** The reason check; any failure leaves every reason NOT_CHECKED, never removes a pick. */
    private fun verdicts(model: GuideModel, request: String, checked: GuideValidator.Result, progress: GuideProgress): Map<String, ReasonCheck> {
        val claims = checked.picks.map { (c, p) ->
            ReasonClaim(c.key, p.reason, p.quote, c.text, checked.checks[c.key].orEmpty().filter { it.status == CheckStatus.CONFIRMED })
        }
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
