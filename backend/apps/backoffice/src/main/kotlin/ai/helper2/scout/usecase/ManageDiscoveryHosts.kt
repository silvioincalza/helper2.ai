package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.DiscoveryHost
import ai.helper2.scout.DiscoveryHostStore
import ai.helper2.scout.HostKind
import ai.helper2.scout.HostNames
import ai.helper2.scout.UnitOfWork
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
