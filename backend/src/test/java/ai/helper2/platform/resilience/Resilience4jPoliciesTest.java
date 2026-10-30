package ai.helper2.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.testing.MovableClock;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Named policies built from the Resilience4j registries: what callers see when a policy refuses or retries a call. */
class Resilience4jPoliciesTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final Resilience resilience = new Resilience4jPolicies(circuits, bulkheads, retries);
    private final AtomicInteger calls = new AtomicInteger();

    private String failing() {
        calls.incrementAndGet();
        throw new IllegalStateException("down");
    }

    private void circuit(String name) {
        circuits.circuitBreaker(name, CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(1)
            .clock(clock).build());
    }

    @Test
    void anUnknownPolicyIsRefusedAtStartupAndOnUse() {
        circuit("redis");

        resilience.require("redis");
        assertThatThrownBy(() -> resilience.require("source-nope"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("source-nope");
        assertThatThrownBy(() -> resilience.call("source-nope", () -> "x"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("source-nope");
    }

    @Test
    void failuresPassThroughUntilTheCircuitOpensThenCallsAreRefusedWithoutRunning() {
        circuit("redis");

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resilience.call("redis", this::failing)).hasMessage("down");
        }
        assertThatThrownBy(() -> resilience.call("redis", this::failing))
            .isInstanceOfSatisfying(CallRefused.class, e -> {
                assertThat(e.reason()).isEqualTo(CallRefused.Reason.CIRCUIT_OPEN);
                assertThat(e.policy()).isEqualTo("redis");
            });
        assertThat(calls).hasValue(2);
    }

    @Test
    void afterTheOpenWindowOneTrialCallClosesTheCircuitAgain() {
        circuit("redis");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resilience.call("redis", this::failing));
        }

        clock.now = clock.now.plusSeconds(31);
        assertThat(resilience.call("redis", () -> "back")).isEqualTo("back");
        assertThat(resilience.call("redis", () -> "again")).isEqualTo("again");
    }

    @Test
    void aFullBulkheadRefusesTheCallAsBusy() throws Exception {
        bulkheads.bulkhead("source-x", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            threads.submit(() -> resilience.run("source-x", () -> {
                inside.countDown();
                await(release);
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> resilience.call("source-x", () -> "second"))
                .isInstanceOfSatisfying(CallRefused.class, e -> assertThat(e.reason()).isEqualTo(CallRefused.Reason.BUSY));
            release.countDown();
        }
        assertThat(resilience.call("source-x", () -> "free again")).isEqualTo("free again");
    }

    @Test
    void aSlotHoldsAPlaceInTheBulkheadUntilItIsReleasedOnce() {
        bulkheads.bulkhead("guide", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());

        Resilience.Slot first = resilience.trySlot("guide").orElseThrow();
        assertThat(resilience.trySlot("guide")).as("every place taken").isEmpty();
        first.release();
        first.release();

        Resilience.Slot second = resilience.trySlot("guide").orElseThrow();
        assertThat(resilience.trySlot("guide")).as("a second release gives back no extra place").isEmpty();
        second.release();
    }

    @Test
    void aSlotNeedsABulkhead() {
        circuit("redis");

        assertThatThrownBy(() -> resilience.trySlot("redis")).isInstanceOf(IllegalStateException.class).hasMessageContaining("redis");
    }

    @Test
    void retriesOnlyWhatThePolicyCallsTransient() {
        retries.retry("collector-x", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1))
            .retryOnException(e -> e instanceof IllegalStateException).build());

        assertThatThrownBy(() -> resilience.call("collector-x", this::failing)).hasMessage("down");
        assertThat(calls).as("three attempts").hasValue(3);

        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> resilience.call("collector-x", () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("permanent");
        })).hasMessage("permanent");
        assertThat(attempts).as("not retried").hasValue(1);
    }

    @Test
    void anOpenCircuitIsNotRetried() {
        circuit("collector-y");
        retries.retry("collector-y", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1))
            .retryOnException(e -> e instanceof IllegalStateException).build());

        assertThatThrownBy(() -> resilience.call("collector-y", this::failing)).isInstanceOf(CallRefused.class);
        assertThat(calls).as("two attempts opened the circuit; the third was refused and not retried").hasValue(2);
        assertThatThrownBy(() -> resilience.call("collector-y", this::failing)).isInstanceOf(CallRefused.class);
        assertThat(calls).hasValue(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
