package ai.helper2.scout

import java.time.Instant
import java.util.Locale

data class ListSeed(
  val repository: String,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
)

interface ListSeedStore {
  fun all(): List<ListSeed>

  fun add(seed: ListSeed): Boolean

  fun remove(repository: String): ListSeed?
}

object ListAddresses {
  const val MAX_LENGTH = 300

  private val REPOSITORY =
    Regex("^(?:https://github\\.com/)?([a-z0-9-]{1,39})/([a-z0-9._-]{1,100}?)(?:\\.git)?/?$")

  private val NOT_OWNERS =
    setOf("topics", "orgs", "sponsors", "features", "marketplace", "apps", "collections", "search")

  enum class Problem {
    EMPTY,
    NOT_A_GITHUB_REPOSITORY,
  }

  sealed interface Checked {
    data class Valid(val repository: String) : Checked

    data class Invalid(val problem: Problem) : Checked
  }

  fun check(raw: String?): Checked {
    val address = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (address.isEmpty()) {
      return Checked.Invalid(Problem.EMPTY)
    }
    val match = if (address.length > MAX_LENGTH) null else REPOSITORY.matchEntire(address)
    val owner = match?.groupValues?.get(1)
    val name = match?.groupValues?.get(2)
    if (owner == null || name == null || owner in NOT_OWNERS || name == "." || name == "..") {
      return Checked.Invalid(Problem.NOT_A_GITHUB_REPOSITORY)
    }
    return Checked.Valid("$owner/$name")
  }
}
