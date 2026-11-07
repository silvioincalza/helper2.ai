package ai.helper2.guide.steps

import ai.helper2.guide.Clarification
import ai.helper2.guide.DateWindow
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.PlannedQueries
import ai.helper2.guide.wantsReading
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Reads a question: the plan (or the backup rules alone), grounded in the user's words, with their
 * removals applied.
 */
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
    val query = plan?.let { PlannedQueries.searchQueryOf(it.searchQuery) }?.ifBlank { null } ?: text
    val aboutEvent =
      intent == GuideIntent.WHEN && (IntentRules.namesEvent(text) || IntentRules.namesEvent(query))
    if (!modelFailed) progress.planned(query, constraints)
    progress.understood(intent, subjects, window)
    q.intentTag = intent.name.lowercase()
    return Reading(
      plan = plan,
      modelFailed = modelFailed,
      intent = intent,
      subjects = subjects,
      window = window,
      newestFirst = intent == GuideIntent.NEWEST && window == null,
      constraints = constraints,
      query = query,
      webQuery = plan?.let { PlannedQueries.webQueryOf(it.webQuery) },
      allowReading = wantsReading(text, plan),
      aboutEvent = aboutEvent,
      askBackSubject = askBackSubject(q, modelFailed, intent, subjects, aboutEvent),
    )
  }

  /** The planner's reading; none without a model, or when its call failed. */
  private fun askPlanner(q: Question, asked: Asked, model: GuideModel?): PlanCall {
    if (model == null) return PlanCall(plan = null, failed = true)
    return try {
      // The two-argument form unless the user answered a clarification: a model that only
      // implements it keeps working.
      val plan =
        if (q.clarification == null) model.plan(q.text, q.path)
        else model.plan(q.text, q.path, q.clarification)
      PlanCall(plan = plan, failed = false)
    } catch (e: RuntimeException) {
      log.warn("guide plan failed: ${failure(e)}")
      asked.budgetExhausted = e is GuideBudgetExhausted
      PlanCall(plan = null, failed = true)
    }
  }

  /** Only what the user's own words support: the fast model copies values from its examples. */
  private fun groundedSubjects(plan: GuideSearchPlan?, text: String): List<String> =
    plan
      ?.subjects
      .orEmpty()
      .map { it.trim().replace(Regex("\\s+"), " ") }
      .filter {
        it.isNotEmpty() && it.length <= MAX_SUBJECT_CHARS && !GuideValidator.containsLink(it)
      }
      .filter { text.contains(it, ignoreCase = true) }
      .distinctBy { it.lowercase() }
      .take(MAX_SUBJECTS)

  /** The question's period, resolved to days, unless the user removed it. */
  private fun windowOf(q: Question, plan: GuideSearchPlan?, text: String): DateWindow? {
    val modelWindow = plan?.window?.takeIf { IntentRules.mentionsTime(text) }
    val planned = if (q.drops.window) null else modelWindow ?: IntentRules.windowOf(text)
    return planned?.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
  }

  /** The planner's intent when the user's words support it, else the backup rules'. */
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
        // The planner's SUGGEST is its "nothing special": newness the user's words state still
        // counts ("new MCP servers").
        modelIntent == GuideIntent.SUGGEST && ruleIntent == GuideIntent.NEWEST -> GuideIntent.NEWEST
        else -> modelIntent ?: ruleIntent ?: GuideIntent.SUGGEST
      }
    // A period with no other intent is a question about what is new ("AI tools from last month").
    val withPeriod = if (window != null && read == GuideIntent.SUGGEST) GuideIntent.NEWEST else read
    // The user's removal applies last (ADR 0016 (part was 0032) §2): a dropped intent stays
    // SUGGEST, period or not.
    return if (q.drops.intent) GuideIntent.SUGGEST else withPeriod
  }

  /** The planner's constraints the user stated and did not remove. */
  private fun constraintsOf(q: Question, plan: GuideSearchPlan?, text: String): List<String> =
    GuideValidator.normalizedConstraints(plan?.constraints.orEmpty()).filter {
      it.lowercase() !in q.drops.constraints && IntentRules.constraintStated(it, text)
    }

  /** The plan call's result: [failed] without a model or when the call threw. */
  private class PlanCall(val plan: GuideSearchPlan?, val failed: Boolean)

  /**
   * The subject checked for namesakes (ADR 0016 (part was 0032) §3): the one grounded subject of a
   * question about that thing itself, read by the planner (its question back is AI-written), and
   * never once the user answered a question back or named the kind of thing they mean ("the Gemini
   * model").
   */
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
        // "Gemini" alone, not "a chat UI for Gemini": there the name is context, the task is the
        // subject.
        GuideIntent.SUGGEST ->
          IntentRules.subjectWords(q.text.replace(subject, " ", ignoreCase = true)).none {
            it.isLetterOrDigit()
          }
        else -> false
      }
    return subject.takeIf { aboutTheSubject }
  }

  /**
   * A model intent the user's words support: newness for NEWEST, the named subjects for the subject
   * intents.
   */
  private fun grounded(intent: GuideIntent, text: String, subjects: List<String>): Boolean =
    when (intent) {
      GuideIntent.NEWEST -> IntentRules.mentionsNewness(text)
      GuideIntent.COMPARE -> subjects.size >= 2
      GuideIntent.ALTERNATIVES,
      GuideIntent.WHAT_IS -> subjects.isNotEmpty()
      else -> true
    }

  /**
   * The planner's question back, kept only when the question really is too vague to search (ADR
   * 0016 (part was 0032) §3): never after the user answered one, and never when a subject, words
   * that name one, or a period can drive the search.
   */
  fun clarificationOf(q: Question, r: Reading): Clarification? {
    val asked = r.plan?.clarify ?: return null
    if (q.clarification != null) return null
    val compareMissingSubject = r.intent == GuideIntent.COMPARE && r.subjects.size < 2
    val nothingToSearch =
      r.subjects.isEmpty() && r.window == null && IntentRules.subjectWords(r.query).isBlank()
    return if (compareMissingSubject || nothingToSearch) asked else null
  }
}
