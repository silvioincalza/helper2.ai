package ai.searchavo.scout.usecase

import ai.searchavo.scout.Candidate
import ai.searchavo.scout.CandidateSignals
import ai.searchavo.scout.CatalogIndex
import ai.searchavo.scout.CollectedBatch
import ai.searchavo.scout.Collector
import ai.searchavo.search.MergedResult
import ai.searchavo.search.NOW
import ai.searchavo.search.PopularityKind
import ai.searchavo.search.PopularityMetric
import ai.searchavo.search.SourceDescriptor
import ai.searchavo.search.Vitality
import ai.searchavo.search.descriptor
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IndexAcceptedCandidateTest {

  private class Found(override val descriptor: SourceDescriptor) : Collector {
    override fun runEvery(): Duration = Duration.ZERO

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

  private val sdks = descriptor("github-sdks").copy(resourceTypes = setOf(ResourceType.LIBRARY))
  private val lists =
    descriptor("github-awesome")
      .copy(resourceTypes = setOf(ResourceType.PRODUCT, ResourceType.COURSE, ResourceType.ARTICLE))

  private val resource = UUID.randomUUID()
  private val catalog = RecordingIndex(listOf(resource))
  private val listed = mutableMapOf<String, ListedResource>()
  private val openWebUi = ListedResource(UUID.randomUUID(), "Open WebUI")
  private var stored: AcceptedCandidate? = null
  private val named = mutableListOf<Triple<UUID, String, NamingList>>()
  private val accept =
    IndexAcceptedCandidate(
      listOf(Found(selfHosted), Found(agentPlatforms), Found(linkedIn), Found(sdks), Found(lists)),
      catalog,
      { url -> listed[url.value] },
      { details -> stored ?: throw InvalidCandidate("unreadable: $details") },
      { id, title, list -> named += Triple(id, title, list) },
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

  private fun sdkCandidate(facts: Map<String, Any>) =
    candidate(
      sourceId = "github-sdks",
      url = "https://github.com/anthropics/anthropic-sdk-java",
      title = "anthropics/anthropic-sdk-java",
      type = ResourceType.LIBRARY,
      tags = setOf("java"),
      facts = facts,
    )

  @Test
  fun `a non-standard licence is left out`() {
    accept.index(sdkCandidate(mapOf(PackageFacts.KEY to mapOf("license" to "non-standard"))))

    assertNull(catalog.indexed.single().first.single().hits.single().license)
  }

  @Test
  fun `a package found for the repo gives the record its licence and latest release`() {
    val released = NOW.minusSeconds(86_400)
    val facts =
      mapOf<String, Any>(
        PackageFacts.KEY to
          mapOf("license" to "Apache-2.0 OR MIT", "latestRelease" to released.toString())
      )

    accept.index(sdkCandidate(facts))

    val record = catalog.indexed.single().first.single().hits.single()
    assertEquals("Apache-2.0 OR MIT", record.license)
    assertEquals(released, record.updated.value)
    assertEquals("github-sdks", record.updated.sourceId)
  }

  @Test
  fun `an accepted SDK keeps its stars and its last push`() {
    val pushed = NOW.minusSeconds(3_600)
    val signals =
      CandidateSignals(stars = 10_689, lastPush = pushed, archived = false, fork = false)

    accept.index(sdkCandidate(emptyMap()).copy(signals = signals))

    val record = catalog.indexed.single().first.single().hits.single()
    assertEquals(
      listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 10_689, "github-sdks", NOW)),
      record.popularity,
    )
    assertEquals(pushed, record.updated.value)
    assertEquals("github-sdks", record.updated.sourceId)
    assertEquals(Vitality(archived = false, fork = false), record.vitality)
  }

  @Test
  fun `the newer of the last push and the latest release is the last change`() {
    val released = NOW.minusSeconds(86_400)
    val pushedBefore = CandidateSignals(lastPush = released.minusSeconds(60))
    val pushedAfter = CandidateSignals(lastPush = released.plusSeconds(60))
    val facts =
      mapOf<String, Any>(PackageFacts.KEY to mapOf("latestRelease" to released.toString()))

    accept.index(sdkCandidate(facts).copy(signals = pushedBefore))
    accept.index(sdkCandidate(facts).copy(signals = pushedAfter))

    val updated = catalog.indexed.map { it.first.single().hits.single().updated.value }
    assertEquals(listOf(released, released.plusSeconds(60)), updated)
  }

  @Test
  fun `a candidate without signals states no popularity, no last push and no vitality`() {
    accept.index(sdkCandidate(emptyMap()))

    val record = catalog.indexed.single().first.single().hits.single()
    assertEquals(emptyList(), record.popularity)
    assertNull(record.updated.value)
    assertEquals(Vitality.UNKNOWN, record.vitality)
  }

  @Test
  fun `a licence that is not an SPDX expression and an unreadable date are left out`() {
    val facts =
      mapOf<String, Any>(
        PackageFacts.KEY to mapOf("license" to "see LICENSE.txt; free", "latestRelease" to "soon")
      )

    accept.index(sdkCandidate(facts))

    val record = catalog.indexed.single().first.single().hits.single()
    assertNull(record.license)
    assertNull(record.updated.value)
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

  @Test
  fun `a type an admin chose need not be declared, but must be a candidate type`() {
    accept.index(candidate(type = ResourceType.EVENT).copy(typeChosenByAdmin = true))
    assertEquals(ResourceType.EVENT, catalog.indexed.single().first.single().hits.single().type)
    assertThrows<InvalidCandidate> {
      accept.index(candidate(type = ResourceType.PAPER).copy(typeChosenByAdmin = true))
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
        { null },
        { candidate() },
        { _, _, _ -> },
        Clock.fixed(NOW, ZoneOffset.UTC),
      )
    assertThrows<InvalidCandidate> { unchanged.index(candidate()) }
  }

  @Test
  fun `accepting reads the stored candidate and indexes it`() {
    stored = candidate()
    assertEquals(AcceptOutcome.Indexed(resource), accept.accept("{...}"))
    assertEquals(1, catalog.indexed.size)
  }

  @Test
  fun `a page another source listed meanwhile adds nothing and names the listed resource`() {
    stored = candidate()
    listed["https://openwebui.com"] = openWebUi
    assertEquals(AcceptOutcome.AlreadyListed(openWebUi), accept.accept("{...}"))
    assertEquals(0, catalog.indexed.size)
  }

  @Test
  fun `a listed page is still checked first, so a bad candidate is refused`() {
    stored = candidate(type = ResourceType.NEWS)
    listed["https://openwebui.com"] = openWebUi
    assertThrows<InvalidCandidate> { accept.accept("{...}") }
    stored = candidate(sourceId = "unknown")
    assertThrows<InvalidCandidate> { accept.accept("{...}") }
  }

  private fun namedByLists(vararg finders: Map<String, Any>) =
    candidate(sourceId = "github-awesome", facts = mapOf("foundBy" to finders.toList()))

  private fun finder(list: String, section: String, directoryList: String? = null) =
    mapOf(
      "sourceId" to "github-awesome",
      "awesome" to
        listOfNotNull(
            "list" to list,
            "section" to section,
            directoryList?.let { "directoryList" to it },
          )
          .toMap(),
    )

  @Test
  fun `UC-030 BR-008 an accepted product is named by the directory list that proposed it`() {
    stored =
      namedByLists(
        finder("https://futurepedia.io/best-ai-coding-tools", "Editors", "Best AI coding tools")
      )

    accept.accept("{...}")

    assertEquals(
      listOf(
        Triple(
          resource,
          "Open WebUI",
          NamingList(
            CanonicalUrl.of("https://futurepedia.io/best-ai-coding-tools")!!,
            "Editors",
            "Best AI coding tools",
          ),
        )
      ),
      named,
    )
  }

  @Test
  fun `UC-030 BR-008 an accepted product is named by the curated list that proposed it`() {
    stored = namedByLists(finder("https://github.com/acme/awesome-llm", "Tools > Chat"))

    accept.accept("{...}")

    assertEquals(
      NamingList(CanonicalUrl.of("https://github.com/acme/awesome-llm")!!, "Tools > Chat", null),
      named.single().third,
    )
  }

  @Test
  fun `UC-030 BR-008 a page already in the catalog is named by the list too`() {
    stored = namedByLists(finder("https://github.com/acme/awesome-llm", "Tools"))
    listed["https://openwebui.com"] = openWebUi

    accept.accept("{...}")

    assertEquals(openWebUi.id, named.single().first)
  }

  @Test
  fun `UC-030 BR-008 a candidate no list proposed, or with an unreadable list, is named by none`() {
    stored =
      candidate(facts = mapOf("foundBy" to listOf(mapOf("sourceId" to "discovery-self-hosted"))))
    accept.accept("{...}")
    stored =
      namedByLists(mapOf("sourceId" to "github-awesome", "awesome" to mapOf("list" to "not a url")))
    accept.accept("{...}")
    stored = namedByLists(mapOf("sourceId" to "github-awesome", "awesome" to "text"))
    accept.accept("{...}")

    assertEquals(emptyList(), named)
  }

  @Test
  fun `unreadable details are refused`() {
    assertThrows<InvalidCandidate> { accept.accept("not json") }
  }
}
