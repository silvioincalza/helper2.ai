package ai.helper2.shared

/**
 * The sources whose records are web pages found by a web search (Tavily): the live source, the
 * Scout's Tavily queries, course discovery and every discovery category (ADR 0051). One place for
 * the page titles ([WebPageTitle.forSource]) and the retention of web news and articles.
 */
object WebSources {
  /** The id prefix of the discovery collectors: one per category ("discovery-coding"). */
  const val DISCOVERY_PREFIX = "discovery-"

  /** SQL LIKE pattern of the discovery collectors' ids. */
  const val DISCOVERY_LIKE = "discovery-_%"

  /** The web sources with a fixed id. */
  @JvmField val FIXED: List<String> = listOf("tavily", "tavily-scout", "course-scout")

  /** True when [sourceId] is a web source. */
  @JvmStatic
  fun isWeb(sourceId: String?): Boolean =
    sourceId != null && (sourceId in FIXED || isDiscovery(sourceId))

  /** True when [sourceId] is one discovery category's collector. */
  @JvmStatic
  fun isDiscovery(sourceId: String?): Boolean =
    sourceId != null &&
      sourceId.startsWith(DISCOVERY_PREFIX) &&
      sourceId.length > DISCOVERY_PREFIX.length
}
