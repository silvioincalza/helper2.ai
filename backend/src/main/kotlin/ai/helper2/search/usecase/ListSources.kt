package ai.helper2.search.usecase

import ai.helper2.platform.resilience.Resilience
import ai.helper2.platform.resilience.Resilience.Circuit
import ai.helper2.search.DescribedSource
import ai.helper2.search.RecordedOutcomes
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceHealth
import ai.helper2.search.WebCredits
import ai.helper2.search.WebSearchQuota
import java.time.Duration
import org.slf4j.LoggerFactory

/** One disclosed source as the Sources page shows it. */
data class SourceOverview(
    val descriptor: SourceDescriptor,
    /** Last outcome: this replica's last search or the Scout's last run, whichever is newer. */
    val last: SourceHealth.Entry?,
    /** Median of this replica's recent calls ([SourceHealth.responseTime]). */
    val responseTime: Duration?,
    /** This replica's circuit `source-<id>`; null for a disabled source or one without a circuit (Scout collectors). */
    val circuit: Circuit?,
    /** The credits of the day for the whole cluster; only on an enabled metered source, null when unreadable. */
    val webCredits: WebCredits?,
)

/**
 * Lists every disclosed source (search sources and Scout collectors, enabled or not) by priority with its live
 * numbers (ADR 0008). Never throws for a store that cannot answer: the Scout outcomes and the credits are then left out.
 */
class ListSources(
    private val sources: List<DescribedSource>,
    private val health: SourceHealth,
    private val recorded: RecordedOutcomes,
    private val resilience: Resilience,
    private val quota: WebSearchQuota,
) {
    private val log = LoggerFactory.getLogger(ListSources::class.java)

    fun list(): List<SourceOverview> {
        val fromScout = recordedOutcomes()
        val descriptors = sources.map { it.descriptor }.sortedWith(compareBy({ it.priority }, { it.id }))
        val credits = if (descriptors.any { it.metered && it.enabled }) quota.creditsToday() else null
        return descriptors.map { d ->
            SourceOverview(
                descriptor = d,
                last = health.lastFor(d.id, fromScout[d.id]),
                responseTime = health.responseTime(d.id),
                circuit = if (d.enabled) resilience.circuit(SearchResources.policy(d)).orElse(null) else null,
                webCredits = credits?.takeIf { d.metered && d.enabled },
            )
        }
    }

    private fun recordedOutcomes(): Map<String, SourceHealth.Entry> = try {
        recorded.lastOutcomes()
    } catch (e: RuntimeException) {
        log.warn("Recorded source outcomes unavailable: {}", e.toString())
        emptyMap()
    }
}
