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

  /**
   * As the real collectors declare them: a discovery category proposes its product type, LinkedIn
   * reading too.
   */
  private val selfHosted =
    descriptor("discovery-self-hosted")
      .copy(resourceTypes = setOf(ResourceType.PRODUCT, ResourceType.ARTICLE))
  private val agentPlatforms =
    descriptor("discovery-agent-platforms")
      .copy(resourceTypes = setOf(ResourceType.AGENT, ResourceType.ARTICLE))
  private val linkedIn =
    descriptor("linkedin-scout")
      .copy(
        resourceTypes =
          setOf(ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PRODUCT, ResourceType.EVENT)
      )

  private val resource = UUID.randomUUID()
  private val catalog = RecordingIndex(listOf(resource))
  private val listed = mutableSetOf<String>()
  private var stored: AcceptedCandidate? = null
  private val accept =
    IndexAcceptedCandidate(
      listOf(Found(selfHosted), Found(agentPlatforms), Found(linkedIn)),
      catalog,
      { url -> url.value in listed },
      { details -> stored ?: throw InvalidCandidate("unreadable: $details") },
      Clock.fixed(NOW, ZoneOffset.UTC),
    )

  private fun candidate(
    sourceId: String = "discovery-self-hosted",
    url: String = "https://www.openwebui.com/?utm_source=x",
    title: String = " Open WebUI ",
    snippet: String? = "Self-hosted AI platform.",
    type: ResourceType = ResourceType.PRODUCT,
    tags: Set<String> = setOf("self-hosted", "local-ai"),
    facts: Map<String, Any> = emptyMap(),
    finders: List<String> = emptyList(),
  ) = AcceptedCandidate(sourceId, url, title, snippet, type, tags, facts, finders)

  @Test
  fun `indexes the candidate as a record of the collector that found it`() {
    assertEquals(resource, accept.index(candidate()))

    val (results, sourceId) = catalog.indexed.single()
    assertEquals("discovery-self-hosted", sourceId)
    val record = results.single().hits.single()
    assertEquals("https://openwebui.com", record.canonicalUrl.value)
    assertEquals("Open WebUI", record.title)
    assertEquals("Self-hosted AI platform.", record.description)
    assertEquals(ResourceType.PRODUCT, record.type)
    assertEquals(NOW, record.retrievedAt)
    assertEquals(setOf("self-hosted", "local-ai"), record.tags)
  }

  @Test
  fun `keeps what the searches said and the whole excerpt as evidence`() {
    val facts =
      mapOf(
        "discovery" to mapOf("category" to "self-hosted"),
        "categories" to listOf("self-hosted"),
      )
    accept.index(candidate(snippet = "x".repeat(3000), facts = facts))

    val record = catalog.indexed.single().first.single().hits.single()
    assertEquals(facts, record.facts)
    assertEquals(2400, record.description?.length)
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
  fun `a type the collector does not declare is refused`() {
    assertThrows<InvalidCandidate> { accept.index(candidate(type = ResourceType.EVENT)) }
    assertThrows<InvalidCandidate> {
      accept.index(candidate(sourceId = "linkedin-scout", type = ResourceType.AGENT))
    }
    assertEquals(0, catalog.indexed.size)
  }

  /** ADR 0026: the admin said what the page is; it is still a candidate type, never a course. */
  @Test
  fun `a type an admin chose need not be declared, but must be a candidate type`() {
    accept.index(candidate(type = ResourceType.EVENT).copy(typeChosenByAdmin = true))
    assertEquals(ResourceType.EVENT, catalog.indexed.single().first.single().hits.single().type)
    assertThrows<InvalidCandidate> {
      accept.index(candidate(type = ResourceType.COURSE).copy(typeChosenByAdmin = true))
    }
  }

  @Test
  fun `a merged candidate may have the type of any collector that found it`() {
    val merged =
      candidate(
        type = ResourceType.AGENT,
        finders = listOf("discovery-self-hosted", "discovery-agent-platforms"),
      )
    accept.index(merged)
    val (results, sourceId) = catalog.indexed.single()
    assertEquals("discovery-self-hosted", sourceId)
    assertEquals(ResourceType.AGENT, results.single().hits.single().type)
    assertThrows<InvalidCandidate> {
      accept.index(merged.copy(type = ResourceType.EVENT))
    }
  }

  @Test
  fun `a LinkedIn post, article or event is indexed with its reading type`() {
    for (type in listOf(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT)) {
      accept.index(
        candidate(
          sourceId = "linkedin-scout",
          url = "https://www.linkedin.com/pulse/agents-in-production-${type.name.lowercase()}",
          title = "Agents in production",
          type = type,
          tags = setOf("linkedin"),
        )
      )
    }
    val records =
      catalog.indexed.map { (results, sourceId) ->
        assertEquals("linkedin-scout", sourceId)
        results.single().hits.single()
      }
    assertEquals(
      listOf(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
      records.map { it.type },
    )
    assertEquals(setOf("linkedin"), records.first().tags)
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
        listOf(Found(descriptor("discovery-self-hosted"))),
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
