package ai.helper2.scout

import ai.helper2.scout.QueueOutcome.ALREADY_PRIORITISED
import ai.helper2.scout.QueueOutcome.ALREADY_SKIPPED
import ai.helper2.scout.QueueOutcome.DONE
import ai.helper2.scout.QueueOutcome.NOT_STOPPED
import ai.helper2.scout.QueueOutcome.NOT_WAITING
import ai.helper2.scout.QueueOutcome.TOP_FULL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QueuePlaceTest {

  private val waiting =
    QueuePlace(due = true, outOfRetries = false, skipped = false, prioritised = false)
  private val atTop = waiting.copy(prioritised = true)
  private val skipped = waiting.copy(skipped = true)
  private val outOfRetries =
    QueuePlace(due = false, outOfRetries = true, skipped = false, prioritised = false)
  private val done =
    QueuePlace(due = false, outOfRetries = false, skipped = false, prioritised = false)

  @Test
  fun `a skipped resource does not wait, even when the rule would take it`() {
    assertTrue(waiting.waiting)
    assertFalse(skipped.waiting)
    assertFalse(outOfRetries.waiting)
  }

  @Test
  fun `requeue applies to a skipped resource and to one out of retries only`() {
    assertEquals(DONE, skipped.requeue())
    assertEquals(DONE, outOfRetries.requeue())
    assertEquals(NOT_STOPPED, waiting.requeue())
    assertEquals(NOT_STOPPED, atTop.requeue())
    assertEquals(NOT_STOPPED, done.requeue())
  }

  @Test
  fun `skip applies to a waiting resource, at the top or not`() {
    assertEquals(DONE, waiting.skip())
    assertEquals(DONE, atTop.skip())
    assertEquals(ALREADY_SKIPPED, skipped.skip())
    assertEquals(NOT_WAITING, outOfRetries.skip())
    assertEquals(NOT_WAITING, done.skip())
  }

  @Test
  fun `prioritise applies to a waiting resource while the top has room`() {
    assertEquals(DONE, waiting.prioritise(prioritisedNow = 0))
    assertEquals(DONE, waiting.prioritise(prioritisedNow = QueuePlace.MAX_PRIORITISED - 1))
    assertEquals(TOP_FULL, waiting.prioritise(prioritisedNow = QueuePlace.MAX_PRIORITISED))
    assertEquals(ALREADY_PRIORITISED, atTop.prioritise(prioritisedNow = 1))
    assertEquals(NOT_WAITING, skipped.prioritise(prioritisedNow = 0))
    assertEquals(NOT_WAITING, outOfRetries.prioritise(prioritisedNow = 0))
    assertEquals(NOT_WAITING, done.prioritise(prioritisedNow = 0))
  }
}
