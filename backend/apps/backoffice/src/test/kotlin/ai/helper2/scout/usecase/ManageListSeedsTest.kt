package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.HostsNeverRead
import ai.helper2.scout.InMemoryListSeedStore
import ai.helper2.scout.ListAddresses
import ai.helper2.scout.ListKind
import ai.helper2.scout.ListOrigin
import ai.helper2.scout.ListSeed
import ai.helper2.shared.UnitOfWork
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ManageListSeedsTest {

  private val now = Instant.parse("2026-11-19T21:00:00Z")
  private val store = InMemoryListSeedStore()
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

  private val neverRead =
    HostsNeverRead(
      mapOf("coursera.org" to "its terms forbid automated reading without written consent")
    )

  private val seeds =
    ManageListSeeds(store, neverRead, trail, unitOfWork, Clock.fixed(now, ZoneOffset.UTC))

  @Test
  fun `an added list is read at the next pass, and the audit says who, what and why`() {
    val added = seeds.add("42", " https://github.com/Acme/Awesome-RAG/ ", "the best RAG list")

    assertIs<ManageListSeeds.Added.Done>(added)
    assertEquals(
      ListSeed(ListKind.GITHUB_REPOSITORY, "acme/awesome-rag", "42", now, "the best RAG list"),
      added.seed,
    )
    assertEquals(listOf(added.seed), store.all())
    assertEquals(
      listOf("42 ADD_LIST_SEED acme/awesome-rag ADDED null acme/awesome-rag the best RAG list"),
      audit,
    )
    assertEquals(1, transactions)
  }

  @ParameterizedTest
  @CsvSource(
    "acme/awesome-rag, acme/awesome-rag",
    "Acme/Awesome.RAG_2, acme/awesome.rag_2",
    "https://github.com/acme/awesome-rag, acme/awesome-rag",
    "https://github.com/acme/awesome-rag.git, acme/awesome-rag",
  )
  fun `a repository is kept as owner and name in lower case`(raw: String, kept: String) {
    assertEquals(
      ListAddresses.Checked.Valid(ListKind.GITHUB_REPOSITORY, kept),
      ListAddresses.check(raw),
    )
  }

  @ParameterizedTest
  @CsvSource(
    "https://Blog.Acme.dev/Best-AI-Tools, https://blog.acme.dev/Best-AI-Tools",
    "' https://www.acme.dev/lists/rag/ ', https://www.acme.dev/lists/rag/",
    "https://acme.dev/lists?page=2, https://acme.dev/lists?page=2",
    "https://acme.dev:443/list, https://acme.dev/list",
    "https://gitlab.com/acme/awesome-rag, https://gitlab.com/acme/awesome-rag",
  )
  fun `a web page is kept as its https address, host in lower case`(raw: String, kept: String) {
    assertEquals(ListAddresses.Checked.Valid(ListKind.WEB_PAGE, kept), ListAddresses.check(raw))
  }

  @Test
  fun `an added web page is stored as a web page, with a stable id`() {
    val added = seeds.add("42", "https://blog.acme.dev/best-ai-tools", "a good roundup")

    assertIs<ManageListSeeds.Added.Done>(added)
    assertEquals(ListKind.WEB_PAGE, added.seed.kind)
    assertEquals("https://blog.acme.dev/best-ai-tools", added.seed.address)
    assertEquals(ListAddresses.idOf("https://blog.acme.dev/best-ai-tools"), added.seed.id)
    assertEquals(
      "42 ADD_LIST_SEED https://blog.acme.dev/best-ai-tools ADDED null" +
        " https://blog.acme.dev/best-ai-tools a good roundup",
      audit.single(),
    )
  }

  @Test
  fun `a web page is added once, with or without www and a trailing slash`() {
    seeds.add("42", "https://acme.dev/lists/rag", null)

    val again = seeds.add("43", "https://www.acme.dev/lists/rag/", null)

    assertIs<ManageListSeeds.Added.AlreadyListed>(again)
    assertEquals("https://acme.dev/lists/rag", again.current.address)
    assertEquals(1, store.all().size)
  }

  @ParameterizedTest
  @CsvSource(
    "https://www.coursera.org/articles/ai-courses",
    "https://coursera.org/",
    "https://blog.Coursera.org/best-ai-courses",
  )
  fun `a page on a host that is never read is refused with the reason, before any write`(
    raw: String
  ) {
    val added = seeds.add("42", raw, null)

    assertEquals(
      ManageListSeeds.Added.NeverRead(
        "coursera.org",
        "its terms forbid automated reading without written consent",
      ),
      added,
    )
    assertEquals(emptyList(), store.all())
    assertEquals(0, transactions)
  }

  @Test
  fun `a host that only ends like one never read is another host`() {
    assertIs<ManageListSeeds.Added.Done>(seeds.add("42", "https://notcoursera.org/list", null))
  }

  @ParameterizedTest
  @CsvSource(
    "'', EMPTY",
    "'   ', EMPTY",
    "awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "http://github.com/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://gist.github.com/acme/0123456789abcdef, NOT_A_GITHUB_REPOSITORY",
    "http://blog.acme.dev/best-ai-tools, NOT_HTTPS",
    "ftp://blog.acme.dev/best-ai-tools, NOT_HTTPS",
    "https://github.com.evil.example/acme/awesome-rag, NOT_A_PUBLIC_HOST",
    "https://127.0.0.1/list, NOT_A_PUBLIC_HOST",
    "https://2130706433/list, NOT_A_PUBLIC_HOST",
    "https://0x7f.0x0.0x0.0x1/list, NOT_A_PUBLIC_HOST",
    "https://[::1]/list, NOT_A_PUBLIC_HOST",
    "https://localhost/list, NOT_A_PUBLIC_HOST",
    "https://wiki.internal/list, NOT_A_PUBLIC_HOST",
    "https://printer.local/list, NOT_A_PUBLIC_HOST",
    "https://xn--80ak6aa92e.com/list, NOT_A_PUBLIC_HOST",
    "https://acme.dev./list, NOT_A_PUBLIC_HOST",
    "https://user@acme.dev/list, NOT_A_PLAIN_PAGE",
    "https://acme.dev:8443/list, NOT_A_PLAIN_PAGE",
    "https://acme.dev/list#tools, NOT_A_PLAIN_PAGE",
    "https://acme.dev/a list, NOT_A_PLAIN_PAGE",
    "https://acme.dev/../list, NOT_A_PLAIN_PAGE",
    "https://user@github.com/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/acme/awesome-rag/blob/main/README.md, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/acme/awesome-rag?tab=readme, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/topics/awesome, NOT_A_GITHUB_REPOSITORY",
    "acme/.., NOT_A_GITHUB_REPOSITORY",
    "acme/awesome rag, NOT_A_GITHUB_REPOSITORY",
  )
  fun `an address that is neither one GitHub repository nor a plain public web page is refused`(
    raw: String,
    problem: String,
  ) {
    val added = seeds.add("42", raw, null)

    assertIs<ManageListSeeds.Added.Invalid>(added)
    assertEquals(ListAddresses.Problem.valueOf(problem), added.problem)
    assertEquals(emptyList(), store.all())
    assertEquals(emptyList(), audit)
  }

  @Test
  fun `a list is added once, whatever the case of its address`() {
    seeds.add("42", "acme/awesome-rag", null)

    val again = seeds.add("43", "https://github.com/ACME/awesome-rag", null)

    assertIs<ManageListSeeds.Added.AlreadyListed>(again)
    assertEquals("42", again.current.addedBy)
    assertEquals(1, store.all().size)
    assertEquals(
      "43 ADD_LIST_SEED acme/awesome-rag ALREADY_LISTED null acme/awesome-rag null",
      audit.last(),
    )
  }

  @Test
  fun `no more lists are added than the collector reads in a pass`() {
    repeat(ManageListSeeds.MAX_SEEDS) { seeds.add("42", "acme/list-$it", null) }

    val added = seeds.add("42", "acme/one-more", null)

    assertEquals(ManageListSeeds.Added.Full, added)
    assertEquals(ManageListSeeds.MAX_SEEDS, store.all().size)
  }

  @Test
  fun `UC-020 BR-003 the lists discovery added do not take the room of an administrator's lists`() {
    repeat(ManageListSeeds.MAX_SEEDS) {
      store.add(
        ListSeed(
          ListKind.WEB_PAGE,
          "https://futurepedia.io/best-$it",
          "discovery-coding",
          now,
          null,
          origin = ListOrigin.DISCOVERY,
        )
      )
    }

    assertIs<ManageListSeeds.Added.Done>(seeds.add("42", "acme/awesome-rag", null))
  }

  @Test
  fun `a reason with a hidden character or over 500 characters is refused before any write`() {
    assertEquals(ManageListSeeds.Added.BadReason, seeds.add("42", "acme/awesome-rag", "a‮b"))
    assertEquals(
      ManageListSeeds.Added.BadReason,
      seeds.add("42", "acme/awesome-rag", "x".repeat(501)),
    )
    assertEquals(
      ManageListSeeds.Removed.BadReason,
      seeds.remove("42", ListAddresses.idOf("acme/awesome-rag"), "a\nb"),
    )
    assertEquals(0, transactions)
  }

  @Test
  fun `a removed list is no longer read, and removing an unknown one is audited too`() {
    seeds.add("42", "acme/awesome-rag", null)
    seeds.add("42", "https://blog.acme.dev/best-ai-tools", null)
    audit.clear()
    val repository = ListAddresses.idOf("acme/awesome-rag")
    val page = ListAddresses.idOf("https://blog.acme.dev/best-ai-tools")

    assertIs<ManageListSeeds.Removed.Done>(seeds.remove("42", repository, "stale"))
    assertIs<ManageListSeeds.Removed.Done>(seeds.remove("42", page, null))
    assertEquals(ManageListSeeds.Removed.NotFound, seeds.remove("42", repository, null))

    assertEquals(emptyList(), store.all())
    assertEquals(
      listOf(
        "42 REMOVE_LIST_SEED acme/awesome-rag REMOVED acme/awesome-rag null stale",
        "42 REMOVE_LIST_SEED https://blog.acme.dev/best-ai-tools REMOVED" +
          " https://blog.acme.dev/best-ai-tools null null",
        "42 REMOVE_LIST_SEED $repository NOT_FOUND null null null",
      ),
      audit,
    )
  }

  // UC-013 BR-004: the value before and the value after hold the whole address.
  @Test
  fun `UC-020 BR-005 a web page of 300 characters is added and removed with its whole address in the audit`() {
    val host = "https://blog.acme.dev/"
    val page = host + "a".repeat(ListAddresses.MAX_LENGTH - host.length)

    val added = seeds.add("42", page, "a long address")
    assertIs<ManageListSeeds.Added.Done>(added)
    assertIs<ManageListSeeds.Removed.Done>(seeds.remove("42", added.seed.id, "stale"))

    assertEquals(
      listOf(
        "42 ADD_LIST_SEED $page ADDED null $page a long address",
        "42 REMOVE_LIST_SEED $page REMOVED $page null stale",
      ),
      audit,
    )
  }
}
