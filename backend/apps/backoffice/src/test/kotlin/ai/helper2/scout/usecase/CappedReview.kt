package ai.helper2.scout.usecase

import ai.helper2.scout.CandidateReview
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.Collector
import ai.helper2.scout.ListIndex
import ai.helper2.scout.Proposal
import ai.helper2.scout.RunLock
import ai.helper2.scout.RunStatus
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.ScoutStore
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

class CappedReview private constructor(private val collector: Collector, clock: Clock) {

  data class Run(val status: RunStatus, val proposed: List<String>, val cursorAfter: ScoutCursor?)

  private val store = Cursors()
  private val proposed = mutableListOf<String>()
  private var heldBackByTheCap = 0
  private val scout =
    RunScout(
      listOf(collector),
      store,
      CatalogIndex { _, _ -> emptyList() },
      null,
      clock,
      UNLOCKED,
      review =
        CandidateReview { _, candidates ->
          proposed += candidates.map { it.url.value }
          Proposal(candidates.size - heldBackByTheCap, heldBackByTheCap)
        },
      lists = ListIndex {},
    )

  private fun run(heldBack: Int): Run {
    heldBackByTheCap = heldBack
    proposed.clear()
    val summary = checkNotNull(scout.tryStart()).single()
    return Run(
      store.runs.getValue(summary.id).status,
      proposed.toList(),
      store.cursor(collector.descriptor.id),
    )
  }

  private class Cursors : ScoutStore {
    private val cursors = mutableMapOf<String, ScoutCursor>()
    val runs = mutableMapOf<UUID, RunSummary>()

    override fun cursor(sourceId: String) = cursors[sourceId]

    override fun saveCursor(sourceId: String, cursor: ScoutCursor) {
      cursors[sourceId] = cursor
    }

    override fun save(run: RunSummary) {
      runs[run.id] = run
    }

    override fun recent(limit: Int) = runs.values.toList().takeLast(limit)

    override fun markInterrupted(at: Instant) = 0
  }

  companion object {
    private val UNLOCKED =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
      }

    @JvmStatic
    fun aHeldBackRunThenTheNext(collector: Collector, clock: Clock): List<Run> {
      val review = CappedReview(collector, clock)
      return listOf(review.run(heldBack = 1), review.run(heldBack = 0))
    }
  }
}
