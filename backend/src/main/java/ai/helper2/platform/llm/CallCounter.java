package ai.helper2.platform.llm;

import java.time.LocalDate;

/**
 * Today's count of a named budget, kept outside the process so a restart or deploy does not hand
 * out a fresh day. Returns the count after adding one, or null when the store is unavailable.
 */
@FunctionalInterface
public interface CallCounter {

  /** No durable store (no Redis): every budget counts in memory. */
  CallCounter NONE = (name, day) -> null;

  Long increment(String name, LocalDate day);

  /** A budget declares its name and daily limit once, so the back office can list every budget. */
  default void declare(String name, int dailyCalls) {}

  /**
   * The same counts, with budgets that are never listed. For budgets per visitor (ClientQuota):
   * their names are derived from the visitor, so they must never reach the back office, and there
   * is one per visitor.
   */
  default CallCounter unlisted() {
    return this::increment;
  }
}
