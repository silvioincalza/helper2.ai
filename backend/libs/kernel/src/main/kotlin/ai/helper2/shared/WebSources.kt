package ai.helper2.shared

/**
 * The sources whose records are web pages found by a web search (Tavily): the live source, the
 * Scout's Tavily queries, course discovery, every discovery category (ADR 0022) and LinkedIn via
 * Tavily (ADR 0021) and the events scout (ADR 0021). One place for the page titles
 * ([WebPageTitle.forSource]) and the retention of web news and articles.
 */
object WebSources {
  /** The id prefix of the discovery collectors: one per category ("discovery-coding"). */
  const val DISCOVERY_PREFIX = "discovery-"

  /** SQL LIKE pattern of the discovery collectors' ids. */
  const val DISCOVERY_LIKE = "discovery-_%"

  /**
   * Upcoming AI events whose days, kind, format and place an AI model read on the event's page,
   * checked by code against the page (ADR 0021).
   */
  const val EVENTS_SCOUT = "live-web-events-scout"

  /** The web sources with a fixed id. */
  @JvmField
  val FIXED: List<String> =
    listOf("live-web", "live-web-scout", "course-scout", "linkedin-scout", EVENTS_SCOUT)

  /**
   * True when an AI model read [sourceId]'s event days: they are AI-extracted (ADR 0021), shown
   * with an AI label and never preferred over days another source states.
   */
  @JvmStatic fun eventDaysReadByAi(sourceId: String?): Boolean = sourceId == EVENTS_SCOUT

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
