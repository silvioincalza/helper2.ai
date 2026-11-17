package ai.helper2.scout.usecase

import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScoutStore

class ListScoutRuns(private val store: ScoutStore) {

  fun latest(): List<RunSummary> = store.latestPerSource()
}
