package ai.helper2.guide

import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceId
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuoteOriginsTest {
  private val merger = ResultMerger()
  private val origins = QuoteOrigins()

  private val repo = "https://github.com/acme/pdfchat"
  private val npm = "https://www.npmjs.com/package/pdfchat"
  private val npmText =
    "Chat with your PDF files locally, with no account and no upload to a server."
  private val repoText =
    "A desktop app that answers questions about PDF documents on your own machine."

  @Test
  fun `a quote from the package text names the package page, not the repository`() {
    val result =
      merger
        .merge(
          listOf(hit("npm", npm, title = "pdfchat", repo = repo, description = npmText)),
          contexts("npm"),
        )
        .single()
    assertEquals(repo, result.canonicalUrl.value)

    val from = origins.of("Chat with your PDF files locally, with no account", result, emptyMap())

    assertEquals(QuotedFrom("npm", URI(npm)), from)
  }

  @Test
  fun `once the repository record is there, its own text names the repository`() {
    val hits =
      listOf(
        hit("npm", npm, title = "pdfchat", repo = repo, description = npmText),
        hit("github", repo, title = "acme/pdfchat", description = repoText),
      )
    val result = merger.merge(hits, contexts("npm", "github")).single()

    val from =
      origins.of("answers questions about PDF documents on your own machine", result, emptyMap())

    assertEquals(QuotedFrom("github", URI(repo)), from)
  }

  @Test
  fun `the record at the resource's own address wins when two records say the same`() {
    val hits =
      listOf(
        hit("npm", npm, title = "pdfchat", repo = repo, description = repoText),
        hit("github", repo, title = "acme/pdfchat", description = repoText),
      )
    val result = merger.merge(hits, contexts("npm", "github")).single()

    val from =
      origins.of("answers questions about PDF documents on your own machine", result, emptyMap())

    assertEquals(QuotedFrom("github", URI(repo)), from)
  }

  @Test
  fun `a catalog row is not a source, the stored record that holds the text is`() {
    val id = ResourceId.of(CanonicalUrl.of(repo)!!)
    val row =
      hit("catalog", repo, title = "acme/pdfchat", description = npmText)
        .copy(externalId = id.toString())
    val result = merger.merge(listOf(row), contexts("catalog")).single()
    val records =
      mapOf(id to listOf(hit("npm", npm, title = "pdfchat", repo = repo, description = npmText)))

    val from = origins.of("Chat with your PDF files locally, with no account", result, records)

    assertEquals(QuotedFrom("npm", URI(npm)), from)
  }

  @Test
  fun `an AI-written description is never a source of a quote`() {
    val written =
      hit("npm", npm, title = "pdfchat", description = npmText)
        .copy(descriptionOrigin = ValueOrigin.AI_SUMMARY)
    val result = merger.merge(listOf(written), contexts("npm")).single()

    assertNull(origins.of("Chat with your PDF files locally, with no account", result, emptyMap()))
  }

  @Test
  fun `a quote no record holds has no source`() {
    val result =
      merger.merge(listOf(hit("github", repo, description = repoText)), contexts("github")).single()

    assertNull(
      origins.of("a sentence that no source ever wrote about this tool", result, emptyMap())
    )
  }

  @Test
  fun `the quote is matched on whole words, as the evidence gate does`() {
    val result =
      merger
        .merge(
          listOf(hit("github", repo, description = "Runs on a locale server.")),
          contexts("github"),
        )
        .single()

    assertNull(origins.of("Runs on a local", result, emptyMap()))
  }

  @Test
  fun `a catalog row merged under another identity is still not a source, its stored records are`() {
    val pkgUrl = "https://www.npmjs.com/package/pdfchat"
    val pkgId = ResourceId.of(CanonicalUrl.of(pkgUrl)!!)
    val row =
      hit(
          "curated",
          pkgUrl,
          title = "pdfchat",
          repo = repo,
          description = "Curated words nobody else wrote here",
        )
        .copy(externalId = pkgId.toString())
    val live = hit("github", repo, title = "acme/pdfchat", description = repoText)
    val result = merger.merge(listOf(row, live), contexts("curated", "github")).single()
    assertEquals(setOf(result.id, pkgId), origins.storedResourcesOf(result))

    assertNull(origins.of("Curated words nobody else wrote here", result, emptyMap()))
    val stored =
      mapOf(pkgId to listOf(hit("npm", pkgUrl, title = "pdfchat", description = npmText)))
    assertEquals(
      QuotedFrom("npm", URI(pkgUrl)),
      origins.of("Chat with your PDF files locally, with no account", result, stored),
    )
  }

  @Test
  fun `a quote of a Hacker News title names the story's page, not the article it links`() {
    val story =
      hit(
          "hacker-news",
          "https://example.com/post",
          title = "Show HN: PDF chat that runs on your own laptop",
        )
        .copy(externalId = "41234567")
    val result = merger.merge(listOf(story), contexts("hacker-news")).single()

    assertEquals(
      QuotedFrom("hacker-news", URI("https://news.ycombinator.com/item?id=41234567")),
      origins.of("Show HN: PDF chat that runs on your own laptop", result, emptyMap()),
    )
  }

  @Test
  fun `a quote of an MCP Registry record names its Registry entry, not the repository it links`() {
    val server =
      hit(
        "mcp-registry",
        repo,
        title = "PDF chat",
        type = ResourceType.MCP_SERVER,
        description = npmText,
        name = "io.github.acme/pdfchat",
      )
    val result = merger.merge(listOf(server), contexts("mcp-registry")).single()

    assertEquals(
      QuotedFrom(
        "mcp-registry",
        URI(
          "https://registry.modelcontextprotocol.io/v0.1/servers/io.github.acme%2Fpdfchat/versions/latest"
        ),
      ),
      QuoteOrigins(mcpRegistry = "https://registry.modelcontextprotocol.io")
        .of(npmText, result, emptyMap()),
    )
  }

  @Test
  fun `a curated record is editorial text, never the page that holds a quote`() {
    val id = ResourceId.of(CanonicalUrl.of(repo)!!)
    val result =
      merger.merge(listOf(hit("github", repo, description = repoText)), contexts("github")).single()
    val curated =
      mapOf(id to listOf(hit("curated", repo, title = "PDF chat for teams that keep data at home")))

    assertNull(origins.of("PDF chat for teams that keep data at home", result, curated))
  }
}
