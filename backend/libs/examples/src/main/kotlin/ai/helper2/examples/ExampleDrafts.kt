package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.util.UUID

data class DraftSource(
  val resourceId: UUID,
  val title: String,
  val type: String,
  val audience: String,
  val description: String?,
)

data class ProposedDraft(
  val resourceId: UUID?,
  val label: String?,
  val query: String?,
  val path: AudiencePath?,
)

data class TakenText(val label: String, val query: String)

data class ExampleDraft(val resourceId: UUID, val input: ExampleInput, val model: String)

interface ExampleDrafter {
  val model: String

  fun draft(sources: List<DraftSource>): List<ProposedDraft>
}

data class WaitingDraft(
  val label: String,
  val query: String,
  val path: AudiencePath,
  val model: String,
)

interface DraftQueue {
  fun open(draft: ExampleDraft)

  fun waitingResources(): Set<UUID>

  fun waiting(): List<WaitingDraft>

  fun read(details: String?): WaitingDraft?
}

fun interface UnitOfWork {
  fun run(work: Runnable)
}

fun interface DraftSources {
  fun wellKnown(limit: Int, excluding: Set<UUID>): List<DraftSource>
}

class DraftsUnavailable(val reason: Reason) : RuntimeException(reason.name) {
  enum class Reason {
    NO_MODEL,
    BUDGET,
    PROVIDER,
    UNREADABLE,
  }
}

object DraftRules {
  fun keep(
    proposed: List<ProposedDraft>,
    sources: List<DraftSource>,
    taken: Collection<TakenText>,
    model: String,
  ): List<ExampleDraft> {
    val given = sources.map { it.resourceId }.toSet()
    val labels = taken.map { it.label.lowercase() }.toMutableSet()
    val queries = taken.map { it.query.lowercase() }.toMutableSet()
    val used = mutableSetOf<UUID>()
    return proposed.mapNotNull { p ->
      val id = p.resourceId?.takeIf { it in given && it !in used } ?: return@mapNotNull null
      val input = valid(p, id) ?: return@mapNotNull null
      val label = input.label.lowercase()
      val query = input.query.lowercase()
      if (label in labels || query in queries) return@mapNotNull null
      labels += label
      queries += query
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
