package ai.helper2.search.usecase

import ai.helper2.platform.resilience.CallRefused
import ai.helper2.platform.resilience.Resilience
import ai.helper2.search.Asker
import ai.helper2.search.CachedHits
import ai.helper2.search.IntentClass
import ai.helper2.search.QuotaDecision
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchMetrics
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceFailure
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.SourceStatus
import ai.helper2.search.VitalityPolicy
import ai.helper2.search.WebFreshness
import ai.helper2.search.WebResultStore
import ai.helper2.search.WebSearchQuota
import ai.helper2.shared.SourceMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

internal class SourceAttempt(val status: SourceStatus, val hits: List<SourceHit>)

internal class FanOutResult(
  val attempts: List<SourceAttempt>,
  val cacheDown: Boolean,
  val webFreshUntil: Instant?,
)

internal class SourceFanOut(
  private val cache: SearchCache,
  private val webResults: WebResultStore,
  private val quota: WebSearchQuota?,
  private val resilience: Resilience,
  private val executor: ExecutorService,
  private val metrics: SearchMetrics,
  private val vitality: VitalityPolicy,
  private val webFreshness: WebFreshness,
  private val staleFor: Duration,
  private val perSourceLimit: Int,
) {
  private val log = LoggerFactory.getLogger(SourceFanOut::class.java)
  private val cooldownUntil = ConcurrentHashMap<String, Instant>()

  fun run(startedAt: Instant, asker: Asker?, intentClass: IntentClass, web: Boolean): Run =
    Run(startedAt, asker, intentClass, web)

  private class Pending(
    val source: SearchSource,
    val query: SourceQuery,
    val future: Future<List<SourceHit>>,
    val startedNanos: Long,
    val finishedNanos: AtomicLong,
  )

  inner class Run(
    private val startedAt: Instant,
    private val asker: Asker?,
    private val intentClass: IntentClass,
    private val web: Boolean,
  ) {
    private val attempts = mutableListOf<SourceAttempt>()
    private val pending = mutableListOf<Pending>()
    private val cachedFor = mutableMapOf<String, CachedHits?>()
    private val answeredByTheWeb = mutableSetOf<Pending>()
    private var cacheDown = false

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

    private fun skippedAttempt(d: SourceDescriptor, query: SourceQuery): SourceAttempt? {
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
      SourceAttempt(
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

    private fun submitLive(source: SearchSource, query: SourceQuery) {
      val d = source.descriptor
      val finished = AtomicLong(0)
      val started = System.nanoTime()
      val future =
        executor.submit<List<SourceHit>> {
          try {
            resilience.call(policy(d)) {
              if (d.metered) metrics.creditSpent(d.id, asker)
              source.search(query)
            }
          } catch (e: CallRefused) {
            if (d.metered) quota?.refund()
            throw e
          } finally {
            finished.set(System.nanoTime())
          }
        }
      pending += Pending(source, query, future, started, finished)
    }

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

    private fun lookup(d: SourceDescriptor, q: SourceQuery): CachedHits? {
      if (!d.metered) return cachedLookup(d, q) { cacheDown = true }
      return try {
        webResults.find(d.id, SourceCacheKeys.cacheMaterial(d, q))
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
          webResults.replace(d.id, SourceCacheKeys.cacheMaterial(d, q), startedAt, hits)
        } catch (e: RuntimeException) {
          log.warn("web result store write failed: ${e.javaClass.simpleName}")
        }
        return
      }
      try {
        cache.put(SourceCacheKeys.cacheKey(d, q), CachedHits(hits, startedAt), staleFor)
      } catch (e: RuntimeException) {
        cacheDown = true
        log.debug("cache put failed", e)
      }
    }

    private fun webFreshUntil(): Instant? =
      attempts
        .filter { it.status.source.metered && it.hits.isNotEmpty() }
        .minOfOrNull { (it.status.cachedAt ?: startedAt).plus(webFreshness.of(intentClass)) }

    fun collectAll(): FanOutResult {
      pending.forEach { attempts += collect(it) }
      settleWebSearches()
      return FanOutResult(attempts, cacheDown, webFreshUntil())
    }

    private fun settleWebSearches() {
      if (asker == null) return
      pending
        .filter { it.source.descriptor.metered }
        .forEach { if (it in answeredByTheWeb) quota?.answered(asker) else quota?.giveBack(asker) }
    }

    private fun collect(p: Pending): SourceAttempt {
      val d = p.source.descriptor
      val deadlineNanos = p.startedNanos + d.timeout.toNanos()
      return try {
        val answered =
          p.future.get(maxOf(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
        val hits = admitted(d, answered)
        val latency = latencyOf(p)
        if (d.mode == SourceMode.LIVE) save(d, p.query, hits)
        if (d.metered) answeredByTheWeb += p
        SourceAttempt(
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

    private fun admitted(d: SourceDescriptor, answered: List<SourceHit>): List<SourceHit> =
      vitality
        .admitted(answered) { _, refusal -> metrics.hitRefused(d.id, refusal) }
        .take(perSourceLimit)

    private fun latencyOf(p: Pending): Duration =
      Duration.ofNanos(
        (p.finishedNanos.get().takeIf { it > 0 } ?: System.nanoTime()) - p.startedNanos
      )

    private fun failedAttempt(
      d: SourceDescriptor,
      cause: Throwable?,
      latency: Duration,
    ): SourceAttempt =
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

    private fun refusedAttempt(d: SourceDescriptor, reason: CallRefused.Reason): SourceAttempt {
      val (message, skipReason) =
        when (reason) {
          CallRefused.Reason.CIRCUIT_OPEN ->
            "Paused after repeated failures" to SkipReason.CIRCUIT_OPEN
          CallRefused.Reason.BUSY -> "Too many searches at once" to SkipReason.BUSY
        }
      return fallBackToCache(d, null, SourceOutcome.SKIPPED, message, skipReason)
    }

    private fun fallBackToCache(
      d: SourceDescriptor,
      latency: Duration?,
      outcome: SourceOutcome,
      message: String,
      skipReason: SkipReason? = null,
    ): SourceAttempt = stale(d, startedAt, latency, outcome, message, cachedFor[d.id], skipReason)
  }

  private fun skipped(d: SourceDescriptor, at: Instant, reason: SkipReason, message: String) =
    SourceAttempt(
      SourceStatus(d, SourceOutcome.SKIPPED, ServedFrom.NONE, 0, null, at, null, message, reason),
      emptyList(),
    )

  private fun stale(
    d: SourceDescriptor,
    at: Instant,
    latency: Duration?,
    outcome: SourceOutcome,
    message: String,
    cached: CachedHits?,
    skipReason: SkipReason? = null,
  ): SourceAttempt {
    val usable = cached?.takeIf {
      Duration.between(it.fetchedAt, at) < if (d.metered) webFreshness.keep else staleFor
    }
    return if (usable != null) {
      metrics.cacheEvent("stale_served")
      SourceAttempt(
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
      SourceAttempt(
        SourceStatus(d, outcome, ServedFrom.NONE, 0, latency, at, null, message, skipReason),
        emptyList(),
      )
    }
  }

  private fun cachedLookup(d: SourceDescriptor, q: SourceQuery, onError: () -> Unit): CachedHits? {
    if (d.mode != SourceMode.LIVE) return null
    return try {
      cache.get(SourceCacheKeys.cacheKey(d, q))
    } catch (e: RuntimeException) {
      onError()
      metrics.cacheEvent("error")
      log.debug("cache get failed", e)
      null
    }
  }

  companion object {
    fun policy(d: SourceDescriptor) = "source-${d.id}"
  }
}
