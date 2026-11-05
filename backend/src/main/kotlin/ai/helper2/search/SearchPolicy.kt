package ai.helper2.search

import java.time.Duration

/**
 * What search allows and keeps, as the text pages state it (GET /api/v1/policy, ADR 0051). Built
 * once from the module's settings; read, never counted.
 *
 * @property webSearches live web searches per visitor, feature and UTC day (ADR 0037)
 * @property registeredTier signed-in users get the registered limits only when this is true
 * @property cachedSearches how long source answers stay in the search cache
 * @property translations how long English translations of a query are kept
 * @property storedWebResults how long stored web result sets are kept
 */
data class SearchPolicy(
  val webSearches: DailyLimits,
  val registeredTier: Boolean,
  val cachedSearches: Duration,
  val translations: Duration,
  val storedWebResults: Duration,
)
