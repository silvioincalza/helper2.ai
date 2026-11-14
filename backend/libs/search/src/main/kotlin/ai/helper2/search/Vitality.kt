package ai.helper2.search

import ai.helper2.shared.ResourceType
import java.time.Duration
import java.time.Instant

data class Vitality
@JvmOverloads
constructor(
  val archived: Boolean? = null,
  val fork: Boolean? = null,
  val deprecated: Boolean? = null,
  val running: Boolean? = null,
) {
  companion object {
    @JvmField val UNKNOWN = Vitality()
  }
}

data class VitalityRules
@JvmOverloads
constructor(
  val hostedAppMinLikes: Long = 3,
  val forkMinStars: Long = 10,
  val maintainedWithin: Duration = Duration.ofDays(1095),
  val establishedStars: Long = 10_000,
)

enum class Refusal(val reason: String) {
  ARCHIVED("archived"),
  DEPRECATED("deprecated"),
  NOT_RUNNING("not-running"),
  TOO_FEW_LIKES("too-few-likes"),
  IDLE_FORK("idle-fork"),
}

class VitalityPolicy @JvmOverloads constructor(private val rules: VitalityRules = VitalityRules()) {

  fun admitted(
    hits: List<SourceHit>,
    onRefusal: (SourceHit, Refusal) -> Unit = { _, _ -> },
  ): List<SourceHit> =
    hits
      .filter { hit -> refusalOf(hit)?.also { onRefusal(hit, it) } == null }
      .mapIndexed { place, hit -> if (hit.rank == place) hit else hit.withRank(place) }

  fun refusalOf(hit: SourceHit): Refusal? {
    val stated = hit.vitality
    return when {
      stated.archived == true -> Refusal.ARCHIVED
      stated.deprecated == true -> Refusal.DEPRECATED
      stated.running == false -> Refusal.NOT_RUNNING
      stated.running == true && likesOf(hit) < rules.hostedAppMinLikes -> Refusal.TOO_FEW_LIKES
      stated.fork == true && isIdleFork(hit) -> Refusal.IDLE_FORK
      else -> null
    }
  }

  fun isUnmaintained(result: MergedResult): Boolean {
    if (result.type !in MAINTAINED_TYPES) return false
    if (isEstablished(result)) return false
    val updated = result.updated.value ?: return false
    val seenAt = result.hits.maxOfOrNull { it.retrievedAt } ?: return false
    return isOlderThanTheWindow(updated, seenAt)
  }

  private fun isEstablished(result: MergedResult): Boolean =
    result.popularity.any {
      it.kind == PopularityKind.GITHUB_STARS && it.value >= rules.establishedStars
    }

  private fun isIdleFork(hit: SourceHit): Boolean {
    val pushed = hit.updated.value
    val maintained = pushed != null && !isOlderThanTheWindow(pushed, hit.retrievedAt)
    return starsOf(hit) < rules.forkMinStars && !maintained
  }

  private fun isOlderThanTheWindow(at: Instant, seenAt: Instant): Boolean =
    at.isBefore(seenAt.minus(rules.maintainedWithin))

  private fun likesOf(hit: SourceHit) = metricOf(hit, PopularityKind.HF_LIKES)

  private fun starsOf(hit: SourceHit) = metricOf(hit, PopularityKind.GITHUB_STARS)

  private fun metricOf(hit: SourceHit, kind: PopularityKind): Long =
    hit.popularity.filter { it.kind == kind }.maxOfOrNull { it.value } ?: 0

  companion object {
    val MAINTAINED_TYPES =
      setOf(
        ResourceType.MCP_SERVER,
        ResourceType.LIBRARY,
        ResourceType.SKILL,
        ResourceType.AGENT,
      )
  }
}
