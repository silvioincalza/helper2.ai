package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.ComparisonProposal
import ai.helper2.guide.ExplanationProposal
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideUnavailable
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
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskGuideDeadlineTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val meters = SimpleMeterRegistry()

  @AfterTest
  fun shutdown() {
    Thread.interrupted()
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

  private class CutAt(
    private val cutStep: Step?,
    private val category: String = GuideUnavailable.CANCELLED,
    private val plan: GuideSearchPlan = GuideSearchPlan("speech recognition"),
  ) : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    val called = mutableListOf<Step>()

    private fun reach(step: Step) {
      called += step
      if (step != cutStep) return
      if (category == GuideUnavailable.CANCELLED) Thread.currentThread().interrupt()
      throw GuideUnavailable("the question ran out of time in $step", category)
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
  private val store = MemoryAnswerStore()
  private val quota = ClientQuota(3, clock, QuotaKeys("test-secret", clock))

  private fun guide(model: GuideModel) =
    AskGuide(
      search,
      model,
      clock,
      cache = GuideAnswerCache(Duration.ofHours(24), clock, store),
      meters = meters,
      quota = quota,
    )

  private fun questions(outcome: String) =
    meters.counter("helper2.guide.questions", "outcome", outcome).count()

  @Test
  fun `cancelled during the reason check, the answer is the validated picks with reasons not checked`() {
    val model = CutAt(Step.VERIFY)

    val answer = guide(model).ask(need, null, client = asker)

    assertEquals(GuideStatus.OK, answer.status)
    val pick = answer.recommendations.single()
    assertEquals("whisper-small", pick.result.title)
    assertTrue(pick.aiReasoned)
    assertEquals("Self-hostable.", pick.reason)
    assertEquals(ReasonCheck.NOT_CHECKED, pick.reasonCheck)
    assertTrue(answer.notices.isEmpty(), "the same shape as a reason check that failed")
    assertFalse(Thread.currentThread().isInterrupted, "the answer can still be sent")
    assertEquals(1.0, questions("answered"))
    assertEquals(0.0, questions("timeout"))
  }

  @Test
  fun `an answer cut during the reason check is not cached and spends a question like any unchecked answer`() {
    val cut = guide(CutAt(Step.VERIFY))

    cut.ask(need, null, client = asker)
    Thread.interrupted()

    assertEquals(2, quota.left(asker))
    val again = CutAt(cutStep = null)
    val checked = guide(again).ask(need, null, client = asker)
    assertEquals(listOf(Step.PLAN, Step.CHOOSE, Step.VERIFY), again.called, "asked afresh")
    assertEquals(ReasonCheck.SUPPORTED, checked.recommendations.single().reasonCheck)
  }

  @Test
  fun `cancelled while the model chooses, the answer is the top search results and says the guide ran out of time`() {
    val model = CutAt(Step.CHOOSE)

    val answer = guide(model).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf("whisper-small", "parrot"), answer.recommendations.map { it.result.title })
    assertTrue(answer.recommendations.none { it.aiReasoned })
    assertEquals(listOf(NoticeCode.GUIDE_OUT_OF_TIME), answer.notices.map { it.code })
    assertEquals(listOf(Step.PLAN, Step.CHOOSE), model.called, "no reason check is started")
    assertFalse(Thread.currentThread().isInterrupted, "the answer can still be sent")
    assertEquals(1.0, questions("model_unavailable"))
  }

  @Test
  fun `an answer cut while the model chooses is free for the asker and not cached`() {
    guide(CutAt(Step.CHOOSE)).ask(need, null, client = asker)

    assertEquals(3, quota.left(asker))
    val again = CutAt(cutStep = null)
    guide(again).ask(need, null, client = asker)
    assertEquals(listOf(Step.PLAN, Step.CHOOSE, Step.VERIFY), again.called, "asked afresh")
  }

  @Test
  fun `a choose call that did not start for lack of time gives the same answer and notice`() {
    val answer =
      guide(CutAt(Step.CHOOSE, GuideUnavailable.DEADLINE)).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf(NoticeCode.GUIDE_OUT_OF_TIME), answer.notices.map { it.code })
  }

  @Test
  fun `a model that fails for another reason gives the top results without the out-of-time notice`() {
    val answer = guide(CutAt(Step.CHOOSE, "timeout")).ask(need, null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertTrue(answer.notices.isEmpty())
  }

  @Test
  fun `cancelled after the model chose and before the reason check, the picks are still validated and answered`() {
    val model =
      object : GuideModel by CutAt(cutStep = null) {
        val inner = CutAt(cutStep = null)

        override fun choose(
          request: Need,
          candidates: List<Candidate>,
          constraints: List<String>,
        ): GuideProposal =
          inner.choose(request, candidates, constraints).also {
            Thread.currentThread().interrupt()
          }

        override fun verifyReasons(
          request: Need,
          claims: List<ReasonClaim>,
        ): Map<String, ReasonVerdict> = error("no call is started for a cancelled question")
      }

    val pick = guide(model).ask(need, null, client = asker).recommendations.single()

    assertTrue(pick.aiReasoned)
    assertEquals(ReasonCheck.NOT_CHECKED, pick.reasonCheck)
  }

  @Test
  fun `cancelled before the search is done, there is nothing to answer with`() {
    val model = CutAt(Step.PLAN)

    assertFailsWith<InterruptedException> { guide(model).ask(need, null, client = asker) }

    assertEquals(listOf(Step.PLAN), model.called)
    assertTrue(Thread.interrupted(), "the cancellation stays visible to the caller")
    assertEquals(1.0, questions("timeout"))
    assertEquals(3, quota.left(asker), "a question with no answer is given back")
  }

  @Test
  fun `cancelled while the model compares, the answer is the subjects found and says the guide ran out of time`() {
    val plan =
      GuideSearchPlan(
        "whisper-small parrot",
        emptyList(),
        false,
        "",
        GuideIntent.COMPARE,
        listOf("whisper-small", "parrot"),
      )

    val answer =
      guide(CutAt(Step.COMPARE, plan = plan))
        .ask("compare whisper-small and parrot", null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf(NoticeCode.GUIDE_OUT_OF_TIME), answer.notices.map { it.code })
    assertEquals(listOf("whisper-small", "parrot"), answer.recommendations.map { it.result.title })
  }

  @Test
  fun `cancelled while the model explains, the answer is the cards found and says the guide ran out of time`() {
    val plan =
      GuideSearchPlan(
        "whisper-small",
        emptyList(),
        false,
        "",
        GuideIntent.WHAT_IS,
        listOf("whisper-small"),
      )

    val answer =
      guide(CutAt(Step.EXPLAIN, plan = plan)).ask("what is whisper-small", null, client = asker)

    assertEquals(GuideStatus.LLM_UNAVAILABLE, answer.status)
    assertEquals(listOf(NoticeCode.GUIDE_OUT_OF_TIME), answer.notices.map { it.code })
    assertTrue(answer.recommendations.isNotEmpty())
  }
}
