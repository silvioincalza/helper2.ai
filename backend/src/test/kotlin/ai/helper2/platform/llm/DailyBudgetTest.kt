package ai.helper2.platform.llm

import ai.helper2.search.NOW
import ai.helper2.testing.MovableClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The global daily ceiling on model calls that protects the demo credits. */
class DailyBudgetTest {

  @Test
  fun `budget allows exactly the daily number of calls`() {
    val budget = DailyBudget(2, Clock.fixed(NOW, ZoneOffset.UTC), "guide")
    assertTrue(budget.tryAcquire())
    assertTrue(budget.tryAcquire())
    assertFalse(budget.tryAcquire())
    assertEquals(2, budget.usedToday())
  }

  @Test
  fun `budget starts again on the next UTC day`() {
    val clock = MovableClock(Instant.parse("2026-10-26T23:59:00Z"))
    val budget = DailyBudget(1, clock, "guide")
    assertTrue(budget.tryAcquire())
    assertFalse(budget.tryAcquire())

    clock.now = Instant.parse("2026-10-27T00:00:01Z")
    assertTrue(budget.tryAcquire())
  }

  /** Stands in for Redis: one shared count per name and day, or down. */
  private class SharedCounter : CallCounter {
    val counts = mutableMapOf<String, Long>()
    var down = false

    val givenBack = mutableMapOf<String, MutableSet<String>>()

    override fun increment(name: String, day: java.time.LocalDate): Long? =
      if (down) null else counts.merge("$name:$day", 1L, Long::plus)

    /** The rule of RedisCallCounter's script, in memory. */
    override fun giveBack(
      name: String,
      day: java.time.LocalDate,
      once: String,
      dailyCap: Int,
    ): CallCounter.GiveBack? {
      if (down) return null
      val ids = givenBack.getOrPut("$name:$day") { mutableSetOf() }
      return when {
        once in ids -> CallCounter.GiveBack.ALREADY_GIVEN_BACK
        ids.size >= dailyCap -> CallCounter.GiveBack.CAP_REACHED
        (counts["$name:$day"] ?: 0L) <= 0L -> CallCounter.GiveBack.NOTHING_COUNTED
        else -> {
          counts.merge("$name:$day", -1L, Long::plus)
          ids += once
          CallCounter.GiveBack.GIVEN_BACK
        }
      }
    }
  }

  @Test
  fun `a restart does not hand out a fresh day when the count is shared`() {
    val counter = SharedCounter()
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    assertTrue(DailyBudget(1, clock, "guide", counter).tryAcquire())

    val afterRestart = DailyBudget(1, clock, "guide", counter)
    assertFalse(afterRestart.tryAcquire())
    assertEquals(1, afterRestart.usedToday())
  }

  @Test
  fun `budgets with different names count separately`() {
    val counter = SharedCounter()
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    assertTrue(DailyBudget(1, clock, "guide", counter).tryAcquire())
    assertTrue(DailyBudget(1, clock, "translation", counter).tryAcquire())
  }

  @Test
  fun `when the counter is down the count goes on in memory from its last value`() {
    val counter = SharedCounter()
    val budget = DailyBudget(2, Clock.fixed(NOW, ZoneOffset.UTC), "guide", counter)
    assertTrue(budget.tryAcquire())

    counter.down = true
    assertTrue(budget.tryAcquire())
    assertFalse(budget.tryAcquire())
  }

  @Test
  fun `a zero budget never calls the model`() {
    assertFalse(DailyBudget(0, Clock.fixed(NOW, ZoneOffset.UTC), "guide").tryAcquire())
  }

  @Test
  fun `a counter that throws is treated as down, never as an error`() {
    val budget =
      DailyBudget(1, Clock.fixed(NOW, ZoneOffset.UTC), "guide") { _, _ ->
        throw IllegalStateException("redis down")
      }
    assertTrue(budget.tryAcquire())
    assertFalse(budget.tryAcquire())
  }

