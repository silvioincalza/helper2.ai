package ai.helper2.testing;

import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import java.time.Duration;

public final class TestPolicies {

  private TestPolicies() {}

  public static Resilience redis(Clock clock) {
    return openingOnFirstFailure("redis", clock);
  }

  public static Resilience dbCache(Clock clock) {
    return openingOnFirstFailure("db-cache", clock);
  }

  private static Resilience openingOnFirstFailure(String policy, Clock clock) {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    circuits.circuitBreaker(
        policy,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(1)
            .minimumNumberOfCalls(1)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(1)
            .clock(clock)
            .build());
    return new Resilience4jPolicies(
        circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
  }

  public static Resilience llmChatOpen() {
    return circuitOpen(LlmPolicies.CHAT);
  }

  public static Resilience circuitOpen(String policy) {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    circuits.circuitBreaker(policy).transitionToForcedOpenState();
    return new Resilience4jPolicies(
        circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
  }
}
