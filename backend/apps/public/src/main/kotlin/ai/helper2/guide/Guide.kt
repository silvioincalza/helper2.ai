package ai.helper2.guide

import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.SearchOutcome
import ai.helper2.search.WebSearchesLeft
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.LinkText.containsLink
import ai.helper2.shared.ResourceType
import java.time.Instant

data class Candidate(val key: String, val result: MergedResult) {
  val text: String
    get() = listOfNotNull(result.title, result.ownDescription).joinToString("\n")
}

val MergedResult.ownDescription: String?
  get() = description.takeUnless { descriptionOrigin.isAiWritten }

data class GuideSearchPlan
@JvmOverloads
constructor(
  val searchQuery: String,
  val constraints: List<String> = emptyList(),
  val wantsReading: Boolean = false,
  val webQuery: String = "",
  val intent: GuideIntent? = null,
  val subjects: List<String> = emptyList(),
  val window: PlannedWindow? = null,
  val clarify: Clarification? = null,
) {
  val plannedQuery: String?
    get() = PlannedQueries.searchQueryOf(searchQuery).ifBlank { null }
}

val READING_TYPES =
  setOf(ResourceType.ARTICLE, ResourceType.PAPER, ResourceType.NEWS, ResourceType.EVENT)

val LEARN_TYPES: Set<ResourceType> = setOf(ResourceType.COURSE)

val TOOL_TYPES: Set<ResourceType> = ResourceType.entries.toSet() - READING_TYPES - LEARN_TYPES

private val READING_WORDS =
  Regex(
    "(?i)\\b(papers?|articles?|news|research|stud(y|ies)|blogs?|posts?|events?|conferences?|reading|read about|" +
      "articol[oi]|notizi[ae]|ricerc[ah]e?|studi[oi]?|eventi?)\\b"
  )

private val QUALIFIERS =
  Regex(
    "(?i)(?<![\\w-])(open[- ]?source|oss|free( of charge)?|for free|gratis|gratuit[oa]|self[- ]?host(ed|able)?|" +
      "local(ly)?|offline|on[- ]prem(ise)?|mit|apache( 2(\\.0)?)?|bsd|gpl|licen[cs]ed?|licen[cs]e|permissive|cheap|low[- ]cost)(?![\\w-])"
  )

fun withoutQualifiers(query: String): String =
  QUALIFIERS.replace(query, " ").replace(Regex("\\s+"), " ").trim().ifBlank { query.trim() }

fun wantsReading(request: String, plan: GuideSearchPlan?): Boolean =
  plan?.wantsReading == true || READING_WORDS.containsMatchIn(request)

object PlannedQueries {
  const val MAX_QUERY_WORDS = 6

  const val MAX_WEB_QUERY_WORDS = 12

  @JvmStatic
  fun searchQueryOf(planned: String): String =
    withoutQualifiers(planned)
      .split(' ')
      .filter { it.isNotBlank() }
      .take(MAX_QUERY_WORDS)
      .joinToString(" ")
      .take(200)
      .takeUnless { containsLink(it) }
      .orEmpty()

  @JvmStatic
  fun webQueryOf(planned: String): String? =
    planned
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() }
      .take(MAX_WEB_QUERY_WORDS)
      .joinToString(" ")
      .take(200)
      .takeUnless { it.isBlank() || containsLink(it) }
}

data class ConstraintQuote(val constraint: String, val quote: String)

data class ProposedPick
@JvmOverloads
constructor(
  val candidateKey: String,
  val reason: String,
  val quote: String,
  val evidence: List<ConstraintQuote> = emptyList(),
)

data class GuideProposal(val picks: List<ProposedPick>)

open class GuideUnavailable
@JvmOverloads
constructor(message: String, val category: String = "error") : RuntimeException(message) {
  val outOfTime: Boolean
    get() = category == CANCELLED || category == DEADLINE

  companion object {
    const val CANCELLED = "cancelled"
    const val DEADLINE = "deadline"
  }
}

class GuideBudgetExhausted
@JvmOverloads
constructor(val opensMoreToday: Boolean = false, val opensAgainAt: Instant? = null) :
  GuideUnavailable(
    if (opensMoreToday) "model-call budget of this part of the day used up"
    else "daily model-call budget used up",
    "budget",
  )

class GuideBusy : GuideUnavailable("every guide slot is taken", "busy")

interface WorkGate {
  fun enter(): Boolean

  fun leave()

  companion object {
    @JvmField
    val OPEN: WorkGate =
      object : WorkGate {
        override fun enter() = true

        override fun leave() {}
      }
  }
}

