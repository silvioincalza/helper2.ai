package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftRulesTest {

  private val deepl = source("DeepL Translator")
  private val otter = source("Otter.ai")
  private val sources = listOf(deepl, otter)

  private fun source(title: String) =
    DraftSource(UUID.randomUUID(), title, "PRODUCT", "USE_AI", null)

  private fun draft(id: UUID?, label: String, query: String) =
    ProposedDraft(id, label, query, AudiencePath.USE_AI)

  private fun taken(label: String, query: String) = TakenText(label, query)

  private fun keep(vararg proposed: ProposedDraft, taken: List<TakenText> = emptyList()) =
    DraftRules.keep(proposed.toList(), sources, taken, "nemotron")

  @Test
  fun `keeps a grounded draft, with its resource as the expected result`() {
    val kept = keep(draft(deepl.resourceId, "Translate documents", "translate documents"))
    val one = kept.single()
    assertEquals(listOf(deepl.resourceId), one.input.expectedResourceIds)
    assertEquals("nemotron", one.model)
  }

  @Test
  fun `drops a draft for a resource it was not given, or with none`() {
    assertEquals(emptyList(), keep(draft(UUID.randomUUID(), "Translate", "translate text")))
    assertEquals(emptyList(), keep(draft(null, "Translate", "translate text")))
  }

  @Test
  fun `drops a draft that breaks the text rules or misses a field`() {
    assertEquals(emptyList(), keep(draft(deepl.resourceId, "<b>x</b>", "translate text")))
    assertEquals(emptyList(), keep(draft(deepl.resourceId, "Translate", "see https://x.y")))
    assertEquals(
      emptyList(),
      keep(ProposedDraft(deepl.resourceId, "Translate", "translate text", null)),
    )
  }

  @Test
  fun `one draft per resource, and no repeat of an example or another draft`() {
    val kept =
      keep(
        draft(deepl.resourceId, "Translate documents", "translate documents"),
        draft(deepl.resourceId, "Translate letters", "translate letters"),
        draft(otter.resourceId, "translate DOCUMENTS", "take meeting notes"),
      )
    assertEquals(listOf("Translate documents"), kept.map { it.input.label })

    val repeat =
      keep(
        draft(otter.resourceId, "Meeting notes", "take meeting notes"),
        taken = listOf(taken("Take notes", "Take Meeting Notes")),
      )
    assertEquals(emptyList(), repeat)
  }

  @Test
  fun `a draft refused for its query does not reserve its label for a later one`() {
    val kept =
      keep(
        draft(deepl.resourceId, "Translate documents", "translate text"),
        draft(otter.resourceId, "Translate documents", "translate documents"),
        taken = listOf(taken("Translate", "translate text")),
      )
    assertEquals(listOf(otter.resourceId), kept.map { it.resourceId })
  }
}
