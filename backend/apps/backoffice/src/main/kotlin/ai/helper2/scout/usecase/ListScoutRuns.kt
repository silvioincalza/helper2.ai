package ai.helper2.scout.usecase

import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScoutStore

class ListScoutRuns(private val store: ScoutStore) {

  companion object {
    const val LATEST = 50
  }

  fun latest(): List<RunSummary> = store.recent(LATEST)
}
