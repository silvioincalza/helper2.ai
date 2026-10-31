package ai.helper2.search

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Last outcome and recent response time per source seen in searches, for the Sources page. In-memory by design (per
 * instance); Scout-only collectors are never searched, so the page merges in [RecordedOutcomes].
 */
class SourceHealth : SearchMetrics {
    data class Entry(val outcome: SourceOutcome, val checkedAt: Instant)

    private val last = ConcurrentHashMap<String, Entry>()
    private val durations = ConcurrentHashMap<String, ArrayDeque<Duration>>()

    override fun sourceCompleted(status: SourceStatus) {
        if (status.skipReason == SkipReason.DISABLED) return
        last[status.source.id] = Entry(status.outcome, status.checkedAt)
        val latency = status.latency
        if (latency != null && status.outcome != SourceOutcome.SKIPPED && status.servedFrom != ServedFrom.CACHE) {
            val recent = durations.computeIfAbsent(status.source.id) { ArrayDeque(WINDOW) }
            synchronized(recent) {
                if (recent.size == WINDOW) recent.removeFirst()
                recent.addLast(latency)
            }
        }
    }

    /** The newer of what this instance saw in searches and what was [recorded] elsewhere (Scout runs). */
    fun lastFor(sourceId: String, recorded: Entry?): Entry? {
        val seen = last[sourceId] ?: return recorded
        return if (recorded != null && recorded.checkedAt.isAfter(seen.checkedAt)) recorded else seen
    }

    /**
     * The median of this instance's last [WINDOW] calls to the source, failed and timed-out ones included; a cache hit
     * or a skipped call is not a call. Null before the first call.
     */
    fun responseTime(sourceId: String): Duration? {
        val recent = durations[sourceId] ?: return null
        val sorted = synchronized(recent) { recent.sorted() }
        return sorted.getOrNull((sorted.size - 1) / 2)
    }

    private companion object {
        const val WINDOW = 20
    }
}
