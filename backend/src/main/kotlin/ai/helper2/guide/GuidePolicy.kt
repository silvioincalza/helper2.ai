package ai.helper2.guide

import java.time.Duration

/**
 * What the guide allows and keeps, as the text pages state it (GET /api/v1/policy, ADR 0052).
 *
 * @property questionsPerDay fresh questions per client address and UTC day (ADR 0017)
 * @property maxCandidates search results the reasoning model chooses from (ADR 0015)
 * @property maxPicks most picks in one answer
 * @property cachedAnswers how long an answer stays in the shared cache (ADR 0041)
 */
data class GuidePolicy
@JvmOverloads
constructor(
  val questionsPerDay: Int,
  val maxCandidates: Int,
  val cachedAnswers: Duration,
  val maxPicks: Int = GuideValidator.MAX_PICKS,
)
