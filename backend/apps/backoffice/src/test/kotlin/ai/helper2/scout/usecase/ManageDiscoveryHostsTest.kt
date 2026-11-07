package ai.helper2.scout.usecase

import ai.helper2.identity.AuditTrail
import ai.helper2.scout.HostKind
import ai.helper2.scout.HostNames
import ai.helper2.scout.InMemoryDiscoveryHostStore
import ai.helper2.scout.SeedDiscoveryHosts
import ai.helper2.scout.UnitOfWork
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Discovery's host lists in the back office (ADR 0022): what is refused, and the audit entries. */
class ManageDiscoveryHostsTest {

  private val now = Instant.parse("2026-11-06T22:00:00Z")
  private val store: InMemoryDiscoveryHostStore = SeedDiscoveryHosts.store()
  private val audit = mutableListOf<String>()

  private val trail =
    object : AuditTrail {
      override fun record(
        adminId: String,
        action: AuditTrail.Action,
        targetId: String?,
        outcome: String,
      ) {
        audit += "$adminId $action $targetId $outcome"
      }

      override fun record(
        adminId: String,
        action: AuditTrail.Action,
        targetId: String?,
        outcome: String,
        change: AuditTrail.Change,
      ) {
        audit +=
          "$adminId $action $targetId $outcome ${change.before} ${change.after} ${change.reason}"
      }
    }

  private var transactions = 0
  private val unitOfWork =
    object : UnitOfWork {
      override fun <T : Any> run(work: Supplier<T>): T {
        transactions++
        return work.get()
      }
    }

  private val hosts =
    ManageDiscoveryHosts(store, trail, unitOfWork, Clock.fixed(now, ZoneOffset.UTC))

  @Test
  fun `an added host is in its list for the next run, and the audit says who, what and why`() {
    val added = hosts.add("42", " WWW.Example-News.com. ", HostKind.WRITING, "a tech news site")

    assertIs<ManageDiscoveryHosts.Added.Done>(added)
    assertEquals("example-news.com", added.host.host)
    assertTrue("example-news.com" in store.lists().writing)
    assertEquals(
      listOf("42 ADD_DISCOVERY_HOST example-news.com ADDED null WRITING a tech news site"),
      audit,
    )
    assertEquals(1, transactions)
  }

  @Test
  fun `a host in either list is not added again, so it is never both kinds`() {
    val added = hosts.add("42", "medium.com", HostKind.DIRECTORY, null)

    assertIs<ManageDiscoveryHosts.Added.AlreadyListed>(added)
    assertEquals(HostKind.WRITING, added.current.kind)
    assertTrue("medium.com" !in store.lists().directory)
    assertEquals(
      listOf("42 ADD_DISCOVERY_HOST medium.com ALREADY_LISTED null DIRECTORY null"),
      audit,
    )
  }

  @ParameterizedTest
  @CsvSource(
    "'', EMPTY",
    "com, TOO_BROAD",
    "co.uk, TOO_BROAD",
    "10.0.0.1, TOO_BROAD",
    "https://acme.ai/, NOT_A_HOST",
    "acme.ai/blog, NOT_A_HOST",
    "acme.ai:8080, NOT_A_HOST",
    "'acme .ai', NOT_A_HOST",
    "-acme.ai, NOT_A_HOST",
    "github.com, GITHUB",
    "gist.github.com, GITHUB",
  )
  fun `a host that is not one, or that would match too much, is refused before anything`(
    raw: String,
    problem: HostNames.Problem,
  ) {
    assertEquals(
      ManageDiscoveryHosts.Added.Invalid(problem),
      hosts.add("42", raw, HostKind.WRITING, null),
    )
    assertEquals(0, transactions)
    assertEquals(SeedDiscoveryHosts.LISTS, store.lists())
  }

  @Test
  fun `a reason with a line break or over 500 characters is refused`() {
    assertEquals(
      ManageDiscoveryHosts.Added.BadReason,
      hosts.add("42", "acme-news.com", HostKind.WRITING, "one\ntwo"),
    )
    assertEquals(
      ManageDiscoveryHosts.Removed.BadReason,
      hosts.remove("42", "medium.com", "x".repeat(501)),
    )
    assertEquals(0, transactions)
  }

  @Test
  fun `a removed host is gone from its list, and the audit keeps its kind`() {
    val removed = hosts.remove("42", "producthunt.com", "lists real products too")

    assertIs<ManageDiscoveryHosts.Removed.Done>(removed)
    assertTrue("producthunt.com" !in store.lists().directory)
    assertEquals(
      listOf(
        "42 REMOVE_DISCOVERY_HOST producthunt.com REMOVED DIRECTORY null lists real products too"
      ),
      audit,
    )
  }

  @Test
  fun `removing a host in no list is NOT_FOUND`() {
    assertEquals(ManageDiscoveryHosts.Removed.NotFound, hosts.remove("42", "unknown.ai", null))
    assertEquals(listOf("42 REMOVE_DISCOVERY_HOST unknown.ai NOT_FOUND null null null"), audit)
  }

  @Test
  fun `the lists hold at most MAX_HOSTS hosts`() {
    repeat(ManageDiscoveryHosts.MAX_HOSTS - store.all().size) {
      hosts.add("42", "site$it.example", HostKind.WRITING, null)
    }

    assertEquals(
      ManageDiscoveryHosts.Added.Full,
      hosts.add("42", "one-more.example", HostKind.WRITING, null),
    )
  }
}
