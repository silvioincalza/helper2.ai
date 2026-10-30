package ai.helper2.guide.usecase

import ai.helper2.guide.AnswerLimits
import ai.helper2.guide.Candidate
import ai.helper2.guide.ExplainedSentence
import ai.helper2.guide.Explanation
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.isSubject
import ai.helper2.search.MergedResult
import ai.helper2.search.SearchRequest
import java.time.Clock
import org.slf4j.LoggerFactory

/** WHAT_IS (ADR 0032): a few quoted sentences about the subject, its own card and related ones. */
internal class ExplainAnswer(
    private val steps: GuideSteps,
    private val reasonCheck: ReasonCheckStep,
    private val validator: GuideValidator,
    private val limits: AnswerLimits,
    private val maxCandidates: Int,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ExplainAnswer::class.java)

    private companion object {
        const val EXPLAIN_CANDIDATES = 12
        const val RELATED_CARDS = 4
        /** Not "the subject's own page": a concept ("retrieval augmented generation") has none, only namesakes. */
        const val SUBJECT_REASON = "Matches the name you asked about."
        const val RELATED_REASON = "Related search result."
    }

    /**
     * "what is MCP?": the subject's own entry first, then what our sources say about it; one model call writes a few
     * sentences, each kept only with a quote from one of them. No sentence left: the subject's card alone.
     */
    fun answer(q: Question, r: Reading, progress: GuideProgress, asked: Asked, model: GuideModel?): GuideAnswer {
        val subject = r.subjects.firstOrNull() ?: r.query
        val outcome = steps.step(q, "search") { steps.find(q, r, SearchRequest(text = subject, path = q.path, size = maxCandidates, webQuery = r.webQuery)) }
        // Only what can be quoted: an entry without a description cannot support a sentence. The subject's own entry
        // first, then web explainers (articles, news), which say what a thing is better than projects named after it.
        val ordered = outcome.results.filterNot { it.description.isNullOrBlank() }
            .sortedWith(compareByDescending<MergedResult> { isSubject(it, subject) }.thenByDescending { it.type in WEB_ANNOUNCEMENTS })
            .take(EXPLAIN_CANDIDATES)
        val candidates = ordered.mapIndexed { i, res -> Candidate("c${i + 1}", res) }
        progress.retrieved(candidates.size, outcome)
        if (log.isDebugEnabled) log.debug("guide explain candidates: {}", candidates.map { "${it.key} ${it.result.type} ${it.result.title.take(60)} subject=${isSubject(it.result, subject)} desc=${it.result.description?.length ?: 0}" })
        if (candidates.isEmpty()) {
            return GuideAnswer(GuideStatus.NO_CANDIDATES, subject, emptyList(), null, !r.modelFailed, 0, 0, outcome, clock.instant(), r.constraints)
        }
        // The subject's own card, then up to RELATED_CARDS - 1 related ones, in search order.
        val own = candidates.firstOrNull { isSubject(it.result, subject) }
        val cards = listOfNotNull(own) + candidates.filter { it !== own }.take(RELATED_CARDS - listOfNotNull(own).size)
        fun cardsAnswer(status: GuideStatus, modelUsed: Boolean, explanation: Explanation?, rejected: Int) = GuideAnswer(
            status, subject,
            cards.map {
                Recommendation(it.result, if (isSubject(it.result, subject)) SUBJECT_REASON else RELATED_REASON,
                    (it.result.description ?: it.result.title).take(160), aiReasoned = false)
            },
            null, modelUsed, candidates.size, rejected, outcome, clock.instant(), r.constraints, explanation = explanation,
        )
        if (model == null) return cardsAnswer(GuideStatus.LLM_UNAVAILABLE, false, null, 0)
        val proposal = try {
            steps.step(q, "explain") { model.explain(q.fullText, subject, candidates) }
        } catch (e: RuntimeException) {
            log.warn("guide explain failed: ${failure(e)}")
            asked.budgetExhausted = e is GuideBudgetExhausted
            return cardsAnswer(GuideStatus.LLM_UNAVAILABLE, false, null, 0)
        }
        progress.chosen(proposal.sentences.size)
        val byKey = candidates.associateBy { it.key }
        var rejected = 0
        val kept = steps.step(q, "validate") {
            proposal.sentences.mapNotNull { p ->
                val c = byKey[p.candidateKey.trim()]
                val ok = c != null && p.text.isNotBlank() && p.text.length <= limits.maxSentenceChars && !GuideValidator.containsLink(p.text) &&
                    validator.quoteHolds(p.quote, c.text)
                if (!ok) rejected++
                if (ok) c!!.key to p else null
            }.take(limits.maxSentences)
        }
        progress.validated(kept.size, rejected)
        log.info("guide explain: candidates=${candidates.size} proposed=${proposal.sentences.size} kept=${kept.size} rejected=$rejected")
        val claims = kept.mapIndexed { i, (key, p) -> ReasonClaim("e${i + 1}", p.text.trim(), p.quote.trim(), byKey.getValue(key).text, emptyList()) }
        val verdicts = if (claims.isEmpty()) emptyMap() else reasonCheck.verdicts(q, model, claims, progress)
        val sentences = kept.mapIndexedNotNull { i, (key, p) ->
            when (val v = verdicts["e${i + 1}"] ?: ReasonCheck.NOT_CHECKED) {
                ReasonCheck.REMOVED -> null
                else -> ExplainedSentence(p.text.trim(), p.quote.trim(), byKey.getValue(key).result, v)
            }
        }
        val status = when {
            sentences.isEmpty() -> GuideStatus.PARTIAL
            rejected == 0 && sentences.size == kept.size -> GuideStatus.OK
            else -> GuideStatus.PARTIAL
        }
        return cardsAnswer(status, true, Explanation(sentences).takeIf { sentences.isNotEmpty() }, rejected)
    }
}
