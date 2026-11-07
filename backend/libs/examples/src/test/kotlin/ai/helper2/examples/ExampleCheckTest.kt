package ai.helper2.examples

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExampleCheckTest {

  private val at = Instant.parse("2026-11-05T03:00:00Z")
  private val expected = UUID.randomUUID()
  private val other = List(12) { UUID.randomUUID() }

  private fun at(rank: Int?): List<UUID> =
    other.toMutableList().also { if (rank != null) it.add(rank - 1, expected) }

  private fun check(ids: List<UUID>, degraded: Boolean = false) =
    ExampleCheck.of(SearchFindings(ids, ids.size, degraded), listOf(expected), at)

  @Test
  fun `an expected result in the top 3 passes`() {
    for (rank in 1..3) {
      val result = check(at(rank))!!
      assertEquals(CheckOutcome.PASS, result.outcome)
      assertEquals(rank, result.bestRank)
      assertEquals(13, result.resultCount)
      assertEquals(at, result.checkedAt)
    }
  }

  @Test
  fun `in the top 4 to 10 it is weak, beyond or absent it fails`() {
    assertEquals(CheckOutcome.WEAK, check(at(4))!!.outcome)
    assertEquals(CheckOutcome.WEAK, check(at(10))!!.outcome)
    val beyond = check(at(11))!!
    assertEquals(CheckOutcome.FAIL, beyond.outcome)
    assertNull(beyond.bestRank)
    assertEquals(CheckOutcome.FAIL, check(other)!!.outcome)
    assertEquals(CheckOutcome.FAIL, check(emptyList())!!.outcome)
  }

  @Test
  fun `the best of several expected results counts`() {
    val second = UUID.randomUUID()
    val ids = listOf(other[0], other[1], other[2], other[3], second, expected)
    val result = ExampleCheck.of(SearchFindings(ids, 6, false), listOf(expected, second), at)!!
    assertEquals(5, result.bestRank)
  }

  @Test
  fun `a degraded search keeps the last result`() {
    assertNull(check(at(1), degraded = true))
  }
}
