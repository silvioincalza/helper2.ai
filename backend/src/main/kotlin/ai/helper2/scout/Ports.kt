package ai.helper2.scout

import ai.helper2.search.DescribedSource
import ai.helper2.search.SourceHit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceType

/**
 * One deterministic collection pass. [checkpoint] is what to pass next time for an incremental run
 * (null = this pass did not finish, keep the previous checkpoint). [nextCursor] is set when the
 * pass stopped early because of the item budget. [hits] go to the catalog; [candidates] go to the
 * admin review list and reach the catalog only when a person accepts them.
 */
data class CollectedBatch
@JvmOverloads
constructor(
  val hits: List<SourceHit>,
  val nextCursor: String?,
  val checkpoint: String?,
  /**
   * true when the collector chose not to call its source this time (e.g. its run interval has not
   * passed).
   */
  val notDue: Boolean = false,
  /** Pages proposed for review, not indexed. */
  val candidates: List<Candidate> = emptyList(),
  /** Why the collector did not call its source ([notDue] only); null: its run interval. */
  val skipReason: String? = null,
) {
  companion object {
    /** Nothing collected and nothing advanced: the previous checkpoint stays. */
    @JvmStatic fun notDue(): CollectedBatch = CollectedBatch(emptyList(), null, null, true)

    /** As [notDue], with the reason the run record shows ("daily credit cap reached"). */
    @JvmStatic
    fun skipped(reason: String): CollectedBatch =
      CollectedBatch(emptyList(), null, null, true, emptyList(), reason)
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

/**
 * A web page a collector found and proposes for the catalog; a person decides before anything is
 * catalogued.
 */
data class Candidate
@JvmOverloads
constructor(
  val url: CanonicalUrl,
  val title: String,
  val snippet: String?,
  /**
   * The platform or vendor the page belongs to (e.g. "Coursera"), or the category a discovery
   * search found it in (e.g. "Self-hosted"), when the collector knows it.
   */
  val platform: String?,
  /**
   * What the page would be in the catalog. COURSE pages wait as course candidates; any other type
   * waits as a resource candidate, which an admin's acceptance indexes (ADR 0051).
   */
  val type: ResourceType = ResourceType.COURSE,
  /**
   * Catalog tags the page gets when it is accepted: the categories it belongs to, at most
   * [MAX_TAGS] (ADR 0051).
   */
  val tags: Set<String> = emptySet(),
  /**
   * What the collector knew when it found the page (the discovery search, ADR 0051), kept with the
   * review item and, once accepted, with the record ([ai.helper2.search.SourceHit.facts]).
   */
  val facts: Map<String, Any> = emptyMap(),
) {
  companion object {
    /** The most tags a candidate can have and still be accepted. */
    const val MAX_TAGS = 12
  }
}

/**
 * Outbound port: the admin review list for collector proposals (ADR 0019 review queue, ADR 0032
 * §8). A RESOURCE_CANDIDATE whose URL already waits OPEN is merged into the waiting item (ADR
 * 0051): two categories that find the same product make one item.
 */
fun interface CandidateReview {
  /**
   * Opens one review item per candidate that is neither in the catalog nor proposed before
   * (whatever was decided then), or merges it into the OPEN item of its URL. Returns how many items
   * were opened.
   */
  fun propose(sourceId: String, candidates: List<Candidate>): Int
}
