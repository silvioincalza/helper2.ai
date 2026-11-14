package ai.helper2.eval

import ai.helper2.search.IntentClass
import ai.helper2.search.LanguageDetector
import ai.helper2.search.MergedResult
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryAnalyzer
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
import ai.helper2.search.Vitality
import ai.helper2.search.VitalityPolicy
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.Audience
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import ai.helper2.shared.TechLevel
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

data class SearchSnapshot(
  val queryId: String,
  val text: String,
  val recordedAt: Instant,
  val titleTerms: List<String>,
  val typeHints: Set<ResourceType>,
  val sources: List<SourceSnapshot>,
  val semantic: List<SemanticHit>? = null,
  val path: AudiencePath? = null,
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
  val curatedFields: Set<String> = emptySet(),
  val techLevel: TechLevel? = null,
  val license: String? = null,
  val pricing: Pricing? = null,
  val openSource: Boolean? = null,
  val updated: Instant? = null,
  val archived: Boolean? = null,
  val fork: Boolean? = null,
  val deprecated: Boolean? = null,
  val running: Boolean? = null,
  val dataOrigin: String? = null,
)

data class SemanticHit(val canonicalUrl: String, val distance: Double)

class SnapshotTaker(
  sources: List<SearchSource>,
  languages: LanguageDetector?,
  translator: QueryTranslator,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val recording = sources.map(::RecordingSource)
  private val search =
    SearchResources(
      sources = recording,
      cache = NO_CACHE,
      observer = { emptyMap() },
      executor = Executors.newVirtualThreadPerTaskExecutor(),
      clock = clock,
      languages = languages,
      translator = translator,
    )

  fun take(
    queryId: String,
    text: String,
    path: AudiencePath? = null,
  ): Pair<SearchSnapshot, SearchOutcome> {
    recording.forEach { it.hits = emptyList() }
    val outcome = search.search(SearchRequest(text, path))
    val sources =
      outcome.statuses.map { st ->
        val hits =
          if (st.outcome == SourceOutcome.SUCCEEDED)
            recording.first { it.descriptor.id == st.source.id }.hits
          else emptyList()
        SourceSnapshot(
          st.source.id,
          st.source.name,
          st.source.mode,
          st.source.priority,
          st.outcome,
          st.latency?.toMillis(),
          hits.map(::snapshotOf),
        )
      }
    val interpretation = outcome.interpretation
    return SearchSnapshot(
      queryId,
      text,
      outcome.executedAt,
      interpretation.requiredVariants.first().split(' '),
      interpretation.typeHints,
      sources,
      path = path,
    ) to outcome
  }

  private class RecordingSource(private val delegate: SearchSource) : SearchSource {
    @Volatile var hits: List<SourceHit> = emptyList()
    override val descriptor: SourceDescriptor
      get() = delegate.descriptor

    override fun search(query: SourceQuery): List<SourceHit> =
      delegate.search(query).also { hits = it }
  }

  private companion object {
    val NO_CACHE =
      object : SearchCache {
        override fun get(key: String) = null

        override fun put(key: String, value: ai.helper2.search.CachedHits, ttl: Duration) {}
      }

    fun snapshotOf(h: SourceHit) =
      HitSnapshot(
        h.rank,
        h.url.toString(),
        h.canonicalUrl.value,
        h.repositoryUrl?.value,
        h.title,
        h.description,
        h.type,
        h.audience,
        h.tags,
        h.popularity.associate { it.kind to it.value },
        h.curatedFields,
        h.techLevel,
        h.license,
        h.pricing,
        h.openSource,
        h.updated.value,
        h.vitality.archived,
        h.vitality.fork,
        h.vitality.deprecated,
        h.vitality.running,
      )
  }
}

