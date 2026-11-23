package ai.helper2.search

import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SourceDescriptorTest {

  private val index =
    SourceDescriptor(
      id = "helper2-index",
      name = "helper2.ai catalog",
      shortName = "Index",
      external = false,
      mode = SourceMode.LIVE,
      homepage = URI.create("https://helper2.ai/sources"),
      termsUrl = null,
      attribution = null,
      resourceTypes = setOf(ResourceType.PRODUCT),
      timeout = Duration.ofSeconds(3),
      cacheTtl = Duration.ZERO,
      enabled = true,
      priority = 50,
      usesWebQuery = true,
      usesTranslation = false,
      filtersByDate = true,
      browses = true,
      metered = true,
    )

  @Test
  fun `another mode keeps every other field`() {
    assertEquals(index.copy(mode = SourceMode.INDEXED), index.withMode(SourceMode.INDEXED))
  }

  @Test
  fun `the same mode returns the same descriptor`() {
    assertSame(index, index.withMode(SourceMode.LIVE))
  }

  @Test
  fun `a short name has 1 to 12 characters`() {
    assertEquals("Twelve chars", index.copy(shortName = "Twelve chars").shortName)
    assertFailsWith<IllegalArgumentException> { index.copy(shortName = "Thirteen char") }
    assertFailsWith<IllegalArgumentException> { index.copy(shortName = " ") }
  }

  @Test
  fun `an external source built by name is the one built by position`() {
    val byName =
      SourceDescriptor.external(
          "live-web",
          "Live web search",
          "Web",
          SourceMode.LIVE,
          URI.create("https://helper2.ai/sources"),
          setOf(ResourceType.NEWS),
          Duration.ofSeconds(3),
          Duration.ofMinutes(10),
        )
        .withTerms(URI.create("https://helper2.ai/terms"))
        .withAttribution("Web results")
        .enabledWhen(false)
        .withPriority(48)
        .askedWithTheWebQuery()
        .askedWithoutTranslation()
        .filteringByDate()
        .browsing()
        .metered()
        .inFamily("discovery")
        .confirmingUrlsUnder("https://huggingface.co/spaces/")

    assertEquals(
      SourceDescriptor(
        id = "live-web",
        name = "Live web search",
        shortName = "Web",
        external = true,
        mode = SourceMode.LIVE,
        homepage = URI.create("https://helper2.ai/sources"),
        termsUrl = URI.create("https://helper2.ai/terms"),
        attribution = "Web results",
        resourceTypes = setOf(ResourceType.NEWS),
        timeout = Duration.ofSeconds(3),
        cacheTtl = Duration.ofMinutes(10),
        enabled = false,
        priority = 48,
        usesWebQuery = true,
        usesTranslation = false,
        filtersByDate = true,
        browses = true,
        metered = true,
        family = "discovery",
        confirmsUrlPrefix = "https://huggingface.co/spaces/",
      ),
      byName,
    )
  }

  @Test
  fun `a source built by name without a step keeps what a source has when nothing is said`() {
    val inHouse =
      SourceDescriptor.inHouse(
        "curated",
        "Hand-picked list",
        "Curated",
        SourceMode.CURATED,
        URI.create("https://helper2.ai/sources"),
        setOf(ResourceType.PRODUCT),
        Duration.ofSeconds(3),
        Duration.ZERO,
      )

    assertEquals(
      SourceDescriptor(
        id = "curated",
        name = "Hand-picked list",
        shortName = "Curated",
        external = false,
        mode = SourceMode.CURATED,
        homepage = URI.create("https://helper2.ai/sources"),
        termsUrl = null,
        attribution = null,
        resourceTypes = setOf(ResourceType.PRODUCT),
        timeout = Duration.ofSeconds(3),
        cacheTtl = Duration.ZERO,
      ),
      inHouse,
    )
  }
}
