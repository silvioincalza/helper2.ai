package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.ConstraintCheck
import ai.helper2.guide.DateWindow
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.Recommendation
import ai.helper2.search.Asker
import ai.helper2.search.IntentClass
import ai.helper2.search.Notice
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Instant

/**
 * What the user asked: the text, the answer to a clarification and the parts of the reading they removed; and who
 * asks, for the daily web searches (one per question, however many searches it runs).
 */
internal class Question(val text: String, val path: AudiencePath?, val clarification: String?, val drops: Drops, val asker: Asker?) {
    /** The text the search and the no-model backup see: the question plus the user's answer to our question. */
    val fullText: String get() = listOfNotNull(text, clarification?.takeIf { it.isNotBlank() }).joinToString(" ")

    /** Set once the question is read; the metric tag of the intent. */
    var intentTag: String = "unknown"

    /** When the earliest web results this question's searches used stop being fresh. */
    var webFreshUntil: Instant? = null
        private set

    /** The comparison searches its subjects at the same time, so two searches can report at once. */
    @Synchronized
    fun webResultsFreshUntil(until: Instant) {
        webFreshUntil = webFreshUntil?.let { minOf(it, until) } ?: until
    }

    /** Milliseconds per step, in the order the steps ran, for the question's log line. */
    val timings = LinkedHashMap<String, Long>()

    /** What the steps found (`kept=2 keptRanks=[1, 4]`), for the question's log line; never user text. */
    val counts = mutableListOf<String>()
}

/** Set while answering, for the question metric: the status alone cannot tell a spent budget from an outage. */
internal class Asked(var budgetExhausted: Boolean = false)

/** How the question was read: the plan (or the backup rules alone), with the user's removals applied. */
internal class Reading(
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

/** What every step of a question shares: its span and timing, and its searches. */
internal class GuideSteps(private val search: SearchResources, private val observations: ObservationRegistry) {

    /**
     * One step of a question: a child span and timer `helper2.guide.step{step,intent}`, and its time in the
     * question's log line. The intent is read when the step ends, so the plan step carries the intent it found.
     */
    fun <T> step(q: Question, name: String, work: () -> T): T {
        val observation = Observation.createNotStarted("helper2.guide.step", observations).lowCardinalityKeyValue("step", name).start()
        val started = System.nanoTime()
        try {
            return observation.openScope().use { work() }
        } catch (e: Throwable) {
            observation.error(e)
            throw e
        } finally {
            q.timings.merge(name, (System.nanoTime() - started) / 1_000_000, Long::plus)
            observation.lowCardinalityKeyValue("intent", q.intentTag)
            observation.stop()
        }
    }

    /**
     * Every search of a question: under its asker's web-search quota, with the freshness its intent needs (dates,
     * newest and learning questions are time-bound), and noting how long the web results it used stay fresh.
     */
    fun find(q: Question, r: Reading, request: SearchRequest): SearchOutcome {
        val intentClass = when (r.intent) {
            GuideIntent.NEWEST, GuideIntent.WHEN, GuideIntent.LEARN -> IntentClass.TIME_BOUND
            else -> IntentClass.GENERAL
        }
        val outcome = search.search(request.copy(asker = q.asker, intentClass = intentClass))
        outcome.webFreshUntil?.let(q::webResultsFreshUntil)
        return outcome
    }
}

/** Web pages that can announce a new resource. */
internal val WEB_ANNOUNCEMENTS = setOf(ResourceType.NEWS, ResourceType.ARTICLE)

/** A subject's own entry is among the first hits for its name, or not found. */
internal const val SUBJECT_SEARCH_SIZE = 5

/** Why a model call failed, for the log: the adapter's category, or the exception's class. */
internal fun failure(e: RuntimeException) = (e as? GuideUnavailable)?.category ?: e.javaClass.simpleName

internal fun GuideAnswer.withNotices(extra: List<Notice>) = if (extra.isEmpty()) this else copy(notices = notices + extra)

/** No trustworthy AI answer: show the top search results, clearly marked as not AI-reasoned. */
internal fun degraded(
    clock: Clock,
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
