package ai.searchavo.search.usecase

import ai.searchavo.platform.resilience.Resilience
import ai.searchavo.platform.resilience.Resilience.Circuit
import ai.searchavo.search.DescribedSource
import ai.searchavo.search.DiscoveredResources
import ai.searchavo.search.RecordedOutcomes
import ai.searchavo.search.SourceDescriptor
import ai.searchavo.search.SourceHealth
import ai.searchavo.search.WebCredits
import ai.searchavo.search.WebSearchQuota
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

data class SourceOverview(
  val descriptor: SourceDescriptor,
  val last: SourceHealth.Entry?,
  val responseTime: Duration?,
  val circuit: Circuit?,
  val webCredits: WebCredits?,
  val newThisWeek: Long?,
)

class ListSources(
  private val sources: List<DescribedSource>,
  private val health: SourceHealth,
  private val recorded: RecordedOutcomes,
  private val resilience: Resilience,
  private val quota: WebSearchQuota,
  private val discovered: DiscoveredResources,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(ListSources::class.java)

  fun list(): List<SourceOverview> {
    val fromScout = recordedOutcomes()
    val newCounts = newThisWeek()
    val descriptors =
      sources.map { it.descriptor }.sortedWith(compareBy({ it.priority }, { it.id }))
    val credits = if (descriptors.any { it.spendsCredits }) quota.creditsToday() else null
    return descriptors.map { overview(it, fromScout[it.id], credits, newCounts) }
  }

  private fun overview(
    d: SourceDescriptor,
    fromScout: SourceHealth.Entry?,
    credits: WebCredits?,
    newCounts: Map<String, Long?>?,
  ): SourceOverview =
    SourceOverview(
      descriptor = d,
      last = health.lastFor(d.id, fromScout),
      responseTime = health.responseTime(d.id),
      circuit = circuitOf(d),
      webCredits = credits?.takeIf { d.spendsCredits },
      newThisWeek = newCounts?.let { if (d.id in it) it[d.id] else 0 },
    )

  private fun circuitOf(d: SourceDescriptor): Circuit? =
    if (d.enabled) resilience.circuit(SearchResources.policy(d)).orElse(null) else null

  private fun newThisWeek(): Map<String, Long?>? =
    try {
      discovered.countsSince(clock.instant().minus(NEW_WINDOW))
    } catch (e: RuntimeException) {
      log.warn("New resources per source unavailable: {}", e.toString())
      null
    }

  private fun recordedOutcomes(): Map<String, SourceHealth.Entry> =
    try {
      recorded.lastOutcomes()
    } catch (e: RuntimeException) {
      log.warn("Recorded source outcomes unavailable: {}", e.toString())
      emptyMap()
    }

  companion object {
    @JvmField val NEW_WINDOW: Duration = Duration.ofDays(7)
  }
}

private val SourceDescriptor.spendsCredits: Boolean
  get() = metered && enabled
