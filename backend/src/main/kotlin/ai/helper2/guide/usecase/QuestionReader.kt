package ai.helper2.guide.usecase

import ai.helper2.guide.Clarification
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.IntentRules
import ai.helper2.guide.PlannedQueries
import ai.helper2.guide.wantsReading
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/** Reads a question: the plan (or the backup rules alone), grounded in the user's words, with their removals applied. */
internal class QuestionReader(private val clock: Clock) {
    private val log = LoggerFactory.getLogger(QuestionReader::class.java)

    private companion object {
        const val MAX_SUBJECTS = 3
        const val MAX_SUBJECT_CHARS = 60
    }

    fun read(q: Question, progress: GuideProgress, asked: Asked, model: GuideModel?): Reading {
        var modelFailed = model == null
        val plan = if (model == null) null else try {
            // The two-argument form unless the user answered a clarification: a model that only implements it keeps working.
            if (q.clarification == null) model.plan(q.text, q.path) else model.plan(q.text, q.path, q.clarification)
        } catch (e: RuntimeException) {
            log.warn("guide plan failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            modelFailed = true
            null
        }
        val text = q.fullText
        // Only what the user's own words support: the fast model copies values from its examples.
        val subjects = plan?.subjects.orEmpty().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && it.length <= MAX_SUBJECT_CHARS && !GuideValidator.containsLink(it) }
            .filter { text.contains(it, ignoreCase = true) }
            .distinctBy { it.lowercase() }.take(MAX_SUBJECTS)
        val modelIntent = plan?.intent?.takeIf { grounded(it, text, subjects) }
        val intent = when {
            q.drops.intent -> GuideIntent.SUGGEST
            else -> modelIntent ?: IntentRules.intentOf(text) ?: GuideIntent.SUGGEST
        }
        val modelWindow = plan?.window?.takeIf { IntentRules.mentionsTime(text) }
        val planned = if (q.drops.window) null else modelWindow ?: IntentRules.windowOf(text)
        val window = planned?.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        // A period with no other intent is a question about what is new ("AI tools from last month").
        val effective = if (window != null && intent == GuideIntent.SUGGEST) GuideIntent.NEWEST else intent
        val constraints = GuideValidator.normalizedConstraints(plan?.constraints.orEmpty())
            .filter { it.lowercase() !in q.drops.constraints && IntentRules.constraintStated(it, text) }
        val query = plan?.let { PlannedQueries.searchQueryOf(it.searchQuery) }?.ifBlank { null } ?: text
        if (!modelFailed) progress.planned(query, constraints)
        progress.understood(effective, subjects, window)
        q.intentTag = effective.name.lowercase()
        return Reading(
            plan = plan,
            modelFailed = modelFailed,
            intent = effective,
            subjects = subjects,
            window = window,
            newestFirst = effective == GuideIntent.NEWEST && window == null,
            constraints = constraints,
            query = query,
            webQuery = plan?.let { PlannedQueries.webQueryOf(it.webQuery) },
            allowReading = wantsReading(text, plan),
        )
    }

    /** A model intent the user's words support: newness for NEWEST, the named subjects for the subject intents. */
    private fun grounded(intent: GuideIntent, text: String, subjects: List<String>): Boolean = when (intent) {
        GuideIntent.NEWEST -> IntentRules.mentionsNewness(text)
        GuideIntent.COMPARE -> subjects.size >= 2
        GuideIntent.ALTERNATIVES, GuideIntent.WHAT_IS -> subjects.isNotEmpty()
        else -> true
    }

    /**
     * The planner's question back, kept only when the question really is too vague to search (ADR 0032 §3): never
     * after the user answered one, and never when a subject, words that name one, or a period can drive the search.
     */
    fun clarificationOf(q: Question, r: Reading): Clarification? {
        val asked = r.plan?.clarify ?: return null
        if (q.clarification != null) return null
        val compareMissingSubject = r.intent == GuideIntent.COMPARE && r.subjects.size < 2
        val nothingToSearch = r.subjects.isEmpty() && r.window == null && IntentRules.subjectWords(r.query).isBlank()
        return if (compareMissingSubject || nothingToSearch) asked else null
    }
}
