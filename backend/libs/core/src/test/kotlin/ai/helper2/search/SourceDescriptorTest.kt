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
}
