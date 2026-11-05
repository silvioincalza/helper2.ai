package ai.helper2.pages

import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The head of every page the server knows (ADR 0050 §2-3). [base] is the public host. */
class Pages(private val base: String) {

  fun home(): PageHead =
    PageHead(
      // The Home's own title and lede (ADR 0042, decision 4), so the search result and the page say
      // the same.
      title = "Helper2.ai: the metasearch for the AI universe",
      description =
        "One search across the world of AI for models, apps, MCP servers and papers. " +
          "A pick with proof, plus the latest AI news and events.",
      canonicalPath = "/",
      robots = Robots.INDEX,
      jsonLd = listOf(website(), organization()),
    )

  fun of(kind: PageKind): PageHead =
    when (kind) {
      PageKind.HOME -> home()
      PageKind.SOURCES ->
        listed(
          "Sources: where Helper2.ai searches",
          "Every source Helper2.ai searches, how it reaches you, and whether it answers right now.",
          "/sources",
        )
      PageKind.MISSION ->
        listed(
          "Our mission",
          "Help people find the right AI resource for their need, and show the proof behind every " +
            "answer.",
          "/mission",
        )
      PageKind.PRIVACY ->
        listed(
          "Privacy",
          "What Helper2.ai collects, why, for how long, and who receives it.",
          "/privacy",
        )
      PageKind.TERMS ->
        listed(
          "Terms of use",
          "The terms that apply when you use the Helper2.ai website.",
          "/terms",
        )
      PageKind.SEARCH -> unlisted("Search")
      PageKind.SAVED -> unlisted("Saved")
      PageKind.ACCOUNT -> unlisted("Account")
      PageKind.ADMIN,
      PageKind.ADMIN_PAGE -> PageHead(brand("Back office"), null, null, Robots.NOINDEX)
      PageKind.RESOURCE -> error("a resource page needs its resource")
    }

  fun pageNotFound(): PageHead = PageHead(brand("Page not found"), null, null, Robots.NOINDEX)

  fun resourceNotFound(): PageHead =
    PageHead(brand("Resource not found"), null, null, Robots.NOINDEX)

  fun resource(r: ResourceView): PageHead {
    val path = "/resource/${r.id}"
    val description = r.sourceDescription?.let { cut(it, DESCRIPTION_MAX) } ?: factLine(r)
    return PageHead(
      title = resourceTitle(r),
      description = description,
      canonicalPath = path,
      robots = if (IndexPolicy.indexWorthy(r)) Robots.INDEX else Robots.NOINDEX_FOLLOW,
      jsonLd = listOfNotNull(JsonLd.of(r, base + path), breadcrumbs(r, base + path)),
      body =
        PageBody(
          heading = r.title,
          description = r.sourceDescription,
          facts = facts(r),
          sources = r.sources.take(MAX_SOURCES),
          related = r.related.take(MAX_RELATED),
        ),
    )
  }

  private fun listed(name: String, description: String, path: String) =
    PageHead(brand(name), description, path, Robots.INDEX)

  private fun unlisted(name: String) = PageHead(brand(name), null, null, Robots.NOINDEX_FOLLOW)

  private fun website(): Map<String, Any?> =
    mapOf(
      "@context" to "https://schema.org",
      "@type" to "WebSite",
      "name" to BRAND,
      "url" to "$base/",
    )

  private fun organization(): Map<String, Any?> =
    mapOf(
      "@context" to "https://schema.org",
      "@type" to "Organization",
      "name" to BRAND,
      "url" to "$base/",
    )

  private fun breadcrumbs(r: ResourceView, url: String): Map<String, Any?> =
    mapOf(
      "@context" to "https://schema.org",
      "@type" to "BreadcrumbList",
      "itemListElement" to
        listOf(
          crumb(1, BRAND, "$base/"),
          crumb(2, r.title, url),
        ),
    )

  private fun crumb(position: Int, name: String, url: String) =
    mapOf("@type" to "ListItem", "position" to position, "name" to name, "item" to url)

