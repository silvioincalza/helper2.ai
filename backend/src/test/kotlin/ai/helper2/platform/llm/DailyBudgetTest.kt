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
        override fun increment(name: String, day: java.time.LocalDate): Long? =
            if (down) null else counts.merge("$name:$day", 1L, Long::plus)
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
        val budget = DailyBudget(1, Clock.fixed(NOW, ZoneOffset.UTC), "guide") { _, _ -> throw IllegalStateException("redis down") }
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
}
