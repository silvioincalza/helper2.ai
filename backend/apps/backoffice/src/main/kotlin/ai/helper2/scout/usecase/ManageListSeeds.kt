package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.ListAddresses
import ai.helper2.scout.ListSeed
import ai.helper2.scout.ListSeedStore
import ai.helper2.shared.UnitOfWork
import java.time.Clock

class ManageListSeeds(
  private val store: ListSeedStore,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  companion object {
    const val MAX_REASON = 500

    const val MAX_SEEDS = 50

    private val HIDDEN_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
  }

  sealed interface Added {
    data class Done(val seed: ListSeed) : Added

    data class Invalid(val problem: ListAddresses.Problem) : Added

    data class AlreadyListed(val current: ListSeed) : Added

    data object Full : Added

    data object BadReason : Added
  }

  sealed interface Removed {
    data class Done(val seed: ListSeed) : Removed

    data object NotFound : Removed

    data object BadReason : Removed
  }

  fun list(): List<ListSeed> = store.all()

  fun add(adminId: String, rawAddress: String?, reason: String?): Added {
    val note = clean(reason) ?: return Added.BadReason
    val checked = ListAddresses.check(rawAddress)
    if (checked is ListAddresses.Checked.Invalid) {
      return Added.Invalid(checked.problem)
    }
    val repository = (checked as ListAddresses.Checked.Valid).repository
    return unitOfWork.run {
      val outcome = tryAdd(adminId, repository, note.text)
      audit.record(
        adminId,
        AuditTrail.Action.ADD_LIST_SEED,
        repository,
        recorded(outcome),
        AuditTrail.Change(null, repository, note.text),
      )
      outcome
    }
  }

  fun remove(adminId: String, rawAddress: String?, reason: String?): Removed {
    val note = clean(reason) ?: return Removed.BadReason
    val repository =
      (ListAddresses.check(rawAddress) as? ListAddresses.Checked.Valid)?.repository
        ?: rawAddress.orEmpty()
    return unitOfWork.run {
      val removed = store.remove(repository)
      val outcome = if (removed == null) Removed.NotFound else Removed.Done(removed)
      audit.record(
        adminId,
        AuditTrail.Action.REMOVE_LIST_SEED,
        repository.take(ListAddresses.MAX_LENGTH),
        if (removed == null) "NOT_FOUND" else "REMOVED",
        AuditTrail.Change(removed?.repository, null, note.text),
      )
      outcome
    }
  }

  private fun tryAdd(adminId: String, repository: String, reason: String?): Added {
    val all = store.all()
    all
      .firstOrNull { it.repository == repository }
      ?.let {
        return Added.AlreadyListed(it)
      }
    if (all.size >= MAX_SEEDS) {
      return Added.Full
    }
    val seed = ListSeed(repository, adminId, clock.instant(), reason)
    return if (store.add(seed)) Added.Done(seed)
    else Added.AlreadyListed(store.all().first { it.repository == repository })
  }

  private fun recorded(outcome: Added): String =
    when (outcome) {
      is Added.Done -> "ADDED"
      is Added.Invalid -> "INVALID"
      is Added.AlreadyListed -> "ALREADY_LISTED"
      Added.Full -> "FULL"
      Added.BadReason -> "BAD_REASON"
    }

  private class Note(val text: String?)

  private fun clean(reason: String?): Note? {
    if (reason == null || reason.isBlank()) {
      return Note(null)
    }
    if (HIDDEN_CHARACTERS.containsMatchIn(reason)) {
      return null
    }
    val text = reason.trim()
    return if (text.length > MAX_REASON) null else Note(text)
  }
}
