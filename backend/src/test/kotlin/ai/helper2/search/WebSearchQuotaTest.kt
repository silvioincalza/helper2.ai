package ai.helper2.search

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.testing.MovableClock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebSearchQuotaTest {

    /** In memory, with the same rules as the Redis script. */
    private class MapCounter : QuotaCounter {
        val counts = HashMap<Counter, Long>()
        var down = false
        val calls = mutableListOf<Pair<Counter?, Counter?>>()

        override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
            if (down) throw IllegalStateException("down")
            calls += own to global
            val used = own?.let { counts[it] ?: 0 } ?: 0
            if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
            if (global != null && (counts[global] ?: 0) >= globalLimit) return Result(Verdict.GLOBAL_LIMIT_REACHED, used)
            global?.let { counts[it] = (counts[it] ?: 0) + 1 }
            own?.let { counts[it] = used + 1 }
            return Result(Verdict.TAKEN, own?.let { counts[it] } ?: 0)
        }

        override fun giveBack(counter: Counter) {
            counts[counter] = maxOf(0, (counts[counter] ?: 0) - 1)
        }

        override fun used(counter: Counter): Long = if (down) throw IllegalStateException("down") else counts[counter] ?: 0

        override fun delete(keys: Collection<String>) {}
    }

    private val counter = MapCounter()
    private val keys = QuotaKeys("test-secret", MovableClock(NOW))
    private val user = UUID.fromString("00000000-0000-0000-0000-000000000042")

    private fun quota(registeredTier: Boolean = false, globalDailyCredits: Long? = null) =
        WebSearchQuota(counter, keys, DailyLimits(guestSearch = 3, guestGuide = 3, registeredSearch = 10, registeredGuide = 10),
            registeredTier, globalDailyCredits)

    @Test
    fun `a guest gets three web searches a day per feature, then the daily limit`() {
        val q = quota()
        repeat(3) { i ->
            val d = assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.SEARCH, "203.0.113.7")))
            assertEquals(WebSearchesLeft(Feature.SEARCH, QuotaTier.GUEST, 3, 2 - i), d.left)
        }
        val over = assertIs<QuotaDecision.OverLimit>(q.take(Asker(Feature.SEARCH, "203.0.113.7")))
        assertEquals(0, over.left!!.remaining)
        assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.GUIDE, "203.0.113.7")), "the guide counts on its own")
        assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.SEARCH, "203.0.113.8")))
    }

    @Test
    fun `one user action takes one web search however often it calls the web`() {
        val q = quota()
        val question = Asker(Feature.GUIDE, "203.0.113.7")
        repeat(3) { assertIs<QuotaDecision.Allowed>(q.take(question)) }
        assertEquals(2, q.left(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining)
        assertEquals(listOf(true, false, false), counter.calls.map { it.first != null }, "the own counter only at the first call")
    }

    @Test
    fun `an action over the limit stays over it without asking the store again`() {
        val q = quota()
        repeat(3) { q.take(Asker(Feature.SEARCH, "c")) }
        val action = Asker(Feature.SEARCH, "c")
        assertIs<QuotaDecision.OverLimit>(q.take(action))
        assertIs<QuotaDecision.OverLimit>(q.take(action))
        assertEquals(4, counter.calls.size)
    }

    @Test
    fun `a signed-in user stays a guest while the registered tier is off`() {
        val d = assertIs<QuotaDecision.Allowed>(quota().take(Asker(Feature.SEARCH, "c", user)))
        assertEquals(QuotaTier.GUEST, d.left!!.tier)
        assertEquals(keys.guest("c"), counter.calls.single().first!!.key)
    }

    @Test
    fun `with the registered tier on, a signed-in user gets ten a day keyed by the user id`() {
        val q = quota(registeredTier = true)
        val d = assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.GUIDE, "c", user)))
        assertEquals(WebSearchesLeft(Feature.GUIDE, QuotaTier.REGISTERED, 10, 9), d.left)
        assertEquals(Counter(keys.user(user), "guide"), counter.calls.single().first)
        assertEquals(9, q.left(Asker(Feature.GUIDE, "another address", user))!!.remaining, "the same user from anywhere")
    }

    @Test
    fun `without a global cap every call still counts on the global counter and none is refused for it`() {
        val q = quota()
        val question = Asker(Feature.GUIDE, "a")
        repeat(2) { assertIs<QuotaDecision.Allowed>(q.take(question)) }
        assertIs<QuotaDecision.Allowed>(q.take(null))

        assertEquals(3L, counter.counts[Counter(keys.global(), "credits")])
        assertEquals(WebCredits(3, null), q.creditsToday())
    }

    @Test
    fun `the global cap counts every call, also the later calls of one action and internal ones`() {
        val q = quota(globalDailyCredits = 3)
        val question = Asker(Feature.GUIDE, "a")
        assertIs<QuotaDecision.Allowed>(q.take(question))
        assertIs<QuotaDecision.Allowed>(q.take(question))
        assertIs<QuotaDecision.Allowed>(q.take(null))
        val capped = assertIs<QuotaDecision.OverLimit>(q.take(Asker(Feature.SEARCH, "b")))
        assertEquals(3, capped.left!!.remaining, "the visitor's own searches are untouched")
        assertIs<QuotaDecision.OverLimit>(q.take(question))
        assertEquals(3L, counter.counts[Counter(keys.global(), "credits")])
    }

    @Test
    fun `the credits of the day come with the global cap`() {
        val q = quota(globalDailyCredits = 50)
        q.take(Asker(Feature.SEARCH, "c"))

        assertEquals(WebCredits(1, 50), q.creditsToday())
    }

    @Test
    fun `the credits of the day are unknown while the store cannot answer`() {
        counter.down = true

        assertNull(quota().creditsToday())
    }

    @Test
    fun `when the store cannot answer the web search is refused, and asked again next time`() {
        val q = quota()
        counter.down = true
        val action = Asker(Feature.SEARCH, "c")
        assertEquals(QuotaDecision.Unavailable, q.take(action))
        assertNull(q.left(action))
        counter.down = false
        assertIs<QuotaDecision.Allowed>(q.take(action))
    }

    @Test
    fun `what is left is read without counting`() {
        val q = quota()
        q.take(Asker(Feature.SEARCH, "c"))
        assertEquals(WebSearchesLeft(Feature.SEARCH, QuotaTier.GUEST, 3, 2), q.left(Asker(Feature.SEARCH, "c")))
        assertEquals(WebSearchesLeft(Feature.SEARCH, QuotaTier.GUEST, 3, 2), q.left(Asker(Feature.SEARCH, "c")))
    }
}
