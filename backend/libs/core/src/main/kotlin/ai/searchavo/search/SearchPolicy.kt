package ai.searchavo.search

import java.time.Duration

data class SearchPolicy(
  val webSearches: DailyLimits,
  val cachedSearches: Duration,
  val translations: Duration,
  val storedWebResults: Duration,
)
