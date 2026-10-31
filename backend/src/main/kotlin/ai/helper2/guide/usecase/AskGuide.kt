package ai.helper2.guide.usecase

import ai.helper2.guide.AnswerLimits
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.ReadingKey
import ai.helper2.guide.SimilarQuestions
import ai.helper2.guide.sha256Hex
import ai.helper2.search.Asker
import ai.helper2.search.Feature
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import org.slf4j.LoggerFactory

/**
 * Retrieval first, AI second, validator last. Works (degraded) without a model.
 *
 * Fresh questions cost model calls, so three things stand before them: the answer [cache]; one shared
 * computation for identical questions asked at the same time; and the per-client [quota], past which a
 * client gets the degraded answer (top search results) instead of an error. With [similar], a question that
 * misses the cache is planned and then answered with the cached answer of a near twin read the same way.
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
    /** How long the subject searches of a comparison may take together; a subject not found by then is not found. */
    private val subjectSearchDeadline: Duration = SUBJECT_SEARCH_DEADLINE,
    private val limits: AnswerLimits = AnswerLimits.DEFAULT,
    /** Reuse of a similar question's answer (ADR 0041); null (no threshold set) keeps the exact key only. */
    private val similar: SimilarQuestions? = null,
) {
    private val log = LoggerFactory.getLogger(AskGuide::class.java)

    /** Everything that changes an answer besides the question: a change gives new cache keys. */
    private val configHash: String = sha256Hex(
        listOf(model?.provider, model?.fastModel, model?.reasoningModel, model?.configFingerprint, validator.fingerprint, limits.fingerprint, maxCandidates)
            .joinToString("\u0000"),
    ).take(16)

    /** Questions being answered right now, by cache key; a second asker waits for the first answer. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<GuideAnswer>>()


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

        /** Above the slowest source's timeout plus the query translation: a subject still searching by then is lost. */
        @JvmField
        val SUBJECT_SEARCH_DEADLINE: Duration = Duration.ofSeconds(10)
    }

    private val steps = GuideSteps(search, observations)
    private val reader = QuestionReader(clock)
    private val reasonCheck = ReasonCheckStep(steps)
    private val picks = PickAnswer(steps, reasonCheck, validator, maxCandidates, clock, meters)
    private val comparison = CompareAnswer(steps, picks, reasonCheck, validator, limits, clock, subjectSearchDeadline)
    private val explanation = ExplainAnswer(steps, reasonCheck, validator, limits, maxCandidates, clock)
    private val dates = DateAnswer(steps, maxCandidates, clock)

    /**
     * A cached or shared answer is returned without any [progress] call: no step ran for this asker. A similar
     * question's answer comes after the plan's calls (`planned`, `understood`) and no other.
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
        var cacheResult = "miss"
        val asker = client?.let { Asker(Feature.GUIDE, it, userId) }
        val question = Question(request.trim(), path, clarification?.trim(), drops, asker)
        try {
            observation.openScope().use {
                val key = GuideAnswerCache.key(request, path, configHash, question.clarification, drops)
                cache?.get(key)?.let {
                    cacheResult = "exact"
                    outcome = "cached"
                    return it.forAsker(asker)
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
                    // Cached in Redis and shared with other askers: it must not carry this asker's address or user id.
                    val answer = answer(question, progress, asked, model).withoutAsker()
                    val expiresAt = cache?.put(key, answer)
                    // A reused answer is not indexed again: a chain of near twins would drift from the question answered.
                    if (expiresAt != null && !asked.reused) asked.probe?.let { similar?.remember(it, key, expiresAt) }
                    mine.complete(answer)
                    if (asked.reused) cacheResult = "similar"
                    outcome = if (asked.reused) "cached" else outcomeOf(answer, asked)
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
            if (cache != null) meters.counter("helper2.guide.cache", "result", cacheResult).increment()
            meters.counter("helper2.guide.questions", "outcome", outcome).increment()
            // Its own meter: adding a tag to helper2.guide.questions would split the series the dashboard and alerts read.
            meters.counter("helper2.guide.intents", "intent", question.intentTag).increment()
        }
    }

    /** The answer with [asker]'s web searches left; read for each asker, so a cached answer never shows another's. */
    private fun GuideAnswer.forAsker(asker: Asker?) = copy(webSearches = asker?.let(this@AskGuide.search::webSearchesLeft))

    private fun GuideAnswer.withoutAsker() = copy(search = search.copy(request = search.request.copy(asker = null)))

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

    private fun answer(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        try {
            return answerByIntent(q, progress, asked, model)
        } finally {
            // Timings feed the evaluation and the provider feedback; no user text is logged.
            log.info(
                (listOf("guide timings: intent=${q.intentTag}") + q.timings.map { (step, ms) -> "$step=${ms}ms" } +
                    listOfNotNull(model?.let { "fast=${it.fastModel} reasoning=${it.reasoningModel}" }) + q.counts).joinToString(" "),
            )
        }
    }

    /**
     * The cached answer of a question read the same way and worded closely enough (ADR 0041), once the planner read
     * this one. Not for a question with a clarification or removed parts: those are rare and read differently. Sets
     * [Asked.probe], so the fresh answer is indexed, or [Asked.reused].
     */
    private fun similarAnswer(q: Question, r: Reading, asked: Asked): GuideAnswer? {
        if (similar == null || cache == null || q.clarification != null || !q.drops.isEmpty || r.plan == null || r.modelFailed) return null
        val probe = similar.probe(q.text, configHash, q.path, r.key()) ?: return null
        asked.probe = probe
        val answer = similar.nearest(probe)?.let(cache::get) ?: return null
        asked.reused = true
        return answer
    }

    private fun Reading.key() = ReadingKey(intent, subjects, constraints, window, allowReading, aboutEvent, askBackSubject != null)

    private fun answerByIntent(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val r = steps.step(q, "plan") { reader.read(q, progress, asked, model) }
        reader.clarificationOf(q, r)?.let { c ->
            return askBack(clock, r, c, SearchOutcome.empty(clock.instant()), progress)
                .copy(intent = r.intent, subjects = r.subjects, window = r.window, planned = true)
        }
        similarAnswer(q, r, asked)?.let { return it }
        val usable = if (r.modelFailed) null else model
        val answer = when (r.intent) {
            GuideIntent.COMPARE -> comparison.answer(q, r, progress, asked, usable)
            GuideIntent.WHAT_IS -> explanation.answer(q, r, progress, asked, usable)
            GuideIntent.WHEN -> dates.answer(q, r, progress)
            GuideIntent.ALTERNATIVES -> picks.alternatives(q, r, progress, asked, usable)
            GuideIntent.NEWEST -> picks.newest(q, r, progress, asked, usable)
            GuideIntent.LEARN -> picks.picks(
                q, r, progress, asked, usable,
                // A stated period ("courses from last month") filters like it does for newest: undated courses drop out.
                Retrieval(r.query, SearchFilters(types = LEARN_TYPES, publishedAfter = r.window?.from, publishedBefore = r.window?.to)),
            )
            GuideIntent.HOW_TO, GuideIntent.SUGGEST -> picks.picks(q, r, progress, asked, usable, Retrieval(r.query, readingFilter(r), askBack = true))
        }
        return answer.copy(intent = r.intent, subjects = r.subjects, window = r.window, webFreshUntil = q.webFreshUntil,
            planned = r.plan != null && !r.modelFailed)
    }
}
