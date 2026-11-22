package ai.helper2.scout.usecase

import ai.helper2.platform.AuditTrail
import ai.helper2.scout.InMemoryListSeedStore
import ai.helper2.scout.ListKind
import ai.helper2.scout.ListOrigin
import ai.helper2.scout.ListReading
import ai.helper2.scout.ListSeed
import ai.helper2.shared.UnitOfWork
import java.time.Instant
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ForgetGoneListSeedTest {

  private val page = "https://www.acme.dev/blog/Awesome-LLM"
  private val added = Instant.parse("2026-11-19T21:00:00Z")
  private val firstCheck = Instant.parse("2026-11-20T03:07:00.123456Z")
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
  private var openTransactions = 0
  private val writtenInATransaction = mutableListOf<Boolean>()
  private val unitOfWork =
    object : UnitOfWork {
      override fun <T : Any> run(work: Supplier<T>): T {
        transactions++
        openTransactions++
        try {
          return work.get()
        } finally {
          openTransactions--
        }
      }
    }

  private val records = ForgetGoneListSeed(store, trail, unitOfWork)

  private fun goneOnThreeChecks(origin: ListOrigin) {
    store.add(ListSeed(ListKind.WEB_PAGE, page, "discovery-coding", added, null, origin = origin))
    store.readSucceeded(page, ListReading(added, emptyList(), emptyList(), emptyList(), 0, 0))
    store.goneChecked(page, firstCheck)
    store.goneChecked(page, firstCheck.plusSeconds(3600))
    store.goneChecked(page, firstCheck.plusSeconds(90_000))
  }

  @Test
  fun `UC-030 BR-012 the removal of a discovered list that is gone is audited in the name of the system`() {
    goneOnThreeChecks(ListOrigin.DISCOVERY)

    records.forget(page)

    assertTrue(store.all().isEmpty())
    assertFalse(page in store.lastReads())
    assertEquals(
      listOf(
        "system REMOVE_GONE_LIST_SEED $page REMOVED null null" +
          " 3 checks in a row said the page is gone, the first at 2026-11-20T03:07:00Z"
      ),
      audit,
    )
    assertEquals(1, transactions)
  }

  @Test
  fun `UC-030 BR-012 the row and its audit entry are written in one transaction`() {
    goneOnThreeChecks(ListOrigin.DISCOVERY)
    val trailInATransaction =
      object : AuditTrail by trail {
        override fun record(
          adminId: String,
          action: AuditTrail.Action,
          targetId: String?,
          outcome: String,
          change: AuditTrail.Change,
        ) {
          writtenInATransaction += openTransactions == 1 && store.all().isEmpty()
        }
      }

    ForgetGoneListSeed(store, trailInATransaction, unitOfWork).forget(page)

    assertEquals(listOf(true), writtenInATransaction)
  }

  @Test
  fun `UC-030 BR-012 a gone list an administrator added stays a list to read and gets no audit entry`() {
    goneOnThreeChecks(ListOrigin.ADMINISTRATOR)

    records.forget(page)

    assertEquals(listOf(page), store.all().map { it.address })
    assertFalse(page in store.lastReads())
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `UC-030 BR-012 a list that is no longer a list to read gets no audit entry`() {
    records.forget(page)

    assertTrue(audit.isEmpty())
  }
}
