package ai.searchavo.scout.usecase

import ai.searchavo.scout.RunSummary
import ai.searchavo.scout.ScoutStore

class ListScoutRuns(private val store: ScoutStore) {

  fun latest(): List<RunSummary> = store.latestPerSource()
}
