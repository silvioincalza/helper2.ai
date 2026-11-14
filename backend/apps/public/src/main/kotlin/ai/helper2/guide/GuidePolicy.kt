package ai.helper2.guide

import java.time.Duration

data class GuidePolicy
@JvmOverloads
constructor(
  val questionsPerDay: Int,
  val freeAnswers: Int,
  val maxCandidates: Int,
  val cachedAnswers: Duration,
  val maxPicks: Int = GuideValidator.MAX_PICKS,
)
