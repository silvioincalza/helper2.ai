package ai.helper2.scout

import java.time.Instant
import java.util.Locale

/**
 * The hosts discovery knows by name (ADR 0022). A WRITING host publishes articles about many
 * products: a post there is an ARTICLE candidate. A DIRECTORY host lists products: a page there is
 * never kept. An admin adds and removes them in the back office.
 */
enum class HostKind {
  WRITING,
  DIRECTORY,
}

/** One host of a list, with who added it, when and why. */
data class DiscoveryHost(
  val host: String,
  val kind: HostKind,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
)

/** The two lists as one discovery run applies them. */
data class HostLists(val writing: Set<String>, val directory: Set<String>) {
  companion object {
    fun of(hosts: Collection<DiscoveryHost>): HostLists =
      HostLists(
        hosts.filter { it.kind == HostKind.WRITING }.map { it.host }.toSet(),
        hosts.filter { it.kind == HostKind.DIRECTORY }.map { it.host }.toSet(),
      )
  }
}

/**
 * Outbound port: the stored hosts, kept by the Scout in discovery_host. A collector reads them once
 * at the start of each run; a read that fails fails the run, so discovery never runs without its
 * lists.
 */
interface DiscoveryHostStore {
  /** Every host, by name. */
  fun all(): List<DiscoveryHost>

  /** Adds the host; false when it is in a list already (of either kind). */
  fun add(host: DiscoveryHost): Boolean

  /** Removes the host; the removed row, or null when it was in no list. */
  fun remove(host: String): DiscoveryHost?

  /** The two lists, for one run. */
  fun lists(): HostLists = HostLists.of(all())
}

/**
 * A host name as a list keeps it: lower case, no trailing dot, no "www.". A list entry also matches
 * every subdomain (blog.example.com matches example.com), so an entry needs a name and a top-level
 * domain: "com" alone would match nearly every site. github.com is refused: discovery reads its
 * repositories by their own rule.
 */
object HostNames {
  const val MAX_LENGTH = 253
  private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

  /** Second levels that registries sell names under ("co.uk", "com.au"): as broad as a TLD. */
  private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

  /** Why a host is refused. */
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
