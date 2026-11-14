package ai.helper2.guide.usecase

import ai.helper2.guide.AnswerLimits
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideBusy
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.LEARN_TYPES
import ai.helper2.guide.ModelActivity
import ai.helper2.guide.ReadingKey
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.SimilarQuestions
import ai.helper2.guide.WorkGate
import ai.helper2.guide.hasAiAnswer
import ai.helper2.guide.sha256Hex
import ai.helper2.guide.steps.Asked
import ai.helper2.guide.steps.CompareAnswer
import ai.helper2.guide.steps.DateAnswer
import ai.helper2.guide.steps.ExplainAnswer
import ai.helper2.guide.steps.GuideSteps
import ai.helper2.guide.steps.PickAnswer
import ai.helper2.guide.steps.Question
import ai.helper2.guide.steps.QuestionReader
import ai.helper2.guide.steps.Reading
import ai.helper2.guide.steps.ReasonCheckStep
import ai.helper2.guide.steps.Retrieval
import ai.helper2.guide.steps.askBack
import ai.helper2.guide.steps.budgetSpentNotice
import ai.helper2.guide.steps.readingFilter
import ai.helper2.guide.steps.withNotices
import ai.helper2.search.Asker
import ai.helper2.search.Feature
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchOutcome
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.Presence
import ai.helper2.shared.SingleFlight
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory

