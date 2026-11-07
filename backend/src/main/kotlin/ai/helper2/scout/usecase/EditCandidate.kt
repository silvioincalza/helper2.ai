package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.Candidate
import ai.helper2.shared.ResourceType
import java.util.UUID

/** What an admin changes on a resource candidate; null leaves it as the collector wrote it. */
data class CandidateEdit(
  val title: String? = null,
  val type: ResourceType? = null,
  val tags: Set<String>? = null,
)

/** A review item as an edit needs it. */
data class CandidateItem(val open: Boolean, val resourceCandidate: Boolean, val details: String?)

/** Outbound port: the review items that hold resource candidates, and their details. */
interface CandidateItems {
  fun find(itemId: UUID): CandidateItem?

  /**
   * [details] with [edit] applied, and the edited fields marked so a later collector does not undo
   * them. Throws [InvalidCandidate] when [details] cannot be read.
   */
  fun edit(details: String?, edit: CandidateEdit): String

  /** Saves [newDetails] when the item is still OPEN and still holds [oldDetails]. */
  fun replace(itemId: UUID, oldDetails: String?, newDetails: String): Boolean
}

/**
 * An admin fixes a resource candidate before accepting it (ADR 0065): its title, its type (any
 * candidate type, not only the ones its finders declare) or its tags. The item stays open. The
 * edited fields are marked in the details, so a collector that finds the page again joins in
 * without undoing them, and accepting the item indexes what the admin wrote. A change saved by
 * someone else between the read and the write wins: this edit then saves nothing.
 */
class EditCandidate(private val items: CandidateItems, private val audit: AuditTrail) {

  sealed interface Outcome {
    data object Edited : Outcome

    data object NotFound : Outcome

    data object NotOpen : Outcome

    data object NotACandidate : Outcome

    data object ChangedMeanwhile : Outcome

    data class Invalid(val reason: String) : Outcome
  }

  fun edit(itemId: UUID, change: CandidateEdit, by: String): Outcome {
    val item = items.find(itemId) ?: return Outcome.NotFound
    if (!item.open) return Outcome.NotOpen
    if (!item.resourceCandidate) return Outcome.NotACandidate
    val checked =
      try {
        checked(change)
      } catch (e: IllegalArgumentException) {
        return Outcome.Invalid(e.message ?: "invalid change")
      }
    val edited =
      try {
        items.edit(item.details, checked)
      } catch (e: InvalidCandidate) {
        return Outcome.Invalid("The candidate cannot be read.")
      }
    if (!items.replace(itemId, item.details, edited)) return Outcome.ChangedMeanwhile
    audit.record(
      by,
      AuditTrail.Action.EDIT_CANDIDATE,
      itemId.toString(),
      "EDITED ${fields(checked)}",
    )
    return Outcome.Edited
  }

  private fun checked(change: CandidateEdit): CandidateEdit {
    require(change.title != null || change.type != null || change.tags != null) {
      "Change at least one of the title, the type and the tags."
    }
    val title = change.title?.trim()
    if (title != null) {
      require(
        title.isNotEmpty() &&
          title.length <= CandidateRules.MAX_TITLE &&
          title.none { Character.isISOControl(it) }
      ) {
        "The title must be 1 to ${CandidateRules.MAX_TITLE} characters on one line."
      }
    }
    if (change.type != null) {
      require(change.type in CandidateRules.TYPES) { "A candidate cannot be a ${change.type}." }
    }
    if (change.tags != null) {
      require(CandidateRules.tagsOk(change.tags)) {
        "At most ${Candidate.MAX_TAGS} tags, each in lower case words joined by hyphens (local-ai)."
      }
    }
    return change.copy(title = title, tags = change.tags?.toSortedSet())
  }

  private fun fields(c: CandidateEdit): String =
    listOfNotNull(
        "title".takeIf { c.title != null },
        "type".takeIf { c.type != null },
        "tags".takeIf { c.tags != null },
      )
      .joinToString(", ")
}
