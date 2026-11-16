package ai.helper2.guide

import ai.helper2.search.SourceHit
import ai.helper2.shared.ResourceId

/**
 * The stored source records of catalog resources, read once per answer for its picks (ADR 0031).
 */
fun interface QuoteRecords {
  fun of(ids: Collection<ResourceId>): Map<ResourceId, List<SourceHit>>

  companion object {
    @JvmField val NONE = QuoteRecords { emptyMap() }
  }
}
