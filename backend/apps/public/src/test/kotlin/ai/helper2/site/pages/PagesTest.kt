package ai.helper2.site.pages

import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class PagesTest {

  private val pages = Pages("https://helper2.ai")
  private val id = UUID.fromString("8a0e6c1e-2f43-5b6e-9d0a-1f1e2d3c4b5a")

  private fun resource(
    type: ResourceType = ResourceType.MCP_SERVER,
    title: String = "postgres-mcp",
    description: String? = "An MCP server that lets an agent query Postgres.",
    origin: ValueOrigin = ValueOrigin.SOURCE_PROVIDED,
    license: String? = "MIT",
    related: List<Related> = emptyList(),
  ) =
    ResourceView(
      id = id,
      title = title,
      type = type,
      description = description,
      descriptionOrigin = origin,
      url = URI.create("https://github.com/acme/postgres-mcp"),
      license = license,
      pricing = null,
      platforms = emptySet(),
      published = null,
      eventStart = null,
      eventEnd = null,
      location = null,
      sources = listOf(Link("GitHub", URI.create("https://github.com/acme/postgres-mcp"))),
      related = related,
    )

  @Test
  fun `a resource page has its own title, the source's description and an absolute canonical`() {
    val head = pages.resource(resource())
    assertEquals("postgres-mcp: MCP server · helper2.ai", head.title)
    assertEquals("An MCP server that lets an agent query Postgres.", head.description)
    assertEquals("/resource/$id/postgres-mcp", head.canonicalPath)
    assertEquals(Robots.INDEX, head.robots)
    assertEquals("SoftwareSourceCode", head.jsonLd.first()["@type"])
    assertEquals("https://helper2.ai/resource/$id/postgres-mcp", head.jsonLd.first()["url"])
    assertEquals("BreadcrumbList", head.jsonLd.last()["@type"])
  }

  @Test
  fun `every page names the product in lower case and in full`() {
    val wrongName = Regex("Helper2|helper2(?!\\.ai)")
    val fixed = PageKind.entries.filter { it != PageKind.RESOURCE }.map { pages.of(it) }
    val heads = fixed + pages.pageNotFound() + pages.resourceNotFound() + pages.resource(resource())

    heads.forEach { head ->
      val words = listOfNotNull(head.title, head.description) + head.jsonLd.map { it.toString() }
      words.forEach { assertFalse(wrongName.containsMatchIn(it), it) }
      assertTrue(head.title.endsWith(" · helper2.ai") || head.title.startsWith("helper2.ai: "))
    }
    assertEquals("helper2.ai", pages.home().jsonLd.first()["name"])
  }

  @Test
  fun `an AI-written description never reaches the meta tag or JSON-LD`() {
    val head = pages.resource(resource(origin = ValueOrigin.AI_SUMMARY, description = "AI words."))
    assertEquals("postgres-mcp: MCP server, license MIT, found on GitHub.", head.description)
    assertFalse(head.jsonLd.first().containsKey("description"))
    assertNull(head.body?.description)
    assertEquals(Robots.NOINDEX_FOLLOW, head.robots)
  }

  @Test
  fun `a long title drops the type, then is cut`() {
    assertEquals(
      "A rather long resource title that goes on · helper2.ai",
      Pages.resourceTitle(resource(title = "A rather long resource title that goes on")),
    )
    val cut = Pages.resourceTitle(resource(title = "word ".repeat(30).trim()))
    assertTrue(cut.length <= Pages.TITLE_MAX)
    assertTrue(cut.endsWith("… · helper2.ai"))
  }

  @Test
  fun `a description is cut at a word, below 155 characters`() {
    val long = "Lorem ipsum dolor sit amet, ".repeat(10)
    val cut = Pages.cut(long, Pages.DESCRIPTION_MAX)
    assertTrue(cut.length <= Pages.DESCRIPTION_MAX)
    assertTrue(cut.endsWith("…"))
    assertFalse(cut.contains(",…"))
  }

  @Test
  fun `index-worthy types need a fact, news, articles and thin pages do not count`() {
    assertTrue(IndexPolicy.indexWorthy(resource()))
    assertFalse(IndexPolicy.indexWorthy(resource(license = null)))
    assertTrue(
      IndexPolicy.indexWorthy(
        resource(license = null, related = listOf(Related(UUID.randomUUID(), "pgvector")))
      )
    )
    assertFalse(IndexPolicy.indexWorthy(resource(type = ResourceType.NEWS)))
    assertFalse(IndexPolicy.indexWorthy(resource(type = ResourceType.ARTICLE)))
    assertFalse(IndexPolicy.indexWorthy(resource(type = ResourceType.OTHER)))
    assertFalse(IndexPolicy.indexWorthy(resource(description = "  ")))
  }

  @Test
  fun `a prompt counts only with its full text`() {
    assertFalse(
      IndexPolicy.indexWorthy(resource(type = ResourceType.PROMPT, description = "Summarise this."))
    )
    assertTrue(
      IndexPolicy.indexWorthy(
        resource(type = ResourceType.PROMPT, description = "x".repeat(200), license = null)
      )
    )
  }

  @Test
  fun `the body lists facts, sources and related resources`() {
    val related = listOf(Related(UUID.randomUUID(), "pgvector"))
    val body = pages.resource(resource(related = related)).body!!
    assertEquals("postgres-mcp", body.heading)
    assertEquals(listOf(Fact("Type", "MCP server"), Fact("License", "MIT")), body.facts)
    assertEquals(related, body.related)
  }

  @Test
  fun `an event carries its dates and place`() {
    val event =
      resource(type = ResourceType.EVENT)
        .copy(
          eventStart = Instant.parse("2026-12-01T00:00:00Z"),
          eventEnd = Instant.parse("2026-12-02T00:00:00Z"),
          location = "Zurich",
          pricing = Pricing.PAID,
        )
    val data = JsonLd.of(event, "https://helper2.ai/resource/$id")!!
    assertEquals("Event", data["@type"])
    assertEquals("2026-12-01T00:00:00Z", data["startDate"])
    assertEquals(mapOf("@type" to "Place", "name" to "Zurich"), data["location"])
    assertTrue(Pages.facts(event).contains(Fact("When", "1 Dec 2026 to 2 Dec 2026")))
  }

  @Test
  fun `static pages - listed ones are indexed, personal ones are not, the back office never`() {
    assertEquals(Robots.INDEX, pages.of(PageKind.HOME).robots)
    assertEquals("/sources", pages.of(PageKind.SOURCES).canonicalPath)
    assertEquals(Robots.NOINDEX_FOLLOW, pages.of(PageKind.SEARCH).robots)
    assertNull(pages.of(PageKind.SAVED).canonicalPath)
    assertEquals(Robots.NOINDEX, pages.of(PageKind.ADMIN_PAGE).robots)
    assertEquals(Robots.NOINDEX, pages.pageNotFound().robots)
    assertEquals("/events", pages.of(PageKind.EVENTS).canonicalPath)
    assertEquals(Robots.INDEX, pages.of(PageKind.EVENTS).robots)
    assertEquals(Robots.NOINDEX_FOLLOW, pages.of(PageKind.NEWS).robots)
  }

  @Test
  fun `addresses map to pages, a bad id or an unknown path does not`() {
    assertEquals(PageAddress.Page(PageKind.HOME), PageAddress.of("/"))
    assertEquals(PageAddress.Page(PageKind.SOURCES), PageAddress.of("/sources"))
    assertEquals(PageAddress.Page(PageKind.NEWS), PageAddress.of("/news"))
    assertEquals(PageAddress.Page(PageKind.EVENTS), PageAddress.of("/events"))
    assertEquals(PageAddress.Page(PageKind.SIGNIN), PageAddress.of("/signin"))
    assertEquals(PageAddress.Page(PageKind.ADMIN_PAGE), PageAddress.of("/admin/review"))
    assertEquals(PageAddress.Resource(id.toString(), id), PageAddress.of("/resource/$id"))
    assertEquals(PageAddress.Resource("nope", null), PageAddress.of("/resource/nope"))
    assertEquals(PageAddress.Unknown, PageAddress.of("/sources/"))
    assertEquals(
      PageAddress.Resource(id.toString(), id, "postgres-mcp"),
      PageAddress.of("/resource/$id/postgres-mcp"),
    )
    assertEquals(PageAddress.Unknown, PageAddress.of("/resource/$id/postgres-mcp/extra"))
    assertEquals(PageAddress.Unknown, PageAddress.of("/nope"))
  }

  @Test
  fun `a slug is the title in plain lowercase words, the same as the frontend's`() {
    val vectors =
      listOf(
        "postgres-mcp" to "postgres-mcp",
        "LangChain: Build LLM apps" to "langchain-build-llm-apps",
        "Café Crème 2.0" to "cafe-creme-2-0",
        "  --Hello__World--  " to "hello-world",
        "日本語" to "",
        "" to "",
        "word ".repeat(20).trim() to "word-word-word-word-word-word-word-word-word-word-word-word",
        "a".repeat(70) to "a".repeat(60),
      )
    for ((title, slug) in vectors) assertEquals(slug, Slug.of(title), title)
    assertEquals("/resource/$id/postgres-mcp", resourcePath(id, "postgres-mcp"))
    assertEquals("/resource/$id", resourcePath(id, "日本語"))
  }

  @Test
  fun `an unlisted page says noindex whatever it was`() {
    assertEquals(Robots.NOINDEX, pages.of(PageKind.SOURCES).unlisted().robots)
    assertEquals(Robots.NOINDEX, pages.resource(resource()).unlisted().robots)
    assertEquals("/sources", pages.of(PageKind.SOURCES).unlisted().canonicalPath)
  }

  @Test
  fun `the home page says what the app's home says before the app loads`() {
    val en =
      JsonMapper.builder()
        .build()
        .readTree(Files.readString(Path.of("../frontend/src/i18n/locales/en.json")))
    assertEquals(en.path("home").path("lede").asString(), pages.home().description)
  }
}
