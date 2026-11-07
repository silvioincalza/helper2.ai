package ai.helper2.search.usecase

import ai.helper2.platform.resilience.CallRefused
import ai.helper2.platform.resilience.Resilience
import ai.helper2.search.AnalyzedQuery
import ai.helper2.search.Asker
import ai.helper2.search.CachedHits
import ai.helper2.search.Coverage
import ai.helper2.search.Facets
import ai.helper2.search.Filtering
import ai.helper2.search.IntentClass
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.QueryTranslator
import ai.helper2.search.QuotaDecision
import ai.helper2.search.Ranking
import ai.helper2.search.ResultMerger
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchMetrics
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.Sorting
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceFailure
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.SourceStatus
import ai.helper2.search.WebFreshness
import ai.helper2.search.WebResultStore
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.WebSearchesLeft
import ai.helper2.shared.DateFact
import ai.helper2.shared.LanguageDetector
import ai.helper2.shared.SourceMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Federated search orchestration. Queries every enabled source in parallel with its own timeout,
 * uses the cache for LIVE sources (fresh hit → no live call; live failure → stale entry if still
 * within [staleFor]), and reports exactly what happened to each source. Never throws because a
 * source, the cache or the index failed.
 *
 * A non-English query is translated in parallel with the sources that never read the translation
 * ([SourceDescriptor.usesTranslation]); the other sources start when it is in, and each source's
 * timeout and reported latency count from its own start, so translation never eats into a source's
 * budget.
 *
 * Each call runs under the source's policy `source-<id>` ([Resilience], ADR 0006): a circuit
 * breaker and a bulkhead, never a retry (the deadline is the budget). A call the policy refuses
 * does not reach the source and is SKIPPED ([SkipReason.CIRCUIT_OPEN], [SkipReason.BUSY]), with a
 * stale cache entry when one exists. Every enabled source must have a policy: a missing one fails
 * at construction, so at startup.
 *
 * A metered source (Tavily, ADR 0014) answers store first: a result set in [webResults] that is
 * fresh for the request's [IntentClass] ([webFreshness]) is served with no live call and no quota
 * used. Otherwise one web search is taken from the asker's daily [quota] before the live call; over
 * the limit, or when the quota cannot be read, the source is SKIPPED ([SkipReason.DAILY_LIMIT],
 * [SkipReason.QUOTA_UNAVAILABLE]) with the stored set when it is not older than
 * [WebFreshness.keep]. A live answer replaces the stored set.
 */
