package ai.helper2.scout

import ai.helper2.shared.ResourceType
import ai.helper2.shared.Verification
import ai.helper2.shared.WebSources
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The evidence ladder (ADR 0056): which fields an enrichment tries to fill for a type, and the
 * product's own pages as one more place to read them. Code picks every URL; the model never does.
 */
object FieldTargets {

  /**
   * The facts a run tries to prove for a resource of [type]. Audience and tech level are judgement
   * fields the model may estimate: they never make a run read more. Reading types (articles,
   * papers, news, events, courses) have none: their facts come from their sources.
   */
  @JvmStatic
  fun forType(type: ResourceType): Set<String> =
    when (type) {
      ResourceType.PRODUCT,
      ResourceType.AGENT -> setOf("pricing", "platforms", "useCase")
      ResourceType.MCP_SERVER,
      ResourceType.SKILL,
      ResourceType.LIBRARY -> setOf("license", "openSource", "platforms", "useCase")
      ResourceType.MODEL,
      ResourceType.DATASET -> setOf("license", "openSource", "useCase")
      ResourceType.PROMPT -> setOf("useCase")
      else -> emptySet()
    }

  /**
   * The target facts of [type] that neither the sources ([sourceFacts]) nor a verified field of
   * [result] state yet.
   */
  @JvmStatic
  fun missing(
    type: ResourceType,
    sourceFacts: Map<String, String>,
    result: ValidationResult,
  ): Set<String> =
    missing(
      type,
      sourceFacts,
      result.fields.filter { it.verification == Verification.VERIFIED }.map { it.field }.toSet(),
    )

  /** The target facts of [type] that neither [sourceFacts] nor the [verified] fields state. */
  @JvmStatic
  fun missing(
    type: ResourceType,
    sourceFacts: Map<String, String>,
    verified: Set<String>,
  ): Set<String> = forType(type) - sourceFacts.keys - verified
}

/** The product's own page a record points at, and the record the read text belongs to. */
data class PageTarget(val url: URI, val anchorRecordId: UUID)

object PageTargets {

  /**
   * True when [sourceId]'s record URL is a product's own page: one a person accepted from any
   * discovery category (`discovery-<category>`, ADR 0051) or one from the editorial seed. Other
   * records point at a registry, a repository or an article, and an MCP Registry entry can link any
   * site its author likes.
   */
  private fun isPageSource(sourceId: String): Boolean =
    sourceId == "curated" || WebSources.isDiscovery(sourceId)

  /** Hosts whose pages are listings, not a product's own site: their own adapters read them. */
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

  /** The first record of a page source whose URL is a product page, or null. */
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

/** Outbound port: the registrable domain of a host (Public Suffix List), or null when none. */
fun interface DomainRoots {
  fun registrable(host: String): String?
}

/**
 * The pages one product may be read from, set by its accepted page [start]: the same registrable
 * domain; the exact host on a shared host (one site per subdomain); the path below a one-product
 * page on a big vendor's site (aws.amazon.com/bedrock). Every URL is checked again after the
 * reader's redirects.
 */
class PageScope
private constructor(
  private val host: String,
  private val domain: String?,
  private val pathPrefix: String?,
  private val roots: DomainRoots,
) {

  companion object {
    /** Hosting services where every subdomain is somebody else's site. */
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

    /** The scope of [start], or null when [start] may not be read at all. */
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

    /**
     * Why [url] can never be read, or null: https only, a name not an IP address, no user info, no
     * port but the default.
     */
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

  /** The host the scope started from: a page there may support a summary claim. */
  fun isStartHost(url: URI): Boolean = url.host?.lowercase(Locale.ROOT) == host

  private fun pathUnder(path: String, prefix: String): Boolean =
    path == prefix || path.startsWith("$prefix/")
}

/** Links on a product's home page that likely state its facts. */
object OfficialLinks {

  /** Path words worth one more read, best first. */
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

  /**
   * Up to [max] distinct pages from [markdown]'s links that [scope] allows, other than [start],
   * whose last path segment is one of the path words; best word first.
   */
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
    // No query and no fragment: the same page, and no tracking parameters.
    URI(u.scheme, u.authority, u.path, null, null)
  }
    .getOrNull()

  private fun lastSegment(url: URI): String =
    url.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)

