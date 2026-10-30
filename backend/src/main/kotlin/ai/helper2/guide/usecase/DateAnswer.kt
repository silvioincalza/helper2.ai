package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.IntentRules
import ai.helper2.guide.Recommendation
import ai.helper2.guide.isSubject
import ai.helper2.search.MergedResult
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchRequest
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** WHEN (ADR 0032): dates from records, never from a model. */
internal class DateAnswer(private val steps: GuideSteps, private val maxCandidates: Int, private val clock: Clock) {

    private companion object {
        const val MAX_EVENTS = 5
    }

    /**
     * "when is NeurIPS?", "AI events next month", "when was Llama 4 released?": dates come from records, never from a
     * model. Events: their stated days, soonest first. Otherwise the subject's creation date and the source stating it.
     */
    fun answer(q: Question, r: Reading, progress: GuideProgress): GuideAnswer {
        val subject = r.subjects.firstOrNull()
        val text = subject ?: IntentRules.subjectWords(r.query).ifBlank { "AI" }
        val outcome = steps.step(q, "search") { steps.find(q, r, SearchRequest(text = text, path = q.path, size = maxCandidates)) }
        progress.retrieved(outcome.results.size, outcome)
        val window = r.window
        val events = outcome.results.filter { it.type == ResourceType.EVENT && it.eventStart.value != null }
            .filter { e -> subject == null || e.title.contains(subject, ignoreCase = true) }
            .filter { e ->
                val start = e.eventStart.value!!
                val end = e.eventEnd.value ?: start
                if (window != null) {
                    !end.isBefore(window.from.atStartOfDay(ZoneOffset.UTC).toInstant()) &&
                        start.isBefore(window.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                } else {
                    !end.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant())
                }
            }
            .sortedBy { it.eventStart.value }
            .take(MAX_EVENTS)
        if (events.isNotEmpty()) {
            return GuideAnswer(
                GuideStatus.OK, text, events.map { Recommendation(it, eventReason(it), (it.description ?: it.title).take(160), aiReasoned = false) },
                null, !r.modelFailed, outcome.results.size, 0, outcome, clock.instant(), r.constraints,
            )
        }
        val own = subject?.let { name -> outcome.results.firstOrNull { isSubject(it, name) && it.type != ResourceType.EVENT } }
        if (own != null) {
            val date = own.published.value
            val reason = if (date == null) "No release date found in our sources."
            else "Created ${DateTimeFormatter.ISO_LOCAL_DATE.format(date.atOffset(ZoneOffset.UTC))}, as stated by ${own.published.sourceId ?: "its source"}."
            return GuideAnswer(
                if (date == null) GuideStatus.PARTIAL else GuideStatus.OK, text,
                listOf(Recommendation(own, reason, (own.description ?: own.title).take(160), aiReasoned = false)),
                null, !r.modelFailed, outcome.results.size, 0, outcome, clock.instant(), r.constraints,
            )
        }
        if (outcome.results.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, text, emptyList(), null, !r.modelFailed, 0, 0, outcome, clock.instant(), r.constraints)
        }
        return degraded(clock, GuideStatus.PARTIAL, text, outcome.results.mapIndexed { i, res -> Candidate("c${i + 1}", res) }, outcome, 0, !r.modelFailed)
            .withNotices(listOf(Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "No date found in our sources; showing the closest search results.")))
    }

    private fun eventReason(e: MergedResult): String {
        val day = DateTimeFormatter.ISO_LOCAL_DATE
        val start = day.format(e.eventStart.value!!.atOffset(ZoneOffset.UTC))
        val end = e.eventEnd.value?.let { day.format(it.atOffset(ZoneOffset.UTC)) }?.takeIf { it != start }
        val where = e.location?.takeIf { it.isNotBlank() }
        return buildString {
            append(if (end == null) "On $start" else "From $start to $end")
            if (where != null) append(", $where")
            append(" (as stated by ${e.eventStart.sourceId ?: "its source"}).")
        }
    }
}
