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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientQuotaTest {

  private val clock = MovableClock(Instant.parse("2026-10-27T22:00:00Z"))
  private val keys = QuotaKeys("test-secret", clock)

  private val counts = mutableMapOf<String, Long>()
  private val counter = CallCounter { name, day, limit ->
    counts.incrementUpTo("$name:$day", limit)
  }

  @Test
  fun `a new UTC day starts every client afresh and drops yesterday's clients`() {
    val quota = ClientQuota(1, clock, keys)
    assertNotNull(quota.reserve("10.0.0.1"))
    assertNotNull(quota.reserve("10.0.0.2"))
    assertNull(quota.reserve("10.0.0.1"))
    assertEquals(2, quota.clientsInMemory)

    clock.now = clock.now.plus(Duration.ofHours(3))

    assertNotNull(quota.reserve("10.0.0.1"))
    assertEquals(1, quota.clientsInMemory)
  }

  @Test
  fun `at most maxClients are held in memory, the least recently seen leaving first`() {
    val quota = ClientQuota(5, clock, keys, maxClients = 2)
    quota.reserve("a")
    quota.reserve("b")
    quota.reserve("a")
    quota.reserve("c")

    assertEquals(2, quota.clientsInMemory)
  }

  @Test
  fun `no client budget is declared for the back office`() {
    val declared = mutableListOf<String>()
    val listing =
      object : CallCounter {
        override fun increment(name: String, day: java.time.LocalDate, limit: Int) =
          counter.increment(name, day, limit)

        override fun declare(name: String, dailyCalls: java.util.function.IntSupplier) {
          declared += name
        }
      }
    val quota = ClientQuota(1, clock, keys, listing)
    assertNotNull(quota.reserve("10.0.0.1"))
    assertNull(quota.reserve("10.0.0.1"))

    assertTrue(declared.isEmpty())
  }

  @Test
  fun `a client that left memory keeps its count in the durable counter`() {
    val quota = ClientQuota(1, clock, keys, counter, maxClients = 1)
    assertNotNull(quota.reserve("a"))
    assertNotNull(quota.reserve("b"))

    assertNull(quota.reserve("a"))
  }

  @Test
  fun `the durable counter names the client by its pseudonym of the day, never by its address`() {
    val quota = ClientQuota(1, clock, keys, counter)
    quota.reserve("203.0.113.7")
    quota.reserve("203.0.113.8")

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

    quota.reserve("a")

    assertEquals(2, quota.left("a"))
    assertEquals(2, quota.left("a"), "reading does not count")
    assertEquals(3, quota.left("b"))
  }

  @Test
  fun `a refused question takes nothing, so left stays at zero`() {
    val quota = ClientQuota(1, clock, keys, readable)
    repeat(3) { quota.reserve("a") }

    assertEquals(0, quota.left("a"))
  }

  @Test
  fun `another replica's count is seen through the durable counter`() {
    ClientQuota(2, clock, keys, readable).reserve("a")

    assertEquals(1, ClientQuota(2, clock, keys, readable).left("a"))
  }

  @Test
  fun `without a durable read, left follows this replica's count and a new day resets it`() {
    val quota = ClientQuota(2, clock, keys)
    quota.reserve("a")
    assertEquals(1, quota.left("a"))

    clock.now = clock.now.plus(Duration.ofHours(3))

    assertEquals(2, quota.left("a"))
  }

  @Test
  fun `rotating addresses inside one network share the network's questions`() {
    val quota = ClientQuota(30, clock, keys, readable, networkQuestionsPerDay = 2)

    assertNotNull(quota.reserve("20010db800000001::/64"))
    assertNotNull(quota.reserve("20010db800000002::/64"))
    assertNull(quota.reserve("20010db8000000ff::/64"), "the same /56")
    assertEquals(0, quota.left("20010db800000004::/64"))
    assertNotNull(quota.reserve("20010db800000101::/64"), "another /56 of the same /48")
  }

  @Test
  fun `addresses of one IPv4 slash 24 share the network's questions, and a free answer frees both`() {
    val quota = ClientQuota(30, clock, keys, networkQuestionsPerDay = 1)
    val first = quota.reserve("198.51.100.7")!!

    assertNull(quota.reserve("198.51.100.8"))
    quota.giveBack(first)
    assertNotNull(quota.reserve("198.51.100.8"))
  }

  @Test
  fun `refusals by a full network spend none of the client's free answers`() {
    val quota = ClientQuota(30, clock, keys, freeAnswersPerDay = 1, networkQuestionsPerDay = 1)
    val first = quota.reserve("198.51.100.7")!!

    repeat(3) { assertNull(quota.reserve("198.51.100.7")) }

    assertTrue(quota.refusedForTheNetwork("198.51.100.7"))
    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(first), "the one free answer is still there")
  }

  @Test
  fun `a client past its own quota does not use up its network's free answers`() {
    val quota = ClientQuota(1, clock, keys, freeAnswersPerDay = 1, networkQuestionsPerDay = 2)
    assertNotNull(quota.reserve("198.51.100.7"))

    repeat(10) { assertNull(quota.reserve("198.51.100.7")) }

    val other = quota.reserve("198.51.100.8")!!
    assertEquals(
      GiveBack.GIVEN_BACK,
      quota.giveBack(other),
      "the network's free answers are untouched",
    )
    assertEquals(1, quota.left("198.51.100.8"))
  }

  @Test
  fun `a client past its own quota does not use up its network's free answers in the shared count`() {
    val shared = SharedCounter()
    val quota =
      ClientQuota(1, clock, keys, shared, freeAnswersPerDay = 1, networkQuestionsPerDay = 2)
    assertNotNull(quota.reserve("198.51.100.7"))

    repeat(10) { assertNull(quota.reserve("198.51.100.7")) }

    assertTrue(shared.givenBack.values.all { it.isEmpty() }, "a refusal is no free answer")
    val other = quota.reserve("198.51.100.8")!!
    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(other))
    assertEquals(1, quota.left("198.51.100.8"))
  }

  @Test
  fun `clients rotating inside one network cannot ask without end on free answers`() {
    val quota = ClientQuota(30, clock, keys, freeAnswersPerDay = 5, networkQuestionsPerDay = 300)
    var asked = 0
    var rotating = 0
    while (asked < 1_000) {
      val client = "20010db8000000%02x::/64".format(rotating++ % 256)
      val question = quota.reserve(client) ?: break
      quota.giveBack(question)
      asked++
    }

    assertEquals(350, asked, "300 questions and the free answers of the 10 clients they stand for")
    assertNotNull(quota.reserve("20010db800000101::/64"), "another network still asks")
  }

  @Test
  fun `a network gets the free answers of as many clients as its questions stand for`() {
    assertEquals(50, ClientQuota.freeAnswersOfANetwork(30, 5, 300))
    assertEquals(5, ClientQuota.freeAnswersOfANetwork(30, 5, 30))
    assertEquals(5, ClientQuota.freeAnswersOfANetwork(30, 5, 1), "never fewer than one client's")
    assertEquals(10, ClientQuota.freeAnswersOfANetwork(30, 5, 31), "a started client counts")
    assertEquals(0, ClientQuota.freeAnswersOfANetwork(30, 0, 300))
    assertEquals(5, ClientQuota.freeAnswersOfANetwork(0, 5, 300))
  }

  @Test
  fun `a client past its own quota is not refused for the network`() {
    val quota = ClientQuota(1, clock, keys, networkQuestionsPerDay = 5)
    quota.reserve("198.51.100.7")

    assertNull(quota.reserve("198.51.100.7"))
    assertFalse(quota.refusedForTheNetwork("198.51.100.7"))
  }

  private val result =
    ResultMerger()
      .merge(listOf(hit("github", "https://github.com/acme/whisper")), contexts("github"))
      .single()

  private val noPick =
    GuideAnswer(
      status = GuideStatus.OK,
      searchQuery = "speech recognition",
      recommendations = emptyList(),
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

    val row = ComparisonRow("Licence", listOf(ComparisonCell("s1", "MIT", "MIT licence")))

    assertTrue(ClientQuota.spendsAQuestion(noPick.copy(recommendations = listOf(pick))))
    assertTrue(
      ClientQuota.spendsAQuestion(noPick.copy(comparison = Comparison(emptyList(), listOf(row))))
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
    assertFalse(
      ClientQuota.spendsAQuestion(noPick.copy(comparison = Comparison(emptyList(), emptyList()))),
      "a comparison with no row left",
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

  @Test
  fun `a refused question does not swallow a give-back in the shared count`() {
    val shared = SharedCounter()
    val quota = ClientQuota(1, clock, keys, shared)
    val a = quota.reserve("a")!!
    assertNull(quota.reserve("a"), "B is refused")

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(a))

    assertEquals(1, quota.left("a"))
    assertNotNull(quota.reserve("a"), "a fresh question")
  }

  @Test
  fun `a refused question does not swallow a give-back in memory`() {
    val quota = ClientQuota(1, clock, keys)
    val a = quota.reserve("a")!!
    assertNull(quota.reserve("a"), "B is refused")

    assertEquals(GiveBack.GIVEN_BACK, quota.giveBack(a))

    assertEquals(1, quota.left("a"))
    assertNotNull(quota.reserve("a"), "a fresh question")
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

    override fun undo(name: String, day: java.time.LocalDate): Boolean {
      if ((counts["$name:$day"] ?: 0L) <= 0L) return false
      counts.merge("$name:$day", -1L, Long::plus)
      return true
    }
  }
}

internal fun MutableMap<String, Long>.incrementUpTo(key: String, limit: Int): Long {
  val used = this[key] ?: 0L
  if (used >= limit) return used + 1
  return (used + 1).also { this[key] = it }
}
