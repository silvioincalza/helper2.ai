package ai.searchavo.guide.web

import ai.searchavo.api.generated.model.GuideRequestDto
import ai.searchavo.guide.Candidate
import ai.searchavo.guide.GuideModel
import ai.searchavo.guide.GuideProposal
import ai.searchavo.guide.GuideSearchPlan
import ai.searchavo.guide.GuideUnavailable
import ai.searchavo.guide.Need
import ai.searchavo.guide.usecase.AskGuide
import ai.searchavo.platform.llm.LlmSettings
import ai.searchavo.platform.resilience.Resilience4jPolicies
import ai.searchavo.search.CachedHits
import ai.searchavo.search.NOW
import ai.searchavo.search.ResultObserver
import ai.searchavo.search.SearchCache
import ai.searchavo.search.SearchSource
import ai.searchavo.search.SourceHit
import ai.searchavo.search.SourceQuery
import ai.searchavo.search.descriptor
import ai.searchavo.search.hit
import ai.searchavo.search.usecase.SearchResources
import ai.searchavo.shared.AudiencePath
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import reactor.core.Disposable

class GuideStreamLeaveTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val meters = SimpleMeterRegistry()
  private val leaveGrace = Duration.ofMillis(300)

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(hit("hf", "https://huggingface.co/acme/whisper-small", title = "whisper-small"))
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private class SlowChooser : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    val choosing = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech to text")

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      choosing.countDown()
      try {
        CountDownLatch(1).await()
      } catch (_: InterruptedException) {
        stopped.countDown()
        Thread.currentThread().interrupt()
        throw GuideUnavailable("the model call was interrupted", GuideUnavailable.CANCELLED)
      }
      return GuideProposal(emptyList())
    }
  }

  private val model = SlowChooser()
  private val bulkheads = BulkheadRegistry.ofDefaults().also { it.bulkhead("guide") }
  private val limits =
    GuideLimits(
      Resilience4jPolicies(
        CircuitBreakerRegistry.ofDefaults(),
        bulkheads,
        RetryRegistry.ofDefaults(),
      ),
      Duration.ofSeconds(90),
      GuideLimits.ANSWER_GRACE,
      leaveGrace,
      clock,
      GuideLimits.DEFAULT_QUESTIONS_PER_CLIENT,
    )
  private val controller =
    GuideStreamController(
      AskGuide(
        SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock),
        model,
        clock,
        meters = meters,
        stopsWhenNobodyWaits = true,
      ),
      GuideDtoMapper(LlmSettings("fake", "", "fast", "reasoning")),
      limits,
      meters,
      Duration.ofSeconds(15),
    )

  @AfterTest
  fun shutdown() {
    limits.destroy()
    executor.shutdownNow()
  }

  private fun ask(): Disposable =
    controller
      .askGuideStream(GuideRequestDto("speech to text"), MockHttpServletRequest())
      .body!!
      .subscribe()

  private fun abandoned() =
    meters.counter("searchavo.guide.questions", "outcome", "abandoned").count()

  @Test
  fun `a question whose stream is closed stops its model call after the leave grace`() {
    val stream = ask()
    assertTrue(model.choosing.await(5, TimeUnit.SECONDS))

    stream.dispose()

    assertTrue(model.stopped.await(5, TimeUnit.SECONDS))
    while (abandoned() == 0.0) Thread.sleep(10)
    assertEquals(1.0, abandoned())
  }

  @Test
  fun `a question asked again within the leave grace goes on for the new stream`() {
    val first = ask()
    assertTrue(model.choosing.await(5, TimeUnit.SECONDS))

    first.dispose()
    ask()

    assertFalse(model.stopped.await(leaveGrace.toMillis() * 3, TimeUnit.MILLISECONDS))
    assertEquals(0.0, abandoned())
  }
}
