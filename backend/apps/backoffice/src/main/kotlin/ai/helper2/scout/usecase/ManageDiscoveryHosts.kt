package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.DiscoveryHost
import ai.helper2.scout.DiscoveryHostStore
import ai.helper2.scout.HostKind
import ai.helper2.scout.HostNames
import ai.helper2.shared.UnitOfWork
import java.time.Clock

class ManageDiscoveryHosts(
  private val store: DiscoveryHostStore,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  companion object {
    const val MAX_REASON = 500

    const val MAX_HOSTS = 500

    private val HIDDEN_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
  }

  sealed interface Added {
    data class Done(val host: DiscoveryHost) : Added

    data class Invalid(val problem: HostNames.Problem) : Added

    data class AlreadyListed(val current: DiscoveryHost) : Added

    data object Full : Added

    data object BadReason : Added
  }

  sealed interface Removed {
    data class Done(val host: DiscoveryHost) : Removed

    data object NotFound : Removed

    data object BadReason : Removed
  }

  sealed interface ListReading {
    data class Done(val host: DiscoveryHost) : ListReading

    data class NotADirectory(val host: DiscoveryHost) : ListReading

    data object NotFound : ListReading

    data object BadReason : ListReading
  }

  fun list(): List<DiscoveryHost> = store.all()

  fun add(adminId: String, rawHost: String?, kind: HostKind, reason: String?): Added {
    val note = clean(reason) ?: return Added.BadReason
    val checked = HostNames.check(rawHost)
    if (checked is HostNames.Checked.Invalid) {
      return Added.Invalid(checked.problem)
    }
    val host = (checked as HostNames.Checked.Valid).host
    return unitOfWork.run {
      val outcome = tryAdd(adminId, host, kind, note.text)
      audit.record(
        adminId,
        AuditTrail.Action.ADD_DISCOVERY_HOST,
        host,
        recorded(outcome),
        AuditTrail.Change(null, kind.name, note.text),
      )
      outcome
    }
  }

  fun remove(adminId: String, rawHost: String?, reason: String?): Removed {
    val note = clean(reason) ?: return Removed.BadReason
    val host = (HostNames.check(rawHost) as? HostNames.Checked.Valid)?.host ?: rawHost.orEmpty()
    return unitOfWork.run {
      val removed = store.remove(host)
      val outcome = if (removed == null) Removed.NotFound else Removed.Done(removed)
      audit.record(
        adminId,
        AuditTrail.Action.REMOVE_DISCOVERY_HOST,
        host.take(HostNames.MAX_LENGTH),
        if (removed == null) "NOT_FOUND" else "REMOVED",
        AuditTrail.Change(removed?.kind?.name, null, note.text),
      )
      outcome
    }
  }

  /** Turns the reading of a directory host's list pages on or off. */
  fun readLists(adminId: String, rawHost: String?, on: Boolean, reason: String?): ListReading {
    val note = clean(reason) ?: return ListReading.BadReason
    val host = (HostNames.check(rawHost) as? HostNames.Checked.Valid)?.host ?: rawHost.orEmpty()
    return unitOfWork.run {
      val listed = store.all().firstOrNull { it.host == host }
      val outcome = switched(listed, on)
      audit.record(
        adminId,
        AuditTrail.Action.READ_DIRECTORY_LISTS,
        host.take(HostNames.MAX_LENGTH),
        recorded(outcome),
        AuditTrail.Change(listed?.readsLists?.let(::switch), switch(on), note.text),
      )
      outcome
    }
  }

  // UC-019 BR-007: only a directory host has the switch.
  private fun switched(listed: DiscoveryHost?, on: Boolean): ListReading =
    when {
      listed == null -> ListReading.NotFound
      listed.kind != HostKind.DIRECTORY -> ListReading.NotADirectory(listed)
      else -> store.readLists(listed.host, on)?.let { ListReading.Done(it) } ?: ListReading.NotFound
    }

  private fun switch(on: Boolean): String = if (on) "ON" else "OFF"

  private fun recorded(outcome: ListReading): String =
    when (outcome) {
      is ListReading.Done -> if (outcome.host.readsLists) "ON" else "OFF"
      is ListReading.NotADirectory -> "NOT_A_DIRECTORY"
      ListReading.NotFound -> "NOT_FOUND"
      ListReading.BadReason -> "BAD_REASON"
    }

  private fun tryAdd(adminId: String, host: String, kind: HostKind, reason: String?): Added {
    val all = store.all()
    all
      .firstOrNull { it.host == host }
      ?.let {
        return Added.AlreadyListed(it)
      }
    if (all.size >= MAX_HOSTS) {
      return Added.Full
    }
    val entry = DiscoveryHost(host, kind, adminId, clock.instant(), reason)
    return if (store.add(entry)) Added.Done(entry)
    else Added.AlreadyListed(store.all().first { it.host == host })
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
