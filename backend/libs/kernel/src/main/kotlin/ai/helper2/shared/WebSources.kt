package ai.helper2.shared

object WebSources {
  const val DISCOVERY_PREFIX = "discovery-"

  const val DISCOVERY_LIKE = "discovery-_%"

  const val EVENTS_SCOUT = "live-web-events-scout"

  @JvmField
  val FIXED: List<String> =
    listOf("live-web", "live-web-scout", "course-scout", "linkedin-scout", EVENTS_SCOUT)

  @JvmStatic fun eventDaysReadByAi(sourceId: String?): Boolean = sourceId == EVENTS_SCOUT

  @JvmStatic
  fun isWeb(sourceId: String?): Boolean =
    sourceId != null && (sourceId in FIXED || isDiscovery(sourceId))

  @JvmStatic
  fun isDiscovery(sourceId: String?): Boolean =
    sourceId != null &&
      sourceId.startsWith(DISCOVERY_PREFIX) &&
      sourceId.length > DISCOVERY_PREFIX.length
}
