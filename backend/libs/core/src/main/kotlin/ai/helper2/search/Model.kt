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

  fun withTerms(termsUrl: URI): SourceDescriptor = copy(termsUrl = termsUrl)

  fun withAttribution(attribution: String): SourceDescriptor = copy(attribution = attribution)

  fun enabledWhen(condition: Boolean): SourceDescriptor = copy(enabled = condition)

  fun withPriority(priority: Int): SourceDescriptor = copy(priority = priority)

  fun askedWithTheWebQuery(): SourceDescriptor = copy(usesWebQuery = true)

  fun askedWithoutTranslation(): SourceDescriptor = copy(usesTranslation = false)

  fun filteringByDate(): SourceDescriptor = copy(filtersByDate = true)

  fun browsing(): SourceDescriptor = copy(browses = true)

  fun metered(): SourceDescriptor = copy(metered = true)

  fun inFamily(family: String): SourceDescriptor = copy(family = family)

  fun confirmingUrlsUnder(prefix: String): SourceDescriptor = copy(confirmsUrlPrefix = prefix)

  companion object {
    const val SHORT_NAME_MAX = 12

    @JvmStatic
    fun external(
      id: String,
      name: String,
      shortName: String,
      mode: SourceMode,
      homepage: URI,
      resourceTypes: Set<ResourceType>,
      timeout: Duration,
      cacheTtl: Duration,
    ): SourceDescriptor =
      SourceDescriptor(
        id = id,
        name = name,
        shortName = shortName,
        external = true,
        mode = mode,
        homepage = homepage,
        termsUrl = null,
        attribution = null,
        resourceTypes = resourceTypes,
        timeout = timeout,
        cacheTtl = cacheTtl,
      )

    @JvmStatic
    fun inHouse(
      id: String,
      name: String,
      shortName: String,
      mode: SourceMode,
      homepage: URI,
      resourceTypes: Set<ResourceType>,
      timeout: Duration,
      cacheTtl: Duration,
    ): SourceDescriptor =
      external(id, name, shortName, mode, homepage, resourceTypes, timeout, cacheTtl)
        .copy(external = false)
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
  fun withRepository(repositoryUrl: CanonicalUrl?): SourceHit = copy(repositoryUrl = repositoryUrl)

  fun withDescription(description: String?): SourceHit = copy(description = description)

  fun withDescription(description: String?, origin: ValueOrigin): SourceHit =
    copy(description = description, descriptionOrigin = origin)

  fun withAudience(audience: Audience): SourceHit = copy(audience = audience)

  fun withTechLevel(techLevel: TechLevel?): SourceHit = copy(techLevel = techLevel)

  fun withUseCases(useCases: Set<String>): SourceHit = copy(useCases = useCases)

  fun withTags(tags: Set<String>): SourceHit = copy(tags = tags)

  fun withLicense(license: String?, openSource: Boolean?): SourceHit =
    copy(license = license, openSource = openSource)

  fun withPricing(pricing: Pricing?): SourceHit = copy(pricing = pricing)

  fun withPlatforms(platforms: Set<String>): SourceHit = copy(platforms = platforms)

  fun withPublished(published: DateFact): SourceHit = copy(published = published)

  fun withUpdated(updated: DateFact): SourceHit = copy(updated = updated)

  fun withPopularity(popularity: List<PopularityMetric>): SourceHit = copy(popularity = popularity)

  fun withRank(rank: Int): SourceHit = copy(rank = rank)

  fun withEventDays(first: DateFact, last: DateFact): SourceHit =
    copy(eventStart = first, eventEnd = last)

  fun withLocation(location: String?): SourceHit = copy(location = location)

  fun withEventKind(eventKind: EventKind?): SourceHit = copy(eventKind = eventKind)

  fun withEventFormat(eventFormat: EventFormat?): SourceHit = copy(eventFormat = eventFormat)

  fun withFacts(facts: Map<String, Any>): SourceHit = copy(facts = facts)

  fun withCategories(categories: Set<String>): SourceHit = copy(categories = categories)

  fun withFoundIn(foundIn: ListMatch?): SourceHit = copy(foundIn = foundIn)

  fun withCuratedFields(curatedFields: Set<String>): SourceHit = copy(curatedFields = curatedFields)

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
