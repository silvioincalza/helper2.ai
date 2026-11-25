package ai.searchavo.scout.usecase

import ai.searchavo.scout.DiscoveredLists.Outcome
import ai.searchavo.scout.HostsNeverRead
import ai.searchavo.scout.InMemoryListSeedStore
import ai.searchavo.scout.ListKind
import ai.searchavo.scout.ListOrigin
import ai.searchavo.scout.ListSeed
import ai.searchavo.shared.CanonicalUrl
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AddDiscoveredListTest {

  private val now = Instant.parse("2026-11-21T03:07:00Z")
  private val store = InMemoryListSeedStore()
  private val neverRead = HostsNeverRead(mapOf("coursera.org" to "never read"))
  private val lists = AddDiscoveredList(store, neverRead, 2, Clock.fixed(now, ZoneOffset.UTC))

  private fun page(address: String): CanonicalUrl = CanonicalUrl.of(address)!!

  @Test
  fun `UC-030 BR-003 a list page discovery found is a list to read, named after its collector`() {
    val outcome = lists.add(page("https://futurepedia.io/best-ai-coding-tools"), "discovery-coding")

    assertEquals(Outcome.ADDED, outcome)
    assertEquals(
      listOf(
        ListSeed(
          ListKind.WEB_PAGE,
          "https://futurepedia.io/best-ai-coding-tools",
          "discovery-coding",
          now,
          null,
          origin = ListOrigin.DISCOVERY,
        )
      ),
      store.all(),
    )
  }

  @Test
  fun `UC-030 BR-003 a page that is a list already is not added twice, whoever added it`() {
    store.add(ListSeed(ListKind.WEB_PAGE, "https://futurepedia.io/best-ai-tools", "42", now, null))

    val outcome = lists.add(page("https://www.futurepedia.io/best-ai-tools/"), "discovery-coding")

    assertEquals(Outcome.ALREADY_ADDED, outcome)
    assertEquals(1, store.all().size)
  }

  @Test
  fun `UC-030 BR-003 past the number of discovered lists a page waits for review`() {
    lists.add(page("https://futurepedia.io/best-1"), "discovery-coding")
    lists.add(page("https://futurepedia.io/best-2"), "discovery-coding")

    val outcome = lists.add(page("https://futurepedia.io/best-3"), "discovery-coding")

    assertEquals(Outcome.WAITS_FOR_REVIEW, outcome)
    assertEquals(2, store.all().size)
  }

  @Test
  fun `UC-030 BR-003 the lists an administrator added do not count against discovery`() {
    repeat(2) { store.add(ListSeed(ListKind.GITHUB_REPOSITORY, "acme/list-$it", "42", now, null)) }

    assertEquals(
      Outcome.ADDED,
      lists.add(page("https://futurepedia.io/best-1"), "discovery-coding"),
    )
  }

  @Test
  fun `UC-020 BR-002 a page on a host that is never read waits for review`() {
    val outcome = lists.add(page("https://www.coursera.org/best-ai-courses"), "discovery-education")

    assertEquals(Outcome.WAITS_FOR_REVIEW, outcome)
    assertEquals(emptyList(), store.all())
  }

  @Test
  fun `UC-020 BR-001 a GitHub address is not a web page discovery adds`() {
    val outcome = lists.add(page("https://github.com/acme/awesome-rag"), "discovery-coding")

    assertEquals(Outcome.WAITS_FOR_REVIEW, outcome)
    assertEquals(emptyList(), store.all())
  }
}
