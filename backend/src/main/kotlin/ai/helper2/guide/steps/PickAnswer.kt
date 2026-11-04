package ai.helper2.guide.steps

import ai.helper2.guide.Candidate
import ai.helper2.guide.CheckStatus
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.PlannedQueries
import ai.helper2.guide.READING_TYPES
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TOOL_TYPES
import ai.helper2.guide.isSubject
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

/**
 * Tools only, unless the user asked for something to read (seen in evaluation run 4: papers filled
 * the slots).
 */
internal fun readingFilter(r: Reading) =
  if (r.allowReading) SearchFilters(types = ResourceType.entries.toSet() - LEARN_TYPES)
  else SearchFilters(types = TOOL_TYPES)

/**
 * The not-found notices' messages: our own words only; the name and the query travel apart (ADR
 * 0038).
 */
internal const val NOT_FOUND_ALTERNATIVES =
  "The name in the question was not found in our sources; showing tools for the planned search instead."
internal const val NOT_FOUND_COMPARED = "A name to compare was not found in our sources."

/** One search for the pick pipeline: text, filters, order, and what must not be picked. */
internal data class Retrieval(
  val text: String,
  val filters: SearchFilters,
  val sort: SortOrder = SortOrder.RELEVANCE,
  val browse: Boolean = false,
  val exclude: (MergedResult) -> Boolean = { false },
  val notices: List<Notice> = emptyList(),
  /**
   * Reading types may be chosen even though the question asks for no reading (web announcements for
   * NEWEST).
   */
  val allowReading: Boolean = false,
  /**
   * The chooser thinks before answering: only for a broad browse, where it has no subject to match.
   */
  val think: Boolean = false,
  /**
   * This search is the question's subject search: namesakes of different kinds are asked back
   * ([namesakes]).
   */
  val askBack: Boolean = false,
  /**
   * [text] is the planner's query (or cut from it), not a name or a source's words: the research
   * trail lists it.
   */
  val planned: Boolean = true,
)

/**
 * The pick pipeline of SUGGEST, HOW_TO, LEARN, NEWEST and ALTERNATIVES: one search, the chooser
 * among real candidates, the validator, the reason check.
 */
