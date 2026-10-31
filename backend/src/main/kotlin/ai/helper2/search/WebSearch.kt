package ai.helper2.search

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.shared.ResourceType
import java.time.Duration
import java.util.UUID

/*
 * Web search with credits (Tavily, ADR 0037): store first, then a live call inside the asker's daily quota.
 */

/** What the user is doing: each feature has its own daily web searches. */
enum class Feature { SEARCH, GUIDE }

enum class QuotaTier { GUEST, REGISTERED }

/** Web searches [feature] has left today for one visitor, out of [limit]. */
data class WebSearchesLeft(val feature: Feature, val tier: QuotaTier, val limit: Int, val remaining: Int)

/** Credits taken today by every live call of all replicas, and the global daily cap (null: none). */
data class WebCredits(val usedToday: Long, val dailyCap: Long?)

/**
 * Who asks, for the daily web-search quota: the client address ([ai.helper2.platform.ClientKey]) and, when signed in,
 * the user id. One instance per user action: a guide question that calls the web source several times takes one web
 * search from the quota, at its first live call.
 */
class Asker @JvmOverloads constructor(val feature: Feature, val client: String, val userId: UUID? = null) {
    /** This action's answer from the quota, once it asked; guarded by the instance's lock. */
    internal var decision: QuotaDecision? = null
}

sealed interface QuotaDecision {
    /** The live call may go out. [left] is null for internal callers, who have no quota. */
    data class Allowed(val left: WebSearchesLeft?) : QuotaDecision

    /** The asker's daily web searches, or the global daily credits, are used up. */
    data class OverLimit(val left: WebSearchesLeft?) : QuotaDecision

    /** The quota could not be read: no live call (fail closed). */
    data object Unavailable : QuotaDecision
}

/** Web searches per day and feature for each tier. */
data class DailyLimits(
    val guestSearch: Int = 3,
    val guestGuide: Int = 3,
    val registeredSearch: Int = 10,
    val registeredGuide: Int = 10,
) {
    fun of(tier: QuotaTier, feature: Feature): Int = when (tier) {
        QuotaTier.GUEST -> if (feature == Feature.SEARCH) guestSearch else guestGuide
        QuotaTier.REGISTERED -> if (feature == Feature.SEARCH) registeredSearch else registeredGuide
    }
}

/**
 * How fast web results for a question age: news, courses, hackathons, conferences and events within a day
 * ([TIME_BOUND]); anything else within two ([GENERAL]).
 */
enum class IntentClass {
    TIME_BOUND,
    GENERAL;

    companion object {
        private val TIME_BOUND_TYPES = setOf(ResourceType.NEWS, ResourceType.EVENT, ResourceType.COURSE)

        /** English and Italian words that ask for something time-bound; the guide states its class from its intent. */
        private val TIME_BOUND_WORDS = setOf(
            "news", "course", "courses", "certification", "certifications", "hackathon", "hackathons",
            "conference", "conferences", "event", "events", "meetup", "meetups", "summit", "webinar", "webinars",
            "workshop", "workshops", "notizie", "corso", "corsi", "conferenza", "conferenze", "evento", "eventi",
        )

        /** The class [request] states, or the one its words, filters and order show. */
        @JvmStatic
        fun of(request: SearchRequest, analyzed: AnalyzedQuery): IntentClass {
            request.intentClass?.let { return it }
            val types = request.filters.types
            val timeBound = (types.isNotEmpty() && TIME_BOUND_TYPES.containsAll(types)) ||
                request.filters.publishedAfter != null || request.sort == SortOrder.NEWEST ||
                analyzed.normalized.split(' ').any { it in TIME_BOUND_WORDS }
            return if (timeBound) TIME_BOUND else GENERAL
        }
    }
}

/** How long stored web results are fresh per [IntentClass], and how long they are kept to answer when no live call can. */
data class WebFreshness(
    val timeBound: Duration = Duration.ofHours(24),
    val general: Duration = Duration.ofHours(48),
    val keep: Duration = Duration.ofDays(7),
) {
    fun of(intentClass: IntentClass): Duration = if (intentClass == IntentClass.TIME_BOUND) timeBound else general
}

