package ai.searchavo.site.pages

import ai.searchavo.catalog.DirectoryRating
import ai.searchavo.catalog.resourcePath
import ai.searchavo.shared.Pricing
import ai.searchavo.shared.ResourceType
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class Pages(private val base: String) {

  fun home(): PageHead =
    PageHead(
      title = "$BRAND: the metasearch for the AI ecosystem",
      description =
        "Models, apps, MCP servers and papers from every corner of AI, in one search. " +
          "Picks come with proof.",
      canonicalPath = "/",
      robots = Robots.INDEX,
      jsonLd = listOf(website(), organization()),
    )

  fun home(newInCatalog: List<Related>): PageHead {
    val head = home()
    val listing = newInCatalog.takeIf { it.isNotEmpty() }?.let { Listing("New in the catalog", it) }
    return head.copy(body = siteBody(BRAND, head.description.orEmpty(), "/", listing))
  }

  @JvmOverloads
  fun of(kind: PageKind, lists: SiteLists = SiteLists.NONE): PageHead =
    when (kind) {
      PageKind.HOME -> home(lists.newInCatalog())
      PageKind.SOURCES ->
        listed(
          "Sources: where $BRAND searches",
          "Every source $BRAND searches, how it reaches you, and whether it answers right now.",
          "/sources",
        )
      PageKind.EVENTS ->
        listed(
          "AI events",
          "Conferences, hackathons, meetups, talks and workshops on AI in the next year, with the " +
            "dates their sources state.",
          "/events",
          lists.upcomingEvents().takeIf { it.isNotEmpty() }?.let { Listing("Coming up", it) },
        )
      PageKind.NEWS -> unlisted("AI news")
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
          "What $BRAND collects, why, for how long, and who receives it.",
          "/privacy",
        )
      PageKind.TERMS ->
        listed(
          "Terms of use",
          "The terms that apply when you use the $BRAND website.",
          "/terms",
        )
      PageKind.SUPPORT ->
        listed(
          "Support $BRAND",
          "What running $BRAND costs, and how to help pay for it.",
          "/support",
        )
      PageKind.SUPPORT_THANKS -> unlisted("Thank you")
      PageKind.SEARCH -> unlisted("Search")
      PageKind.SAVED -> unlisted("Saved")
      PageKind.ACCOUNT -> unlisted("Account")
      PageKind.SIGNIN -> unlisted("Sign in")
      PageKind.HUB_AI_APPS,
      PageKind.HUB_AI_MODELS,
      PageKind.HUB_AI_AGENTS,
      PageKind.HUB_MCP_SERVERS,
      PageKind.HUB_LIBRARIES,
      PageKind.HUB_AGENT_SKILLS,
      PageKind.HUB_DATASETS,
      PageKind.HUB_PAPERS,
      PageKind.HUB_PROMPTS,
      PageKind.HUB_COURSES -> hub(Hub.of(kind)!!, lists.hub(Hub.of(kind)!!.type))
      PageKind.ADMIN,
      PageKind.ADMIN_PAGE -> PageHead(brand("Back office"), null, null, Robots.NOINDEX)
      PageKind.RESOURCE -> error("a resource page needs its resource")
    }

  fun pageNotFound(): PageHead = PageHead(brand("Page not found"), null, null, Robots.NOINDEX)

  fun resourceNotFound(): PageHead =
    PageHead(brand("Resource not found"), null, null, Robots.NOINDEX)

  fun resource(r: ResourceView): PageHead {
    val path = resourcePath(r.id, r.title)
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
          foundIn = r.foundIn,
        ),
    )
  }

  fun hub(hub: Hub, items: List<Related>): PageHead {
    val newest = items.take(Hub.SIZE)
    val head =
      listed(
        "${hub.label}: newest in the catalog",
        hub.description,
        hub.path,
        newest.takeIf { it.isNotEmpty() }?.let { Listing("Newest change first", it) },
        Hub.SIZE,
      )
    return if (newest.isEmpty()) head.copy(robots = Robots.NOINDEX_FOLLOW)
    else head.copy(jsonLd = listOf(collection(hub, newest)))
  }

  private fun listed(
    name: String,
    description: String,
    path: String,
    listing: Listing? = null,
    max: Int = MAX_LISTED,
  ) =
    PageHead(
      brand(name),
      description,
      path,
      Robots.INDEX,
      body = siteBody(name, description, path, listing, max),
    )

  private fun siteBody(
    heading: String,
    description: String,
    path: String,
    listing: Listing?,
    max: Int = MAX_LISTED,
  ) =
    PageBody(
      heading = heading,
      description = description,
      facts = emptyList(),
      sources = emptyList(),
      related = emptyList(),
      listing = listing?.let { it.copy(items = it.items.take(max)) },
      pages = NAV.filter { it.path != path },
    )

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

  private fun collection(hub: Hub, items: List<Related>): Map<String, Any?> =
    mapOf(
      "@context" to "https://schema.org",
      "@type" to "CollectionPage",
      "name" to hub.label,
      "url" to base + hub.path,
      "mainEntity" to
        mapOf(
          "@type" to "ItemList",
          "numberOfItems" to items.size,
          "itemListElement" to
            items.mapIndexed { i, r -> crumb(i + 1, r.title, base + resourcePath(r.id, r.title)) },
        ),
    )

  private fun crumb(position: Int, name: String, url: String) =
    mapOf("@type" to "ListItem", "position" to position, "name" to name, "item" to url)

  companion object {
    const val BRAND = "searchavo.ai"
    const val TITLE_MAX = 60
    const val DESCRIPTION_MAX = 155
    const val MAX_FACTS = 7
    const val MAX_SOURCES = 5
    const val MAX_RELATED = 8
    const val MAX_LISTED = 20
    const val SOCIAL_IMAGE = "/assets/og-3.png"
    const val SOCIAL_IMAGE_WIDTH = 1200
    const val SOCIAL_IMAGE_HEIGHT = 630
    const val SOCIAL_IMAGE_ALT =
      "$BRAND: the metasearch for the AI ecosystem. One search across models, apps, MCP servers, " +
        "agents and papers."
    val SITE_PAGES =
      listOf(
        PageLink("/", "Search"),
        PageLink("/sources", "Sources"),
        PageLink("/events", "AI events"),
        PageLink("/mission", "Mission"),
        PageLink("/privacy", "Privacy"),
        PageLink("/terms", "Terms"),
        PageLink("/support", "Support $BRAND"),
      )
    val NAV = SITE_PAGES + Hub.entries.map { PageLink(it.path, it.label) }
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

    fun brand(name: String) = "$name · $BRAND"

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
        ResourceType.AWESOME_LIST -> "awesome list"
        ResourceType.OTHER -> "AI resource"
      }

    fun pricingInWords(pricing: Pricing): String =
      when (pricing) {
        Pricing.FREE -> "Free"
        Pricing.FREEMIUM -> "Free plan and paid plans"
        Pricing.PAID -> "Paid"
        Pricing.OPEN_SOURCE_SELF_HOSTED -> "Open source, self-hosted"
      }

    fun cut(text: String, max: Int): String {
      val t = text.replace(Regex("\\s+"), " ").trim()
      if (t.length <= max) return t
      val room = t.substring(0, max - 1)
      val space = room.lastIndexOf(' ')
      val head = if (space >= max / 2) room.substring(0, space) else room
      return head.trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    fun factLine(r: ResourceView): String {
      val parts = mutableListOf(typeInWords(r.type).replaceFirstChar { it.uppercase() })
      r.license?.let { parts += "license $it" }
      r.pricing?.let { parts += pricingInWords(it).lowercase() }
      val sources = r.sources.map { it.name }.distinct()
      val found = if (sources.isEmpty()) "" else ", found on ${sources.joinToString(", ")}"
      return cut("${r.title}: ${parts.joinToString(", ")}$found.", DESCRIPTION_MAX)
    }

    // UC-007 BR-011: the page a crawler gets says what the page of a visitor says.
    private fun ratingInWords(rating: DirectoryRating?): String =
      when {
        rating == null -> "Not rated yet"
        rating.directories == 1 -> "${rating.shownScore()} out of 5, from 1 directory"
        else -> "${rating.shownScore()} out of 5, from ${rating.directories} directories"
      }

    fun facts(r: ResourceView): List<Fact> =
      listOfNotNull(
          Fact("Type", typeInWords(r.type)),
          Fact("Rating", ratingInWords(r.rating)),
          r.license?.let { Fact("License", it) },
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