class AskGuide(
  private val search: SearchResources,
  private val model: GuideModel?,
  private val clock: Clock,
  private val maxCandidates: Int = 24,
  private val validator: GuideValidator = GuideValidator(),
  private val cache: GuideAnswerCache? = null,
  private val observations: ObservationRegistry = ObservationRegistry.NOOP,
  private val meters: MeterRegistry = SimpleMeterRegistry(),
  private val quota: ClientQuota? = null,
  private val subjectSearchDeadline: Duration = SUBJECT_SEARCH_DEADLINE,
  private val limits: AnswerLimits = AnswerLimits.DEFAULT,
  private val similar: SimilarQuestions? = null,
  private val stopsWhenNobodyWaits: Boolean = false,
) {
  val answersWithModel: Boolean
    get() = model != null

  private val log = LoggerFactory.getLogger(AskGuide::class.java)

  val configHash: String =
    sha256Hex(
        listOf(
            model?.provider,
            model?.fastModel,
            model?.reasoningModel,
            model?.configFingerprint,
            validator.fingerprint,
            limits.fingerprint,
            maxCandidates,
          )
          .joinToString("\u0000")
      )
      .take(16)

  private val singleFlight = SingleFlight<GuideAnswer>()

  companion object {
    private val DAILY_QUOTA_NOTICE =
      Notice(
        NoticeCode.GUIDE_DAILY_QUOTA,
        "You have used today's AI answers; showing the top search results. AI answers are back after 00:00 UTC.",
      )

    private val NETWORK_QUOTA_NOTICE =
      Notice(
        NoticeCode.GUIDE_NETWORK_QUOTA,
        "Your network has used today's AI answers; showing the top search results. AI answers are back after 00:00 UTC.",
      )

    @JvmField val SUBJECT_SEARCH_DEADLINE: Duration = Duration.ofSeconds(10)

    private val NO_INTERPRETATION = QueryAnalyzer().analyze("")
  }

  private val steps = GuideSteps(search, observations, clock)
  private val reader = QuestionReader(clock)
  private val reasonCheck = ReasonCheckStep(steps, validator)
  private val picks = PickAnswer(steps, reasonCheck, validator, maxCandidates, clock, meters)
  private val comparison =
    CompareAnswer(
      steps,
      picks,
      reasonCheck,
      validator.evidenceGate,
      limits,
      clock,
      subjectSearchDeadline,
    )
  private val explanation =
    ExplainAnswer(steps, reasonCheck, validator.evidenceGate, limits, maxCandidates, clock)
  private val dates = DateAnswer(steps, maxCandidates, clock)

  @JvmOverloads
  fun ask(
    request: String,
    path: AudiencePath?,
    progress: GuideProgress = GuideProgress.NONE,
    client: String? = null,
    clarification: String? = null,
    drops: Drops = Drops.NONE,
    categories: Set<String> = emptySet(),
    gate: WorkGate = WorkGate.OPEN,
    presence: Presence? = null,
  ): GuideAnswer {
    val observation = Observation.createNotStarted("helper2.guide.ask", observations).start()
    val tally = Tally()
    val asker = client?.let { Asker(Feature.GUIDE, it) }
    val question = Question(request.trim(), path, clarification?.trim(), drops, asker, categories)
    try {
      observation.openScope().use {
        return answerOnce(question, progress, client, gate, countedIfQuestionsStop(presence), tally)
          .forAsker(asker)
      }
    } catch (e: Throwable) {
      observation.error(e)
      if (e is InterruptedException) tally.outcome = "timeout"
      if (e is GuideBusy) tally.outcome = "busy"
      if (e is SingleFlight.Abandoned) tally.outcome = "abandoned"
      throw e
    } finally {
      recordOutcome(observation, tally, question)
    }
  }

  private fun countedIfQuestionsStop(presence: Presence?) = presence.takeIf { stopsWhenNobodyWaits }

  private class Tally(var outcome: String = "error", var cacheResult: String = "miss")

  private fun answerOnce(
    q: Question,
    progress: GuideProgress,
    client: String?,
    gate: WorkGate,
    presence: Presence?,
    tally: Tally,
  ): GuideAnswer {
    val key = cacheKey(q)
    cache?.get(key)?.let {
      tally.cacheResult = "exact"
      tally.outcome = "cached"
      return it
    }
    singleFlight.follow(key, presence)?.let {
      tally.outcome = "shared"
      return it
    }
    if (!gate.enter()) throw GuideBusy()
    val asked = Asked()
    val reservation = client?.let { quota?.reserve(it) }
    if (client != null && quota != null && reservation == null)
      return searchOnly(q, progress, asked, tally, refusalNotice(client))
    val turn = QuotaTurn(reservation)
    try {
      val outcome =
        singleFlight.run(
          key,
          presence,
          beforeFollowing = {
            gate.leave()
            turn.free = FreeBecause.SHARED
            tally.outcome = "shared"
          },
        ) {
          answerAndStore(q, key, progress, asked, tally)
        }
      if (outcome is SingleFlight.Led)
        turn.free = if (ClientQuota.spendsAQuestion(outcome.value)) null else FreeBecause.NO_PICK
      return outcome.value
    } catch (e: SingleFlight.Abandoned) {
      log.info("guide question abandoned: nobody waits for its answer any more")
      turn.abandoned()
      throw e
    } finally {
      turn.settle()
    }
  }

  private fun cacheKey(q: Question): String {
    val config =
      if (q.categories.isEmpty()) configHash
      else configHash + "|categories=" + q.categories.sorted().joinToString(",")
    return GuideAnswerCache.key(q.text, q.path, config, q.clarification, q.drops)
  }

  private fun refusalNotice(client: String): Notice {
    val refused =
      if (quota?.refusedForTheNetwork(client) == true) NETWORK_QUOTA_NOTICE else DAILY_QUOTA_NOTICE
    return refused.copy(retryAt = nextUtcDay())
  }

  private fun nextUtcDay(): Instant =
    clock.instant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant()

  private fun searchOnly(
    q: Question,
    progress: GuideProgress,
    asked: Asked,
    tally: Tally,
    notice: Notice,
  ): GuideAnswer {
    log.info("guide quota reached ({}): answering from search only", notice.code)
    tally.outcome =
      if (notice.code == NoticeCode.GUIDE_NETWORK_QUOTA) "network_quota" else "client_quota"
    val answer = answer(q, progress, asked, model = null)
    return answer.copy(notices = answer.notices + notice)
  }

  private fun answerAndStore(
    q: Question,
    key: String,
    progress: GuideProgress,
    asked: Asked,
    tally: Tally,
  ): GuideAnswer {
    val answer =
      answer(q, progress, asked, model).withNotices(budgetSpentNotice(asked)).withoutAsker()
    val complete = !asked.outOfTime && !asked.budgetExhausted
    val expiresAt = if (complete) cache?.put(key, answer.withoutTheQuestion(q)) else null
    if (expiresAt != null && !asked.reused && answer.hasAiAnswer())
      asked.probe?.let { similar?.remember(it, key, expiresAt) }
    if (asked.reused) tally.cacheResult = "similar"
    tally.outcome = if (asked.reused) "cached" else outcomeOf(answer, asked)
    return answer
  }

  private enum class FreeBecause {
    NO_PICK,
    FAILED,
    SHARED,
    ABANDONED,
  }

  private inner class QuotaTurn(private val reservation: ClientQuota.Reservation?) {
    var free: FreeBecause? = FreeBecause.FAILED

    fun abandoned() {
      if (free == FreeBecause.FAILED) free = FreeBecause.ABANDONED
    }

    fun settle() {
      val given = reservation ?: return
      val because = free ?: return
      val interrupted = Thread.interrupted()
      try {
        val outcome = quota?.giveBack(given) ?: return
        meters
          .counter(
            "helper2.guide.quota.give_back",
            "reason",
            because.name.lowercase(),
            "outcome",
            outcome.name.lowercase(),
          )
          .increment()
      } finally {
        if (interrupted) Thread.currentThread().interrupt()
      }
    }
  }

  private fun recordOutcome(observation: Observation, tally: Tally, question: Question) {
    val recorded = if (Thread.currentThread().isInterrupted) "timeout" else tally.outcome
    observation.lowCardinalityKeyValue("outcome", recorded)
    observation.stop()
    if (cache != null)
      meters.counter("helper2.guide.cache", "result", tally.cacheResult).increment()
    meters.counter("helper2.guide.questions", "outcome", recorded).increment()
    meters.counter("helper2.guide.intents", "intent", question.intentTag).increment()
  }

  private fun GuideAnswer.forAsker(asker: Asker?) =
    copy(webSearches = asker?.let(this@AskGuide.search::webSearchesLeft))

  private fun GuideAnswer.withoutAsker() =
    copy(search = search.copy(request = search.request.copy(asker = null)))

  private fun GuideAnswer.withoutTheQuestion(q: Question): GuideAnswer {
    val ownWords = !q.isPlannerText(search.interpretation.original)
    return copy(
      searchQuery = searchQuery.takeIf(q::isPlannerText) ?: "",
      search =
        search.copy(
          request = search.request.copy(text = "", webQuery = null),
          interpretation = if (ownWords) NO_INTERPRETATION else search.interpretation,
          notices = search.notices.filter { it.code != NoticeCode.QUERY_EXPANDED },
        ),
    )
  }

  private fun outcomeOf(answer: GuideAnswer, asked: Asked) =
    when {
      asked.budgetExhausted -> "budget_exhausted"
      answer.status == GuideStatus.NEEDS_CLARIFICATION -> "clarify"
      answer.status == GuideStatus.LLM_UNAVAILABLE -> "model_unavailable"
      answer.status == GuideStatus.NO_CANDIDATES -> "no_candidates"
      answer.recommendations.any { it.aiReasoned } ||
        answer.comparison != null ||
        answer.explanation != null -> "answered"
      else -> "degraded"
    }

  private fun answer(
    q: Question,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    try {
      return ModelActivity.reportedTo(progress) { answerByIntent(q, progress, asked, model) }
        .also { asked.outOfTime = cutAtTheDeadline() }
    } finally {
      log.info(
        (listOf("guide timings: intent=${q.intentTag}") +
            q.timings.map { (step, ms) -> "$step=${ms}ms" } +
            listOfNotNull(model?.let { "fast=${it.fastModel} reasoning=${it.reasoningModel}" }) +
            q.counts)
          .joinToString(" ")
      )
    }
  }

  private fun cutAtTheDeadline(): Boolean {
    val cancelled = steps.questionIsCancelled()
    Thread.interrupted()
    if (cancelled) log.warn("guide question cancelled at its deadline: answering with what it has")
    return cancelled
  }

  private fun similarAnswer(q: Question, r: Reading, asked: Asked): GuideAnswer? {
    if (
      similar == null ||
        cache == null ||
        q.clarification != null ||
        !q.drops.isEmpty ||
        q.categories.isNotEmpty() ||
        r.plan == null ||
        r.modelFailed
    )
      return null
    val probe = similar.probe(q.text, configHash, q.path, r.key(q)) ?: return null
    asked.probe = probe
    val answer = similar.nearest(probe)?.let(cache::get) ?: return null
    asked.reused = true
    return answer.copy(
      searchQuery = "",
      trail = answer.trail.withoutQueries(),
      notices = answer.notices.map { it.copy(query = null) },
      search =
        answer.search.copy(
          notices = answer.search.notices.filter { it.code != NoticeCode.QUERY_EXPANDED }
        ),
    )
  }

  private fun Reading.key(q: Question): ReadingKey {
    val text = q.text.lowercase()
    val inOrder = subjects.sortedBy { text.indexOf(it.lowercase()) }
    return ReadingKey(
      intent,
      inOrder,
      constraints,
      window,
      allowReading,
      aboutEvent,
      askBackSubject != null,
    )
  }

  private fun answerByIntent(
    q: Question,
    progress: GuideProgress,
    asked: Asked,
    model: GuideModel?,
  ): GuideAnswer {
    val r = steps.stepNeededToAnswer(q, "plan") { reader.read(q, progress, asked, model) }
    reader.clarificationOf(q, r)?.let { c ->
      return askBack(clock, r, c, SearchOutcome.empty(clock.instant()), progress)
        .copy(intent = r.intent, subjects = r.subjects, window = r.window, planned = true)
    }
    similarAnswer(q, r, asked)?.let {
      return it
    }
    val usable = if (r.modelFailed) null else model
    val answer =
      when (r.intent) {
        GuideIntent.COMPARE -> comparison.answer(q, r, progress, asked, usable)
        GuideIntent.WHAT_IS -> explanation.answer(q, r, progress, asked, usable)
        GuideIntent.WHEN -> dates.answer(q, r, progress)
        GuideIntent.ALTERNATIVES -> picks.alternatives(q, r, progress, asked, usable)
        GuideIntent.NEWEST -> picks.newest(q, r, progress, asked, usable)
        GuideIntent.LEARN -> picks.picks(q, r, progress, asked, usable, learnRetrieval(r))
        GuideIntent.HOW_TO,
        GuideIntent.SUGGEST ->
          picks.picks(
            q,
            r,
            progress,
            asked,
            usable,
            Retrieval(r.query, readingFilter(r), askBack = true),
          )
      }
    val planned = r.plan != null && !r.modelFailed
    val trail = ResearchTrail.of(if (planned) q.queriesRun() else emptyList(), answer.trail.removed)
    return answer.copy(
      intent = r.intent,
      subjects = r.subjects,
      window = r.window,
      webFreshUntil = q.webFreshUntil,
      planned = planned,
      trail = trail,
    )
  }

  private fun learnRetrieval(r: Reading) =
    Retrieval(
      r.query,
      SearchFilters(
        types = LEARN_TYPES,
        publishedAfter = r.window?.from,
        publishedBefore = r.window?.to,
      ),
    )
}
