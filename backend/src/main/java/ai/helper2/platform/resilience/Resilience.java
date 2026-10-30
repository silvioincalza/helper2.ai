package ai.helper2.platform.resilience;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs work under a named resilience policy (circuit breaker, bulkhead, retry; ADR 0033). The policy names are
 * configured, not coded: {@code source-<id>}, {@code collector-<id>}, {@code redis}, {@code llm-chat},
 * {@code llm-embed}, {@code guide}. A call the policy does not let through throws {@link CallRefused} without running
 * the work; any other exception is the work's own.
 */
public interface Resilience {

    <T> T call(String policy, Supplier<T> work);

    default void run(String policy, Runnable work) {
        call(policy, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Takes one place in the policy's bulkhead for work that outlives one call: a guide question runs on its own thread
     * and gives its place back at its deadline, even before the thread ends. Empty, at once, when every place is taken.
     * Fails with {@link IllegalStateException} when the policy has no bulkhead, as for any policy without one.
     */
    default Optional<Slot> trySlot(String policy) {
        throw new IllegalStateException("no bulkhead named " + policy + " (resilience4j.bulkhead in application.yaml)");
    }

    /** Fails with {@link IllegalStateException} when no policy has this name, so a missing policy stops startup. */
    void require(String policy);

    /** A place taken with {@link #trySlot}; releasing it again does nothing. */
    @FunctionalInterface
    interface Slot {
        void release();
    }

    /** Runs the work as is, always has a place and knows every name: for use cases tested without policies. */
    Resilience NONE = new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
            return work.get();
        }

        @Override
        public Optional<Slot> trySlot(String policy) {
            return Optional.of(() -> {
            });
        }

        @Override
        public void require(String policy) {
        }
    };
}
