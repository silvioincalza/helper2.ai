package ai.searchavo.guide.internal

import ai.searchavo.guide.CallTimeLimit
import ai.searchavo.guide.GuideAnswer
import ai.searchavo.guide.GuideProgress
import ai.searchavo.guide.config.ModelStream
import ai.searchavo.guide.internal.FakeProvider.Reply
import ai.searchavo.guide.usecase.AskGuide
import ai.searchavo.platform.llm.DailyBudget
import ai.searchavo.platform.llm.LlmPolicies
import ai.searchavo.platform.llm.ModelCallMetrics
import ai.searchavo.platform.llm.ModelErrors
import ai.searchavo.platform.resilience.Resilience4jPolicies
import ai.searchavo.search.CachedHits
import ai.searchavo.search.ResultObserver
import ai.searchavo.search.SearchCache
import ai.searchavo.search.usecase.SearchResources
import ai.searchavo.shared.Presence
import ai.searchavo.shared.SingleFlight
import ai.searchavo.testing.Eventually.eventually
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbandonOnTheWireTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.systemUTC()
  private val provider = FakeProvider()
  private val meters = SimpleMeterRegistry()
  private val budget = DailyBudget(10, clock, "guide")
  private val circuits = CircuitBreakerRegistry.ofDefaults()
  private val circuit: CircuitBreaker =
    circuits.circuitBreaker(
      LlmPolicies.GUIDE_FAST,
      CircuitBreakerConfig.custom().ignoreException(ModelErrors.NotFromProvider()).build(),
    )

  init {
    circuits.circuitBreaker(LlmPolicies.GUIDE_REASONING)
  }

  @AfterTest
  fun stop() {
    provider.close()
    executor.shutdownNow()
  }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val guide =
    AskGuide(
      SearchResources(emptyList(), noCache, ResultObserver { emptyMap() }, executor, clock),
      SpringAiGuideModel(
        provider.chatModel(),
        "test",
        "fast",
        "reasoning",
        true,
        8192,
        SpringAiGuideModel.Thinking(512, 0, 0, 0),
        budget,
        ModelCallMetrics(ObservationRegistry.NOOP, meters),
        Resilience4jPolicies(circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults()),
        CallTimeLimit(Duration.ofSeconds(60), Duration.ofMillis(300), Duration.ofSeconds(1)),
        clock,
        ModelStream.ON,
      ),
      clock,
      meters = meters,
      stopsWhenNobodyWaits = true,
    )

  @Test
  fun `the streamed model call of a question its last asker left is closed at the provider, stays paid and is no provider failure`() {
    provider.then(Reply.stallsAfter("{\"searchQuery\":\"MCP"))
    val asker = Presence()
    val asking =
      CompletableFuture.supplyAsync<GuideAnswer>(
        { guide.ask("postgres MCP server", null, GuideProgress.NONE, presence = asker) },
        executor,
      )
    eventually { assertTrue(provider.bodies.size == 1, "the plan call reached the provider") }

    asker.leave()

    assertIs<SingleFlight.Abandoned>(
      assertFailsWith<Exception> { asking.get(20, TimeUnit.SECONDS) }.cause
    )
    assertEquals(1, provider.closedByTheClientAfter(1).size)
    assertEquals(1, provider.bodies.size, "the stopped call is not asked again")
    assertEquals(1, budget.usedToday(), "the call was made: its unit stays spent")
    assertEquals(0, circuit.metrics.numberOfFailedCalls)
    assertEquals(CircuitBreaker.State.CLOSED, circuit.state)
  }
}
