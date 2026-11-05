package ai.helper2.scout.usecase

import ai.helper2.scout.Candidate
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.CollectedBatch
import ai.helper2.scout.Collector
import ai.helper2.search.MergedResult
import ai.helper2.search.NOW
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.descriptor
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IndexAcceptedCandidateTest {

  private class Found(override val descriptor: SourceDescriptor) : Collector {
    override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int) =
      CollectedBatch(emptyList(), null, null)
  }

  private class RecordingIndex(private val ids: List<UUID>) : CatalogIndex {
    val indexed = mutableListOf<Pair<List<MergedResult>, String>>()

    override fun index(results: List<MergedResult>, sourceId: String): List<UUID> {
      indexed += results to sourceId
      return ids
    }
  }

  private val resource = UUID.randomUUID()
  private val catalog = RecordingIndex(listOf(resource))
  private val listed = mutableSetOf<String>()
  private var stored: AcceptedCandidate? = null
  private val accept =
    IndexAcceptedCandidate(
      listOf(Found(descriptor("tavily-discovery"))),
      catalog,
      { url -> url.value in listed },
      { details -> stored ?: throw InvalidCandidate("unreadable: $details") },
      Clock.fixed(NOW, ZoneOffset.UTC),
    )

  private fun candidate(
    sourceId: String = "tavily-discovery",
    url: String = "https://www.openwebui.com/?utm_source=x",
    title: String = " Open WebUI ",
    snippet: String? = "Self-hosted AI platform.",
    type: ResourceType = ResourceType.PRODUCT,
    tags: Set<String> = setOf("self-hosted", "local-ai"),
  ) = AcceptedCandidate(sourceId, url, title, snippet, type, tags)

  @Test
  fun `indexes the candidate as a record of the collector that found it`() {
    assertEquals(resource, accept.index(candidate()))

    val (results, sourceId) = catalog.indexed.single()
    assertEquals("tavily-discovery", sourceId)
    val record = results.single().hits.single()
    assertEquals("https://openwebui.com", record.canonicalUrl.value)
    assertEquals("Open WebUI", record.title)
    assertEquals("Self-hosted AI platform.", record.description)
    assertEquals(ResourceType.PRODUCT, record.type)
    assertEquals(NOW, record.retrievedAt)
    assertEquals(setOf("self-hosted", "local-ai"), record.tags)
  }

  @Test
  fun `a blank excerpt is no description`() {
    accept.index(candidate(snippet = "  "))
    assertNull(catalog.indexed.single().first.single().hits.single().description)
  }

  @Test
  fun `refuses what the review item cannot have proposed`() {
    assertThrows<InvalidCandidate> { accept.index(candidate(sourceId = "unknown")) }
    assertThrows<InvalidCandidate> { accept.index(candidate(url = "javascript:alert(1)")) }
    assertThrows<InvalidCandidate> { accept.index(candidate(title = " ")) }
    assertThrows<InvalidCandidate> { accept.index(candidate(type = ResourceType.NEWS)) }
    assertThrows<InvalidCandidate> { accept.index(candidate(type = ResourceType.COURSE)) }
    assertThrows<InvalidCandidate> { accept.index(candidate(tags = setOf("Not A Tag"))) }
    assertEquals(0, catalog.indexed.size)
  }

  @Test
  fun `a candidate takes as many tags as a collector gives it, and no more`() {
    val most = (1..Candidate.MAX_TAGS).map { "category-$it" }.toSet()
    accept.index(candidate(tags = most))
    assertEquals(most, catalog.indexed.single().first.single().hits.single().tags)
    assertThrows<InvalidCandidate> { accept.index(candidate(tags = most + "one-more")) }
  }

  @Test
  fun `a candidate the catalog did not change is refused, so the item stays open`() {
    val unchanged =
      IndexAcceptedCandidate(
        listOf(Found(descriptor("tavily-discovery"))),
        RecordingIndex(emptyList()),
        { false },
        { candidate() },
        Clock.fixed(NOW, ZoneOffset.UTC),
      )
    assertThrows<InvalidCandidate> { unchanged.index(candidate()) }
  }

  @Test
  fun `accepting reads the stored candidate and indexes it`() {
    stored = candidate()
    assertEquals(resource, accept.accept("{...}"))
    assertEquals(1, catalog.indexed.size)
  }

  @Test
  fun `a page another source listed meanwhile is left as it is`() {
    stored = candidate()
    listed += "https://openwebui.com"
    assertNull(accept.accept("{...}"))
    assertEquals(0, catalog.indexed.size)
  }

  @Test
  fun `a listed page is still checked first, so a bad candidate is refused`() {
    stored = candidate(type = ResourceType.NEWS)
    listed += "https://openwebui.com"
    assertThrows<InvalidCandidate> { accept.accept("{...}") }
    stored = candidate(sourceId = "unknown")
    assertThrows<InvalidCandidate> { accept.accept("{...}") }
  }

  @Test
  fun `unreadable details are refused`() {
    assertThrows<InvalidCandidate> { accept.accept("not json") }
  }
}
