package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.SourceHit
import java.net.URI

/** The source page whose own text holds a quote: the page a reader can open to find it. */
data class QuotedFrom(val sourceId: String, val url: URI)

/**
 * Names the record a checked quote comes from (ADR 0031). A resource shows one link and one text,
 * but its text can come from another record than its link (a package's text under its repository's
 * link, ADR 0021). The quote is looked up, by the gate's own whole-word rule, in the title and
 * source-written description of each record: the record at the resource's own address first, then
 * the others in source order. A catalog row (a hit whose external id is the resource id) is the
 * resource itself, not a source, so its stored records stand in for it.
 */
class QuoteOrigins(private val gate: EvidenceGate = EvidenceGate()) {

  fun of(quote: String, result: MergedResult, records: List<SourceHit>): QuotedFrom? {
    val quoted = gate.normalize(quote)
    if (quoted.isEmpty()) return null
    return evidence(result, records)
      .firstOrNull { gate.containsWords(gate.normalize(ownText(it)), quoted) }
      ?.let { QuotedFrom(it.sourceId, it.url) }
  }

  private fun evidence(result: MergedResult, records: List<SourceHit>): List<SourceHit> {
    val catalogRow = result.id.toString()
    return (result.hits.filterNot { it.externalId == catalogRow } + records)
      .distinctBy { it.sourceId to it.externalId }
      .sortedBy { if (it.canonicalUrl == result.canonicalUrl) 0 else 1 }
  }

  private fun ownText(hit: SourceHit): String =
    listOfNotNull(hit.title, hit.description.takeUnless { hit.descriptionOrigin.isAiWritten })
      .joinToString("\n")
}
