package ai.helper2.guide.steps

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.READING_TYPES
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TOOL_TYPES
import ai.helper2.guide.isSubject
import ai.helper2.guide.ownDescription
import ai.helper2.search.Filtering
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.search.SortOrder
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

internal fun readingFilter(r: Reading) =
  if (r.allowReading) SearchFilters(types = ResourceType.entries.toSet() - LEARN_TYPES)
  else SearchFilters(types = TOOL_TYPES)

internal const val NOT_FOUND_ALTERNATIVES =
  "The name in the question was not found in our sources; showing tools for the planned search instead."
internal const val NOT_FOUND_COMPARED = "A name to compare was not found in our sources."

internal data class Retrieval(
  val text: String,
  val filters: SearchFilters,
  val sort: SortOrder = SortOrder.RELEVANCE,
  val browse: Boolean = false,
  val exclude: (MergedResult) -> Boolean = { false },
  val notices: List<Notice> = emptyList(),
  val allowReading: Boolean = false,
  val think: Boolean = false,
  val askBack: Boolean = false,
  val planned: Boolean = true,
)

internal class PickAnswer(
  private val steps: GuideSteps,
  private val reasonCheck: ReasonCheckStep,
  private val validator: GuideValidator,
  private val maxCandidates: Int,
  private val clock: Clock,
  private val meters: MeterRegistry,
) {
  private val log = LoggerFactory.getLogger(PickAnswer::class.java)

  fun newest(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    val answer = picks(q, r, progress, asked, model, newestRetrieval(r))
    return answer.copy(
      recommendations =
        answer.recommendations.sortedWith(
          compareBy(nullsLast(reverseOrder())) { it.result.published.value }
        )
    )
  }

  private fun newestRetrieval(r: Reading): Retrieval {
    val subject = IntentRules.subjectWords(r.query)
    val types = TOOL_TYPES + if (r.allowReading) READING_TYPES else WEB_ANNOUNCEMENTS
    val filters =
      SearchFilters(types = types, publishedAfter = r.window?.from, publishedBefore = r.window?.to)
    val browse = r.window != null && subject.isBlank()
    return Retrieval(
      text = if (browse) "" else subject.ifBlank { r.query },
      filters = filters,
      sort = if (r.window == null) SortOrder.NEWEST else SortOrder.RELEVANCE,
      browse = browse,
      exclude = { it.ownDescription.isNullOrBlank() },
      allowReading = true,
      think = browse,
    )
  }

  fun alternatives(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    val name =
      r.subjects.firstOrNull()
        ?: return picks(q, r, progress, asked, model, Retrieval(r.query, readingFilter(r)))
    val found = steps.stepNeededToAnswer(q, "search") { searchSubject(q, r, name) }
    namesakes(r, found)?.let {
      return askBack(clock, r, it, found, progress)
    }
    val own = found.results.firstOrNull { isSubject(it, name) }
    val category = own?.let { categoryOf(it) }
    val notices = if (own == null) subjectNotFound(name, r) else emptyList()
    val text = category?.takeIf { IntentRules.subjectWords(it).isNotBlank() } ?: r.query
    return picks(
      q,
      r,
      progress,
      asked,
      model,
      Retrieval(
        text,
        readingFilter(r),
        exclude = { isSubject(it, name) },
        notices = notices,
        planned = text == r.query,
      ),
    )
  }

  private fun searchSubject(q: Question, r: Reading, name: String): SearchOutcome =
    steps.find(
      q,
      r,
      SearchRequest(
        text = name,
        path = q.path,
        filters = SearchFilters(types = TOOL_TYPES),
        size = SUBJECT_SEARCH_SIZE,
      ),
    )

  private fun categoryOf(own: MergedResult): String =
    listOfNotNull(
        own.useCases.joinToString(" ").ifBlank { null },
        own.type.name.lowercase().replace('_', ' '),
      )
      .joinToString(" ")

  private fun subjectNotFound(name: String, r: Reading): List<Notice> {
    return listOf(
      Notice(
        NoticeCode.GUIDE_SUBJECT_NOT_FOUND,
        NOT_FOUND_ALTERNATIVES,
        subject = name,
        query = r.plan?.plannedQuery,
      )
    )
  }

  fun picks(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
    retrieval: Retrieval,
  ): GuideAnswer {
    val outcome = steps.stepNeededToAnswer(q, "search") { searchFor(q, r, retrieval) }
    if (retrieval.askBack)
      namesakes(r, outcome)?.let {
        return askBack(clock, r, it, outcome, progress)
      }
    val candidates =
      outcome.results.filterNot(retrieval.exclude).take(maxCandidates).mapIndexed { i, res ->
        Candidate("c${i + 1}", res)
      }
    val allowReading = r.allowReading || retrieval.allowReading
    val choosable =
      if (allowReading) candidates else candidates.filter { it.result.type !in READING_TYPES }
    progress.retrieved(candidates.size, outcome)
    logCandidates(candidates)
    val constraints = r.constraints
    if (candidates.isEmpty()) return noCandidates(retrieval, r, outcome)
    if (model == null) {
      return topResults(
        GuideStatus.LLM_UNAVAILABLE,
        retrieval,
        candidates,
        outcome,
        modelUsed = false,
        constraints = emptyList(),
      )
    }
    if (choosable.isEmpty()) {
      return topResults(
        GuideStatus.PARTIAL,
        retrieval,
        candidates,
        outcome,
        modelUsed = true,
        constraints = constraints,
      )
    }
    val proposal =
      try {
        steps.modelStep(q, "choose") { chooseAmong(q, model, retrieval, choosable, constraints) }
      } catch (e: RuntimeException) {
        log.warn("guide choose failed: ${failure(e)}")
        asked.modelFailed(e)
        return topResults(
            GuideStatus.LLM_UNAVAILABLE,
            retrieval,
            candidates,
            outcome,
            modelUsed = false,
            constraints = constraints,
          )
          .withNotices(outOfTimeNotice(e))
      }
    progress.chosen(proposal.picks.size)
    val checked =
      steps.step(q, "validate") {
        validator
          .validate(proposal, choosable, constraints, allowReading)
          .insideWindow(retrieval.filters)
      }
    progress.validated(checked.picks.size, checked.rejected)
    val keptRanks = countKeptRanks(checked, candidates)
    val verdicts =
      if (checked.picks.isEmpty()) ReasonVerdicts.NONE
      else reasonCheck.verdicts(q, model, claimsOf(checked), progress)
    q.counts +=
      "candidates=${candidates.size} kept=${checked.picks.size} keptRanks=$keptRanks rejected=${checked.rejected} " +
        "rejectedBy=${checked.rejectedBy} reasons=${verdicts.checks.values.groupingBy { it }.eachCount()} " +
        "offNeed=${verdicts.offNeed.size}"
    val fitting = checked.withoutOffNeed(verdicts)
    val trail = ResearchTrail(removed = validator.removedOf(fitting))
    if (fitting.picks.isEmpty()) {
      return topResults(
          GuideStatus.PARTIAL,
          retrieval,
          candidates,
          outcome,
          rejected = fitting.rejected,
          modelUsed = true,
          constraints = constraints,
        )
        .copy(trail = trail)
    }
    return pickedAnswer(retrieval, candidates, outcome, fitting, verdicts, constraints, trail)
  }

  private fun searchFor(q: Question, r: Reading, retrieval: Retrieval): SearchOutcome =
    steps.find(
      q,
      r,
      SearchRequest(
        text = retrieval.text,
        path = q.path,
        filters = retrieval.filters.copy(categories = retrieval.filters.categories + q.categories),
        sort = retrieval.sort,
        size = maxCandidates,
        webQuery = r.webQuery,
        browse = retrieval.browse,
      ),
      planned = retrieval.planned,
    )

  private fun logCandidates(candidates: List<Candidate>) {
    if (log.isDebugEnabled)
      log.debug(
        "guide candidates: {}",
        candidates.map {
          "${it.key} ${it.result.type} ${it.result.title.take(60)} ${it.result.published.value} desc=${it.result.description?.length ?: 0}"
        },
      )
  }

  private fun noCandidates(retrieval: Retrieval, r: Reading, outcome: SearchOutcome) =
    GuideAnswer(
      GuideStatus.NO_CANDIDATES,
      retrieval.text,
      emptyList(),
      !r.modelFailed,
      0,
      0,
      outcome,
      clock.instant(),
      r.constraints,
      notices = retrieval.notices,
    )

  private fun topResults(
    status: GuideStatus,
    retrieval: Retrieval,
    candidates: List<Candidate>,
    outcome: SearchOutcome,
    rejected: Int = 0,
    modelUsed: Boolean,
    constraints: List<String>,
  ) =
    degraded(
        clock,
        status,
        retrieval.text,
        candidates,
        outcome,
        rejected,
        modelUsed = modelUsed,
        constraints = constraints,
      )
      .withNotices(retrieval.notices)

  private fun chooseAmong(
    q: Question,
    model: GuideModel,
    retrieval: Retrieval,
    choosable: List<Candidate>,
    constraints: List<String>,
  ): GuideProposal =
    if (retrieval.think) model.choose(q.need, choosable, constraints, true)
    else model.choose(q.need, choosable, constraints)

  private fun countKeptRanks(
    checked: GuideValidator.Result,
    candidates: List<Candidate>,
  ): List<Int> {
    val keptRanks = checked.picks.map { candidates.indexOf(it.candidate) + 1 }
    keptRanks.forEach {
      meters.counter("helper2.guide.picks", "rank", rankBand(it)).increment()
    }
    return keptRanks
  }

  private fun pickedAnswer(
    retrieval: Retrieval,
    candidates: List<Candidate>,
    outcome: SearchOutcome,
    checked: GuideValidator.Result,
    verdicts: ReasonVerdicts,
    constraints: List<String>,
    trail: ResearchTrail,
  ) =
    GuideAnswer(
      status = if (checked.rejected == 0) GuideStatus.OK else GuideStatus.PARTIAL,
      searchQuery = retrieval.text,
      recommendations =
        checked.picks.map { (c, p) ->
          val verdict = verdicts[c.key] ?: ReasonCheck.NOT_CHECKED
          Recommendation(
            c.result,
            if (verdict == ReasonCheck.REMOVED) "" else p.reason,
            p.quote,
            aiReasoned = true,
            checks = checked.checks[c.key].orEmpty(),
            reasonCheck = verdict,
          )
        },
      modelUsed = true,
      candidatesConsidered = candidates.size,
      rejected = checked.rejected,
      search = outcome,
      generatedAt = clock.instant(),
      constraints = constraints,
      notices = retrieval.notices,
      trail = trail,
    )

  private fun GuideValidator.Result.withoutOffNeed(
    verdicts: ReasonVerdicts
  ): GuideValidator.Result {
    val (fit, off) = picks.partition { verdicts.fitsNeed(it.candidate.key) }
    if (off.isEmpty()) return this
    val rejection = GuideValidator.Rejection.OFF_NEED
    return copy(
      picks = fit,
      rejected = rejected + off.size,
      rejectedBy = rejectedBy + (rejection to (rejectedBy[rejection] ?: 0) + off.size),
      rejectedPicks =
        rejectedPicks + off.map { GuideValidator.RejectedPick(it.candidate, rejection) },
    )
  }

  private fun GuideValidator.Result.insideWindow(filters: SearchFilters): GuideValidator.Result {
    if (filters.publishedAfter == null && filters.publishedBefore == null) return this
    val inside = picks.filter {
      Filtering.apply(
          listOf(it.candidate.result),
          SearchFilters(
            publishedAfter = filters.publishedAfter,
            publishedBefore = filters.publishedBefore,
          ),
        )
        .isNotEmpty()
    }
    val out = picks.size - inside.size
    if (out == 0) return this
    val outside =
      (picks - inside.toSet()).map {
        GuideValidator.RejectedPick(it.candidate, GuideValidator.Rejection.OUTSIDE_WINDOW)
      }
    return copy(
      picks = inside,
      rejected = rejected + out,
      rejectedBy =
        rejectedBy +
          (GuideValidator.Rejection.OUTSIDE_WINDOW to
            (rejectedBy[GuideValidator.Rejection.OUTSIDE_WINDOW] ?: 0) + out),
      rejectedPicks = rejectedPicks + outside,
    )
  }

  private fun claimsOf(checked: GuideValidator.Result) =
    checked.picks.map { (c, p) ->
      val created =
        c.result.published.value?.let {
          "\nCreated: ${DateTimeFormatter.ISO_LOCAL_DATE.format(it.atOffset(ZoneOffset.UTC))} (as stated by ${c.result.published.sourceId ?: "its source"})"
        } ?: ""
      ReasonClaim(
        c.key,
        p.reason,
        p.quote,
        c.text + created,
        checked.checks[c.key].orEmpty().filter { it.status == CheckStatus.CONFIRMED },
      )
    }
}

internal fun rankBand(rank: Int): String =
  when {
    rank <= 3 -> "1-3"
    rank <= 8 -> "4-8"
    else -> "9+"
  }
