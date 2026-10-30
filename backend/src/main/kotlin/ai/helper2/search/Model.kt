package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.EventFormat
import ai.helper2.shared.EventKind
import ai.helper2.shared.Pricing
import ai.helper2.shared.ResourceId
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SourceMode
import ai.helper2.shared.SourcedValue
import ai.helper2.shared.TechLevel
import ai.helper2.shared.ValueOrigin
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Static description of a configured source. Disclosed to users on the Sources page. */
data class SourceDescriptor @JvmOverloads constructor(
    val id: String,
    val name: String,
    val mode: SourceMode,
    val homepage: URI,
    val termsUrl: URI?,
    val attribution: String?,
    val resourceTypes: Set<ResourceType>,
    val timeout: Duration,
    val cacheTtl: Duration,
    val enabled: Boolean = true,
    /** Lower = preferred when sources disagree on display text. */
    val priority: Int = 100,
    /** True when [SourceQuery.webQuery] changes what this source returns; only then is it part of the cache key. */
    val usesWebQuery: Boolean = false,
    /**
     * False when the source never reads the English translation of a non-English query (it searches the
     * text as typed, e.g. by meaning): it then starts at once instead of waiting for the translation.
     */
    val usesTranslation: Boolean = true,
    /**
     * True when the source restricts its own results to [SourceQuery.publishedAfter] (by creation date); only
     * then is the window part of the cache key. Other sources return relevant hits and the search filters them.
     */
    val filtersByDate: Boolean = false,
    /** True when the source can list resources without any subject words ([SourceQuery.browse]); others are skipped. */
    val browses: Boolean = false,
)

enum class PopularityKind { GITHUB_STARS, HF_LIKES, HF_DOWNLOADS, NPM_DOWNLOADS_WEEKLY }

/** A real metric reported by one named source. Never combined across sources. */
data class PopularityMetric(val kind: PopularityKind, val value: Long, val sourceId: String, val asOf: Instant)

/** One normalized result from one source. Everything a card shows must be traceable to hits. */
data class SourceHit @JvmOverloads constructor(
    val sourceId: String,
    val externalId: String,
    val url: URI,
    val canonicalUrl: CanonicalUrl,
    /** e.g. the GitHub repository of an npm package; joins the repository's own hit when no other resource links to it. */
    val repositoryUrl: CanonicalUrl? = null,
    val title: String,
    val description: String? = null,
    val descriptionOrigin: ValueOrigin = ValueOrigin.SOURCE_PROVIDED,
    val type: ResourceType,
    val audience: Audience = Audience.UNKNOWN,
    val techLevel: TechLevel? = null,
    val useCases: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val license: String? = null,
    val pricing: Pricing? = null,
    val platforms: Set<String> = emptySet(),
    val openSource: Boolean? = null,
    val published: DateFact = DateFact.unknown(),
    val updated: DateFact = DateFact.unknown(),
    val popularity: List<PopularityMetric> = emptyList(),
    val retrievedAt: Instant,
    /** 0-based position in the source's own ranking for this query. */
    val rank: Int = 0,
    /** EVENT only: when it takes place, as the source states it (unknown otherwise; never inferred). */
    val eventStart: DateFact = DateFact.unknown(),
    val eventEnd: DateFact = DateFact.unknown(),
    /** EVENT only: city/country as the source states it. */
    val location: String? = null,
    /** EVENT only: kind and format as the source states them (null when it does not say; never inferred). */
    val eventKind: EventKind? = null,
    val eventFormat: EventFormat? = null,
)

/**
 * [requiredVariants] and [synonyms] are for the Helper2 index (see [AnalyzedQuery]); live sources
 * send [text] to their own search engines and ignore them.
 */
