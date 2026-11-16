package ai.helper2.guide

import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
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

    val from = origins.of("Chat with your PDF files locally, with no account", result, emptyList())

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
      origins.of("answers questions about PDF documents on your own machine", result, emptyList())

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
      origins.of("answers questions about PDF documents on your own machine", result, emptyList())

    assertEquals(QuotedFrom("github", URI(repo)), from)
  }

  @Test
  fun `a catalog row is not a source, the stored record that holds the text is`() {
    val row = hit("catalog", repo, title = "acme/pdfchat", description = npmText)
    val result = merger.merge(listOf(row), contexts("catalog")).single()
    val catalogRow =
      result.copy(hits = result.hits.map { it.copy(externalId = result.id.toString()) })
    val records = listOf(hit("npm", npm, title = "pdfchat", repo = repo, description = npmText))

    val from = origins.of("Chat with your PDF files locally, with no account", catalogRow, records)

    assertEquals(QuotedFrom("npm", URI(npm)), from)
  }

  @Test
  fun `an AI-written description is never a source of a quote`() {
    val written =
      hit("npm", npm, title = "pdfchat", description = npmText)
        .copy(descriptionOrigin = ValueOrigin.AI_SUMMARY)
    val result = merger.merge(listOf(written), contexts("npm")).single()

    assertNull(origins.of("Chat with your PDF files locally, with no account", result, emptyList()))
  }

  @Test
  fun `a quote no record holds has no source`() {
    val result =
      merger.merge(listOf(hit("github", repo, description = repoText)), contexts("github")).single()

    assertNull(
      origins.of("a sentence that no source ever wrote about this tool", result, emptyList())
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

    assertNull(origins.of("Runs on a local", result, emptyList()))
  }
}
