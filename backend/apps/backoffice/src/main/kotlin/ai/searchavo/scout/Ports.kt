package ai.searchavo.scout

import ai.searchavo.catalog.DirectoryRatingIndex
import ai.searchavo.search.DescribedSource
import ai.searchavo.search.PopularityKind
import ai.searchavo.search.PopularityMetric
import ai.searchavo.search.SourceDescriptor
import ai.searchavo.search.SourceHit
import ai.searchavo.search.Vitality
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.ResourceType
import java.time.Duration
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
  fun runEvery(): Duration

  fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch

  fun collect(cursor: ScoutCursor, maxItems: Int): CollectedBatch =
    collect(cursor.checkpoint, cursor.resumeCursor, maxItems)

  fun alsoWrites(): List<SourceDescriptor> = emptyList()
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

data class ListText
@JvmOverloads
constructor(
  val url: CanonicalUrl,
  val headings: String,
  val entries: List<ListEntryText>,
  val directoryListTitle: String? = null,
  /** UC-030 BR-010: the figures a list page of a directory host states for what it names. */
  val ratings: DirectoryRatingIndex.ListRatings? = null,
) {
  /** A list page of a directory host: it names resources and is never a resource itself. */
  val onDirectoryHost: Boolean
    get() = directoryListTitle != null
}

data class ListEntryText(val url: CanonicalUrl, val text: String, val headingPath: String)