/**
 * Daily web searches per visitor and feature (ADR 0037). A guest is counted under an HMAC of the client address and the
 * day, a signed-in user (only with [registeredTier] on) under the user id. Every live call also counts on one global
 * daily counter for all of Helper2, the credits of the day; with [globalDailyCredits] set, it is capped. Check and
 * count run in one step in [counter], so no replica passes a limit. When [counter] cannot answer, no live call goes
 * out: credits are not spent unguarded.
 */
class WebSearchQuota @JvmOverloads constructor(
    private val counter: QuotaCounter,
    private val keys: QuotaKeys,
    private val limits: DailyLimits = DailyLimits(),
    private val registeredTier: Boolean = false,
    private val globalDailyCredits: Long? = null,
) {
    fun tierOf(asker: Asker): QuotaTier = if (registeredTier && asker.userId != null) QuotaTier.REGISTERED else QuotaTier.GUEST

    /** One web search for [asker]'s action (null: an internal caller, bound only by the global cap). */
    fun take(asker: Asker?): QuotaDecision {
        val global = global()
        if (asker == null) {
            return ask(null, 0, global) { null }
        }
        synchronized(asker) {
            val decision = when (val earlier = asker.decision) {
                is QuotaDecision.OverLimit -> earlier
                is QuotaDecision.Allowed ->
                    ask(null, 0, global) { earlier.left }.let { if (it is QuotaDecision.Allowed) earlier else it }
                QuotaDecision.Unavailable, null -> {
                    val tier = tierOf(asker)
                    val limit = limits.of(tier, asker.feature)
                    ask(own(asker, tier), limit, global) { used -> WebSearchesLeft(asker.feature, tier, limit, maxOf(0, limit - used.toInt())) }
                }
            }
            asker.decision = decision
            return decision
        }
    }

    /** What [asker]'s feature has left today, without counting; null when the quota cannot be read. */
    fun left(asker: Asker): WebSearchesLeft? {
        synchronized(asker) {
            when (val d = asker.decision) {
                is QuotaDecision.Allowed -> d.left?.let { return it }
                is QuotaDecision.OverLimit -> d.left?.let { return it }
                else -> {}
            }
        }
        val tier = tierOf(asker)
        val limit = limits.of(tier, asker.feature)
        return try {
            WebSearchesLeft(asker.feature, tier, limit, maxOf(0, limit - counter.used(own(asker, tier)).toInt()))
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Gives back the credit of the day [take] counted for a live call that did not go out (the source's circuit or
     * bulkhead refused it). The asker's own web search stays taken. When the counter cannot answer, the credit stays
     * counted: that errs towards the cap.
     */
    fun refund() {
        try {
            counter.giveBack(global())
        } catch (_: RuntimeException) {
        }
    }

    /** The credits of the day for all of Helper2, without counting; null when the counter cannot be read. */
    fun creditsToday(): WebCredits? = try {
        WebCredits(counter.used(global()), globalDailyCredits)
    } catch (_: RuntimeException) {
        null
    }

    private fun global() = Counter(keys.global(), GLOBAL_FIELD)

    /** The quota counters [asker] uses. */
    private fun own(asker: Asker, tier: QuotaTier): Counter {
        val field = asker.feature.name.lowercase()
        return if (tier == QuotaTier.REGISTERED) Counter(keys.user(asker.userId!!), field) else Counter(keys.guest(asker.client), field)
    }

    private fun ask(own: Counter?, limit: Int, global: Counter, left: (Long) -> WebSearchesLeft?): QuotaDecision {
        val result = try {
            counter.take(own, limit, global, globalDailyCredits ?: Long.MAX_VALUE)
        } catch (_: RuntimeException) {
            return QuotaDecision.Unavailable
        }
        return when (result.verdict()) {
            Verdict.TAKEN -> QuotaDecision.Allowed(left(result.used()))
            Verdict.LIMIT_REACHED, Verdict.GLOBAL_LIMIT_REACHED, null -> QuotaDecision.OverLimit(left(result.used()))
        }
    }

    private companion object {
        const val GLOBAL_FIELD = "credits"
    }
}
