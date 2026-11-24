package ai.searchavo.search

import ai.searchavo.shared.Audience
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.EventFormat
import ai.searchavo.shared.EventKind
import ai.searchavo.shared.Pricing
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.TechLevel
import ai.searchavo.shared.ValueOrigin
import java.net.URI
import java.time.Instant
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceHitTest {

  private val now = Instant.parse("2026-10-27T10:00:00Z")
  private val earlier = Instant.parse("2026-09-01T00:00:00Z")
  private val url = CanonicalUrl.of("https://github.com/a/tool")!!

  private val onlyWhatEveryHitStates =
    SourceHit(
      sourceId = "github",
      externalId = "a/tool",
      url = URI.create("https://github.com/a/tool"),
      canonicalUrl = url,
      title = "a/tool",
      type = ResourceType.LIBRARY,
      retrievedAt = now,
    )

  private val builtByName =
    onlyWhatEveryHitStates
      .withRepository(url)
      .withDescription("A tool.", ValueOrigin.AI_SUMMARY)
      .withAudience(Audience.BUILD_WITH_AI)
      .withTechLevel(TechLevel.ADVANCED)
      .withUseCases(setOf("agents"))
      .withTags(setOf("mcp"))
      .withLicense("MIT", true)
      .withPricing(Pricing.FREE)
      .withPlatforms(setOf("linux"))
      .withPublished(DateFact.fromSource(earlier, "github", now))
      .withUpdated(DateFact.fromSource(earlier.plusSeconds(60), "github", now))
      .withPopularity(listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 42, "github", now)))
      .withRank(3)
      .withEventDays(
        DateFact.fromSource(earlier.plusSeconds(120), "github", now),
        DateFact.fromSource(earlier.plusSeconds(180), "github", now),
      )
      .withLocation("Zurich")
      .withEventKind(EventKind.MEETUP)
      .withEventFormat(EventFormat.HYBRID)
      .withFacts(mapOf("stars" to 42))
      .withCategories(setOf("agents"))
      .withFoundIn(ListMatch("Awesome", "Tools > Agents"))
      .withCuratedFields(setOf("title"))
      .withVitality(Vitality(archived = true, fork = true, deprecated = true, running = true))

  @Test
  fun `a hit built by name is the one built by position`() {
    assertEquals(
      SourceHit(
        sourceId = "github",
        externalId = "a/tool",
        url = URI.create("https://github.com/a/tool"),
        canonicalUrl = url,
        repositoryUrl = url,
        title = "a/tool",
        description = "A tool.",
        descriptionOrigin = ValueOrigin.AI_SUMMARY,
        type = ResourceType.LIBRARY,
        audience = Audience.BUILD_WITH_AI,
        techLevel = TechLevel.ADVANCED,
        useCases = setOf("agents"),
        tags = setOf("mcp"),
        license = "MIT",
        pricing = Pricing.FREE,
        platforms = setOf("linux"),
        openSource = true,
        published = DateFact.fromSource(earlier, "github", now),
        updated = DateFact.fromSource(earlier.plusSeconds(60), "github", now),
        popularity = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 42, "github", now)),
        retrievedAt = now,
        rank = 3,
        eventStart = DateFact.fromSource(earlier.plusSeconds(120), "github", now),
        eventEnd = DateFact.fromSource(earlier.plusSeconds(180), "github", now),
        location = "Zurich",
        eventKind = EventKind.MEETUP,
        eventFormat = EventFormat.HYBRID,
        facts = mapOf("stars" to 42),
        categories = setOf("agents"),
        foundIn = ListMatch("Awesome", "Tools > Agents"),
        curatedFields = setOf("title"),
        vitality = Vitality(archived = true, fork = true, deprecated = true, running = true),
      ),
      builtByName,
    )
  }

  @Test
  fun `the steps reach every field a hit does not always state`() {
    val alwaysStated =
      setOf("sourceId", "externalId", "url", "canonicalUrl", "title", "type", "retrievedAt")

    val leftAsItWas =
      SourceHit::class
        .memberProperties
        .filter { it.get(onlyWhatEveryHitStates) == it.get(builtByName) }
        .map { it.name }
        .toSet()

    assertEquals(alwaysStated, leftAsItWas)
  }

  @Test
  fun `a description stated alone keeps its origin`() {
    assertEquals(
      onlyWhatEveryHitStates.copy(description = "A tool."),
      onlyWhatEveryHitStates.withDescription("A tool."),
    )
  }
}
