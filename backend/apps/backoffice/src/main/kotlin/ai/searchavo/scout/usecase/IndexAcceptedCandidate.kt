package ai.searchavo.scout.usecase

import ai.searchavo.catalog.CatalogLimits
import ai.searchavo.catalog.ScoutPolicy
import ai.searchavo.scout.Candidate
import ai.searchavo.scout.CandidateSignals
import ai.searchavo.scout.CatalogIndex
import ai.searchavo.scout.Collector
import ai.searchavo.scout.EvidenceValidator
import ai.searchavo.search.ResultMerger
import ai.searchavo.search.ServedFrom
import ai.searchavo.search.SourceContext
import ai.searchavo.search.SourceHit
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.ResourceType
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

data class AcceptedCandidate
@JvmOverloads
constructor(
  val sourceId: String,
  val url: String,
  val title: String,
  val snippet: String?,
  val type: ResourceType,
  val tags: Set<String> = emptySet(),
  val facts: Map<String, Any> = emptyMap(),
  val finders: List<String> = emptyList(),
  val typeChosenByAdmin: Boolean = false,
  val signals: CandidateSignals = CandidateSignals.UNKNOWN,
  val confirmed: Map<String, String> = emptyMap(),
  val descriptionByAi: Boolean = false,
)

object CandidateRules {
  const val MAX_TITLE = CatalogLimits.TITLE_CHARS

  const val MAX_DESCRIPTION = 4000

  @JvmField
  val LANGUAGES =
    setOf(
      "simple",
      "ar",
      "hy",
      "eu",
      "ca",
      "da",
      "nl",
      "en",
      "fi",
      "fr",
      "de",
      "el",
      "hi",
      "hu",
      "id",
      "ga",
      "it",
      "lt",
      "no",
      "pt",
      "ro",
      "ru",
      "sr",
      "es",
      "sv",
      "ta",
      "tr",
    )

  private val values = EvidenceValidator()

  fun checkConfirmed(c: CandidateEdit, clock: java.time.Clock) {
    c.pricing?.let {
      require(values.allows("pricing", it)) { "The pricing $it is not one the catalog takes." }
    }
    c.license?.let {
      require(values.allows("license", it.trim())) {
        "The licence must be an SPDX id, such as MIT."
      }
    }
    c.audience?.let {
      require(values.allows("audience", it)) { "The audience $it is not one the catalog takes." }
    }
    c.techLevel?.let {
      require(values.allows("techLevel", it)) { "The tech level $it is not one the catalog takes." }
    }
    c.description?.trim()?.let { d ->
      require(
        d.isNotEmpty() &&
          d.length <= MAX_DESCRIPTION &&
          d.none { Character.isISOControl(it) && it != '\n' }
      ) {
        "The description must be 1 to $MAX_DESCRIPTION characters."
      }
    }
    c.language?.let {
      require(it in LANGUAGES) { "The language $it is not one the catalog takes." }
    }
    c.publishedAt?.let {
      require(
        !it.isAfter(java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).plusDays(1))
      ) {
        "The published date is in the future."
      }
    }
  }

  @JvmField val TAG = Regex("[a-z0-9]+(-[a-z0-9]+)*")

  @JvmField
  val TYPES =
    setOf(
      ResourceType.PRODUCT,
      ResourceType.AGENT,
      ResourceType.PROMPT,
      ResourceType.SKILL,
      ResourceType.MCP_SERVER,
      ResourceType.LIBRARY,
      ResourceType.MODEL,
      ResourceType.DATASET,
      ResourceType.ARTICLE,
      ResourceType.NEWS,
      ResourceType.EVENT,
      ResourceType.COURSE,
      ResourceType.AWESOME_LIST,
    )

  @JvmStatic
  fun tagsOk(tags: Set<String>): Boolean =
    tags.size <= Candidate.MAX_TAGS && tags.all { it.length <= 40 && TAG.matches(it) }
}

class InvalidCandidate(message: String) : IllegalArgumentException(message)

data class ListedResource(val id: UUID, val title: String)

sealed interface AcceptOutcome {
  data class Indexed(
    val id: UUID,
    val confirmed: Map<String, String> = emptyMap(),
    val descriptionByAi: Boolean = false,
  ) : AcceptOutcome

  data class AlreadyListed(val resource: ListedResource) : AcceptOutcome
}

fun interface CandidateDetails {
  fun read(details: String?): AcceptedCandidate
}

fun interface CatalogLookup {
  fun listedAs(url: CanonicalUrl): ListedResource?
}

/**
 * A list that proposed a candidate; [directoryListTitle] is set for a list page of a directory
 * host.
 */
data class NamingList(
  val url: CanonicalUrl,
  val headingPath: String,
  val directoryListTitle: String?,
)

/**
 * Records that a list names a resource, under the resource's [title] for the list's search words.
 */
fun interface NamingLists {
  fun name(resource: UUID, title: String, list: NamingList)
}

