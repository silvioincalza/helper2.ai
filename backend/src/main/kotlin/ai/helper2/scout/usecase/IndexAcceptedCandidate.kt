package ai.helper2.scout.usecase

import ai.helper2.scout.Candidate
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.Collector
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceHit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceType
import java.net.URI
import java.time.Clock
import java.util.UUID

/** A resource candidate an admin accepted: what the collector that found it proposed. */
data class AcceptedCandidate(
  val sourceId: String,
  val url: String,
  val title: String,
  val snippet: String?,
  val type: ResourceType,
  /** The categories it belongs to, as catalog tags. */
  val tags: Set<String> = emptySet(),
)

/** Why an accepted candidate cannot be indexed; the review item stays open. */
class InvalidCandidate(message: String) : IllegalArgumentException(message)

/**
 * Outbound port: reads a candidate from a review item's details, as its collector wrote them.
 * Throws [InvalidCandidate] when they cannot be read.
 */
fun interface CandidateDetails {
  fun read(details: String?): AcceptedCandidate
}

/** Outbound port: whether the catalog already lists a page. */
fun interface CatalogLookup {
  fun listed(url: CanonicalUrl): Boolean
}

/**
 * An admin accepted a resource candidate (ADR 0051): it is indexed as a record of the collector
 * that found it, with the collector's own title and excerpt, so it is catalogued like everything
 * the Scout collects. It has no enrichment yet: the next Scout run takes it from the backlog. The
 * values are checked again here, because the review item is stored text. A candidate's type must be
 * one its collector declares (ADR 0053): LinkedIn may propose articles, posts and events, discovery
 * only products.
 */
class IndexAcceptedCandidate(
  collectors: List<Collector>,
  private val catalog: CatalogIndex,
  private val lookup: CatalogLookup,
  private val details: CandidateDetails,
  private val clock: Clock,
) {
  private val sources = collectors.associateBy { it.descriptor.id }
  private val merger = ResultMerger()

  private companion object {
    const val MAX_TITLE = 300
    const val MAX_SNIPPET = 1000
    val TAG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    /** Every type a candidate may have; a collector narrows it to the types it declares. */
    val TYPES =
      setOf(
        ResourceType.PRODUCT,
        ResourceType.AGENT,
        ResourceType.PROMPT,
        ResourceType.SKILL,
        ResourceType.MCP_SERVER,
        ResourceType.LIBRARY,
        ResourceType.MODEL,
        ResourceType.DATASET,
        ResourceType.ARTICLE,
        ResourceType.NEWS,
        ResourceType.EVENT,
      )
  }

  /**
   * Accepts the candidate stored in a review item's [details]: checked first, then indexed. A page
   * another source indexed after it was proposed is left as that source wrote it: the result is
   * null and only the decision is recorded.
   */
  fun accept(details: String?): UUID? {
    val candidate = this.details.read(details)
    val url = hitOf(candidate, collectorOf(candidate)).canonicalUrl
    return if (lookup.listed(url)) null else index(candidate)
  }

  /** The id of the resource the candidate became. */
  fun index(candidate: AcceptedCandidate): UUID {
    val collector = collectorOf(candidate)
    val hit = hitOf(candidate, collector)
    val merged =
      merger.merge(
        listOf(hit),
        mapOf(hit.sourceId to SourceContext(collector.descriptor, ServedFrom.LIVE)),
      )
    return catalog.index(merged, hit.sourceId).firstOrNull()
      ?: throw InvalidCandidate("the candidate was not indexed")
  }

  private fun collectorOf(c: AcceptedCandidate): Collector =
    sources[c.sourceId] ?: throw InvalidCandidate("unknown source ${c.sourceId}")

  private fun hitOf(c: AcceptedCandidate, collector: Collector): SourceHit {
    val url = CanonicalUrl.of(c.url) ?: throw InvalidCandidate("unreadable URL")
    val title = c.title.trim()
    if (title.isEmpty() || title.length > MAX_TITLE)
      throw InvalidCandidate("title missing or too long")
    if (c.type !in TYPES) throw InvalidCandidate("type ${c.type} cannot be a candidate")
    if (c.type !in collector.descriptor.resourceTypes) {
      throw InvalidCandidate("${c.sourceId} does not propose ${c.type}")
    }
    if (c.tags.size > Candidate.MAX_TAGS || c.tags.any { it.length > 40 || !TAG.matches(it) }) {
      throw InvalidCandidate("unreadable tags")
    }
    return SourceHit(
      sourceId = c.sourceId,
      externalId = url.value,
      url = URI.create(url.value),
      canonicalUrl = url,
      title = title,
      description = c.snippet?.trim()?.take(MAX_SNIPPET)?.ifEmpty { null },
      type = c.type,
      tags = c.tags,
      retrievedAt = clock.instant(),
    )
  }
}
