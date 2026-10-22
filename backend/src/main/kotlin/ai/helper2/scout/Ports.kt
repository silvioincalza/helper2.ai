package ai.helper2.scout

import ai.helper2.search.DescribedSource
import ai.helper2.search.SourceHit

/**
 * One deterministic collection pass. [checkpoint] is what to pass next time for an incremental run
 * (null = this pass did not finish, keep the previous checkpoint). [nextCursor] is set when the
 * pass stopped early because of the item budget.
 */
data class CollectedBatch @JvmOverloads constructor(
    val hits: List<SourceHit>,
    val nextCursor: String?,
    val checkpoint: String?,
    /** true when the collector chose not to call its source this time (e.g. its run interval has not passed). */
    val notDue: Boolean = false,
) {
    companion object {
        /** Nothing collected and nothing advanced: the previous checkpoint stays. */
        @JvmStatic
        fun notDue(): CollectedBatch = CollectedBatch(emptyList(), null, null, true)
    }
}

/** Outbound port: a Scout connector. Must only report sources it actually queried. */
interface Collector : DescribedSource {
    /**
     * @param checkpoint previous complete-pass checkpoint, or null for a full first run.
     * @param resumeCursor pagination cursor to continue an unfinished pass, or null to start one.
     */
    fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch
}
