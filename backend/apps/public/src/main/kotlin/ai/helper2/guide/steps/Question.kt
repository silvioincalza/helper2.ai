package ai.helper2.guide.steps

import ai.helper2.guide.AmbiguousSubject
import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.Clarification
import ai.helper2.guide.ConstraintCheck
import ai.helper2.guide.DateWindow
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.Need
import ai.helper2.guide.QuestionDeadline
import ai.helper2.guide.Recommendation
import ai.helper2.guide.SimilarQuestions
import ai.helper2.guide.ownDescription
import ai.helper2.search.Asker
import ai.helper2.search.IntentClass
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SourceOutcome
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class Question(
  val text: String,
  val path: AudiencePath?,
  val clarification: String?,
  val drops: Drops,
  val asker: Asker?,
  val categories: Set<String> = emptySet(),
) {
  val fullText: String
    get() = listOfNotNull(text, clarification?.takeIf { it.isNotBlank() }).joinToString(" ")

  val need: Need
    get() = Need(text, clarification?.takeIf { it.isNotBlank() })

  var intentTag: String = "unknown"

  var webFreshUntil: Instant? = null
    private set

  @Synchronized
  fun webResultsFreshUntil(until: Instant) {
    webFreshUntil = webFreshUntil?.let { minOf(it, until) } ?: until
  }

  val timings = LinkedHashMap<String, Long>()

  val counts = mutableListOf<String>()

  private val queries = mutableListOf<String>()

  private val plannerTexts = mutableSetOf<String>()

  @Synchronized
  fun plannerWrote(text: String) {
    if (text.isNotBlank()) plannerTexts += text
  }

  @Synchronized fun isPlannerText(text: String) = text.isBlank() || text in plannerTexts

  @Synchronized
  fun ran(query: String?) {
    if (!query.isNullOrBlank()) queries += query
  }

  @Synchronized fun queriesRun(): List<String> = queries.toList()
}

internal class Asked {
  var spentBudget: GuideBudgetExhausted? = null
    private set

  val budgetExhausted: Boolean
    get() = spentBudget != null

  fun modelFailed(e: RuntimeException) {
    spentBudget = e as? GuideBudgetExhausted
  }

  var probe: SimilarQuestions.Probe? = null
  var reused: Boolean = false
  var outOfTime: Boolean = false
}

internal class Reading(
  val plan: GuideSearchPlan?,
  val modelFailed: Boolean,
  val intent: GuideIntent,
  val subjects: List<String>,
  val window: DateWindow?,
  val constraints: List<String>,
  val query: String,
  val webQuery: String?,
  val allowReading: Boolean,
  val aboutEvent: Boolean = false,
  val askBackSubject: String? = null,
)

internal class GuideSteps(
  private val search: SearchResources,
  private val observations: ObservationRegistry,
  private val clock: Clock,
) {
  fun questionIsCancelled() = Thread.currentThread().isInterrupted || QuestionDeadline.isOver(clock)

  fun <T> step(q: Question, name: String, work: () -> T): T {
    val observation =
      Observation.createNotStarted("helper2.guide.step", observations)
        .lowCardinalityKeyValue("step", name)
        .start()
    val started = System.nanoTime()
    try {
      return observation.openScope().use { work() }
    } catch (e: Throwable) {
      observation.error(e)
      throw e
    } finally {
      q.timings.merge(name, (System.nanoTime() - started) / 1_000_000, Long::plus)
      observation.lowCardinalityKeyValue("intent", q.intentTag)
      observation.stop()
    }
  }

  fun <T> stepNeededToAnswer(q: Question, name: String, work: () -> T): T {
    if (questionIsCancelled())
      throw InterruptedException("the question was cancelled before its $name step")
    return step(q, name, work)
  }

  fun <T> modelStep(q: Question, name: String, work: () -> T): T {
    if (questionIsCancelled())
      throw GuideUnavailable(
        "the question was cancelled before its $name step",
        GuideUnavailable.CANCELLED,
      )
    return step(q, name, work)
  }

  fun find(
    q: Question,
    r: Reading,
    request: SearchRequest,
    planned: Boolean = false,
  ): SearchOutcome {
    val intentClass =
      when (r.intent) {
        GuideIntent.NEWEST,
        GuideIntent.WHEN,
        GuideIntent.LEARN -> IntentClass.TIME_BOUND
        else -> IntentClass.GENERAL
      }
    val outcome = search.search(request.copy(asker = q.asker, intentClass = intentClass))
    outcome.webFreshUntil?.let(q::webResultsFreshUntil)
    if (planned && r.plan?.plannedQuery != null) q.ran(request.text)
    val web = request.webQuery
    if (
      web != null &&
        web == r.webQuery &&
        outcome.statuses.any { it.source.usesWebQuery && it.outcome != SourceOutcome.SKIPPED }
    )
      q.ran(web)
    return outcome
  }
}