data class ReasonClaim(
  val key: String,
  val reason: String,
  val quote: String,
  val text: String,
  val confirmed: List<ConstraintCheck>,
)

data class Need @JvmOverloads constructor(val request: String, val answer: String? = null)

data class ReasonVerdict
@JvmOverloads
constructor(
  val claimsSupported: Boolean,
  val fitsNeed: Boolean = true,
)

enum class ReasonCheck {
  SUPPORTED,
  REMOVED,
  NOT_CHECKED,
}

interface GuideModel {
  val provider: String
  val fastModel: String
  val reasoningModel: String

  val configFingerprint: String
    get() = ""

  fun plan(request: String, path: AudiencePath?): GuideSearchPlan

  fun choose(request: Need, candidates: List<Candidate>, constraints: List<String>): GuideProposal

  fun choose(
    request: Need,
    candidates: List<Candidate>,
    constraints: List<String>,
    think: Boolean,
  ): GuideProposal = choose(request, candidates, constraints)

  fun verifyReasons(request: Need, claims: List<ReasonClaim>): Map<String, ReasonVerdict> =
    emptyMap()

  fun plan(request: String, path: AudiencePath?, clarification: String?): GuideSearchPlan =
    plan(request, path)

  fun compare(request: Need, subjects: List<Candidate>): ComparisonProposal =
    ComparisonProposal(emptyList())

  fun explain(request: Need, subject: String, candidates: List<Candidate>): ExplanationProposal =
    ExplanationProposal(emptyList())
}

data class ProposedCell(val subjectKey: String, val text: String, val quote: String)

data class ProposedRow(val aspect: String, val cells: List<ProposedCell>)

data class ComparisonProposal(val rows: List<ProposedRow>)

data class ComparisonCell(
  val subjectKey: String,
  val text: String?,
  val quote: String?,
  val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED,
)

data class ComparisonRow(val aspect: String, val cells: List<ComparisonCell>)

data class ComparedSubject(val key: String, val name: String, val result: MergedResult?)

data class Comparison(
  val subjects: List<ComparedSubject>,
  val rows: List<ComparisonRow>,
  val cellsNotChecked: Int = 0,
)

data class ProposedSentence(val text: String, val candidateKey: String, val quote: String)

data class ExplanationProposal(val sentences: List<ProposedSentence>)

data class ExplainedSentence(
  val text: String,
  val quote: String,
  val source: MergedResult,
  val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED,
)

data class Explanation(val sentences: List<ExplainedSentence>)

enum class GuideStatus {
  OK,
  PARTIAL,
  LLM_UNAVAILABLE,
  NO_CANDIDATES,
  NEEDS_CLARIFICATION,
}

enum class CheckStatus {
  CONFIRMED,
  NOT_CONFIRMED,
}

data class ConstraintCheck(val constraint: String, val status: CheckStatus, val quote: String?)

data class Recommendation
@JvmOverloads
constructor(
  val result: MergedResult,
  val reason: String,
  val quote: String,
  val aiReasoned: Boolean,
  val checks: List<ConstraintCheck> = emptyList(),
  val reasonCheck: ReasonCheck = ReasonCheck.NOT_CHECKED,
)

enum class TrailCheck {
  QUOTE_NOT_FOUND,
  REASON_NOT_SUPPORTED,
  CONSTRAINT_NOT_MET,
  OFF_NEED,
}

data class RemovedSuggestion(val title: String, val check: TrailCheck)

data class ResearchTrail(
  val queries: List<String> = emptyList(),
  val removed: List<RemovedSuggestion> = emptyList(),
) {
  fun withoutQueries() = copy(queries = emptyList())

  companion object {
    const val MAX_QUERIES = 3
    const val MAX_QUERY_CHARS = 200
    const val MAX_REMOVED = 12
    const val MAX_TITLE_CHARS = 200

    @JvmField val EMPTY = ResearchTrail()

    @JvmStatic
    fun of(queries: List<String>, removed: List<RemovedSuggestion>) =
      ResearchTrail(
        queries
          .asSequence()
          .map { it.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_CHARS) }
          .filter { it.isNotEmpty() && !containsLink(it) }
          .distinctBy { it.lowercase() }
          .take(MAX_QUERIES)
          .toList(),
        removed
          .asSequence()
          .map { it.copy(title = it.title.trim().take(MAX_TITLE_CHARS)) }
          .filter { it.title.isNotEmpty() }
          .take(MAX_REMOVED)
          .toList(),
      )
  }
}

