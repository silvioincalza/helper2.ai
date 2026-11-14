package ai.helper2.scout

import ai.helper2.catalog.ScoutPolicy
import ai.helper2.shared.ResourceType
import ai.helper2.shared.Verification
import ai.helper2.shared.WebSources
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

object FieldTargets {
  @JvmStatic
  fun forType(type: ResourceType): Set<String> =
    when (type) {
      ResourceType.PRODUCT,
      ResourceType.AGENT -> setOf("pricing", "platforms", "useCase")
      ResourceType.MCP_SERVER,
      ResourceType.SKILL -> setOf("license", "openSource", "platforms", "useCase")
      ResourceType.LIBRARY -> setOf("license", "openSource", "platforms")
      ResourceType.MODEL,
      ResourceType.DATASET -> setOf("license", "openSource", "useCase")
      ResourceType.PROMPT -> setOf("useCase")
      else -> emptySet()
    }

  @JvmStatic
  fun missing(
    type: ResourceType,
    statedBySource: Set<String>,
    result: ValidationResult,
  ): Set<String> =
    missing(
      type,
      statedBySource,
      result.fields.filter { it.verification == Verification.VERIFIED }.map { it.field }.toSet(),
    )

  @JvmStatic
  fun missing(
    type: ResourceType,
    statedBySource: Set<String>,
    verified: Set<String>,
  ): Set<String> = forType(type) - statedBySource - verified
}

data class PageTarget(val url: URI, val anchorRecordId: UUID)

object PageTargets {
  private fun isPageSource(sourceId: String): Boolean =
    sourceId == "curated" || sourceId == GITHUB_AWESOME || WebSources.isDiscovery(sourceId)

  private const val GITHUB_AWESOME = "github-awesome"

  private val PLATFORM_HOSTS =
    setOf(
      "github.com",
      "huggingface.co",
      "npmjs.com",
      "www.npmjs.com",
      "pypi.org",
      "arxiv.org",
      "registry.modelcontextprotocol.io",
    )

  @JvmStatic
  fun select(records: List<GapSourceRecord>): PageTarget? = records.firstNotNullOfOrNull { r ->
    if (!isPageSource(r.sourceId)) return@firstNotNullOfOrNull null
    val url =
      runCatching { URI(r.sourceUrl.trim()) }.getOrNull() ?: return@firstNotNullOfOrNull null
    val host = url.host?.lowercase(Locale.ROOT) ?: return@firstNotNullOfOrNull null
    if (host in PLATFORM_HOSTS || PageScope.plainUrlProblem(url) != null) null
    else PageTarget(url, r.id)
  }
}

fun interface DomainRoots {
  fun registrable(host: String): String?
}

class PageScope
private constructor(
  private val host: String,
  private val domain: String?,
  private val pathPrefix: String?,
  private val roots: DomainRoots,
) {

  companion object {
    val SHARED_HOSTS =
      setOf(
        "github.io",
        "vercel.app",
        "netlify.app",
        "pages.dev",
        "hf.space",
        "notion.site",
        "webflow.io",
      )

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    @JvmStatic
    fun of(start: URI, roots: DomainRoots): PageScope? {
      if (plainUrlProblem(start) != null) return null
      val host = start.host.lowercase(Locale.ROOT)
      val domain = roots.registrable(host) ?: return null
      val shared = SHARED_HOSTS.any { host == it || host.endsWith(".$it") }
      val path = start.path.orEmpty().trimEnd('/')
      return when {
        path.isNotEmpty() -> PageScope(host, null, path, roots)
        shared -> PageScope(host, null, null, roots)
        else -> PageScope(host, domain, null, roots)
      }
    }

    @JvmStatic
    fun plainUrlProblem(url: URI): String? {
      val host = url.host?.lowercase(Locale.ROOT)
      return when {
        !"https".equals(url.scheme, ignoreCase = true) -> "not https"
        host.isNullOrBlank() -> "no host"
        url.rawUserInfo != null -> "user info"
        url.port != -1 && url.port != 443 -> "port"
        host.startsWith("[") || ':' in host || IPV4.matches(host) -> "ip address"
        '.' !in host -> "no domain"
        else -> null
      }
    }
  }

  fun allows(url: URI): Boolean {
    if (plainUrlProblem(url) != null) return false
    val h = url.host.lowercase(Locale.ROOT)
    return when {
      pathPrefix != null -> h == host && pathUnder(url.path.orEmpty(), pathPrefix)
      domain == null -> h == host
      else -> roots.registrable(h) == domain
    }
  }

  fun isStartHost(url: URI): Boolean = url.host?.lowercase(Locale.ROOT) == host

  private fun pathUnder(path: String, prefix: String): Boolean =
    path == prefix || path.startsWith("$prefix/")
}

object OfficialLinks {
  private val WORDS =
    listOf(
      "pricing",
      "plans",
      "download",
      "downloads",
      "features",
      "docs",
      "documentation",
      "integrations",
      "about",
    )

  private val MARKDOWN_LINK = Regex("\\]\\(\\s{0,5}([^)\\s]{1,2000})[^)]{0,300}\\)")

