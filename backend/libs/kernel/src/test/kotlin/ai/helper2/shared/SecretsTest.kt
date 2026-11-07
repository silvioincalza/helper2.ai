package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretsTest {

  @Test
  fun `a set secret is never shown`() {
    assertEquals("***", Secrets.mask("ghp_abc123"))
  }

  @Test
  fun `a missing or blank secret says none`() {
    assertEquals("none", Secrets.mask(null))
    assertEquals("none", Secrets.mask(""))
    assertEquals("none", Secrets.mask("  "))
  }
}
