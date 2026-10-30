package ai.helper2.platform.resilience;

import java.util.function.Supplier;

/**
 * Runs work under a named resilience policy (circuit breaker, bulkhead, retry; ADR 0033). The policy names are
 * configured, not coded: {@code source-<id>}, {@code collector-<id>}, {@code redis}. A call the policy does not let
 * through throws {@link CallRefused} without running the work; any other exception is the work's own.
 */
public interface Resilience {

    <T> T call(String policy, Supplier<T> work);

    default void run(String policy, Runnable work) {
        call(policy, () -> {
            work.run();
            return null;
        });
    }

    /** Fails with {@link IllegalStateException} when no policy has this name, so a missing policy stops startup. */
    void require(String policy);

    /** Runs the work as is and knows every name: for use cases tested without policies. */
    Resilience NONE = new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
            return work.get();
        }

        @Override
        public void require(String policy) {
        }
    };
}
