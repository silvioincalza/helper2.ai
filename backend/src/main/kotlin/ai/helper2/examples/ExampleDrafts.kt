package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.util.UUID

/** A catalog resource a draft may be written for: public facts only, never visitor data. */
data class DraftSource(
  val resourceId: UUID,
  val title: String,
  val type: String,
  val audience: String,
  val description: String?,
)

/** What a model proposed: an example whose expected result is [resourceId]. */
data class ProposedDraft(
  val resourceId: UUID?,
  val label: String?,
  val query: String?,
  val path: AudiencePath?,
)

/** A draft that follows every rule; it waits in the review queue for a person. */
data class ExampleDraft(val resourceId: UUID, val input: ExampleInput, val model: String)

/** Outbound port: the model that drafts examples. */
interface ExampleDrafter {
  val model: String

  /** The model's proposals for [sources]; may be empty. Throws [DraftsUnavailable]. */
  fun draft(sources: List<DraftSource>): List<ProposedDraft>
}

/** Outbound port: well-known catalog resources that are not yet an example's expected result. */
fun interface DraftSources {
  fun wellKnown(limit: Int, excluding: Set<UUID>): List<DraftSource>
}

/** No draft now: no model, the daily budget is used, or the model cannot answer. */
class DraftsUnavailable(val reason: Reason) : RuntimeException(reason.name) {
  enum class Reason {
    NO_MODEL,
    BUDGET,
    PROVIDER,
    UNREADABLE,
  }
}

/**
 * The rules a model's proposal must pass before a person sees it (ADR 0049 §6): grounded (it names
 * a resource it was given, which becomes its expected result), the same text rules as an admin's
 * example, one per resource, and no repeat of an example or of another draft.
 */
object DraftRules {
  fun keep(
    proposed: List<ProposedDraft>,
    sources: List<DraftSource>,
    taken: Collection<HomeExample>,
    model: String,
  ): List<ExampleDraft> {
    val given = sources.map { it.resourceId }.toSet()
    val labels = taken.map { it.label.lowercase() }.toMutableSet()
    val queries = taken.map { it.query.lowercase() }.toMutableSet()
    val used = mutableSetOf<UUID>()
    return proposed.mapNotNull { p ->
      val id = p.resourceId?.takeIf { it in given && it !in used } ?: return@mapNotNull null
      val input = valid(p, id) ?: return@mapNotNull null
      if (!labels.add(input.label.lowercase()) || !queries.add(input.query.lowercase())) {
        return@mapNotNull null
      }
      used += id
      ExampleDraft(id, input, model)
    }
  }

  private fun valid(p: ProposedDraft, id: UUID): ExampleInput? {
    if (p.label == null || p.query == null || p.path == null) return null
    return (ExampleInput.of(p.label, p.query, p.path, listOf(id)) as? ExampleInput.Checked.Valid)
      ?.input
  }
}
