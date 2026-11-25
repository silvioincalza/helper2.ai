package ai.searchavo.scout.admission

import ai.searchavo.scout.CollectedBatch
import ai.searchavo.search.SourceHit
import ai.searchavo.search.VitalityPolicy
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
