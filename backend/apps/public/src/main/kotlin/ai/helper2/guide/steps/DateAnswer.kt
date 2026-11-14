package ai.helper2.guide.steps

import ai.helper2.guide.Candidate
import ai.helper2.guide.DateWindow
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.IntentRules
import ai.helper2.guide.Recommendation
import ai.helper2.guide.isSubject
import ai.helper2.guide.ownDescription
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class DateAnswer(
  private val steps: GuideSteps,
  private val maxCandidates: Int,
  private val clock: Clock,
) {

  private companion object {
    const val MAX_EVENTS = 5
    val EVENTS = setOf(ResourceType.EVENT)
  }

  fun answer(q: Question, r: Reading, progress: GuideProgress): GuideAnswer {
    val subject = r.subjects.firstOrNull()
    val text = subject ?: IntentRules.subjectWords(r.query).ifBlank { "AI" }
    val request = searchRequest(q, r, text)
    val planned = subject == null && IntentRules.subjectWords(r.query).isNotBlank()
    val outcome = steps.stepNeededToAnswer(q, "search") { steps.find(q, r, request, planned) }
    namesakes(r, outcome)?.let {
      return askBack(clock, r, it, outcome, progress)
    }
    progress.retrieved(outcome.results.size, outcome)
    val events = eventsOf(outcome, subject, r.window)
    if (events.isNotEmpty()) return eventsAnswer(events, text, r, outcome)
    val own = subject?.let { name ->
      outcome.results.firstOrNull { isSubject(it, name) && it.type != ResourceType.EVENT }
    }
    if (own != null) return creationDateAnswer(own, text, r, outcome)
    if (outcome.results.isEmpty()) return noCandidates(text, r, outcome)
    return closestResults(text, r, outcome)
  }

  private fun searchRequest(q: Question, r: Reading, text: String): SearchRequest =
    if (r.aboutEvent) {
      SearchRequest(
        text = text,
        path = q.path,
        filters = SearchFilters(types = EVENTS),
        size = maxCandidates,
        webQuery = r.webQuery,
      )
    } else {
      SearchRequest(text = text, path = q.path, size = maxCandidates)
    }

  private fun eventsOf(
    outcome: SearchOutcome,
    subject: String?,
    window: DateWindow?,
  ): List<MergedResult> =
    outcome.results
      .filter { it.type == ResourceType.EVENT && it.eventStart.value != null }
      .filter { e -> subject == null || e.title.contains(subject, ignoreCase = true) }
      .filter { e -> isInWindowOrUpcoming(e, window) }
      .sortedBy { it.eventStart.value }
      .take(MAX_EVENTS)

  private fun isInWindowOrUpcoming(e: MergedResult, window: DateWindow?): Boolean {
    val start = e.eventStart.value!!
    val end = e.eventEnd.value ?: start
    return if (window != null) {
      !end.isBefore(window.from.atStartOfDay(ZoneOffset.UTC).toInstant()) &&
        start.isBefore(window.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    } else {
      !end.isBefore(
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant()
      )
    }
  }

  private fun eventsAnswer(
    events: List<MergedResult>,
    text: String,
    r: Reading,
    outcome: SearchOutcome,
  ) =
    GuideAnswer(
      GuideStatus.OK,
      text,
      events.map {
        Recommendation(
          it,
          eventReason(it),
          (it.ownDescription ?: it.title).take(160),
          aiReasoned = false,
        )
      },
      !r.modelFailed,
      outcome.results.size,
      0,
      outcome,
      clock.instant(),
      r.constraints,
    )

  private fun creationDateAnswer(
    own: MergedResult,
    text: String,
    r: Reading,
    outcome: SearchOutcome,
  ): GuideAnswer {
    val date = own.published.value
    val reason =
      if (date == null) "No release date found in our sources."
      else
        "Created ${DateTimeFormatter.ISO_LOCAL_DATE.format(date.atOffset(ZoneOffset.UTC))}, as stated by ${own.published.sourceId ?: "its source"}."
    return GuideAnswer(
      if (date == null) GuideStatus.PARTIAL else GuideStatus.OK,
      text,
      listOf(
        Recommendation(own, reason, (own.ownDescription ?: own.title).take(160), aiReasoned = false)
      ),
      !r.modelFailed,
      outcome.results.size,
      0,
      outcome,
      clock.instant(),
      r.constraints,
    )
  }

  private fun noCandidates(text: String, r: Reading, outcome: SearchOutcome) =
    GuideAnswer(
      GuideStatus.NO_CANDIDATES,
      text,
      emptyList(),
      !r.modelFailed,
      0,
      0,
      outcome,
      clock.instant(),
      r.constraints,
    )

  private fun closestResults(text: String, r: Reading, outcome: SearchOutcome) =
    degraded(
        clock,
        GuideStatus.PARTIAL,
        text,
        outcome.results.mapIndexed { i, res -> Candidate("c${i + 1}", res) },
        outcome,
        0,
        modelUsed = !r.modelFailed,
      )
      .withNotices(
        listOf(
          Notice(
            NoticeCode.GUIDE_SUBJECT_NOT_FOUND,
            "No date found in our sources; showing the closest search results.",
          )
        )
      )

  private fun eventReason(e: MergedResult): String {
    val day = DateTimeFormatter.ISO_LOCAL_DATE
    val start = day.format(e.eventStart.value!!.atOffset(ZoneOffset.UTC))
    val end =
      e.eventEnd.value?.let { day.format(it.atOffset(ZoneOffset.UTC)) }?.takeIf { it != start }
    val where = e.location?.takeIf { it.isNotBlank() }
    return buildString {
      append(if (end == null) "On $start" else "From $start to $end")
      if (where != null) append(", $where")
      append(" (as stated by ${e.eventStart.sourceId ?: "its source"}).")
    }
  }
}
