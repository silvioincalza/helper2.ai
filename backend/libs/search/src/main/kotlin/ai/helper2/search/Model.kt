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

data class SourceDescriptor
@JvmOverloads
constructor(
  val id: String,
  val name: String,
  val shortName: String,
  val external: Boolean,
  val mode: SourceMode,
  val homepage: URI,
  val termsUrl: URI?,
  val attribution: String?,
  val resourceTypes: Set<ResourceType>,
  val timeout: Duration,
  val cacheTtl: Duration,
  val enabled: Boolean = true,
  val priority: Int = 100,
  val usesWebQuery: Boolean = false,
  val usesTranslation: Boolean = true,
  val filtersByDate: Boolean = false,
  val browses: Boolean = false,
  val metered: Boolean = false,
  val family: String? = null,
  val confirmsUrlPrefix: String? = null,
) {
  init {
    require(shortName.isNotBlank() && shortName.length <= SHORT_NAME_MAX) {
      "Source $id needs a short name of 1 to $SHORT_NAME_MAX characters, got '$shortName'"
    }
  }

  fun withMode(mode: SourceMode): SourceDescriptor =
    if (this.mode == mode) this else copy(mode = mode)

  companion object {
    const val SHORT_NAME_MAX = 12
  }
}

enum class PopularityKind {
  GITHUB_STARS,
  HF_LIKES,
  HF_DOWNLOADS,
  NPM_DOWNLOADS_WEEKLY,
}

data class PopularityMetric(
  val kind: PopularityKind,
  val value: Long,
  val sourceId: String,
  val asOf: Instant,
)

data class ListMatch(val listTitle: String, val headingPath: String)

data class SourceHit
@JvmOverloads
constructor(
  val sourceId: String,
  val externalId: String,
  val url: URI,
  val canonicalUrl: CanonicalUrl,
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
  val rank: Int = 0,
  val eventStart: DateFact = DateFact.unknown(),
  val eventEnd: DateFact = DateFact.unknown(),
  val location: String? = null,
  val eventKind: EventKind? = null,
  val eventFormat: EventFormat? = null,
  val facts: Map<String, Any> = emptyMap(),
  val categories: Set<String> = emptySet(),
  val foundIn: ListMatch? = null,
  val curatedFields: Set<String> = emptySet(),
  val vitality: Vitality = Vitality.UNKNOWN,
) {
  fun withRank(rank: Int): SourceHit = copy(rank = rank)

  fun withFacts(facts: Map<String, Any>): SourceHit = copy(facts = facts)

  fun withVitality(vitality: Vitality): SourceHit = copy(vitality = vitality)
}

data class SourceQuery
@JvmOverloads
constructor(
  val text: String,
  val expandedTerms: List<String>,
  val path: AudiencePath?,
  val limit: Int,
  val requiredVariants: List<String> = listOf(text),
  val synonyms: List<String> = emptyList(),
  val language: String? = null,
  val webQuery: String? = null,
  val publishedAfter: LocalDate? = null,
  val browse: Boolean = false,
  val types: Set<ResourceType> = emptySet(),
  val searchTerms: List<String> = emptyList(),
  val translation: String? = null,
) {
  val keywords: String
    get() =
      if (searchTerms.isNotEmpty()) searchTerms.joinToString(" ")
      else (listOf(text) + expandedTerms).joinToString(" ").trim()
}

sealed class SourceFailure(message: String) : RuntimeException(message) {
  class RateLimited(val retryAfter: Duration?) : SourceFailure("rate limited")

  class Unavailable @JvmOverloads constructor(message: String, val transient: Boolean = false) :
    SourceFailure(message)
}

enum class SourceOutcome {
  SUCCEEDED,
  FAILED,
  TIMED_OUT,
  RATE_LIMITED,
  SKIPPED,
}

enum class SkipReason {
  DISABLED,
  NOT_BROWSABLE,
  RATE_LIMIT_PAUSE,
  CIRCUIT_OPEN,
  BUSY,
  DAILY_LIMIT,
  QUOTA_UNAVAILABLE,
  WEB_NOT_ASKED,
}

enum class ServedFrom {
  LIVE,
  CACHE,
  STALE_CACHE,
  NONE,
}

data class SourceStatus(
  val source: SourceDescriptor,
  val outcome: SourceOutcome,
  val servedFrom: ServedFrom,
  val resultCount: Int,
  val latency: Duration?,
  val checkedAt: Instant,
  val cachedAt: Instant? = null,
  val message: String? = null,
  val skipReason: SkipReason? = null,
)

enum class Freshness {
  LIVE,
  CACHED,
  STALE,
  INDEXED,
  CURATED,
  DEMO,
}

data class FieldConflict(val field: String, val values: List<SourcedValue>)

data class SourceRef(
  val sourceId: String,
  val sourceName: String,
  val url: URI,
  val mode: SourceMode,
  val retrievedAt: Instant,
)

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
  val categories: Set<String> = emptySet(),
) {
  val assertedLicenses: Set<String>
    get() = hits.mapNotNull { it.license }.toSet()

  val foundIn: ListMatch?
    get() {
      val found = hits.map { it.foundIn }
      return if (found.isNotEmpty() && found.all { it != null }) found.first() else null
    }

  fun withDiscovered(discovered: DateFact): MergedResult = copy(discovered = discovered)

  fun withCategories(categories: Collection<String>): MergedResult =
    copy(categories = LinkedHashSet(categories))

  fun withIdentity(id: ResourceId, canonicalUrl: CanonicalUrl): MergedResult =
    copy(id = id, canonicalUrl = canonicalUrl)

  fun withCurated(
    fields: Set<String>,
    type: ResourceType,
    audience: Audience,
    techLevel: TechLevel?,
    license: String?,
    pricing: Pricing?,
    openSource: Boolean?,
  ): MergedResult {
    if (fields.isEmpty()) return this
    return copy(
      type = if ("type" in fields) type else this.type,
      audience = if ("audience" in fields) audience else this.audience,
      techLevel = if ("techLevel" in fields) techLevel else this.techLevel,
      license = if ("license" in fields) license else this.license,
      pricing = if ("pricing" in fields) pricing else this.pricing,
      openSource = if ("openSource" in fields) openSource else this.openSource,
      conflicts = conflicts.filterNot { it.field in fields },
    )
  }

  fun withReconciledFacts(
    type: ResourceType,
    license: String?,
    pricing: Pricing?,
    openSource: Boolean?,
    theirConflicts: List<FieldConflict>,
  ): MergedResult =
    copy(
      type = type,
      license = license,
      pricing = pricing,
      openSource = openSource,
      conflicts =
        conflicts.filterNot { it.field in RECONCILED_FACTS } +
          theirConflicts.filter { it.field in RECONCILED_FACTS },
    )

  fun withReconciledFactsOf(counted: MergedResult): MergedResult =
    withReconciledFacts(
      counted.type,
      counted.license,
      counted.pricing,
      counted.openSource,
      counted.conflicts,
    )

  companion object {
    private val RECONCILED_FACTS = setOf("license", "pricing", "openSource")
  }
}
