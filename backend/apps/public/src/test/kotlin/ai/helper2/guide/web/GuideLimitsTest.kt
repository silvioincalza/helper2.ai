package ai.helper2.guide.web

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.QuestionDeadline
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.guide.WorkGate
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.platform.llm.LlmSettings
import ai.helper2.platform.resilience.Resilience4jPolicies
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.testing.MdcTraceIds
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class GuideLimitsTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val limits = mutableListOf<GuideLimits>()

  @AfterTest
  fun shutdown() {
    executor.shutdownNow()
    limits.forEach { it.destroy() }
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
            description = "Speech recognition model you can self-host",
          )
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val planning = CountDownLatch(1)

  private val stuck =
    object : GuideModel {
      override val provider = "fake"
      override val fastModel = "fast"
      override val reasoningModel = "reasoning"

      override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
        planning.countDown()
        Thread.sleep(Duration.ofSeconds(30))
        return GuideSearchPlan("speech")
      }

      override fun choose(request: Need, candidates: List<Candidate>, constraints: List<String>) =
        GuideProposal(emptyList())
    }

  private class SlowIn(
    private val slowStep: String,
    private val whenCancelled: () -> Nothing = {
      Thread.currentThread().interrupt()
      throw GuideUnavailable("the question was cancelled", GuideUnavailable.CANCELLED)
    },
  ) : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"

    private fun reach(step: String) {
      if (step != slowStep) return
      try {
        Thread.sleep(Duration.ofSeconds(30))
      } catch (e: InterruptedException) {
        whenCancelled()
      }
    }

    override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech")

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      reach("choose")
      return GuideProposal(
        listOf(
          ProposedPick(
            candidates.first().key,
            "Self-hostable.",
            "Speech recognition model you can self-host",
          )
        )
      )
    }

    override fun verifyReasons(
      request: Need,
      claims: List<ReasonClaim>,
    ): Map<String, ReasonVerdict> {
      reach("verify")
      return claims.associate { it.key to ReasonVerdict(true) }
    }
  }

  private fun mvc(
    maxConcurrent: Int,
    deadline: Duration,
    model: GuideModel = stuck,
    answerGrace: Duration = GuideLimits.ANSWER_GRACE,
    heartbeat: Duration = Duration.ofMillis(50),
  ): MockMvc {
    val guide =
      AskGuide(
        SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock),
        model,
        clock,
      )
    val mapper = GuideDtoMapper(LlmSettings("fake", "", "fast", "reasoning"))
    val bulkheads =
      BulkheadRegistry.of(
        BulkheadConfig.custom()
          .maxConcurrentCalls(maxConcurrent)
          .maxWaitDuration(Duration.ZERO)
          .build()
      )
    bulkheads.bulkhead("guide")
    val policies =
      Resilience4jPolicies(
        CircuitBreakerRegistry.ofDefaults(),
        bulkheads,
        RetryRegistry.ofDefaults(),
      )
    val shared =
      GuideLimits(
          policies,
          deadline,
          answerGrace,
          Clock.systemUTC(),
          GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
        )
        .also { limits += it }
    return MockMvcBuilders.standaloneSetup(
        GuideStreamController(
          guide,
          mapper,
          shared,
          SimpleMeterRegistry(),
          heartbeat,
        )
      )
      .build()
  }

  private fun stream(mvc: MockMvc, question: String = "speech to text"): String {
    val result =
      mvc
        .perform(
          post("/api/v1/guide/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"request\":\"$question\"}")
        )
        .andExpect(request().asyncStarted())
        .andReturn()
    result.getAsyncResult(10_000)
    mvc.perform(asyncDispatch(result))
    return result.response.contentAsString
  }

  @Test
  fun `the work of a question knows how much of its deadline is left`() {
    val bulkheads =
      BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).build()).also {
        it.bulkhead("guide")
      }
    val shared =
      GuideLimits(
          Resilience4jPolicies(
            CircuitBreakerRegistry.ofDefaults(),
            bulkheads,
            RetryRegistry.ofDefaults(),
          ),
          Duration.ofSeconds(30),
        )
        .also { limits += it }
    val seen = CompletableFuture<Duration>()

    shared.tryStart("c", { _ -> seen.complete(QuestionDeadline.left(Clock.systemUTC()).get()) }, {})

    val left = seen.get(5, TimeUnit.SECONDS)
    assertTrue(left > Duration.ofSeconds(25) && left <= Duration.ofSeconds(30), "$left")
  }

  @Test
  fun `when every slot is taken the client is told at once that the guide is busy`() {
    val events = stream(mvc(maxConcurrent = 0, deadline = Duration.ofSeconds(90)))

    assertTrue(events.startsWith("event:error"), events)
    assertTrue(events.contains("\"status\":503"), events)
  }

  @Test
  fun `a question cancelled before its search is done ends with an error event at the deadline`() {
    val started = System.nanoTime()

    val events = stream(mvc(maxConcurrent = 1, deadline = Duration.ofMillis(300)))

    assertTrue(events.contains("event:error"), events)
    assertTrue(events.contains("\"status\":504"), events)
    assertFalse(events.contains("event:answer"), events)
    assertTrue(events.indexOf(":keep-alive") in 0..<events.indexOf("event:error"), events)
    assertTrue(
      Duration.ofNanos(System.nanoTime() - started) < GuideLimits.ANSWER_GRACE,
      "the error is not held back for the answer grace",
    )
  }

  private fun lastEvent(events: String) =
    Regex("event:(\\w+)").findAll(events).last().groupValues[1]

  @Test
  fun `a question cancelled during its reason check ends with its answer, the picks not checked`() {
    val events =
      stream(mvc(maxConcurrent = 1, deadline = Duration.ofMillis(500), model = SlowIn("verify")))

    assertEquals("answer", lastEvent(events), events)
    assertFalse(events.contains("event:error"), events)
    val answer = events.substringAfter("event:answer")
    assertTrue(answer.contains("\"status\":\"OK\""), answer)
    assertTrue(answer.contains("\"aiReasoned\":true"), answer)
    assertTrue(answer.contains("\"reasonCheck\":\"NOT_CHECKED\""), answer)
    assertTrue(
      events.contains("event:checked\ndata:{\"supported\":0,\"removed\":0,\"notChecked\":1"),
      events,
    )
  }

  @Test
  fun `a question cancelled while the model chooses ends with the top results and says it ran out of time`() {
    val events =
      stream(mvc(maxConcurrent = 1, deadline = Duration.ofMillis(500), model = SlowIn("choose")))

    assertEquals("answer", lastEvent(events), events)
    val answer = events.substringAfter("event:answer")
    assertTrue(answer.contains("\"status\":\"LLM_UNAVAILABLE\""), answer)
    assertTrue(answer.contains("\"aiReasoned\":false"), answer)
    assertTrue(answer.contains("\"code\":\"GUIDE_OUT_OF_TIME\""), answer)
  }

  @Test
  fun `a question that does not end after it is cancelled gets the error event once the answer grace is over`() {
    val deaf =
      SlowIn(
        "verify",
        whenCancelled = {
          while (true) {
            try {
              Thread.sleep(Duration.ofSeconds(30))
            } catch (e: InterruptedException) {
              continue
            }
          }
          @Suppress("UNREACHABLE_CODE") error("never ends")
        },
      )
    val started = System.nanoTime()

    val events =
      stream(
        mvc(
          maxConcurrent = 1,
          deadline = Duration.ofMillis(300),
          model = deaf,
          answerGrace = Duration.ofMillis(400),
        )
      )

    assertEquals("error", lastEvent(events), events)
    assertTrue(events.contains("\"status\":504"), events)
    assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(700))
  }

  @Test
  fun `a second question while every slot is taken is told at once that the guide is busy`() {
    val mvc =
      mvc(maxConcurrent = 1, deadline = Duration.ofSeconds(30), heartbeat = Duration.ofSeconds(30))
    mvc.perform(
      post("/api/v1/guide/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"request\":\"speech to text\"}")
    )
    assertTrue(planning.await(5, TimeUnit.SECONDS), "the first question holds the slot")

    val events = stream(mvc, "a vector database")

    assertTrue(events.startsWith("event:error"), events)
    assertTrue(events.contains("\"status\":503"), events)
  }

  @Test
  fun `a cancelled question gives its slot back`() {
    val mvc = mvc(maxConcurrent = 1, deadline = Duration.ofMillis(200))
    stream(mvc)
    Thread.sleep(200)

    val events = stream(mvc)

    assertTrue(events.contains("\"status\":504"), events)
  }

  private fun limitsOf(slots: Int, perClient: Int) =
    GuideLimits(
        Resilience4jPolicies(
          CircuitBreakerRegistry.ofDefaults(),
          BulkheadRegistry.of(
              BulkheadConfig.custom()
                .maxConcurrentCalls(slots)
                .maxWaitDuration(Duration.ZERO)
                .build()
            )
            .also { it.bulkhead("guide") },
          RetryRegistry.ofDefaults(),
        ),
        Duration.ofSeconds(30),
        Clock.systemUTC(),
        perClient,
      )
      .also { limits += it }

  @Test
  fun `a client gets at most its share of questions running at once`() {
    val shared = limitsOf(slots = 5, perClient = 1)
    val release = CountDownLatch(1)

    assertTrue(shared.tryStart("a", { _ -> release.await() }, {}))
    assertFalse(shared.tryStart("a", { _ -> }, {}), "the same client again")
    assertTrue(shared.tryStart("b", { _ -> }, {}), "another client")
    release.countDown()

    assertTrue(eventually { shared.tryStart("a", { _ -> }, {}) })
  }

  @Test
  fun `a question takes a slot only when it does work, and gives it back when it only waits`() {
    val shared = limitsOf(slots = 1, perClient = 5)
    val first = CompletableFuture<WorkGate>()
    val hold = CountDownLatch(1)
    val second = CompletableFuture<Boolean>()
    val third = CompletableFuture<Boolean>()

    shared.tryStart(
      "a",
      { gate ->
        gate.enter()
        first.complete(gate)
        hold.await()
      },
      {},
    )
    val gate = first.get(5, TimeUnit.SECONDS)
    shared.tryStart("b", { g -> second.complete(g.enter()) }, {})
    assertFalse(second.get(5, TimeUnit.SECONDS), "every slot is taken")
    gate.leave()
    shared.tryStart("c", { g -> third.complete(g.enter()) }, {})

    assertTrue(third.get(5, TimeUnit.SECONDS), "a waiting question holds no slot")
    hold.countDown()
  }

  private fun eventually(check: () -> Boolean): Boolean {
    repeat(50) {
      if (check()) return true
      Thread.sleep(20)
    }
    return false
  }

  @Test
  fun `the no-answer callback logs with the trace id of the question's request`() {
    val bulkheads =
      BulkheadRegistry.of(
        BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()
      )
    bulkheads.bulkhead("guide")
    val shared =
      GuideLimits(
          Resilience4jPolicies(
            CircuitBreakerRegistry.ofDefaults(),
            bulkheads,
            RetryRegistry.ofDefaults(),
          ),
          Duration.ofMillis(100),
          Duration.ofMillis(100),
          Clock.systemUTC(),
          GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
        )
        .also { limits += it }
    val request = Observation.start("http.server.requests", MdcTraceIds.registry())
    val seen = CompletableFuture<String?>()
    val release = CountDownLatch(1)

    request.openScope().use {
      shared.tryStart(
        "c",
        { _ -> awaitIgnoringCancellation(release) },
        { seen.complete(MDC.get(MdcTraceIds.KEY)) },
      )
    }
    request.stop()

    assertEquals(MdcTraceIds.traceId(request), seen.get(5, TimeUnit.SECONDS))
    release.countDown()
  }

  private fun awaitIgnoringCancellation(release: CountDownLatch) {
    while (true) {
      try {
        release.await()
        return
      } catch (e: InterruptedException) {
        continue
      }
    }
  }

  @Test
  fun `a question that ends when it is cancelled is not told again that it had no answer in time`() {
    val shared =
      GuideLimits(
          Resilience4jPolicies(
            CircuitBreakerRegistry.ofDefaults(),
            BulkheadRegistry.ofDefaults().also { it.bulkhead("guide") },
            RetryRegistry.ofDefaults(),
          ),
          Duration.ofMillis(100),
          Duration.ofMillis(100),
          Clock.systemUTC(),
          GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
        )
        .also { limits += it }
    val cancelled = CompletableFuture<Boolean>()
    val toldNoAnswer = CompletableFuture<Boolean>()

    shared.tryStart(
      "c",
      { _ ->
        try {
          Thread.sleep(Duration.ofSeconds(30))
        } catch (e: InterruptedException) {
          cancelled.complete(shared.deadlineIsOver())
        }
      },
      { toldNoAnswer.complete(true) },
    )

    assertTrue(cancelled.get(5, TimeUnit.SECONDS), "cancelled at the deadline")
    Thread.sleep(400)
    assertFalse(toldNoAnswer.isDone)
  }
}
