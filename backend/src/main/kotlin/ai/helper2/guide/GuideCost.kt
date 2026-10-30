package ai.helper2.guide

import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.llm.DailyBudget
import ai.helper2.shared.AudiencePath
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat

/**
 * Fresh (not cached) guide questions per client per UTC day. The per-minute rate limit alone lets one client
 * spend the global daily budget in about twenty minutes; past this quota the client still gets an answer, from
 * search only. Counted in [counter] (Redis) under a hash of the client, so no address is stored; when the
 * counter fails, counting goes on in memory.
 *
 * In memory, only today's clients are kept, at most [maxClients], the least recently seen leaving first. A client
 * that left keeps its count in [counter]; only the in-memory fallback count is lost.
 */
class ClientQuota @JvmOverloads constructor(
    private val questionsPerDay: Int,
    private val clock: Clock,
    private val counter: CallCounter = CallCounter.NONE,
    private val maxClients: Int = MAX_CLIENTS,
) {
    private var day: LocalDate = today()
    private val budgets = object : LinkedHashMap<String, DailyBudget>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DailyBudget>?) = size > maxClients
    }

    /** Takes one question from [client]'s quota for today; false when it is used up. */
    fun tryAcquire(client: String): Boolean {
        val name = "guide-client:" + sha256Hex(client).take(16)
        val budget = synchronized(this) {
            val now = today()
            if (now != day) {
                day = now
                budgets.clear()
            }
            budgets.getOrPut(name) { DailyBudget(questionsPerDay, clock, name, counter) }
        }
        // Outside the lock: the counter is a Redis call.
        return budget.tryAcquire()
    }

    /** Clients held in memory right now. */
    internal val clientsInMemory: Int get() = synchronized(this) { budgets.size }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    companion object {
        /** About 2 MB of budgets; far more distinct clients than a day of the guide's 600 model calls can serve. */
        const val MAX_CLIENTS = 10_000
    }
}

internal fun sha256Hex(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

/**
 * Durable copy of the guide answer cache, so a restart or a deploy does not throw away answers that
 * cost three model calls each. An unavailable store or an unreadable entry is a miss, never an error.
 */
interface GuideAnswerStore {
    fun get(key: String): GuideAnswer?
    fun put(key: String, answer: GuideAnswer, ttl: Duration)

    companion object {
        /** No durable copy (no Redis): the cache works in memory only. */
        @JvmField
        val NONE: GuideAnswerStore = object : GuideAnswerStore {
            override fun get(key: String): GuideAnswer? = null
            override fun put(key: String, answer: GuideAnswer, ttl: Duration) {}
        }
    }
}

/**
 * Recent AI-reasoned answers by normalised request, so a repeated question costs no model call and
 * answers at once (judges and visitors mostly try the same example query). The answer keeps its
 * original generatedAt, so the page stays honest about its age. Degraded answers are never cached:
 * they would hide a provider recovery.
 *
 * In memory first; on a memory miss it reads through to [store] (Redis), so entries survive restarts.
 * An answer read from the store expires [ttl] after its generatedAt, the same moment as in the store.
 */
class GuideAnswerCache @JvmOverloads constructor(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val clock: Clock,
    private val store: GuideAnswerStore? = null,
) {
    private data class Entry(val answer: GuideAnswer, val expiresAt: Instant)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun get(key: String): GuideAnswer? {
        val now = clock.instant()
        synchronized(this) {
            val e = entries[key]
            if (e != null) {
                if (now.isBefore(e.expiresAt)) return e.answer
                entries.remove(key)
            }
        }
        // Outside the lock: a slow store must not queue every caller behind it.
        val durable = store?.let { runCatching { it.get(key) }.getOrNull() } ?: return null
        val expiresAt = durable.generatedAt.plus(ttlOf(durable))
        if (!now.isBefore(expiresAt) || !isCacheable(durable)) return null
        synchronized(this) { entries[key] = Entry(durable, expiresAt) }
        return durable
    }

    fun put(key: String, answer: GuideAnswer) {
        val ttl = ttlOf(answer)
        if (!isCacheable(answer) || !ttl.isPositive) return
        synchronized(this) { entries[key] = Entry(answer, clock.instant().plus(ttl)) }
        store?.let { runCatching { it.put(key, answer, ttl) } }
    }

    /**
     * The cache is read before the plan, so its key cannot hold the resolved window of "last month". An answer with a
     * window lives at most [WINDOWED_TTL] instead, which bounds how far its window drifts from today's. An answer built
     * on web results lives no longer than they are fresh ([GuideAnswer.webFreshUntil]); one built on stale ones is not
     * cached at all.
     */
    private fun ttlOf(answer: GuideAnswer): Duration {
        val life = if (answer.window != null) minOf(ttl, WINDOWED_TTL) else ttl
        return answer.webFreshUntil?.let { minOf(life, Duration.between(answer.generatedAt, it)) } ?: life
    }

    companion object {
        /**
         * Only complete AI answers: at least one AI-reasoned pick, and every AI reason checked. A reason left
         * NOT_CHECKED (the check failed or the budget ran out) would otherwise be served unchecked for a day.
         */
        @JvmStatic
        fun isCacheable(a: GuideAnswer): Boolean {
            if (!a.modelUsed || a.status == GuideStatus.NEEDS_CLARIFICATION) return false
            val picksChecked = a.recommendations.none { it.aiReasoned && it.reasonCheck == ReasonCheck.NOT_CHECKED }
            val comparisonChecked = a.comparison?.rows.orEmpty().flatMap { it.cells }
                .none { it.text != null && it.reasonCheck == ReasonCheck.NOT_CHECKED }
            val explanationChecked = a.explanation?.sentences.orEmpty().none { it.reasonCheck == ReasonCheck.NOT_CHECKED }
            val answered = a.recommendations.any { it.aiReasoned } || a.comparison != null || a.explanation?.sentences?.isNotEmpty() == true
            return answered && picksChecked && comparisonChecked && explanationChecked
        }

        /** Longest life of an answer with a window ("last month"): see [ttlOf]. */
        @JvmField
        val WINDOWED_TTL: Duration = Duration.ofHours(6)

        /** Bumped when the answer's shape changes, so answers cached in the old shape are not served after a deploy. */
        private const val SHAPE = "v2"

        /**
         * Same question, different casing or spacing, same [config] (models, prompts, limits) → same key. A
         * clarification answer and removed parts of the reading ([drops]) are part of the question.
         */
        @JvmStatic
        @JvmOverloads
        fun key(request: String, path: AudiencePath?, config: String, clarification: String? = null, drops: Drops = Drops.NONE): String =
            listOf(
                SHAPE, normalized(request), path?.name ?: "-", config,
                clarification?.let { "c:" + normalized(it) } ?: "-", drops.key.ifEmpty { "-" },
            ).joinToString("|")

        private fun normalized(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
