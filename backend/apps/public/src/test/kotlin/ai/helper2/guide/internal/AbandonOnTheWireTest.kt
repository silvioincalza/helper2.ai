package ai.helper2.guide.internal

import ai.helper2.guide.CallTimeLimit
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.config.ModelStream
import ai.helper2.guide.internal.FakeProvider.Reply
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.platform.llm.DailyBudget
import ai.helper2.platform.llm.LlmPolicies
import ai.helper2.platform.llm.ModelCallMetrics
import ai.helper2.platform.llm.ModelErrors
import ai.helper2.platform.resilience.Resilience4jPolicies
import ai.helper2.search.CachedHits
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.Presence
import ai.helper2.shared.SingleFlight
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
      LlmPolicies.GUIDE,
      CircuitBreakerConfig.custom().ignoreException(ModelErrors.NotFromProvider()).build(),
    )

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

  private fun eventually(check: () -> Boolean): Boolean {
    val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (!check() && System.nanoTime() < giveUp) Thread.sleep(10)
    return check()
  }

  @Test
  fun `the streamed model call of a question its last asker left is closed at the provider, stays paid and is no provider failure`() {
    provider.then(Reply.stallsAfter("{\"searchQuery\":\"MCP"))
    val asker = Presence()
    val asking =
      CompletableFuture.supplyAsync<GuideAnswer>(
        { guide.ask("postgres MCP server", null, GuideProgress.NONE, presence = asker) },
        executor,
      )
    assertTrue(eventually { provider.bodies.size == 1 }, "the plan call reached the provider")

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
