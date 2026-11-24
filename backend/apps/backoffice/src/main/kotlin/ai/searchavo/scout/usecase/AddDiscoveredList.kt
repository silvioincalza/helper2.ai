package ai.searchavo.scout.usecase

import ai.searchavo.scout.DiscoveredLists
import ai.searchavo.scout.DiscoveredLists.Outcome
import ai.searchavo.scout.HostsNeverRead
import ai.searchavo.scout.ListAddresses
import ai.searchavo.scout.ListKind
import ai.searchavo.scout.ListOrigin
import ai.searchavo.scout.ListSeed
import ai.searchavo.scout.ListSeedStore
import ai.searchavo.shared.CanonicalUrl
import java.time.Clock

/**
 * Adds a list page discovery found on a directory host as a list to read. The page follows the
 * rules of a web page an administrator adds; when one refuses it, the page waits for review.
 */
class AddDiscoveredList(
  private val store: ListSeedStore,
  private val neverRead: HostsNeverRead,
  private val maxLists: Int,
  private val clock: Clock,
) : DiscoveredLists {

  override fun add(page: CanonicalUrl, foundBy: String): Outcome {
    // UC-020 BR-001: a discovered page has the address of a web page an administrator may add.
    val list = ListAddresses.check(page.value) as? ListAddresses.Checked.Valid
    if (list == null || list.kind != ListKind.WEB_PAGE) {
      return Outcome.WAITS_FOR_REVIEW
    }
    // UC-020 BR-002: a host that is never read wins over the switch of a directory host.
    if (neverRead.refusalOf(list.address) != null) {
      return Outcome.WAITS_FOR_REVIEW
    }
    val all = store.all()
    if (all.any { it.identity == list.identity }) {
      return Outcome.ALREADY_ADDED
    }
    // UC-030 BR-003: discovery adds at most its own number of lists; past it a page waits for
    // review.
    if (all.count { it.origin == ListOrigin.DISCOVERY } >= maxLists) {
      return Outcome.WAITS_FOR_REVIEW
    }
    val seed =
      ListSeed(
        ListKind.WEB_PAGE,
        list.address,
        foundBy,
        clock.instant(),
        null,
        origin = ListOrigin.DISCOVERY,
      )
    return if (store.add(seed)) Outcome.ADDED else Outcome.ALREADY_ADDED
  }
}
