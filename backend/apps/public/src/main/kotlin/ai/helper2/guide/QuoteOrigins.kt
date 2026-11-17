package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.SourceHit
import java.net.URI

data class QuotedFrom(val sourceId: String, val url: URI)

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
