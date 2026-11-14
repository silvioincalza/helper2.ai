package ai.helper2.guide.steps

import ai.helper2.guide.AnswerLimits
import ai.helper2.guide.Candidate
import ai.helper2.guide.EvidenceGate
import ai.helper2.guide.ExplainedSentence
import ai.helper2.guide.Explanation
import ai.helper2.guide.ExplanationProposal
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.ProposedSentence
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.Recommendation
import ai.helper2.guide.RemovedSuggestion
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TrailCheck
import ai.helper2.guide.isSubject
import ai.helper2.guide.ownDescription
import ai.helper2.search.MergedResult
import ai.helper2.search.SearchOutcome
import ai.helper2.search.SearchRequest
import java.time.Clock
import org.slf4j.LoggerFactory

internal class ExplainAnswer(
  private val steps: GuideSteps,
  private val reasonCheck: ReasonCheckStep,
  private val evidence: EvidenceGate,
  private val limits: AnswerLimits,
  private val maxCandidates: Int,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(ExplainAnswer::class.java)

  private companion object {
    const val EXPLAIN_CANDIDATES = 12
    const val RELATED_CARDS = 4
    const val SUBJECT_REASON = "Matches the name you asked about."
    const val RELATED_REASON = "Related search result."
  }

  fun answer(
    q: Question,
    r: Reading,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    val subject = r.subjects.firstOrNull() ?: r.query
    val outcome = steps.stepNeededToAnswer(q, "search") { searchSubject(q, r, subject) }
    namesakes(r, outcome)?.let {
      return askBack(clock, r, it, outcome, progress)
    }
    val candidates = quotableCandidates(outcome, subject)
    progress.retrieved(candidates.size, outcome)
    logCandidates(candidates, subject)
    if (candidates.isEmpty()) return noCandidates(subject, r, outcome)
    val cards = SubjectCards(subject, cardsOf(candidates, subject), candidates.size, outcome, r)
    if (model == null) return cards.unexplained()
    val proposal =
      try {
        steps.modelStep(q, "explain") { model.explain(q.need, subject, candidates) }
      } catch (e: RuntimeException) {
        log.warn("guide explain failed: ${failure(e)}")
        asked.modelFailed(e)
        return cards.unexplained().withNotices(outOfTimeNotice(e))
      }
    progress.chosen(proposal.sentences.size)
    return explained(q, progress, model, proposal, candidates, cards)
  }

  private fun searchSubject(q: Question, r: Reading, subject: String): SearchOutcome =
    steps.find(
      q,
      r,
      SearchRequest(text = subject, path = q.path, size = maxCandidates, webQuery = r.webQuery),
      planned = r.subjects.isEmpty(),
    )

  private fun quotableCandidates(outcome: SearchOutcome, subject: String): List<Candidate> =
    outcome.results
      .filterNot { it.ownDescription.isNullOrBlank() }
      .sortedWith(
        compareByDescending<MergedResult> { isSubject(it, subject) }
          .thenByDescending { it.type in WEB_ANNOUNCEMENTS }
      )
      .take(EXPLAIN_CANDIDATES)
      .mapIndexed { i, res -> Candidate("c${i + 1}", res) }

  private fun logCandidates(candidates: List<Candidate>, subject: String) {
    if (log.isDebugEnabled)
      log.debug(
        "guide explain candidates: {}",
        candidates.map {
          "${it.key} ${it.result.type} ${it.result.title.take(60)} subject=${isSubject(it.result, subject)} desc=${it.result.description?.length ?: 0}"
        },
      )
  }

  private fun noCandidates(subject: String, r: Reading, outcome: SearchOutcome) =
    GuideAnswer(
      GuideStatus.NO_CANDIDATES,
      subject,
      emptyList(),
      !r.modelFailed,
      0,
      0,
      outcome,
      clock.instant(),
      r.constraints,
    )

  private fun cardsOf(candidates: List<Candidate>, subject: String): List<Candidate> {
    val own = candidates.firstOrNull { isSubject(it.result, subject) }
    return listOfNotNull(own) +
      candidates.filter { it !== own }.take(RELATED_CARDS - listOfNotNull(own).size)
  }

  private fun explained(
    q: Question,
    progress: GuideProgress,
    model: GuideModel,
    proposal: ExplanationProposal,
    candidates: List<Candidate>,
    cards: SubjectCards,
  ): GuideAnswer {
    val byKey = candidates.associateBy { it.key }
    val gated = steps.step(q, "validate") { quotedSentences(proposal, byKey) }
    val kept = gated.kept
    val rejected = gated.rejected
    progress.validated(kept.size, rejected)
    q.counts +=
      "candidates=${candidates.size} proposed=${proposal.sentences.size} kept=${kept.size} rejected=$rejected"
    val claims = claimsOf(kept, byKey)
    val verdicts =
      if (claims.isEmpty()) ReasonVerdicts.NONE
      else reasonCheck.verdicts(q, model, claims, progress)
    val sentences = reasonChecked(kept, verdicts, byKey, gated.removed)
    return cards
      .explained(
        statusOf(sentences, kept, rejected),
        Explanation(sentences).takeIf { sentences.isNotEmpty() },
        rejected,
      )
      .copy(trail = ResearchTrail(removed = gated.removed))
  }

  private fun quotedSentences(
    proposal: ExplanationProposal,
    byKey: Map<String, Candidate>,
  ): GatedSentences {
    var rejected = 0
    val removed = mutableListOf<RemovedSuggestion>()
    val kept =
      proposal.sentences
        .mapNotNull { p ->
          val c = byKey[p.candidateKey.trim()]
          val check = c?.let {
            evidence.claimProblem(p.text, p.quote, it, limits.maxSentenceChars)?.trailCheck
          }
          val ok = c != null && check == null
          if (!ok) rejected++
          if (c != null && check != null) removed += RemovedSuggestion(c.result.title, check)
          if (ok) c!!.key to p else null
        }
        .take(limits.maxSentences)
    return GatedSentences(kept, rejected, removed)
  }

  private fun claimsOf(
    kept: List<Pair<String, ProposedSentence>>,
    byKey: Map<String, Candidate>,
  ): List<ReasonClaim> = kept.mapIndexed { i, (key, p) ->
    ReasonClaim("e${i + 1}", p.text.trim(), p.quote.trim(), byKey.getValue(key).text, emptyList())
  }

  private fun reasonChecked(
    kept: List<Pair<String, ProposedSentence>>,
    verdicts: ReasonVerdicts,
    byKey: Map<String, Candidate>,
    removed: MutableList<RemovedSuggestion>,
  ): List<ExplainedSentence> = kept.mapIndexedNotNull { i, (key, p) ->
    when (val v = verdicts["e${i + 1}"] ?: ReasonCheck.NOT_CHECKED) {
      ReasonCheck.REMOVED -> {
        removed +=
          RemovedSuggestion(byKey.getValue(key).result.title, TrailCheck.REASON_NOT_SUPPORTED)
        null
      }
      else -> ExplainedSentence(p.text.trim(), p.quote.trim(), byKey.getValue(key).result, v)
    }
  }

  private fun statusOf(
    sentences: List<ExplainedSentence>,
    kept: List<Pair<String, ProposedSentence>>,
    rejected: Int,
  ) =
    when {
      sentences.isEmpty() -> GuideStatus.PARTIAL
      rejected == 0 && sentences.size == kept.size -> GuideStatus.OK
      else -> GuideStatus.PARTIAL
    }

  private class GatedSentences(
    val kept: List<Pair<String, ProposedSentence>>,
    val rejected: Int,
    val removed: MutableList<RemovedSuggestion>,
  )

  private inner class SubjectCards(
    private val subject: String,
    private val cards: List<Candidate>,
    private val candidatesConsidered: Int,
    private val outcome: SearchOutcome,
    private val r: Reading,
  ) {
    fun unexplained() =
      cardsAnswer(GuideStatus.LLM_UNAVAILABLE, modelUsed = false, explanation = null, rejected = 0)

    fun explained(status: GuideStatus, explanation: Explanation?, rejected: Int) =
      cardsAnswer(status, modelUsed = true, explanation = explanation, rejected = rejected)

    private fun cardsAnswer(
      status: GuideStatus,
      modelUsed: Boolean,
      explanation: Explanation?,
      rejected: Int,
    ) =
      GuideAnswer(
        status,
        subject,
        cards.map {
          Recommendation(
            it.result,
            if (isSubject(it.result, subject)) SUBJECT_REASON else RELATED_REASON,
            (it.result.ownDescription ?: it.result.title).take(160),
            aiReasoned = false,
          )
        },
        modelUsed,
        candidatesConsidered,
        rejected,
        outcome,
        clock.instant(),
        r.constraints,
        explanation = explanation,
      )
  }
}
