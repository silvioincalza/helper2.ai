package ai.helper2.search

import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SourceDescriptorTest {

  private val index =
    SourceDescriptor(
      id = "helper2-index",
      name = "Helper2 index",
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
}
