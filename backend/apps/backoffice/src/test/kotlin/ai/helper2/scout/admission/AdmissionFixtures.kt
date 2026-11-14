package ai.helper2.scout.admission

import ai.helper2.scout.CollectedBatch
import ai.helper2.search.SourceHit
import ai.helper2.search.VitalityPolicy
import java.time.Duration

object AdmissionFixtures {
  const val TOPIC_LEADERS = "github-topic-leaders"
  const val HF_TRENDING = "hf-trending"
  const val PYPI = "pypi"

  @JvmField
  val AS_CONFIGURED =
    AdmissionPolicy(
      VitalityPolicy(),
      AdmissionRules(
        mapOf(
          TOPIC_LEADERS to IndexFloor(minStars = 1000, pushedWithin = Duration.ofDays(90)),
          HF_TRENDING to IndexFloor(minLikes = 20),
          PYPI to IndexFloor(needsRepositoryAndDescription = true),
        )
      ),
    )

  @JvmStatic
  fun indexedOf(batch: CollectedBatch): List<SourceHit> =
    batch.hits.filter { AS_CONFIGURED.indexes(it, it.sourceId) }
}
