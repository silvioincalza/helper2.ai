package ai.helper2.scout

import ai.helper2.search.DescribedSource
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.SourceHit
import ai.helper2.search.Vitality
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
import java.time.Instant

data class CollectedBatch
@JvmOverloads
constructor(
  val hits: List<SourceHit>,
  val nextCursor: String?,
  val checkpoint: String?,
  val notDue: Boolean = false,
  val candidates: List<Candidate> = emptyList(),
  val skipReason: String? = null,
  val lists: List<ListText> = emptyList(),
  val deferred: Boolean = false,
  val newCandidatesPerDay: Int? = null,
  val withdrawn: List<String> = emptyList(),
) {
  companion object {
    @JvmStatic fun notDue(): CollectedBatch = CollectedBatch(emptyList(), null, null, true)

    @JvmStatic
    fun skipped(reason: String): CollectedBatch =
      CollectedBatch(emptyList(), null, null, true, emptyList(), reason)

    @JvmStatic
    fun deferred(reason: String): CollectedBatch =
      CollectedBatch(emptyList(), null, null, true, emptyList(), reason, deferred = true)
  }
}

interface Collector : DescribedSource {
  fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch

  fun collect(cursor: ScoutCursor, maxItems: Int): CollectedBatch =
    collect(cursor.checkpoint, cursor.resumeCursor, maxItems)
}

data class Candidate
@JvmOverloads
constructor(
  val url: CanonicalUrl,
  val title: String,
  val snippet: String?,
  val platform: String?,
  val type: ResourceType,
  val tags: Set<String> = emptySet(),
  val facts: Map<String, Any> = emptyMap(),
  val signals: CandidateSignals = CandidateSignals.UNKNOWN,
) {
  companion object {
    const val MAX_TAGS = 12
  }
}

data class CandidateSignals
@JvmOverloads
constructor(
  val stars: Long? = null,
  val lastPush: Instant? = null,
  val archived: Boolean? = null,
  val fork: Boolean? = null,
) {
  fun statedOn(hit: SourceHit): SourceHit =
    hit.copy(
      popularity = hit.popularity + stars(hit.sourceId, hit.retrievedAt),
      updated = lastChange(hit),
      vitality = Vitality(archived = archived, fork = fork),
    )

  private fun stars(sourceId: String, asOf: Instant): List<PopularityMetric> =
    listOfNotNull(stars?.let { PopularityMetric(PopularityKind.GITHUB_STARS, it, sourceId, asOf) })

  private fun lastChange(hit: SourceHit): DateFact =
    DateFact.lastChange(
      listOf(hit.updated, DateFact.fromSource(lastPush, hit.sourceId, hit.retrievedAt))
    )

  companion object {
    @JvmField val UNKNOWN = CandidateSignals()
  }
}

data class Proposal(val opened: Int, val heldBack: Int = 0)

fun interface CandidateReview {
  fun propose(sourceId: String, candidates: List<Candidate>): Proposal

  fun propose(sourceId: String, candidates: List<Candidate>, newPerDay: Int?): Proposal =
    propose(sourceId, candidates)
}

data class ListText(val url: CanonicalUrl, val headings: String, val entries: List<ListEntryText>)

data class ListEntryText(val url: CanonicalUrl, val text: String, val headingPath: String)
