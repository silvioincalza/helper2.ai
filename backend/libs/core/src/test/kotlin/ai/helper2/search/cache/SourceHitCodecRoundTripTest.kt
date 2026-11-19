package ai.helper2.search.cache

import ai.helper2.search.CachedHits
import ai.helper2.search.ListMatch
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.SourceHit
import ai.helper2.search.Vitality
import ai.helper2.shared.Audience
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.EventFormat
import ai.helper2.shared.EventKind
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.TechLevel
import ai.helper2.shared.ValueOrigin
import java.net.URI
import java.time.Instant
import kotlin.reflect.full.memberProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class SourceHitCodecRoundTripTest {

  private val codec = SourceHitCodec(JsonMapper.builder().build())
  private val now = Instant.parse("2026-10-27T10:00:00Z")
  private val earlier = Instant.parse("2026-09-01T00:00:00Z")

  private val notCached = setOf("facts", "categories", "curatedFields", "vitality")

  private val everyFieldSet =
    SourceHit(
      sourceId = "github",
      externalId = "a/tool",
      url = URI.create("https://github.com/a/tool"),
      canonicalUrl = CanonicalUrl.of("https://github.com/a/tool")!!,
      repositoryUrl = CanonicalUrl.of("https://github.com/a/tool"),
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
    )

  @Test
  fun `a hit comes back from the cache with every field but the four it does not hold`() {
    val back = codec.decode(codec.encode(CachedHits(listOf(everyFieldSet), now)))!!.hits.single()

    val lost =
      SourceHit::class
        .memberProperties
        .filter { it.get(everyFieldSet) != it.get(back) }
        .map { it.name }
        .toSet()

    assertThat(lost).isEqualTo(notCached)
  }

  @Test
  fun `the hit of the test sets every field away from its default`() {
    val blank =
      SourceHit(
        sourceId = "",
        externalId = "",
        url = URI.create("https://example.org"),
        canonicalUrl = CanonicalUrl.of("https://example.org")!!,
        title = "",
        type = ResourceType.OTHER,
        retrievedAt = Instant.EPOCH,
      )

    val leftAtDefault =
      SourceHit::class
        .memberProperties
        .filter { it.get(everyFieldSet) == it.get(blank) }
        .map { it.name }

    assertThat(leftAtDefault).isEmpty()
  }
}
