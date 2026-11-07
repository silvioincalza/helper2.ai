package ai.helper2.pages

import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import java.net.URI
import java.time.Instant
import java.util.UUID

/** What the robots meta says. FOLLOW keeps the links of a page that is not listed itself. */
enum class Robots(val content: String) {
  INDEX("index, follow"),
  NOINDEX_FOLLOW("noindex, follow"),
  NOINDEX("noindex"),
}

/**
 * What the server writes into the app shell for one address (ADR 0029). [canonicalPath] is joined
 * to the public host by the adapter. Texts are plain: the adapter escapes them.
 */
data class PageHead(
  val title: String,
  val description: String?,
  val canonicalPath: String?,
  val robots: Robots,
  val jsonLd: List<Map<String, Any?>> = emptyList(),
  val body: PageBody? = null,
) {
  /** The same page that no search engine may list (staging, H2_NOINDEX): meta and header agree. */
  fun unlisted(): PageHead = if (robots == Robots.NOINDEX) this else copy(robots = Robots.NOINDEX)
}

/** The short text a crawler reads in #root; React replaces it. */
data class PageBody(
  val heading: String,
  val description: String?,
  val facts: List<Fact>,
  val sources: List<Link>,
  val related: List<Related>,
)

data class Fact(val name: String, val value: String)

data class Link(val name: String, val url: URI)

data class Related(val id: UUID, val title: String)

/** A resource as the page shows it: the catalog's detail, cut to what the head needs. */
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
) {
  /**
   * The description when a source (or a person) wrote it. An AI-written one never goes into a meta
   * tag or JSON-LD: they cannot carry the AI label (ADR 0018).
   */
  val sourceDescription: String?
    get() =
      description?.trim()?.takeIf { it.isNotEmpty() && descriptionOrigin in WRITTEN_BY_PEOPLE }

  /** A stated fact beyond the title: licence, pricing, a date, or a related resource. */
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

/**
 * Which resource pages are worth a search engine's index (ADR 0029 (part was 0050) §5). The sitemap
 * query in the catalog applies the same rule; an integration test keeps the two in line.
 */
object IndexPolicy {
  val INDEXED_TYPES =
    setOf(
      ResourceType.PRODUCT,
      ResourceType.AGENT,
      ResourceType.MODEL,
      ResourceType.MCP_SERVER,
      ResourceType.LIBRARY,
      ResourceType.SKILL,
      ResourceType.DATASET,
      ResourceType.PAPER,
      ResourceType.EVENT,
      ResourceType.COURSE,
    )

  /** A prompt page is worth it only with its full text: at least this many characters. */
  const val PROMPT_MIN_LENGTH = 200

  fun indexWorthy(r: ResourceView): Boolean {
    val description = r.sourceDescription ?: return false
    return when (r.type) {
      ResourceType.PROMPT -> description.length >= PROMPT_MIN_LENGTH
      in INDEXED_TYPES -> r.hasFact
      else -> false
    }
  }
}
