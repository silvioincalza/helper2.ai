package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.HostsNeverRead
import ai.searchavo.scout.ListAddresses
import ai.searchavo.scout.ListKind
import ai.searchavo.scout.ListOrigin
import ai.searchavo.scout.ListSeed
import ai.searchavo.scout.ListSeedStore
import ai.searchavo.shared.UnitOfWork
import java.time.Clock
import java.util.UUID

class ManageListSeeds(
  private val store: ListSeedStore,
  private val neverRead: HostsNeverRead,
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

    data class NeverRead(val host: String, val reason: String) : Added

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
    val list = checked as ListAddresses.Checked.Valid
    refusalOf(list)?.let {
      return Added.NeverRead(it.host, it.reason)
    }
    return unitOfWork.run {
      val outcome = tryAdd(adminId, list, note.text)
      audit.record(
        adminId,
        AuditTrail.Action.ADD_LIST_SEED,
        list.address,
        recorded(outcome),
        AuditTrail.Change(null, list.address, note.text),
      )
      outcome
    }
  }

  fun remove(adminId: String, id: UUID, reason: String?): Removed {
    val note = clean(reason) ?: return Removed.BadReason
    return unitOfWork.run {
      val removed = store.all().firstOrNull { it.id == id }?.let { store.remove(it.address) }
      val outcome = if (removed == null) Removed.NotFound else Removed.Done(removed)
      audit.record(
        adminId,
        AuditTrail.Action.REMOVE_LIST_SEED,
        removed?.address ?: id.toString(),
        if (removed == null) "NOT_FOUND" else "REMOVED",
        AuditTrail.Change(removed?.address, null, note.text),
      )
      outcome
    }
  }

  private fun refusalOf(list: ListAddresses.Checked.Valid): HostsNeverRead.Refusal? =
    if (list.kind == ListKind.WEB_PAGE) neverRead.refusalOf(list.address) else null

  private fun tryAdd(adminId: String, list: ListAddresses.Checked.Valid, reason: String?): Added {
    val all = store.all()
    all
      .firstOrNull { it.identity == list.identity }
      ?.let {
        return Added.AlreadyListed(it)
      }
    // UC-020 BR-003: the lists discovery added have their own number (UC-030 BR-003).
    if (all.count { it.origin == ListOrigin.ADMINISTRATOR } >= MAX_SEEDS) {
      return Added.Full
    }
    val seed = ListSeed(list.kind, list.address, adminId, clock.instant(), reason)
    return if (store.add(seed)) Added.Done(seed)
    else Added.AlreadyListed(store.all().first { it.address == list.address })
  }

  private fun recorded(outcome: Added): String =
    when (outcome) {
      is Added.Done -> "ADDED"
      is Added.Invalid -> "INVALID"
      is Added.NeverRead -> "NEVER_READ"
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
