package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CategoryMatcherTest {

  private val coding =
    Category(
      "coding",
      "Coding",
      "Coding",
      "developers",
      CategoryWords.of(listOf("coding assistant", "code review")),
    )
  private val selfHosted =
    Category(
      "self-hosted",
      "Self-hosted",
      "Self-hosted",
      "run-it-yourself",
      CategoryWords.of(listOf("self hosted", "homelab")),
    )
  private val health =
    Category(
      "health",
      "Health",
      "Health",
      "everyday-apps",
      CategoryWords.of(listOf("medical scribe", "clinical notes")),
      requireKeyword = true,
    )
  private val prompts =
    Category(
      "prompt-libraries",
      "Prompt libraries",
      "Prompts",
      "prompts-and-skills",
      CategoryWords.of(listOf("prompt library")),
      type = ResourceType.PROMPT,
    )
  private val catalog = CategoryCatalog(listOf(prompts, coding, selfHosted, health))
  private val matcher = CategoryMatcher(catalog)

  private fun match(
    title: String,
    description: String? = null,
    tags: List<String> = emptyList(),
    type: ResourceType? = ResourceType.PRODUCT,
    finders: List<String> = emptyList(),
  ) = matcher.match(CategoryInput(title, description, tags, type, finders))

  @Test
  fun `a discovery collector gives its own category`() {
    assertEquals(listOf("coding"), match("Acme", finders = listOf("discovery-coding")))
  }

  @Test
  fun `a finder that is not a discovery collector or not a configured category gives nothing`() {
    assertEquals(emptyList(), match("Acme", finders = listOf("github", "tavily", "discovery-gone")))
  }

  @Test
  fun `a word in the title or the description gives the category`() {
    assertEquals(listOf("coding"), match("Acme, the AI coding assistant"))
    assertEquals(listOf("self-hosted"), match("Acme", "A Self-Hosted dashboard for your homelab"))
  }

  @Test
  fun `a word matches only at the start of a word`() {
    assertEquals(emptyList(), match("Acme", "Not a decode review"))
  }

  @Test
  fun `a tag that is the category id or has one of its words gives the category`() {
    assertEquals(listOf("coding"), match("Acme", tags = listOf("coding")))
    assertEquals(listOf("self-hosted"), match("Acme", tags = listOf("self-hosted")))
  }

  @Test
  fun `words of two tags do not join into one phrase`() {
    assertEquals(emptyList(), match("Acme", tags = listOf("code", "review")))
  }

  @Test
  fun `a require-keyword category never comes from a tag alone`() {
    assertEquals(emptyList(), match("Acme", tags = listOf("health", "medical scribe")))
  }

  @Test
  fun `a require-keyword category comes from its finder or a word in title or description`() {
    assertEquals(listOf("health"), match("Acme", finders = listOf("discovery-health")))
    assertEquals(listOf("health"), match("Acme", "Writes clinical notes for doctors"))
  }

  @Test
  fun `a prompt is a prompt library by its type`() {
    assertEquals(listOf("prompt-libraries"), match("Acme prompts", type = ResourceType.PROMPT))
  }

  @Test
  fun `a type that more than one category has names none`() {
    val twoPromptCategories =
      CategoryMatcher(
        CategoryCatalog(
          listOf(prompts, prompts.copy(id = "more-prompts", words = CategoryWords.NONE))
        )
      )
    assertEquals(
      emptyList(),
      twoPromptCategories.match(CategoryInput("Acme", type = ResourceType.PROMPT)),
    )
  }

  @Test
  fun `several categories come back in the configured order, each once`() {
    assertEquals(
      listOf("coding", "self-hosted", "health"),
      match(
        "Self-hosted code review",
        "clinical notes",
        tags = listOf("coding"),
        finders = listOf("discovery-health", "discovery-coding"),
      ),
    )
  }

  private fun underHeading(vararg headings: String, title: String = "Acme") =
    matcher.match(CategoryInput(title, headings = headings.toList()))

  @Test
  fun `a section heading with one of the words gives the category`() {
    assertEquals(listOf("coding"), underHeading("Tools > Code Review"))
    assertEquals(listOf("self-hosted"), underHeading("Apps > Self-Hosted"))
  }

  @Test
  fun `a heading that names the category id gives it`() {
    assertEquals(listOf("prompt-libraries"), underHeading("Prompt libraries"))
    assertEquals(listOf("coding"), underHeading("Coding"))
  }

  @Test
  fun `each heading of a path is read alone, so two never join into one phrase`() {
    assertEquals(emptyList(), underHeading("Code > Review"))
    assertEquals(emptyList(), underHeading("Self > Hosted"))
  }

  @Test
  fun `a heading alone never gives a category that requires a word`() {
    assertEquals(emptyList(), underHeading("Health > Clinical notes"))
    assertEquals(listOf("health"), underHeading("Health", title = "Acme clinical notes"))
  }

  @Test
  fun `a heading path joins the other inputs`() {
    assertEquals(
      listOf("coding", "self-hosted"),
      matcher.match(
        CategoryInput("Acme", tags = listOf("homelab"), headings = listOf("Coding assistants"))
      ),
    )
  }

  @Test
  fun `the same input gives the same answer`() {
    val input = CategoryInput("A coding assistant", "for your homelab", listOf("x"))
    assertEquals(matcher.match(input), matcher.match(input))
  }

  @Test
  fun `the catalog refuses a bad id, a repeated id and the no-category bucket`() {
    assertFailsWith<IllegalArgumentException> {
      CategoryCatalog(listOf(coding.copy(id = "Not A Tag")))
    }
    assertFailsWith<IllegalArgumentException> { CategoryCatalog(listOf(coding, coding)) }
    assertFailsWith<IllegalArgumentException> {
      CategoryCatalog(listOf(coding.copy(id = CategoryCatalog.NONE)))
    }
  }

  @Test
  fun `the fingerprint changes with the words but not with the names`() {
    val renamed = CategoryCatalog(listOf(coding.copy(name = "Code")))
    val reworded = CategoryCatalog(listOf(coding.copy(words = CategoryWords.of(listOf("ai ide")))))
    val base = CategoryCatalog(listOf(coding))
    assertEquals(base.fingerprint, renamed.fingerprint)
    assertNotEquals(base.fingerprint, reworded.fingerprint)
  }

  @Test
  fun `ordered keeps only categories, in the configured order`() {
    assertEquals(listOf("coding", "health"), catalog.ordered(listOf("health", "nope", "coding")))
    assertTrue("coding" in catalog)
  }
}