internal class PickAnswer(
  private val steps: GuideSteps,
  private val reasonCheck: ReasonCheckStep,
  private val validator: GuideValidator,
  private val maxCandidates: Int,
  private val clock: Clock,
  private val meters: MeterRegistry,
) {
  private val log = LoggerFactory.getLogger(PickAnswer::class.java)

  /**
   * "AI tools born last month": created inside the window, chosen among the most relevant and used,
   * shown newest first.
   */
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
          compareByDescending(nullsLast()) { it.result.published.value }
        )
    )
  }

  private fun newestRetrieval(r: Reading): Retrieval {
    val subject = IntentRules.subjectWords(r.query)
    // Web announcements count: "new AI tools last month" is answered as much by an article
    // launching one as by a
    // catalog entry. The web query is AI-scoped and the web source drops non-AI pages.
    val types = TOOL_TYPES + if (r.allowReading) READING_TYPES else WEB_ANNOUNCEMENTS
    val filters =
      SearchFilters(types = types, publishedAfter = r.window?.from, publishedBefore = r.window?.to)
    val browse = r.window != null && subject.isBlank()
    return Retrieval(
      // Browsing ranks by popularity inside the window: words like "AI tools" would favour
      // projects named "tool".
      text = if (browse) "" else subject.ifBlank { r.query },
      filters = filters,
      // No period: "latest" orders by date. With a period, relevance inside it; the picks are
      // dated in [newest].
      sort = if (r.window == null) SortOrder.NEWEST else SortOrder.RELEVANCE,
      browse = browse,
      // A new entry without a description cannot be quoted, so it can never be chosen; it would
      // only take a slot.
      exclude = { it.description.isNullOrBlank() },
      allowReading = true,
      think = browse,
    )
  }

  /**
   * "alternatives to Cursor": the subject's own use cases as the search, the subject itself never a
   * pick.
   */
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
    val found = steps.step(q, "search") { searchSubject(q, r, name) }
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

  /** What the subject is for: its use cases and its kind, as search words. */
  private fun categoryOf(own: MergedResult): String =
    listOfNotNull(
        own.useCases.joinToString(" ").ifBlank { null },
        own.type.name.lowercase().replace('_', ' '),
      )
      .joinToString(" ")

  private fun subjectNotFound(name: String, r: Reading): List<Notice> {
    // The name and the query are the planner's words: fields of their own, so the page marks them
    // AI-written (ADR 0038).
    // Only a query the planner wrote: without one, r.query is the user's own text, which must not
    // be marked AI-written.
    val plannedQuery =
      r.plan?.let { PlannedQueries.searchQueryOf(it.searchQuery) }?.ifBlank { null }
    return listOf(
      Notice(
        NoticeCode.GUIDE_SUBJECT_NOT_FOUND,
        NOT_FOUND_ALTERNATIVES,
        subject = name,
        query = plannedQuery,
      )
    )
  }

  /**
   * Retrieval first, AI second, validator last: the guide as it was before intents, parameterised
   * by [retrieval]. Works (degraded) without a model.
   */
  fun picks(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
    retrieval: Retrieval,
  ): GuideAnswer {
    val outcome = steps.step(q, "search") { searchFor(q, r, retrieval) }
    if (retrieval.askBack)
      namesakes(r, outcome)?.let {
        return askBack(clock, r, it, outcome, progress)
      }
    val candidates =
      outcome.results.filterNot(retrieval.exclude).take(maxCandidates).mapIndexed { i, res ->
        Candidate("c${i + 1}", res)
      }
    // Defence in depth: the search filter already removed reading types; keep the model and the
    // validator on the same rule in case a source mislabels a type after filtering.
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
        steps.step(q, "choose") { chooseAmong(q, model, retrieval, choosable, constraints) }
      } catch (e: RuntimeException) {
        log.warn("guide choose failed: ${failure(e)}")
        asked.budgetExhausted = e is GuideBudgetExhausted
        return topResults(
          GuideStatus.LLM_UNAVAILABLE,
          retrieval,
          candidates,
          outcome,
          modelUsed = false,
          constraints = constraints,
        )
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
      if (checked.picks.isEmpty()) emptyMap()
      else reasonCheck.verdicts(q, model, claimsOf(checked), progress)
    q.counts +=
      "candidates=${candidates.size} kept=${checked.picks.size} keptRanks=$keptRanks rejected=${checked.rejected} " +
        "rejectedBy=${checked.rejectedBy} reasons=${verdicts.values.groupingBy { it }.eachCount()}"
    val trail = ResearchTrail(removed = validator.removedOf(checked))
    if (checked.picks.isEmpty()) {
      return topResults(
          GuideStatus.PARTIAL,
          retrieval,
          candidates,
          outcome,
          rejected = checked.rejected,
          modelUsed = true,
          constraints = constraints,
        )
        .copy(trail = trail)
    }
    return pickedAnswer(retrieval, candidates, outcome, checked, verdicts, constraints, trail)
  }

  private fun searchFor(q: Question, r: Reading, retrieval: Retrieval): SearchOutcome =
    steps.find(
      q,
      r,
      SearchRequest(
        text = retrieval.text,
        path = q.path,
        filters = retrieval.filters,
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
      null,
      !r.modelFailed,
      0,
      0,
      outcome,
      clock.instant(),
      r.constraints,
      notices = retrieval.notices,
    )

  /** The top search results instead of picks, with the retrieval's notices. */
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

  /** The thinking form only for a broad browse ([Retrieval.think]). */
  private fun chooseAmong(
    q: Question,
    model: GuideModel,
    retrieval: Retrieval,
    choosable: List<Candidate>,
    constraints: List<String>,
  ): GuideProposal =
    if (retrieval.think) model.choose(q.fullText, choosable, constraints, true)
    else model.choose(q.fullText, choosable, constraints)

  /**
   * Search rank of each kept pick, counted by band. A pick far down the list is the first sign of
   * text written to be chosen (content poisoning): its quote is genuine, so the gate cannot catch
   * it.
   */
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
    verdicts: Map<String, ReasonCheck>,
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
      prompt = checked.prompt,
      modelUsed = true,
      candidatesConsidered = candidates.size,
      rejected = checked.rejected,
      search = outcome,
      generatedAt = clock.instant(),
      constraints = constraints,
      notices = retrieval.notices,
      trail = trail,
    )

  /**
   * A pick born outside the window is rejected by code, not left to the model: the search filter
   * already keeps only dated candidates inside it, so this only catches a source that misreports a
   * date after filtering.
   */
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

  /**
   * The evidence includes the creation date its source states, when known: a reason that says "new
   * this month" is then checked against that date instead of being removed for a claim the text
   * cannot show.
   */
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

/** Search rank of a kept pick as a low-cardinality tag: `1-3`, `4-8` or `9+`. */
internal fun rankBand(rank: Int): String =
  when {
    rank <= 3 -> "1-3"
    rank <= 8 -> "4-8"
    else -> "9+"
  }
