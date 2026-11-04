package ai.helper2.guide.steps

import ai.helper2.guide.AnswerLimits
import ai.helper2.guide.Candidate
import ai.helper2.guide.ComparedSubject
import ai.helper2.guide.Comparison
import ai.helper2.guide.ComparisonCell
import ai.helper2.guide.ComparisonProposal
import ai.helper2.guide.ComparisonRow
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.ProposedRow
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.RemovedSuggestion
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TrailCheck
import ai.helper2.guide.isSubject
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import io.micrometer.context.ContextExecutorService
import io.micrometer.context.ContextSnapshotFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * COMPARE (ADR 0032): each subject's own entry, aspect rows from one model call, every cell quoted.
 */
internal class CompareAnswer(
  private val steps: GuideSteps,
  private val picks: PickAnswer,
  private val reasonCheck: ReasonCheckStep,
  private val validator: GuideValidator,
  private val limits: AnswerLimits,
  private val clock: Clock,
  private val subjectSearchDeadline: Duration,
) {
  private val log = LoggerFactory.getLogger(CompareAnswer::class.java)

  /**
   * "Ollama vs LM Studio": each subject's own entry, then one model call that fills aspect rows; a
   * cell survives only with a quote from that subject's own text. Fewer than two subjects found:
   * the picks of the one found.
   */
  fun answer(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    val found = steps.step(q, "search") { subjectsFound(q, r) }
    val subjects =
      r.subjects.zip(found).mapIndexed { i, (name, res) -> ComparedSubject("s${i + 1}", name, res) }
    val present = subjects.mapNotNull { s -> s.result?.let { Candidate(s.key, it) } }
    progress.retrieved(present.size, SearchOutcome.empty(clock.instant()))
    if (present.size < 2) return picksOfFound(q, r, progress, asked, model, subjects, present)
    val empty = Comparison(subjects, emptyList())
    if (model == null)
      return subjectsOnly(GuideStatus.LLM_UNAVAILABLE, r, present, empty, modelUsed = false)
    val proposal =
      try {
        steps.step(q, "compare") { model.compare(q.fullText, present) }
      } catch (e: RuntimeException) {
        log.warn("guide compare failed: ${failure(e)}")
        asked.budgetExhausted = e is GuideBudgetExhausted
        return subjectsOnly(GuideStatus.LLM_UNAVAILABLE, r, present, empty, modelUsed = false)
      }
    progress.chosen(proposal.rows.size)
    return compared(q, r, progress, model, proposal, subjects, present)
  }

  /** Fewer than two subjects found: the picks of the one found, or of the question's query. */
  private fun picksOfFound(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
    subjects: List<ComparedSubject>,
    present: List<Candidate>,
  ): GuideAnswer {
    val fallback = present.firstOrNull()?.result?.title ?: r.query
    // The name is the planner's: a field of its own, so the page marks it AI-written (ADR 0038).
    val notices =
      subjects
        .filter { it.result == null }
        .map { Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, NOT_FOUND_COMPARED, subject = it.name) }
    return picks.picks(
      q,
      r,
      progress,
      asked,
      model,
      Retrieval(fallback, readingFilter(r), notices = notices, planned = fallback == r.query),
    )
  }

  /** The model's rows, each cell kept only with its quote and when the reason check keeps it. */
  private fun compared(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    model: GuideModel,
    proposal: ComparisonProposal,
    subjects: List<ComparedSubject>,
    present: List<Candidate>,
  ): GuideAnswer {
    val byKey = present.associateBy { it.key }
    val gate = CellGate()
    val rows = steps.step(q, "validate") { quotedRows(proposal, subjects, byKey, gate) }
    val rejected = gate.rejected
    progress.validated(rows.sumOf { row -> row.cells.count { it.text != null } }, rejected)
    q.counts +=
      "subjects=${present.size} rows=${proposal.rows.size} keptRows=${rows.size} rejectedCells=$rejected"
    val claims = claimsOf(rows, byKey)
    val verdicts =
      if (claims.isEmpty()) emptyMap() else reasonCheck.verdicts(q, model, claims, progress)
    val checkedRows = reasonChecked(rows, verdicts, byKey, gate.removed)
    val status =
      if (checkedRows.isEmpty()) GuideStatus.PARTIAL
      else if (rejected == 0) GuideStatus.OK else GuideStatus.PARTIAL
    return subjectsOnly(
        status,
        r,
        present,
        Comparison(subjects, checkedRows),
        modelUsed = true,
        rejected = rejected,
      )
      .copy(trail = ResearchTrail(removed = gate.removed))
  }

  /**
   * The proposed rows with a usable aspect, at most [AnswerLimits.maxRows], each cell through the
   * quote gate; a row with no cell left is dropped.
   */
  private fun quotedRows(
    proposal: ComparisonProposal,
    subjects: List<ComparedSubject>,
    byKey: Map<String, Candidate>,
    gate: CellGate,
  ): List<ComparisonRow> =
    proposal.rows
      .asSequence()
      .map { it.aspect.trim() }
      .zip(proposal.rows.asSequence())
      .filter { (aspect, _) -> isUsableAspect(aspect) }
      .distinctBy { (aspect, _) -> aspect.lowercase() }
      .take(limits.maxRows)
      .map { (aspect, row) ->
        ComparisonRow(aspect, subjects.map { s -> quotedCell(s, row, byKey, gate) })
      }
      .filter { row -> row.cells.any { it.text != null } }
      .toList()

  private fun isUsableAspect(aspect: String) =
    aspect.isNotEmpty() &&
      aspect.length <= limits.maxAspectChars &&
      !GuideValidator.containsLink(aspect)

  /** [s]'s cell of [row] when its quote holds in [s]'s own text, else an empty cell. */
  private fun quotedCell(
    s: ComparedSubject,
    row: ProposedRow,
    byKey: Map<String, Candidate>,
    gate: CellGate,
  ): ComparisonCell {
    val cell = row.cells.firstOrNull { it.subjectKey.trim() == s.key }
    val c = byKey[s.key]
    val check =
      when {
        cell == null || c == null -> null
        cell.text.isBlank() ||
          cell.text.length > limits.maxCellChars ||
          GuideValidator.containsLink(cell.text) -> TrailCheck.REASON_NOT_SUPPORTED
        !validator.shortQuoteHolds(cell.quote, c.text) -> TrailCheck.QUOTE_NOT_FOUND
        else -> null
      }
    val ok = cell != null && c != null && check == null
    if (cell != null && !ok) gate.rejected++
    // The trail names the subject whose cell was removed, never the cell or its aspect
    // (ADR 0044).
    if (c != null && check != null) gate.removed += RemovedSuggestion(c.result.title, check)
    return if (ok) ComparisonCell(s.key, cell!!.text.trim(), cell.quote.trim())
    else ComparisonCell(s.key, null, null)
  }

  private fun claimsOf(rows: List<ComparisonRow>, byKey: Map<String, Candidate>) =
    rows.flatMapIndexed { i, row ->
      row.cells
        .filter { it.text != null }
        .map { cell ->
          ReasonClaim(
            "r${i + 1}-${cell.subjectKey}",
            cell.text!!,
            cell.quote!!,
            byKey.getValue(cell.subjectKey).text,
            emptyList(),
          )
        }
    }

  /** The rows with each cell's verdict; a removed cell is emptied and goes to [removed]. */
  private fun reasonChecked(
    rows: List<ComparisonRow>,
    verdicts: Map<String, ReasonCheck>,
    byKey: Map<String, Candidate>,
    removed: MutableList<RemovedSuggestion>,
  ): List<ComparisonRow> = rows.mapIndexed { i, row ->
    row.copy(
      cells =
        row.cells.map { cell ->
          when (val v = verdicts["r${i + 1}-${cell.subjectKey}"]) {
            null -> cell
            ReasonCheck.REMOVED -> {
              removed +=
                RemovedSuggestion(
                  byKey.getValue(cell.subjectKey).result.title,
                  TrailCheck.REASON_NOT_SUPPORTED,
                )
              ComparisonCell(cell.subjectKey, null, null, ReasonCheck.REMOVED)
            }
            else -> cell.copy(reasonCheck = v)
          }
        }
    )
  }

  /** What the quote gate rejected, over every gated cell, and the trail of what it removed. */
  private class CellGate {
    var rejected = 0
    val removed = mutableListOf<RemovedSuggestion>()
  }

  /**
   * Each subject's own entry, or null when its search found none, failed or missed
   * [subjectSearchDeadline]. The searches run at the same time, one virtual thread each, carrying
   * the question's trace.
   */
  private fun subjectsFound(q: Question, r: Reading): List<MergedResult?> {
    val searches =
      r.subjects.map { name ->
        Callable {
          steps
            .find(q, r, SearchRequest(text = name, path = q.path, size = SUBJECT_SEARCH_SIZE))
            .results
            .firstOrNull { isSubject(it, name) }
        }
      }
    val threads =
      ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        ContextSnapshotFactory.builder().build(),
      )
    try {
      return threads
        .invokeAll(searches, subjectSearchDeadline.toNanos(), TimeUnit.NANOSECONDS)
        .map(::foundOrNull)
    } finally {
      threads.shutdownNow()
    }
  }

  private fun foundOrNull(search: Future<MergedResult?>): MergedResult? =
    try {
      search.get()
    } catch (e: CancellationException) {
      log.warn("guide compare: a subject search missed the deadline of $subjectSearchDeadline")
      null
    } catch (e: ExecutionException) {
      log.warn("guide compare: a subject search failed: ${e.cause?.javaClass?.simpleName}")
      null
    }

  private fun subjectsOnly(
    status: GuideStatus,
    r: Reading,
    present: List<Candidate>,
    comparison: Comparison,
    modelUsed: Boolean,
    rejected: Int = 0,
  ) =
    GuideAnswer(
      status,
      r.query,
      present.map {
        Recommendation(
          it.result,
          "",
          (it.result.description ?: it.result.title).take(160),
          aiReasoned = false,
        )
      },
      null,
      modelUsed,
      present.size,
      rejected,
      SearchOutcome.empty(clock.instant()),
      clock.instant(),
      r.constraints,
      comparison = comparison,
    )
}