data class SourceQuery @JvmOverloads constructor(
    val text: String,
    val expandedTerms: List<String>,
    val path: AudiencePath?,
    val limit: Int,
    val requiredVariants: List<String> = listOf(text),
    val synonyms: List<String> = emptyList(),
    /** ISO code of the query's language when detected and not English (catalog adds that stemmer); else null. */
    val language: String? = null,
    /** Natural-language query for web search engines when the guide planned one; else null. */
    val webQuery: String? = null,
    /** Only resources created on or after this day are wanted; sources with [SourceDescriptor.filtersByDate] apply it. */
    val publishedAfter: LocalDate? = null,
    /**
     * No subject words: list resources by [publishedAfter] and [types] alone, most popular first. Only sources with
     * [SourceDescriptor.browses] are called.
     */
    val browse: Boolean = false,
    /** The types the search keeps; a browsing source may use them to fill its limit with useful rows. Empty = any. */
    val types: Set<ResourceType> = emptySet(),
)

/** Raised by source adapters; anything else thrown is treated as FAILED. */
sealed class SourceFailure(message: String) : RuntimeException(message) {
    class RateLimited(val retryAfter: Duration?) : SourceFailure("rate limited")

    /** [transient]: the same call may succeed soon (503, network error or timeout), so a background caller may retry it. */
    class Unavailable @JvmOverloads constructor(message: String, val transient: Boolean = false) : SourceFailure(message)
}

enum class SourceOutcome { SUCCEEDED, FAILED, TIMED_OUT, RATE_LIMITED, SKIPPED }

/**
 * Why a source was SKIPPED in a search: turned off, nothing to browse, paused after a rate limit, its circuit open
 * after repeated failures, or already running as many calls as its bulkhead allows.
 */
enum class SkipReason { DISABLED, NOT_BROWSABLE, RATE_LIMIT_PAUSE, CIRCUIT_OPEN, BUSY }

enum class ServedFrom { LIVE, CACHE, STALE_CACHE, NONE }

data class SourceStatus(
    val source: SourceDescriptor,
    val outcome: SourceOutcome,
    val servedFrom: ServedFrom,
    val resultCount: Int,
    val latency: Duration?,
    val checkedAt: Instant,
    val cachedAt: Instant? = null,
    val message: String? = null,
    /** SKIPPED only. */
    val skipReason: SkipReason? = null,
)

enum class Freshness { LIVE, CACHED, STALE, INDEXED, CURATED, DEMO }

data class FieldConflict(val field: String, val values: List<SourcedValue>)

data class SourceRef(val sourceId: String, val sourceName: String, val url: URI, val mode: SourceMode, val retrievedAt: Instant)

/** A resource after merging every hit that refers to it. */
data class MergedResult(
    val id: ResourceId,
    val canonicalUrl: CanonicalUrl,
    val url: URI,
    val title: String,
    val description: String?,
    val descriptionOrigin: ValueOrigin,
    val type: ResourceType,
    val audience: Audience,
    val techLevel: TechLevel?,
    val useCases: Set<String>,
    val tags: Set<String>,
    /** null when unknown OR when sources conflict (see [conflicts]). */
    val license: String?,
    val pricing: Pricing?,
    val platforms: Set<String>,
    val openSource: Boolean?,
    val published: DateFact,
    val updated: DateFact,
    val discovered: DateFact,
    val popularity: List<PopularityMetric>,
    val sources: List<SourceRef>,
    val freshness: Freshness,
    val conflicts: List<FieldConflict>,
    val hits: List<SourceHit>,
    val score: Double = 0.0,
    val eventStart: DateFact = DateFact.unknown(),
    val eventEnd: DateFact = DateFact.unknown(),
    val location: String? = null,
    val eventKind: EventKind? = null,
    val eventFormat: EventFormat? = null,
) {
    /** Every license value any source asserted; used so a conflicting resource still matches a filter. */
    val assertedLicenses: Set<String> get() = hits.mapNotNull { it.license }.toSet()

    fun withDiscovered(discovered: DateFact): MergedResult = copy(discovered = discovered)

    /** The same result under the identity the catalog stores it as (its records may merge to another URL). */
    fun withIdentity(id: ResourceId, canonicalUrl: CanonicalUrl): MergedResult = copy(id = id, canonicalUrl = canonicalUrl)
}
