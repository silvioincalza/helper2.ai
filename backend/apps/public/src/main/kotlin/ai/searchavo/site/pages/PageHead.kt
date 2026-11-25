package ai.searchavo.site.pages

import ai.searchavo.catalog.DirectoryRating
import ai.searchavo.catalog.IndexRule
import ai.searchavo.shared.Pricing
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.ValueOrigin
import java.net.URI
import java.time.Instant
import java.util.UUID

enum class Robots(val content: String) {
  INDEX("index, follow"),
  NOINDEX_FOLLOW("noindex, follow"),
  NOINDEX("noindex"),
}

data class PageHead(
  val title: String,
  val description: String?,
  val canonicalPath: String?,
  val robots: Robots,
  val jsonLd: List<Map<String, Any?>> = emptyList(),
  val body: PageBody? = null,
) {
  fun unlisted(): PageHead = if (robots == Robots.NOINDEX) this else copy(robots = Robots.NOINDEX)
}

data class PageBody(
  val heading: String,
  val description: String?,
  val facts: List<Fact>,
  val sources: List<Link>,
  val related: List<Related>,
  val foundIn: List<Related> = emptyList(),
  val listing: Listing? = null,
  val pages: List<PageLink> = emptyList(),
)

data class Listing(val heading: String, val items: List<Related>)

data class PageLink(val path: String, val name: String)

data class Fact(val name: String, val value: String)

data class Link(val name: String, val url: URI)

data class Related(val id: UUID, val title: String)

data class ResourceView(
  val id: UUID,
  val title: String,
  val type: ResourceType,
  val description: String?,
  val descriptionOrigin: ValueOrigin,
  val url: URI,
  val license: String?,
  val pricing: Pricing?,
  val platforms: Set<String>,
  val published: Instant?,
  val eventStart: Instant?,
  val eventEnd: Instant?,
  val location: String?,
  val sources: List<Link>,
  val related: List<Related>,
  val foundIn: List<Related> = emptyList(),
  val rating: DirectoryRating? = null,
) {
  val sourceDescription: String?
    get() =
      description?.trim()?.takeIf { it.isNotEmpty() && descriptionOrigin in WRITTEN_BY_PEOPLE }

  val hasFact: Boolean
    get() =
      license != null ||
        pricing != null ||
        published != null ||
        eventStart != null ||
        related.isNotEmpty()

  companion object {
    val WRITTEN_BY_PEOPLE =
      setOf(ValueOrigin.SOURCE_PROVIDED, ValueOrigin.CURATED, ValueOrigin.COMMUNITY)
  }
}

object IndexPolicy {
  val INDEXED_TYPES = IndexRule.INDEXED_TYPES

  val LISTED_TYPES = IndexRule.LISTED_TYPES

  const val PROMPT_MIN_LENGTH = IndexRule.PROMPT_MIN_LENGTH

  fun indexWorthy(r: ResourceView): Boolean {
    val description = r.sourceDescription ?: return false
    return when (r.type) {
      ResourceType.PROMPT -> description.length >= PROMPT_MIN_LENGTH
      in INDEXED_TYPES -> r.hasFact
      else -> false
    }
  }
}
