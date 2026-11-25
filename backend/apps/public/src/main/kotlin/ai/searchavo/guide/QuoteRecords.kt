package ai.searchavo.guide

import ai.searchavo.search.SourceHit
import ai.searchavo.shared.ResourceId

fun interface QuoteRecords {
  fun of(ids: Collection<ResourceId>): Map<ResourceId, List<SourceHit>>

  companion object {
    @JvmField val NONE = QuoteRecords { emptyMap() }
  }
}
