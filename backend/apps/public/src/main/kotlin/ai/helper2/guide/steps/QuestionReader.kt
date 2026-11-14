package ai.helper2.guide.steps

import ai.helper2.guide.Clarification
import ai.helper2.guide.DateWindow
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.PlannedQueries
import ai.helper2.guide.QuestionDirections
import ai.helper2.guide.wantsReading
import ai.helper2.shared.LinkText
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

internal class QuestionReader(private val clock: Clock) {
  private val log = LoggerFactory.getLogger(QuestionReader::class.java)

  private companion object {
    const val MAX_SUBJECTS = 3
    const val MAX_SUBJECT_CHARS = 60
  }

  fun read(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): Reading {
    val planCall = askPlanner(q, asked, model)
    val plan = planCall.plan
    val modelFailed = planCall.failed
    val text = q.fullText
    val subjects = groundedSubjects(plan, text)
    val window = windowOf(q, plan, text)
    val intent = intentOf(q, plan, text, subjects, window)
    val constraints = constraintsOf(q, plan, text)
    val planned = plan?.plannedQuery
    planned?.let {
      q.plannerWrote(it)
      q.plannerWrote(IntentRules.subjectWords(it))
    }
    val query = planned ?: text
    val aboutEvent =
      intent == GuideIntent.WHEN && (IntentRules.namesEvent(text) || IntentRules.namesEvent(query))
    if (!modelFailed) progress.planned(planned.orEmpty(), constraints)
    progress.understood(intent, subjects, window)
    q.intentTag = intent.name.lowercase()
    return Reading(
      plan = plan,
      modelFailed = modelFailed,
      intent = intent,
      subjects = subjects,
      window = window,
      constraints = constraints,
      query = query,
      webQuery = plan?.let { PlannedQueries.webQueryOf(it.webQuery) },
      allowReading = wantsReading(text, plan),
      aboutEvent = aboutEvent,
      askBackSubject = askBackSubject(q, modelFailed, intent, subjects, aboutEvent),
    )
  }

  private fun askPlanner(q: Question, asked: Asked, model: GuideModel?): PlanCall {
    if (model == null) return PlanCall(plan = null, failed = true)
    return try {
      val plan =
        if (q.clarification == null) model.plan(q.text, q.path)
        else model.plan(q.text, q.path, q.clarification)
      PlanCall(plan = plan, failed = false)
    } catch (e: RuntimeException) {
      log.warn("guide plan failed: ${failure(e)}")
      asked.modelFailed(e)
      PlanCall(plan = null, failed = true)
    }
  }

  private fun groundedSubjects(plan: GuideSearchPlan?, text: String): List<String> =
    plan
      ?.subjects
      .orEmpty()
      .map { it.trim().replace(Regex("\\s+"), " ") }
      .filter {
        it.isNotEmpty() && it.length <= MAX_SUBJECT_CHARS && !LinkText.containsLink(it)
      }
      .filter { text.contains(it, ignoreCase = true) }
      .distinctBy { it.lowercase() }
      .take(MAX_SUBJECTS)

  private fun windowOf(q: Question, plan: GuideSearchPlan?, text: String): DateWindow? {
    val modelWindow = plan?.window?.takeIf { IntentRules.mentionsTime(text) }
    val planned = if (q.drops.window) null else modelWindow ?: IntentRules.windowOf(text)
    return planned?.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
  }

  private fun intentOf(
    q: Question,
    plan: GuideSearchPlan?,
    text: String,
    subjects: List<String>,
    window: DateWindow?,
  ): GuideIntent {
    val modelIntent = plan?.intent?.takeIf { grounded(it, text, subjects) }
    val ruleIntent = IntentRules.intentOf(text)
    val read =
      when {
        modelIntent == GuideIntent.SUGGEST && ruleIntent == GuideIntent.NEWEST -> GuideIntent.NEWEST
        else -> modelIntent ?: ruleIntent ?: GuideIntent.SUGGEST
      }
    val withPeriod = if (window != null && read == GuideIntent.SUGGEST) GuideIntent.NEWEST else read
    return if (q.drops.intent) GuideIntent.SUGGEST else withPeriod
  }

  private fun constraintsOf(q: Question, plan: GuideSearchPlan?, text: String): List<String> {
    val need = QuestionDirections.needOf(text)
    return GuideValidator.normalizedConstraints(plan?.constraints.orEmpty()).filter {
      it.lowercase() !in q.drops.constraints &&
        QuestionDirections.isResourceProperty(it) &&
        IntentRules.constraintStated(it, need)
    }
  }

  private class PlanCall(val plan: GuideSearchPlan?, val failed: Boolean)

  private fun askBackSubject(
    q: Question,
    modelFailed: Boolean,
    intent: GuideIntent,
    subjects: List<String>,
    aboutEvent: Boolean,
  ): String? {
    if (q.clarification != null || modelFailed || IntentRules.namesKind(q.text)) return null
    val subject = subjects.singleOrNull() ?: return null
    val aboutTheSubject =
      when (intent) {
        GuideIntent.WHAT_IS,
        GuideIntent.ALTERNATIVES -> true
        GuideIntent.WHEN -> !aboutEvent
        GuideIntent.SUGGEST ->
          IntentRules.subjectWords(q.text.replace(subject, " ", ignoreCase = true)).none {
            it.isLetterOrDigit()
          }
        else -> false
      }
    return subject.takeIf { aboutTheSubject }
  }

  private fun grounded(intent: GuideIntent, text: String, subjects: List<String>): Boolean =
    when (intent) {
      GuideIntent.NEWEST -> IntentRules.mentionsNewness(text)
      GuideIntent.COMPARE -> subjects.size >= 2
      GuideIntent.ALTERNATIVES,
      GuideIntent.WHAT_IS -> subjects.isNotEmpty()
      else -> true
    }

  fun clarificationOf(q: Question, r: Reading): Clarification? {
    val asked = r.plan?.clarify ?: return null
    if (q.clarification != null) return null
    val compareMissingSubject = r.intent == GuideIntent.COMPARE && r.subjects.size < 2
    val nothingToSearch =
      r.subjects.isEmpty() && r.window == null && IntentRules.subjectWords(r.query).isBlank()
    return if (compareMissingSubject || nothingToSearch) asked else null
  }
}
