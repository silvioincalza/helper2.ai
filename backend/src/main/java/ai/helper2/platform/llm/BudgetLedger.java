package ai.helper2.platform.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.jspecify.annotations.Nullable;

/**
 * Every daily model budget of the app and today's count of each, for the back office. It is the
 * {@link CallCounter} the budgets count on: each {@link DailyBudget} declares its name and limit
 * here, and its calls go to the shared store (Redis) when there is one. A count is read from that
 * store, so every replica shows the same number; without one, or while it cannot answer, the count
 * is unknown (null), never a guess from one replica's memory.
 */
public final class BudgetLedger implements CallCounter {

  /**
   * One budget today: its daily limit and the calls counted so far, null when the count is unknown.
   */
  public record Reading(String name, int dailyLimit, @Nullable Long usedToday) {}

  private final @Nullable RedisCallCounter store;
  private final Clock clock;
  private final Map<String, Integer> limits = new ConcurrentSkipListMap<>();

  BudgetLedger(@Nullable RedisCallCounter store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public @Nullable Long increment(String name, LocalDate day) {
    return store == null ? null : store.increment(name, day);
  }

  /**
   * Budgets the app has: one per use, a handful. A name past this many is not listed, so a budget
   * per visitor that forgot {@link CallCounter#unlisted()} can never grow the list without bound.
   */
  static final int MAX_LISTED = 64;

  @Override
  public void declare(String name, int dailyCalls) {
    if (limits.size() < MAX_LISTED || limits.containsKey(name)) {
      limits.put(name, dailyCalls);
    }
  }

  /**
   * Every declared budget, by name. A count above the limit (refused calls count too) shows as the
   * limit.
   */
  public List<Reading> today() {
    LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return limits.entrySet().stream()
        .map(e -> {
          Long used = store == null ? null : store.current(e.getKey(), day);
          Long shown = used == null ? null : Math.min(used, (long) Math.max(e.getValue(), 0));
          return new Reading(e.getKey(), e.getValue(), shown);
        })
        .toList();
  }
}
