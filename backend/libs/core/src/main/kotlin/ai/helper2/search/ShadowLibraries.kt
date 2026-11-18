package ai.helper2.search

import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

data class ShadowLibrary(
  val name: String,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
)

interface ShadowLibraryStore {
  fun all(): List<ShadowLibrary>

  fun names(): Set<String>

  fun add(library: ShadowLibrary): Boolean

  fun remove(name: String): ShadowLibrary?
}

object ShadowLibraryNames {
  @JvmField
  val SEEDED =
    setOf(
      "z-lib",
      "z-library",
      "zlibrary",
      "1lib",
      "singlelogin",
      "libgen",
      "sci-hub",
      "annas-archive",
      "pdfdrive",
    )

  const val MIN_LENGTH = 3
  const val MAX_LENGTH = 63
  private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

  private val TOO_COMMON =
    setOf(
      "www",
      "com",
      "net",
      "org",
      "edu",
      "gov",
      "mil",
      "int",
      "biz",
      "info",
      "app",
      "dev",
      "api",
      "cdn",
      "web",
      "blog",
      "docs",
      "news",
      "mail",
      "static",
    )

  enum class Problem {
    EMPTY,
    NOT_A_NAME,
    TOO_SHORT,
    TOO_COMMON,
    A_SOURCE,
  }

  sealed interface Checked {
    data class Valid(val name: String) : Checked

    data class Invalid(val problem: Problem) : Checked
  }

  fun check(raw: String?): Checked {
    val name = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
      name.isEmpty() -> Checked.Invalid(Problem.EMPTY)
      name.length > MAX_LENGTH || !LABEL.matches(name) -> Checked.Invalid(Problem.NOT_A_NAME)
      name.length < MIN_LENGTH -> Checked.Invalid(Problem.TOO_SHORT)
      name in TOO_COMMON -> Checked.Invalid(Problem.TOO_COMMON)
      else -> Checked.Valid(name)
    }
  }

  fun namesIn(hosts: Collection<String>): Set<String> =
    hosts.flatMap { labelsBeforeTheLast(it) }.toSet()

  fun anyNames(names: Set<String>, host: String?): Boolean {
    if (host.isNullOrBlank() || names.isEmpty()) {
      return false
    }
    return labelsBeforeTheLast(host).any { it in names }
  }

  private fun labelsBeforeTheLast(host: String): List<String> =
    host.lowercase(Locale.ROOT).split(".").dropLast(1)
}

class ShadowLibraries
private constructor(first: Set<String>, private val source: () -> Set<String>) {

  private val names = AtomicReference(first)

  fun isShadowLibrary(host: String?): Boolean = ShadowLibraryNames.anyNames(names.get(), host)

  fun reload() {
    names.set(source().toSet())
  }

  companion object {
    @JvmStatic
    fun readFrom(store: ShadowLibraryStore): ShadowLibraries =
      ShadowLibraries(ShadowLibraryNames.SEEDED, store::names)

    @JvmStatic
    fun of(vararg names: String): ShadowLibraries = names.toSet().let { ShadowLibraries(it) { it } }
  }
}

interface UnitOfWork {
  fun <T : Any> run(work: Supplier<T>): T
}