  @Test
  fun `the budget reports the calls left today`() {
    val budget = DailyBudget(3, Clock.fixed(NOW, ZoneOffset.UTC), "guide")
    budget.tryAcquire()

    assertEquals(2, budget.remaining())
    assertEquals(0, DailyBudget(0, Clock.fixed(NOW, ZoneOffset.UTC), "guide").remaining())
  }

  @Test
  fun `a call given back can be taken again`() {
    val budget = DailyBudget(1, Clock.fixed(NOW, ZoneOffset.UTC), "guide")
    assertTrue(budget.tryAcquire())

    assertEquals(CallCounter.GiveBack.GIVEN_BACK, budget.giveBack(NOW.day(), "q1", 5))

    assertEquals(0, budget.usedToday())
    assertTrue(budget.tryAcquire())
  }

  @Test
  fun `a call is given back once, and never below zero`() {
    val budget = DailyBudget(3, Clock.fixed(NOW, ZoneOffset.UTC), "guide")
    budget.tryAcquire()

    budget.giveBack(NOW.day(), "q1", 5)

    assertEquals(CallCounter.GiveBack.ALREADY_GIVEN_BACK, budget.giveBack(NOW.day(), "q1", 5))
    assertEquals(CallCounter.GiveBack.NOTHING_COUNTED, budget.giveBack(NOW.day(), "q2", 5))
    assertEquals(0, budget.usedToday())
  }

  @Test
  fun `at most the daily cap of calls is given back`() {
    val budget = DailyBudget(5, Clock.fixed(NOW, ZoneOffset.UTC), "guide")
    repeat(3) { budget.tryAcquire() }

    budget.giveBack(NOW.day(), "q1", 2)
    budget.giveBack(NOW.day(), "q2", 2)

    assertEquals(CallCounter.GiveBack.CAP_REACHED, budget.giveBack(NOW.day(), "q3", 2))
    assertEquals(1, budget.usedToday())
  }

  @Test
  fun `the shared count is given back, so every replica sees it`() {
    val counter = SharedCounter()
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val budget = DailyBudget(1, clock, "guide", counter)
    budget.tryAcquire()

    assertEquals(CallCounter.GiveBack.GIVEN_BACK, budget.giveBack(NOW.day(), "q1", 5))

    assertEquals(0L, counter.counts["guide:${NOW.day()}"])
    assertEquals(
      CallCounter.GiveBack.ALREADY_GIVEN_BACK,
      DailyBudget(1, clock, "guide", counter).giveBack(NOW.day(), "q1", 5),
      "another replica, the same question",
    )
    assertTrue(DailyBudget(1, clock, "guide", counter).tryAcquire())
  }

  @Test
  fun `when the counter is down the call is given back in memory`() {
    val counter = SharedCounter()
    val budget = DailyBudget(1, Clock.fixed(NOW, ZoneOffset.UTC), "guide", counter)
    budget.tryAcquire()
    counter.down = true

    assertEquals(CallCounter.GiveBack.GIVEN_BACK, budget.giveBack(NOW.day(), "q1", 5))
    assertTrue(budget.tryAcquire())
  }

  @Test
  fun `a call of a day that is over is not given back in memory`() {
    val clock = MovableClock(Instant.parse("2026-10-26T23:59:00Z"))
    val budget = DailyBudget(1, clock, "guide")
    budget.tryAcquire()
    val yesterday = java.time.LocalDate.of(2026, 10, 26)

    clock.now = Instant.parse("2026-10-27T00:00:01Z")
    budget.tryAcquire()

    assertEquals(CallCounter.GiveBack.NOTHING_COUNTED, budget.giveBack(yesterday, "q1", 5))
    assertEquals(1, budget.usedToday())
  }

  private fun Instant.day() = java.time.LocalDate.ofInstant(this, ZoneOffset.UTC)
}
