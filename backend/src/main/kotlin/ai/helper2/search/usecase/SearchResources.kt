package ai.helper2.search.usecase

import ai.helper2.search.AnalyzedQuery
import ai.helper2.search.CachedHits
import ai.helper2.search.Coverage
import ai.helper2.search.Facets
import ai.helper2.search.Filtering
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
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
import ai.helper2.search.SourceFailure
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.SourceStatus
import ai.helper2.shared.AudiencePath
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
 * ([SourceDescriptor.usesTranslation]); the other sources start when it is in, and each source's timeout
 * and reported latency count from its own start, so translation never eats into a source's budget.
 */
class SearchResources(
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
) {
    private val log = LoggerFactory.getLogger(SearchResources::class.java)
    private val cooldownUntil = ConcurrentHashMap<String, Instant>()

    private class Attempt(val status: SourceStatus, val hits: List<SourceHit>)

    /** A source call in flight. Its deadline and latency count from its own start, not the search's. */
    private class Pending(
        val source: SearchSource,
        val query: SourceQuery,
        val future: Future<List<SourceHit>>,
        val startedNanos: Long,
        val finishedNanos: AtomicLong,
    )

    /** What happened to each source in one search. */
    private inner class Run(val startedAt: Instant) {
        val attempts = mutableListOf<Attempt>()
        private val pending = mutableListOf<Pending>()
        private val cachedFor = mutableMapOf<String, CachedHits?>()
        var cacheDown = false

        /** Skips [source], answers it from the cache, or calls it with [query] in the background. */
        fun start(source: SearchSource, query: SourceQuery) {
            val d = source.descriptor
            if (!d.enabled) {
                attempts += skipped(d, startedAt, "Disabled in configuration")
                return
            }
            val cooldown = cooldownUntil[d.id]
            if (cooldown != null && startedAt.isBefore(cooldown)) {
                attempts += stale(d, startedAt, null, SourceOutcome.SKIPPED, "Paused after a rate limit until $cooldown",
                    cachedLookup(d, query) { cacheDown = true })
                return
            }
            if (d.mode == SourceMode.LIVE) {
                val cached = cachedLookup(d, query) { cacheDown = true }
                cachedFor[d.id] = cached
                if (cached != null && Duration.between(cached.fetchedAt, startedAt) < d.cacheTtl) {
                    attempts += Attempt(
                        SourceStatus(d, SourceOutcome.SUCCEEDED, ServedFrom.CACHE, cached.hits.size, Duration.ZERO, startedAt, cached.fetchedAt),
                        cached.hits,
                    )
                    metrics.cacheEvent("hit")
                    return
                }
                metrics.cacheEvent(if (cached == null) "miss" else "expired")
            }
            // Each task records its own completion time so latency is per source, not per collection order.
            val finished = AtomicLong(0)
            val started = System.nanoTime()
            val future = executor.submit<List<SourceHit>> {
                try {
                    source.search(query)
                } finally {
                    finished.set(System.nanoTime())
                }
            }
            pending += Pending(source, query, future, started, finished)
        }

        fun collectAll() {
            pending.forEach { attempts += collect(it) }
        }

        private fun collect(p: Pending): Attempt {
            val d = p.source.descriptor
            fun latency(): Duration = Duration.ofNanos((p.finishedNanos.get().takeIf { it > 0 } ?: System.nanoTime()) - p.startedNanos)
            val deadlineNanos = p.startedNanos + d.timeout.toNanos()
            return try {
                val hits = p.future.get(maxOf(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
                val latency = latency()
                if (d.mode == SourceMode.LIVE) {
                    try {
                        cache.put(cacheKey(d, p.query), CachedHits(hits, startedAt), staleFor)
                    } catch (e: RuntimeException) {
                        cacheDown = true
                        log.debug("cache put failed", e)
                    }
                }
                Attempt(SourceStatus(d, SourceOutcome.SUCCEEDED, ServedFrom.LIVE, hits.size, latency, startedAt), hits)
            } catch (_: TimeoutException) {
                p.future.cancel(true)
                stale(d, startedAt, d.timeout, SourceOutcome.TIMED_OUT, "No response within ${d.timeout.toMillis()} ms", cachedFor[d.id])
            } catch (e: ExecutionException) {
                val latency = latency()
                when (val cause = e.cause) {
                    is SourceFailure.RateLimited -> {
                        cooldownUntil[d.id] = startedAt.plus(cause.retryAfter ?: Duration.ofMinutes(1))
                        stale(d, startedAt, latency, SourceOutcome.RATE_LIMITED, "Source rate limit reached", cachedFor[d.id])
                    }
                    is SourceFailure.Unavailable ->
                        stale(d, startedAt, latency, SourceOutcome.FAILED, "Source unavailable", cachedFor[d.id])
                    else -> {
                        log.warn("source ${d.id} failed: ${cause?.javaClass?.simpleName}")
                        stale(d, startedAt, latency, SourceOutcome.FAILED, "Source returned an error", cachedFor[d.id])
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                stale(d, startedAt, null, SourceOutcome.FAILED, "Interrupted", cachedFor[d.id])
            }
        }
    }

    fun search(request: SearchRequest): SearchOutcome {
        val startedNanos = System.nanoTime()
        val startedAt = clock.instant()
        val language = languages?.detect(request.text)?.takeIf { it != "en" }
        val untranslated = analyzer.analyze(request.text)
        val translation = language?.let { startTranslation(request.text, it) }
        val run = Run(startedAt)
        val (early, late) = sources.partition { translation == null || !it.descriptor.usesTranslation }
        val untranslatedQuery = sourceQuery(request, untranslated, language)
        early.forEach { run.start(it, untranslatedQuery) }
        val analyzed = withTranslation(untranslated, translation?.let { awaitTranslation(it, request.text) })
        val query = if (analyzed === untranslated) untranslatedQuery else sourceQuery(request, analyzed, language)
        late.forEach { run.start(it, query) }
        run.collectAll()

        val attempts = run.attempts
        val notices = mutableListOf<Notice>()
        val statuses = sources.map { s -> attempts.first { it.status.source.id == s.descriptor.id }.status }
        statuses.forEach(metrics::sourceCompleted)

        val contexts = attempts.associate { it.status.source.id to SourceContext(it.status.source, it.status.servedFrom) }
        var merged = merger.merge(attempts.flatMap { it.hits }, contexts)

        try {
            val discovered = observer.firstDiscovered(merged)
            merged = merged.map { r -> r.copy(discovered = DateFact.observed(discovered[r.id])) }
        } catch (e: RuntimeException) {
            log.warn("result observation failed: ${e.javaClass.simpleName}")
            notices += Notice(NoticeCode.INDEX_UNAVAILABLE, "Helper2 index unavailable; discovery dates are unknown for this search.")
        }
        record(merged, statuses)

        val ranked = ranking.rank(merged, request.text, request.path, analyzed.typeHints,
            analyzed.requiredVariants.first().split(' '))
        val filtered = Filtering.apply(ranked, request.filters)
        val sorted = Sorting.sort(filtered, request.sort)
        val pageItems = sorted.drop(request.page * request.size).take(request.size)

        val enabled = statuses.filter { it.source.enabled }
        val queried = statuses.count { it.outcome != SourceOutcome.SKIPPED || it.servedFrom != ServedFrom.NONE }
        val succeeded = statuses.count { it.outcome == SourceOutcome.SUCCEEDED }
        val partial = enabled.any { it.outcome != SourceOutcome.SUCCEEDED }

        if (run.cacheDown) notices += Notice(NoticeCode.CACHE_UNAVAILABLE, "Cache unavailable; all sources were queried live.")
        if (analyzed.expandedTerms.isNotEmpty()) {
            notices += Notice(NoticeCode.QUERY_EXPANDED, "Also searched for: ${analyzed.expandedTerms.joinToString(", ")}")
        }
        if (enabled.isNotEmpty() && succeeded == 0 && enabled.none { it.servedFrom == ServedFrom.STALE_CACHE }) {
            notices += Notice(NoticeCode.ALL_SOURCES_FAILED, "No source could be reached.")
        }

        metrics.searchCompleted(partial, Duration.ofNanos(System.nanoTime() - startedNanos))
        return SearchOutcome(
            request = request,
            interpretation = analyzed,
            executedAt = startedAt,
            statuses = statuses,
            results = pageItems,
            total = sorted.size,
            facets = Facets.compute(ranked, request.filters),
            coverage = Coverage(partial, sources.size, queried, succeeded),
            notices = notices,
        )
    }

    private fun sourceQuery(request: SearchRequest, analyzed: AnalyzedQuery, language: String?) =
        SourceQuery(request.text.trim(), analyzed.expandedTerms, request.path, perSourceLimit,
            analyzed.requiredVariants, analyzed.synonyms, language, request.webQuery?.trim()?.ifBlank { null })

    /** Hands the catalog only what was fetched live now: hits replayed from the cache were recorded when fetched. */
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
        if (translator === QueryTranslator.NONE) null else executor.submit<String?> { translator.toEnglish(text, language) }

    /**
     * English terms for a non-English query, or null. Bounded by [translateTimeout]; any failure means
     * the search runs untranslated. The model's answer is untrusted: only words are kept, never links.
     */
    private fun awaitTranslation(future: Future<String?>, text: String): String? {
        val raw = try {
            future.get(translateTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.info("Query translation timed out after $translateTimeout")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            log.info("Query translation failed", e)
            null
        } ?: return null
        if (URL_LIKE.containsMatchIn(raw)) return null
        val words = raw.lowercase().replace(NON_WORD, " ").trim().split(SPACES).take(QueryTranslator.MAX_WORDS)
            .joinToString(" ").take(MAX_TRANSLATION_CHARS).trim()
        return words.takeIf { it.isNotBlank() && it != analyzer.analyze(text).normalized }
    }

    /** The translation is searched first (all its words required) and disclosed like any expansion. */
    private fun withTranslation(analyzed: AnalyzedQuery, english: String?): AnalyzedQuery =
        if (english == null) analyzed else analyzed.copy(
            expandedTerms = (listOf(english) + analyzed.expandedTerms).distinct(),
            requiredVariants = (listOf(english) + analyzed.requiredVariants).distinct(),
        )

    private fun skipped(d: SourceDescriptor, at: Instant, message: String) =
        Attempt(SourceStatus(d, SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, at, null, message), emptyList())

    /** A failed attempt, rescued by a stale cache entry when one exists within [staleFor]. */
    private fun stale(
        d: SourceDescriptor,
        at: Instant,
        latency: Duration?,
        outcome: SourceOutcome,
        message: String,
        cached: CachedHits?,
    ): Attempt {
        val usable = cached?.takeIf { Duration.between(it.fetchedAt, at) < staleFor }
        return if (usable != null) {
            metrics.cacheEvent("stale_served")
            Attempt(
                SourceStatus(d, outcome, ServedFrom.STALE_CACHE, usable.hits.size, latency, at, usable.fetchedAt, "$message; showing cached results"),
                usable.hits,
            )
        } else {
            Attempt(SourceStatus(d, outcome, ServedFrom.NONE, 0, latency, at, null, message), emptyList())
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
        private val URL_LIKE = Regex("(?i)https?:|www\\.|://")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s-]+")
        private val SPACES = Regex("\\s+")

        /** `h2:v1:src:{sourceId}:{sha256(normalized query|expansions|limit[|web query, sources that use it])}` — no raw query text in keys. */
        @JvmStatic
        fun cacheKey(d: SourceDescriptor, q: SourceQuery): String {
            val normalized = QueryAnalyzer().analyze(q.text).normalized
            // webQuery only for sources that read it, so other sources and plain searches keep sharing entries.
            val web = q.webQuery?.takeIf { d.usesWebQuery }?.let { "|web:$it" } ?: ""
            val material = "$normalized|${q.expandedTerms.joinToString(",")}|${q.limit}$web"
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            return "h2:v1:src:${d.id}:${HexFormat.of().formatHex(digest, 0, 16)}"
        }
    }
}