class SearchReplay(
  private val merger: ResultMerger = ResultMerger(),
  private val ranking: Ranking = Ranking(),
  private val oneCatalogVote: Boolean = false,
  private val echoNoVote: Boolean = false,
  private val newsLast: Boolean = false,
  private val vitality: VitalityPolicy = VitalityPolicy(),
) {
  fun rank(snapshot: SearchSnapshot, without: Set<String> = emptySet()): List<MergedResult> {
    val kept = snapshot.sources.filter { it.id !in without }
    val hits = kept.flatMap { s ->
      vitality.admitted(s.hits.map { hitOf(s.id, it, snapshot.recordedAt) }).take(PER_SOURCE_LIMIT)
    }
    val merged = merger.merge(hits, contextsOf(kept))
    val voters = Voters(kept, hits)
    val ranked =
      ranking.rank(
        merged,
        snapshot.text,
        snapshot.path,
        snapshot.typeHints,
        snapshot.titleTerms,
        votes = { voters.of(it.hits) },
      )
    return if (newsLast && !asksForNews(snapshot)) ranked.sortedBy { it.type == ResourceType.NEWS }
    else ranked
  }

  private fun contextsOf(kept: List<SourceSnapshot>): Map<String, SourceContext> =
    kept.associate { s ->
      s.id to
        SourceContext(
          SourceDescriptor(
            s.id,
            s.name,
            s.id.take(12),
            true,
            s.mode,
            PLACEHOLDER,
            null,
            null,
            ResourceType.entries.toSet(),
            Duration.ofSeconds(1),
            Duration.ZERO,
            priority = s.priority,
          ),
          ServedFrom.LIVE,
        )
    }

  private inner class Voters(kept: List<SourceSnapshot>, all: List<SourceHit>) {
    private val index = kept.filter { it.mode == SourceMode.INDEXED }.map { it.id }.toSet()
    private val catalog = kept.filter { it.mode in CATALOG_MODES }.map { it.id }.toSet()
    private val observed =
      kept
        .flatMap { s ->
          s.hits.mapNotNull { h -> h.dataOrigin?.let { (s.id to h.canonicalUrl) to it } }
        }
        .toMap()
    private val foundLive =
      kept
        .filter { it.mode == SourceMode.LIVE }
        .map { it.id }
        .toSet()
        .let { live -> all.filter { it.sourceId in live }.map { it.canonicalUrl }.toSet() }

    fun of(hits: List<SourceHit>): List<SourceHit> {
      val voting = if (echoNoVote) hits.filterNot(::isEcho) else hits
      return if (oneCatalogVote) withOneCatalogVote(voting) else voting
    }

    private fun isEcho(hit: SourceHit): Boolean {
      if (hit.sourceId !in index) return false
      if (observed.isNotEmpty()) return observed[hit.sourceId to hit.canonicalUrl.value] == OBSERVED
      return hit.canonicalUrl in foundLive
    }

    private fun withOneCatalogVote(hits: List<SourceHit>): List<SourceHit> {
      val (inCatalog, others) = hits.partition { it.sourceId in catalog }
      val best = inCatalog.minByOrNull { it.rank } ?: return hits
      return others + best.copy(sourceId = CATALOG_VOTE)
    }
  }

  private fun asksForNews(snapshot: SearchSnapshot): Boolean =
    ResourceType.NEWS in snapshot.typeHints ||
      IntentClass.of(SearchRequest(text = snapshot.text), QueryAnalyzer().analyze(snapshot.text)) ==
        IntentClass.TIME_BOUND

  private fun hitOf(sourceId: String, h: HitSnapshot, at: Instant) =
    SourceHit(
      sourceId = sourceId,
      externalId = h.url,
      url = URI(h.url),
      canonicalUrl = CanonicalUrl.of(h.canonicalUrl)!!,
      repositoryUrl = h.repositoryUrl?.let(CanonicalUrl::of),
      title = h.title,
      description = h.description,
      type = h.type,
      audience = h.audience,
      tags = h.tags,
      popularity =
        h.popularity.map { (kind, value) -> PopularityMetric(kind, value, sourceId, at) },
      retrievedAt = at,
      rank = h.rank,
      techLevel = h.techLevel,
      license = h.license,
      pricing = h.pricing,
      openSource = h.openSource,
      curatedFields = h.curatedFields,
      updated = DateFact.fromSource(h.updated, sourceId, at),
      vitality = Vitality(h.archived, h.fork, h.deprecated, h.running),
    )

  private companion object {
    val PLACEHOLDER: URI = URI("https://helper2.ai/sources")
    const val CATALOG_VOTE = "catalog"
    const val OBSERVED = "OBSERVED"
    const val PER_SOURCE_LIMIT = 30
    val CATALOG_MODES = setOf(SourceMode.INDEXED, SourceMode.CURATED)
  }
}