  @JvmStatic
  fun pick(markdown: String, start: URI, scope: PageScope, max: Int): List<URI> {
    val ranked = mutableListOf<Pair<Int, URI>>()
    val seen = mutableSetOf(key(start))
    for (m in MARKDOWN_LINK.findAll(markdown.take(UntrustedPageText.MAX_RAW_CHARS))) {
      val url = resolve(start, m.groupValues[1]) ?: continue
      val rank = WORDS.indexOf(lastSegment(url))
      if (rank < 0 || !scope.allows(url) || !seen.add(key(url))) continue
      ranked += rank to url
    }
    return ranked.sortedBy { it.first }.take(max).map { it.second }
  }

  private fun resolve(start: URI, href: String): URI? = runCatching {
    val u = start.resolve(href.trim())
    URI(u.scheme, u.authority, u.path, null, null)
  }
    .getOrNull()

  private fun lastSegment(url: URI): String =
    url.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)

  private fun key(url: URI): String =
    "${url.host?.lowercase(Locale.ROOT)}${url.path.orEmpty().trimEnd('/')}"
}

data class ReadPage(val url: String, val raw: String)

fun interface PageReader {
  fun extract(urls: List<URI>): List<ReadPage>

  val available: Boolean
    get() = true
}

fun interface ExtractBudget {
  fun tryTake(): Boolean
}

interface PageLedger {
  fun seen(url: URI): Boolean

  fun record(url: URI, contentHash: String)

  companion object {
    @JvmField
    val NONE: PageLedger =
      object : PageLedger {
        override fun seen(url: URI) = false

        override fun record(url: URI, contentHash: String) {}
      }
  }
}

interface PageTextCache {
  fun get(target: URI): List<OfficialPage>?

  fun put(target: URI, pages: List<OfficialPage>)

  companion object {
    @JvmField
    val NONE: PageTextCache =
      object : PageTextCache {
        override fun get(target: URI): List<OfficialPage>? = null

        override fun put(target: URI, pages: List<OfficialPage>) {}
      }
  }
}

data class OfficialPage(
  val url: URI,
  val retrievedAt: Instant,
  val text: String,
  val startHost: Boolean,
)

data class PageReading(val pages: List<OfficialPage>, val outcome: String) {
  companion object {
    fun none(outcome: String) = PageReading(emptyList(), outcome)
  }
}

class OfficialPages
@JvmOverloads
constructor(
  private val reader: PageReader,
  private val budget: ExtractBudget,
  private val roots: DomainRoots,
  private val clock: Clock,
  private val ledger: PageLedger = PageLedger.NONE,
  private val textCache: PageTextCache = PageTextCache.NONE,
) {

  companion object {
    const val MAX_LINKED_PAGES = ScoutPolicy.LINKED_PAGES
    const val MAX_URLS = 5
  }

  fun read(target: PageTarget): PageReading {
    val scope = PageScope.of(target.url, roots) ?: return PageReading.none("not_allowed")
    if (ledger.seen(target.url)) return PageReading.none("skipped_seen")
    textCache.get(target.url)?.let { cached ->
      val allowed = cached.filter { scope.allows(it.url) && it.text.isNotBlank() }
      if (allowed.isNotEmpty()) return PageReading(allowed, "cached")
    }
    if (!budget.tryTake()) return PageReading.none("skipped_cap")
    val home =
      extract(listOf(target.url), scope).firstOrNull() ?: return PageReading.none("no_text")
    val links =
      OfficialLinks.pick(home.raw, target.url, scope, MAX_LINKED_PAGES)
        .filterNot(ledger::seen)
        .take(MAX_URLS - 1)
    val more = if (links.isNotEmpty() && budget.tryTake()) extract(links, scope) else emptyList()
    val pages =
      (listOf(home) + more).mapNotNull { page ->
        val url = URI(page.url)
        val text =
          UntrustedPageText.window(
            UntrustedPageText.clean(page.raw),
            UntrustedPageText.MAX_PAGE_CHARS,
            UntrustedPageText.PAGE_HEADING,
          )
        if (text.isBlank()) null
        else OfficialPage(url, clock.instant(), text, scope.isStartHost(url))
      }
    if (pages.isEmpty()) return PageReading.none("no_text")
    textCache.put(target.url, pages)
    return PageReading(pages, "read")
  }

  fun markRead(target: PageTarget, pages: List<OfficialPage>) {
    val home = pages.firstOrNull { sameUrl(it.url.toString(), target.url) } ?: pages.firstOrNull()
    ledger.record(target.url, sha256Prefix(home?.text.orEmpty()))
    pages
      .filterNot { sameUrl(it.url.toString(), target.url) }
      .forEach {
        ledger.record(it.url, sha256Prefix(it.text))
      }
  }

  private fun extract(urls: List<URI>, scope: PageScope): List<ReadPage> =
    reader.extract(urls).filter { page ->
      val url = runCatching { URI(page.url) }.getOrNull()
      url != null && scope.allows(url) && page.raw.isNotBlank()
    }

  private fun sameUrl(returned: String, asked: URI): Boolean = runCatching {
    URI(returned)
  }
    .map { it.host.equals(asked.host, ignoreCase = true) && it.path == asked.path }
    .getOrDefault(false)
}
