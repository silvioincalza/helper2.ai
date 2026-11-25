package ai.searchavo.scout.usecase

import ai.searchavo.scout.CandidateReview
import ai.searchavo.scout.CatalogIndex
import ai.searchavo.scout.Collector
import ai.searchavo.scout.ListIndex
import ai.searchavo.scout.Proposal
import ai.searchavo.scout.RunLock
import ai.searchavo.scout.RunStatus
import ai.searchavo.scout.RunSummary
import ai.searchavo.scout.ScoutCursor
import ai.searchavo.scout.ScoutStore
import ai.searchavo.scout.admission.AdmissionFixtures
import ai.searchavo.scout.admission.AdmissionPolicy
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
    meters.counter("searchavo.source.items.dropped", "source", sourceId, "reason", reason).count()

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
