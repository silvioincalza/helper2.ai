package ai.searchavo.search.usecase

import ai.searchavo.platform.AuditTrail
import ai.searchavo.search.ShadowLibraries
import ai.searchavo.search.ShadowLibrary
import ai.searchavo.search.ShadowLibraryNames
import ai.searchavo.search.ShadowLibraryStore
import ai.searchavo.shared.UnitOfWork
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ManageShadowLibrariesTest {

  private val now = Instant.parse("2026-11-18T03:00:00Z")

  private val store =
    object : ShadowLibraryStore {
      val rows = linkedMapOf("libgen" to ShadowLibrary("libgen", "migration", Instant.EPOCH, null))

      override fun all(): List<ShadowLibrary> = rows.values.sortedBy { it.name }

      override fun names(): Set<String> = rows.keys.toSet()

      override fun add(library: ShadowLibrary): Boolean =
        rows.putIfAbsent(library.name, library) == null

      override fun remove(name: String): ShadowLibrary? = rows.remove(name)
    }

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

  private val libraries = ShadowLibraries.readFrom(store).also { it.reload() }

  private val manage =
    ManageShadowLibraries(store, libraries, trail, unitOfWork, Clock.fixed(now, ZoneOffset.UTC))

  @Test
  fun `an added name blocks its sites at once, and the audit says who, what and why`() {
    val added = manage.add("42", " Sci-Hub ", "papers without a licence")

    assertIs<ManageShadowLibraries.Added.Done>(added)
    assertEquals(ShadowLibrary("sci-hub", "42", now, "papers without a licence"), added.library)
    assertTrue(libraries.isShadowLibrary("sci-hub.se"))
    assertEquals(
      listOf("42 ADD_SHADOW_LIBRARY sci-hub ADDED null sci-hub papers without a licence"),
      audit,
    )
    assertEquals(1, transactions)
  }

  @Test
  fun `a listed name is not added again`() {
    val added = manage.add("42", "libgen", null)

    assertIs<ManageShadowLibraries.Added.AlreadyListed>(added)
    assertEquals("migration", added.current.addedBy)
    assertEquals(listOf("42 ADD_SHADOW_LIBRARY libgen ALREADY_LISTED null libgen null"), audit)
  }

  @Test
  fun `a name that is not one host label is refused before anything is written`() {
    val added = manage.add("42", "libgen.rs", null)

    assertEquals(
      ManageShadowLibraries.Added.Invalid(ShadowLibraryNames.Problem.NOT_A_NAME),
      added,
    )
    assertEquals(0, transactions)
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `a name that would hide a source is refused before anything is written`() {
    val guarded =
      ManageShadowLibraries(
        store,
        libraries,
        trail,
        unitOfWork,
        Clock.fixed(now, ZoneOffset.UTC),
        listOf("github.com", "huggingface.co", "news.ycombinator.com", "www.npmjs.com"),
      )

    listOf("GitHub", "huggingface", "ycombinator", "npmjs").forEach {
      assertEquals(
        ManageShadowLibraries.Added.Invalid(ShadowLibraryNames.Problem.A_SOURCE),
        guarded.add("42", it, null),
      )
    }
    assertIs<ManageShadowLibraries.Added.Done>(guarded.add("42", "sci-hub", null))
    assertEquals(1, transactions)
  }

  @Test
  fun `a listed name that a source uses is named, so startup can warn of it`() {
    val guarded =
      ManageShadowLibraries(
        store,
        libraries,
        trail,
        unitOfWork,
        Clock.fixed(now, ZoneOffset.UTC),
        listOf("libgen.example", "github.com"),
      )

    assertEquals(setOf("libgen"), guarded.namesThatHideASource())
  }

  @Test
  fun `a reason on more than one line is refused`() {
    assertEquals(ManageShadowLibraries.Added.BadReason, manage.add("42", "sci-hub", "one\ntwo"))
    assertEquals(
      ManageShadowLibraries.Removed.BadReason,
      manage.remove("42", "libgen", "x".repeat(ManageShadowLibraries.MAX_REASON + 1)),
    )
    assertTrue(audit.isEmpty())
  }

  @Test
  fun `a full list takes no more names`() {
    repeat(ManageShadowLibraries.MAX_NAMES - 1) { manage.add("42", "mirror-$it", null) }
    audit.clear()

    assertEquals(ManageShadowLibraries.Added.Full, manage.add("42", "sci-hub", null))
    assertEquals(listOf("42 ADD_SHADOW_LIBRARY sci-hub FULL null sci-hub null"), audit)
  }

  @Test
  fun `a removed name lets its sites through again, and the audit keeps the name`() {
    val removed = manage.remove("42", "LibGen", "a publisher now")

    assertIs<ManageShadowLibraries.Removed.Done>(removed)
    assertFalse(libraries.isShadowLibrary("libgen.rs"))
    assertEquals(
      listOf("42 REMOVE_SHADOW_LIBRARY libgen REMOVED libgen null a publisher now"),
      audit,
    )
  }

  @Test
  fun `removing a name that is not listed changes nothing and is still audited`() {
    assertEquals(ManageShadowLibraries.Removed.NotFound, manage.remove("42", "sci-hub", null))
    assertTrue(libraries.isShadowLibrary("libgen.rs"))
    assertEquals(listOf("42 REMOVE_SHADOW_LIBRARY sci-hub NOT_FOUND null null null"), audit)
  }

  @Test
  fun `a change stays saved when the list cannot be read again, the next refresh reads it`() {
    val failing =
      ShadowLibraries.readFrom(
        object : ShadowLibraryStore by store {
          override fun names(): Set<String> = throw IllegalStateException("database down")
        }
      )
    val manageWithFailingRead =
      ManageShadowLibraries(store, failing, trail, unitOfWork, Clock.fixed(now, ZoneOffset.UTC))

    assertIs<ManageShadowLibraries.Added.Done>(manageWithFailingRead.add("42", "sci-hub", null))
    assertTrue("sci-hub" in store.names())
  }
}