  private fun key(url: URI): String =
    "${url.host?.lowercase(Locale.ROOT)}${url.path.orEmpty().trimEnd('/')}"
}

/** One page as the reader returned it: the URL it ended up at and its text (markdown). */
data class ReadPage(val url: String, val raw: String)

/**
 * Outbound port: reads public pages (Tavily Extract). Returns the pages it could read, in any
 * order; a page it could not read is missing. Throws when the reader fails.
 */
fun interface PageReader {
  fun extract(urls: List<URI>): List<ReadPage>

  /** false when the reader is off (no key, or turned off): then the page step is skipped. */
  val available: Boolean
    get() = true
}

/** Outbound port: one reader credit (one Extract call of up to 5 pages); false when spent. */
fun interface ExtractBudget {
  fun tryTake(): Boolean
}

/**
 * Outbound port: the pages whose text a saved enrichment run read, by URL, with a hash of their
 * text, for 30 days. No page text is kept. A page in it is not read (or paid for) again until the
 * entry expires. Written only after the run is saved (ADR 0056), so a failed model call leaves the
 * page free to read again.
 */
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

/**
 * Outbound port: the cleaned text of a product's pages (after [UntrustedPageText]: hidden text
 * gone, emails and phone numbers masked, cut to the window), by the accepted page's URL, for at
 * most 24 hours (ADR 0056). Only so that a retry (the model was down, the gap cap was spent) does
 * not pay Tavily again. Disposable: a miss reads the pages again.
 */
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

/** A product page after cleaning: where it was read, when, and whether it is on the start host. */
data class OfficialPage(
  val url: URI,
  val retrievedAt: Instant,
  val text: String,
  val startHost: Boolean,
)

/** What a read gave: the pages, and the outcome for the gap metric ("read", "cached", ...). */
data class PageReading(val pages: List<OfficialPage>, val outcome: String) {
  companion object {
    fun none(outcome: String) = PageReading(emptyList(), outcome)
  }
}

/**
 * Reads a product's own pages (ADR 0056): its accepted page, then up to [MAX_LINKED_PAGES] pages
 * its links lead to on the same site. Two reader calls at most, each one credit; at most [MAX_URLS]
 * URLs per product. Every page the reader returns is checked against the scope again, so a redirect
 * to another site is dropped. The cleaned pages are kept in [textCache] for a retry; the [ledger]
 * learns of them only through [markRead], once a run that read them is saved.
 */
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
    const val MAX_LINKED_PAGES = 3
    const val MAX_URLS = 5
  }

  fun read(target: PageTarget): PageReading {
    val scope = PageScope.of(target.url, roots) ?: return PageReading.none("not_allowed")
    if (ledger.seen(target.url)) return PageReading.none("skipped_seen")
    textCache.get(target.url)?.let { cached ->
      // Checked again: the cache is outside this process.
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

  /**
   * Records [pages] (and the accepted page [target]) in the 30-day ledger: called once the run that
   * read them is saved.
   */
  fun markRead(target: PageTarget, pages: List<OfficialPage>) {
    val home = pages.firstOrNull { sameUrl(it.url.toString(), target.url) } ?: pages.firstOrNull()
    ledger.record(target.url, sha256Prefix(home?.text.orEmpty()))
    pages
      .filterNot { sameUrl(it.url.toString(), target.url) }
      .forEach {
        ledger.record(it.url, sha256Prefix(it.text))
      }
  }

  /** The pages [scope] allows among what the reader returned for [urls]. */
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
