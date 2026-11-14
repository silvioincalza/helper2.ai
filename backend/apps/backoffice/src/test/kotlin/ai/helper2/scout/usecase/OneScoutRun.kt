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
import ai.helper2.scout.admission.AdmissionFixtures
import ai.helper2.scout.admission.AdmissionPolicy
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier

class OneScoutRun
private constructor(collector: Collector, clock: Clock, admission: AdmissionPolicy) {

  private val meters = SimpleMeterRegistry()
  private val sourceId = collector.descriptor.id
  private val indexedIds = mutableListOf<String>()
  private val runs = Runs()

  val indexed: List<String>
    get() = indexedIds.toList()

  val status: RunStatus

  init {
    val scout =
      RunScout(
        listOf(collector),
        runs,
        CatalogIndex { results, _ ->
          indexedIds += results.flatMap { result -> result.hits.map { it.externalId } }
          emptyList()
        },
        null,
        clock,
        UNLOCKED,
        meters = meters,
        admission = admission,
        review = CandidateReview { _, candidates -> Proposal(candidates.size) },
        lists = ListIndex {},
      )
    scout.tryStart()
    status = runs.last.status
  }

  fun dropped(reason: String): Double =
    meters.counter("helper2.source.items.dropped", "source", sourceId, "reason", reason).count()

  private class Runs : ScoutStore {
    lateinit var last: RunSummary

    override fun cursor(sourceId: String): ScoutCursor? = null

    override fun saveCursor(sourceId: String, cursor: ScoutCursor) {}

    override fun save(run: RunSummary) {
      last = run
    }

    override fun recent(limit: Int) = listOf(last)

    override fun markInterrupted(at: Instant) = 0
  }

  companion object {
    private val UNLOCKED =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
      }

    @JvmStatic
    fun of(collector: Collector, clock: Clock) =
      OneScoutRun(collector, clock, AdmissionFixtures.AS_CONFIGURED)
  }
}
