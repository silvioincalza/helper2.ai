package ai.helper2.scout

import ai.helper2.search.DescribedSource
import ai.helper2.search.SourceHit
import ai.helper2.shared.CanonicalUrl

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

/** A web page a collector found and proposes for the catalog; a person decides before anything is catalogued. */
data class Candidate(
    val url: CanonicalUrl,
    val title: String,
    val snippet: String?,
    /** The platform or vendor the page belongs to (e.g. "Coursera"), when the collector knows it. */
    val platform: String?,
)

/** Outbound port: the admin review list for collector proposals (ADR 0019 review queue, ADR 0032 §8). */
fun interface CandidateReview {
    /**
     * Opens one review item per candidate that is neither in the catalog nor proposed before (whatever was
     * decided then). Returns how many items were opened.
     */
    fun propose(sourceId: String, candidates: List<Candidate>): Int
}
