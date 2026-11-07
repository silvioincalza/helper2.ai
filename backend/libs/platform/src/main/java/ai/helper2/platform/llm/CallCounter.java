package ai.helper2.platform.llm;

import java.time.LocalDate;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Today's count of a named budget, kept outside the process so a restart or deploy does not hand
 * out a fresh day.
 */
@FunctionalInterface
public interface CallCounter {

  /** No durable store (no Redis): every budget counts in memory. */
  CallCounter NONE = (name, day, limit) -> null;

  /**
   * Adds one to [name] on [day] unless the count already reached [limit], in one atomic step.
   * Returns the count with this call: above [limit] means refused, and nothing was added, so a
   * refused call never takes a count that a {@link #giveBack} should free. Null when the store is
   * unavailable.
   */
  @Nullable Long increment(String name, LocalDate day, int limit);

  /**
   * Today's count of [name] without adding one: 0 when nothing was counted yet, null when there is
   * no durable store or it cannot answer.
   */
  default @Nullable Long current(String name, LocalDate day) {
    return null;
  }

  /** What {@link #giveBack} did. */
  enum GiveBack {
    /** One count was taken off. */
    GIVEN_BACK,
    /** This {@code once} was given back before; nothing changed. */
    ALREADY_GIVEN_BACK,
    /** {@code dailyCap} counts of this name were given back that day already; nothing changed. */
    CAP_REACHED,
    /** The count is 0: nothing to give back. */
    NOTHING_COUNTED,
  }

  /**
   * Takes one count off [name] on [day] for work that turned out free, in one atomic step: never
   * below 0, at most once per [once] (a retry gives nothing back twice) and at most [dailyCap]
   * times per name and day. Null when there is no durable store or it cannot answer.
   */
  default @Nullable GiveBack giveBack(String name, LocalDate day, String once, int dailyCap) {
    return null;
  }

  /**
   * A budget declares its name and daily limit once, so the back office can list every budget. The
   * limit is a supplier: a runtime setting (ADR 0007) can change it.
   */
  default void declare(String name, IntSupplier dailyCalls) {}

  /**
   * The same counts, with budgets that are never listed. For budgets per visitor (ClientQuota):
   * their names are derived from the visitor, so they must never reach the back office, and there
   * is one per visitor.
   */
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
    };
  }
}
