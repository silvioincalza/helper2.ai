package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceId
import ai.helper2.shared.SourceMode
import ai.helper2.shared.SourcedValue

/** What the merger needs to know about the source behind a hit. */
data class SourceContext(val descriptor: SourceDescriptor, val servedFrom: ServedFrom)

/**
 * Deterministic deduplication and merging. No LLM involved.
 *
 * Hits are grouped when they share a canonical URL or a repository URL (union-find), so an npm
 * package and its GitHub repository become one result. Display text comes from the preferred
 * source; categorical facts that disagree (license, pricing, open source) are NOT resolved — the
 * merged value is null and every competing assertion is kept in [MergedResult.conflicts]. Dates keep the
 * earliest (or latest) value and are marked CONFLICTING when two sources state different days (DateFact).
 */
class ResultMerger(private val analyzer: QueryAnalyzer = QueryAnalyzer()) {

    fun merge(hits: List<SourceHit>, contexts: Map<String, SourceContext>): List<MergedResult> =
        group(hits).map { mergeGroup(it, contexts) }

    private fun group(hits: List<SourceHit>): Collection<List<SourceHit>> {
        val parent = HashMap<CanonicalUrl, CanonicalUrl>()
        fun find(x: CanonicalUrl): CanonicalUrl {
            var root = x
            while (parent[root] != root) root = parent.getValue(root)
            parent[x] = root
            return root
        }
        fun union(a: CanonicalUrl, b: CanonicalUrl) {
            parent.putIfAbsent(a, a); parent.putIfAbsent(b, b)
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        hits.forEach { h ->
            parent.putIfAbsent(h.canonicalUrl, h.canonicalUrl)
            h.repositoryUrl?.let { union(h.canonicalUrl, it) }
        }
        return hits.groupBy { find(it.canonicalUrl) }.values
    }

    private fun mergeGroup(group: List<SourceHit>, contexts: Map<String, SourceContext>): MergedResult {
        val ordered = group.sortedWith(
            compareBy<SourceHit>({ contexts[it.sourceId]?.descriptor?.priority ?: Int.MAX_VALUE }, { it.sourceId }, { it.rank }),
        )
        val primary = ordered.first()
        // Stable identity: prefer a repository-style URL any hit points to, then lexical order.
        val identity = group.map { it.identityUrl }.minWith(compareBy({ if (it.host == "github.com") 0 else 1 }, { it.value }))
        val conflicts = mutableListOf<FieldConflict>()

        val license = categorical("license", group, conflicts) { it.license?.let(::normalizeLicense) }
        val pricing = categorical("pricing", group, conflicts) { it.pricing?.name }
        val openSource = categorical("openSource", group, conflicts) { it.openSource?.toString() }

        return MergedResult(
            id = ResourceId.of(identity),
            canonicalUrl = identity,
            url = primary.url,
            title = primary.title,
            description = ordered.firstNotNullOfOrNull { it.description },
            descriptionOrigin = ordered.firstOrNull { it.description != null }?.descriptionOrigin ?: primary.descriptionOrigin,
            type = group.minBy { it.type.specificity }.type,
            audience = group.map { it.audience }.fold(Audience.UNKNOWN) { acc, a -> acc.combine(a) },
            techLevel = ordered.firstNotNullOfOrNull { it.techLevel },
            // Stated use cases win; otherwise derive them by the fixed EN/IT lexicon (rule, not LLM).
            useCases = group.flatMapTo(sortedSetOf()) { it.useCases }.ifEmpty {
                group.flatMapTo(sortedSetOf()) { h -> analyzer.useCasesOf(h.title, h.description, h.tags.joinToString(" ")) }
            },
            tags = group.flatMapTo(sortedSetOf()) { it.tags },
            license = license,
            pricing = pricing?.let { ai.helper2.shared.Pricing.valueOf(it) },
            platforms = group.flatMapTo(sortedSetOf()) { it.platforms },
            openSource = openSource?.toBooleanStrict(),
            published = DateFact.earliest(group.map { it.published }),
            updated = DateFact.lastChange(group.map { it.updated }),
            discovered = DateFact.unknown(),
            popularity = group.flatMap { it.popularity }.distinctBy { it.kind to it.sourceId },
            sources = ordered.distinctBy { it.sourceId }.map { h ->
                val d = contexts[h.sourceId]?.descriptor
                SourceRef(h.sourceId, d?.name ?: h.sourceId, h.url, d?.mode ?: SourceMode.LIVE, h.retrievedAt)
            },
            freshness = group.map { freshnessOf(contexts[it.sourceId]) }.minBy { it.ordinal },
            conflicts = conflicts,
            hits = ordered,
            // Different start or end days from two sources are CONFLICTING, as for published.
            eventStart = DateFact.earliest(group.map { it.eventStart }),
            eventEnd = DateFact.latest(group.map { it.eventEnd }),
            location = ordered.firstNotNullOfOrNull { it.location },
            eventKind = ordered.firstNotNullOfOrNull { it.eventKind },
            eventFormat = ordered.firstNotNullOfOrNull { it.eventFormat },
        )
    }

    /** Returns the agreed value, or null (unknown or conflicting). Conflicts are recorded, never resolved. */
    private fun categorical(
        field: String,
        group: List<SourceHit>,
        conflicts: MutableList<FieldConflict>,
        extract: (SourceHit) -> String?,
    ): String? {
        val asserted = group.mapNotNull { h -> extract(h)?.let { SourcedValue(it, h.sourceId) } }.distinct()
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

    private fun freshnessOf(ctx: SourceContext?): Freshness = when {
        ctx == null -> Freshness.LIVE
        ctx.descriptor.mode == SourceMode.INDEXED -> Freshness.INDEXED
        ctx.descriptor.mode == SourceMode.CURATED -> Freshness.CURATED
        ctx.descriptor.mode == SourceMode.DEMO -> Freshness.DEMO
        ctx.servedFrom == ServedFrom.CACHE -> Freshness.CACHED
        ctx.servedFrom == ServedFrom.STALE_CACHE -> Freshness.STALE
        else -> Freshness.LIVE
    }

    companion object {
        private val LICENSE_ALIASES = mapOf(
            "mit license" to "MIT", "mit" to "MIT",
            "apache license 2.0" to "Apache-2.0", "apache-2.0" to "Apache-2.0", "apache 2.0" to "Apache-2.0",
            "bsd-3-clause" to "BSD-3-Clause", "bsd 3-clause \"new\" or \"revised\" license" to "BSD-3-Clause",
            "gpl-3.0" to "GPL-3.0", "gnu general public license v3.0" to "GPL-3.0",
            "isc" to "ISC", "isc license" to "ISC", "mpl-2.0" to "MPL-2.0", "agpl-3.0" to "AGPL-3.0",
            "unlicense" to "Unlicense", "the unlicense" to "Unlicense",
        )

        @JvmStatic
        fun normalizeLicense(raw: String): String? {
            val t = raw.trim()
            // npm "SEE LICENSE IN <file>" and "UNLICENSED" do not state a license value we can show.
            if (t.isEmpty() || t.equals("noassertion", true) || t.equals("other", true) ||
                t.startsWith("SEE LICENSE", true) || t.equals("UNLICENSED", true)) return null
            return LICENSE_ALIASES[t.lowercase()] ?: t
        }
    }
}
