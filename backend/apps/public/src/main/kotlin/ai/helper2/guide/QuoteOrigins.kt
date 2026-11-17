package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.SourceHit
import ai.helper2.shared.CollectorIds
import ai.helper2.shared.McpRegistryEntry
import ai.helper2.shared.ResourceId
import java.net.URI

data class QuotedFrom(val sourceId: String, val url: URI)

class QuoteOrigins(
  private val gate: EvidenceGate = EvidenceGate(),
  private val mcpRegistry: String? = null,
) {

  fun storedResourcesOf(result: MergedResult): Set<ResourceId> =
    setOf(result.id) + result.hits.filter(::isCatalogRow).map { ResourceId.of(it.canonicalUrl) }

  fun of(
    quote: String,
    result: MergedResult,
    records: Map<ResourceId, List<SourceHit>>,
  ): QuotedFrom? {
    val quoted = gate.normalize(quote)
    if (quoted.isEmpty()) return null
    return evidence(result, records)
      .firstOrNull { gate.containsWords(gate.normalize(ownText(it)), quoted) }
      ?.let { QuotedFrom(it.sourceId, textPage(it)) }
  }

  private fun evidence(
    result: MergedResult,
    records: Map<ResourceId, List<SourceHit>>,
  ): List<SourceHit> =
    (result.hits.filterNot(::isCatalogRow) +
        storedResourcesOf(result).flatMap { records[it].orEmpty() })
      .filterNot { it.sourceId == CURATED }
      .distinctBy { it.sourceId to it.externalId }
      .sortedBy { if (it.canonicalUrl == result.canonicalUrl) 0 else 1 }

  private fun isCatalogRow(hit: SourceHit): Boolean =
    hit.externalId == ResourceId.of(hit.canonicalUrl).toString()

  private fun textPage(hit: SourceHit): URI =
    when {
      hit.sourceId == HACKER_NEWS && hit.externalId.all(Char::isDigit) ->
        URI("https://news.ycombinator.com/item?id=${hit.externalId}")
      hit.sourceId == CollectorIds.MCP_REGISTRY && mcpRegistry != null ->
        URI(McpRegistryEntry.url(mcpRegistry, hit.externalId))
      else -> hit.url
    }

  private fun ownText(hit: SourceHit): String =
    listOfNotNull(hit.title, hit.description.takeUnless { hit.descriptionOrigin.isAiWritten })
      .joinToString("\n")

  private companion object {
    const val HACKER_NEWS = "hacker-news"
    const val CURATED = "curated"
  }
}
