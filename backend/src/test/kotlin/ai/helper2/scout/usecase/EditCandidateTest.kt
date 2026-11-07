package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.shared.ResourceType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0065: an admin fixes a resource candidate's title, type or tags before accepting it. */
class EditCandidateTest {

  private val id = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

  /** Items in memory; [edit] writes the change as text, so the test sees exactly what was sent. */
  private inner class Items(var item: CandidateItem?) : CandidateItems {
    val saved = mutableListOf<String>()
    var conflict = false

    override fun find(itemId: UUID) = item?.takeIf { itemId == id }

    override fun edit(details: String?, edit: CandidateEdit) =
      "$details|${edit.title}|${edit.type}|${edit.tags}"

    override fun replace(itemId: UUID, oldDetails: String?, newDetails: String): Boolean {
      if (conflict) return false
      saved += newDetails
      return true
    }
  }

  private val audit = mutableListOf<String>()
  private val trail = AuditTrail { by, action, target, outcome ->
    audit += "$by $action $target $outcome"
  }

  private fun open(details: String = "d") =
    CandidateItem(open = true, resourceCandidate = true, details)

  private fun edit(items: Items, change: CandidateEdit) =
    EditCandidate(items, trail).edit(id, change, "42")

  @Test
  fun `a new title, type and tags are saved and audited`() {
    val items = Items(open())
    val r =
      edit(
        items,
        CandidateEdit(" Gemini 4 Carbon ", ResourceType.MODEL, setOf("ai-assistants", "coding")),
      )
    assertIs<EditCandidate.Outcome.Edited>(r)
    assertEquals(listOf("d|Gemini 4 Carbon|MODEL|[ai-assistants, coding]"), items.saved)
    assertEquals(listOf("42 EDIT_CANDIDATE $id EDITED title, type, tags"), audit)
  }

  @Test
  fun `only what is given changes`() {
    val items = Items(open())
    edit(items, CandidateEdit(type = ResourceType.ARTICLE))
    assertEquals(listOf("d|null|ARTICLE|null"), items.saved)
    assertEquals(listOf("42 EDIT_CANDIDATE $id EDITED type"), audit)
  }

  @Test
  fun `a value a candidate cannot have is refused and nothing is saved`() {
    for (bad in
      listOf(
        CandidateEdit(),
        CandidateEdit(title = " "),
        CandidateEdit(title = "x".repeat(301)),
        CandidateEdit(title = "line\nbreak"),
        CandidateEdit(type = ResourceType.COURSE),
        CandidateEdit(type = ResourceType.OTHER),
        CandidateEdit(tags = setOf("Not A Tag")),
        CandidateEdit(tags = (1..21).map { "t$it" }.toSet()),
      )) {
      val items = Items(open())
      assertIs<EditCandidate.Outcome.Invalid>(edit(items, bad), bad.toString())
      assertTrue(items.saved.isEmpty())
    }
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `an item that is not an open resource candidate is left alone`() {
    assertIs<EditCandidate.Outcome.NotFound>(edit(Items(null), CandidateEdit(title = "x")))
    assertIs<EditCandidate.Outcome.NotOpen>(
      edit(Items(open().copy(open = false)), CandidateEdit(title = "x"))
    )
    assertIs<EditCandidate.Outcome.NotACandidate>(
      edit(Items(open().copy(resourceCandidate = false)), CandidateEdit(title = "x"))
    )
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `a change made meanwhile by a collector or another admin wins, and nothing is saved`() {
    val items = Items(open()).apply { conflict = true }
    assertIs<EditCandidate.Outcome.ChangedMeanwhile>(edit(items, CandidateEdit(title = "x")))
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `an unreadable candidate is refused`() {
    val items =
      object : CandidateItems by Items(open()) {
        override fun edit(details: String?, edit: CandidateEdit): String =
          throw InvalidCandidate("the candidate is not JSON")
      }
    val r = EditCandidate(items, trail).edit(id, CandidateEdit(title = "x"), "42")
    assertIs<EditCandidate.Outcome.Invalid>(r)
    assertNull(audit.firstOrNull())
  }
}