data class GuideAnswer(
  val status: GuideStatus,
  val searchQuery: String,
  val recommendations: List<Recommendation>,
  val modelUsed: Boolean,
  val candidatesConsidered: Int,
  val rejected: Int,
  val search: SearchOutcome,
  val generatedAt: Instant,
  val constraints: List<String> = emptyList(),
  val notices: List<Notice> = emptyList(),
  val intent: GuideIntent = GuideIntent.SUGGEST,
  val subjects: List<String> = emptyList(),
  val window: DateWindow? = null,
  val clarification: Clarification? = null,
  val comparison: Comparison? = null,
  val explanation: Explanation? = null,
  val webFreshUntil: Instant? = null,
  val webSearches: WebSearchesLeft? = null,
  val planned: Boolean = false,
  val trail: ResearchTrail = ResearchTrail.EMPTY,
)

class GuideValidator(
  private val maxPicks: Int = MAX_PICKS,
  private val minQuoteChars: Int = MIN_QUOTE_CHARS,
  private val minQuoteWords: Int = MIN_QUOTE_WORDS,
  private val maxQuote: Int = MAX_QUOTE_CHARS,
) {

  val evidenceGate = EvidenceGate(minQuoteChars, minQuoteWords, maxQuote)

  data class Result(
    val picks: List<KeptPick>,
    val rejected: Int,
    val checks: Map<String, List<ConstraintCheck>> = emptyMap(),
    val rejectedBy: Map<Rejection, Int> = emptyMap(),
    val rejectedPicks: List<RejectedPick> = emptyList(),
  )

  data class RejectedPick(val candidate: Candidate, val rejection: Rejection)

  data class KeptPick(val candidate: Candidate, val pick: ProposedPick)

  val fingerprint: String
    get() =
      "$maxPicks/$minQuoteChars/$minQuoteWords/$maxQuote/$MAX_REASON_CHARS/$MIN_CHECK_QUOTE/words/secrets"

  enum class Rejection {
    UNKNOWN_CANDIDATE,
    DUPLICATE,
    READING_TYPE,
    NO_REASON,
    REASON_TOO_LONG,
    REASON_INVALID,
    QUOTE_TOO_LONG,
    QUOTE_NOT_IN_TEXT,
    QUOTE_TOO_SHORT,
    OUTSIDE_WINDOW,
    OFF_NEED,
    OVER_LIMIT;

    val trailCheck: TrailCheck?
      get() =
        when (this) {
          QUOTE_NOT_IN_TEXT,
          QUOTE_TOO_SHORT,
          QUOTE_TOO_LONG -> TrailCheck.QUOTE_NOT_FOUND
          NO_REASON,
          REASON_TOO_LONG,
          REASON_INVALID -> TrailCheck.REASON_NOT_SUPPORTED
          READING_TYPE,
          OUTSIDE_WINDOW -> TrailCheck.CONSTRAINT_NOT_MET
          OFF_NEED -> TrailCheck.OFF_NEED
          UNKNOWN_CANDIDATE,
          DUPLICATE,
          OVER_LIMIT -> null
        }
  }

  fun removedOf(result: Result): List<RemovedSuggestion> {
    val kept = result.picks.map { it.candidate.key }.toSet()
    return result.rejectedPicks
      .filter { it.candidate.key !in kept }
      .mapNotNull { (c, r) -> r.trailCheck?.let { RemovedSuggestion(c.result.title, it) } }
  }

  fun validate(
    proposal: GuideProposal,
    candidates: List<Candidate>,
    constraints: List<String> = emptyList(),
    allowReading: Boolean = true,
  ): Result {
    val byKey = candidates.associateBy { it.key }
    val rejectedBy = mutableMapOf<Rejection, Int>()
    val rejectedPicks = mutableListOf<RejectedPick>()
    fun reject(r: Rejection, c: Candidate? = null) {
      rejectedBy.merge(r, 1, Int::plus)
      if (c != null) rejectedPicks += RejectedPick(c, r)
    }
    val picks = mutableListOf<KeptPick>()
    for (p in proposal.picks) {
      val c = byKey[p.candidateKey.trim()]
      val problem =
        when {
          c == null -> Rejection.UNKNOWN_CANDIDATE
          picks.any { it.candidate.key == c.key } -> Rejection.DUPLICATE
          !allowReading && c.result.type in READING_TYPES -> Rejection.READING_TYPE
          else -> evidenceGate.claimProblem(p.reason, p.quote, c, MAX_REASON_CHARS)
        }
      when {
        problem != null -> reject(problem, c)
        picks.size >= maxPicks -> reject(Rejection.OVER_LIMIT)
        else -> picks += KeptPick(c!!, p.copy(reason = p.reason.trim(), quote = p.quote.trim()))
      }
    }
    val checks = picks.associate { (c, p) -> c.key to checkConstraints(constraints, c, p.evidence) }
    return Result(picks, rejectedBy.values.sum(), checks, rejectedBy, rejectedPicks)
  }

  fun checkConstraints(
    constraints: List<String>,
    candidate: Candidate,
    evidence: List<ConstraintQuote>,
  ): List<ConstraintCheck> {
    val text = evidenceGate.normalize(candidate.text)
    return normalizedConstraints(constraints).map { constraint ->
      licenceCheck(constraint, candidate.result)?.let {
        return@map it
      }
      val quote =
        evidence
          .asSequence()
          .filter { evidenceGate.normalize(it.constraint) == evidenceGate.normalize(constraint) }
          .map { it.quote.trim() }
          .firstOrNull { q ->
            q.length in MIN_CHECK_QUOTE..maxQuote &&
              evidenceGate.containsWords(text, evidenceGate.normalize(q))
          }
      if (quote != null) ConstraintCheck(constraint, CheckStatus.CONFIRMED, quote)
      else ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
    }
  }

  fun namesUnstatedSecret(claim: ReasonClaim): Boolean =
    SecretWords.onlyInReason(claim.reason, evidenceOf(claim))

  private fun evidenceOf(claim: ReasonClaim) =
    (listOf(claim.text, claim.quote) + claim.confirmed.flatMap { listOf(it.constraint, it.quote) })
      .filterNotNull()
      .joinToString("\n")

  private fun licenceCheck(constraint: String, result: MergedResult): ConstraintCheck? {
    val c = constraint.lowercase()
    val named = LICENCE_NAMES.filter { word(it).containsMatchIn(c) }
    val asksOpenSource = OPEN_SOURCE.containsMatchIn(c)
    if (named.isEmpty() && !asksOpenSource) return null
    val licence = result.license
    if (licence == null) {
      return when {
        named.isEmpty() && result.openSource == false ->
          ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
        else -> null
      }
    }
    val ok =
      if (named.isNotEmpty()) named.any { word(it).containsMatchIn(licence.lowercase()) }
      else result.openSource == true
    if (!ok) return ConstraintCheck(constraint, CheckStatus.NOT_CONFIRMED, null)
    val stated =
      "License: $licence (stated by ${result.sources.joinToString { s -> s.sourceName }})"
    return ConstraintCheck(constraint, CheckStatus.CONFIRMED, stated)
  }

  private fun word(name: String) = Regex("(?<![a-z0-9])${Regex.escape(name)}(?![a-z0-9])")

  companion object {
    const val MAX_PICKS = 3
    const val MIN_QUOTE_CHARS = 40
    const val MIN_QUOTE_WORDS = 6
    const val MAX_QUOTE_CHARS = 300
    const val MAX_REASON_CHARS = 400

    private val OPEN_SOURCE = Regex("open[- ]?source|\\boss\\b|\\bosi\\b")
    private val LICENCE_NAMES =
      listOf("mit", "apache", "bsd", "gpl", "lgpl", "agpl", "mpl", "isc", "cc0", "unlicense")

    const val MIN_CHECK_QUOTE = 5
    const val MAX_CONSTRAINTS = 4
    private const val MAX_CONSTRAINT_CHARS = 60

    fun normalizedConstraints(raw: List<String>): List<String> =
      raw
        .asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() && it.length <= MAX_CONSTRAINT_CHARS && !containsLink(it) }
        .distinctBy { it.lowercase() }
        .take(MAX_CONSTRAINTS)
        .toList()
  }
}

enum class ModelStep {
  PLAN,
  CHOOSE,
  COMPARE,
  EXPLAIN,
  VERIFY;

  val label: String = name.lowercase()
}

interface GuideProgress {
  fun planned(searchQuery: String, constraints: List<String>) {}

  fun understood(intent: GuideIntent, subjects: List<String>, window: DateWindow?) {}

  fun clarify(clarification: Clarification) {}

  fun retrieved(candidates: Int, search: SearchOutcome) {}

  fun chosen(proposed: Int) {}

  fun validated(kept: Int, rejected: Int) {}

  fun reasonsChecked(supported: Int, removed: Int, notChecked: Int) {}

  fun reasoning(step: ModelStep) {}

  fun writing(step: ModelStep) {}

  companion object {
    @JvmField val NONE: GuideProgress = object : GuideProgress {}
  }
}
