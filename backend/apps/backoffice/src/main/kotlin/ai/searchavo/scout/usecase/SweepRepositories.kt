package ai.searchavo.scout.usecase

import ai.searchavo.catalog.enrichment.RepositoryVisits
import ai.searchavo.catalog.enrichment.RepositoryVisits.Due
import ai.searchavo.catalog.enrichment.RepositoryVisits.Found
import ai.searchavo.catalog.enrichment.RepositoryVisits.Gone
import ai.searchavo.catalog.enrichment.RepositoryVisits.Unanswered
import ai.searchavo.catalog.enrichment.RepositoryVisits.Visit
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.UUID

fun interface RepositoryStates {
  fun of(repositories: Collection<String>): Map<String, Visit>
}

/** What a curated list named goes once its repository is gone at GitHub (UC-030 BR-012). */
fun interface GoneListRule {
  fun forget(notFoundRecords: Collection<UUID>)
}

class SweepRepositories(
  private val visits: RepositoryVisits,
  private val states: RepositoryStates,
  private val clock: Clock,
  private val batch: Int,
  private val meters: MeterRegistry,
  private val goneLists: GoneListRule,
) {

  fun sweepOneBatch(): Int {
    val due = visits.due(batch)
    if (due.isEmpty()) return 0
    val seen = states.of(due.map(Due::repository).distinct())
    val visitedAt = clock.instant()
    val notFound = mutableListOf<UUID>()
    due.forEach { record ->
      val visit = seen[record.repository] ?: Unanswered()
      visits.record(record.recordId, visit, visitedAt)
      count(visit)
      if (visit is Gone) notFound += record.recordId
    }
    // UC-030 BR-012: only a visit GitHub answers with "not found" can make a list gone; a
    // repository without an answer and a failed question hand nothing over.
    if (notFound.isNotEmpty()) goneLists.forget(notFound)
    return due.size
  }

  private fun count(visit: Visit) =
    meters.counter("searchavo.scout.repository_sweep", "outcome", outcomeOf(visit)).increment()

  private fun outcomeOf(visit: Visit): String =
    when (visit) {
      is Found -> if (visit.archived) "archived" else "found"
      is Gone -> "not_found"
      is Unanswered -> "unanswered"
    }
}