  companion object {
    const val BRAND = "Helper2.ai"
    const val TITLE_MAX = 60
    const val DESCRIPTION_MAX = 155
    const val MAX_FACTS = 6
    const val MAX_SOURCES = 5
    const val MAX_RELATED = 8
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

    fun brand(name: String) = "$name · $BRAND"

    /**
     * `{title}: {type in words} · Helper2.ai`, the type dropped and then the title cut when too
     * long.
     */
    fun resourceTitle(r: ResourceView): String {
      val full = brand("${r.title}: ${typeInWords(r.type)}")
      if (full.length <= TITLE_MAX) return full
      val plain = brand(r.title)
      if (plain.length <= TITLE_MAX) return plain
      return brand(cut(r.title, TITLE_MAX - " · $BRAND".length))
    }

    fun typeInWords(type: ResourceType): String =
      when (type) {
        ResourceType.PRODUCT -> "AI app"
        ResourceType.MODEL -> "AI model"
        ResourceType.AGENT -> "AI agent"
        ResourceType.PROMPT -> "prompt"
        ResourceType.SKILL -> "agent skill"
        ResourceType.MCP_SERVER -> "MCP server"
        ResourceType.LIBRARY -> "library"
        ResourceType.DATASET -> "dataset"
        ResourceType.ARTICLE -> "article"
        ResourceType.PAPER -> "paper"
        ResourceType.NEWS -> "news"
        ResourceType.EVENT -> "event"
        ResourceType.COURSE -> "course"
        ResourceType.OTHER -> "AI resource"
      }

    fun pricingInWords(pricing: Pricing): String =
      when (pricing) {
        Pricing.FREE -> "Free"
        Pricing.FREEMIUM -> "Free plan and paid plans"
        Pricing.PAID -> "Paid"
        Pricing.OPEN_SOURCE_SELF_HOSTED -> "Open source, self-hosted"
      }

    /** [text] cut at a word boundary to at most [max] characters, with "…" when cut. */
    fun cut(text: String, max: Int): String {
      val t = text.replace(Regex("\\s+"), " ").trim()
      if (t.length <= max) return t
      val room = t.substring(0, max - 1)
      val space = room.lastIndexOf(' ')
      val head = if (space >= max / 2) room.substring(0, space) else room
      return head.trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    /** A description built from facts, for a resource without one a person wrote. */
    fun factLine(r: ResourceView): String {
      val parts = mutableListOf(typeInWords(r.type).replaceFirstChar { it.uppercase() })
      r.license?.let { parts += "licence $it" }
      r.pricing?.let { parts += pricingInWords(it).lowercase() }
      val sources = r.sources.map { it.name }.distinct()
      val found = if (sources.isEmpty()) "" else ", found on ${sources.joinToString(", ")}"
      return cut("${r.title}: ${parts.joinToString(", ")}$found.", DESCRIPTION_MAX)
    }

    fun facts(r: ResourceView): List<Fact> =
      listOfNotNull(
          Fact("Type", typeInWords(r.type)),
          r.license?.let { Fact("Licence", it) },
          r.pricing?.let { Fact("Pricing", pricingInWords(it)) },
          r.platforms
            .takeIf { it.isNotEmpty() }
            ?.let { Fact("Platforms", it.sorted().joinToString(", ")) },
          r.published?.let { Fact("Published", DAY.format(it)) },
          r.eventStart?.let { start ->
            Fact("When", listOfNotNull(start, r.eventEnd).joinToString(" to ") { DAY.format(it) })
          },
          r.location?.let { Fact("Where", it) },
        )
        .take(MAX_FACTS)
  }
}

/**
 * schema.org data by resource type (ADR 0050 §3). Only facts the page shows; no AI-written text.
 */
object JsonLd {
  fun of(r: ResourceView, url: String): Map<String, Any?>? {
    val type = schemaType(r.type) ?: return null
    val data =
      linkedMapOf<String, Any?>(
        "@context" to "https://schema.org",
        "@type" to type,
        "name" to r.title,
        "url" to url,
        "sameAs" to r.url.toString(),
      )
    r.sourceDescription?.let { data["description"] = it }
    when (r.type) {
      ResourceType.LIBRARY,
      ResourceType.MCP_SERVER,
      ResourceType.SKILL -> {
        data["codeRepository"] = r.url.toString()
        r.license?.let { data["license"] = it }
      }
      ResourceType.PAPER -> {
        data["headline"] = r.title
        r.published?.let { data["datePublished"] = it.toString() }
      }
      ResourceType.EVENT -> {
        r.eventStart?.let { data["startDate"] = it.toString() }
        r.eventEnd?.let { data["endDate"] = it.toString() }
        r.location?.let { data["location"] = mapOf("@type" to "Place", "name" to it) }
      }
      else -> r.license?.let { data["license"] = it }
    }
    return data
  }

  private fun schemaType(type: ResourceType): String? =
    when (type) {
      ResourceType.PRODUCT,
      ResourceType.AGENT,
      ResourceType.MODEL -> "SoftwareApplication"
      ResourceType.LIBRARY,
      ResourceType.MCP_SERVER,
      ResourceType.SKILL -> "SoftwareSourceCode"
      ResourceType.PAPER -> "ScholarlyArticle"
      ResourceType.EVENT -> "Event"
      ResourceType.COURSE -> "Course"
      ResourceType.DATASET -> "Dataset"
      else -> null
    }
}
