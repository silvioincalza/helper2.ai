package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.MovableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final CircuitBreaker circuit = new CircuitBreaker("test", clock, Duration.ofSeconds(30));

    @Test
    void closedUntilAFailure() {
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    void aFailureOpensItForOpenForThenTheNextCallTriesAgain() {
        circuit.trip(new IllegalStateException("down"));
        assertThat(circuit.isOpen()).isTrue();

        clock.now = clock.now.plusSeconds(29);
        assertThat(circuit.isOpen()).isTrue();

        clock.now = clock.now.plusSeconds(1);
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    void aNewFailureStartsANewWindow() {
        circuit.trip(new IllegalStateException("down"));
        clock.now = clock.now.plusSeconds(20);
        circuit.trip(new IllegalStateException("still down"));

        clock.now = clock.now.plusSeconds(20);
        assertThat(circuit.isOpen()).isTrue();
        clock.now = clock.now.plusSeconds(10);
        assertThat(circuit.isOpen()).isFalse();
    }
}
