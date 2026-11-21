package ai.helper2.scout

import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.WebAddresses
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class ListKind {
  GITHUB_REPOSITORY,
  WEB_PAGE,
}

/**
 * Who added a list to read: an administrator, or discovery on a directory host whose lists are
 * read.
 */
enum class ListOrigin {
  ADMINISTRATOR,
  DISCOVERY,
}

data class ListSeed
@JvmOverloads
constructor(
  val kind: ListKind,
  val address: String,
  val addedBy: String,
  val addedAt: Instant,
  val reason: String?,
  val readTriedAt: Instant? = null,
  val readFailures: Int = 0,
  val origin: ListOrigin = ListOrigin.ADMINISTRATOR,
) {
  val id: UUID
    get() = ListAddresses.idOf(address)

  val identity: String
    get() = ListAddresses.identityOf(kind, address)
}

data class ListReading
@JvmOverloads
constructor(
  val readAt: Instant,
  val headings: List<String>,
  val entries: List<Entry>,
  val links: List<Link>,
  val textLines: Int,
  val linkedItems: Int,
  val followed: List<Followed> = emptyList(),
) {
  data class Entry(
    val repository: String,
    val section: String,
    val headingPath: String,
    val text: String,
    val inListOfLists: Boolean,
  )

  data class Link(
    val url: String,
    val words: String,
    val section: String,
    val headingPath: String,
    val entryText: String,
  )

  /**
   * UC-030 BR-013: what following one link of the list gave: the address of the item's own page, or
   * nothing. [decidedBy] says who decided that the page is the item's: `code` or `model`.
   */
  data class Followed(val link: String, val item: String?, val steps: Int, val decidedBy: String?)
}

interface ListSeedStore {
  fun all(): List<ListSeed>

  fun add(seed: ListSeed): Boolean

  fun remove(address: String): ListSeed?

  fun readStarted(address: String, at: Instant)

  fun readSucceeded(address: String, reading: ListReading)

  /** UC-030 BR-013: keeps what following the links of the last read of a page gave. */
  fun followed(address: String, followed: List<ListReading.Followed>)

  fun lastReads(): Map<String, ListReading>
}

/** Where discovery hands a list page it found on a directory host whose lists are read. */
fun interface DiscoveredLists {

  enum class Outcome {
    ADDED,
    ALREADY_ADDED,
    WAITS_FOR_REVIEW,
  }

  fun add(page: CanonicalUrl, foundBy: String): Outcome
}

class HostsNeverRead(reasons: Map<String, String>) {

  private val reasons: Map<String, String> = reasons.mapKeys {
    it.key.trim().lowercase(Locale.ROOT)
  }

  data class Refusal(val host: String, val reason: String)

  fun refusal(host: String?): Refusal? {
    val name = host?.trim()?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
    return reasons.entries
      .firstOrNull { name == it.key || name.endsWith(".${it.key}") }
      ?.let { Refusal(it.key, it.value) }
  }

  fun refusalOf(address: String): Refusal? =
    try {
      refusal(URI(address).host)
    } catch (_: URISyntaxException) {
      null
    }

  companion object {
    @JvmField val NONE = HostsNeverRead(emptyMap())
  }
}

object ListAddresses {
  const val MAX_LENGTH = 300

  private val REPOSITORY =
    Regex("^(?:https://github\\.com/)?([a-z0-9-]{1,39})/([a-z0-9._-]{1,100}?)(?:\\.git)?/?$")

  private val NOT_OWNERS =
    setOf("topics", "orgs", "sponsors", "features", "marketplace", "apps", "collections", "search")

  private val HIDDEN = Regex("[\\p{Cc}\\p{Cf}\\s]")

  enum class Problem {
    EMPTY,
    NOT_A_GITHUB_REPOSITORY,
    NOT_HTTPS,
    NOT_A_PUBLIC_HOST,
    NOT_A_PLAIN_PAGE,
  }

  sealed interface Checked {
    data class Valid(val kind: ListKind, val address: String) : Checked {
      val identity: String
        get() = identityOf(kind, address)
    }

    data class Invalid(val problem: Problem) : Checked
  }

  @JvmStatic
  fun idOf(address: String): UUID =
    UUID.nameUUIDFromBytes("list-seed:$address".toByteArray(StandardCharsets.UTF_8))

  @JvmStatic
  fun identityOf(kind: ListKind, address: String): String =
    if (kind == ListKind.WEB_PAGE) CanonicalUrl.of(address)?.value ?: address else address

  @JvmStatic
  fun check(raw: String?): Checked {
    val address = raw?.trim().orEmpty()
    if (address.isEmpty()) {
      return Checked.Invalid(Problem.EMPTY)
    }
    val lower = address.lowercase(Locale.ROOT)
    if (!lower.contains("://")) {
      return repository(lower)
    }
    val url = parse(address) ?: return Checked.Invalid(Problem.NOT_A_PLAIN_PAGE)
    val host = url.host?.lowercase(Locale.ROOT)
    if (host == "github.com" || host?.endsWith(".github.com") == true) {
      return repository(lower)
    }
    return webPage(url)
  }

  private fun repository(address: String): Checked {
    val match = if (address.length > MAX_LENGTH) null else REPOSITORY.matchEntire(address)
    val owner = match?.groupValues?.get(1)
    val name = match?.groupValues?.get(2)
    if (owner == null || name == null || owner in NOT_OWNERS || name == "." || name == "..") {
      return Checked.Invalid(Problem.NOT_A_GITHUB_REPOSITORY)
    }
    return Checked.Valid(ListKind.GITHUB_REPOSITORY, "$owner/$name")
  }

  private fun webPage(url: URI): Checked {
    if (url.rawFragment != null) {
      return Checked.Invalid(
        if ("https".equals(url.scheme, ignoreCase = true)) Problem.NOT_A_PLAIN_PAGE
        else Problem.NOT_HTTPS
      )
    }
    val host =
      when (val address = WebAddresses.check(url)) {
        is WebAddresses.Checked.Refused -> return Checked.Invalid(problemOf(address.refusal))
        is WebAddresses.Checked.Public -> address.host
      }
    val page = "https://$host${url.rawPath.orEmpty()}${url.rawQuery?.let { "?$it" }.orEmpty()}"
    if (page.length > MAX_LENGTH || CanonicalUrl.of(page) == null) {
      return Checked.Invalid(Problem.NOT_A_PLAIN_PAGE)
    }
    return Checked.Valid(ListKind.WEB_PAGE, page)
  }

  private fun problemOf(refusal: WebAddresses.Refusal): Problem =
    when (refusal) {
      WebAddresses.Refusal.NOT_HTTPS -> Problem.NOT_HTTPS
      WebAddresses.Refusal.MALFORMED,
      WebAddresses.Refusal.USER_INFO,
      WebAddresses.Refusal.PORT -> Problem.NOT_A_PLAIN_PAGE
      WebAddresses.Refusal.IP_ADDRESS,
      WebAddresses.Refusal.PRIVATE_HOST,
      WebAddresses.Refusal.PUNYCODE,
      WebAddresses.Refusal.NOT_A_HOST_NAME -> Problem.NOT_A_PUBLIC_HOST
    }

  private fun parse(address: String): URI? {
    if (HIDDEN.containsMatchIn(address)) {
      return null
    }
    return try {
      URI(address)
    } catch (_: URISyntaxException) {
      null
    }
  }
}
