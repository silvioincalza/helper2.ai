package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.InMemoryListSeedStore
import ai.helper2.scout.ListAddresses
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

  private val seeds = ManageListSeeds(store, trail, unitOfWork, Clock.fixed(now, ZoneOffset.UTC))

  @Test
  fun `an added list is read at the next pass, and the audit says who, what and why`() {
    val added = seeds.add("42", " https://github.com/Acme/Awesome-RAG/ ", "the best RAG list")

    assertIs<ManageListSeeds.Added.Done>(added)
    assertEquals(ListSeed("acme/awesome-rag", "42", now, "the best RAG list"), added.seed)
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
    assertEquals(ListAddresses.Checked.Valid(kept), ListAddresses.check(raw))
  }

  @ParameterizedTest
  @CsvSource(
    "'', EMPTY",
    "'   ', EMPTY",
    "awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "http://github.com/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://gitlab.com/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://github.com.evil.example/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://user@github.com/acme/awesome-rag, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/acme/awesome-rag/blob/main/README.md, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/acme/awesome-rag?tab=readme, NOT_A_GITHUB_REPOSITORY",
    "https://github.com/topics/awesome, NOT_A_GITHUB_REPOSITORY",
    "acme/.., NOT_A_GITHUB_REPOSITORY",
    "acme/awesome rag, NOT_A_GITHUB_REPOSITORY",
    "https://www.coursera.org/articles/ai-courses, NOT_A_GITHUB_REPOSITORY",
  )
  fun `an address that is not one GitHub repository is refused`(raw: String, problem: String) {
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
  fun `a reason with a hidden character or over 500 characters is refused before any write`() {
    assertEquals(ManageListSeeds.Added.BadReason, seeds.add("42", "acme/awesome-rag", "a‮b"))
    assertEquals(
      ManageListSeeds.Added.BadReason,
      seeds.add("42", "acme/awesome-rag", "x".repeat(501)),
    )
    assertEquals(ManageListSeeds.Removed.BadReason, seeds.remove("42", "acme/awesome-rag", "a\nb"))
    assertEquals(0, transactions)
  }

  @Test
  fun `a removed list is no longer read, and removing an unknown one is audited too`() {
    seeds.add("42", "acme/awesome-rag", null)
    audit.clear()

    assertIs<ManageListSeeds.Removed.Done>(seeds.remove("42", "ACME/awesome-rag", "stale"))
    assertEquals(ManageListSeeds.Removed.NotFound, seeds.remove("42", "acme/awesome-rag", null))

    assertEquals(emptyList(), store.all())
    assertEquals(
      listOf(
        "42 REMOVE_LIST_SEED acme/awesome-rag REMOVED acme/awesome-rag null stale",
        "42 REMOVE_LIST_SEED acme/awesome-rag NOT_FOUND null null null",
      ),
      audit,
    )
  }
}