internal val WEB_ANNOUNCEMENTS = setOf(ResourceType.NEWS, ResourceType.ARTICLE)

internal const val SUBJECT_SEARCH_SIZE = 5

internal fun failure(e: RuntimeException) =
  (e as? GuideUnavailable)?.category ?: e.javaClass.simpleName

internal const val OUT_OF_TIME =
  "The guide ran out of time before its AI answer was ready; showing search results instead."

internal fun outOfTimeNotice(e: RuntimeException): List<Notice> =
  if ((e as? GuideUnavailable)?.outOfTime == true)
    listOf(Notice(NoticeCode.GUIDE_OUT_OF_TIME, OUT_OF_TIME))
  else emptyList()

internal const val BUDGET_SPENT_TODAY =
  "The AI guide has answered as many questions as it can today; showing search results instead. AI answers are back after 00:00 UTC."

internal const val BUDGET_SPENT_FOR_NOW =
  "The AI guide has answered as many questions as it can for now; showing search results instead."

private val UTC_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

internal fun budgetSpentNotice(asked: Asked): List<Notice> {
  val spent = asked.spentBudget ?: return emptyList()
  val back =
    spent.opensAgainAt?.let { "AI answers are back at ${UTC_TIME.format(it)} UTC." }
      ?: "Try again later today."
  val message = if (spent.opensMoreToday) "$BUDGET_SPENT_FOR_NOW $back" else BUDGET_SPENT_TODAY
  return listOf(Notice(NoticeCode.GUIDE_BUDGET_SPENT, message, retryAt = spent.opensAgainAt))
}

internal fun askBack(
  clock: Clock,
  r: Reading,
  c: Clarification,
  outcome: SearchOutcome,
  progress: GuideProgress,
): GuideAnswer {
  progress.clarify(c)
  return GuideAnswer(
    GuideStatus.NEEDS_CLARIFICATION,
    r.query,
    emptyList(),
    true,
    0,
    0,
    outcome,
    clock.instant(),
    r.constraints,
    clarification = c,
  )
}

internal fun namesakes(r: Reading, outcome: SearchOutcome): Clarification? =
  r.askBackSubject?.let { AmbiguousSubject.clarification(it, outcome.results, r.plan?.clarify) }

internal fun GuideAnswer.withNotices(extra: List<Notice>) =
  if (extra.isEmpty()) this else copy(notices = notices + extra)

internal fun degraded(
  clock: Clock,
  status: GuideStatus,
  query: String,
  candidates: List<Candidate>,
  outcome: SearchOutcome,
  rejected: Int,
  modelUsed: Boolean,
  constraints: List<String> = emptyList(),
) =
  GuideAnswer(
    status = status,
    searchQuery = query,
    recommendations =
      candidates.take(3).map { c ->
        Recommendation(
          c.result,
          "Top search result (no AI reasoning available).",
          (c.result.ownDescription ?: c.result.title).take(160),
          aiReasoned = false,
          checks = constraints.map { ConstraintCheck(it, CheckStatus.NOT_CONFIRMED, null) },
        )
      },
    modelUsed = modelUsed,
    candidatesConsidered = candidates.size,
    rejected = rejected,
    search = outcome,
    generatedAt = clock.instant(),
    constraints = constraints,
  )
