package ai.helper2.search

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.testing.MovableClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebSearchQuotaTest {
  private class MapCounter : QuotaCounter {
    val counts = HashMap<Counter, Long>()
    var down = false
    var refusesToGiveBack = false
    val calls = mutableListOf<Pair<Counter?, Counter?>>()

    override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
      if (down) throw IllegalStateException("down")
      calls += own to global
      val used = own?.let { counts[it] ?: 0 } ?: 0
      if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
      if (global != null && (counts[global] ?: 0) >= globalLimit)
        return Result(Verdict.GLOBAL_LIMIT_REACHED, used)
      global?.let { counts[it] = (counts[it] ?: 0) + 1 }
      own?.let { counts[it] = used + 1 }
      return Result(Verdict.TAKEN, own?.let { counts[it] } ?: 0)
    }

    override fun add(counter: Counter, amount: Long) {
      counts[counter] = (counts[counter] ?: 0) + amount
    }

    override fun giveBack(counter: Counter) {
      if (refusesToGiveBack) throw IllegalStateException("down")
      counts[counter] = maxOf(0, (counts[counter] ?: 0) - 1)
    }

    override fun used(counter: Counter): Long =
      if (down) throw IllegalStateException("down") else counts[counter] ?: 0
  }

  private val counter = MapCounter()
  private val keys = QuotaKeys("test-secret", MovableClock(NOW))

  private fun quota(globalDailyCredits: Long? = null) =
    WebSearchQuota(counter, keys, DailyLimits(search = 3, guide = 3)) { globalDailyCredits }

  @Test
  fun `a visitor gets three web searches a day per feature, then the daily limit`() {
    val q = quota()
    repeat(3) { i ->
      val d = assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.SEARCH, "203.0.113.7")))
      assertEquals(WebSearchesLeft(Feature.SEARCH, 3, 2 - i), d.left)
    }
    val over = assertIs<QuotaDecision.OverLimit>(q.take(Asker(Feature.SEARCH, "203.0.113.7")))
    assertEquals(0, over.left!!.remaining)
    assertIs<QuotaDecision.Allowed>(
      q.take(Asker(Feature.GUIDE, "203.0.113.7")),
      "the guide counts on its own",
    )
    assertIs<QuotaDecision.Allowed>(q.take(Asker(Feature.SEARCH, "203.0.113.8")))
  }

  @Test
  fun `one user action takes one web search however often it calls the web`() {
    val q = quota()
    val question = Asker(Feature.GUIDE, "203.0.113.7")
    repeat(3) { assertIs<QuotaDecision.Allowed>(q.take(question)) }
    assertEquals(2, q.left(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining)
    assertEquals(
      listOf(true, false, false),
      counter.calls.map { it.first != null },
      "the own counter only at the first call",
    )
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
  fun `every visitor is counted under the hash of the client address, per feature`() {
    quota().take(Asker(Feature.GUIDE, "c"))
    assertEquals(Counter(keys.guest("c"), "guide"), counter.calls.single().first)
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
  fun `an action the web gave nothing gets its web search back and takes it again at its next call`() {
    val q = quota()
    val question = Asker(Feature.GUIDE, "c")
    q.take(question)

    q.giveBack(question)

    assertEquals(3, q.left(question)!!.remaining)
    assertEquals(1L, counter.counts[Counter(keys.global(), "credits")], "the credit stays counted")
    val again = assertIs<QuotaDecision.Allowed>(q.take(question))
    assertEquals(2, again.left!!.remaining)
  }

  @Test
  fun `a web search is given back once`() {
    val q = quota()
    q.take(Asker(Feature.SEARCH, "c"))
    val action = Asker(Feature.SEARCH, "c")
    q.take(action)

    repeat(2) { q.giveBack(action) }

    assertEquals(2, q.left(action)!!.remaining)
  }

  @Test
  fun `an action the web answered keeps its web search`() {
    val q = quota()
    val question = Asker(Feature.GUIDE, "c")
    q.take(question)
    q.answered(question)

    q.giveBack(question)

    assertEquals(2, q.left(question)!!.remaining)
  }

  @Test
  fun `an action keeps its web search while another of its calls can still answer`() {
    val q = quota()
    val question = Asker(Feature.GUIDE, "c")
    repeat(2) { q.take(question) }

    q.giveBack(question)
    q.answered(question)

    assertEquals(2, q.left(question)!!.remaining)
    assertEquals(1L, counter.counts[Counter(keys.guest("c"), "guide")])
  }

  @Test
  fun `an action none of its calls answered gets its web search back once`() {
    val q = quota()
    q.take(Asker(Feature.GUIDE, "c"))
    val question = Asker(Feature.GUIDE, "c")
    repeat(2) { q.take(question) }

    repeat(2) { q.giveBack(question) }

    assertEquals(1L, counter.counts[Counter(keys.guest("c"), "guide")])
  }

  @Test
  fun `a web search the store could not give back stays taken, and is given back at the next call`() {
    val q = quota()
    val question = Asker(Feature.GUIDE, "c")
    q.take(question)
    counter.refusesToGiveBack = true
    q.giveBack(question)
    counter.refusesToGiveBack = false

    val again = assertIs<QuotaDecision.Allowed>(q.take(question))

    assertEquals(2, again.left!!.remaining)
    assertEquals(1L, counter.counts[Counter(keys.guest("c"), "guide")], "taken once, not twice")
    q.giveBack(question)
    assertEquals(3, q.left(question)!!.remaining)
  }

  @Test
  fun `an action over the limit has no web search to give back`() {
    val q = quota()
    repeat(3) { q.take(Asker(Feature.SEARCH, "c")) }
    val action = Asker(Feature.SEARCH, "c")
    q.take(action)

    q.giveBack(action)

    assertEquals(3L, counter.counts[Counter(keys.guest("c"), "search")])
  }

  @Test
  fun `what is left is read without counting`() {
    val q = quota()
    q.take(Asker(Feature.SEARCH, "c"))
    assertEquals(
      WebSearchesLeft(Feature.SEARCH, 3, 2),
      q.left(Asker(Feature.SEARCH, "c")),
    )
    assertEquals(
      WebSearchesLeft(Feature.SEARCH, 3, 2),
      q.left(Asker(Feature.SEARCH, "c")),
    )
  }
}
