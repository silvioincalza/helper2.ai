package ai.searchavo.scout

import ai.searchavo.shared.WebAddresses
import java.time.Instant
import java.util.Locale

enum class HostKind {
  WRITING,
  DIRECTORY,
}

data class DiscoveryHost
@JvmOverloads
constructor(
  val host: String,
  val kind: HostKind,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
  val readsLists: Boolean = false,
)

/**
 * The hosts discovery knows by name. [directoryListsRead] holds the directory hosts whose list
 * pages are read; it is a part of [directory].
 */
data class HostLists
@JvmOverloads
constructor(
  val writing: Set<String>,
  val directory: Set<String>,
  val directoryListsRead: Set<String> = emptySet(),
) {
  fun isDirectory(host: String): Boolean = covers(directory, host)

  // UC-019 BR-007: the lists of a directory host are read only when its switch is on.
  fun readsListsOf(host: String): Boolean = covers(directoryListsRead, host)

  private fun covers(hosts: Set<String>, host: String): Boolean = hosts.any {
    host == it || host.endsWith(".$it")
  }

  companion object {
    fun of(hosts: Collection<DiscoveryHost>): HostLists {
      val directories = hosts.filter { it.kind == HostKind.DIRECTORY }
      return HostLists(
        hosts.filter { it.kind == HostKind.WRITING }.map { it.host }.toSet(),
        directories.map { it.host }.toSet(),
        directories.filter { it.readsLists }.map { it.host }.toSet(),
      )
    }
  }
}

interface DiscoveryHostStore {
  fun all(): List<DiscoveryHost>

  fun add(host: DiscoveryHost): Boolean

  fun remove(host: String): DiscoveryHost?

  fun readLists(host: String, on: Boolean): DiscoveryHost?

  fun lists(): HostLists = HostLists.of(all())
}

object HostNames {
  const val MAX_LENGTH = 253
  private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

  enum class Problem {
    EMPTY,
    NOT_A_HOST,
    TOO_BROAD,
    GITHUB,
  }

  sealed interface Checked {
    data class Valid(val host: String) : Checked

    data class Invalid(val problem: Problem) : Checked
  }

  fun check(raw: String?): Checked {
    val host =
      raw?.trim()?.lowercase(Locale.ROOT)?.removeSuffix(".")?.removePrefix("www.")
        ?: return Checked.Invalid(Problem.EMPTY)
    if (host.isEmpty()) {
      return Checked.Invalid(Problem.EMPTY)
    }
    val labels = host.split(".")
    if (host.length > MAX_LENGTH || labels.any { !LABEL.matches(it) }) {
      return Checked.Invalid(Problem.NOT_A_HOST)
    }
    if (
      labels.size < 2 || labels.last().all { it.isDigit() } || WebAddresses.isRegistrySuffix(labels)
    ) {
      return Checked.Invalid(Problem.TOO_BROAD)
    }
    if (host == "github.com" || host.endsWith(".github.com")) {
      return Checked.Invalid(Problem.GITHUB)
    }
    return Checked.Valid(host)
  }
}
