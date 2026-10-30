package ai.helper2.search

import ai.helper2.shared.ResourceId
import java.time.Duration
import java.time.Instant

/** Outbound port: one federated source (live API, Helper2 index, curated seed...). */
/** Anything that has a disclosed descriptor: search sources and Scout collectors. */
interface DescribedSource {
    val descriptor: SourceDescriptor
}

interface SearchSource : DescribedSource {

    /** Throw [SourceFailure] for known failure modes; any other exception counts as FAILED. */
    fun search(query: SourceQuery): List<SourceHit>
}

data class CachedHits(val hits: List<SourceHit>, val fetchedAt: Instant)

/** Outbound port: distributed cache of per-source responses. Never the source of truth; may throw. */
interface SearchCache {
    fun get(key: String): CachedHits?
    fun put(key: String, value: CachedHits, ttl: Duration)
}

/**
 * Outbound port to the catalog, which keeps every resource a search saw so Helper2 can state when it was
 * first discovered and detail pages work for live results.
 */
fun interface ResultObserver {
    /** When each of [results] was first discovered, for those already recorded. One read; may throw. */
    fun firstDiscovered(results: List<MergedResult>): Map<ResourceId, Instant>

    /**
     * Records [results] fetched live in this search (never ones served from the cache). Must not block the
     * search: implementations write in the background and may drop work under load.
     */
    fun record(results: List<MergedResult>) {}

    /**
     * The hits [sourceId] gave for the resources [ids] (each the [ResourceId] of a hit's own URL), as recorded, in
     * the order of [ids]; ids without such a record are left out. May throw.
     */
    fun recordedHits(sourceId: String, ids: List<ResourceId>): List<SourceHit> = emptyList()
}

/**
 * Outbound port: the result set a metered web source returned for a query (ADR 0037), by source, query and
 * [IntentClass]. The adapter keys it by an HMAC of [query]; the text itself is never stored. A set whose hits are no
 * longer all in the catalog is not found. May throw.
 */
interface WebResultStore {
    fun find(sourceId: String, query: String, intentClass: IntentClass): CachedHits?

    /** Replaces the stored set of this query with [hits], fetched at [fetchedAt]. */
    fun replace(sourceId: String, query: String, intentClass: IntentClass, fetchedAt: Instant, hits: List<SourceHit>)

    companion object {
        /** Nothing stored: every search of a metered source is a live call. */
        @JvmField
        val NONE: WebResultStore = object : WebResultStore {
            override fun find(sourceId: String, query: String, intentClass: IntentClass): CachedHits? = null
            override fun replace(sourceId: String, query: String, intentClass: IntentClass, fetchedAt: Instant, hits: List<SourceHit>) {}
        }
    }
}

/** Outbound port: stored web result sets past their retention (ADR 0034). */
fun interface WebResultRetention {
    /** Deletes the result sets fetched before [cutoff]; returns how many. */
    fun deleteFetchedBefore(cutoff: Instant): Int
}

/**
 * Outbound port: the last outcome each source recorded outside a search (Scout collector runs),
 * persisted so it survives restarts and is the same on every instance. Implemented by the catalog.
 * May throw; the Sources page then shows only what this instance saw in searches.
 */
fun interface RecordedOutcomes {
    fun lastOutcomes(): Map<String, SourceHealth.Entry>
}

/** Outbound port for operational metrics. */
interface SearchMetrics {
    fun sourceCompleted(status: SourceStatus) {}
    fun cacheEvent(event: String) {}
    fun searchCompleted(partial: Boolean, latency: Duration) {}

    /** One live call of the metered source [sourceId], for [asker] (null: an internal caller) in [tier]. */
    fun creditSpent(sourceId: String, asker: Asker?, tier: QuotaTier?) {}

    companion object {
        @JvmField
        val NOOP: SearchMetrics = object : SearchMetrics {}
    }
}

/**
 * Outbound port: English search terms for a query written in another language, so the English catalog
 * and the live sources (English APIs) can match it. Implemented with the guide's fast model; null when
 * no model is configured, the daily model budget is spent, or the call fails. May be slow: SearchResources
 * bounds it with a timeout and treats the result as untrusted text.
 */
fun interface QueryTranslator {
    fun toEnglish(text: String, language: String): String?

    companion object {
        /** Longest translation search uses; the translation prompt states the same number. */
        @JvmField
        val MAX_WORDS: Int = 12

        @JvmField
        val NONE: QueryTranslator = QueryTranslator { _, _ -> null }
    }
}
