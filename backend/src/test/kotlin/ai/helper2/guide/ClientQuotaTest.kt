package ai.helper2.guide

import ai.helper2.platform.llm.CallCounter
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientQuotaTest {

    private val clock = MovableClock(Instant.parse("2026-10-27T22:00:00Z"))

    /** A durable counter in memory, standing in for Redis. */
    private val counts = mutableMapOf<String, Long>()
    private val counter = CallCounter { name, day -> counts.merge("$name:$day", 1L, Long::plus) }

    @Test
    fun `a new UTC day starts every client afresh and drops yesterday's clients`() {
        val quota = ClientQuota(1, clock)
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
        val quota = ClientQuota(5, clock, maxClients = 2)
        quota.tryAcquire("a")
        quota.tryAcquire("b")
        quota.tryAcquire("a")
        quota.tryAcquire("c")

        assertEquals(2, quota.clientsInMemory)
    }

    @Test
    fun `a client that left memory keeps its count in the durable counter`() {
        val quota = ClientQuota(1, clock, counter, maxClients = 1)
        assertTrue(quota.tryAcquire("a"))
        assertTrue(quota.tryAcquire("b"))

        assertFalse(quota.tryAcquire("a"))
    }
}
