package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.Candidate
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.UnitOfWork
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class CandidateEdit
@JvmOverloads
constructor(
  val title: String? = null,
  val type: ResourceType? = null,
  val tags: Set<String>? = null,
  val canonicalUrl: String? = null,
  val pricing: String? = null,
  val openSource: Boolean? = null,
  val license: String? = null,
  val audience: String? = null,
  val techLevel: String? = null,
  val description: String? = null,
  val descriptionByAi: Boolean? = null,
  val language: String? = null,
  val publishedAt: LocalDate? = null,
) {
  fun confirmed(): Map<String, String> =
    listOfNotNull(
        pricing?.let { "pricing" to it },
        openSource?.let { "openSource" to it.toString() },
        license?.let { "license" to it },
        audience?.let { "audience" to it },
        techLevel?.let { "techLevel" to it },
        description?.let { "description" to it },
        language?.let { "language" to it },
        publishedAt?.let { "publishedAt" to it.toString() },
      )
      .toMap()
}

data class CandidateItem(val open: Boolean, val resourceCandidate: Boolean, val details: String?)

interface CandidateItems {
  fun find(itemId: UUID): CandidateItem?

  fun edit(details: String?, edit: CandidateEdit): String

  fun replace(itemId: UUID, oldDetails: String?, newDetails: String): Boolean
}

class EditCandidate
@JvmOverloads
constructor(
  private val items: CandidateItems,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock = Clock.systemUTC(),
) {

  sealed interface Outcome {
    data object Edited : Outcome

    data object NotFound : Outcome

    data object NotOpen : Outcome

    data object NotACandidate : Outcome

    data object ChangedMeanwhile : Outcome

    data class Invalid(val reason: String) : Outcome
  }

  fun edit(itemId: UUID, expectedRevision: String, change: CandidateEdit, by: String): Outcome {
    val item = items.find(itemId) ?: return Outcome.NotFound
    if (!item.open) return Outcome.NotOpen
    if (!item.resourceCandidate) return Outcome.NotACandidate
    if (CandidateRevision.of(item.details) != expectedRevision) return Outcome.ChangedMeanwhile
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
    return save(itemId, item.details, edited, fields(checked), by)
  }

  private fun save(
    itemId: UUID,
    oldDetails: String?,
    newDetails: String,
    fields: String,
    by: String,
  ): Outcome = unitOfWork.run {
    if (items.replace(itemId, oldDetails, newDetails)) {
      audit.record(by, AuditTrail.Action.EDIT_CANDIDATE, itemId.toString(), "EDITED $fields")
      Outcome.Edited
    } else {
      Outcome.ChangedMeanwhile
    }
  }

  private fun checked(change: CandidateEdit): CandidateEdit {
    require(
      change.title != null ||
        change.type != null ||
        change.tags != null ||
        change.canonicalUrl != null ||
        change.confirmed().isNotEmpty()
    ) {
      "Change at least one value."
    }
    val url = change.canonicalUrl?.trim()
    if (url != null) {
      require(url.startsWith("https://") && url.length <= 2000 && CanonicalUrl.of(url) != null) {
        "The canonical URL must be an https URL."
      }
    }
    CandidateRules.checkConfirmed(change, clock)
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
    return change.copy(
      title = title,
      tags = change.tags?.toSortedSet(),
      canonicalUrl = url,
      description = change.description?.trim(),
      license = change.license?.trim(),
    )
  }

  private fun fields(c: CandidateEdit): String =
    (listOfNotNull(
        "title".takeIf { c.title != null },
        "type".takeIf { c.type != null },
        "tags".takeIf { c.tags != null },
        "canonicalUrl".takeIf { c.canonicalUrl != null },
      ) + c.confirmed().keys)
      .joinToString(", ")
}