class SearchResources
@JvmOverloads
constructor(
  private val sources: List<SearchSource>,
  private val cache: SearchCache,
  private val observer: ResultObserver,
  private val executor: ExecutorService,
  private val clock: Clock,
  private val staleFor: Duration = Duration.ofHours(24),
  private val perSourceLimit: Int = 30,
  private val metrics: SearchMetrics = SearchMetrics.NOOP,
  private val analyzer: QueryAnalyzer = QueryAnalyzer(),
  private val merger: ResultMerger = ResultMerger(),
  private val ranking: Ranking = Ranking(),
  private val languages: LanguageDetector? = null,
  private val translator: QueryTranslator = QueryTranslator.NONE,
  private val translateTimeout: Duration = Duration.ofMillis(2500),
  private val resilience: Resilience = Resilience.NONE,
  private val webResults: WebResultStore = WebResultStore.NONE,
  /** Null: no daily quota (tests and tools); the application always has one. */
  private val quota: WebSearchQuota? = null,
  private val webFreshness: WebFreshness = WebFreshness(),
) {
  private val log = LoggerFactory.getLogger(SearchResources::class.java)
  private val cooldownUntil = ConcurrentHashMap<String, Instant>()

  init {
    sources.filter { it.descriptor.enabled }.forEach { resilience.require(policy(it.descriptor)) }
  }

  private class Attempt(val status: SourceStatus, val hits: List<SourceHit>)

  /**
   * A source call in flight. Its deadline and latency count from its own start, not the search's.
   */
  private class Pending(
    val source: SearchSource,
    val query: SourceQuery,
    val future: Future<List<SourceHit>>,
    val startedNanos: Long,
    val finishedNanos: AtomicLong,
  )

  /** What happened to each source in one search. */
  private inner class Run(
    val startedAt: Instant,
    val asker: Asker?,
    val intentClass: IntentClass,
    val web: Boolean = true,
  ) {
    val attempts = mutableListOf<Attempt>()
    private val pending = mutableListOf<Pending>()
    private val cachedFor = mutableMapOf<String, CachedHits?>()
    var cacheDown = false

    /** Skips [source], answers it from the cache, or calls it with [query] in the background. */
    fun start(source: SearchSource, query: SourceQuery) {
      val d = source.descriptor
      val skipped = skippedAttempt(d, query)
      if (skipped != null) {
        attempts += skipped
        return
      }
      if (d.mode == SourceMode.LIVE && answerFromFreshCache(d, query)) return
      if (d.metered && !quotaAllows(d)) return
      submitLive(source, query)
    }

    /**
     * The attempt of a source that must not run now (disabled, no web asked for, nothing to browse,
     * paused after a rate limit); null when it may run.
     */
    private fun skippedAttempt(d: SourceDescriptor, query: SourceQuery): Attempt? {
      if (!d.enabled) return skipped(d, startedAt, SkipReason.DISABLED, "Disabled in configuration")
      if (d.metered && !web) {
        return skipped(d, startedAt, SkipReason.WEB_NOT_ASKED, "Web search not asked for")
      }
      if (query.browse && !d.browses) {
        return skipped(d, startedAt, SkipReason.NOT_BROWSABLE, "No subject to search")
      }
      val pausedUntil = cooldownUntil[d.id]?.takeIf { startedAt.isBefore(it) } ?: return null
      return stale(
        d,
        startedAt,
        null,
        SourceOutcome.SKIPPED,
        "Paused after a rate limit until $pausedUntil",
        lookup(d, query),
        SkipReason.RATE_LIMIT_PAUSE,
      )
    }

    /**
     * Answers a LIVE source from a fresh cache entry; false when there is none. The entry, fresh or
     * not, is kept as the fallback for a failing live call.
     */
    private fun answerFromFreshCache(d: SourceDescriptor, query: SourceQuery): Boolean {
      val cached = lookup(d, query)
      cachedFor[d.id] = cached
      val event = if (d.metered) "store_" else ""
      if (cached != null && Duration.between(cached.fetchedAt, startedAt) < freshFor(d)) {
        attempts += servedFromCache(d, cached)
        metrics.cacheEvent(event + "hit")
        return true
      }
      metrics.cacheEvent(event + if (cached == null) "miss" else "expired")
      return false
    }

    private fun servedFromCache(d: SourceDescriptor, cached: CachedHits) =
      Attempt(
        SourceStatus(
          d,
          SourceOutcome.SUCCEEDED,
          ServedFrom.CACHE,
          cached.hits.size,
          Duration.ZERO,
          startedAt,
          cached.fetchedAt,
        ),
        cached.hits,
      )

    /** Calls [source] in the background under its resilience policy, to be collected later. */
    private fun submitLive(source: SearchSource, query: SourceQuery) {
      val d = source.descriptor
      // Each task records its own completion time so latency is per source, not per collection
      // order.
      val finished = AtomicLong(0)
      val started = System.nanoTime()
      val future =
        executor.submit<List<SourceHit>> {
          try {
            resilience.call(policy(d)) {
              if (d.metered) metrics.creditSpent(d.id, asker, asker?.let { quota?.tierOf(it) })
              source.search(query)
            }
          } catch (e: CallRefused) {
            // The credit was counted before the circuit was asked; no live call went out.
            if (d.metered) quota?.refund()
            throw e
          } finally {
            finished.set(System.nanoTime())
          }
        }
      pending += Pending(source, query, future, started, finished)
    }

    /**
     * Takes one web search for [d]'s live call; false (and the source SKIPPED) when the quota
     * refuses.
     */
    private fun quotaAllows(d: SourceDescriptor): Boolean {
      val (reason, message) =
        when (quota?.take(asker) ?: QuotaDecision.Allowed(null)) {
          is QuotaDecision.Allowed -> return true
          is QuotaDecision.OverLimit ->
            SkipReason.DAILY_LIMIT to "Daily limit of web searches reached; back at 00:00 UTC"
          QuotaDecision.Unavailable ->
            SkipReason.QUOTA_UNAVAILABLE to "Web search paused: the daily limit cannot be checked"
        }
      attempts += fallBackToCache(d, null, SourceOutcome.SKIPPED, message, reason)
      return false
    }

    /**
     * The stored set of a metered source, the cached entry of another LIVE source; a failing store
     * is a miss.
     */
    private fun lookup(d: SourceDescriptor, q: SourceQuery): CachedHits? {
      if (!d.metered) return cachedLookup(d, q) { cacheDown = true }
      return try {
        webResults.find(d.id, cacheMaterial(d, q), intentClass)
      } catch (e: RuntimeException) {
        metrics.cacheEvent("store_error")
        log.warn("web result store unavailable: ${e.javaClass.simpleName}")
        null
      }
    }

    private fun freshFor(d: SourceDescriptor): Duration =
      if (d.metered) webFreshness.of(intentClass) else d.cacheTtl

    private fun save(d: SourceDescriptor, q: SourceQuery, hits: List<SourceHit>) {
      if (d.metered) {
        try {
          webResults.replace(d.id, cacheMaterial(d, q), intentClass, startedAt, hits)
        } catch (e: RuntimeException) {
          log.warn("web result store write failed: ${e.javaClass.simpleName}")
        }
        return
      }
      try {
        cache.put(cacheKey(d, q), CachedHits(hits, startedAt), staleFor)
      } catch (e: RuntimeException) {
        cacheDown = true
        log.debug("cache put failed", e)
      }
    }

    /** When the metered results this search used stop being fresh; null when it used none. */
    fun webFreshUntil(): Instant? =
      attempts
        .filter { it.status.source.metered && it.hits.isNotEmpty() }
        .minOfOrNull { (it.status.cachedAt ?: startedAt).plus(webFreshness.of(intentClass)) }

    fun collectAll() {
      pending.forEach { attempts += collect(it) }
    }

    private fun collect(p: Pending): Attempt {
      val d = p.source.descriptor
      val deadlineNanos = p.startedNanos + d.timeout.toNanos()
      return try {
        val hits = p.future.get(maxOf(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
        val latency = latencyOf(p)
        if (d.mode == SourceMode.LIVE) save(d, p.query, hits)
        Attempt(
          SourceStatus(d, SourceOutcome.SUCCEEDED, ServedFrom.LIVE, hits.size, latency, startedAt),
          hits,
        )
      } catch (_: TimeoutException) {
        p.future.cancel(true)
        fallBackToCache(
          d,
          d.timeout,
          SourceOutcome.TIMED_OUT,
          "No response within ${d.timeout.toMillis()} ms",
        )
      } catch (e: ExecutionException) {
        failedAttempt(d, e.cause, latencyOf(p))
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        fallBackToCache(d, null, SourceOutcome.FAILED, "Interrupted")
      }
    }

    /** From the call's own start to its end, or to now while it still runs. */
    private fun latencyOf(p: Pending): Duration =
      Duration.ofNanos(
        (p.finishedNanos.get().takeIf { it > 0 } ?: System.nanoTime()) - p.startedNanos
      )

    /** The attempt of a call that threw [cause]; a rate limit also pauses the source. */
    private fun failedAttempt(d: SourceDescriptor, cause: Throwable?, latency: Duration): Attempt =
      when (cause) {
        is CallRefused -> refusedAttempt(d, cause.reason())
        is SourceFailure.RateLimited -> {
          cooldownUntil[d.id] = startedAt.plus(cause.retryAfter ?: Duration.ofMinutes(1))
          fallBackToCache(d, latency, SourceOutcome.RATE_LIMITED, "Source rate limit reached")
        }
        is SourceFailure.Unavailable ->
          fallBackToCache(d, latency, SourceOutcome.FAILED, "Source unavailable")
        else -> {
          log.warn("source ${d.id} failed: ${cause?.javaClass?.simpleName}")
          fallBackToCache(d, latency, SourceOutcome.FAILED, "Source returned an error")
        }
      }

    /** A call the resilience policy refused never reached the source: SKIPPED. */
    private fun refusedAttempt(d: SourceDescriptor, reason: CallRefused.Reason): Attempt {
      val (message, skipReason) =
        when (reason) {
          CallRefused.Reason.CIRCUIT_OPEN ->
            "Paused after repeated failures" to SkipReason.CIRCUIT_OPEN
          CallRefused.Reason.BUSY -> "Too many searches at once" to SkipReason.BUSY
        }
      return fallBackToCache(d, null, SourceOutcome.SKIPPED, message, skipReason)
    }

    /** A failed attempt for [d], rescued by the entry looked up when it started, if any. */
    private fun fallBackToCache(
      d: SourceDescriptor,
      latency: Duration?,
      outcome: SourceOutcome,
      message: String,
      skipReason: SkipReason? = null,
    ): Attempt = stale(d, startedAt, latency, outcome, message, cachedFor[d.id], skipReason)
  }

  fun search(request: SearchRequest): SearchOutcome {
    val startedNanos = System.nanoTime()
    val startedAt = clock.instant()
    val language = languages?.detect(request.text)?.takeIf { it != "en" }
    val untranslated = analyzer.analyze(request.text)
    val translation = language?.let { startTranslation(request.text, it) }
    val run = Run(startedAt, request.asker, IntentClass.of(request, untranslated), request.web)
    val analyzed = runSources(run, request, untranslated, language, translation)

    val statuses = statusesOf(run.attempts)
    statuses.forEach(metrics::sourceCompleted)
    val merged = mergeHits(run.attempts)
    val dated = withDiscoveryDates(merged)
    val results = dated ?: merged
    record(results, statuses)

    val ranked = rank(results, request, analyzed)
    val sorted = Sorting.sort(Filtering.apply(ranked, request.filters), request.sort)
    val coverage = coverageOf(statuses)
    val notices =
      listOfNotNull(
        indexUnavailableNotice(dated),
        cacheUnavailableNotice(run),
        queryExpandedNotice(analyzed),
        allSourcesFailedNotice(statuses),
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
      webFreshUntil = run.webFreshUntil(),
    )
  }

  /**
   * Starts the sources that never read the translation first, the others once it is in, and
   * collects them all. Returns the query as searched: translated when a translation came back.
   */
  private fun runSources(
    run: Run,
    request: SearchRequest,
    untranslated: AnalyzedQuery,
    language: String?,
    translation: Future<String?>?,
  ): AnalyzedQuery {
    val (early, late) = sources.partition { translation == null || !it.descriptor.usesTranslation }
    val untranslatedQuery = sourceQuery(request, untranslated, language)
    early.forEach { run.start(it, untranslatedQuery) }
    val analyzed =
      withTranslation(untranslated, translation?.let { awaitTranslation(it, request.text) })
    val query =
      if (analyzed === untranslated) untranslatedQuery else sourceQuery(request, analyzed, language)
    late.forEach { run.start(it, query) }
    run.collectAll()
    return analyzed
  }

  /** One status per configured source, in configuration order. */
  private fun statusesOf(attempts: List<Attempt>): List<SourceStatus> = sources.map { s ->
    attempts.first { it.status.source.id == s.descriptor.id }.status
  }

  private fun mergeHits(attempts: List<Attempt>): List<MergedResult> {
    val contexts = attempts.associate {
      it.status.source.id to SourceContext(it.status.source, it.status.servedFrom)
    }
    return merger.merge(attempts.flatMap { it.hits }, contexts)
  }

  /** [merged] with the date the index first saw each result; null when the index is down. */
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

  private fun coverageOf(statuses: List<SourceStatus>): Coverage {
    val queried = statuses.count(::wasExpectedToAnswer)
    val succeeded = statuses.count { it.outcome == SourceOutcome.SUCCEEDED }
    // The banner and its counts come from the same two numbers, so it never says "8 of 8".
    return Coverage(succeeded < queried, sources.size, queried, succeeded)
  }

  /**
   * A source counts as queried when it was called, served from cache, or was left out because it is
   * failing (paused, circuit open, busy, quota counter down). Left out on purpose (disabled,
   * nothing to browse, no web credits left) it does not count: its tile says why, and the results
   * are not incomplete for that.
   */
  private fun wasExpectedToAnswer(status: SourceStatus): Boolean =
    status.outcome != SourceOutcome.SKIPPED ||
      status.servedFrom != ServedFrom.NONE ||
      status.skipReason in UNAVAILABLE

  private fun indexUnavailableNotice(dated: List<MergedResult>?): Notice? =
    if (dated != null) null
    else
      Notice(
        NoticeCode.INDEX_UNAVAILABLE,
        "Helper2 index unavailable; discovery dates are unknown for this search.",
      )

  private fun cacheUnavailableNotice(run: Run): Notice? =
    if (!run.cacheDown) null
    else Notice(NoticeCode.CACHE_UNAVAILABLE, "Cache unavailable; all sources were queried live.")

  private fun queryExpandedNotice(analyzed: AnalyzedQuery): Notice? =
    if (analyzed.expandedTerms.isEmpty()) null
    else
      Notice(
        NoticeCode.QUERY_EXPANDED,
        "Also searched for: ${analyzed.expandedTerms.joinToString(", ")}",
      )

  /** Every enabled source failed and none could be rescued from the cache. */
  private fun allSourcesFailedNotice(statuses: List<SourceStatus>): Notice? {
    val enabled = statuses.filter { it.source.enabled }
    if (enabled.isEmpty()) return null
    if (statuses.any { it.outcome == SourceOutcome.SUCCEEDED }) return null
    if (enabled.any { it.servedFrom == ServedFrom.STALE_CACHE }) return null
    return Notice(NoticeCode.ALL_SOURCES_FAILED, "No source could be reached.")
  }

  /**
   * What [asker]'s feature has left today; null without an enabled metered source, a quota, or a
   * readable count.
   */
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
    )

  /**
   * Hands the catalog only what was fetched live now: hits replayed from the cache were recorded
   * when fetched.
   */
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

  /** Starts the model call; null when no model is configured. */
  private fun startTranslation(text: String, language: String): Future<String?>? =
    if (translator === QueryTranslator.NONE) null
    else executor.submit<String?> { translator.toEnglish(text, language) }

  /**
   * English terms for a non-English query, or null. Bounded by [translateTimeout]; any failure
   * means the search runs untranslated. The model's answer is untrusted: only words are kept, never
   * links.
   */
  private fun awaitTranslation(future: Future<String?>, text: String): String? {
    val raw =
      try {
        future.get(translateTimeout.toMillis(), TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        future.cancel(true)
        log.info("Query translation timed out after $translateTimeout")
        null
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
      } catch (e: Exception) {
        log.info("Query translation failed: {}", e.javaClass.simpleName)
        null
      } ?: return null
    if (URL_LIKE.containsMatchIn(raw)) return null
    val words =
      raw
        .lowercase()
        .replace(NON_WORD, " ")
        .trim()
        .split(SPACES)
        .take(QueryTranslator.MAX_WORDS)
        .joinToString(" ")
        .take(MAX_TRANSLATION_CHARS)
        .trim()
    return words.takeIf { it.isNotBlank() && it != analyzer.analyze(text).normalized }
  }

  /**
   * The translation is searched first (all its words required) and disclosed like any expansion.
   */
  private fun withTranslation(analyzed: AnalyzedQuery, english: String?): AnalyzedQuery =
    if (english == null) analyzed
    else
      analyzed.copy(
        expandedTerms = (listOf(english) + analyzed.expandedTerms).distinct(),
        requiredVariants = (listOf(english) + analyzed.requiredVariants).distinct(),
        translation = english,
      )

  private fun skipped(d: SourceDescriptor, at: Instant, reason: SkipReason, message: String) =
    Attempt(
      SourceStatus(d, SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, at, null, message, reason),
      emptyList(),
    )

  /**
   * A failed attempt, rescued by a stale entry when one exists within [staleFor]
   * ([WebFreshness.keep] when metered).
   */
  private fun stale(
    d: SourceDescriptor,
    at: Instant,
    latency: Duration?,
    outcome: SourceOutcome,
    message: String,
    cached: CachedHits?,
    skipReason: SkipReason? = null,
  ): Attempt {
    val usable = cached?.takeIf {
      Duration.between(it.fetchedAt, at) < if (d.metered) webFreshness.keep else staleFor
    }
    return if (usable != null) {
      metrics.cacheEvent("stale_served")
      Attempt(
        SourceStatus(
          d,
          outcome,
          ServedFrom.STALE_CACHE,
          usable.hits.size,
          latency,
          at,
          usable.fetchedAt,
          "$message; showing cached results",
          skipReason,
        ),
        usable.hits,
      )
    } else {
      Attempt(
        SourceStatus(d, outcome, ServedFrom.NONE, 0, latency, at, null, message, skipReason),
        emptyList(),
      )
    }
  }

  private fun cachedLookup(d: SourceDescriptor, q: SourceQuery, onError: () -> Unit): CachedHits? {
    if (d.mode != SourceMode.LIVE) return null
    return try {
      cache.get(cacheKey(d, q))
    } catch (e: RuntimeException) {
      onError()
      metrics.cacheEvent("error")
      log.debug("cache get failed", e)
      null
    }
  }

  companion object {
    private const val MAX_TRANSLATION_CHARS = 120

    /**
     * Skip reasons that mean the source or its quota counter is failing, not left out on purpose.
     */
    private val UNAVAILABLE =
      setOf(
        SkipReason.RATE_LIMIT_PAUSE,
        SkipReason.CIRCUIT_OPEN,
        SkipReason.BUSY,
        SkipReason.QUOTA_UNAVAILABLE,
      )

    /** The resilience policy of a search source. */
    @JvmStatic fun policy(d: SourceDescriptor) = "source-${d.id}"

    private val URL_LIKE = Regex("(?i)https?:|www\\.|://")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s-]+")
    private val SPACES = Regex("\\s+")

    /** `h2:v1:src:{sourceId}:{sha256(cacheMaterial)}` — no raw query text in keys. */
    @JvmStatic
    fun cacheKey(d: SourceDescriptor, q: SourceQuery): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(cacheMaterial(d, q).toByteArray())
      return "h2:v1:src:${d.id}:${HexFormat.of().formatHex(digest, 0, 16)}"
    }

    /**
     * What a source's answer depends on: `normalized query|expansions|limit[|web
     * query][|after][|browse, types]`. Each optional part only for sources whose results it
     * changes, so plain searches keep sharing entries; without the window a windowed query would be
     * served the cached unwindowed hits. Hashed before it is stored anywhere.
     */
    private fun cacheMaterial(d: SourceDescriptor, q: SourceQuery): String {
      val normalized = QueryAnalyzer().analyze(q.text).normalized
      val web = q.webQuery?.takeIf { d.usesWebQuery }?.let { "|web:$it" } ?: ""
      val after = q.publishedAfter?.takeIf { d.filtersByDate }?.let { "|after:$it" } ?: ""
      val browse =
        if (q.browse) "|browse:${q.types.map { it.name }.sorted().joinToString(",")}" else ""
      return "$normalized|${q.expandedTerms.joinToString(",")}|${q.limit}$web$after$browse"
    }
  }
}
