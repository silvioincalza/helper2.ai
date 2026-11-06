package ai.helper2.scout

import java.time.Instant

/**
 * The host lists V17 seeds (ADR 0063), for tests of the discovery rules. JdbcDiscoveryHostStoreIT
 * checks that the migration seeds exactly these.
 */
object SeedDiscoveryHosts {

  @JvmField
  val WRITING: Set<String> =
    setOf(
      "medium.com",
      "dev.to",
      "substack.com",
      "forbes.com",
      "techcrunch.com",
      "theverge.com",
      "wired.com",
      "zdnet.com",
      "venturebeat.com",
      "hashnode.dev",
      "wordpress.com",
      "blogspot.com",
      "beehiiv.com",
    )

  @JvmField
  val DIRECTORY: Set<String> =
    setOf(
      "producthunt.com",
      "huggingface.co",
      "wikipedia.org",
      "news.ycombinator.com",
      "g2.com",
      "capterra.com",
      "gartner.com",
      "notion.site",
      "theresanaiforthat.com",
      "futurepedia.io",
      "futuretools.io",
      "toolify.ai",
      "topai.tools",
      "aitools.fyi",
      "getapp.com",
      "softwareadvice.com",
      "trustradius.com",
      "trustpilot.com",
      "alternativeto.net",
      "saasworthy.com",
    )

  @JvmField val LISTS: HostLists = HostLists(WRITING, DIRECTORY)

  /** A store in memory that starts with the seed lists. */
  @JvmStatic
  fun store(): InMemoryDiscoveryHostStore =
    InMemoryDiscoveryHostStore().apply {
      WRITING.forEach { add(DiscoveryHost(it, HostKind.WRITING, "migration", Instant.EPOCH, null)) }
      DIRECTORY.forEach {
        add(DiscoveryHost(it, HostKind.DIRECTORY, "migration", Instant.EPOCH, null))
      }
    }
}

/** The store port in memory; [failing] makes every read throw, as a database that is down. */
class InMemoryDiscoveryHostStore : DiscoveryHostStore {
  private val hosts = LinkedHashMap<String, DiscoveryHost>()
  var failing = false

  override fun all(): List<DiscoveryHost> {
    check(!failing) { "store down" }
    return hosts.values.sortedWith(compareBy({ it.kind }, { it.host }))
  }

  override fun add(host: DiscoveryHost): Boolean = hosts.putIfAbsent(host.host, host) == null

  override fun remove(host: String): DiscoveryHost? = hosts.remove(host)
}
