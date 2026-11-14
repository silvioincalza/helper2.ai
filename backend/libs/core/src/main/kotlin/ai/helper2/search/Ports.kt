package ai.helper2.search

import ai.helper2.shared.ResourceId
import java.time.Duration
import java.time.Instant

interface DescribedSource {
  val descriptor: SourceDescriptor
}

interface SearchSource : DescribedSource {
  fun search(query: SourceQuery): List<SourceHit>
}

data class CachedHits(val hits: List<SourceHit>, val fetchedAt: Instant)

interface SearchCache {
  fun get(key: String): CachedHits?

  fun put(key: String, value: CachedHits, ttl: Duration)
}

fun interface ResultObserver {
  fun firstDiscovered(results: List<MergedResult>): Map<ResourceId, Instant>

  fun record(results: List<MergedResult>) {}

  fun recordedHits(sourceId: String, ids: List<ResourceId>): List<SourceHit> = emptyList()
}

interface WebResultStore {
  fun find(sourceId: String, query: String): CachedHits?

  fun replace(
    sourceId: String,
    query: String,
    fetchedAt: Instant,
    hits: List<SourceHit>,
  )

  companion object {
    @JvmField
    val NONE: WebResultStore =
      object : WebResultStore {
        override fun find(sourceId: String, query: String): CachedHits? = null

        override fun replace(
          sourceId: String,
          query: String,
          fetchedAt: Instant,
          hits: List<SourceHit>,
        ) {}
      }
  }
}

fun interface WebResultRetention {
  fun deleteFetchedBefore(cutoff: Instant): Int
}

fun interface RecordedOutcomes {
  fun lastOutcomes(): Map<String, SourceHealth.Entry>
}

fun interface DiscoveredResources {
  fun countsSince(since: Instant): Map<String, Long?>
}

interface SearchMetrics {
  fun sourceCompleted(status: SourceStatus) {}

  fun cacheEvent(event: String) {}

  fun searchCompleted(partial: Boolean, empty: Boolean, latency: Duration) {}

  fun creditSpent(sourceId: String, asker: Asker?) {}

  fun hitRefused(sourceId: String, refusal: Refusal) {}

  companion object {
    @JvmField val NOOP: SearchMetrics = object : SearchMetrics {}
  }
}

fun interface QueryTranslator {
  fun toEnglish(text: String, language: String): String?

  companion object {
    @JvmField val MAX_WORDS: Int = 12

    @JvmField val MAX_CHARS: Int = 120

    @JvmStatic
    fun bounded(text: String): String {
      val words = text.trim().split(Regex("\\s+")).take(MAX_WORDS).joinToString(" ")
      if (words.codePointCount(0, words.length) <= MAX_CHARS) return words
      return words.substring(0, words.offsetByCodePoints(0, MAX_CHARS)).trim()
    }

    @JvmField val NONE: QueryTranslator = QueryTranslator { _, _ -> null }
  }
}
