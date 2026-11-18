package ai.helper2.search.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.search.ShadowLibraries
import ai.helper2.search.ShadowLibrary
import ai.helper2.search.ShadowLibraryNames
import ai.helper2.search.ShadowLibraryStore
import ai.helper2.search.UnitOfWork
import java.time.Clock
import org.slf4j.LoggerFactory

class ManageShadowLibraries(
  private val store: ShadowLibraryStore,
  private val libraries: ShadowLibraries,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
  private val clock: Clock,
) {

  companion object {
    const val MAX_REASON = 500

    const val MAX_NAMES = 200

    private val HIDDEN_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")

    private val log = LoggerFactory.getLogger(ManageShadowLibraries::class.java)
  }

  sealed interface Added {
    data class Done(val library: ShadowLibrary) : Added

    data class Invalid(val problem: ShadowLibraryNames.Problem) : Added

    data class AlreadyListed(val current: ShadowLibrary) : Added

    data object Full : Added

    data object BadReason : Added
  }

  sealed interface Removed {
    data class Done(val library: ShadowLibrary) : Removed

    data object NotFound : Removed

    data object BadReason : Removed
  }

  fun list(): List<ShadowLibrary> = store.all()

  fun add(adminId: String, rawName: String?, reason: String?): Added {
    val note = clean(reason) ?: return Added.BadReason
    val checked = ShadowLibraryNames.check(rawName)
    if (checked is ShadowLibraryNames.Checked.Invalid) {
      return Added.Invalid(checked.problem)
    }
    val name = (checked as ShadowLibraryNames.Checked.Valid).name
    val outcome = unitOfWork.run {
      val outcome = tryAdd(adminId, name, note.text)
      audit.record(
        adminId,
        AuditTrail.Action.ADD_SHADOW_LIBRARY,
        name,
        recorded(outcome),
        AuditTrail.Change(null, name, note.text),
      )
      outcome
    }
    if (outcome is Added.Done) {
      readTheListAgain()
    }
    return outcome
  }

  fun remove(adminId: String, rawName: String?, reason: String?): Removed {
    val note = clean(reason) ?: return Removed.BadReason
    val name =
      (ShadowLibraryNames.check(rawName) as? ShadowLibraryNames.Checked.Valid)?.name
        ?: rawName.orEmpty()
    val outcome = unitOfWork.run {
      val removed = store.remove(name)
      audit.record(
        adminId,
        AuditTrail.Action.REMOVE_SHADOW_LIBRARY,
        name.take(ShadowLibraryNames.MAX_LENGTH),
        if (removed == null) "NOT_FOUND" else "REMOVED",
        AuditTrail.Change(removed?.name, null, note.text),
      )
      if (removed == null) Removed.NotFound else Removed.Done(removed)
    }
    if (outcome is Removed.Done) {
      readTheListAgain()
    }
    return outcome
  }

  private fun tryAdd(adminId: String, name: String, reason: String?): Added {
    val all = store.all()
    all
      .firstOrNull { it.name == name }
      ?.let {
        return Added.AlreadyListed(it)
      }
    if (all.size >= MAX_NAMES) {
      return Added.Full
    }
    val entry = ShadowLibrary(name, adminId, clock.instant(), reason)
    return if (store.add(entry)) Added.Done(entry)
    else Added.AlreadyListed(store.all().first { it.name == name })
  }

  private fun readTheListAgain() {
    try {
      libraries.reload()
    } catch (e: RuntimeException) {
      log.warn(
        "shadow libraries not read again after a change, the next refresh reads them: {}",
        e.javaClass.simpleName,
      )
    }
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
