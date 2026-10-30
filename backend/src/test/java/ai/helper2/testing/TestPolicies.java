package ai.helper2.testing;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import java.time.Duration;

/** Resilience policies for adapter tests, on a clock the test moves. */
public final class TestPolicies {

    private TestPolicies() {
    }

    /** A {@code redis} circuit that opens on the first failure for 30 s, then lets one trial call through. */
    public static Resilience redis(Clock clock) {
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        circuits.circuitBreaker("redis", CircuitBreakerConfig.custom()
            .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(1)
            .clock(clock).build());
        return new Resilience4jPolicies(circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    }
}
