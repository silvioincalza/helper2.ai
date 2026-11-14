package ai.helper2.platform.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

public final class BudgetLedger implements CallCounter {
  public record Reading(String name, int dailyLimit, @Nullable Long usedToday) {}

  private final @Nullable RedisCallCounter store;
  private final Clock clock;
  private final Map<String, IntSupplier> limits = new ConcurrentSkipListMap<>();

  BudgetLedger(@Nullable RedisCallCounter store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public @Nullable Long increment(String name, LocalDate day, int limit) {
    return store == null ? null : store.increment(name, day, limit);
  }

  @Override
  public @Nullable Long current(String name, LocalDate day) {
    return store == null ? null : store.current(name, day);
  }

  @Override
  public @Nullable GiveBack giveBack(String name, LocalDate day, String once, int dailyCap) {
    return store == null ? null : store.giveBack(name, day, once, dailyCap);
  }

  @Override
  public @Nullable Boolean undo(String name, LocalDate day) {
    return store == null ? null : store.undo(name, day);
  }

  static final int MAX_LISTED = 64;

  @Override
  public void declare(String name, IntSupplier dailyCalls) {
    if (limits.size() < MAX_LISTED || limits.containsKey(name)) {
      limits.put(name, dailyCalls);
    }
  }

  public List<Reading> today() {
    LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return limits.entrySet().stream()
        .map(e -> {
          int limit = e.getValue().getAsInt();
          Long used = store == null ? null : store.current(e.getKey(), day);
          Long shown = used == null ? null : Math.min(used, (long) Math.max(limit, 0));
          return new Reading(e.getKey(), limit, shown);
        })
        .toList();
  }
}
