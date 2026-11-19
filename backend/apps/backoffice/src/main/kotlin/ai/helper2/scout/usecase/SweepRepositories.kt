package ai.helper2.scout.usecase

import ai.helper2.catalog.enrichment.RepositoryVisits
import ai.helper2.catalog.enrichment.RepositoryVisits.Due
import ai.helper2.catalog.enrichment.RepositoryVisits.Found
import ai.helper2.catalog.enrichment.RepositoryVisits.Gone
import ai.helper2.catalog.enrichment.RepositoryVisits.Unanswered
import ai.helper2.catalog.enrichment.RepositoryVisits.Visit
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

fun interface RepositoryStates {
  fun of(repositories: Collection<String>): Map<String, Visit>
}

class SweepRepositories(
  private val visits: RepositoryVisits,
  private val states: RepositoryStates,
  private val clock: Clock,
  private val batch: Int,
  private val meters: MeterRegistry,
) {

  fun sweepOneBatch(): Int {
    val due = visits.due(batch)
    if (due.isEmpty()) return 0
    val seen = states.of(due.map(Due::repository).distinct())
    val visitedAt = clock.instant()
    due.forEach { record ->
      val visit = seen[record.repository] ?: Unanswered()
      visits.record(record.recordId, visit, visitedAt)
      count(visit)
    }
    return due.size
  }

  private fun count(visit: Visit) =
    meters.counter("helper2.scout.repository_sweep", "outcome", outcomeOf(visit)).increment()

  private fun outcomeOf(visit: Visit): String =
    when (visit) {
      is Found -> if (visit.archived) "archived" else "found"
      is Gone -> "not_found"
      is Unanswered -> "unanswered"
    }
}
