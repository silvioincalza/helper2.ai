package ai.helper2.shared

object NewsFeeds {
  const val PREFIX = "feed-"

  const val LIKE = "feed-_%"

  @JvmStatic
  fun isFeed(sourceId: String?): Boolean =
    sourceId != null && sourceId.startsWith(PREFIX) && sourceId.length > PREFIX.length
}
