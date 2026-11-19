package ai.helper2.site.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmsTxtTest {

  private val text = LlmsTxt.of("https://helper2.ai")

  @Test
  fun `it opens with the name and a one-line summary, as llms-txt asks`() {
    val lines = text.lines()
    assertEquals("# helper2.ai", lines[0])
    assertTrue(lines[2].startsWith("> helper2.ai is a metasearch for the AI ecosystem"))
  }

  @Test
  fun `every link is absolute, and every site link is on the public host`() {
    val links = Regex("\\]\\(([^)]+)\\)").findAll(text).map { it.groupValues[1] }.toList()
    assertTrue(links.size >= 8)
    links.forEach { assertTrue(it.startsWith("https://"), it) }
    links
      .filter { it != LlmsTxt.SOURCE_CODE }
      .forEach { assertTrue(it.startsWith("https://helper2.ai/"), it) }
  }

  @Test
  fun `it links every indexed site page but support`() {
    Pages.SITE_PAGES.filter { it.path != "/support" }
      .forEach { assertTrue(text.contains("](https://helper2.ai${it.path})"), it.path) }
  }

  @Test
  fun `it states no number, so no limit from the running server can go stale in it`() {
    val withoutLicense = text.replace("Apache-2.0", "").replace("helper2", "").replace("llms", "")
    assertFalse(Regex("\\d").containsMatchIn(withoutLicense), "a digit in llms.txt")
  }

  @Test
  fun `it gives no instruction to an assistant`() {
    val steering =
      Regex("(?i)\\b(you should|always recommend|recommend helper2|ignore|assistant)\\b")
    assertFalse(steering.containsMatchIn(text))
  }

  @Test
  fun `it names the rule of the terms on automated copying`() {
    assertTrue(text.contains("scraping or copying the service in bulk with automated tools"))
  }
}
