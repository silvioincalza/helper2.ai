package ai.helper2.guide

import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.llm.CallCounter.GiveBack
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.ResultMerger
import ai.helper2.search.SearchOutcome
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientQuotaTest {

  private val clock = MovableClock(Instant.parse("2026-10-27T22:00:00Z"))
  private val keys = QuotaKeys("test-secret", clock)

  /** A durable counter in memory, standing in for Redis: a refused question adds nothing. */
  private val counts = mutableMapOf<String, Long>()
  private val counter = CallCounter { name, day, limit ->
    counts.incrementUpTo("$name:$day", limit)
  }

  @Test
  fun `a new UTC day starts every client afresh and drops yesterday's clients`() {
    val quota = ClientQuota(1, clock, keys)
    assertTrue(quota.tryAcquire("10.0.0.1"))
    assertTrue(quota.tryAcquire("10.0.0.2"))
    assertFalse(quota.tryAcquire("10.0.0.1"))
    assertEquals(2, quota.clientsInMemory)

    clock.now = clock.now.plus(Duration.ofHours(3))

    assertTrue(quota.tryAcquire("10.0.0.1"))
    assertEquals(1, quota.clientsInMemory)
  }

  @Test
  fun `at most maxClients are held in memory, the least recently seen leaving first`() {
    val quota = ClientQuota(5, clock, keys, maxClients = 2)
    quota.tryAcquire("a")
    quota.tryAcquire("b")
    quota.tryAcquire("a")
    quota.tryAcquire("c")

    assertEquals(2, quota.clientsInMemory)
  }

  /** A budget per client is never listed: its name comes from the client's address (ADR 0045). */
  @Test
  fun `no client budget is declared for the back office`() {
    val declared = mutableListOf<String>()
    val listing =
      object : CallCounter {
        override fun increment(name: String, day: java.time.LocalDate, limit: Int) =
          counter.increment(name, day, limit)

        override fun declare(name: String, dailyCalls: Int) {
          declared += name
        }
      }
    val quota = ClientQuota(1, clock, keys, listing)
    assertTrue(quota.tryAcquire("10.0.0.1"))
    assertFalse(quota.tryAcquire("10.0.0.1"))

    assertTrue(declared.isEmpty())
  }

  @Test
  fun `a client that left memory keeps its count in the durable counter`() {
    val quota = ClientQuota(1, clock, keys, counter, maxClients = 1)
    assertTrue(quota.tryAcquire("a"))
    assertTrue(quota.tryAcquire("b"))

    assertFalse(quota.tryAcquire("a"))
  }

  @Test
  fun `the durable counter names the client by its pseudonym of the day, never by its address`() {
    val quota = ClientQuota(1, clock, keys, counter)
    quota.tryAcquire("203.0.113.7")
    quota.tryAcquire("203.0.113.8")

    assertEquals(2, counts.size)
    assertTrue(counts.keys.none { it.contains("203.0.113") })
    assertEquals(
      setOf(
        "guide-client:${keys.client("203.0.113.7")}:2026-10-27",
        "guide-client:${keys.client("203.0.113.8")}:2026-10-27",
      ),
      counts.keys,
    )
  }

  /** The durable counter with a read, as Redis has. */
  private val readable =
    object : CallCounter {
      override fun increment(name: String, day: java.time.LocalDate, limit: Int) =
        counter.increment(name, day, limit)

      override fun current(name: String, day: java.time.LocalDate): Long =
        counts["$name:$day"] ?: 0L
    }

  @Test
  fun `left reads the count without taking a question`() {
    val quota = ClientQuota(3, clock, keys, readable)
    assertEquals(3, quota.left("a"))

    quota.tryAcquire("a")

    assertEquals(2, quota.left("a"))
    assertEquals(2, quota.left("a"), "reading does not count")
    assertEquals(3, quota.left("b"))
  }

  @Test
  fun `a refused question takes nothing, so left stays at zero`() {
    val quota = ClientQuota(1, clock, keys, readable)
    repeat(3) { quota.tryAcquire("a") }

    assertEquals(0, quota.left("a"))
  }

  @Test
  fun `another replica's count is seen through the durable counter`() {
    ClientQuota(2, clock, keys, readable).tryAcquire("a")

    assertEquals(1, ClientQuota(2, clock, keys, readable).left("a"))
  }

  @Test
  fun `without a durable read, left follows this replica's count and a new day resets it`() {
    val quota = ClientQuota(2, clock, keys)
    quota.tryAcquire("a")
    assertEquals(1, quota.left("a"))

    clock.now = clock.now.plus(Duration.ofHours(3))

    assertEquals(2, quota.left("a"))
  }

  // An answer with no pick gives its question back (ADR 0017).

  private val result =
    ResultMerger()
      .merge(listOf(hit("github", "https://github.com/acme/whisper")), contexts("github"))
      .single()

  private val noPick =
    GuideAnswer(
      status = GuideStatus.OK,
      searchQuery = "speech recognition",
      recommendations = emptyList(),
      prompt = null,
      modelUsed = true,
      candidatesConsidered = 3,
      rejected = 0,
      search = SearchOutcome.empty(clock.now),
      generatedAt = clock.now,
    )

  @Test
  fun `an answer with an AI pick, a comparison or an explanation spends a question`() {
    val pick = Recommendation(result, "Self-hostable.", "Speech recognition", aiReasoned = true)
    val sentence = ExplainedSentence("Whisper transcribes speech.", "Speech recognition", result)

    assertTrue(ClientQuota.spendsAQuestion(noPick.copy(recommendations = listOf(pick))))
    assertTrue(
      ClientQuota.spendsAQuestion(noPick.copy(comparison = Comparison(emptyList(), emptyList())))
    )
    assertTrue(
      ClientQuota.spendsAQuestion(noPick.copy(explanation = Explanation(listOf(sentence))))
    )
  }

  @Test
  fun `an answer with nothing from the AI is free`() {
    val searchResult = Recommendation(result, "", "", aiReasoned = false)

    assertFalse(ClientQuota.spendsAQuestion(noPick), "nothing clearly fits")
    assertFalse(
      ClientQuota.spendsAQuestion(
        noPick.copy(status = GuideStatus.LLM_UNAVAILABLE, recommendations = listOf(searchResult))
      ),
      "the model is down: top search results only",
    )
    assertFalse(
      ClientQuota.spendsAQuestion(noPick.copy(status = GuideStatus.NEEDS_CLARIFICATION)),
      "a question back",
    )
    assertFalse(
      ClientQuota.spendsAQuestion(noPick.copy(explanation = Explanation(emptyList()))),
      "an explanation with no sentence left",
    )
  }

  @Test
  fun `a question given back is left again`() {
    val quota = ClientQuota(3, clock, keys)
    val reservation = quota.reserve("a")!!

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(reservation))

    assertEquals(3, quota.left("a"))
  }

  @Test
  fun `a reservation is given back once, whoever calls it twice`() {
    val quota = ClientQuota(3, clock, keys)
    quota.reserve("a")
    val reservation = quota.reserve("a")!!

    quota.giveBack(reservation)

    assertEquals(GiveBack.ALREADY_GIVEN_BACK, quota.giveBack(reservation))
    assertEquals(2, quota.left("a"))
  }

  @Test
  fun `a failure after a no-pick give-back gives nothing back again, also in the shared count`() {
    val shared = SharedCounter()
    val quota = ClientQuota(3, clock, keys, shared)
    quota.reserve("a")
    val reservation = quota.reserve("a")!!
    quota.giveBack(reservation)

    assertEquals(GiveBack.ALREADY_GIVEN_BACK, quota.giveBack(reservation))
    assertEquals(
      GiveBack.ALREADY_GIVEN_BACK,
      reservation.budget.giveBack(reservation.day, reservation.id, 3),
      "the same id again in the durable counter",
    )
    assertEquals(2, quota.left("a"))
  }

  @Test
  fun `at most the free answers of a day are given back, then a question counts`() {
    val quota = ClientQuota(3, clock, keys, freeAnswersPerDay = 2)
    repeat(2) { quota.giveBack(quota.reserve("a")!!) }

    assertEquals(GiveBack.CAP_REACHED, quota.giveBack(quota.reserve("a")!!))
    assertEquals(2, quota.left("a"))
  }

  @Test
  fun `free answers are capped per client`() {
    val quota = ClientQuota(3, clock, keys, freeAnswersPerDay = 1)
    quota.giveBack(quota.reserve("a")!!)

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(quota.reserve("b")!!))
  }

  @Test
  fun `no free answer is given back with a cap of zero`() {
    val quota = ClientQuota(3, clock, keys, freeAnswersPerDay = 0)

    assertEquals(GiveBack.CAP_REACHED, quota.giveBack(quota.reserve("a")!!))
    assertEquals(2, quota.left("a"))
  }

  @Test
  fun `a used-up quota reserves nothing`() {
    val quota = ClientQuota(1, clock, keys)
    quota.reserve("a")

    assertNull(quota.reserve("a"))
  }

  /**
   * The review finding on #203: a refused question must not swallow a give-back. Limit 1: A takes
   * the last question, B is refused, A is given back, and the question is left again.
   */
  @Test
  fun `a refused question does not swallow a give-back in the shared count`() {
    val shared = SharedCounter()
    val quota = ClientQuota(1, clock, keys, shared)
    val a = quota.reserve("a")!!
    assertNull(quota.reserve("a"), "B is refused")

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(a))

    assertEquals(1, quota.left("a"))
    assertTrue(quota.tryAcquire("a"), "a fresh question")
  }

  @Test
  fun `a refused question does not swallow a give-back in memory`() {
    val quota = ClientQuota(1, clock, keys)
    val a = quota.reserve("a")!!
    assertNull(quota.reserve("a"), "B is refused")

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(a))

    assertEquals(1, quota.left("a"))
    assertTrue(quota.tryAcquire("a"), "a fresh question")
  }

  @Test
  fun `left follows the durable count after a give-back on another replica`() {
    val shared = SharedCounter()
    val here = ClientQuota(3, clock, keys, shared)
    val there = ClientQuota(3, clock, keys, shared)
    here.reserve("a")
    val elsewhere = there.reserve("a")!!
    here.reserve("a")

    there.giveBack(elsewhere)

    assertEquals(1, here.left("a"), "the shared count, not this replica's higher one")
  }

  /** The durable counter with a read and the give-back rule of RedisCallCounter's script. */
  private class SharedCounter : CallCounter {
    val counts = mutableMapOf<String, Long>()
    val givenBack = mutableMapOf<String, MutableSet<String>>()

    override fun increment(name: String, day: java.time.LocalDate, limit: Int) =
      counts.incrementUpTo("$name:$day", limit)

    override fun current(name: String, day: java.time.LocalDate) = counts["$name:$day"] ?: 0L

    override fun giveBack(
      name: String,
      day: java.time.LocalDate,
      once: String,
      dailyCap: Int,
    ): GiveBack {
      val ids = givenBack.getOrPut("$name:$day") { mutableSetOf() }
      return when {
        once in ids -> GiveBack.ALREADY_GIVEN_BACK
        ids.size >= dailyCap -> GiveBack.CAP_REACHED
        (counts["$name:$day"] ?: 0L) <= 0L -> GiveBack.NOTHING_COUNTED
        else -> {
          counts.merge("$name:$day", -1L, Long::plus)
          ids += once
          GiveBack.GIVEN_BACK
        }
      }
    }
  }
}

/**
 * The rule of RedisCallCounter's script, in memory: at [limit], answers count + 1, adds nothing.
 */
internal fun MutableMap<String, Long>.incrementUpTo(key: String, limit: Int): Long {
  val used = this[key] ?: 0L
  if (used >= limit) return used + 1
  return (used + 1).also { this[key] = it }
}
