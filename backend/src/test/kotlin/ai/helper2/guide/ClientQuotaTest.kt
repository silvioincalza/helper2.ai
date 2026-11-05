package ai.helper2.guide

import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientQuotaTest {

  private val clock = MovableClock(Instant.parse("2026-10-27T22:00:00Z"))
  private val keys = QuotaKeys("test-secret", clock)

  /** A durable counter in memory, standing in for Redis. */
  private val counts = mutableMapOf<String, Long>()
  private val counter = CallCounter { name, day -> counts.merge("$name:$day", 1L, Long::plus) }

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
        override fun increment(name: String, day: java.time.LocalDate) =
          counter.increment(name, day)

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
}
