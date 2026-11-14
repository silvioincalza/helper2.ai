package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExampleInputTest {

  private val one = listOf(UUID.randomUUID())

  private fun of(
    label: String = "Vector database",
    query: String = "vector database",
    expected: List<UUID> = one,
  ) = ExampleInput.of(label, query, AudiencePath.BUILD_WITH_AI, expected)

  @Test
  fun `normalises the texts to NFC, trimmed, with one space between words`() {
    val valid =
      assertIs<ExampleInput.Checked.Valid>(
        of(label = "  Café   notes ", query = "take\n meeting\tnotes")
      )
    assertEquals("Café notes", valid.input.label)
    assertEquals("take meeting notes", valid.input.query)
  }

  @Test
  fun `keeps each expected result once`() {
    val id = UUID.randomUUID()
    val valid = assertIs<ExampleInput.Checked.Valid>(of(expected = listOf(id, id)))
    assertEquals(listOf(id), valid.input.expectedResourceIds)
  }

  @Test
  fun `refuses texts the home page must not show`() {
    assertEquals(listOf("label: 2 to 32 characters"), problems(of(label = "x")))
    assertEquals(listOf("label: 2 to 32 characters"), problems(of(label = "x".repeat(33))))
    assertEquals(listOf("query: 3 to 200 characters"), problems(of(query = "ab")))
    assertEquals(listOf("label: no < or >"), problems(of(label = "<b>hi</b>")))
    assertEquals(
      listOf("query: no web addresses"),
      problems(of(query = "see https://evil.example")),
    )
    assertEquals(listOf("query: no web addresses"), problems(of(query = "www.example.com tools")))
    assertEquals(listOf("label: no control characters"), problems(of(label = "ab\u0000c")))
  }

  @Test
  fun `needs one to three expected results`() {
    assertEquals(listOf("give 1 to 3 expected results"), problems(of(expected = emptyList())))
    val four = List(4) { UUID.randomUUID() }
    assertEquals(listOf("give 1 to 3 expected results"), problems(of(expected = four)))
  }

  private fun problems(checked: ExampleInput.Checked) =
    assertIs<ExampleInput.Checked.Invalid>(checked).problems
}
