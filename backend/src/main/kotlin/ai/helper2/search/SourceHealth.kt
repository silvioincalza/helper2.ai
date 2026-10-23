package ai.helper2.search

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Last outcome per source seen in searches, for the Sources page. In-memory by design (per instance);
 * Scout-only collectors are never searched, so the page merges in [RecordedOutcomes].
 */
class SourceHealth : SearchMetrics {
    data class Entry(val outcome: SourceOutcome, val checkedAt: Instant)

    private val last = ConcurrentHashMap<String, Entry>()

    override fun sourceCompleted(status: SourceStatus) {
        if (status.outcome == SourceOutcome.SKIPPED && status.servedFrom == ServedFrom.NONE && status.message == "Disabled in configuration") return
        last[status.source.id] = Entry(status.outcome, status.checkedAt)
    }

    /** The newer of what this instance saw in searches and what was [recorded] elsewhere (Scout runs). */
    fun lastFor(sourceId: String, recorded: Entry?): Entry? {
        val seen = last[sourceId] ?: return recorded
        return if (recorded != null && recorded.checkedAt.isAfter(seen.checkedAt)) recorded else seen
    }
}
