package ai.helper2.platform.resilience;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public interface Resilience {

  <T> T call(String policy, Supplier<T> work);

  default void run(String policy, Runnable work) {
    call(
        policy,
        () -> {
          work.run();
          return null;
        });
  }

  default Optional<Slot> trySlot(String policy) {
    throw new IllegalStateException(
        "no bulkhead named " + policy + " (resilience4j.bulkhead in the yaml settings)");
  }

  void require(String policy);

  default Optional<Circuit> circuit(String policy) {
    return Optional.empty();
  }

  record Circuit(State state, @Nullable Instant openedAt) {

    public enum State {
      CLOSED,
      OPEN,
      HALF_OPEN,
    }
  }

  @FunctionalInterface
  interface Slot {
    void release();
  }

  Resilience NONE =
      new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
          return work.get();
        }

        @Override
        public Optional<Slot> trySlot(String policy) {
          return Optional.of(() -> {});
        }

        @Override
        public void require(String policy) {}
      };
}
