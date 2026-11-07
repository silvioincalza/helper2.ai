package ai.helper2.eval

import ai.helper2.shared.Category
import ai.helper2.shared.CategoryCatalog
import ai.helper2.shared.CategoryInput
import ai.helper2.shared.CategoryMatcher
import ai.helper2.shared.CategoryWords
import ai.helper2.shared.ResourceType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scoring of the category evaluation harness, and the labelled file's format. */
class CategoryEvaluationTest {

  private val matcher =
    CategoryMatcher(
      CategoryCatalog(
        listOf(
          Category("coding", "Coding", "Coding", "developers", CategoryWords.of(listOf("coding"))),
          Category(
            "writing",
            "Writing",
            "Writing",
            "everyday-apps",
            CategoryWords.of(listOf("writer")),
          ),
        )
      )
    )

  private fun row(title: String, vararg expected: String) =
    LabelledResource(CategoryInput(title), expected.toSet())

  @Test
  fun `precision and recall count what the matcher gives against the labels`() {
    val evaluation =
      CategoryEvaluation(
        matcher,
        listOf(
          row("a coding tool", "coding"),
          row("a coding writer", "coding"), // writing given, not labelled: a false positive
          row("an ai writer"), // writing given, not labelled
          row("a pair programmer", "coding"), // coding missed
        ),
      )

    val coding = evaluation.scores().single { it.id == "coding" }
    assertEquals(1.0, coding.precision)
    assertEquals(2.0 / 3, coding.recall)
    val writing = evaluation.scores().single { it.id == "writing" }
    assertEquals(0.0, writing.precision)
    assertNull(writing.recall)
    assertEquals(CategoryScore("all", 2, 2, 1), evaluation.total())
    assertTrue(evaluation.report().contains("| coding | 1.00 | 0.67 | 2 | 0 | 1 |"))
  }

  @Test
  fun `a row is read with quoted commas, lists and an empty expected set`() {
    val r =
      CategoryEvaluation.parse(
        "\"Acme, the tool\",\"Says \"\"hi\"\"\",a;b,PRODUCT,discovery-coding;github,,note"
      )
    assertEquals(
      CategoryInput(
        "Acme, the tool",
        "Says \"hi\"",
        listOf("a", "b"),
        ResourceType.PRODUCT,
        listOf("discovery-coding", "github"),
      ),
      r.input,
    )
    assertEquals(emptySet(), r.expected)
  }

  @Test
  fun `the labelled file has a header and no labels until a person adds them`() {
    val file = File(javaClass.getResource("/eval/categories-labelled.csv")!!.toURI())
    assertTrue(
      file.readLines().any { it.startsWith("title,description,tags,type,finders,expected") }
    )
    assertEquals(emptyList(), CategoryEvaluation.read(file))
  }
}
