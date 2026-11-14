package ai.helper2.search.usecase

import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceQuery
import java.security.MessageDigest
import java.util.HexFormat

internal object SourceCacheKeys {
  fun cacheKey(d: SourceDescriptor, q: SourceQuery): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cacheMaterial(d, q).toByteArray())
    return "h2:v1:src:${d.id}:${HexFormat.of().formatHex(digest, 0, 16)}"
  }

  fun cacheMaterial(d: SourceDescriptor, q: SourceQuery): String {
    val normalized = QueryAnalyzer().analyze(q.text).normalized
    val web = q.webQuery?.takeIf { d.usesWebQuery }?.let { "|web:$it" } ?: ""
    val after = q.publishedAfter?.takeIf { d.filtersByDate }?.let { "|after:$it" } ?: ""
    val browse =
      if (q.browse) "|browse:${q.types.map { it.name }.sorted().joinToString(",")}" else ""
    return "$normalized|${q.expandedTerms.joinToString(",")}|${q.limit}$web$after$browse"
  }
}
