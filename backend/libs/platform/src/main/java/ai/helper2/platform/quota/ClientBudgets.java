package ai.helper2.platform.quota;

import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientBudgets {

  public static final int MAX_CLIENTS = 10_000;

  private final String prefix;
  private final int perDay;
  private final Clock clock;
  private final QuotaKeys keys;
  private final CallCounter counter;
  private final Map<String, DailyBudget> budgets;
  private LocalDate day;

  public ClientBudgets(
      String prefix, int perDay, Clock clock, QuotaKeys keys, CallCounter counter, int maxClients) {
    this.prefix = prefix;
    this.perDay = perDay;
    this.clock = clock;
    this.keys = keys;
    this.counter = counter.unlisted();
    this.day = today();
    this.budgets =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, DailyBudget> eldest) {
            return size() > maxClients;
          }
        };
  }

  public synchronized DailyBudget budgetOf(String client) {
    LocalDate now = today();
    if (!now.equals(day)) {
      day = now;
      budgets.clear();
    }
    String name = nameOf(client);
    return budgets.computeIfAbsent(name, n -> new DailyBudget(perDay, clock, n, counter));
  }

  public int limit() {
    return Math.max(0, perDay);
  }

  public int left(String client) {
    String name = nameOf(client);
    Long durable;
    try {
      durable = counter.current(name, today());
    } catch (RuntimeException e) {
      durable = null;
    }
    long used = durable != null ? durable : usedInMemory(name);
    return (int) Math.max(0L, limit() - used);
  }

  public synchronized int inMemory() {
    return budgets.size();
  }

  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private synchronized long usedInMemory(String name) {
    if (!day.equals(today())) {
      return 0;
    }
    DailyBudget budget = budgets.get(name);
    return budget == null ? 0 : budget.usedToday();
  }

  private String nameOf(String client) {
    return prefix + keys.client(client);
  }
}
