package ai.helper2.guide.web

import ai.helper2.platform.resilience.Resilience
import java.time.Clock
import java.time.Duration

internal fun guideLimits(
  resilience: Resilience,
  deadline: Duration,
  questionsPerClient: Int = GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
): GuideLimits =
  GuideLimits(
    resilience,
    deadline,
    GuideLimits.ANSWER_GRACE,
    GuideLimits.LEAVE_GRACE,
    Clock.systemUTC(),
    questionsPerClient,
  )
