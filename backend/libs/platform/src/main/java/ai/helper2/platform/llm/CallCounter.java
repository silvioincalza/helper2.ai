package ai.helper2.platform.llm;

import java.time.LocalDate;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface CallCounter {
  CallCounter NONE = (name, day, limit) -> null;

  @Nullable Long increment(String name, LocalDate day, int limit);

  default @Nullable Long current(String name, LocalDate day) {
    return null;
  }

  enum GiveBack {
    GIVEN_BACK,
    ALREADY_GIVEN_BACK,
    CAP_REACHED,
    NOTHING_COUNTED,
  }

  default @Nullable GiveBack giveBack(String name, LocalDate day, String once, int dailyCap) {
    return null;
  }

  default @Nullable Boolean undo(String name, LocalDate day) {
    return null;
  }

  default void declare(String name, IntSupplier dailyCalls) {}

  default CallCounter unlisted() {
    CallCounter counts = this;
    return new CallCounter() {
      @Override
      public @Nullable Long increment(String name, LocalDate day, int limit) {
        return counts.increment(name, day, limit);
      }

      @Override
      public @Nullable Long current(String name, LocalDate day) {
        return counts.current(name, day);
      }

      @Override
      public @Nullable GiveBack giveBack(String name, LocalDate day, String once, int dailyCap) {
        return counts.giveBack(name, day, once, dailyCap);
      }

      @Override
      public @Nullable Boolean undo(String name, LocalDate day) {
        return counts.undo(name, day);
      }
    };
  }
}
