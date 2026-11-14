package ai.helper2.search.usecase

import ai.helper2.platform.resilience.Resilience
import ai.helper2.search.AnalyzedQuery
import ai.helper2.search.Asker
import ai.helper2.search.Facets
import ai.helper2.search.Filtering
import ai.helper2.search.IntentClass
import ai.helper2.search.LanguageDetector
import ai.helper2.search.MergedResult
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.QueryTranslator
import ai.helper2.search.Ranking
import ai.helper2.search.ResultMerger
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchMetrics
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.Sorting
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceQuery
import ai.helper2.search.SourceStatus
import ai.helper2.search.VitalityPolicy
import ai.helper2.search.WebFreshness
import ai.helper2.search.WebResultStore
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.WebSearchesLeft
import ai.helper2.shared.DateFact
import ai.helper2.shared.SourceMode
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.slf4j.LoggerFactory

class SearchResources
@JvmOverloads
constructor(
  private val sources: List<SearchSource>,
  cache: SearchCache,
  private val observer: ResultObserver,
  executor: ExecutorService,
  private val clock: Clock,
  staleFor: Duration = Duration.ofHours(24),
  private val perSourceLimit: Int = 30,
  private val metrics: SearchMetrics = SearchMetrics.NOOP,
  private val analyzer: QueryAnalyzer = QueryAnalyzer(),
  private val merger: ResultMerger = ResultMerger(),
  private val ranking: Ranking = Ranking(),
  private val languages: LanguageDetector? = null,
  translator: QueryTranslator = QueryTranslator.NONE,
  translateTimeout: Duration = Duration.ofMillis(2500),
  resilience: Resilience = Resilience.NONE,
  webResults: WebResultStore = WebResultStore.NONE,
  private val quota: WebSearchQuota? = null,
  webFreshness: WebFreshness = WebFreshness(),
  vitality: VitalityPolicy = VitalityPolicy(),
) {
  private val log = LoggerFactory.getLogger(SearchResources::class.java)
  private val fanOut =
    SourceFanOut(
      cache,
      webResults,
      quota,
      resilience,
      executor,
      metrics,
      vitality,
      webFreshness,
      staleFor,
      perSourceLimit,
    )

  private val translation = QueryTranslation(translator, executor, analyzer, translateTimeout)

  init {
    sources.filter { it.descriptor.enabled }.forEach { resilience.require(policy(it.descriptor)) }
  }

  fun search(request: SearchRequest): SearchOutcome {
    val startedNanos = System.nanoTime()
    val startedAt = clock.instant()
    val language = languages?.detect(request.text)?.takeIf { it != "en" }
    val untranslated = analyzer.analyze(request.text)
    val translating = language?.let { translation.start(request.text, it) }
    val run =
      fanOut.run(startedAt, request.asker, IntentClass.of(request, untranslated), request.web)
    val analyzed = startSources(run, request, untranslated, language, translating)
    val fanned = run.collectAll()

    val statuses = statusesOf(fanned.attempts)
    statuses.forEach(metrics::sourceCompleted)
    val merged = confirmedNow(mergeHits(fanned.attempts))
    val dated = withDiscoveryDates(merged)
    val results = dated ?: merged
    record(results, statuses)

    val ranked = rank(results, request, analyzed)
    val sorted = Sorting.sort(Filtering.apply(ranked, request.filters), request.sort)
    val coverage = SearchNotices.coverageOf(statuses, sources.size)
    val notices =
      SearchNotices.of(
        indexDown = dated == null,
        cacheDown = fanned.cacheDown,
        analyzed = analyzed,
        statuses = statuses,
      )

    metrics.searchCompleted(
      coverage.partial,
      sorted.isEmpty(),
      Duration.ofNanos(System.nanoTime() - startedNanos),
    )
    return SearchOutcome(
      request = request,
      interpretation = analyzed,
      executedAt = startedAt,
      statuses = statuses,
      results = pageOf(sorted, request),
      total = sorted.size,
      facets = Facets.compute(ranked, request.filters),
      coverage = coverage,
      notices = notices,
      webSearches = request.asker?.let(::webSearchesLeft),
      webFreshUntil = fanned.webFreshUntil,
    )
  }

  private fun startSources(
    run: SourceFanOut.Run,
    request: SearchRequest,
    untranslated: AnalyzedQuery,
    language: String?,
    translating: Future<String?>?,
  ): AnalyzedQuery {
    val (early, late) = sources.partition { translating == null || !it.descriptor.usesTranslation }
    val untranslatedQuery = sourceQuery(request, untranslated, language)
    early.forEach { run.start(it, untranslatedQuery) }
    val analyzed =
      translation.withTranslation(
        untranslated,
        translating?.let { translation.await(it, request.text) },
      )
    val query =
      if (analyzed === untranslated) untranslatedQuery else sourceQuery(request, analyzed, language)
    late.forEach { run.start(it, query) }
    return analyzed
  }

  private fun statusesOf(attempts: List<SourceAttempt>): List<SourceStatus> = sources.map { s ->
    attempts.first { it.status.source.id == s.descriptor.id }.status
  }

  private fun mergeHits(attempts: List<SourceAttempt>): List<MergedResult> {
    val contexts = attempts.associate {
      it.status.source.id to SourceContext(it.status.source, it.status.servedFrom)
    }
    return merger.merge(attempts.flatMap { it.hits }, contexts)
  }

  private fun confirmedNow(merged: List<MergedResult>): List<MergedResult> {
    val prefixes = sources.mapNotNull { it.descriptor.confirmsUrlPrefix }
    if (prefixes.isEmpty()) return merged
    val catalog =
      sources.map { it.descriptor }.filter { it.mode == SourceMode.INDEXED }.map { it.id }.toSet()
    return merged.filterNot { r ->
      r.hits.all { it.sourceId in catalog } && prefixes.any { r.canonicalUrl.value.startsWith(it) }
    }
  }

  private fun withDiscoveryDates(merged: List<MergedResult>): List<MergedResult>? =
    try {
      val discovered = observer.firstDiscovered(merged)
      merged.map { r -> r.copy(discovered = DateFact.observed(discovered[r.id])) }
    } catch (e: RuntimeException) {
      log.warn("result observation failed: ${e.javaClass.simpleName}")
      null
    }

  private fun rank(
    results: List<MergedResult>,
    request: SearchRequest,
    analyzed: AnalyzedQuery,
  ): List<MergedResult> =
    ranking.rank(
      results,
      request.text,
      request.path,
      analyzed.typeHints,
      analyzed.requiredVariants.first().split(' '),
    )

  private fun pageOf(sorted: List<MergedResult>, request: SearchRequest): List<MergedResult> =
    sorted.drop(request.page * request.size).take(request.size)

  fun webSearchesLeft(asker: Asker): WebSearchesLeft? =
    if (quota == null || sources.none { it.descriptor.metered && it.descriptor.enabled }) null
    else quota.left(asker)

  private fun sourceQuery(request: SearchRequest, analyzed: AnalyzedQuery, language: String?) =
    SourceQuery(
      request.text.trim(),
      analyzed.expandedTerms,
      request.path,
      perSourceLimit,
      analyzed.requiredVariants,
      analyzed.synonyms,
      language,
      request.webQuery?.trim()?.ifBlank { null },
      request.filters.publishedAfter,
      request.browse,
      request.filters.types,
      searchTermsOf(analyzed),
      analyzed.translation,
    )

  private fun searchTermsOf(analyzed: AnalyzedQuery): List<String> =
    when {
      analyzed.translation != null -> analyzer.analyze(analyzed.translation).contentTerms
      analyzed.lexiconTerms.isNotEmpty() -> emptyList()
      else -> analyzed.contentTerms
    }

  private fun record(merged: List<MergedResult>, statuses: List<SourceStatus>) {
    val fresh = statuses.filter { it.servedFrom == ServedFrom.LIVE }.map { it.source.id }.toSet()
    val toRecord = merged.mapNotNull { r ->
      val hits = r.hits.filter { it.sourceId in fresh }
      when {
        hits.isEmpty() -> null
        hits.size == r.hits.size -> r
        else -> r.copy(hits = hits)
      }
    }
    if (toRecord.isEmpty()) return
    try {
      observer.record(toRecord)
    } catch (e: RuntimeException) {
      log.warn("result recording failed: ${e.javaClass.simpleName}")
    }
  }

  companion object {
    @JvmStatic fun policy(d: SourceDescriptor) = SourceFanOut.policy(d)

    @JvmStatic
    fun cacheKey(d: SourceDescriptor, q: SourceQuery): String = SourceCacheKeys.cacheKey(d, q)
  }
}
