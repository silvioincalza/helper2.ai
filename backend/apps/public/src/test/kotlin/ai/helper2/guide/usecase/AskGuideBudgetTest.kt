package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.ComparisonProposal
import ai.helper2.guide.ExplanationProposal
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideBudgetExhausted
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.MemoryAnswerStore
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.NoticeCode
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskGuideBudgetTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val meters = SimpleMeterRegistry()

  @AfterTest
  fun shutdown() {
    executor.shutdownNow()
  }

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(
          hit(
            "hf",
            "https://huggingface.co/acme/whisper-small",
            title = "whisper-small",
            type = ResourceType.MODEL,
            description = "Speech recognition model you can self-host",
          ),
          hit(
            "hf",
            "https://huggingface.co/acme/parrot",
            title = "parrot",
            rank = 1,
            type = ResourceType.MODEL,
            description = "Text to speech voices for long audio books",
          ),
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val search =
    SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

  private enum class Step {
    PLAN,
    CHOOSE,
    VERIFY,
    COMPARE,
    EXPLAIN,
  }

  private class SpentAt(
    private val spentStep: Step?,
    private val opensMoreToday: Boolean = false,
    private val opensAgainAt: Instant? = null,
    private val plan: GuideSearchPlan = GuideSearchPlan("speech recognition"),
  ) : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    val called = mutableListOf<Step>()

    private fun reach(step: Step) {
      called += step
      if (step == spentStep) throw GuideBudgetExhausted(opensMoreToday, opensAgainAt)
    }

    override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
      reach(Step.PLAN)
      return plan
    }

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      reach(Step.CHOOSE)
      val whisper = candidates.first { it.result.title == "whisper-small" }.key
      return GuideProposal(
        listOf(
          ProposedPick(whisper, "Self-hostable.", "Speech recognition model you can self-host")
        )
      )
    }

    override fun verifyReasons(
      request: Need,
      claims: List<ReasonClaim>,
    ): Map<String, ReasonVerdict> {
      reach(Step.VERIFY)
      return claims.associate { it.key to ReasonVerdict(true) }
    }

    override fun compare(request: Need, subjects: List<Candidate>): ComparisonProposal {
      reach(Step.COMPARE)
      return ComparisonProposal(emptyList())
    }

    override fun explain(
      request: Need,
      subject: String,
      candidates: List<Candidate>,
    ): ExplanationProposal {
      reach(Step.EXPLAIN)
      return ExplanationProposal(emptyList())
    }
  }

  private val need = "speech to text I can self-host"
  private val asker = "10.0.0.1"
  private val quota = ClientQuota(3, clock, QuotaKeys("test-secret", clock))
  private val cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore())

  private fun guide(model: GuideModel) =
    AskGuide(search, model, clock, cache = cache, meters = meters, quota = quota)

  private fun questions(outcome: String) =
    meters.counter("helper2.guide.questions", "outcome", outcome).count()

  private val comparePlan =
    GuideSearchPlan(
      "whisper-small parrot",
      emptyList(),
      false,
      "",
      GuideIntent.COMPARE,
      listOf("whisper-small", "parrot"),
    )

  private val explainPlan =
    GuideSearchPlan(
      "whisper-small",
      emptyList(),
      false,
      "",
      GuideIntent.WHAT_IS,
      listOf("whisper-small"),
    )

  @Test
  fun `a budget spent at the plan gives the top search results and says the guide can answer no more today`() {
    val model = SpentAt(Step.PLAN)

    val answer = guide(model).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertTrue(answer.recommendations.isNotEmpty())
    assertTrue(answer.recommendations.none { it.aiReasoned })
    val notice = answer.notices.single()
    assertEquals(NoticeCode.GUIDE_BUDGET_SPENT, notice.code)
    assertEquals(
      "The AI guide has answered as many questions as it can today; showing search results instead. AI answers are back after 00:00 UTC.",
      notice.message,
    )
    assertEquals(listOf(Step.PLAN), model.called, "no other model call is tried")
    assertEquals(1.0, questions("budget_exhausted"))
  }

  @Test
  fun `a budget spent while the model chooses gives the top search results with the notice`() {
    val answer = guide(SpentAt(Step.CHOOSE)).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf("whisper-small", "parrot"), answer.recommendations.map { it.result.title })
    assertEquals(listOf(NoticeCode.GUIDE_BUDGET_SPENT), answer.notices.map { it.code })
    assertEquals(1.0, questions("budget_exhausted"))
  }

  @Test
  fun `a budget spent while the model compares gives the subjects found with the notice`() {
    val answer =
      guide(SpentAt(Step.COMPARE, plan = comparePlan))
        .ask("compare whisper-small and parrot", null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf("whisper-small", "parrot"), answer.recommendations.map { it.result.title })
    assertEquals(listOf(NoticeCode.GUIDE_BUDGET_SPENT), answer.notices.map { it.code })
  }

  @Test
  fun `a budget spent while the model explains gives the cards found with the notice`() {
    val answer =
      guide(SpentAt(Step.EXPLAIN, plan = explainPlan))
        .ask("what is whisper-small", null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertTrue(answer.recommendations.isNotEmpty())
    assertEquals(listOf(NoticeCode.GUIDE_BUDGET_SPENT), answer.notices.map { it.code })
  }

  @Test
  fun `a budget spent at the reason check keeps the validated picks, not checked and without a notice`() {
    val answer = guide(SpentAt(Step.VERIFY)).ask(need, null, client = asker)

    assertEquals(GuideStatus.OK, answer.status)
    val pick = answer.recommendations.single()
    assertTrue(pick.aiReasoned)
    assertEquals(ReasonCheck.NOT_CHECKED, pick.reasonCheck)
    assertTrue(answer.notices.isEmpty())
    assertEquals(1.0, questions("answered"))
    assertEquals(0.0, questions("budget_exhausted"))
  }

  @Test
  fun `a spent part of the day says when the next part opens, in the message and as a time`() {
    val nextPart = Instant.parse("2026-11-09T14:00:00Z")

    val answer =
      guide(SpentAt(Step.CHOOSE, opensMoreToday = true, opensAgainAt = nextPart))
        .ask(need, null, client = asker)

    val notice = answer.notices.single()
    assertEquals(NoticeCode.GUIDE_BUDGET_SPENT, notice.code)
    assertEquals(
      "The AI guide has answered as many questions as it can for now; showing search results instead. AI answers are back at 14:00 UTC.",
      notice.message,
    )
    assertEquals(nextPart, notice.retryAt)
  }

  @Test
  fun `a spent day carries the next UTC midnight as its time`() {
    val midnight = Instant.parse("2026-11-10T00:00:00Z")

    val notice =
      guide(SpentAt(Step.PLAN, opensAgainAt = midnight))
        .ask(need, null, client = asker)
        .notices
        .single()

    assertTrue(notice.message.endsWith("AI answers are back after 00:00 UTC."))
    assertEquals(midnight, notice.retryAt)
  }

  @Test
  fun `a spent part of the day with no known time says to try again later today`() {
    val notice =
      guide(SpentAt(Step.CHOOSE, opensMoreToday = true))
        .ask(need, null, client = asker)
        .notices
        .single()

    assertTrue(notice.message.endsWith("Try again later today."))
    assertEquals(null, notice.retryAt)
  }

  @Test
  fun `a client over its daily questions is told the next UTC midnight`() {
    val guide = guide(SpentAt(spentStep = null))
    repeat(3) { guide.ask("$need $it", null, client = asker) }

    val refused = guide.ask("one more question about speech", null, client = asker)

    val notice = refused.notices.single { it.code == NoticeCode.GUIDE_DAILY_QUOTA }
    assertEquals(NOW.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS), notice.retryAt)
  }

  @Test
  fun `the notice gives no number of calls`() {
    val messages =
      listOf(false, true).map {
        guide(SpentAt(Step.PLAN, opensMoreToday = it))
          .ask("$need $it", null, client = asker)
          .notices
          .single()
          .message
      }

    assertTrue(messages.none { m -> m.replace("00:00", "").any(Char::isDigit) })
    assertTrue(messages.none { m -> "call" in m })
  }

  @Test
  fun `an answer refused by the budget is free for the asker and not cached`() {
    listOf(Step.PLAN, Step.CHOOSE).forEach { guide(SpentAt(it)).ask(need, null, client = asker) }

    assertEquals(3, quota.left(asker), "a question the model did not answer is given back")
    val again = SpentAt(spentStep = null)
    val answered = guide(again).ask(need, null, client = asker)
    assertEquals(listOf(Step.PLAN, Step.CHOOSE, Step.VERIFY), again.called, "asked afresh")
    assertTrue(answered.notices.isEmpty())
    assertEquals(2, quota.left(asker))
  }

  @Test
  fun `a model that fails for another reason gives the top results without the budget notice`() {
    val down =
      object : GuideModel by SpentAt(spentStep = null) {
        override fun choose(
          request: Need,
          candidates: List<Candidate>,
          constraints: List<String>,
        ): GuideProposal = throw IllegalStateException("down")
      }

    val answer = guide(down).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertFalse(answer.notices.any { it.code == NoticeCode.GUIDE_BUDGET_SPENT })
  }
}
