package ai.helper2.eval

import ai.helper2.search.MergedResult
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryTranslator
import ai.helper2.search.Ranking
import ai.helper2.search.ResultMerger
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.Audience
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.LanguageDetector
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/** What one search returned from each source, frozen so ranking can be replayed offline (ADR 0036). */
data class SearchSnapshot(
    val queryId: String,
    val text: String,
    val recordedAt: Instant,
    /** The words the title boost looks for and the types the query names, as the live search computed them. */
    val titleTerms: List<String>,
    val typeHints: Set<ResourceType>,
    val sources: List<SourceSnapshot>,
    /** The nearest catalog vectors without any cutoff (null when vector search was off), for the cutoff sweep. */
    val semantic: List<SemanticHit>? = null,
)

data class SourceSnapshot(
    val id: String,
    val name: String,
    val mode: SourceMode,
    val priority: Int,
    val outcome: SourceOutcome,
    val latencyMs: Long?,
    val hits: List<HitSnapshot>,
)

data class HitSnapshot(
    val rank: Int,
    val url: String,
    val canonicalUrl: String,
    val repositoryUrl: String?,
    val title: String,
    val description: String?,
    val type: ResourceType,
    val audience: Audience,
    val tags: Set<String>,
    val popularity: Map<PopularityKind, Long>,
)

data class SemanticHit(val canonicalUrl: String, val distance: Double)

/**
 * Runs the production search use case once per query with every source wrapped so its hits are kept. No cache
 * and no catalog writes: the snapshot is what the sources returned to this search, nothing else.
 */
class SnapshotTaker(
    sources: List<SearchSource>,
    languages: LanguageDetector?,
    translator: QueryTranslator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val recording = sources.map(::RecordingSource)
    private val search = SearchResources(
        sources = recording,
        cache = NO_CACHE,
        observer = { emptyMap() },
        executor = Executors.newVirtualThreadPerTaskExecutor(),
        clock = clock,
        languages = languages,
        translator = translator,
    )

    fun take(queryId: String, text: String): Pair<SearchSnapshot, SearchOutcome> {
        recording.forEach { it.hits = emptyList() }
        val outcome = search.search(SearchRequest(text))
        val sources = outcome.statuses.map { st ->
            val hits = if (st.outcome == SourceOutcome.SUCCEEDED) recording.first { it.descriptor.id == st.source.id }.hits else emptyList()
            SourceSnapshot(st.source.id, st.source.name, st.source.mode, st.source.priority, st.outcome,
                st.latency?.toMillis(), hits.map(::snapshotOf))
        }
        val interpretation = outcome.interpretation
        return SearchSnapshot(queryId, text, outcome.executedAt, interpretation.requiredVariants.first().split(' '),
            interpretation.typeHints, sources) to outcome
    }

    private class RecordingSource(private val delegate: SearchSource) : SearchSource {
        @Volatile var hits: List<SourceHit> = emptyList()
        override val descriptor: SourceDescriptor get() = delegate.descriptor
        override fun search(query: SourceQuery): List<SourceHit> = delegate.search(query).also { hits = it }
    }

    private companion object {
        val NO_CACHE = object : SearchCache {
            override fun get(key: String) = null
            override fun put(key: String, value: ai.helper2.search.CachedHits, ttl: Duration) {}
        }

        fun snapshotOf(h: SourceHit) = HitSnapshot(h.rank, h.url.toString(), h.canonicalUrl.value, h.repositoryUrl?.value,
            h.title, h.description, h.type, h.audience, h.tags, h.popularity.associate { it.kind to it.value })
    }
}

/** Production merge and ranking over a snapshot: no network, same input, same order. */
class SearchReplay(private val merger: ResultMerger = ResultMerger(), private val ranking: Ranking = Ranking()) {

    /** Ranked results of [snapshot], as if the sources in [without] had not been queried. */
    fun rank(snapshot: SearchSnapshot, without: Set<String> = emptySet()): List<MergedResult> {
        val kept = snapshot.sources.filter { it.id !in without }
        val contexts = kept.associate { s ->
            s.id to SourceContext(SourceDescriptor(s.id, s.name, s.mode, PLACEHOLDER, null, null, ResourceType.entries.toSet(),
                Duration.ofSeconds(1), Duration.ZERO, priority = s.priority), ServedFrom.LIVE)
        }
        val hits = kept.flatMap { s -> s.hits.map { hitOf(s.id, it, snapshot.recordedAt) } }
        return ranking.rank(merger.merge(hits, contexts), snapshot.text, null, snapshot.typeHints, snapshot.titleTerms)
    }

    private fun hitOf(sourceId: String, h: HitSnapshot, at: Instant) = SourceHit(
        sourceId = sourceId, externalId = h.url, url = URI(h.url), canonicalUrl = CanonicalUrl.of(h.canonicalUrl)!!,
        repositoryUrl = h.repositoryUrl?.let(CanonicalUrl::of), title = h.title, description = h.description,
        type = h.type, audience = h.audience, tags = h.tags,
        popularity = h.popularity.map { (kind, value) -> PopularityMetric(kind, value, sourceId, at) },
        retrievedAt = at, rank = h.rank,
    )

    private companion object {
        val PLACEHOLDER: URI = URI("https://helper2.ai/sources")
    }
}
