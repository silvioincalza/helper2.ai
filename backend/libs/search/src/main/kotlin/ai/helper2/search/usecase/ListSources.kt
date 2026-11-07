package ai.helper2.search.usecase

import ai.helper2.platform.resilience.Resilience
import ai.helper2.platform.resilience.Resilience.Circuit
import ai.helper2.search.DescribedSource
import ai.helper2.search.DiscoveredResources
import ai.helper2.search.RecordedOutcomes
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceHealth
import ai.helper2.search.WebCredits
import ai.helper2.search.WebSearchQuota
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/** One disclosed source as the Sources page shows it. */
data class SourceOverview(
  val descriptor: SourceDescriptor,
  /** Last outcome: this replica's last search or the Scout's last run, whichever is newer. */
  val last: SourceHealth.Entry?,
  /** Median of this replica's recent calls ([SourceHealth.responseTime]). */
  val responseTime: Duration?,
  /**
   * This replica's circuit `source-<id>`; null for a disabled source or one without a circuit
   * (Scout collectors).
   */
  val circuit: Circuit?,
  /**
   * The credits of the day for the whole cluster; only on an enabled metered source, null when
   * unreadable.
   */
  val webCredits: WebCredits?,
  /**
   * Catalog resources this source brought that were first discovered in the last
   * [ListSources.NEW_WINDOW]; null when the source has no count of its own or the catalog cannot
   * answer.
   */
  val newThisWeek: Long?,
)

/**
 * Lists every disclosed source (search sources and Scout collectors, enabled or not) by priority
 * with its live numbers (ADR 0009) and what it brought this week (ADR 0028). Never throws for a
 * store that cannot answer: the Scout outcomes, the credits and the new counts are then left out.
 */
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
    /** "New this week": a rolling window, not the calendar week. */
    @JvmField val NEW_WINDOW: Duration = Duration.ofDays(7)
  }
}

/** An enabled metered source: it spends the day's web credits. */
private val SourceDescriptor.spendsCredits: Boolean
  get() = metered && enabled
