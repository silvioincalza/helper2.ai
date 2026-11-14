package ai.helper2.scout.usecase

import ai.helper2.catalog.CatalogLimits
import ai.helper2.catalog.ScoutPolicy
import ai.helper2.scout.Candidate
import ai.helper2.scout.CandidateSignals
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.Collector
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceHit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
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
)

object CandidateRules {
  const val MAX_TITLE = CatalogLimits.TITLE_CHARS

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
    )

  @JvmStatic
  fun tagsOk(tags: Set<String>): Boolean =
    tags.size <= Candidate.MAX_TAGS && tags.all { it.length <= 40 && TAG.matches(it) }
}

class InvalidCandidate(message: String) : IllegalArgumentException(message)

data class ListedResource(val id: UUID, val title: String)

sealed interface AcceptOutcome {
  data class Indexed(val id: UUID) : AcceptOutcome

  data class AlreadyListed(val resource: ListedResource) : AcceptOutcome
}

fun interface CandidateDetails {
  fun read(details: String?): AcceptedCandidate
}

fun interface CatalogLookup {
  fun listedAs(url: CanonicalUrl): ListedResource?
}

class IndexAcceptedCandidate(
  collectors: List<Collector>,
  private val catalog: CatalogIndex,
  private val lookup: CatalogLookup,
  private val details: CandidateDetails,
  private val clock: Clock,
) {
  private val sources = collectors.associateBy { it.descriptor.id }
  private val merger = ResultMerger()

  private companion object {
    const val MAX_SNIPPET = ScoutPolicy.PRODUCT_PAGE_EXCERPT_CHARS
  }

  fun accept(details: String?): AcceptOutcome {
    val candidate = this.details.read(details)
    val url = hitOf(candidate, collectorOf(candidate)).canonicalUrl
    val listed = lookup.listedAs(url) ?: return AcceptOutcome.Indexed(index(candidate))
    return AcceptOutcome.AlreadyListed(listed)
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
