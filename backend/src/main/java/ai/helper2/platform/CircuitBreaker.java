package ai.helper2.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-fast guard for an optional dependency such as Redis: after any failure the circuit opens for
 * {@code openFor}, and callers skip the dependency while it is open, so an outage never adds latency
 * to every request. No half-open state: the first call after {@code openFor} simply tries again.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final Clock clock;
    private final Duration openFor;
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.MIN);

    /** {@code name} only labels the log line ("search cache", "guide answer store"). */
    public CircuitBreaker(String name, Clock clock, Duration openFor) {
        this.name = name;
        this.clock = clock;
        this.openFor = openFor;
    }

    public boolean isOpen() {
        return clock.instant().isBefore(openUntil.get());
    }

    /** Opens the circuit after {@code failure}. */
    public void trip(RuntimeException failure) {
        openUntil.set(clock.instant().plus(openFor));
        log.warn("{} unavailable, bypassing for {}s: {}", name, openFor.toSeconds(), failure.getClass().getSimpleName());
    }
}