class IndexAcceptedCandidate(
  collectors: List<Collector>,
  private val catalog: CatalogIndex,
  private val lookup: CatalogLookup,
  private val details: CandidateDetails,
  private val namingLists: NamingLists,
  private val clock: Clock,
) {
  private val sources = collectors.associateBy { it.descriptor.id }
  private val merger = ResultMerger()

  private companion object {
    const val MAX_SNIPPET = ScoutPolicy.PRODUCT_PAGE_EXCERPT_CHARS
    const val FOUND_BY = "foundBy"
    const val LIST_FACT = "awesome"
    const val MAX_HEADING_PATH = 200
    const val MAX_LIST_TITLE = 300
  }

  fun accept(details: String?): AcceptOutcome {
    val candidate = this.details.read(details)
    val url = hitOf(candidate, collectorOf(candidate)).canonicalUrl
    val listed = lookup.listedAs(url)
    val resource = listed?.id ?: index(candidate)
    nameByItsLists(resource, candidate)
    return if (listed != null) AcceptOutcome.AlreadyListed(listed)
    else AcceptOutcome.Indexed(resource, candidate.confirmed, candidate.descriptionByAi)
  }

  // UC-030 BR-008: an accepted candidate is named by the lists that proposed it.
  private fun nameByItsLists(resource: UUID, candidate: AcceptedCandidate) {
    val finders = candidate.facts[FOUND_BY] as? List<*> ?: return
    finders
      .mapNotNull { (it as? Map<*, *>)?.get(LIST_FACT) as? Map<*, *> }
      .mapNotNull(::namingList)
      .distinctBy { it.url }
      .forEach { namingLists.name(resource, candidate.title.trim(), it) }
  }

  private fun namingList(fact: Map<*, *>): NamingList? {
    val url = (fact["list"] as? String)?.let(CanonicalUrl::of) ?: return null
    return NamingList(
      url,
      (fact["section"] as? String).orEmpty().take(MAX_HEADING_PATH),
      (fact["directoryList"] as? String)?.trim()?.take(MAX_LIST_TITLE)?.ifEmpty { null },
    )
  }

  fun index(candidate: AcceptedCandidate): UUID {
    val collector = collectorOf(candidate)
    val hit = hitOf(candidate, collector)
    val merged =
      merger.merge(
        listOf(hit),
        mapOf(hit.sourceId to SourceContext(collector.descriptor, ServedFrom.LIVE)),
      )
    return catalog.index(merged, hit.sourceId).firstOrNull()
      ?: throw InvalidCandidate("the candidate was not indexed")
  }

  private fun collectorOf(c: AcceptedCandidate): Collector =
    sources[c.sourceId] ?: throw InvalidCandidate("unknown source ${c.sourceId}")

  private fun declaredTypes(c: AcceptedCandidate, collector: Collector): Set<ResourceType> =
    collector.descriptor.resourceTypes +
      c.finders.mapNotNull { sources[it] }.flatMap { it.descriptor.resourceTypes }

  private fun hitOf(c: AcceptedCandidate, collector: Collector): SourceHit {
    val url = CanonicalUrl.of(c.url) ?: throw InvalidCandidate("unreadable URL")
    val title = c.title.trim()
    if (title.isEmpty() || title.length > CandidateRules.MAX_TITLE)
      throw InvalidCandidate("title missing or too long")
    if (c.type !in CandidateRules.TYPES)
      throw InvalidCandidate("type ${c.type} cannot be a candidate")
    if (!c.typeChosenByAdmin && c.type !in declaredTypes(c, collector)) {
      throw InvalidCandidate("no collector that found it proposes ${c.type}")
    }
    if (!CandidateRules.tagsOk(c.tags)) throw InvalidCandidate("unreadable tags")
    val retrievedAt = clock.instant()
    val registryPackage = c.facts[PackageFacts.KEY] as? Map<*, *>
    val record =
      SourceHit(
        sourceId = c.sourceId,
        externalId = url.value,
        url = URI.create(url.value),
        canonicalUrl = url,
        title = title,
        description = c.snippet?.trim()?.take(MAX_SNIPPET)?.ifEmpty { null },
        type = c.type,
        tags = c.tags,
        license = PackageFacts.license(registryPackage),
        updated =
          DateFact.fromSource(PackageFacts.latestRelease(registryPackage), c.sourceId, retrievedAt),
        retrievedAt = retrievedAt,
        facts = c.facts,
      )
    return c.signals.statedOn(record)
  }
}

object PackageFacts {
  const val KEY = "package"

  const val TERMS = "terms"

  private val SPDX = Regex("[A-Za-z0-9.+-]+( (AND|OR|WITH) [A-Za-z0-9.+-]+)*")

  private val NOT_A_LICENCE = setOf("non-standard", "noassertion", "none", "unknown", "other")

  @JvmStatic
  fun license(fact: Map<*, *>?): String? =
    (fact?.get("license") as? String)?.takeIf {
      it.length <= 100 &&
        SPDX.matches(it) &&
        it.split(' ').none { id -> id.lowercase() in NOT_A_LICENCE }
    }

  @JvmStatic
  fun latestRelease(fact: Map<*, *>?): Instant? =
    (fact?.get("latestRelease") as? String)?.let {
      try {
        Instant.parse(it)
      } catch (e: DateTimeParseException) {
        null
      }
    }
}
