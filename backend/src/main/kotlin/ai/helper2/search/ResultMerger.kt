package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.CategoryInput
import ai.helper2.shared.CategoryMatcher
import ai.helper2.shared.DateFact
import ai.helper2.shared.DateStatus
import ai.helper2.shared.ResourceId
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import ai.helper2.shared.SourcedValue
import ai.helper2.shared.WebSources

/** What the merger needs to know about the source behind a hit. */
data class SourceContext(val descriptor: SourceDescriptor, val servedFrom: ServedFrom)

/**
 * Deterministic deduplication and merging. No LLM involved.
 *
 * Hits are grouped when they share a canonical URL or a repository link (union-find), so an npm
 * package and its GitHub repository become one result. A repository link joins only when no other
 * resource of the same source links to that repository: packages of one monorepo stay separate
 * results, each keeping its repository link (see [joiningRepositories]). Display text comes from
 * the preferred source; categorical facts that disagree (license, pricing, open source) are NOT
 * resolved — the merged value is null and every competing assertion is kept in
 * [MergedResult.conflicts]. Dates keep the earliest (or latest) value and are marked CONFLICTING
 * when two sources state different days (DateFact).
 */
class ResultMerger
@JvmOverloads
constructor(
  private val analyzer: QueryAnalyzer = QueryAnalyzer(),
  /**
   * Assigns the categories of a merged result (ADR 0053), so live web results can be filtered by
   * them too; [CategoryMatcher.NONE] keeps only the categories the hits carry.
   */
  private val categories: CategoryMatcher = CategoryMatcher.NONE,
) {

  fun merge(hits: List<SourceHit>, contexts: Map<String, SourceContext>): List<MergedResult> {
    val joining = joiningRepositories(hits)
    return group(hits, joining).map { withCurated(mergeGroup(it, contexts), it) }
  }

  /**
   * [merged] with the values an admin set (ADR 0066), carried by the catalog's hit: each curated
   * field wins over the other sources and is no longer a conflict.
   */
  private fun withCurated(merged: MergedResult, group: List<SourceHit>): MergedResult {
    val curated = group.firstOrNull { it.curatedFields.isNotEmpty() } ?: return merged
    return merged.withCurated(
      curated.curatedFields,
      curated.type,
      curated.audience,
      curated.techLevel,
      curated.license,
      curated.pricing,
      curated.openSource,
    )
  }

  /**
   * Resource URL to repository URL, for the repository links that join their repository. A link
   * joins only when it is the only resource of its source in [hits] linking to that repository: the
   * packages of one monorepo all link to it, so none joins and each stays its own result with its
   * repository link. A sub-directory link is its own identity ([CanonicalUrl]) and never reaches
   * the repository root.
   */
  private fun joiningRepositories(hits: List<SourceHit>): Map<CanonicalUrl, CanonicalUrl> {
    val links =
      hits
        .mapNotNull { h ->
          h.repositoryUrl
            ?.takeIf { it != h.canonicalUrl }
            ?.let { RepositoryLink(h.sourceId, h.canonicalUrl, it) }
        }
        .distinct()
    val shared = links.groupBy { it.sourceId to it.repository }.filterValues { it.size > 1 }.keys
    return links
      .filterNot { (it.sourceId to it.repository) in shared }
      .associate { it.resource to it.repository }
  }

  private data class RepositoryLink(
    val sourceId: String,
    val resource: CanonicalUrl,
    val repository: CanonicalUrl,
  )

  private fun group(
    hits: List<SourceHit>,
    joining: Map<CanonicalUrl, CanonicalUrl>,
  ): Collection<List<SourceHit>> {
    val parent = HashMap<CanonicalUrl, CanonicalUrl>()
    fun find(x: CanonicalUrl): CanonicalUrl {
      var root = x
      while (parent[root] != root) root = parent.getValue(root)
      parent[x] = root
      return root
    }
    fun union(a: CanonicalUrl, b: CanonicalUrl) {
      parent.putIfAbsent(a, a)
      parent.putIfAbsent(b, b)
      val ra = find(a)
      val rb = find(b)
      if (ra != rb) parent[ra] = rb
    }
    hits.forEach { parent.putIfAbsent(it.canonicalUrl, it.canonicalUrl) }
    joining.forEach { (resource, repository) -> union(resource, repository) }
    return hits.groupBy { find(it.canonicalUrl) }.values
  }

  private fun mergeGroup(
    group: List<SourceHit>,
    contexts: Map<String, SourceContext>,
  ): MergedResult {
    val ordered =
      group.sortedWith(
        compareBy<SourceHit>(
          { contexts[it.sourceId]?.descriptor?.priority ?: Int.MAX_VALUE },
          { it.sourceId },
          { it.rank },
        )
      )
    val primary = ordered.first()
    // Identity from the hits' own URLs, a GitHub URL first, then lexical order. A repository link
    // alone never names a result: monorepo packages found in separate searches keep separate ids.
    val identity =
      group
        .map { it.canonicalUrl }
        .minWith(compareBy({ if (it.host == "github.com") 0 else 1 }, { it.value }))
    val conflicts = mutableListOf<FieldConflict>()

    val license = categorical("license", group, conflicts) { it.license?.let(::normalizeLicense) }
    val pricing = categorical("pricing", group, conflicts) { it.pricing?.name }
    val openSource = categorical("openSource", group, conflicts) { it.openSource?.toString() }
    val type = group.minBy { it.type.specificity }.type
    val tags = group.flatMapTo(sortedSetOf()) { it.tags }

    return MergedResult(
      id = ResourceId.of(identity),
      canonicalUrl = identity,
      url = primary.url,
      title = primary.title,
      description = ordered.firstNotNullOfOrNull { it.description },
      descriptionOrigin =
        ordered.firstOrNull { it.description != null }?.descriptionOrigin
          ?: primary.descriptionOrigin,
      type = type,
      audience = group.map { it.audience }.fold(Audience.UNKNOWN) { acc, a -> acc.combine(a) },
      techLevel = ordered.firstNotNullOfOrNull { it.techLevel },
      // Stated use cases win; otherwise derive them by the fixed EN/IT lexicon (rule, not LLM).
      useCases =
        group
          .flatMapTo(sortedSetOf()) { it.useCases }
          .ifEmpty {
            group.flatMapTo(sortedSetOf()) { h ->
              analyzer.useCasesOf(h.title, h.description, h.tags.joinToString(" "))
            }
          },
      tags = tags,
      license = license,
      pricing = pricing?.let { ai.helper2.shared.Pricing.valueOf(it) },
      platforms = group.flatMapTo(sortedSetOf()) { it.platforms },
      openSource = openSource?.toBooleanStrict(),
      published = DateFact.earliest(group.map { it.published }),
      updated = DateFact.lastChange(group.map { it.updated }),
      discovered = DateFact.unknown(),
      popularity = group.flatMap { it.popularity }.distinctBy { it.kind to it.sourceId },
      sources =
        ordered
          .distinctBy { it.sourceId }
          .map { h ->
            val d = contexts[h.sourceId]?.descriptor
            SourceRef(
              h.sourceId,
              d?.name ?: h.sourceId,
              h.url,
              d?.mode ?: SourceMode.LIVE,
              h.retrievedAt,
            )
          },
      freshness = group.map { freshnessOf(contexts[it.sourceId]) }.minBy { it.ordinal },
      conflicts = conflicts,
      hits = ordered,
      // Different start or end days from two sources are CONFLICTING, as for published. Days an AI
      // model read (ADR 0067) count only when no other source states any.
      eventStart = DateFact.earliest(eventSources(group).map { it.eventStart }),
      eventEnd = DateFact.latest(eventSources(group).map { it.eventEnd }),
      location = statedFirst(ordered).firstNotNullOfOrNull { it.location },
      eventKind = statedFirst(ordered).firstNotNullOfOrNull { it.eventKind },
      eventFormat = statedFirst(ordered).firstNotNullOfOrNull { it.eventFormat },
      categories = categoriesOf(primary.title, group, tags, type),
    )
  }

  /** The hits whose event days count: those a source states, or else those an AI model read. */
  private fun eventSources(group: List<SourceHit>): List<SourceHit> {
    val stated = group.filter {
      !WebSources.eventDaysReadByAi(it.sourceId) && it.eventStart.status == DateStatus.KNOWN
    }
    return stated.ifEmpty { group }
  }

  /** [hits] with those whose event facts an AI model read last. */
  private fun statedFirst(hits: List<SourceHit>): List<SourceHit> = hits.sortedBy {
    WebSources.eventDaysReadByAi(it.sourceId)
  }

  /**
   * What the catalog stored for the hits, plus what the matcher reads in the merged result: its
   * title, what its sources wrote (never an AI-written description), its tags, type and finders.
   */
  private fun categoriesOf(
    title: String,
    group: List<SourceHit>,
    tags: Set<String>,
    type: ResourceType,
  ): Set<String> {
    val stored = group.flatMap { it.categories }
    val description =
      group
        .filterNot { it.descriptionOrigin.name.startsWith("AI_") }
        .mapNotNull { it.description }
        .distinct()
        .joinToString("\n")
    val finders = group.map { it.sourceId }.distinct()
    val matched = categories.match(CategoryInput(title, description, tags, type, finders))
    val all = (matched + stored).distinct()
    return LinkedHashSet(
      if (categories === CategoryMatcher.NONE) all else categories.catalog.ordered(all)
    )
  }

  /**
   * Returns the agreed value, or null (unknown or conflicting). Conflicts are recorded, never
   * resolved.
   */
  private fun categorical(
    field: String,
    group: List<SourceHit>,
    conflicts: MutableList<FieldConflict>,
    extract: (SourceHit) -> String?,
  ): String? {
    val asserted =
      group.mapNotNull { h -> extract(h)?.let { SourcedValue(it, h.sourceId) } }.distinct()
    val distinctValues = asserted.map { it.value }.distinct()
    return when (distinctValues.size) {
      0 -> null
      1 -> distinctValues.single()
      else -> {
        conflicts += FieldConflict(field, asserted)
        null
      }
    }
  }

  private fun freshnessOf(ctx: SourceContext?): Freshness =
    when {
      ctx == null -> Freshness.LIVE
      ctx.descriptor.mode == SourceMode.INDEXED -> Freshness.INDEXED
      ctx.descriptor.mode == SourceMode.CURATED -> Freshness.CURATED
      ctx.descriptor.mode == SourceMode.DEMO -> Freshness.DEMO
      ctx.servedFrom == ServedFrom.CACHE -> Freshness.CACHED
      ctx.servedFrom == ServedFrom.STALE_CACHE -> Freshness.STALE
      else -> Freshness.LIVE
    }

  companion object {
    private val LICENSE_ALIASES =
      mapOf(
        "mit license" to "MIT",
        "mit" to "MIT",
        "apache license 2.0" to "Apache-2.0",
        "apache-2.0" to "Apache-2.0",
        "apache 2.0" to "Apache-2.0",
        "bsd-3-clause" to "BSD-3-Clause",
        "bsd 3-clause \"new\" or \"revised\" license" to "BSD-3-Clause",
        "gpl-3.0" to "GPL-3.0",
        "gnu general public license v3.0" to "GPL-3.0",
        "isc" to "ISC",
        "isc license" to "ISC",
        "mpl-2.0" to "MPL-2.0",
        "agpl-3.0" to "AGPL-3.0",
        "unlicense" to "Unlicense",
        "the unlicense" to "Unlicense",
      )

    @JvmStatic
    fun normalizeLicense(raw: String): String? {
      val t = raw.trim()
      // npm "SEE LICENSE IN <file>" and "UNLICENSED" do not state a license value we can show.
      if (
        t.isEmpty() ||
          t.equals("noassertion", true) ||
          t.equals("other", true) ||
          t.startsWith("SEE LICENSE", true) ||
          t.equals("UNLICENSED", true)
      )
        return null
      return LICENSE_ALIASES[t.lowercase()] ?: t
    }
  }
}
