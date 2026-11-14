package ai.helper2.scout

import java.time.Instant
import java.util.Locale

enum class HostKind {
  WRITING,
  DIRECTORY,
}

data class DiscoveryHost(
  val host: String,
  val kind: HostKind,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
)

data class HostLists(val writing: Set<String>, val directory: Set<String>) {
  companion object {
    fun of(hosts: Collection<DiscoveryHost>): HostLists =
      HostLists(
        hosts.filter { it.kind == HostKind.WRITING }.map { it.host }.toSet(),
        hosts.filter { it.kind == HostKind.DIRECTORY }.map { it.host }.toSet(),
      )
  }
}

interface DiscoveryHostStore {
  fun all(): List<DiscoveryHost>

  fun add(host: DiscoveryHost): Boolean

  fun remove(host: String): DiscoveryHost?

  fun lists(): HostLists = HostLists.of(all())
}

object HostNames {
  const val MAX_LENGTH = 253
  private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

  private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

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
      labels.size < 2 ||
        labels.last().all { it.isDigit() } ||
        (labels.size == 2 && labels.first() in SECOND_LEVEL)
    ) {
      return Checked.Invalid(Problem.TOO_BROAD)
    }
    if (host == "github.com" || host.endsWith(".github.com")) {
      return Checked.Invalid(Problem.GITHUB)
    }
    return Checked.Valid(host)
  }
}
