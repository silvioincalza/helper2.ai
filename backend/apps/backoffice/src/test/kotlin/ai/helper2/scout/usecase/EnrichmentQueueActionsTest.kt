package ai.helper2.scout.usecase

import ai.helper2.catalog.enrichment.EnrichmentCandidates
import ai.helper2.platform.AuditTrail
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentQueueControl
import ai.helper2.scout.QueueOutcome
import ai.helper2.scout.QueuePlace
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnrichmentQueueActionsTest {

  private val now = Instant.parse("2026-11-05T10:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val id = UUID.randomUUID()
  private val waiting =
    QueuePlace(due = true, outOfRetries = false, skipped = false, prioritised = false)

  private var place: QueuePlace? = waiting
  private var atTop = 0
  private val changes = mutableListOf<String>()
  private val audit = mutableListOf<String>()

  private val topSteps = mutableListOf<String>()

  private val backlog =
    object : EnrichmentBacklog {
      override fun pending(limit: Int) = emptyList<UUID>()

      override fun pendingCount() = 0L

      override fun queue(
        view: EnrichmentCandidates.View,
        after: EnrichmentCandidates.BacklogCursor?,
        size: Int,
      ) = emptyList<EnrichmentCandidates.BacklogEntry>()

      override fun place(resourceId: UUID) = if (resourceId == id) place else null

      override fun prioritisedCount(): Int {
        topSteps += "count"
        return atTop
      }

      override fun eligible(resourceIds: List<UUID>) = resourceIds

      override fun requestRun(resourceId: UUID) = Unit
    }

  private val control =
    object : EnrichmentQueueControl {
      override fun lockTop() {
        topSteps += "lock"
      }

      override fun prioritise(resourceId: UUID, at: Instant) {
        changes += "prioritise $resourceId $at"
      }

      override fun skip(resourceId: UUID, reason: String?, at: Instant) {
        changes += "skip $resourceId $reason $at"
      }

      override fun requeue(resourceId: UUID, resetFailures: Boolean, at: Instant) {
        changes += "requeue $resourceId $resetFailures $at"
      }
    }

  private val trail = AuditTrail { adminId, action, targetId, outcome ->
    audit += "$adminId $action $targetId $outcome"
  }

  private var transactions = 0
  private val unitOfWork =
    object : UnitOfWork {
      override fun <T : Any> run(work: Supplier<T>): T {
        transactions++
        return work.get()
      }
    }

  private val requeue = RequeueEnrichment(backlog, control, trail, unitOfWork, clock)
  private val skip = SkipEnrichment(backlog, control, trail, unitOfWork, clock)
  private val prioritise = PrioritiseEnrichment(backlog, control, trail, unitOfWork, clock)

  @Test
  fun `prioritise moves a waiting resource to the top and records PRIORITISED`() {
    assertEquals(QueueOutcome.DONE, prioritise.prioritise("42", id))
    assertEquals(listOf("prioritise $id $now"), changes)
    assertEquals(listOf("42 PRIORITISE_ENRICHMENT $id PRIORITISED"), audit)
    assertEquals(1, transactions)
  }

  @Test
  fun `prioritise locks the top before it counts, so the limit holds for two admins at once`() {
    prioritise.prioritise("42", id)
    assertEquals(listOf("lock", "count"), topSteps)
  }

  @Test
  fun `prioritise changes nothing when the top is full, and records why`() {
    atTop = QueuePlace.MAX_PRIORITISED
    assertEquals(QueueOutcome.TOP_FULL, prioritise.prioritise("42", id))
    assertTrue(changes.isEmpty())
    assertEquals(listOf("42 PRIORITISE_ENRICHMENT $id TOP_FULL"), audit)
  }

  @Test
  fun `skip keeps a trimmed reason, and a blank one counts as none`() {
    assertEquals(QueueOutcome.DONE, skip.skip("42", id, "  spam  "))
    place = waiting
    skip.skip("42", id, "   ")
    assertEquals(listOf("skip $id spam $now", "skip $id null $now"), changes)
    assertEquals("42 SKIP_ENRICHMENT $id SKIPPED", audit.first())
  }

  @Test
  fun `skip drops control characters from the reason`() {
    skip.skip("42", id, "\u0000 not\u0007 AI\n\t")
    assertEquals(listOf("skip $id not AI $now"), changes)
  }

  @Test
  fun `a reason longer than the limit is refused before anything`() {
    assertThrows<IllegalArgumentException> {
      skip.skip("42", id, "x".repeat(SkipEnrichment.MAX_REASON + 1))
    }
    assertTrue(changes.isEmpty())
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `requeue resets the failures only for a resource out of retries, and records REQUEUED_RESET`() {
    place = waiting.copy(skipped = true)
    assertEquals(QueueOutcome.DONE, requeue.requeue("42", id))
    place = QueuePlace(due = false, outOfRetries = true, skipped = false, prioritised = false)
    assertEquals(QueueOutcome.DONE, requeue.requeue("42", id))
    assertEquals(listOf("requeue $id false $now", "requeue $id true $now"), changes)
    assertEquals(
      listOf("42 REQUEUE_ENRICHMENT $id REQUEUED", "42 REQUEUE_ENRICHMENT $id REQUEUED_RESET"),
      audit,
    )
  }

  @Test
  fun `requeue of a waiting resource is refused and recorded as NOT_STOPPED`() {
    assertEquals(QueueOutcome.NOT_STOPPED, requeue.requeue("42", id))
    assertTrue(changes.isEmpty())
    assertEquals(listOf("42 REQUEUE_ENRICHMENT $id NOT_STOPPED"), audit)
  }

  @Test
  fun `an unknown resource is NOT_FOUND for every action`() {
    val unknown = UUID.randomUUID()
    assertEquals(QueueOutcome.NOT_FOUND, requeue.requeue("42", unknown))
    assertEquals(QueueOutcome.NOT_FOUND, skip.skip("42", unknown, null))
    assertEquals(QueueOutcome.NOT_FOUND, prioritise.prioritise("42", unknown))
    assertTrue(changes.isEmpty())
    assertEquals(
      listOf(
        "42 REQUEUE_ENRICHMENT $unknown NOT_FOUND",
        "42 SKIP_ENRICHMENT $unknown NOT_FOUND",
        "42 PRIORITISE_ENRICHMENT $unknown NOT_FOUND",
      ),
      audit,
    )
  }
}
