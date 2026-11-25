package ai.searchavo.scout.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.scout.GoneListSeeds
import ai.searchavo.scout.ListSeed
import ai.searchavo.scout.ListSeedStore
import ai.searchavo.shared.UnitOfWork
import java.time.temporal.ChronoUnit

/**
 * Cleans the Scout's own record of a web page list whose site says the page is gone (UC-030
 * BR-012). A list discovery added is no longer a list to read, and the audit trail says so in the
 * name of the system, because no administrator removed it.
 */
class ForgetGoneListSeed(
  private val store: ListSeedStore,
  private val audit: AuditTrail,
  private val unitOfWork: UnitOfWork,
) : GoneListSeeds {

  // UC-030 BR-012: the list leaves the lists to read and its audit entry is written in one
  // transaction, so there is no removal without its entry and no entry without its removal.
  override fun forget(address: String) {
    unitOfWork.runWithoutResult { store.forgotten(address)?.let(::recordRemoval) }
  }

  private fun recordRemoval(removed: ListSeed) {
    audit.record(
      AuditTrail.SYSTEM,
      AuditTrail.Action.REMOVE_GONE_LIST_SEED,
      removed.address,
      "REMOVED",
      AuditTrail.Change(null, null, checksOf(removed)),
    )
  }

  private fun checksOf(removed: ListSeed): String {
    val first = removed.goneSince?.truncatedTo(ChronoUnit.SECONDS)
    return "${removed.goneChecks} checks in a row said the page is gone, the first at $first"
  }
}
