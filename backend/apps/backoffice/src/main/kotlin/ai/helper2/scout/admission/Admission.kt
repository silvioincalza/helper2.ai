package ai.helper2.scout.admission

import ai.helper2.search.PopularityKind
import ai.helper2.search.Refusal
import ai.helper2.search.SourceHit
import ai.helper2.search.VitalityPolicy
import java.time.Duration
import java.time.Instant

data class ResourceSignals(
  val stars: Long?,
  val likes: Long?,
  val lastChange: Instant?,
  val seenAt: Instant,
  val hasRepository: Boolean,
  val hasDescription: Boolean,
) {
  companion object {
    @JvmStatic
    fun statedBy(hit: SourceHit): ResourceSignals =
      ResourceSignals(
        stars = metricOf(hit, PopularityKind.GITHUB_STARS),
        likes = metricOf(hit, PopularityKind.HF_LIKES),
        lastChange = hit.updated.value,
        seenAt = hit.retrievedAt,
        hasRepository = hit.repositoryUrl != null,
        hasDescription = !hit.description.isNullOrBlank(),
      )

    private fun metricOf(hit: SourceHit, kind: PopularityKind): Long? =
      hit.popularity.filter { it.kind == kind }.maxOfOrNull { it.value }
  }
}

enum class ReviewReason(val reason: String) {
  NO_REPOSITORY("no-repository"),
  NO_DESCRIPTION("no-description"),
  FEW_STARS("few-stars"),
  NOT_ACTIVE("not-active"),
  FEW_LIKES("few-likes"),
}

sealed interface Admission {
  data object Index : Admission

  data class Review(val reason: ReviewReason) : Admission

  data class Refuse(val reason: Refusal) : Admission
}

data class IndexFloor
@JvmOverloads
constructor(
  val minStars: Long = 0,
  val pushedWithin: Duration? = null,
  val minLikes: Long = 0,
  val needsRepositoryAndDescription: Boolean = false,
) {
  fun reasonToReview(signals: ResourceSignals): ReviewReason? =
    when {
      needsRepositoryAndDescription && !signals.hasRepository -> ReviewReason.NO_REPOSITORY
      needsRepositoryAndDescription && !signals.hasDescription -> ReviewReason.NO_DESCRIPTION
      (signals.stars ?: 0) < minStars -> ReviewReason.FEW_STARS
      pushedWithin != null && !changedInside(pushedWithin, signals) -> ReviewReason.NOT_ACTIVE
      (signals.likes ?: 0) < minLikes -> ReviewReason.FEW_LIKES
      else -> null
    }

  private fun changedInside(window: Duration, signals: ResourceSignals): Boolean =
    signals.lastChange != null && !signals.lastChange.isBefore(signals.seenAt.minus(window))

  companion object {
    @JvmField val NONE = IndexFloor()
  }
}

class AdmissionRules(private val floorByProfile: Map<String, IndexFloor>) {
  fun of(profile: String): IndexFloor = floorByProfile[profile] ?: IndexFloor.NONE

  companion object {
    @JvmField val NONE = AdmissionRules(emptyMap())
  }
}

class AdmissionPolicy
@JvmOverloads
constructor(
  private val vitality: VitalityPolicy = VitalityPolicy(),
  private val rules: AdmissionRules = AdmissionRules.NONE,
) {
  fun decide(stated: SourceHit, profile: String): Admission {
    val refusal = vitality.refusalOf(stated)
    if (refusal != null) return Admission.Refuse(refusal)
    val reason = rules.of(profile).reasonToReview(ResourceSignals.statedBy(stated))
    return if (reason == null) Admission.Index else Admission.Review(reason)
  }

  fun indexes(stated: SourceHit, profile: String): Boolean =
    decide(stated, profile) == Admission.Index
}
